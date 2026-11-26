package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.box.RememberedScroll;
import com.nishiyu.enderframelib.core.css.CssLength;
import com.nishiyu.enderframelib.core.css.LengthResolver;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;

import java.util.List;
import java.util.Locale;

/**
 * アンカー位置指定（Engine v3 M2b-5。旧 {@code AnchorPositioning.placeAbsolute} の移植。CSS Anchor Positioning 1）。{@code anchor()}・
 * {@code anchor-size()}・{@code position-area}・{@code position-try-fallbacks}（{@code @position-try}・反転・{@code position-try-order}）を
 * 使う流れの外の箱を、包含ブロックの中のアンカー（{@link Anchors}）に合わせて置く。
 *
 * <ul>
 *   <li>まず箱の自然な大きさ（指定・両側の inset の間・縮小して合わせた幅、内容の高さ）を測り、候補ごとに大きさと位置を決める。</li>
 *   <li>候補は基本の位置と {@code position-try-fallbacks} の項。{@code normal} は基本の位置が包含ブロックに収まればそれ、収まらなければ
 *       収まる最初の候補（無ければ基本の位置）。{@code most-width} / {@code most-height} などは収まる候補のうち inset で縮めた包含ブロック
 *       が最も広いもの。</li>
 *   <li>アンカーが見つからない {@code anchor()} は 0（旧経路と同じ）。</li>
 * </ul>
 *
 * <p>旧経路と違う点: アンカーは包含ブロックの中身からだけ探す（{@link Anchors}）。スクロールの量はレイアウトの入力にしない
 * （仕様 §3.3）: アンカーはスクロールコンテナが初期位置にあるものとして探し、箱の覚えたスクロール量（{@link BoxNode#anchorScroll}）
 * だけずらす。スクロールへの追従と候補の選び直しの入力は {@link AnchorScroll} として出し、描画とビューが使う（G8）。</p>
 */
final class AnchorPlacement {
    private static final float OVERFLOW_EPS = 0.05f;
    private static final int TOP = 0, RIGHT = 1, BOTTOM = 2, LEFT = 3;

    private AnchorPlacement() { }

    /** この箱の配置にアンカーが関わるか。 */
    static boolean applies(Style st) {
        if (!none(st.rare.positionArea) || !none(st.rare.positionTryFallbacks)) return true;
        return AnchorTry.isAnchorFn(raw(st, st.box.left)) || AnchorTry.isAnchorFn(raw(st, st.box.right))
                || AnchorTry.isAnchorFn(raw(st, st.box.top)) || AnchorTry.isAnchorFn(raw(st, st.box.bottom))
                || AnchorTry.isAnchorSizeFn(raw(st, st.box.width)) || AnchorTry.isAnchorSizeFn(raw(st, st.box.height));
    }

    /** 位置を決めた 1 つの候補。{@code imcbW} / {@code imcbH} は inset で縮めた包含ブロックの寸法。 */
    private record Result(float x, float y, float w, float h, float imcbW, float imcbH, boolean fits, AnchorTry.Spec spec) { }

    /** 1 回の配置の文脈（箱・包含ブロック・アンカー）。 */
    private record Ctx(BoxNode box, Style st, BoxGeometry g, Anchors anchors, OutOfFlow.Pending p,
                       float cbX, float cbY, float cbW, float cbH, LayoutSession s, java.util.Set<BoxNode> seen) { }

