package com.nishiyu.enderframelib.core.text.font;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * OpenType の GSUB（字形置換）を純 Java で引く。追加のネイティブ依存（HarfBuzz）なしで、
 * 合字（{@code liga}/{@code clig}/{@code rlig}）・分解/合成（{@code ccmp}）・単一置換を適用する。
 *
 * <p>対応する lookup は Type 1（単一）・2（複数）・4（合字）・7（拡張）。文脈依存の Type 5/6/8 と
 * lookupFlag による字の読み飛ばしは未対応で、該当する lookup は無いものとして扱う。壊れた表も無いものとして扱う。</p>
 */
public final class GsubTable {

    public static final GsubTable NONE = new GsubTable(null, List.of());

    /** 既定で有効にする機能（横書きの標準の合字・分解）。 */
    public static final Set<String> DEFAULT_FEATURES = Set.of("ccmp", "liga", "clig", "rlig");

    private final ByteBuffer b;
    /** 適用順（lookup の番号順）の lookup ごとの副表（GSUB 内の絶対位置と種別）。 */
    private final List<int[][]> lookups;

    private GsubTable(ByteBuffer b, List<int[][]> lookups) {
        this.b = b;
        this.lookups = lookups;
    }

    public static GsubTable parse(byte[] gsub) {
        return parse(gsub, DEFAULT_FEATURES);
    }

    public static GsubTable parse(byte[] gsub, Set<String> features) {
        if (gsub == null || gsub.length < 10) return NONE;
        try {
            ByteBuffer b = ByteBuffer.wrap(gsub).order(ByteOrder.BIG_ENDIAN);
            List<int[][]> lookups = collect(b, features);
            return lookups.isEmpty() ? NONE : new GsubTable(b, lookups);
        } catch (RuntimeException e) {
            return NONE;
        }
    }

    public boolean isEmpty() {
        return lookups.isEmpty();
    }

    /** 字形列に置換を適用した新しい列。変わらなければ同じ配列をそのまま返す。 */
    public int[] apply(int[] glyphs) {
        if (isEmpty() || glyphs.length == 0) return glyphs;
        try {
            int[] cur = glyphs;
            for (int[][] lookup : lookups) cur = applyLookup(lookup, cur);
            return cur;
        } catch (RuntimeException e) {
            return glyphs;
        }
    }

    private int[] applyLookup(int[][] subtables, int[] in) {
        int[] out = new int[in.length + 8];
        int n = 0;
        boolean changed = false;
        int i = 0;
        while (i < in.length) {
            int consumed = 0;
            for (int[] st : subtables) {
                int[] r = applyAt(st[0], st[1], in, i);
                if (r != null) {
                    consumed = r[0];
                    if (n + r.length - 1 > out.length) out = Arrays.copyOf(out, Math.max(out.length * 2, n + r.length));
                    for (int k = 1; k < r.length; k++) out[n++] = r[k];
                    changed = true;
                    break;
                }
            }
            if (consumed == 0) {
                if (n == out.length) out = Arrays.copyOf(out, out.length * 2);
                out[n++] = in[i++];
            } else {
                i += consumed;
            }
        }
        return changed ? Arrays.copyOf(out, n) : in;
    }

