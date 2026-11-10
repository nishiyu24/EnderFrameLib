package com.nishiyu.enderframelib.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/** WebViewSurface のライフサイクル API（状態・タイマー・再読み込み・通知・needsFrame）。 */
class SurfaceLifecycleTest {

    private static WebViewSurface surface(String html) {
        return EnderEngine.html(html).size(120, 80).clearColor(0xFFFFFFFF).build();
    }

    /** 実時計のタイマーが実行されるまで、フレームを進めながら待つ（最大 3 秒）。 */
    private static boolean pump(WebViewSurface s, BooleanSupplier until) throws InterruptedException {
        long end = System.currentTimeMillis() + 3000;
        long t = 0;
        while (System.currentTimeMillis() < end) {
            s.frame(t += 16, false);
            if (until.getAsBoolean()) return true;
            Thread.sleep(5);
        }
        return until.getAsBoolean();
    }

    private static String text(WebViewSurface s, String id) {
        return s.dom().getElementById(id).getInnerHTML();
    }

    // ---- 状態 ----

    @Test
    void stateValuesReachTheDomAndRender() {
        try (WebViewSurface s = surface("<html><body><p id=\"t\">Hi {{name}}</p></body></html>")) {
            s.state().set("name", "Steve");
            s.frame(0, true);
            assertEquals("Hi Steve", text(s, "t"));
            assertEquals("Steve", s.state().get("name"));
            s.state().set("name", "Alex");
            s.frame(16, false);
            assertEquals("Hi Alex", text(s, "t"));
            assertEquals("", s.state().get("unset"));
        }
    }

    @Test
    void conditionalAndListBindingsFollowState() {
        String html = "<html><body><p id=\"c\" data-if=\"on\">shown</p>"
                + "<ul id=\"l\" data-for=\"it in rows\"><template><li>{{it.label}}</li></template></ul></body></html>";
        try (WebViewSurface s = surface(html)) {
            s.state().set("on", "false");
            s.state().setList("rows", List.of(Map.of("label", "a"), Map.of("label", "b")));
            s.frame(0, true);
            assertTrue(s.dom().getElementById("c").hasAttribute("hidden"));
            assertEquals(2, s.dom().querySelectorAll("#l li").getLength());
            s.state().set("on", "true");
            s.state().setList("rows", List.of(Map.of("label", "x")));
            s.frame(16, false);
            assertFalse(s.dom().getElementById("c").hasAttribute("hidden"));
            assertEquals(1, s.dom().querySelectorAll("#l li").getLength());
        }
    }

    @Test
    void watchersAndSuppliersWork() {
        try (WebViewSurface s = surface("<html><body><p id=\"t\">{{v}}</p></body></html>")) {
            List<String> seen = new ArrayList<>();
            s.state().watch("v", seen::add);
            s.state().set("v", "1").set("v", "1").set("v", "2");
            assertEquals(List.of("1", "2"), seen, "変わったときだけ通知される");
            AtomicInteger src = new AtomicInteger(7);
            s.state().bind("n", src::get);
            assertEquals("7", s.state().get("n"));
            src.set(9);
            s.state().forceSync();
            s.frame(0, true);
            assertEquals("2", text(s, "t"));
        }
    }

    // ---- 再読み込み ----

    @Test
    void loadReplacesTheDocumentAndKeepsState() {
        try (WebViewSurface s = surface("<html><body><p id=\"a\">{{k}}</p></body></html>")) {
            s.state().set("k", "kept");
            s.frame(0, true);
            assertEquals("kept", text(s, "a"));
            s.load("<html><body style=\"margin:0\"><p id=\"b\">{{k}}</p><div id=\"r\" style=\"width:20px;height:20px;background:#ff0000\"></div></body></html>");
            assertFalse(s.document().hasElement("a"));
            assertTrue(s.document().hasElement("b"));
            assertTrue(s.frame(16, false), "読み込み直後は描く");
            assertEquals("kept", text(s, "b"), "状態は新しい文書へ当て直される");
            assertThrows(NullPointerException.class, () -> s.load(null));
        }
    }

    // ---- タイマー ----

    @Test
    void timeoutRunsOnceAndCanBeCancelled() throws Exception {
        try (WebViewSurface s = surface("<html><body></body></html>")) {
            AtomicInteger ran = new AtomicInteger();
            AtomicInteger cancelled = new AtomicInteger();
            s.setTimeout(ran::incrementAndGet, 0);
            int id = s.setTimeout(cancelled::incrementAndGet, 0);
            assertTrue(s.clearTimeout(id));
            assertFalse(s.clearTimeout(id), "二度目は false");
            assertTrue(pump(s, () -> ran.get() > 0));
            for (int i = 0; i < 5; i++) s.frame(1000 + i, false);
            assertEquals(1, ran.get(), "1 回だけ");
            assertEquals(0, cancelled.get());
        }
    }

