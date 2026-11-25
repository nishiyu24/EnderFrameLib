package com.nishiyu.enderframelib.core.paint.tree;

import com.nishiyu.enderframelib.core.box.BoxKind;
import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.WritingModes;
import com.nishiyu.enderframelib.core.css.model.Position;
import com.nishiyu.enderframelib.core.dom.node.ElementTraits;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.fragment.BoxGeometry;
import com.nishiyu.enderframelib.core.layout.fragment.InlineFlow;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutFragment;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutSession;
import com.nishiyu.enderframelib.core.layout.fragment.LineFragment;
import com.nishiyu.enderframelib.core.layout.fragment.PlacedFragment;
import com.nishiyu.enderframelib.core.layout.fragment.ScrollableOverflow;
import com.nishiyu.enderframelib.core.layout.fragment.TableBorders;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontStyle;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 断片木と最新の木から描画用の木（{@link PaintNode}）を作る（M2c ②-2。計画書 §3.4.2-4）。木の形・幾何・行の断片の分け方は M2b の
 * 一時アダプタ（{@code FragmentProjection}）と同じで、描画だけの群は最新の木（{@link BoxNode#layout} でない側）から読む。
 *
 * <p><b>増分</b>: 前回の木と並走し、ブロック水準の節は {@code (断片 ==, 最新の箱 ==)} なら前回の節をそのまま使う（記憶はこの 1 層だけ。
 * 節 → 結果の別の表は持たない）。断片を引く前回の候補は、作り直す親の前回の子から 1 回の整形の間だけ作る。</p>
 *
 * <p><b>最新の箱の引き方</b>: 断片はレイアウトの入力の箱を指すので、最新の木の箱へ戻す対応を 1 回の構築の間だけ作る。2 つの木が
 * 分かれている所（描画だけが変わった節とその子孫）だけを位置で並走して辿るので、費用は描画の変化の大きさに比例する（変化が無ければ空）。</p>
 */
public final class PaintTreeBuilder {
    private final LayoutSession session;
    private PaintNode root;

    // ---- 1 回の構築の間だけ使う ----
    private final Map<BoxNode, BoxNode> latest = new IdentityHashMap<>();
    private final Map<LayoutFragment, float[]> overflow = new IdentityHashMap<>();
    private final Map<HtmlNode, Integer> anonymousCounts = new IdentityHashMap<>();
    private int built, reused;

    public PaintTreeBuilder(LayoutSession session) {
        this.session = session;
    }

    /** 前回の結果（まだ無ければ null）。 */
    public PaintNode root() {
        return root;
    }

    /** 直前の構築で新しく作った節の数。 */
    public int built() {
        return built;
    }

    /** 直前の構築で前回のまま使った節（ブロック水準の部分木の根）の数。 */
    public int reused() {
        return reused;
    }

    /**
     * 描画用の木を作る。{@code rootFrag} はレイアウトの入力の木（{@code latestRoot.layout}）を整形した根の断片、{@code latestRoot} は
     * 最新の木の根。
     */
    public PaintNode build(LayoutFragment rootFrag, BoxNode latestRoot) {
        built = reused = 0;
        try {
            diverge(latestRoot.layout, latestRoot);
            Map<Object, PaintNode> prev = new IdentityHashMap<>();
            if (root != null) index(root, prev);
            Object r = block(rootFrag, 0, 0, prev, false);
            root = r instanceof PaintNode p ? p : freeze((Draft) r);
            return root;
        } finally {
            latest.clear();
            overflow.clear();
            anonymousCounts.clear();
        }
    }

    // ---- 最新の箱 ----

    /** 2 つの木が分かれた所だけを、{@link BoxNode#flow} の位置とマーカーで並走して対応を作る。 */
    private void diverge(BoxNode l, BoxNode p) {
        if (l == p) return;
        latest.put(l, p);
        for (int i = 0, n = Math.min(l.flow.size(), p.flow.size()); i < n; i++) diverge(l.flow.get(i), p.flow.get(i));
        if (l.marker != null && p.marker != null) diverge(l.marker, p.marker);
    }

    private BoxNode latestOf(BoxNode l) {
        BoxNode p = latest.get(l);
        return p != null ? p : l;
    }

    // ---- 前回の候補 ----

    /**
     * 前回の節 {@code n} のブロック水準の子を、断片・レイアウトの入力の箱・DOM の節（疑似要素でない箱）で引けるようにする。断片と箱が
     * 新しくなった親（子を足した {@code ul} など）でも、前回の同じ節の子を候補にできる（子の断片の多くは前回の参照のまま）。
     */
    private static void index(PaintNode n, Map<Object, PaintNode> out) {
        if (n.frag != null) {
            out.put(n.frag, n);
            BoxNode b = n.frag.box;
            if (b != null) {
                out.putIfAbsent(b, n);
                if (!b.generated()) out.putIfAbsent(b.node, n);
            }
        }
    }

    private static Map<Object, PaintNode> childIndex(PaintNode n) {
        if (n == null || n.children.length == 0) return null;
        Map<Object, PaintNode> out = new IdentityHashMap<>();
        for (PaintNode c : n.children) index(c, out);
        return out;
    }

    // ---- ブロック水準の節 ----

    /** 断片 1 つ（{@code x}, {@code y} は親の border-box 基準の位置。文字の箱だけが使う）。前回の節か、作りかけの節を返す。 */
    private Object block(LayoutFragment f, float x, float y, Map<Object, PaintNode> prev, boolean legend) {
        BoxNode b = f.box;
        BoxNode lb = latestOf(b);
        PaintNode old = prev != null ? prev.get(f) : null;
        if (old != null && old.box == lb && old.renderedLegend == legend && b.kind != BoxKind.TEXT && anonymousMatches(old, b)) {
            reused++;
            return old;
        }
        PaintNode before = old != null ? old : prev == null ? null
                : prev.get(b) != null ? prev.get(b) : !b.generated() ? prev.get(b.node) : null;
        Draft d = new Draft();
        d.box = lb;
        d.frag = f;
        d.node = nodeOf(b);
        d.style = new ComputedStyle(lb.style);
        d.display = LayoutBox.Display.of(d.node, d.style).blockified(d.style);
        d.anonymous = b.anonymousTableBox();
        d.verticalWrapper = b.orthogonalWrapper();
        d.renderedLegend = legend;
        d.placed = true;
        d.x = x;
        d.y = y;
        d.w = f.width;
        d.h = f.height;
        BoxGeometry g = new BoxGeometry(b.style, f.space.availableWidth, session);
        d.mT = g.mT; d.mR = g.mR; d.mB = g.mB; d.mL = f.marginLeft;
        d.pT = g.pT; d.pR = g.pR; d.pB = g.pB; d.pL = g.pL;
        d.bT = g.bT; d.bR = g.bR; d.bB = g.bB; d.bL = g.bL;
        tableEdges(d, f);
        float[] ov = ScrollableOverflow.of(f, overflow, session);
        d.scrollWidth = ov[0];
        d.scrollHeight = ov[1];
        d.lineHeight = InlineFlow.lineHeight(b.style, session.face(b.style));
        Map<Object, PaintNode> kids = childIndex(before);
        if (b.kind == BoxKind.TEXT) { // flex の項目になった文字の節: 文字片を自分に持つ（文字の箱は原点に足されないので親の基準で置く）
            Map<BoxNode, Draft> boxes = new IdentityHashMap<>();
            boxes.put(b, d);
            lines(d, f.lines, x + f.insetLeft, y + f.insetTop, boxes, kids);
            return d;
        }
        float cx = f.insetLeft, cy = f.insetTop;
        Map<BoxNode, Draft> textBoxes = new IdentityHashMap<>();
        if (f.lines != null) {
            inlineChildren(b, lb, d, textBoxes, f.width - d.bL - d.pL - d.pR - d.bR);
            lines(d, f.lines, cx, cy, textBoxes, kids);
        }
        if (f.flow != null) {
            f.flow.forEach((child, x1, y1) -> {
                if (child.box != null) {
                    d.add(block(child, cx + x1, cy + y1, kids, false), cx + x1, cy + y1);
                } else { // 混在の流れの匿名の塊: 中のインラインの子をこの箱の下へ足し、行を写す
                    Map<BoxNode, Draft> anon = new IdentityHashMap<>();
                    float cbw = d.w - d.bL - d.pL - d.pR - d.bR;
                    for (LineFragment l : child.lines) {
                        for (LineFragment.Piece p : l.pieces()) collectAncestors(b, lb, p.text(), d, anon, cbw);
                        for (LineFragment.Span sp : l.spans()) collectAncestors(b, lb, sp.box(), d, anon, cbw);
                    }
                    lines(d, child.lines, cx + x1, cy + y1, anon, kids);
                }
            });
        }
        for (Child c : d.children) { // 断片から置いた箱（原子・flex の項目に blockify したインライン要素）は位置を持つので触らない
            if (c.n instanceof Draft cd && !cd.placed && (cd.text || inlineFlow(cd.display))) fitInline(cd);
        }
        if (f.oof != null) { // 流れの外の断片は包含ブロックの子として後ろに置く（描画の順も位置指定の子が後）
            for (PlacedFragment p : f.oof) {
                BoxNode cb = p.frag().box;
                // fieldset の描画される legend（FieldsetLegend が置いた）: 旧描画が枠線を切り欠く印
                boolean isLegend = cb != null && cb.style.box.position != Position.ABSOLUTE && cb.style.box.position != Position.FIXED
                        && ElementTraits.isFieldset(b.node.tag) && ElementTraits.isLegend(cb.node.tag);
                d.add(block(p.frag(), p.x(), p.y(), kids, isLegend), p.x(), p.y());
            }
        }
        return d;
    }

    /** 前回の節の合成の節の番号（表の匿名の箱）が、今回の文書順の番号と同じか。同じなら番号を進めて true。 */
    private boolean anonymousMatches(PaintNode old, BoxNode b) {
        if (old.anonChain == 0) return true;
        int next = anonymousCounts.getOrDefault(b.node, 0) + 1;
        if (!(b.node.key() + "_anon" + next).equals(old.node.getId())) return false;
        anonymousCounts.put(b.node, next - 1 + old.anonChain);
        return true;
    }

    private static boolean inlineFlow(LayoutBox.Display d) {
        return d != null && d.outer() == LayoutBox.Display.Outer.INLINE && d.inner() == LayoutBox.Display.Inner.FLOW;
    }

    /**
     * 表の箱の縁（M2b-4）: 枠線を重ねる表とセルは表が決めた使う枠線で、表は padding を持たない。行グループと行は旧経路と同じく縁を
     * 持たない（表が大きさだけを決め、自分では測られない）。
     */
    private void tableEdges(Draft d, LayoutFragment f) {
        BoxNode b = f.box;
        TableBorders.Edges e = f.space.usedBorders();
        if (TableBorders.collapses(b)) {
            e = TableBorders.ofTable(b, session);
            d.pT = d.pR = d.pB = d.pL = 0;
        }
        if (e != null) {
            d.bT = e.top(); d.bR = e.right(); d.bB = e.bottom(); d.bL = e.left();
            d.collapsedBorders = new float[]{e.top(), e.right(), e.bottom(), e.left()};
        }
        if (b.kind == BoxKind.TABLE_ROW || b.kind == BoxKind.TABLE_ROW_GROUP) {
            d.mT = d.mR = d.mB = d.mL = 0;
            d.pT = d.pR = d.pB = d.pL = 0;
            d.bT = d.bR = d.bB = d.bL = 0;
        }
    }

    // ---- インラインの部分木 ----

    /** インライン水準の子孫（文字の節・インライン要素）の箱を、DOM の入れ子のまま {@code parent} の下へ足す（2 つの木を位置で並走）。 */
    private void inlineChildren(BoxNode owner, BoxNode latestOwner, Draft parent, Map<BoxNode, Draft> boxes, float cbw) {
        for (int i = 0, n = owner.flow.size(); i < n; i++) {
            BoxNode c = owner.flow.get(i), lc = latestOwner.flow.get(i);
            if (c.kind == BoxKind.NONE || c.kind.atomicInline()) continue; // 原子は行の断片から写す
            if (c.kind == BoxKind.CONTENTS) { // 箱を作らない: 中身を同じ親の下へ
                inlineChildren(c, lc, parent, boxes, cbw);
                continue;
            }
            Draft d = inlineDraft(c, lc, cbw);
            parent.add(d, 0, 0);
            boxes.put(c, d); // 文字の節（文字片）とインライン要素（行ごとの箱の断片）
            if (c.kind != BoxKind.TEXT) inlineChildren(c, lc, d, boxes, cbw);
        }
    }

    /** 混在の流れの文字の節 {@code target} とそのインラインの祖先の箱を（まだ無ければ）{@code ownerDraft} の下に作る。 */
    private void collectAncestors(BoxNode owner, BoxNode latestOwner, BoxNode target, Draft ownerDraft, Map<BoxNode, Draft> boxes,
                                  float cbw) {
        if (boxes.containsKey(target)) return;
        List<BoxNode> path = new ArrayList<>();
        List<Integer> at = new ArrayList<>();
        if (!pathTo(owner, target, -1, path, at)) return;
        Draft parent = ownerDraft;
        BoxNode cur = latestOwner;
        for (int i = 1; i < path.size(); i++) {
            BoxNode n = path.get(i);
            cur = cur.flow.get(at.get(i));
            if (n.kind == BoxKind.CONTENTS) continue; // 箱を作らない
            Draft d = boxes.get(n);
            if (d == null) {
                d = inlineDraft(n, cur, cbw);
                parent.add(d, 0, 0);
                boxes.put(n, d);
            }
            parent = d;
        }
    }

    private static boolean pathTo(BoxNode from, BoxNode target, int index, List<BoxNode> path, List<Integer> at) {
        path.add(from);
        at.add(index);
        if (from == target) return true;
        if (from.kind == BoxKind.INLINE || from.kind == BoxKind.CONTENTS || path.size() == 1) {
            for (int i = 0, n = from.flow.size(); i < n; i++) {
                if (pathTo(from.flow.get(i), target, i, path, at)) return true;
            }
        }
        path.remove(path.size() - 1);
        at.remove(at.size() - 1);
        return false;
    }

    /** インライン要素・文字の節の作りかけの節（縁はレイアウトの入力の箱、描画のスタイルは最新の箱から）。 */
    private Draft inlineDraft(BoxNode c, BoxNode lc, float cbw) {
        Draft d = new Draft();
        d.box = lc;
        d.node = nodeOf(c);
        d.style = new ComputedStyle(lc.style);
        d.display = LayoutBox.Display.of(d.node, d.style).blockified(d.style);
        d.text = c.kind == BoxKind.TEXT;
        d.lineHeight = InlineFlow.lineHeight(c.style, session.face(c.style));
        if (!d.text) {
            BoxGeometry g = new BoxGeometry(c.style, cbw, session);
            d.mT = g.mT; d.mR = g.mR; d.mB = g.mB; d.mL = g.mL;
            d.pT = g.pT; d.pR = g.pR; d.pB = g.pB; d.pL = g.pL;
            d.bT = g.bT; d.bR = g.bR; d.bB = g.bB; d.bL = g.bL;
        }
        return d;
    }

    /**
     * インラインの箱（文字の節・インライン要素）の幾何を、自分と子孫の文字片の外接矩形にする（描画の間引きが空の箱を飛ばすため）。
     * インラインの箱は座標の原点に足されないので、位置は最も近いブロックの border-box 基準の値をそのまま入れる。
     */
    private static float[] fitInline(Draft b) {
        float x0 = Float.POSITIVE_INFINITY, y0 = Float.POSITIVE_INFINITY, x1 = Float.NEGATIVE_INFINITY, y1 = Float.NEGATIVE_INFINITY;
        for (TreeRun f : b.runs) {
            if (!f.isText()) continue;
            x0 = Math.min(x0, f.x()); y0 = Math.min(y0, f.y()); x1 = Math.max(x1, f.x() + f.w()); y1 = Math.max(y1, f.y() + f.h());
        }
        for (Child c : b.children) {
            if (!(c.n instanceof Draft cd)) continue;
            float[] r = fitInline(cd);
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
    private static float[] spanBounds(Draft b) {
        float x0 = Float.POSITIVE_INFINITY, y0 = Float.POSITIVE_INFINITY, x1 = Float.NEGATIVE_INFINITY, y1 = Float.NEGATIVE_INFINITY;
        TreeRun first = null;
        for (TreeRun f : b.runs) {
            if (f.isText()) continue;
            if (first == null) first = f;
            if (f.w() <= 0 || f.h() <= 0) continue;
            x0 = Math.min(x0, f.x()); y0 = Math.min(y0, f.y()); x1 = Math.max(x1, f.x() + f.w()); y1 = Math.max(y1, f.y() + f.h());
        }
        if (x0 <= x1) return new float[]{x0, y0, x1, y1};
        return first == null ? null : new float[]{first.x(), first.y(), first.x() + first.w(), first.y() + first.h()};
    }

    /** 行の文字片を文字の節の断片にする（{@code ox}, {@code oy} はブロックの border-box 基準の行の原点）。 */
    private void lines(Draft block, LineFragment[] lines, float ox, float oy, Map<BoxNode, Draft> boxes, Map<Object, PaintNode> prev) {
        if (lines == null) return;
        for (LineFragment l : lines) {
            float lineBase = l.baseline();
            for (PlacedFragment a : l.atoms()) { // インライン水準の原子: ブロックの border-box 基準に置いた箱
                float ax = ox + l.x() + a.x(), ay = oy + l.y() + a.y();
                block.add(block(a.frag(), ax, ay, prev, false), ax, ay);
            }
            for (LineFragment.Span sp : l.spans()) { // インライン要素の行ごとの箱（背景・枠線を描く断片）
                Draft eb = boxes.get(sp.box());
                if (eb != null) eb.runs.add(new TreeRun(ox + l.x() + sp.x(), oy + l.y() + sp.y(), sp.width(), sp.height(), null,
                        sp.baseline(), sp.startEdge(), sp.endEdge()));
            }
            for (LineFragment.Piece p : l.pieces()) {
                Draft tb = boxes.get(p.text());
                if (tb == null) continue;
                Style st = p.text().style;
                FontFace face = session.face(st);
                FontStyle fs = st.facts.fontStyle;
                float runH = InlineFlow.lineHeight(st, face), runBase = InlineFlow.runBaseline(st, session);
                float fy = LayoutUnit.snap(oy + l.y() + (lineBase - runBase) + p.dy());
                // 旧経路と同じく折り返し候補ごと（後ろの空白を含む）の断片にする。最後の語だけは片の幅の残りで終わらせる
                String text = p.string();
                float x = ox + l.x() + p.x(), end = x + p.width();
                for (int i = 0, n = text.length(); i < n; ) {
                    int stop = InlineFlow.nextBreak(text, i, n, st.facts.wordBreak); // 折り返し候補（UAX #14）ごと
                    String word = text.substring(i, stop);
                    float w = stop == n ? end - x : face.advance(word, fs);
                    tb.runs.add(new TreeRun(x, fy, w, runH, word, runBase, true, true));
                    x += w;
                    i = stop;
                }
            }
        }
    }

    /**
     * 箱の描画用の節。疑似要素の箱（{@link BoxNode#pseudo}）は DOM に節を持たないので、旧描画が生成内容として扱う使い捨ての節
     * （{@code isPseudoNode}。持ち主の子のように親だけを繋ぎ、DOM には入れない）を作る。鍵は旧経路の生成箱と同じ形。
     */
    private HtmlNode nodeOf(BoxNode b) {
        if (!b.generated()) return b.node;
        if (b.orthogonalWrapper()) { // 直交フローの包む箱（_vw / _hw）
            HtmlNode n = new HtmlNode("div");
            n.isPseudoNode = true;
            n.parent = b.node;
            n.setId(b.node.key() + (b.style.inherited.writingMode != null
                    && WritingModes.flowOf(b.style.inherited.writingMode) == WritingModes.HORIZONTAL ? "_hw" : "_vw"));
            return n;
        }
        if (b.anonymousTableBox()) { // 表の匿名の箱: 持ち主の子のように親だけを繋いだ使い捨ての節
            HtmlNode n = new HtmlNode("div");
            n.parent = b.node;
            n.setId(b.node.key() + "_anon" + anonymousCounts.merge(b.node, 1, Integer::sum));
            return n;
        }
        HtmlNode n = b.kind == BoxKind.TEXT ? new HtmlText(b.text) : new HtmlNode("span");
        n.isPseudoNode = true;
        n.parent = b.node;
        n.setId(b.node.key() + "_" + b.pseudo.name().toLowerCase(Locale.ROOT) + (b.kind == BoxKind.TEXT ? "_txt" : ""));
        return n;
    }

    // ---- 作りかけの節 ----

    private PaintNode freeze(Draft d) {
        int n = d.children.size();
        PaintNode[] kids = n == 0 ? PaintNode.NO_CHILDREN : new PaintNode[n];
        float[] xs = n == 0 ? PaintNode.NO_POS : new float[n], ys = n == 0 ? PaintNode.NO_POS : new float[n];
        for (int i = 0; i < n; i++) {
            Child c = d.children.get(i);
            if (c.n instanceof Draft cd) {
                kids[i] = freeze(cd);
                xs[i] = cd.x;
                ys[i] = cd.y;
            } else {
                kids[i] = (PaintNode) c.n;
                xs[i] = c.x;
                ys[i] = c.y;
            }
        }
        built++;
        return new PaintNode(d, kids, xs, ys);
    }

    /** 子 1 つ（作りかけの節か、前回のまま使う節と、その位置）。 */
    private record Child(Object n, float x, float y) { }

    /** 構築の間だけの可変の節（{@link #freeze} で不変の {@link PaintNode} にする）。 */
    static final class Draft {
        BoxNode box;
        LayoutFragment frag;
        HtmlNode node;
        ComputedStyle style;
        LayoutBox.Display display;
        boolean anonymous, verticalWrapper, renderedLegend, text, placed;
        float x, y, w, h, mT, mR, mB, mL, pT, pR, pB, pL, bT, bR, bB, bL;
        float[] collapsedBorders;
        float scrollWidth, scrollHeight, lineHeight;
        final List<TreeRun> runs = new ArrayList<>(0);
        final List<Child> children = new ArrayList<>(0);

        void add(Object child, float x, float y) {
            children.add(new Child(child, x, y));
        }
    }
}
