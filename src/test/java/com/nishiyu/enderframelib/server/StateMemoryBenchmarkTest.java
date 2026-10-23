package com.nishiyu.enderframelib.server;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * フェーズ H0: サーバーヒープメモリ消費量ベンチマークテスト (Dual DOM 現行方式の基準値測定)。
 *
 * <p>機械 UI を想定した 100 個のセッションを生成した際のサーバー側ヒープメモリ消費量を計測する。
 *
 * <p>H1 以降の Headless State モデル（UIState）導入時に、
 * メモリ消費量が 90% 以上削減されていることをアサートするための基準値となる。
 */
public class StateMemoryBenchmarkTest {

    private static final int SESSION_COUNT = 100;
    private static final int WARMUP_COUNT = 20;

    public record MemoryBenchmarkResult(
            int sessionCount,
            long usedMemoryBytes,
            long bytesPerSession,
            int totalDomNodes,
            int nodesPerSession
    ) {}

    public static void triggerGc() {
        for (int i = 0; i < 4; i++) {
            System.gc();
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {}
        }
    }

    public static long getUsedMemory() {
        triggerGc();
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    public static int countNodes(HtmlNode node) {
        if (node == null) return 0;
        int count = 1;
        for (HtmlNode child : node.children) {
            count += countNodes(child);
        }
        return count;
    }

    public static MemoryBenchmarkResult measureDualDomMemory() {
        String machineHtml = PacketBandwidthTest.loadMachineHtml();

        // 1. ウォームアップ（クラスロード・JIT 最適化・静的初期化の完了）
        List<WebSession> warmupSessions = new ArrayList<>(WARMUP_COUNT);
        for (int i = 0; i < WARMUP_COUNT; i++) {
            WebSession session = new WebSession("warmup_" + i, s -> {});
            session.loadHtml(machineHtml);
            warmupSessions.add(session);
        }
        warmupSessions.clear();
        triggerGc();

        // 2. 測定前のベースラインメモリ取得
        long memBefore = getUsedMemory();

        // 3. 100 個の機械セッションを生成して DOM をロード
        List<WebSession> sessions = new ArrayList<>(SESSION_COUNT);
        int totalNodes = 0;
        for (int i = 0; i < SESSION_COUNT; i++) {
            WebSession session = new WebSession("block:100," + i + ",200", s -> {});
            session.loadHtml(machineHtml);
            sessions.add(session);
            if (session.getDocument() != null && session.getDocument().root != null) {
                totalNodes += countNodes(session.getDocument().root);
            }
        }

        // 4. 生成後のヒープメモリ取得
        long memAfter = getUsedMemory();

        // セッションが GC されないように参照を維持しつつサイズを確定
        assertNotNull(sessions.get(SESSION_COUNT - 1));

        long diff = Math.max(0, memAfter - memBefore);
        long perSession = diff / SESSION_COUNT;
        int nodesPerSession = totalNodes / SESSION_COUNT;

        return new MemoryBenchmarkResult(SESSION_COUNT, diff, perSession, totalNodes, nodesPerSession);
    }

    @Test
    @DisplayName("Dual DOM 方式の 100 セッション生成時ヒープメモリ消費量ベースライン計測")
    void testMeasureDualDomHeapMemory() throws IOException {
        MemoryBenchmarkResult result = measureDualDomMemory();

        System.out.printf(Locale.ROOT,
                """
                =======================================================
                [StateMemoryBenchmarkTest] Dual DOM Baseline
                  Session Count:             %d
                  Total Heap Memory Used:    %d bytes (%.2f KB, %.2f MB)
                  Memory Per Session:        %d bytes (%.2f KB)
                  Total DOM Nodes Retained:  %d nodes
                  DOM Nodes Per Session:     %d nodes
                =======================================================
                """,
                result.sessionCount(),
                result.usedMemoryBytes(),
                result.usedMemoryBytes() / 1024.0,
                result.usedMemoryBytes() / (1024.0 * 1024.0),
                result.bytesPerSession(),
                result.bytesPerSession() / 1024.0,
                result.totalDomNodes(),
                result.nodesPerSession()
        );

        // 100 セッションで DOM ノードが保持されていることを確認（決定論的指標）
        assertEquals(3000, result.totalDomNodes(), "100 セッションで合計 3000 個の DOM ノードがヒープに常駐していること");
        assertEquals(30, result.nodesPerSession(), "1 セッションあたり 30 個の DOM ノードが保持されていること");
        // メモリ消費量（DOM 100 個分で確実に消費されること）
        assertTrue(result.usedMemoryBytes() > 1_000_000,
                "100 セッション生成により少なくとも 1MB 以上のヒープ消費が増加すること（実測: ~35MB）");

        // ベースライン結果の記録
        Path outReport = Path.of("build/reports/state-memory-baseline.txt");
        Files.createDirectories(outReport.getParent());
        String report = String.format(Locale.ROOT,
                "sessionCount=%d%nusedMemoryBytes=%d%nbytesPerSession=%d%ntotalDomNodes=%d%nnodesPerSession=%d%n",
                result.sessionCount(), result.usedMemoryBytes(), result.bytesPerSession(),
                result.totalDomNodes(), result.nodesPerSession());
        Files.writeString(outReport, report, StandardCharsets.UTF_8);
    }
}
