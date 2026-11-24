package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssLength;
import com.nishiyu.enderframelib.core.css.LengthResolver;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.StyleFacts;
import com.nishiyu.enderframelib.core.css.model.*;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CSS Anchor Positioning 1（{@code anchor()}・{@code anchor-size()}・{@code position-area}・
 * {@code position-try-fallbacks}・{@code position-try-order}・{@code @position-try}）の配置解決。
 */
final class AnchorPositioning {

    private static final float OVERFLOW_EPS = 0.05f;
    private static final int FLIP_BLOCK = 0;
    private static final int FLIP_INLINE = 1;
    private static final int FLIP_START = 2;

    private AnchorPositioning() {}

    /**
     * grid / flex 容器の絶対配置の子の静的位置を「領域 + 揃え」で入れる（横書きのみ。他は内容ボックスの原点のまま）。
     * <ul>
     *   <li>grid: 容器の内容ボックス（容器が包含ブロックなら grid-area の領域）を唯一のグリッド領域とみなし、{@code justify-self} / {@code align-self}
     *       （auto は容器の {@code justify-items} / {@code align-items}）で揃える（css-grid §9.4）。</li>
     *   <li>flex: 唯一の項目とみなし、主軸は {@code justify-content}、交差軸は {@code align-self} で揃える（css-flexbox §4.1）。</li>
     * </ul>
     */
    static void applyStaticArea(LayoutBox parent, LayoutBox child, float innerX, float innerY) {
        child.staticAreaW = Float.NaN;
        child.staticAreaH = Float.NaN;
        LayoutBox.Display.Inner inner = parent.display.inner();
        if (inner != LayoutBox.Display.Inner.GRID && inner != LayoutBox.Display.Inner.FLEX) return;
        if (parent.verticalAxisFlow != 0 || parent.isVerticalRoot || !isHorizontal(parent.style) || !isHorizontal(child.style)) return;
        boolean rtl = isRtl(parent.style);
        ComputedStyle ps = parent.style, cs = child.style;
        if (inner == LayoutBox.Display.Inner.GRID) {
            child.staticAlignX = selfFactor(selfValue(cs, true, ps), rtl, true, false);
            child.staticAlignY = selfFactor(selfValue(cs, false, ps), false, false, false);
            if (ps.typed().box.position != Position.STATIC) {
                // 容器が包含ブロック（grid-area があればそのグリッド領域）なら、その中で揃える。大きさは配置のときに決まる
                child.staticAreaW = child.staticAreaH = STATIC_AREA_IS_CB;
                return;
            }
            // 容器が包含ブロックでなければ、内容ボックスの中で揃える（ブラウザの実装と WPT の参照に合わせる）
            child.staticAreaW = LayoutReads.parentInnerW(parent);
            child.staticAreaH = LayoutReads.parentInnerH(parent);
            child.staticAlignX = selfFactor(selfValue(cs, true, ps), rtl, true, false);
            child.staticAlignY = selfFactor(selfValue(cs, false, ps), false, false, false);
            return;
        }
        String dir = ps.typed().flexGrid.flexDirection.cssName();
        boolean row = !dir.startsWith("column");
        boolean reverse = dir.endsWith("reverse");
        boolean wrapReverse = ps.typed().flexGrid.flexWrap.cssName().equals("wrap-reverse");
        child.staticAreaW = LayoutReads.parentInnerW(parent);
        child.staticAreaH = LayoutReads.parentInnerH(parent);
        String justify = ps.typed().flexGrid.justifyContent.cssName();
        // 項目が 1 つなら space-between は flex-start、space-around / space-evenly は center と同じ
        justify = switch (justify) {
            case "space-between" -> "flex-start";
            case "space-around", "space-evenly" -> "center";
            default -> justify;
        };
        float main = switch (justify) {
            case "center" -> 0.5f;
            case "flex-end" -> reverse ? 0 : 1;
            case "end" -> 1;
            case "right" -> row ? (rtl ? 0 : 1) : 0; // 下の rtl の裏返しを打ち消す（right は物理）
            case "left" -> row ? (rtl ? 1 : 0) : 0;
            case "flex-start", "normal", "stretch" -> reverse ? 1 : 0;
            default -> 0;
        };
        if (row && rtl) main = 1 - main;
        float cross = selfFactor(selfValue(cs, false, ps), false, false, wrapReverse);
        child.staticAlignX = row ? main : cross;
        child.staticAlignY = row ? cross : main;
    }

    /** {@link LayoutBox#staticAreaW} がこの値なら、静的位置の領域は包含ブロックそのもの。 */
    static final float STATIC_AREA_IS_CB = -1f;

    private static boolean isHorizontal(ComputedStyle s) {
        String wm = s.typed().inherited.writingMode;
        return wm == null || wm.isBlank() || norm(wm).equals("horizontal-tb");
    }

    private static boolean isRtl(ComputedStyle s) {
        return norm(s.typed().inherited.direction).equals("rtl");
    }

