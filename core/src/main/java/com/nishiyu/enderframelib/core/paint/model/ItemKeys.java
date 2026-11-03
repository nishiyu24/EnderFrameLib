package com.nishiyu.enderframelib.core.paint.model;

import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link DisplayItem#id()} を密な {@code long} の鍵へ写す表（API 非依存）。
 * 同じ ID 文字列は常に同じ鍵、異なる ID は異なる鍵になる。索引・アニメ状態・ヒットテストが
 * 文字列のハッシュ／比較をせずに済むようにするための段階移行用で、{@code id} 自体は互換のため残す。
 * 鍵は 1 から始まり 0 は「未割り当て」。表はプロセス内で単調に増える（ID の種類数に比例）。
 */
public final class ItemKeys {
    private static final ConcurrentHashMap<String, Long> TABLE = new ConcurrentHashMap<>();
    private static long next = 1;

    private ItemKeys() {}

    public static long of(String id) {
        if (id == null) return 0L;
        Long k = TABLE.get(id);
        if (k != null) return k;
        synchronized (ItemKeys.class) {
            k = TABLE.get(id);
            if (k == null) {
                k = next++;
                TABLE.put(id, k);
            }
            return k;
        }
    }

    /** 登録済みの ID の数（テスト・診断用）。 */
    public static int size() {
        return TABLE.size();
    }
}
