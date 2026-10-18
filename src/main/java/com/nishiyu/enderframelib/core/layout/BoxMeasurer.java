package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.LengthResolver;
import com.nishiyu.enderframelib.core.dom.NodeType;
import com.nishiyu.enderframelib.core.layout.LayoutBox.SizingMode;
import com.nishiyu.enderframelib.core.layout.LayoutContext.FormattingContext;
import com.nishiyu.enderframelib.core.layout.LayoutContext.IntrinsicSizeResolver;
import com.nishiyu.enderframelib.core.text.TextShaper;

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
    public static void measure(LayoutBox box, float tentativeInnerW, float parentH,
                               FormattingContext fmtCtx, LayoutContext ctx) {
        ComputedStyle style = box.style;
        box.invalidateMinContentCache();

        if (isDisplayNone(style)) {
            box.w = 0;
            box.h = 0;
            return;
        }

        float rootW = ctx.viewport().rootW();
        float rootH = ctx.viewport().rootH();

        float parentW = tentativeInnerW;
        if (box.isFixed) {
            parentW = rootW;
            parentH = rootH;
        }

        // font-size は算出スタイルが親のフォントサイズを基準に解決済み（ComputedStyle.of）。
        // ここで length() を使って解き直していたため、% が包含ブロックの「幅」基準になっていた。
        box.lineHeightPx = lineHeightOf(box, ctx);

        resolveBoxEdges(box, parentW, parentH, rootW, rootH);

        boolean autoLeft = "auto".equals(style.get(CssProperty.MARGIN_LEFT));
        boolean autoRight = "auto".equals(style.get(CssProperty.MARGIN_RIGHT));
        boolean isBorderBox = isBorderBox(style);

        String widthStr = style.getOrDefault(CssProperty.WIDTH, "auto").trim();
        // min-content / max-content / fit-content は長さではなく内容から決まる（W2 以前は 0 px に解けていた）
        String intrinsicWidth = !"auto".equals(widthStr) && LayoutMetrics.isIntrinsicSizeKeyword(widthStr)
                ? widthStr.toLowerCase() : null;
        boolean hasWidth = !"auto".equals(widthStr) && intrinsicWidth == null;
        boolean isPercentWidthAndShrinking = hasWidth && widthStr.contains("%") && fmtCtx.isShrink();
        String display = style.getOrDefault(CssProperty.DISPLAY, "block");

        resolveWidth(box, ctx, parentW, rootW, rootH, hasWidth, intrinsicWidth != null,
                isPercentWidthAndShrinking, isBorderBox, display, fmtCtx);

        applyMinMaxWidths(box, ctx, parentW, rootW, rootH, isPercentWidthAndShrinking, isBorderBox);

        float engineInnerW;
        if ("max-content".equals(intrinsicWidth)) {
            // 折り返さずに並べたときの幅が max-content
            engineInnerW = LayoutMetrics.UNBOUNDED_WIDTH;
        } else if (box.sizingMode == SizingMode.SHRINK_TO_FIT) {
            engineInnerW = Math.max(0, parentW - box.mL - box.mR - box.horizontalDecoration());
        } else {
            engineInnerW = Math.max(0, box.w - box.horizontalDecoration());
        }

        LayoutEngine engine = LayoutEngine.getEngine(display);
        engine.measure(box, engineInnerW, parentH, fmtCtx, ctx);

        if (intrinsicWidth != null) {
            resolveIntrinsicWidth(box, ctx, intrinsicWidth, parentW, rootW, rootH, isBorderBox);
        }

        applyMinMaxWidths(box, ctx, parentW, rootW, rootH, isPercentWidthAndShrinking, isBorderBox);

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
    public static void resolveFlexibleHeights(LayoutBox box, float parentH, FormattingContext fmtCtx,
                                              LayoutContext ctx) {
        LayoutEngine engine = LayoutEngine.getEngine(box.style.getOrDefault(CssProperty.DISPLAY, "block"));
        resolveHeights(box, parentH, ctx, isBorderBox(box.style), engine, fmtCtx);
    }

    private static void resolveBoxEdges(LayoutBox box, float parentW, float parentH, float rootW, float rootH) {
        ComputedStyle style = box.style;

        box.mT = style.length(CssProperty.MARGIN_TOP, parentW, rootW, rootH);
        box.mR = style.length(CssProperty.MARGIN_RIGHT, parentW, rootW, rootH);
        box.mB = style.length(CssProperty.MARGIN_BOTTOM, parentW, rootW, rootH);
        box.mL = style.length(CssProperty.MARGIN_LEFT, parentW, rootW, rootH);

        box.pT = style.length(CssProperty.PADDING_TOP, parentW, rootW, rootH);
        box.pR = style.length(CssProperty.PADDING_RIGHT, parentW, rootW, rootH);
        box.pB = style.length(CssProperty.PADDING_BOTTOM, parentW, rootW, rootH);
        box.pL = style.length(CssProperty.PADDING_LEFT, parentW, rootW, rootH);


        box.bT = style.length(CssProperty.BORDER_TOP_WIDTH, parentH, rootW, rootH);
        box.bR = style.length(CssProperty.BORDER_RIGHT_WIDTH, parentW, rootW, rootH);
        box.bB = style.length(CssProperty.BORDER_BOTTOM_WIDTH, parentH, rootW, rootH);
        box.bL = style.length(CssProperty.BORDER_LEFT_WIDTH, parentW, rootW, rootH);

        box.rowGap = style.length(CssProperty.ROW_GAP, parentH, rootW, rootH);
        box.columnGap = style.length(CssProperty.COLUMN_GAP, parentW, rootW, rootH);
    }

    private static void resolveWidth(LayoutBox box, LayoutContext ctx, float parentW, float rootW, float rootH,
                                     boolean hasWidth, boolean hasIntrinsicWidth, boolean isPercentWidthAndShrinking,
                                     boolean isBorderBox, String display, FormattingContext fmtCtx) {
        ComputedStyle style = box.style;
        String tag = box.node.tag;

        if (hasIntrinsicWidth) {
            // 内容を測ってから resolveIntrinsicWidth で確定する
            box.w = 0;
            box.sizingMode = SizingMode.SHRINK_TO_FIT;
        } else if (hasWidth && !isPercentWidthAndShrinking) {
            box.w = toBorderBox(style.length(CssProperty.WIDTH, parentW, rootW, rootH),
                    box.horizontalDecoration(), isBorderBox);
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

    /** テキストノードは整形した結果の最長行が幅になる。 */
    private static void measureTextWidth(LayoutBox box, LayoutContext ctx, float parentW, float rootW, float rootH) {
        ComputedStyle style = box.style;

        float limitW = parentW;
        if (style.containsKey(CssProperty.MAX_WIDTH)) {
            float mw = style.length(CssProperty.MAX_WIDTH, parentW, rootW, rootH);
            if (mw > 0) limitW = Math.min(limitW, mw);
        }
        if (limitW <= 0) limitW = LayoutMetrics.UNBOUNDED_WIDTH;

        box.shapedLines = TextShaper.shape(box.displayText, ctx.faceFor(style), ctx.textStyleFor(style), limitW);

        float maxLineW = 0;
        for (TextShaper.ShapedLine line : box.shapedLines) {
            maxLineW = Math.max(maxLineW, line.width());
        }
        box.w = maxLineW + box.horizontalDecoration();
        box.sizingMode = SizingMode.DEFINITE;
    }

    /**
     * {@code width} の内容サイズのキーワードを解く。エンジンが shrink-to-fit で測った幅
     * （= min(max-content, 利用可能幅)）を出発点にする。
     *
     * <ul>
     *   <li>{@code min-content}: これ以上縮められない幅</li>
     *   <li>{@code max-content}: 折り返さない幅（エンジンには無制限の幅で測らせてある）</li>
     *   <li>{@code fit-content}: shrink-to-fit の幅そのもの</li>
     *   <li>{@code fit-content(<長さ>)}: min(max-content, max(min-content, 長さ))</li>
     * </ul>
     */
    private static void resolveIntrinsicWidth(LayoutBox box, LayoutContext ctx, String keyword,
                                              float parentW, float rootW, float rootH, boolean isBorderBox) {
        if ("min-content".equals(keyword)) {
            box.w = box.getMinContentWidth(ctx);
        } else if (keyword.startsWith("fit-content(") && keyword.endsWith(")")) {
            String arg = keyword.substring("fit-content(".length(), keyword.length() - 1).trim();
            float limit = toBorderBox(LengthResolver.parseDim(arg, parentW, rootW, rootH,
                    box.style.fontSizePx(), box.style.rootFontSizePx()), box.horizontalDecoration(), isBorderBox);
            box.w = Math.max(box.getMinContentWidth(ctx), Math.min(box.w, limit));
        }
        // 幅が確定したので、以降は min-width / max-width の対象になる
        box.sizingMode = SizingMode.DEFINITE;
    }

    /**
     * {@code width} / {@code height} などの指定値を border box の寸法に直す。
     *
     * 何が壊れていたか: {@code border-box} で padding + border が指定値を超えるとき、
     * 内容ボックスを 0 で止めずに border box を指定値のままにしていた（内容が負の大きさになる）。
     * CSS では内容ボックスが 0 で止まり、border box は padding + border まで広がる。
     */
    private static float toBorderBox(float specified, float decoration, boolean isBorderBox) {
        return isBorderBox ? Math.max(specified, decoration) : specified + decoration;
    }

    private static void applyMinMaxWidths(LayoutBox box, LayoutContext ctx, float parentW, float rootW, float rootH,
                                          boolean isPercentWidthAndShrinking, boolean isBorderBox) {
        ComputedStyle style = box.style;
        if (box.sizingMode != SizingMode.DEFINITE) return;

        String maxWStr = style.get(CssProperty.MAX_WIDTH);
        if (maxWStr != null) {
            if (LayoutMetrics.isIntrinsicSizeKeyword(maxWStr)) {
                // max-content / fit-content / auto は上限を課さない。min-content だけが上限になる。
                if ("min-content".equals(maxWStr.trim().toLowerCase())) {
                    box.w = Math.min(box.w, box.getMinContentWidth(ctx));
                }
            } else if (!(maxWStr.contains("%") && isPercentWidthAndShrinking)) {
                float maxW = toBorderBox(style.length(CssProperty.MAX_WIDTH, parentW, rootW, rootH),
                        box.horizontalDecoration(), isBorderBox);
                box.w = Math.min(box.w, maxW);
            }
        }

        String minWStr = style.get(CssProperty.MIN_WIDTH);
        if (minWStr != null) {
            if (LayoutMetrics.isIntrinsicSizeKeyword(minWStr)) {
                // min-content / fit-content / auto はいずれも「内容がこれ以上縮まない幅」が下限。
                // getMinContentWidth は装飾込みで返すため、ここで足し直さない。
                if (!"max-content".equals(minWStr.trim().toLowerCase())) {
                    box.w = Math.max(box.w, box.getMinContentWidth(ctx));
                }
            } else {
                float minW = toBorderBox(style.length(CssProperty.MIN_WIDTH, parentW, rootW, rootH),
                        box.horizontalDecoration(), isBorderBox);
                box.w = Math.max(box.w, minW);
            }
        }
    }

    private static void resolveHeights(LayoutBox box, float parentH, LayoutContext ctx, boolean isBorderBox,
                                       LayoutEngine engine, FormattingContext fmtCtx) {
        ComputedStyle style = box.style;
        float rootW = ctx.viewport().rootW();
        float rootH = ctx.viewport().rootH();

        String heightStr = style.getOrDefault(CssProperty.HEIGHT, "auto").trim();
        boolean hasHeight = style.containsKey(CssProperty.HEIGHT) && !"auto".equals(heightStr);

        if (hasHeight) {
            box.h = toBorderBox(style.length(CssProperty.HEIGHT, parentH, rootW, rootH),
                    box.verticalDecoration(), isBorderBox);
        } else if (style.containsKey(CssProperty.ASPECT_RATIO) && box.w > 0) {
            applyAspectRatio(box);
        } else {
            resolveIntrinsicHeight(box, ctx, heightStr);
        }

        engine.resolveFlexibleHeights(box, parentH, fmtCtx, ctx);

        if (style.containsKey(CssProperty.MAX_HEIGHT)) {
            String maxHStr = style.get(CssProperty.MAX_HEIGHT).trim();
            if (!"none".equals(maxHStr) && !maxHStr.isEmpty()) {
                float maxH = style.length(CssProperty.MAX_HEIGHT, parentH, rootW, rootH);
                if (maxH > 0) {
                    maxH = toBorderBox(maxH, box.verticalDecoration(), isBorderBox);
                    box.h = Math.min(box.h, maxH);
                }
            }
        }
        if (style.containsKey(CssProperty.MIN_HEIGHT)) {
            // B-5 修正済み: ここは縦方向なので verticalDecoration（以前は横を足していた）
            float minH = toBorderBox(style.length(CssProperty.MIN_HEIGHT, parentH, rootW, rootH),
                    box.verticalDecoration(), isBorderBox);
            box.h = Math.max(box.h, minH);
        }
    }

    private static void applyAspectRatio(LayoutBox box) {
        float innerW = Math.max(0, box.w - box.horizontalDecoration());
        String ratioStr = box.style.get(CssProperty.ASPECT_RATIO);
        try {
            if (ratioStr.contains("/")) {
                String[] parts = ratioStr.split("/");
                float rw = Float.parseFloat(parts[0].trim());
                float rh = Float.parseFloat(parts[1].trim());
                if (rw > 0) box.h = (innerW * (rh / rw)) + box.verticalDecoration();
            } else {
                float ratio = Float.parseFloat(ratioStr.trim());
                if (ratio > 0) box.h = (innerW / ratio) + box.verticalDecoration();
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
            // 高さは整形結果の行高の合計。行数と行送りを別々に数え直さない。
            float total = 0;
            if (box.shapedLines != null && !box.shapedLines.isEmpty()) {
                for (TextShaper.ShapedLine line : box.shapedLines) total += line.height();
            } else {
                total = box.lineHeightPx;
            }
            box.h = total + box.verticalDecoration();
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
                    float innerW = Math.max(0, box.w - box.horizontalDecoration());
                    box.h = (innerW * ((float) intrinsic[1] / intrinsic[0])) + box.verticalDecoration();
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
                box.h = box.lineHeightPx + 8 + box.verticalDecoration();
            }
            return;
        }

        if (tag.equals("progress")) {
            box.h = 12 + box.verticalDecoration();
        }
    }

    /** この要素の 1 行分の高さ（px）。書体と line-height から決まる。 */
    private static float lineHeightOf(LayoutBox box, LayoutContext ctx) {
        return LayoutMetrics.lineHeightPx(box.style, ctx.faceFor(box.style), ctx.fontStyleFor(box.style));
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
    private static float replacedFallbackWidth(LayoutBox box) {
        String src = box.node.attrs.getOrDefault("src", "");
        boolean remote = src.startsWith("http://") || src.startsWith("https://");
        return remote ? LayoutMetrics.PENDING_IMAGE_SIZE : LayoutMetrics.FALLBACK_REPLACED_SIZE;
    }

    private static boolean isBorderBox(ComputedStyle style) {
        return "border-box".equals(style.get(CssProperty.BOX_SIZING));
    }


    private static boolean isDisplayNone(ComputedStyle style) {
        return "none".equals(style.getOrDefault(CssProperty.DISPLAY, "block"));
    }
}
