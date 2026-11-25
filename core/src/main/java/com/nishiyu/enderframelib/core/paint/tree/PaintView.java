package com.nishiyu.enderframelib.core.paint.tree;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.model.Position;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.PaintBox;
import com.nishiyu.enderframelib.core.layout.PaintRun;
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
    private List<PaintView> kids;

    private PaintView(PaintNode n, PaintView parent, float x, float y) {
        super(n.node, n.anonymous);
        this.n = n;
        this.parent = parent;
        this.x = x;
        this.y = y;
    }

    /** 根の節の見え方（文書の左上に置く）。 */
    public static PaintView root(PaintNode root) {
        return new PaintView(root, null, 0, 0);
    }

    /** {@code i} 番目の子の見え方。 */
    public PaintView child(int i) {
        PaintView o = isOrigin() ? this : originParent();
        return new PaintView(n.children[i], this, (o != null ? o.x : 0) + n.childX[i], (o != null ? o.y : 0) + n.childY[i]);
    }

    /**
     * 子の座標の原点になる箱か（旧 {@code PlacementMemo.isInlineFlowForOrigin} の否定）。行の中のインライン要素（外がインラインで中が
     * 流れ。置換要素でなく、親が flex / grid でない）は原点にならない。
     */
    boolean isOrigin() {
        LayoutBox.Display d = n.display;
        if (d == null || !d.isInlineLevel() || d.inner() != LayoutBox.Display.Inner.FLOW) return true;
        if (parent != null && (parent.displayTraits() & FLEX_OR_GRID) != 0) return true;
        return ReplacedElements.isReplacedTag(n.node.tag);
    }

    /** {@code display} を外と中に分けたもの（旧経路の箱と同じ規則で、描画の木の節のスタイルから決めた値）。 */
    public LayoutBox.Display display() { return n.display; }

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
    @Override public float mB() { return n.mB; }
    @Override public float scrollWidth() { return n.scrollWidth; }
    @Override public float scrollHeight() { return n.scrollHeight; }
    @Override public float lineHeightPx() { return n.lineHeight; }

    @Override
    public boolean isAbsolute() {
        Position p = n.style.typed().box.position;
        return p == Position.ABSOLUTE || p == Position.FIXED;
    }

    @Override public boolean isVerticalWrapper() { return n.verticalWrapper; }

    /** 状態の規則の印は ②-3 で入れる（旧経路の射影と同じく今は false）。 */
    @Override public boolean interactiveRules() { return false; }

    @Override public float[] collapsedBorders() { return n.collapsedBorders; }
    @Override public float[][] columnRules() { return null; }
    @Override public float[] firstLineBgRect() { return null; }
    @Override public ComputedStyle firstLine() { return null; }
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
        LayoutBox.Display d = n.display;
        if (d == null) return 0;
        LayoutBox.Display.Inner inner = d.inner();
        return (inner == LayoutBox.Display.Inner.TABLE_CELL ? TABLE_CELL : 0) | (d.isNone() ? DISPLAY_NONE : 0)
                | (d.isInlineLevel() && inner != LayoutBox.Display.Inner.FLOW ? ATOMIC_INLINE : 0)
                | (inner == LayoutBox.Display.Inner.FLEX || inner == LayoutBox.Display.Inner.GRID ? FLEX_OR_GRID : 0);
    }

    @Override
    public String toString() {
        return "PaintView[" + n.node.key() + " @" + x + "," + y + "]";
    }
}
