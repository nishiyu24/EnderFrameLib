package com.nishiyu.enderframelib.core.text.font;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 書体操作の全域ロック（旧 {@code LoadedFace.LOCK} 1 本）を、面ごとのロック＋面ごとの FT_Library に分けたことの検証。
 *
 * 守るもの: ① 別々のスレッドが同じ面・別の面を同時に測定・ラスタライズしても、直列で得た結果と画素まで一致する
 * （FreeType を壊さない）、② 一覧・走査用の全域ロックを別のスレッドが握っていても、開いた面の測定・描画は止まらない、
 * ③ 別のスレッドがハンドルを解放・開き直している最中でも結果が変わらない。
 */
public class LoadedFaceConcurrencyTest {

    private static List<SystemFonts.Face> faces;

    @BeforeAll
    static void setUp() {
        SystemFonts system = SystemFonts.system().withLocale(Locale.ENGLISH);
        Assumptions.assumeTrue(system.hasFamily("Arial") || system.hasFamily("DejaVu Sans"), "Arial / DejaVu Sans が無い環境");
        String name = system.hasFamily("Arial") ? "Arial" : "DejaVu Sans";
        faces = new ArrayList<>(system.family(name));
        Assumptions.assumeTrue(faces.size() >= 2, "別々の面が 2 つ要る");
    }

    private static final String TEXT = "The quick brown fox jumps over the lazy dog 日本語 0123456789";

    /** 1 つの面で測定とラスタライズを一巡して、結果の要約（送り幅の和と全字形の被覆率の連結）を返す。 */
    private static Digest digest(SystemFonts.Face record) {
        LoadedFace f = LoadedFace.open(record);
        double advance = 0;
        java.io.ByteArrayOutputStream cov = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < TEXT.length(); i++) {
            int cp = TEXT.charAt(i);
            advance += f.advanceEm(cp);
            GlyphBitmap g = f.rasterize(cp, 14f + (i % 5), (i & 1) == 0, false, i % LoadedFace.SUBPIXEL_STEPS);
            cov.writeBytes(g.coverage());
        }
        return new Digest(advance, cov.toByteArray());
    }

    private record Digest(double advance, byte[] coverage) {}

    @Test
    void parallelWorkOnManyFacesMatchesSerialResults() throws Exception {
        int n = Math.min(4, faces.size());
        List<Digest> serial = new ArrayList<>();
        for (int i = 0; i < n; i++) serial.add(digest(faces.get(i)));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Digest>> out = new ArrayList<>();
            for (int t = 0; t < threads * 3; t++) {
                int idx = t % n;
                out.add(pool.submit(() -> {
                    go.await();
                    return digest(faces.get(idx));
                }));
            }
            go.countDown();
            for (int t = 0; t < out.size(); t++) {
                Digest d = out.get(t).get(60, TimeUnit.SECONDS);
                Digest s = serial.get(t % n);
                assertEquals(s.advance(), d.advance(), 0.0, "送り幅の和が直列と一致");
                assertArrayEquals(s.coverage(), d.coverage(), "字形の画素が直列と一致");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void openFaceWorkIsNotBlockedByTheGlobalListLock() throws Exception {
        LoadedFace f = LoadedFace.open(faces.get(0));
        f.advanceEm('A'); // 先に開いておく（開く処理は全域ロックの中で登録する）
        Digest expected = digest(faces.get(0));

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            synchronized (LoadedFace.LOCK) {
                held.countDown();
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                }
            }
        });
        holder.start();
        try {
            assertTrue(held.await(5, TimeUnit.SECONDS));
            // 全域ロックを別スレッドが握ったまま。旧実装なら、ここで測定もラスタライズも永遠に待たされる
            Digest during = assertTimeoutPreemptively(java.time.Duration.ofSeconds(20), () -> {
                ExecutorService one = Executors.newSingleThreadExecutor();
                try {
                    return one.submit(() -> digest(faces.get(0))).get();
                } finally {
                    one.shutdownNow();
                }
            });
            assertEquals(expected.advance(), during.advance(), 0.0);
            assertArrayEquals(expected.coverage(), during.coverage());
        } finally {
            release.countDown();
            holder.join(5000);
        }
    }

    @Test
    void releasingHandlesWhileOtherThreadsWorkKeepsResults() throws Exception {
        int n = Math.min(3, faces.size());
        List<Digest> serial = new ArrayList<>();
        for (int i = 0; i < n; i++) serial.add(digest(faces.get(i)));

        AtomicBoolean stop = new AtomicBoolean();
        Thread releaser = new Thread(() -> {
            while (!stop.get()) {
                LoadedFace.releaseIdle(0); // 使われていない面を片端から閉じる
                Thread.onSpinWait();
            }
        });
        ExecutorService pool = Executors.newFixedThreadPool(6);
        releaser.start();
        try {
            List<Future<Digest>> out = new ArrayList<>();
            for (int t = 0; t < 18; t++) {
                int idx = t % n;
                out.add(pool.submit(() -> digest(faces.get(idx))));
            }
            for (int t = 0; t < out.size(); t++) {
                Digest d = out.get(t).get(60, TimeUnit.SECONDS);
                Digest s = serial.get(t % n);
                assertEquals(s.advance(), d.advance(), 0.0);
                assertArrayEquals(s.coverage(), d.coverage(), "解放と開き直しの最中でも同じ字形");
            }
        } finally {
            stop.set(true);
            releaser.join(5000);
            pool.shutdownNow();
        }
    }
}
