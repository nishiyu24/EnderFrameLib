package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
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
        UiView.childPatchEnabled = true;
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
                for (int i = 0; i < 1 + rnd.nextInt(3); i++) p.appendChild(fresh(p.getId(), step * 10 + i, rnd));
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
        UiView.childPatchEnabled = patch;
        v.ui().flushInvalidation();
        return v.ui().renderCache();
    }

    private static void sameItems(List<DisplayItem> expected, List<DisplayItem> actual, String step) {
        assertEquals(expected.size(), actual.size(), step + ": 項目の数");
        for (int i = 0; i < expected.size(); i++) assertEquals(expected.get(i), actual.get(i), step + ": 項目 " + i);
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
                UiView.childPatchEnabled = true; // 変更の通知は有効なまま、パッチする側だけを flush で切り替える
                MUTATIONS[m].apply(off, rOff, step);
                MUTATIONS[m].apply(on, rOn, step);
                sameItems(flush(off, false), flush(on, true), "seed " + seed + " 手順 " + step + " (変更 " + m + ")");
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

    /** 行の高さが整数なら、後続の行は下へ整数ぶんずれるだけなので、前回の項目を厳密に平行移動して使い回す。 */
    @Test
    void integerRowHeightsReuseTranslatedItemsExactly() {
        String doc = "<style>.row { height: 24px; border-bottom: 1px solid #ccc; line-height: 20px; font-size: 16px }</style><div id=list>"
                + "<div class=row>x</div>".repeat(60) + "</div>";
        StandaloneView off = new StandaloneView(doc, 420, 2000, FontResolver.FALLBACK);
        StandaloneView on = new StandaloneView(doc, 420, 2000, FontResolver.FALLBACK);
        sameItems(flush(off, false), flush(on, true), "初回");
        long translatedBefore = com.nishiyu.enderframelib.core.paint.DisplayListBuilder.STAT_TRANSLATED.sum();
        long hitsBefore = UiView.CHILD_PATCH_HITS.get();
        Random r = new Random(7);
        for (int step = 0; step < 20; step++) {
            int at = r.nextInt(30);
            for (StandaloneView v : new StandaloneView[]{off, on}) {
                UiView.childPatchEnabled = true;
                HtmlNode list = v.root().getNodeById("list");
                HtmlElement row = new HtmlElement("div");
                row.setAttribute("class", "row");
                row.updateTextContent("new " + step);
                list.insertBefore(row, list.children.get(at));
            }
            sameItems(flush(off, false), flush(on, true), "挿入 " + step);
        }
        assertTrue(UiView.CHILD_PATCH_HITS.get() - hitsBefore >= 15, "局所パッチが使われている");
        assertTrue(com.nishiyu.enderframelib.core.paint.DisplayListBuilder.STAT_TRANSLATED.sum() - translatedBefore > 200,
                "後続の行の項目を平行移動して使い回している");
    }

    @Test
    void siblingRulesMakeThePatchStepBackAndStillMatch() {
        run("", true, 4, false);
    }

    /**
     * 文字の箱は自分の位置を持たず断片だけが置かれるので、後続の行が整数ぶんずれるときも箱は動かず断片だけが動く。
     * その箱が毎回「不一致」で描き直しにならず、断片の動きで平行移動して使い回されること（結果は描き直しと完全に同じ）。
     * 折り返す長い文・入れ子の span・複数の文字の塊を含む行で確かめる。
     */
    @Test
    void textBoxesOfFollowingRowsAreTranslatedNotRedrawn() {
        var sb = new StringBuilder("<style>.row { height: 48px; border-bottom: 1px solid #ccc; line-height: 20px; font-size: 16px; padding: 0 4px }</style><div id=list>");
        for (int i = 0; i < 120; i++) {
            sb.append("<div class=row>Row ").append(i).append(" some wrapped description text that goes on and on <span>inner ")
                    .append(i).append("</span> tail</div>");
        }
        sb.append("</div>");
        String doc = sb.toString();
        StandaloneView off = new StandaloneView(doc, 300, 2000, FontResolver.FALLBACK);
        StandaloneView on = new StandaloneView(doc, 300, 2000, FontResolver.FALLBACK);
        sameItems(flush(off, false), flush(on, true), "初回");
        var stMiss = com.nishiyu.enderframelib.core.paint.DisplayListBuilder.STAT_MISS;
        var stTr = com.nishiyu.enderframelib.core.paint.DisplayListBuilder.STAT_TRANSLATED;
        long miss = 0, translated = 0; // 数えるのは局所パッチ側（on）の更新だけ。off は毎回の全再構築の基準
        int steps = 20;
        Random r = new Random(11);
        for (int step = 0; step < steps; step++) {
            int at = 10 + r.nextInt(20);
            boolean remove = step % 3 == 2;
            for (StandaloneView v : new StandaloneView[]{off, on}) {
                UiView.childPatchEnabled = true;
                HtmlNode list = v.root().getNodeById("list");
                if (remove) {
                    list.removeChild(list.children.get(at));
                } else {
                    HtmlElement row = new HtmlElement("div");
                    row.setAttribute("class", "row");
                    row.updateTextContent("new " + step);
                    list.insertBefore(row, list.children.get(at));
                }
            }
            List<DisplayItem> expected = flush(off, false);
            long m0 = stMiss.sum(), t0 = stTr.sum();
            List<DisplayItem> actual = flush(on, true);
            miss += stMiss.sum() - m0;
            translated += stTr.sum() - t0;
            sameItems(expected, actual, "手順 " + step);
        }
        // 1 更新で動く後続の行は 100 以上。文字の箱が描き直しになると、不一致が 1 更新あたり 100 を超える
        assertTrue(translated > steps * 100L, "後続の行の項目を平行移動して使い回している: " + translated);
        assertTrue(miss < steps * 30L, "文字の箱が不一致で描き直しになっていない(1 更新 30 未満): " + miss);
    }
}
