package com.nishiyu.enderframelib.core.text.font;

import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontStyle;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * アウトライン書体の送り位置の記憶（{@link OutlineFontFace#positions}。計画書 §2.3 の L1。M5-5 で static の照会用の鍵
 * {@code ThreadLocal} と、上限で全部を捨てる表を、書体が持つ上限つきの表 {@code TextMemo} にした）。
 *
 * 守るもの: ① 同じ（文字列の値・スタイルの値）は同じ配列を返す、② スタイルの値が違えば別の値、③ 複数のスレッドが同じ書体を
 * 欄の数より多い語で同時に測っても、記憶の無い書体で測った値と常に一致する。
 */
class OutlinePositionMemoTest {

    private static OutlineFontFace face() {
        SystemFonts system = SystemFonts.system().withLocale(Locale.ENGLISH);
        Assumptions.assumeTrue(system.hasFamily("Arial") || system.hasFamily("DejaVu Sans"), "Arial / DejaVu Sans が無い環境");
        FontFace f = system.resolve(system.hasFamily("Arial") ? "Arial" : "DejaVu Sans");
        Assumptions.assumeTrue(f instanceof OutlineFontFace, "アウトライン書体が引けない");
        return (OutlineFontFace) f;
    }

    /** 記憶を持たない（空の表の）同じ並びの書体。 */
    private static OutlineFontFace fresh(OutlineFontFace like) {
        return new OutlineFontFace(SystemFonts.system().withLocale(Locale.ENGLISH), like.families(), List.of());
    }

    @Test
    void sameTextAndStyleValuesReturnTheRememberedPositions() {
        OutlineFontFace face = face();
        FontStyle s16 = new FontStyle(16f, false, false, 0f);
        float[] a = face.positions("Hello world", s16);
        assertSame(a, face.positions(new String("Hello world"), new FontStyle(16f, false, false, 0f)));
        float[] b = face.positions("Hello world", new FontStyle(20f, false, false, 0f));
        assertEquals(a.length, b.length);
        assertEquals(a[a.length - 1] * 20f / 16f, b[b.length - 1], 0.01f);
        assertEquals(face.advance("Hello world", s16), a[a.length - 1], 0.001f);
    }

    @Test
    void concurrentPositionsAlwaysMatchAFreshFace() throws Exception {
        OutlineFontFace shared = face();
        OutlineFontFace reference = fresh(shared);
        List<String> words = new ArrayList<>();
        Random rnd = new Random(3);
        for (int i = 0; i < 6_000; i++) { // 欄の数（4,096）より多い
            StringBuilder sb = new StringBuilder();
            int len = 1 + rnd.nextInt(12);
            for (int k = 0; k < len; k++) sb.append((char) ('a' + rnd.nextInt(26)));
            words.add(sb.toString());
        }
        FontStyle[] styles = {new FontStyle(12f, false, false, 0f), new FontStyle(16f, true, false, 0f),
                new FontStyle(16f, false, false, 1f)};
        // 基準は 1 スレッドで記憶の無い書体から（同じ書体の記憶を通さない）
        List<float[][]> expected = new ArrayList<>();
        for (String w : words) {
            float[][] per = new float[styles.length][];
            for (int s = 0; s < styles.length; s++) per[s] = reference.positions(w, styles[s]).clone();
            expected.add(per);
        }
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int seed = t;
                futures.add(pool.submit(() -> {
                    Random r = new Random(seed);
                    for (int i = 0; i < 40_000; i++) {
                        int wi = r.nextInt(words.size());
                        int si = r.nextInt(styles.length);
                        assertArrayEquals(expected.get(wi)[si], shared.positions(words.get(wi), styles[si]),
                                words.get(wi) + " / " + styles[si]);
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) f.get();
        } finally {
            pool.shutdownNow();
        }
    }
}
