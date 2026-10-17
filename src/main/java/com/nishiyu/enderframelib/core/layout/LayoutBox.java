package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.dom.HtmlNode;

import java.util.ArrayList;
import java.util.List;

public class LayoutBox {

    // ServerImageRegistryへの依存を切り離すためのフック
    public static IntrinsicSizeResolver intrinsicSizeResolver = null;

    public final HtmlNode node;
    public final ComputedStyle style;
    public final List<LayoutBox> children = new ArrayList<>();
    public int x, y, w, h;
    public String displayText = "";

    public int mT, mR, mB, mL, pT, pR, pB, pL, bT, bR, bB, bL;
    /** row-gap: 行（ブロック方向）の間隔 / column-gap: 列（インライン方向）の間隔 */
    public int rowGap, columnGap;
    public boolean isAbsolute = false, isFixed = false, isRelative = false;
    public int zIndex = 0;
    public float fontSize = 1.0f;
    public List<String> textLines = null;

    public List<InlineFragment> inlineFragments = new ArrayList<>();
    public Object engineState;
    public SizingMode sizingMode = SizingMode.SHRINK_TO_FIT;

    public int scrollWidth = 0;
    public int scrollHeight = 0;

    public LayoutBox(HtmlNode node, ComputedStyle style) {
        this.node = node;
        this.style = style;

        String t = node.text;
        if (t != null) {
            t = t.replaceAll("[\\r\\n\\t]+", " ").replaceAll(" +", " ");
        }
        this.displayText = t != null ? t : "";

        String pos = style.getOrDefault("position", "static");
        this.isFixed = "fixed".equals(pos);
        this.isAbsolute = "absolute".equals(pos) || this.isFixed;
        this.isRelative = "relative".equals(pos);
        try {
            if (style.containsKey("z-index")) this.zIndex = Integer.parseInt(style.get("z-index"));
        } catch (Exception ignored) {}
    }

    public int horizontalDecoration() { return pL + pR + bL + bR; }
    public int verticalDecoration() { return pT + pB + bT + bB; }

    public List<LayoutBox> getFlowChildren() {
        List<LayoutBox> list = new ArrayList<>();
        for (LayoutBox c : children) {
            if (!c.isAbsolute && !"none".equals(c.style.getOrDefault("display", "block"))) {
                list.add(c);
            }
        }
        return list;
    }

    public int getMinContentWidth(LayoutContext ctx) {
        int rootW = ctx.viewport().rootW();
        int rootH = ctx.viewport().rootH();

        if (style.containsKey("min-width")) return style.length("min-width", 9999, rootW, rootH);

        if (node.tag.equals("input") || node.tag.equals("select") || node.tag.equals("button") || node.tag.equals("progress")) {
            int inputW = 90;
            String type = node.attrs.getOrDefault("type", "text");
            if (node.tag.equals("input") && (type.equals("checkbox") || type.equals("radio"))) inputW = 16;
            else if (node.attrs.containsKey("size")) {
                try { inputW = Integer.parseInt(node.attrs.get("size")) * 8; } catch (Exception ignored) {}
            }
            return inputW + horizontalDecoration();
        }

        if (node.tag.equals("#text")) {
            int maxWordW = 0;
            boolean bold = style.getOrDefault("font-weight", "").matches("(?i)bold|bolder|700|800|900");
            String[] words = this.displayText.split("(?<=\\s)|(?=\\s)|(?<=[、。！？.,])");
            for (String word : words) {
                if (!word.trim().isEmpty()) {
                    maxWordW = Math.max(maxWordW, ctx.fontMetrics().getDisplayWidth(word, this.fontSize, bold));
                }
            }
            return maxWordW;
        } else if (children.isEmpty()) {
            return this.w > 0 ? this.w : 16;
        } else {
            int max = 0;
            for (LayoutBox c : getFlowChildren()) {
                max = Math.max(max, c.getMinContentWidth(ctx) + c.mL + c.mR);
            }
            return max + horizontalDecoration();
        }
    }

