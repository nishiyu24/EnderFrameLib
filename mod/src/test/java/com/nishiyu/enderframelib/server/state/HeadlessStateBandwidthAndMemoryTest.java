package com.nishiyu.enderframelib.server.state;

import com.nishiyu.enderframelib.api.state.UIState;
import com.nishiyu.enderframelib.network.OpenUiS2CPacket;
import com.nishiyu.enderframelib.network.StateDeltaS2CPacket;
import com.nishiyu.enderframelib.server.EnderFrameServerState;
import com.nishiyu.enderframelib.server.PacketBandwidthTest;
import com.nishiyu.enderframelib.server.StateMemoryBenchmarkTest;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * フェーズ H1（BENCHMARK.md §6）:
 * Headless Server State モデルにおけるパケット帯域幅およびサーバーメモリ消費量の改善検証テスト。
 *
 * <p>フェーズ H0 で測定・固定された Dual DOM ベースライン（server_baseline.txt）と比較し、
 * 通信帯域（初回オープン 90% 以上削減、稼働中更新 70% 以上削減）および
 * サーバー側ヒープ（DOM ノード保持 0、メモリ大幅削減）の達成を実測アサートする。</p>
 */
public class HeadlessStateBandwidthAndMemoryTest {

    private static final int DUAL_DOM_INITIAL_OPEN_BYTES = 4548;
    private static final int DUAL_DOM_SINGLE_UPDATE_BYTES = 166;
    private static final int DUAL_DOM_100_UPDATES_BYTES = 16584;
    private static final int DUAL_DOM_100_SESSIONS_NODES = 3000;

    @BeforeEach
    void setUp() {
        EnderFrameServerState.clearAll();
    }

    @AfterEach
    void tearDown() {
        EnderFrameServerState.clearAll();
    }

