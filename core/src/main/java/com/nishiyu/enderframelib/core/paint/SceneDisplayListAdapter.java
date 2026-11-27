package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.css.model.CssKeywords;
import com.nishiyu.enderframelib.core.css.model.Keyframes;
import com.nishiyu.enderframelib.core.dom.node.ElementTraits;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.Corners;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.LayerSpec;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;
import com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo;
import com.nishiyu.enderframelib.core.paint.model.StickyShift;
import com.nishiyu.enderframelib.core.paint.scene.DisplayScene;
import com.nishiyu.enderframelib.core.paint.scene.DynamicSceneState;
import com.nishiyu.enderframelib.core.paint.scene.SceneAnchorFollow;
import com.nishiyu.enderframelib.core.paint.scene.SceneBox;
import com.nishiyu.enderframelib.core.paint.scene.SceneLayer;
import com.nishiyu.enderframelib.core.paint.scene.StackingKind;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.nishiyu.enderframelib.core.paint.PaintSupport.addElement;

/**
 * {@link DisplayScene} と {@link DynamicSceneState} から従来の {@link DisplayItem} の列を出す一時アダプタ
 * （M3b-2。計画書 §2.3・§3.4。バックエンドが {@code DisplayScene} を直接描く M3b-4 で削除する）。
 *
 * <p>記憶は一切持たず（規範ルール 3）、描画のたびに {@link DisplayScene} を CSS 2.2 付録 E の層順に走査する。</p>
 */
public final class SceneDisplayListAdapter {
    private static final int DEFAULT_CANVAS_COLOR = 0x00000000;
    private static final int MASK_ID_BASE = 1_000_000;
    private static final ItemProps EMPTY_PROPS = new ItemProps();
    private static final RenderMeta MOVING_NODE_META = new RenderMeta(null, null, null, 12.0f, 0.5f, 0.5f, 1, false);
    private static final Corners SCROLLBAR_CORNERS = new Corners(2, 2, 2, 2);

    private final AtomicInteger fragIds = new AtomicInteger();
    private final BoxItemPainter painter;
    private final PaintSupport.CaptureList scratch = new PaintSupport.CaptureList(64);
    private int rootW, rootH;
    private float devicePixelRatio = 1f;

    // ---- 1 回の走査の間だけ使う ----
    private DynamicSceneState dynamic;
    private SceneBox canvasSource;
    private Set<HtmlNode> followedScrollers = Set.of();
    private final Map<SceneBox, ClipRect> contentClips = new IdentityHashMap<>();
    private final Map<SceneBox, ChildContext> childContexts = new IdentityHashMap<>();
    private final Map<SceneBox, ScrollContent> scrollContentOf = new IdentityHashMap<>();
    private final List<ScrollContent> scrollContentOrder = new ArrayList<>();
    private final List<ScrollContentInfo> scrollInfos = new ArrayList<>();
    private List<DisplayItem> out;

    private record ChildContext(ClipRect clip, String parentId) { }

    private record ZEntry(SceneBox box, ClipRect clip, String parentId, boolean hoist) { }

    private static final class ScrollContent {
        final SceneBox box;
        final String contentId;
        final float sx0, sy0;
        String thumbVId, thumbHId;
        float kx, ky, fx0, fy0;
        List<StickyShift> stickies = List.of();

        ScrollContent(SceneBox box, String contentId, float sx0, float sy0) {
            this.box = box;
            this.contentId = contentId;
            this.sx0 = sx0;
            this.sy0 = sy0;
        }
    }

    public SceneDisplayListAdapter(int rootW, int rootH, Map<String, Keyframes> keyframes) {
        this.rootW = rootW;
        this.rootH = rootH;
        this.painter = new BoxItemPainter(rootW, rootH, keyframes != null ? keyframes : Map.of(), fragIds);
    }

    public void setDevicePixelRatio(float dpr) {
        this.devicePixelRatio = dpr;
    }

