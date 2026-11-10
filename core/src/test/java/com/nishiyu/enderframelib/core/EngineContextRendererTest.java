package com.nishiyu.enderframelib.core;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.RenderPipeline;
import com.nishiyu.enderframelib.core.paint.ILayoutBoxRenderer;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 箱の描画関数とリソース代替取得が EngineContext（スレッド単位で切り替え可能）へ移ったことの確認。 */
class EngineContextRendererTest {

    private static List<DisplayItem> renderWith(EngineContext ctx) {
        var out = new Object() { List<DisplayItem> v; };
        EngineContext.runWith(ctx, () -> out.v = RenderPipeline.renderBox(null, 10, 10, Map.of(), new ScrollState()));
        return out.v;
    }

    @Test
    void defaultsAreTheBuiltInRenderer() {
        EngineContext fresh = new EngineContext();
        assertSame(ILayoutBoxRenderer.DEFAULT, fresh.boxRenderer);
        assertNull(fresh.clientResourceFallback);
        assertNull(fresh.datapackResourceFallback);
        assertSame(ILayoutBoxRenderer.DEFAULT, EngineContext.global().boxRenderer);
    }

    @Test
    void scopedRendererIsUsedOnlyInsideTheScope() {
        AtomicInteger calls = new AtomicInteger();
        EngineContext ctx = new EngineContext();
        ctx.boxRenderer = (root, w, h, kf, ss) -> { calls.incrementAndGet(); return List.of(); };
        assertEquals(List.of(), renderWith(ctx));
        assertEquals(1, calls.get());
        assertSame(ILayoutBoxRenderer.DEFAULT, EngineContext.current().boxRenderer, "スコープの外は global のまま");
    }

    @Test
    void nullRendererDrawsNothing() {
        EngineContext ctx = new EngineContext();
        ctx.boxRenderer = null;
        assertTrue(renderWith(ctx).isEmpty());
    }

    @Test
    void twoThreadsSeeTheirOwnRenderer() throws Exception {
        EngineContext a = new EngineContext();
        EngineContext b = new EngineContext();
        a.boxRenderer = (r, w, h, k, s) -> List.of();
        b.boxRenderer = (r, w, h, k, s) -> null;
        var ra = new Object() { Object v = "unset"; };
        var rb = new Object() { Object v = "unset"; };
        Thread ta = new Thread(() -> EngineContext.runWith(a, () -> ra.v = RenderPipeline.renderBox(null, 1, 1, Map.of(), new ScrollState())));
        Thread tb = new Thread(() -> EngineContext.runWith(b, () -> rb.v = RenderPipeline.renderBox(null, 1, 1, Map.of(), new ScrollState())));
        ta.start(); tb.start(); ta.join(); tb.join();
        assertEquals(List.of(), ra.v);
        assertNull(rb.v);
    }

    @Test
    void resourceFallbacksAreScopedToo() throws Exception {
        EngineContext ctx = new EngineContext();
        ctx.clientResourceFallback = p -> "client:" + p;
        ctx.datapackResourceFallback = p -> "dp:" + p;
        String out = EngineContext.callWith(ctx, () -> com.nishiyu.enderframelib.common.DocumentLoader.DEFAULT_RESOURCE_TEXT_RESOLVER.apply("ns:foo"));
        // データパックが先に答える
        assertEquals("dp:ns:foo", out);
        ctx.datapackResourceFallback = null;
        assertEquals("client:ns:foo", EngineContext.callWith(ctx, () -> com.nishiyu.enderframelib.common.DocumentLoader.DEFAULT_RESOURCE_TEXT_RESOLVER.apply("ns:foo")));
    }

    @Test
    void resourceTextResolverDefaultsToBuiltInAndIsScoped() throws Exception {
        var loader = com.nishiyu.enderframelib.common.DocumentLoader.class;
        assertNull(new EngineContext().resourceTextResolver);
        assertSame(com.nishiyu.enderframelib.common.DocumentLoader.DEFAULT_RESOURCE_TEXT_RESOLVER,
                com.nishiyu.enderframelib.common.DocumentLoader.effectiveResourceTextResolver(), "global が空なら既定");
        EngineContext ctx = new EngineContext();
        ctx.resourceTextResolver = p -> "custom:" + p;
        assertEquals("custom:a", EngineContext.callWith(ctx,
                () -> com.nishiyu.enderframelib.common.DocumentLoader.effectiveResourceTextResolver().apply("a")));
        assertSame(com.nishiyu.enderframelib.common.DocumentLoader.DEFAULT_RESOURCE_TEXT_RESOLVER,
                com.nishiyu.enderframelib.common.DocumentLoader.effectiveResourceTextResolver(), "スコープの外は既定へ戻る");
        assertNotNull(loader);
    }

    @Test
    void oldStaticsAreGoneAndTheInterfaceLivesInCore() throws IOException {
        Path common = Path.of("core/src/main/java/com/nishiyu/enderframelib/common");
        for (String f : List.of("RenderPipeline.java", "UiDocumentCompiler.java", "DocumentLoader.java", "UiView.java")) {
            String s = Files.readString(common.resolve(f));
            for (String bad : List.of("clientBoxRenderer", "DEFAULT_BOX_RENDERER", "interface ILayoutBoxRenderer",
                    "static Function<String, String> clientResourceFallback", "static Function<String, String> datapackResourceFallback", "resourceTextResolver =")) {
                assertFalse(s.contains(bad), f + " に " + bad + " が復活している（EngineContext へ）");
            }
        }
        for (String f : List.of("RenderPipeline.java", "UiDocumentCompiler.java")) {
            assertFalse(Files.readString(common.resolve(f)).contains("resourceTextResolver"), f);
        }
        assertTrue(Files.exists(Path.of("core/src/main/java/com/nishiyu/enderframelib/core/paint/ILayoutBoxRenderer.java")));
    }
}
