package com.nishiyu.enderframelib.core.text.model;

import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;

/** 語の内側で分割してよいか。CSS の {@code word-break} に対応する。 */
public enum WordBreak {
    NORMAL, BREAK_ALL, KEEP_ALL;

    public static WordBreak of(String keyword) {
        if (keyword == null) return NORMAL;
        return switch (keyword.trim().toLowerCase()) {
            case "break-all" -> BREAK_ALL;
            case "keep-all" -> KEEP_ALL;
            default -> NORMAL;
        };
    }
}