    public void setViewport(int rootW, int rootH) {
        this.rootW = rootW;
        this.rootH = rootH;
        this.painter.setViewport(rootW, rootH);
    }

    public List<ScrollContentInfo> scrollContents() {
        return new ArrayList<>(scrollInfos);
    }

    public List<DisplayItem> emit(DisplayScene scene, DynamicSceneState dynamicState) {
        this.dynamic = dynamicState != null ? dynamicState : DynamicSceneState.of(null, rootW, rootH);
        this.painter.decor.setDevicePixelRatio(devicePixelRatio);
        this.fragIds.set(0);
        this.contentClips.clear();
        this.childContexts.clear();
        this.scrollContentOf.clear();
        this.scrollContentOrder.clear();
        this.scrollInfos.clear();
        this.followedScrollers = SceneAnchorFollow.followedScrollers(scene);
        this.canvasSource = null;

        SceneBox top = SceneBox.root(scene, this.dynamic);
        List<DisplayItem> result = new ArrayList<>();
        this.out = result;
        try {
            if ("root".equals(top.node().tag)) {
                int canvasBg = DEFAULT_CANVAS_COLOR;
                SceneBox source = SceneAdapterSupport.canvasBackgroundSource(top);
                if (source != null) {
                    canvasBg = source.style().typed().visual.backgroundColor;
                    canvasSource = source;
                }
                if (canvasBg != 0x00000000) {
                    long key = source != null && source.node() != null ? DisplayItem.bgKeyOf(source.node().nodeId()) : 0L;
                    PaintSupport.CaptureList bg = new PaintSupport.CaptureList(1);
                    PaintSupport.addElement(bg, "bg_root", "bg_root", ElementType.RECT, top.x(), top.y(),
                            Math.max(top.w(), rootW), Math.max(top.h(), rootH), "", 0, canvasBg, EMPTY_PROPS,
                            0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, RenderMeta.EMPTY, key);
                    result.add(bg.get(0));
                }
                emitRootChildren(top);
            } else {
                emitSubtreeRoot(top);
            }
            for (ScrollContent c : scrollContentOrder) {
                scrollInfos.add(new ScrollContentInfo(c.box.node(), c.contentId, c.thumbVId, c.thumbHId,
                        c.sx0, c.sy0, c.kx, c.ky, c.fx0, c.fy0, c.stickies));
            }
            return result;
        } finally {
            this.out = null;
            this.dynamic = null;
            this.canvasSource = null;
            this.followedScrollers = Set.of();
            this.contentClips.clear();
            this.childContexts.clear();
            this.scrollContentOf.clear();
            this.scrollContentOrder.clear();
        }
    }

    private void emitRootChildren(SceneBox top) {
        ChildContext cc = enterChildren(top, null, null);
        emitZPass(top, cc.clip, cc.parentId, SceneLayer.LAYER_NEG);
        emitBlocksPass(top, cc.clip, cc.parentId);
        emitFloatsPass(top, cc.clip, cc.parentId);
        emitInlinesPass(top, cc.clip, cc.parentId);
        emitPos0Pass(top, cc.clip, cc.parentId);
        emitZPass(top, cc.clip, cc.parentId, SceneLayer.LAYER_POSZ | SceneLayer.LAYER_HOIST);
        emitAfterPass(top, cc.clip, cc.parentId);
    }

    private void emitSubtreeRoot(SceneBox top) {
        if (!isVisible(top)) return;
        boolean valueControl = ElementTraits.isValueControl(top.node().tag);
        ChildContext cc = valueControl ? null : enterChildren(top, null, null);
        if (top.layer.hasSelf()) {
            drawOwn(top, null, null);
        }
        finishScrollContent(top);
        if (!valueControl) {
            emitZPass(top, cc.clip, cc.parentId, SceneLayer.LAYER_NEG);
            emitBlocksPass(top, cc.clip, cc.parentId);
            emitFloatsPass(top, cc.clip, cc.parentId);
            emitInlinesPass(top, cc.clip, cc.parentId);
            emitPos0Pass(top, cc.clip, cc.parentId);
            emitZPass(top, cc.clip, cc.parentId, SceneLayer.LAYER_POSZ | SceneLayer.LAYER_HOIST);
            emitAfterPass(top, cc.clip, cc.parentId);
            if (top.layer.wantsScrollbars()) drawScrollbars(top, null);
        }
    }

