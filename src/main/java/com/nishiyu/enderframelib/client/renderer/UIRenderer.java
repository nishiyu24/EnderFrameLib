package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.core.css.CssParser;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.model.UIElement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UIRenderer {
    private final int rootW;
    private final int rootH;
    private final Map<String, String> keyframes;
    private int fragIdCounter = 0;

    public UIRenderer(int rootW, int rootH, Map<String, String> keyframes) {
        this.rootW = rootW;
        this.rootH = rootH;
        this.keyframes = keyframes;
    }

    public List<UIElement> render(LayoutBox box) {
        fragIdCounter = 0;
        List<RenderTask> renderQueue = new ArrayList<>();
        List<UIElement> elements = new ArrayList<>();

        if (box.node.tag.equals("root")) {
            int canvasBg = 0xFF111111;
            LayoutBox bodyBox = findBodyBox(box);
            if (bodyBox != null) {
                if (bodyBox.style.containsKey("background") || bodyBox.style.containsKey("background-color")) {
                    String bodyBg = bodyBox.style.getOrDefault("background", bodyBox.style.get("background-color"));
                    canvasBg = CssParser.parseColor(bodyBg);
                }
            }
            if (canvasBg != 0x00000000) {
                addElement(elements, "bg_root", "rect", box.x, box.y, Math.max(box.w, rootW), Math.max(box.h, rootH), "", 0, canvasBg, Map.of(), 0, 1f, 0, 0, 1f, 1f, 0, 0, "");
            }

            for (LayoutBox child : box.children) {
                buildRenderQueue(child, renderQueue, 0, null, null);
            }
        } else {
            buildRenderQueue(box, renderQueue, 0, null, null);
        }

        renderQueue.sort((a, b) -> Integer.compare(a.zIndex(), b.zIndex()));

        for (RenderTask task : renderQueue) {
            task.drawAction().accept(elements);
        }
        return elements;
    }

    private LayoutBox findBodyBox(LayoutBox box) {
        if (box.node.tag.equals("body")) return box;
        for (LayoutBox child : box.children) {
            LayoutBox found = findBodyBox(child);
            if (found != null) return found;
        }
        return null;
    }

    private float parseOriginProp(String p) {
        switch (p) {
            case "left", "top" -> {
                return 0.0f;
            }
            case "right", "bottom" -> {
                return 1.0f;
            }
            case "center" -> {
                return 0.5f;
            }
        }
        if (p.endsWith("%")) {
            try {
                return Float.parseFloat(p.replace("%", "")) / 100f;
            } catch (Exception ignored) {
            }
        }
        return 0.5f;
    }

    private static String parseGradientDef(String bgProp) {
        if (bgProp == null || !bgProp.toLowerCase().contains("linear-gradient")) return null;
        String lower = bgProp.toLowerCase();
        String dir = "bottom";
        if (lower.contains("to right") || lower.contains("90deg")) dir = "right";
        else if (lower.contains("to left") || lower.contains("270deg") || lower.contains("-90deg")) dir = "left";
        else if (lower.contains("to top") || lower.contains("0deg")) dir = "top";
        else if (lower.contains("135deg") || lower.contains("to bottom right")) dir = "diag";

        List<Integer> colors = new ArrayList<>();
        Matcher m = Pattern.compile("(#[0-9a-fA-F]{3,8}|rgba?\\s*\\([^)]+\\)|\\b(?:red|green|lime|blue|yellow|aqua|cyan|magenta|purple|orange|gold|black|white|gray|grey)\\b)").matcher(bgProp);
        while (m.find()) {
            colors.add(CssParser.parseColor(m.group(1)));
        }
        if (colors.size() >= 2) {
            return dir + ":" + colors.getFirst() + ":" + colors.getLast();
        }
        return null;
    }

    private void buildRenderQueue(LayoutBox box, List<RenderTask> queue, int parentZIndex, float[] clipRect, String parentId) {
        if (box.w <= 0 || box.h <= 0) return;
        if (box.style.getOrDefault("display", "block").equals("none")) return;

        int currentZ = box.zIndex != 0 ? box.zIndex : parentZIndex;
        String currentId = box.node.id;

        queue.add(new RenderTask(currentZ, (elements) -> drawBoxSelf(elements, box, clipRect, parentId)));

        float[] childClip = clipRect;
        String overflow = box.style.getOrDefault("overflow", "visible");

        boolean isRootElement = box.node.tag.equals("root") || box.node.tag.equals("html") || box.node.tag.equals("body");

        if (!isRootElement && (overflow.equals("hidden") || overflow.equals("auto") || overflow.equals("scroll") || overflow.equals("clip"))) {
            float nMinX = box.x + box.bL;
            float nMinY = box.y + box.bT;
            float nMaxX = box.x + box.w - box.bR;
            float nMaxY = box.y + box.h - box.bB;

            int[] radii = CssParser.parseBorderRadius(box.style.getOrDefault("border-radius", "0"), box.w, rootW, rootH);
            float maxBorder = Math.max(Math.max(box.bT, box.bB), Math.max(box.bL, box.bR));

            float limit = Math.min(nMaxX - nMinX, nMaxY - nMinY) / 2.0f;
            float cRTL = Math.clamp(radii[0] - maxBorder, 0, limit);
            float cRTR = Math.clamp(radii[1] - maxBorder, 0, limit);
            float cRBR = Math.clamp(radii[2] - maxBorder, 0, limit);
            float cRBL = Math.clamp(radii[3] - maxBorder, 0, limit);

            if (childClip != null) {
                nMinX = Math.max(childClip[0], nMinX);
                nMinY = Math.max(childClip[1], nMinY);
                nMaxX = Math.min(childClip[2], nMaxX);
                nMaxY = Math.min(childClip[3], nMaxY);
            }
            childClip = new float[]{nMinX, nMinY, nMaxX, nMaxY, cRTL, cRTR, cRBR, cRBL};
        }

        final float[] finalChildClip = childClip;
        for (LayoutBox child : box.children) {
            buildRenderQueue(child, queue, currentZ, finalChildClip, currentId);
        }

        if (overflow.equals("scroll") || overflow.equals("auto")) {
            queue.add(new RenderTask(currentZ + 1, (elements) -> drawScrollbarsIfNeeded(elements, box, clipRect)));
        }
    }

    private void drawScrollbarsIfNeeded(List<UIElement> elements, LayoutBox box, float[] clipRect) {
        if ("none".equals(box.style.get("scrollbar-width"))) return;
        float scrollW = box.w, scrollH = box.h, curX = 0, curY = 0;
        try {
            scrollW = Float.parseFloat(box.node.attrs.getOrDefault("data-scroll-w", String.valueOf(box.w)));
            scrollH = Float.parseFloat(box.node.attrs.getOrDefault("data-scroll-h", String.valueOf(box.h)));
            curX = Float.parseFloat(box.node.attrs.getOrDefault("data-scroll-x", "0"));
            curY = Float.parseFloat(box.node.attrs.getOrDefault("data-scroll-y", "0"));
        } catch (Exception ignored) {}

        Map<String, String> sbEvents = new HashMap<>();
        sbEvents.put("mc-parent", box.node.id);
        if (clipRect != null && clipRect.length >= 8) {
            sbEvents.put("mc-clip", clipRect[0] + "," + clipRect[1] + "," + clipRect[2] + "," + clipRect[3] + "," + clipRect[4] + "," + clipRect[5] + "," + clipRect[6] + "," + clipRect[7]);
        }

        int barThickness = 4;
        if (scrollH > box.h + 2 && box.h > 12) {
            int trackX = box.x + box.w - box.bR - barThickness - 2;
            int trackY = box.y + box.bT + 2;
            int trackH = box.h - box.bT - box.bB - 4;
            int thumbH = Math.max(12, (int) (trackH * (box.h / scrollH)));
            float maxScrollY = Math.max(1f, scrollH - box.h);
            int thumbY = trackY + (int) ((trackH - thumbH) * Math.clamp(curY / maxScrollY, 0f, 1f));

            addElement(elements, box.node.id + "_sb_vt", "rect", trackX, trackY, barThickness, trackH, "", 0, 0x33000000, sbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, "");
            addElement(elements, box.node.id + "_sb_v", "rect", trackX, thumbY, barThickness, thumbH, "", 0, 0x88CCCCCC, sbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, "");
        }
        if (scrollW > box.w + 2 && box.w > 12) {
            int trackX = box.x + box.bL + 2;
            int trackY = box.y + box.h - box.bB - barThickness - 2;
            int trackW = box.w - box.bL - box.bR - 4;
            int thumbW = Math.max(12, (int) (trackW * (box.w / scrollW)));
            float maxScrollX = Math.max(1f, scrollW - box.w);
            int thumbX = trackX + (int) ((trackW - thumbW) * Math.clamp(curX / maxScrollX, 0f, 1f));

            addElement(elements, box.node.id + "_sb_ht", "rect", trackX, trackY, trackW, barThickness, "", 0, 0x33000000, sbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, "");
            addElement(elements, box.node.id + "_sb_h", "rect", thumbX, trackY, thumbW, barThickness, "", 0, 0x88CCCCCC, sbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, "");
        }
    }

    private void addElement(List<UIElement> elements, String id, String type, int x, int y, int w, int h, String text, int color, int bgColor, Map<String, String> events, int radius, float opacity, float tx, float ty, float sx, float sy, float rot, int trans, String animDef) {
        elements.add(new UIElement(id, type, x, y, w, h, text, color, bgColor, events, radius, opacity, tx, ty, sx, sy, rot, trans, animDef));
    }

    private static float[] parseTransformProps(String t, int boxW, int boxH, float defTx, float defTy, float defSx, float defSy, float defRot) {
        float tx = defTx, ty = defTy, sx = defSx, sy = defSy, rot = defRot;
        Matcher txM = Pattern.compile("translateX\\(\\s*(-?[0-9.]+)(px|%)\\s*\\)").matcher(t);
        if (txM.find())
            tx = txM.group(2).equals("%") ? (Float.parseFloat(txM.group(1)) / 100.0f) * boxW : Float.parseFloat(txM.group(1));
        Matcher tyM = Pattern.compile("translateY\\(\\s*(-?[0-9.]+)(px|%)\\s*\\)").matcher(t);
        if (tyM.find())
            ty = tyM.group(2).equals("%") ? (Float.parseFloat(tyM.group(1)) / 100.0f) * boxH : Float.parseFloat(tyM.group(1));
        Matcher tm = Pattern.compile("translate\\(\\s*(-?[0-9.]+)(px|%)(?:\\s*,\\s*(-?[0-9.]+)(px|%))?\\s*\\)").matcher(t);
        if (tm.find()) {
            tx = tm.group(2).equals("%") ? (Float.parseFloat(tm.group(1)) / 100.0f) * boxW : Float.parseFloat(tm.group(1));
            if (tm.group(3) != null)
                ty = tm.group(4).equals("%") ? (Float.parseFloat(tm.group(3)) / 100.0f) * boxH : Float.parseFloat(tm.group(3));
        }
        Matcher sm = Pattern.compile("scale\\(\\s*(-?[0-9.]+)(?:\\s*,\\s*(-?[0-9.]+))?\\s*\\)").matcher(t);
        if (sm.find()) {
            sx = Float.parseFloat(sm.group(1));
            sy = sm.group(2) != null ? Float.parseFloat(sm.group(2)) : sx;
        }
        Matcher rm = Pattern.compile("rotate\\(\\s*(-?[0-9.]+)(deg|rad)?\\s*\\)").matcher(t);
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

        if (box.style.containsKey(prefix + "-opacity")) {
            try { op = Float.parseFloat(box.style.get(prefix + "-opacity")); has = true; } catch (Exception ignored) {}
        }
        if (box.style.containsKey(prefix + "-transform")) {
            has = true;
            float[] tr = parseTransformProps(box.style.get(prefix + "-transform"), box.w, box.h, tx, ty, sx, sy, rot);
            tx = tr[0]; ty = tr[1]; sx = tr[2]; sy = tr[3]; rot = tr[4];
        }
        if (box.style.containsKey(prefix + "-color")) {
            col = CssParser.parseColor(box.style.get(prefix + "-color"));
            has = true;
        }
        if (box.style.containsKey(prefix + "-background") || box.style.containsKey(prefix + "-background-color")) {
            bgCol = CssParser.parseColor(box.style.getOrDefault(prefix + "-background", box.style.get(prefix + "-background-color")));
            has = true;
        }
        return has ? (marker + op + ":" + tx + ":" + ty + ":" + sx + ":" + sy + ":" + rot + ":" + col + ":" + bgCol) : "";
    }

    private String parseAnimationDef(String animProp, float baseW, float baseH) {
        if (animProp == null || animProp.equals("none")) return "";
        String[] parts = animProp.split("\\s+");
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
            if (p.matches("[0-9.]+(s|ms)")) {
                try {
                    float val = Float.parseFloat(p.replace("ms", "").replace("s", ""));
                    if (p.endsWith("s") && !p.endsWith("ms")) val *= 1000;
                    dur = (int) val;
                } catch (Exception ignored) {
                }
            }
        }

        String rawContent = keyframes.get(name);
        StringBuilder sb = new StringBuilder();
        sb.append(dur).append("_").append(infinite ? "1" : "0").append("_");

        for (String frame : rawContent.split("\\|")) {
            if (frame.isEmpty()) continue;
            String[] kv = frame.split("`");
            if (kv.length < 2) continue;
            String pct = kv[0];
            String props = kv[1];

            float[] tr = parseTransformProps(props, (int) baseW, (int) baseH, 0, 0, 1, 1, 0);
            float op = 1;
            Matcher om = Pattern.compile("opacity\\s*:\\s*([0-9.]+)").matcher(props);
            if (om.find()) op = Float.parseFloat(om.group(1));

            sb.append(pct).append(":").append(tr[0]).append(",").append(tr[1]).append(",")
                    .append(tr[2]).append(",").append(tr[3]).append(",").append(tr[4]).append(",").append(op).append("|");
        }
        return sb.toString();
    }

    private void drawBoxSelf(List<UIElement> elements, LayoutBox box, float[] clipRect, String parentId) {
        int[] radii = CssParser.parseBorderRadius(box.style.getOrDefault("border-radius", "0"), box.w, rootW, rootH);
        int fallbackRadius = radii[0];
        int rOff = Math.min(fallbackRadius, Math.min(box.w, box.h) / 2);

        int color = CssParser.parseColor(box.style.get("color"));
        if (color == 0x00000000) color = 0xFFF5F5F0;

        String bgProp = box.style.getOrDefault("background", box.style.getOrDefault("background-color", "transparent"));
        int bgColor = CssParser.parseColor(bgProp);
        String gradientDef = parseGradientDef(bgProp);

        String id = box.node.id;

        float tx = 0, ty = 0, sx = 1, sy = 1, rot = 0, opacity = 1;
        int trans = 0, delay = 0;
        String easing = "ease-out";

        if (box.style.containsKey("opacity")) {
            try {
                opacity = Float.parseFloat(box.style.get("opacity"));
            } catch (Exception ignored) {
            }
        }

        if (box.style.containsKey("transform")) {
            float[] tr = parseTransformProps(box.style.get("transform"), box.w, box.h, tx, ty, sx, sy, rot);
            tx = tr[0]; ty = tr[1]; sx = tr[2]; sy = tr[3]; rot = tr[4];
        }

        if (box.style.containsKey("transition")) {
            String tr = box.style.get("transition");
            Matcher m = Pattern.compile("([0-9.]+)(s|ms)").matcher(tr);
            if (m.find()) {
                float val = Float.parseFloat(m.group(1));
                if (m.group(2).equals("s")) val *= 1000;
                trans = (int) val;
                if (m.find()) {
                    float dval = Float.parseFloat(m.group(1));
                    if (m.group(2).equals("s")) dval *= 1000;
                    delay = (int) dval;
                }
            }
            if (tr.contains("linear")) easing = "linear";
            else if (tr.contains("ease-in-out")) easing = "ease-in-out";
            else if (tr.contains("ease-in")) easing = "ease-in";
            else if (tr.contains("ease-out") || tr.contains("ease")) easing = "ease-out";
        }
        if (box.style.containsKey("transition-delay")) {
            String td = box.style.get("transition-delay");
            Matcher m = Pattern.compile("([0-9.]+)(s|ms)").matcher(td);
            if (m.find()) {
                float val = Float.parseFloat(m.group(1));
                if (m.group(2).equals("s")) val *= 1000;
                delay = (int) val;
            }
        }
        if (box.style.containsKey("transition-timing-function")) {
            easing = box.style.get("transition-timing-function").trim();
        }

        String hoverStr = buildPseudoStateDef("hover", "@@HOVER@@", box, opacity, tx, ty, sx, sy, rot, color, bgColor);
        String activeStr = buildPseudoStateDef("active", "@@ACTIVE@@", box, opacity, tx, ty, sx, sy, rot, color, bgColor);
        String focusStr = buildPseudoStateDef("focus", "@@FOCUS@@", box, opacity, tx, ty, sx, sy, rot, color, bgColor);
        String animDef = parseAnimationDef(box.style.get("animation"), box.w, box.h) + hoverStr + activeStr + focusStr;

        Map<String, String> anchorEvents = new HashMap<>();
        if (parentId != null) anchorEvents.put("mc-parent", parentId);
        if (delay > 0) anchorEvents.put("mc-delay", String.valueOf(delay));
        if (!easing.equals("ease-out")) anchorEvents.put("mc-easing", easing);
        if (box.style.containsKey("transform-origin")) {
            String origin = box.style.get("transform-origin").toLowerCase();
            float ox = 0.5f, oy = 0.5f;
            String[] parts = origin.split("\\s+");
            if (parts.length > 0) ox = parseOriginProp(parts[0]);
            if (parts.length > 1) oy = parseOriginProp(parts[1]);
            anchorEvents.put("mc-origin", ox + "," + oy);
        }

        addElement(elements, id, "node", box.x, box.y, box.w, box.h, "", 0, 0, anchorEvents, 0, opacity, tx, ty, sx, sy, rot, trans, animDef);

        Map<String, String> partsEvents = new HashMap<>();
        partsEvents.put("mc-parent", id);

        if (gradientDef != null) partsEvents.put("mc-gradient", gradientDef);
        if (box.node.attrs.containsKey("title")) partsEvents.put("mc-title", box.node.attrs.get("title"));
        else if (box.node.attrs.containsKey("data-tooltip")) partsEvents.put("mc-title", box.node.attrs.get("data-tooltip"));

        if (box.node.attrs.containsKey("data-sound")) partsEvents.put("data-sound", box.node.attrs.get("data-sound"));
        if (box.node.attrs.containsKey("data-sound-pitch")) partsEvents.put("data-sound-pitch", box.node.attrs.get("data-sound-pitch"));

        String overflowX = box.style.getOrDefault("overflow-x", box.style.getOrDefault("overflow", "visible"));
        String overflowY = box.style.getOrDefault("overflow-y", box.style.getOrDefault("overflow", "visible"));
        boolean isScrollable = overflowX.equals("scroll") || overflowX.equals("auto") || overflowY.equals("scroll") || overflowY.equals("auto");
        if (box.node.tag.equals("root") || box.node.tag.equals("html") || box.node.tag.equals("body")) {
            isScrollable = true;
        }
        if (isScrollable) {
            partsEvents.put("mc-scrollable", box.node.attrs.getOrDefault("data-scroll-w", String.valueOf(box.w)) + "," + box.node.attrs.getOrDefault("data-scroll-h", String.valueOf(box.h)));
            partsEvents.put("mc-scroll-current", box.node.attrs.getOrDefault("data-scroll-x", "0") + "," + box.node.attrs.getOrDefault("data-scroll-y", "0"));
        }

        partsEvents.put("mc-radius", radii[0] + "," + radii[1] + "," + radii[2] + "," + radii[3]);

        if (clipRect != null && clipRect.length >= 8) {
            partsEvents.put("mc-clip", clipRect[0] + "," + clipRect[1] + "," + clipRect[2] + "," + clipRect[3] + "," + clipRect[4] + "," + clipRect[5] + "," + clipRect[6] + "," + clipRect[7]);
        } else if (clipRect != null && clipRect.length >= 5) {
            partsEvents.put("mc-clip", clipRect[0] + "," + clipRect[1] + "," + clipRect[2] + "," + clipRect[3] + "," + clipRect[4] + "," + clipRect[4] + "," + clipRect[4] + "," + clipRect[4]);
        }

        for (Map.Entry<String, String> entry : box.node.attrs.entrySet()) {
            if (entry.getKey().toLowerCase().startsWith("on"))
                partsEvents.put(entry.getKey().toLowerCase(), entry.getValue());
        }
        for (Map.Entry<String, String> entry : box.style.entrySet()) {
            if (entry.getKey().toLowerCase().startsWith("on"))
                partsEvents.put(entry.getKey().toLowerCase(), entry.getValue());
        }
        if ((box.node.tag.equals("button") || box.node.tag.equals("select")) && !partsEvents.containsKey("onclick"))
            partsEvents.put("onclick", id);

        String baseType = "rect";

        if (!box.inlineFragments.isEmpty() && !box.node.tag.equals("#text") && !box.node.tag.equals("img") && !box.node.tag.equals("input") && !box.node.tag.equals("select") && !box.node.tag.equals("progress")) {
            for (LayoutBox.InlineFragment frag : box.inlineFragments) {
                if (bgColor != 0x00000000 || !partsEvents.isEmpty()) {
                    addElement(elements, id + "_frag_" + (fragIdCounter++), baseType, frag.x, frag.y, frag.w, frag.h, "", color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "");
                }
            }
            return;
        }

        String shadow = box.style.get("box-shadow");
        if (shadow != null && !shadow.equals("none")) {
            int shadowOffset = 8;
            addElement(elements, id + "_shadow", "rect", box.x + shadowOffset / 2, box.y + shadowOffset, box.w, box.h, "", 0, 0x1A000000, Map.of("mc-parent", id), fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "");
        }

        boolean hasBorder = false;
        int borderColor = 0x00000000;
        int bwTop = 0, bwRight = 0, bwBottom = 0, bwLeft = 0;
        int bcTop = 0, bcRight = 0, bcBottom = 0, bcLeft = 0;

        String[] directions = {"top", "right", "bottom", "left"};
        for (String dir : directions) {
            String borderProp = box.style.get("border-" + dir);
            if (borderProp != null && !borderProp.equals("none")) {
                String[] parts = borderProp.split("\\s+");
                if (parts.length >= 3) {
                    int bw = CssParser.parseDim(parts[0], box.w, rootW, rootH);
                    if (bw > 0) {
                        int bc = CssParser.parseColor(parts[parts.length - 1]);
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

        boolean isCircle = radii[0] >= (box.w / 2) - 1 && radii[1] >= (box.w / 2) - 1 && radii[2] >= (box.w / 2) - 1 && radii[3] >= (box.w / 2) - 1 && box.w > 0 && box.h > 0 && Math.abs(box.w - box.h) <= 2;
        boolean useRoundedBorder = (radii[0] > 0 || radii[1] > 0 || radii[2] > 0 || radii[3] > 0) && hasBorder && !isCircle;

        switch (box.node.tag) {
            case "img", "video" -> {
                String src = box.style.getOrDefault("src", box.node.attrs.getOrDefault("src", ""));
                String type = "img:" + box.style.getOrDefault("object-fit", "fill");
                if (box.node.tag.equals("video")) type = "video";

                if (isCircle && hasBorder) {
                    int thickness = Math.max(Math.max(bwTop, bwRight), Math.max(bwBottom, bwLeft));
                    String bData = thickness + ":" + bcTop + "," + bcRight + "," + bcBottom + "," + bcLeft;
                    addElement(elements, id + "_cb", "circle_border", box.x, box.y, box.w, box.h, bData, 0, 0, partsEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, "");
                } else if (useRoundedBorder) {
                    addElement(elements, id + "_bbg", "rect", box.x, box.y, box.w, box.h, "", 0, borderColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "");
                } else if (hasBorder) {
                    drawStraightBorders(elements, id, box, rootW, rootH, rOff, partsEvents, bwTop, bwRight, bwBottom, bwLeft, bcTop, bcRight, bcBottom, bcLeft);
                }

                int innerX = box.x + bwLeft;
                int innerY = box.y + bwTop;
                int innerW = Math.max(0, box.w - bwLeft - bwRight);
                int innerH = Math.max(0, box.h - bwTop - bwBottom);

                addElement(elements, id + "_img", type, innerX, innerY, innerW, innerH, src, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "");
            }
            case "#text" -> {
                Map<String, String> baseTextEvents = new HashMap<>(partsEvents);
                if (box.style.containsKey("font-weight")) baseTextEvents.put("font-weight", box.style.get("font-weight"));
                if (box.style.containsKey("text-decoration")) baseTextEvents.put("text-decoration", box.style.get("text-decoration"));
                if (box.style.containsKey("text-shadow") && !"none".equals(box.style.get("text-shadow"))) baseTextEvents.put("text-shadow", "true");

                if (!box.inlineFragments.isEmpty()) {
                    for (LayoutBox.InlineFragment frag : box.inlineFragments) {
                        if (frag.text != null && !frag.text.trim().isEmpty()) {
                            int fontH = (int) (16 * box.fontSize);
                            addElement(elements, id + "_txt_" + (fragIdCounter++), "text", frag.x, frag.y, frag.w, fontH, frag.text, color, bgColor, baseTextEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, "");
                        }
                    }
                } else if (box.textLines != null && !box.textLines.isEmpty()) {
                    String align = box.style.getOrDefault("text-align", "left");
                    Map<String, String> textEvents = new HashMap<>(baseTextEvents);
                    textEvents.put("text-align", align);

                    int lineY = box.y + box.pT + box.bT;
                    int fontH = (int) (16 * box.fontSize);

                    float lh = 1.375f;
                    if (box.style.containsKey("line-height")) {
                        String lhStr = box.style.get("line-height");
                        try {
                            lh = Float.parseFloat(lhStr);
                        } catch (Exception e) {
                            if (lhStr.endsWith("px")) lh = Float.parseFloat(lhStr.replace("px", "")) / 16f;
                        }
                    }
                    int lineStep = (int) (fontH * lh);
                    int availableW = Math.max(0, box.w - box.pL - box.pR - box.bL - box.bR);

                    for (String line : box.textLines) {
                        if (line == null || line.trim().isEmpty()) {
                            lineY += lineStep;
                            continue;
                        }
                        if (box.style.getOrDefault("text-transform", "").equals("uppercase")) line = line.toUpperCase();
                        int lineX = box.x + box.pL + box.bL;
                        addElement(elements, id + "_txt_" + (fragIdCounter++), "text", lineX, lineY, availableW, fontH, line, color, bgColor, textEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, "");
                        lineY += lineStep;
                    }
                }
            }
            case "progress" -> {
                String val = box.node.attrs.getOrDefault("value", "0");
                String min = box.node.attrs.getOrDefault("min", "0");
                String max = box.node.attrs.getOrDefault("max", "100");
                String progressData = val + ":" + min + ":" + max;
                addElement(elements, id + "_progress", "progress", box.x, box.y, box.w, box.h, progressData, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "");
            }
            case "select" -> {
                String currentVal = box.node.attrs.getOrDefault("value", "");
                String displayLabel = currentVal;
                for (HtmlNode child : box.node.children) {
                    if ("option".equalsIgnoreCase(child.tag)) {
                        String optVal = child.attrs.getOrDefault("value", child.getTextContent().trim());
                        if (currentVal.isEmpty() || currentVal.equals(optVal)) {
                            displayLabel = child.getTextContent().trim();
                            if (currentVal.isEmpty()) currentVal = optVal;
                            break;
                        }
                    }
                }
                int innerX = box.x + bwLeft, innerY = box.y + bwTop;
                int innerW = Math.max(0, box.w - bwLeft - bwRight), innerH = Math.max(0, box.h - bwTop - bwBottom);
                if (useRoundedBorder) {
                    addElement(elements, id + "_bbg", "rect", box.x, box.y, box.w, box.h, "", 0, borderColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "");
                    addElement(elements, id + "_select", "select", innerX, innerY, innerW, innerH, displayLabel + " ▾", color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "");
                } else {
                    addElement(elements, id + "_select", "select", box.x, box.y, box.w, box.h, displayLabel + " ▾", color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "");
                    if (hasBorder) drawStraightBorders(elements, id, box, rootW, rootH, rOff, partsEvents, bwTop, bwRight, bwBottom, bwLeft, bcTop, bcRight, bcBottom, bcLeft);
                }
            }
            case "input" -> {
                String inputType = box.node.attrs.getOrDefault("type", "text");
                String val = box.node.attrs.getOrDefault("value", "");

                int innerX = box.x + bwLeft;
                int innerY = box.y + bwTop;
                int innerW = Math.max(0, box.w - bwLeft - bwRight);
                int innerH = Math.max(0, box.h - bwTop - bwBottom);

                Map<String, String> innerEvents = new HashMap<>(partsEvents);
                int irTL = Math.max(0, radii[0] - Math.max(bwTop, bwLeft));
                int irTR = Math.max(0, radii[1] - Math.max(bwTop, bwRight));
                int irBR = Math.max(0, radii[2] - Math.max(bwBottom, bwRight));
                int irBL = Math.max(0, radii[3] - Math.max(bwBottom, bwLeft));
                innerEvents.put("mc-radius", irTL + "," + irTR + "," + irBR + "," + irBL);

                if (inputType.equals("checkbox") || inputType.equals("radio")) {
                    val = Boolean.toString(box.node.attrs.containsKey("checked"));
                    if (useRoundedBorder) {
                        addElement(elements, id + "_bbg", "rect", box.x, box.y, box.w, box.h, "", 0, borderColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "");
                        addElement(elements, id + "_input", "toggle", innerX, innerY, innerW, innerH, val, color, bgColor, innerEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "");
                    } else {
                        addElement(elements, id + "_input", "toggle", box.x, box.y, box.w, box.h, val, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "");
                        if (hasBorder) drawStraightBorders(elements, id, box, rootW, rootH, rOff, partsEvents, bwTop, bwRight, bwBottom, bwLeft, bcTop, bcRight, bcBottom, bcLeft);
                    }
                } else if (inputType.equals("range")) {
                    String min = box.node.attrs.getOrDefault("min", "0"), max = box.node.attrs.getOrDefault("max", "100");
                    if (val.isEmpty()) val = "50";
                    String rangeData = val + ":" + min + ":" + max;
                    if (useRoundedBorder) {
                        addElement(elements, id + "_bbg", "rect", box.x, box.y, box.w, box.h, "", 0, borderColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "");
                        addElement(elements, id + "_input", "range", innerX, innerY, innerW, innerH, rangeData, color, bgColor, innerEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "");
                    } else {
                        addElement(elements, id + "_input", "range", box.x, box.y, box.w, box.h, rangeData, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "");
                        if (hasBorder) drawStraightBorders(elements, id, box, rootW, rootH, rOff, partsEvents, bwTop, bwRight, bwBottom, bwLeft, bcTop, bcRight, bcBottom, bcLeft);
                    }
                } else {
                    boolean hasPlaceholder = val.isEmpty() && box.node.attrs.containsKey("placeholder");
                    String placeholderText = hasPlaceholder ? box.node.attrs.get("placeholder") : "";
                    if (useRoundedBorder) {
                        addElement(elements, id + "_bbg", "rect", box.x, box.y, box.w, box.h, "", 0, borderColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "");
                        addElement(elements, id + "_input", "input", innerX, innerY, innerW, innerH, val, color, bgColor, innerEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "");
                    } else {
                        addElement(elements, id + "_input", "input", box.x, box.y, box.w, box.h, val, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "");
                        if (hasBorder) drawStraightBorders(elements, id, box, rootW, rootH, rOff, partsEvents, bwTop, bwRight, bwBottom, bwLeft, bcTop, bcRight, bcBottom, bcLeft);
                    }
                    if (hasPlaceholder) {
                        int phColor = 0xFFAAAAAA, fontH = (int) (16 * box.fontSize), phX = box.x + bwLeft + 4, phY = box.y + (box.h - fontH) / 2;
                        addElement(elements, id + "_ph", "text", phX, phY, box.w - bwLeft - bwRight, fontH, placeholderText, phColor, 0x00000000, Map.of("mc-parent", id), 0, 1f, 0, 0, 1f, 1f, 0, 0, "");
                    }
                }
            }
            case "body", "html", "div", "p", "h1", "h2", "h3", "span", "main", "header", "footer", "section", "nav",
                 "ul", "li", "article", "button", "a" -> {

                if (box.node.tag.equals("li")) {
                    int dotSize = (int) (4 * box.fontSize);
                    if (dotSize < 2) dotSize = 2;
                    int dotX = box.x - (int) (12 * box.fontSize);
                    int dotY = box.y + box.pT + (int) (8 * box.fontSize) - (dotSize / 2);
                    addElement(elements, id + "_dot", "rect", dotX, dotY, dotSize, dotSize, "", 0, color, Map.of("mc-parent", id), dotSize / 2, 1f, 0, 0, 1f, 1f, 0, 0, "");
                }

                if (isCircle && hasBorder) {
                    int thickness = Math.max(Math.max(bwTop, bwRight), Math.max(bwBottom, bwLeft));
                    String bData = thickness + ":" + bcTop + "," + bcRight + "," + bcBottom + "," + bcLeft;
                    if (bgColor != 0x00000000 || !partsEvents.isEmpty())
                        addElement(elements, id + "_bg", baseType, box.x, box.y, box.w, box.h, "", color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "");
                    addElement(elements, id + "_cb", "circle_border", box.x, box.y, box.w, box.h, bData, 0, 0, Map.of("mc-parent", id), 0, 1f, 0, 0, 1f, 1f, 0, 0, "");
                } else if (useRoundedBorder) {
                    addElement(elements, id + "_bbg", "rect", box.x, box.y, box.w, box.h, "", 0, borderColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "");

                    if (bgColor != 0x00000000 || !partsEvents.isEmpty()) {
                        Map<String, String> innerEvents = new HashMap<>(partsEvents);
                        int irTL = Math.max(0, radii[0] - Math.max(bwTop, bwLeft));
                        int irTR = Math.max(0, radii[1] - Math.max(bwTop, bwRight));
                        int irBR = Math.max(0, radii[2] - Math.max(bwBottom, bwRight));
                        int irBL = Math.max(0, radii[3] - Math.max(bwBottom, bwLeft));
                        innerEvents.put("mc-radius", irTL + "," + irTR + "," + irBR + "," + irBL);

                        int innerX = box.x + bwLeft;
                        int innerY = box.y + bwTop;
                        int innerW = Math.max(0, box.w - bwLeft - bwRight);
                        int innerH = Math.max(0, box.h - bwTop - bwBottom);

                        addElement(elements, id + "_bg", baseType, innerX, innerY, innerW, innerH, "", color, bgColor, innerEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "");
                    }
                } else {
                    if (bgColor != 0x00000000 || !partsEvents.isEmpty())
                        addElement(elements, id + "_bg", baseType, box.x, box.y, box.w, box.h, "", color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, 0, "");
                    if (hasBorder) {
                        drawStraightBorders(elements, id, box, rootW, rootH, rOff, partsEvents, bwTop, bwRight, bwBottom, bwLeft, bcTop, bcRight, bcBottom, bcLeft);
                    }
                }
            }
        }
    }

    private void drawStraightBorders(List<UIElement> elements, String id, LayoutBox box, int rootW, int rootH, int rOff, Map<String, String> partsEvents, int bwTop, int bwRight, int bwBottom, int bwLeft, int bcTop, int bcRight, int bcBottom, int bcLeft) {
        if (bwTop > 0)
            addElement(elements, id + "_b_top", "rect", box.x + rOff, box.y, box.w - rOff * 2, bwTop, "", 0, bcTop, partsEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, "");
        if (bwBottom > 0)
            addElement(elements, id + "_b_bottom", "rect", box.x + rOff, box.y + box.h - bwBottom, box.w - rOff * 2, bwBottom, "", 0, bcBottom, partsEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, "");
        if (bwLeft > 0)
            addElement(elements, id + "_b_left", "rect", box.x, box.y + rOff, bwLeft, box.h - rOff * 2, "", 0, bcLeft, partsEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, "");
        if (bwRight > 0)
            addElement(elements, id + "_b_right", "rect", box.x + box.w - bwRight, box.y + rOff, bwRight, box.h - rOff * 2, "", 0, bcRight, partsEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, "");
    }

    private record RenderTask(int zIndex, Consumer<List<UIElement>> drawAction) {
    }
}