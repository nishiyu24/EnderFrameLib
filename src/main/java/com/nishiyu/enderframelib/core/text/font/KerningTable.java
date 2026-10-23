package com.nishiyu.enderframelib.core.text.font;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 字の組み合わせごとの詰め（カーニング）。Chrome（HarfBuzz）と同じく、GPOS の {@code kern} 機能を優先し、
 * GPOS に {@code kern} が無い書体では旧式の {@code kern} 表を使う。
 *
 * 扱うのは横書きの送り幅の調整（PairPos の 1 つ目の字の XAdvance）だけ。
 * 可変フォントでは XAdvance に付いた VariationIndex を GDEF の ItemVariationStore で解いて足す
 * （Noto Sans JP などは太さによって詰めの量が変わる）。
 */
public final class KerningTable {

    public static final KerningTable NONE = new KerningTable(null, List.of(), null, null, null);

    private static final int TAG_KERN = tag("kern");

    private final ByteBuffer gpos;
    /** kern 機能が参照する lookup ごとの PairPos の副表（GPOS 内の絶対位置）。 */
    private final List<List<Integer>> pairSubtables;
    private final ByteBuffer legacyKern;
    /** GDEF（ItemVariationStore を持つもの）と、そのときの正規化座標。可変フォントでなければ null。 */
    private final ByteBuffer gdef;
    private final float[] normalizedCoords;
    private final int varStore;
    private final Map<Long, Short> cache = new ConcurrentHashMap<>();

    private KerningTable(ByteBuffer gpos, List<List<Integer>> pairSubtables, ByteBuffer legacyKern,
                         ByteBuffer gdef, float[] normalizedCoords) {
        this.gpos = gpos;
        this.pairSubtables = pairSubtables;
        this.legacyKern = legacyKern;
        int store = 0;
        if (gdef != null && normalizedCoords != null && gdef.capacity() >= 18 && u16(gdef, 2) >= 3) {
            store = gdef.getInt(14);
        }
        this.varStore = store;
        this.gdef = store > 0 ? gdef : null;
        this.normalizedCoords = store > 0 ? normalizedCoords : null;
    }

    public static KerningTable parse(byte[] gposBytes, byte[] kernBytes) {
        return parse(gposBytes, kernBytes, null, null);
    }

    /**
     * GPOS と kern の表の中身（どちらも無ければ null）から作る。壊れた表は無いものとして扱う。
     *
     * @param gdefBytes        可変フォントの詰めの変化量を解くための GDEF（無ければ null）
     * @param normalizedCoords 可変フォントのインスタンスの正規化座標（-1〜1、軸の順）。可変でなければ null
     */
    public static KerningTable parse(byte[] gposBytes, byte[] kernBytes, byte[] gdefBytes, float[] normalizedCoords) {
        ByteBuffer gpos = gposBytes != null ? ByteBuffer.wrap(gposBytes).order(ByteOrder.BIG_ENDIAN) : null;
        List<List<Integer>> subtables = List.of();
        if (gpos != null) {
            try {
                subtables = kernPairSubtables(gpos);
            } catch (RuntimeException e) {
                subtables = List.of();
            }
        }
        ByteBuffer kern = null;
        if (subtables.isEmpty() && kernBytes != null) kern = ByteBuffer.wrap(kernBytes).order(ByteOrder.BIG_ENDIAN);
        if (subtables.isEmpty() && kern == null) return NONE;
        ByteBuffer gdef = gdefBytes != null ? ByteBuffer.wrap(gdefBytes).order(ByteOrder.BIG_ENDIAN) : null;
        return new KerningTable(subtables.isEmpty() ? null : gpos, subtables, kern, gdef, normalizedCoords);
    }

    public boolean isEmpty() {
        return pairSubtables.isEmpty() && legacyKern == null;
    }

