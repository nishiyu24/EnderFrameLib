package com.nishiyu.enderframelib.core.paint.tree;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.model.Position;
import com.nishiyu.enderframelib.core.box.Display;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.replaced.ReplacedElements;
import com.nishiyu.enderframelib.core.text.model.ShapedLine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 描画用の木の節を、親と絶対位置に結び付けた見え方（M2c ②-2）。箱 1 つを描く道具（{@code BoxItemPainter} ほか）が読む
 * {@link PaintBox} を実装する。描く間だけ作る使い捨ての値で、節（{@link PaintNode}）の側は位置も親も持たない。
 *
 * <p>位置の約束は旧経路の {@code LayoutBox} と同じ: 子の位置は原点の箱（親か、親が行の中のインライン要素なら、その原点の箱）の左上から、
 * 行の断片の位置は {@link #originParent} の左上から。</p>
 */
public final class PaintView extends PaintBox {
    /** 見ている節。 */
    public final PaintNode n;
    private final PaintView parent;
    private final float x, y;
    /** スクロールの位置（null なら全て 0）。スクロールコンテナの子は、その分だけずらして置く。 */
    private final ScrollState scroll;
    /** ビューポートの大きさ（スクロールコンテナの外の sticky の基準）。 */
    private final float vw, vh;
    /** {@code position: sticky} の貼り付きで足した縦のずれ（{@link #y} に含む。sticky でなければ 0）。 */
    private final float stickyDy;
    private List<PaintView> kids;

    private PaintView(PaintNode n, PaintView parent, float x, float y, ScrollState scroll, float vw, float vh, float stickyDy) {
        super(n.node, n.anonymous);
        this.n = n;
        this.parent = parent;
        this.x = x;
        this.y = y;
        this.scroll = scroll;
        this.vw = vw;
        this.vh = vh;
        this.stickyDy = stickyDy;
    }

    /** 根の節の見え方（文書の左上に置く。スクロールの位置は全て 0）。 */
    public static PaintView root(PaintNode root) {
        return new PaintView(root, null, 0, 0, null, root.w, root.h, 0f);
    }

    /**
     * 根の節の見え方。{@code scroll} はスクロールの位置（M2c ③。スクロール量はレイアウトの入力にしないので、ビューの側でここで足す。
     * スクロールコンテナの子〔流れの子と、包含ブロックがそのコンテナの流れの外の子〕は、スクロールした分だけ戻して置く）。
     */
    public static PaintView root(PaintNode root, ScrollState scroll) {
        return root(root, scroll, root.w, root.h);
    }

    /** 根の節の見え方。{@code viewportW}, {@code viewportH} はビューポートの大きさ（スクロールコンテナの外の sticky が貼り付く枠）。 */
    public static PaintView root(PaintNode root, ScrollState scroll, float viewportW, float viewportH) {
        return new PaintView(root, null, 0, 0, scroll, viewportW, viewportH, 0f);
    }

    /** {@code i} 番目の子の見え方。 */
    public PaintView child(int i) {
        PaintView o = isOrigin() ? this : originParent();
        float ox = o != null ? o.x : 0, oy = o != null ? o.y : 0;
        if (o != null && scroll != null && o.isScrollContainer()) {
            ox -= scroll.getScrollX(o.node());
            oy -= scroll.getScrollY(o.node());
        }
        PaintNode c = n.children[i];
        float cx = ox + n.childX[i], cy = oy + n.childY[i];
        float[] stick = StickyOffset.isSticky(c) ? StickyOffset.of(c, this, cx, cy, vw, vh) : null;
        if (stick == null) return new PaintView(c, this, cx, cy, scroll, vw, vh, 0f);
        return new PaintView(c, this, cx + stick[0], cy + stick[1], scroll, vw, vh, stick[1]);
    }

    /** {@code position: sticky} の貼り付きで足した縦のずれ（sticky でない・貼り付いていなければ 0）。 */
    public float stickyDy() { return stickyDy; }

    /**
     * 子の座標の原点になる箱か（旧 {@code PlacementMemo.isInlineFlowForOrigin} の否定）。行の中のインライン要素（外がインラインで中が
     * 流れ。置換要素でなく、親が flex / grid でない）は原点にならない。
     */
    boolean isOrigin() {
        Display d = n.display;
        if (d == null || !d.isInlineLevel() || d.inner() != Display.Inner.FLOW) return true;
        if (parent != null && (parent.displayTraits() & FLEX_OR_GRID) != 0) return true;
        return ReplacedElements.isReplacedTag(n.node.tag);
    }

    /** {@code ::backdrop} のスタイル（Top Layer に入れる要素で規則があるときだけ。無ければ null）。 */
    public com.nishiyu.enderframelib.core.css.Style backdrop() {
        return n.box != null ? n.box.backdrop : null;
    }

    /** {@code display} を外と中に分けたもの（旧経路の箱と同じ規則で、描画の木の節のスタイルから決めた値）。 */
    public Display display() { return n.display; }

    @Override public ComputedStyle style() { return n.style; }
    @Override public ComputedStyle axisStyle() { return n.style; }
    @Override public PaintView paintParent() { return parent; }

    @Override
    public List<PaintView> paintChildren() {
        if (kids == null) {
            List<PaintView> out = new ArrayList<>(n.children.length);
            for (int i = 0; i < n.children.length; i++) out.add(child(i));
            kids = out;
        }
        return kids;
    }

    @Override public List<? extends PaintRun> runs() { return Arrays.asList(n.runs); }

    @Override
    public PaintView originParent() {
        PaintView p = parent;
        while (p != null && !p.isOrigin()) p = p.parent;
        return p;
    }

    @Override public float x() { return x; }
    @Override public float y() { return y; }
    @Override public float w() { return n.w; }
    @Override public float h() { return n.h; }
    @Override public float bT() { return n.bT; }
    @Override public float bR() { return n.bR; }
    @Override public float bB() { return n.bB; }
    @Override public float bL() { return n.bL; }
    @Override public float pT() { return n.pT; }
    @Override public float pR() { return n.pR; }
    @Override public float pB() { return n.pB; }
    @Override public float pL() { return n.pL; }
    @Override public float mT() { return n.mT; }
    @Override public float mR() { return n.mR; }
    @Override public float mB() { return n.mB; }
    @Override public float mL() { return n.mL; }
    @Override public boolean isScrollContainer() { return n.style.facts().scrollContainer; }

    @Override
    public boolean isCollapsedTable() {
        return n.box != null && com.nishiyu.enderframelib.core.layout.fragment.TableBorders.collapses(n.box);
    }
    @Override public float scrollWidth() { return n.scrollWidth; }
    @Override public float scrollHeight() { return n.scrollHeight; }
    @Override public float lineHeightPx() { return n.lineHeight; }

    @Override
    public boolean isAbsolute() {
        Position p = n.style.typed().box.position;
        return p == Position.ABSOLUTE || p == Position.FIXED;
    }

    @Override public boolean isVerticalWrapper() { return n.verticalWrapper; }

    @Override public boolean interactiveRules() { return n.interactive; }

    @Override public float[] collapsedBorders() { return n.collapsedBorders; }
    @Override public float[][] columnRules() { return null; }
    @Override public float[] firstLineBgRect() { return n.firstLineBgRect; }
    @Override public ComputedStyle firstLine() { return n.firstLine; }
    @Override public ComputedStyle placeholder() { return null; }

    @Override
    public PaintView renderedLegend() {
        for (int i = 0; i < n.children.length; i++) {
            PaintNode c = n.children[i];
            if (c.renderedLegend) return c.display != null && c.display.isNone() ? null : child(i);
        }
        return null;
    }

    @Override public List<ShapedLine> shapedLines() { return null; }

    @Override
    public int displayTraits() {
        Display d = n.display;
        if (d == null) return 0;
        Display.Inner inner = d.inner();
        return (inner == Display.Inner.TABLE_CELL ? TABLE_CELL : 0) | (d.isNone() ? DISPLAY_NONE : 0)
                | (d.isInlineLevel() && inner != Display.Inner.FLOW ? ATOMIC_INLINE : 0)
                | (inner == Display.Inner.FLEX || inner == Display.Inner.GRID ? FLEX_OR_GRID : 0);
    }

    @Override
    public String toString() {
        return "PaintView[" + n.node.key() + " @" + x + "," + y + "]";
    }
}