    @Test
    void intervalRepeatsUntilCleared() throws Exception {
        try (WebViewSurface s = surface("<html><body></body></html>")) {
            AtomicInteger ticks = new AtomicInteger();
            int id = s.setInterval(ticks::incrementAndGet, 1);
            assertTrue(pump(s, () -> ticks.get() >= 3));
            assertTrue(s.clearInterval(id));
            int at = ticks.get();
            Thread.sleep(20);
            s.frame(5000, false);
            assertEquals(at, ticks.get(), "取り消した後は進まない");
        }
    }

    @Test
    void animationFrameCallbackGetsTheFrameTime() {
        try (WebViewSurface s = surface("<html><body></body></html>")) {
            List<Double> stamps = new ArrayList<>();
            s.requestAnimationFrame(stamps::add);
            int cancelled = s.requestAnimationFrame(d -> stamps.add(-1.0));
            assertTrue(s.cancelAnimationFrame(cancelled));
            s.frame(1234, true);
            s.frame(1250, true);
            assertEquals(List.of(1234.0), stamps, "次のフレームの直前に 1 回だけ");
            assertEquals(-1, s.requestAnimationFrame(null));
        }
    }

    @Test
    void pendingTimerKeepsNeedsFrameTrue() {
        try (WebViewSurface s = surface("<html><body></body></html>")) {
            s.frame(0, true);
            s.frame(16, false);
            assertFalse(s.needsFrame(), "何も待っていなければ描き直さなくてよい");
            int id = s.setTimeout(() -> {}, 60_000);
            assertTrue(s.needsFrame());
            s.clearTimeout(id);
            assertFalse(s.needsFrame());
            s.invalidate();
            assertTrue(s.frame(32, false), "invalidate は次のフレームで描き直す");
        }
    }

    // ---- 通知・スクロール・終了 ----

    @Test
    void lifecycleListenerSeesFramesResizesLoadsAndClose() {
        List<String> log = new ArrayList<>();
        SurfaceLifecycleListener l = new SurfaceLifecycleListener() {
            @Override public void onLoaded() { log.add("loaded"); }
            @Override public void onFrame(long nowMs, boolean drew) { log.add("frame:" + nowMs + ":" + drew); }
            @Override public void onResized(int w, int h) { log.add("resized:" + w + "x" + h); }
            @Override public void onClosed() { log.add("closed"); }
        };
        WebViewSurface s = surface("<html><body></body></html>");
        s.addLifecycleListener(l);
        assertFalse(s.isClosed());
        s.frame(0, true);
        s.frame(16, false);
        s.resize(200, 100);
        s.load("<html><body>x</body></html>");
        s.close();
        s.close();
        assertTrue(s.isClosed());
        assertEquals(List.of("frame:0:true", "frame:16:false", "resized:200x100", "loaded", "closed"), log, "close は 1 回だけ通知");
    }

    @Test
    void removedListenersAreNotCalled() {
        AtomicInteger n = new AtomicInteger();
        SurfaceLifecycleListener l = new SurfaceLifecycleListener() {
            @Override public void onFrame(long nowMs, boolean drew) { n.incrementAndGet(); }
        };
        try (WebViewSurface s = surface("<html><body></body></html>")) {
            s.addLifecycleListener(l);
            s.frame(0, true);
            s.removeLifecycleListener(l);
            s.frame(16, true);
            assertEquals(1, n.get());
        }
    }

    @Test
    void scrollToAnchorFindsOnlyExistingElements() {
        String html = "<html><body style=\"margin:0\"><div style=\"height:600px\"></div><p id=\"end\">end</p></body></html>";
        try (WebViewSurface s = surface(html)) {
            s.frame(0, true);
            assertFalse(s.scrollToAnchor("missing"));
            assertFalse(s.scrollToAnchor(""));
            // 存在する id はスクロール箱が無くても例外にならない（結果は文書のスクロール可否に依る）
            assertDoesNotThrow(() -> s.scrollToAnchor("end"));
        }
    }

    @Test
    void lifecycleApiLeaksNoEngineInternals() {
        for (Class<?> c : List.of(SurfaceState.class, SurfaceLifecycleListener.class, WebViewSurface.class)) {
            for (var m : c.getMethods()) {
                List<Class<?>> types = new ArrayList<>(List.of(m.getParameterTypes()));
                types.add(m.getReturnType());
                for (Class<?> t : types) {
                    assertFalse(t.getName().startsWith("com.nishiyu.enderframelib.core")
                            || t.getName().startsWith("com.nishiyu.enderframelib.common"), c.getSimpleName() + "#" + m.getName());
                }
            }
        }
    }
}
