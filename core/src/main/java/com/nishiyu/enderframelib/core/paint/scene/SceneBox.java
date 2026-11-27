package com.nishiyu.enderframelib.core.paint.scene;

import com.nishiyu.enderframelib.core.box.Display;
import com.nishiyu.enderframelib.core.box.DomValues;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.layout.fragment.TableBorders;
import com.nishiyu.enderframelib.core.paint.tree.PaintBox;
import com.nishiyu.enderframelib.core.paint.tree.PaintRun;
import com.nishiyu.enderframelib.core.text.model.ShapedLine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link SceneLayer} を親と絶対位置・動的状態（{@link DynamicSceneState}）に結び付けた一時アダプタ（M3b-2。計画書 §2.3）。
 * 問い合わせ（{@code BoxLayoutQuery} ほか）が断片木とシーンへ移る M3b-3 と、バックエンドがシーンを直接描く M3b-4 までのつなぎとして
 * {@link PaintBox} を実装する。
 */
public final class SceneBox extends PaintBox {
    public final SceneLayer layer;
    private final SceneBox parent;
    private final float x, y;
    private final DynamicSceneState dynamic;
    private final float stickyDy;
    private List<SceneBox> kids;

    private SceneBox(SceneLayer layer, SceneBox parent, float x, float y, DynamicSceneState dynamic, float stickyDy) {
        super(layer.node, layer.anonymous());
        this.layer = layer;
        this.parent = parent;
        this.x = x;
        this.y = y;
        this.dynamic = dynamic;
        this.stickyDy = stickyDy;
    }

    public static SceneBox root(DisplayScene scene, DynamicSceneState dynamic) {
        return root(scene.root, dynamic);
    }

    public static SceneBox root(SceneLayer root, DynamicSceneState dynamic) {
        DynamicSceneState dyn = dynamic != null ? dynamic : DynamicSceneState.of(null, root.w, root.h);
        return new SceneBox(root, null, 0f, 0f, dyn, 0f);
    }

    private SceneBox createChild(SceneLayer c, float chunkOx, float chunkOy) {
        SceneBox o = layer.isOrigin() ? this : originParent();
        float ox = o != null ? o.x : 0f, oy = o != null ? o.y : 0f;
        if (o != null && o.isScrollContainer()) {
            ox -= dynamic.scrollX(o.layer);
            oy -= dynamic.scrollY(o.layer);
        }
        float cx = ox + chunkOx + c.x;
        float cy = oy + chunkOy + c.y;
        if (c.follow == null && c.sticky == null) {
            return new SceneBox(c, this, cx, cy, dynamic, 0f);
        }
        float[] d = dynamic.anchorShift(c);
        if (d != null) {
            cx += d[0];
            cy += d[1];
        }
        float[] stick = null;
        if (c.sticky != null) {
            SceneBox port = this;
            while (port != null && !port.isScrollContainer()) port = port.parent;
            float portX = port != null ? port.x + port.bL() : 0f;
            float portY = port != null ? port.y + port.bT() : 0f;
            float portW = port != null ? Math.max(0f, port.w() - port.bL() - port.bR()) : dynamic.viewportW;
            float portH = port != null ? Math.max(0f, port.h() - port.bT() - port.bB()) : dynamic.viewportH;
            stick = dynamic.stickyOffset(c, cx, cy, portX, portY, portW, portH,
                    x + bL() + pL(), y + bT() + pT(), x + w() - bR() - pR(), y + h() - bB() - pB());
        }
        if (stick == null) return new SceneBox(c, this, cx, cy, dynamic, 0f);
        return new SceneBox(c, this, cx + stick[0], cy + stick[1], dynamic, stick[1]);
    }

    /** {@code position: sticky} の貼り付きで足した縦のずれ。 */
    public float stickyDy() {
        return stickyDy;
    }

    /** {@code ::backdrop} のスタイル（Top Layer の要素で規則があるときだけ。無ければ null）。 */
    public Style backdrop() {
        return layer.box != null ? layer.box.backdrop : null;
    }