    /** 流れの外の箱 {@code p} を、包含ブロック（border-box 基準の {@code (cbX, cbY)} から {@code cbW} × {@code cbH}）に置く。 */
    static PlacedFragment place(OutOfFlow.Pending p, float cbX, float cbY, float cbW, float cbH, LayoutFragment prior, Anchors anchors,
                                LayoutSession s) {
        BoxNode box = p.box();
        Style st = box.style;
        BoxGeometry g = new BoxGeometry(st, cbW, s);
        Ctx c = new Ctx(box, st, g, anchors, p, cbX, cbY, cbW, cbH, s, new java.util.LinkedHashSet<>());
        float mw = naturalWidth(c);
        float mh = naturalHeight(c, mw, prior);
        AnchorTry.Spec base = AnchorTry.base(st, g);
        Result win = evaluate(c, base, mw, mh, prior);
        String fb = st.rare.positionTryFallbacks;
        if (!none(fb)) {
            List<AnchorTry.Spec> cands = AnchorTry.fallbacks(fb, base, s.positionTryRules(), st, cbW, s);
            String order = st.rare.positionTryOrder == null ? "normal" : st.rare.positionTryOrder.trim().toLowerCase(Locale.ROOT);
            if (order.isEmpty() || order.equals("normal")) {
                if (!win.fits) {
                    for (AnchorTry.Spec spec : cands) {
                        Result r = evaluate(c, spec, mw, mh, prior);
                        if (r.fits) {
                            win = r;
                            break;
                        }
                    }
                }
            } else {
                boolean horizontal = order.equals("most-width") || order.equals("most-inline-size");
                Result best = win.fits ? win : null;
                for (AnchorTry.Spec spec : cands) {
                    Result r = evaluate(c, spec, mw, mh, prior);
                    if (!r.fits) continue;
                    if (best == null || (horizontal ? r.imcbW > best.imcbW : r.imcbH > best.imcbH)) best = r;
                }
                if (best != null) win = best;
            }
        }
        float w = LayoutUnit.snap(Math.max(0, win.w)), h = LayoutUnit.snap(Math.max(0, win.h));
        LayoutFragment f = s.cache().layout(box, s.space(cbW, w, h, cbH, ConstraintSpace.FORMATTING_ROOT), OutOfFlow.priorOf(prior, box), s);
        AnchorTry.Spec sp = win.spec;
        boolean flipped = sp != null && (sp.mT != g.mT || sp.mR != g.mR || sp.mB != g.mB || sp.mL != g.mL);
        float x = LayoutUnit.snap(win.x), y = LayoutUnit.snap(win.y);
        return new PlacedFragment(f, x, y, flipped ? new float[]{sp.mT, sp.mR, sp.mB, sp.mL} : null,
                follow(c, sp, x, y, f.width, f.height, !none(fb)));
    }

    /**
     * スクロールへの追従の入力（G8。参照したアンカーがどれもスクロールコンテナの中に無ければ null）。{@code x}, {@code y}, {@code w},
     * {@code h} は置いた border-box。
     */
    private static AnchorScroll follow(Ctx c, AnchorTry.Spec sp, float x, float y, float w, float h, boolean retries) {
        if (c.seen.isEmpty() || sp == null) return null;
        Anchors.Hit dh = hit(c, null, sp.positionAnchor);
        BoxNode[] chain = dh != null ? dh.scrollers() : new BoxNode[0];
        boolean fx = chain.length > 0 && (sp.area != null || AnchorTry.isAnchorFn(sp.left) || AnchorTry.isAnchorFn(sp.right));
        boolean fy = chain.length > 0 && (sp.area != null || AnchorTry.isAnchorFn(sp.top) || AnchorTry.isAnchorFn(sp.bottom));
        float[] used = {0, 0};
        RememberedScroll mem = c.box.anchorScroll;
        if (mem != null) for (BoxNode b : chain) mem.addTo(b.node, used);
        com.nishiyu.enderframelib.core.dom.node.HtmlNode[] all = new com.nishiyu.enderframelib.core.dom.node.HtmlNode[c.seen.size()];
        int i = 0;
        for (BoxNode b : c.seen) all[i++] = b.node;
        com.nishiyu.enderframelib.core.dom.node.HtmlNode[] followed = new com.nishiyu.enderframelib.core.dom.node.HtmlNode[chain.length];
        for (int k = 0; k < chain.length; k++) followed[k] = chain[k].node;
        return new AnchorScroll(all, followed, fx, fy, used[0], used[1],
                new float[]{x - sp.mL, y - sp.mT, x + w + sp.mR, y + h + sp.mB},
                new float[]{c.cbX, c.cbY, c.cbX + c.cbW, c.cbY + c.cbH}, retries);
    }

    // ---- 自然な大きさ（border-box）

