package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.css.LengthResolver;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.model.CssKeywords.Alignment;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;

/**
 * グリッドのトラック（Engine v3 M2b-3）: {@code grid-template-columns / -rows}・{@code grid-auto-*} の解析（旧 {@code TrackList} の
 * 移植）と、トラックの寸法の解決（CSS Grid §11 の簡略版。旧 {@code GridLayoutEngine.sizeTracks} の移植）。純粋な static 関数で、
 * 旧経路の文字列の解析の {@code ThreadLocal} の記憶は持ち込まない（計画書 §3.4.2-2）。
 */
final class GridTracks {
    private GridTracks() { }

    enum Kind { FIXED, FR, AUTO, MIN_CONTENT, MAX_CONTENT, FIT_CONTENT }

    /** トラックの寸法の関数の片側（min または max）。 */
    record Size(Kind kind, float value) {
        static final Size AUTO = new Size(Kind.AUTO, 0);

        boolean intrinsic() {
            return kind == Kind.AUTO || kind == Kind.MIN_CONTENT || kind == Kind.MAX_CONTENT || kind == Kind.FIT_CONTENT;
        }
    }

    /** 1 本のトラックの寸法の関数（min と max）。 */
    record Def(Size min, Size max) {
        static final Def AUTO = new Def(Size.AUTO, Size.AUTO);

        boolean intrinsic() {
            return min.intrinsic() || max.intrinsic();
        }
    }

    /** トラックの並びと各線の名前（{@code lineNames.get(i)} は i 番目の線）。1 回の整形の間だけの作業域。 */
    static final class Template {
        final List<Def> tracks = new ArrayList<>();
        final List<List<String>> lineNames = new ArrayList<>();
        /** {@code repeat(auto-fit)} が作ったトラックの位置。項目が入らなければ潰す。 */
        final BitSet autoFit = new BitSet();

        private void addNames(List<String> names) {
            while (lineNames.size() <= tracks.size()) lineNames.add(new ArrayList<>());
            lineNames.get(tracks.size()).addAll(names);
        }

        private void addTrack(Def d) {
            while (lineNames.size() <= tracks.size()) lineNames.add(new ArrayList<>());
            tracks.add(d);
        }

        void nameLine(int line, String name) {
            while (lineNames.size() <= line) lineNames.add(new ArrayList<>());
            lineNames.get(line).add(name);
        }

        /** {@code name} を持つ {@code nth} 番目（1 始まり）の線の番号。無ければ null。 */
        Integer findLine(String name, int nth) {
            int seen = 0;
            for (int i = 0; i < lineNames.size(); i++) {
                if (lineNames.get(i).contains(name) && ++seen == nth) return i;
            }
            return null;
        }
    }

    /** {@code available} は % と {@code repeat(auto-fill)} の基準（不定なら NaN）。 */
    static Template parse(String value, float available, float gap, Style st, LayoutSession s) {
        Template t = new Template();
        if (value == null || value.isBlank() || "none".equalsIgnoreCase(value.trim()) || isSubgrid(value)) return t;
        parseInto(t, value, available, gap, st, s);
        while (t.lineNames.size() <= t.tracks.size()) t.lineNames.add(new ArrayList<>());
        return t;
    }

    /** {@code subgrid}（{@code subgrid [名前]...} を含む）か。新経路では親のトラックを共有せず none と同じに扱う。 */
    static boolean isSubgrid(String value) {
        if (value == null) return false;
        String v = value.trim();
        return v.length() >= 7 && v.regionMatches(true, 0, "subgrid", 0, 7) && (v.length() == 7 || Character.isWhitespace(v.charAt(7)));
    }

