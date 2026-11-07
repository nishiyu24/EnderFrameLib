package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 要素数 N を 500 / 2,000 / 7,500 と振ったときの、1 回の操作のコスト・保持メモリの記録
 * （DIRTY_AND_COMPLEXITY_PLAN.md Phase 0）。絶対値は機械依存なので落とさず、N を増やしたときの時間の比（指数）（傾き）を出力する。
 * 比が約 1 なら O(1)、約 10 なら O(N)。結果は core/BENCHMARK_SCALING.md に転記する。
 */
class ScalingBenchmarkTest {

    private static final int[] SIZES = {500, 2000, 7500};

    /** 文書の読み込みは HTML を 131072 文字で切る（DocumentLoader）ので、行は短く、id は測る 1 行だけに付ける。 */
    private static String listHtml(int rows, boolean hasRule) {
        StringBuilder sb = new StringBuilder("<html><head><style>li{height:20px} li.hot{color:#f00}");
        if (hasRule) sb.append(" ul:has(li.hot){background:#eee}");
        sb.append("</style></head><body style='margin:0'><ul>");
        for (int i = 0; i < rows; i++) sb.append(i == rows / 2 ? "<li id='mid'>row</li>" : "<li>row</li>");
        return sb.append("</ul></body></html>").toString();
    }

    private static long usedHeap() {
        for (int i = 0; i < 3; i++) { System.gc(); try { Thread.sleep(20); } catch (InterruptedException ignored) { } }
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static double median(long[] v) {
        long[] c = v.clone();
        Arrays.sort(c);
        return c[c.length / 2];
    }

    private record Row(int n, double loadMs, double heapBytesPerRow, double idleNs, double textFrameUs, double textNodeFrameUs,
                       double classFrameUs, double classMarkOnlyUs, double hasClassFrameUs, double hasMarkOnlyUs) { }

    private static Row measure(int n) {
        double textNodeUs;
        long heap0 = usedHeap();
        long t0 = System.nanoTime();
        StandaloneView view = new StandaloneView(listHtml(n, false), 400, 300);
        double loadMs = (System.nanoTime() - t0) / 1e6;
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(400, 300, 1f)) {
            org.junit.jupiter.api.Assertions.assertEquals(n, view.root().querySelectorAll("li").getLength(), "行が切られていない");
            long now = 1000;
            view.frame(now, cpu, true);
            view.frame(now += 16, cpu, false);
            double heapPerRow = (usedHeap() - heap0) / (double) n;

            for (int i = 0; i < 200; i++) view.needsFrame();
            long[] idle = new long[5];
            for (int r = 0; r < idle.length; r++) {
                long s = System.nanoTime();
                for (int i = 0; i < 2000; i++) view.needsFrame();
                idle[r] = (System.nanoTime() - s) / 2000;
            }

            int reps = n >= 20000 ? 9 : 15;
            HtmlNode mid = view.root().getElementById("mid");
            assertNotNull(mid);
            long[] text = new long[reps];
            for (int i = 0; i < reps + 3; i++) {
                mid.setTextContent("changed " + i);
                long s = System.nanoTime();
                view.frame(now += 16, cpu, false);
                if (i >= 3) text[i - 3] = System.nanoTime() - s;
            }
            System.out.println("SCALING-BREAKDOWN n=" + n + " text(element.setTextContent): " + view.lastTimings());
            // 要素の setTextContent は子を消して新しい Text を足す（構造変更）。Text 節そのものを書き換えると text-patch に乗る
            HtmlNode textNode = mid.children.get(0);
            long hits0 = com.nishiyu.enderframelib.common.UiView.TEXT_PATCH_HITS.get();
            long falls0 = com.nishiyu.enderframelib.common.UiView.TEXT_PATCH_FALLBACKS.get();
            long[] tn = new long[reps];
            for (int i = 0; i < reps + 3; i++) {
                textNode.setTextContent("patched " + i);
                long s = System.nanoTime();
                view.frame(now += 16, cpu, false);
                if (i >= 3) tn[i - 3] = System.nanoTime() - s;
            }
            System.out.println("SCALING-BREAKDOWN n=" + n + " text(Text node): hits=" + (com.nishiyu.enderframelib.common.UiView.TEXT_PATCH_HITS.get() - hits0)
                    + " fallbacks=" + (com.nishiyu.enderframelib.common.UiView.TEXT_PATCH_FALLBACKS.get() - falls0) + " " + view.lastTimings());
            textNodeUs = median(tn) / 1e3;
            long[] classFrame = new long[reps];
            long[] classMark = new long[reps];
            for (int i = 0; i < reps + 3; i++) {
                long s0 = System.nanoTime();
                mid.setAttribute("class", i % 2 == 0 ? "hot" : "");
                long s1 = System.nanoTime();
                view.frame(now += 16, cpu, false);
                if (i >= 3) { classMark[i - 3] = s1 - s0; classFrame[i - 3] = System.nanoTime() - s1; }
            }
            System.out.println("SCALING-BREAKDOWN n=" + n + " class: " + view.lastTimings());
            // :has() を使う文書（propagateDirtyToAncestors が毎回動く）
            long[] hasFrame = new long[reps];
            long[] hasMark = new long[reps];
            try (StandaloneView hv = new StandaloneView(listHtml(n, true), 400, 300)) {
                long hn = 1000;
                hv.frame(hn, cpu, true);
                hv.frame(hn += 16, cpu, false);
                HtmlNode hm = hv.root().getElementById("mid");
                for (int i = 0; i < reps + 3; i++) {
                    long s0 = System.nanoTime();
                    hm.setAttribute("class", i % 2 == 0 ? "hot" : "");
                    long s1 = System.nanoTime();
                    hv.frame(hn += 16, cpu, false);
                    if (i == reps + 2) System.out.println("SCALING-BREAKDOWN n=" + n + " has: " + hv.lastTimings());
                    if (i >= 3) { hasMark[i - 3] = s1 - s0; hasFrame[i - 3] = System.nanoTime() - s1; }
                }
            }
            return new Row(n, loadMs, heapPerRow, median(idle), median(text) / 1e3, textNodeUs, median(classFrame) / 1e3,
                    median(classMark) / 1e3, median(hasFrame) / 1e3, median(hasMark) / 1e3);
        } finally {
            view.close();
        }
    }