    private static float naturalWidth(Ctx c) {
        Style st = c.st;
        BoxGeometry g = c.g;
        float l = numericInset(c, LEFT), r = numericInset(c, RIGHT);
        float avail = c.cbW - (Float.isNaN(l) ? 0 : l) - (Float.isNaN(r) ? 0 : r) - g.mL - g.mR;
        float w = AnchorTry.isAnchorSizeFn(raw(st, st.box.width)) ? Float.NaN : g.specifiedWidth(c.box, c.cbW, avail, c.s);
        if (Float.isNaN(w)) {
            if (!Float.isNaN(l) && !Float.isNaN(r)) {
                w = c.cbW - l - r - g.mL - g.mR;
            } else {
                MinMaxSizes mm = c.s.cache().minMax(c.box, c.s);
                w = Math.min(Math.max(mm.min, avail), mm.max);
            }
        }
        return LayoutUnit.snap(g.clampWidth(st, Math.max(0, w), c.cbW, c.s));
    }

    private static float naturalHeight(Ctx c, float w, LayoutFragment prior) {
        Style st = c.st;
        BoxGeometry g = c.g;
        float h = AnchorTry.isAnchorSizeFn(raw(st, st.box.height)) ? Float.NaN : g.specifiedHeight(st, c.cbH, c.s);
        if (Float.isNaN(h)) {
            float t = numericInset(c, TOP), b = numericInset(c, BOTTOM);
            if (!Float.isNaN(t) && !Float.isNaN(b)) {
                h = c.cbH - t - b - g.mT - g.mB;
            } else {
                h = c.s.cache().layout(c.box, c.s.space(c.cbW, w, Float.NaN, c.cbH, ConstraintSpace.FORMATTING_ROOT),
                        OutOfFlow.priorOf(prior, c.box), c.s).height;
            }
        }
        return LayoutUnit.snap(g.clampHeight(st, Math.max(0, h), c.cbH, c.s));
    }

    /** {@code anchor()} でない inset の px（auto・{@code anchor()} は NaN）。 */
    private static float numericInset(Ctx c, int side) {
        long v = insetLen(c.st, side);
        if (CssLength.isAutoOrUndeclared(v) || AnchorTry.isAnchorFn(raw(c.st, v))) return Float.NaN;
        boolean h = side == LEFT || side == RIGHT;
        return c.st.boxPx(v, h ? c.cbW : c.cbH, c.s.viewportW(), c.s.viewportH());
    }

    // ---- 候補の評価（旧 AnchorPositioning.evaluate）

