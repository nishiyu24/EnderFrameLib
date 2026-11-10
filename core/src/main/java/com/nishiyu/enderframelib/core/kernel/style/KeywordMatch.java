package com.nishiyu.enderframelib.core.kernel.style;

/**
 * キーワード値の照合を、{@code trim().toLowerCase()} の新しい文字列を作らずに行う。
 * 算出スタイルの事実（{@link StyleFacts}）や空白・改行の扱いの判定が、構築のたびに文字列を 1〜2 個確保していたのを無くす。
 */
public final class KeywordMatch {
    private KeywordMatch() {}

    /** {@code value.trim().equalsIgnoreCase(lowerKeyword)} と同じ。{@code value} が null なら false。 */
    public static boolean is(String value, String lowerKeyword) {
        if (value == null) return false;
        int s = 0, e = value.length();
        while (s < e && value.charAt(s) <= ' ') s++;
        while (e > s && value.charAt(e - 1) <= ' ') e--;
        return e - s == lowerKeyword.length() && value.regionMatches(true, s, lowerKeyword, 0, e - s);
    }

    /** {@code value.trim().toLowerCase().startsWith(lowerPrefix)} と同じ。{@code value} が null なら false。 */
    public static boolean trimmedStartsWithIgnoreCase(String value, String lowerPrefix) {
        if (value == null) return false;
        int s = 0, e = value.length();
        while (s < e && value.charAt(s) <= ' ') s++;
        return value.regionMatches(true, s, lowerPrefix, 0, lowerPrefix.length());
    }

    /** {@code value.trim().startsWith(prefix)}（大小文字は区別する）と同じ。{@code value} が null なら false。 */
    public static boolean trimmedStartsWith(String value, String prefix) {
        if (value == null) return false;
        int s = 0, e = value.length();
        while (s < e && value.charAt(s) <= ' ') s++;
        return value.startsWith(prefix, s);
    }
}
