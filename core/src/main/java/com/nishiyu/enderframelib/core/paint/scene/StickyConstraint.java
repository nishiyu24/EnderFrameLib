package com.nishiyu.enderframelib.core.paint.scene;

import com.nishiyu.enderframelib.core.css.CssExprs;
import com.nishiyu.enderframelib.core.css.CssLength;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.model.Position;

/**
 * {@code position: sticky} の貼り付き制約（M3b-2）。スクロール量に依らない静的な値だけを保持し、
 * 実際のずれは走査時に {@link DynamicSceneState} から計算する。
 */
public final class StickyConstraint {
    public final long top, right, bottom, left;
    public final CssExprs exprs;
    public final float fontSize, rootFontSizePx;
    public final float mT, mR, mB, mL;

    public StickyConstraint(long top, long right, long bottom, long left, CssExprs exprs,
                            float fontSize, float rootFontSizePx, float mT, float mR, float mB, float mL) {
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.left = left;
        this.exprs = exprs;
        this.fontSize = fontSize;
        this.rootFontSizePx = rootFontSizePx;
        this.mT = mT;
        this.mR = mR;
        this.mB = mB;
        this.mL = mL;
    }

    /** {@code style} が {@code position: sticky} なら制約を作る。そうでなければ {@code null}。 */
    public static StickyConstraint of(Style style, float mT, float mR, float mB, float mL) {
        if (style == null || style.box.position != Position.STICKY) return null;
        return new StickyConstraint(
                style.box.top, style.box.right, style.box.bottom, style.box.left, style.box.exprs,
                style.inherited.fontSize, style.inherited.rootFontSizePx, mT, mR, mB, mL);
    }

    /** 上の inset だけを持つ単純な sticky か（スクロール節の {@code StickyShift} 用）。 */
    public boolean isTopOnly() {
        return !CssLength.isAutoOrUndeclared(top)
                && CssLength.isAutoOrUndeclared(bottom)
                && CssLength.isAutoOrUndeclared(left)
                && CssLength.isAutoOrUndeclared(right);
    }

    /** 上の inset の解決済み px 値。 */
    public float topPx(float portH, float vw, float vh) {
        return CssLength.resolve(top, exprs, portH, fontSize, rootFontSizePx, vw, vh);
    }

    /**
     * スクロールポートと包含ブロックの内容ボックスから貼り付きのずれ {@code {dx, dy}} を返す。ずれが無ければ {@code null}。
     */
    public float[] offset(float bx, float by, float w, float h,
                          float portX, float portY, float portW, float portH,
                          float cbLeft, float cbTop, float cbRight, float cbBottom,
                          float vw, float vh) {
        float dx = 0f, dy = 0f;
        if (!CssLength.isAutoOrUndeclared(top)) {
            float limit = portY + CssLength.resolve(top, exprs, portH, fontSize, rootFontSizePx, vw, vh);
            if (by < limit) dy = Math.max(0f, Math.min(limit - by, cbBottom - (by + h + mB)));
        } else if (!CssLength.isAutoOrUndeclared(bottom)) {
            float limit = portY + portH - CssLength.resolve(bottom, exprs, portH, fontSize, rootFontSizePx, vw, vh);
            if (by + h > limit) dy = -Math.max(0f, Math.min(by + h - limit, by - mT - cbTop));
        }
        if (!CssLength.isAutoOrUndeclared(left)) {
            float limit = portX + CssLength.resolve(left, exprs, portW, fontSize, rootFontSizePx, vw, vh);
            if (bx < limit) dx = Math.max(0f, Math.min(limit - bx, cbRight - (bx + w + mR)));
        } else if (!CssLength.isAutoOrUndeclared(right)) {
            float limit = portX + portW - CssLength.resolve(right, exprs, portW, fontSize, rootFontSizePx, vw, vh);
            if (bx + w > limit) dx = -Math.max(0f, Math.min(bx + w - limit, bx - mL - cbLeft));
        }
        return dx == 0f && dy == 0f ? null : new float[]{dx, dy};
    }
}
