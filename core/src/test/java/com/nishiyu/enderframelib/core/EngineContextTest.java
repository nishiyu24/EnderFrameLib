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
        ctx.i18nResolver = k -> "CTX:" + k;
        EngineContext.runWith(ctx, () -> assertEquals("CTX:a.b", resolve("{{i18n:a.b}}")));
        assertEquals(before, resolve("{{i18n:a.b}}"), "終わったら元の文脈へ戻る");
        assertSame(EngineContext.global(), EngineContext.current());
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
        a.i18nResolver = k -> "A:" + k;
        EngineContext b = new EngineContext();
        b.i18nResolver = k -> "B:" + k;
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
        assertEquals("x", c.i18nResolver.apply("x"));
        assertEquals("x", c.i18nFormatter.apply("x", new Object[0]));
        assertNull(c.jsonListParser);
        assertNull(c.intrinsicSizeResolver);
    }

    @Test
    void staticHooksAreGoneFromBindingsAndPipeline() throws Exception {
        Path main = Path.of("core/src/main/java/com/nishiyu/enderframelib");
        String bindings = Files.readString(main.resolve("core/dom/Bindings.java"));
        assertFalse(bindings.contains("public static Function<String, String> i18nResolver"));
        assertFalse(bindings.contains("public static BiFunction<String, Object[], String> i18nFormatter"));
        String rp = Files.readString(main.resolve("common/RenderPipeline.java"));
        assertFalse(rp.contains("public static IntrinsicSizeResolver intrinsicSizeResolver"));
        assertFalse(rp.contains("static final List<LayoutBox> layoutChangedBoxes"), "再レイアウトの印付けリストはスレッド単位");
        assertFalse(Files.readString(main.resolve("common/UiDocumentCompiler.java")).contains("IntrinsicSizeResolver intrinsicSizeResolver"));
    }
}
