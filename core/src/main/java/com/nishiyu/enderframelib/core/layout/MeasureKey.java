package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.kernel.Shortcut;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.ExternalEpochs;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.IntrinsicSizeResolver;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

import java.util.Objects;

/**
 * 測定の記憶を引く不変の完全な鍵（制約空間＋親の上書き寸法＋ビューポート＋外部世代＋skip 状態）（C3 ①）。
 *
 * <p>入力の照合と免除判定を {@link MeasureReads} の読み取りビット列へ一元化し、記憶側で免除規則を重複させない。</p>
 */
record MeasureKey(
        ConstraintSpace space,
        float overrideW,
        float overrideH,
        Viewport viewport,
        ExternalEpochs epochs,
        boolean contentAutoSkipped
) {
    /** 鍵の全欄が完全に一致するか（主スロットの鍵オブジェクト再利用用）。 */
    boolean matchesExact(ConstraintSpace s, float ow, float oh, Viewport vp, ExternalEpochs ep, boolean autoSkipped) {
        return contentAutoSkipped == autoSkipped
                && MeasureAnalysis.sameLength(overrideW, ow)
                && MeasureAnalysis.sameLength(overrideH, oh)
                && Objects.equals(space, s)
                && Objects.equals(viewport, vp)
                && epochs.matchesExact(ep);
    }

    /** 読み取り集合 {@code readMask} に基づき、新しい入力がこの測定結果と等価とみなせるかを判定する。 */
    boolean matches(ConstraintSpace s, float ow, float oh, Viewport vp,
                    int curMeasureEpoch, int curShapeEpoch,
                    FontResolver curFonts, long curFontGen,
                    IntrinsicSizeResolver curIntrinsics, long curIntrinsicGen,
                    boolean autoSkipped, int readMask, float fitNeed,
                    boolean isTextNode, boolean allowFillFit) {
        if (contentAutoSkipped != autoSkipped
                || !MeasureAnalysis.sameLength(overrideW, ow)
                || !MeasureAnalysis.sameLength(overrideH, oh)
                || !MeasureAnalysis.sameFixedSizes(space, s)) {
            return false;
        }
        if (!epochs.matches(curMeasureEpoch, curShapeEpoch, (readMask & MeasureReads.READ_SHAPE_EPOCH) != 0,
                curFonts, curFontGen, curIntrinsics, curIntrinsicGen)) {
            return false;
        }
        float avail = s.availableInlineSize();
        float prevAvail = space.availableInlineSize();
        boolean exactAvail = Float.floatToIntBits(prevAvail) == Float.floatToIntBits(avail);
        boolean widthFree = (readMask & MeasureReads.READ_AVAIL_INLINE) == 0 && Shortcut.WIDTH_INDEPENDENT_REUSE.on();
        int fitFlag = allowFillFit ? (MeasureReads.READ_FIT_BOUNDED | MeasureReads.READ_FILL_FIT) : MeasureReads.READ_FIT_BOUNDED;
        boolean fitHit = (readMask & fitFlag) != 0 && Shortcut.FIT_REUSE.on()
                && fitNeed != Float.POSITIVE_INFINITY && avail >= fitNeed && prevAvail >= fitNeed;
        if (!exactAvail && !widthFree && !fitHit) {
            return false;
        }
        if (!viewport.equals(vp)) {
            if (viewport.rootW() != vp.rootW()) {
                if ((readMask & MeasureReads.READ_VIEWPORT_W) != 0) return false;
                if (!fitHit && !widthFree && !Shortcut.VIEWPORT_WIDTH_FREE_REUSE.on()) return false;
            }
            if (viewport.rootH() != vp.rootH() && (readMask & MeasureReads.READ_VIEWPORT_H) != 0) return false;
            if (viewport.devicePixelRatio() != vp.devicePixelRatio()) return false;
        }
        if (fitHit && isTextNode) {
            return true;
        }
        if (((readMask & MeasureReads.READ_PERCENT_BLOCK) != 0 || !Shortcut.BLOCK_PERCENT_FREE.on())
                && Float.floatToIntBits(space.percentageBlockSize()) != Float.floatToIntBits(s.percentageBlockSize())) {
            return false;
        }
        FormattingContext a = space.parent(), b = s.parent();
        if (a != b && !a.equals(b)) {
            if (a.parentDisplay() != b.parentDisplay()) return false;
            if ((readMask & MeasureReads.READ_STRETCH) != 0 && a.stretch() != b.stretch()) return false;
            if ((readMask & MeasureReads.READ_SHRINK) != 0 && a.isShrink() != b.isShrink()) return false;
        }
        return true;
    }
}