    private static Result evaluate(Ctx c, AnchorTry.Spec spec, float measuredW, float measuredH, LayoutFragment prior) {
        Style st = c.st;
        BoxGeometry g = c.g;
        boolean borderBox = st.facts.borderBox;
        float refX = c.cbX, refY = c.cbY, refW = c.cbW, refH = c.cbH;
        float hDeco = g.hdec(), vDeco = g.vdec();
        float ml = spec.mL, mr = spec.mR, mt = spec.mT, mb = spec.mB;
        boolean leftAuto = AnchorTry.auto(spec.left), rightAuto = AnchorTry.auto(spec.right);
        boolean topAuto = AnchorTry.auto(spec.top), bottomAuto = AnchorTry.auto(spec.bottom);
        boolean widthIsAuto = spec.widthDecl != null ? AnchorTry.auto(spec.widthDecl) : st.facts.widthAuto;
        boolean heightIsAuto = spec.heightDecl != null ? AnchorTry.auto(spec.heightDecl) : st.facts.heightAuto;

        float w = size(c, spec.widthDecl, true, measuredW, spec, leftAuto, rightAuto, prior);
        float h = size(c, spec.heightDecl, false, measuredH, spec, topAuto, bottomAuto, prior);
        w = minMax(c, w, true, spec.minWidthDecl, spec.maxWidthDecl, refW, borderBox);
        h = minMax(c, h, false, spec.minHeightDecl, spec.maxHeightDecl, refH, borderBox);

        float x, imcbW;
        if (!leftAuto) {
            float left = insetPx(c, LEFT, spec.left, spec.insetsOverridden, refX, refW, spec.positionAnchor);
            if (!rightAuto) {
                float right = insetPx(c, RIGHT, spec.right, spec.insetsOverridden, refX, refW, spec.positionAnchor);
                if (widthIsAuto && (spec.widthOverridden || spec.insetsOverridden || AnchorTry.isAnchorFn(spec.left)
                        || AnchorTry.isAnchorFn(spec.right))) {
                    w = minMax(c, Math.max(hDeco, refW - left - right - ml - mr), true, spec.minWidthDecl, spec.maxWidthDecl, refW, borderBox);
                }
                imcbW = Math.max(0, refW - left - right);
                float eml = ml;
                if ((spec.autoMarginL || spec.autoMarginR) && !widthIsAuto) {
                    float free = refW - left - right - w - (spec.autoMarginL ? 0 : ml) - (spec.autoMarginR ? 0 : mr);
                    if (spec.autoMarginL && spec.autoMarginR) eml = Math.max(0, free / 2);
                    else if (spec.autoMarginL) eml = Math.max(0, free);
                }
                x = refX + left + eml;
            } else {
                imcbW = Math.max(0, refW - left);
                x = refX + left + ml;
            }
        } else if (!rightAuto) {
            float right = insetPx(c, RIGHT, spec.right, spec.insetsOverridden, refX, refW, spec.positionAnchor);
            imcbW = Math.max(0, refW - right);
            x = refX + refW - w - right - mr;
        } else {
            imcbW = refW;
            x = c.p.x() + ml + c.p.dx(w + ml + mr);
        }

        float y, imcbH;
        if (!topAuto) {
            float top = insetPx(c, TOP, spec.top, spec.insetsOverridden, refY, refH, spec.positionAnchor);
            if (!bottomAuto) {
                float bottom = insetPx(c, BOTTOM, spec.bottom, spec.insetsOverridden, refY, refH, spec.positionAnchor);
                if (heightIsAuto && (spec.heightOverridden || spec.insetsOverridden || AnchorTry.isAnchorFn(spec.top)
                        || AnchorTry.isAnchorFn(spec.bottom))) {
                    h = minMax(c, Math.max(vDeco, refH - top - bottom - mt - mb), false, spec.minHeightDecl, spec.maxHeightDecl, refH, borderBox);
                }
                imcbH = Math.max(0, refH - top - bottom);
                float emt = mt;
                if ((spec.autoMarginT || spec.autoMarginB) && !heightIsAuto) {
                    float free = refH - top - bottom - h - (spec.autoMarginT ? 0 : mt) - (spec.autoMarginB ? 0 : mb);
                    if (spec.autoMarginT && spec.autoMarginB) emt = Math.max(0, free / 2);
                    else if (spec.autoMarginT) emt = Math.max(0, free);
                }
                y = refY + top + emt;
            } else {
                imcbH = Math.max(0, refH - top);
                y = refY + top + mt;
            }
        } else if (!bottomAuto) {
            float bottom = insetPx(c, BOTTOM, spec.bottom, spec.insetsOverridden, refY, refH, spec.positionAnchor);
            imcbH = Math.max(0, refH - bottom);
            y = refY + refH - h - bottom - mb;
        } else {
            imcbH = refH;
            y = c.p.y() + mt + c.p.dy(h + mt + mb);
        }

        if (spec.area != null) {
            float[] a = anchor(c, null, spec.positionAnchor);
            if (a != null) {
                if (leftAuto && rightAuto) {
                    x = areaPos(spec.area[0], a[0], a[2], w, ml, mr, x);
                    imcbW = areaAvailable(spec.area[0], a[0], a[2], refX, refW);
                }
                if (topAuto && bottomAuto) {
                    y = areaPos(spec.area[1], a[1], a[3], h, mt, mb, y);
                    imcbH = areaAvailable(spec.area[1], a[1], a[3], refY, refH);
                }
            }
        }
        boolean fits = x - ml >= refX - OVERFLOW_EPS && x + w + mr <= refX + refW + OVERFLOW_EPS
                && y - mt >= refY - OVERFLOW_EPS && y + h + mb <= refY + refH + OVERFLOW_EPS;
        return new Result(x, y, w, h, imcbW, imcbH, fits, spec);
    }

