package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.layout.GridLayoutEngine.SizeKind;
import com.nishiyu.enderframelib.core.layout.GridLayoutEngine.TrackDef;
import com.nishiyu.enderframelib.core.layout.GridLayoutEngine.TrackSize;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.LengthResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** トラックの並びと、各線の名前（{@code lineNames.get(i)} は i 番目の線）。 */
/** グリッドのトラックの並びと線の名前（{@code grid-template-rows} / {@code grid-template-columns} の解析結果）。A8 で {@code GridLayoutEngine} から分けた。 */
final class TrackList {
    final List<TrackDef> tracks = new ArrayList<>();
    final List<List<String>> lineNames = new ArrayList<>();
    /** {@code repeat(auto-fit)} が作ったトラックの位置。アイテムが入らなければ潰す。 */
    final java.util.BitSet autoFit = new java.util.BitSet();

    private void addNames(List<String> names) {
        while (lineNames.size() <= tracks.size()) lineNames.add(new ArrayList<>());
        lineNames.get(tracks.size()).addAll(names);
    }

    void addTrack(TrackDef def) {
        while (lineNames.size() <= tracks.size()) lineNames.add(new ArrayList<>());
        tracks.add(def);
    }

    /** {@code name} を持つ {@code nth} 番目（1 始まり）の線の番号。無ければ null。 */
    Integer findLine(String name, int nth) {
        int seen = 0;
        for (int i = 0; i < lineNames.size(); i++) {
            if (lineNames.get(i).contains(name) && ++seen == nth) return i;
        }
        return null;
    }

    /**
     * {@code grid-template-columns} / {@code -rows} を読む。{@code available} は % と
     * {@code repeat(auto-fill)} の基準（不定なら NaN）。
     */
    static TrackList parse(String value, float available, float gap, ComputedStyle style, float rootW, float rootH) {
        TrackList list = new TrackList();
        if (value == null || value.isBlank() || "none".equalsIgnoreCase(value.trim())) return list;
        parseInto(list, value, available, gap, style, rootW, rootH);
        while (list.lineNames.size() <= list.tracks.size()) list.lineNames.add(new ArrayList<>());
        return list;
    }

    private static void parseInto(TrackList list, String value, float available, float gap,
                                  ComputedStyle style, float rootW, float rootH) {
        for (String token : GridLayoutEngine.splitTrackList(value)) {
            String t = token.trim();
            String lower = t.toLowerCase(Locale.ROOT);
            if (t.startsWith("[") && t.endsWith("]")) {
                List<String> names = new ArrayList<>();
                for (String n : t.substring(1, t.length() - 1).trim().split("\\s+")) if (!n.isEmpty()) names.add(n);
                list.addNames(names);
            } else if (lower.startsWith("repeat(") && lower.endsWith(")")) {
                String inner = t.substring(7, t.length() - 1);
                int comma = GridLayoutEngine.topLevelComma(inner);
                if (comma < 0) continue;
                String countStr = inner.substring(0, comma).trim().toLowerCase(Locale.ROOT);
                String body = inner.substring(comma + 1).trim();
                int count;
                if (countStr.equals("auto-fill") || countStr.equals("auto-fit")) {
                    count = autoRepeatCount(body, available, gap, style, rootW, rootH);
                } else {
                    try {
                        count = Math.max(1, Math.min(10000, Integer.parseInt(countStr)));
                    } catch (NumberFormatException e) {
                        continue;
                    }
                }
                int from = list.tracks.size();
                for (int i = 0; i < count; i++) parseInto(list, body, available, gap, style, rootW, rootH);
                if (countStr.equals("auto-fit")) list.autoFit.set(from, list.tracks.size());
            } else {
                list.addTrack(parseTrack(t, available, style, rootW, rootH));
            }
        }
    }

    /**
     * {@code repeat(auto-fill, ...)} の繰り返し数。トラックの寸法（max が確定していなければ min）で
     * 利用できる幅に入るだけ。空間が不定なら 1。
     * {@code auto-fit} の個数は {@code auto-fill} と同じで、空の繰り返しを潰すのは配置の後（{@link #collapseEmptyAutoFitTracks}）。
     */
    private static int autoRepeatCount(String body, float available, float gap, ComputedStyle style, float rootW, float rootH) {
        if (Float.isNaN(available)) return 1;
        TrackList one = new TrackList();
        parseInto(one, body, available, gap, style, rootW, rootH);
        float size = 0;
        for (TrackDef d : one.tracks) {
            float s = d.max().kind() == SizeKind.FIXED ? d.max().value() : (d.min().kind() == SizeKind.FIXED ? d.min().value() : 0);
            size += s;
        }
        int n = one.tracks.size();
        float unit = size + gap * n;
        if (unit <= 0) return 1;
        return Math.max(1, (int) Math.floor((available + gap) / unit + 1e-4));
    }