    @Test
    void scaling() {
        // テスト JVM は検証モード（全箱の署名の再計算・float の再走査）が常時有効で、計測が数倍に膨らむ。測る間は切る
        boolean stamps = com.nishiyu.enderframelib.core.paint.DisplayListBuilder.verifyStamps;
        boolean floats = com.nishiyu.enderframelib.core.layout.BlockLayoutEngine.verifyFloatMemo;
        com.nishiyu.enderframelib.core.paint.DisplayListBuilder.verifyStamps = false;
        com.nishiyu.enderframelib.core.layout.BlockLayoutEngine.verifyFloatMemo = false;
        try {
            run();
        } finally {
            com.nishiyu.enderframelib.core.paint.DisplayListBuilder.verifyStamps = stamps;
            com.nishiyu.enderframelib.core.layout.BlockLayoutEngine.verifyFloatMemo = floats;
        }
    }

    private void run() {
        Row[] rows = new Row[SIZES.length];
        measure(500); // 暖機（JIT）
        for (int i = 0; i < SIZES.length; i++) rows[i] = measure(SIZES[i]);
        System.out.println("SCALING | rows | load ms | heap B/row | idle needsFrame ns | text-change(element) frame us | text-change(Text node) frame us | class-toggle mark us | class-toggle frame us | :has mark us | :has frame us");
        for (Row r : rows) {
            System.out.printf("SCALING | %d | %.0f | %.0f | %.0f | %.0f | %.0f | %.1f | %.0f | %.1f | %.0f%n",
                    r.n, r.loadMs, r.heapBytesPerRow, r.idleNs, r.textFrameUs, r.textNodeFrameUs, r.classMarkOnlyUs, r.classFrameUs,
                    r.hasMarkOnlyUs, r.hasClassFrameUs);
        }
        Row a = rows[0], b = rows[1], c = rows[2];
        System.out.printf("SCALING 指数(500->2000 / 2000->7500; 0=定数 1=比例): " +
                        "load %.2f/%.2f text %.2f/%.2f classFrame %.2f/%.2f hasFrame %.2f/%.2f%n",
                exp(a.loadMs, b.loadMs, 4), exp(b.loadMs, c.loadMs, 3.75),
                exp(a.textFrameUs, b.textFrameUs, 4), exp(b.textFrameUs, c.textFrameUs, 3.75),
                exp(a.classFrameUs, b.classFrameUs, 4), exp(b.classFrameUs, c.classFrameUs, 3.75),
                exp(a.hasClassFrameUs, b.hasClassFrameUs, 4), exp(b.hasClassFrameUs, c.hasClassFrameUs, 3.75));
    }

    /** 時間の比が N の比の何乗か（0 なら定数、1 なら比例）。 */
    private static double exp(double small, double large, double nRatio) {
        return Math.log(Math.max(large, 1e-9) / Math.max(small, 1e-9)) / Math.log(nRatio);
    }
}
