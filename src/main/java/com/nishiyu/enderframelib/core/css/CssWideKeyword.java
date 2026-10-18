package com.nishiyu.enderframelib.core.css;

import java.util.Locale;

/**
 * どのプロパティにも書ける CSS 全域キーワード。
 *
 * {@code revert} はカスケードの中で（UA オリジンの値に巻き戻すので、オリジンが分かる場所で）、
 * それ以外は算出値を決める段（{@link ValueComputer}）で解く。
 */
public enum CssWideKeyword {
    INHERIT, INITIAL, UNSET, REVERT;

    /** 値が CSS 全域キーワードならそれを、そうでなければ null を返す。 */
    public static CssWideKeyword of(String value) {
        if (value == null) return null;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "inherit" -> INHERIT;
            case "initial" -> INITIAL;
            case "unset" -> UNSET;
            // revert-layer はカスケードレイヤーを持たないので revert と同じになる
            case "revert", "revert-layer" -> REVERT;
            default -> null;
        };
    }
}
