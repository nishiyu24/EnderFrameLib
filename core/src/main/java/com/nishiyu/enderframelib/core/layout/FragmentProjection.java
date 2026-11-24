package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.box.BoxKind;
import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;
import com.nishiyu.enderframelib.core.layout.fragment.BoxGeometry;
import com.nishiyu.enderframelib.core.layout.fragment.InlineFlow;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutFragment;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutSession;
import com.nishiyu.enderframelib.core.layout.fragment.LineFragment;
import com.nishiyu.enderframelib.core.layout.fragment.PlacedFragment;
import com.nishiyu.enderframelib.core.text.TextShaper;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontStyle;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>M2b の一時アダプタ</b>（計画書 §3.4.1 M2b-0。M2b-6 で必ず削除する）。新経路の不変の断片木（{@code LayoutFragment}）を、
 * 旧経路の描画（{@code DisplayListBuilder}）が読む {@link LayoutBox} の木へ写す。旧エンジンの測定・配置は一切呼ばず、
 * 幾何（親相対の位置・寸法・縁）と行の文字片を写すだけ。
 *
 * <ul>
 *   <li>ブロック・flex の断片 → 親相対の {@link LayoutBox}（{@code x()/y()} は祖先の和なので、親の border-box 基準の位置を入れる）。</li>
 *   <li>インラインの部分木 → 同じブロックの下の入れ子の {@link LayoutBox}（インライン要素は座標の原点に足されない）。</li>
 *   <li>行の文字片 → 文字の節の {@link LayoutBox.InlineFragment}（最も近いブロックの border-box 基準。ベースラインは書体の
 *       ascent/descent と半分の行間から求める）。</li>
 * </ul>
 *
 * <p>まだ写さないもの（M2b の各段で足す）: インライン要素の背景・枠線の断片・{@code ::first-line}・スクロール量。</p>
 */
public final class FragmentProjection {
    private final LayoutSession session;

    private FragmentProjection(LayoutSession session) {
        this.session = session;
    }

    /** 断片木の根から、描画用の {@link LayoutBox} の木を作る。 */
    public static LayoutBox project(LayoutFragment root, LayoutSession session) {
        FragmentProjection p = new FragmentProjection(session);
        LayoutBox lb = p.block(root, 0, 0);
        lb.x = 0;
        lb.y = 0;
        return lb;
    }

    /** ブロック水準の断片 1 つ（{@code x}, {@code y} は親の border-box 基準）。 */
    private LayoutBox block(LayoutFragment f, float x, float y) {
        BoxNode b = f.box;
        LayoutBox lb = new LayoutBox(b.node, new ComputedStyle(b.style));
        lb.x = x;
        lb.y = y;
        lb.w = f.width;
        lb.h = f.height;
        BoxGeometry g = new BoxGeometry(b.style, f.space.availableWidth, session);
        lb.mT = g.mT; lb.mR = g.mR; lb.mB = g.mB; lb.mL = f.marginLeft;
        lb.pT = g.pT; lb.pR = g.pR; lb.pB = g.pB; lb.pL = g.pL;
        lb.bT = g.bT; lb.bR = g.bR; lb.bB = g.bB; lb.bL = g.bL;
        lb.scrollWidth = f.width;
        lb.scrollHeight = f.height;
        lb.lineHeightPx = InlineFlow.lineHeight(b.style, session.face(b.style));
        if (b.kind == BoxKind.TEXT) { // flex の項目になった文字の節: 文字片を自分に持つ
            Map<BoxNode, LayoutBox> boxes = new IdentityHashMap<>();
            boxes.put(b, lb);
            lines(f.lines, f.insetLeft, f.insetTop, boxes, new IdentityHashMap<>());
            return lb;
        }
        float cx = f.insetLeft, cy = f.insetTop;
        Map<BoxNode, LayoutBox> textBoxes = new IdentityHashMap<>();
        Map<BoxNode, List<LayoutBox>> parents = new IdentityHashMap<>();
        if (f.lines != null) {
            inlineChildren(b, lb, textBoxes, parents, new ArrayList<>());
            lines(f.lines, cx, cy, textBoxes, parents);
        }
        if (f.flow != null) {
            f.flow.forEach((child, x1, y1) -> {
                if (child.box != null) {
                    lb.addChild(block(child, cx + x1, cy + y1));
                } else { // 混在の流れの匿名の塊: 中のインラインの子をこの箱の下へ足し、行を写す
                    Map<BoxNode, LayoutBox> anon = new IdentityHashMap<>();
                    Map<BoxNode, List<LayoutBox>> anonParents = new IdentityHashMap<>();
                    for (LineFragment l : child.lines) {
                        for (LineFragment.Piece p : l.pieces()) collectAncestors(b, p.text(), lb, anon, anonParents);
                    }
                    lines(child.lines, cx + x1, cy + y1, anon, anonParents);
                }
            });
        }
        for (LayoutBox c : lb.children) {
            if (c.display != null && c.display.isInlineLevel() && !c.node.isPseudoNode && c.inlineFragments != null) fitInline(c);
        }
        if (f.oof != null) { // 流れの外の断片は包含ブロックの子として後ろに置く（描画の順も位置指定の子が後）
            for (PlacedFragment p : f.oof) lb.addChild(block(p.frag(), p.x(), p.y()));
        }
        return lb;
    }