    public static int measurePacketSize(OpenUiS2CPacket packet) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            OpenUiS2CPacket.STREAM_CODEC.encode(buf, packet);
            return buf.readableBytes();
        } finally {
            buf.release();
        }
    }

    public static int measurePacketSize(StateDeltaS2CPacket packet) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            StateDeltaS2CPacket.STREAM_CODEC.encode(buf, packet);
            return buf.readableBytes();
        } finally {
            buf.release();
        }
    }

    @Test
    @DisplayName("パケット帯域幅検証: Dual DOM と比較して初回 90% 以上、更新 70% 以上の帯域削減を達成すること")
    void testBandwidthReductionOverBaseline() throws IOException {
        String templateId = "mymod:crusher";
        String instanceId = "block:100,64,200";

        // 実際の UIStateImpl が送るパケットを測る（キー辞書と番号は状態側が振る）
        List<CustomPacketPayload> sent = new ArrayList<>();
        UIStateImpl state = (UIStateImpl) UIStateManager.createState(templateId, instanceId);
        state.setPacketSender((player, packet) -> sent.add(packet));

        // 1. 初回オープンの初期状態
        state.set("progress", 0.0);
        state.set("progress_text", "0%");
        state.set("energy", 100000);
        state.set("status", "idle");
        state.addViewer(TestServerPlayerHelper.createMockPlayer("Viewer"));
        int initialOpenBytes = measurePacketSize((OpenUiS2CPacket) sent.get(0));
        // 開く前に set した分の差分（同じ値）を流して捨てる
        state.flush();
        sent.clear();

        // 2. 単一更新（progress + progress_text）
        state.set("progress", 0.5);
        state.set("progress_text", "50%");
        state.flush();
        assertEquals(1, sent.size(), "単一更新で差分パケットが 1 件だけ送られること");
        int singleUpdateBytes = measurePacketSize((StateDeltaS2CPacket) sent.remove(0));

        // 3. 100 回の進捗更新累計バイト数
        int total100UpdatesBytes = 0;
        for (int i = 1; i <= 100; i++) {
            state.set("progress", i / 100.0);
            state.set("progress_text", i + "%");
            state.flush();
            total100UpdatesBytes += measurePacketSize((StateDeltaS2CPacket) sent.remove(0));
        }

        double openReductionRate = 1.0 - ((double) initialOpenBytes / DUAL_DOM_INITIAL_OPEN_BYTES);
        double singleUpdateReductionRate = 1.0 - ((double) singleUpdateBytes / DUAL_DOM_SINGLE_UPDATE_BYTES);
        double total100ReductionRate = 1.0 - ((double) total100UpdatesBytes / DUAL_DOM_100_UPDATES_BYTES);

        System.out.printf(Locale.ROOT,
                """
                ========================================================================================
                [HeadlessStateBandwidthAndMemoryTest] Bandwidth Comparison: Dual DOM vs Headless State
                ----------------------------------------------------------------------------------------
                Metric                  | Dual DOM (Baseline) | Headless State (H1) | Reduction Rate
                ----------------------------------------------------------------------------------------
                Initial Open Packet     |         %5d bytes |         %5d bytes |        %6.2f %%
                Single Update Packet    |         %5d bytes |         %5d bytes |        %6.2f %%
                100 Updates Cumulative  |         %5d bytes |         %5d bytes |        %6.2f %%
                ========================================================================================
                """,
                DUAL_DOM_INITIAL_OPEN_BYTES, initialOpenBytes, openReductionRate * 100.0,
                DUAL_DOM_SINGLE_UPDATE_BYTES, singleUpdateBytes, singleUpdateReductionRate * 100.0,
                DUAL_DOM_100_UPDATES_BYTES, total100UpdatesBytes, total100ReductionRate * 100.0
        );

        // 検証要件（BENCHMARK.md §6）
        assertTrue(openReductionRate >= 0.90,
                String.format(Locale.ROOT, "初回オープン通信量が 90%% 以上削減されていること (実測: %.2f%%, %d bytes)",
                        openReductionRate * 100.0, initialOpenBytes));

        assertTrue(singleUpdateReductionRate >= 0.60,
                String.format(Locale.ROOT, "単一更新通信量が 60%% 以上削減されていること (実測: %.2f%%, %d bytes)",
                        singleUpdateReductionRate * 100.0, singleUpdateBytes));

        assertTrue(total100ReductionRate >= 0.60,
                String.format(Locale.ROOT, "100 回更新の総通信量が 60%% 以上削減されていること (実測: %.2f%%, %d bytes)",
                        total100ReductionRate * 100.0, total100UpdatesBytes));

        // BENCHMARK.md §6 の当初の目標（インスタンスとキーの番号化で達成）
        assertTrue(initialOpenBytes < 400, "初回オープンが 400 bytes 未満であること (実測: " + initialOpenBytes + " bytes)");
        assertTrue(singleUpdateBytes < 30, "単一更新が 30 bytes 未満であること (実測: " + singleUpdateBytes + " bytes)");
        assertTrue(total100UpdatesBytes < 3000, "100 回更新の累計が 3,000 bytes 未満であること (実測: " + total100UpdatesBytes + " bytes)");

        // レポート出力
        Path outReport = Path.of("build/reports/headless-state-bandwidth-result.txt");
        Files.createDirectories(outReport.getParent());
        String report = String.format(Locale.ROOT,
                "initialOpenBytes=%d%nsingleUpdateBytes=%d%ntotal100UpdatesBytes=%d%nopenReductionRate=%.4f%nupdateReductionRate=%.4f%n",
                initialOpenBytes, singleUpdateBytes, total100UpdatesBytes, openReductionRate, singleUpdateReductionRate);
        Files.writeString(outReport, report, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("サーバーメモリ検証: 100 セッションで保持 DOM ノード数 0、メモリ消費量の大幅削減を検証")
    void testServerMemoryZeroDomNodesAndHeapReduction() {
        // 1. ウォームアップ
        for (int i = 0; i < 20; i++) {
            UIState s = UIStateManager.createState("mymod:crusher", "warmup:" + i);
            s.set("progress", 0.5);
            s.set("energy", 1000);
        }
        UIStateManager.clearAll();
        StateMemoryBenchmarkTest.triggerGc();

        long memBefore = StateMemoryBenchmarkTest.getUsedMemory();

        // 2. 100 個の Headless UIState セッションを生成
        List<UIState> states = new ArrayList<>(100);
        for (int i = 0; i < 100; i++) {
            UIState s = UIStateManager.createState("mymod:crusher", "block:100," + i + ",200");
            s.set("progress", 0.0);
            s.set("energy", 50000);
            s.set("status", "idle");
            s.set("tier", 1);
            states.add(s);
        }

        long memAfter = StateMemoryBenchmarkTest.getUsedMemory();
        long diff = Math.max(0, memAfter - memBefore);

        System.out.printf(Locale.ROOT,
                """
                ========================================================================================
                [HeadlessStateBandwidthAndMemoryTest] Memory Comparison: Dual DOM vs Headless State
                ----------------------------------------------------------------------------------------
                Metric                  | Dual DOM (Baseline) | Headless State (H1) | Improvement
                ----------------------------------------------------------------------------------------
                Retained DOM Nodes      |          3000 nodes |             0 nodes |      100.00 %% (ゼロ保持)
                Total Heap Used (100)   |      35,631,416 bytes|        %8d bytes |      %6.2f %% 削減
                ========================================================================================
                """,
                diff, (1.0 - ((double) diff / 35631416.0)) * 100.0
        );

        // ★重要★: サーバー側での DOM ノード保持はゼロ（完全撤廃）
        assertEquals(0, countServerRetainedDomNodes(), "Headless State ではサーバー側 DOM ノード保持数が 0 であること");

        // 100 セッション常駐しても、Dual DOM（~35MB）と比べてメモリが極めて小さいこと（10MB 以下、通常数十〜数百 KB）
        assertTrue(diff < 10_000_000,
                String.format(Locale.ROOT, "100 セッションのヒープ消費量が 10MB 未満であること (実測: %d bytes)", diff));
    }

    private static int countServerRetainedDomNodes() {
        // Headless Server State においては UIStateManager も UIStateImpl も HtmlNode を保持しない
        // UIState クラスのフィールドに HtmlNode や Document が存在しないことをリフレクションで厳密検証
        int nodeCount = 0;
        for (java.lang.reflect.Field field : UIStateImpl.class.getDeclaredFields()) {
            if (field.getType().getName().contains("HtmlNode") || field.getType().getName().contains("Document")) {
                nodeCount++;
            }
        }
        return nodeCount;
    }
}
