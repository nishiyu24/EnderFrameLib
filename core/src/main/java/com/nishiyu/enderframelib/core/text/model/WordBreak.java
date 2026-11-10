package com.nishiyu.enderframelib.core.text.model;

import com.nishiyu.enderframelib.core.css.KeywordMatch;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;

/** 語の内側で分割してよいか。CSS の {@code word-break} に対応する。 */
public enum WordBreak {
    NORMAL, BREAK_ALL, KEEP_ALL;

    public static WordBreak of(String keyword) {
        if (keyword == null) return NORMAL;
        if (KeywordMatch.is(keyword, "break-all")) return BREAK_ALL;
        if (KeywordMatch.is(keyword, "keep-all")) return KEEP_ALL;
        return NORMAL;
    }
}