    /** インライン水準の子孫（文字の節・インライン要素）の箱を、DOM の入れ子のまま {@code parent} の下へ足す。 */
    private void inlineChildren(BoxNode owner, LayoutBox parent, Map<BoxNode, LayoutBox> textBoxes,
                                Map<BoxNode, List<LayoutBox>> parents, List<LayoutBox> chain) {
        for (int i = 0, n = owner.children.size(); i < n; i++) {
            BoxNode c = owner.children.get(i);
            if (c.kind == BoxKind.NONE) continue;
            LayoutBox lb = new LayoutBox(c.node, new ComputedStyle(c.style));
            lb.lineHeightPx = InlineFlow.lineHeight(c.style, session.face(c.style));
            parent.addChild(lb);
            if (c.kind == BoxKind.TEXT) {
                textBoxes.put(c, lb);
                parents.put(c, new ArrayList<>(chain));
            } else {
                chain.add(0, lb);
                inlineChildren(c, lb, textBoxes, parents, chain);
                chain.remove(0);
            }
        }
    }

    /** 混在の流れの文字の節 {@code text} とそのインラインの祖先の箱を（まだ無ければ）{@code owner} の下に作る。 */
    private void collectAncestors(BoxNode owner, BoxNode text, LayoutBox ownerBox, Map<BoxNode, LayoutBox> boxes,
                                  Map<BoxNode, List<LayoutBox>> parents) {
        if (boxes.containsKey(text)) return;
        List<BoxNode> path = new ArrayList<>();
        if (!pathTo(owner, text, path)) return;
        LayoutBox parent = ownerBox;
        List<LayoutBox> chain = new ArrayList<>();
        for (int i = 1; i < path.size(); i++) {
            BoxNode n = path.get(i);
            LayoutBox lb = boxes.get(n);
            if (lb == null) {
                lb = new LayoutBox(n.node, new ComputedStyle(n.style));
                lb.lineHeightPx = InlineFlow.lineHeight(n.style, session.face(n.style));
                parent.addChild(lb);
                boxes.put(n, lb);
            }
            if (n != text) chain.add(0, lb);
            parent = lb;
        }
        parents.put(text, chain);
    }

    private static boolean pathTo(BoxNode from, BoxNode target, List<BoxNode> path) {
        path.add(from);
        if (from == target) return true;
        if (from.kind == BoxKind.INLINE || path.size() == 1) {
            for (int i = 0, n = from.children.size(); i < n; i++) {
                if (pathTo(from.children.get(i), target, path)) return true;
            }
        }
        path.remove(path.size() - 1);
        return false;
    }