    /** 副表を位置 {@code at} に当てる。当たれば {消費した字数, 置換後の字形...}、外れれば null。 */
    private int[] applyAt(int type, int sub, int[] g, int at) {
        int glyph = g[at];
        switch (type) {
            case 1 -> {
                int format = u16(sub);
                int idx = coverage(sub + u16(sub + 2), glyph);
                if (idx < 0) return null;
                if (format == 1) return new int[] {1, (glyph + b.getShort(sub + 4)) & 0xFFFF};
                int count = u16(sub + 4);
                return idx < count ? new int[] {1, u16(sub + 6 + idx * 2)} : null;
            }
            case 2 -> {
                int idx = coverage(sub + u16(sub + 2), glyph);
                if (idx < 0 || idx >= u16(sub + 4)) return null;
                int seq = sub + u16(sub + 6 + idx * 2);
                int count = u16(seq);
                if (count == 0) return null;
                int[] r = new int[count + 1];
                r[0] = 1;
                for (int k = 0; k < count; k++) r[k + 1] = u16(seq + 2 + k * 2);
                return r;
            }
            case 4 -> {
                int idx = coverage(sub + u16(sub + 2), glyph);
                if (idx < 0 || idx >= u16(sub + 4)) return null;
                int set = sub + u16(sub + 6 + idx * 2);
                int ligCount = u16(set);
                for (int l = 0; l < ligCount; l++) {
                    int lig = set + u16(set + 2 + l * 2);
                    int comps = u16(lig + 2);
                    if (comps < 1 || at + comps > g.length) continue;
                    boolean match = true;
                    for (int k = 1; k < comps; k++) {
                        if (u16(lig + 4 + (k - 1) * 2) != g[at + k]) { match = false; break; }
                    }
                    if (match) return new int[] {comps, u16(lig)};
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    // ---- 表の読み出し

    private static List<int[][]> collect(ByteBuffer b, Set<String> wanted) {
        int scriptList = u16(b, 4), featureList = u16(b, 6), lookupList = u16(b, 8);

        TreeSet<Integer> featureIndices = new TreeSet<>();
        int scriptCount = u16(b, scriptList);
        for (int s = 0; s < scriptCount; s++) {
            int script = scriptList + u16(b, scriptList + 2 + s * 6 + 4);
            int def = u16(b, script);
            if (def != 0) langSysFeatures(b, script + def, featureIndices);
            int langCount = u16(b, script + 2);
            for (int l = 0; l < langCount; l++) langSysFeatures(b, script + u16(b, script + 4 + l * 6 + 4), featureIndices);
        }

        TreeSet<Integer> lookupIndices = new TreeSet<>();
        int featureCount = u16(b, featureList);
        for (int fi : featureIndices) {
            if (fi >= featureCount) continue;
            int rec = featureList + 2 + fi * 6;
            if (!wanted.contains(tagName(b.getInt(rec)))) continue;
            int feature = featureList + u16(b, rec + 4);
            int count = u16(b, feature + 2);
            for (int i = 0; i < count; i++) lookupIndices.add(u16(b, feature + 4 + i * 2));
        }

        List<int[][]> out = new ArrayList<>();
        int lookupCount = u16(b, lookupList);
        for (int li : lookupIndices) {
            if (li >= lookupCount) continue;
            int lookup = lookupList + u16(b, lookupList + 2 + li * 2);
            int type = u16(b, lookup);
            int subCount = u16(b, lookup + 4);
            List<int[]> subs = new ArrayList<>();
            for (int i = 0; i < subCount; i++) {
                int sub = lookup + u16(b, lookup + 6 + i * 2);
                int subType = type;
                if (type == 7) {
                    subType = u16(b, sub + 2);
                    sub = sub + b.getInt(sub + 4);
                }
                if (subType == 1 || subType == 2 || subType == 4) subs.add(new int[] {subType, sub});
            }
            if (!subs.isEmpty()) out.add(subs.toArray(new int[0][]));
        }
        return out;
    }

    private static void langSysFeatures(ByteBuffer b, int langSys, TreeSet<Integer> out) {
        int required = u16(b, langSys + 2);
        if (required != 0xFFFF) out.add(required);
        int count = u16(b, langSys + 4);
        for (int i = 0; i < count; i++) out.add(u16(b, langSys + 6 + i * 2));
    }

    private int coverage(int table, int glyph) {
        int format = u16(table);
        int count = u16(table + 2);
        int lo = 0, hi = count - 1;
        if (format == 1) {
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int g = u16(table + 4 + mid * 2);
                if (g == glyph) return mid;
                if (g < glyph) lo = mid + 1; else hi = mid - 1;
            }
        } else if (format == 2) {
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int rec = table + 4 + mid * 6;
                int start = u16(rec), end = u16(rec + 2);
                if (glyph < start) hi = mid - 1;
                else if (glyph > end) lo = mid + 1;
                else return u16(rec + 4) + glyph - start;
            }
        }
        return -1;
    }

    private int u16(int pos) {
        return Short.toUnsignedInt(b.getShort(pos));
    }

    private static int u16(ByteBuffer b, int pos) {
        return Short.toUnsignedInt(b.getShort(pos));
    }

    private static String tagName(int tag) {
        return new String(new char[] {(char) (tag >>> 24 & 0xFF), (char) (tag >>> 16 & 0xFF), (char) (tag >>> 8 & 0xFF), (char) (tag & 0xFF)});
    }
}