    public void applyRelativeOffset(int dx, int dy) {
        this.x += dx;
        this.y += dy;
        for (LayoutBox child : children) child.applyRelativeOffset(dx, dy);
        for (InlineFragment frag : inlineFragments) {
            frag.x += dx;
            frag.y += dy;
        }
    }

    public void measure(int tentativeInnerW, int parentH, FormattingContext fmtCtx, LayoutContext ctx) {
        if ("none".equals(style.getOrDefault("display", "block"))) {
            this.w = 0;
            this.h = 0;
            return;
        }

        int parentW = tentativeInnerW;
        int rootW = ctx.viewport().rootW();
        int rootH = ctx.viewport().rootH();

        if (this.isFixed) {
            parentW = rootW;
            parentH = rootH;
        }

        int fsPx = style.length("font-size", parentW, rootW, rootH);
        this.fontSize = (fsPx > 0) ? (fsPx / 16.0f) : 1.0f;

        this.mT = style.length("margin-top", parentH, rootW, rootH);
        this.mR = style.length("margin-right", parentW, rootW, rootH);
        this.mB = style.length("margin-bottom", parentH, rootW, rootH);
        this.mL = style.length("margin-left", parentW, rootW, rootH);
        boolean autoLeft = "auto".equals(style.get("margin-left"));
        boolean autoRight = "auto".equals(style.get("margin-right"));

        this.pT = style.length("padding-top", parentH, rootW, rootH);
        this.pR = style.length("padding-right", parentW, rootW, rootH);
        this.pB = style.length("padding-bottom", parentH, rootW, rootH);
        this.pL = style.length("padding-left", parentW, rootW, rootH);

        this.bT = style.length("border-top-width", parentH, rootW, rootH);
        this.bR = style.length("border-right-width", parentW, rootW, rootH);
        this.bB = style.length("border-bottom-width", parentH, rootW, rootH);
        this.bL = style.length("border-left-width", parentW, rootW, rootH);

        this.rowGap = style.length("row-gap", parentH, rootW, rootH);
        this.columnGap = style.length("column-gap", parentW, rootW, rootH);
        boolean isBorderBox = !"content-box".equals(style.getOrDefault("box-sizing", "border-box"));

        String widthStr = style.getOrDefault("width", "auto").trim();
        boolean hasWidth = !"auto".equals(widthStr);
        boolean isPercentWidthAndShrinking = hasWidth && widthStr.contains("%") && fmtCtx.isShrink();
        String display = style.getOrDefault("display", "block");

        if (hasWidth && !isPercentWidthAndShrinking) {
            this.w = style.length("width", parentW, rootW, rootH);
            if (!isBorderBox) this.w += horizontalDecoration();
            this.sizingMode = SizingMode.DEFINITE;
        } else if (node.tag.equals("#text")) {
            int limitW = parentW;
            if (style.containsKey("max-width")) {
                int mw = style.length("max-width", parentW, rootW, rootH);
                if (mw > 0) limitW = Math.min(limitW, mw);
            }
            if (limitW <= 0) limitW = 9999;
            boolean isBold = style.getOrDefault("font-weight", "").matches("(?i)bold|bolder|700|800|900");

            List<String> rawLines = new ArrayList<>();
            String[] tokens = this.displayText.split("(?<=\\s)|(?=\\s)|(?<=[、。！？.,])");
            StringBuilder currentLine = new StringBuilder();
            int currentW = 0;
            for (String token : tokens) {
                int tokenW = ctx.fontMetrics().getDisplayWidth(token, this.fontSize, isBold);
                if (currentW + tokenW > limitW && !currentLine.isEmpty() && !token.trim().isEmpty()) {
                    rawLines.add(currentLine.toString().trim());
                    currentLine = new StringBuilder();
                    currentW = 0;
                }
                currentLine.append(token);
                currentW += tokenW;
            }
            if (!currentLine.isEmpty()) rawLines.add(currentLine.toString().trim());

            this.textLines = rawLines;
            int maxLineW = 0;
            for (String line : rawLines) {
                maxLineW = Math.max(maxLineW, ctx.fontMetrics().getDisplayWidth(line, this.fontSize, isBold));
            }
            this.w = maxLineW + horizontalDecoration();
            this.sizingMode = SizingMode.DEFINITE;
        } else if (node.tag.equals("mc-scene")) {
            this.w = Math.max(64, parentW - mL - mR);
            this.sizingMode = SizingMode.DEFINITE;
        } else if (node.tag.equals("img") || node.tag.equals("mc-fluid") || node.tag.equals("mc-entity")) {
            String src = node.attrs.getOrDefault("src", "");
            if (src.startsWith("http://") || src.startsWith("https://")) {
                int[] imgSize = intrinsicSizeResolver != null ? intrinsicSizeResolver.getSize(src) : null;
                this.w = (imgSize != null && imgSize[0] > 0) ? imgSize[0] + horizontalDecoration() : 32 + horizontalDecoration();
            } else {
                this.w = 16 + horizontalDecoration();
            }
            this.sizingMode = SizingMode.DEFINITE;
        } else if (node.tag.equals("input") || node.tag.equals("select") || node.tag.equals("progress")) {
            this.w = getMinContentWidth(ctx);
            this.sizingMode = SizingMode.DEFINITE;
        } else if (isAbsolute || fmtCtx.isFlexRow() || fmtCtx.isShrink() || display.equals("inline-block") || display.equals("inline")) {
            this.w = 0;
            this.sizingMode = SizingMode.SHRINK_TO_FIT;
        } else if (fmtCtx.isFlexColumn() && !"stretch".equals(fmtCtx.alignItems())) {
            this.w = 0;
            this.sizingMode = SizingMode.SHRINK_TO_FIT;
        } else {
            this.w = Math.max(0, parentW - mL - mR);
            this.sizingMode = SizingMode.DEFINITE;
        }

        applyMinMaxWidths(parentW, rootW, rootH, isPercentWidthAndShrinking, isBorderBox);

        int engineInnerW = this.sizingMode == SizingMode.SHRINK_TO_FIT
                ? Math.max(0, parentW - mL - mR - horizontalDecoration())
                : Math.max(0, this.w - horizontalDecoration());

        LayoutEngine engine = LayoutEngineFactory.getEngine(display);
        engine.measure(this, engineInnerW, parentH, fmtCtx, ctx);

        applyMinMaxWidths(parentW, rootW, rootH, isPercentWidthAndShrinking, isBorderBox);

        if (!isAbsolute) {
            if (autoLeft && autoRight) {
                this.mL = Math.max(0, (parentW - this.w) / 2);
                this.mR = Math.max(0, parentW - this.w - this.mL);
            } else if (autoLeft) this.mL = Math.max(0, parentW - this.w - this.mR);
            else if (autoRight) this.mR = Math.max(0, parentW - this.w - this.mL);
        }

        // ここでresolveFlexibleHeightsを直接呼ぶのではなく、処理自体をインラインで行い、
        // エンジンのresolveFlexibleHeightsを呼び出す
        resolveHeights(parentH, ctx, isBorderBox, engine, fmtCtx);
    }

