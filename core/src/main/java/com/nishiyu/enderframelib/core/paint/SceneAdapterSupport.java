package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.LengthResolver;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.VisualStyle;
import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.ClipShape;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.FilterOp;
import com.nishiyu.enderframelib.core.paint.model.LayerSpec;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;
import com.nishiyu.enderframelib.core.paint.model.StickyShift;
import com.nishiyu.enderframelib.core.paint.scene.LayerEffects;
import com.nishiyu.enderframelib.core.paint.scene.StackingKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.nishiyu.enderframelib.core.paint.PaintSupport.addElement;

/**
 * {@link SceneDisplayListAdapter} 用の静的補助（クリップ・スクロール纏め・層仕様・背景ソース）。M3b-4 でアダプタと一緒に削除する。
 */
final class SceneAdapterSupport {
    private SceneAdapterSupport() { }

    static boolean boxHasOwnNode(SceneAdapterBox b) {
        return b != null && !b.anonymous() && b.w() > 0f && b.h() > 0f && b.node() != null
                && !"root".equals(b.node().tag) && !"#text".equals(b.node().tag);
    }

    static boolean isPositioned(SceneAdapterBox box) {
        return box.layer.stackingKind == StackingKind.POSITIONED
                || box.style().typed().box.position != com.nishiyu.enderframelib.core.css.model.Position.STATIC;
    }

    static SceneAdapterBox containingBlock(SceneAdapterBox box) {
        boolean fixed = box.layer.isFixed();
        for (SceneAdapterBox cur = box.paintParent(); cur != null; cur = cur.paintParent()) {
            VisualStyle v = cur.style().typed().visual;
            boolean transformLike = !PaintFacts.isNone(v.transform) || !PaintFacts.isNone(v.filter)
                    || (cur.style().containment() & (ComputedStyle.CONTAIN_LAYOUT | ComputedStyle.CONTAIN_PAINT)) != 0;
            if (transformLike || (!fixed && isPositioned(cur))) return cur;
        }
        return null;
    }

    static SceneAdapterBox effectiveParentNodeBox(SceneAdapterBox box, String parentId) {
        if (parentId == null || box == null) return null;
        for (SceneAdapterBox a = box.paintParent(); a != null; a = a.paintParent()) {
            if (a.node() != null && "root".equals(a.node().tag)) return null;
            if (a.anonymous()) continue;
            return boxHasOwnNode(a) ? a : null;
        }
        return null;
    }

    static List<StickyShift> scrollsAsOneNode(SceneAdapterBox box, Set<HtmlNode> followedScrollers, int rootW, int rootH) {
        if (box.anonymous() || box.w() <= 0 || box.h() <= 0 || !box.isScrollContainer()) return null;
        String tag = box.node().tag;
        if ("root".equals(tag) || "html".equals(tag) || "body".equals(tag)) return null;
        if (followedScrollers.contains(box.node())) return null;
        List<SceneAdapterBox> stickies = new ArrayList<>(0);
        if (hasUnscrollableDescendant(box, box, false, stickies, rootW, rootH)) return null;
        if (stickies.isEmpty()) return List.of();
        List<StickyShift> shifts = new ArrayList<>(stickies.size());
        for (SceneAdapterBox s : stickies) {
            float[] p = boxHasOwnNode(s) ? stickyScrollParams(s, box, rootW, rootH) : null;
            if (p == null) return null;
            StickyShift shift = new StickyShift(s.node().key(), p[0], p[1], p[2], p[3], p[4], p[5] != 0f, s.stickyDy());
            if (Float.floatToIntBits(shift.dyAt(0f)) != Float.floatToIntBits(s.stickyDy())) return null;
            shifts.add(shift);
        }
        return shifts;
    }

    static float[] stickyScrollParams(SceneAdapterBox box, SceneAdapterBox port, int rootW, int rootH) {
        SceneAdapterBox parent = box.paintParent();
        if (box.layer.sticky == null || parent == null) return null;
        SceneAdapterBox near = parent;
        while (near != null && !near.isScrollContainer()) near = near.paintParent();
        if (near != port) return null;
        if (!box.layer.sticky.isTopOnly()) return null;
        float portY = port.y() + port.bT();
        float portH = Math.max(0f, port.h() - port.bT() - port.bB());
        float limit = portY + box.layer.sticky.topPx(portH, rootW, rootH);
        float cbBottom = parent.y() + parent.h() - parent.bB() - parent.pB();
        return new float[]{limit, cbBottom, box.y() - box.stickyDy(), box.h(), box.mB(), parent != port ? 1f : 0f};
    }

