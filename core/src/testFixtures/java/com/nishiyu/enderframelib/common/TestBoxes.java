package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.box.Display;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.scene.DisplayScene;
import com.nishiyu.enderframelib.core.paint.scene.DynamicSceneState;
import com.nishiyu.enderframelib.core.paint.scene.SceneChunk;
import com.nishiyu.enderframelib.core.paint.scene.SceneChildren;
import com.nishiyu.enderframelib.core.paint.scene.SceneLayer;
import com.nishiyu.enderframelib.core.paint.scene.SceneRun;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * テスト用の補助（M3b-3）: {@link DisplayScene} と {@link DynamicSceneState} から箱の配置・寸法を辿る。
 */
public final class TestBoxes {
    private TestBoxes() { }

    /** {@link SceneLayer} に絶対位置と親子を持たせたテスト用の見え方。 */
    public static final class Box {
        public final SceneLayer layer;
        public final HtmlNode node;
        public final boolean isAnonymous;
        private final Box parent;
        private final float x, y;
        private final DynamicSceneState dynamic;
        private final float stickyDy;
        private List<Box> kids;

        private Box(SceneLayer layer, Box parent, float x, float y, DynamicSceneState dynamic, float stickyDy) {
            this.layer = layer;
            this.node = layer.node;
            this.isAnonymous = layer.anonymous();
            this.parent = parent;
            this.x = x;
            this.y = y;
            this.dynamic = dynamic;
            this.stickyDy = stickyDy;
        }

        private Box createChild(SceneLayer c, float chunkOx, float chunkOy) {
            Box o = layer.isOrigin() ? this : originParent();
            float ox = o != null ? o.x : 0f, oy = o != null ? o.y : 0f;
            if (o != null && o.isScrollContainer()) {
                ox -= dynamic.scrollX(o.layer);
                oy -= dynamic.scrollY(o.layer);
            }
            float cx = ox + chunkOx + c.x;
            float cy = oy + chunkOy + c.y;
            if (c.follow == null && c.sticky == null) return new Box(c, this, cx, cy, dynamic, 0f);
            float[] d = dynamic.anchorShift(c);
            if (d != null) { cx += d[0]; cy += d[1]; }
            float[] stick = null;
            if (c.sticky != null) {
                Box port = this;
                while (port != null && !port.isScrollContainer()) port = port.parent;
                float portX = port != null ? port.x + port.bL() : 0f;
                float portY = port != null ? port.y + port.bT() : 0f;
                float portW = port != null ? Math.max(0f, port.w() - port.bL() - port.bR()) : dynamic.viewportW;
                float portH = port != null ? Math.max(0f, port.h() - port.bT() - port.bB()) : dynamic.viewportH;
                stick = dynamic.stickyOffset(c, cx, cy, portX, portY, portW, portH,
                        x + bL() + pL(), y + bT() + pT(), x + w() - bR() - pR(), y + h() - bB() - pB());
            }
            return stick == null ? new Box(c, this, cx, cy, dynamic, 0f)
                    : new Box(c, this, cx + stick[0], cy + stick[1], dynamic, stick[1]);
        }

