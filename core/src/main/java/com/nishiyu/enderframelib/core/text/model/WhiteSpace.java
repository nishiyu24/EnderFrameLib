package com.nishiyu.enderframelib.core.text.model;

import com.nishiyu.enderframelib.core.kernel.style.KeywordMatch;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;

/** 空白の扱い。CSS の {@code white-space} に対応する。 */
public enum WhiteSpace {
    NORMAL, NOWRAP, PRE, PRE_WRAP, PRE_LINE;

    /** 連続した空白を 1 つに畳むか。 */
    public boolean collapses() {
        return this == NORMAL || this == NOWRAP || this == PRE_LINE;
    }

    /** 行の折り返しをするか。 */
    public boolean wraps() {
        return this == NORMAL || this == PRE_WRAP || this == PRE_LINE;
    }

    /** 原文の改行を行分割として残すか。 */
    public boolean preservesNewlines() {
        return this == PRE || this == PRE_WRAP || this == PRE_LINE;
    }

    public static WhiteSpace of(String keyword) {
        if (keyword == null) return NORMAL;
        if (KeywordMatch.is(keyword, "nowrap")) return NOWRAP;
        if (KeywordMatch.is(keyword, "pre")) return PRE;
        if (KeywordMatch.is(keyword, "pre-wrap")) return PRE_WRAP;
        if (KeywordMatch.is(keyword, "pre-line")) return PRE_LINE;
        return NORMAL;
    }
}
