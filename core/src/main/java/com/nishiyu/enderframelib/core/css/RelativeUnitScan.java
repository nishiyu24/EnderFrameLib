package com.nishiyu.enderframelib.core.css;

/** 値の文字列が、ビューポート（{@code vw} 系）・コンテナ（{@code cq*}）基準の単位を使っているかの走査（{@link ComputedStyle#usesViewportUnits()} の部品）。 */
final class RelativeUnitScan {
    private RelativeUnitScan() {}

    static boolean scan(String v) {
        int n = v.length();
        for (int i = 0; i < n; i++) {
            char c = v.charAt(i);
            if (c < '0' || c > '9') continue;
            // 数値（整数・小数・指数の手前まで）を読み飛ばし、直後の英字の並びを単位として見る
            int j = i + 1;
            while (j < n && ((v.charAt(j) >= '0' && v.charAt(j) <= '9') || v.charAt(j) == '.')) j++;
            int k = j;
            while (k < n && Character.isLetter(v.charAt(k))) k++;
            if (k > j && isRelativeUnit(v, j, k)) return true;
            i = Math.max(i, k - 1);
        }
        return false;
    }

    /** {@code vw} 系（{@code s/l/d} 接頭辞つきを含む）と {@code cq*} か。単位ごとに文字列を作らず、大小文字を無視して照合する。 */
    static boolean isRelativeUnit(String v, int from, int to) {
        int len = to - from;
        if (len < 2 || len > 6) return false;
        char c0 = Character.toLowerCase(v.charAt(from));
        if (c0 == 'c') return len >= 3 && Character.toLowerCase(v.charAt(from + 1)) == 'q' && isAxisUnit(v, from + 2, to);
        int start = from;
        if ((c0 == 's' || c0 == 'l' || c0 == 'd') && len >= 3) {
            start = from + 1;
            c0 = Character.toLowerCase(v.charAt(start));
        }
        return c0 == 'v' && isAxisUnit(v, start + 1, to);
    }

    /** {@code w h i b min max}（{@code vw} の {@code w} など、単位の後半）か。 */
    private static boolean isAxisUnit(String v, int from, int to) {
        int n = to - from;
        if (n == 1) {
            char c = Character.toLowerCase(v.charAt(from));
            return c == 'w' || c == 'h' || c == 'i' || c == 'b';
        }
        return n == 3 && (v.regionMatches(true, from, "min", 0, 3) || v.regionMatches(true, from, "max", 0, 3));
    }
}
