package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.dom.NodeType;

import java.util.List;

/**
 * ボックスモデル（マージン・パディング・ボーダー・幅・高さ）の決定。
 *
 * 以前はこの約 220 行が {@code LayoutBox} の中にあり、レイアウトのアルゴリズムが
 * 「結果の入れ物」と同じクラスに同居していた（エンジン側と二重にロジックがある状態）。
 * {@code LayoutBox} はジオメトリ結果の保持に専念し、寸法の決定はここが受け持つ。
 */
public final class BoxMeasurer {

    private BoxMeasurer() {}

    /**
     * ボックスの幅を決め、display に対応するエンジンに内容を測らせ、最後に高さを決める。
     *
     * @param tentativeInnerW 親が与える暫定の内容幅
     * @param parentH         親の内容高（パーセント指定の基準）
     */
    public static void measure(LayoutBox box, int tentativeInnerW, int parentH,
                               FormattingContext fmtCtx, LayoutContext ctx) {
        ComputedStyle style = box.style;
        box.invalidateMinContentCache();

        if (isDisplayNone(style)) {
            box.w = 0;
            box.h = 0;
            return;
        }

        int rootW = ctx.viewport().rootW();
        int rootH = ctx.viewport().rootH();

        int parentW = tentativeInnerW;
        if (box.isFixed) {
            parentW = rootW;
            parentH = rootH;
        }

        int fsPx = style.length(CssProperty.FONT_SIZE, parentW, rootW, rootH);
        box.fontSize = (fsPx > 0) ? (fsPx / LayoutMetrics.BASE_FONT_SIZE) : 1.0f;

        resolveBoxEdges(box, parentW, parentH, rootW, rootH);

        boolean autoLeft = "auto".equals(style.get(CssProperty.MARGIN_LEFT));
        boolean autoRight = "auto".equals(style.get(CssProperty.MARGIN_RIGHT));
        boolean isBorderBox = isBorderBox(style);

        String widthStr = style.getOrDefault(CssProperty.WIDTH, "auto").trim();
        boolean hasWidth = !"auto".equals(widthStr);
        boolean isPercentWidthAndShrinking = hasWidth && widthStr.contains("%") && fmtCtx.isShrink();
        String display = style.getOrDefault(CssProperty.DISPLAY, "block");

        resolveWidth(box, ctx, parentW, rootW, rootH, hasWidth, isPercentWidthAndShrinking, isBorderBox,
                display, fmtCtx);

        applyMinMaxWidths(box, parentW, rootW, rootH, isPercentWidthAndShrinking, isBorderBox);

        int engineInnerW = box.sizingMode == SizingMode.SHRINK_TO_FIT
                ? Math.max(0, parentW - box.mL - box.mR - box.horizontalDecoration())
                : Math.max(0, box.w - box.horizontalDecoration());

        LayoutEngine engine = LayoutEngineFactory.getEngine(display);
        engine.measure(box, engineInnerW, parentH, fmtCtx, ctx);

        applyMinMaxWidths(box, parentW, rootW, rootH, isPercentWidthAndShrinking, isBorderBox);

        if (!box.isAbsolute) {
            if (autoLeft && autoRight) {
                box.mL = Math.max(0, (parentW - box.w) / 2);
                box.mR = Math.max(0, parentW - box.w - box.mL);
            } else if (autoLeft) {
                box.mL = Math.max(0, parentW - box.w - box.mR);
            } else if (autoRight) {
                box.mR = Math.max(0, parentW - box.w - box.mL);
            }
        }

        resolveHeights(box, parentH, ctx, isBorderBox, engine, fmtCtx);
    }

    /**
     * 高さだけを再解決する。
     * flex の grow/shrink やグリッドの stretch で幅・高さが変わった子に対して、
     * エンジンから呼ばれる。
     */
    public static void resolveFlexibleHeights(LayoutBox box, int parentH, FormattingContext fmtCtx,
                                              LayoutContext ctx) {
        LayoutEngine engine = LayoutEngineFactory.getEngine(box.style.getOrDefault(CssProperty.DISPLAY, "block"));
        resolveHeights(box, parentH, ctx, isBorderBox(box.style), engine, fmtCtx);
    }