    private static void parseInto(Template list, String value, float available, float gap, Style st, LayoutSession s) {
        for (String token : split(value)) {
            String t = token.trim();
            String lower = t.toLowerCase(Locale.ROOT);
            if (t.startsWith("[") && t.endsWith("]")) {
                List<String> names = new ArrayList<>();
                for (String n : t.substring(1, t.length() - 1).trim().split("\\s+")) if (!n.isEmpty()) names.add(n);
                list.addNames(names);
            } else if (lower.startsWith("repeat(") && lower.endsWith(")")) {
                String inner = t.substring(7, t.length() - 1);
                int comma = topLevelComma(inner);
                if (comma < 0) continue;
                String countStr = inner.substring(0, comma).trim().toLowerCase(Locale.ROOT);
                String body = inner.substring(comma + 1).trim();
                int count;
                boolean auto = countStr.equals("auto-fill") || countStr.equals("auto-fit");
                if (auto) {
                    count = autoRepeatCount(body, available, gap, st, s);
                } else {
                    try {
                        count = Math.max(1, Math.min(10000, Integer.parseInt(countStr)));
                    } catch (NumberFormatException e) {
                        continue;
                    }
                }
                int from = list.tracks.size();
                for (int i = 0; i < count; i++) parseInto(list, body, available, gap, st, s);
                if (countStr.equals("auto-fit")) list.autoFit.set(from, list.tracks.size());
            } else {
                list.addTrack(parseTrack(t, available, st, s));
            }
        }
    }

    /** {@code repeat(auto-fill / auto-fit, ...)} の繰り返し数: 確定した大きさ（max、無ければ min）で使える幅に入るだけ。不定なら 1。 */
    private static int autoRepeatCount(String body, float available, float gap, Style st, LayoutSession s) {
        if (Float.isNaN(available)) return 1;
        Template one = new Template();
        parseInto(one, body, available, gap, st, s);
        float size = 0;
        for (Def d : one.tracks) size += d.max.kind == Kind.FIXED ? d.max.value : d.min.kind == Kind.FIXED ? d.min.value : 0;
        float unit = size + gap * one.tracks.size();
        if (unit <= 0) return 1;
        return Math.max(1, (int) Math.floor((available + gap) / unit + 1e-4));
    }

