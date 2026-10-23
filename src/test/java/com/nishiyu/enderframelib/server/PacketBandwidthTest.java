package com.nishiyu.enderframelib.server;

import com.nishiyu.enderframelib.network.DomMutationRecord;
import com.nishiyu.enderframelib.network.WebSyncS2CPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * フェーズ H0: パケット帯域幅ベンチマークテスト (Dual DOM 現行方式の基準値測定)。
 *
 * <p>機械 UI を想定した画面における以下を計測・検証する:
 * <ul>
 *   <li>初回オープン時のパケットサイズ（全 DOM ツリー送出: dom_sync）</li>
 *   <li>稼働中 100 回の進捗更新時の総パケットバイト数（DOM 変異レコード: dom_mutations）</li>
 * </ul>
 *
 * <p>H1 以降の Headless State モデル（OpenUiS2CPacket / StateDeltaS2CPacket）との
 * 比較基準となる。
 */
public class PacketBandwidthTest {

    private static final Path BASELINE_PATH = Path.of("src/test/resources/benchmark/server_baseline.txt");
    private static final Path MACHINE_UI_PATH = Path.of("src/test/resources/benchmark/machine_ui.html");

    public record BandwidthBenchmarkResult(
            int initialOpenBytes,
            int singleMutationBytes,
            int total100UpdatesBytes
    ) {}

    public static String loadMachineHtml() {
        try {
            if (Files.exists(MACHINE_UI_PATH)) {
                return Files.readString(MACHINE_UI_PATH, StandardCharsets.UTF_8);
            }
            try (InputStream is = PacketBandwidthTest.class.getResourceAsStream("/benchmark/machine_ui.html")) {
                if (is != null) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read machine_ui.html", e);
        }
        throw new IllegalStateException("machine_ui.html not found at " + MACHINE_UI_PATH);
    }

    public static int measurePacketSize(WebSyncS2CPacket packet) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.write(buf);
            return buf.readableBytes();
        } finally {
            buf.release();
        }
    }

    public static BandwidthBenchmarkResult measureDualDomBandwidth() {
        String sessionId = "block:100,64,200";
        WebSession session = new WebSession(sessionId, s -> {});
        session.loadHtml(loadMachineHtml());

        // 1. 初回オープンの dom_sync パケット
        CompoundTag domTag = WebSessionManager.packDom(session);
        WebSyncS2CPacket openPacket = new WebSyncS2CPacket(sessionId, "dom_sync", domTag);
        int initialOpenBytes = measurePacketSize(openPacket);

        // 2. 稼働中の 1 回の更新（progress スタイル + progress-text のバッチ更新）
        DomMutationRecord mutStyle = DomMutationRecord.style("progress", "width", "50%");
        DomMutationRecord mutText = DomMutationRecord.text("progress-text", "50%");
        ListTag singleList = new ListTag();
        singleList.add(mutStyle.toTag());
        singleList.add(mutText.toTag());
        CompoundTag singlePayload = new CompoundTag();
        singlePayload.put("Mutations", singleList);
        WebSyncS2CPacket singleUpdatePacket = new WebSyncS2CPacket(sessionId, "dom_mutations", singlePayload);
        int singleMutationBytes = measurePacketSize(singleUpdatePacket);

        // 3. 100 回の進捗更新（1% から 100% までの累積バイト数）
        int total100UpdatesBytes = 0;
        for (int i = 1; i <= 100; i++) {
            DomMutationRecord m1 = DomMutationRecord.style("progress", "width", i + "%");
            DomMutationRecord m2 = DomMutationRecord.text("progress-text", i + "%");
            ListTag list = new ListTag();
            list.add(m1.toTag());
            list.add(m2.toTag());
            CompoundTag payload = new CompoundTag();
            payload.put("Mutations", list);
            WebSyncS2CPacket pkt = new WebSyncS2CPacket(sessionId, "dom_mutations", payload);
            total100UpdatesBytes += measurePacketSize(pkt);
        }

        return new BandwidthBenchmarkResult(initialOpenBytes, singleMutationBytes, total100UpdatesBytes);
    }

    @Test
    @DisplayName("Dual DOM 方式のパケット帯域幅ベースライン計測と検証")
    void testMeasureDualDomBandwidth() throws IOException {
        BandwidthBenchmarkResult result = measureDualDomBandwidth();

        System.out.printf(Locale.ROOT,
                """
                =======================================================
                [PacketBandwidthTest] Dual DOM Baseline
                  Initial Open (dom_sync):        %d bytes
                  Single Update (2 mutations):    %d bytes
                  100 Updates Cumulative:         %d bytes (%.2f KB)
                =======================================================
                """,
                result.initialOpenBytes(),
                result.singleMutationBytes(),
                result.total100UpdatesBytes(),
                result.total100UpdatesBytes() / 1024.0
        );

        // ベースラインのアサート（Dual DOM は DOM ツリー全体を送るため 4KB 以上）
        assertEquals(4548, result.initialOpenBytes(), "初回オープンのパケットサイズがベースラインと一致すること");
        assertEquals(166, result.singleMutationBytes(), "単一更新のパケットサイズがベースラインと一致すること");
        assertEquals(16584, result.total100UpdatesBytes(), "100 回更新の総パケットバイト数がベースラインと一致すること");

        // ベースライン結果のレポート出力
        Path outReport = Path.of("build/reports/packet-bandwidth-baseline.txt");
        Files.createDirectories(outReport.getParent());
        String report = String.format(Locale.ROOT,
                "initialOpenBytes=%d%nsingleMutationBytes=%d%ntotal100UpdatesBytes=%d%n",
                result.initialOpenBytes(), result.singleMutationBytes(), result.total100UpdatesBytes());
        Files.writeString(outReport, report, StandardCharsets.UTF_8);
    }
}
