package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 子の増減のあとの結果が、独立の基準と一致すること（K5b L2: 局所パッチは撤去済みで、子の増減は変更台帳 {@code CHILDREN} から触れられた親だけを
 * 組み直す）。基準は 2 つ: ①記憶の無い {@code DisplayListBuilder} が今の箱の木から作る表示リスト〔描画の記憶の古さを見つける〕②{@link FrameOracle}
 * 〔DOM を複製して最初から作った箱の木との比較〕。差分でない基準を全体のフラグの切り替えで作らない〔並列に走る他のテストを乱す〕。
 * 兄弟の規則・番号つきマーカー・文字の子も同じ文書に混ぜる。
 */
class ChildPatchTest {

    private static final String CSS = """
            <style>
              body { margin: 4px }
              .list { padding: 0; margin: 2px; list-style: none }
              .list > li, .row { padding: 3px; border-bottom: 1px solid #ccc; margin-top: 2px; margin-bottom: 3px }
              .row.hot { background: #fed }
              .row.big { font-size: 20px; height: 40px }
              .flexrow { display: flex; gap: 3px }
              .box { display: block; margin: 5px 0 }
              .scroller { height: 120px; overflow-y: auto }
              .bullets li { margin: 1px 0 }
            </style>
            """;

    private static String rows(String cls, int n, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append("<div class='row ").append(cls).append("' id='").append(cls).append("-").append(i)
                .append("'>Item ").append(i).append(" text").append("</div>").append(sep);
        return sb.toString();
    }

    /** 兄弟の構造規則（これがあると、どこの子の増減でも兄弟が再スタイル対象になり、局所パッチは退く）。 */
    private static final String STRUCTURAL_CSS = "<style>.nth > .row:nth-child(2n) { background: #def } "
            + ".first > .row:first-child { color: #a00 } .sib > .row + .row { margin-top: 9px }</style>";

    static String html(String sep, boolean structural) {
        StringBuilder sb = new StringBuilder(CSS);
        if (structural) sb.append(STRUCTURAL_CSS);
        sb.append("<div id=plain>").append(rows("p", 8, sep)).append("</div>");
        sb.append("<div id=scr class=scroller>").append(rows("s", 12, sep)).append("</div>");
        sb.append("<ul id=ul class=list>");
        for (int i = 0; i < 6; i++) sb.append("<li id=u-").append(i).append(">li ").append(i).append("</li>").append(sep);
        sb.append("</ul>");
        sb.append("<ul id=bul class=bullets>");
        for (int i = 0; i < 6; i++) sb.append("<li id=b-").append(i).append(">bullet ").append(i).append("</li>").append(sep);
        sb.append("</ul>");
        sb.append("<ol id=ol>");
        for (int i = 0; i < 4; i++) sb.append("<li id=o-").append(i).append(">num ").append(i).append("</li>").append(sep);
        sb.append("</ol>");
        sb.append("<div id=nth class=nth>").append(rows("n", 6, sep)).append("</div>");
        sb.append("<div id=first class=first>").append(rows("f", 6, sep)).append("</div>");
        sb.append("<div id=sib class=sib>").append(rows("g", 6, sep)).append("</div>");
        sb.append("<div id=mixed>").append("lead text <b>bold</b> ").append(rows("m", 3, sep)).append("tail</div>");
        sb.append("<div id=nest><div id=nest-in class=box>").append(rows("i", 4, sep)).append("</div></div>");
        sb.append("<div id=flex class=flexrow>");
        for (int i = 0; i < 5; i++) sb.append("<div class='row' id=x-").append(i).append(">flex ").append(i).append("</div>");
        sb.append("</div>");
        return sb.toString();
    }

    static String lastParent = "";
    static final String[] PARENTS = {"plain", "scr", "ul", "bul", "ol", "nth", "first", "sib", "mixed", "nest-in", "flex"};

    private static HtmlNode parent(StandaloneView v, Random rnd) {
        String id = PARENTS[rnd.nextInt(PARENTS.length)];
        lastParent = id;
        return v.root().getNodeById(id);
    }

    private static HtmlElement fresh(String parentId, int step, Random rnd) {
        boolean li = parentId.equals("ul") || parentId.equals("bul") || parentId.equals("ol");
        HtmlElement e = new HtmlElement(li ? "li" : "div");
        if (!li) e.setAttribute("class", rnd.nextBoolean() ? "row" : "row hot" + (rnd.nextInt(5) == 0 ? " big" : ""));
        e.updateTextContent("new " + step + " " + "word ".repeat(rnd.nextInt(12)));
        if (rnd.nextInt(6) == 0) {
            HtmlElement inner = new HtmlElement("span");
            inner.updateTextContent("in" + step);
            e.appendChild(inner);
        }
        return e;
    }

    interface Mutation {
        void apply(StandaloneView v, Random rnd, int step);
    }

