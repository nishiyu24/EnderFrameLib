package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.kernel.LayoutUnit;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssPropertyIndex;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.text.TextShaper;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontStyle;
import com.nishiyu.enderframelib.core.text.model.TextStyle;

import java.util.List;

/**
 * 内在幅（min-content）・表示用テキストの正規化に関する算出処理（A7・C3）。
 */
final class IntrinsicSizing {
    private IntrinsicSizing() {}

    private static long minContentKey(LayoutContext ctx) {
        long key = ((long) ctx.viewport().rootW() << 32) | (ctx.viewport().rootH() & 0xFFFFFFFFL);
        return key * 31 + Float.floatToIntBits(ctx.viewport().devicePixelRatio());
    }

    static float getMinContentWidth(LayoutBox box, LayoutContext ctx) {
        MeasureMemo mm = box.measureMemo;
        if (mm.isTentativeStashed() && mm.isMinContentVar()) {
            mm.settleTentative(box);
        }
        long key = minContentKey(ctx);
        float cached = mm.cachedMinContentWidth(key);
        if (cached >= 0) return cached;

        float result = computeMinContentWidth(box, ctx);
        mm.cacheMinContentWidth(key, result);
        return result;
    }

    /** 親（または祖先）の測定中に子の min-content 幅を読み、子および寄与する子孫へ読み取り印と値を記録する（C3 ②）。 */
    static float readChildMinContentWidth(LayoutBox child, LayoutContext ctx) {
        if (MeasureReads.currentMeasuringBox() == null) return getMinContentWidth(child, ctx);
        MeasureMemo mm = child.measureMemo;
        boolean hadRead = mm.hasParentReadMinContent();
        if (mm.isTentativeStashed() && mm.isMinContentVar()) {
            mm.settleTentative(child);
        }
        long key = minContentKey(ctx);
        float w = mm.cachedMinContentWidth(key);
        if (w < 0) {
            w = computeMinContentWidth(child, ctx);
            mm.cacheMinContentWidth(key, w);
        } else if (!hadRead && (!child.children.isEmpty() || child.renderedLegend() != null)) {
            propagateCachedMinContentReads(child, ctx);
        }
        mm.noteParentReadMinContent(w);
        return w;
    }

    private static void propagateCachedMinContentReads(LayoutBox box, LayoutContext ctx) {
        if (box.effectiveContainsInlineSize()) return;
        ComputedStyle ax = box.axisStyle();
        String minWidthDecl = ax.get(CssPropertyIndex.MIN_WIDTH);
        if (minWidthDecl != null && !LayoutMetrics.isIntrinsicSizeKeyword(minWidthDecl)
                && box.display.inner() != LayoutBox.Display.Inner.TABLE) {
            return;
        }
        if (!Float.isNaN(ElementSizing.minContentWidth(box, ctx))
                || box.node.nodeType == NodeType.TEXT || box.children.isEmpty()) {
            return;
        }
        for (int i = 0, n = box.children.size(); i < n; i++) {
            LayoutBox c = box.children.get(i);
            if (!c.participatesInFlow()) continue;
            minContentContribution(c, ctx);
        }
        LayoutBox legend = box.renderedLegend();
        if (legend != null) minContentContribution(legend, ctx);
    }

