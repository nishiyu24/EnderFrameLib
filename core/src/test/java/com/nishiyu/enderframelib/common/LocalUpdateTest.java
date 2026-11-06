package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.DisplayListBuilder;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 局所更新（文字 1 つの変更など）の最適化の検収をまとめたもの（ファイル数のラチェットのため 1 ファイルに統合）。
 * <ul>
 *   <li>{@link ChunkRetention}: 描画順の部分木の保持（{@code PaintOrder.Chunk}）が、使わないときと項目ごとに同じ</li>
 *   <li>{@link CutoffLayout}: 外寸が変わらない子の変化を親が吸収する測定（cutoff）が、吸収しないときと同じ</li>
 *   <li>{@link Probe}: 内訳の診断（表示のみ。判定しない）</li>
 * </ul>
 */
public class LocalUpdateTest {

    @Nested
    class ChunkRetention {

    private static final String CSS = """
            <style>
              body { margin: 4px; font-size: 13px }
              ul { list-style: none; padding: 0; margin: 0 }
              li { padding: 3px; border-bottom: 1px solid #ccc }
              li.hot { background: #fed; color: #a00 }
              li:hover { background: #def }
              .card { margin: 2px; padding: 2px; border: 1px solid #888; border-radius: 4px }
              .card b { color: #080 }
              .abs { position: absolute; left: 10px; top: 10px }
              .fl { float: left; width: 20px }
              .op { opacity: .5 }
            </style>
            """;

    @AfterEach
    void restore() {
        DisplayListBuilder.setChunksEnabled(true);
    }

    private static String html(int rows) {
        StringBuilder sb = new StringBuilder(CSS).append("<div id=wrap><ul id=list>");
        for (int i = 0; i < rows; i++) {
            sb.append("<li id=r").append(i).append(">Row ").append(i);
            if (i % 5 == 0) sb.append(" <span class=card><b>bold ").append(i).append("</b> text</span>");
            if (i % 11 == 0) sb.append("<span class=abs>abs</span>");
            if (i % 13 == 0) sb.append("<span class=fl>f</span>");
            if (i % 7 == 0) sb.append("<span class=op>faded</span>");
            sb.append("</li>");
        }
        return sb.append("</ul></div>").toString();
    }

    private static List<DisplayItem> flush(StandaloneView v, boolean chunks) {
        DisplayListBuilder.setChunksEnabled(chunks);
        v.ui().flushInvalidation();
        return v.ui().renderCache();
    }

    private static void sameItems(List<DisplayItem> expected, List<DisplayItem> actual, String step) {
        assertEquals(expected.size(), actual.size(), step + ": 項目の数");
        for (int i = 0; i < expected.size(); i++) assertEquals(expected.get(i), actual.get(i), step + ": 項目 " + i);
    }

    private interface Mutation {
        void apply(StandaloneView v, Random rnd, int rows, int step);
    }

    private static HtmlNode row(StandaloneView v, Random rnd, int rows) {
        for (int tries = 0; tries < 20; tries++) {
            HtmlNode n = v.root().getNodeById("r" + rnd.nextInt(rows));
            if (n != null) return n;
        }
        return v.root().getNodeById("r0");
    }

    private static final Mutation[] MUTATIONS = {
            (v, rnd, rows, step) -> row(v, rnd, rows).updateTextContent("text " + step),
            (v, rnd, rows, step) -> ((HtmlElement) row(v, rnd, rows)).toggleClass("hot"),
            (v, rnd, rows, step) -> ((HtmlElement) row(v, rnd, rows)).setAttribute("style", step % 2 == 0 ? "padding: 9px" : "color: #00c"),
            (v, rnd, rows, step) -> {
                HtmlNode list = v.root().getNodeById("list");
                HtmlNode added = row(v, rnd, rows).cloneNode(true);
                ((HtmlElement) added).setAttribute("id", "n" + step);
                list.appendChild(added);
            },
            (v, rnd, rows, step) -> {
                HtmlNode list = v.root().getNodeById("list");
                HtmlNode r = row(v, rnd, rows);
                if (r.parent == list && list.children.size() > 20) list.removeChild(r);
            },
            (v, rnd, rows, step) -> {
                HtmlNode r = row(v, rnd, rows);
                HtmlNode span = new HtmlElement("span");
                span.updateTextContent("ins " + step);
                r.appendChild(span);
            },
            (v, rnd, rows, step) -> {
                HtmlElement r = (HtmlElement) row(v, rnd, rows);
                if (r.hasAttribute("hidden")) r.removeAttribute("hidden"); else r.setAttribute("hidden", "");
            },
    };

