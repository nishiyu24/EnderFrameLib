package com.nishiyu.enderframelib.core.text.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文字の測定の 1 層の記憶（{@link TextMemo}。計画書 §2.3 の L1。M5-5）と、それを持つ書体（{@link BitmapFontFace}）。
 *
 * 守るもの: ① 鍵は文字列の値と整形の指定の値（別の実例でも当たり、指定が違えば当たらない）、② 大きさは欄の数を超えない、
 * ③ 複数のスレッドが 1 つの書体を同時に測っても、測り直した値と常に一致する（以前の {@code ThreadLocal} の表は
 * スレッドごとに別の表で、共有しない代わりに競合も無かった。共有した今、鍵と値を取り違えないこと）。
 */
class TextMemoTest {

    /** 素の送り幅を数える書体（記憶が当たったかを呼び出しの数で見る）。 */
    private static final class CountingBitmapFace extends BitmapFontFace {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        protected float naturalAdvance(String text, boolean bold) {
            calls.incrementAndGet();
            return uncached(text, bold);
        }

        static float uncached(String text, boolean bold) {
            int width = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == 'l' || c == 'i' || c == '!' || c == '.' || c == ':' || c == '|') width += 3;
                else if (c == ' ') width += 4;
                else if (c <= 0x00FF) width += 7;
                else width += 12 + (c % 5); // 字ごとに違う幅（取り違えを見つけやすくする）
            }
            if (bold) width += text.length();
            return width;
        }
    }

    @Test
    void keyIsTheTextValueAndTheSpecValue() {
        TextMemo<FontStyle, float[]> memo = new TextMemo<>(16);
        FontStyle s16 = new FontStyle(16f, false, false, 0f);
        float[] v = {1f, 2f};
        assertNull(memo.get("alpha", s16));
        assertSame(v, memo.put("alpha", s16, v));
        // 別の実例の同じ値の文字列・同じ値の指定でも当たる
        assertSame(v, memo.get(new String("alpha"), new FontStyle(16f, false, false, 0f)));
        // 指定の値が違えば当たらない
        assertNull(memo.get("alpha", new FontStyle(16f, true, false, 0f)));
        assertNull(memo.get("alpha", new FontStyle(17f, false, false, 0f)));
        assertNull(memo.get("alphb", s16));
    }

    @Test
    void sizeNeverExceedsTheCapacity() {
        TextMemo<Boolean, Float> memo = new TextMemo<>(8);
        assertEquals(16, memo.capacity());
        for (int i = 0; i < 10_000; i++) memo.put("w" + i, (i & 1) == 0, (float) i);
        assertTrue(memo.size() <= memo.capacity(), "上限を超えた: " + memo.size());
        assertEquals(memo.capacity(), memo.size(), "1 万件を入れた後は全ての欄が埋まる");
        // 最後に入れたものは引ける（組の 1 列目）
        assertEquals(9_999f, memo.get("w9999", false));
        assertThrows(IllegalArgumentException.class, () -> new TextMemo<>(12));
    }

    @Test
    void twoWaysKeepTheTwoLatestOfACollidingBucket() {
        TextMemo<Boolean, Float> memo = new TextMemo<>(1); // 組は 1 つだけ: 全てが衝突する
        memo.put("a", false, 1f);
        memo.put("b", false, 2f);
        assertEquals(1f, memo.get("a", false));
        assertEquals(2f, memo.get("b", false));
        memo.put("c", false, 3f);
        assertNull(memo.get("a", false), "3 つ目で一番古いものが落ちる");
        assertEquals(2f, memo.get("b", false));
        assertEquals(3f, memo.get("c", false));
    }

    @Test
    void bitmapFaceMeasuresOnceAcrossSizesAndSeparatesBold() {
        CountingBitmapFace face = new CountingBitmapFace();
        FontStyle s16 = new FontStyle(16f, false, false, 0f);
        FontStyle s24 = new FontStyle(24f, false, false, 1f);
        FontStyle b16 = new FontStyle(16f, true, false, 0f);

        float w1 = face.advance("alpha", s16);
        assertEquals(1, face.calls.get());
        // 別の実例の文字列・別の大きさ・別の字間でも素の送り幅は測り直さない
        assertEquals(w1, face.advance(new String("alpha"), s16));
        float expected24 = (w1 / face.scaleFor(s16)) * face.scaleFor(s24) + 5f;
        assertEquals(expected24, face.advance("alpha", s24), 0.0001f);
        assertEquals(1, face.calls.get());
        // 太字は別の鍵
        float wb = face.advance("alpha", b16);
        assertTrue(wb > w1);
        assertEquals(2, face.calls.get());
        assertEquals(wb, face.advance("alpha", b16));
        assertEquals(2, face.calls.get());
    }

    @Test
    void concurrentMeasurementOfOneFaceAlwaysMatchesTheUncachedValue() throws Exception {
        // 欄の数（8,192）より多い語で、スレッドどうしが同じ組を書き換え合う状態を作る
        CountingBitmapFace face = new CountingBitmapFace();
        assertEquals(0, measureConcurrently(face, words(20_000), 8));
        // 欄に収まる語の数なら、共有した記憶がスレッドをまたいで当たる（測り直しは語の数の程度）
        CountingBitmapFace small = new CountingBitmapFace();
        assertEquals(0, measureConcurrently(small, words(1_000), 8));
        assertTrue(small.calls.get() < 8 * 200_000 / 20, "記憶が当たっていない: " + small.calls.get());
    }

    private static List<String> words(int n) {
        List<String> words = new ArrayList<>();
        Random rnd = new Random(5);
        for (int i = 0; i < n; i++) {
            StringBuilder sb = new StringBuilder();
            int len = 2 + rnd.nextInt(10);
            for (int k = 0; k < len; k++) sb.append((char) (rnd.nextBoolean() ? 'a' + rnd.nextInt(26) : 0x3042 + rnd.nextInt(80)));
            words.add(sb.toString());
        }
        return words;
    }

    /** {@code threads} 本のスレッドが 1 つの書体で語を測り、記憶の無い値と違った回数を返す。 */
    private static int measureConcurrently(CountingBitmapFace face, List<String> words, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int seed = t;
                results.add(pool.submit(() -> {
                    Random r = new Random(seed);
                    int bad = 0;
                    for (int i = 0; i < 200_000; i++) {
                        String w = words.get(r.nextInt(words.size()));
                        boolean bold = r.nextInt(4) == 0;
                        float got = face.cachedNaturalAdvance(w, bold);
                        if (Float.compare(got, CountingBitmapFace.uncached(w, bold)) != 0) bad++;
                    }
                    return bad;
                }));
            }
            int bad = 0;
            for (Future<Integer> f : results) bad += f.get();
            return bad;
        } finally {
            pool.shutdownNow();
        }
    }
}
