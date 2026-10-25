package com.nishiyu.enderframelib.core.css.model;

import com.nishiyu.enderframelib.core.css.*;
import com.nishiyu.enderframelib.core.css.model.*;

public enum TextAlign implements CssKeywords.Keyword {
    START, END, LEFT, RIGHT, CENTER, JUSTIFY, MATCH_PARENT;

    /**
     * {@code start} / {@code end} を、行の流れる向き（{@code direction}）で左右に直す（CSS Text 3 §7.1）。
     * {@code match-parent} は親の {@code direction} で {@code start} と同じに解く（呼び出し側が親の向きを渡す）。
     * {@code justify} は最終行を開始端に寄せる扱いで {@code start} と同じ。
     */
    public TextAlign physical(boolean rtl) {
        return switch (this) {
            case START, JUSTIFY, MATCH_PARENT -> rtl ? RIGHT : LEFT;
            case END -> rtl ? LEFT : RIGHT;
            default -> this;
        };
    }
}
