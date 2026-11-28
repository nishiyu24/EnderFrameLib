package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.box.Display;
import com.nishiyu.enderframelib.core.box.DomValues;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.scene.DisplayScene;
import com.nishiyu.enderframelib.core.paint.scene.DynamicSceneState;
import com.nishiyu.enderframelib.core.paint.scene.SceneChunk;
import com.nishiyu.enderframelib.core.paint.scene.SceneChildren;
import com.nishiyu.enderframelib.core.paint.scene.SceneLayer;
import com.nishiyu.enderframelib.core.text.model.ShapedLine;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link SceneLayer} を親・絶対位置・{@link DynamicSceneState} に結び付け、旧 {@link SceneDisplayListAdapter} が
 * 読む形に揃えるパッケージプライベート一時アダプタ（M3b-3。M3b-4 でアダプタごと削除する）。
 */
final class SceneAdapterBox {
    static final int TABLE_CELL = 1, DISPLAY_NONE = 2, ATOMIC_INLINE = 4, FLEX_OR_GRID = 8;

    public final SceneLayer layer;
    public final HtmlNode node;
    public final boolean isAnonymous;
    private final SceneAdapterBox parent;
    private final float x, y;
    private final DynamicSceneState dynamic;
    private final float stickyDy;
    private List<SceneAdapterBox> kids;

    private SceneAdapterBox(SceneLayer layer, SceneAdapterBox parent, float x, float y,
                            DynamicSceneState dynamic, float stickyDy) {
        this.layer = layer;
        this.node = layer.node;
        this.isAnonymous = layer.anonymous();
        this.parent = parent;
        this.x = x;
        this.y = y;
        this.dynamic = dynamic;
        this.stickyDy = stickyDy;
    }

    static SceneAdapterBox root(DisplayScene scene, DynamicSceneState dynamic) {
        return root(scene.root, dynamic);
    }

    static SceneAdapterBox root(SceneLayer root, DynamicSceneState dynamic) {
        DynamicSceneState dyn = dynamic != null ? dynamic : DynamicSceneState.of(null, root.w, root.h);
        return new SceneAdapterBox(root, null, 0f, 0f, dyn, 0f);
    }

    private SceneAdapterBox createChild(SceneLayer c, float chunkOx, float chunkOy) {
        SceneAdapterBox o = layer.isOrigin() ? this : originParent();
        float ox = o != null ? o.x : 0f, oy = o != null ? o.y : 0f;
        if (o != null && o.isScrollContainer()) {
            ox -= dynamic.scrollX(o.layer);
            oy -= dynamic.scrollY(o.layer);
        }
        float cx = ox + chunkOx + c.x;
        float cy = oy + chunkOy + c.y;
        if (c.follow == null && c.sticky == null) {
            return new SceneAdapterBox(c, this, cx, cy, dynamic, 0f);
        }
        float[] d = dynamic.anchorShift(c);
        if (d != null) {
            cx += d[0];
            cy += d[1];
        }
        float[] stick = null;
        if (c.sticky != null) {
            SceneAdapterBox port = this;
            while (port != null && !port.isScrollContainer()) port = port.parent;
            float portX = port != null ? port.x + port.bL() : 0f;
            float portY = port != null ? port.y + port.bT() : 0f;
            float portW = port != null ? Math.max(0f, port.w() - port.bL() - port.bR()) : dynamic.viewportW;
            float portH = port != null ? Math.max(0f, port.h() - port.bT() - port.bB()) : dynamic.viewportH;
            stick = dynamic.stickyOffset(c, cx, cy, portX, portY, portW, portH,
                    x + bL() + pL(), y + bT() + pT(), x + w() - bR() - pR(), y + h() - bB() - pB());
        }
        if (stick == null) return new SceneAdapterBox(c, this, cx, cy, dynamic, 0f);
        return new SceneAdapterBox(c, this, cx + stick[0], cy + stick[1], dynamic, stick[1]);
    }

    HtmlNode node() { return node; }
    boolean anonymous() { return isAnonymous; }
    float stickyDy() { return stickyDy; }
    Style backdrop() { return layer.backdrop(); }
    Display display() { return layer.display; }
    ComputedStyle style() { return layer.style; }
    DomValues values() { return layer.values(); }
    ComputedStyle axisStyle() { return layer.style; }
    SceneAdapterBox paintParent() { return parent; }

    List<SceneAdapterBox> paintChildren() {
        if (kids == null) {
            SceneChildren sc = layer.children;
            List<SceneAdapterBox> out = new ArrayList<>(sc.size);
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


    SceneAdapterBox originParent() {
        SceneAdapterBox p = parent;
        while (p != null && !p.layer.isOrigin()) p = p.parent;
        return p;
    }

    float x() { return x; }
    float y() { return y; }
    float w() { return layer.w; }
    float h() { return layer.h; }
    float bT() { return layer.bT; }
    float bR() { return layer.bR; }
    float bB() { return layer.bB; }
    float bL() { return layer.bL; }
    float pT() { return layer.pT; }
    float pR() { return layer.pR; }
    float pB() { return layer.pB; }
    float pL() { return layer.pL; }
    float mT() { return layer.mT; }
    float mR() { return layer.mR; }
    float mB() { return layer.mB; }
    float mL() { return layer.mL; }
    boolean isScrollContainer() { return layer.isScrollContainer(); }
    boolean isCollapsedTable() { return layer.isCollapsedTable(); }
    float scrollWidth() { return layer.scrollWidth; }
    float scrollHeight() { return layer.scrollHeight; }
    float lineHeightPx() { return layer.lineHeight; }
    boolean isAbsolute() { return layer.isAbsolute(); }
    boolean isVerticalWrapper() { return layer.verticalWrapper(); }
    boolean interactiveRules() { return layer.interactive(); }
    float[] collapsedBorders() { return layer.collapsedBorders; }
    float[][] columnRules() { return null; }
    float[] firstLineBgRect() { return layer.firstLineBgRect; }
    ComputedStyle firstLine() { return layer.firstLine; }
    ComputedStyle placeholder() { return null; }

    SceneAdapterBox renderedLegend() {
        List<SceneAdapterBox> ch = paintChildren();
        for (int i = 0; i < ch.size(); i++) {
            SceneAdapterBox c = ch.get(i);
            if (layer.children.isRenderedLegend(c.layer)) {
                return c.layer.display != null && c.layer.display.isNone() ? null : c;
            }
        }
        return null;
    }

    List<ShapedLine> shapedLines() { return null; }

    int displayTraits() {
        Display d = layer.display;
        if (d == null) return 0;
        Display.Inner inner = d.inner();
        return (inner == Display.Inner.TABLE_CELL ? TABLE_CELL : 0) | (d.isNone() ? DISPLAY_NONE : 0)
                | (d.isInlineLevel() && inner != Display.Inner.FLOW ? ATOMIC_INLINE : 0)
                | (inner == Display.Inner.FLEX || inner == Display.Inner.GRID ? FLEX_OR_GRID : 0);
    }

    boolean inFlexOrGrid() {
        SceneAdapterBox p = paintParent();
        return p != null && (p.displayTraits() & FLEX_OR_GRID) != 0;
    }

    boolean atomicInline() { return (displayTraits() & ATOMIC_INLINE) != 0; }
    boolean tableCell() { return (displayTraits() & TABLE_CELL) != 0; }
    boolean displayNone() { return (displayTraits() & DISPLAY_NONE) != 0; }

    @Override
    public String toString() {
        return "SceneAdapterBox[" + (layer.node != null ? layer.node.key() : "?") + " @" + x + "," + y + "]";
    }
}