    private static void resolveBoxEdges(LayoutBox box, int parentW, int parentH, int rootW, int rootH) {
        ComputedStyle style = box.style;

        box.mT = style.length(CssProperty.MARGIN_TOP, parentH, rootW, rootH);
        box.mR = style.length(CssProperty.MARGIN_RIGHT, parentW, rootW, rootH);
        box.mB = style.length(CssProperty.MARGIN_BOTTOM, parentH, rootW, rootH);
        box.mL = style.length(CssProperty.MARGIN_LEFT, parentW, rootW, rootH);

        box.pT = style.length(CssProperty.PADDING_TOP, parentH, rootW, rootH);
        box.pR = style.length(CssProperty.PADDING_RIGHT, parentW, rootW, rootH);
        box.pB = style.length(CssProperty.PADDING_BOTTOM, parentH, rootW, rootH);
        box.pL = style.length(CssProperty.PADDING_LEFT, parentW, rootW, rootH);

        box.bT = style.length(CssProperty.BORDER_TOP_WIDTH, parentH, rootW, rootH);
        box.bR = style.length(CssProperty.BORDER_RIGHT_WIDTH, parentW, rootW, rootH);
        box.bB = style.length(CssProperty.BORDER_BOTTOM_WIDTH, parentH, rootW, rootH);
        box.bL = style.length(CssProperty.BORDER_LEFT_WIDTH, parentW, rootW, rootH);

        box.rowGap = style.length(CssProperty.ROW_GAP, parentH, rootW, rootH);
        box.columnGap = style.length(CssProperty.COLUMN_GAP, parentW, rootW, rootH);
    }

    private static void resolveWidth(LayoutBox box, LayoutContext ctx, int parentW, int rootW, int rootH,
                                     boolean hasWidth, boolean isPercentWidthAndShrinking, boolean isBorderBox,
                                     String display, FormattingContext fmtCtx) {
        ComputedStyle style = box.style;
        String tag = box.node.tag;

        if (hasWidth && !isPercentWidthAndShrinking) {
            box.w = style.length(CssProperty.WIDTH, parentW, rootW, rootH);
            if (!isBorderBox) box.w += box.horizontalDecoration();
            box.sizingMode = SizingMode.DEFINITE;
        } else if (box.node.nodeType == NodeType.TEXT) {
            measureTextWidth(box, ctx, parentW, rootW, rootH);
        } else if (tag.equals("mc-scene")) {
            box.w = Math.max(64, parentW - box.mL - box.mR);
            box.sizingMode = SizingMode.DEFINITE;
        } else if (isReplacedImage(tag)) {
            int[] intrinsic = intrinsicSize(box, ctx);
            box.w = (intrinsic != null && intrinsic[0] > 0)
                    ? intrinsic[0] + box.horizontalDecoration()
                    : replacedFallbackWidth(box) + box.horizontalDecoration();
            box.sizingMode = SizingMode.DEFINITE;
        } else if (tag.equals("input") || tag.equals("select") || tag.equals("progress")) {
            box.w = box.getMinContentWidth(ctx);
            box.sizingMode = SizingMode.DEFINITE;
        } else if (box.isAbsolute || fmtCtx.isFlexRow() || fmtCtx.isShrink()
                || display.equals("inline-block") || display.equals("inline")) {
            box.w = 0;
            box.sizingMode = SizingMode.SHRINK_TO_FIT;
        } else if (fmtCtx.isFlexColumn() && !"stretch".equals(fmtCtx.alignItems())) {
            box.w = 0;
            box.sizingMode = SizingMode.SHRINK_TO_FIT;
        } else {
            box.w = Math.max(0, parentW - box.mL - box.mR);
            box.sizingMode = SizingMode.DEFINITE;
        }
    }

