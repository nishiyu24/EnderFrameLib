package com.nishiyu.enderframelib.core.text.model;

import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;

/**
 * 1 回の測定を決める書体の指定。
 */
public record FontStyle(float fontSizePx, boolean bold, boolean italic, float letterSpacingPx) {

    public static final FontStyle DEFAULT = new FontStyle(16.0f, false, false, 0.0f);

    public FontStyle withSize(float px) {
        return new FontStyle(px, bold, italic, letterSpacingPx);
    }

    public FontStyle withBold(boolean isBold) {
        return new FontStyle(fontSizePx, isBold, italic, letterSpacingPx);
    }
}
