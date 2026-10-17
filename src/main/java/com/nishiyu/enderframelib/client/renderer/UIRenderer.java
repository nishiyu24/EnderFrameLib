package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.core.model.AnimationSpec;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.core.css.ColorParser;
import com.nishiyu.enderframelib.core.css.LengthResolver;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.ScrollState;
import com.nishiyu.enderframelib.core.model.*;

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
    private static final Pattern OPACITY_PROP_PATTERN = Pattern.compile("opacity\\s*:\\s*([0-9.]+)");
    private static final Pattern TIME_UNIT_PATTERN = Pattern.compile("([0-9.]+)(s|ms)");
    private static final Pattern WHITESPACE_SPLIT = Pattern.compile("\\s+");

    private static final Set<String> NON_FRAGMENT_TAGS = Set.of(
            "#text", "img", "mc-fluid", "mc-entity", "mc-scene",
            "input", "select", "progress", "meter", "textarea", "canvas"
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
        this.keyframes = keyframes;
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

        renderQueue.sort(Comparator.comparingInt(RenderTask::zIndex).thenComparingInt(RenderTask::domOrder));

        for (RenderTask task : renderQueue) {
            task.drawAction().accept(elements);
        }
        return elements;
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
        if (box.w <= 0 || box.h <= 0) return;
        if ("none".equals(box.style.getOrDefault(CssProperty.DISPLAY, "block"))) return;
        if ("option".equalsIgnoreCase(box.node.tag)) return;

        int currentZ = box.zIndex != 0 ? (parentZIndex + box.zIndex) : parentZIndex;
        String currentId = box.node.getId();
        int order = domOrderCounter++;

        queue.add(new RenderTask(currentZ, order, elements -> drawBoxSelf(elements, box, clipRect, parentId)));

        String tag = box.node.tag;
        if ("select".equalsIgnoreCase(tag) || "textarea".equalsIgnoreCase(tag) || "input".equalsIgnoreCase(tag)) {
            return;
        }

        float[] childClip = computeChildClipRect(box, clipRect);
        for (LayoutBox child : box.children) {
            buildRenderQueue(child, queue, currentZ, childClip, currentId);
        }

        String overflow = box.style.getOrDefault(CssProperty.OVERFLOW, "visible");
        if ("scroll".equals(overflow) || "auto".equals(overflow)) {
            int sbOrder = domOrderCounter++;
            queue.add(new RenderTask(currentZ + 1, sbOrder, elements -> drawScrollbarsIfNeeded(elements, box, clipRect)));
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

        int[] radii = LengthResolver.parseBorderRadius(box.style.getOrDefault(CssProperty.BORDER_RADIUS, "0"), box.w, rootW, rootH);
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
        UIElement.RenderMeta sbMeta = new UIElement.RenderMeta(sbClip, new Corners(2, 2, 2, 2), null, null, 12.0f, 0.5f, 0.5f, 1, PseudoStates.EMPTY);

        int barThickness = 4;
        if (scrollH > box.h + 2 && box.h > 12) {
            int trackX = box.x + box.w - box.bR - barThickness - 2;
            int trackY = box.y + box.bT + 2;
            int trackH = box.h - box.bT - box.bB - 4;
            int thumbH = Math.max(12, (int) (trackH * (box.h / scrollH)));
            float maxScrollY = Math.max(1f, scrollH - box.h);
            int thumbY = trackY + (int) ((trackH - thumbH) * Math.clamp(curY / maxScrollY, 0f, 1f));

            addElement(elements, box.node.getId(), box.node.getId() + "_sb_vt", ElementType.RECT, trackX, trackY, barThickness, trackH, "", 0, 0x33000000, sbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, sbMeta);
            addElement(elements, box.node.getId(), box.node.getId() + "_sb_v", ElementType.RECT, trackX, thumbY, barThickness, thumbH, "", 0, 0x88CCCCCC, sbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, sbMeta);
        }
        if (scrollW > box.w + 2 && box.w > 12) {
            int trackX = box.x + box.bL + 2;
            int trackY = box.y + box.h - box.bB - barThickness - 2;
            int trackW = box.w - box.bL - box.bR - 4;
            int thumbW = Math.max(12, (int) (trackW * (box.w / scrollW)));
            float maxScrollX = Math.max(1f, scrollW - box.w);
            int thumbX = trackX + (int) ((trackW - thumbW) * Math.clamp(curX / maxScrollX, 0f, 1f));

            addElement(elements, box.node.getId(), box.node.getId() + "_sb_ht", ElementType.RECT, trackX, trackY, trackW, barThickness, "", 0, 0x33000000, sbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, sbMeta);
            addElement(elements, box.node.getId(), box.node.getId() + "_sb_h", ElementType.RECT, thumbX, trackY, thumbW, barThickness, "", 0, 0x88CCCCCC, sbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, sbMeta);
        }
    }

    private void addElement(List<UIElement> elements, String baseNodeId, String id, ElementType type, ImageFitMode fitMode,
                            int x, int y, int w, int h, String text,
                            int color, int bgColor, Map<String, String> events, int radius, float opacity,
                            float tx, float ty, float sx, float sy, float rot, int trans, AnimationSpec animDef,
                            UIElement.RenderMeta meta) {
        elements.add(new UIElement(id, baseNodeId, type, x, y, w, h, text, color, bgColor, events, radius, opacity, tx, ty, sx, sy, rot, trans, animDef, fitMode, meta));
    }

    private void addElement(List<UIElement> elements, String baseNodeId, String id, ElementType type,
                            int x, int y, int w, int h, String text,
                            int color, int bgColor, Map<String, String> events, int radius, float opacity,
                            float tx, float ty, float sx, float sy, float rot, int trans, AnimationSpec animDef,
                            UIElement.RenderMeta meta) {
        addElement(elements, baseNodeId, id, type, ImageFitMode.FILL, x, y, w, h, text, color, bgColor, events, radius, opacity, tx, ty, sx, sy, rot, trans, animDef, meta);
    }

    private static String resolveLiveInputValue(String nodeId, String attrValue) {
        return attrValue != null ? attrValue : "";
    }

    private static float[] parseTransformProps(String t, int boxW, int boxH, float defTx, float defTy, float defSx, float defSy, float defRot) {
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

    private PseudoTarget buildPseudoTarget(String prefix, LayoutBox box, float baseOp, float baseTx, float baseTy, float baseSx, float baseSy, float baseRot, int baseColor, int baseBgColor) {
        float op = baseOp, tx = baseTx, ty = baseTy, sx = baseSx, sy = baseSy, rot = baseRot;
        int col = baseColor, bgCol = baseBgColor;
        boolean has = false;

        String opStr = box.style.get(prefix + "-opacity");
        if (opStr != null) {
            try {
                op = Float.parseFloat(opStr);
                has = true;
            } catch (NumberFormatException ignored) {}
        }
        String trStr = box.style.get(prefix + "-transform");
        if (trStr != null) {
            has = true;
            float[] tr = parseTransformProps(trStr, box.w, box.h, tx, ty, sx, sy, rot);
            tx = tr[0]; ty = tr[1]; sx = tr[2]; sy = tr[3]; rot = tr[4];
        }
        String colStr = box.style.get(prefix + "-color");
        if (colStr != null) {
            col = ColorParser.parseColor(colStr);
            has = true;
        }
        if (box.style.containsKey(prefix + "-background") || box.style.containsKey(prefix + "-background-color")) {
            bgCol = ColorParser.parseColor(box.style.getOrDefault(prefix + "-background", box.style.get(prefix + "-background-color")));
            has = true;
        }
        return has ? new PseudoTarget(op, tx, ty, sx, sy, rot, col, bgCol) : null;
    }

    /** CSS の {@code animation} 宣言と {@code @keyframes} を {@link AnimationSpec} に解決する。 */
    private AnimationSpec parseAnimationDef(String animProp, float baseW, float baseH) {
        if (animProp == null || "none".equals(animProp)) return AnimationSpec.NONE;
        String[] parts = WHITESPACE_SPLIT.split(animProp.trim());
        if (parts.length == 0) return AnimationSpec.NONE;

        String name = "";
        for (String p : parts) {
            if (keyframes.containsKey(p)) {
                name = p;
                break;
            }
        }
        if (name.isEmpty()) return AnimationSpec.NONE;

        int dur = 1000;
        boolean infinite = animProp.contains("infinite");
        for (String p : parts) {
            Matcher tm = TIME_UNIT_PATTERN.matcher(p);
            if (tm.matches()) {
                try {
                    float val = Float.parseFloat(tm.group(1));
                    if ("s".equals(tm.group(2))) val *= 1000f;
                    dur = (int) val;
                } catch (NumberFormatException ignored) {}
            }
        }

        List<AnimationSpec.Keyframe> frames = new ArrayList<>();
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

            float[] tr = parseTransformProps(props, (int) baseW, (int) baseH, 0, 0, 1, 1, 0);
            float op = 1f;
            Matcher om = OPACITY_PROP_PATTERN.matcher(props);
            if (om.find()) {
                try { op = Float.parseFloat(om.group(1)); } catch (NumberFormatException ignored) {}
            }

            frames.add(new AnimationSpec.Keyframe(pct, tr[0], tr[1], tr[2], tr[3], tr[4], op));
        }

        return frames.isEmpty() ? AnimationSpec.NONE : new AnimationSpec(dur, infinite, frames);
    }

    private static TransitionSpec parseTransitionSpec(Map<String, String> style) {
        int durationMs = 0;
        int delayMs = 0;
        String easing = "ease-out";

        String tr = style.get(CssProperty.TRANSITION);
        if (tr != null) {
            Matcher m = TIME_UNIT_PATTERN.matcher(tr);
            if (m.find()) {
                float val = Float.parseFloat(m.group(1));
                if ("s".equals(m.group(2))) val *= 1000f;
                durationMs = (int) val;
                if (m.find()) {
                    float dval = Float.parseFloat(m.group(1));
                    if ("s".equals(m.group(2))) dval *= 1000f;
                    delayMs = (int) dval;
                }
            }
            if (tr.contains("linear")) easing = "linear";
            else if (tr.contains("ease-in-out")) easing = "ease-in-out";
            else if (tr.contains("ease-in")) easing = "ease-in";
            else if (tr.contains("ease-out") || tr.contains("ease")) easing = "ease-out";
        }

        String td = style.get(CssProperty.TRANSITION_DELAY);
        if (td != null) {
            Matcher m = TIME_UNIT_PATTERN.matcher(td);
            if (m.find()) {
                float val = Float.parseFloat(m.group(1));
                if ("s".equals(m.group(2))) val *= 1000f;
                delayMs = (int) val;
            }
        }

        String ttf = style.get(CssProperty.TRANSITION_TIMING_FUNCTION);
        if (ttf != null && !ttf.isBlank()) {
            easing = ttf.trim();
        }
        return new TransitionSpec(durationMs, delayMs, easing);
    }

    private BorderSpec parseBorderSpec(LayoutBox box, int[] radii) {
        boolean hasBorder = false;
        int borderColor = 0x00000000;
        int bwTop = 0, bwRight = 0, bwBottom = 0, bwLeft = 0;
        int bcTop = 0, bcRight = 0, bcBottom = 0, bcLeft = 0;

        for (String dir : BORDER_DIRECTIONS) {
            String borderProp = box.style.get("border-" + dir);
            if (borderProp != null && !"none".equals(borderProp)) {
                String[] parts = WHITESPACE_SPLIT.split(borderProp.trim());
                if (parts.length >= 3) {
                    int bw = LengthResolver.parseDim(parts[0], box.w, rootW, rootH, box.fontSize * 16f, 16f);
                    if (bw > 0) {
                        int bc = ColorParser.parseColor(parts[parts.length - 1]);
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
            }
        }

        boolean isCircle = radii[0] >= (box.w / 2) - 1 && radii[1] >= (box.w / 2) - 1
                && radii[2] >= (box.w / 2) - 1 && radii[3] >= (box.w / 2) - 1
                && box.w > 0 && box.h > 0 && Math.abs(box.w - box.h) <= 2;
        boolean useRoundedBorder = (radii[0] > 0 || radii[1] > 0 || radii[2] > 0 || radii[3] > 0) && hasBorder && !isCircle;

        Corners innerRadii = new Corners(
                Math.max(0, radii[0] - Math.max(bwTop, bwLeft)),
                Math.max(0, radii[1] - Math.max(bwTop, bwRight)),
                Math.max(0, radii[2] - Math.max(bwBottom, bwRight)),
                Math.max(0, radii[3] - Math.max(bwBottom, bwLeft))
        );

        return new BorderSpec(hasBorder, isCircle, useRoundedBorder, borderColor,
                bwTop, bwRight, bwBottom, bwLeft, bcTop, bcRight, bcBottom, bcLeft, innerRadii);
    }

    private void drawBoxSelf(List<UIElement> elements, LayoutBox box, float[] clipRect, String parentId) {
        int[] rawRadii = LengthResolver.parseBorderRadius(box.style.getOrDefault(CssProperty.BORDER_RADIUS, "0"), box.w, rootW, rootH);
        Corners radii = Corners.fromArray(rawRadii);
        int fallbackRadius = radii.topLeft();
        int rOff = Math.min(fallbackRadius, Math.min(box.w, box.h) / 2);

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

        PseudoTarget hover = buildPseudoTarget("hover", box, opacity, tx, ty, sx, sy, rot, color, bgColor);
        PseudoTarget active = buildPseudoTarget("active", box, opacity, tx, ty, sx, sy, rot, color, bgColor);
        PseudoTarget focus = buildPseudoTarget("focus", box, opacity, tx, ty, sx, sy, rot, color, bgColor);
        PseudoStates pseudoStates = new PseudoStates(hover, active, focus);
        AnimationSpec animDef = parseAnimationDef(box.style.get(CssProperty.ANIMATION), box.w, box.h);

        float ox = 0.5f, oy = 0.5f;
        Map<String, String> anchorEvents = new HashMap<>(4);
        if (parentId != null) anchorEvents.put(RenderKeys.MC_PARENT, parentId);
        if (transSpec.delayMs() > 0) anchorEvents.put(RenderKeys.MC_DELAY, String.valueOf(transSpec.delayMs()));
        if (!"ease-out".equals(transSpec.easing())) anchorEvents.put(RenderKeys.MC_EASING, transSpec.easing());
        if (box.style.containsKey(CssProperty.TRANSFORM_ORIGIN)) {
            String[] parts = WHITESPACE_SPLIT.split(box.style.get(CssProperty.TRANSFORM_ORIGIN).toLowerCase(Locale.ROOT).trim());
            if (parts.length > 0) ox = parseOriginProp(parts[0]);
            if (parts.length > 1) oy = parseOriginProp(parts[1]);
            anchorEvents.put(RenderKeys.MC_ORIGIN, ox + "," + oy);
        }

        float fontHVal = Math.max(10.0f, 16.0f * box.fontSize);
        int itemCount = (int) parseFloatAttr(box.node.attrs, "data-count", 1f);

        ClipRect normalizedClip = normalizeClipRect(clipRect);
        UIElement.RenderMeta anchorMeta = new UIElement.RenderMeta(normalizedClip, radii, null, null, fontHVal, ox, oy, itemCount, pseudoStates);
        addElement(elements, id, id, ElementType.NODE, box.x, box.y, box.w, box.h, "", 0, 0, anchorEvents, 0, opacity, tx, ty, sx, sy, rot, transSpec.durationMs(), animDef, anchorMeta);

        Map<String, String> partsEvents = buildPartsEvents(box, id, fontHVal, gradientSpec);
        Map<String, String> passiveBorderEvents = buildPassiveBorderEvents(id);

        UIElement.RenderMeta baseMeta = new UIElement.RenderMeta(normalizedClip, radii, gradientSpec, null, fontHVal, ox, oy, itemCount, pseudoStates);

        if (!box.inlineFragments.isEmpty() && !NON_FRAGMENT_TAGS.contains(box.node.tag)) {
            for (LayoutBox.InlineFragment frag : box.inlineFragments) {
                if (bgColor != 0x00000000 || !partsEvents.isEmpty()) {
                    addElement(elements, id, id + "_frag_" + (fragIdCounter++), ElementType.RECT, frag.x, frag.y, frag.w, frag.h,
                            "", color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, baseMeta);
                }
            }
            return;
        }

        drawBoxShadowIfPresent(elements, box, id, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy);

        BorderSpec border = parseBorderSpec(box, rawRadii);
        UIElement.RenderMeta innerMeta = new UIElement.RenderMeta(normalizedClip, border.innerRadii(), gradientSpec, null, fontHVal, ox, oy, itemCount, pseudoStates);

        switch (box.node.tag) {
            case "mc-scene" -> drawSceneElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff);
            case "img", "video", "mc-fluid", "mc-entity" -> drawMediaElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff);
            case "#text" -> drawTextNodeElement(elements, box, id, color, bgColor, normalizedClip, fontHVal, ox, oy, partsEvents);
            case "progress", "meter" -> {
                String val = box.node.attrs.getOrDefault("value", "0");
                String min = box.node.attrs.getOrDefault("min", "0");
                String max = box.node.attrs.getOrDefault("max", "100");
                addElement(elements, id, id + "_progress", ElementType.RANGE, box.x, box.y, box.w, box.h, val + ":" + min + ":" + max, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, baseMeta);
            }
            case "canvas" -> drawCanvasElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff);
            case "textarea" -> drawTextareaElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff);
            case "select" -> drawSelectElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff);
            case "input" -> drawInputElement(elements, box, id, color, bgColor, fallbackRadius, fontHVal, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff);
            default -> drawDefaultBlockElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff);
        }
    }

    private Map<String, String> buildPartsEvents(LayoutBox box, String id, float fontHVal, UIElement.GradientSpec gradientSpec) {
        Map<String, String> partsEvents = new HashMap<>();
        partsEvents.put(RenderKeys.MC_PARENT, id);
        partsEvents.put(RenderKeys.MC_FONT_H, String.valueOf(fontHVal));
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

    private static Map<String, String> buildPassiveBorderEvents(String id) {
        Map<String, String> passive = new HashMap<>(2);
        passive.put(RenderKeys.MC_PARENT, id);
        return passive;
    }

    private void drawBoxShadowIfPresent(List<UIElement> elements, LayoutBox box, String id, int fallbackRadius, Corners radii, ClipRect normalizedClip, float fontHVal, float ox, float oy) {
        String shadow = box.style.get(CssProperty.BOX_SHADOW);
        if (shadow == null || "none".equals(shadow)) return;
        Map<String, String> shadowEv = Map.of(RenderKeys.MC_PARENT, id);
        Corners r1 = new Corners(radii.topLeft() + 1, radii.topRight() + 1, radii.bottomRight() + 1, radii.bottomLeft() + 1);
        addElement(elements, id, id + "_shadow_1", ElementType.RECT, box.x + 1, box.y + 2, box.w + 2, box.h + 2, "", 0, 0x22000000, shadowEv, fallbackRadius + 1, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE,
                new UIElement.RenderMeta(normalizedClip, r1, null, null, fontHVal, ox, oy, 1, PseudoStates.EMPTY));
        Corners r2 = new Corners(radii.topLeft() + 2, radii.topRight() + 2, radii.bottomRight() + 2, radii.bottomLeft() + 2);
        addElement(elements, id, id + "_shadow_2", ElementType.RECT, box.x + 2, box.y + 4, box.w + 4, box.h + 4, "", 0, 0x15000000, shadowEv, fallbackRadius + 2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE,
                new UIElement.RenderMeta(normalizedClip, r2, null, null, fontHVal, ox, oy, 1, PseudoStates.EMPTY));
        Corners r3 = new Corners(radii.topLeft() + 3, radii.topRight() + 3, radii.bottomRight() + 3, radii.bottomLeft() + 3);
        addElement(elements, id, id + "_shadow_3", ElementType.RECT, box.x + 3, box.y + 6, box.w + 6, box.h + 6, "", 0, 0x0B000000, shadowEv, fallbackRadius + 3, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE,
                new UIElement.RenderMeta(normalizedClip, r3, null, null, fontHVal, ox, oy, 1, PseudoStates.EMPTY));
    }

    private void drawSceneElement(List<UIElement> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                  Map<String, String> partsEvents, Map<String, String> passiveBorderEvents,
                                  BorderSpec border, UIElement.RenderMeta baseMeta, UIElement.RenderMeta innerMeta, int rOff) {
        if (border.useRoundedBorder()) {
            addElement(elements, id, id + "_bbg", ElementType.RECT, box.x, box.y, box.w, box.h, "", 0, border.borderColor(), passiveBorderEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, baseMeta);
        } else if (border.hasBorder()) {
            drawStraightBorders(elements, id, box, rOff, passiveBorderEvents, border, baseMeta);
        }
        int innerX = box.x + border.bwLeft();
        int innerY = box.y + border.bwTop();
        int innerW = Math.max(0, box.w - border.bwLeft() - border.bwRight());
        int innerH = Math.max(0, box.h - border.bwTop() - border.bwBottom());

        Map<String, String> sceneEvents = new HashMap<>(partsEvents);
        if (box.node.attrs.containsKey("data-blocks")) sceneEvents.put("data-blocks", box.node.attrs.get("data-blocks"));
        if (box.node.attrs.containsKey("slice-y")) sceneEvents.put("data-slice-y", box.node.attrs.get("slice-y"));
        else if (box.node.attrs.containsKey("data-slice-y")) sceneEvents.put("data-slice-y", box.node.attrs.get("data-slice-y"));
        if (box.node.attrs.containsKey("auto-rotate")) sceneEvents.put("data-auto-rotate", box.node.attrs.get("auto-rotate"));

        addElement(elements, id, id + "_scene", ElementType.SCENE, innerX, innerY, innerW, innerH, id, color, bgColor, sceneEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, innerMeta);
    }

    private void drawMediaElement(List<UIElement> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                  Corners radii, ClipRect normalizedClip, float fontHVal, float ox, float oy,
                                  Map<String, String> partsEvents, Map<String, String> passiveBorderEvents,
                                  BorderSpec border, UIElement.RenderMeta baseMeta, UIElement.RenderMeta innerMeta, int rOff) {
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
            addElement(elements, id, id + "_cb", ElementType.CIRCLE_BORDER, box.x, box.y, box.w, box.h, bData, 0, 0, passiveBorderEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE,
                    new UIElement.RenderMeta(normalizedClip, radii, null, cbSpec, fontHVal, ox, oy, 1, PseudoStates.EMPTY));
        } else if (border.useRoundedBorder()) {
            addElement(elements, id, id + "_bbg", ElementType.RECT, box.x, box.y, box.w, box.h, "", 0, border.borderColor(), passiveBorderEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, baseMeta);
        } else if (border.hasBorder()) {
            drawStraightBorders(elements, id, box, rOff, passiveBorderEvents, border, baseMeta);
        }

        int innerX = box.x + border.bwLeft();
        int innerY = box.y + border.bwTop();
        int innerW = Math.max(0, box.w - border.bwLeft() - border.bwRight());
        int innerH = Math.max(0, box.h - border.bwTop() - border.bwBottom());

        addElement(elements, id, id + "_img", type, fitMode, innerX, innerY, innerW, innerH, src, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, innerMeta);
    }

    private void drawTextNodeElement(List<UIElement> elements, LayoutBox box, String id, int color, int bgColor,
                                     ClipRect normalizedClip, float fontHVal, float ox, float oy, Map<String, String> partsEvents) {
        Map<String, String> baseTextEvents = new HashMap<>(partsEvents);
        if (box.style.containsKey(CssProperty.FONT_WEIGHT)) baseTextEvents.put("font-weight", box.style.get(CssProperty.FONT_WEIGHT));
        if (box.style.containsKey(CssProperty.FONT_FAMILY)) baseTextEvents.put("font-family", box.style.get(CssProperty.FONT_FAMILY));
        if (box.style.containsKey(CssProperty.TEXT_DECORATION)) baseTextEvents.put("text-decoration", box.style.get(CssProperty.TEXT_DECORATION));
        if (box.style.containsKey(CssProperty.TEXT_SHADOW) && !"none".equals(box.style.get(CssProperty.TEXT_SHADOW))) baseTextEvents.put("text-shadow", "true");

        UIElement.RenderMeta txtMeta = new UIElement.RenderMeta(normalizedClip, null, null, null, fontHVal, ox, oy, 1, PseudoStates.EMPTY);

        if (!box.inlineFragments.isEmpty()) {
            int fontH = (int) (16 * box.fontSize);
            for (LayoutBox.InlineFragment frag : box.inlineFragments) {
                if (frag.text != null && !frag.text.trim().isEmpty()) {
                    addElement(elements, id, id + "_txt_" + (fragIdCounter++), ElementType.TEXT, frag.x, frag.y, frag.w, fontH, frag.text, color, bgColor, baseTextEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, txtMeta);
                }
            }
        } else if (box.textLines != null && !box.textLines.isEmpty()) {
            String align = box.style.getOrDefault(CssProperty.TEXT_ALIGN, "left");
            Map<String, String> textEvents = new HashMap<>(baseTextEvents);
            textEvents.put("text-align", align);

            int lineY = box.y + box.pT + box.bT;
            int fontH = (int) (16 * box.fontSize);

            float lh = 1.375f;
            String lhStr = box.style.get(CssProperty.LINE_HEIGHT);
            if (lhStr != null) {
                try {
                    lh = lhStr.endsWith("px") ? Float.parseFloat(lhStr.substring(0, lhStr.length() - 2)) / 16f : Float.parseFloat(lhStr);
                } catch (NumberFormatException ignored) {}
            }
            int lineStep = (int) (fontH * lh);
            int availableW = Math.max(0, box.w - box.pL - box.pR - box.bL - box.bR);
            boolean isUppercase = "uppercase".equals(box.style.get(CssProperty.TEXT_TRANSFORM));

            for (String line : box.textLines) {
                if (line == null || line.trim().isEmpty()) {
                    lineY += lineStep;
                    continue;
                }
                String renderedLine = isUppercase ? line.toUpperCase(Locale.ROOT) : line;
                int lineX = box.x + box.pL + box.bL;
                addElement(elements, id, id + "_txt_" + (fragIdCounter++), ElementType.TEXT, lineX, lineY, availableW, fontH, renderedLine, color, bgColor, textEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, txtMeta);
                lineY += lineStep;
            }
        }
    }

    private void drawCanvasElement(List<UIElement> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                   Map<String, String> partsEvents, Map<String, String> passiveBorderEvents,
                                   BorderSpec border, UIElement.RenderMeta baseMeta, UIElement.RenderMeta innerMeta, int rOff) {
        if (border.useRoundedBorder()) {
            addElement(elements, id, id + "_bbg", ElementType.RECT, box.x, box.y, box.w, box.h, "", 0, border.borderColor(), passiveBorderEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, baseMeta);
        } else if (border.hasBorder()) {
            drawStraightBorders(elements, id, box, rOff, passiveBorderEvents, border, baseMeta);
        }
        int innerX = box.x + border.bwLeft();
        int innerY = box.y + border.bwTop();
        int innerW = Math.max(0, box.w - border.bwLeft() - border.bwRight());
        int innerH = Math.max(0, box.h - border.bwTop() - border.bwBottom());
        addElement(elements, id, id + "_canvas", ElementType.CANVAS, innerX, innerY, innerW, innerH, id, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, innerMeta);
    }

    private void drawTextareaElement(List<UIElement> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                     Map<String, String> partsEvents, Map<String, String> passiveBorderEvents,
                                     BorderSpec border, UIElement.RenderMeta baseMeta, UIElement.RenderMeta innerMeta, int rOff) {
        String val = box.node.attrs.getOrDefault("value", box.node.getTextContent());
        int innerX = box.x + border.bwLeft();
        int innerY = box.y + border.bwTop();
        int innerW = Math.max(0, box.w - border.bwLeft() - border.bwRight());
        int innerH = Math.max(0, box.h - border.bwTop() - border.bwBottom());

        Map<String, String> taEvents = new HashMap<>(partsEvents);
        if (box.node.attrs.containsKey("data-syntax")) taEvents.put("data-syntax", box.node.attrs.get("data-syntax"));
        if (box.node.attrs.containsKey("data-line-numbers")) taEvents.put("data-line-numbers", box.node.attrs.get("data-line-numbers"));

        if (border.useRoundedBorder()) {
            addElement(elements, id, id + "_bbg", ElementType.RECT, box.x, box.y, box.w, box.h, "", 0, border.borderColor(), passiveBorderEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, baseMeta);
            addElement(elements, id, id + "_textarea", ElementType.TEXTAREA, innerX, innerY, innerW, innerH, val, color, bgColor, taEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, innerMeta);
        } else {
            addElement(elements, id, id + "_textarea", ElementType.TEXTAREA, box.x, box.y, box.w, box.h, val, color, bgColor, taEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, baseMeta);
            if (border.hasBorder()) drawStraightBorders(elements, id, box, rOff, passiveBorderEvents, border, baseMeta);
        }
    }

    private void drawSelectElement(List<UIElement> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                   Map<String, String> partsEvents, Map<String, String> passiveBorderEvents,
                                   BorderSpec border, UIElement.RenderMeta baseMeta, UIElement.RenderMeta innerMeta, int rOff) {
        String currentVal = box.node.attrs.getOrDefault("value", "");
        String displayLabel = ClientWebManager.resolveSelectDisplayLabel(box.node, currentVal) + " \u25be";
        int innerX = box.x + border.bwLeft(), innerY = box.y + border.bwTop();
        int innerW = Math.max(0, box.w - border.bwLeft() - border.bwRight()), innerH = Math.max(0, box.h - border.bwTop() - border.bwBottom());

        if (border.useRoundedBorder()) {
            addElement(elements, id, id + "_bbg", ElementType.RECT, box.x, box.y, box.w, box.h, "", 0, border.borderColor(), passiveBorderEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, baseMeta);
            addElement(elements, id, id + "_select", ElementType.SELECT, innerX, innerY, innerW, innerH, displayLabel, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, innerMeta);
        } else {
            addElement(elements, id, id + "_select", ElementType.SELECT, box.x, box.y, box.w, box.h, displayLabel, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, baseMeta);
            if (border.hasBorder()) drawStraightBorders(elements, id, box, rOff, passiveBorderEvents, border, baseMeta);
        }
    }

    private void drawInputElement(List<UIElement> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius, float fontHVal,
                                  Map<String, String> partsEvents, Map<String, String> passiveBorderEvents,
                                  BorderSpec border, UIElement.RenderMeta baseMeta, UIElement.RenderMeta innerMeta, int rOff) {
        String inputType = box.node.attrs.getOrDefault("type", "text");
        String val = resolveLiveInputValue(id, box.node.attrs.getOrDefault("value", ""));

        int innerX = box.x + border.bwLeft();
        int innerY = box.y + border.bwTop();
        int innerW = Math.max(0, box.w - border.bwLeft() - border.bwRight());
        int innerH = Math.max(0, box.h - border.bwTop() - border.bwBottom());

        Map<String, String> innerEvents = new HashMap<>(partsEvents);

        switch (inputType) {
            case "checkbox", "radio" -> {
                String checkedVal = Boolean.toString(box.node.attrs.containsKey("checked"));
                drawBorderedControl(elements, id, "_input", ElementType.TOGGLE, box, innerX, innerY, innerW, innerH, checkedVal, color, bgColor, fallbackRadius, partsEvents, innerEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff);
            }
            case "range" -> {
                String min = box.node.attrs.getOrDefault("min", "0");
                String max = box.node.attrs.getOrDefault("max", "100");
                if (val.isEmpty()) val = "50";
                int colonIdx = val.indexOf(':');
                if (colonIdx > 0) val = val.substring(0, colonIdx);
                String rangeData = val + ":" + min + ":" + max;
                drawBorderedControl(elements, id, "_input", ElementType.RANGE, box, innerX, innerY, innerW, innerH, rangeData, color, bgColor, fallbackRadius, partsEvents, innerEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff);
            }
            case "color" -> {
                int swatchColor = ColorParser.parseColor(val.isEmpty() ? "#89b4fa" : val);
                drawBorderedControl(elements, id, "_input", ElementType.INPUT, box, innerX, innerY, innerW, innerH, val, color, swatchColor, fallbackRadius, partsEvents, innerEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff);
            }
            default -> {
                drawBorderedControl(elements, id, "_input", ElementType.INPUT, box, innerX, innerY, innerW, innerH, val, color, bgColor, fallbackRadius, partsEvents, innerEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff);
                if (box.node.attrs.containsKey("placeholder")) {
                    String placeholderText = val.isEmpty() ? box.node.attrs.get("placeholder") : "";
                    int fontH = (int) fontHVal;
                    int phX = box.x + border.bwLeft() + 4;
                    int phY = box.y + (box.h - fontH) / 2;
                    Map<String, String> phEvents = new HashMap<>(passiveBorderEvents);
                    addElement(elements, id, id + "_ph", ElementType.TEXT, phX, phY, innerW, fontH, placeholderText, 0xFFAAAAAA, 0x00000000, phEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, innerMeta);
                }
            }
        }
    }

    private void drawBorderedControl(List<UIElement> elements, String id, String suffix, ElementType type, LayoutBox box,
                                     int innerX, int innerY, int innerW, int innerH, String text, int color, int bgColor, int fallbackRadius,
                                     Map<String, String> partsEvents, Map<String, String> innerEvents, Map<String, String> passiveBorderEvents,
                                     BorderSpec border, UIElement.RenderMeta baseMeta, UIElement.RenderMeta innerMeta, int rOff) {
        if (border.useRoundedBorder()) {
            addElement(elements, id, id + "_bbg", ElementType.RECT, box.x, box.y, box.w, box.h, "", 0, border.borderColor(), passiveBorderEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, baseMeta);
            addElement(elements, id, id + suffix, type, innerX, innerY, innerW, innerH, text, color, bgColor, innerEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, innerMeta);
        } else {
            addElement(elements, id, id + suffix, type, box.x, box.y, box.w, box.h, text, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, baseMeta);
            if (border.hasBorder()) drawStraightBorders(elements, id, box, rOff, passiveBorderEvents, border, baseMeta);
        }
    }

    private void drawDefaultBlockElement(List<UIElement> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                         Corners radii, ClipRect normalizedClip, float fontHVal, float ox, float oy,
                                         Map<String, String> partsEvents, Map<String, String> passiveBorderEvents,
                                         BorderSpec border, UIElement.RenderMeta baseMeta, UIElement.RenderMeta innerMeta, int rOff) {
        if ("li".equals(box.node.tag)) {
            int dotSize = Math.max(2, (int) (4 * box.fontSize));
            int dotX = box.x - (int) (12 * box.fontSize);
            int dotY = box.y + box.pT + (int) (8 * box.fontSize) - (dotSize / 2);
            addElement(elements, id, id + "_dot", ElementType.RECT, dotX, dotY, dotSize, dotSize, "", 0, color, Map.of(RenderKeys.MC_PARENT, id), dotSize / 2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, baseMeta);
        }

        if (border.isCircle() && border.hasBorder()) {
            int thickness = border.maxThickness();
            String bData = thickness + ":" + border.bcTop() + "," + border.bcRight() + "," + border.bcBottom() + "," + border.bcLeft();
            UIElement.CircleBorderSpec cbSpec = new UIElement.CircleBorderSpec(thickness, border.bcTop(), border.bcRight(), border.bcBottom(), border.bcLeft());
            if (bgColor != 0x00000000 || !partsEvents.isEmpty()) {
                addElement(elements, id, id + "_bg", ElementType.RECT, box.x, box.y, box.w, box.h, "", color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, baseMeta);
            }
            addElement(elements, id, id + "_cb", ElementType.CIRCLE_BORDER, box.x, box.y, box.w, box.h, bData, 0, 0, Map.of(RenderKeys.MC_PARENT, id), 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE,
                    new UIElement.RenderMeta(normalizedClip, radii, null, cbSpec, fontHVal, ox, oy, 1, PseudoStates.EMPTY));
        } else if (border.useRoundedBorder()) {
            addElement(elements, id, id + "_bbg", ElementType.RECT, box.x, box.y, box.w, box.h, "", 0, border.borderColor(), passiveBorderEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, baseMeta);
            if (bgColor != 0x00000000 || !partsEvents.isEmpty()) {
                Map<String, String> innerEvents = new HashMap<>(partsEvents);
                int innerX = box.x + border.bwLeft();
                int innerY = box.y + border.bwTop();
                int innerW = Math.max(0, box.w - border.bwLeft() - border.bwRight());
                int innerH = Math.max(0, box.h - border.bwTop() - border.bwBottom());
                addElement(elements, id, id + "_bg", ElementType.RECT, innerX, innerY, innerW, innerH, "", color, bgColor, innerEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, innerMeta);
            }
        } else {
            if (bgColor != 0x00000000 || !partsEvents.isEmpty()) {
                addElement(elements, id, id + "_bg", ElementType.RECT, box.x, box.y, box.w, box.h, "", color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, baseMeta);
            }
            if (border.hasBorder()) {
                drawStraightBorders(elements, id, box, rOff, passiveBorderEvents, border, baseMeta);
            }
        }

        if (box.node.attrs.containsKey("data-ghost-item")) {
            String ghostItem = box.node.attrs.get("data-ghost-item").trim();
            if (!ghostItem.isEmpty() && !"minecraft:air".equals(ghostItem)) {
                int iconX = box.x + (box.w - 16) / 2;
                int iconY = box.y + (box.h - 16) / 2;
                addElement(elements, id, id + "_ghost_img", ElementType.IMAGE, ImageFitMode.FILL, iconX, iconY, 16, 16, "item:" + ghostItem, color, 0, partsEvents, 0, 0.85f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, innerMeta);
            }
        }
    }

    private void drawStraightBorders(List<UIElement> elements, String id, LayoutBox box, int rOff, Map<String, String> partsEvents, BorderSpec b, UIElement.RenderMeta meta) {
        UIElement.RenderMeta borderMeta = new UIElement.RenderMeta(meta.clipRect(), null, null, null, meta.fontHeight(), meta.originX(), meta.originY(), 1, PseudoStates.EMPTY);
        if (b.bwTop() > 0)
            addElement(elements, id, id + "_b_top", ElementType.RECT, box.x + rOff, box.y, box.w - rOff * 2, b.bwTop(), "", 0, b.bcTop(), partsEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, borderMeta);
        if (b.bwBottom() > 0)
            addElement(elements, id, id + "_b_bottom", ElementType.RECT, box.x + rOff, box.y + box.h - b.bwBottom(), box.w - rOff * 2, b.bwBottom(), "", 0, b.bcBottom(), partsEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, borderMeta);
        if (b.bwLeft() > 0)
            addElement(elements, id, id + "_b_left", ElementType.RECT, box.x, box.y + rOff, b.bwLeft(), box.h - rOff * 2, "", 0, b.bcLeft(), partsEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, borderMeta);
        if (b.bwRight() > 0)
            addElement(elements, id, id + "_b_right", ElementType.RECT, box.x + box.w - b.bwRight(), box.y + rOff, b.bwRight(), box.h - rOff * 2, "", 0, b.bcRight(), partsEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, borderMeta);
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

    private record TransitionSpec(int durationMs, int delayMs, String easing) {}

    private record BorderSpec(
            boolean hasBorder, boolean isCircle, boolean useRoundedBorder, int borderColor,
            int bwTop, int bwRight, int bwBottom, int bwLeft,
            int bcTop, int bcRight, int bcBottom, int bcLeft,
            Corners innerRadii
    ) {
        int maxThickness() {
            return Math.max(Math.max(bwTop, bwRight), Math.max(bwBottom, bwLeft));
        }
    }

    private record RenderTask(int zIndex, int domOrder, Consumer<List<UIElement>> drawAction) {}
}