    static boolean hasUnscrollableDescendant(SceneAdapterBox container, SceneAdapterBox box, boolean underSticky,
                                             List<SceneAdapterBox> stickies, int rootW, int rootH) {
        for (SceneAdapterBox child : box.paintChildren()) {
            if (child.displayNone()) continue;
            if (child.layer.isFixed() || child.layer.contentVisibilityAuto()) return true;
            if (child.layer.sticky != null) {
                if (underSticky || stickyScrollParams(child, container, rootW, rootH) == null) return true;
                stickies.add(child);
                if (hasUnscrollableDescendant(container, child, true, stickies, rootW, rootH)) return true;
                continue;
            }
            if (child.isAbsolute()) {
                SceneAdapterBox cb = containingBlock(child);
                if (cb == null) return true;
                boolean inside = false;
                for (SceneAdapterBox a = cb; a != null; a = a.paintParent()) {
                    if (a == container) { inside = true; break; }
                }
                if (!inside) return true;
            }
            if (hasUnscrollableDescendant(container, child, underSticky, stickies, rootW, rootH)) return true;
        }
        return false;
    }

    static ClipRect computeChildClipRect(SceneAdapterBox box, ClipRect parentClip, int rootW, int rootH) {
        String tag = box.node().tag;
        if ("root".equals(tag) || "html".equals(tag) || "body".equals(tag) || "#text".equals(tag)) return parentClip;
        boolean clipX = box.layer.clipsX(), clipY = box.layer.clipsY();
        if (!clipX && !clipY) return parentClip;
        float minX = clipX ? box.x() + box.bL() : -PaintFacts.UNCLIPPED;
        float minY = clipY ? box.y() + box.bT() : -PaintFacts.UNCLIPPED;
        float maxX = clipX ? box.x() + box.w() - box.bR() : PaintFacts.UNCLIPPED;
        float maxY = clipY ? box.y() + box.h() - box.bB() : PaintFacts.UNCLIPPED;
        float rx0 = 0f, rx1 = 0f, rx2 = 0f, rx3 = 0f, ry0 = 0f, ry1 = 0f, ry2 = 0f, ry3 = 0f;
        boolean elliptical = false;
        if (clipX && clipY) {
            float[] r = LengthResolver.cornerRadiiXYShared(box.axisStyle(), box.w(), box.h(), rootW, rootH);
            for (int i = 0; i < 4; i++) elliptical |= Math.round(r[i]) != Math.round(r[4 + i]);
            if (!elliptical) {
                rx0 = Math.min(r[0], r[4]) - Math.max(box.bT(), box.bL());
                rx1 = Math.min(r[1], r[5]) - Math.max(box.bT(), box.bR());
                rx2 = Math.min(r[2], r[6]) - Math.max(box.bB(), box.bR());
                rx3 = Math.min(r[3], r[7]) - Math.max(box.bB(), box.bL());
            } else {
                rx0 = Math.max(0f, r[0] - box.bL()); ry0 = Math.max(0f, r[4] - box.bT());
                rx1 = Math.max(0f, r[1] - box.bR()); ry1 = Math.max(0f, r[5] - box.bT());
                rx2 = Math.max(0f, r[2] - box.bR()); ry2 = Math.max(0f, r[6] - box.bB());
                rx3 = Math.max(0f, r[3] - box.bL()); ry3 = Math.max(0f, r[7] - box.bB());
            }
        }
        int ox = boxHasOwnNode(box) && clipX ? Math.round(box.x()) : 0;
        int oy = boxHasOwnNode(box) && clipY ? Math.round(box.y()) : 0;
        float[] chainOwn = elliptical
                ? new float[]{minX - ox, minY - oy, maxX - ox, maxY - oy, rx0, rx1, rx2, rx3, ry0, ry1, ry2, ry3}
                : new float[]{minX - ox, minY - oy, maxX - ox, maxY - oy, rx0, rx1, rx2, rx3};
        return BoxDecorations.intersectClipRect(parentClip, minX, minY, maxX, maxY, rx0, rx1, rx2, rx3, ry0, ry1, ry2, ry3,
                elliptical, null, ClipRect.appendChain(parentClip, chainOwn, box.node().key(), ox, oy));
    }

