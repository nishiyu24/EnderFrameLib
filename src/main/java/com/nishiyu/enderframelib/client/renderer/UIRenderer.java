package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.core.css.ColorParser;
import com.nishiyu.enderframelib.core.css.LengthResolver;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.ScrollState;
import com.nishiyu.enderframelib.core.model.UIElement;
import net.minecraft.client.gui.components.EditBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
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
            if (bodyBox != null && (bodyBox.style.containsKey("background") || bodyBox.style.containsKey("background-color"))) {
                String bodyBg = bodyBox.style.getOrDefault("background", bodyBox.style.get("background-color"));
                canvasBg = ColorParser.parseColor(bodyBg);
            }
            if (canvasBg != 0x00000000) {
                addElement(elements, "bg_root", "rect", box.x, box.y, Math.max(box.w, rootW), Math.max(box.h, rootH),
                        "", 0, canvasBg, Map.of(), 0, 1f, 0, 0, 1f, 1f, 0, 0, "", UIElement.RenderMeta.EMPTY);
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
        if (bgProp == null || !bgProp.toLowerCase().contains("linear-gradient")) return null;
        String lower = bgProp.toLowerCase();
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
        if ("none".equals(box.style.getOrDefault("display", "block"))) return;
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

        String overflow = box.style.getOrDefault("overflow", "visible");
        if ("scroll".equals(overflow) || "auto".equals(overflow)) {
            int sbOrder = domOrderCounter++;
            queue.add(new RenderTask(currentZ + 1, sbOrder, elements -> drawScrollbarsIfNeeded(elements, box, clipRect)));
        }
    }

    private float[] computeChildClipRect(LayoutBox box, float[] parentClip) {
        String tag = box.node.tag;
        boolean isRootElement = "root".equals(tag) || "html".equals(tag) || "body".equals(tag);
        if (isRootElement) return parentClip;

        String overflow = box.style.getOrDefault("overflow", "visible");
        boolean shouldClip = "hidden".equals(overflow) || "auto".equals(overflow)
                || "scroll".equals(overflow) || "clip".equals(overflow)
                || "true".equalsIgnoreCase(box.node.attrs.get("data-pan-zoom"));
        if (!shouldClip) return parentClip;

        float nMinX = box.x + box.bL;
        float nMinY = box.y + box.bT;
        float nMaxX = box.x + box.w - box.bR;
        float nMaxY = box.y + box.h - box.bB;

        int[] radii = LengthResolver.parseBorderRadius(box.style.getOrDefault("border-radius", "0"), box.w, rootW, rootH);
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
        if ("none".equals(box.style.get("scrollbar-width"))) return;

        float scrollW = box.scrollWidth;
        float scrollH = box.scrollHeight;
        float curX = scrollState.getScrollX(box.node.getId());
        float curY = scrollState.getScrollY(box.node.getId());

        Map<String, String> sbEvents = new HashMap<>(4);
        sbEvents.put("mc-parent", box.node.getId());
        if (clipRect != null && clipRect.length >= 8) {
            sbEvents.put("mc-clip", formatClipRect(clipRect));
        }
        UIElement.RenderMeta sbMeta = new UIElement.RenderMeta(clipRect, new int[]{2, 2, 2, 2}, null, null, 12.0f, 0.5f, 0.5f, 1);

        int barThickness = 4;
        if (scrollH > box.h + 2 && box.h > 12) {
            int trackX = box.x + box.w - box.bR - barThickness - 2;
            int trackY = box.y + box.bT + 2;
            int trackH = box.h - box.bT - box.bB - 4;
            int thumbH = Math.max(12, (int) (trackH * (box.h / scrollH)));
            float maxScrollY = Math.max(1f, scrollH - box.h);
            int thumbY = trackY + (int) ((trackH - thumbH) * Math.clamp(curY / maxScrollY, 0f, 1f));

            addElement(elements, box.node.getId() + "_sb_vt", "rect", trackX, trackY, barThickness, trackH, "", 0, 0x33000000, sbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, "", sbMeta);
            addElement(elements, box.node.getId() + "_sb_v", "rect", trackX, thumbY, barThickness, thumbH, "", 0, 0x88CCCCCC, sbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, "", sbMeta);
        }
        if (scrollW > box.w + 2 && box.w > 12) {
            int trackX = box.x + box.bL + 2;
            int trackY = box.y + box.h - box.bB - barThickness - 2;
            int trackW = box.w - box.bL - box.bR - 4;
            int thumbW = Math.max(12, (int) (trackW * (box.w / scrollW)));
            float maxScrollX = Math.max(1f, scrollW - box.w);
            int thumbX = trackX + (int) ((trackW - thumbW) * Math.clamp(curX / maxScrollX, 0f, 1f));

            addElement(elements, box.node.getId() + "_sb_ht", "rect", trackX, trackY, trackW, barThickness, "", 0, 0x33000000, sbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, "", sbMeta);
            addElement(elements, box.node.getId() + "_sb_h", "rect", thumbX, trackY, thumbW, barThickness, "", 0, 0x88CCCCCC, sbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, "", sbMeta);
        }
    }

    private void addElement(List<UIElement> elements, String id, String type, int x, int y, int w, int h, String text,
                            int color, int bgColor, Map<String, String> events, int radius, float opacity,
                            float tx, float ty, float sx, float sy, float rot, int trans, String animDef,
                            UIElement.RenderMeta meta) {
        elements.add(new UIElement(id, type, x, y, w, h, text, color, bgColor, events, radius, opacity, tx, ty, sx, sy, rot, trans, animDef, meta));
    }

    private static String resolveLiveInputValue(String nodeId, String attrValue) {
        if (!WebRenderCore.activeInputId.isEmpty() && !WebRenderCore.activeInputId.endsWith("_textarea")) {
            String activeBaseId = ClientWebManager.getBaseNodeId(WebRenderCore.activeInputId);
            if (nodeId.equals(activeBaseId) || (nodeId + "_input").equals(WebRenderCore.activeInputId)) {
                EditBox eb = WebRenderCore.findActiveEditBox();
                if (eb != null) {
                    return eb.getValue();
                }
            }
        }
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

    private String buildPseudoStateDef(String prefix, String marker, LayoutBox box, float baseOp, float baseTx, float baseTy, float baseSx, float baseSy, float baseRot, int baseColor, int baseBgColor) {
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
        return has ? (marker + op + ":" + tx + ":" + ty + ":" + sx + ":" + sy + ":" + rot + ":" + col + ":" + bgCol) : "";
    }

    private String parseAnimationDef(String animProp, float baseW, float baseH) {
        if (animProp == null || "none".equals(animProp)) return "";
        String[] parts = WHITESPACE_SPLIT.split(animProp.trim());
        if (parts.length == 0) return "";

        String name = "";
        for (String p : parts) {
            if (keyframes.containsKey(p)) {
                name = p;
                break;
            }
        }
        if (name.isEmpty()) return "";

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

        String rawContent = keyframes.get(name);
        StringBuilder sb = new StringBuilder();
        sb.append(dur).append('_').append(infinite ? '1' : '0').append('_');

        for (String frame : rawContent.split("\\|")) {
            if (frame.isEmpty()) continue;
            int tickIdx = frame.indexOf('`');
            if (tickIdx <= 0) continue;
            String pct = frame.substring(0, tickIdx);
            String props = frame.substring(tickIdx + 1);

            float[] tr = parseTransformProps(props, (int) baseW, (int) baseH, 0, 0, 1, 1, 0);
            float op = 1f;
            Matcher om = OPACITY_PROP_PATTERN.matcher(props);
            if (om.find()) {
                try { op = Float.parseFloat(om.group(1)); } catch (NumberFormatException ignored) {}
            }

            sb.append(pct).append(':').append(tr[0]).append(',').append(tr[1]).append(',')
                    .append(tr[2]).append(',').append(tr[3]).append(',').append(tr[4]).append(',').append(op).append('|');
        }
        return sb.toString();
    }

    private static TransitionSpec parseTransitionSpec(Map<String, String> style) {
        int durationMs = 0;
        int delayMs = 0;
        String easing = "ease-out";

        String tr = style.get("transition");
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

        String td = style.get("transition-delay");
        if (td != null) {
            Matcher m = TIME_UNIT_PATTERN.matcher(td);
            if (m.find()) {
                float val = Float.parseFloat(m.group(1));
                if ("s".equals(m.group(2))) val *= 1000f;
                delayMs = (int) val;
            }
        }

        String ttf = style.get("transition-timing-function");
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

        int[] innerRadii = new int[]{
                Math.max(0, radii[0] - Math.max(bwTop, bwLeft)),
                Math.max(0, radii[1] - Math.max(bwTop, bwRight)),
                Math.max(0, radii[2] - Math.max(bwBottom, bwRight)),
                Math.max(0, radii[3] - Math.max(bwBottom, bwLeft))
        };

        return new BorderSpec(hasBorder, isCircle, useRoundedBorder, borderColor,
                bwTop, bwRight, bwBottom, bwLeft, bcTop, bcRight, bcBottom, bcLeft, innerRadii);
    }

    private void drawBoxSelf(List<UIElement> elements, LayoutBox box, float[] clipRect, String parentId) {
        int[] radii = LengthResolver.parseBorderRadius(box.style.getOrDefault("border-radius", "0"), box.w, rootW, rootH);
        int fallbackRadius = radii[0];
        int rOff = Math.min(fallbackRadius, Math.min(box.w, box.h) / 2);

        int color = ColorParser.parseColor(box.style.get("color"));
        if (color == 0x00000000) color = 0xFFF5F5F0;

        String bgProp = box.style.getOrDefault("background", box.style.getOrDefault("background-color", "transparent"));
        int bgColor = ColorParser.parseColor(bgProp);
        UIElement.GradientSpec gradientSpec = parseGradientSpec(bgProp);

        String id = box.node.getId();

        float opacity = parseFloatAttr(box.style.getRawMap(), "opacity", 1.0f);
        float[] tr = parseTransformProps(box.style.get("transform"), box.w, box.h, 0f, 0f, 1f, 1f, 0f);
        float tx = tr[0] + parseFloatAttr(box.node.attrs, "data-pan-x", 0f);
        float ty = tr[1] + parseFloatAttr(box.node.attrs, "data-pan-y", 0f);
        float zoom = parseFloatAttr(box.node.attrs, "data-zoom", 1.0f);
        float sx = tr[2] * zoom;
        float sy = tr[3] * zoom;
        float rot = tr[4];

        TransitionSpec transSpec = parseTransitionSpec(box.style.getRawMap());

        String hoverStr = buildPseudoStateDef("hover", "@@HOVER@@", box, opacity, tx, ty, sx, sy, rot, color, bgColor);
        String activeStr = buildPseudoStateDef("active", "@@ACTIVE@@", box, opacity, tx, ty, sx, sy, rot, color, bgColor);
        String focusStr = buildPseudoStateDef("focus", "@@FOCUS@@", box, opacity, tx, ty, sx, sy, rot, color, bgColor);
        String animDef = parseAnimationDef(box.style.get("animation"), box.w, box.h) + hoverStr + activeStr + focusStr;

        float ox = 0.5f, oy = 0.5f;
        Map<String, String> anchorEvents = new HashMap<>(4);
        if (parentId != null) anchorEvents.put("mc-parent", parentId);
        if (transSpec.delayMs() > 0) anchorEvents.put("mc-delay", String.valueOf(transSpec.delayMs()));
        if (!"ease-out".equals(transSpec.easing())) anchorEvents.put("mc-easing", transSpec.easing());
        if (box.style.containsKey("transform-origin")) {
            String[] parts = WHITESPACE_SPLIT.split(box.style.get("transform-origin").toLowerCase().trim());
            if (parts.length > 0) ox = parseOriginProp(parts[0]);
            if (parts.length > 1) oy = parseOriginProp(parts[1]);
            anchorEvents.put("mc-origin", ox + "," + oy);
        }

        float fontHVal = Math.max(10.0f, 16.0f * box.fontSize);
        int itemCount = (int) parseFloatAttr(box.node.attrs, "data-count", 1f);

        float[] normalizedClip = normalizeClipRect(clipRect);
        UIElement.RenderMeta anchorMeta = new UIElement.RenderMeta(normalizedClip, radii, null, null, fontHVal, ox, oy, itemCount);
        addElement(elements, id, "node", box.x, box.y, box.w, box.h, "", 0, 0, anchorEvents, 0, opacity, tx, ty, sx, sy, rot, transSpec.durationMs(), animDef, anchorMeta);

        Map<String, String> partsEvents = buildPartsEvents(box, id, fontHVal, gradientSpec, radii, normalizedClip);
        Map<String, String> passiveBorderEvents = buildPassiveBorderEvents(id, radii, partsEvents.get("mc-clip"));

        UIElement.RenderMeta baseMeta = new UIElement.RenderMeta(normalizedClip, radii, gradientSpec, null, fontHVal, ox, oy, itemCount);

        if (!box.inlineFragments.isEmpty() && !NON_FRAGMENT_TAGS.contains(box.node.tag)) {
            for (LayoutBox.InlineFragment frag : box.inlineFragments) {
                if (bgColor != 0x00000000 || !partsEvents.isEmpty()) {
                    addElement(elements, id + "_frag_" + (fragIdCounter++), "rect", frag.x, frag.y, frag.w, frag.h,
                            "", color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "", baseMeta);
                }
            }
            return;
        }

        drawBoxShadowIfPresent(elements, box, id, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy);

        BorderSpec border = parseBorderSpec(box, radii);
        UIElement.RenderMeta innerMeta = new UIElement.RenderMeta(normalizedClip, border.innerRadii(), gradientSpec, null, fontHVal, ox, oy, itemCount);

        switch (box.node.tag) {
            case "mc-scene" -> drawSceneElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff);
            case "img", "video", "mc-fluid", "mc-entity" -> drawMediaElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff);
            case "#text" -> drawTextNodeElement(elements, box, id, color, bgColor, normalizedClip, fontHVal, ox, oy, partsEvents);
            case "progress", "meter" -> {
                String val = box.node.attrs.getOrDefault("value", "0");
                String min = box.node.attrs.getOrDefault("min", "0");
                String max = box.node.attrs.getOrDefault("max", "100");
                addElement(elements, id + "_progress", "progress", box.x, box.y, box.w, box.h, val + ":" + min + ":" + max, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "", baseMeta);
            }
            case "canvas" -> drawCanvasElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff);
            case "textarea" -> drawTextareaElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff);
            case "select" -> drawSelectElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff);
            case "input" -> drawInputElement(elements, box, id, color, bgColor, fallbackRadius, fontHVal, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff);
            default -> drawDefaultBlockElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff);
        }
    }

    private Map<String, String> buildPartsEvents(LayoutBox box, String id, float fontHVal, UIElement.GradientSpec gradientSpec, int[] radii, float[] normalizedClip) {
        Map<String, String> partsEvents = new HashMap<>();
        partsEvents.put("mc-parent", id);
        partsEvents.put("mc-font-h", String.valueOf(fontHVal));
        if (box.style.containsKey("font-family")) {
            partsEvents.put("font-family", box.style.get("font-family"));
        }
        if (gradientSpec != null) {
            partsEvents.put("mc-gradient", gradientSpec.direction() + ":" + gradientSpec.startColor() + ":" + gradientSpec.endColor());
        }
        if (box.node.attrs.containsKey("title")) partsEvents.put("mc-title", box.node.attrs.get("title"));
        else if (box.node.attrs.containsKey("data-tooltip")) partsEvents.put("mc-title", box.node.attrs.get("data-tooltip"));

        for (String attr : PASSTHROUGH_DATA_ATTRS) {
            if (box.node.attrs.containsKey(attr)) {
                partsEvents.put(attr, box.node.attrs.get(attr));
            }
        }

        String overflowX = box.style.getOrDefault("overflow-x", box.style.getOrDefault("overflow", "visible"));
        String overflowY = box.style.getOrDefault("overflow-y", box.style.getOrDefault("overflow", "visible"));
        boolean isScrollable = "scroll".equals(overflowX) || "auto".equals(overflowX)
                || "scroll".equals(overflowY) || "auto".equals(overflowY)
                || "root".equals(box.node.tag) || "html".equals(box.node.tag) || "body".equals(box.node.tag);
        if (isScrollable) {
            partsEvents.put("mc-scrollable", box.scrollWidth + "," + box.scrollHeight);
            partsEvents.put("mc-scroll-current", scrollState.getScrollX(box.node.getId()) + "," + scrollState.getScrollY(box.node.getId()));
        }

        partsEvents.put("mc-radius", formatRadii(radii));
        if (normalizedClip != null) {
            partsEvents.put("mc-clip", formatClipRect(normalizedClip));
        }

        for (Map.Entry<String, String> entry : box.node.attrs.entrySet()) {
            if (entry.getKey().toLowerCase().startsWith("on")) {
                partsEvents.put(entry.getKey().toLowerCase(), entry.getValue());
            }
        }
        for (Map.Entry<String, String> entry : box.style.getRawMap().entrySet()) {
            if (entry.getKey().toLowerCase().startsWith("on")) {
                partsEvents.put(entry.getKey().toLowerCase(), entry.getValue());
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

    private static Map<String, String> buildPassiveBorderEvents(String id, int[] radii, String clipStr) {
        Map<String, String> passive = new HashMap<>(4);
        passive.put("mc-parent", id);
        passive.put("mc-radius", formatRadii(radii));
        if (clipStr != null) passive.put("mc-clip", clipStr);
        return passive;
    }

    private void drawBoxShadowIfPresent(List<UIElement> elements, LayoutBox box, String id, int fallbackRadius, int[] radii, float[] normalizedClip, float fontHVal, float ox, float oy) {
        String shadow = box.style.get("box-shadow");
        if (shadow == null || "none".equals(shadow)) return;
        Map<String, String> shadowEv = Map.of("mc-parent", id);
        addElement(elements, id + "_shadow_1", "rect", box.x + 1, box.y + 2, box.w + 2, box.h + 2, "", 0, 0x22000000, shadowEv, fallbackRadius + 1, 1f, 0, 0, 1f, 1f, 0, 0, "",
                new UIElement.RenderMeta(normalizedClip, new int[]{radii[0] + 1, radii[1] + 1, radii[2] + 1, radii[3] + 1}, null, null, fontHVal, ox, oy, 1));
        addElement(elements, id + "_shadow_2", "rect", box.x + 2, box.y + 4, box.w + 4, box.h + 4, "", 0, 0x15000000, shadowEv, fallbackRadius + 2, 1f, 0, 0, 1f, 1f, 0, 0, "",
                new UIElement.RenderMeta(normalizedClip, new int[]{radii[0] + 2, radii[1] + 2, radii[2] + 2, radii[3] + 2}, null, null, fontHVal, ox, oy, 1));
        addElement(elements, id + "_shadow_3", "rect", box.x + 3, box.y + 6, box.w + 6, box.h + 6, "", 0, 0x0B000000, shadowEv, fallbackRadius + 3, 1f, 0, 0, 1f, 1f, 0, 0, "",
                new UIElement.RenderMeta(normalizedClip, new int[]{radii[0] + 3, radii[1] + 3, radii[2] + 3, radii[3] + 3}, null, null, fontHVal, ox, oy, 1));
    }

    private void drawSceneElement(List<UIElement> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                  Map<String, String> partsEvents, Map<String, String> passiveBorderEvents,
                                  BorderSpec border, UIElement.RenderMeta baseMeta, UIElement.RenderMeta innerMeta, int rOff) {
        if (border.useRoundedBorder()) {
            addElement(elements, id + "_bbg", "rect", box.x, box.y, box.w, box.h, "", 0, border.borderColor(), passiveBorderEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "", baseMeta);
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

        addElement(elements, id + "_scene", "scene", innerX, innerY, innerW, innerH, id, color, bgColor, sceneEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "", innerMeta);
    }

    private void drawMediaElement(List<UIElement> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                  int[] radii, float[] normalizedClip, float fontHVal, float ox, float oy,
                                  Map<String, String> partsEvents, Map<String, String> passiveBorderEvents,
                                  BorderSpec border, UIElement.RenderMeta baseMeta, UIElement.RenderMeta innerMeta, int rOff) {
        String src = box.style.getOrDefault("src", box.node.attrs.getOrDefault("src", ""));
        if ("mc-fluid".equals(box.node.tag) && !src.startsWith("fluid:")) {
            src = "fluid:" + box.node.attrs.getOrDefault("fluid", src);
        } else if ("mc-entity".equals(box.node.tag) && !src.startsWith("entity:")) {
            src = "entity:" + box.node.attrs.getOrDefault("entity", src.isEmpty() ? "@self" : src);
        }

        String type = "video".equals(box.node.tag) ? "video" : "img:" + box.style.getOrDefault("object-fit", "fill");

        if (border.isCircle() && border.hasBorder()) {
            int thickness = border.maxThickness();
            String bData = thickness + ":" + border.bcTop() + "," + border.bcRight() + "," + border.bcBottom() + "," + border.bcLeft();
            UIElement.CircleBorderSpec cbSpec = new UIElement.CircleBorderSpec(thickness, border.bcTop(), border.bcRight(), border.bcBottom(), border.bcLeft());
            addElement(elements, id + "_cb", "circle_border", box.x, box.y, box.w, box.h, bData, 0, 0, passiveBorderEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, "",
                    new UIElement.RenderMeta(normalizedClip, radii, null, cbSpec, fontHVal, ox, oy, 1));
        } else if (border.useRoundedBorder()) {
            addElement(elements, id + "_bbg", "rect", box.x, box.y, box.w, box.h, "", 0, border.borderColor(), passiveBorderEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "", baseMeta);
        } else if (border.hasBorder()) {
            drawStraightBorders(elements, id, box, rOff, passiveBorderEvents, border, baseMeta);
        }

        int innerX = box.x + border.bwLeft();
        int innerY = box.y + border.bwTop();
        int innerW = Math.max(0, box.w - border.bwLeft() - border.bwRight());
        int innerH = Math.max(0, box.h - border.bwTop() - border.bwBottom());

        addElement(elements, id + "_img", type, innerX, innerY, innerW, innerH, src, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "", innerMeta);
    }

    private void drawTextNodeElement(List<UIElement> elements, LayoutBox box, String id, int color, int bgColor,
                                     float[] normalizedClip, float fontHVal, float ox, float oy, Map<String, String> partsEvents) {
        Map<String, String> baseTextEvents = new HashMap<>(partsEvents);
        if (box.style.containsKey("font-weight")) baseTextEvents.put("font-weight", box.style.get("font-weight"));
        if (box.style.containsKey("font-family")) baseTextEvents.put("font-family", box.style.get("font-family"));
        if (box.style.containsKey("text-decoration")) baseTextEvents.put("text-decoration", box.style.get("text-decoration"));
        if (box.style.containsKey("text-shadow") && !"none".equals(box.style.get("text-shadow"))) baseTextEvents.put("text-shadow", "true");

        UIElement.RenderMeta txtMeta = new UIElement.RenderMeta(normalizedClip, null, null, null, fontHVal, ox, oy, 1);

        if (!box.inlineFragments.isEmpty()) {
            int fontH = (int) (16 * box.fontSize);
            for (LayoutBox.InlineFragment frag : box.inlineFragments) {
                if (frag.text != null && !frag.text.trim().isEmpty()) {
                    addElement(elements, id + "_txt_" + (fragIdCounter++), "text", frag.x, frag.y, frag.w, fontH, frag.text, color, bgColor, baseTextEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, "", txtMeta);
                }
            }
        } else if (box.textLines != null && !box.textLines.isEmpty()) {
            String align = box.style.getOrDefault("text-align", "left");
            Map<String, String> textEvents = new HashMap<>(baseTextEvents);
            textEvents.put("text-align", align);

            int lineY = box.y + box.pT + box.bT;
            int fontH = (int) (16 * box.fontSize);

            float lh = 1.375f;
            String lhStr = box.style.get("line-height");
            if (lhStr != null) {
                try {
                    lh = lhStr.endsWith("px") ? Float.parseFloat(lhStr.substring(0, lhStr.length() - 2)) / 16f : Float.parseFloat(lhStr);
                } catch (NumberFormatException ignored) {}
            }
            int lineStep = (int) (fontH * lh);
            int availableW = Math.max(0, box.w - box.pL - box.pR - box.bL - box.bR);
            boolean isUppercase = "uppercase".equals(box.style.get("text-transform"));

            for (String line : box.textLines) {
                if (line == null || line.trim().isEmpty()) {
                    lineY += lineStep;
                    continue;
                }
                String renderedLine = isUppercase ? line.toUpperCase() : line;
                int lineX = box.x + box.pL + box.bL;
                addElement(elements, id + "_txt_" + (fragIdCounter++), "text", lineX, lineY, availableW, fontH, renderedLine, color, bgColor, textEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, "", txtMeta);
                lineY += lineStep;
            }
        }
    }

    private void drawCanvasElement(List<UIElement> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                   Map<String, String> partsEvents, Map<String, String> passiveBorderEvents,
                                   BorderSpec border, UIElement.RenderMeta baseMeta, UIElement.RenderMeta innerMeta, int rOff) {
        if (border.useRoundedBorder()) {
            addElement(elements, id + "_bbg", "rect", box.x, box.y, box.w, box.h, "", 0, border.borderColor(), passiveBorderEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "", baseMeta);
        } else if (border.hasBorder()) {
            drawStraightBorders(elements, id, box, rOff, passiveBorderEvents, border, baseMeta);
        }
        int innerX = box.x + border.bwLeft();
        int innerY = box.y + border.bwTop();
        int innerW = Math.max(0, box.w - border.bwLeft() - border.bwRight());
        int innerH = Math.max(0, box.h - border.bwTop() - border.bwBottom());
        addElement(elements, id + "_canvas", "canvas", innerX, innerY, innerW, innerH, id, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "", innerMeta);
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
        taEvents.put("mc-radius", formatRadii(border.innerRadii()));
        if (box.node.attrs.containsKey("data-syntax")) taEvents.put("data-syntax", box.node.attrs.get("data-syntax"));
        if (box.node.attrs.containsKey("data-line-numbers")) taEvents.put("data-line-numbers", box.node.attrs.get("data-line-numbers"));

        if (border.useRoundedBorder()) {
            addElement(elements, id + "_bbg", "rect", box.x, box.y, box.w, box.h, "", 0, border.borderColor(), passiveBorderEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "", baseMeta);
            addElement(elements, id + "_textarea", "textarea", innerX, innerY, innerW, innerH, val, color, bgColor, taEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "", innerMeta);
        } else {
            addElement(elements, id + "_textarea", "textarea", box.x, box.y, box.w, box.h, val, color, bgColor, taEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "", baseMeta);
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
            addElement(elements, id + "_bbg", "rect", box.x, box.y, box.w, box.h, "", 0, border.borderColor(), passiveBorderEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "", baseMeta);
            addElement(elements, id + "_select", "select", innerX, innerY, innerW, innerH, displayLabel, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "", innerMeta);
        } else {
            addElement(elements, id + "_select", "select", box.x, box.y, box.w, box.h, displayLabel, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "", baseMeta);
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
        innerEvents.put("mc-radius", formatRadii(border.innerRadii()));

        switch (inputType) {
            case "checkbox", "radio" -> {
                String checkedVal = Boolean.toString(box.node.attrs.containsKey("checked"));
                drawBorderedControl(elements, id, "_input", "toggle", box, innerX, innerY, innerW, innerH, checkedVal, color, bgColor, fallbackRadius, partsEvents, innerEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff);
            }
            case "range" -> {
                String min = box.node.attrs.getOrDefault("min", "0");
                String max = box.node.attrs.getOrDefault("max", "100");
                if (val.isEmpty()) val = "50";
                int colonIdx = val.indexOf(':');
                if (colonIdx > 0) val = val.substring(0, colonIdx);
                String rangeData = val + ":" + min + ":" + max;
                drawBorderedControl(elements, id, "_input", "range", box, innerX, innerY, innerW, innerH, rangeData, color, bgColor, fallbackRadius, partsEvents, innerEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff);
            }
            case "color" -> {
                int swatchColor = ColorParser.parseColor(val.isEmpty() ? "#89b4fa" : val);
                drawBorderedControl(elements, id, "_input", "input", box, innerX, innerY, innerW, innerH, val, color, swatchColor, fallbackRadius, partsEvents, innerEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff);
            }
            default -> {
                drawBorderedControl(elements, id, "_input", "input", box, innerX, innerY, innerW, innerH, val, color, bgColor, fallbackRadius, partsEvents, innerEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff);
                if (box.node.attrs.containsKey("placeholder")) {
                    String placeholderText = val.isEmpty() ? box.node.attrs.get("placeholder") : "";
                    int fontH = (int) fontHVal;
                    int phX = box.x + border.bwLeft() + 4;
                    int phY = box.y + (box.h - fontH) / 2;
                    Map<String, String> phEvents = new HashMap<>(passiveBorderEvents);
                    addElement(elements, id + "_ph", "text", phX, phY, innerW, fontH, placeholderText, 0xFFAAAAAA, 0x00000000, phEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, "", innerMeta);
                }
            }
        }
    }

    private void drawBorderedControl(List<UIElement> elements, String id, String suffix, String type, LayoutBox box,
                                     int innerX, int innerY, int innerW, int innerH, String text, int color, int bgColor, int fallbackRadius,
                                     Map<String, String> partsEvents, Map<String, String> innerEvents, Map<String, String> passiveBorderEvents,
                                     BorderSpec border, UIElement.RenderMeta baseMeta, UIElement.RenderMeta innerMeta, int rOff) {
        if (border.useRoundedBorder()) {
            addElement(elements, id + "_bbg", "rect", box.x, box.y, box.w, box.h, "", 0, border.borderColor(), passiveBorderEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "", baseMeta);
            addElement(elements, id + suffix, type, innerX, innerY, innerW, innerH, text, color, bgColor, innerEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "", innerMeta);
        } else {
            addElement(elements, id + suffix, type, box.x, box.y, box.w, box.h, text, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "", baseMeta);
            if (border.hasBorder()) drawStraightBorders(elements, id, box, rOff, passiveBorderEvents, border, baseMeta);
        }
    }

    private void drawDefaultBlockElement(List<UIElement> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                         int[] radii, float[] normalizedClip, float fontHVal, float ox, float oy,
                                         Map<String, String> partsEvents, Map<String, String> passiveBorderEvents,
                                         BorderSpec border, UIElement.RenderMeta baseMeta, UIElement.RenderMeta innerMeta, int rOff) {
        if ("li".equals(box.node.tag)) {
            int dotSize = Math.max(2, (int) (4 * box.fontSize));
            int dotX = box.x - (int) (12 * box.fontSize);
            int dotY = box.y + box.pT + (int) (8 * box.fontSize) - (dotSize / 2);
            addElement(elements, id + "_dot", "rect", dotX, dotY, dotSize, dotSize, "", 0, color, Map.of("mc-parent", id), dotSize / 2, 1f, 0, 0, 1f, 1f, 0, 0, "", baseMeta);
        }

        if (border.isCircle() && border.hasBorder()) {
            int thickness = border.maxThickness();
            String bData = thickness + ":" + border.bcTop() + "," + border.bcRight() + "," + border.bcBottom() + "," + border.bcLeft();
            UIElement.CircleBorderSpec cbSpec = new UIElement.CircleBorderSpec(thickness, border.bcTop(), border.bcRight(), border.bcBottom(), border.bcLeft());
            if (bgColor != 0x00000000 || !partsEvents.isEmpty()) {
                addElement(elements, id + "_bg", "rect", box.x, box.y, box.w, box.h, "", color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "", baseMeta);
            }
            addElement(elements, id + "_cb", "circle_border", box.x, box.y, box.w, box.h, bData, 0, 0, Map.of("mc-parent", id), 0, 1f, 0, 0, 1f, 1f, 0, 0, "",
                    new UIElement.RenderMeta(normalizedClip, radii, null, cbSpec, fontHVal, ox, oy, 1));
        } else if (border.useRoundedBorder()) {
            addElement(elements, id + "_bbg", "rect", box.x, box.y, box.w, box.h, "", 0, border.borderColor(), passiveBorderEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "", baseMeta);
            if (bgColor != 0x00000000 || !partsEvents.isEmpty()) {
                Map<String, String> innerEvents = new HashMap<>(partsEvents);
                innerEvents.put("mc-radius", formatRadii(border.innerRadii()));
                int innerX = box.x + border.bwLeft();
                int innerY = box.y + border.bwTop();
                int innerW = Math.max(0, box.w - border.bwLeft() - border.bwRight());
                int innerH = Math.max(0, box.h - border.bwTop() - border.bwBottom());
                addElement(elements, id + "_bg", "rect", innerX, innerY, innerW, innerH, "", color, bgColor, innerEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "", innerMeta);
            }
        } else {
            if (bgColor != 0x00000000 || !partsEvents.isEmpty()) {
                addElement(elements, id + "_bg", "rect", box.x, box.y, box.w, box.h, "", color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "", baseMeta);
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
                addElement(elements, id + "_ghost_img", "img:fill", iconX, iconY, 16, 16, "item:" + ghostItem, color, 0, partsEvents, 0, 0.85f, 0, 0, 1f, 1f, 0, 0, "", innerMeta);
            }
        }
    }

    private void drawStraightBorders(List<UIElement> elements, String id, LayoutBox box, int rOff, Map<String, String> partsEvents, BorderSpec b, UIElement.RenderMeta meta) {
        UIElement.RenderMeta borderMeta = new UIElement.RenderMeta(meta.clipRect(), null, null, null, meta.fontHeight(), meta.originX(), meta.originY(), 1);
        if (b.bwTop() > 0)
            addElement(elements, id + "_b_top", "rect", box.x + rOff, box.y, box.w - rOff * 2, b.bwTop(), "", 0, b.bcTop(), partsEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, "", borderMeta);
        if (b.bwBottom() > 0)
            addElement(elements, id + "_b_bottom", "rect", box.x + rOff, box.y + box.h - b.bwBottom(), box.w - rOff * 2, b.bwBottom(), "", 0, b.bcBottom(), partsEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, "", borderMeta);
        if (b.bwLeft() > 0)
            addElement(elements, id + "_b_left", "rect", box.x, box.y + rOff, b.bwLeft(), box.h - rOff * 2, "", 0, b.bcLeft(), partsEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, "", borderMeta);
        if (b.bwRight() > 0)
            addElement(elements, id + "_b_right", "rect", box.x + box.w - b.bwRight(), box.y + rOff, b.bwRight(), box.h - rOff * 2, "", 0, b.bcRight(), partsEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, "", borderMeta);
    }

    private static float[] normalizeClipRect(float[] clipRect) {
        if (clipRect == null) return null;
        if (clipRect.length >= 8) return clipRect;
        if (clipRect.length >= 5) {
            return new float[]{clipRect[0], clipRect[1], clipRect[2], clipRect[3], clipRect[4], clipRect[4], clipRect[4], clipRect[4]};
        }
        return null;
    }

    private static String formatRadii(int[] r) {
        return r[0] + "," + r[1] + "," + r[2] + "," + r[3];
    }

    private static String formatClipRect(float[] c) {
        return c[0] + "," + c[1] + "," + c[2] + "," + c[3] + "," + c[4] + "," + c[5] + "," + c[6] + "," + c[7];
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
            int[] innerRadii
    ) {
        int maxThickness() {
            return Math.max(Math.max(bwTop, bwRight), Math.max(bwBottom, bwLeft));
        }
    }

    private record RenderTask(int zIndex, int domOrder, Consumer<List<UIElement>> drawAction) {}
}