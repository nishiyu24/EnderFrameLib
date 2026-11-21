package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.DisplayListBuilder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 経路の予測テスト（ENGINE_REARCHITECTURE_PLAN C7 の試作）。変更の前後の木の差から、箱ごとに「理想の再利用の段」を
 * 記憶とは独立に予測し、実際に選ばれた段〔{@code FragmentPaint} の観測口〕と突き合わせる。
 *
 * <p>段: 0 = 部分木ごと再利用、1 = 箱自身の項目を再利用して子だけ組み直す、2 = 作り直す。</p>
 * <ul>
 *   <li><b>不健全</b>〔実際が予測より浅い〕: 変わったはずの箱を再利用している。古い項目を出す不具合。0 件でなければならない。</li>
 *   <li><b>取りこぼし</b>〔実際が予測より深い〕: 変わっていない箱を作り直している。効率の損。件数を表で見張る。</li>
 * </ul>
 * 予測は、箱の入力（スタイルの宣言・位置と大きさ・属性の版・文字の断片・子の並び）の前後比較だけで決める。祖先のスタイルは入力に含めない
 * 〔子が祖先から読むものは、鍵の位置・クリップ・動く祖先に入っており、継承は子自身のスタイルの更新で伝わる〕。
 */
class PathPredictionTest {
    private static final String HTML = """
            <style>
              body { margin: 0; font-size: 14px }
              .card { padding: 6px; margin: 3px; border: 1px solid #888; background: #eef }
              .hot { background: #fdd; border-radius: 12px }
              .big { font-size: 20px }
              .a { width: 120px }
              .c { color: #080 }
              .scroller { height: 60px; overflow: auto }
              .rel { position: relative; top: 2px }
            </style>
            <div id="l1">
              <div class="card" id="i1">one <span id="s1">v1</span></div>
              <div class="card a" id="i2">two <b id="b1">bold</b> and more words to wrap around a little</div>
              <div class="card" id="i3"><div id="t1">t</div><div id="t2">u</div></div>
              <div class="card scroller" id="sc"><div id="r1">row 1</div><div id="r2">row 2</div><div id="r3">row 3</div><div id="r4">row 4</div></div>
              <div class="card rel" id="i5">five</div>
              <div class="card" id="i6">six <i id="e1">it</i></div>
            </div>
            """;
    /** 取りこぼしの上限（40 種 × 40 手）。実測 1,104〔C7 ② の前は 17,102。C7 ④ で属性の版を署名へ足して 1,049 → 1,104〕。 */
    private static final long MISSED_CEILING = 1_200;
    private static final String[] CLASSES = {"", "card", "card hot", "card big", "card c", "card a", "rel", "big c"};
    private static final String[] STYLES = {"", "width:80px", "color:red", "padding:10px", "font-size:18px", "margin-left:7px"};

    /** 箱の入力の写し。 */
    private record Snap(LayoutBox parent, ComputedStyle style, float[] nums, int attr, String frags, LayoutBox[] kids) {}

    private static Snap snap(LayoutBox b) {
        float[] n = {b.x(), b.y(), b.w(), b.h(), b.pL(), b.pT(), b.pR(), b.pB(), b.bL(), b.bT(), b.bR(), b.bB(), b.lineHeightPx(), b.scrollWidth(), b.scrollHeight()};
        StringBuilder f = new StringBuilder();
        for (LayoutBox.InlineFragment x : b.inlineFragments) f.append(x.x).append(',').append(x.y).append(',').append(x.w).append(',').append(x.h).append(',').append(x.text).append(';');
        return new Snap(b.parent, b.style, n, b.node.attrVersion(), f.toString(), b.children.toArray(new LayoutBox[0]));
    }

    private static void collect(LayoutBox b, Map<LayoutBox, Snap> out) {
        out.put(b, snap(b));
        for (LayoutBox c : b.children) collect(c, out);
    }

    private static boolean sameStyle(ComputedStyle a, ComputedStyle b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.fontSizePx() == b.fontSizePx() && a.rootFontSizePx() == b.rootFontSizePx() && ComputedStyle.sameDeclarations(a, b);
    }

    private static String why(Snap p, Snap c) {
        if (p == null) return "new";
        StringBuilder sb = new StringBuilder();
        if (p.parent != c.parent) sb.append("parent ");
        if (!sameStyle(p.style, c.style)) sb.append("style ");
        if (!java.util.Arrays.equals(p.nums, c.nums)) sb.append("nums ").append(java.util.Arrays.toString(p.nums)).append("->").append(java.util.Arrays.toString(c.nums)).append(' ');
        if (p.attr != c.attr) sb.append("attr ");
        if (!p.frags.equals(c.frags)) sb.append("frags ").append(p.frags).append("->").append(c.frags).append(' ');
        if (!java.util.Arrays.equals(p.kids, c.kids)) sb.append("kids ");
        return sb.length() == 0 ? "ancestorStyle" : sb.toString();
    }

    private static boolean sameOwn(Snap p, Snap c) {
        return p.parent == c.parent && sameStyle(p.style, c.style) && java.util.Arrays.equals(p.nums, c.nums) && p.attr == c.attr
                && p.frags.equals(c.frags) && java.util.Arrays.equals(p.kids, c.kids);
    }

