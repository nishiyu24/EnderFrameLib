package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.dom.node.CustomElementPainter;
import com.nishiyu.enderframelib.core.css.CssPropertyIndex;
import com.nishiyu.enderframelib.core.css.StyleFacts;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;
import com.nishiyu.enderframelib.core.layout.model.*;

import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.model.CssKeywords;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.LengthResolver;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.layout.LayoutBox.Display.Inner;
import com.nishiyu.enderframelib.core.layout.LayoutBox.SizingMode;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.IntrinsicSizeResolver;
import com.nishiyu.enderframelib.core.media.ImageChoice;
import com.nishiyu.enderframelib.core.media.ImageSources;
import com.nishiyu.enderframelib.core.raster.svg.SvgImage;
import com.nishiyu.enderframelib.core.raster.svg.SvgSize;

import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

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
    public static void measure(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        float tentativeInnerW = space.availableInlineSize();
        float parentH = space.percentageBlockSize();
        FormattingContext fmtCtx = space.parent();
        ComputedStyle style = box.axisStyle();
        box.invalidateMinContentCache();

        if (isDisplayNone(style)) {
            box.w = 0;
            box.h = 0;
            return;
        }

        float rootW = ctx.viewport().rootW();
        float rootH = ctx.viewport().rootH();
        // 測り直しは前回の高さから始めない。高さは「今の値と内容の高さの大きい方」で決まるので、
        // 以前は親が伸ばした高さ（表の行・stretch）が次の測定に残り、内容の高さが分からなくなっていた
        box.h = 0;
        box.legendSpill = 0;
        box.measuredClamped = false;
        box.naturalInlineSize = Float.NaN;
        box.naturalFitInnerWidth = Float.NaN;

        float parentW = tentativeInnerW;
        if (box.isFixed) {
            parentW = rootW;
            parentH = rootH;
        }

        // font-size は算出スタイルが親のフォントサイズを基準に解決済み（ComputedStyle.of）。
        // ここで length() を使って解き直していたため、% が包含ブロックの「幅」基準になっていた。
        box.lineHeightPx = lineHeightOf(box, ctx);

        // 枠線を重ねる表の外周の枠線は、縁を解く前に決める（内側の幅の予算が枠線を引いた値になるように）
        if (box.display != null && box.display.inner() == Inner.TABLE && !box.contentAutoSkipped) TableLayoutEngine.prepareCollapsedBorders(box, ctx);
        resolveBoxEdges(box, parentW, parentH, rootW, rootH);

        StyleFacts facts = style.facts();
        recordStyleAndContextReads(box, facts);
        boolean autoLeft = facts.marginLeftAuto;
        boolean autoRight = facts.marginRightAuto;
        boolean isBorderBox = isBorderBox(style);

        // min-content / max-content / fit-content は長さではなく内容から決まる（W2 以前は 0 px に解けていた）
        byte intrinsicWidth = facts.intrinsicWidthKind;
        boolean hasWidth = !facts.widthAuto && intrinsicWidth == StyleFacts.IW_NONE;
        boolean isPercentWidthAndShrinking = false;
        if (hasWidth && facts.widthPercent) {
            if (Float.isNaN(box.overrideWidth)) MeasureReads.recordInput(MeasureReads.READ_SHRINK);
            isPercentWidthAndShrinking = fmtCtx.isShrink();
        }
        boolean bfc = establishesBfc(box, fmtCtx);
        if (box.establishesBfc != bfc) {
            box.establishesBfc = bfc;
            if (box.measureMemo.measuredSpace() != null) LayoutBox.bumpShapeEpoch(); // 親の float メモ（containsFloats）が読む
            else LayoutBox.invalidateFloats(box);
        }

        resolveWidth(box, ctx, parentW, rootW, rootH, hasWidth, intrinsicWidth != StyleFacts.IW_NONE,
                isPercentWidthAndShrinking, isBorderBox, fmtCtx);

        if (!box.isHorizontalReturnRoot) {
            applyMinMaxWidths(box, ctx, parentW, rootW, rootH, isPercentWidthAndShrinking, isBorderBox);
        }
        // 親のエンジンが決めた幅（flex の伸縮・stretch）は min / max で切り詰め済み
        boolean overridden = !Float.isNaN(box.overrideWidth);
        if (overridden) {
            box.w = box.overrideWidth;
            box.sizingMode = SizingMode.DEFINITE;
        }

        float engineInnerW;
        if (!box.isHorizontalReturnRoot && intrinsicWidth == StyleFacts.IW_MAX_CONTENT) {
            // 折り返さずに並べたときの幅が max-content
            engineInnerW = LayoutMetrics.UNBOUNDED_WIDTH;
        } else if (box.sizingMode == SizingMode.SHRINK_TO_FIT) {
            engineInnerW = Math.max(0, parentW - box.mL - box.mR - box.horizontalDecoration());
            // 縮小幅は min(max-content, 使える幅) を max-width で切り詰めたもの（CSS 2.2 §10.3.5・§10.4）。内容はその幅で折り返す
            float maxInner = shrinkMaxInnerWidth(box, parentW, rootW, rootH, isBorderBox, isPercentWidthAndShrinking);
            if (!Float.isNaN(maxInner)) engineInnerW = Math.min(engineInnerW, maxInner);
        } else {
            engineInnerW = Math.max(0, box.w - box.horizontalDecoration());
        }

        LayoutEngine engine = LayoutEngine.getEngine(box);
        // 箱自身の幅はここで決める。エンジンは子を測って内容の幅を返すだけ
        ConstraintSpace contentSpace = ConstraintSpace.reuseOrCreate(box.contentSpaceCache, engineInnerW, parentH, fmtCtx);
        box.contentSpaceCache = contentSpace;
        boolean skipByAuto = box.contentAutoSkipped;
        float contentW = skipByAuto
                ? box.containIntrinsicInlinePx(rootW, rootH)
                : engine.measureContent(box, contentSpace, ctx);
        LayoutBox legend = skipByAuto ? null : box.renderedLegend();
        if (legend != null) {
            legend.measureMemo.noteParentReadOpaque();
            measureLegend(legend, engineInnerW, ctx);
            contentW = Math.max(contentW, legend.w + legend.ownInlineMargins(ctx));
        }
        if (box.sizingMode == SizingMode.SHRINK_TO_FIT) {
            // 横の size containment がある箱（コンテナ）は、中身で幅が決まらない
            box.w = (box.effectiveContainsInlineSize() ? box.containIntrinsicInlinePx(rootW, rootH) : contentW)
                    + box.horizontalDecoration();
            float maxInner = shrinkMaxInnerWidth(box, parentW, rootW, rootH, isBorderBox, isPercentWidthAndShrinking);
            if (!Float.isNaN(maxInner)) box.w = Math.min(box.w, maxInner + box.horizontalDecoration());
            box.naturalInlineSize = box.w;
        }

        if (!box.isHorizontalReturnRoot && intrinsicWidth != StyleFacts.IW_NONE && !overridden) {
            resolveIntrinsicWidth(box, ctx, facts, parentW, rootW, rootH, isBorderBox);
        }

        if (!box.isHorizontalReturnRoot && !overridden) {
            applyMinMaxWidths(box, ctx, parentW, rootW, rootH, isPercentWidthAndShrinking, isBorderBox);
            // 縮小幅（float・inline-block・絶対配置）の箱も、内容で決めた幅に min-width の下限が掛かる（CSS 2.2 §10.4）
            if (box.sizingMode == SizingMode.SHRINK_TO_FIT) applyShrinkMinWidth(box, ctx, parentW, rootW, rootH, isBorderBox);
        }

        // auto マージンで中央に寄せるのはブロックフローだけ。flex / grid は自分の揃えで auto マージンを解く
        if (!box.isAbsolute && fmtCtx.isBlock()) {
            if (autoLeft && autoRight) {
                box.mL = Math.max(0, (parentW - box.w) / 2);
                box.mR = Math.max(0, parentW - box.w - box.mL);
            } else if (autoLeft) {
                box.mL = Math.max(0, parentW - box.w - box.mR);
            } else if (autoRight) {
                box.mR = Math.max(0, parentW - box.w - box.mL);
            }
        }

        if (legend != null) {
            float innerW = Math.max(0, box.w - box.horizontalDecoration());
            if (innerW != engineInnerW) measureLegend(legend, innerW, ctx);
            box.legendSpill = legendSpill(box, legend);
        }

        resolveHeights(box, contentSpace, ctx, isBorderBox, engine);
        if (!box.contentAutoSkipped && facts.contentVisibilityAuto) {
            box.recordRememberedSize();
        }
    }

    /** fieldset の legend の幅は fit-content（HTML §15.3.13）。auto マージンは置くときに解く。 */
    private static final FormattingContext LEGEND_CONTEXT = FormattingContext.of(FormattingContext.Display.LEGEND, true, false);

    private static void measureLegend(LayoutBox legend, float fieldsetInnerW, LayoutContext ctx) {
        legend.measure(new ConstraintSpace(fieldsetInnerW, Float.NaN, LEGEND_CONTEXT), ctx);
    }

    /**
     * legend が上の枠線からはみ出す分。legend の border box は枠線の中央に置かれ（枠線より高ければ上端に揃う）、
     * その下マージンまでが枠線の下へ出る分だけ内容が下がる。上マージンは数えない（Chrome の実測）。
     */
    private static float legendSpill(LayoutBox fieldset, LayoutBox legend) {
        float top = Math.max(0, (fieldset.bT - legend.h) / 2);
        return Math.max(0, top + legend.h + legend.mB - fieldset.bT);
    }

    /**
     * 高さだけを再解決する。
     * flex の grow/shrink やグリッドの stretch で幅・高さが変わった子に対して、
     * エンジンから呼ばれる。
     */
    public static void resolveFlexibleHeights(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        resolveFlexibleHeights(box, space, ctx, true);
    }

    /**
     * 高さだけを再解決する。親フローに先立つ float が無く（{@code hadIncomingFloats == false}）、子自身の
     * 測定が同じ幅・% 基準で既に有効かつ組み直し前後で外寸が変わらない場合は、祖先と子の {@code measurementValid} を保つ。
     */
    static void resolveFlexibleHeights(LayoutBox box, ConstraintSpace space, LayoutContext ctx, boolean hadIncomingFloats) {
        LayoutEngine engine = LayoutEngine.getEngine(box);
        if (!hadIncomingFloats && box.canPreserveMeasurementForEmptyFloatFlow(space, ctx)) {
            resolveHeights(box, space, ctx, isBorderBox(box.axisStyle()), engine);
            if (!box.outputsMatchRecordedMeasurement()) {
                box.invalidateMeasurement();
            }
            return;
        }
        // measure を通らずに中身を組み直す（float を避けた行・伸ばした高さ）。前回の measure の結果はもう当てはまらない
        box.invalidateMeasurement();
        resolveHeights(box, space, ctx, isBorderBox(box.axisStyle()), engine);
    }


    private static void resolveBoxEdges(LayoutBox box, float parentW, float parentH, float rootW, float rootH) {
        ComputedStyle style = box.axisStyle();
        Style t = style.typed();

        box.mT = LayoutUnit.snap(t.boxPx(t.box.marginTop, parentW, rootW, rootH));
        box.mR = LayoutUnit.snap(t.boxPx(t.box.marginRight, parentW, rootW, rootH));
        box.mB = LayoutUnit.snap(t.boxPx(t.box.marginBottom, parentW, rootW, rootH));
        box.mL = LayoutUnit.snap(t.boxPx(t.box.marginLeft, parentW, rootW, rootH));
        box.ownMarginTop = box.mT;
        box.ownMarginBottom = box.mB;

        box.pT = LayoutUnit.snap(t.boxPx(t.box.paddingTop, parentW, rootW, rootH));
        box.pR = LayoutUnit.snap(t.boxPx(t.box.paddingRight, parentW, rootW, rootH));
        box.pB = LayoutUnit.snap(t.boxPx(t.box.paddingBottom, parentW, rootW, rootH));
        box.pL = LayoutUnit.snap(t.boxPx(t.box.paddingLeft, parentW, rootW, rootH));


        box.bT = LayoutUnit.snap(t.boxPx(t.box.borderTopWidth, parentH, rootW, rootH));
        box.bR = LayoutUnit.snap(t.boxPx(t.box.borderRightWidth, parentW, rootW, rootH));
        box.bB = LayoutUnit.snap(t.boxPx(t.box.borderBottomWidth, parentH, rootW, rootH));
        box.bL = LayoutUnit.snap(t.boxPx(t.box.borderLeftWidth, parentW, rootW, rootH));
        if (box.collapsedBorders != null) {
            box.bT = LayoutUnit.snap(box.collapsedBorders[0]);
            box.bR = LayoutUnit.snap(box.collapsedBorders[1]);
            box.bB = LayoutUnit.snap(box.collapsedBorders[2]);
            box.bL = LayoutUnit.snap(box.collapsedBorders[3]);
        }
        // 枠線を重ねる表は padding を持たない（CSS 2.2 §17.6.2）
        if (box.display.inner() == Inner.TABLE
                && style.typed().inherited.borderCollapse == CssKeywords.BorderCollapse.COLLAPSE) {
            box.pT = box.pR = box.pB = box.pL = 0;
        }

        box.rowGap = LayoutUnit.snap(t.gridPx(t.flexGrid.rowGap, parentH, rootW, rootH));
        box.columnGap = LayoutUnit.snap(t.gridPx(t.flexGrid.columnGap, parentW, rootW, rootH));
    }

    private static void resolveWidth(LayoutBox box, LayoutContext ctx, float parentW, float rootW, float rootH,
                                     boolean hasWidth, boolean hasIntrinsicWidth, boolean isPercentWidthAndShrinking,
                                     boolean isBorderBox, FormattingContext fmtCtx) {
        ComputedStyle style = box.axisStyle();
        String tag = box.node.tag;

        if (box.isHorizontalReturnRoot) {
            // 直交戻しの根は width/height を内側の水平ラッパーが解釈する
            box.w = 0;
            box.sizingMode = SizingMode.SHRINK_TO_FIT;
            return;
        }

        float anchorW = box.isAbsolute ? box.anchorWidthPx() : -1;
        if (anchorW >= 0) {
            // width: anchor-size(...)。アンカーの幅に合わせる（アンカーが先に測られている場合）
            box.w = anchorW + (isBorderBox ? 0 : box.horizontalDecoration());
            box.sizingMode = SizingMode.DEFINITE;
        } else if (hasIntrinsicWidth) {
            // 内容を測ってから resolveIntrinsicWidth で確定する
            box.w = 0;
            box.sizingMode = SizingMode.SHRINK_TO_FIT;
        } else if (hasWidth && !isPercentWidthAndShrinking) {
            box.w = toBorderBox(style.length(CssPropertyIndex.WIDTH, parentW, rootW, rootH),
                    box.horizontalDecoration(), isBorderBox);
            box.sizingMode = SizingMode.DEFINITE;
        } else if (box.node.nodeType == NodeType.TEXT) {
            measureTextWidth(box, ctx, parentW, rootW, rootH);
        } else if (ReplacedElements.isHostScene(tag)) {
            CustomElementPainter scene = ReplacedElements.painterOf(tag);
            box.w = scene.sceneFillsWidth()
                    ? Math.max(scene.sceneMinWidth(), parentW - box.mL - box.mR)
                    : scene.sceneMinWidth() + box.horizontalDecoration();
            box.sizingMode = SizingMode.DEFINITE;
        } else if (isReplacedImage(tag)) {
            int[] intrinsic = intrinsicSize(box, ctx);
            box.w = (intrinsic != null && intrinsic[0] > 0)
                    ? intrinsic[0] + box.horizontalDecoration()
                    : replacedFallbackWidth(box, ctx) + box.horizontalDecoration();
            box.sizingMode = SizingMode.DEFINITE;
        } else if (com.nishiyu.enderframelib.core.dom.node.ElementTraits.isControlSizedByContent(tag)) {
            box.w = box.getMinContentWidth(ctx);
            box.sizingMode = SizingMode.DEFINITE;
        } else if (box.isAbsolute && hasBothInsets(style, CssPropertyIndex.LEFT, CssPropertyIndex.RIGHT)) {
            // left と right の両方があれば width: auto は間の幅（CSS 2.2 §10.3.7）。
            // 以前は shrink-to-fit にしていて、内容の無い箱は幅 0 になっていた
            float insets = style.length(CssPropertyIndex.LEFT, parentW, rootW, rootH)
                    + style.length(CssPropertyIndex.RIGHT, parentW, rootW, rootH);
            box.w = Math.max(box.horizontalDecoration(), parentW - insets - box.mL - box.mR);
            box.sizingMode = SizingMode.DEFINITE;
        } else if (box.isAbsolute || box.isRenderedLegend || fmtCtx.isFlexRow() || BlockLayoutEngine.isFloating(box)
                || box.display.isInlineLevel() || box.display.inner() == Inner.TABLE
                || box.isVerticalRoot || readShrink(box, fmtCtx)) {
            // 縦書きの根の幅（物理）は論理のブロック寸法＝行を積んだ高さで決まる（直交フロー）
            // インラインレベルの箱・float・表・fieldset の legend は shrink-to-fit（表の幅は列幅の合計）
            box.w = 0;
            box.sizingMode = SizingMode.SHRINK_TO_FIT;
        } else if (fmtCtx.isFlexColumn() && !readStretch(box, fmtCtx)) {
            box.w = 0;
            box.sizingMode = SizingMode.SHRINK_TO_FIT;
        } else {
            box.w = Math.max(0, parentW - box.mL - box.mR);
            box.sizingMode = SizingMode.DEFINITE;
        }
    }

    /** テキストノードは整形した結果の最長行が幅になる。 */
    private static void measureTextWidth(LayoutBox box, LayoutContext ctx, float parentW, float rootW, float rootH) {
        ComputedStyle style = box.axisStyle();

        float limitW = parentW;
        if (style.containsKey(CssPropertyIndex.MAX_WIDTH)) {
            float mw = style.length(CssPropertyIndex.MAX_WIDTH, parentW, rootW, rootH);
            if (mw > 0) limitW = Math.min(limitW, mw);
        }
        if (limitW <= 0) limitW = LayoutMetrics.UNBOUNDED_WIDTH;

        var interval = box.shapeTextInterval(box.displayText, ctx.faceFor(style), ctx.textStyleFor(style), limitW);
        box.shapedLines = interval.lines();
        box.textBaseline = BlockLayoutEngine.baselineWithin(ctx.textStyleFor(style), ctx.faceFor(style));
        float maxLineW = interval.maxLineWidth();
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
    private static void resolveIntrinsicWidth(LayoutBox box, LayoutContext ctx, StyleFacts facts,
                                              float parentW, float rootW, float rootH, boolean isBorderBox) {
        if (facts.intrinsicWidthKind == StyleFacts.IW_MIN_CONTENT) {
            box.w = box.getMinContentWidth(ctx);
        } else if (facts.intrinsicWidthKind == StyleFacts.IW_FIT_CONTENT_FN && facts.fitContentArg != null) {
            String arg = facts.fitContentArg;
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
    static float toBorderBox(float specified, float decoration, boolean isBorderBox) {
        return isBorderBox ? Math.max(specified, decoration) : specified + decoration;
    }

    /** 縮小幅の箱の max-width（内容ボックスの幅）。指定が無い・解けない（不定の %・キーワード）なら NaN。 */
    private static float shrinkMaxInnerWidth(LayoutBox box, float parentW, float rootW, float rootH, boolean isBorderBox,
                                             boolean isPercentWidthAndShrinking) {
        if (box.isHorizontalReturnRoot) return Float.NaN;
        ComputedStyle style = box.axisStyle();
        String maxWStr = style.get(CssPropertyIndex.MAX_WIDTH);
        if (maxWStr == null || "none".equalsIgnoreCase(maxWStr.trim()) || LayoutMetrics.isIntrinsicSizeKeyword(maxWStr)) return Float.NaN;
        if (maxWStr.contains("%") && (isPercentWidthAndShrinking || parentW <= 0)) return Float.NaN;
        float maxW = toBorderBox(style.length(CssPropertyIndex.MAX_WIDTH, parentW, rootW, rootH), box.horizontalDecoration(), isBorderBox);
        return Math.max(0, maxW - box.horizontalDecoration());
    }

    private static void applyShrinkMinWidth(LayoutBox box, LayoutContext ctx, float parentW, float rootW, float rootH, boolean isBorderBox) {
        ComputedStyle style = box.axisStyle();
        String minWStr = style.get(CssPropertyIndex.MIN_WIDTH);
        if (minWStr == null || LayoutMetrics.isIntrinsicSizeKeyword(minWStr)) return;
        float minW = toBorderBox(style.length(CssPropertyIndex.MIN_WIDTH, parentW, rootW, rootH), box.horizontalDecoration(), isBorderBox);
        if (minW > box.w) box.w = minW;
    }

    private static void applyMinMaxWidths(LayoutBox box, LayoutContext ctx, float parentW, float rootW, float rootH,
                                          boolean isPercentWidthAndShrinking, boolean isBorderBox) {
        ComputedStyle style = box.axisStyle();
        if (box.sizingMode != SizingMode.DEFINITE) return;

        String maxWStr = style.get(CssPropertyIndex.MAX_WIDTH);
        if (maxWStr != null) {
            if (LayoutMetrics.isIntrinsicSizeKeyword(maxWStr)) {
                // max-content / fit-content / auto は上限を課さない。min-content だけが上限になる。
                if ("min-content".equals(maxWStr.trim().toLowerCase())) {
                    box.w = Math.min(box.w, box.getMinContentWidth(ctx));
                }
            } else if (!(maxWStr.contains("%") && isPercentWidthAndShrinking)) {
                float maxW = toBorderBox(style.length(CssPropertyIndex.MAX_WIDTH, parentW, rootW, rootH),
                        box.horizontalDecoration(), isBorderBox);
                box.w = Math.min(box.w, maxW);
            }
        }

        String minWStr = style.get(CssPropertyIndex.MIN_WIDTH);
        if (minWStr != null) {
            if (LayoutMetrics.isIntrinsicSizeKeyword(minWStr)) {
                // min-content / fit-content / auto はいずれも「内容がこれ以上縮まない幅」が下限。
                // getMinContentWidth は装飾込みで返すため、ここで足し直さない。
                if (!"max-content".equals(minWStr.trim().toLowerCase())) {
                    box.w = Math.max(box.w, box.getMinContentWidth(ctx));
                }
            } else {
                float minW = toBorderBox(style.length(CssPropertyIndex.MIN_WIDTH, parentW, rootW, rootH),
                        box.horizontalDecoration(), isBorderBox);
                box.w = Math.max(box.w, minW);
            }
        }
        // 表の幅は列の最小幅の和より狭くならない（width / max-width より優先。CSS 2.2 §17.5.2.1）
        if (box.display.inner() == Inner.TABLE) box.w = Math.max(box.w, box.getMinContentWidth(ctx));
    }

    private static void resolveHeights(LayoutBox box, ConstraintSpace space, LayoutContext ctx, boolean isBorderBox,
                                       LayoutEngine engine) {
        float parentH = space.percentageBlockSize();
        ComputedStyle style = box.axisStyle();
        float rootW = ctx.viewport().rootW();
        float rootH = ctx.viewport().rootH();

        StyleFacts facts = style.facts();
        // 基準の高さが不定（NaN）なら % の高さは auto（CSS 2.2 §10.5）。グリッドの行の大きさを決める段階がこれを使う
        boolean hasHeight = !box.isHorizontalReturnRoot && !facts.heightAuto
                && !(Float.isNaN(parentH) && facts.heightPercent);
        boolean overridden = !Float.isNaN(box.overrideHeight);

        float anchorH = box.isAbsolute ? box.anchorHeightPx() : -1;
        if (overridden) {
            box.h = box.overrideHeight;
            hasHeight = true;
        } else if (box.isHorizontalReturnRoot) {
            // 内側の水平ラッパーの幅が H.h になる
        } else if (anchorH >= 0) {
            box.h = anchorH + (isBorderBox ? 0 : box.verticalDecoration());
            hasHeight = true;
        } else if (hasHeight) {
            box.h = toBorderBox(style.length(CssPropertyIndex.HEIGHT, parentH, rootW, rootH),
                    specifiedDecoration(box, isBorderBox), isBorderBox);
        } else if (box.isAbsolute && hasBothInsets(style, CssPropertyIndex.TOP, CssPropertyIndex.BOTTOM)) {
            // top と bottom の両方があれば height: auto は間の高さ（CSS 2.2 §10.6.4）
            float insets = style.length(CssPropertyIndex.TOP, parentH, rootW, rootH)
                    + style.length(CssPropertyIndex.BOTTOM, parentH, rootW, rootH);
            box.h = Math.max(box.verticalDecoration(), parentH - insets - box.mT - box.mB);
            hasHeight = true;
        } else if (style.containsKey(CssPropertyIndex.ASPECT_RATIO) && box.w > 0) {
            applyAspectRatio(box);
        } else if (!box.contentAutoSkipped && !facts.contentVisibilityHidden) {
            resolveIntrinsicHeight(box, ctx, facts.heightAuto);
        }

        // gap の % は容器自身の内容ボックスが基準（CSS Box Alignment 3 §8.1）。親の寸法で解いた値を、幅と高さが決まったここで解き直す。
        // 高さが不定なら行の gap の % は 0
        Style t = style.typed();
        String rg = style.get(CssPropertyIndex.ROW_GAP), cg = style.get(CssPropertyIndex.COLUMN_GAP);
        if (cg != null && cg.indexOf('%') >= 0) {
            box.columnGap = LayoutUnit.snap(t.gridPx(t.flexGrid.columnGap, Math.max(0, box.w - box.horizontalDecoration()), rootW, rootH));
        }
        if (rg != null && rg.indexOf('%') >= 0) {
            box.rowGap = LayoutUnit.snap(t.gridPx(t.flexGrid.rowGap, hasHeight ? Math.max(0, box.h - box.verticalDecoration()) : 0, rootW, rootH));
        }

        // 高さが auto なら内容の高さが箱の高さになる。伸長や stretch はその高さを見て行う
        float contentH;
        if (box.effectiveContainsBlockSize()) {
            // 中身の高さは寄与しないので測らない。前回の測定で作った配置の状態を残すと、子が増減した後の配置に使われてしまう
            box.engineState = null;
            contentH = box.containIntrinsicBlockPx(rootW, rootH);
        } else {
            contentH = engine.contentBlockSize(box, space, ctx);
        }
        box.contentBlockExtent = contentH + box.verticalDecoration();
        if (!hasHeight) box.h = Math.max(box.h, contentH + box.verticalDecoration());
        else if (box.display.inner() == Inner.TABLE && !overridden) {
            // 表の height は行の部分の下限。caption はその外に足され、内容が高ければ内容に合わせる（CSS 2.2 §17.4・§17.5.3）
            box.h = Math.max(box.h + TableLayoutEngine.captionExtent(box), contentH + box.verticalDecoration());
        }
        if (!box.contentAutoSkipped) engine.finishLayout(box, space, ctx);
        if (overridden || box.isHorizontalReturnRoot) return;

        if (style.containsKey(CssPropertyIndex.MAX_HEIGHT)) {
            String maxHStr = style.get(CssPropertyIndex.MAX_HEIGHT).trim();
            if (!"none".equals(maxHStr) && !maxHStr.isEmpty()) {
                float maxH = style.length(CssPropertyIndex.MAX_HEIGHT, parentH, rootW, rootH);
                if (maxH > 0) {
                    maxH = toBorderBox(maxH, specifiedDecoration(box, isBorderBox), isBorderBox);
                    box.h = Math.min(box.h, maxH);
                }
            }
        }
        if (style.containsKey(CssPropertyIndex.MIN_HEIGHT)) {
            // B-5 修正済み: ここは縦方向なので verticalDecoration（以前は横を足していた）
            float minH = toBorderBox(style.length(CssPropertyIndex.MIN_HEIGHT, parentH, rootW, rootH),
                    specifiedDecoration(box, isBorderBox), isBorderBox);
            box.h = Math.max(box.h, minH);
        }
    }

    /**
     * 指定した高さに足す縦の装飾。fieldset の legend が枠線からはみ出す分は、content-box の指定の高さでは
     * 内容の高さから差し引く（HTML §15.3.13）ので足さない。border-box では装飾の下限にだけ効く。
     */
    private static float specifiedDecoration(LayoutBox box, boolean isBorderBox) {
        return isBorderBox ? box.verticalDecoration() : box.verticalDecoration() - box.legendSpill;
    }

    private static void applyAspectRatio(LayoutBox box) {
        ComputedStyle s = box.axisStyle();
        float ratio = aspectRatioOf(s);
        if (!(ratio > 0)) {
            box.h = box.w;
            return;
        }
        // box-sizing: border-box なら比率は枠線ボックスに掛かる（CSS Sizing 4 §5.1）
        if (s.isBorderBox()) box.h = Math.max(box.verticalDecoration(), box.w / ratio);
        else box.h = Math.max(0, box.w - box.horizontalDecoration()) / ratio + box.verticalDecoration();
    }

    /** {@code aspect-ratio} の幅 / 高さ（{@code auto} だけ・解けなければ NaN）。{@code auto 1/1} の auto は無視する。 */
    static float aspectRatioOf(ComputedStyle s) {
        String v = s.get(CssPropertyIndex.ASPECT_RATIO);
        if (v == null) return Float.NaN;
        String t = v.trim().toLowerCase(java.util.Locale.ROOT).replace("auto", "").trim();
        if (t.isEmpty()) return Float.NaN;
        try {
            int slash = t.indexOf('/');
            if (slash < 0) return Float.parseFloat(t);
            float rw = Float.parseFloat(t.substring(0, slash).trim()), rh = Float.parseFloat(t.substring(slash + 1).trim());
            return rh > 0 ? rw / rh : Float.NaN;
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    /** 比率 {@code ratio}（幅 / 高さ）の箱の、枠線ボックスの高さ {@code borderH} に対する枠線ボックスの幅。 */
    static float widthFromHeight(LayoutBox box, float ratio, float borderH) {
        if (box.axisStyle().isBorderBox()) return Math.max(box.horizontalDecoration(), borderH * ratio);
        return Math.max(0, borderH - box.verticalDecoration()) * ratio + box.horizontalDecoration();
    }

    /**
     * 置換要素・テキスト・フォームコントロールの固有高さ。
     *
     * 新しい置換要素（カスタム要素）を足す場合はここが拡張点になる。
     * サイズの実体は {@link IntrinsicSizeResolver} が外（クライアント側）から供給する。
     */
    private static void resolveIntrinsicHeight(LayoutBox box, LayoutContext ctx, boolean heightAuto) {
        String tag = box.node.tag;

        if (box.node.nodeType == NodeType.TEXT) {
            // 高さは整形結果の行高の合計。行数と行送りを別々に数え直さない。
            float total = 0;
            if (box.shapedLines != null && !box.shapedLines.isEmpty()) {
                for (int i = 0, n = box.shapedLines.size(); i < n; i++) total += box.shapedLines.get(i).height();
            } else {
                total = box.lineHeightPx;
            }
            box.h = total + box.verticalDecoration();
            return;
        }

        if (ReplacedElements.isHostScene(tag)) {
            box.h = ReplacedElements.painterOf(tag).sceneHeight() + box.verticalDecoration();
            return;
        }

        if (isReplacedImage(tag)) {
            int[] intrinsic = intrinsicSize(box, ctx);
            if (intrinsic != null && intrinsic[0] > 0 && intrinsic[1] > 0) {
                boolean heightIsAuto = !box.axisStyle().containsKey(CssPropertyIndex.HEIGHT) || heightAuto;
                if (box.w > 0 && heightIsAuto) {
                    float innerW = Math.max(0, box.w - box.horizontalDecoration());
                    box.h = (innerW * ((float) intrinsic[1] / intrinsic[0])) + box.verticalDecoration();
                } else {
                    box.h = intrinsic[1] + box.verticalDecoration();
                }
            } else {
                box.h = replacedFallbackWidth(box, ctx) + box.verticalDecoration();
            }
            return;
        }

        float control = ElementSizing.height(box, ctx);
        if (!Float.isNaN(control)) box.h = control;
    }

    /** この要素の 1 行分の高さ（px）。書体と line-height から決まる。 */
    private static float lineHeightOf(LayoutBox box, LayoutContext ctx) {
        return LayoutMetrics.lineHeightPx(box.style, ctx.faceFor(box.style), ctx.fontStyleFor(box.style));
    }

    static boolean isReplacedImage(String tag) {
        return com.nishiyu.enderframelib.core.dom.node.ElementTraits.isBuiltinReplacedImage(tag) || ReplacedElements.isHostImageLike(tag);
    }

    /**
     * 置換要素の固有サイズ（CSS px）。{@code canvas} は {@code width} / {@code height} 属性（既定 300×150）、
     * インライン {@code svg} は属性か {@code viewBox}、{@code img} は選んだ候補（{@code srcset} / {@code sizes}）の画像の画素数を
     * 選んだ密度で割った値。ローカルの PNG などは固定サイズ（null）、リモート画像と SVG は外部の解決を待つ。
     */
    private static int[] intrinsicSize(LayoutBox box, LayoutContext ctx) {
        String tag = box.node.tag;
        int[] embedded = ElementSizing.embeddedIntrinsic(box);
        if (embedded != null) return embedded;
        ImageChoice sel = selectedImage(box, ctx);
        if (sel == null) return null;
        String src = sel.url();
        int[] size;
        if (SvgImage.isSvgDataUri(src)) {
            SvgImage svg = SvgImage.cachedFromDataUri(src);
            size = svg == null ? null : new int[]{(int) Math.round(svg.width()), (int) Math.round(svg.height())};
        } else if (isRemote(src) || SvgImage.looksLikeSvg(src)) {
            IntrinsicSizeResolver resolver = ctx.intrinsicResolver();
            size = resolver != null ? resolver.getSize(src) : null;
        } else {
            return null;
        }
        if (size == null || sel.density() == 1) return size;
        return new int[]{(int) Math.max(1, Math.round(size[0] / sel.density())), (int) Math.max(1, Math.round(size[1] / sel.density()))};
    }

    private static ImageChoice selectedImage(LayoutBox box, LayoutContext ctx) {
        return ImageSources.forImg(box.node.attrs, ctx.viewport().rootW(), ctx.viewport().rootH(), ctx.viewport().devicePixelRatio());
    }

    private static boolean isRemote(String src) {
        return src.startsWith("http://") || src.startsWith("https://");
    }

    /** サイズが分からないときの既定値。リモート画像は読み込み中なので少し大きめにとる。 */
    /**
     * 画像類（img / canvas / svg / ホストの画像）の固有の幅（装飾込み）。{@link #resolveWidth} の置換画像の経路と同じ値を、
     * 箱が前回に測った幅（flex の伸縮で変わり得る）を読まずに求める。画像類でなければ NaN。
     */
    static float replacedImageWidth(LayoutBox box, LayoutContext ctx) {
        if (!isReplacedImage(box.node.tag)) return Float.NaN;
        int[] intrinsic = intrinsicSize(box, ctx);
        return (intrinsic != null && intrinsic[0] > 0)
                ? intrinsic[0] + box.horizontalDecoration()
                : replacedFallbackWidth(box, ctx) + box.horizontalDecoration();
    }

    private static float replacedFallbackWidth(LayoutBox box, LayoutContext ctx) {
        ImageChoice sel = com.nishiyu.enderframelib.core.dom.node.ElementTraits.isImage(box.node.tag) ? selectedImage(box, ctx) : null;
        String src = sel != null ? sel.url() : box.node.attrs.getOrDefault("src", "");
        boolean pending = isRemote(src) || SvgImage.looksLikeSvg(src);
        return pending ? LayoutMetrics.PENDING_IMAGE_SIZE : LayoutMetrics.FALLBACK_REPLACED_SIZE;
    }

    /**
     * この箱が子のためにブロック整形コンテキスト（BFC）を作るか（CSS 2.2 §9.4.1）。
     *
     * 何が壊れていたか: 以前は BFC という概念が無く、親子のマージン相殺が「親の上パディングと
     * ボーダーが 0 か」だけで決まっていた。そのため {@code display: flow-root} や
     * {@code overflow: hidden} の親、flex アイテムの中でも子のマージンが外へ貫通していた。
     */
    static boolean establishesBfc(LayoutBox box, FormattingContext fmtCtx) {
        if (box.parent == null || "html".equals(box.node.tag)) return true;
        switch (box.display.inner()) {
            case FLOW_ROOT, FLEX, GRID, TABLE, TABLE_CELL, TABLE_CAPTION -> {
                return true;
            }
            default -> {
            }
        }
        // 段組のコンテナは独立した整形コンテキストを作る（CSS Multicol 1 §2）
        if (MulticolLayout.isMulticol(box)) return true;
        // 直交フロー（縦書き根・縦中横・ラッパー）および fieldset 等は独立した整形コンテキストを作る（CSS Writing Modes §7.1 / HTML §15.3.13）
        if (box.isVerticalWrapper || box.isVerticalRoot || box.isHorizontalReturnRoot
                || box.isAbsolute || box.isRenderedLegend || com.nishiyu.enderframelib.core.dom.node.ElementTraits.isFieldset(box.node.tag)) return true;
        StyleFacts facts = box.axisStyle().facts();
        if (facts.floating) return true;
        // flex / grid アイテムは自分の中に独立した整形コンテキストを作る
        if (fmtCtx.isFlexRow() || fmtCtx.isFlexColumn() || fmtCtx.isGrid()) return true;
        // layout / paint containment は独立した整形コンテキストを作る（CSS Containment 2）
        // visible と clip 以外の overflow も BFC を作る（clip はスクロールコンテナにならない）
        return facts.bfcByStyle;
    }

    /** 両側の inset が auto でないか（絶対配置の寸法を inset から決められるか）。 */
    static boolean hasBothInsets(ComputedStyle style, int start, int end) {
        return !isAutoInset(style, start) && !isAutoInset(style, end);
    }

    static boolean isAutoInset(ComputedStyle style, int side) {
        String v = style.get(side);
        return v == null || "auto".equalsIgnoreCase(v.trim());
    }

    private static boolean isBorderBox(ComputedStyle style) {
        return style.isBorderBox();
    }

    private static boolean readShrink(LayoutBox box, FormattingContext fmtCtx) {
        if (Float.isNaN(box.overrideWidth)) MeasureReads.recordInput(MeasureReads.READ_SHRINK);
        return fmtCtx.isShrink();
    }

    private static boolean readStretch(LayoutBox box, FormattingContext fmtCtx) {
        if (Float.isNaN(box.overrideWidth)) MeasureReads.recordInput(MeasureReads.READ_STRETCH);
        return fmtCtx.stretch();
    }

    private static void recordStyleAndContextReads(LayoutBox box, StyleFacts facts) {
        int mask = 0;
        if (box.parent == null || box.isFixed || box.isAbsolute || facts.usesViewportUnits
                || box.isVerticalRoot || box.isHorizontalReturnRoot
                || (box.node != null && com.nishiyu.enderframelib.core.dom.node.ElementTraits.selectsImageCandidates(box.node.tag))
                || box.effectiveContainsInlineSize() || box.effectiveContainsBlockSize()) {
            mask |= MeasureReads.READ_VIEWPORT_W | MeasureReads.READ_VIEWPORT_H;
        }
        if (!facts.blockPercentFree || facts.percentGeometry || facts.intrinsicWidthKind != StyleFacts.IW_NONE
                || box.renderedLegend() != null || box.parent == null || box.isFixed
                || box.isVerticalRoot || box.isHorizontalReturnRoot
                || LayoutBox.isTableBox(box) || box.collapsedBorders != null
                || MulticolLayout.isMulticol(box)) {
            mask |= MeasureReads.READ_PERCENT_BLOCK;
        }
        if (!facts.horizontalFixed || box.isFixed || box.node.nodeType == NodeType.TEXT
                || LayoutBox.isTableBox(box) || box.collapsedBorders != null
                || (box.display.isInlineLevel() && !BlockLayoutEngine.isAtomicInline(box))
                || box.isHorizontalReturnRoot || box.isVerticalRoot || box.verticalAxisFlow != 0) {
            mask |= MeasureReads.READ_AVAIL_INLINE;
        }
        if (mask != 0) MeasureReads.recordInput(mask);
    }

    private static boolean isDisplayNone(ComputedStyle style) {
        return style.facts().displayNone;
    }
}