    /** 候補の幅（高さ）: 宣言が {@code anchor-size()}・長さ・auto（基本が固定なら内容から測り直す）、無ければ自然な大きさ。 */
    private static float size(Ctx c, String decl, boolean horizontal, float measured, AnchorTry.Spec spec, boolean startAuto, boolean endAuto,
                              LayoutFragment prior) {
        Style st = c.st;
        float deco = horizontal ? c.g.hdec() : c.g.vdec();
        boolean borderBox = st.facts.borderBox;
        float ref = horizontal ? c.cbW : c.cbH;
        if (decl == null) decl = raw(st, horizontal ? st.box.width : st.box.height);
        if (decl != null && AnchorTry.isAnchorSizeFn(decl)) {
            float a = anchorSize(c, decl, horizontal, spec.positionAnchor);
            return a >= 0 ? toBorderBox(a, deco, borderBox) : measured;
        }
        boolean overridden = horizontal ? spec.widthDecl != null : spec.heightDecl != null;
        if (!overridden) return measured;
        if (AnchorTry.auto(decl)) {
            boolean baseAuto = horizontal ? st.facts.widthAuto : st.facts.heightAuto;
            if (baseAuto || (!startAuto && !endAuto)) return measured;
            if (horizontal) { // 基本は固定の幅で、候補が auto: 内容から縮小して合わせた幅
                MinMaxSizes mm = c.s.cache().minMax(c.box, c.s);
                return Math.min(Math.max(mm.min, c.cbW - spec.mL - spec.mR), mm.max);
            }
            return c.s.cache().layout(c.box, c.s.space(c.cbW, measuredWidthOf(c), Float.NaN, c.cbH, ConstraintSpace.FORMATTING_ROOT),
                    OutOfFlow.priorOf(prior, c.box), c.s).height;
        }
        return toBorderBox(dim(c, decl, ref), deco, borderBox);
    }

    private static float measuredWidthOf(Ctx c) {
        return naturalWidth(c);
    }

    private static float minMax(Ctx c, float v, boolean horizontal, String minDecl, String maxDecl, float ref, boolean borderBox) {
        Style st = c.st;
        float deco = horizontal ? c.g.hdec() : c.g.vdec();
        float max = limit(c, maxDecl, horizontal ? st.box.maxWidth : st.box.maxHeight, true, ref);
        if (!Float.isNaN(max)) {
            float m = toBorderBox(max, deco, borderBox);
            if (m > 0) v = Math.min(v, m);
        }
        float min = limit(c, minDecl, horizontal ? st.box.minWidth : st.box.minHeight, false, ref);
        if (!Float.isNaN(min)) v = Math.max(v, toBorderBox(min, deco, borderBox));
        return v;
    }

    private static float limit(Ctx c, String override, long len, boolean isMax, float ref) {
        Style st = c.st;
        if (override != null) {
            String t = override.trim();
            if (t.isEmpty() || (isMax ? "none" : "auto").equalsIgnoreCase(t) || intrinsicKeyword(t)) return Float.NaN;
            return dim(c, t, ref);
        }
        boolean ignored = !CssLength.isDeclared(len) || (isMax ? CssLength.isKeyword(len, CssLength.KW_NONE) : CssLength.isAuto(len))
                || CssLength.isIntrinsicSize(len, st.box.exprs);
        return ignored ? Float.NaN : st.boxPx(len, ref, c.s.viewportW(), c.s.viewportH());
    }

    private static boolean intrinsicKeyword(String v) {
        String t = v.toLowerCase(Locale.ROOT);
        return t.equals("min-content") || t.equals("max-content") || t.equals("fit-content") || t.startsWith("fit-content(");
    }

    // ---- anchor() / anchor-size() / position-area

    private static float insetPx(Ctx c, int side, String raw, boolean overridden, float cbOrigin, float cbLen, String positionAnchor) {
        if (AnchorTry.isAnchorFn(raw)) return anchorInset(c, raw, side, cbOrigin, cbLen, positionAnchor);
        if (!overridden) return c.st.boxPx(insetLen(c.st, side), cbLen, c.s.viewportW(), c.s.viewportH());
        return dim(c, raw, cbLen);
    }

    /** {@code anchor(--名前 辺)}: アンカーの辺の座標から、包含ブロックのその側の端までの距離。アンカーが無ければ 0。 */
    private static float anchorInset(Ctx c, String raw, int sideCode, float cbOrigin, float cbLen, String positionAnchor) {
        String[] ns = AnchorTry.nameAndArg(raw, 7);
        if (ns == null || ns[1] == null) return 0;
        float[] a = anchor(c, ns[0], positionAnchor);
        if (a == null) return 0;
        boolean horizontal = sideCode == LEFT || sideCode == RIGHT;
        float from = horizontal ? a[0] : a[1], size = horizontal ? a[2] : a[3];
        String side = ns[1];
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
        boolean fromStart = sideCode == LEFT || sideCode == TOP;
        return fromStart ? coord - cbOrigin : cbOrigin + cbLen - coord;
    }

