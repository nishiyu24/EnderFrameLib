package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.kernel.Clock;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.tree.PaintBox;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;

/**
 * フレームのオラクル（計画書 K0 ③・設計書 {@code DESIGN_INCREMENTAL_KERNEL.md} §3.5・K3）: 増分で作った今の面を、<b>最初から作った独立の面</b>と全体で比べる。
 *
 * <p>今の面の DOM を構造のまま複製し（クラス・style 属性・文字・要素の増減はすべて載る）、記憶・箱の木・表示リストが何も無い新しい
 * {@link StandaloneView} へ入れて、同じ大きさ・同じ時刻に描き、箱の木（種類・位置・大きさ・子の数。任意でスクロール範囲）を行きがけ順に比べる。
 * 部分木だけを置き直す検証（{@code PlacementVerifier} など）と違い、<b>無効化の漏れ</b>〔レイアウトが走るべきなのに走らなかった〕も、
 * 保持の誤りも、同じ形（今の面と最初から作った面の違い）で見つかる。画面全体を別に作るので、断片の管理の人工物による偽陽性も出ない。</p>
 *
 * <p><b>使える場面</b>: 結果が時間と対話の履歴に依らない場面。新しい面は次を持たない — ホバー／押下／フォーカス・スクロール量・
 * 走っているトランジション／アニメーション〔{@code transition: width} は途中の幅をレイアウトに使うので、履歴のある面とは比べられない〕。
 * 呼ぶ側はそれらが結果に効かない場面（または落ち着いた後）で使う。表示項目は比べない（途中の色・半径などが時間で動くため）。
 * 箱の幾何は表示項目の元なので、箱が合えば項目の位置・大きさも合う。{@code display:none} の部分木は観測できないので、種類だけ比べて降りない。</p>
 *
 * <p>HTML に書き出して読み直す方式にしない: パーサは {@code table > div} などの不正な入れ子を直し、DOM の API で作った木と別の木になる
 * （実測で、書き出し方式は半数の手で偽陽性になった）。</p>
 */
public final class FrameOracle {
    private FrameOracle() { }

    /** 今の面と最初から作った面の違い。 */
    public record Difference(String where, String live, String fresh) {
        @Override public String toString() { return where + "（今の面 " + live + " ≠ 最初から作った面 " + fresh + "）"; }
    }

    /** 比べる範囲: 位置・大きさ・子の数は常に比べる。スクロール範囲も既定で比べる。 */
    public enum Scope { GEOMETRY, GEOMETRY_AND_SCROLL_EXTENTS }

    /** {@link Scope#GEOMETRY_AND_SCROLL_EXTENTS} で比べる。 */
    public static Difference firstDifference(StandaloneView live) {
        return firstDifference(live, Scope.GEOMETRY_AND_SCROLL_EXTENTS);
    }

    /** {@code live} を最初から作った独立の面と比べ、最初に見つかった違い（無ければ null）を返す。{@code live} は触らない。 */
    public static Difference firstDifference(StandaloneView live, Scope scope) {
        boolean scroll = scope == Scope.GEOMETRY_AND_SCROLL_EXTENTS;
        try (StandaloneView fresh = freshCopy(live); var be = new MutationScenario.NoDraw()) {
            fresh.frame(nowOf(live), be, true);
            List<String> a = boxes(live.ui().boxRoot(), scroll);
            List<String> b = boxes(fresh.ui().boxRoot(), scroll);
            for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
                if (!a.get(i).equals(b.get(i))) return new Difference("箱 " + i, a.get(i), b.get(i));
            }
            if (a.size() != b.size()) return new Difference("箱の数", String.valueOf(a.size()), String.valueOf(b.size()));
            return null;
        }
    }

    /** {@code live} の DOM を構造のまま複製した、記憶の無い新しい面。 */
    static StandaloneView freshCopy(StandaloneView live) {
        StandaloneView fresh = new StandaloneView("", live.width(), live.height(), FontResolver.FALLBACK);
        fresh.clock(new Clock.Manual());
        if (!(live.root().cloneNode(true) instanceof HtmlDocument copy)) throw new IllegalStateException("root が文書でない");
        adopt(copy, copy);
        fresh.ui().installDocument(copy, live.ui().document().authorCss(), live.width(), live.height(), false);
        return fresh;
    }

    private static void adopt(HtmlNode n, HtmlDocument doc) {
        n.ownerDocument = doc;
        for (HtmlNode c : n.children) adopt(c, doc);
    }

    private static long nowOf(StandaloneView v) {
        try {
            var f = StandaloneView.class.getDeclaredField("lastNow");
            f.setAccessible(true);
            return f.getLong(v);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 箱の木を行きがけ順に 1 行ずつ（種類・位置・大きさ・子の数。任意でスクロール範囲）。 */
    static List<String> boxes(PaintBox root, boolean scrollExtents) {
        List<String> out = new ArrayList<>();
        walk(root, scrollExtents, out);
        return out;
    }

    private static void walk(PaintBox b, boolean scrollExtents, List<String> out) {
        // display:none の部分木は描かれず、位置・大きさは観測できない（今の面は隠す前の古い値を持ち、最初から作った面は置かない）。種類だけ比べて降りない
        if (b.style() != null && "none".equals(b.style().typed().box.display)) {
            out.add((b.node == null ? "(anon)" : b.node.tag) + " display:none");
            return;
        }
        int kids = 0;
        for (PaintBox c : b.paintChildren()) if (!invisibleText(c)) kids++;
        out.add((b.node == null ? "(anon)" : b.node.tag) + "@" + b.x() + "," + b.y() + " " + b.w() + "x" + b.h()
                + (scrollExtents ? " scroll " + b.scrollWidth() + "x" + b.scrollHeight() : "") + " kids " + kids);
        for (PaintBox c : b.paintChildren()) if (!invisibleText(c)) walk(c, scrollExtents, out);
    }

    /**
     * 畳まれた空白だけの文字の箱（断片なし・幅 0）。何も描かず、スクロール範囲にも入らない。最初から作った面は子が空白だけの
     * ブロックにこの箱を残し、増分で最後のブロックを消した面は残さない（箱の木の作り方の差で、観測できる差ではない）ので数えない。
     */
    private static boolean invisibleText(PaintBox c) {
        return c.node != null && c.node.nodeType == com.nishiyu.enderframelib.core.dom.node.NodeType.TEXT
                && c.runs().isEmpty() && c.w() == 0 && (c.node.text == null || c.node.text.isBlank());
    }
}