    /** テキストノードは折り返した結果の最長行が幅になる。 */
    private static void measureTextWidth(LayoutBox box, LayoutContext ctx, int parentW, int rootW, int rootH) {
        ComputedStyle style = box.style;

        int limitW = parentW;
        if (style.containsKey(CssProperty.MAX_WIDTH)) {
            int mw = style.length(CssProperty.MAX_WIDTH, parentW, rootW, rootH);
            if (mw > 0) limitW = Math.min(limitW, mw);
        }
        if (limitW <= 0) limitW = LayoutMetrics.UNBOUNDED_WIDTH;

        boolean isBold = LayoutMetrics.isBold(style);
        List<String> lines = LayoutMetrics.wrapText(box.displayText, limitW, box.fontSize, isBold, ctx.fontMetrics());
        box.textLines = lines;

        int maxLineW = 0;
        for (String line : lines) {
            maxLineW = Math.max(maxLineW, ctx.fontMetrics().getDisplayWidth(line, box.fontSize, isBold));
        }
        box.w = maxLineW + box.horizontalDecoration();
        box.sizingMode = SizingMode.DEFINITE;
    }

    private static void applyMinMaxWidths(LayoutBox box, int parentW, int rootW, int rootH,
                                          boolean isPercentWidthAndShrinking, boolean isBorderBox) {
        ComputedStyle style = box.style;
        if (box.sizingMode != SizingMode.DEFINITE) return;

        if (style.containsKey(CssProperty.MAX_WIDTH)) {
            String maxWStr = style.get(CssProperty.MAX_WIDTH);
            if (!(maxWStr.contains("%") && isPercentWidthAndShrinking)) {
                int maxW = style.length(CssProperty.MAX_WIDTH, parentW, rootW, rootH);
                if (!isBorderBox) maxW += box.horizontalDecoration();
                box.w = Math.min(box.w, maxW);
            }
        }
        if (style.containsKey(CssProperty.MIN_WIDTH)) {
            int minW = style.length(CssProperty.MIN_WIDTH, parentW, rootW, rootH);
            if (!isBorderBox) minW += box.horizontalDecoration();
            box.w = Math.max(box.w, minW);
        }
    }

    private static void resolveHeights(LayoutBox box, int parentH, LayoutContext ctx, boolean isBorderBox,
                                       LayoutEngine engine, FormattingContext fmtCtx) {
        ComputedStyle style = box.style;
        int rootW = ctx.viewport().rootW();
        int rootH = ctx.viewport().rootH();

        String heightStr = style.getOrDefault(CssProperty.HEIGHT, "auto").trim();
        boolean hasHeight = style.containsKey(CssProperty.HEIGHT) && !"auto".equals(heightStr);

        if (hasHeight) {
            box.h = style.length(CssProperty.HEIGHT, parentH, rootW, rootH);
            if (!isBorderBox) box.h += box.verticalDecoration();
        } else if (style.containsKey(CssProperty.ASPECT_RATIO) && box.w > 0) {
            applyAspectRatio(box);
        } else {
            resolveIntrinsicHeight(box, ctx, heightStr);
        }

        engine.resolveFlexibleHeights(box, parentH, fmtCtx, ctx);

        if (style.containsKey(CssProperty.MAX_HEIGHT)) {
            String maxHStr = style.get(CssProperty.MAX_HEIGHT).trim();
            if (!"none".equals(maxHStr) && !maxHStr.isEmpty()) {
                int maxH = style.length(CssProperty.MAX_HEIGHT, parentH, rootW, rootH);
                if (maxH > 0) {
                    if (!isBorderBox) maxH += box.verticalDecoration();
                    box.h = Math.min(box.h, maxH);
                }
            }
        }
        if (style.containsKey(CssProperty.MIN_HEIGHT)) {
            // B-5 修正済み: ここは縦方向なので verticalDecoration（以前は横を足していた）
            int minH = style.length(CssProperty.MIN_HEIGHT, parentH, rootW, rootH);
            if (!isBorderBox) minH += box.verticalDecoration();
            box.h = Math.max(box.h, minH);
        }
    }

    private static void applyAspectRatio(LayoutBox box) {
        int innerW = Math.max(0, box.w - box.horizontalDecoration());
        String ratioStr = box.style.get(CssProperty.ASPECT_RATIO);
        try {
            if (ratioStr.contains("/")) {
                String[] parts = ratioStr.split("/");
                float rw = Float.parseFloat(parts[0].trim());
                float rh = Float.parseFloat(parts[1].trim());
                if (rw > 0) box.h = (int) (innerW * (rh / rw)) + box.verticalDecoration();
            } else {
                float ratio = Float.parseFloat(ratioStr.trim());
                if (ratio > 0) box.h = (int) (innerW / ratio) + box.verticalDecoration();
            }
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            box.h = box.w;
        }
    }

