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
 * <ul>
 *   <li>インライン要素の行ごとの箱（{@code LineFragment.Span}）→ その要素の箱の文字でない断片（背景・枠線。旧 {@code placeSpan} と同じ形）。</li>
 * </ul>
 *
 * <p>到達範囲（{@code scrollWidth} など）は新経路の {@code ScrollableOverflow} が断片木から求めた値を写す。
 * まだ写さないもの（M2b の各段で足す）: {@code ::first-line}・スクロール量。</p>
 */
public final class FragmentProjection {
    private final LayoutSession session;
    private final Map<BoxNode, com.nishiyu.enderframelib.core.dom.node.HtmlNode> synthetic = new IdentityHashMap<>();
    /** 断片から位置を決めて置いた箱（インラインの外接矩形合わせの対象外）。 */
    private final java.util.Set<LayoutBox> placed = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    /** 1 回の写しの間だけの断片の到達範囲の表（{@link com.nishiyu.enderframelib.core.layout.fragment.ScrollableOverflow}）。 */
    private final Map<LayoutFragment, float[]> overflow = new IdentityHashMap<>();
    /** 表の匿名の箱に付けた番号（持ち主ごとに一意の鍵を作る。旧 {@code BoxTreeBuilder.createAnonymous} と同じ形）。 */
    private final Map<com.nishiyu.enderframelib.core.dom.node.HtmlNode, Integer> anonymousCounts = new IdentityHashMap<>();

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
        LayoutBox lb = b.pseudo == BoxNode.Pseudo.ANONYMOUS ? LayoutBox.anonymous(nodeOf(b), new ComputedStyle(b.style))
                : new LayoutBox(nodeOf(b), new ComputedStyle(b.style));
        // 直交フローの包む箱: 旧描画がスタイルの transform で回す（旧経路の生成箱と同じ印）
        if (b.pseudo == BoxNode.Pseudo.ORTHOGONAL) lb.isVerticalWrapper = true;
        placed.add(lb);
        lb.x = x;
        lb.y = y;
        lb.w = f.width;
        lb.h = f.height;
        BoxGeometry g = new BoxGeometry(b.style, f.space.availableWidth, session);
        lb.mT = g.mT; lb.mR = g.mR; lb.mB = g.mB; lb.mL = f.marginLeft;
        lb.pT = g.pT; lb.pR = g.pR; lb.pB = g.pB; lb.pL = g.pL;
        lb.bT = g.bT; lb.bR = g.bR; lb.bB = g.bB; lb.bL = g.bL;
        tableEdges(lb, f);
        float[] ov = com.nishiyu.enderframelib.core.layout.fragment.ScrollableOverflow.of(f, overflow, session); // スクロールの範囲
        lb.scrollWidth = ov[0];
        lb.scrollHeight = ov[1];
        lb.descendantOverflowRight = ov[2];
        lb.descendantOverflowBottom = ov[3];
        lb.lineHeightPx = InlineFlow.lineHeight(b.style, session.face(b.style));
        if (b.kind == BoxKind.TEXT) { // flex の項目になった文字の節: 文字片を自分に持つ（文字の箱は原点に足されないので親の基準で置く）
            Map<BoxNode, LayoutBox> boxes = new IdentityHashMap<>();
            boxes.put(b, lb);
            lines(lb, f.lines, x + f.insetLeft, y + f.insetTop, boxes, new IdentityHashMap<>());
            return lb;
        }
        float cx = f.insetLeft, cy = f.insetTop;
        Map<BoxNode, LayoutBox> textBoxes = new IdentityHashMap<>();
        Map<BoxNode, List<LayoutBox>> parents = new IdentityHashMap<>();
        if (f.lines != null) {
            inlineChildren(b, lb, textBoxes, parents, new ArrayList<>(), f.width - lb.bL - lb.pL - lb.pR - lb.bR);
            lines(lb, f.lines, cx, cy, textBoxes, parents);
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
                        for (LineFragment.Span sp : l.spans()) collectAncestors(b, sp.box(), lb, anon, anonParents);
                    }
                    lines(lb, child.lines, cx + x1, cy + y1, anon, anonParents);
                }
            });
        }
        for (LayoutBox c : lb.children) { // 断片から置いた箱（原子・flex の項目に blockify したインライン要素）は位置を持つので触らない
            if (!placed.contains(c) && (c.isInlineFlow() || c.node.nodeType == com.nishiyu.enderframelib.core.dom.node.NodeType.TEXT)) fitInline(c);
        }
        if (f.oof != null) { // 流れの外の断片は包含ブロックの子として後ろに置く（描画の順も位置指定の子が後）
            for (PlacedFragment p : f.oof) {
                LayoutBox c = block(p.frag(), p.x(), p.y());
                // fieldset の描画される legend（FieldsetLegend が置いた）: 旧描画が枠線を切り欠く印
                c.isRenderedLegend = f.box != null && p.frag().box != null && p.frag().box.style.box.position != com.nishiyu.enderframelib.core.css.model.Position.ABSOLUTE
                        && p.frag().box.style.box.position != com.nishiyu.enderframelib.core.css.model.Position.FIXED
                        && com.nishiyu.enderframelib.core.dom.node.ElementTraits.isFieldset(f.box.node.tag)
                        && com.nishiyu.enderframelib.core.dom.node.ElementTraits.isLegend(p.frag().box.node.tag);
                lb.addChild(c);
            }
        }
        return lb;
    }

    /**
     * 表の箱の縁（M2b-4）: 枠線を重ねる表とセルは表が決めた使う枠線（旧描画は {@code collapsedBorders} で枠線の記憶を止める）で、表は
     * padding を持たない。行グループと行は旧経路と同じく縁を持たない（表が大きさだけを決め、自分では測られない）。
     */
    private void tableEdges(LayoutBox lb, LayoutFragment f) {
        BoxNode b = f.box;
        com.nishiyu.enderframelib.core.layout.fragment.TableBorders.Edges e = f.space.usedBorders();
        if (b.kind.tableContainer() && b.style.inherited.borderCollapse == com.nishiyu.enderframelib.core.css.model.CssKeywords.BorderCollapse.COLLAPSE) {
            e = com.nishiyu.enderframelib.core.layout.fragment.TableBorders.ofTable(b, session);
            lb.pT = lb.pR = lb.pB = lb.pL = 0;
        }
        if (e != null) {
            lb.bT = e.top(); lb.bR = e.right(); lb.bB = e.bottom(); lb.bL = e.left();
            lb.collapsedBorders = new float[]{e.top(), e.right(), e.bottom(), e.left()};
        }
        if (b.kind == BoxKind.TABLE_ROW || b.kind == BoxKind.TABLE_ROW_GROUP) {
            lb.mT = lb.mR = lb.mB = lb.mL = 0;
            lb.pT = lb.pR = lb.pB = lb.pL = 0;
            lb.bT = lb.bR = lb.bB = lb.bL = 0;
        }
    }

    /** インライン水準の子孫（文字の節・インライン要素）の箱を、DOM の入れ子のまま {@code parent} の下へ足す。 */
    private void inlineChildren(BoxNode owner, LayoutBox parent, Map<BoxNode, LayoutBox> textBoxes,
                                Map<BoxNode, List<LayoutBox>> parents, List<LayoutBox> chain, float cbw) {
        for (int i = 0, n = owner.flow.size(); i < n; i++) {
            BoxNode c = owner.flow.get(i);
            if (c.kind == BoxKind.NONE || c.kind.atomicInline()) continue; // 原子は行の断片から写す
            if (c.kind == BoxKind.CONTENTS) { // 箱を作らない: 中身を同じ親の下へ
                inlineChildren(c, parent, textBoxes, parents, chain, cbw);
                continue;
            }
            LayoutBox lb = new LayoutBox(nodeOf(c), new ComputedStyle(c.style));
            lb.lineHeightPx = InlineFlow.lineHeight(c.style, session.face(c.style));
            if (c.kind != BoxKind.TEXT) inlineEdges(lb, c, cbw);
            parent.addChild(lb);
            textBoxes.put(c, lb); // 文字の節（文字片）とインライン要素（行ごとの箱の断片）
            if (c.kind == BoxKind.TEXT) {
                parents.put(c, new ArrayList<>(chain));
            } else {
                chain.add(0, lb);
                inlineChildren(c, lb, textBoxes, parents, chain, cbw);
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
            if (n.kind == BoxKind.CONTENTS) continue; // 箱を作らない
            LayoutBox lb = boxes.get(n);
            if (lb == null) {
                lb = new LayoutBox(nodeOf(n), new ComputedStyle(n.style));
                lb.lineHeightPx = InlineFlow.lineHeight(n.style, session.face(n.style));
                if (n.kind != BoxKind.TEXT) inlineEdges(lb, n, ownerBox.w - ownerBox.bL - ownerBox.pL - ownerBox.pR - ownerBox.bR);
                parent.addChild(lb);
                boxes.put(n, lb);
            }
            if (n != text) chain.add(0, lb);
            parent = lb;
        }
        parents.put(text, chain);
    }

    /** インライン要素の箱の余白・枠線・padding（旧描画が枠線の幅と行ごとの断片の端を読む。{@code cbw} は包含ブロックの幅）。 */
    private void inlineEdges(LayoutBox lb, BoxNode n, float cbw) {
        BoxGeometry g = new BoxGeometry(n.style, cbw, session);
        lb.mT = g.mT; lb.mR = g.mR; lb.mB = g.mB; lb.mL = g.mL;
        lb.pT = g.pT; lb.pR = g.pR; lb.pB = g.pB; lb.pL = g.pL;
        lb.bT = g.bT; lb.bR = g.bR; lb.bB = g.bB; lb.bL = g.bL;
    }

    private static boolean pathTo(BoxNode from, BoxNode target, List<BoxNode> path) {
        path.add(from);
        if (from == target) return true;
        if (from.kind == BoxKind.INLINE || from.kind == BoxKind.CONTENTS || path.size() == 1) {
            for (int i = 0, n = from.flow.size(); i < n; i++) {
                if (pathTo(from.flow.get(i), target, path)) return true;
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
            if (!f.isText()) continue;
            x0 = Math.min(x0, f.x); y0 = Math.min(y0, f.y); x1 = Math.max(x1, f.x + f.w); y1 = Math.max(y1, f.y + f.h);
        }
        for (LayoutBox c : b.children) {
            float[] r = fitInline(c);
            if (r == null) continue;
            x0 = Math.min(x0, r[0]); y0 = Math.min(y0, r[1]); x1 = Math.max(x1, r[2]); y1 = Math.max(y1, r[3]);
        }
        float[] own = spanBounds(b);
        if (x0 > x1 && own == null) return null;
        if (own != null) { // 旧 BlockLayoutEngine.fitToFragments と同じく、インライン要素の矩形は行ごとの箱の断片を囲む矩形
            b.x = own[0];
            b.y = own[1];
            b.w = own[2] - own[0];
            b.h = own[3] - own[1];
            return x0 > x1 ? own : new float[]{Math.min(x0, own[0]), Math.min(y0, own[1]), Math.max(x1, own[2]), Math.max(y1, own[3])};
        }
        b.x = 0; // 文字の箱は原点に置き寸法だけ外接矩形にする（文字片は最も近いブロック基準）
        b.y = 0;
        b.w = x1 - x0;
        b.h = y1 - y0;
        return new float[]{x0, y0, x1, y1};
    }

    /** 箱の断片（文字でない断片）を囲む矩形。大きさ 0 の断片は除き、全部 0 なら最初の断片（旧経路と同じ）。無ければ null。 */
    private static float[] spanBounds(LayoutBox b) {
        float x0 = Float.POSITIVE_INFINITY, y0 = Float.POSITIVE_INFINITY, x1 = Float.NEGATIVE_INFINITY, y1 = Float.NEGATIVE_INFINITY;
        LayoutBox.InlineFragment first = null;
        for (LayoutBox.InlineFragment f : b.inlineFragments) {
            if (f.isText()) continue;
            if (first == null) first = f;
            if (f.w <= 0 || f.h <= 0) continue;
            x0 = Math.min(x0, f.x); y0 = Math.min(y0, f.y); x1 = Math.max(x1, f.x + f.w); y1 = Math.max(y1, f.y + f.h);
        }
        if (x0 <= x1) return new float[]{x0, y0, x1, y1};
        return first == null ? null : new float[]{first.x, first.y, first.x + first.w, first.y + first.h};
    }

    /** 行の文字片を、文字の節の箱の {@link LayoutBox.InlineFragment} にする（{@code ox}, {@code oy} はブロックの border-box 基準の行の原点）。 */
    private void lines(LayoutBox block, LineFragment[] lines, float ox, float oy, Map<BoxNode, LayoutBox> boxes,
                       Map<BoxNode, List<LayoutBox>> parents) {
        if (lines == null) return;
        for (LineFragment l : lines) {
            float lineBase = l.baseline();
            for (PlacedFragment a : l.atoms()) { // インライン水準の原子: ブロックの border-box 基準に置いた箱
                block.addChild(block(a.frag(), ox + l.x() + a.x(), oy + l.y() + a.y()));
            }
            for (LineFragment.Span sp : l.spans()) { // インライン要素の行ごとの箱（背景・枠線を描く断片）
                LayoutBox eb = boxes.get(sp.box());
                if (eb != null) eb.addFragment(ox + l.x() + sp.x(), oy + l.y() + sp.y(), sp.width(), sp.height(), null,
                        sp.baseline(), 0, null, sp.startEdge(), sp.endEdge());
            }
            for (LineFragment.Piece p : l.pieces()) {
                LayoutBox tb = boxes.get(p.text());
                if (tb == null) continue;
                Style st = p.text().style;
                FontFace face = session.face(st);
                FontStyle fs = st.facts.fontStyle;
                float runH = InlineFlow.lineHeight(st, face), runBase = runBaseline(st);
                float fy = LayoutUnit.snap(oy + l.y() + (lineBase - runBase) + p.dy());
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

    /**
     * 箱の描画用の節。疑似要素の箱（{@link BoxNode#pseudo}）は DOM に節を持たないので、旧描画が生成内容として扱う使い捨ての節
     * （{@code isPseudoNode}。持ち主の子のように親だけを繋ぎ、DOM には入れない）を 1 フレームの射影の間だけ作る。
     */
    private com.nishiyu.enderframelib.core.dom.node.HtmlNode nodeOf(BoxNode b) {
        if (b.pseudo == BoxNode.Pseudo.NONE) return b.node;
        if (b.pseudo == BoxNode.Pseudo.ORTHOGONAL) { // 直交フローの包む箱: 旧経路の生成箱と同じ鍵の形（_vw / _hw）
            return synthetic.computeIfAbsent(b, k -> {
                com.nishiyu.enderframelib.core.dom.node.HtmlNode n = new com.nishiyu.enderframelib.core.dom.node.HtmlNode("div");
                n.isPseudoNode = true;
                n.parent = k.node;

                n.setId(k.node.key() + (k.style.inherited.writingMode != null
                        && com.nishiyu.enderframelib.core.css.WritingModes.flowOf(k.style.inherited.writingMode)
                        == com.nishiyu.enderframelib.core.css.WritingModes.HORIZONTAL ? "_hw" : "_vw"));
                return n;
            });
        }
        if (b.pseudo == BoxNode.Pseudo.ANONYMOUS) { // 表の匿名の箱: 持ち主の子のように親だけを繋いだ使い捨ての節
            return synthetic.computeIfAbsent(b, k -> {
                com.nishiyu.enderframelib.core.dom.node.HtmlNode n = new com.nishiyu.enderframelib.core.dom.node.HtmlNode("div");
                n.parent = k.node;
                n.setId(k.node.key() + "_anon" + anonymousCounts.merge(k.node, 1, Integer::sum));
                return n;
            });
        }
        return synthetic.computeIfAbsent(b, k -> {
            com.nishiyu.enderframelib.core.dom.node.HtmlNode n = k.kind == BoxKind.TEXT
                    ? new com.nishiyu.enderframelib.core.dom.node.HtmlText(k.text)
                    : new com.nishiyu.enderframelib.core.dom.node.HtmlNode("span");
            n.isPseudoNode = true;
            n.parent = k.node;
            // 旧描画は節の鍵で描画の順と記憶を引くので、持ち主の鍵から一意の鍵を付ける（旧経路の生成箱と同じ形）
            n.setId(k.node.key() + "_" + k.pseudo.name().toLowerCase(java.util.Locale.ROOT) + (k.kind == BoxKind.TEXT ? "_txt" : ""));
            return n;
        });
    }

    private float runBaseline(Style st) {
        return InlineFlow.runBaseline(st, session);
    }
}