    private void emitStacking(SceneBox box, ClipRect clip, String parentId) {
        boolean hasSelf = box.layer.hasSelf();
        LayerSpec spec = hasSelf ? SceneAdapterSupport.layerSpecFor(box, rootW, rootH) : null;
        boolean ownNode = SceneAdapterSupport.boxHasOwnNode(box);
        boolean valueControl = ElementTraits.isValueControl(box.node().tag);
        int startIdx = out.size();
        ChildContext cc = valueControl ? null : enterChildren(box, clip, parentId);
        if (hasSelf) drawOwn(box, clip, parentId);
        finishScrollContent(box);
        if (!valueControl) {
            int kidsLayers = box.layer.children.propagatedLayers;
            if ((kidsLayers & SceneLayer.LAYER_NEG) != 0) emitZPass(box, cc.clip, cc.parentId, SceneLayer.LAYER_NEG);
            if ((kidsLayers & SceneLayer.LAYER_BLOCK) != 0) emitBlocksPass(box, cc.clip, cc.parentId);
            if ((kidsLayers & SceneLayer.LAYER_FLOAT) != 0) emitFloatsPass(box, cc.clip, cc.parentId);
            if ((kidsLayers & SceneLayer.LAYER_INLINE) != 0) emitInlinesPass(box, cc.clip, cc.parentId);
            if ((kidsLayers & SceneLayer.LAYER_POS0) != 0) emitPos0Pass(box, cc.clip, cc.parentId);
            if ((kidsLayers & SceneLayer.LAYER_POSZ) != 0) emitZPass(box, cc.clip, cc.parentId, SceneLayer.LAYER_POSZ);
            if ((kidsLayers & SceneLayer.LAYER_AFTER) != 0) emitAfterPass(box, cc.clip, cc.parentId);
            if (box.layer.wantsScrollbars()) drawScrollbars(box, clip);
        }
        if (spec != null && out.size() > startIdx) {
            if (spec.wantsMask()) {
                int contentSpan = out.size() - startIdx;
                scratch.resetForBuild();
                fragIds.set(MASK_ID_BASE);
                painter.drawMask(scratch, box, ownNode);
                if (!scratch.isEmpty()) {
                    spec = spec.withMaskFrom(contentSpan);
                    out.addAll(scratch);
                } else {
                    spec = spec.withMaskFrom(-1);
                }
            }
            out.set(startIdx, BoxItemPainter.withLayer(out.get(startIdx), spec.withSpan(out.size() - startIdx)));
        }
    }

    private void emitPlain(SceneBox box, ClipRect clip, String parentId) {
        boolean valueControl = ElementTraits.isValueControl(box.node().tag);
        ChildContext cc = valueControl ? null : enterChildren(box, clip, parentId);
        if (box.layer.hasSelf()) drawOwn(box, clip, parentId);
        finishScrollContent(box);
        if (!valueControl) {
            int kidsLayers = box.layer.children.propagatedLayers;
            if ((kidsLayers & SceneLayer.LAYER_BLOCK) != 0) emitBlocksPass(box, cc.clip, cc.parentId);
            if ((kidsLayers & SceneLayer.LAYER_FLOAT) != 0) emitFloatsPass(box, cc.clip, cc.parentId);
            if ((kidsLayers & SceneLayer.LAYER_INLINE) != 0) emitInlinesPass(box, cc.clip, cc.parentId);
            if ((kidsLayers & SceneLayer.LAYER_AFTER) != 0) emitAfterPass(box, cc.clip, cc.parentId);
            if (box.layer.wantsScrollbars()) drawScrollbars(box, clip);
        }
    }

