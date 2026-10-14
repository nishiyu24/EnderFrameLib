package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.core.css.CssParser;
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
        if (p.equals("left") || p.equals("top")) return 0.0f;
        if (p.equals("right") || p.equals("bottom")) return 1.0f;
        if (p.equals("center")) return 0.5f;
        if (p.endsWith("%")) {
            try {
                return Float.parseFloat(p.replace("%", "")) / 100f;
            } catch (Exception ignored) {
            }
        }
        return 0.5f;
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
            float cRTL = Math.min(Math.max(0, radii[0] - maxBorder), limit);
            float cRTR = Math.min(Math.max(0, radii[1] - maxBorder), limit);
            float cRBR = Math.min(Math.max(0, radii[2] - maxBorder), limit);
            float cRBL = Math.min(Math.max(0, radii[3] - maxBorder), limit);

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
    }

    private void addElement(List<UIElement> elements, String id, String type, int x, int y, int w, int h, String text, int color, int bgColor, Map<String, String> events, int radius, float opacity, float tx, float ty, float sx, float sy, float rot, int trans, String animDef) {
        elements.add(new UIElement(id, type, x, y, w, h, text, color, bgColor, events, radius, opacity, tx, ty, sx, sy, rot, trans, animDef));
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

            float tx = 0, ty = 0, sx = 1, sy = 1, rot = 0, op = 1;

            Matcher txM = Pattern.compile("translateX\\(\\s*(-?[0-9.]+)(px|%)\\s*\\)").matcher(props);
            if (txM.find()) {
                tx = Float.parseFloat(txM.group(1));
                if (txM.group(2).equals("%")) tx = (tx / 100.0f) * baseW;
            }
            Matcher tyM = Pattern.compile("translateY\\(\\s*(-?[0-9.]+)(px|%)\\s*\\)").matcher(props);
            if (tyM.find()) {
                ty = Float.parseFloat(tyM.group(1));
                if (tyM.group(2).equals("%")) ty = (ty / 100.0f) * baseH;
            }
            Matcher tm = Pattern.compile("translate\\(\\s*(-?[0-9.]+)(px|%)(?:\\s*,\\s*(-?[0-9.]+)(px|%))?\\s*\\)").matcher(props);
            if (tm.find()) {
                tx = Float.parseFloat(tm.group(1));
                if (tm.group(2).equals("%")) tx = (tx / 100.0f) * baseW;
                if (tm.group(3) != null) {
                    ty = Float.parseFloat(tm.group(3));
                    if (tm.group(4).equals("%")) ty = (ty / 100.0f) * baseH;
                }
            }

            Matcher sm = Pattern.compile("scale\\(\\s*(-?[0-9.]+)(?:\\s*,\\s*(-?[0-9.]+))?\\s*\\)").matcher(props);
            if (sm.find()) {
                sx = Float.parseFloat(sm.group(1));
                sy = sm.group(2) != null ? Float.parseFloat(sm.group(2)) : sx;
            }
            Matcher rm = Pattern.compile("rotate\\(\\s*(-?[0-9.]+)(deg|rad)?\\s*\\)").matcher(props);
            if (rm.find()) {
                float val = Float.parseFloat(rm.group(1));
                if ("rad".equals(rm.group(2))) val = (float) Math.toDegrees(val);
                rot = val;
            }
            Matcher om = Pattern.compile("opacity\\s*:\\s*([0-9.]+)").matcher(props);
            if (om.find()) op = Float.parseFloat(om.group(1));

            sb.append(pct).append(":").append(tx).append(",").append(ty).append(",")
                    .append(sx).append(",").append(sy).append(",").append(rot).append(",").append(op).append("|");
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
            String t = box.style.get("transform");
            Matcher txM = Pattern.compile("translateX\\(\\s*(-?[0-9.]+)(px|%)\\s*\\)").matcher(t);
            if (txM.find())
                tx = txM.group(2).equals("%") ? (Float.parseFloat(txM.group(1)) / 100.0f) * box.w : Float.parseFloat(txM.group(1));
            Matcher tyM = Pattern.compile("translateY\\(\\s*(-?[0-9.]+)(px|%)\\s*\\)").matcher(t);
            if (tyM.find())
                ty = tyM.group(2).equals("%") ? (Float.parseFloat(tyM.group(1)) / 100.0f) * box.h : Float.parseFloat(tyM.group(1));
            Matcher tm = Pattern.compile("translate\\(\\s*(-?[0-9.]+)(px|%)(?:\\s*,\\s*(-?[0-9.]+)(px|%))?\\s*\\)").matcher(t);
            if (tm.find()) {
                tx = tm.group(2).equals("%") ? (Float.parseFloat(tm.group(1)) / 100.0f) * box.w : Float.parseFloat(tm.group(1));
                if (tm.group(3) != null)
                    ty = tm.group(4).equals("%") ? (Float.parseFloat(tm.group(3)) / 100.0f) * box.h : Float.parseFloat(tm.group(3));
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

        float htx = tx, hty = ty, hsx = sx, hsy = sy, hrot = rot, hop = opacity;
        int hc = color, hbgc = bgColor;
        boolean hasHover = false;

        if (box.style.containsKey("hover-opacity")) {
            hop = Float.parseFloat(box.style.get("hover-opacity"));
            hasHover = true;
        }
        if (box.style.containsKey("hover-transform")) {
            hasHover = true;
            String ht = box.style.get("hover-transform");

            Matcher txM = Pattern.compile("translateX\\(\\s*(-?[0-9.]+)(px|%)\\s*\\)").matcher(ht);
            if (txM.find())
                htx = txM.group(2).equals("%") ? (Float.parseFloat(txM.group(1)) / 100.0f) * box.w : Float.parseFloat(txM.group(1));
            Matcher tyM = Pattern.compile("translateY\\(\\s*(-?[0-9.]+)(px|%)\\s*\\)").matcher(ht);
            if (tyM.find())
                hty = tyM.group(2).equals("%") ? (Float.parseFloat(tyM.group(1)) / 100.0f) * box.h : Float.parseFloat(tyM.group(1));
            Matcher tm = Pattern.compile("translate\\(\\s*(-?[0-9.]+)(px|%)(?:\\s*,\\s*(-?[0-9.]+)(px|%))?\\s*\\)").matcher(ht);
            if (tm.find()) {
                htx = tm.group(2).equals("%") ? (Float.parseFloat(tm.group(1)) / 100.0f) * box.w : Float.parseFloat(tm.group(1));
                if (tm.group(3) != null)
                    hty = tm.group(4).equals("%") ? (Float.parseFloat(tm.group(3)) / 100.0f) * box.h : Float.parseFloat(tm.group(3));
            }
            Matcher sm = Pattern.compile("scale\\(\\s*(-?[0-9.]+)(?:\\s*,\\s*(-?[0-9.]+))?\\s*\\)").matcher(ht);
            if (sm.find()) {
                hsx = Float.parseFloat(sm.group(1));
                hsy = sm.group(2) != null ? Float.parseFloat(sm.group(2)) : hsx;
            }
            Matcher rm = Pattern.compile("rotate\\(\\s*(-?[0-9.]+)(deg|rad)?\\s*\\)").matcher(ht);
            if (rm.find()) {
                float val = Float.parseFloat(rm.group(1));
                if ("rad".equals(rm.group(2))) val = (float) Math.toDegrees(val);
                hrot = val;
            }
        }
        if (box.style.containsKey("hover-color")) {
            hc = CssParser.parseColor(box.style.get("hover-color"));
            hasHover = true;
        }
        if (box.style.containsKey("hover-background") || box.style.containsKey("hover-background-color")) {
            hbgc = CssParser.parseColor(box.style.getOrDefault("hover-background", box.style.get("hover-background-color")));
            hasHover = true;
        }

        String hoverStr = "";
        if (hasHover)
            hoverStr = "@@HOVER@@" + hop + ":" + htx + ":" + hty + ":" + hsx + ":" + hsy + ":" + hrot + ":" + hc + ":" + hbgc;
        String animDef = parseAnimationDef(box.style.get("animation"), box.w, box.h) + hoverStr;

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
        if (box.node.tag.equals("button") && !partsEvents.containsKey("onclick")) partsEvents.put("onclick", id);

        String baseType = "rect";

        if (!box.inlineFragments.isEmpty() && !box.node.tag.equals("#text") && !box.node.tag.equals("img") && !box.node.tag.equals("input")) {
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
                if (!box.inlineFragments.isEmpty()) {
                    for (LayoutBox.InlineFragment frag : box.inlineFragments) {
                        if (frag.text != null && !frag.text.trim().isEmpty()) {
                            int fontH = (int) (16 * box.fontSize);
                            Map<String, String> fEvents = new HashMap<>(partsEvents);
                            if (box.style.containsKey("font-weight"))
                                fEvents.put("font-weight", box.style.get("font-weight"));
                            addElement(elements, id + "_txt_" + (fragIdCounter++), "text", frag.x, frag.y, frag.w, fontH, frag.text, color, bgColor, fEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, "");
                        }
                    }
                } else if (box.textLines != null && !box.textLines.isEmpty()) {
                    String align = box.style.getOrDefault("text-align", "left");
                    Map<String, String> textEvents = new HashMap<>(partsEvents);
                    textEvents.put("text-align", align);
                    if (box.style.containsKey("font-weight"))
                        textEvents.put("font-weight", box.style.get("font-weight"));

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