    /**
     * 置換要素・テキスト・フォームコントロールの固有高さ。
     *
     * 新しい置換要素（カスタム要素）を足す場合はここが拡張点になる。
     * サイズの実体は {@link IntrinsicSizeResolver} が外（クライアント側）から供給する。
     */
    private static void resolveIntrinsicHeight(LayoutBox box, LayoutContext ctx, String heightStr) {
        String tag = box.node.tag;

        if (box.node.nodeType == NodeType.TEXT) {
            float lh = LayoutMetrics.lineHeightFactor(box.style);
            int lineCount = (box.textLines != null && !box.textLines.isEmpty()) ? box.textLines.size() : 1;
            box.h = (Math.max(0, lineCount - 1) * LayoutMetrics.lineHeightPx(box.fontSize, lh))
                    + LayoutMetrics.fontHeightPx(box.fontSize)
                    + box.verticalDecoration();
            return;
        }

        if (tag.equals("mc-scene")) {
            box.h = 120 + box.verticalDecoration();
            return;
        }

        if (isReplacedImage(tag)) {
            int[] intrinsic = intrinsicSize(box, ctx);
            if (intrinsic != null && intrinsic[0] > 0 && intrinsic[1] > 0) {
                boolean heightIsAuto = !box.style.containsKey(CssProperty.HEIGHT) || heightStr.equals("auto");
                if (box.w > 0 && heightIsAuto) {
                    int innerW = Math.max(0, box.w - box.horizontalDecoration());
                    box.h = (int) (innerW * ((float) intrinsic[1] / intrinsic[0])) + box.verticalDecoration();
                } else {
                    box.h = intrinsic[1] + box.verticalDecoration();
                }
            } else {
                box.h = replacedFallbackWidth(box) + box.verticalDecoration();
            }
            return;
        }

        if (tag.equals("input") || tag.equals("select")) {
            box.h = LayoutMetrics.FALLBACK_REPLACED_SIZE + box.verticalDecoration();
            String type = box.node.attrs.getOrDefault("type", "text");
            boolean compact = type.equals("checkbox") || type.equals("radio") || type.equals("range");
            if (tag.equals("select") || !compact) {
                box.h = LayoutMetrics.fontHeightPx(box.fontSize) + 8 + box.verticalDecoration();
            }
            return;
        }

        if (tag.equals("progress")) {
            box.h = 12 + box.verticalDecoration();
        }
    }

    private static boolean isReplacedImage(String tag) {
        return tag.equals("img") || tag.equals("mc-fluid") || tag.equals("mc-entity");
    }

    /** リモート画像だけが外部解決を必要とする。ローカル資産は固定サイズ。 */
    private static int[] intrinsicSize(LayoutBox box, LayoutContext ctx) {
        String src = box.node.attrs.getOrDefault("src", "");
        if (!src.startsWith("http://") && !src.startsWith("https://")) return null;
        IntrinsicSizeResolver resolver = ctx.intrinsicResolver();
        return resolver != null ? resolver.getSize(src) : null;
    }

    /** サイズが分からないときの既定値。リモート画像は読み込み中なので少し大きめにとる。 */
    private static int replacedFallbackWidth(LayoutBox box) {
        String src = box.node.attrs.getOrDefault("src", "");
        boolean remote = src.startsWith("http://") || src.startsWith("https://");
        return remote ? LayoutMetrics.PENDING_IMAGE_SIZE : LayoutMetrics.FALLBACK_REPLACED_SIZE;
    }

    private static boolean isBorderBox(ComputedStyle style) {
        return !"content-box".equals(style.getOrDefault(CssProperty.BOX_SIZING, "border-box"));
    }

    private static boolean isDisplayNone(ComputedStyle style) {
        return "none".equals(style.getOrDefault(CssProperty.DISPLAY, "block"));
    }
}