    /**
     * インラインの箱（文字の節・インライン要素）の幾何を、自分と子孫の文字片の外接矩形にする（描画の間引きが空の箱を飛ばすため）。
     * インラインの箱は座標の原点に足されないので、位置は最も近いブロックの border-box 基準の値をそのまま入れる。
     */
    private static float[] fitInline(LayoutBox b) {
        float x0 = Float.POSITIVE_INFINITY, y0 = Float.POSITIVE_INFINITY, x1 = Float.NEGATIVE_INFINITY, y1 = Float.NEGATIVE_INFINITY;
        for (LayoutBox.InlineFragment f : b.inlineFragments) {
            x0 = Math.min(x0, f.x); y0 = Math.min(y0, f.y); x1 = Math.max(x1, f.x + f.w); y1 = Math.max(y1, f.y + f.h);
        }
        for (LayoutBox c : b.children) {
            float[] r = fitInline(c);
            if (r == null) continue;
            x0 = Math.min(x0, r[0]); y0 = Math.min(y0, r[1]); x1 = Math.max(x1, r[2]); y1 = Math.max(y1, r[3]);
        }
        if (x0 > x1) return null;
        b.x = 0; // 旧経路と同じく、インラインの箱は原点に置き寸法だけ外接矩形にする（文字片は最も近いブロック基準）
        b.y = 0;
        b.w = x1 - x0;
        b.h = y1 - y0;
        return new float[]{x0, y0, x1, y1};
    }

    /** 行の文字片を、文字の節の箱の {@link LayoutBox.InlineFragment} にする（{@code ox}, {@code oy} はブロックの border-box 基準の行の原点）。 */
    private void lines(LineFragment[] lines, float ox, float oy, Map<BoxNode, LayoutBox> boxes, Map<BoxNode, List<LayoutBox>> parents) {
        if (lines == null) return;
        for (LineFragment l : lines) {
            float lineBase = 0;
            for (LineFragment.Piece p : l.pieces()) lineBase = Math.max(lineBase, runBaseline(p.text().style));
            for (LineFragment.Piece p : l.pieces()) {
                LayoutBox tb = boxes.get(p.text());
                if (tb == null) continue;
                Style st = p.text().style;
                FontFace face = session.face(st);
                FontStyle fs = st.facts.fontStyle;
                float runH = InlineFlow.lineHeight(st, face), runBase = runBaseline(st);
                float fy = LayoutUnit.snap(oy + l.y() + (lineBase - runBase));
                List<LayoutBox> chain = parents.getOrDefault(p.text(), List.of());
                // 旧経路と同じく折り返し候補ごと（後ろの空白を含む）の断片にする。最後の語だけは片の幅の残りで終わらせる
                String text = p.string();
                float x = ox + l.x() + p.x(), end = x + p.width();
                for (int i = 0, n = text.length(); i < n; ) {
                    int stop = InlineFlow.nextBreak(text, i, n, st.facts.wordBreak); // 折り返し候補（UAX #14）ごと
                    String word = text.substring(i, stop);
                    float w = stop == n ? end - x : face.advance(word, fs);
                    tb.addFragment(x, fy, w, runH, word, runBase, face.descent(fs), chain, true, true);
                    x += w;
                    i = stop;
                }
            }
        }
    }

    /** 文字の並びの上端からベースラインまで（半分の行間 + ascent）。 */
    private float runBaseline(Style st) {
        FontFace face = session.face(st);
        FontStyle fs = st.facts.fontStyle;
        float asc = face.ascent(fs), desc = face.descent(fs);
        return TextShaper.leadingAbove(InlineFlow.lineHeight(st, face), asc, desc) + asc;
    }
}