    private void emitZPass(SceneBox rootBox, ClipRect clip, String parentId, int mask) {
        List<ZEntry> entries = new ArrayList<>();
        collectZPass(rootBox, clip, parentId, mask, entries);
        if (entries.isEmpty()) return;
        entries.sort((a, b) -> Integer.compare(a.box.layer.zIndex, b.box.layer.zIndex));
        for (ZEntry e : entries) {
            if (e.hoist && e.box.backdrop() != null) {
                scratch.resetForBuild();
                painter.drawBackdrop(scratch, e.box, e.box.backdrop(), SceneAdapterSupport.boxHasOwnNode(e.box));
                out.addAll(scratch);
            }
            emitStacking(e.box, e.clip, e.parentId);
        }
    }

    private void collectZPass(SceneBox container, ClipRect clip, String parentId, int mask, List<ZEntry> outEntries) {
        for (SceneBox child : container.paintChildren()) {
            int prop = child.layer.propagatedLayers;
            if ((prop & mask) == 0 || !isVisible(child)) continue;
            ClipRect cClip = clipForChild(child, clip);
            if (child.layer.stackingKind == StackingKind.STACKING) {
                boolean topLayer = child.layer.isTopLayer();
                int z = child.layer.zIndex;
                boolean matches = topLayer ? (mask & SceneLayer.LAYER_HOIST) != 0
                        : z < 0 ? (mask & SceneLayer.LAYER_NEG) != 0
                        : z > 0 && (mask & SceneLayer.LAYER_POSZ) != 0;
                if (matches) outEntries.add(new ZEntry(child, cClip, parentId, topLayer));
                if ((mask & SceneLayer.LAYER_HOIST) != 0
                        && !ElementTraits.isValueControl(child.node().tag)
                        && (child.layer.children.propagatedLayers & SceneLayer.LAYER_HOIST) != 0) {
                    ChildContext cc = enterChildren(child, cClip, parentId);
                    collectZPass(child, cc.clip, cc.parentId, SceneLayer.LAYER_HOIST, outEntries);
                }
            } else if (!ElementTraits.isValueControl(child.node().tag)) {
                ChildContext cc = enterChildren(child, cClip, parentId);
                collectZPass(child, cc.clip, cc.parentId, mask, outEntries);
            }
        }
    }

    private void emitBlocksPass(SceneBox container, ClipRect clip, String parentId) {
        for (SceneBox child : container.paintChildren()) {
            if ((child.layer.propagatedLayers & SceneLayer.LAYER_BLOCK) == 0 || !isVisible(child)) continue;
            ClipRect cClip = clipForChild(child, clip);
            StackingKind k = child.layer.stackingKind;
            if (k == StackingKind.BLOCK) {
                boolean valueControl = ElementTraits.isValueControl(child.node().tag);
                ChildContext cc = valueControl ? null : enterChildren(child, cClip, parentId);
                if (child.layer.hasSelf()) drawOwn(child, cClip, parentId);
                finishScrollContent(child);
                if (!valueControl && (child.layer.children.propagatedLayers & SceneLayer.LAYER_BLOCK) != 0) {
                    emitBlocksPass(child, cc.clip, cc.parentId);
                }
            } else if (k == StackingKind.INLINE && !ElementTraits.isValueControl(child.node().tag)
                    && (child.layer.children.propagatedLayers & SceneLayer.LAYER_BLOCK) != 0) {
                ChildContext cc = enterChildren(child, cClip, parentId);
                emitBlocksPass(child, cc.clip, cc.parentId);
            }
        }
    }