    /** 1 つ目の字の送り幅に足す量（設計単位）。 */
    public int adjustment(int firstGlyph, int secondGlyph) {
        if (isEmpty() || firstGlyph == 0 || secondGlyph == 0) return 0;
        long key = ((long) firstGlyph << 32) | (secondGlyph & 0xFFFFFFFFL);
        Short cached = cache.get(key);
        if (cached != null) return cached;
        int value;
        try {
            value = pairSubtables.isEmpty() ? legacyValue(firstGlyph, secondGlyph) : gposValue(firstGlyph, secondGlyph);
        } catch (RuntimeException e) {
            value = 0;
        }
        cache.put(key, (short) value);
        return value;
    }

    // ---- GPOS

    /** ScriptList の全スクリプト・全言語から kern 機能を集め、その lookup の PairPos 副表を並べる。 */
    private static List<List<Integer>> kernPairSubtables(ByteBuffer b) {
        int scriptList = u16(b, 4);
        int featureList = u16(b, 6);
        int lookupList = u16(b, 8);

        Set<Integer> featureIndices = new LinkedHashSet<>();
        int scriptCount = u16(b, scriptList);
        for (int s = 0; s < scriptCount; s++) {
            int script = scriptList + u16(b, scriptList + 2 + s * 6 + 4);
            int defaultLangSys = u16(b, script);
            if (defaultLangSys != 0) addLangSysFeatures(b, script + defaultLangSys, featureIndices);
            int langCount = u16(b, script + 2);
            for (int l = 0; l < langCount; l++) {
                addLangSysFeatures(b, script + u16(b, script + 4 + l * 6 + 4), featureIndices);
            }
        }

        Set<Integer> lookups = new java.util.TreeSet<>();
        int featureCount = u16(b, featureList);
        for (int fi : featureIndices) {
            if (fi >= featureCount) continue;
            int record = featureList + 2 + fi * 6;
            if (b.getInt(record) != TAG_KERN) continue;
            int feature = featureList + u16(b, record + 4);
            int count = u16(b, feature + 2);
            for (int i = 0; i < count; i++) lookups.add(u16(b, feature + 4 + i * 2));
        }

        List<List<Integer>> out = new ArrayList<>();
        int lookupCount = u16(b, lookupList);
        for (int li : lookups) {
            if (li >= lookupCount) continue;
            List<Integer> subs = new ArrayList<>();
            int lookup = lookupList + u16(b, lookupList + 2 + li * 2);
            int type = u16(b, lookup);
            int subCount = u16(b, lookup + 4);
            for (int i = 0; i < subCount; i++) {
                int sub = lookup + u16(b, lookup + 6 + i * 2);
                int subType = type;
                if (type == 9) {
                    subType = u16(b, sub + 2);
                    sub = sub + b.getInt(sub + 4);
                }
                if (subType == 2) subs.add(sub);
            }
            if (!subs.isEmpty()) out.add(List.copyOf(subs));
        }
        return out;
    }

    private static void addLangSysFeatures(ByteBuffer b, int langSys, Set<Integer> out) {
        int required = u16(b, langSys + 2);
        if (required != 0xFFFF) out.add(required);
        int count = u16(b, langSys + 4);
        for (int i = 0; i < count; i++) out.add(u16(b, langSys + 6 + i * 2));
    }

    /** kern の lookup ごとに最初に当たった副表の値を取り、lookup どうしは足し合わせる（HarfBuzz と同じ）。 */
    private int gposValue(int first, int second) {
        int total = 0;
        for (List<Integer> lookup : pairSubtables) {
            for (int sub : lookup) {
                Integer v = pairPosValue(sub, first, second);
                if (v != null) {
                    total += v;
                    break;
                }
            }
        }
        return total;
    }

