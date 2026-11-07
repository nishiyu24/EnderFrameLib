package com.nishiyu.enderframelib.core.css;

/**
 * CSS の数値を文字列にする。{@code String.format("%.4f")} と正規表現の往復をやめ、
 * 小数 4 桁・末尾の 0 を落とす整形を整数演算だけで行う（{@code Formatter} / {@code Matcher} を作らない）。
 */
public final class CssNumbers {
    private CssNumbers() {}

    /** {@code String.format(Locale.ROOT, "%.4f", v)} 相当（HALF_UP）で小数 4 桁にして末尾の 0 と「.」を落とす。 */
    public static String trimmed4(double v) {
        return trimmed(v, 4);
    }

    private static final long[] POW10 = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000};

    /** 小数 {@code digits}（0..7）桁に HALF_UP で丸め、末尾の 0 と「.」を落とした文字列。 */
    public static String trimmed(double v, int digits) {
        long scale = POW10[digits];
        if (Double.isNaN(v) || Double.isInfinite(v) || Math.abs(v) * scale >= 1e15) {
            if (Double.isNaN(v) || Double.isInfinite(v)) return Double.toString(v);
            String s = new java.math.BigDecimal(v).setScale(digits, java.math.RoundingMode.HALF_UP).toPlainString();
            return stripZeros(s);
        }
        boolean neg = v < 0;
        long scaled = (long) Math.floor(Math.abs(v) * scale + 0.5); // 絶対値の HALF_UP（=0 から遠ざかる側）
        long ip = scaled / scale;
        int frac = (int) (scaled % scale);
        StringBuilder sb = new StringBuilder(20);
        if (neg) sb.append('-');
        sb.append(ip);
        if (frac != 0) {
            sb.append('.');
            int d = digits;
            while (frac % 10 == 0) {
                frac /= 10;
                d--;
            }
            for (int i = d - decimalLength(frac); i > 0; i--) sb.append('0'); // 先頭側の 0（0.0050 → "005"）
            sb.append(frac);
        }
        return sb.toString();
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** {@code #rrggbb}（小文字）。 */
    public static String hexRgb(int r, int g, int b) {
        char[] c = {'#', HEX[(r >> 4) & 15], HEX[r & 15], HEX[(g >> 4) & 15], HEX[g & 15], HEX[(b >> 4) & 15], HEX[b & 15]};
        return new String(c);
    }

    /** px の算出値の文字列。整数なら小数点を付けない。 */
    public static String px(float value) {
        float rounded = Math.round(value);
        if (Math.abs(value - rounded) < 1e-4f) return Long.toString((long) rounded).concat("px");
        return trimmed4(value).concat("px");
    }

    private static int decimalLength(int n) {
        return n >= 1_000_000 ? 7 : n >= 100_000 ? 6 : n >= 10_000 ? 5 : n >= 1000 ? 4 : n >= 100 ? 3 : n >= 10 ? 2 : 1;
    }

    private static String stripZeros(String s) {
        int end = s.length();
        if (s.indexOf('.') < 0) return s;
        while (end > 0 && s.charAt(end - 1) == '0') end--;
        if (end > 0 && s.charAt(end - 1) == '.') end--;
        return s.substring(0, end);
    }
}
