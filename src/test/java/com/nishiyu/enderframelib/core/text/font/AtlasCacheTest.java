package com.nishiyu.enderframelib.core.text.font;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字形の置き場（テクスチャ）の保持と破棄、置き場の鍵（字形の番号・サイズの量子化）。
 *
 * 何が壊れていたか: 置き場の鍵に原点の端数（4 段）と字の明るさ（2 段）を含めていたので、1 サイズあたり最大 8 枚の
 * テクスチャが別々に作られていた。{@code scale} のアニメーション中はサイズごとの置き場が溜まり続け、
 * 破棄は個数の上限（256）に達したときの最古のものだけだった。
 */
public class AtlasCacheTest {

    private static final long SEC = 1_000_000_000L;

    private final AtomicLong now = new AtomicLong();
    private final List<String> released = new ArrayList<>();

    private AtlasCache<String, String> cache(int softLimit) {
        return new AtlasCache<>(softLimit, 2 * SEC, 30 * SEC, now::get, released::add);
    }

    @Test
    void sameKeyIsCreatedOnce() {
        AtlasCache<String, String> c = cache(4);
        int[] created = {0};
        for (int i = 0; i < 5; i++) c.get("a", k -> { created[0]++; return k; });
        assertEquals(1, created[0]);
        assertEquals(1, c.size());
    }

    @Test
    void recentlyUsedAtlasIsNeverEvictedEvenOverTheLimit() {
        AtlasCache<String, String> c = cache(2);
        for (String k : List.of("a", "b", "c", "d")) c.get(k, x -> x);
        // 全部が直近に使われている（描画バッファがまだ参照しているかもしれない）ので、上限を超えても捨てない
        assertEquals(4, c.size());
        assertTrue(released.isEmpty());
    }

    @Test
    void overTheLimitTheOldestIdleOnesGoFirst() {
        AtlasCache<String, String> c = cache(2);
        for (String k : List.of("a", "b", "c")) {
            c.get(k, x -> x);
            now.addAndGet(SEC);
        }
        now.addAndGet(2 * SEC); // a, b, c はいずれも保護の外（2 秒以上前）
        c.get("d", x -> x);
        // 上限 2 に収まるまで、使われてから長い順に捨てる
        assertEquals(List.of("a", "b"), released);
        assertEquals(2, c.size());
    }

    @Test
    void touchingAnAtlasKeepsItAlive() {
        AtlasCache<String, String> c = cache(1);
        c.get("a", x -> x);
        c.get("b", x -> x);
        now.addAndGet(5 * SEC);
        c.get("a", x -> x); // a を使い直す
        now.addAndGet(3 * SEC);
        c.get("c", x -> x);
        assertTrue(released.contains("b"));
        assertTrue(!released.contains("a") || released.indexOf("b") < released.indexOf("a"), "古い b が先に捨てられる");
    }

    @Test
    void unusedAtlasesExpireEvenUnderTheLimit() {
        AtlasCache<String, String> c = cache(64);
        c.get("anim-1", x -> x);
        c.get("anim-2", x -> x);
        now.addAndGet(31 * SEC);
        c.get("steady", x -> x);
        c.sweepIfDue();
        // scale アニメーションが残した古いサイズの置き場は、個数の上限に関わらず VRAM から外れる
        assertEquals(List.of("anim-1", "anim-2"), released);
        assertEquals(1, c.size());
    }

    @Test
    void clearReleasesEverything() {
        AtlasCache<String, String> c = cache(8);
        c.get("a", x -> x);
        c.get("b", x -> x);
        c.clear();
        assertEquals(2, released.size());
        assertEquals(0, c.size());
    }

    @Test
    void glyphKeyRoundTripsAndKeepsVariantsApart() {
        GlyphKey keys = new GlyphKey();
        int cp = 0x1F600; // 面外のコードポイント
        for (int sub = 0; sub < LoadedFace.SUBPIXEL_STEPS; sub++) {
            for (boolean light : new boolean[]{false, true}) {
                int key = keys.encode(cp, sub, light);
                assertEquals(cp, keys.codePoint(key));
                assertEquals(sub, GlyphKey.subpixel(key));
                assertEquals(light, GlyphKey.lightText(key));
            }
        }
        // 同じ文字の 8 通りは別の番号（1 つの置き場に別々の字形として載る）。同じ指定は同じ番号
        java.util.Set<Integer> variants = new java.util.HashSet<>();
        for (int sub = 0; sub < LoadedFace.SUBPIXEL_STEPS; sub++) {
            variants.add(keys.encode('A', sub, false));
            variants.add(keys.encode('A', sub, true));
        }
        assertEquals(GlyphKey.VARIANTS, variants.size());
        assertEquals(keys.encode('A', 2, true), keys.encode('A', 2, true));
        assertEquals('A', keys.codePoint(keys.encode('A', 3, true)));
        assertEquals(2, keys.size());
        // 割り当てていない番号は -1（FontSet は置き場を作るとき、空白の番号 32 などで字形を引いてみる）
        assertEquals(-1, keys.codePoint(32));
        assertEquals(-1, keys.codePoint(-5));
    }

    @Test
    void glyphKeysStayInsideMinecraftsCodepointTable() {
        // FontSet の字形の表（CodepointMap）は 0..0x10FFFF の範囲しか持てない。どんな文字・変種でも収まる
        GlyphKey keys = new GlyphKey();
        int max = 0;
        for (int cp = 0; cp < 0x110000; cp += 7) {
            for (int sub = 0; sub < LoadedFace.SUBPIXEL_STEPS; sub++) {
                max = Math.max(max, keys.encode(cp, sub, (cp & 1) == 0));
            }
        }
        assertTrue(max < 0x110000, "字形の番号が表の範囲を超えている: " + max);
        // 最後に割り当てられる通し番号でも収まる
        GlyphKey full = new GlyphKey();
        for (int i = 0; i < 0x110000 / GlyphKey.VARIANTS + 10; i++) {
            assertTrue(full.encode(0x20000 + i, 3, true) < 0x110000);
        }
    }

    @Test
    void sizesAreQuantizedSoAnimationDoesNotMultiplyAtlases() {
        // 13px の文字を scale(1.0 → 3.0) で動かす: 毎フレーム違う大きさ
        java.util.Set<Integer> distinct = new java.util.HashSet<>();
        int frames = 0;
        for (float scale = 1.0f; scale <= 3.0f; scale += 0.003f, frames++) {
            distinct.add(AtlasSizing.key(AtlasSizing.quantize(13f * scale)));
        }
        assertTrue(frames > 600);
        assertTrue(distinct.size() < frames / 4, "置き場の種類: " + distinct.size() + " / " + frames + " フレーム");

        // 普通の静止した大きさは変わらない
        assertEquals(13f, AtlasSizing.quantize(13f), 0f);
        assertEquals(16.25f, AtlasSizing.quantize(16.25f), 0f);
        assertEquals(26f, AtlasSizing.quantize(26f), 0f);
        assertEquals(48f, AtlasSizing.quantize(48f), 0f);
        // 誤差は 1% 未満
        for (float px = 6f; px <= 192f; px += 0.37f) {
            float q = AtlasSizing.quantize(px);
            assertTrue(Math.abs(q - px) / px < 0.01f + 0.125f / px, px + " -> " + q);
        }
    }
}