    /** PairPos 副表の値。1 つ目の字が対象外、または組が無ければ null。 */
    private Integer pairPosValue(int sub, int first, int second) {
        ByteBuffer b = gpos;
        int format = u16(b, sub);
        int coverageIndex = coverage(b, sub + u16(b, sub + 2), first);
        if (coverageIndex < 0) return null;
        int vf1 = u16(b, sub + 4);
        int vf2 = u16(b, sub + 6);
        int size1 = Integer.bitCount(vf1 & 0xFF) * 2;
        int size2 = Integer.bitCount(vf2 & 0xFF) * 2;
        if (format == 1) {
            int pairSetCount = u16(b, sub + 8);
            if (coverageIndex >= pairSetCount) return null;
            int pairSet = sub + u16(b, sub + 10 + coverageIndex * 2);
            int count = u16(b, pairSet);
            int recordSize = 2 + size1 + size2;
            int lo = 0, hi = count - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int rec = pairSet + 2 + mid * recordSize;
                int g = u16(b, rec);
                // 形式 1 の Device の位置は PairSet からの距離
                if (g == second) return xAdvance(b, rec + 2, vf1, pairSet);
                if (g < second) lo = mid + 1; else hi = mid - 1;
            }
            return null;
        }
        if (format == 2) {
            int class1 = classOf(b, sub + u16(b, sub + 8), first);
            int class2 = classOf(b, sub + u16(b, sub + 10), second);
            int class1Count = u16(b, sub + 12);
            int class2Count = u16(b, sub + 14);
            if (class1 >= class1Count || class2 >= class2Count) return null;
            int rec = sub + 16 + (class1 * class2Count + class2) * (size1 + size2);
            // 形式 2 の Device の位置は副表の先頭からの距離
            return xAdvance(b, rec, vf1, sub);
        }
        return null;
    }

    /**
     * ValueRecord の XAdvance（valueFormat の 0x4）に、XAdvDevice（0x40）の可変フォントの変化量を足したもの。
     * {@code base} は Device の位置の基準。
     */
    private int xAdvance(ByteBuffer b, int record, int valueFormat, int base) {
        int value = 0;
        if ((valueFormat & 0x4) != 0) {
            value = b.getShort(record + Integer.bitCount(valueFormat & 0x3) * 2);
        }
        if ((valueFormat & 0x40) != 0 && gdef != null) {
            int deviceOffset = u16(b, record + Integer.bitCount(valueFormat & 0x3F) * 2);
            if (deviceOffset != 0) {
                int device = base + deviceOffset;
                if (u16(b, device + 4) == 0x8000) {
                    value += Math.round(itemDelta(u16(b, device), u16(b, device + 2)));
                }
            }
        }
        return value;
    }

    // ---- ItemVariationStore（OpenType の可変フォントの共通の仕組み）

    /** 変化量の組 {@code outer} の {@code inner} 番目を、今の座標で解いた値。 */
    private float itemDelta(int outer, int inner) {
        ByteBuffer g = gdef;
        int store = varStore;
        int regionList = store + g.getInt(store + 2);
        int dataCount = u16(g, store + 6);
        if (outer >= dataCount) return 0;
        int data = store + g.getInt(store + 8 + outer * 4);
        int itemCount = u16(g, data);
        if (inner >= itemCount) return 0;
        int wordDeltaCount = u16(g, data + 2);
        boolean longWords = (wordDeltaCount & 0x8000) != 0;
        int wordCount = wordDeltaCount & 0x7FFF;
        int regionIndexCount = u16(g, data + 4);
        int wordSize = longWords ? 4 : 2;
        int byteSize = longWords ? 2 : 1;
        int rowSize = wordCount * wordSize + (regionIndexCount - wordCount) * byteSize;
        int row = data + 6 + regionIndexCount * 2 + inner * rowSize;

        int axisCount = u16(g, regionList);
        float total = 0;
        for (int r = 0; r < regionIndexCount; r++) {
            int delta;
            if (r < wordCount) {
                delta = longWords ? g.getInt(row + r * 4) : g.getShort(row + r * 2);
            } else {
                int p = row + wordCount * wordSize + (r - wordCount) * byteSize;
                delta = longWords ? g.getShort(p) : g.get(p);
            }
            if (delta == 0) continue;
            int region = u16(g, data + 6 + r * 2);
            total += delta * regionScalar(g, regionList + 4 + region * axisCount * 6, axisCount);
        }
        return total;
    }

    /** 領域の重み（各軸の三角形の値の積）。OpenType の「Algorithm for interpolation」のとおり。 */
    private float regionScalar(ByteBuffer g, int region, int axisCount) {
        float scalar = 1;
        for (int a = 0; a < axisCount; a++) {
            float start = g.getShort(region + a * 6) / 16384f;
            float peak = g.getShort(region + a * 6 + 2) / 16384f;
            float end = g.getShort(region + a * 6 + 4) / 16384f;
            float coord = a < normalizedCoords.length ? normalizedCoords[a] : 0;
            if (start > peak || peak > end) continue;
            if (start < 0 && end > 0 && peak != 0) continue;
            if (peak == 0) continue;
            if (coord < start || coord > end) return 0;
            if (coord == peak) continue;
            scalar *= coord < peak ? (coord - start) / (peak - start) : (end - coord) / (end - peak);
        }
        return scalar;
    }

    private static int coverage(ByteBuffer b, int table, int glyph) {
        int format = u16(b, table);
        if (format == 1) {
            int count = u16(b, table + 2);
            int lo = 0, hi = count - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int g = u16(b, table + 4 + mid * 2);
                if (g == glyph) return mid;
                if (g < glyph) lo = mid + 1; else hi = mid - 1;
            }
            return -1;
        }
        if (format == 2) {
            int count = u16(b, table + 2);
            int lo = 0, hi = count - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int rec = table + 4 + mid * 6;
                int start = u16(b, rec), end = u16(b, rec + 2);
                if (glyph < start) hi = mid - 1;
                else if (glyph > end) lo = mid + 1;
                else return u16(b, rec + 4) + glyph - start;
            }
        }
        return -1;
    }

    private static int classOf(ByteBuffer b, int table, int glyph) {
        int format = u16(b, table);
        if (format == 1) {
            int start = u16(b, table + 2);
            int count = u16(b, table + 4);
            int i = glyph - start;
            return i >= 0 && i < count ? u16(b, table + 6 + i * 2) : 0;
        }
        if (format == 2) {
            int count = u16(b, table + 2);
            int lo = 0, hi = count - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int rec = table + 4 + mid * 6;
                int start = u16(b, rec), end = u16(b, rec + 2);
                if (glyph < start) hi = mid - 1;
                else if (glyph > end) lo = mid + 1;
                else return u16(b, rec + 4);
            }
        }
        return 0;
    }

    // ---- kern（旧式、Microsoft 形式のバージョン 0 / 形式 0 の副表だけ）

    private int legacyValue(int first, int second) {
        ByteBuffer b = legacyKern;
        if (u16(b, 0) != 0) return 0;
        int tables = u16(b, 2);
        int pos = 4;
        int total = 0;
        for (int t = 0; t < tables; t++) {
            int length = u16(b, pos + 2);
            int coverage = u16(b, pos + 4);
            boolean horizontal = (coverage & 0x1) != 0;
            boolean minimum = (coverage & 0x2) != 0;
            boolean crossStream = (coverage & 0x4) != 0;
            boolean override = (coverage & 0x8) != 0;
            int format = coverage >>> 8;
            if (horizontal && !minimum && !crossStream && format == 0) {
                int nPairs = u16(b, pos + 6);
                int base = pos + 14;
                long key = ((long) first << 16) | second;
                int lo = 0, hi = nPairs - 1;
                while (lo <= hi) {
                    int mid = (lo + hi) >>> 1;
                    int rec = base + mid * 6;
                    long k = ((long) u16(b, rec) << 16) | u16(b, rec + 2);
                    if (k == key) {
                        int v = b.getShort(rec + 4);
                        total = override ? v : total + v;
                        break;
                    }
                    if (k < key) lo = mid + 1; else hi = mid - 1;
                }
            }
            if (length <= 0) break;
            pos += length;
        }
        return total;
    }

    private static int u16(ByteBuffer b, int pos) {
        return Short.toUnsignedInt(b.getShort(pos));
    }

    private static int tag(String s) {
        return (s.charAt(0) << 24) | (s.charAt(1) << 16) | (s.charAt(2) << 8) | s.charAt(3);
    }
}