    private void emitFloatsPass(SceneBox container, ClipRect clip, String parentId) {
        for (SceneBox child : container.paintChildren()) {
            if ((child.layer.propagatedLayers & SceneLayer.LAYER_FLOAT) == 0 || !isVisible(child)) continue;
            ClipRect cClip = clipForChild(child, clip);
            StackingKind k = child.layer.stackingKind;
            if (k == StackingKind.FLOAT) {
                emitPlain(child, cClip, parentId);
            } else if ((k == StackingKind.BLOCK || k == StackingKind.INLINE)
                    && !ElementTraits.isValueControl(child.node().tag)) {
                ChildContext cc = enterChildren(child, cClip, parentId);
                emitFloatsPass(child, cc.clip, cc.parentId);
            }
        }
    }

    private void emitInlinesPass(SceneBox container, ClipRect clip, String parentId) {
        for (SceneBox child : container.paintChildren()) {
            if ((child.layer.propagatedLayers & SceneLayer.LAYER_INLINE) == 0 || !isVisible(child)) continue;
            ClipRect cClip = clipForChild(child, clip);
            StackingKind k = child.layer.stackingKind;
            if (k == StackingKind.ATOMIC) {
                emitPlain(child, cClip, parentId);
            } else if (k == StackingKind.INLINE) {
                boolean valueControl = ElementTraits.isValueControl(child.node().tag);
                ChildContext cc = valueControl ? null : enterChildren(child, cClip, parentId);
                if (child.layer.hasSelf()) drawOwn(child, cClip, parentId);
                finishScrollContent(child);
                if (!valueControl && (child.layer.children.propagatedLayers & SceneLayer.LAYER_INLINE) != 0) {
                    emitInlinesPass(child, cc.clip, cc.parentId);
                }
            } else if (k == StackingKind.BLOCK && !ElementTraits.isValueControl(child.node().tag)) {
                ChildContext cc = enterChildren(child, cClip, parentId);
                emitInlinesPass(child, cc.clip, cc.parentId);
            }
        }
    }

    private void emitPos0Pass(SceneBox container, ClipRect clip, String parentId) {
        for (SceneBox child : container.paintChildren()) {
            if ((child.layer.propagatedLayers & SceneLayer.LAYER_POS0) == 0 || !isVisible(child)) continue;
            ClipRect cClip = clipForChild(child, clip);
            StackingKind k = child.layer.stackingKind;
            if (k == StackingKind.STACKING) {
                if (!child.layer.isTopLayer() && child.layer.zIndex == 0) emitStacking(child, cClip, parentId);
            } else if (k == StackingKind.POSITIONED) {
                emitPlain(child, cClip, parentId);
                if (!ElementTraits.isValueControl(child.node().tag)
                        && (child.layer.children.propagatedLayers & SceneLayer.LAYER_POS0) != 0) {
                    ChildContext cc = enterChildren(child, cClip, parentId);
                    emitPos0Pass(child, cc.clip, cc.parentId);
                }
            } else if (!ElementTraits.isValueControl(child.node().tag)) {
                ChildContext cc = enterChildren(child, cClip, parentId);
                emitPos0Pass(child, cc.clip, cc.parentId);
            }
        }
    }

    private void emitAfterPass(SceneBox container, ClipRect clip, String parentId) {
        for (SceneBox child : container.paintChildren()) {
            if ((child.layer.propagatedLayers & SceneLayer.LAYER_AFTER) == 0 || !isVisible(child)) continue;
            ClipRect cClip = clipForChild(child, clip);
            StackingKind k = child.layer.stackingKind;
            if ((k == StackingKind.BLOCK || k == StackingKind.INLINE)
                    && !ElementTraits.isValueControl(child.node().tag)) {
                ChildContext cc = enterChildren(child, cClip, parentId);
                if ((child.layer.children.propagatedLayers & SceneLayer.LAYER_AFTER) != 0) {
                    emitAfterPass(child, cc.clip, cc.parentId);
                }
                if (child.layer.wantsScrollbars()) {
                    drawScrollbars(child, cClip);
                }
            }
        }
    }

    private boolean isVisible(SceneBox box) {
        SceneLayer l = box.layer;
        if (l.displayNone() || ElementTraits.isOption(l.node.tag)) return false;
        return !l.contentVisibilityAuto() || dynamic.nearViewport(box.x(), box.y(), box.w(), box.h());
    }