    /** 予測: 箱自身の入力（と祖先のスタイル）が変わった → 2、部分木だけが変わった → 1、何も → 0。 */
    private static int predict(LayoutBox b, Map<LayoutBox, Snap> before, Map<LayoutBox, Snap> after, Map<LayoutBox, Integer> memo) {
        Integer known = memo.get(b);
        if (known != null) return known;
        int level = 0;
        Snap pb = before.get(b), pa = after.get(b);
        // 無名の箱は自分の項目を持たない〔PaintOrder.hasSelf〕ので、自分の入力の変化は描画に出ない（子の変化だけが部分木へ伝わる）
        boolean own = pb == null || (!b.isAnonymous && !sameOwn(pb, pa));
        if (own) level = 2;
        for (LayoutBox c : b.children) {
            int cl = predict(c, before, after, memo);
            if (level < 1 && cl > 0) level = 1;
        }
        memo.put(b, level);
        return level;
    }

    private static int visited(LayoutBox b, Map<LayoutBox, Integer> actual) {
        return actual.getOrDefault(b, -1);
    }

    @AfterEach
    void clear() {
        MutationScenario.STEP_HOOK = null;
    }

    private static final class Tally {
        int boxes, hitOk, unsound, missed;
        final List<String> unsoundLog = new ArrayList<>();
        final Map<String, Integer> missedBy = new java.util.TreeMap<>();
    }

    private Tally run(long seeds, int steps) {
        Tally t = new Tally();
        for (long seed = 1; seed <= seeds; seed++) {
            Map<LayoutBox, Snap>[] prev = new Map[1];
            List<Map<LayoutBox, Integer>> passes = new ArrayList<>();
            boolean[] armed = {false};
            long[] epochBefore = {0};
            long sd = seed;
            MutationScenario.STEP_HOOK = (step, view) -> {
                LayoutBox root = view.ui().layoutRoot();
                Map<LayoutBox, Snap> now = new IdentityHashMap<>();
                collect(root, now);
                // 1 手に描画が 2 回以上ある手は、記憶の基準が手の前の木と一致しないので比べない
                if (prev[0] != null && passes.size() == 1) {
                    String what = MutationScenario.LOG.isEmpty() ? "(time only)" : MutationScenario.LOG.get(MutationScenario.LOG.size() - 1);
                    Map<LayoutBox, Integer> actual = passes.get(0);
                    if (!what.startsWith("resize") && !what.startsWith("(time")) {
                        Map<LayoutBox, Integer> memo = new IdentityHashMap<>();
                        predict(root, prev[0], now, memo);
                        for (Map.Entry<LayoutBox, Integer> e : memo.entrySet()) {
                            LayoutBox b = e.getKey();
                            int want = e.getValue(), got = visited(b, actual);
                            if (got < 0) continue; // 部分木ごと再利用された祖先の下（祖先が判定される）か、描かない箱
                            t.boxes++;
                            if (got < want) {
                                t.unsound++;
                                if (t.unsoundLog.size() < 8) t.unsoundLog.add("seed " + sd + " step " + step + " (" + what + ") " + b.node.tag + "#" + b.node.getId() + " 予測=" + want + " 実際=" + got + " why=" + why(prev[0].get(b), now.get(b)));
                            } else if (got > want) {
                                t.missed++;
                                t.missedBy.merge(what.split(" ")[0] + ":" + want + "→" + got + (want == 0 ? (b.subtreePaintStamp() > epochBefore[0] ? " touched" : " untouched") : ""), 1, Integer::sum);
                            } else if (got == 0) {
                                t.hitOk++;
                            }
                        }
                    }
                }
                prev[0] = now;
                epochBefore[0] = LayoutBox.paintEpoch();
                passes.clear();
                if (!armed[0]) {
                    armed[0] = true;
                    DisplayListBuilder b = view.ui().paintBuilder();
                    b.setPathRecorder((box, level) -> {
                        if (box == null) passes.add(new IdentityHashMap<>());
                        else if (!passes.isEmpty()) passes.get(passes.size() - 1).put(box, level);
                    });
                }
            };
            MutationScenario.run(HTML, CLASSES, STYLES, seed, 40, false);
        }
        return t;
    }

    @Test
    void chosenPathsAreNeverShallowerThanPredicted() {
        Tally t = run(40, 40);
        System.out.println("[path-prediction] boxes=" + t.boxes + " unsound=" + t.unsound + " missed=" + t.missed + " hit=" + t.hitOk + " missedBy=" + t.missedBy);
        assertTrue(t.boxes > 500, "比べた箱が少なすぎる: " + t.boxes);
        // 取りこぼし（変わっていない箱の作り直し）の上限。残りは、位置・大きさの変化の伝わり方・クリップの実体の違い〔予測は見ていない〕・属性の版。
        // 減ったら上限を下げる（増やさない）
        assertTrue(t.missed <= MISSED_CEILING, "取りこぼしが増えた: " + t.missed + " > " + MISSED_CEILING);
        assertTrue(t.unsound == 0, "変わった箱を再利用している（古い項目）: " + t.unsoundLog);
    }
}
