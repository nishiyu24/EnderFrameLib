package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子の増減の局所パッチ（{@link UiView.InvalidationLevel#CHILD_PATCH}）が、毎回全体を組み直す場合と同じ表示リストになること。
 * パッチが退くべき形（兄弟の規則・番号つきマーカー・文字の子）も同じ文書に混ぜ、退いたときも結果が同じであることを確かめる。
 */
class ChildPatchTest {

    @AfterEach
    void restore() {
        com.nishiyu.enderframelib.core.kernel.Shortcut.CHILD_PATCH.set(true);
    }

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

    private static List<DisplayItem> flush(StandaloneView v, boolean patch) {
        com.nishiyu.enderframelib.core.kernel.Shortcut.CHILD_PATCH.set(patch);
        v.ui().flushInvalidation();
        return v.ui().renderCache();
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

    private void run(String sep, boolean structural, int seeds, boolean expectHits) {
        long hitsBefore = UiView.CHILD_PATCH_HITS.get();
        java.util.Map<String, Integer> reasons = new java.util.TreeMap<>();
        for (long seed = 1; seed <= seeds; seed++) {
            StandaloneView off = new StandaloneView(html(sep, structural), 420, 520, FontResolver.FALLBACK);
            StandaloneView on = new StandaloneView(html(sep, structural), 420, 520, FontResolver.FALLBACK);
            sameItems(flush(off, false), flush(on, true), "seed " + seed + " 初回");
            Random rOff = new Random(seed), rOn = new Random(seed);
            for (int step = 0; step < 50; step++) {
                int m = new Random(seed * 1000 + step).nextInt(MUTATIONS.length);
                com.nishiyu.enderframelib.core.kernel.Shortcut.CHILD_PATCH.set(true); // 変更の通知は有効なまま、パッチする側だけを flush で切り替える
                MUTATIONS[m].apply(off, rOff, step);
                MUTATIONS[m].apply(on, rOn, step);
                List<DisplayItem> exp = flush(off, false);
                List<DisplayItem> act = flush(on, true);
                sameItems(exp, act, "seed " + seed + " 手順 " + step + " (変更 " + m + ")");
                reasons.merge(UiView.lastChildPatchRejection, 1, Integer::sum);
            }
        }
        if (expectHits) assertTrue(UiView.CHILD_PATCH_HITS.get() > hitsBefore, "局所パッチが実際に使われている: " + reasons);
        else assertEquals(hitsBefore, UiView.CHILD_PATCH_HITS.get(), "兄弟の規則があれば局所パッチは退く: " + reasons);
    }

    @Test
    void randomInsertAndRemoveMatchesFullRelayout() {
        run("", false, 10, true);
    }

    @Test
    void randomInsertAndRemoveWithWhitespaceBetweenChildrenMatchesFullRelayout() {
        run("\n  ", false, 10, true);
    }

    @Test
    void siblingRulesMakeThePatchStepBackAndStillMatch() {
        run("", true, 4, false);
    }

}
