package com.nishiyu.enderframelib.core;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.dom.Bindings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** ホストの差し込みを static からエンジン文脈へ移したこと（独立した文脈・スレッド単位の切り替え）。 */
class EngineContextTest {

    private static String resolve(String tpl) {
        return Bindings.resolve(tpl, Map.of(), Map.of());
    }

    @Test
    void scopedContextChangesBehaviourOnlyInsideTheScope() {
        String before = resolve("{{i18n:a.b}}");
        EngineContext ctx = new EngineContext();
        ctx.updateHost(h -> h.withI18nResolver(k -> "CTX:" + k));
        EngineContext.runWith(ctx, () -> assertEquals("CTX:a.b", resolve("{{i18n:a.b}}")));
        assertEquals(before, resolve("{{i18n:a.b}}"), "終わったら元の文脈へ戻る");
        assertSame(EngineContext.global(), EngineContext.current());
    }

    /** S1 ②: 文脈は public な可変の欄を持たない（差し込み口は不変の値で、updateHost が丸ごと差し替える）。 */
    @Test
    void contextHasNoPublicMutableFields() {
        for (java.lang.reflect.Field f : EngineContext.class.getDeclaredFields()) {
            int m = f.getModifiers();
            assertFalse(java.lang.reflect.Modifier.isPublic(m) && !java.lang.reflect.Modifier.isFinal(m), "public で可変: " + f.getName());
        }
        for (Class<?> value : List.of(HostServices.class)) {
            assertTrue(value.isRecord(), value + " は不変の値");
        }
    }

    @Test
    void updatesReplaceOneValueAndKeepTheOthers() {
        EngineContext ctx = new EngineContext();
        assertSame(HostServices.DEFAULTS, ctx.host());
        HostServices before = ctx.host();
        ctx.updateHost(h -> h.withResourceTextResolver(p -> "R:" + p));
        ctx.updateHost(h -> h.withI18nResolver(k -> "T:" + k));
        assertEquals("R:x", ctx.host().resourceTextResolver().apply("x"), "前の差し替えが残る");
        assertEquals("T:y", ctx.host().i18nResolver().apply("y"));
        assertNull(before.resourceTextResolver(), "元の値は変わらない（読んだ側が途中の状態を見ない）");
        assertThrows(NullPointerException.class, () -> ctx.updateHost(h -> null));
    }

    @Test
    void concurrentUpdatesAreNotLost() throws Exception {
        EngineContext ctx = new EngineContext();
        int threads = 4, per = 500;
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> ts = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();
        for (int t = 0; t < threads; t++) {
            Thread th = new Thread(() -> {
                try { go.await(); } catch (InterruptedException e) { return; }
                for (int i = 0; i < per; i++) {
                    // 前の関数を包む差し替え（読み替えの間に他のスレッドの差し替えが入ると、包みが 1 段失われる）
                    ctx.updateHost(h -> { var prev = h.i18nResolver(); counter.incrementAndGet(); return h.withI18nResolver(k -> prev.apply(k) + "+"); });
                }
            });
            ts.add(th);
            th.start();
        }
        go.countDown();
        for (Thread th : ts) th.join();
        assertEquals(threads * per, ctx.host().i18nResolver().apply("").length(), "差し替えが 1 つも失われない");
    }

    @Test
    void scopeIsRestoredEvenWhenBodyThrows() {
        EngineContext ctx = new EngineContext();
        assertThrows(IllegalStateException.class, () -> EngineContext.runWith(ctx, () -> { throw new IllegalStateException(); }));
        assertSame(EngineContext.global(), EngineContext.current());
    }

    @Test
    void scopesNest() throws Exception {
        EngineContext a = new EngineContext();
        EngineContext b = new EngineContext();
        EngineContext.runWith(a, () -> {
            assertSame(a, EngineContext.current());
            EngineContext.runWith(b, () -> assertSame(b, EngineContext.current()));
            assertSame(a, EngineContext.current());
        });
    }

    @Test
    void contextsAreIsolatedAcrossThreads() throws Exception {
        EngineContext a = new EngineContext();
        a.updateHost(h -> h.withI18nResolver(k -> "A:" + k));
        EngineContext b = new EngineContext();
        b.updateHost(h -> h.withI18nResolver(k -> "B:" + k));
        CountDownLatch bothIn = new CountDownLatch(2);
        AtomicReference<String> ra = new AtomicReference<>();
        AtomicReference<String> rb = new AtomicReference<>();
        Thread ta = new Thread(() -> EngineContext.runWith(a, () -> { bothIn.countDown(); await(bothIn); ra.set(resolve("{{i18n:k}}")); }));
        Thread tb = new Thread(() -> EngineContext.runWith(b, () -> { bothIn.countDown(); await(bothIn); rb.set(resolve("{{i18n:k}}")); }));
        ta.start(); tb.start(); ta.join(); tb.join();
        assertEquals("A:k", ra.get());
        assertEquals("B:k", rb.get());
    }

    private static void await(CountDownLatch l) {
        try { l.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
    }

    @Test
    void newContextStartsWithDefaults() {
        EngineContext c = new EngineContext();
        assertEquals("x", c.host().i18nResolver().apply("x"));
        assertEquals("x", c.host().i18nFormatter().apply("x", new Object[0]));
        assertNull(c.host().jsonListParser());
        assertNull(c.host().intrinsicSizeResolver());
    }

    @Test
    void staticHooksAreGoneFromBindingsAndPipeline() throws Exception {
        Path main = Path.of("core/src/main/java/com/nishiyu/enderframelib");
        String bindings = Files.readString(main.resolve("core/dom/Bindings.java"));
        assertFalse(bindings.contains("public static Function<String, String> i18nResolver"));
        assertFalse(bindings.contains("public static BiFunction<String, Object[], String> i18nFormatter"));
        String frame = Files.readString(main.resolve("common/FrameRender.java")) + Files.readString(main.resolve("common/V3Frame.java"));
        assertFalse(frame.contains("public static IntrinsicSizeResolver intrinsicSizeResolver"));
        assertFalse(frame.contains("static volatile"), "1 回のフレームとビューのフレームはプロセス共有の状態を持たない");
        assertFalse(Files.readString(main.resolve("common/UiDocumentCompiler.java")).contains("IntrinsicSizeResolver intrinsicSizeResolver"));
    }
}
