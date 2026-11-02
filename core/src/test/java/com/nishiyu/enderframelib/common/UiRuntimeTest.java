package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Step 5-3: タイマー・リアクティブ状態・pub/sub・canvas が Minecraft なし（コアだけ）で動くことの確認。 */
class UiRuntimeTest {

    private static UiView load(String html) {
        UiView view = new UiView("rt");
        view.loadLocalHtml(html, 320, 240);
        return view;
    }

    // ---------------------------------------------------------------- UiTimers

    @Test
    void animationFrameRunsOnceAndCanBeCancelled() {
        UiTimers timers = new UiTimers("s");
        List<Double> seen = new ArrayList<>();
        int a = timers.requestAnimationFrame(seen::add);
        int b = timers.requestAnimationFrame(seen::add);
        assertNotEquals(a, b);
        assertTrue(timers.cancelAnimationFrame(b));
        assertFalse(timers.cancelAnimationFrame(b));

        timers.runAnimationFrames(16.5);
        assertEquals(List.of(16.5), seen);
        timers.runAnimationFrames(33.0);
        assertEquals(1, seen.size(), "1 回実行したら解除される");
        assertEquals(-1, timers.requestAnimationFrame(null));
    }

    @Test
    void timeoutAndIntervalFollowTheClock() {
        UiTimers timers = new UiTimers("s");
        AtomicInteger once = new AtomicInteger();
        AtomicInteger every = new AtomicInteger();
        long t0 = System.currentTimeMillis();
        timers.setTimeout(once::incrementAndGet, 100);
        int interval = timers.setInterval(every::incrementAndGet, 50);

        timers.runDueTimers(() -> t0 + 10);
        assertEquals(0, once.get());
        assertEquals(0, every.get());

        timers.runDueTimers(() -> t0 + 60);
        assertEquals(0, once.get());
        assertEquals(1, every.get());

        timers.runDueTimers(() -> t0 + 120);
        assertEquals(1, once.get());
        assertEquals(2, every.get());

        timers.runDueTimers(() -> t0 + 500);
        assertEquals(1, once.get(), "setTimeout は 1 回だけ");
        assertEquals(3, every.get());

        assertTrue(timers.clearInterval(interval));
        timers.runDueTimers(() -> t0 + 5000);
        assertEquals(3, every.get());
        assertTrue(timers.isEmpty());
    }

    @Test
    void failingCallbackDoesNotStopTheOthers() {
        UiTimers timers = new UiTimers("s");
        AtomicInteger ran = new AtomicInteger();
        timers.requestAnimationFrame(t -> { throw new IllegalStateException("boom"); });
        timers.requestAnimationFrame(t -> ran.incrementAndGet());
        timers.setTimeout(() -> { throw new IllegalStateException("boom"); }, 0);
        timers.setTimeout(ran::incrementAndGet, 0);

        timers.runAnimationFrames(1);
        timers.runDueTimers(() -> System.currentTimeMillis() + 10);
        assertEquals(2, ran.get());
    }

    @Test
    void framesOfSeveralRegistriesRunAsOneBatch() {
        UiTimers a = new UiTimers("a");
        UiTimers b = new UiTimers("b");
        List<String> order = new ArrayList<>();
        a.requestAnimationFrame(t -> {
            order.add("a");
            // バッチ実行中に登録したものは次のフレームへ回る
            b.requestAnimationFrame(x -> order.add("late"));
        });
        b.requestAnimationFrame(t -> order.add("b"));
        UiTimers.runAnimationFrames(List.of(a, b), 1);
        assertEquals(2, order.size());
        assertTrue(order.containsAll(List.of("a", "b")));
        UiTimers.runAnimationFrames(List.of(a, b), 2);
        assertEquals(3, order.size());
        assertEquals("late", order.get(2));
    }

    @Test
    void closingTheViewDropsItsRegistrations() {
        UiView view = load("<div id='x'></div>");
        view.timers().setInterval(() -> {}, 10);
        view.timers().requestAnimationFrame(t -> {});
        view.dataBindings().add(new UiPubSub.DataBinding("x", "c", "p", "text"));
        assertFalse(view.timers().isEmpty());
        view.close();
        assertTrue(view.timers().isEmpty());
        assertTrue(view.dataBindings().isEmpty());
    }

    // ---------------------------------------------------------------- UiStateStore

    @Test
    void stateStoreRewritesThePlaceholdersOfTheView() {
        UiView view = load("<div id='root'><p id='msg'>Hello {{name}}, count is {{count}}</p></div>");
        UiStateStore store = new UiStateStore("rt", () -> view);
        store.set("name", "Alice").set("count", 1);
        store.syncToDom();
        assertEquals("Hello Alice, count is 1", view.rootNode().getElementById("msg").getTextContent());

        store.set("count", 2);
        assertEquals("Hello Alice, count is 2", view.rootNode().getElementById("msg").getTextContent());
        assertEquals(2, store.getInt("count", -1));
    }