    private static float computeMinContentWidth(LayoutBox box, LayoutContext ctx) {
        if (box.effectiveContainsInlineSize()) {
            return inlineDecoration(box, ctx) + box.containIntrinsicInlinePx(ctx.viewport().rootW(), ctx.viewport().rootH());
        }
        float rootW = ctx.viewport().rootW();
        float rootH = ctx.viewport().rootH();

        ComputedStyle ax = box.axisStyle();
        String minWidthDecl = ax.get(CssPropertyIndex.MIN_WIDTH);
        if (minWidthDecl != null && !LayoutMetrics.isIntrinsicSizeKeyword(minWidthDecl)
                && box.display.inner() != LayoutBox.Display.Inner.TABLE) {
            float min = ax.length(CssPropertyIndex.MIN_WIDTH, LayoutMetrics.UNBOUNDED_WIDTH, rootW, rootH);
            return ax.isBorderBox() ? Math.max(min, inlineDecoration(box, ctx)) : min + inlineDecoration(box, ctx);
        }

        float control = ElementSizing.minContentWidth(box, ctx);
        if (!Float.isNaN(control)) return control;

        if (box.node.nodeType == NodeType.TEXT) {
            TextStyle textStyle = ctx.textStyleFor(box.style);
            FontFace face = ctx.faceFor(box.style);
            FontStyle font = textStyle.font();
            float maxWordW = 0;
            if (!textStyle.whiteSpace().wraps()) {
                if (box.displayText.indexOf('\n') < 0) {
                    String l = textStyle.whiteSpace().collapses() ? box.displayText.strip() : box.displayText;
                    return l.isEmpty() ? 0 : face.advance(l, font);
                }
                for (String line : box.displayText.split("\n", -1)) {
                    String l = textStyle.whiteSpace().collapses() ? line.strip() : line;
                    if (!l.isEmpty()) maxWordW = Math.max(maxWordW, face.advance(l, font));
                }
                return maxWordW;
            }
            return TextShaper.segmented(box.displayText, face, font, textStyle.wordBreak()).maxStrippedW();
        }

        if (box.children.isEmpty()) {
            if (!ReplacedElements.isReplacedTag(box.node.tag) && !com.nishiyu.enderframelib.core.dom.node.ElementTraits.isBuiltinReplacedMedia(box.node.tag)) {
                return inlineDecoration(box, ctx);
            }
            float image = BoxMeasurer.replacedImageWidth(box, ctx);
            if (!Float.isNaN(image)) return image;
            return box.w > 0 ? box.w : LayoutMetrics.FALLBACK_REPLACED_SIZE;
        }

        boolean sumItems = box.display != null && box.display.inner() == LayoutBox.Display.Inner.FLEX && isSingleLineRowFlex(box);
        float max = 0, sum = 0;
        int count = 0;
        for (LayoutBox c : box.children) {
            if (!c.participatesInFlow()) continue;
            if (sumItems && FlexLayoutEngine.isCollapsed(c)) continue;
            float contribution = minContentContribution(c, ctx) + c.intrinsicInlineMargins(ctx);
            max = Math.max(max, contribution);
            sum += contribution;
            count++;
        }
        LayoutBox legend = box.renderedLegend();
        float legendW = legend == null ? 0 : minContentContribution(legend, ctx) + ownInlineMargins(legend, ctx);
        if (sumItems) return Math.max(legendW, sum + box.columnGap * Math.max(0, count - 1)) + inlineDecoration(box, ctx);
        return Math.max(legendW, max) + inlineDecoration(box, ctx);
    }

    private static float minContentContribution(LayoutBox box, LayoutContext ctx) {
        ComputedStyle ax = box.axisStyle();
        String width = ax.get(CssPropertyIndex.WIDTH);
        if (width == null || width.contains("%") || LayoutMetrics.isIntrinsicSizeKeyword(width)) {
            return MeasureReads.currentMeasuringBox() != null
                    ? readChildMinContentWidth(box, ctx)
                    : getMinContentWidth(box, ctx);
        }
        if (MeasureReads.currentMeasuringBox() != null) box.measureMemo.noteParentReadOuter();
        float v = ax.length(CssPropertyIndex.WIDTH, 0, ctx.viewport().rootW(), ctx.viewport().rootH());
        float borderBox = ax.isBorderBox() ? Math.max(v, inlineDecoration(box, ctx)) : v + inlineDecoration(box, ctx);
        String minWidth = ax.get(CssPropertyIndex.MIN_WIDTH);
        if (minWidth != null && !minWidth.contains("%") && !LayoutMetrics.isIntrinsicSizeKeyword(minWidth)) {
            float min = ax.length(CssPropertyIndex.MIN_WIDTH, 0, ctx.viewport().rootW(), ctx.viewport().rootH());
            borderBox = Math.max(borderBox, ax.isBorderBox() ? min : min + inlineDecoration(box, ctx));
        }
        return borderBox;
    }

