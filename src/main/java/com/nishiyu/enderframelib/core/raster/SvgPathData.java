package com.nishiyu.enderframelib.core.raster;

/** SVG のパスデータ（{@code d} 属性）の読み取り。エラーに出会ったら、そこまでのパスを返す。 */
final class SvgPathData {

    private final String s;
    private int i;

    private SvgPathData(String s) {
        this.s = s == null ? "" : s;
    }

    static Path2D parse(String d) {
        return new SvgPathData(d).run();
    }

    private Path2D run() {
        Path2D path = new Path2D();
        double cx = 0;
        double cy = 0;
        double sx = 0;
        double sy = 0;
        double lastCtrlX = 0;
        double lastCtrlY = 0;
        char prev = 0;
        char cmd = 0;
        boolean first = true;
        while (true) {
            skipSeparators();
            if (i >= s.length()) break;
            char ch = s.charAt(i);
            if (isCommand(ch)) {
                cmd = ch;
                i++;
                if (first && cmd != 'M' && cmd != 'm') break;
                if (cmd == 'Z' || cmd == 'z') {
                    path.closePath();
                    cx = sx;
                    cy = sy;
                    prev = 'z';
                    first = false;
                    continue;
                }
            } else if (cmd == 0 || cmd == 'Z' || cmd == 'z' || !startsNumber(ch)) {
                break;
            }
            first = false;
            boolean rel = Character.isLowerCase(cmd);
            char up = Character.toUpperCase(cmd);
            switch (up) {
                case 'M' -> {
                    double[] v = numbers(2);
                    if (v == null) return path;
                    double x = rel ? cx + v[0] : v[0];
                    double y = rel ? cy + v[1] : v[1];
                    path.moveTo(x, y);
                    cx = sx = x;
                    cy = sy = y;
                    // 続く座標の組は lineTo として扱う
                    cmd = rel ? 'l' : 'L';
                    prev = 'M';
                }
                case 'L' -> {
                    double[] v = numbers(2);
                    if (v == null) return path;
                    cx = rel ? cx + v[0] : v[0];
                    cy = rel ? cy + v[1] : v[1];
                    path.lineTo(cx, cy);
                    prev = 'L';
                }
                case 'H' -> {
                    double[] v = numbers(1);
                    if (v == null) return path;
                    cx = rel ? cx + v[0] : v[0];
                    path.lineTo(cx, cy);
                    prev = 'L';
                }
                case 'V' -> {
                    double[] v = numbers(1);
                    if (v == null) return path;
                    cy = rel ? cy + v[0] : v[0];
                    path.lineTo(cx, cy);
                    prev = 'L';
                }
                case 'C' -> {
                    double[] v = numbers(6);
                    if (v == null) return path;
                    double x1 = rel ? cx + v[0] : v[0];
                    double y1 = rel ? cy + v[1] : v[1];
                    double x2 = rel ? cx + v[2] : v[2];
                    double y2 = rel ? cy + v[3] : v[3];
                    double x = rel ? cx + v[4] : v[4];
                    double y = rel ? cy + v[5] : v[5];
                    path.cubicTo(x1, y1, x2, y2, x, y);
                    lastCtrlX = x2;
                    lastCtrlY = y2;
                    cx = x;
                    cy = y;
                    prev = 'C';
                }
                case 'S' -> {
                    double[] v = numbers(4);
                    if (v == null) return path;
                    double x1 = prev == 'C' ? 2 * cx - lastCtrlX : cx;
                    double y1 = prev == 'C' ? 2 * cy - lastCtrlY : cy;
                    double x2 = rel ? cx + v[0] : v[0];
                    double y2 = rel ? cy + v[1] : v[1];
                    double x = rel ? cx + v[2] : v[2];
                    double y = rel ? cy + v[3] : v[3];
                    path.cubicTo(x1, y1, x2, y2, x, y);
                    lastCtrlX = x2;
                    lastCtrlY = y2;
                    cx = x;
                    cy = y;
                    prev = 'C';
                }
                case 'Q' -> {
                    double[] v = numbers(4);
                    if (v == null) return path;
                    double x1 = rel ? cx + v[0] : v[0];
                    double y1 = rel ? cy + v[1] : v[1];
                    double x = rel ? cx + v[2] : v[2];
                    double y = rel ? cy + v[3] : v[3];
                    path.quadTo(x1, y1, x, y);
                    lastCtrlX = x1;
                    lastCtrlY = y1;
                    cx = x;
                    cy = y;
                    prev = 'Q';
                }
                case 'T' -> {
                    double[] v = numbers(2);
                    if (v == null) return path;
                    double x1 = prev == 'Q' ? 2 * cx - lastCtrlX : cx;
                    double y1 = prev == 'Q' ? 2 * cy - lastCtrlY : cy;
                    double x = rel ? cx + v[0] : v[0];
                    double y = rel ? cy + v[1] : v[1];
                    path.quadTo(x1, y1, x, y);
                    lastCtrlX = x1;
                    lastCtrlY = y1;
                    cx = x;
                    cy = y;
                    prev = 'Q';
                }
                case 'A' -> {
                    double rx = number();
                    double ry = number();
                    double rot = number();
                    int large = flag();
                    int sweep = flag();
                    double ex = number();
                    double ey = number();
                    if (Double.isNaN(rx) || Double.isNaN(ry) || Double.isNaN(rot) || large < 0 || sweep < 0
                            || Double.isNaN(ex) || Double.isNaN(ey)) {
                        return path;
                    }
                    double x = rel ? cx + ex : ex;
                    double y = rel ? cy + ey : ey;
                    path.svgArcTo(rx, ry, rot, large == 1, sweep == 1, x, y);
                    cx = x;
                    cy = y;
                    prev = 'A';
                }
                default -> {
                    return path;
                }
            }
        }
        return path;
    }

    private static boolean isCommand(char c) {
        return "MmLlHhVvCcSsQqTtAaZz".indexOf(c) >= 0;
    }

    private static boolean startsNumber(char c) {
        return (c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.';
    }

    private void skipSeparators() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == ',') i++;
            else break;
        }
    }

    private double[] numbers(int count) {
        double[] out = new double[count];
        for (int k = 0; k < count; k++) {
            double v = number();
            if (Double.isNaN(v)) return null;
            out[k] = v;
        }
        return out;
    }

    /** 数を 1 つ読む。読めなければ {@code NaN}。 */
    private double number() {
        skipSeparators();
        int start = i;
        int n = s.length();
        if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
        int digits = 0;
        while (i < n && Character.isDigit(s.charAt(i))) {
            i++;
            digits++;
        }
        if (i < n && s.charAt(i) == '.') {
            i++;
            while (i < n && Character.isDigit(s.charAt(i))) {
                i++;
                digits++;
            }
        }
        if (digits == 0) {
            i = start;
            return Double.NaN;
        }
        if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            int save = i;
            i++;
            if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
            int exp = 0;
            while (i < n && Character.isDigit(s.charAt(i))) {
                i++;
                exp++;
            }
            if (exp == 0) i = save;
        }
        try {
            return Double.parseDouble(s.substring(start, i));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** アークのフラグ（{@code 0} か {@code 1} の 1 文字。区切り無しで続けてよい）。 */
    private int flag() {
        skipSeparators();
        if (i < s.length()) {
            char c = s.charAt(i);
            if (c == '0' || c == '1') {
                i++;
                return c - '0';
            }
        }
        return -1;
    }
}
