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
}
