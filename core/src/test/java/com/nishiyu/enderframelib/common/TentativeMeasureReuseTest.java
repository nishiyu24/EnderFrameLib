package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * flex の仮の測定を前回の産物で済ませる（{@link LayoutBox#measureTentatively}）と、毎回本当に測る場合とで、
 * どんな変更の列のあとでも表示リストが完全に一致すること。
 */
class TentativeMeasureReuseTest {

    private static final String CSS = """
            <style>
              .row { display: flex; width: 380px; gap: 3px; padding: 2px }
              .wrap { flex-wrap: wrap }
              .col { flex-direction: column; height: 200px }
              .it { padding: 2px 4%; border: 1px solid #888 }
              .it.g { flex: 1 1 0 }
              .it.s { flex: 0 1 90px }
              .it.m { margin-left: auto }
              .it.c { align-self: center }
              .it.b { align-self: baseline }
              .it.big { font-size: 22px }
              .it.fix { width: 60px }
              .row.ctr { align-items: center }
              .row.rev { flex-direction: row-reverse }
              .col.nh { height: auto; width: 150px }
              .col.rev { flex-direction: column-reverse }
              .col.ctr { align-items: center }
              .inner { display: flex; gap: 2px }
              .grid { display: grid; width: 380px; gap: 3px; grid-template-columns: auto 1fr 80px }
              .grid.g2 { grid-template-columns: repeat(auto-fill, minmax(70px, 1fr)) }
              .grid.g3 { grid-template-columns: 1fr 2fr; grid-auto-rows: 40px }
              .grid.g4 { grid-template-columns: auto auto; grid-auto-rows: minmax(30px, auto); height: 260px }
              .grid.ctr { align-items: center }
            </style>
            """;

    @AfterEach
    void restore() {
        LayoutBox.tentativeReuse = true;
    }

    private static String html() {
        StringBuilder sb = new StringBuilder(CSS);
        String[] kinds = {"row", "row wrap", "row ctr", "row rev", "col", "row", "grid", "grid g2", "grid g3 ctr", "grid g4", "col nh", "col wrap ctr", "col nh rev"};
        for (int c = 0; c < kinds.length; c++) {
            sb.append("<div id=c").append(c).append(" class='").append(kinds[c]).append("'>");
            for (int i = 0; i < 9; i++) {
                String cls = "it" + (i % 3 == 0 ? " g" : "") + (i % 4 == 1 ? " s" : "") + (i % 5 == 2 ? " m" : "")
                        + (i % 6 == 3 ? " c" : "") + (i % 7 == 4 ? " b" : "") + (i % 8 == 5 ? " big" : "") + (i % 9 == 6 ? " fix" : "");
                sb.append("<div id=i").append(c).append('_').append(i).append(" class='").append(cls).append("'>");
                sb.append("<span id=t").append(c).append('_').append(i).append(">item ").append(i).append(" words here</span>");
                if (i % 2 == 0) sb.append("<div class=inner><b>x</b><i>yy</i></div>");
                sb.append("</div>");
            }
            sb.append("</div>");
        }
        return sb.toString();
    }

    private static List<DisplayItem> flush(StandaloneView v, boolean tentative) {
        LayoutBox.tentativeReuse = tentative;
        v.ui().flushInvalidation();
        return v.ui().renderCache();
    }

    private static void sameItems(List<DisplayItem> expected, List<DisplayItem> actual, String step) {
        assertEquals(expected.size(), actual.size(), step + ": 項目の数");
        for (int i = 0; i < expected.size(); i++) assertEquals(expected.get(i), actual.get(i), step + ": 項目 " + i);
    }

    private interface Mutation {
        void apply(StandaloneView v, Random rnd, int step);
    }

    private static HtmlNode item(StandaloneView v, Random rnd) {
        for (int tries = 0; tries < 30; tries++) {
            HtmlNode n = v.root().getNodeById("i" + rnd.nextInt(KINDS) + "_" + rnd.nextInt(9));
            if (n != null) return n;
        }
        return v.root().getNodeById("i0_0");
    }

    private static final int KINDS = 13;
    private static final String[] CLASSES = {"g", "s", "m", "c", "b", "big", "fix"};
    private static final String[] CONTAINER_CLASSES = {"wrap", "ctr", "rev"};

    private static final Mutation[] MUTATIONS = {
            (v, rnd, step) -> {
                HtmlNode n = v.root().getNodeById("t" + rnd.nextInt(KINDS) + "_" + rnd.nextInt(9));
                if (n != null) n.updateTextContent("w" + step + " ".repeat(step % 2) + "q".repeat(step % 17));
            },
            (v, rnd, step) -> ((HtmlElement) item(v, rnd)).toggleClass(CLASSES[rnd.nextInt(CLASSES.length)]),
            (v, rnd, step) -> ((HtmlElement) v.root().getNodeById("c" + rnd.nextInt(KINDS)))
                    .toggleClass(CONTAINER_CLASSES[rnd.nextInt(CONTAINER_CLASSES.length)]),
            (v, rnd, step) -> ((HtmlElement) item(v, rnd)).setAttribute("style", switch (step % 4) {
                case 0 -> "width: " + (30 + step % 90) + "px";
                case 1 -> "flex-grow: " + (step % 3);
                case 2 -> "min-width: " + (step % 70) + "px";
                default -> "margin: " + (step % 9) + "px";
            }),
            (v, rnd, step) -> {
                HtmlNode c = v.root().getNodeById("c" + rnd.nextInt(KINDS));
                HtmlNode added = item(v, rnd).cloneNode(true);
                ((HtmlElement) added).setAttribute("id", "n" + step);
                c.appendChild(added);
            },
            (v, rnd, step) -> {
                HtmlNode n = item(v, rnd);
                if (n.parent != null && n.parent.children.size() > 4) n.parent.removeChild(n);
            },
            (v, rnd, step) -> ((HtmlElement) v.root().getNodeById("c" + rnd.nextInt(KINDS)))
                    .setAttribute("style", "width: " + (200 + step % 200) + "px"),
    };

    @Test
    void randomChangesGiveTheSameDisplayListWithAndWithoutTentativeReuse() {
        long skippedBefore = LayoutBox.tentativeSkipped();
        for (long seed = 1; seed <= 12; seed++) {
            StandaloneView off = new StandaloneView(html(), 420, 700, FontResolver.FALLBACK);
            StandaloneView on = new StandaloneView(html(), 420, 700, FontResolver.FALLBACK);
            sameItems(flush(off, false), flush(on, true), "seed " + seed + " 初回");
            Random rOff = new Random(seed);
            Random rOn = new Random(seed);
            for (int step = 0; step < 60; step++) {
                int m = new Random(seed * 1000 + step).nextInt(MUTATIONS.length);
                MUTATIONS[m].apply(off, rOff, step);
                MUTATIONS[m].apply(on, rOn, step);
                sameItems(flush(off, false), flush(on, true), "seed " + seed + " 手順 " + step + " (変更 " + m + ")");
            }
        }
        assertTrue(LayoutBox.tentativeSkipped() > skippedBefore, "仮の測定を飛ばす経路が実際に使われている");
    }

    @Test
    void alternatingResizeOnShowcaseMatchesWithoutReuseAndReducesMeasurementsByOver20Percent() throws Exception {
        String showcase = null;
        for (String p : new String[]{"../src/main/resources/data/enderframelib/enderframe_ui/showcase.html",
                "src/main/resources/data/enderframelib/enderframe_ui/showcase.html"}) {
            java.nio.file.Path path = java.nio.file.Path.of(p);
            if (java.nio.file.Files.exists(path)) {
                showcase = java.nio.file.Files.readString(path, java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
                break;
            }
        }
        assertTrue(showcase != null, "showcase.html が読めること");

        StandaloneView off = new StandaloneView(showcase, 800, 600, FontResolver.FALLBACK);
        StandaloneView on = new StandaloneView(showcase, 800, 600, FontResolver.FALLBACK);
        sameItems(flush(off, false), flush(on, true), "showcase 初回");

        long offMeasurements = 0;
        long onMeasurements = 0;
        for (int i = 0; i < 20; i++) {
            int w = 800 - (i & 1);
            LayoutBox.tentativeReuse = false;
            long m0 = LayoutBox.measurementsComputed();
            off.ui().recomputeLayout(w, 600);
            if (i >= 4) offMeasurements += LayoutBox.measurementsComputed() - m0;

            LayoutBox.tentativeReuse = true;
            long m1 = LayoutBox.measurementsComputed();
            on.ui().recomputeLayout(w, 600);
            if (i >= 4) onMeasurements += LayoutBox.measurementsComputed() - m1;

            sameItems(off.ui().renderCache(), on.ui().renderCache(), "showcase resizeexact 手順 " + i + " w=" + w);
        }
        assertTrue(onMeasurements * 10 <= offMeasurements * 8,
                "resizeexact の測り直しが 20% 以上減ること: on=" + onMeasurements + " off=" + offMeasurements);
    }

    @Test
    void definiteLeafAndStretchedFlexGridItemsAreIdenticalAcrossResizes() {
        String doc = """
                <style>
                  .outer { display: flex; flex-direction: column; gap: 6px; padding: 8px; }
                  .toolbar { display: flex; gap: 6px; align-items: stretch; }
                  .toolbar.base { align-items: baseline; }
                  .fixed { width: 140px; padding: 4px; border: 1px solid #555; }
                  .grow { flex: 1; }
                  .pct { width: 120px; height: 28px; padding: 2px; }
                  .pct > span { display: block; height: 100%; }
                  .spacer { width: 40px; }
                  .grid { display: grid; grid-template-columns: 140px 1fr 1fr; gap: 6px; align-items: stretch; }
                  .card { display: flex; flex-direction: column; gap: 4px; border: 1px solid #444; }
                  .btn { padding: 4px 8px; }
                </style>
                <div class="outer">
                  <div class="toolbar">
                    <div class="fixed">fixed box</div>
                    <input class="grow" value="abc">
                    <select class="fixed"><option>A</option></select>
                    <progress class="grow" value="50" max="100"></progress>
                    <div class="spacer"></div>
                    <div class="pct"><span>tall</span></div>
                  </div>
                  <div class="toolbar base">
                    <span style="font-size:20px">big</span>
                    <button class="btn">go</button>
                    <div class="fixed">tail</div>
                  </div>
                  <div class="grid">
                    <div class="card"><span class="btn">c1</span><button class="btn">buy</button></div>
                    <input class="grow" value="g2">
                    <div class="card" style="height:80px"><div class="pct"><span>h</span></div></div>
                  </div>
                </div>
                """;
        StandaloneView off = new StandaloneView(doc, 800, 600, FontResolver.FALLBACK);
        StandaloneView on = new StandaloneView(doc, 800, 600, FontResolver.FALLBACK);
        sameItems(flush(off, false), flush(on, true), "初回");
        int[] widths = {799, 800, 799, 700, 799, 640, 800, 799, 800};
        for (int i = 0; i < widths.length; i++) {
            int w = widths[i];
            LayoutBox.tentativeReuse = false;
            off.ui().recomputeLayout(w, 600);
            LayoutBox.tentativeReuse = true;
            on.ui().recomputeLayout(w, 600);
            sameItems(off.ui().renderCache(), on.ui().renderCache(), "step " + i + " w=" + w);
        }
    }
}
