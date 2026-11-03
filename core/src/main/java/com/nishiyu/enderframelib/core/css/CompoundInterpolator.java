package com.nishiyu.enderframelib.core.css;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 複数の値を持つプロパティの補間（CSS Values 4 §「補間」）: 背景の位置・大きさ、影、フィルターの並び、{@code clip-path} の図形。
 * 補間できない組（種類・個数が違うなど）は {@code null} を返し、呼び出し側が離散的に（進行度 0.5 で）切り替える。
 *
 * <p>長さ・色・数値の 1 つ 1 つは {@link ValueInterpolator} に任せる。結果は同じプロパティの値として読める文字列。</p>
 */
final class CompoundInterpolator {

    private CompoundInterpolator() {}

    static String interpolate(String property, String from, String to, double t) {
        return switch (property) {
            case "background-position", "object-position", "transform-origin", "perspective-origin" -> positions(from, to, t);
            case "background-size" -> sizes(from, to, t);
            case "box-shadow", "text-shadow" -> shadows(from, to, t);
            case "filter", "backdrop-filter" -> filters(from, to, t);
            case "clip-path" -> clipPath(from, to, t);
            default -> null;
        };
    }

    // ------------------------------------------------------------------ 字句

    /** 括弧の外にある {@code sep} で分ける。 */
    static List<String> splitTop(String s, char sep) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && (sep == ' ' ? Character.isWhitespace(c) : c == sep)) {
                if (i > start || sep != ' ') out.add(s.substring(start, i).trim());
                start = i + 1;
            }
        }
        out.add(s.substring(start).trim());
        out.removeIf(x -> x.isEmpty() && sep == ' ');
        return out;
    }

    private static String lp(String a, String b, double t) {
        return ValueInterpolator.interpolate("width", a, b, t);
    }

    private static String color(String a, String b, double t) {
        return ValueInterpolator.interpolate("color", a, b, t);
    }

    private static final java.util.regex.Pattern LENGTH = java.util.regex.Pattern.compile("[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:%|[a-zA-Z]+)?");

    /** 長さ・割合か（単位の無い数は 0 だけ）。 */
    private static boolean isLength(String s) {
        if (!LENGTH.matcher(s).matches()) return false;
        char last = s.charAt(s.length() - 1);
        return !(Character.isDigit(last) || last == '.') || Double.parseDouble(s) == 0;
    }

    // ------------------------------------------------------------------ 位置

    /** 1 つの位置を {@code {x, y}}（長さ・割合）にする。3・4 値（辺からのずれ）は読まない。 */
    private static String[] position(String layer) {
        List<String> w = splitTop(layer.toLowerCase(Locale.ROOT), ' ');
        if (w.isEmpty() || w.size() > 2) return null;
        String x, y;
        if (w.size() == 1) {
            String v = w.get(0);
            if (v.equals("top") || v.equals("bottom")) {
                x = "center";
                y = v;
            } else {
                x = v;
                y = "center";
            }
        } else if (w.get(0).equals("top") || w.get(0).equals("bottom") || w.get(1).equals("left") || w.get(1).equals("right")) {
            x = w.get(1);
            y = w.get(0);
        } else {
            x = w.get(0);
            y = w.get(1);
        }
        x = keywordToPercent(x);
        y = keywordToPercent(y);
        return x == null || y == null ? null : new String[]{x, y};
    }

    private static String keywordToPercent(String v) {
        return switch (v) {
            case "left", "top" -> "0%";
            case "center" -> "50%";
            case "right", "bottom" -> "100%";
            default -> isLength(v) || v.startsWith("calc(") ? v : null;
        };
    }

    private static String positions(String from, String to, double t) {
        List<String> a = splitTop(from, ','), b = splitTop(to, ',');
        if (a.size() != b.size()) return null;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            String[] pa = position(a.get(i)), pb = position(b.get(i));
            if (pa == null || pb == null) return null;
            String x = lp(pa[0], pb[0], t), y = lp(pa[1], pb[1], t);
            if (x == null || y == null) return null;
            out.add(x + " " + y);
        }
        return String.join(", ", out);
    }

    // ------------------------------------------------------------------ 大きさ

    private static String[] size(String layer) {
        List<String> w = splitTop(layer.toLowerCase(Locale.ROOT), ' ');
        if (w.isEmpty() || w.size() > 2) return null;
        for (String v : w) if (!v.equals("auto") && !isLength(v)) return null; // cover / contain は補間しない
        return new String[]{w.get(0), w.size() == 2 ? w.get(1) : "auto"};
    }

    private static String sizes(String from, String to, double t) {
        List<String> a = splitTop(from, ','), b = splitTop(to, ',');
        if (a.size() != b.size()) return null;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            String[] sa = size(a.get(i)), sb = size(b.get(i));
            if (sa == null || sb == null) return null;
            String[] r = new String[2];
            for (int k = 0; k < 2; k++) {
                if (sa[k].equals("auto") || sb[k].equals("auto")) {
                    if (!sa[k].equals(sb[k])) return null; // auto と長さは補間できない
                    r[k] = "auto";
                } else {
                    r[k] = lp(sa[k], sb[k], t);
                    if (r[k] == null) return null;
                }
            }
            out.add(r[0] + " " + r[1]);
        }
        return String.join(", ", out);
    }

    // ------------------------------------------------------------------ 影

    /** 1 つの影。{@code color} は null なら currentcolor（補間できない）。 */
    private record Shadow(boolean inset, String x, String y, String blur, String spread, String color) {}

    private static Shadow shadow(String text, boolean allowSpread) {
        boolean inset = false;
        List<String> lengths = new ArrayList<>();
        String color = null;
        for (String tok : splitTop(text, ' ')) {
            if (tok.equalsIgnoreCase("inset")) inset = true;
            else if (isLength(tok)) lengths.add(tok);
            else if (color == null) color = tok;
            else return null;
        }
        if (lengths.size() < 2 || lengths.size() > (allowSpread ? 4 : 3)) return null;
        return new Shadow(inset, lengths.get(0), lengths.get(1), lengths.size() > 2 ? lengths.get(2) : "0px",
                lengths.size() > 3 ? lengths.get(3) : "0px", color);
    }

    private static List<Shadow> shadowList(String value, boolean allowSpread) {
        List<Shadow> out = new ArrayList<>();
        for (String part : splitTop(value, ',')) {
            Shadow s = shadow(part, allowSpread);
            if (s == null) return null;
            out.add(s);
        }
        return out;
    }

    private static String shadows(String from, String to, double t) {
        List<Shadow> a = from.equalsIgnoreCase("none") ? new ArrayList<>() : shadowList(from, true);
        List<Shadow> b = to.equalsIgnoreCase("none") ? new ArrayList<>() : shadowList(to, true);
        if (a == null || b == null) return null;
        // 短いほうは、相手と同じ向きの「影なし」（色は相手の色で不透明度 0）で埋める
        while (a.size() < b.size()) a.add(zero(b.get(a.size())));
        while (b.size() < a.size()) b.add(zero(a.get(b.size())));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            Shadow p = a.get(i), q = b.get(i);
            if (p.inset != q.inset) return null;
            String x = lp(p.x, q.x, t), y = lp(p.y, q.y, t), blur = lp(p.blur, q.blur, t), spread = lp(p.spread, q.spread, t);
            if (x == null || y == null || blur == null || spread == null) return null;
            String c;
            if (p.color == null && q.color == null) c = null;
            else if (p.color == null || q.color == null) return null; // currentcolor と色は補間しない
            else if ((c = color(p.color, q.color, t)) == null) return null;
            out.add((p.inset ? "inset " : "") + x + " " + y + " " + blur + " " + spread + (c == null ? "" : " " + c));
        }
        return String.join(", ", out);
    }

    private static Shadow zero(Shadow like) {
        String c = like.color == null || !ColorParser.isColor(like.color) ? like.color : colorWithZeroAlpha(like.color);
        return new Shadow(like.inset, "0px", "0px", "0px", "0px", c);
    }

    /** 同じ色で不透明度 0（影が無い側から伸びる影が、色相を保つように）。 */
    private static String colorWithZeroAlpha(String color) {
        int argb = ColorParser.parseColor(color);
        return "rgba(" + ((argb >> 16) & 0xFF) + ", " + ((argb >> 8) & 0xFF) + ", " + (argb & 0xFF) + ", 0)";
    }

    // ------------------------------------------------------------------ フィルター

    private record Fn(String name, String arg) {}

    private static List<Fn> functions(String value) {
        List<Fn> out = new ArrayList<>();
        for (String tok : splitTop(value, ' ')) {
            int open = tok.indexOf('(');
            if (open <= 0 || !tok.endsWith(")")) return null; // url() や var() は補間しない
            String name = tok.substring(0, open).toLowerCase(Locale.ROOT);
            if (name.equals("url") || name.equals("var")) return null;
            out.add(new Fn(name, tok.substring(open + 1, tok.length() - 1).trim()));
        }
        return out;
    }

    private static String identityArg(String name) {
        return switch (name) {
            case "blur" -> "0px";
            case "brightness", "contrast", "saturate", "opacity" -> "1";
            case "grayscale", "sepia", "invert" -> "0";
            case "hue-rotate" -> "0deg";
            case "drop-shadow" -> "0px 0px 0px transparent";
            default -> null;
        };
    }

    private static Double plainNumber(String s) {
        try {
            String v = s.trim();
            return v.endsWith("%") ? Double.parseDouble(v.substring(0, v.length() - 1)) / 100 : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double degrees(String s) {
        String v = s.trim().toLowerCase(Locale.ROOT);
        try {
            if (v.endsWith("deg")) return Double.parseDouble(v.substring(0, v.length() - 3));
            if (v.endsWith("turn")) return Double.parseDouble(v.substring(0, v.length() - 4)) * 360;
            if (v.endsWith("rad")) return Math.toDegrees(Double.parseDouble(v.substring(0, v.length() - 3)));
            if (v.endsWith("grad")) return Double.parseDouble(v.substring(0, v.length() - 4)) * 0.9;
            return Double.parseDouble(v) == 0 ? 0.0 : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String filters(String from, String to, double t) {
        List<Fn> a = from.equalsIgnoreCase("none") ? null : functions(from);
        List<Fn> b = to.equalsIgnoreCase("none") ? null : functions(to);
        if ((a == null && !from.equalsIgnoreCase("none")) || (b == null && !to.equalsIgnoreCase("none"))) return null;
        // none は、相手と同じ並びの「何もしない」関数
        if (a == null) a = identity(b);
        if (b == null) b = identity(a);
        if (a == null || b == null || a.size() != b.size()) return null;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            Fn p = a.get(i), q = b.get(i);
            if (!p.name.equals(q.name)) return null;
            String v = switch (p.name) {
                case "blur" -> lp(p.arg.isEmpty() ? "0px" : p.arg, q.arg.isEmpty() ? "0px" : q.arg, t);
                case "brightness", "contrast", "saturate", "opacity", "grayscale", "sepia", "invert" -> {
                    Double x = plainNumber(p.arg.isEmpty() ? identityArg(p.name) : p.arg), y = plainNumber(q.arg.isEmpty() ? identityArg(q.name) : q.arg);
                    yield x == null || y == null ? null : ValueInterpolator.format(x + (y - x) * t);
                }
                case "hue-rotate" -> {
                    Double x = degrees(p.arg.isEmpty() ? "0deg" : p.arg), y = degrees(q.arg.isEmpty() ? "0deg" : q.arg);
                    yield x == null || y == null ? null : ValueInterpolator.format(x + (y - x) * t) + "deg";
                }
                case "drop-shadow" -> shadows(p.arg, q.arg, t);
                default -> null;
            };
            if (v == null) return null;
            out.add(p.name + "(" + v + ")");
        }
        return String.join(" ", out);
    }

    private static List<Fn> identity(List<Fn> like) {
        if (like == null) return null;
        List<Fn> out = new ArrayList<>();
        for (Fn f : like) {
            String id = identityArg(f.name);
            if (id == null) return null;
            out.add(new Fn(f.name, id));
        }
        return out;
    }

    // ------------------------------------------------------------------ clip-path

    /** 図形 {@code name(arg)} を 1 つだけ持つ値。 */
    private static Fn shape(String value) {
        List<String> w = splitTop(value.trim(), ' ');
        // "inset(10px round 5px)" のように括弧の外に空白が無い形だけを扱う（geometry-box の併記は補間しない）
        if (w.size() != 1) return null;
        String tok = w.get(0);
        int open = tok.indexOf('(');
        if (open <= 0 || !tok.endsWith(")")) return null;
        return new Fn(tok.substring(0, open).toLowerCase(Locale.ROOT), tok.substring(open + 1, tok.length() - 1).trim());
    }

    private static String clipPath(String from, String to, double t) {
        Fn a = shape(from), b = shape(to);
        if (a == null || b == null || !a.name.equals(b.name)) return null;
        String inner = switch (a.name) {
            case "inset" -> insetArgs(a.arg, b.arg, t);
            case "circle" -> circleArgs(a.arg, b.arg, t);
            case "ellipse" -> ellipseArgs(a.arg, b.arg, t);
            case "polygon" -> polygonArgs(a.arg, b.arg, t);
            default -> null;
        };
        return inner == null ? null : a.name + "(" + inner + ")";
    }

    /** inset の {@code <辺>{1,4} [round <半径>]}: 4 辺に展開して補間する（半径は同じ書き方のときだけ）。 */
    private static String insetArgs(String from, String to, double t) {
        String[] a = insetParts(from), b = insetParts(to);
        if (a == null || b == null) return null;
        List<String> sides = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String v = lp(a[i], b[i], t);
            if (v == null) return null;
            sides.add(v);
        }
        String out = String.join(" ", sides);
        if (a[4] != null || b[4] != null) {
            if (a[4] == null || b[4] == null) return null;
            List<String> ra = splitTop(a[4], ' '), rb = splitTop(b[4], ' ');
            if (ra.size() != rb.size() || ra.size() > 4) return null;
            List<String> radii = new ArrayList<>();
            for (int i = 0; i < ra.size(); i++) {
                String v = lp(ra.get(i), rb.get(i), t);
                if (v == null) return null;
                radii.add(v);
            }
            out += " round " + String.join(" ", radii);
        }
        return out;
    }

    private static String[] insetParts(String arg) {
        String[] parts = arg.split("(?i)\\bround\\b", 2);
        List<String> w = splitTop(parts[0].trim(), ' ');
        if (w.isEmpty() || w.size() > 4) return null;
        String top = w.get(0), right = w.size() > 1 ? w.get(1) : top, bottom = w.size() > 2 ? w.get(2) : top, left = w.size() > 3 ? w.get(3) : right;
        return new String[]{top, right, bottom, left, parts.length > 1 ? parts[1].trim() : null};
    }

    /** {@code <半径>? [at <位置>]}。 */
    private static String circleArgs(String from, String to, double t) {
        String[] a = radiusAndAt(from), b = radiusAndAt(to);
        if (a == null || b == null) return null;
        String r = lp(a[0], b[0], t);
        if (r == null) return null;
        String at = positions(a[1], b[1], t);
        return at == null ? null : r + " at " + at;
    }

    private static String ellipseArgs(String from, String to, double t) {
        String[] a = radiiAndAt(from), b = radiiAndAt(to);
        if (a == null || b == null) return null;
        String rx = lp(a[0], b[0], t), ry = lp(a[1], b[1], t);
        if (rx == null || ry == null) return null;
        String at = positions(a[2], b[2], t);
        return at == null ? null : rx + " " + ry + " at " + at;
    }

    private static String[] radiusAndAt(String arg) {
        String[] parts = arg.split("(?i)\\bat\\b", 2);
        String r = parts[0].trim();
        if (r.isEmpty() || !isLength(r)) return null; // closest-side などは補間しない
        return new String[]{r, parts.length > 1 ? parts[1].trim() : "50% 50%"};
    }

    private static String[] radiiAndAt(String arg) {
        String[] parts = arg.split("(?i)\\bat\\b", 2);
        List<String> w = splitTop(parts[0].trim(), ' ');
        if (w.size() != 2 || !isLength(w.get(0)) || !isLength(w.get(1))) return null;
        return new String[]{w.get(0), w.get(1), parts.length > 1 ? parts[1].trim() : "50% 50%"};
    }

    private static String polygonArgs(String from, String to, double t) {
        List<String> a = splitTop(from, ','), b = splitTop(to, ',');
        boolean evenOdd = a.get(0).equalsIgnoreCase("evenodd") || a.get(0).equalsIgnoreCase("nonzero");
        if (evenOdd != (b.get(0).equalsIgnoreCase("evenodd") || b.get(0).equalsIgnoreCase("nonzero"))) return null;
        if (evenOdd && !a.get(0).equalsIgnoreCase(b.get(0))) return null;
        if (a.size() != b.size()) return null;
        List<String> out = new ArrayList<>();
        for (int i = evenOdd ? 1 : 0; i < a.size(); i++) {
            List<String> pa = splitTop(a.get(i), ' '), pb = splitTop(b.get(i), ' ');
            if (pa.size() != 2 || pb.size() != 2) return null;
            String x = lp(pa.get(0), pb.get(0), t), y = lp(pa.get(1), pb.get(1), t);
            if (x == null || y == null) return null;
            out.add(x + " " + y);
        }
        return (evenOdd ? a.get(0) + ", " : "") + String.join(", ", out);
    }
}