    private void applyMinMaxWidths(int parentW, int rootW, int rootH, boolean isPercentWidthAndShrinking, boolean isBorderBox) {
        if (this.sizingMode == SizingMode.DEFINITE && style.containsKey("max-width")) {
            String maxWStr = style.get("max-width");
            if (!(maxWStr.contains("%") && isPercentWidthAndShrinking)) {
                int maxW = style.length("max-width", parentW, rootW, rootH);
                if (!isBorderBox) maxW += horizontalDecoration();
                this.w = Math.min(this.w, maxW);
            }
        }
        if (this.sizingMode == SizingMode.DEFINITE && style.containsKey("min-width")) {
            int minW = style.length("min-width", parentW, rootW, rootH);
            if (!isBorderBox) minW += horizontalDecoration();
            this.w = Math.max(this.w, minW);
        }
    }

    // ★ 追加：エンジンから呼び出せるように公開
    public void resolveFlexibleHeights(int parentH, FormattingContext fmtCtx, LayoutContext ctx) {
        boolean isBorderBox = !"content-box".equals(style.getOrDefault("box-sizing", "border-box"));
        LayoutEngine engine = LayoutEngineFactory.getEngine(style.getOrDefault("display", "block"));
        resolveHeights(parentH, ctx, isBorderBox, engine, fmtCtx);
    }