    @Test
    public void retainedSubtreesMatchRebuiltOnesAcrossRandomMutations() {
        int rows = 120;
        StandaloneView on = new StandaloneView(html(rows), 480, 600, FontResolver.FALLBACK);
        StandaloneView off = new StandaloneView(html(rows), 480, 600, FontResolver.FALLBACK);
        sameItems(flush(off, false), flush(on, true), "初回");
        long replayed0 = DisplayListBuilder.chunksReplayed();
        Random rnd = new Random(20261107L);
        for (int step = 0; step < 400; step++) {
            Mutation m = MUTATIONS[rnd.nextInt(MUTATIONS.length)];
            long seed = rnd.nextLong();
            m.apply(on, new Random(seed), rows, step);
            m.apply(off, new Random(seed), rows, step);
            sameItems(flush(off, false), flush(on, true), "手順 " + step);
        }
        assertTrue(DisplayListBuilder.chunksReplayed() - replayed0 > 0, "保持した部分木を再利用した経路に乗っている");
    }

    /** 文字だけを繰り返し変えると、変えていない行は保持した項目で出る（再利用の回数で固定する）。 */
    @Test
    public void textUpdatesReplayUntouchedRows() {
        int rows = 200;
        // float や位置指定の行を含まない文書（float は後続の行を毎回組み直させるので、保持が効かないのが正しい）
        StringBuilder plain = new StringBuilder(CSS).append("<ul id=list>");
        for (int i = 0; i < rows; i++) plain.append("<li id=r").append(i).append(">Row ").append(i).append("</li>");
        StandaloneView v = new StandaloneView(plain.append("</ul>").toString(), 480, 600, FontResolver.FALLBACK);
        flush(v, true);
        for (int i = 0; i < 3; i++) {
            v.root().getNodeById("r1").updateTextContent("warm " + i);
            flush(v, true);
        }
        long before = DisplayListBuilder.chunksReplayed();
        v.root().getNodeById("r1").updateTextContent("measured");
        flush(v, true);
        long replayed = DisplayListBuilder.chunksReplayed() - before;
        assertTrue(replayed > rows / 2, "変えていない行を保持から出した数: " + replayed);
    }