    private static float anchorSize(Ctx c, String raw, boolean horizontal, String positionAnchor) {
        String[] ns = AnchorTry.nameAndArg(raw, 12);
        if (ns == null) return -1;
        float[] a = anchor(c, ns[0], positionAnchor);
        if (a == null) return -1;
        String dim = ns[1] != null ? ns[1] : horizontal ? "width" : "height";
        return switch (dim) {
            case "width", "inline" -> a[2];
            case "height", "block" -> a[3];
            default -> -1;
        };
    }

    /**
     * アンカーの矩形（包含ブロックの border-box 基準）。名前が無ければ {@code position-anchor}、それも auto ならポップオーバーの呼び出し元
     * （暗黙のアンカー）。
     */
    private static float[] anchor(Ctx c, String name, String positionAnchor) {
        Anchors.Hit h = hit(c, name, positionAnchor);
        if (h == null) return null;
        float[] r = h.rect();
        if (h.scrollers().length == 0) return r;
        java.util.Collections.addAll(c.seen, h.scrollers());
        RememberedScroll mem = c.box.anchorScroll;
        if (mem == null) return r;
        float[] off = {0, 0}; // 覚えたスクロール量だけずらす（仕様 §3.3。G8）
        for (BoxNode b : h.scrollers()) mem.addTo(b.node, off);
        return off[0] == 0 && off[1] == 0 ? r : new float[]{r[0] - off[0], r[1] - off[1], r[2], r[3]};
    }

    /** アンカー（名前が無ければ {@code position-anchor}、それも auto なら暗黙のアンカー。無ければ null）。 */
    private static Anchors.Hit hit(Ctx c, String name, String positionAnchor) {
        if (c.anchors == null) return null;
        if (name == null) name = positionAnchor;
        if (name != null && !name.isBlank() && !"auto".equalsIgnoreCase(name.trim())) return c.anchors.named(name.trim());
        String id = c.box.popover != null ? c.box.popover.id() : null;
        return id == null ? null : c.anchors.invoker(id);
    }

    private static float areaPos(String tok, float a0, float aLen, float size, float mStart, float mEnd, float fallback) {
        return switch (tok) {
            case "left", "top", "x-start", "y-start", "x-self-start", "y-self-start", "start", "self-start" -> a0 - size - mEnd;
            case "right", "bottom", "x-end", "y-end", "x-self-end", "y-self-end", "end", "self-end" -> a0 + aLen + mStart;
            case "span-left", "span-top", "span-x-start", "span-y-start", "span-start", "span-self-start" -> a0 + aLen - size - mEnd;
            case "span-right", "span-bottom", "span-x-end", "span-y-end", "span-end", "span-self-end" -> a0 + mStart;
            case "center", "span-all" -> a0 + (aLen - size) / 2f;
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
            case "center" -> Math.max(0, Math.min(aEnd, cbEnd) - Math.max(aStart, cbStart));
            default -> cbLen;
        };
    }

    // ---- 補助

    private static long insetLen(Style st, int side) {
        return switch (side) {
            case TOP -> st.box.top;
            case RIGHT -> st.box.right;
            case BOTTOM -> st.box.bottom;
            default -> st.box.left;
        };
    }

    private static float dim(Ctx c, String v, float ref) {
        return LengthResolver.parseDim(v, ref, c.s.viewportW(), c.s.viewportH(), c.st.inherited.fontSize, c.st.inherited.rootFontSizePx);
    }

    private static float toBorderBox(float v, float deco, boolean borderBox) {
        return borderBox ? Math.max(v, deco) : v + deco;
    }

    private static String raw(Style st, long len) {
        return CssLength.isCalc(len) ? st.box.exprs.raw(len) : null;
    }

    private static boolean none(String v) {
        return v == null || v.isBlank() || "none".equalsIgnoreCase(v.trim());
    }
}