        public HtmlNode node() { return node; }
        public boolean anonymous() { return isAnonymous; }
        public Display display() { return layer.display; }
        public ComputedStyle style() { return layer.style; }
        public ComputedStyle axisStyle() { return layer.style; }
        public Box paintParent() { return parent; }
        public Box originParent() {
            Box p = parent;
            while (p != null && !p.layer.isOrigin()) p = p.parent;
            return p;
        }
        public List<Box> paintChildren() {
            if (kids == null) {
                SceneChildren sc = layer.children;
                List<Box> out = new ArrayList<>(sc.size);
                for (int k = 0; k < sc.chunks.length; k++) {
                    SceneChunk c = sc.chunks[k];
                    float cx = sc.chunkX[k], cy = sc.chunkY[k];
                    for (SceneLayer child : c.layers) out.add(createChild(child, cx, cy));
                }
                kids = out;
            }
            return kids;
        }
        public List<SceneRun> runs() { return layer.ops().runList(); }
        public float x() { return x; }
        public float y() { return y; }
        public float w() { return layer.w; }
        public float h() { return layer.h; }
        public float bT() { return layer.bT; }
        public float bR() { return layer.bR; }
        public float bB() { return layer.bB; }
        public float bL() { return layer.bL; }
        public float pT() { return layer.pT; }
        public float pR() { return layer.pR; }
        public float pB() { return layer.pB; }
        public float pL() { return layer.pL; }
        public float mT() { return layer.mT; }
        public float mR() { return layer.mR; }
        public float mB() { return layer.mB; }
        public float mL() { return layer.mL; }
        public boolean isScrollContainer() { return layer.isScrollContainer(); }
        public boolean isCollapsedTable() { return layer.isCollapsedTable(); }
        public float scrollWidth() { return layer.scrollWidth; }
        public float scrollHeight() { return layer.scrollHeight; }
        public float lineHeightPx() { return layer.lineHeight; }
        public boolean isAbsolute() { return layer.isAbsolute(); }
        public boolean isVerticalWrapper() { return layer.verticalWrapper(); }
        public boolean displayNone() { return layer.display != null && layer.display.isNone(); }
        public int displayTraits() {
            Display d = layer.display;
            if (d == null) return 0;
            Display.Inner inner = d.inner();
            return (inner == Display.Inner.TABLE_CELL ? 1 : 0) | (d.isNone() ? 2 : 0)
                    | (d.isInlineLevel() && inner != Display.Inner.FLOW ? 4 : 0)
                    | (inner == Display.Inner.FLEX || inner == Display.Inner.GRID ? 8 : 0);
        }
        public float stickyDy() { return stickyDy; }
        public float[][] columnRules() { return null; }
        public Box renderedLegend() {
            for (Box c : paintChildren()) {
                if (c.layer.renderedLegend()) return c.displayNone() ? null : c;
            }
            return null;
        }
    }

    public static Box root(DisplayScene scene) {
        return root(scene, (DynamicSceneState) null);
    }

    public static Box root(DisplayScene scene, DynamicSceneState dynamic) {
        return scene != null ? root(scene.root, dynamic) : null;
    }

    public static Box root(SceneLayer rootLayer, DynamicSceneState dynamic) {
        if (rootLayer == null) return null;
        DynamicSceneState dyn = dynamic != null ? dynamic : DynamicSceneState.of(null, rootLayer.w, rootLayer.h);
        return new Box(rootLayer, null, 0f, 0f, dyn, 0f);
    }

    public static Box root(FrameRender.Frame frame) {
        return frame != null ? root(frame.scene(), frame.dynamic()) : null;
    }

    public static Box root(DisplayScene scene, ScrollState scroll, int w, int h) {
        return scene != null ? root(scene, DynamicSceneState.of(scroll, w, h)) : null;
    }

    public static Box root(UiView ui) {
        return ui != null ? root(ui.scene(), ui.dynamicState()) : null;
    }

    public static Box layout(String html, int w, int h) {
        return layout(html, w, h, FontResolver.FALLBACK);
    }

    public static Box layout(String html, int w, int h, FontResolver fonts) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument(html, "", w, h);
        FrameRender.Frame f = FrameRender.document(doc, w, h, Map.of(), new ScrollState(), fonts, new InteractionState());
        return root(f.scene(), f.dynamic());
    }

    public static List<DisplayItem> render(String html, int w, int h) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument(html, "", w, h);
        return FrameRender.document(doc, w, h, FontResolver.FALLBACK).items();
    }

    public static Box find(Box root, String id) {
        if (root == null) return null;
        if (!root.isAnonymous && root.node != null && !root.node.isPseudoNode && id.equals(root.node.attrs.get("id"))) return root;
        for (Box c : root.paintChildren()) {
            Box hit = find(c, id);
            if (hit != null) return hit;
        }
        return null;
    }

    public static Box byNode(Box root, HtmlNode node) {
        if (root == null) return null;
        if (!root.isAnonymous && root.node == node && (node == null || !node.isPseudoNode)) return root;
        for (Box c : root.paintChildren()) {
            Box hit = byNode(c, node);
            if (hit != null) return hit;
        }
        return null;
    }

    public static List<Box> elements(Box box) {
        List<Box> out = new ArrayList<>();
        collectElements(box, out);
        return out;
    }

    private static void collectElements(Box box, List<Box> out) {
        for (Box c : box.paintChildren()) {
            if (c.isAnonymous) {
                if (c.node == null || c.node.getId() == null || !c.node.getId().endsWith("_marker")) collectElements(c, out);
            } else if (c.node != null) {
                out.add(c);
            }
        }
    }

    public static List<Box> elementBoxes(Box box) {
        List<Box> out = new ArrayList<>(elements(box));
        out.removeIf(b -> b.node.nodeType != NodeType.ELEMENT);
        return out;
    }
}
