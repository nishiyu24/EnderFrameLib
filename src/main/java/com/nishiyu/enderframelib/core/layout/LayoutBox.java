package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.common.ServerImageRegistry;
import com.nishiyu.enderframelib.core.css.CssParser;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.text.TextMetrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class LayoutBox {
    public HtmlNode node;
    public Map<String, String> style;
    public List<LayoutBox> children = new ArrayList<>();
    public int x, y, w, h;
    public String displayText = "";

    public int mT, mR, mB, mL, pT, pR, pB, pL, bT, bR, bB, bL, gap;
    public boolean isAbsolute = false, isFixed = false, isRelative = false;
    public int zIndex = 0;
    public float fontSize = 1.0f;
    public List<String> textLines = null;

    public List<InlineFragment> inlineFragments = new ArrayList<>();
    public Object engineState;

    public LayoutBox(HtmlNode node, Map<String, String> style, Map<String, String> env) {
        this.node = node;
        this.style = style;

        String t = node.text;
        if (t != null) {
            t = t.replaceAll("[\\r\\n\\t]+", " ").replaceAll(" +", " ");
        }
        this.displayText = t != null ? t : "";

        String pos = style.getOrDefault("position", "static");
        this.isFixed = pos.equals("fixed");
        this.isAbsolute = pos.equals("absolute") || this.isFixed;
        this.isRelative = pos.equals("relative");
        try {
            if (style.containsKey("z-index")) this.zIndex = Integer.parseInt(style.get("z-index"));
        } catch (Exception ignored) {}
    }

    public int horizontalDecoration() { return pL + pR + bL + bR; }
    public int verticalDecoration() { return pT + pB + bT + bB; }

    public List<LayoutBox> getFlowChildren() {
        List<LayoutBox> list = new ArrayList<>();
        for (LayoutBox c : children) {
            if (!c.isAbsolute && !c.style.getOrDefault("display", "block").equals("none")) {
                list.add(c);
            }
        }
        return list;
    }

    public int getMinContentWidth() {
        if (style.containsKey("min-width")) return CssParser.parseDim(style.get("min-width"), 9999, 1920, 1080);

        // ★ Web標準の置換要素（input/button/select 等）の最小幅(Intrinsic Width)を保証し、潰れを防ぐ
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
            String text = this.displayText;
            boolean bold = style.getOrDefault("font-weight", "").matches("(?i)bold|bolder|700|800|900");
            String[] words = text.split("(?<=\\s)|(?=\\s)|(?<=[、。！？.,])");
            for (String word : words) {
                if (!word.trim().isEmpty()) {
                    maxWordW = Math.max(maxWordW, TextMetrics.getDisplayWidth(bold ? ("§l" + word) : word, this.fontSize));
                }
            }
            return maxWordW;
        } else if (children.isEmpty()) {
            return this.w > 0 ? this.w : 16;
        } else {
            int max = 0;
            for (LayoutBox c : getFlowChildren()) {
                max = Math.max(max, c.getMinContentWidth() + c.mL + c.mR);
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

    public void computeSize(int parentW, int parentH, boolean isParentFlexRow, boolean isParentFlexColumn, String parentAlignItems, boolean isParentShrink, int rootW, int rootH) {
        if (style.getOrDefault("display", "block").equals("none")) {
            this.w = 0;
            this.h = 0;
            return;
        }
        if (this.isFixed) {
            parentW = rootW;
            parentH = rootH;
        }

        if (style.containsKey("flex")) {
            String flexStr = style.get("flex").trim();
            if (flexStr.equals("auto")) {
                style.putIfAbsent("flex-grow", "1");
                style.putIfAbsent("flex-shrink", "1");
                style.putIfAbsent("flex-basis", "auto");
            } else if (flexStr.equals("none")) {
                style.putIfAbsent("flex-grow", "0");
                style.putIfAbsent("flex-shrink", "0");
                style.putIfAbsent("flex-basis", "auto");
            } else if (flexStr.matches("^[\\d.]+$")) {
                style.putIfAbsent("flex-grow", flexStr);
                style.putIfAbsent("flex-shrink", "1");
                style.putIfAbsent("flex-basis", "auto");
            } else {
                String[] parts = flexStr.split("\\s+");
                if (parts.length > 0) style.putIfAbsent("flex-grow", parts[0]);
                if (parts.length > 1 && parts[1].matches("^[\\d.]+$")) style.putIfAbsent("flex-shrink", parts[1]);
                if (parts.length > 2) style.putIfAbsent("flex-basis", parts[2]);
                else if (parts.length == 2 && !parts[1].matches("^[\\d.]+$")) style.putIfAbsent("flex-basis", parts[1]);
            }
        }

        String fs = style.getOrDefault("font-size", "1rem");
        int fsPx = CssParser.parseDim(fs, parentW, rootW, rootH);
        this.fontSize = (fsPx > 0) ? (fsPx / 16.0f) : 1.0f;

        String[] mStr = CssParser.parseBoxEdgesStr(style.get("margin"));
        this.mT = CssParser.parseDim(style.getOrDefault("margin-top", mStr[0]), parentH, rootW, rootH);
        this.mB = CssParser.parseDim(style.getOrDefault("margin-bottom", mStr[2]), parentH, rootW, rootH);
        String mLStr = style.getOrDefault("margin-left", mStr[3]), mRStr = style.getOrDefault("margin-right", mStr[1]);
        boolean autoLeft = mLStr.equals("auto"), autoRight = mRStr.equals("auto");
        this.mL = autoLeft ? 0 : CssParser.parseDim(mLStr, parentW, rootW, rootH);
        this.mR = autoRight ? 0 : CssParser.parseDim(mRStr, parentW, rootW, rootH);

        String[] pStr = CssParser.parseBoxEdgesStr(style.get("padding"));
        this.pT = CssParser.parseDim(style.getOrDefault("padding-top", pStr[0]), parentH, rootW, rootH);
        this.pR = CssParser.parseDim(style.getOrDefault("padding-right", pStr[1]), parentW, rootW, rootH);
        this.pB = CssParser.parseDim(style.getOrDefault("padding-bottom", pStr[2]), parentH, rootW, rootH);
        this.pL = CssParser.parseDim(style.getOrDefault("padding-left", pStr[3]), parentW, rootW, rootH);

        this.bT = CssParser.parseDim(style.getOrDefault("border-top-width", "0"), parentH, rootW, rootH);
        this.bR = CssParser.parseDim(style.getOrDefault("border-right-width", "0"), parentW, rootW, rootH);
        this.bB = CssParser.parseDim(style.getOrDefault("border-bottom-width", "0"), parentH, rootW, rootH);
        this.bL = CssParser.parseDim(style.getOrDefault("border-left-width", "0"), parentW, rootW, rootH);

        this.gap = CssParser.parseDim(style.getOrDefault("gap", "0"), parentW, rootW, rootH);
        boolean isBorderBox = !style.getOrDefault("box-sizing", "border-box").equals("content-box");

        String widthStr = style.getOrDefault("width", "").trim();
        boolean hasWidth = style.containsKey("width") && !widthStr.equals("auto");
        boolean isPercentWidthAndShrinking = hasWidth && widthStr.contains("%") && isParentShrink;
        boolean isFlexBasisZero = style.getOrDefault("flex-basis", "auto").equals("0") || style.getOrDefault("flex-basis", "auto").equals("0%") || style.getOrDefault("flex-basis", "auto").equals("0px");
        String display = style.getOrDefault("display", "block");

        if (hasWidth && !isPercentWidthAndShrinking) {
            this.w = CssParser.parseDim(widthStr, parentW, rootW, rootH);
            if (!isBorderBox) this.w += horizontalDecoration();
        } else if (node.tag.equals("#text")) {
            int limitW = parentW;
            if (style.containsKey("max-width")) {
                int mw = CssParser.parseDim(style.get("max-width"), parentW, rootW, rootH);
                if (mw > 0) limitW = Math.min(limitW, mw);
            }
            if (limitW <= 0) limitW = 9999;
            boolean isBold = style.getOrDefault("font-weight", "").matches("(?i)bold|bolder|700|800|900");
            List<String> rawLines = TextMetrics.wrapText(this.displayText, limitW, this.fontSize);
            this.textLines = new ArrayList<>(rawLines.size());
            int maxLineW = 0;
            for (String s : rawLines) {
                String formatted = isBold ? ("§l" + s) : s;
                this.textLines.add(formatted);
                maxLineW = Math.max(maxLineW, TextMetrics.getDisplayWidth(formatted, this.fontSize));
            }
            this.w = maxLineW + horizontalDecoration();
        } else if (node.tag.equals("mc-scene")) {
            this.w = Math.max(64, parentW - mL - mR);
        } else if (node.tag.equals("img") || node.tag.equals("mc-fluid") || node.tag.equals("mc-entity")) {
            String src = node.attrs.getOrDefault("src", "");
            if (src.startsWith("http://") || src.startsWith("https://")) {
                int[] imgSize = ServerImageRegistry.getSize(src);
                this.w = (imgSize != null && imgSize[0] > 0) ? imgSize[0] + horizontalDecoration() : 32 + horizontalDecoration();
            } else {
                this.w = 16 + horizontalDecoration();
            }
        } else if (node.tag.equals("input") || node.tag.equals("select") || node.tag.equals("progress")) {
            // ここで getMinContentWidth() と連携して初期幅を決定
            this.w = getMinContentWidth();
        } else if (isAbsolute || isParentFlexRow || isParentShrink || display.equals("inline-block") || display.equals("inline")) {
            if (isParentFlexRow && isFlexBasisZero && !hasWidth) this.w = horizontalDecoration();
            else this.w = -1;
        } else if (isParentFlexColumn && !parentAlignItems.equals("stretch")) {
            this.w = -1;
        } else {
            this.w = Math.max(0, parentW - mL - mR);
        }

        if (this.w != -1 && style.containsKey("max-width")) {
            String maxWStr = style.get("max-width");
            if (!(maxWStr.contains("%") && isParentShrink)) {
                int maxW = CssParser.parseDim(maxWStr, parentW, rootW, rootH);
                if (!isBorderBox) maxW += horizontalDecoration();
                this.w = Math.min(this.w, maxW);
            }
        }
        if (this.w != -1 && style.containsKey("min-width")) {
            int minW = CssParser.parseDim(style.get("min-width"), parentW, rootW, rootH);
            if (!isBorderBox) minW += horizontalDecoration();
            this.w = Math.max(this.w, minW);
        }

        int tentativeInnerW = this.w == -1 ? Math.max(0, parentW - mL - mR - horizontalDecoration()) : Math.max(0, this.w - horizontalDecoration());

        LayoutEngine engine = LayoutEngineFactory.getEngine(display);
        engine.computeSize(this, tentativeInnerW, parentH, isParentFlexRow, isParentFlexColumn, parentAlignItems, isParentShrink, rootW, rootH);

        // ComputeSize完了後に再度Min/Max制約を適用
        if (style.containsKey("max-width")) {
            String maxWStr = style.get("max-width");
            if (!(maxWStr.contains("%") && isParentShrink)) {
                int maxW = CssParser.parseDim(maxWStr, parentW, rootW, rootH);
                if (!isBorderBox) maxW += horizontalDecoration();
                this.w = Math.min(this.w, maxW);
            }
        }
        if (style.containsKey("min-width")) {
            int minW = CssParser.parseDim(style.get("min-width"), parentW, rootW, rootH);
            if (!isBorderBox) minW += horizontalDecoration();
            this.w = Math.max(this.w, minW);
        }

        if (!isAbsolute) {
            if (autoLeft && autoRight) {
                this.mL = Math.max(0, (parentW - this.w) / 2);
                this.mR = Math.max(0, parentW - this.w - this.mL);
            } else if (autoLeft) this.mL = Math.max(0, parentW - this.w - this.mR);
            else if (autoRight) this.mR = Math.max(0, parentW - this.w - this.mL);
        }

        float lh = 1.375f;
        if (style.containsKey("line-height")) {
            try { lh = Float.parseFloat(style.get("line-height")); }
            catch (Exception e) {
                if (style.get("line-height").endsWith("px")) lh = Float.parseFloat(style.get("line-height").replace("px", "")) / 16.0f;
            }
        }

        String heightStr = style.getOrDefault("height", "").trim();
        if (style.containsKey("height") && !heightStr.equals("auto")) {
            this.h = CssParser.parseDim(heightStr, parentH, rootW, rootH);
            if (!isBorderBox) this.h += verticalDecoration();
        } else if (style.containsKey("aspect-ratio") && this.w > 0) {
            try {
                int innerW = Math.max(0, this.w - horizontalDecoration());
                if (style.get("aspect-ratio").contains("/")) {
                    String[] parts = style.get("aspect-ratio").split("/");
                    float rw = Float.parseFloat(parts[0].trim()), rh = Float.parseFloat(parts[1].trim());
                    if (rw > 0) this.h = (int) (innerW * (rh / rw)) + verticalDecoration();
                } else {
                    float ratio = Float.parseFloat(style.get("aspect-ratio"));
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
                int[] imgSize = ServerImageRegistry.getSize(src);
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

        engine.computeHeightAndFinalizeChildren(this, parentH, isParentFlexRow, isParentFlexColumn, rootW, rootH);

        if (style.containsKey("max-height")) {
            String maxHStr = style.get("max-height").trim();
            if (!maxHStr.equals("none") && !maxHStr.isEmpty()) {
                int maxH = CssParser.parseDim(maxHStr, parentH, rootW, rootH);
                if (maxH > 0) {
                    if (!isBorderBox) maxH += verticalDecoration();
                    this.h = Math.min(this.h, maxH);
                }
            }
        }
        if (style.containsKey("min-height")) {
            int minH = CssParser.parseDim(style.get("min-height"), parentH, rootW, rootH);
            if (!isBorderBox) minH += horizontalDecoration();
            this.h = Math.max(this.h, minH);
        }
    }

    public void layout(int parentX, int parentY, int parentOuterW, int parentOuterH, int rootW, int rootH) {
        if (style.getOrDefault("display", "block").equals("none")) return;
        int refX = this.isFixed ? 0 : parentX;
        int refY = this.isFixed ? 0 : parentY;
        int refW = this.isFixed ? rootW : parentOuterW;
        int refH = this.isFixed ? rootH : parentOuterH;

        if (this.isAbsolute) {
            if (style.containsKey("right"))
                this.x = refX + refW - this.w - CssParser.parseDim(style.get("right"), refW, rootW, rootH) - mR;
            else this.x = refX + CssParser.parseDim(style.getOrDefault("left", "0"), refW, rootW, rootH) + mL;
            if (style.containsKey("bottom"))
                this.y = refY + refH - this.h - CssParser.parseDim(style.get("bottom"), refH, rootW, rootH) - mB;
            else this.y = refY + CssParser.parseDim(style.getOrDefault("top", "0"), refH, rootW, rootH) + mT;
        } else {
            this.x = refX + mL;
            this.y = refY + mT;
        }

        int scrollX = 0, scrollY = 0;
        try {
            if (node.attrs.containsKey("data-scroll-x")) scrollX = (int) Float.parseFloat(node.attrs.get("data-scroll-x"));
            if (node.attrs.containsKey("data-scroll-y")) scrollY = (int) Float.parseFloat(node.attrs.get("data-scroll-y"));
        } catch (Exception ignored) {}

        int innerX = this.x + pL + bL - scrollX;
        int innerY = this.y + pT + bT - scrollY;

        LayoutEngine engine = LayoutEngineFactory.getEngine(style.getOrDefault("display", "block"));
        engine.layoutChildren(this, innerX, innerY, rootW, rootH);

        for (LayoutBox child : children) {
            if (child.style.getOrDefault("display", "block").equals("none")) continue;
            if (child.isAbsolute) child.layout(this.x - scrollX, this.y - scrollY, this.w, this.h, rootW, rootH);
        }

        if (this.isRelative) {
            int offX = 0, offY = 0;
            if (style.containsKey("left")) offX += CssParser.parseDim(style.get("left"), parentOuterW, rootW, rootH);
            else if (style.containsKey("right")) offX -= CssParser.parseDim(style.get("right"), parentOuterW, rootW, rootH);
            if (style.containsKey("top")) offY += CssParser.parseDim(style.get("top"), parentOuterH, rootW, rootH);
            else if (style.containsKey("bottom")) offY -= CssParser.parseDim(style.get("bottom"), parentOuterH, rootW, rootH);
            if (offX != 0 || offY != 0) applyRelativeOffset(offX, offY);
        }

        // ★ 子要素のスクロールオフセットを足し戻して、絶対的なコンテンツ終端を計算する
        int maxChildRight = this.x + pL + bL;
        int maxChildBottom = this.y + pT + bT;
        for (LayoutBox child : children) {
            if (child.style.getOrDefault("display", "block").equals("none") || child.isAbsolute) continue;
            maxChildRight = Math.max(maxChildRight, child.x + child.w + child.mR + scrollX);
            maxChildBottom = Math.max(maxChildBottom, child.y + child.h + child.mB + scrollY);
        }
        if (this.engineState instanceof GridLayoutEngine.GridState gs) {
            for (GridLayoutEngine.GridItem item : gs.items) {
                if (item.box.isAbsolute) continue;
                maxChildRight = Math.max(maxChildRight, item.box.x + item.box.w + item.box.mR + scrollX);
                maxChildBottom = Math.max(maxChildBottom, item.box.y + item.box.h + item.box.mB + scrollY);
            }
        }

        // ★ 親要素の高さ(h)が 100% などで制限されていても、内容がはみ出た分だけ scrollHeight が正しく伸びる
        int scrollWidth = Math.max(this.w, maxChildRight - this.x + pR + bR);
        int scrollHeight = Math.max(this.h, maxChildBottom - this.y + pB + bB);
        this.node.attrs.put("data-scroll-w", String.valueOf(scrollWidth));
        this.node.attrs.put("data-scroll-h", String.valueOf(scrollHeight));
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