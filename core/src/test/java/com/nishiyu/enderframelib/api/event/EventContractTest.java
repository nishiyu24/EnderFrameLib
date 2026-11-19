package com.nishiyu.enderframelib.api.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.api.EnderEngine;
import com.nishiyu.enderframelib.api.WebViewSurface;
import com.nishiyu.enderframelib.api.dom.Element;
import com.nishiyu.enderframelib.api.dom.EventTarget;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code api.event} と {@code EventTarget} の契約（ENGINE_REARCHITECTURE_PLAN T4）。公開 API だけを使い、利用者が自分で書く
 * {@code Event}・{@code AbortSignal} の実装と、面への入力から来るイベントの両方で、WHATWG DOM §2.7〜2.9 の約束を確かめる。
 */
class EventContractTest {

    private static final String PAGE = "<html><body style=\"margin:0\"><div id=\"outer\"><div id=\"mid\">"
            + "<button id=\"btn\" style=\"display:block;width:80px;height:30px\">x</button></div></div></body></html>";

    private static WebViewSurface surface() {
        return EnderEngine.html(PAGE).size(120, 60).build();
    }

    /** 利用者が書くイベント（公開の interface だけで作る）。 */
    static final class UserEvent implements Event {
        final String type;
        final boolean bubbles, cancelable;
        boolean prevented;

        UserEvent(String type, boolean bubbles, boolean cancelable) {
            this.type = type;
            this.bubbles = bubbles;
            this.cancelable = cancelable;
        }

        @Override public String getType() { return type; }
        @Override public EventTarget getTarget() { return null; }
        @Override public EventTarget getCurrentTarget() { return null; }
        @Override public List<EventTarget> composedPath() { return List.of(); }
        @Override public short getEventPhase() { return NONE; }
        @Override public void stopPropagation() { }
        @Override public void stopImmediatePropagation() { }
        @Override public boolean isBubbles() { return bubbles; }
        @Override public boolean isCancelable() { return cancelable; }
        @Override public void preventDefault() { if (cancelable) prevented = true; }
        @Override public boolean isDefaultPrevented() { return prevented; }
        @Override public boolean isComposed() { return false; }
        @Override public boolean isTrusted() { return false; }
        @Override public double getTimeStamp() { return 0; }
    }

    /** 利用者が書く中止の信号（AbortController の役も兼ねる）。 */
    static final class UserSignal implements AbortSignal {
        private final List<EventListener> listeners = new ArrayList<>();
        private Object reason;

        void abort(Object why) {
            reason = why;
            for (EventListener l : List.copyOf(listeners)) l.handleEvent(new UserEvent("abort", false, false));
        }

        @Override public boolean isAborted() { return reason != null; }
        @Override public Object getReason() { return reason; }
        @Override public void throwIfAborted() { if (reason != null) throw new IllegalStateException(String.valueOf(reason)); }
        @Override public void addEventListener(String type, EventListener listener) { if ("abort".equals(type)) listeners.add(listener); }
        @Override public void removeEventListener(String type, EventListener listener) { listeners.remove(listener); }
    }

    // ---- 選択肢の値 ----

    @Test
    void listenerOptionsHaveTheDictionaryDefaults() {
        assertFalse(EventListenerOptions.of(false).isCapture());
        assertTrue(new EventListenerOptions(true).isCapture());
        AddEventListenerOptions d = AddEventListenerOptions.builder().build();
        assertFalse(d.isCapture());
        assertFalse(d.isPassive());
        assertFalse(d.isOnce());
        assertNull(d.getSignal());
        AddEventListenerOptions o = AddEventListenerOptions.of(true, true);
        assertTrue(o.isCapture());
        assertTrue(o.isOnce());
        assertFalse(o.isPassive());
        UserSignal s = new UserSignal();
        AddEventListenerOptions b = AddEventListenerOptions.builder().capture(true).passive(true).once(true).signal(s).build();
        assertTrue(b.isCapture() && b.isPassive() && b.isOnce());
        assertSame(s, b.getSignal());
        assertTrue(b instanceof EventListenerOptions, "removeEventListener に同じ値を渡せる");
    }

    // ---- 登録と解除（§2.7 add/remove an event listener） ----

    @Test
    void theSameListenerIsRegisteredOncePerCaptureFlag() {
        try (WebViewSurface s = surface()) {
            Element btn = s.dom().getElementById("btn");
            List<String> log = new ArrayList<>();
            EventListener l = ev -> log.add("hit");
            btn.addEventListener("ping", l);
            btn.addEventListener("ping", l); // 重複は無視
            btn.addEventListener("ping", l, true); // capture が違えば別の登録
            btn.dispatchEvent(new UserEvent("ping", false, false));
            assertEquals(2, log.size(), "(type, listener, capture) ごとに 1 回");

            log.clear();
            btn.removeEventListener("ping", l, true);
            btn.dispatchEvent(new UserEvent("ping", false, false));
            assertEquals(1, log.size(), "capture の登録だけが外れる");

            log.clear();
            btn.removeEventListener("ping", l);
            btn.dispatchEvent(new UserEvent("ping", false, false));
            assertEquals(0, log.size());
        }
    }

    @Test
    void onceListenersRunOnlyOnce() {
        try (WebViewSurface s = surface()) {
            Element btn = s.dom().getElementById("btn");
            List<String> log = new ArrayList<>();
            btn.addEventListener("ping", ev -> log.add("once"), AddEventListenerOptions.of(false, true));
            btn.dispatchEvent(new UserEvent("ping", false, false));
            btn.dispatchEvent(new UserEvent("ping", false, false));
            assertEquals(List.of("once"), log);
        }
    }

