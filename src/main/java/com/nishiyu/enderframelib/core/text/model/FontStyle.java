package com.nishiyu.enderframelib.core.text.model;

import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;

/**
 * 1 回の測定を決める書体の指定。
 *
 * {@code weight} は {@code font-weight} の数値（100〜900）。書体の照合（CSS Fonts 4 §5.2）が
 * Semibold（600）などの中間の太さを選ぶのに使う。{@code bold} は太字を合成する書体（ビットマップ）向けの
 * 「600 以上か」で、weight から決まる。{@code kerning} は {@code font-kerning} が none でないこと。
 */
public record FontStyle(float fontSizePx, boolean bold, boolean italic, float letterSpacingPx, int weight, boolean kerning) {

    public static final int NORMAL_WEIGHT = 400;
    public static final int BOLD_WEIGHT = 700;

    public static final FontStyle DEFAULT = new FontStyle(16.0f, false, false, 0.0f);

    /** 太さを「通常 / 太字」だけで指定する。太字は 700。 */
    public FontStyle(float fontSizePx, boolean bold, boolean italic, float letterSpacingPx) {
        this(fontSizePx, bold, italic, letterSpacingPx, bold ? BOLD_WEIGHT : NORMAL_WEIGHT, true);
    }

    public FontStyle(float fontSizePx, boolean bold, boolean italic, float letterSpacingPx, int weight) {
        this(fontSizePx, bold, italic, letterSpacingPx, weight, true);
    }

    public FontStyle withSize(float px) {
        return new FontStyle(px, bold, italic, letterSpacingPx, weight, kerning);
    }

    public FontStyle withBold(boolean isBold) {
        return new FontStyle(fontSizePx, isBold, italic, letterSpacingPx, isBold ? Math.max(weight, BOLD_WEIGHT) : NORMAL_WEIGHT, kerning);
    }

    public FontStyle withKerning(boolean on) {
        return new FontStyle(fontSizePx, bold, italic, letterSpacingPx, weight, on);
    }
}