    private static Def parseTrack(String t, float available, Style st, LayoutSession s) {
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.startsWith("minmax(") && lower.endsWith(")")) {
            String inner = t.substring(7, t.length() - 1);
            int comma = topLevelComma(inner);
            if (comma < 0) return Def.AUTO;
            Size min = parseSize(inner.substring(0, comma).trim(), available, st, s);
            Size max = parseSize(inner.substring(comma + 1).trim(), available, st, s);
            if (min.kind == Kind.FR) min = Size.AUTO; // fr は min に置けない
            return new Def(min, max);
        }
        Size size = parseSize(t, available, st, s);
        return size.kind == Kind.FR || size.kind == Kind.FIT_CONTENT ? new Def(Size.AUTO, size) : new Def(size, size);
    }

    private static Size parseSize(String t, float available, Style st, LayoutSession s) {
        String lower = t.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "auto" -> { return Size.AUTO; }
            case "min-content" -> { return new Size(Kind.MIN_CONTENT, 0); }
            case "max-content" -> { return new Size(Kind.MAX_CONTENT, 0); }
            default -> { }
        }
        float fs = st.facts.fontStyle.fontSizePx(), rfs = st.inherited.rootFontSizePx;
        if (lower.endsWith("fr")) {
            String num = lower.substring(0, lower.length() - 2).trim();
            try {
                return new Size(Kind.FR, num.isEmpty() ? 1f : Math.max(0, Float.parseFloat(num)));
            } catch (NumberFormatException e) {
                return Size.AUTO;
            }
        }
        if (lower.startsWith("fit-content(") && lower.endsWith(")")) {
            String arg = t.substring(12, t.length() - 1).trim();
            if (arg.contains("%") && Float.isNaN(available)) return new Size(Kind.MAX_CONTENT, 0);
            return new Size(Kind.FIT_CONTENT, LengthResolver.parseDim(arg, Float.isNaN(available) ? 0 : available,
                    s.viewportW(), s.viewportH(), fs, rfs));
        }
        // 不定の空間に対する % は auto（CSS Grid §7.2.1）
        if (lower.contains("%") && !lower.startsWith("calc") && Float.isNaN(available)) return Size.AUTO;
        return new Size(Kind.FIXED, LengthResolver.parseDim(t, Float.isNaN(available) ? 0 : available, s.viewportW(), s.viewportH(), fs, rfs));
    }

    static int topLevelComma(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) return i;
        }
        return -1;
    }

    /** 最上位の空白で区切る（括弧と角括弧の中は区切らない）。 */
    private static List<String> split(String def) {
        List<String> list = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < def.length(); i++) {
            char c = def.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            if (Character.isWhitespace(c) && depth == 0) {
                if (!cur.isEmpty()) {
                    list.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) list.add(cur.toString());
        return list;
    }

    // ---- 寸法の解決 ----

    /**
     * トラックの寸法を解く: 固定長 → 内容依存のトラックを 1 本だけにまたがる項目で決め、複数にまたがる項目の不足を内容依存の
     * トラックに等分 → 上限まで伸ばす（不定なら上限そのもの）→ fr に残りを配る → {@code *-content} が normal / stretch なら
     * auto トラックを伸ばす。{@code minOnly} なら min-content の制約で解く（内在サイズの min）。
     */
    static float[] size(List<Def> defs, List<GridItem> items, boolean columns, float available, float gap, Alignment distribution,
                        boolean minOnly) {
        int n = defs.size();
        float[] base = new float[n], limit = new float[n];
        for (int i = 0; i < n; i++) {
            Def d = defs.get(i);
            base[i] = d.min.kind == Kind.FIXED ? d.min.value : 0;
            limit[i] = switch (d.max.kind) {
                case FIXED -> d.max.value;
                case FR -> Float.POSITIVE_INFINITY;
                default -> 0;
            };
        }
        for (GridItem it : items) { // 1 本だけにまたがる項目
            if (it.span(columns) != 1) continue;
            int i = it.start(columns);
            Def d = defs.get(i);
            float minC = it.minContribution(columns), maxC = minOnly ? minC : it.maxContribution(columns);
            if (d.min.intrinsic()) base[i] = Math.max(base[i], d.min.kind == Kind.MAX_CONTENT ? maxC : minC);
            if (d.max.intrinsic()) {
                float c = d.max.kind == Kind.MIN_CONTENT ? minC : maxC;
                if (d.max.kind == Kind.FIT_CONTENT) c = Math.min(c, Math.max(base[i], d.max.value));
                limit[i] = Math.max(limit[i], c);
            }
        }
        for (GridItem it : items) { // 複数にまたがる項目: 足りない分を、またがる内容依存のトラックに等分する
            int start = it.start(columns), span = it.span(columns);
            if (span < 2) continue;
            List<Integer> intrinsic = new ArrayList<>();
            boolean flexible = false;
            float covered = gap * (span - 1);
            for (int i = start; i < start + span; i++) {
                covered += base[i];
                if (defs.get(i).max.kind == Kind.FR) flexible = true;
                else if (defs.get(i).intrinsic()) intrinsic.add(i);
            }
            if (flexible || intrinsic.isEmpty()) continue;
            float minC = it.minContribution(columns), maxC = minOnly ? minC : it.maxContribution(columns);
            float deficit = minC - covered;
            if (deficit > 0) for (int i : intrinsic) base[i] += deficit / intrinsic.size();
            float growth = maxC - covered;
            if (growth > 0) for (int i : intrinsic) limit[i] = Math.max(limit[i], base[i] + growth / intrinsic.size());
        }
        for (int i = 0; i < n; i++) if (limit[i] < base[i]) limit[i] = base[i];

        float gaps = gap * Math.max(0, n - 1);
        boolean definite = !Float.isNaN(available);
        if (!definite) {
            for (int i = 0; i < n; i++) if (!Float.isInfinite(limit[i])) base[i] = limit[i];
        } else {
            distributeUpToLimits(base, limit, available - gaps - sum(base));
        }
        float frSum = 0;
        for (Def d : defs) if (d.max.kind == Kind.FR) frSum += d.max.value;
        if (frSum > 0) {
            float fraction;
            if (definite) {
                fraction = flexFraction(defs, base, available - gaps);
            } else { // 不定の空間では、各トラックの内容を収める最大の 1fr（§11.7.1）
                fraction = 0;
                for (int i = 0; i < n; i++) {
                    Def d = defs.get(i);
                    if (d.max.kind == Kind.FR && d.max.value > 0) fraction = Math.max(fraction, (d.max.value >= 1 ? base[i] : 0) / Math.max(1, d.max.value));
                }
                for (GridItem it : items) {
                    int i = it.start(columns);
                    if (it.span(columns) == 1 && defs.get(i).max.kind == Kind.FR) {
                        float c = minOnly ? it.minContribution(columns) : it.maxContribution(columns);
                        fraction = Math.max(fraction, c / Math.max(1, defs.get(i).max.value));
                    }
                }
            }
            for (int i = 0; i < n; i++) if (defs.get(i).max.kind == Kind.FR) base[i] = Math.max(base[i], fraction * defs.get(i).max.value);
        } else if (definite && (distribution == null || distribution == Alignment.NORMAL || distribution == Alignment.STRETCH)) {
            List<Integer> autos = new ArrayList<>(); // auto トラックの stretch: 残りを等分する（§11.8）
            for (int i = 0; i < n; i++) if (defs.get(i).max.kind == Kind.AUTO) autos.add(i);
            float free = available - gaps - sum(base);
            if (free > 0 && !autos.isEmpty()) for (int i : autos) base[i] += free / autos.size();
        }
        return base;
    }

    /** 余りを上限に届くまで等分する。上限に届いたトラックの分は残りに回す。 */
    private static void distributeUpToLimits(float[] base, float[] limit, float free) {
        for (int guard = 0; guard < base.length + 1 && free > 0.001f; guard++) {
            int growable = 0;
            for (int i = 0; i < base.length; i++) if (base[i] < limit[i] && !Float.isInfinite(limit[i])) growable++;
            if (growable == 0) return;
            float share = free / growable, used = 0;
            for (int i = 0; i < base.length; i++) {
                if (base[i] >= limit[i] || Float.isInfinite(limit[i])) continue;
                float add = Math.min(share, limit[i] - base[i]);
                base[i] += add;
                used += add;
            }
            free -= used;
        }
    }

    /** 確定した空間での 1fr の大きさ。fr 分より大きな下限を持つトラックは固定扱いにして解き直す。 */
    private static float flexFraction(List<Def> defs, float[] base, float space) {
        boolean[] inflexible = new boolean[defs.size()];
        for (int guard = 0; guard < defs.size() + 1; guard++) {
            float leftover = space, frSum = 0;
            for (int i = 0; i < defs.size(); i++) {
                if (defs.get(i).max.kind == Kind.FR && !inflexible[i]) frSum += defs.get(i).max.value;
                else leftover -= base[i];
            }
            if (frSum <= 0) return 0;
            float fraction = Math.max(0, leftover) / Math.max(1, frSum); // 係数の和が 1 未満なら空間の一部しか使わない
            boolean changed = false;
            for (int i = 0; i < defs.size(); i++) {
                Def d = defs.get(i);
                if (d.max.kind == Kind.FR && !inflexible[i] && fraction * d.max.value < base[i]) {
                    inflexible[i] = true;
                    changed = true;
                }
            }
            if (!changed) return fraction;
        }
        return 0;
    }

    /** トラックの始点の位置（末尾に終端を 1 つ足す）。トラックの合計が狭ければ {@code *-content} で寄せる・間を広げる。 */
    static float[] offsets(float[] sizes, float gap, float inner, Alignment distribution, boolean safe) {
        int n = sizes.length;
        float total = sum(sizes) + gap * Math.max(0, n - 1);
        float free = Float.isNaN(inner) ? 0 : inner - total;
        Alignment a = distribution == null ? Alignment.START : switch (distribution) {
            case NORMAL, STRETCH, START, LEFT, FLEX_START, SELF_START -> Alignment.START;
            case RIGHT, FLEX_END, SELF_END -> Alignment.END;
            default -> distribution;
        };
        if (free < -0.01f && safe) a = Alignment.START;
        float[] d = FlexLines.distribute(a, free, n, gap);
        float[] out = new float[n + 1];
        float pos = d[0];
        for (int i = 0; i < n; i++) {
            out[i] = pos;
            pos += sizes[i] + (i < n - 1 ? d[1] : 0);
        }
        out[n] = pos;
        return out;
    }

    static float sum(float[] v) {
        float s = 0;
        for (float x : v) s += x;
        return s;
    }
}