    private static final ThreadLocal<Map<String, TrackDef>> STATIC_TRACK_CACHE =
            ThreadLocal.withInitial(java.util.HashMap::new);

    private static TrackDef parseTrack(String t, float available, ComputedStyle style, float rootW, float rootH) {
        if (isStaticTrackToken(t)) {
            Map<String, TrackDef> cache = STATIC_TRACK_CACHE.get();
            TrackDef hit = cache.get(t);
            if (hit != null) return hit;
            TrackDef parsed = parseTrackUncached(t, available, style, rootW, rootH);
            if (cache.size() >= 256) cache.clear();
            cache.put(t, parsed);
            return parsed;
        }
        return parseTrackUncached(t, available, style, rootW, rootH);
    }

    private static boolean isStaticTrackToken(String t) {
        // %, em/rem/ex/ch, viewport units, calc/var, or font-dependent units require live context
        for (int i = 0, n = t.length(); i < n; i++) {
            char c = t.charAt(i);
            if (c == '%' || c == 'e' || c == 'E' || c == 'v' || c == 'V' || c == 'c' || c == 'C') {
                String lower = t.toLowerCase(Locale.ROOT);
                return lower.equals("auto") || lower.equals("min-content") || lower.equals("max-content")
                        || (!lower.contains("%") && !lower.contains("em") && !lower.contains("ex")
                        && !lower.contains("ch") && !lower.contains("vw") && !lower.contains("vh")
                        && !lower.contains("vmin") && !lower.contains("vmax") && !lower.contains("calc")
                        && !lower.contains("fit-content"));
            }
        }
        return true;
    }

    private static TrackDef parseTrackUncached(String t, float available, ComputedStyle style, float rootW, float rootH) {
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.startsWith("minmax(") && lower.endsWith(")")) {
            String inner = t.substring(7, t.length() - 1);
            int comma = GridLayoutEngine.topLevelComma(inner);
            if (comma < 0) return TrackDef.AUTO;
            TrackSize min = parseSize(inner.substring(0, comma).trim(), available, style, rootW, rootH);
            TrackSize max = parseSize(inner.substring(comma + 1).trim(), available, style, rootW, rootH);
            // fr は min に置けない（auto として扱う）
            if (min.kind() == SizeKind.FR) min = TrackSize.AUTO;
            return new TrackDef(min, max);
        }
        TrackSize size = parseSize(t, available, style, rootW, rootH);
        return switch (size.kind()) {
            case FR -> new TrackDef(TrackSize.AUTO, size);
            case FIT_CONTENT -> new TrackDef(TrackSize.AUTO, size);
            default -> new TrackDef(size, size);
        };
    }

    private static TrackSize parseSize(String t, float available, ComputedStyle style, float rootW, float rootH) {
        String lower = t.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "auto" -> {
                return TrackSize.AUTO;
            }
            case "min-content" -> {
                return new TrackSize(SizeKind.MIN_CONTENT, 0);
            }
            case "max-content" -> {
                return new TrackSize(SizeKind.MAX_CONTENT, 0);
            }
            default -> {
            }
        }
        if (lower.endsWith("fr")) {
            String num = lower.substring(0, lower.length() - 2).trim();
            try {
                return new TrackSize(SizeKind.FR, num.isEmpty() ? 1f : Math.max(0, Float.parseFloat(num)));
            } catch (NumberFormatException e) {
                return TrackSize.AUTO;
            }
        }
        if (lower.startsWith("fit-content(") && lower.endsWith(")")) {
            String arg = t.substring(12, t.length() - 1).trim();
            if (arg.contains("%") && Float.isNaN(available)) return new TrackSize(SizeKind.MAX_CONTENT, 0);
            return new TrackSize(SizeKind.FIT_CONTENT, LengthResolver.parseDim(arg, Float.isNaN(available) ? 0 : available,
                    rootW, rootH, style.fontSizePx(), style.rootFontSizePx()));
        }
        // 不定の空間に対する % は auto（CSS Grid §7.2.1）
        if (lower.contains("%") && !lower.startsWith("calc") && Float.isNaN(available)) return TrackSize.AUTO;
        return new TrackSize(SizeKind.FIXED, LengthResolver.parseDim(t, Float.isNaN(available) ? 0 : available,
                rootW, rootH, style.fontSizePx(), style.rootFontSizePx()));
    }
}