    /**
     * 内在の幅に足す左右の枠と余白。スタイルだけから求める（% は 0。{@link LayoutBox#intrinsicInlineMargins} と同じ扱い）。
     * 前回の測定が置いた {@code pL}・{@code bL} などを読まない: まだ測っていない箱は 0 で、使い回した箱とで内在の幅が食い違うため。
     * 表は重ねた枠線を測定が置くので箱の値を使う。
     */
    static float inlineDecoration(LayoutBox box, LayoutContext ctx) {
        if (box.display.inner() == LayoutBox.Display.Inner.TABLE) return box.horizontalDecoration();
        float rootW = ctx.viewport().rootW(), rootH = ctx.viewport().rootH();
        com.nishiyu.enderframelib.core.css.Style t = box.axisStyle().typed();
        return LayoutUnit.snap(t.boxPx(t.box.paddingLeft, 0, rootW, rootH)) + LayoutUnit.snap(t.boxPx(t.box.paddingRight, 0, rootW, rootH))
                + LayoutUnit.snap(t.boxPx(t.box.borderLeftWidth, 0, rootW, rootH)) + LayoutUnit.snap(t.boxPx(t.box.borderRightWidth, 0, rootW, rootH));
    }

    static float ownInlineMargins(LayoutBox box, LayoutContext ctx) {
        float rootW = ctx.viewport().rootW(), rootH = ctx.viewport().rootH();
        ComputedStyle ax = box.axisStyle();
        float l = "auto".equals(ax.get(CssPropertyIndex.MARGIN_LEFT)) ? 0 : ax.length(CssPropertyIndex.MARGIN_LEFT, 0, rootW, rootH);
        float r = "auto".equals(ax.get(CssPropertyIndex.MARGIN_RIGHT)) ? 0 : ax.length(CssPropertyIndex.MARGIN_RIGHT, 0, rootW, rootH);
        return l + r;
    }

    private static boolean isSingleLineRowFlex(LayoutBox box) {
        String direction = box.style.getOrDefault(CssPropertyIndex.FLEX_DIRECTION, "row").trim();
        String wrap = box.style.getOrDefault(CssPropertyIndex.FLEX_WRAP, "nowrap").trim();
        return direction.startsWith("row") && "nowrap".equals(wrap);
    }

    static String trimNewlineEdges(String s) {
        int n = s.length();
        if (n == 0) return s;
        int start = 0, end = n;
        char first = s.charAt(0), last = s.charAt(n - 1);
        if (s.isBlank()) return " ";
        if (first == '\n' || first == '\r') while (start < end && Character.isWhitespace(s.charAt(start))) start++;
        if (last == '\n' || last == '\r') while (end > start && Character.isWhitespace(s.charAt(end - 1))) end--;
        return start == 0 && end == n ? s : s.substring(start, end);
    }

    static boolean endsWithWordChar(HtmlNode node) {
        for (HtmlNode n = node; n != null && n.parent != null; n = n.parent) {
            List<HtmlNode> sib = n.parent.children;
            for (int i = sib.indexOf(n) - 1; i >= 0; i--) {
                int c = lastChar(sib.get(i));
                if (c >= 0) return Character.isLetterOrDigit(c);
            }
            if (n.parent.nodeType == NodeType.ELEMENT && !"span".equals(n.parent.tag) && !"b".equals(n.parent.tag)
                    && !"i".equals(n.parent.tag) && !com.nishiyu.enderframelib.core.dom.node.ElementTraits.isAnchor(n.parent.tag) && !"em".equals(n.parent.tag)) return false;
        }
        return false;
    }

    private static int lastChar(HtmlNode n) {
        if (n.nodeType == NodeType.TEXT) return n.text == null || n.text.isEmpty() ? -1 : n.text.charAt(n.text.length() - 1);
        for (int i = n.children.size() - 1; i >= 0; i--) {
            int c = lastChar(n.children.get(i));
            if (c >= 0) return c;
        }
        return -1;
    }

    static String stripIgnorable(String s) {
        int n = s.length();
        int i = 0;
        while (i < n && !isIgnorableFormat(s.charAt(i))) i++;
        if (i == n) return s;
        StringBuilder sb = new StringBuilder(n);
        sb.append(s, 0, i);
        for (; i < n; i++) {
            char c = s.charAt(i);
            if (!isIgnorableFormat(c)) sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isIgnorableFormat(char c) {
        if (c < 0x200B) return false;
        return c <= 0x200F || (c >= 0x202A && c <= 0x202E) || (c >= 0x2060 && c <= 0x2064) || (c >= 0x2066 && c <= 0x2069) || c == 0xFEFF;
    }
}