    @Override public Display display() { return layer.display; }
    @Override public ComputedStyle style() { return layer.style; }

    @Override
    public DomValues values() {
        return layer.box != null ? layer.box.values : DomValues.NONE;
    }

    @Override public ComputedStyle axisStyle() { return layer.style; }
    @Override public SceneBox paintParent() { return parent; }

    @Override
    public List<SceneBox> paintChildren() {
        if (kids == null) {
            SceneChildren sc = layer.children;
            List<SceneBox> out = new ArrayList<>(sc.size);
            for (int k = 0; k < sc.chunks.length; k++) {
                SceneChunk c = sc.chunks[k];
                float cx = sc.chunkX[k], cy = sc.chunkY[k];
                for (SceneLayer child : c.layers) {
                    out.add(createChild(child, cx, cy));
                }
            }
            kids = out;
        }
        return kids;
    }

    @Override public List<? extends PaintRun> runs() { return Arrays.asList(layer.runs); }

    @Override
    public SceneBox originParent() {
        SceneBox p = parent;
        while (p != null && !p.layer.isOrigin()) p = p.parent;
        return p;
    }

    @Override public float x() { return x; }
    @Override public float y() { return y; }
    @Override public float w() { return layer.w; }
    @Override public float h() { return layer.h; }
    @Override public float bT() { return layer.bT; }
    @Override public float bR() { return layer.bR; }
    @Override public float bB() { return layer.bB; }
    @Override public float bL() { return layer.bL; }
    @Override public float pT() { return layer.pT; }
    @Override public float pR() { return layer.pR; }
    @Override public float pB() { return layer.pB; }
    @Override public float pL() { return layer.pL; }
    @Override public float mT() { return layer.mT; }
    @Override public float mR() { return layer.mR; }
    @Override public float mB() { return layer.mB; }
    @Override public float mL() { return layer.mL; }
    @Override public boolean isScrollContainer() { return layer.isScrollContainer(); }

    @Override
    public boolean isCollapsedTable() {
        return layer.box != null && TableBorders.collapses(layer.box);
    }

    @Override public float scrollWidth() { return layer.scrollWidth; }
    @Override public float scrollHeight() { return layer.scrollHeight; }
    @Override public float lineHeightPx() { return layer.lineHeight; }
    @Override public boolean isAbsolute() { return layer.isAbsolute(); }
    @Override public boolean isVerticalWrapper() { return layer.verticalWrapper(); }
    @Override public boolean interactiveRules() { return layer.interactive(); }
    @Override public float[] collapsedBorders() { return layer.collapsedBorders; }
    @Override public float[][] columnRules() { return null; }
    @Override public float[] firstLineBgRect() { return layer.firstLineBgRect; }
    @Override public ComputedStyle firstLine() { return layer.firstLine; }
    @Override public ComputedStyle placeholder() { return null; }

    @Override
    public SceneBox renderedLegend() {
        List<SceneBox> ch = paintChildren();
        for (int i = 0; i < ch.size(); i++) {
            SceneBox c = ch.get(i);
            if (c.layer.renderedLegend()) {
                return c.layer.display != null && c.layer.display.isNone() ? null : c;
            }
        }
        return null;
    }

    @Override public List<ShapedLine> shapedLines() { return null; }

    @Override
    public int displayTraits() {
        Display d = layer.display;
        if (d == null) return 0;
        Display.Inner inner = d.inner();
        return (inner == Display.Inner.TABLE_CELL ? TABLE_CELL : 0) | (d.isNone() ? DISPLAY_NONE : 0)
                | (d.isInlineLevel() && inner != Display.Inner.FLOW ? ATOMIC_INLINE : 0)
                | (inner == Display.Inner.FLEX || inner == Display.Inner.GRID ? FLEX_OR_GRID : 0);
    }

    @Override
    public String toString() {
        return "SceneBox[" + (layer.node != null ? layer.node.key() : "?") + " @" + x + "," + y + "]";
    }
}