    @Test
    void abortSignalRemovesTheListenerAndAnAbortedSignalAddsNothing() {
        try (WebViewSurface s = surface()) {
            Element btn = s.dom().getElementById("btn");
            List<String> log = new ArrayList<>();
            UserSignal signal = new UserSignal();
            btn.addEventListener("ping", ev -> log.add("live"), AddEventListenerOptions.builder().signal(signal).build());
            btn.dispatchEvent(new UserEvent("ping", false, false));
            signal.abort("stop");
            btn.dispatchEvent(new UserEvent("ping", false, false));
            assertEquals(List.of("live"), log, "中止した後は呼ばれない");

            UserSignal dead = new UserSignal();
            dead.abort("already");
            btn.addEventListener("ping", ev -> log.add("dead"), AddEventListenerOptions.builder().signal(dead).build());
            btn.dispatchEvent(new UserEvent("ping", false, false));
            assertEquals(List.of("live"), log, "中止済みの信号つきの登録は足されない");
        }
    }

    // ---- 配送（§2.9 dispatch） ----

    @Test
    void userEventsTravelCaptureTargetThenBubble() {
        try (WebViewSurface s = surface()) {
            Element outer = s.dom().getElementById("outer"), btn = s.dom().getElementById("btn");
            List<String> log = new ArrayList<>();
            outer.addEventListener("ping", ev -> log.add("outer-bubble"));
            outer.addEventListener("ping", ev -> log.add("outer-capture"), true);
            btn.addEventListener("ping", ev -> log.add("target"));
            assertTrue(btn.dispatchEvent(new UserEvent("ping", true, false)));
            assertEquals(List.of("outer-capture", "target", "outer-bubble"), log);

            log.clear();
            btn.dispatchEvent(new UserEvent("ping", false, false));
            assertEquals(List.of("outer-capture", "target"), log, "bubbles=false は泡立たない（capture は走る）");
        }
    }

    @Test
    void dispatchReturnsFalseOnlyWhenACancelableEventIsPrevented() {
        try (WebViewSurface s = surface()) {
            Element btn = s.dom().getElementById("btn");
            btn.addEventListener("ping", Event::preventDefault);
            assertFalse(btn.dispatchEvent(new UserEvent("ping", true, true)), "取り消せるイベントを取り消した");
            assertTrue(btn.dispatchEvent(new UserEvent("ping", true, false)), "取り消せないイベントの preventDefault は効かない");
        }
    }

    /** 面への入力から来るイベントは、エンジンが段階・現在の対象・経路を持ち、伝播を止められる。 */
    @Test
    void inputEventsExposePhaseCurrentTargetPathAndStopPropagation() {
        try (WebViewSurface s = surface()) {
            Element outer = s.dom().getElementById("outer"), mid = s.dom().getElementById("mid"), btn = s.dom().getElementById("btn");
            List<String> log = new ArrayList<>();
            outer.addEventListener("click", ev -> {
                log.add("capture:" + ev.getEventPhase() + ":" + (ev.getCurrentTarget() == outer) + ":" + (ev.getTarget() == btn));
                List<EventTarget> path = ev.composedPath();
                log.add("path:" + (path.get(0) == btn) + ":" + path.contains(mid) + ":" + path.contains(outer));
            }, true);
            btn.addEventListener("click", ev -> log.add("target:" + ev.getEventPhase() + ":" + ev.isBubbles()));
            btn.addEventListener("click", Event::stopPropagation);
            btn.addEventListener("click", ev -> log.add("same-node-still-runs"));
            mid.addEventListener("click", ev -> log.add("mid-bubble"));
            s.frame(0, true);
            s.mouseMove(10, 10);
            s.mouseButton(true);
            s.mouseButton(false);
            s.frame(16, false);
            assertEquals(List.of("capture:" + Event.CAPTURING_PHASE + ":true:true", "path:true:true:true",
                    "target:" + Event.AT_TARGET + ":true", "same-node-still-runs"), log,
                    "stopPropagation は同じ節の残りのリスナーを止めず、祖先へ泡立たない");
        }
    }

    @Test
    void stopImmediatePropagationSkipsTheRemainingListenersOnTheSameNode() {
        try (WebViewSurface s = surface()) {
            Element btn = s.dom().getElementById("btn");
            List<String> log = new ArrayList<>();
            btn.addEventListener("click", ev -> { log.add("first"); ev.stopImmediatePropagation(); });
            btn.addEventListener("click", ev -> log.add("second"));
            s.frame(0, true);
            s.mouseMove(10, 10);
            s.mouseButton(true);
            s.mouseButton(false);
            s.frame(16, false);
            assertEquals(List.of("first"), log);
        }
    }

    @Test
    void preventDefaultInsideAPassiveListenerIsIgnored() {
        try (WebViewSurface s = surface()) {
            Element btn = s.dom().getElementById("btn");
            List<Boolean> seen = new ArrayList<>();
            btn.addEventListener("click", Event::preventDefault, AddEventListenerOptions.builder().passive(true).build());
            btn.addEventListener("click", ev -> seen.add(ev.isDefaultPrevented()));
            s.frame(0, true);
            s.mouseMove(10, 10);
            s.mouseButton(true);
            s.mouseButton(false);
            s.frame(16, false);
            assertEquals(List.of(false), seen);
        }
    }
}