    private void drawOwn(SceneBox box, ClipRect clip, String parentId) {
        boolean ownNode = SceneAdapterSupport.boxHasOwnNode(box);
        SceneBox origin = SceneAdapterSupport.effectiveParentNodeBox(box, parentId);
        scratch.resetForBuild();
        fragIds.set(0);
        painter.drawBox(scratch, box, clip, parentId, null, origin, ownNode, box == canvasSource);
        ScrollContent content = scrollContentOf.get(box);
        if (content != null) {
            int sox = scratch.originX, soy = scratch.originY;
            if (ownNode) scratch.setOrigin(Math.round(box.x()), Math.round(box.y()));
            try {
                SceneAdapterSupport.addMovingNode(scratch, content.contentId, box, null, MOVING_NODE_META);
            } finally {
                scratch.setOrigin(sox, soy);
            }
        }
        out.addAll(scratch);
    }

    private ChildContext enterChildren(SceneBox box, ClipRect clip, String parentId) {
        ChildContext cached = childContexts.get(box);
        if (cached != null) return cached;
        String tag = box.node().tag;
        ClipRect childClip = SceneAdapterSupport.computeChildClipRect(box, clip, rootW, rootH);
        contentClips.put(box, childClip);
        String childParent = "root".equals(tag) ? null : box.anonymous() ? parentId : box.node().key();
        List<StickyShift> stickies = SceneAdapterSupport.scrollsAsOneNode(box, followedScrollers, rootW, rootH);
        if (stickies != null) {
            ScrollContent content = new ScrollContent(box, box.node().scrollContentKey(),
                    dynamic.scroll.getScrollX(box.node()), dynamic.scroll.getScrollY(box.node()));
            content.stickies = stickies;
            scrollContentOf.put(box, content);
            scrollContentOrder.add(content);
            childParent = content.contentId;
        }
        ChildContext cc = new ChildContext(childClip, childParent);
        childContexts.put(box, cc);
        return cc;
    }

    private void finishScrollContent(SceneBox box) {
        // drawScrollbars は AFTER パスで動くため、ScrollContentInfo の確定は emit の末尾で行う
    }

    private ClipRect clipForChild(SceneBox child, ClipRect flowClip) {
        if (!child.isAbsolute()) return flowClip;
        SceneBox cb = SceneAdapterSupport.containingBlock(child);
        if (cb == null) return null;
        return contentClips.containsKey(cb) ? contentClips.get(cb) : flowClip;
    }

