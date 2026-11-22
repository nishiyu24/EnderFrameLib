package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.kernel.Shortcut;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.IntrinsicSizeResolver;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

/**
 * 仮の測定（上書き寸法なし）・確定の測定（上書き寸法あり）の結果の産物と、その入力（{@link MeasureKey}＋読み取り集合）。
 *
 * <p>C3 ① でキー欄・外部世代・免除フラグ群を {@link MeasureKey} と {@code readMask} へまとめ、照合を一元化する。</p>
 */
record Tentative(
        Kind kind,
        MeasureKey key,
        MeasureOutputs outputs,
        int readMask,
        float fitNeed
) {
    enum Kind { TENTATIVE, DEFINITE }

    boolean isFillFit() {
        return (readMask & MeasureReads.READ_FILL_FIT) != 0;
    }

    /** 仮測定の再利用判定（{@code allowFillFit == true}、上書き寸法は NaN）。 */
    boolean matchesTentative(ConstraintSpace s, Viewport vp, int curMeasureEpoch, int curShapeEpoch,
                             FontResolver fonts, long fontGen, IntrinsicSizeResolver intrinsics, long intrinsicGen,
                             boolean autoSkipped, boolean isTextNode) {
        return key.matches(s, Float.NaN, Float.NaN, vp, curMeasureEpoch, curShapeEpoch,
                fonts, fontGen, intrinsics, intrinsicGen, autoSkipped, readMask, fitNeed, isTextNode, true);
    }

    /** 確定葉の再利用判定（{@code allowFillFit == false}、上書き寸法一致を要求）。 */
    boolean matchesDefinite(ConstraintSpace s, float ow, float oh, Viewport vp,
                            int curMeasureEpoch, int curShapeEpoch,
                            FontResolver fonts, long fontGen, IntrinsicSizeResolver intrinsics, long intrinsicGen,
                            boolean autoSkipped, boolean isTextNode) {
        return key.matches(s, ow, oh, vp, curMeasureEpoch, curShapeEpoch,
                fonts, fontGen, intrinsics, intrinsicGen, autoSkipped, readMask, fitNeed, isTextNode, false);
    }

    /** 枠追い出し・上書き先の探索用: 外部文脈抜きで制約空間がこの枠と同値とみなせるか。 */
    boolean matchesSpaceForEviction(ConstraintSpace s) {
        ConstraintSpace prev = key.space();
        if (!MeasureAnalysis.sameFixedSizes(prev, s)) return false;
        float avail = s.availableInlineSize();
        float prevAvail = prev.availableInlineSize();
        boolean exactAvail = Float.floatToIntBits(prevAvail) == Float.floatToIntBits(avail);
        boolean widthFree = (readMask & MeasureReads.READ_AVAIL_INLINE) == 0 && Shortcut.WIDTH_INDEPENDENT_REUSE.on();
        boolean fitHit = (readMask & (MeasureReads.READ_FIT_BOUNDED | MeasureReads.READ_FILL_FIT)) != 0
                && Shortcut.FIT_REUSE.on() && fitNeed != Float.POSITIVE_INFINITY
                && avail >= fitNeed && prevAvail >= fitNeed;
        if (!exactAvail && !widthFree && !fitHit) return false;
        if (((readMask & MeasureReads.READ_PERCENT_BLOCK) != 0 || !Shortcut.BLOCK_PERCENT_FREE.on())
                && Float.floatToIntBits(prev.percentageBlockSize()) != Float.floatToIntBits(s.percentageBlockSize())) {
            return false;
        }
        FormattingContext a = prev.parent(), b = s.parent();
        if (a != b && !a.equals(b)) {
            if (a.parentDisplay() != b.parentDisplay()) return false;
            if ((readMask & MeasureReads.READ_STRETCH) != 0 && a.stretch() != b.stretch()) return false;
            if ((readMask & MeasureReads.READ_SHRINK) != 0 && a.isShrink() != b.isShrink()) return false;
        }
        return true;
    }

    boolean matchesExactRecord(Kind kind, MeasureKey key, LayoutBox box, int readMask, float fitNeed) {
        return this.kind == kind
                && this.readMask == readMask
                && Float.floatToIntBits(this.fitNeed) == Float.floatToIntBits(fitNeed)
                && this.key.matchesExact(key.space(), key.overrideW(), key.overrideH(), key.viewport(),
                        key.epochs(), key.contentAutoSkipped())
                && this.outputs.matchesAll(box);
    }
}
