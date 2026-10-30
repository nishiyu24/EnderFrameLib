package com.nishiyu.enderframelib.core.paint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 画像の候補の選択: {@code <img srcset sizes>}（HTML §4.8.4.3）と {@code image-set()}（CSS Images 4 §2.3）。
 *
 * 候補は {@code (url, density)}。{@code w} 記述子は {@code sizes} で決めた描画幅で割って密度にする。
 * 選び方は仕様どおり、デバイスピクセル比以上で最小の密度、無ければ最大の密度。
 * 選んだ画像の固有サイズは、画像の画素数を選んだ密度で割った CSS px になる。
 *
 * デバイスピクセル比は画面ごとの値なので、状態は持たず、呼び出し側が文書ごとの値
 * （{@code Viewport#devicePixelRatio}）を渡す。
 */
public final class ImageSources {

    private ImageSources() {}

    /** 候補。{@code density} は 1 CSS px あたりの画像の画素数。 */
    public record Candidate(String url, double density) {}

    // ------------------------------------------------------------------ srcset / sizes

    private record Raw(String url, double x, double w) {}

    /**
     * {@code srcset} と {@code sizes} から選ぶ。{@code srcset} が空・不正なら {@code fallback}（{@code src}）を密度 1 で返す。
     *
     * @param viewportW ビューポートの幅（CSS px。{@code sizes} の {@code vw} と media 条件の基準）
     * @param viewportH ビューポートの高さ
     */
    public static ImageChoice selectSrcset(String srcset, String sizes, String fallback, double viewportW, double viewportH, double dppx) {
        List<Raw> raws = parseSrcset(srcset);
        if (raws.isEmpty()) return fallback == null || fallback.isBlank() ? null : new ImageChoice(fallback.trim(), 1);
        boolean anyW = false;
        for (Raw r : raws) anyW |= r.w > 0;
        double sourceSize = anyW ? sourceSize(sizes, viewportW, viewportH) : 0;
        List<Candidate> cands = new ArrayList<>();
        for (Raw r : raws) {
            double density;
            if (r.w > 0) {
                if (!(sourceSize > 0)) continue;
                density = r.w / sourceSize;
            } else {
                density = r.x > 0 ? r.x : 1;
            }
            cands.add(new Candidate(r.url, density));
        }
        // src は密度 1 の候補として加わる（w 記述子だけの srcset では加えない: HTML §4.8.4.3.? のとおり src は 1x）
        if (!anyW && fallback != null && !fallback.isBlank()
                && cands.stream().noneMatch(c -> c.density == 1)) {
            cands.add(new Candidate(fallback.trim(), 1));
        }
        return pick(cands, dppx);
    }

    static List<Raw> parseSrcset(String srcset) {
        List<Raw> out = new ArrayList<>();
        if (srcset == null) return out;
        int i = 0;
        int n = srcset.length();
        while (i < n) {
            while (i < n && (Character.isWhitespace(srcset.charAt(i)) || srcset.charAt(i) == ',')) i++;
            if (i >= n) break;
            int start = i;
            while (i < n && !Character.isWhitespace(srcset.charAt(i))) i++;
            String url = srcset.substring(start, i);
            // URL 末尾のカンマは区切り（記述子なし）。URL 内のカンマ（data: など）は残す
            boolean trailingComma = false;
            while (url.endsWith(",")) {
                url = url.substring(0, url.length() - 1);
                trailingComma = true;
            }
            String desc = "";
            if (!trailingComma) {
                int dStart = i;
                int paren = 0;
                while (i < n && !(srcset.charAt(i) == ',' && paren == 0)) {
                    if (srcset.charAt(i) == '(') paren++;
                    else if (srcset.charAt(i) == ')') paren = Math.max(0, paren - 1);
                    i++;
                }
                desc = srcset.substring(dStart, i).trim();
            }
            if (url.isEmpty()) continue;
            double x = 0, w = 0;
            if (desc.isEmpty()) {
                x = 1;
            } else {
                String d = desc.toLowerCase(Locale.ROOT);
                try {
                    if (d.endsWith("x")) x = Double.parseDouble(d.substring(0, d.length() - 1));
                    else if (d.endsWith("w")) w = Integer.parseInt(d.substring(0, d.length() - 1));
                    else continue;
                } catch (NumberFormatException e) {
                    continue;
                }
                if ((d.endsWith("x") && !(x > 0)) || (d.endsWith("w") && w <= 0)) continue;
            }
            out.add(new Raw(url, x, w));
        }
        return out;
    }

    /** 最も近い密度を選ぶ: dppx 以上で最小、無ければ最大。同じ密度は先のもの。 */
    static ImageChoice pick(List<Candidate> cands, double dppx) {
        if (cands.isEmpty()) return null;
        Candidate best = null;
        for (Candidate c : cands) {
            if (best == null) best = c;
            else if (best.density < dppx) {
                if (c.density > best.density) best = c;
            } else if (c.density >= dppx && c.density < best.density) {
                best = c;
            }
        }
        return new ImageChoice(best.url, best.density);
    }

    /** {@code <img>} の属性（{@code srcset} / {@code sizes} / {@code src}）から選ぶ。何も無ければ null。 */
    public static ImageChoice forImg(java.util.Map<String, String> attrs, double viewportW, double viewportH, double dppx) {
        String src = attrs.get("src");
        String srcset = attrs.get("srcset");
        if (srcset == null || srcset.isBlank()) {
            return src == null || src.isBlank() ? null : new ImageChoice(src.trim(), 1);
        }
        return selectSrcset(srcset, attrs.get("sizes"), src, viewportW, viewportH, dppx);
    }

    // ------------------------------------------------------------------ sizes

    /** {@code sizes} 属性の描画幅（CSS px）。条件に合う最初のもの。無ければ 100vw。 */
    public static double sourceSize(String sizes, double vw, double vh) {
        if (sizes != null && !sizes.isBlank()) {
            for (String part : splitTopLevel(sizes, ',')) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                int split = lastTopLevelSpace(p);
                String cond = split < 0 ? "" : p.substring(0, split).trim();
                String len = split < 0 ? p : p.substring(split + 1).trim();
                if (!cond.isEmpty() && !mediaMatches(cond, vw, vh)) continue;
                double v = sizeLength(len, vw, vh);
                if (v >= 0) return v;
            }
        }
        return vw;
    }

    private static List<String> splitTopLevel(String s, char sep) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth = Math.max(0, depth - 1);
            else if (c == sep && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    private static int lastTopLevelSpace(String s) {
        int depth = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == ')') depth++;
            else if (c == '(') depth--;
            else if (Character.isWhitespace(c) && depth == 0) return i;
        }
        return -1;
    }

    /** {@code (min-width: 600px) and (max-width: 900px)} など、幅と高さの範囲だけ読む。読めないものは偽。 */
    static boolean mediaMatches(String cond, double vw, double vh) {
        String c = cond.trim().toLowerCase(Locale.ROOT);
        if (c.startsWith("not ")) return !mediaMatches(c.substring(4), vw, vh);
        for (String term : c.split("\\s+and\\s+")) {
            String t = term.trim();
            if (t.equals("all") || t.equals("screen")) continue;
            if (!t.startsWith("(") || !t.endsWith(")")) return false;
            String body = t.substring(1, t.length() - 1);
            int colon = body.indexOf(':');
            if (colon < 0) return false;
            String feature = body.substring(0, colon).trim();
            double v = sizeLength(body.substring(colon + 1).trim(), vw, vh);
            if (v < 0) return false;
            switch (feature) {
                case "min-width" -> { if (!(vw >= v)) return false; }
                case "max-width" -> { if (!(vw <= v)) return false; }
                case "width" -> { if (vw != v) return false; }
                case "min-height" -> { if (!(vh >= v)) return false; }
                case "max-height" -> { if (!(vh <= v)) return false; }
                case "height" -> { if (vh != v) return false; }
                default -> { return false; }
            }
        }
        return true;
    }

    /** px / vw / vh / em / rem と {@code calc()} の加減。負や読めない値は -1。 */
    static double sizeLength(String s, double vw, double vh) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("calc(") && t.endsWith(")")) {
            String inner = t.substring(5, t.length() - 1).trim();
            return calc(inner, vw, vh);
        }
        return simpleLength(t, vw, vh);
    }

    private static double simpleLength(String t, double vw, double vh) {
        try {
            if (t.endsWith("vw")) return Double.parseDouble(t.substring(0, t.length() - 2)) * vw / 100;
            if (t.endsWith("vh")) return Double.parseDouble(t.substring(0, t.length() - 2)) * vh / 100;
            if (t.endsWith("rem")) return Double.parseDouble(t.substring(0, t.length() - 3)) * 16;
            if (t.endsWith("em")) return Double.parseDouble(t.substring(0, t.length() - 2)) * 16;
            if (t.endsWith("px")) return Double.parseDouble(t.substring(0, t.length() - 2));
            if (t.equals("0")) return 0;
        } catch (NumberFormatException e) {
            return -1;
        }
        return -1;
    }

    /** 項を {@code +} / {@code -}（前後に空白が要る）で足し引きするだけの {@code calc()}。 */
    private static double calc(String expr, double vw, double vh) {
        String[] tokens = expr.split("\\s+");
        if (tokens.length % 2 == 0) return -1;
        double acc = simpleLength(tokens[0], vw, vh);
        if (acc < 0 && !tokens[0].startsWith("-")) return -1;
        for (int i = 1; i + 1 < tokens.length; i += 2) {
            double v = simpleLength(tokens[i + 1], vw, vh);
            if (v < 0) return -1;
            switch (tokens[i]) {
                case "+" -> acc += v;
                case "-" -> acc -= v;
                default -> { return -1; }
            }
        }
        return Math.max(0, acc);
    }

    // ------------------------------------------------------------------ image-set()

    /**
     * {@code image-set(url(a.png) 1x, url("b.png") 2x, "c.png" 3x, url(d.png) 96dpi)} から選ぶ。
     * {@code image-set} でなければ null。{@code dpi} / {@code dpcm} は 96dpi = 1x に直す。
     */
    public static ImageChoice selectImageSet(String value, double dppx) {
        if (value == null) return null;
        String v = value.trim();
        String lower = v.toLowerCase(Locale.ROOT);
        String prefix = lower.startsWith("-webkit-image-set(") ? "-webkit-image-set(" : lower.startsWith("image-set(") ? "image-set(" : null;
        if (prefix == null || !v.endsWith(")")) return null;
        String inner = v.substring(prefix.length(), v.length() - 1);
        List<Candidate> cands = new ArrayList<>();
        for (String option : splitTopLevel(inner, ',')) {
            String o = option.trim();
            if (o.isEmpty()) continue;
            String url;
            String rest;
            if (o.regionMatches(true, 0, "url(", 0, 4)) {
                int close = matchingParen(o, 3);
                if (close < 0) continue;
                url = unquote(o.substring(4, close).trim());
                rest = o.substring(close + 1).trim();
            } else if (o.startsWith("\"") || o.startsWith("'")) {
                char q = o.charAt(0);
                int end = o.indexOf(q, 1);
                if (end < 0) continue;
                url = o.substring(1, end);
                rest = o.substring(end + 1).trim();
            } else {
                continue;
            }
            if (url.isEmpty()) continue;
            double density = 1;
            for (String word : rest.split("\\s+")) {
                String w = word.toLowerCase(Locale.ROOT);
                if (w.isEmpty() || w.startsWith("type(")) continue;
                density = resolution(w, density);
            }
            if (!(density > 0)) continue;
            cands.add(new Candidate(url, density));
        }
        return pick(cands, dppx);
    }

    public static boolean isImageSet(String value) {
        if (value == null) return false;
        String l = value.trim().toLowerCase(Locale.ROOT);
        return l.startsWith("image-set(") || l.startsWith("-webkit-image-set(");
    }

    private static double resolution(String w, double def) {
        try {
            if (w.endsWith("dppx")) return Double.parseDouble(w.substring(0, w.length() - 4));
            if (w.endsWith("dpcm")) return Double.parseDouble(w.substring(0, w.length() - 4)) * 2.54 / 96;
            if (w.endsWith("dpi")) return Double.parseDouble(w.substring(0, w.length() - 3)) / 96;
            if (w.endsWith("x")) return Double.parseDouble(w.substring(0, w.length() - 1));
        } catch (NumberFormatException e) {
            return -1;
        }
        return def;
    }

    private static int matchingParen(String s, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.startsWith("\"") || s.startsWith("'")) && s.charAt(s.length() - 1) == s.charAt(0)) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