    private void drawScrollbars(SceneBox box, ClipRect clipRect) {
        if (box.style().typed().box.scrollbarWidth == CssKeywords.ScrollbarWidth.NONE) return;
        scratch.resetForBuild();
        int prevOx = scratch.originX, prevOy = scratch.originY;
        if (SceneAdapterSupport.boxHasOwnNode(box)) scratch.setOrigin(Math.round(box.x()), Math.round(box.y()));
        try {
            float scrollW = box.scrollWidth(), scrollH = box.scrollHeight();
            float curX = dynamic.scroll.getScrollX(box.node()), curY = dynamic.scroll.getScrollY(box.node());
            int nodeHandle = box.node() != null ? box.node().paintHandle() : 0;
            long nodeKey = nodeHandle > 0 ? DisplayItem.nodeKeyOf(nodeHandle) : 0L;
            String ownerId = box.node().key();
            ItemProps sbEvents = new ItemProps();
            sbEvents.parentId(ownerId, nodeKey);
            ScrollContent content = scrollContentOf.get(box);
            RenderMeta sbMeta = new RenderMeta(clipRect, SCROLLBAR_CORNERS, null, 12.0f, 0f, 0f, 0.5f, 0.5f, 1, false, null, sbEvents.hints());
            int bar = 4;
            if (scrollH > box.h() + 2 && box.h() > 12) {
                float trackX = box.x() + box.w() - box.bR() - bar - 2, trackY = box.y() + box.bT() + 2;
                float trackH = box.h() - box.bT() - box.bB() - 4;
                float thumbH = Math.round(Math.max(12, trackH * (box.h() / scrollH)));
                float maxScrollY = Math.max(1f, scrollH - box.h());
                float thumbY = trackY + (trackH - thumbH) * Math.clamp(curY / maxScrollY, 0f, 1f);
                addElement(scratch, ownerId, box.node().partKey(12, "_sb_vt"), ElementType.RECT, trackX, trackY, bar, trackH, "", 0, 0x33000000,
                        sbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, sbMeta,
                        nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_SCROLLBAR_TRACK_V) : 0L);
                ItemProps thumbEvents = sbEvents;
                RenderMeta thumbMeta = sbMeta;
                if (content != null) {
                    content.thumbVId = box.node().partKey(13, "_scrollv");
                    content.ky = (trackH - thumbH) / maxScrollY;
                    content.fy0 = thumbY - Math.round(thumbY);
                    SceneAdapterSupport.addMovingNode(scratch, content.thumbVId, box, sbMeta, MOVING_NODE_META);
                    thumbEvents = new ItemProps();
                    thumbEvents.parentId(content.thumbVId, nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_SCROLLBAR_NODE_V) : 0L);
                    thumbMeta = new RenderMeta(clipRect, SCROLLBAR_CORNERS, null, 12.0f, 0f, 0f, 0.5f, 0.5f, 1, false, null, thumbEvents.hints());
                }
                addElement(scratch, ownerId, box.node().partKey(14, "_sb_v"), ElementType.RECT, trackX, thumbY, bar, thumbH, "", 0, 0x88CCCCCC,
                        thumbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, thumbMeta,
                        nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_SCROLLBAR_V) : 0L);
            }
            if (scrollW > box.w() + 2 && box.w() > 12) {
                float trackX = box.x() + box.bL() + 2, trackY = box.y() + box.h() - box.bB() - bar - 2;
                float trackW = box.w() - box.bL() - box.bR() - 4;
                float thumbW = Math.round(Math.max(12, trackW * (box.w() / scrollW)));
                float maxScrollX = Math.max(1f, scrollW - box.w());
                float thumbX = trackX + (trackW - thumbW) * Math.clamp(curX / maxScrollX, 0f, 1f);
                addElement(scratch, ownerId, box.node().partKey(15, "_sb_ht"), ElementType.RECT, trackX, trackY, trackW, bar, "", 0, 0x33000000,
                        sbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, sbMeta,
                        nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_SCROLLBAR_TRACK_H) : 0L);
                ItemProps thumbEvents = sbEvents;
                RenderMeta thumbMeta = sbMeta;
                if (content != null) {
                    content.thumbHId = box.node().partKey(16, "_scrollh");
                    content.kx = (trackW - thumbW) / maxScrollX;
                    content.fx0 = thumbX - Math.round(thumbX);
                    SceneAdapterSupport.addMovingNode(scratch, content.thumbHId, box, sbMeta, MOVING_NODE_META);
                    thumbEvents = new ItemProps();
                    thumbEvents.parentId(content.thumbHId, nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_SCROLLBAR_NODE_H) : 0L);
                    thumbMeta = new RenderMeta(clipRect, SCROLLBAR_CORNERS, null, 12.0f, 0f, 0f, 0.5f, 0.5f, 1, false, null, thumbEvents.hints());
                }
                addElement(scratch, ownerId, box.node().partKey(17, "_sb_h"), ElementType.RECT, thumbX, trackY, thumbW, bar, "", 0, 0x88CCCCCC,
                        thumbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, thumbMeta,
                        nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_SCROLLBAR_H) : 0L);
            }
            out.addAll(scratch);
        } finally {
            scratch.setOrigin(prevOx, prevOy);
        }
    }
}
