package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.core.css.ColorParser;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.CssParser.CssTokenizer;
import com.nishiyu.enderframelib.core.css.EasingFunction;
import com.nishiyu.enderframelib.core.css.LengthResolver;
import com.nishiyu.enderframelib.core.layout.BlockLayoutEngine;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.LayoutContext.ScrollState;
import com.nishiyu.enderframelib.core.model.AnimationSpec;
import com.nishiyu.enderframelib.core.model.UIElement.ClipRect;
import com.nishiyu.enderframelib.core.model.UIElement.Corners;
import com.nishiyu.enderframelib.core.model.UIElement.ElementType;
import com.nishiyu.enderframelib.core.model.UIElement.ImageFitMode;
import com.nishiyu.enderframelib.core.model.UIElement.RenderKeys;
import com.nishiyu.enderframelib.core.model.UIElement;
import com.nishiyu.enderframelib.core.text.TextShaper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UIRenderer {

    private static final Pattern COLOR_TOKEN_PATTERN = Pattern.compile(
            "(#[0-9a-fA-F]{3,8}|rgba?\\s*\\([^)]+\\)|hsla?\\s*\\([^)]+\\)|\\b(?:red|green|lime|blue|yellow|aqua|cyan|magenta|purple|orange|gold|black|white|gray|grey)\\b)"
    );
    private static final Pattern TRANSLATE_X_PATTERN = Pattern.compile("translateX\\(\\s*(-?[0-9.]+)(px|%)\\s*\\)");
    private static final Pattern TRANSLATE_Y_PATTERN = Pattern.compile("translateY\\(\\s*(-?[0-9.]+)(px|%)\\s*\\)");
    private static final Pattern TRANSLATE_PATTERN = Pattern.compile("translate\\(\\s*(-?[0-9.]+)(px|%)(?:\\s*,\\s*(-?[0-9.]+)(px|%))?\\s*\\)");
    private static final Pattern SCALE_PATTERN = Pattern.compile("scale\\(\\s*(-?[0-9.]+)(?:\\s*,\\s*(-?[0-9.]+))?\\s*\\)");
    private static final Pattern ROTATE_PATTERN = Pattern.compile("rotate\\(\\s*(-?[0-9.]+)(deg|rad)?\\s*\\)");
    private static final Pattern TIME_UNIT_PATTERN = Pattern.compile("([0-9]*\\.?[0-9]+)(ms|s)");
    private static final Pattern CUBIC_BEZIER_SPAN_PATTERN = Pattern.compile("cubic-bezier\\([^)]*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE_SPLIT = Pattern.compile("\\s+");

    private static final Set<String> NON_FRAGMENT_TAGS = Set.of(
            "#text", "img", "mc-fluid", "mc-entity", "mc-scene",
            "input", "select", "progress", "meter", "textarea", "canvas"
    );

    private static final Set<String> EASING_KEYWORDS = Set.of(
            "ease", "linear", "ease-in", "ease-out", "ease-in-out"
    );

    private static final String[] PASSTHROUGH_DATA_ATTRS = {
            "data-sound", "data-sound-pitch", "draggable", "data-count",
            "data-ghost-slot", "data-pan-zoom", "for", "tabindex"
    };

    private static final String[] BORDER_DIRECTIONS = {"top", "right", "bottom", "left"};

    private final int rootW;
    private final int rootH;
    private final Map<String, String> keyframes;
    private final ScrollState scrollState;
    private int fragIdCounter = 0;
    private int domOrderCounter = 0;

    public UIRenderer(int rootW, int rootH, Map<String, String> keyframes, ScrollState scrollState) {
        this.rootW = rootW;
        this.rootH = rootH;
        this.keyframes = keyframes != null ? keyframes : Map.of();
        this.scrollState = scrollState;
    }

    public List<UIElement> render(LayoutBox box) {
        fragIdCounter = 0;
        domOrderCounter = 0;
        List<RenderTask> renderQueue = new ArrayList<>();
        List<UIElement> elements = new ArrayList<>();

        if ("root".equals(box.node.tag)) {
            int canvasBg = 0xFF111111;
            LayoutBox bodyBox = findBodyBox(box);
            if (bodyBox != null && (bodyBox.style.containsKey(CssProperty.BACKGROUND) || bodyBox.style.containsKey(CssProperty.BACKGROUND_COLOR))) {
                String bodyBg = bodyBox.style.getOrDefault(CssProperty.BACKGROUND, bodyBox.style.get(CssProperty.BACKGROUND_COLOR));
                canvasBg = ColorParser.parseColor(bodyBg);
            }
            if (canvasBg != 0x00000000) {
                addElement(elements, "bg_root", "bg_root", ElementType.RECT, box.x, box.y, Math.max(box.w, rootW), Math.max(box.h, rootH),
                        "", 0, canvasBg, Map.of(), 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, UIElement.RenderMeta.EMPTY);
            }

            for (LayoutBox child : box.children) {
                buildRenderQueue(child, renderQueue, 0, null, null);
            }
        } else {
            buildRenderQueue(box, renderQueue, 0, null, null);
        }

        sortPaintOrder(renderQueue);

        for (RenderTask task : renderQueue) {
            task.drawAction().accept(elements);
        }
        return elements;
    }

    /**
     * 箱の描画順（先に描くものが先頭）。{@link #render} と同じ並べ替えを通す。
     *
     * 忠実度コーパスが z-order をブラウザと突き合わせるための読み口
     * （{@code FidelityDumper} の paint 列）。描画順の決め方を変えるときは
     * ここも同じ経路を通るので、テストが描画と食い違うことはない。
     */
    public List<LayoutBox> paintOrder(LayoutBox root) {
        fragIdCounter = 0;
        domOrderCounter = 0;
        List<RenderTask> queue = new ArrayList<>();
        if ("root".equals(root.node.tag)) {
            for (LayoutBox child : root.children) buildRenderQueue(child, queue, 0, null, null);
        } else {
            buildRenderQueue(root, queue, 0, null, null);
        }
        sortPaintOrder(queue);
        List<LayoutBox> order = new ArrayList<>(queue.size());
        for (RenderTask task : queue) {
            if (task.box() != null) order.add(task.box());
        }
        return order;
    }

    private static void sortPaintOrder(List<RenderTask> queue) {
        queue.sort(Comparator.comparingInt(RenderTask::zIndex).thenComparingInt(RenderTask::layer)
                .thenComparingInt(RenderTask::domOrder));
    }

    private LayoutBox findBodyBox(LayoutBox box) {
        if ("body".equals(box.node.tag)) return box;
        for (LayoutBox child : box.children) {
            LayoutBox found = findBodyBox(child);
            if (found != null) return found;
        }
        return null;
    }

    private static float parseOriginProp(String p) {
        return switch (p) {
            case "left", "top" -> 0.0f;
            case "right", "bottom" -> 1.0f;
            case "center" -> 0.5f;
            default -> {
                if (p.endsWith("%")) {
                    try {
                        yield Float.parseFloat(p.substring(0, p.length() - 1)) / 100f;
                    } catch (NumberFormatException ignored) {}
                }
                yield 0.5f;
            }
        };
    }

    private static UIElement.GradientSpec parseGradientSpec(String bgProp) {
        if (bgProp == null || !bgProp.toLowerCase(Locale.ROOT).contains("linear-gradient")) return null;
        String lower = bgProp.toLowerCase(Locale.ROOT);
        String dir = "bottom";
        if (lower.contains("to right") || lower.contains("90deg")) dir = "right";
        else if (lower.contains("to left") || lower.contains("270deg") || lower.contains("-90deg")) dir = "left";
        else if (lower.contains("to top") || lower.contains("0deg")) dir = "top";
        else if (lower.contains("135deg") || lower.contains("to bottom right")) dir = "diag";

        List<Integer> colors = new ArrayList<>(2);
        Matcher m = COLOR_TOKEN_PATTERN.matcher(bgProp);
        while (m.find()) {
            colors.add(ColorParser.parseColor(m.group(1)));
        }
        if (colors.size() >= 2) {
            return new UIElement.GradientSpec(dir, colors.getFirst(), colors.getLast());
        }
        return null;
    }

    private void buildRenderQueue(LayoutBox box, List<RenderTask> queue, int parentZIndex, float[] clipRect, String parentId) {
        buildRenderQueue(box, queue, parentZIndex, 0, clipRect, parentId);
    }

    /**
     * {@code layer} は同じ z-index の中での層。float とその中身は通常フローのブロックより上（CSS 2.2 付録 E の
     * 層 5 が層 4 より上）、位置指定要素とその中身はさらに上（層 6）に描く。以前は文書順だけで、
     * 後続のブロックの背景が float と位置指定要素を隠していた。
     */
    private void buildRenderQueue(LayoutBox box, List<RenderTask> queue, int parentZIndex, int parentLayer,
                                  float[] clipRect, String parentId) {
        if ("none".equals(box.style.getOrDefault(CssProperty.DISPLAY, "block"))) return;
        if ("option".equalsIgnoreCase(box.node.tag)) return;

        // LIMITATION(制約): z-index を親に足し込むだけで、スタッキングコンテキストが無い。
        // 中の z-index が外へ漏れ、opacity / transform はコンテキストを作らず、静的なブロックの
        // z-index も効いてしまう（層は float と位置指定だけを分けている）。
        // W5 で直す。FIDELITY.md の「W5 — 描画モデル」と README の "Not supported" に対応。
        int currentZ = box.zIndex != 0 ? (parentZIndex + box.zIndex) : parentZIndex;
        // 匿名ボックスは描くものを持たない。子の持ち主は匿名ボックスの外の要素のまま
        String currentId = box.isAnonymous ? parentId : box.node.getId();
        int order = domOrderCounter++;
        int layer = parentLayer;
        if (box.isRelative || box.isAbsolute || box.isSticky) layer = 2;
        else if (BlockLayoutEngine.isFloating(box)) layer = Math.max(parentLayer, 1);

        if (box.w > 0 && box.h > 0 && !box.isAnonymous) {
            queue.add(new RenderTask(currentZ, layer, order, box, elements -> drawBoxSelf(elements, box, clipRect, parentId)));
        }

        String tag = box.node.tag;
        if ("select".equalsIgnoreCase(tag) || "textarea".equalsIgnoreCase(tag) || "input".equalsIgnoreCase(tag)) {
            return;
        }

        float[] childClip = computeChildClipRect(box, clipRect);
        for (LayoutBox child : box.children) {
            buildRenderQueue(child, queue, currentZ, layer, childClip, currentId);
        }

        String overflow = box.style.getOrDefault(CssProperty.OVERFLOW, "visible");
        if (box.w > 0 && box.h > 0 && ("scroll".equals(overflow) || "auto".equals(overflow))) {
            int sbOrder = domOrderCounter++;
            queue.add(new RenderTask(currentZ + 1, layer, sbOrder, null, elements -> drawScrollbarsIfNeeded(elements, box, clipRect)));
        }
    }

    private float[] computeChildClipRect(LayoutBox box, float[] parentClip) {
        String tag = box.node.tag;
        boolean isRootElement = "root".equals(tag) || "html".equals(tag) || "body".equals(tag);
        if (isRootElement) return parentClip;

        String overflow = box.style.getOrDefault(CssProperty.OVERFLOW, "visible");
        boolean shouldClip = "hidden".equals(overflow) || "auto".equals(overflow)
                || "scroll".equals(overflow) || "clip".equals(overflow)
                || "true".equalsIgnoreCase(box.node.attrs.get("data-pan-zoom"));
        if (!shouldClip) return parentClip;

        float nMinX = box.x + box.bL;
        float nMinY = box.y + box.bT;
        float nMaxX = box.x + box.w - box.bR;
        float nMaxY = box.y + box.h - box.bB;

        float[] radii = LengthResolver.parseBorderRadius(box.style.getOrDefault(CssProperty.BORDER_RADIUS, "0"), box.w, rootW, rootH);
        float maxBorder = Math.max(Math.max(box.bT, box.bB), Math.max(box.bL, box.bR));

        float limit = Math.min(nMaxX - nMinX, nMaxY - nMinY) / 2.0f;
        float cRTL = Math.clamp(radii[0] - maxBorder, 0, limit);
        float cRTR = Math.clamp(radii[1] - maxBorder, 0, limit);
        float cRBR = Math.clamp(radii[2] - maxBorder, 0, limit);
        float cRBL = Math.clamp(radii[3] - maxBorder, 0, limit);

        if (parentClip != null) {
            nMinX = Math.max(parentClip[0], nMinX);
            nMinY = Math.max(parentClip[1], nMinY);
            nMaxX = Math.min(parentClip[2], nMaxX);
            nMaxY = Math.min(parentClip[3], nMaxY);
        }
        return new float[]{nMinX, nMinY, nMaxX, nMaxY, cRTL, cRTR, cRBR, cRBL};
    }

    private void drawScrollbarsIfNeeded(List<UIElement> elements, LayoutBox box, float[] clipRect) {
        if ("none".equals(box.style.get(CssProperty.SCROLLBAR_WIDTH))) return;

        float scrollW = box.scrollWidth;
        float scrollH = box.scrollHeight;
        float curX = scrollState.getScrollX(box.node.getId());
        float curY = scrollState.getScrollY(box.node.getId());

        Map<String, String> sbEvents = new HashMap<>(4);
        sbEvents.put(RenderKeys.MC_PARENT, box.node.getId());
        ClipRect sbClip = normalizeClipRect(clipRect);
        UIElement.RenderMeta sbMeta = new UIElement.RenderMeta(sbClip, new Corners(2, 2, 2, 2), null, null, 12.0f, 0.5f, 0.5f, 1, false);

        int barThickness = 4;
        if (scrollH > box.h + 2 && box.h > 12) {
            float trackX = box.x + box.w - box.bR - barThickness - 2;
            float trackY = box.y + box.bT + 2;
            float trackH = box.h - box.bT - box.bB - 4;
            float thumbH = Math.max(12, trackH * (box.h / scrollH));
            float maxScrollY = Math.max(1f, scrollH - box.h);
            float thumbY = trackY + (trackH - thumbH) * Math.clamp(curY / maxScrollY, 0f, 1f);

            addElement(elements, box.node.getId(), box.node.getId() + "_sb_vt", ElementType.RECT, trackX, trackY, barThickness, trackH, "", 0, 0x33000000, sbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, sbMeta);
            addElement(elements, box.node.getId(), box.node.getId() + "_sb_v", ElementType.RECT, trackX, thumbY, barThickness, thumbH, "", 0, 0x88CCCCCC, sbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, sbMeta);
        }
        if (scrollW > box.w + 2 && box.w > 12) {
            float trackX = box.x + box.bL + 2;
            float trackY = box.y + box.h - box.bB - barThickness - 2;
            float trackW = box.w - box.bL - box.bR - 4;
            float thumbW = Math.max(12, trackW * (box.w / scrollW));
            float maxScrollX = Math.max(1f, scrollW - box.w);
            float thumbX = trackX + (trackW - thumbW) * Math.clamp(curX / maxScrollX, 0f, 1f);

            addElement(elements, box.node.getId(), box.node.getId() + "_sb_ht", ElementType.RECT, trackX, trackY, trackW, barThickness, "", 0, 0x33000000, sbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, sbMeta);
            addElement(elements, box.node.getId(), box.node.getId() + "_sb_h", ElementType.RECT, thumbX, trackY, thumbW, barThickness, "", 0, 0x88CCCCCC, sbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, sbMeta);
        }
    }

    /**
     * 描画要素を 1 つ足す。レイアウトの float 座標を整数に丸めるのはここ 1 箇所だけ（軸 3）。
     *
     * 幅と高さを個別に丸めず、左右（上下）の辺をそれぞれ丸めて差を取る。こうすると
     * 隣り合う箱の辺が同じ整数に落ち、端数の幅が並んでも隙間や重なりが出ない。
     * 以前はレイアウトの各段が int に切り捨てていたため、丸め誤差が入れ子の深さだけ溜まっていた。
     */
    private void addElement(List<UIElement> elements, String baseNodeId, String id, ElementType type, ImageFitMode fitMode,
                            float x, float y, float w, float h, String text,
                            int color, int bgColor, Map<String, String> events, int radius, float opacity,
                            float tx, float ty, float sx, float sy, float rot, int trans, AnimationSpec animDef,
                            UIElement.RenderMeta meta) {
        int left = Math.round(x);
        int top = Math.round(y);
        int width = Math.round(x + w) - left;
        int height = Math.round(y + h) - top;
        elements.add(new UIElement(id, baseNodeId, type, left, top, width, height, text, color, bgColor, events, radius, opacity, tx, ty, sx, sy, rot, trans, animDef, fitMode, meta));
    }

    private void addElement(List<UIElement> elements, String baseNodeId, String id, ElementType type,
                            float x, float y, float w, float h, String text,
                            int color, int bgColor, Map<String, String> events, int radius, float opacity,
                            float tx, float ty, float sx, float sy, float rot, int trans, AnimationSpec animDef,
                            UIElement.RenderMeta meta) {
        addElement(elements, baseNodeId, id, type, ImageFitMode.FILL, x, y, w, h, text, color, bgColor, events, radius, opacity, tx, ty, sx, sy, rot, trans, animDef, meta);
    }

    private static String resolveLiveInputValue(String nodeId, String attrValue) {
        return attrValue != null ? attrValue : "";
    }

    private static float[] parseTransformProps(String t, float boxW, float boxH, float defTx, float defTy, float defSx, float defSy, float defRot) {
        float tx = defTx, ty = defTy, sx = defSx, sy = defSy, rot = defRot;
        if (t == null || t.isEmpty()) return new float[]{tx, ty, sx, sy, rot};

        Matcher txM = TRANSLATE_X_PATTERN.matcher(t);
        if (txM.find()) {
            tx = "%".equals(txM.group(2)) ? (Float.parseFloat(txM.group(1)) / 100.0f) * boxW : Float.parseFloat(txM.group(1));
        }
        Matcher tyM = TRANSLATE_Y_PATTERN.matcher(t);
        if (tyM.find()) {
            ty = "%".equals(tyM.group(2)) ? (Float.parseFloat(tyM.group(1)) / 100.0f) * boxH : Float.parseFloat(tyM.group(1));
        }
        Matcher tm = TRANSLATE_PATTERN.matcher(t);
        if (tm.find()) {
            tx = "%".equals(tm.group(2)) ? (Float.parseFloat(tm.group(1)) / 100.0f) * boxW : Float.parseFloat(tm.group(1));
            if (tm.group(3) != null) {
                ty = "%".equals(tm.group(4)) ? (Float.parseFloat(tm.group(3)) / 100.0f) * boxH : Float.parseFloat(tm.group(3));
            }
        }
        Matcher sm = SCALE_PATTERN.matcher(t);
        if (sm.find()) {
            sx = Float.parseFloat(sm.group(1));
            sy = sm.group(2) != null ? Float.parseFloat(sm.group(2)) : sx;
        }
        Matcher rm = ROTATE_PATTERN.matcher(t);
        if (rm.find()) {
            float val = Float.parseFloat(rm.group(1));
            if ("rad".equals(rm.group(2))) val = (float) Math.toDegrees(val);
            rot = val;
        }
        return new float[]{tx, ty, sx, sy, rot};
    }

    /**
     * CSS の {@code animation} ショートハンド・各ロングハンドと {@code @keyframes} を
     * {@link AnimationSpec} に解決する。
     */
    private AnimationSpec parseAnimationDef(ComputedStyle style, float baseW, float baseH, int baseColor, int baseBgColor) {
        String animProp = style.get(CssProperty.ANIMATION);
        String name = "";
        int dur = 1000;
        int delay = 0;
        float iterationCount = 1.0f;
        AnimationSpec.Direction direction = AnimationSpec.Direction.NORMAL;
        AnimationSpec.FillMode fillMode = AnimationSpec.FillMode.NONE;
        String easing = EasingFunction.DEFAULT_EASING;

        if (animProp != null && !"none".equalsIgnoreCase(animProp.trim())) {
            easing = EasingFunction.extractFromShorthand(animProp);
            String cleaned = CUBIC_BEZIER_SPAN_PATTERN.matcher(animProp).replaceAll(" ").trim();
            String[] parts = WHITESPACE_SPLIT.split(cleaned);
            int timeCount = 0;
            for (String p : parts) {
                if (p.isEmpty()) continue;
                String lower = p.toLowerCase(Locale.ROOT);
                Matcher tm = TIME_UNIT_PATTERN.matcher(lower);
                if (tm.matches()) {
                    int ms = parseTimeTokenMs(tm);
                    if (timeCount == 0) dur = ms;
                    else if (timeCount == 1) delay = ms;
                    timeCount++;
                } else if ("infinite".equals(lower)) {
                    iterationCount = Float.POSITIVE_INFINITY;
                } else if ("normal".equals(lower) || "reverse".equals(lower)
                        || "alternate".equals(lower) || "alternate-reverse".equals(lower)) {
                    direction = AnimationSpec.Direction.fromCss(lower);
                } else if ("forwards".equals(lower) || "backwards".equals(lower) || "both".equals(lower)) {
                    fillMode = AnimationSpec.FillMode.fromCss(lower);
                } else if (EASING_KEYWORDS.contains(lower)) {
                    easing = lower;
                } else if (keyframes.containsKey(p)) {
                    name = p;
                } else {
                    try {
                        iterationCount = Float.parseFloat(lower);
                    } catch (NumberFormatException ignored) {
                        if (name.isEmpty()) name = p;
                    }
                }
            }
        }

        // 個別ロングハンドの上書き
        String nameProp = style.get(CssProperty.ANIMATION_NAME);
        if (nameProp != null && !nameProp.isBlank()) {
            name = nameProp.trim();
        }
        if (name.isEmpty() || "none".equalsIgnoreCase(name) || !keyframes.containsKey(name)) {
            return AnimationSpec.NONE;
        }

        String durProp = style.get(CssProperty.ANIMATION_DURATION);
        if (durProp != null) {
            Matcher tm = TIME_UNIT_PATTERN.matcher(durProp.trim().toLowerCase(Locale.ROOT));
            if (tm.find()) dur = parseTimeTokenMs(tm);
        }

        String delayProp = style.get(CssProperty.ANIMATION_DELAY);
        if (delayProp != null) {
            Matcher tm = TIME_UNIT_PATTERN.matcher(delayProp.trim().toLowerCase(Locale.ROOT));
            if (tm.find()) delay = parseTimeTokenMs(tm);
        }

        String iterProp = style.get(CssProperty.ANIMATION_ITERATION_COUNT);
        if (iterProp != null && !iterProp.isBlank()) {
            String trimmed = iterProp.trim().toLowerCase(Locale.ROOT);
            if ("infinite".equals(trimmed)) {
                iterationCount = Float.POSITIVE_INFINITY;
            } else {
                try {
                    iterationCount = Float.parseFloat(trimmed);
                } catch (NumberFormatException ignored) {}
            }
        }

        String dirProp = style.get(CssProperty.ANIMATION_DIRECTION);
        if (dirProp != null && !dirProp.isBlank()) {
            direction = AnimationSpec.Direction.fromCss(dirProp);
        }

        String fillProp = style.get(CssProperty.ANIMATION_FILL_MODE);
        if (fillProp != null && !fillProp.isBlank()) {
            fillMode = AnimationSpec.FillMode.fromCss(fillProp);
        }

        String tfProp = style.get(CssProperty.ANIMATION_TIMING_FUNCTION);
        if (tfProp != null && !tfProp.isBlank()) {
            easing = tfProp.trim();
        }

        List<AnimationSpec.Keyframe> frames = new ArrayList<>();
        int animatedChannels = 0;

        for (String frame : keyframes.get(name).split("\\|")) {
            if (frame.isEmpty()) continue;
            int tickIdx = frame.indexOf('`');
            if (tickIdx <= 0) continue;
            String props = frame.substring(tickIdx + 1);

            float pct;
            try {
                pct = Float.parseFloat(frame.substring(0, tickIdx));
            } catch (NumberFormatException e) {
                continue;
            }

            Map<String, String> decls = CssTokenizer.parseDeclarations(props);
            int definedChannels = 0;

            float[] tr = new float[]{0f, 0f, 1f, 1f, 0f};
            if (decls.containsKey(CssProperty.TRANSFORM)) {
                tr = parseTransformProps(decls.get(CssProperty.TRANSFORM), baseW, baseH, 0f, 0f, 1f, 1f, 0f);
                definedChannels |= AnimationSpec.CH_TRANSFORM;
            }

            float op = 1f;
            if (decls.containsKey(CssProperty.OPACITY)) {
                try {
                    op = Float.parseFloat(decls.get(CssProperty.OPACITY).trim());
                    definedChannels |= AnimationSpec.CH_OPACITY;
                } catch (NumberFormatException ignored) {}
            }

            int col = baseColor;
            if (decls.containsKey(CssProperty.COLOR)) {
                int parsed = ColorParser.parseColor(decls.get(CssProperty.COLOR));
                if (parsed != 0x00000000) {
                    col = parsed;
                    definedChannels |= AnimationSpec.CH_COLOR;
                }
            }

            int bgCol = baseBgColor;
            String bgDecl = decls.getOrDefault(CssProperty.BACKGROUND_COLOR, decls.get(CssProperty.BACKGROUND));
            if (bgDecl != null) {
                bgCol = ColorParser.parseColor(bgDecl);
                definedChannels |= AnimationSpec.CH_BG_COLOR;
            }

            animatedChannels |= definedChannels;
            frames.add(new AnimationSpec.Keyframe(pct, tr[0], tr[1], tr[2], tr[3], tr[4], op, col, bgCol, definedChannels));
        }

        if (frames.isEmpty() || animatedChannels == 0) return AnimationSpec.NONE;
        return new AnimationSpec(dur, delay, iterationCount, direction, fillMode, easing, animatedChannels, frames);
    }

    private static int parseTimeTokenMs(Matcher tm) {
        try {
            float val = Float.parseFloat(tm.group(1));
            if ("s".equals(tm.group(2))) val *= 1000f;
            return Math.round(val);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * CSS の {@code transition} ショートハンドとロングハンド（{@code transition-property},
     * {@code transition-duration}, {@code transition-delay}, {@code transition-timing-function}）を解決する。
     * CSS 既定のイージングは {@code ease}（{@code cubic-bezier(0.25, 0.1, 0.25, 1.0)}）。
     */
    private static TransitionSpec parseTransitionSpec(Map<String, String> style) {
        int durationMs = 0;
        int delayMs = 0;
        String easing = EasingFunction.DEFAULT_EASING;
        String property = "all";

        String tr = style.get(CssProperty.TRANSITION);
        if (tr != null && !tr.isBlank()) {
            if ("none".equalsIgnoreCase(tr.trim())) {
                property = "none";
            } else {
                easing = EasingFunction.extractFromShorthand(tr);
                List<String> entries = CssTokenizer.splitTopLevel(tr, ',');
                List<String> propNames = new ArrayList<>(entries.size());

                for (int idx = 0; idx < entries.size(); idx++) {
                    String entry = entries.get(idx).trim();
                    if (entry.isEmpty()) continue;
                    String entryWithoutBezier = CUBIC_BEZIER_SPAN_PATTERN.matcher(entry).replaceAll(" ").trim();
                    String[] tokens = WHITESPACE_SPLIT.split(entryWithoutBezier);
                    int entryTimeCount = 0;
                    for (String tok : tokens) {
                        if (tok.isEmpty()) continue;
                        String lower = tok.toLowerCase(Locale.ROOT);
                        Matcher tm = TIME_UNIT_PATTERN.matcher(lower);
                        if (tm.matches()) {
                            int ms = parseTimeTokenMs(tm);
                            if (idx == 0) {
                                if (entryTimeCount == 0) durationMs = ms;
                                else if (entryTimeCount == 1) delayMs = ms;
                            } else if (entryTimeCount == 0 && durationMs == 0) {
                                durationMs = ms;
                            }
                            entryTimeCount++;
                        } else if (!EASING_KEYWORDS.contains(lower)) {
                            propNames.add(lower);
                        }
                    }
                }
                if (!propNames.isEmpty()) {
                    property = String.join(",", propNames);
                }
            }
        }

        String tp = style.get(CssProperty.TRANSITION_PROPERTY);
        if (tp != null && !tp.isBlank()) {
            property = tp.trim().toLowerCase(Locale.ROOT);
        }

        String tdur = style.get(CssProperty.TRANSITION_DURATION);
        if (tdur != null) {
            Matcher m = TIME_UNIT_PATTERN.matcher(tdur.trim().toLowerCase(Locale.ROOT));
            if (m.find()) {
                durationMs = parseTimeTokenMs(m);
            }
        }

        String td = style.get(CssProperty.TRANSITION_DELAY);
        if (td != null) {
            Matcher m = TIME_UNIT_PATTERN.matcher(td.trim().toLowerCase(Locale.ROOT));
            if (m.find()) {
                delayMs = parseTimeTokenMs(m);
            }
        }

        String ttf = style.get(CssProperty.TRANSITION_TIMING_FUNCTION);
        if (ttf != null && !ttf.isBlank()) {
            easing = ttf.trim();
        }
        return new TransitionSpec(durationMs, delayMs, easing, property);
    }

    private BorderSpec parseBorderSpec(LayoutBox box, int[] radii) {
        boolean hasBorder = false;
        int borderColor = 0x00000000;
        float bwTop = 0, bwRight = 0, bwBottom = 0, bwLeft = 0;
        int bcTop = 0, bcRight = 0, bcBottom = 0, bcLeft = 0;

        for (String dir : BORDER_DIRECTIONS) {
            float bw = 0;
            int bc = 0x00000000;

            String widthProp = box.style.get("border-" + dir + "-width");
            String colorProp = box.style.get("border-" + dir + "-color");
            String borderProp = box.style.get("border-" + dir);

            if (widthProp != null && !"none".equals(widthProp)) {
                bw = LengthResolver.parseDim(widthProp, box.w, rootW, rootH, box.style.fontSizePx(), box.style.rootFontSizePx());
            } else if (borderProp != null && !"none".equals(borderProp)) {
                String[] parts = WHITESPACE_SPLIT.split(borderProp.trim());
                if (parts.length >= 1) {
                    bw = LengthResolver.parseDim(parts[0], box.w, rootW, rootH, box.style.fontSizePx(), box.style.rootFontSizePx());
                }
            }

            // 枠線を重ねる表のセルは、隣と分け合った半分の幅を描く（隣の半分と合わせて 1 本になる）
            if (box.collapsedBorders != null) {
                bw = switch (dir) {
                    case "top" -> box.bT;
                    case "right" -> box.bR;
                    case "bottom" -> box.bB;
                    default -> box.bL;
                };
            }

            if (colorProp != null && !"none".equals(colorProp)) {
                bc = ColorParser.parseColor(colorProp);
            } else if (borderProp != null && !"none".equals(borderProp)) {
                Matcher cm = COLOR_TOKEN_PATTERN.matcher(borderProp);
                String lastColor = null;
                while (cm.find()) {
                    lastColor = cm.group(1);
                }
                if (lastColor != null) {
                    bc = ColorParser.parseColor(lastColor);
                } else {
                    String[] parts = WHITESPACE_SPLIT.split(borderProp.trim());
                    if (parts.length >= 3) {
                        bc = ColorParser.parseColor(parts[parts.length - 1]);
                    }
                }
            }

            if (bw > 0) {
                hasBorder = true;
                borderColor = bc;
                switch (dir) {
                    case "top" -> { bwTop = bw; bcTop = bc; }
                    case "right" -> { bwRight = bw; bcRight = bc; }
                    case "bottom" -> { bwBottom = bw; bcBottom = bc; }
                    case "left" -> { bwLeft = bw; bcLeft = bc; }
                }
            }
        }

        boolean isCircle = radii[0] >= (box.w / 2) - 1 && radii[1] >= (box.w / 2) - 1
                && radii[2] >= (box.w / 2) - 1 && radii[3] >= (box.w / 2) - 1
                && box.w > 0 && box.h > 0 && Math.abs(box.w - box.h) <= 2;
        boolean useRoundedBorder = (radii[0] > 0 || radii[1] > 0 || radii[2] > 0 || radii[3] > 0) && hasBorder && !isCircle;

        Corners innerRadii = new Corners(
                Math.max(0, Math.round(radii[0] - Math.max(bwTop, bwLeft))),
                Math.max(0, Math.round(radii[1] - Math.max(bwTop, bwRight))),
                Math.max(0, Math.round(radii[2] - Math.max(bwBottom, bwRight))),
                Math.max(0, Math.round(radii[3] - Math.max(bwBottom, bwLeft)))
        );

        return new BorderSpec(hasBorder, isCircle, useRoundedBorder, borderColor,
                bwTop, bwRight, bwBottom, bwLeft, bcTop, bcRight, bcBottom, bcLeft, innerRadii);
    }

    private void drawBoxSelf(List<UIElement> elements, LayoutBox box, float[] clipRect, String parentId) {
        int[] rawRadii = roundRadii(LengthResolver.parseBorderRadius(
                box.style.getOrDefault(CssProperty.BORDER_RADIUS, "0"), box.w, rootW, rootH));
        Corners radii = Corners.fromArray(rawRadii);
        int fallbackRadius = radii.topLeft();
        float rOff = Math.min(fallbackRadius, Math.min(box.w, box.h) / 2);

        int color = ColorParser.parseColor(box.style.get(CssProperty.COLOR));
        if (color == 0x00000000) color = 0xFFF5F5F0;

        String bgProp = box.style.getOrDefault(CssProperty.BACKGROUND, box.style.getOrDefault(CssProperty.BACKGROUND_COLOR, "transparent"));
        int bgColor = ColorParser.parseColor(bgProp);
        UIElement.GradientSpec gradientSpec = parseGradientSpec(bgProp);

        String id = box.node.getId();

        float opacity = box.style.number(CssProperty.OPACITY, 1.0f);
        float[] tr = parseTransformProps(box.style.get(CssProperty.TRANSFORM), box.w, box.h, 0f, 0f, 1f, 1f, 0f);
        float tx = tr[0] + parseFloatAttr(box.node.attrs, "data-pan-x", 0f);
        float ty = tr[1] + parseFloatAttr(box.node.attrs, "data-pan-y", 0f);
        float zoom = parseFloatAttr(box.node.attrs, "data-zoom", 1.0f);
        float sx = tr[2] * zoom;
        float sy = tr[3] * zoom;
        float rot = tr[4];

        TransitionSpec transSpec = parseTransitionSpec(box.style.asMap());
        AnimationSpec animDef = parseAnimationDef(box.style, box.w, box.h, color, bgColor);
        AnimationSpec colorBgAnim = animDef.withChannels(AnimationSpec.CH_COLOR | AnimationSpec.CH_BG_COLOR);
        int transDur = transSpec.durationMs();
        boolean interactive = box.hasInteractiveRules;

        float ox = 0.5f, oy = 0.5f;
        Map<String, String> anchorEvents = new HashMap<>(6);
        if (parentId != null) anchorEvents.put(RenderKeys.MC_PARENT, parentId);
        applyTransitionEvents(anchorEvents, transSpec);
        if (box.style.containsKey(CssProperty.TRANSFORM_ORIGIN)) {
            String[] parts = WHITESPACE_SPLIT.split(box.style.get(CssProperty.TRANSFORM_ORIGIN).toLowerCase(Locale.ROOT).trim());
            if (parts.length > 0) ox = parseOriginProp(parts[0]);
            if (parts.length > 1) oy = parseOriginProp(parts[1]);
            anchorEvents.put(RenderKeys.MC_ORIGIN, ox + "," + oy);
        }

        // 算出済みの font-size をそのまま運ぶ。描画側は書体の em からの倍率に直す。
        // 以前は 16 倍した「倍率」を渡し、描画側が 12 で割るという二重の換算だった。
        float fontHVal = Math.max(1.0f, box.style.fontSizePx());
        int itemCount = (int) parseFloatAttr(box.node.attrs, "data-count", 1f);

        ClipRect normalizedClip = normalizeClipRect(clipRect);
        UIElement.RenderMeta anchorMeta = new UIElement.RenderMeta(normalizedClip, radii, null, null, fontHVal, 0f, box.lineHeightPx, ox, oy, itemCount, interactive);
        addElement(elements, id, id, ElementType.NODE, box.x, box.y, box.w, box.h, "", color, bgColor, anchorEvents, 0, opacity, tx, ty, sx, sy, rot, transDur, animDef, anchorMeta);

        Map<String, String> partsEvents = buildPartsEvents(box, id, fontHVal, gradientSpec, transSpec);
        Map<String, String> passiveBorderEvents = buildPassiveBorderEvents(id, transSpec);

        UIElement.RenderMeta baseMeta = new UIElement.RenderMeta(normalizedClip, radii, gradientSpec, null, fontHVal, 0f, box.lineHeightPx, ox, oy, itemCount, interactive);

        if (!box.inlineFragments.isEmpty() && !NON_FRAGMENT_TAGS.contains(box.node.tag)) {
            for (LayoutBox.InlineFragment frag : box.inlineFragments) {
                if (bgColor != 0x00000000 || !partsEvents.isEmpty()) {
                    addElement(elements, id, id + "_frag_" + (fragIdCounter++), ElementType.RECT, frag.x, frag.y, frag.w, frag.h,
                            "", color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, baseMeta);
                }
            }
            return;
        }

        drawBoxShadowIfPresent(elements, box, id, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy);

        BorderSpec border = parseBorderSpec(box, rawRadii);
        UIElement.RenderMeta innerMeta = new UIElement.RenderMeta(normalizedClip, border.innerRadii(), gradientSpec, null, fontHVal, 0f, box.lineHeightPx, ox, oy, itemCount, interactive);

        switch (box.node.tag) {
            case "mc-scene" -> drawSceneElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
            case "img", "video", "mc-fluid", "mc-entity" -> drawMediaElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
            case "#text" -> drawTextNodeElement(elements, box, id, color, bgColor, normalizedClip, fontHVal, ox, oy, partsEvents, transDur, animDef.withChannels(AnimationSpec.CH_COLOR));
            case "progress", "meter" -> {
                String val = box.node.attrs.getOrDefault("value", "0");
                String min = box.node.attrs.getOrDefault("min", "0");
                String max = box.node.attrs.getOrDefault("max", "100");
                // 何が壊れていたか: ここが RANGE を出していたため、progress / meter が
                // つまみ付きのスライダーとして描かれていた。進捗バーの描画実装
                // (WebRenderCore.renderProgressElement) は呼ばれない死にコードだった。
                addElement(elements, id, id + "_progress", ElementType.PROGRESS, box.x, box.y, box.w, box.h, val + ":" + min + ":" + max, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, baseMeta);
            }
            case "canvas" -> drawCanvasElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
            case "textarea" -> drawTextareaElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
            case "select" -> drawSelectElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
            case "input" -> drawInputElement(elements, box, id, color, bgColor, fallbackRadius, fontHVal, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
            default -> drawDefaultBlockElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
        }
    }

    private static void applyTransitionEvents(Map<String, String> events, TransitionSpec transSpec) {
        if (transSpec.delayMs() > 0) {
            events.put(RenderKeys.MC_DELAY, String.valueOf(transSpec.delayMs()));
        }
        if (!EasingFunction.DEFAULT_EASING.equals(transSpec.easing())) {
            events.put(RenderKeys.MC_EASING, transSpec.easing());
        }
        if (!"all".equals(transSpec.property())) {
            events.put(RenderKeys.MC_TRANSITION_PROP, transSpec.property());
        }
    }

    private Map<String, String> buildPartsEvents(LayoutBox box, String id, float fontHVal, UIElement.GradientSpec gradientSpec, TransitionSpec transSpec) {
        Map<String, String> partsEvents = new HashMap<>();
        partsEvents.put(RenderKeys.MC_PARENT, id);
        partsEvents.put(RenderKeys.MC_FONT_H, String.valueOf(fontHVal));
        applyTransitionEvents(partsEvents, transSpec);
        if (box.style.containsKey(CssProperty.FONT_FAMILY)) {
            partsEvents.put("font-family", box.style.get(CssProperty.FONT_FAMILY));
        }
        if (gradientSpec != null) {
            partsEvents.put(RenderKeys.MC_GRADIENT, gradientSpec.direction() + ":" + gradientSpec.startColor() + ":" + gradientSpec.endColor());
        }
        if (box.node.attrs.containsKey("title")) partsEvents.put(RenderKeys.MC_TITLE, box.node.attrs.get("title"));
        else if (box.node.attrs.containsKey("data-tooltip")) partsEvents.put(RenderKeys.MC_TITLE, box.node.attrs.get("data-tooltip"));

        for (String attr : PASSTHROUGH_DATA_ATTRS) {
            if (box.node.attrs.containsKey(attr)) {
                partsEvents.put(attr, box.node.attrs.get(attr));
            }
        }

        String overflowX = box.style.getOrDefault(CssProperty.OVERFLOW_X, box.style.getOrDefault(CssProperty.OVERFLOW, "visible"));
        String overflowY = box.style.getOrDefault(CssProperty.OVERFLOW_Y, box.style.getOrDefault(CssProperty.OVERFLOW, "visible"));
        boolean isScrollable = "scroll".equals(overflowX) || "auto".equals(overflowX)
                || "scroll".equals(overflowY) || "auto".equals(overflowY)
                || "root".equals(box.node.tag) || "html".equals(box.node.tag) || "body".equals(box.node.tag);
        if (isScrollable) {
            partsEvents.put(RenderKeys.MC_SCROLLABLE, box.scrollWidth + "," + box.scrollHeight);
            partsEvents.put(RenderKeys.MC_SCROLL_CURRENT, scrollState.getScrollX(box.node.getId()) + "," + scrollState.getScrollY(box.node.getId()));
        }

        for (Map.Entry<String, String> entry : box.node.attrs.entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).startsWith("on")) {
                partsEvents.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
            }
        }
        for (Map.Entry<String, String> entry : box.style.asMap().entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).startsWith("on")) {
                partsEvents.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
            }
        }

        String tag = box.node.tag;
        if (("button".equals(tag) || "select".equals(tag) || "summary".equals(tag)) && !partsEvents.containsKey("onclick")) {
            partsEvents.put("onclick", id);
        }
        if ("label".equals(tag) && box.node.attrs.containsKey("for") && !partsEvents.containsKey("onclick")) {
            partsEvents.put("onclick", "label_for:" + box.node.attrs.get("for"));
        }
        return partsEvents;
    }

    private static Map<String, String> buildPassiveBorderEvents(String id, TransitionSpec transSpec) {
        Map<String, String> passive = new HashMap<>(4);
        passive.put(RenderKeys.MC_PARENT, id);
        applyTransitionEvents(passive, transSpec);
        return passive;
    }

    private void drawBoxShadowIfPresent(List<UIElement> elements, LayoutBox box, String id, int fallbackRadius, Corners radii, ClipRect normalizedClip, float fontHVal, float ox, float oy) {
        String shadow = box.style.get(CssProperty.BOX_SHADOW);
        if (shadow == null || "none".equals(shadow)) return;

        int shadowRgb = 0x000000;
        int baseAlpha = 0x22;
        Matcher cm = COLOR_TOKEN_PATTERN.matcher(shadow);
        if (cm.find()) {
            int parsed = ColorParser.parseColor(cm.group(1));
            if (parsed != 0x00000000) {
                shadowRgb = parsed & 0x00FFFFFF;
                baseAlpha = (parsed >>> 24) & 0xFF;
            }
        }
        int c1 = (Math.clamp(baseAlpha, 0, 255) << 24) | shadowRgb;
        int c2 = (Math.clamp(Math.round(baseAlpha * 0.62f), 0, 255) << 24) | shadowRgb;
        int c3 = (Math.clamp(Math.round(baseAlpha * 0.32f), 0, 255) << 24) | shadowRgb;

        Map<String, String> shadowEv = Map.of(RenderKeys.MC_PARENT, id);
        Corners r1 = new Corners(radii.topLeft() + 1, radii.topRight() + 1, radii.bottomRight() + 1, radii.bottomLeft() + 1);
        addElement(elements, id, id + "_shadow_1", ElementType.RECT, box.x + 1, box.y + 2, box.w + 2, box.h + 2, "", 0, c1, shadowEv, fallbackRadius + 1, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE,
                new UIElement.RenderMeta(normalizedClip, r1, null, null, fontHVal, ox, oy, 1, false));
        Corners r2 = new Corners(radii.topLeft() + 2, radii.topRight() + 2, radii.bottomRight() + 2, radii.bottomLeft() + 2);
        addElement(elements, id, id + "_shadow_2", ElementType.RECT, box.x + 2, box.y + 4, box.w + 4, box.h + 4, "", 0, c2, shadowEv, fallbackRadius + 2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE,
                new UIElement.RenderMeta(normalizedClip, r2, null, null, fontHVal, ox, oy, 1, false));
        Corners r3 = new Corners(radii.topLeft() + 3, radii.topRight() + 3, radii.bottomRight() + 3, radii.bottomLeft() + 3);
        addElement(elements, id, id + "_shadow_3", ElementType.RECT, box.x + 3, box.y + 6, box.w + 6, box.h + 6, "", 0, c3, shadowEv, fallbackRadius + 3, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE,
                new UIElement.RenderMeta(normalizedClip, r3, null, null, fontHVal, ox, oy, 1, false));
    }

    private void drawSceneElement(List<UIElement> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                  Map<String, String> partsEvents, Map<String, String> passiveBorderEvents,
                                  BorderSpec border, UIElement.RenderMeta baseMeta, UIElement.RenderMeta innerMeta, float rOff,
                                  int transDur, AnimationSpec colorBgAnim) {
        if (border.useRoundedBorder()) {
            addElement(elements, id, id + "_bbg", ElementType.RECT, box.x, box.y, box.w, box.h, "", 0, border.borderColor(), passiveBorderEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE, baseMeta);
        } else if (border.hasBorder()) {
            drawStraightBorders(elements, id, box, rOff, passiveBorderEvents, border, baseMeta, transDur);
        }
        float innerX = box.x + border.bwLeft();
        float innerY = box.y + border.bwTop();
        float innerW = Math.max(0, box.w - border.bwLeft() - border.bwRight());
        float innerH = Math.max(0, box.h - border.bwTop() - border.bwBottom());

        Map<String, String> sceneEvents = new HashMap<>(partsEvents);
        if (box.node.attrs.containsKey("data-blocks")) sceneEvents.put("data-blocks", box.node.attrs.get("data-blocks"));
        if (box.node.attrs.containsKey("slice-y")) sceneEvents.put("data-slice-y", box.node.attrs.get("slice-y"));
        else if (box.node.attrs.containsKey("data-slice-y")) sceneEvents.put("data-slice-y", box.node.attrs.get("data-slice-y"));
        if (box.node.attrs.containsKey("auto-rotate")) sceneEvents.put("data-auto-rotate", box.node.attrs.get("auto-rotate"));

        addElement(elements, id, id + "_scene", ElementType.SCENE, innerX, innerY, innerW, innerH, id, color, bgColor, sceneEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, innerMeta);
    }

    private void drawMediaElement(List<UIElement> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                  Corners radii, ClipRect normalizedClip, float fontHVal, float ox, float oy,
                                  Map<String, String> partsEvents, Map<String, String> passiveBorderEvents,
                                  BorderSpec border, UIElement.RenderMeta baseMeta, UIElement.RenderMeta innerMeta, float rOff,
                                  int transDur, AnimationSpec colorBgAnim) {
        String src = box.style.getOrDefault(CssProperty.SRC, box.node.attrs.getOrDefault("src", ""));
        if ("mc-fluid".equals(box.node.tag) && !src.startsWith("fluid:")) {
            src = "fluid:" + box.node.attrs.getOrDefault("fluid", src);
        } else if ("mc-entity".equals(box.node.tag) && !src.startsWith("entity:")) {
            src = "entity:" + box.node.attrs.getOrDefault("entity", src.isEmpty() ? "@self" : src);
        }

        ElementType type = "video".equals(box.node.tag) ? ElementType.VIDEO : ElementType.IMAGE;
        ImageFitMode fitMode = ImageFitMode.fromString(box.style.getOrDefault(CssProperty.OBJECT_FIT, "fill"));

        if (border.isCircle() && border.hasBorder()) {
            int thickness = border.maxThickness();
            String bData = thickness + ":" + border.bcTop() + "," + border.bcRight() + "," + border.bcBottom() + "," + border.bcLeft();
            UIElement.CircleBorderSpec cbSpec = new UIElement.CircleBorderSpec(thickness, border.bcTop(), border.bcRight(), border.bcBottom(), border.bcLeft());
            addElement(elements, id, id + "_cb", ElementType.CIRCLE_BORDER, box.x, box.y, box.w, box.h, bData, 0, 0, passiveBorderEvents, 0, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE,
                    new UIElement.RenderMeta(normalizedClip, radii, null, cbSpec, fontHVal, ox, oy, 1, false));
        } else if (border.useRoundedBorder()) {
            addElement(elements, id, id + "_bbg", ElementType.RECT, box.x, box.y, box.w, box.h, "", 0, border.borderColor(), passiveBorderEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE, baseMeta);
        } else if (border.hasBorder()) {
            drawStraightBorders(elements, id, box, rOff, passiveBorderEvents, border, baseMeta, transDur);
        }

        float innerX = box.x + border.bwLeft();
        float innerY = box.y + border.bwTop();
        float innerW = Math.max(0, box.w - border.bwLeft() - border.bwRight());
        float innerH = Math.max(0, box.h - border.bwTop() - border.bwBottom());

        addElement(elements, id, id + "_img", type, fitMode, innerX, innerY, innerW, innerH, src, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, innerMeta);
    }

    private void drawTextNodeElement(List<UIElement> elements, LayoutBox box, String id, int color, int bgColor,
                                     ClipRect normalizedClip, float fontHVal, float ox, float oy, Map<String, String> partsEvents,
                                     int transDur, AnimationSpec colorAnim) {
        Map<String, String> baseTextEvents = new HashMap<>(partsEvents);
        if (box.style.containsKey(CssProperty.FONT_WEIGHT)) baseTextEvents.put("font-weight", box.style.get(CssProperty.FONT_WEIGHT));
        if (box.style.containsKey(CssProperty.FONT_FAMILY)) baseTextEvents.put("font-family", box.style.get(CssProperty.FONT_FAMILY));
        if (box.style.containsKey(CssProperty.TEXT_DECORATION)) baseTextEvents.put("text-decoration", box.style.get(CssProperty.TEXT_DECORATION));
        if (box.style.containsKey(CssProperty.TEXT_SHADOW) && !"none".equals(box.style.get(CssProperty.TEXT_SHADOW))) baseTextEvents.put("text-shadow", "true");

        if (!box.inlineFragments.isEmpty()) {
            for (LayoutBox.InlineFragment frag : box.inlineFragments) {
                if (frag.text == null || frag.text.isBlank()) continue;
                UIElement.RenderMeta fragMeta = new UIElement.RenderMeta(
                        normalizedClip, null, null, null, fontHVal, frag.baseline, frag.h, ox, oy, 1, false);
                addElement(elements, id, id + "_txt_" + (fragIdCounter++), ElementType.TEXT, frag.x, frag.y, frag.w, frag.h, frag.text, color, bgColor, baseTextEvents, 0, 1f, 0, 0, 1f, 1f, 0, transDur, colorAnim, fragMeta);
            }
        } else if (box.shapedLines != null && !box.shapedLines.isEmpty()) {
            // 行の文字列・幅・ベースラインは整形器（TextShaper）が確定済み。
            // ここがするのは text-align の offset と丸めだけで、描画側は測り直さない。
            String align = box.style.getOrDefault(CssProperty.TEXT_ALIGN, "left");
            float contentX = box.x + box.pL + box.bL;
            float availableW = Math.max(0, box.w - box.pL - box.pR - box.bL - box.bR);
            float lineY = box.y + box.pT + box.bT;

            for (TextShaper.ShapedLine line : box.shapedLines) {
                if (!line.text().isBlank()) {
                    float lineW = line.width();
                    float lineX = contentX + alignOffset(align, availableW, lineW);
                    UIElement.RenderMeta lineMeta = new UIElement.RenderMeta(
                            normalizedClip, null, null, null, fontHVal, line.baseline(), line.height(), ox, oy, 1, false);
                    // text-transform は displayText を作る時点で適用済み（測定と描画で同じ文字列）
                    addElement(elements, id, id + "_txt_" + (fragIdCounter++), ElementType.TEXT, lineX, lineY, lineW, line.height(), line.text(), color, bgColor, baseTextEvents, 0, 1f, 0, 0, 1f, 1f, 0, transDur, colorAnim, lineMeta);
                }
                lineY += line.height();
            }
        }
    }

    /** text-align が行に与える水平方向のずれ。 */
    private static float alignOffset(String align, float availableW, float lineW) {
        return switch (align) {
            case "center" -> Math.max(0, (availableW - lineW) / 2);
            case "right" -> Math.max(0, availableW - lineW);
            default -> 0;
        };
    }

    private void drawCanvasElement(List<UIElement> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                   Map<String, String> partsEvents, Map<String, String> passiveBorderEvents,
                                   BorderSpec border, UIElement.RenderMeta baseMeta, UIElement.RenderMeta innerMeta, float rOff,
                                   int transDur, AnimationSpec colorBgAnim) {
        if (border.useRoundedBorder()) {
            addElement(elements, id, id + "_bbg", ElementType.RECT, box.x, box.y, box.w, box.h, "", 0, border.borderColor(), passiveBorderEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE, baseMeta);
        } else if (border.hasBorder()) {
            drawStraightBorders(elements, id, box, rOff, passiveBorderEvents, border, baseMeta, transDur);
        }
        float innerX = box.x + border.bwLeft();
        float innerY = box.y + border.bwTop();
        float innerW = Math.max(0, box.w - border.bwLeft() - border.bwRight());
        float innerH = Math.max(0, box.h - border.bwTop() - border.bwBottom());
        addElement(elements, id, id + "_canvas", ElementType.CANVAS, innerX, innerY, innerW, innerH, id, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, innerMeta);
    }

    private void drawTextareaElement(List<UIElement> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                     Map<String, String> partsEvents, Map<String, String> passiveBorderEvents,
                                     BorderSpec border, UIElement.RenderMeta baseMeta, UIElement.RenderMeta innerMeta, float rOff,
                                     int transDur, AnimationSpec colorBgAnim) {
        String val = box.node.attrs.getOrDefault("value", box.node.getTextContent());
        float innerX = box.x + border.bwLeft();
        float innerY = box.y + border.bwTop();
        float innerW = Math.max(0, box.w - border.bwLeft() - border.bwRight());
        float innerH = Math.max(0, box.h - border.bwTop() - border.bwBottom());

        Map<String, String> taEvents = new HashMap<>(partsEvents);
        if (box.node.attrs.containsKey("data-syntax")) taEvents.put("data-syntax", box.node.attrs.get("data-syntax"));
        if (box.node.attrs.containsKey("data-line-numbers")) taEvents.put("data-line-numbers", box.node.attrs.get("data-line-numbers"));

        if (border.useRoundedBorder()) {
            addElement(elements, id, id + "_bbg", ElementType.RECT, box.x, box.y, box.w, box.h, "", 0, border.borderColor(), passiveBorderEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE, baseMeta);
            addElement(elements, id, id + "_textarea", ElementType.TEXTAREA, innerX, innerY, innerW, innerH, val, color, bgColor, taEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, innerMeta);
        } else {
            addElement(elements, id, id + "_textarea", ElementType.TEXTAREA, box.x, box.y, box.w, box.h, val, color, bgColor, taEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, baseMeta);
            if (border.hasBorder()) drawStraightBorders(elements, id, box, rOff, passiveBorderEvents, border, baseMeta, transDur);
        }
    }

    private void drawSelectElement(List<UIElement> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                   Map<String, String> partsEvents, Map<String, String> passiveBorderEvents,
                                   BorderSpec border, UIElement.RenderMeta baseMeta, UIElement.RenderMeta innerMeta, float rOff,
                                   int transDur, AnimationSpec colorBgAnim) {
        String currentVal = box.node.attrs.getOrDefault("value", "");
        String displayLabel = ClientWebManager.resolveSelectDisplayLabel(box.node, currentVal) + " \u25be";
        float innerX = box.x + border.bwLeft(), innerY = box.y + border.bwTop();
        float innerW = Math.max(0, box.w - border.bwLeft() - border.bwRight()), innerH = Math.max(0, box.h - border.bwTop() - border.bwBottom());

        if (border.useRoundedBorder()) {
            addElement(elements, id, id + "_bbg", ElementType.RECT, box.x, box.y, box.w, box.h, "", 0, border.borderColor(), passiveBorderEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE, baseMeta);
            addElement(elements, id, id + "_select", ElementType.SELECT, innerX, innerY, innerW, innerH, displayLabel, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, innerMeta);
        } else {
            addElement(elements, id, id + "_select", ElementType.SELECT, box.x, box.y, box.w, box.h, displayLabel, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, baseMeta);
            if (border.hasBorder()) drawStraightBorders(elements, id, box, rOff, passiveBorderEvents, border, baseMeta, transDur);
        }
    }

    private void drawInputElement(List<UIElement> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius, float fontHVal,
                                  Map<String, String> partsEvents, Map<String, String> passiveBorderEvents,
                                  BorderSpec border, UIElement.RenderMeta baseMeta, UIElement.RenderMeta innerMeta, float rOff,
                                  int transDur, AnimationSpec colorBgAnim) {
        String inputType = box.node.attrs.getOrDefault("type", "text");
        String val = resolveLiveInputValue(id, box.node.attrs.getOrDefault("value", ""));

        float innerX = box.x + border.bwLeft();
        float innerY = box.y + border.bwTop();
        float innerW = Math.max(0, box.w - border.bwLeft() - border.bwRight());
        float innerH = Math.max(0, box.h - border.bwTop() - border.bwBottom());

        Map<String, String> innerEvents = new HashMap<>(partsEvents);

        switch (inputType) {
            case "checkbox", "radio" -> {
                ElementType toggleType = "radio".equalsIgnoreCase(inputType) ? ElementType.RADIO : ElementType.CHECKBOX;
                String checkedVal = Boolean.toString(box.node.attrs.containsKey("checked"));
                drawBorderedControl(elements, id, "_input", toggleType, box, innerX, innerY, innerW, innerH, checkedVal, color, bgColor, fallbackRadius, partsEvents, innerEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
            }
            case "range" -> {
                String min = box.node.attrs.getOrDefault("min", "0");
                String max = box.node.attrs.getOrDefault("max", "100");
                if (val.isEmpty()) val = "50";
                int colonIdx = val.indexOf(':');
                if (colonIdx > 0) val = val.substring(0, colonIdx);
                String rangeData = val + ":" + min + ":" + max;
                drawBorderedControl(elements, id, "_input", ElementType.RANGE, box, innerX, innerY, innerW, innerH, rangeData, color, bgColor, fallbackRadius, partsEvents, innerEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
            }
            case "color" -> {
                int swatchColor = ColorParser.parseColor(val.isEmpty() ? "#89b4fa" : val);
                drawBorderedControl(elements, id, "_input", ElementType.INPUT, box, innerX, innerY, innerW, innerH, val, color, swatchColor, fallbackRadius, partsEvents, innerEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
            }
            default -> {
                drawBorderedControl(elements, id, "_input", ElementType.INPUT, box, innerX, innerY, innerW, innerH, val, color, bgColor, fallbackRadius, partsEvents, innerEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
                if (box.node.attrs.containsKey("placeholder")) {
                    String placeholderText = val.isEmpty() ? box.node.attrs.get("placeholder") : "";
                    // 入力欄の内容領域をそのまま渡す。縦方向の中央寄せは書体の
                    // ascent / descent を知っている描画側が 1 箇所で行う
                    float phX = box.x + border.bwLeft() + 4;
                    Map<String, String> phEvents = new HashMap<>(passiveBorderEvents);
                    addElement(elements, id, id + "_ph", ElementType.TEXT, phX, innerY, innerW, innerH, placeholderText, 0xFFAAAAAA, 0x00000000, phEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, innerMeta);
                }
            }
        }
    }

    private void drawBorderedControl(List<UIElement> elements, String id, String suffix, ElementType type, LayoutBox box,
                                     float innerX, float innerY, float innerW, float innerH, String text, int color, int bgColor, int fallbackRadius,
                                     Map<String, String> partsEvents, Map<String, String> innerEvents, Map<String, String> passiveBorderEvents,
                                     BorderSpec border, UIElement.RenderMeta baseMeta, UIElement.RenderMeta innerMeta, float rOff,
                                     int transDur, AnimationSpec colorBgAnim) {
        if (border.useRoundedBorder()) {
            addElement(elements, id, id + "_bbg", ElementType.RECT, box.x, box.y, box.w, box.h, "", 0, border.borderColor(), passiveBorderEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE, baseMeta);
            addElement(elements, id, id + suffix, type, innerX, innerY, innerW, innerH, text, color, bgColor, innerEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, innerMeta);
        } else {
            addElement(elements, id, id + suffix, type, box.x, box.y, box.w, box.h, text, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, baseMeta);
            if (border.hasBorder()) drawStraightBorders(elements, id, box, rOff, passiveBorderEvents, border, baseMeta, transDur);
        }
    }

    private void drawDefaultBlockElement(List<UIElement> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                         Corners radii, ClipRect normalizedClip, float fontHVal, float ox, float oy,
                                         Map<String, String> partsEvents, Map<String, String> passiveBorderEvents,
                                         BorderSpec border, UIElement.RenderMeta baseMeta, UIElement.RenderMeta innerMeta, float rOff,
                                         int transDur, AnimationSpec colorBgAnim) {
        if ("li".equals(box.node.tag)) {
            // マーカーは font-size に比例させ、縦は 1 行目の中央に合わせる
            float fontSizePx = box.style.fontSizePx();
            int dotSize = Math.max(2, Math.round(fontSizePx / 4f));
            float dotX = box.x - fontSizePx * 0.75f;
            float dotY = box.y + box.pT + box.lineHeightPx / 2f - dotSize / 2f;
            addElement(elements, id, id + "_dot", ElementType.RECT, dotX, dotY, dotSize, dotSize, "", 0, color, Map.of(RenderKeys.MC_PARENT, id), dotSize / 2, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE, baseMeta);
        }

        ElementType bgType = ElementType.RECT;
        if ("button".equalsIgnoreCase(box.node.tag)) {
            bgType = ElementType.BUTTON;
        } else if ("a".equalsIgnoreCase(box.node.tag)) {
            bgType = ElementType.A;
        }
        boolean isInteractiveBox = bgType != ElementType.RECT;

        if (border.isCircle() && border.hasBorder()) {
            int thickness = border.maxThickness();
            String bData = thickness + ":" + border.bcTop() + "," + border.bcRight() + "," + border.bcBottom() + "," + border.bcLeft();
            UIElement.CircleBorderSpec cbSpec = new UIElement.CircleBorderSpec(thickness, border.bcTop(), border.bcRight(), border.bcBottom(), border.bcLeft());
            if (bgColor != 0x00000000 || !partsEvents.isEmpty() || isInteractiveBox || colorBgAnim.animates(AnimationSpec.CH_BG_COLOR)) {
                addElement(elements, id, id + "_bg", bgType, box.x, box.y, box.w, box.h, "", color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, baseMeta);
            }
            addElement(elements, id, id + "_cb", ElementType.CIRCLE_BORDER, box.x, box.y, box.w, box.h, bData, 0, 0, passiveBorderEvents, 0, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE,
                    new UIElement.RenderMeta(normalizedClip, radii, null, cbSpec, fontHVal, ox, oy, 1, false));
        } else if (border.useRoundedBorder()) {
            addElement(elements, id, id + "_bbg", ElementType.RECT, box.x, box.y, box.w, box.h, "", 0, border.borderColor(), passiveBorderEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE, baseMeta);
            if (bgColor != 0x00000000 || !partsEvents.isEmpty() || isInteractiveBox || colorBgAnim.animates(AnimationSpec.CH_BG_COLOR)) {
                Map<String, String> innerEvents = new HashMap<>(partsEvents);
                float innerX = box.x + border.bwLeft();
                float innerY = box.y + border.bwTop();
                float innerW = Math.max(0, box.w - border.bwLeft() - border.bwRight());
                float innerH = Math.max(0, box.h - border.bwTop() - border.bwBottom());
                addElement(elements, id, id + "_bg", bgType, innerX, innerY, innerW, innerH, "", color, bgColor, innerEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, innerMeta);
            }
        } else {
            if (bgColor != 0x00000000 || !partsEvents.isEmpty() || isInteractiveBox || colorBgAnim.animates(AnimationSpec.CH_BG_COLOR)) {
                addElement(elements, id, id + "_bg", bgType, box.x, box.y, box.w, box.h, "", color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, baseMeta);
            }
            if (border.hasBorder()) {
                drawStraightBorders(elements, id, box, rOff, passiveBorderEvents, border, baseMeta, transDur);
            }
        }

        if (box.node.attrs.containsKey("data-ghost-item")) {
            String ghostItem = box.node.attrs.get("data-ghost-item").trim();
            if (!ghostItem.isEmpty() && !"minecraft:air".equals(ghostItem)) {
                float iconX = box.x + (box.w - 16) / 2;
                float iconY = box.y + (box.h - 16) / 2;
                addElement(elements, id, id + "_ghost_img", ElementType.IMAGE, ImageFitMode.FILL, iconX, iconY, 16, 16, "item:" + ghostItem, color, 0, partsEvents, 0, 0.85f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, innerMeta);
            }
        }
    }

    private void drawStraightBorders(List<UIElement> elements, String id, LayoutBox box, float rOff, Map<String, String> partsEvents, BorderSpec b, UIElement.RenderMeta meta, int transDur) {
        UIElement.RenderMeta borderMeta = new UIElement.RenderMeta(meta.clipRect(), null, null, null, meta.fontHeight(), meta.originX(), meta.originY(), 1, false);
        if (b.bwTop() > 0)
            addElement(elements, id, id + "_b_top", ElementType.RECT, box.x + rOff, box.y, box.w - rOff * 2, b.bwTop(), "", 0, b.bcTop(), partsEvents, 0, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE, borderMeta);
        if (b.bwBottom() > 0)
            addElement(elements, id, id + "_b_bottom", ElementType.RECT, box.x + rOff, box.y + box.h - b.bwBottom(), box.w - rOff * 2, b.bwBottom(), "", 0, b.bcBottom(), partsEvents, 0, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE, borderMeta);
        if (b.bwLeft() > 0)
            addElement(elements, id, id + "_b_left", ElementType.RECT, box.x, box.y + rOff, b.bwLeft(), box.h - rOff * 2, "", 0, b.bcLeft(), partsEvents, 0, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE, borderMeta);
        if (b.bwRight() > 0)
            addElement(elements, id, id + "_b_right", ElementType.RECT, box.x + box.w - b.bwRight(), box.y + rOff, b.bwRight(), box.h - rOff * 2, "", 0, b.bcRight(), partsEvents, 0, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE, borderMeta);
    }

    /** 角丸の半径は描画側の整数値（{@link Corners}）なので、解いた長さをここで丸める。 */
    private static int[] roundRadii(float[] radii) {
        int[] rounded = new int[radii.length];
        for (int i = 0; i < radii.length; i++) rounded[i] = Math.round(radii[i]);
        return rounded;
    }

    private static ClipRect normalizeClipRect(float[] clipRect) {
        if (clipRect == null) return null;
        return ClipRect.fromArray(clipRect);
    }

    private static float parseFloatAttr(Map<String, String> map, String key, float fallback) {
        String val = map.get(key);
        if (val == null || val.isEmpty()) return fallback;
        try {
            return Float.parseFloat(val.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private record TransitionSpec(int durationMs, int delayMs, String easing, String property) {}

    private record BorderSpec(
            boolean hasBorder, boolean isCircle, boolean useRoundedBorder, int borderColor,
            float bwTop, float bwRight, float bwBottom, float bwLeft,
            int bcTop, int bcRight, int bcBottom, int bcLeft,
            Corners innerRadii
    ) {
        int maxThickness() {
            return Math.round(Math.max(Math.max(bwTop, bwRight), Math.max(bwBottom, bwLeft)));
        }
    }

    /** {@code box} は箱自身を描くタスクだけが持つ（スクロールバーは null）。 */
    private record RenderTask(int zIndex, int layer, int domOrder, LayoutBox box, Consumer<List<UIElement>> drawAction) {}
}