    @Test
    void stateStoreWatchersAndSuppliersWork() {
        UiView view = load("<p id='msg'>{{v}}</p>");
        UiStateStore store = new UiStateStore("rt", () -> view);
        List<String> seen = new ArrayList<>();
        store.watch("v", seen::add);
        AtomicInteger source = new AtomicInteger(5);
        store.bind("w", source::get);
        store.set("v", "a").set("v", "a").set("v", "b");
        assertEquals(List.of("a", "b"), seen, "同じ値の再設定では通知しない");
        assertEquals("5", store.get("w"));
        source.set(7);
        store.syncToDom();
        assertEquals("7", store.get("w"), "bind したサプライヤは syncToDom で引き直す");
    }

    @Test
    void stateStoreWithoutViewIsHarmless() {
        UiStateStore store = new UiStateStore("none", () -> null);
        store.set("a", 1);
        store.syncToDom();
        assertEquals("1", store.get("a"));
    }

    // ---------------------------------------------------------------- UiPubSub

    @Test
    void pubSubUpdatesSubscribedViewsOnly() {
        UiView sub = load("<div><span id='n'>0</span></div>");
        UiView other = load("<div><span id='n'>0</span></div>");
        UiPubSub bus = new UiPubSub();
        List<String> sent = new ArrayList<>();
        bus.subscriptionSender = sent::add;

        bus.registerDataBinding(sub, "n", "stats", "hp", "text");
        bus.requestSubscribe(sub, "stats");
        bus.registerDataBinding(other, "n", "stats", "hp", "text");
        assertEquals(List.of("stats"), sent);

        bus.onReceiveUpdate("stats", true, Map.of("hp", 20), List.of(sub, other));
        assertEquals("20", sub.rootNode().getElementById("n").getTextContent());
        assertEquals("0", other.rootNode().getElementById("n").getTextContent(), "購読していないビューは触らない");
        assertEquals(20, bus.getChannelData("stats").get("hp"));

        // 差分更新・削除
        bus.onReceiveUpdate("stats", false, Map.of("hp", 15), List.of(sub));
        assertEquals("15", sub.rootNode().getElementById("n").getTextContent());
        Map<String, Object> removal = new java.util.HashMap<>();
        removal.put("hp", null);
        bus.onReceiveUpdate("stats", false, removal, List.of(sub));
        assertFalse(bus.getChannelData("stats").containsKey("hp"));
    }

    @Test
    void pubSubChannelListenersAreCalledEvenWithoutChange() {
        UiPubSub bus = new UiPubSub();
        AtomicInteger calls = new AtomicInteger();
        java.util.function.Consumer<Map<String, Object>> l = m -> calls.incrementAndGet();
        bus.addChannelListener("c", l);
        bus.onReceiveUpdate("c", true, Map.of("k", 1), List.of());
        bus.onReceiveUpdate("c", true, Map.of("k", 1), List.of());
        assertEquals(2, calls.get());
        bus.removeChannelListener("c", l);
        bus.onReceiveUpdate("c", true, Map.of("k", 2), List.of());
        assertEquals(2, calls.get());
        bus.clearAll();
        assertTrue(bus.getChannelData("c").isEmpty());
    }

    @Test
    void pubSubReregisteringReplacesTheBinding() {
        UiView view = load("<span id='n'></span>");
        UiPubSub bus = new UiPubSub();
        bus.registerDataBinding(view, "n", "c1", "a", "text");
        bus.registerDataBinding(view, "n", "c2", "b", "text");
        assertEquals(1, view.dataBindings().size());
        assertEquals("c2", view.dataBindings().get(0).channel());
        HtmlNode tpl = new HtmlNode("template");
        bus.registerVirtualList(view, "n", "c", tpl, 10, 10);
        bus.registerVirtualList(view, "n", "c", tpl, 20, 10);
        assertEquals(1, view.virtualListBindings().size());
        assertEquals(20, view.virtualListBindings().get(0).itemW());
    }

    // ---------------------------------------------------------------- CanvasSurface

    @Test
    void canvasSurfaceDrawsWithoutAnyHost() {
        CanvasSurface canvas = new CanvasSurface();
        assertEquals(CanvasSurface.DEFAULT_WIDTH, canvas.width());
        assertEquals(CanvasSurface.DEFAULT_HEIGHT, canvas.height());
        long before = canvas.context2d().modCount();
        canvas.resize(40, 20).fillRect(0, 0, 10, 10, 0xFFFF0000);
        assertEquals(40, canvas.width());
        assertEquals(0xFFFF0000, canvas.context2d().bitmap().get(5, 5));
        assertEquals(0, canvas.context2d().bitmap().get(30, 15));
        assertTrue(canvas.context2d().modCount() > before);

        canvas.clear();
        assertEquals(0, canvas.context2d().bitmap().get(5, 5));
        canvas.fillRect(0, 0, 0, 5, 0xFF00FF00); // 幅 0 は無視
        canvas.drawLine(0, 10, 39, 10, 2, 0xFF0000FF).drawCircle(20, 10, 5, 0, 0xFFFFFFFF);
        assertNotEquals(0, canvas.context2d().bitmap().get(20, 10));
    }

    @Test
    void canvasSurfaceAdoptsTheElementSize() {
        CanvasSurface canvas = new CanvasSurface();
        canvas.adoptSize(Map.of("canvas-width", "64", "canvas-height", "32"));
        assertEquals(64, canvas.width());
        assertEquals(32, canvas.height());
        canvas.adoptSize(Map.of("canvas-width", "bad"));
        assertEquals(CanvasSurface.DEFAULT_WIDTH, canvas.width());
    }
}