    static final Mutation[] MUTATIONS = {
            // 末尾に追加
            (v, rnd, step) -> {
                HtmlNode p = parent(v, rnd);
                p.appendChild(fresh(p.getId(), step, rnd));
            },
            // 途中に挿入
            (v, rnd, step) -> {
                HtmlNode p = parent(v, rnd);
                List<HtmlNode> kids = new ArrayList<>(p.children);
                HtmlNode ref = kids.isEmpty() ? null : kids.get(rnd.nextInt(kids.size()));
                p.insertBefore(fresh(p.getId(), step, rnd), ref);
            },
            // 削除
            (v, rnd, step) -> {
                HtmlNode p = parent(v, rnd);
                List<HtmlNode> kids = new ArrayList<>(p.children);
                if (kids.size() > 2) p.removeChild(kids.get(rnd.nextInt(kids.size())));
            },
            // 置き換え（削除 + 挿入）
            (v, rnd, step) -> {
                HtmlNode p = parent(v, rnd);
                List<HtmlNode> kids = new ArrayList<>(p.children);
                if (kids.size() > 2) {
                    HtmlNode victim = kids.get(rnd.nextInt(kids.size()));
                    p.insertBefore(fresh(p.getId(), step, rnd), victim);
                    p.removeChild(victim);
                }
            },
            // 連続で数個追加
            (v, rnd, step) -> {
                HtmlNode p = parent(v, rnd);
                int count = 1 + rnd.nextInt(3);
                for (int i = 0; i < count; i++) p.appendChild(fresh(p.getId(), step * 10 + i, rnd));
            },
            // 同じフレームの別の変更（文字・class）と重ねる
            (v, rnd, step) -> {
                HtmlNode p = parent(v, rnd);
                if (!p.children.isEmpty()) {
                    HtmlNode k = p.children.get(rnd.nextInt(p.children.size()));
                    if (k instanceof HtmlElement el && el.tag.equals("div")) el.toggleClass("hot");
                }
                p.appendChild(fresh(p.getId(), step, rnd));
            },
    };

    private static List<DisplayItem> flush(StandaloneView v) {
        v.ui().flushInvalidation();
        return v.ui().renderCache();
    }

    /** 記憶の無いビルダーが、今の箱の木から作る表示リスト。 */
    private static List<DisplayItem> freshList(StandaloneView v) {
        UiView ui = v.ui();
        return new com.nishiyu.enderframelib.core.paint.DisplayListBuilder(420, 520, ui.document().sheet.keyframes, ui.scrollState()).render(ui.layoutRoot(), false);
    }

    private static void sameItems(List<DisplayItem> expected, List<DisplayItem> actual, String step) {
        if (expected.size() != actual.size()) {
            System.out.printf("SIZE MISMATCH %s: exp=%d act=%d%n", step, expected.size(), actual.size());
            int minLen = Math.min(expected.size(), actual.size());
            for (int i = 0; i < minLen; i++) {
                if (!expected.get(i).equals(actual.get(i))) {
                    System.out.printf("FIRST MISMATCH at %d: exp=%s act=%s%n", i, expected.get(i).id(), actual.get(i).id());
                    break;
                }
            }
            if (expected.size() > actual.size()) {
                System.out.println("MISSING in actual: " + expected.subList(actual.size(), expected.size()));
            } else {
                System.out.println("EXTRA in actual: " + actual.subList(expected.size(), actual.size()));
            }
        }
        assertEquals(expected.size(), actual.size(), step + ": 項目の数");
        for (int i = 0; i < expected.size(); i++) {
            try {
                assertEquals(expected.get(i), actual.get(i), step + ": 項目 " + i);
            } catch (AssertionError e) {
                System.out.println("ChildPatchTest MISMATCH at " + i + " step=" + step + ":\n  exp: " + expected.get(i) + "\n  act: " + actual.get(i));
                throw e;
            }
        }
    }

    private void run(String sep, boolean structural, int seeds) {
        for (long seed = 1; seed <= seeds; seed++) {
            try (StandaloneView v = new StandaloneView(html(sep, structural), 420, 520, FontResolver.FALLBACK)) {
                sameItems(freshList(v), flush(v), "seed " + seed + " 初回");
                Random r = new Random(seed);
                for (int step = 0; step < 50; step++) {
                    int m = new Random(seed * 1000 + step).nextInt(MUTATIONS.length);
                    MUTATIONS[m].apply(v, r, step);
                    List<DisplayItem> act = flush(v);
                    String where = "seed " + seed + " 手順 " + step + " (変更 " + m + ")";
                    sameItems(freshList(v), act, where);
                    assertNull(FrameOracle.firstDifference(v), where + ": 箱の木");
                }
            }
        }
    }

    @Test
    void randomInsertAndRemoveMatchesFullRelayout() {
        run("", false, 10);
    }

    @Test
    void randomInsertAndRemoveWithWhitespaceBetweenChildrenMatchesFullRelayout() {
        run("\n  ", false, 10);
    }

    @Test
    void siblingRulesMakeThePatchStepBackAndStillMatch() {
        run("", true, 4);
    }

}