    private void resolveHeights(int parentH, LayoutContext ctx, boolean isBorderBox, LayoutEngine engine, FormattingContext fmtCtx) {
        int rootW = ctx.viewport().rootW();
        int rootH = ctx.viewport().rootH();

        float lh = 1.375f;
        String lhStr = style.get("line-height");
        if (lhStr != null) {
            try { lh = lhStr.endsWith("px") ? Float.parseFloat(lhStr.replace("px", "")) / 16.0f : Float.parseFloat(lhStr); }
            catch (Exception ignored) {}
        }

        String heightStr = style.getOrDefault("height", "auto").trim();
        if (style.containsKey("height") && !"auto".equals(heightStr)) {
            this.h = style.length("height", parentH, rootW, rootH);
            if (!isBorderBox) this.h += verticalDecoration();
        } else if (style.containsKey("aspect-ratio") && this.w > 0) {
            try {
                int innerW = Math.max(0, this.w - horizontalDecoration());
                String ratioStr = style.get("aspect-ratio");
                if (ratioStr.contains("/")) {
                    String[] parts = ratioStr.split("/");
                    float rw = Float.parseFloat(parts[0].trim()), rh = Float.parseFloat(parts[1].trim());
                    if (rw > 0) this.h = (int) (innerW * (rh / rw)) + verticalDecoration();
                } else {
                    float ratio = Float.parseFloat(ratioStr);
                    if (ratio > 0) this.h = (int) (innerW / ratio) + verticalDecoration();
                }
            } catch (Exception ignored) { this.h = this.w; }
        } else if (node.tag.equals("#text")) {
            int lineCount = this.textLines != null && !this.textLines.isEmpty() ? this.textLines.size() : 1;
            this.h = (Math.max(0, lineCount - 1) * (int) (16 * this.fontSize * lh)) + (int) (16 * this.fontSize) + verticalDecoration();
        } else if (node.tag.equals("mc-scene")) {
            this.h = 120 + verticalDecoration();
        } else if (node.tag.equals("img") || node.tag.equals("mc-fluid") || node.tag.equals("mc-entity")) {
            String src = node.attrs.getOrDefault("src", "");
            if (src.startsWith("http://") || src.startsWith("https://")) {
                int[] imgSize = intrinsicSizeResolver != null ? intrinsicSizeResolver.getSize(src) : null;
                if (imgSize != null && imgSize[0] > 0 && imgSize[1] > 0) {
                    if (this.w > 0 && (!style.containsKey("height") || heightStr.equals("auto")))
                        this.h = (int) (Math.max(0, this.w - horizontalDecoration()) * ((float) imgSize[1] / imgSize[0])) + verticalDecoration();
                    else this.h = imgSize[1] + verticalDecoration();
                } else this.h = 32 + verticalDecoration();
            } else {
                this.h = 16 + verticalDecoration();
            }
        } else if (node.tag.equals("input") || node.tag.equals("select")) {
            this.h = 16 + verticalDecoration();
            if (node.tag.equals("select") || (!node.attrs.getOrDefault("type", "text").equals("checkbox") && !node.attrs.getOrDefault("type", "text").equals("radio") && !node.attrs.getOrDefault("type", "text").equals("range"))) {
                this.h = (int) (16 * this.fontSize) + 8 + verticalDecoration();
            }
        } else if (node.tag.equals("progress")) {
            this.h = 12 + verticalDecoration();
        }

        engine.resolveFlexibleHeights(this, parentH, fmtCtx, ctx);

        if (style.containsKey("max-height")) {
            String maxHStr = style.get("max-height").trim();
            if (!"none".equals(maxHStr) && !maxHStr.isEmpty()) {
                int maxH = style.length("max-height", parentH, rootW, rootH);
                if (maxH > 0) {
                    if (!isBorderBox) maxH += verticalDecoration();
                    this.h = Math.min(this.h, maxH);
                }
            }
        }
        if (style.containsKey("min-height")) {
            int minH = style.length("min-height", parentH, rootW, rootH);
            if (!isBorderBox) minH += verticalDecoration();
            this.h = Math.max(this.h, minH);
        }
    }

