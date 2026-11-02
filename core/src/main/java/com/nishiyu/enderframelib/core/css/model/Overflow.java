package com.nishiyu.enderframelib.core.css.model;

import com.nishiyu.enderframelib.core.css.*;
import com.nishiyu.enderframelib.core.css.model.*;

public enum Overflow implements CssKeywords.Keyword {
    VISIBLE, HIDDEN, CLIP, SCROLL, AUTO;

    /** はみ出しを切る・スクロールさせる（スクロールコンテナまたはクリップ）か。 */
    public boolean clips() {
        return this != VISIBLE;
    }

    /** 利用者がスクロールできる（スクロールコンテナ）か。{@code hidden} はスクロールコンテナだがスクロールバーを持たない。 */
    public boolean scrolls() {
        return this == SCROLL || this == AUTO || this == HIDDEN;
    }
}
