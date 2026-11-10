package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.ValueComputer.CssWideKeyword;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ショートハンド展開の実行表。展開の順・ショートハンドとロングハンドの添字・（ショートハンド, 値）ごとの展開結果を
 * 起動時と初回の展開で配列に詰めておき、要素ごとの展開は添字で作業表（{@link StyleTable}）を直接読み書きする。
 *
 * 以前は要素ごと・ショートハンドごとに、名前でレジストリとマップを引き、結果の {@code Map.Entry} を走査して
 * ロングハンドごとに名前で {@code putIfAbsent} していた。展開結果は（名前, 値）だけで決まる純関数なので、
 * 結果を「書く先の添字と値」の配列に直して覚え、適用は配列の走査だけにする。
 */
final class ShorthandPlan {
    private static final String[] NAMES;
    private static final int[] IDS;
    private static final PropertyRegistry.Property[] PROPS;
    /** ショートハンドごとのロングハンドの名前と添字（全域キーワード・展開不能のときの配り先）。 */
    private static final String[][] LONGHANDS;
    private static final int[][] LONGHAND_IDS;
    /** 展開できない値のときに全ロングハンドへ配る {@code unset}。ショートハンドごとに 1 つ。 */
    private static final Expansion[] UNSET_ALL;
    @SuppressWarnings("unchecked")
    private static final ConcurrentHashMap<String, Expansion>[] CACHE;
    private static final int CACHE_LIMIT = 2048;

    /** 展開の結果: 書く先（添字。未登録の名前は -1 で名前）と値。順序は元の {@code putIfAbsent} の順。 */
    record Expansion(String[] names, int[] ids, String[] values, CssValue[] cssValues) {
        Expansion(String[] names, int[] ids, String[] values) {
            this(names, ids, values, toValues(values));
        }

        private static CssValue[] toValues(String[] values) {
            CssValue[] out = new CssValue[values.length];
            for (int i = 0; i < out.length; i++) out[i] = CssValue.of(values[i]);
            return out;
        }

        void applyTo(StyleTable t) {
            for (int k = 0; k < ids.length; k++) {
                if (ids[k] >= 0) t.putIfAbsentValueById(ids[k], cssValues[k]);
                else t.putIfAbsent(names[k], values[k]);
            }
        }
    }

    static {
        List<String> order = PropertyRegistry.expansionOrder();
        int n = order.size();
        NAMES = order.toArray(new String[0]);
        IDS = new int[n];
        PROPS = new PropertyRegistry.Property[n];
        LONGHANDS = new String[n][];
        LONGHAND_IDS = new int[n][];
        UNSET_ALL = new Expansion[n];
        CACHE = new ConcurrentHashMap[n];
        for (int i = 0; i < n; i++) {
            PropertyRegistry.Property p = PropertyRegistry.get(NAMES[i]);
            PROPS[i] = p;
            IDS[i] = ComputedStyle.idOf(NAMES[i]);
            LONGHANDS[i] = p.longhands().toArray(new String[0]);
            LONGHAND_IDS[i] = idsOf(LONGHANDS[i]);
            String[] unset = new String[LONGHANDS[i].length];
            java.util.Arrays.fill(unset, "unset");
            UNSET_ALL[i] = new Expansion(LONGHANDS[i], LONGHAND_IDS[i], unset);
            CACHE[i] = new ConcurrentHashMap<>();
        }
    }

    private ShorthandPlan() {}

    private static int[] idsOf(String[] names) {
        int[] ids = new int[names.length];
        for (int i = 0; i < ids.length; i++) ids[i] = ComputedStyle.idOf(names[i]);
        return ids;
    }

    /** 作業表のショートハンドを展開する（意味は {@link PropertyRegistry#expandShorthands}）。 */
    static void expand(StyleTable t) {
        for (int i = 0; i < NAMES.length; i++) {
            int id = IDS[i];
            String value = id >= 0 ? t.textById(id) : t.get(NAMES[i]);
            if (value == null || value.isBlank()) continue;
            if (CssWideKeyword.of(value) != null) {
                String keyword = value.trim();
                CssValue keywordValue = CssValue.of(keyword);
                for (int k = 0; k < LONGHANDS[i].length; k++) {
                    if (LONGHAND_IDS[i][k] >= 0) t.putIfAbsentValueById(LONGHAND_IDS[i][k], keywordValue);
                    else t.putIfAbsent(LONGHANDS[i][k], keyword);
                }
                continue;
            }
            String trimmed = value.trim();
            ConcurrentHashMap<String, Expansion> cache = CACHE[i];
            Expansion e = cache.get(trimmed);
            if (e == null) {
                e = build(i, trimmed);
                if (cache.size() >= CACHE_LIMIT) cache.clear();
                cache.put(trimmed, e);
            }
            e.applyTo(t);
        }
    }

    private static Expansion build(int i, String value) {
        PropertyRegistry.Property p = PROPS[i];
        Map<String, String> parts = p.expander().apply(value);
        if (parts.isEmpty()) return UNSET_ALL[i];
        List<String> names = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, String> e : parts.entrySet()) {
            if (e.getValue() != null) {
                names.add(e.getKey());
                values.add(e.getValue());
            }
        }
        for (String l : LONGHANDS[i]) {
            PropertyRegistry.Property longhand = PropertyRegistry.get(l);
            if (parts.get(l) == null && !longhand.isShorthand() && longhand.initial() != null) {
                names.add(l);
                values.add(longhand.initial());
            }
        }
        String[] nameArray = names.toArray(new String[0]);
        return new Expansion(nameArray, idsOf(nameArray), values.toArray(new String[0]));
    }
}