    /** 小文字にして前後の空白と {@code safe} / {@code unsafe} を除いた値（null は空）。 */
    private static String norm(String v) {
        if (v == null) return "";
        String t = v.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("safe ")) t = t.substring(5).trim();
        else if (t.startsWith("unsafe ")) t = t.substring(7).trim();
        return t;
    }

    private static String selfValue(ComputedStyle child, boolean inline, ComputedStyle parent) {
        var cfg = child.typed().flexGrid;
        var pfg = parent.typed().flexGrid;
        CssKeywords.Alignment kw = inline ? cfg.justifySelf : cfg.alignSelf;
        if (kw == CssKeywords.Alignment.AUTO) kw = inline ? pfg.justifyItems : pfg.alignItems;
        String v = kw.cssName();
        if (v.startsWith("legacy")) v = v.substring(6).trim();
        return v;
    }

    /**
     * 自分の揃え（{@code *-self}）を 0 / 0.5 / 1 にする。{@code inline} は横（インライン軸）で、rtl なら start / end を裏返す。
     * {@code flexReverse} は flex の交差軸が wrap-reverse で flex-start / flex-end を裏返すとき。
     */
    private static float selfFactor(String v, boolean rtl, boolean inline, boolean flexReverse) {
        float f = switch (v) {
            case "center" -> 0.5f;
            case "end", "self-end", "last baseline" -> 1;
            case "flex-end" -> flexReverse ? 0 : 1;
            case "flex-start" -> flexReverse ? 1 : 0;
            case "right" -> inline ? (rtl ? 0 : 1) : 0;
            case "left" -> inline ? (rtl ? 1 : 0) : 0;
            default -> 0; // normal / stretch / start / self-start / baseline
        };
        return inline && rtl ? 1 - f : f;
    }

    /**
     * 絶対配置・固定配置の箱の位置（および {@code anchor-size()} / {@code @position-try} /
     * {@code anchor()} 両端 inset による寸法）を決め、{@code position-try-fallbacks} があれば
     * 包含ブロックからはみ出さない候補を選ぶ。
     */
    static void placeAbsolute(LayoutBox box, LayoutBox.ContainingBlock cb,
                              float rootW, float rootH, LayoutContext ctx) {
        float opX = PlacementMemo.originParentX(box);
        float opY = PlacementMemo.originParentY(box);
        String fallbacks = box.style.typed().rare.positionTryFallbacks;
        if (fallbacks == null || fallbacks.isBlank() || "none".equalsIgnoreCase(fallbacks.trim())) {
            placeWithoutFallbacks(box, cb, rootW, rootH, opX, opY, ctx);
        } else {
            placeWithFallbacks(box, cb, rootW, rootH, opX, opY, fallbacks, ctx);
        }
        box.x -= opX;
        box.y -= opY;
    }

    private static final int SIDE_TOP = 0, SIDE_RIGHT = 1, SIDE_BOTTOM = 2, SIDE_LEFT = 3;

    private static void placeWithoutFallbacks(LayoutBox box, LayoutBox.ContainingBlock cb,
                                              float rootW, float rootH, float opX, float opY, LayoutContext ctx) {
        ComputedStyle style = box.style;
        Style tStyle = style.typed();
        StyleFacts facts = style.facts();
        float refX = cb.x(), refY = cb.y(), refW = cb.w(), refH = cb.h();
        float origW = box.w, origH = box.h;

        // width / height が anchor-size() ならアンカーの寸法に合わせる（アンカーが後順のときここで確定する）
        float aw = anchorSizePx(box, true, null);
        float ah = anchorSizePx(box, false, null);
        boolean isBorderBox = style.isBorderBox();
        if (aw >= 0) box.w = aw + (isBorderBox ? 0 : box.horizontalDecoration());
        if (ah >= 0) box.h = ah + (isBorderBox ? 0 : box.verticalDecoration());

        boolean leftAuto = CssLength.isAutoOrUndeclared(tStyle.box.left);
        boolean rightAuto = CssLength.isAutoOrUndeclared(tStyle.box.right);
        boolean topAuto = CssLength.isAutoOrUndeclared(tStyle.box.top);
        boolean bottomAuto = CssLength.isAutoOrUndeclared(tStyle.box.bottom);

        // width/height: auto で両端の inset が指定されていれば、包含ブロックの寸法から間の幅・高さを解く（CSS 2.2 §10.3.7・§10.6.4）。
        // 測定のときの基準は親の寸法で、包含ブロックが祖父母以上だと（親の高さが auto なら）高さが 0 になっていた
        if (facts.widthAuto && !leftAuto && !rightAuto) {
            float l = insetPx(box, SIDE_LEFT, refX, refW, rootW, rootH);
            float r = insetPx(box, SIDE_RIGHT, refX, refW, rootW, rootH);
            float w = Math.max(box.horizontalDecoration(), refW - l - r - box.mL - box.mR);
            box.w = applyMinMaxDecl(box, w, true, null, null, refW, rootW, rootH, isBorderBox);
        }
        if (facts.heightAuto && !topAuto && !bottomAuto) {
            float t = insetPx(box, SIDE_TOP, refY, refH, rootW, rootH);
            float b = insetPx(box, SIDE_BOTTOM, refY, refH, rootW, rootH);
            float h = Math.max(box.verticalDecoration(), refH - t - b - box.mT - box.mB);
            box.h = applyMinMaxDecl(box, h, false, null, null, refH, rootW, rootH, isBorderBox);
        }

        if (!box.children.isEmpty() && (box.w != origW || box.h != origH)) {
            remeasureAtSize(box, box.w, box.h, refW, refH, ctx, box.mT, box.mR, box.mB, box.mL);
        }

        if (!leftAuto) {
            float left = insetPx(box, SIDE_LEFT, refX, refW, rootW, rootH);
            float ml = box.mL;
            if ((facts.autoMarginL || facts.autoMarginR) && !rightAuto && !facts.widthAuto) {
                float right = insetPx(box, SIDE_RIGHT, refX, refW, rootW, rootH);
                float free = refW - left - right - box.w - (facts.autoMarginL ? 0 : box.mL) - (facts.autoMarginR ? 0 : box.mR);
                if (facts.autoMarginL && facts.autoMarginR) ml = Math.max(0, free / 2);
                else if (facts.autoMarginL) ml = Math.max(0, free);
            }
            box.x = refX + left + ml;
        } else if (!rightAuto) {
            box.x = refX + refW - box.w - insetPx(box, SIDE_RIGHT, refX, refW, rootW, rootH) - box.mR;
        } else {
            box.x = box.staticAreaW == STATIC_AREA_IS_CB ? refX + box.staticAlignX * (refW - (box.w + box.mL + box.mR)) + box.mL
                    : (Float.isNaN(box.staticX) ? refX : opX + box.staticX + box.staticOffsetX(box.w + box.mL + box.mR)) + box.mL;
        }

        if (!topAuto) {
            float top = insetPx(box, SIDE_TOP, refY, refH, rootW, rootH);
            float mt = box.mT;
            if ((facts.autoMarginT || facts.autoMarginB) && !bottomAuto && !facts.heightAuto) {
                float bottom = insetPx(box, SIDE_BOTTOM, refY, refH, rootW, rootH);
                float free = refH - top - bottom - box.h - (facts.autoMarginT ? 0 : box.mT) - (facts.autoMarginB ? 0 : box.mB);
                if (facts.autoMarginT && facts.autoMarginB) mt = Math.max(0, free / 2);
                else if (facts.autoMarginT) mt = Math.max(0, free);
            }
            box.y = refY + top + mt;
        } else if (!bottomAuto) {
            box.y = refY + refH - box.h - insetPx(box, SIDE_BOTTOM, refY, refH, rootW, rootH) - box.mB;
        } else {
            box.y = box.staticAreaH == STATIC_AREA_IS_CB ? refY + box.staticAlignY * (refH - (box.h + box.mT + box.mB)) + box.mT
                    : (Float.isNaN(box.staticY) ? refY : opY + box.staticY + box.staticOffsetY(box.h + box.mT + box.mB)) + box.mT;
        }

        String[] area = parsePositionArea(style.typed().rare.positionArea);
        if (area != null) {
            LayoutBox a = LayoutReads.findAnchor(box, null, null);
            if (a != null) {
                if (leftAuto && rightAuto) box.x = positionAreaX(area[0], a, box.w, box.mL, box.mR, box.x);
                if (topAuto && bottomAuto) box.y = positionAreaY(area[1], a, box.h, box.mT, box.mB, box.y);
            }
        }
    }

    private static void placeWithFallbacks(LayoutBox box, LayoutBox.ContainingBlock cb,
                                           float rootW, float rootH, float opX, float opY,
                                           String fallbacksStr, LayoutContext ctx) {
        ComputedStyle style = box.style;
        Style t = style.typed();
        StyleFacts facts = style.facts();
        float origW = box.w, origH = box.h;
        float origMT = box.mT, origMR = box.mR, origMB = box.mB, origML = box.mL;

        CandidateSpec base = new CandidateSpec(
                rawDeclOrAuto(t, t.box.left),
                rawDeclOrAuto(t, t.box.right),
                rawDeclOrAuto(t, t.box.top),
                rawDeclOrAuto(t, t.box.bottom),
                t.rare.positionAnchor,
                parsePositionArea(t.rare.positionArea),
                origMT, origMR, origMB, origML,
                facts.autoMarginT, facts.autoMarginR, facts.autoMarginB, facts.autoMarginL,
                null, null, null, null, null, null,
                false, false, false
        );

        Map<String, PositionTryRule> tryRules = LayoutReads.positionTryRules(box);
        List<CandidateSpec> candidates = parseFallbacks(fallbacksStr, base, tryRules, box, cb, rootW, rootH);

        PlacementResult baseRes = evaluate(box, base, cb, rootW, rootH, opX, opY, origW, origH, ctx);
        String order = style.typed().rare.positionTryOrder;
        String orderLower = order == null ? "normal" : order.trim().toLowerCase(Locale.ROOT);

        PlacementResult winner;
        if ("normal".equals(orderLower) || orderLower.isEmpty()) {
            if (baseRes.fits) {
                winner = baseRes;
            } else {
                PlacementResult chosen = null;
                for (int i = 0, n = candidates.size(); i < n; i++) {
                    PlacementResult res = evaluate(box, candidates.get(i), cb, rootW, rootH, opX, opY, origW, origH, ctx);
                    if (res.fits) {
                        chosen = res;
                        break;
                    }
                }
                winner = chosen != null ? chosen : baseRes;
            }
        } else {
            boolean horizontal = "most-width".equals(orderLower) || "most-inline-size".equals(orderLower);
            PlacementResult best = baseRes.fits ? baseRes : null;
            float bestSize = best == null ? -1f : (horizontal ? best.imcbW : best.imcbH);
            for (int i = 0, n = candidates.size(); i < n; i++) {
                PlacementResult res = evaluate(box, candidates.get(i), cb, rootW, rootH, opX, opY, origW, origH, ctx);
                if (!res.fits) continue;
                float sz = horizontal ? res.imcbW : res.imcbH;
                if (best == null || sz > bestSize) {
                    best = res;
                    bestSize = sz;
                }
            }
            winner = best != null ? best : baseRes;
        }

        box.w = winner.w;
        box.h = winner.h;
        if (!box.children.isEmpty() && (winner.w != origW || winner.h != origH)) {
            remeasureAtSize(box, winner.w, winner.h, cb.w(), cb.h(), ctx, winner.mT, winner.mR, winner.mB, winner.mL);
        }
        box.x = winner.x;
        box.y = winner.y;
        box.mT = winner.mT;
        box.mR = winner.mR;
        box.mB = winner.mB;
        box.mL = winner.mL;
    }

    private static PlacementResult evaluate(LayoutBox box, CandidateSpec spec, LayoutBox.ContainingBlock cb,
                                            float rootW, float rootH, float opX, float opY,
                                            float measuredW, float measuredH, LayoutContext ctx) {
        ComputedStyle style = box.style;
        StyleFacts facts = style.facts();
        boolean isBorderBox = style.isBorderBox();
        float refX = cb.x(), refY = cb.y(), refW = cb.w(), refH = cb.h();
        float fs = style.fontSizePx(), rfs = style.rootFontSizePx();
        float hDeco = box.horizontalDecoration(), vDeco = box.verticalDecoration();

        float ml = spec.mL, mr = spec.mR, mt = spec.mT, mb = spec.mB;
        boolean leftAuto = isAuto(spec.left), rightAuto = isAuto(spec.right);
        boolean topAuto = isAuto(spec.top), bottomAuto = isAuto(spec.bottom);

        boolean widthIsAuto = spec.widthDecl != null ? isAuto(spec.widthDecl) : facts.widthAuto;
        boolean heightIsAuto = spec.heightDecl != null ? isAuto(spec.heightDecl) : facts.heightAuto;

        float w = measuredW;
        if (spec.widthDecl != null) {
            if (isAuto(spec.widthDecl)) {
                if (!facts.widthAuto && !(!leftAuto && !rightAuto)) {
                    w = measureIntrinsicAutoDim(box, true, spec, refW, refH, ctx);
                }
            } else if (isAnchorSizeFn(spec.widthDecl)) {
                float aw = anchorSizePx(box, spec.widthDecl, true, spec.positionAnchor);
                if (aw >= 0) w = BoxMeasurer.toBorderBox(aw, hDeco, isBorderBox);
            } else {
                float len = LengthResolver.parseDim(spec.widthDecl, refW, rootW, rootH, fs, rfs);
                w = BoxMeasurer.toBorderBox(len, hDeco, isBorderBox);
            }
        } else {
            float aw = anchorSizePx(box, true, spec.positionAnchor);
            if (aw >= 0) w = BoxMeasurer.toBorderBox(aw, hDeco, isBorderBox);
        }

        float h = measuredH;
        if (spec.heightDecl != null) {
            if (isAuto(spec.heightDecl)) {
                if (!facts.heightAuto && !(!topAuto && !bottomAuto)) {
                    h = measureIntrinsicAutoDim(box, false, spec, refW, refH, ctx);
                }
            } else if (isAnchorSizeFn(spec.heightDecl)) {
                float ah = anchorSizePx(box, spec.heightDecl, false, spec.positionAnchor);
                if (ah >= 0) h = BoxMeasurer.toBorderBox(ah, vDeco, isBorderBox);
            } else {
                float len = LengthResolver.parseDim(spec.heightDecl, refH, rootW, rootH, fs, rfs);
                h = BoxMeasurer.toBorderBox(len, vDeco, isBorderBox);
            }
        } else {
            float ah = anchorSizePx(box, false, spec.positionAnchor);
            if (ah >= 0) h = BoxMeasurer.toBorderBox(ah, vDeco, isBorderBox);
        }

        w = applyMinMaxDecl(box, w, true, spec.minWidthDecl, spec.maxWidthDecl, refW, rootW, rootH, isBorderBox);
        h = applyMinMaxDecl(box, h, false, spec.minHeightDecl, spec.maxHeightDecl, refH, rootW, rootH, isBorderBox);

        float x, imcbW;
        if (!leftAuto) {
            float leftPx = resolveInsetPx(box, SIDE_LEFT, spec.left, spec.insetsOverridden,
                    refX, refW, rootW, rootH, spec.positionAnchor);
            if (!rightAuto) {
                float rightPx = resolveInsetPx(box, SIDE_RIGHT, spec.right, spec.insetsOverridden,
                        refX, refW, rootW, rootH, spec.positionAnchor);
                if (widthIsAuto && (spec.widthOverridden || spec.insetsOverridden || isAnchorFn(spec.left) || isAnchorFn(spec.right))) {
                    w = Math.max(hDeco, refW - leftPx - rightPx - ml - mr);
                    w = applyMinMaxDecl(box, w, true, spec.minWidthDecl, spec.maxWidthDecl, refW, rootW, rootH, isBorderBox);
                }
                imcbW = Math.max(0, refW - leftPx - rightPx);
                float effectiveMl = ml;
                if ((spec.autoMarginL || spec.autoMarginR) && !widthIsAuto) {
                    float free = refW - leftPx - rightPx - w - (spec.autoMarginL ? 0 : ml) - (spec.autoMarginR ? 0 : mr);
                    if (spec.autoMarginL && spec.autoMarginR) effectiveMl = Math.max(0, free / 2);
                    else if (spec.autoMarginL) effectiveMl = Math.max(0, free);
                }
                x = refX + leftPx + effectiveMl;
            } else {
                imcbW = Math.max(0, refW - leftPx);
                x = refX + leftPx + ml;
            }
        } else if (!rightAuto) {
            float rightPx = resolveInsetPx(box, SIDE_RIGHT, spec.right, spec.insetsOverridden,
                    refX, refW, rootW, rootH, spec.positionAnchor);
            imcbW = Math.max(0, refW - rightPx);
            x = refX + refW - w - rightPx - mr;
        } else {
            imcbW = refW;
            x = box.staticAreaW == STATIC_AREA_IS_CB ? refX + box.staticAlignX * (refW - (w + ml + mr)) + ml
                    : (Float.isNaN(box.staticX) ? refX : opX + box.staticX + box.staticOffsetX(w + ml + mr)) + ml;
        }

        float y, imcbH;
        if (!topAuto) {
            float topPx = resolveInsetPx(box, SIDE_TOP, spec.top, spec.insetsOverridden,
                    refY, refH, rootW, rootH, spec.positionAnchor);
            if (!bottomAuto) {
                float bottomPx = resolveInsetPx(box, SIDE_BOTTOM, spec.bottom, spec.insetsOverridden,
                        refY, refH, rootW, rootH, spec.positionAnchor);
                if (heightIsAuto && (spec.heightOverridden || spec.insetsOverridden || isAnchorFn(spec.top) || isAnchorFn(spec.bottom))) {
                    h = Math.max(vDeco, refH - topPx - bottomPx - mt - mb);
                    h = applyMinMaxDecl(box, h, false, spec.minHeightDecl, spec.maxHeightDecl, refH, rootW, rootH, isBorderBox);
                }
                imcbH = Math.max(0, refH - topPx - bottomPx);
                float effectiveMt = mt;
                if ((spec.autoMarginT || spec.autoMarginB) && !heightIsAuto) {
                    float free = refH - topPx - bottomPx - h - (spec.autoMarginT ? 0 : mt) - (spec.autoMarginB ? 0 : mb);
                    if (spec.autoMarginT && spec.autoMarginB) effectiveMt = Math.max(0, free / 2);
                    else if (spec.autoMarginT) effectiveMt = Math.max(0, free);
                }
                y = refY + topPx + effectiveMt;
            } else {
                imcbH = Math.max(0, refH - topPx);
                y = refY + topPx + mt;
            }
        } else if (!bottomAuto) {
            float bottomPx = resolveInsetPx(box, SIDE_BOTTOM, spec.bottom, spec.insetsOverridden,
                    refY, refH, rootW, rootH, spec.positionAnchor);
            imcbH = Math.max(0, refH - bottomPx);
            y = refY + refH - h - bottomPx - mb;
        } else {
            imcbH = refH;
            y = box.staticAreaH == STATIC_AREA_IS_CB ? refY + box.staticAlignY * (refH - (h + mt + mb)) + mt
                    : (Float.isNaN(box.staticY) ? refY : opY + box.staticY + box.staticOffsetY(h + mt + mb)) + mt;
        }

        if (spec.area != null) {
            LayoutBox a = LayoutReads.findAnchor(box, null, spec.positionAnchor);
            if (a != null) {
                if (leftAuto && rightAuto) {
                    x = positionAreaX(spec.area[0], a, w, ml, mr, x);
                    imcbW = areaAvailable(spec.area[0], LayoutReads.anchorX(a), LayoutReads.anchorW(a), refX, refW);
                }
                if (topAuto && bottomAuto) {
                    y = positionAreaY(spec.area[1], a, h, mt, mb, y);
                    imcbH = areaAvailable(spec.area[1], LayoutReads.anchorY(a), LayoutReads.anchorH(a), refY, refH);
                }
            }
        }

        float mbL = x - ml, mbR = x + w + mr, mbT = y - mt, mbB = y + h + mb;
        boolean fits = mbL >= refX - OVERFLOW_EPS && mbR <= refX + refW + OVERFLOW_EPS
                && mbT >= refY - OVERFLOW_EPS && mbB <= refY + refH + OVERFLOW_EPS;
        return new PlacementResult(x, y, w, h, mt, mr, mb, ml, imcbW, imcbH, fits);
    }

    // ---- フォールバック解析 & トライ戦術

    private static List<CandidateSpec> parseFallbacks(String fallbacksStr, CandidateSpec base,
                                                    Map<String, PositionTryRule> tryRules, LayoutBox box,
                                                    LayoutBox.ContainingBlock cb, float rootW, float rootH) {
        List<CandidateSpec> out = new ArrayList<>();
        for (String entry : splitCommaTopLevel(fallbacksStr)) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            String[] tokens = trimmed.split("\\s+");
            CandidateSpec spec = parseFallbackOption(tokens, base, tryRules, box, cb, rootW, rootH);
            if (spec != null) out.add(spec);
        }
        return out;
    }

    private static CandidateSpec parseFallbackOption(String[] tokens, CandidateSpec base,
                                                       Map<String, PositionTryRule> tryRules, LayoutBox box,
                                                       LayoutBox.ContainingBlock cb, float rootW, float rootH) {
        String ruleName = null;
        int tacticCount = 0;
        boolean allTryOrIdent = true;
        for (String tok : tokens) {
            if (tok.startsWith("--")) {
                if (ruleName != null) { allTryOrIdent = false; break; }
                ruleName = tok;
            } else if (isTryTactic(tok)) {
                tacticCount++;
            } else {
                allTryOrIdent = false;
                break;
            }
        }

        if (allTryOrIdent && (ruleName != null || tacticCount > 0)) {
            CandidateSpec spec = base.copy();
            if (ruleName != null) {
                PositionTryRule rule = tryRules != null ? tryRules.get(ruleName) : null;
                if (rule == null) return null; // 未定義の @position-try はこの候補を飛ばす
                applyRule(spec, rule, box.style, cb, rootW, rootH);
            }
            for (String tok : tokens) {
                switch (tok.toLowerCase(Locale.ROOT)) {
                    case "flip-block" -> applyFlipBlock(spec, box.style);
                    case "flip-inline" -> applyFlipInline(spec, box.style);
                    case "flip-start" -> applyFlipStart(spec, box.style);
                }
            }
            return spec;
        }

        // <position-area> 単体（1〜2 語）
        if (tokens.length <= 2) {
            String[] area = parsePositionAreaTokens(tokens);
            if (area != null) {
                CandidateSpec spec = base.copy();
                spec.area = area;
                return spec;
            }
        }
        return null;
    }

    private static boolean isTryTactic(String tok) {
        return switch (tok.toLowerCase(Locale.ROOT)) {
            case "flip-block", "flip-inline", "flip-start" -> true;
            default -> false;
        };
    }

    private static void applyRule(CandidateSpec spec, PositionTryRule rule, ComputedStyle baseStyle,
                                  LayoutBox.ContainingBlock cb, float rootW, float rootH) {
        float fs = baseStyle.fontSizePx(), rfs = baseStyle.rootFontSizePx();
        for (Map.Entry<String, String> e : rule.declarations().entrySet()) {
            String prop = e.getKey();
            String val = e.getValue();
            switch (prop) {
                case "left" -> { spec.left = val; spec.insetsOverridden = true; }
                case "right" -> { spec.right = val; spec.insetsOverridden = true; }
                case "top" -> { spec.top = val; spec.insetsOverridden = true; }
                case "bottom" -> { spec.bottom = val; spec.insetsOverridden = true; }
                case "margin-left" -> {
                    if ("auto".equalsIgnoreCase(val)) { spec.autoMarginL = true; spec.mL = 0; }
                    else { spec.autoMarginL = false; spec.mL = LayoutUnit.snap(LengthResolver.parseDim(val, cb.w(), rootW, rootH, fs, rfs)); }
                }
                case "margin-right" -> {
                    if ("auto".equalsIgnoreCase(val)) { spec.autoMarginR = true; spec.mR = 0; }
                    else { spec.autoMarginR = false; spec.mR = LayoutUnit.snap(LengthResolver.parseDim(val, cb.w(), rootW, rootH, fs, rfs)); }
                }
                case "margin-top" -> {
                    if ("auto".equalsIgnoreCase(val)) { spec.autoMarginT = true; spec.mT = 0; }
                    else { spec.autoMarginT = false; spec.mT = LayoutUnit.snap(LengthResolver.parseDim(val, cb.w(), rootW, rootH, fs, rfs)); }
                }
                case "margin-bottom" -> {
                    if ("auto".equalsIgnoreCase(val)) { spec.autoMarginB = true; spec.mB = 0; }
                    else { spec.autoMarginB = false; spec.mB = LayoutUnit.snap(LengthResolver.parseDim(val, cb.w(), rootW, rootH, fs, rfs)); }
                }
                case "width" -> { spec.widthDecl = val; spec.widthOverridden = true; }
                case "height" -> { spec.heightDecl = val; spec.heightOverridden = true; }
                case "min-width" -> spec.minWidthDecl = val;
                case "max-width" -> spec.maxWidthDecl = val;
                case "min-height" -> spec.minHeightDecl = val;
                case "max-height" -> spec.maxHeightDecl = val;
                case "position-anchor" -> spec.positionAnchor = val;
                case "position-area" -> spec.area = parsePositionArea(val);
            }
        }
    }

    private static void applyFlipBlock(CandidateSpec spec, ComputedStyle baseStyle) {
        String newTop = flipAnchorString(spec.bottom, FLIP_BLOCK);
        String newBottom = flipAnchorString(spec.top, FLIP_BLOCK);
        spec.top = newTop;
        spec.bottom = newBottom;
        spec.insetsOverridden = true;

        float tmpM = spec.mT; spec.mT = spec.mB; spec.mB = tmpM;
        boolean tmpAuto = spec.autoMarginT; spec.autoMarginT = spec.autoMarginB; spec.autoMarginB = tmpAuto;

        if (spec.area != null) {
            spec.area[1] = flipAreaToken(spec.area[1], FLIP_BLOCK);
        }
        // height / min-height / max-height 内の anchor-size() は向きを変えない（同軸の反転）
    }

    private static void applyFlipInline(CandidateSpec spec, ComputedStyle baseStyle) {
        String newLeft = flipAnchorString(spec.right, FLIP_INLINE);
        String newRight = flipAnchorString(spec.left, FLIP_INLINE);
        spec.left = newLeft;
        spec.right = newRight;
        spec.insetsOverridden = true;

        float tmpM = spec.mL; spec.mL = spec.mR; spec.mR = tmpM;
        boolean tmpAuto = spec.autoMarginL; spec.autoMarginL = spec.autoMarginR; spec.autoMarginR = tmpAuto;

        if (spec.area != null) {
            spec.area[0] = flipAreaToken(spec.area[0], FLIP_INLINE);
        }
    }

    private static void applyFlipStart(CandidateSpec spec, ComputedStyle baseStyle) {
        String oldTop = spec.top, oldRight = spec.right, oldBottom = spec.bottom, oldLeft = spec.left;
        spec.top = flipAnchorString(oldLeft, FLIP_START);
        spec.left = flipAnchorString(oldTop, FLIP_START);
        spec.bottom = flipAnchorString(oldRight, FLIP_START);
        spec.right = flipAnchorString(oldBottom, FLIP_START);
        spec.insetsOverridden = true;

        float tmpMT = spec.mT; spec.mT = spec.mL; spec.mL = tmpMT;
        float tmpMB = spec.mB; spec.mB = spec.mR; spec.mR = tmpMB;
        boolean tmpAT = spec.autoMarginT; spec.autoMarginT = spec.autoMarginL; spec.autoMarginL = tmpAT;
        boolean tmpAB = spec.autoMarginB; spec.autoMarginB = spec.autoMarginR; spec.autoMarginR = tmpAB;

        Style t = baseStyle.typed();
        String curW = spec.widthDecl != null ? spec.widthDecl : rawDeclOrAuto(t, t.box.width);
        String curH = spec.heightDecl != null ? spec.heightDecl : rawDeclOrAuto(t, t.box.height);
        spec.widthDecl = flipAnchorSizeString(curH);
        spec.heightDecl = flipAnchorSizeString(curW);
        spec.widthOverridden = true;
        spec.heightOverridden = true;

        String curMinW = spec.minWidthDecl != null ? spec.minWidthDecl : CssLength.toCssString(t.box.minWidth, t.box.exprs);
        String curMinH = spec.minHeightDecl != null ? spec.minHeightDecl : CssLength.toCssString(t.box.minHeight, t.box.exprs);
        spec.minWidthDecl = curMinH;
        spec.minHeightDecl = curMinW;

        String curMaxW = spec.maxWidthDecl != null ? spec.maxWidthDecl : CssLength.toCssString(t.box.maxWidth, t.box.exprs);
        String curMaxH = spec.maxHeightDecl != null ? spec.maxHeightDecl : CssLength.toCssString(t.box.maxHeight, t.box.exprs);
        spec.maxWidthDecl = curMaxH;
        spec.maxHeightDecl = curMaxW;

        if (spec.area != null) {
            String newX = flipAreaToken(spec.area[1], FLIP_START);
            String newY = flipAreaToken(spec.area[0], FLIP_START);
            spec.area[0] = newX;
            spec.area[1] = newY;
        }
    }

    private static String flipAnchorString(String raw, int flipKind) {
        if (raw == null || !raw.regionMatches(true, 0, "anchor(", 0, 7)) return raw;
        int close = raw.lastIndexOf(')');
        if (close < 0) return raw;
        String[] parts = raw.substring(7, close).trim().split("[\\s,]+");
        String name = null, side = null;
        for (String part : parts) {
            if (part.startsWith("--")) name = part;
            else if (!part.isEmpty() && side == null) side = part.toLowerCase(Locale.ROOT);
        }
        if (side == null) return raw;
        String flipped = switch (flipKind) {
            case FLIP_BLOCK -> switch (side) {
                case "top" -> "bottom";
                case "bottom" -> "top";
                case "start" -> "end";
                case "end" -> "start";
                case "self-start" -> "self-end";
                case "self-end" -> "self-start";
                default -> flipPercentSide(side);
            };
            case FLIP_INLINE -> switch (side) {
                case "left" -> "right";
                case "right" -> "left";
                case "start" -> "end";
                case "end" -> "start";
                case "self-start" -> "self-end";
                case "self-end" -> "self-start";
                default -> flipPercentSide(side);
            };
            default -> switch (side) { // FLIP_START
                case "top" -> "left";
                case "left" -> "top";
                case "bottom" -> "right";
                case "right" -> "bottom";
                default -> side;
            };
        };
        return "anchor(" + (name != null ? name + " " : "") + flipped + ")";
    }

    private static String flipPercentSide(String side) {
        if (!side.endsWith("%")) return side;
        try {
            float p = 100f - Float.parseFloat(side.substring(0, side.length() - 1));
            return (p == Math.rint(p) ? Integer.toString((int) p) : Float.toString(p)) + "%";
        } catch (NumberFormatException e) {
            return side;
        }
    }

    private static String flipAnchorSizeString(String raw) {
        if (raw == null || !raw.regionMatches(true, 0, "anchor-size(", 0, 12)) return raw;
        int close = raw.lastIndexOf(')');
        if (close < 0) return raw;
        String[] parts = raw.substring(12, close).trim().split("[\\s,]+");
        String name = null, dim = null;
        for (String part : parts) {
            if (part.startsWith("--")) name = part;
            else if (!part.isEmpty() && dim == null) dim = part.toLowerCase(Locale.ROOT);
        }
        if (dim == null) return raw;
        String flipped = switch (dim) {
            case "width" -> "height";
            case "height" -> "width";
            case "inline" -> "block";
            case "block" -> "inline";
            default -> dim;
        };
        return "anchor-size(" + (name != null ? name + " " : "") + flipped + ")";
    }

    private static String flipAreaToken(String tok, int flipKind) {
        if (tok == null) return null;
        if (flipKind == FLIP_START) {
            return swapToken(tok, "left", "top", "right", "bottom",
                    "span-left", "span-top", "span-right", "span-bottom",
                    "x-start", "y-start", "x-end", "y-end",
                    "span-x-start", "span-y-start", "span-x-end", "span-y-end",
                    "x-self-start", "y-self-start", "x-self-end", "y-self-end");
        }
        String r = flipKind == FLIP_BLOCK
                ? swapToken(tok, "top", "bottom", "span-top", "span-bottom", "y-start", "y-end", "span-y-start", "span-y-end", "y-self-start", "y-self-end")
                : swapToken(tok, "left", "right", "span-left", "span-right", "x-start", "x-end", "span-x-start", "span-x-end", "x-self-start", "x-self-end");
        return !r.equals(tok) ? r
                : swapToken(tok, "start", "end", "span-start", "span-end", "self-start", "self-end", "span-self-start", "span-self-end");
    }

    private static String swapToken(String tok, String... pairs) {
        for (int i = 0; i < pairs.length; i += 2) {
            if (tok.equals(pairs[i])) return pairs[i + 1];
            if (tok.equals(pairs[i + 1])) return pairs[i];
        }
        return tok;
    }

    // ---- anchor() / anchor-size() / position-area プリミティブ

    private static long insetLen(Style t, int side) {
        return switch (side) {
            case SIDE_TOP -> t.box.top;
            case SIDE_RIGHT -> t.box.right;
            case SIDE_BOTTOM -> t.box.bottom;
            default -> t.box.left;
        };
    }

    /**
     * 絶対配置の inset（{@code left} / {@code right} / {@code top} / {@code bottom}）を px にする。
     * 値が {@code anchor(...)} ならアンカーの辺の座標から求める。
     */
    static float insetPx(LayoutBox box, int side, float cbOrigin, float cbLen, float rootW, float rootH) {
        Style t = box.style.typed();
        long len = insetLen(t, side);
        if (CssLength.isCalc(len)) {
            String raw = t.box.exprs.raw(len);
            if (isAnchorFn(raw)) return resolveAnchorInset(box, raw, side, cbOrigin, cbLen, null);
        }
        return t.boxPx(len, cbLen, rootW, rootH);
    }

    private static float resolveInsetPx(LayoutBox box, int side, String raw, boolean overridden,
                                        float cbOrigin, float cbLen, float rootW, float rootH,
                                        String overridePositionAnchor) {
        if (isAnchorFn(raw)) return resolveAnchorInset(box, raw, side, cbOrigin, cbLen, overridePositionAnchor);
        if (!overridden) {
            Style t = box.style.typed();
            return t.boxPx(insetLen(t, side), cbLen, rootW, rootH);
        }
        return LengthResolver.parseDim(raw, cbLen, rootW, rootH, box.style.fontSizePx(), box.style.rootFontSizePx());
    }

    private static float resolveAnchorInset(LayoutBox box, String raw, int sideCode,
                                            float cbOrigin, float cbLen, String overridePositionAnchor) {
        int close = raw.lastIndexOf(')');
        if (close < 0) return 0;
        String[] parts = raw.substring(7, close).trim().split("[\\s,]+");
        String name = null, side = null;
        for (String part : parts) {
            if (part.startsWith("--")) name = part;
            else if (!part.isEmpty() && side == null) side = part.toLowerCase(Locale.ROOT);
        }
        if (side == null) return 0;
        LayoutBox anchor = LayoutReads.findAnchor(box, name, overridePositionAnchor);
        if (anchor == null) return 0;
        boolean horizontal = sideCode == SIDE_LEFT || sideCode == SIDE_RIGHT;
        float from = horizontal ? LayoutReads.anchorX(anchor) : LayoutReads.anchorY(anchor);
        float size = horizontal ? LayoutReads.anchorW(anchor) : LayoutReads.anchorH(anchor);
        float coord;
        switch (side) {
            case "left", "top", "start", "self-start" -> coord = from;
            case "right", "bottom", "end", "self-end" -> coord = from + size;
            case "center" -> coord = from + size / 2f;
            default -> {
                if (!side.endsWith("%")) return 0;
                try {
                    coord = from + size * Float.parseFloat(side.substring(0, side.length() - 1)) / 100f;
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        boolean fromStart = sideCode == SIDE_LEFT || sideCode == SIDE_TOP;
        return fromStart ? coord - cbOrigin : cbOrigin + cbLen - coord;
    }

    static float anchorSizePx(LayoutBox box, boolean horizontal, String overridePositionAnchor) {
        Style t = box.style.typed();
        long len = horizontal ? t.box.width : t.box.height;
        if (!CssLength.isCalc(len)) return -1;
        return anchorSizePx(box, t.box.exprs.raw(len), horizontal, overridePositionAnchor);
    }

    static float anchorSizePx(LayoutBox box, String raw, boolean horizontal, String overridePositionAnchor) {
        if (!isAnchorSizeFn(raw)) return -1;
        int close = raw.lastIndexOf(')');
        if (close < 0) return -1;
        String name = null, dim = null;
        for (String part : raw.substring(12, close).trim().split("[\\s,]+")) {
            if (part.startsWith("--")) name = part;
            else if (!part.isEmpty() && dim == null) dim = part.toLowerCase(Locale.ROOT);
        }
        LayoutBox anchor = LayoutReads.findAnchor(box, name, overridePositionAnchor);
        if (anchor == null) return -1;
        if (dim == null) dim = horizontal ? "width" : "height";
        return switch (dim) {
            case "width", "inline" -> LayoutReads.anchorW(anchor);
            case "height", "block" -> LayoutReads.anchorH(anchor);
            default -> -1;
        };
    }

    static String[] parsePositionArea(String area) {
        if (area == null || area.isBlank() || "none".equalsIgnoreCase(area.trim())) return null;
        String[] parts = area.trim().toLowerCase(Locale.ROOT).split("\\s+");
        return parsePositionAreaTokens(parts);
    }

    private static String[] parsePositionAreaTokens(String[] parts) {
        if (parts.length == 0 || parts.length > 2) return null;
        String xTok = null, yTok = null;
        if (parts.length == 1) {
            String t = parts[0].toLowerCase(Locale.ROOT);
            if (isAreaX(t)) { xTok = t; yTok = "span-all"; }
            else if (isAreaY(t)) { yTok = t; xTok = "span-all"; }
            else if (isAreaNeutral(t)) { yTok = t; xTok = t; }
            else return null;
        } else {
            String t0 = parts[0].toLowerCase(Locale.ROOT), t1 = parts[1].toLowerCase(Locale.ROOT);
            if ((isAreaX(t0) && (isAreaY(t1) || isAreaNeutral(t1)))) { xTok = t0; yTok = t1; }
            else if ((isAreaY(t0) && (isAreaX(t1) || isAreaNeutral(t1)))) { yTok = t0; xTok = t1; }
            else if ((isAreaNeutral(t0) && isAreaX(t1))) { yTok = t0; xTok = t1; }
            else if ((isAreaNeutral(t0) && isAreaY(t1))) { xTok = t0; yTok = t1; }
            else if (isAreaNeutral(t0) && isAreaNeutral(t1)) { yTok = t0; xTok = t1; }
            else return null;
        }
        return new String[]{xTok, yTok};
    }

    private static float positionAreaX(String xTok, LayoutBox a, float w, float mL, float mR, float fallback) {
        float ax = LayoutReads.anchorX(a), aw = LayoutReads.anchorW(a);
        return switch (xTok) {
            case "left", "x-start", "x-self-start", "start", "self-start" -> ax - w - mR;
            case "right", "x-end", "x-self-end", "end", "self-end" -> ax + aw + mL;
            case "span-left", "span-x-start", "span-start", "span-self-start" -> ax + aw - w - mR;
            case "span-right", "span-x-end", "span-end", "span-self-end" -> ax + mL;
            case "center", "span-all" -> ax + (aw - w) / 2f;
            default -> fallback;
        };
    }

    private static float positionAreaY(String yTok, LayoutBox a, float h, float mT, float mB, float fallback) {
        float ay = LayoutReads.anchorY(a), ah = LayoutReads.anchorH(a);
        return switch (yTok) {
            case "top", "y-start", "y-self-start", "start", "self-start" -> ay - h - mB;
            case "bottom", "y-end", "y-self-end", "end", "self-end" -> ay + ah + mT;
            case "span-top", "span-y-start", "span-start", "span-self-start" -> ay + ah - h - mB;
            case "span-bottom", "span-y-end", "span-end", "span-self-end" -> ay + mT;
            case "center", "span-all" -> ay + (ah - h) / 2f;
            default -> fallback;
        };
    }

    private static float areaAvailable(String tok, float aStart, float aLen, float cbStart, float cbLen) {
        float cbEnd = cbStart + cbLen, aEnd = aStart + aLen;
        return switch (tok) {
            case "left", "top", "x-start", "y-start", "x-self-start", "y-self-start", "start", "self-start" ->
                    Math.max(0, Math.min(aStart, cbEnd) - cbStart);
            case "right", "bottom", "x-end", "y-end", "x-self-end", "y-self-end", "end", "self-end" ->
                    Math.max(0, cbEnd - Math.max(aEnd, cbStart));
            case "span-left", "span-top", "span-x-start", "span-y-start", "span-start", "span-self-start" ->
                    Math.max(0, Math.min(aEnd, cbEnd) - cbStart);
            case "span-right", "span-bottom", "span-x-end", "span-y-end", "span-end", "span-self-end" ->
                    Math.max(0, cbEnd - Math.max(aStart, cbStart));
            case "center" ->
                    Math.max(0, Math.min(aEnd, cbEnd) - Math.max(aStart, cbStart));
            default -> cbLen;
        };
    }

    private static boolean isAreaX(String t) {
        return switch (t) {
            case "left", "right", "span-left", "span-right",
                 "x-start", "x-end", "x-self-start", "x-self-end",
                 "span-x-start", "span-x-end" -> true;
            default -> false;
        };
    }

    private static boolean isAreaY(String t) {
        return switch (t) {
            case "top", "bottom", "span-top", "span-bottom",
                 "y-start", "y-end", "y-self-start", "y-self-end",
                 "span-y-start", "span-y-end" -> true;
            default -> false;
        };
    }

    private static boolean isAreaNeutral(String t) {
        return switch (t) {
            case "center", "span-all", "start", "end", "self-start", "self-end",
                 "span-start", "span-end", "span-self-start", "span-self-end" -> true;
            default -> false;
        };
    }

    // ---- 寸法補助

    private static float resolveLimit(Style t, String override, long len, boolean isMax,
                                      float cbLen, float rootW, float rootH, float fs, float rfs) {
        if (override != null) {
            String trimmed = override.trim();
            String ignoreKw = isMax ? "none" : "auto";
            if (trimmed.isEmpty() || ignoreKw.equalsIgnoreCase(trimmed) || LayoutMetrics.isIntrinsicSizeKeyword(trimmed)) return Float.NaN;
            return LengthResolver.parseDim(trimmed, cbLen, rootW, rootH, fs, rfs);
        }
        boolean ignored = !CssLength.isDeclared(len)
                || (isMax ? CssLength.isKeyword(len, CssLength.KW_NONE) : CssLength.isAuto(len))
                || CssLength.isIntrinsicSize(len, t.box.exprs);
        return ignored ? Float.NaN : t.boxPx(len, cbLen, rootW, rootH);
    }

    private static float applyMinMaxDecl(LayoutBox box, float val, boolean horizontal,
                                         String minOverride, String maxOverride,
                                         float cbLen, float rootW, float rootH, boolean isBorderBox) {
        ComputedStyle style = box.style;
        Style t = style.typed();
        float deco = horizontal ? box.horizontalDecoration() : box.verticalDecoration();
        float fs = style.fontSizePx(), rfs = style.rootFontSizePx();
        float rawMax = resolveLimit(t, maxOverride, horizontal ? t.box.maxWidth : t.box.maxHeight, true, cbLen, rootW, rootH, fs, rfs);
        if (!Float.isNaN(rawMax)) {
            float maxV = BoxMeasurer.toBorderBox(rawMax, deco, isBorderBox);
            if (maxV > 0) val = Math.min(val, maxV);
        }
        float rawMin = resolveLimit(t, minOverride, horizontal ? t.box.minWidth : t.box.minHeight, false, cbLen, rootW, rootH, fs, rfs);
        if (!Float.isNaN(rawMin)) val = Math.max(val, BoxMeasurer.toBorderBox(rawMin, deco, isBorderBox));
        return val;
    }

    private static void remeasureAtSize(LayoutBox box, float w, float h, float cbW, float cbH, LayoutContext ctx,
                                        float mT, float mR, float mB, float mL) {
        ConstraintSpace space = ConstraintSpace.reuseOrCreate(null, cbW, cbH, FormattingContext.BLOCK);
        box.overrideWidth = w;
        box.overrideHeight = h;
        try {
            BoxMeasurer.measure(box, space, ctx);
        } finally {
            box.overrideWidth = Float.NaN;
            box.overrideHeight = Float.NaN;
        }
        box.mT = mT;
        box.mR = mR;
        box.mB = mB;
        box.mL = mL;
    }

    private static float measureIntrinsicAutoDim(LayoutBox box, boolean horizontal, CandidateSpec spec,
                                                 float cbW, float cbH, LayoutContext ctx) {
        ComputedStyle prevStyle = box.style;
        float prevW = box.w, prevH = box.h;
        float prevMT = box.mT, prevMR = box.mR, prevMB = box.mB, prevML = box.mL;
        ComputedStyle tmp = prevStyle.withSizingOverrides(spec.widthDecl, spec.heightDecl,
                spec.minWidthDecl, spec.maxWidthDecl, spec.minHeightDecl, spec.maxHeightDecl);
        ConstraintSpace space = ConstraintSpace.reuseOrCreate(null, cbW, cbH, FormattingContext.BLOCK);
        box.style = tmp;
        try {
            BoxMeasurer.measure(box, space, ctx);
            return horizontal ? box.w : box.h;
        } finally {
            box.style = prevStyle;
            box.w = prevW;
            box.h = prevH;
            box.mT = prevMT;
            box.mR = prevMR;
            box.mB = prevMB;
            box.mL = prevML;
        }
    }

    private static List<String> splitCommaTopLevel(String s) {
        List<String> out = new ArrayList<>(4);
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && depth > 0) depth--;
            else if (c == ',' && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    private static String rawDeclOrAuto(Style t, long len) {
        String v = CssLength.toCssString(len, t.box.exprs);
        return v == null || v.isBlank() ? "auto" : v.trim();
    }

    private static boolean isAuto(String v) {
        return v == null || v.isBlank() || "auto".equalsIgnoreCase(v.trim());
    }

    private static boolean isAnchorFn(String v) {
        return v != null && v.regionMatches(true, 0, "anchor(", 0, 7);
    }

    private static boolean isAnchorSizeFn(String v) {
        return v != null && v.regionMatches(true, 0, "anchor-size(", 0, 12);
    }

    private static final class CandidateSpec {
        String left, right, top, bottom;
        String positionAnchor;
        String[] area;
        float mT, mR, mB, mL;
        boolean autoMarginT, autoMarginR, autoMarginB, autoMarginL;
        String widthDecl, heightDecl, minWidthDecl, maxWidthDecl, minHeightDecl, maxHeightDecl;
        boolean insetsOverridden, widthOverridden, heightOverridden;

        CandidateSpec(String left, String right, String top, String bottom,
                      String positionAnchor, String[] area,
                      float mT, float mR, float mB, float mL,
                      boolean autoMarginT, boolean autoMarginR, boolean autoMarginB, boolean autoMarginL,
                      String widthDecl, String heightDecl, String minWidthDecl, String maxWidthDecl,
                      String minHeightDecl, String maxHeightDecl,
                      boolean insetsOverridden, boolean widthOverridden, boolean heightOverridden) {
            this.left = left;
            this.right = right;
            this.top = top;
            this.bottom = bottom;
            this.positionAnchor = positionAnchor;
            this.area = area;
            this.mT = mT;
            this.mR = mR;
            this.mB = mB;
            this.mL = mL;
            this.autoMarginT = autoMarginT;
            this.autoMarginR = autoMarginR;
            this.autoMarginB = autoMarginB;
            this.autoMarginL = autoMarginL;
            this.widthDecl = widthDecl;
            this.heightDecl = heightDecl;
            this.minWidthDecl = minWidthDecl;
            this.maxWidthDecl = maxWidthDecl;
            this.minHeightDecl = minHeightDecl;
            this.maxHeightDecl = maxHeightDecl;
            this.insetsOverridden = insetsOverridden;
            this.widthOverridden = widthOverridden;
            this.heightOverridden = heightOverridden;
        }

        CandidateSpec copy() {
            return new CandidateSpec(
                    left, right, top, bottom,
                    positionAnchor, area != null ? new String[]{area[0], area[1]} : null,
                    mT, mR, mB, mL,
                    autoMarginT, autoMarginR, autoMarginB, autoMarginL,
                    widthDecl, heightDecl, minWidthDecl, maxWidthDecl, minHeightDecl, maxHeightDecl,
                    insetsOverridden, widthOverridden, heightOverridden);
        }
    }

    private record PlacementResult(
            float x, float y, float w, float h,
            float mT, float mR, float mB, float mL,
            float imcbW, float imcbH, boolean fits) {}
}