    static LayerSpec layerSpecFor(SceneAdapterBox box, int rootW, int rootH) {
        LayerEffects fx = box.layer.effects;
        if (fx == null) return null;
        boolean rel = boxHasOwnNode(box);
        float lx = rel ? box.x() - Math.round(box.x()) : box.x();
        float ly = rel ? box.y() - Math.round(box.y()) : box.y();
        Style st = box.style().typed();
        float fontPx = st.inherited.fontSize, rootFontPx = st.inherited.rootFontSizePx;
        ClipShape clip = null;
        if (box.layer.clip != null) {
            float[] border = {lx, ly, box.w(), box.h()};
            float[] padding = {lx + box.bL(), ly + box.bT(), Math.max(0, box.w() - box.bL() - box.bR()), Math.max(0, box.h() - box.bT() - box.bB())};
            float[] content = {padding[0] + box.pL(), padding[1] + box.pT(), Math.max(0, padding[2] - box.pL() - box.pR()), Math.max(0, padding[3] - box.pT() - box.pB())};
            clip = ClipShape.parse(st.visual.clipPath, ClipShape.Context.of(border, padding, content, rootW, rootH, fontPx, rootFontPx));
        }
        ClipShape backdropShape = null;
        List<FilterOp> backdrop = fx.backdropFilters();
        if (!backdrop.isEmpty()) {
            float[] r = LengthResolver.cornerRadiiXYShared(box.axisStyle(), box.w(), box.h(), rootW, rootH);
            float[] d = new float[]{lx, ly, lx + box.w(), ly + box.h(), 0, 0, 0, 0, 0, 0, 0, 0};
            System.arraycopy(r, 0, d, 4, 8);
            backdropShape = new ClipShape(ClipShape.Kind.INSET, d, false);
        }
        return new LayerSpec(0, fx.opacity(), fx.filters(), fx.blendMode(), fx.isolate(), clip,
                fx.masked() ? LayerSpec.WANTS_MASK : -1, fx.luminanceMask(), backdrop, backdropShape);
    }

    static void addMovingNode(List<DisplayItem> elements, String id, SceneAdapterBox box, RenderMeta meta, RenderMeta movingNodeMeta) {
        ItemProps events = new ItemProps();
        int nodeHandle = box.node() != null ? box.node().paintHandle() : 0;
        long nodeKey = nodeHandle > 0 ? DisplayItem.nodeKeyOf(nodeHandle) : 0L;
        events.parentId(box.node() != null ? box.node().key() : id, nodeKey);
        events.pointerEventsNone();
        boolean isV = id.endsWith("_scrollv"), isH = !isV && id.endsWith("_scrollh");
        long movingKey = nodeHandle > 0 ? (isV ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_SCROLLBAR_NODE_V)
                : isH ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_SCROLLBAR_NODE_H)
                : id.endsWith(PaintSceneDriver.SCROLL_CONTENT_SUFFIX) ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_SCROLL_CONTENT)
                : DisplayItem.nodeKeyOf(nodeHandle)) : 0L;
        RenderMeta baseMeta = (meta == null ? RenderMeta.EMPTY : movingNodeMeta).withHints(events.hints());
        addElement(elements, id, id, ElementType.NODE, box.x(), box.y(), box.w(), box.h(), "", 0, 0, events,
                0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, baseMeta, movingKey);
    }

    static SceneAdapterBox canvasBackgroundSource(SceneAdapterBox root) {
        SceneAdapterBox html = childWithTag(root, "html");
        if (html != null && hasBackground(html)) return html;
        SceneAdapterBox body = childWithTag(html != null ? html : root, "body");
        return body != null && hasBackground(body) ? body : null;
    }

    static SceneAdapterBox childWithTag(SceneAdapterBox parent, String tag) {
        for (SceneAdapterBox child : parent.paintChildren()) {
            if (tag.equals(child.node().tag)) return child;
        }
        return null;
    }

    static boolean hasBackground(SceneAdapterBox box) {
        VisualStyle v = box.style().typed().visual;
        return v.hasBackgroundColor || (v.backgroundImage != null && !"none".equals(v.backgroundImage));
    }
}