    public void layout(int parentX, int parentY, int parentOuterW, int parentOuterH, LayoutContext ctx) {
        if ("none".equals(style.getOrDefault("display", "block"))) return;

        int rootW = ctx.viewport().rootW();
        int rootH = ctx.viewport().rootH();

        int refX = this.isFixed ? 0 : parentX;
        int refY = this.isFixed ? 0 : parentY;
        int refW = this.isFixed ? rootW : parentOuterW;
        int refH = this.isFixed ? rootH : parentOuterH;

        if (this.isAbsolute) {
            if (style.containsKey("right"))
                this.x = refX + refW - this.w - style.length("right", refW, rootW, rootH) - mR;
            else this.x = refX + style.length("left", refW, rootW, rootH) + mL;
            if (style.containsKey("bottom"))
                this.y = refY + refH - this.h - style.length("bottom", refH, rootW, rootH) - mB;
            else this.y = refY + style.length("top", refH, rootW, rootH) + mT;
        } else {
            this.x = refX + mL;
            this.y = refY + mT;
        }

        int scrollX = ctx.scrollState().getScrollX(node.getId());
        int scrollY = ctx.scrollState().getScrollY(node.getId());

        int innerX = this.x + pL + bL - scrollX;
        int innerY = this.y + pT + bT - scrollY;

        LayoutEngine engine = LayoutEngineFactory.getEngine(style.getOrDefault("display", "block"));
        engine.arrangeChildren(this, innerX, innerY, ctx);

        for (LayoutBox child : children) {
            if ("none".equals(child.style.getOrDefault("display", "block"))) continue;
            if (child.isAbsolute) child.layout(this.x - scrollX, this.y - scrollY, this.w, this.h, ctx);
        }

        if (this.isRelative) {
            int offX = 0, offY = 0;
            if (style.containsKey("left")) offX += style.length("left", parentOuterW, rootW, rootH);
            else if (style.containsKey("right")) offX -= style.length("right", parentOuterW, rootW, rootH);
            if (style.containsKey("top")) offY += style.length("top", parentOuterH, rootW, rootH);
            else if (style.containsKey("bottom")) offY -= style.length("bottom", parentOuterH, rootW, rootH);
            if (offX != 0 || offY != 0) applyRelativeOffset(offX, offY);
        }

        int maxChildRight = this.x + pL + bL;
        int maxChildBottom = this.y + pT + bT;
        for (LayoutBox child : children) {
            if ("none".equals(child.style.getOrDefault("display", "block")) || child.isAbsolute) continue;
            maxChildRight = Math.max(maxChildRight, child.x + child.w + child.mR + scrollX);
            maxChildBottom = Math.max(maxChildBottom, child.y + child.h + child.mB + scrollY);
        }
        if (this.engineState instanceof GridState gs) {
            for (GridItem item : gs.items) {
                if (item.box.isAbsolute) continue;
                maxChildRight = Math.max(maxChildRight, item.box.x + item.box.w + item.box.mR + scrollX);
                maxChildBottom = Math.max(maxChildBottom, item.box.y + item.box.h + item.box.mB + scrollY);
            }
        }

        this.scrollWidth = Math.max(this.w, maxChildRight - this.x + pR + bR);
        this.scrollHeight = Math.max(this.h, maxChildBottom - this.y + pB + bB);
    }

    public static class InlineFragment {
        public LayoutBox box;
        public int x, y, w, h;
        public String text;
        public List<LayoutBox> parents;

        public InlineFragment(LayoutBox box, int x, int y, int w, int h, String text) {
            this.box = box;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.text = text;
        }
        public boolean isText() { return text != null; }
    }
}