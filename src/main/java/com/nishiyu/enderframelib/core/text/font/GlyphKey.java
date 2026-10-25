package com.nishiyu.enderframelib.core.text.font;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * 1 つの字形の置き場の中での、字形の番号の割り当て。コードポイントに、原点の端数（{@link LoadedFace#SUBPIXEL_STEPS} 段）と
 * 明るい字かどうかを足した 8 通りを、同じサイズの字形としてすべて 1 つの置き場（1 枚のテクスチャの並び）に載せる。
 *
 * 以前はこの 2 つを置き場の鍵に含めていたので、1 サイズあたり最大 8 枚のテクスチャが別々に作られ、どれも少ししか埋まらなかった。
 *
 * 番号は Minecraft の {@code FontSet} が字形を引く表（{@code CodepointMap}。0〜0x10FFFF の範囲しか持てない）に収めるため、
 * コードポイントそのものではなく、置き場ごとに使った順に振る 0 起点の通し番号 × 8 + 変種で表す。
 * 通し番号は 139,263 まで（Unicode のコードポイント数 / 8）。それより多い異なる字は 0 番の変種に畳む。
 */
public final class GlyphKey {

    /** 1 つのコードポイントの変種の数（端数 4 × 明るさ 2）。 */
    public static final int VARIANTS = LoadedFace.SUBPIXEL_STEPS * 2;
    private static final int MAX_IDS = 0x110000 / VARIANTS;

    private final Int2IntOpenHashMap ids = new Int2IntOpenHashMap();
    private final IntArrayList codePoints = new IntArrayList();

    /** 字形の番号。同じ (コードポイント, 端数, 明るさ) には同じ番号を返す。 */
    public synchronized int encode(int codePoint, int subpixel, boolean lightText) {
        int id = ids.getOrDefault(codePoint, -1);
        if (id < 0) {
            if (codePoints.size() >= MAX_IDS) return 0;
            id = codePoints.size();
            ids.put(codePoint, id);
            codePoints.add(codePoint);
        }
        return id * VARIANTS + subpixel * 2 + (lightText ? 1 : 0);
    }

    /** 番号のコードポイント。割り当てていない番号（{@code FontSet} が置き場を作るときに試す空白など）は -1。 */
    public synchronized int codePoint(int key) {
        int id = key / VARIANTS;
        return key >= 0 && id < codePoints.size() ? codePoints.getInt(id) : -1;
    }

    public static int subpixel(int key) {
        return (key % VARIANTS) / 2;
    }

    public static boolean lightText(int key) {
        return (key & 1) != 0;
    }

    /** 割り当て済みのコードポイントの数。 */
    public synchronized int size() {
        return codePoints.size();
    }
}