    /** 変異の列を作り直さずに、保持あり・なしで同じ最終結果になる（保持が古い項目を残さない）。 */
    @Test
    public void retentionNeverLeavesStaleItemsAfterRowRemoval() {
        int rows = 60;
        StandaloneView on = new StandaloneView(html(rows), 480, 600, FontResolver.FALLBACK);
        StandaloneView off = new StandaloneView(html(rows), 480, 600, FontResolver.FALLBACK);
        sameItems(flush(off, false), flush(on, true), "初回");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < rows; i += 3) ids.add("r" + i);
        for (String id : ids) {
            on.root().getNodeById(id).parent.removeChild(on.root().getNodeById(id));
            off.root().getNodeById(id).parent.removeChild(off.root().getNodeById(id));
            sameItems(flush(off, false), flush(on, true), "削除 " + id);
            on.root().getNodeById("r1").updateTextContent("after " + id);
            off.root().getNodeById("r1").updateTextContent("after " + id);
            sameItems(flush(off, false), flush(on, true), "削除後の文字 " + id);
        }
    }
    }

    @Nested
    class CutoffLayout {

    @AfterEach
    void restore() {
        LayoutBox.cutoffEnabled = true;
    }

    private static final String HTML = """
            <style>
              body { margin: 4px; font-size: 13px }
              ul { list-style: none; padding: 0; margin: 0 }
              li { padding: 3px; border-bottom: 1px solid #ccc }
              .card { margin: 6px 2px; padding: 4px; border: 1px solid #888 }
              .fixed { height: 24px; overflow: hidden }
              .narrow { width: 120px }
              p { margin: 4px 0 }
              .flex { display: flex; gap: 4px }
              .flex > div { flex: 1 }
            </style>
            <div id=wrap>
              <ul id=list>%s</ul>
              <div class=card id=card><p id=para>start</p><div id=inner><p id=deep>deep text</p></div></div>
              <div class="card narrow" id=narrow><p id=np>narrow paragraph</p></div>
              <div class=flex id=flex><div id=fa>a</div><div id=fb>b</div></div>
              <div id=tail>tail</div>
            </div>
            """;

    private static String html(int rows) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows; i++) {
            sb.append("<li id=r").append(i).append(i % 7 == 0 ? " class=fixed" : "").append(">Row ").append(i).append("</li>");
        }
        return HTML.formatted(sb);
    }

    private static final String[] TEXTS = {
            "x", "short", "a considerably longer piece of text that wraps in a narrow box",
            "this text is long enough that it must wrap onto several lines when the box is narrow and keep going and going",
            "mid length text here", "y", "",
    };

    private static final String[] IDS = {"para", "deep", "np", "fa", "fb", "tail", "r1", "r2", "r7", "r14", "r30"};

    private static List<DisplayItem> flush(StandaloneView v, boolean cutoff) {
        LayoutBox.cutoffEnabled = cutoff;
        v.ui().flushInvalidation();
        return v.ui().renderCache();
    }

    private static void same(List<DisplayItem> expected, List<DisplayItem> actual, String step) {
        assertEquals(expected.size(), actual.size(), step + ": 項目の数");
        for (int i = 0; i < expected.size(); i++) assertEquals(expected.get(i), actual.get(i), step + ": 項目 " + i);
    }

    @Test
    public void cutoffMatchesNoCutoffAcrossRandomTextMutations() {
        String doc = html(60);
        StandaloneView on = new StandaloneView(doc, 360, 500, FontResolver.FALLBACK);
        StandaloneView off = new StandaloneView(doc, 360, 500, FontResolver.FALLBACK);
        same(flush(off, false), flush(on, true), "初回");
        Random rnd = new Random(77L);
        for (int step = 0; step < 500; step++) {
            String id = IDS[rnd.nextInt(IDS.length)];
            String text = TEXTS[rnd.nextInt(TEXTS.length)];
            if (rnd.nextInt(8) == 0) text = "t" + step; // 同じ長さの別の文字（外寸が変わらない）
            HtmlNode a = on.root().getNodeById(id), b = off.root().getNodeById(id);
            a.updateTextContent(text);
            b.updateTextContent(text);
            same(flush(off, false), flush(on, true), "手順 " + step + " (" + id + " = '" + text + "')");
        }
    }

    /** 外寸が変わらない更新は、親の側を測り直さない（測り直した箱の数で固定する）。 */
    @Test
    public void sameSizeUpdateRemeasuresOnlyTheChangedPath() {
        StandaloneView v = new StandaloneView(html(300), 360, 500, FontResolver.FALLBACK);
        v.ui().flushInvalidation();
        HtmlNode r = v.root().getNodeById("r1");
        for (int i = 0; i < 4; i++) { r.updateTextContent("w" + i); v.ui().flushInvalidation(); }
        long before = LayoutBox.measurementsComputed();
        r.updateTextContent("q9");
        v.ui().flushInvalidation();
        long measured = LayoutBox.measurementsComputed() - before;
        assertTrue(measured < 20, "同じ大きさの更新で測り直した箱の数: " + measured);
    }

    /** 高さが変わる更新は、後ろの箱を動かす（吸収されずに親へ伝わる）。 */
    @Test
    public void heightChangePropagatesToFollowingBoxes() {
        StandaloneView on = new StandaloneView(html(40), 360, 500, FontResolver.FALLBACK);
        StandaloneView off = new StandaloneView(html(40), 360, 500, FontResolver.FALLBACK);
        same(flush(off, false), flush(on, true), "初回");
        String longText = TEXTS[3];
        for (String id : new String[]{"np", "para", "deep"}) {
            on.root().getNodeById(id).updateTextContent(longText);
            off.root().getNodeById(id).updateTextContent(longText);
            List<DisplayItem> a = flush(on, true);
            same(flush(off, false), a, "長くする " + id);
            on.root().getNodeById(id).updateTextContent("s");
            off.root().getNodeById(id).updateTextContent("s");
            same(flush(off, false), flush(on, true), "短くする " + id);
        }
    }
    }

    @Nested
    class Probe {

    private static String listHtml(int rows) {
        StringBuilder sb = new StringBuilder("<style>body{margin:8px}ul{list-style:none;padding:0}"
                + "li{padding:4px;border-bottom:1px solid #ccc}</style>"
                + "<div id=scroller style=\"height:400px;overflow-y:auto\"><ul id=list>");
        // DocumentLoader は入力を 128KB で黙って切る。5000 行が収まるよう行を短くし、読めた行数を必ず確かめる
        for (int i = 1; i <= rows; i++) {
            sb.append("<li id=i").append(i).append(">Row ").append(i).append("</li>");
        }
        String html = sb.append("</ul></div>").toString();
        if (html.length() > 131072) throw new IllegalStateException("fixture exceeds DocumentLoader's 128KB cap: " + html.length());
        return html;
    }

    private static StandaloneView open(int rows) {
        StandaloneView v = new StandaloneView(listHtml(rows), 800, 600, FontResolver.FALLBACK);
        v.ui().flushInvalidation();
        if (v.root().getNodeById("i" + rows) == null) throw new IllegalStateException("document truncated: item-" + rows + " missing");
        return v;
    }

    private static double median(long[] ns) {
        long[] c = ns.clone();
        Arrays.sort(c);
        return c[c.length / 2] / 1_000_000.0;
    }

    @Test
    public void probeTextChange() {
        // テストでは検証モードが常時ON（全箱を走査し直す）。実測では切る
        boolean stamps = com.nishiyu.enderframelib.core.paint.DisplayListBuilder.verifyStamps;
        boolean floats = com.nishiyu.enderframelib.core.layout.BlockLayoutEngine.verifyFloatMemo;
        com.nishiyu.enderframelib.core.paint.DisplayListBuilder.verifyStamps = false;
        com.nishiyu.enderframelib.core.layout.BlockLayoutEngine.verifyFloatMemo = false;
        try {
            probe();
        } finally {
            com.nishiyu.enderframelib.core.paint.DisplayListBuilder.verifyStamps = stamps;
            com.nishiyu.enderframelib.core.layout.BlockLayoutEngine.verifyFloatMemo = floats;
        }
    }

    /** 5000 行で局所更新を繰り返し、別スレッドから主スレッドのスタックを採って、どこで時間を使うかを集計する。 */
    @Test
    public void sampleHotSpots() throws Exception {
        boolean stamps = com.nishiyu.enderframelib.core.paint.DisplayListBuilder.verifyStamps;
        boolean floats = com.nishiyu.enderframelib.core.layout.BlockLayoutEngine.verifyFloatMemo;
        com.nishiyu.enderframelib.core.paint.DisplayListBuilder.verifyStamps = false;
        com.nishiyu.enderframelib.core.layout.BlockLayoutEngine.verifyFloatMemo = false;
        try {
            int rows = 5000;
            StandaloneView v = open(rows);
            v.ui().flushInvalidation();
            for (int i = 0; i < 300; i++) {
                v.root().getNodeById("i" + (1 + (i * 7) % rows)).updateTextContent("w " + i);
                v.ui().flushInvalidation();
            }
            Thread main = Thread.currentThread();
            java.util.Map<String, Integer> self = new java.util.HashMap<>(), incl = new java.util.HashMap<>();
            int[] total = {0};
            boolean[] run = {true};
            Thread sampler = new Thread(() -> {
                while (run[0]) {
                    StackTraceElement[] st = main.getStackTrace();
                    if (st.length > 0 && st[0].getClassName().startsWith("com.nishiyu")) {
                        total[0]++;
                    }
                    if (st.length > 0) {
                        self.merge(st[0].getClassName().replaceAll(".*\\.", "") + "." + st[0].getMethodName() + ":" + st[0].getLineNumber(), 1, Integer::sum);
                        java.util.Set<String> seen = new java.util.HashSet<>();
                        for (StackTraceElement e : st) {
                            if (!e.getClassName().startsWith("com.nishiyu")) continue;
                            String k = e.getClassName().replaceAll(".*\\.", "") + "." + e.getMethodName();
                            if (seen.add(k)) incl.merge(k, 1, Integer::sum);
                        }
                    }
                    try { Thread.sleep(0, 200_000); } catch (InterruptedException e) { return; }
                }
            });
            sampler.setDaemon(true);
            sampler.start();
            for (int i = 0; i < 3000; i++) {
                HtmlNode r0 = v.root().getNodeById("i" + (1 + (i * 7) % rows)); if (r0 == null) throw new IllegalStateException("missing i" + (1 + (i * 7) % rows) + " at i=" + i); r0.updateTextContent("x " + i);
                v.ui().flushInvalidation();
            }
            run[0] = false;
            sampler.join();
            System.out.println("[hot] samples in engine=" + total[0]);
            incl.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(28)
                    .forEach(e -> System.out.println("[hot] incl " + e.getValue() + " " + e.getKey()));
            self.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(14)
                    .forEach(e -> System.out.println("[hot] self " + e.getValue() + " " + e.getKey()));
        } finally {
            com.nishiyu.enderframelib.core.paint.DisplayListBuilder.verifyStamps = stamps;
            com.nishiyu.enderframelib.core.layout.BlockLayoutEngine.verifyFloatMemo = floats;
        }
    }

    private void probe() {
        for (int rows : new int[]{500, 5000}) {
            StandaloneView v = open(rows);
            v.ui().flushInvalidation();
            int warm = 60, n = 100;
            long[] flush = new long[n];
            String[] breakdown = new String[n];
            for (int i = 0; i < warm + n; i++) {
                HtmlNode row = v.root().getNodeById("i" + (1 + (i * 7) % rows));
                row.updateTextContent("changed " + i);
                long t0 = System.nanoTime();
                v.ui().flushInvalidation();
                long dt = System.nanoTime() - t0;
                if (i >= warm) {
                    flush[i - warm] = dt;
                    breakdown[i - warm] = v.ui().lastBreakdown();
                }
            }
            System.out.printf("[probe] rows=%d items=%d median flush=%.3fms  last: %s%n",
                    rows, v.ui().renderCache().size(), median(flush), breakdown[n - 1]);
        }
    }
    }
}
