package com.nishiyu.enderframelib.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.api.dom.Document;
import com.nishiyu.enderframelib.api.dom.Element;
import com.nishiyu.enderframelib.api.event.AbortSignal;
import com.nishiyu.enderframelib.api.event.AddEventListenerOptions;
import com.nishiyu.enderframelib.api.event.Event;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A9 / §6-114: 公開 API だけで合成イベントと AbortController を作れ、伝播の制御が効く。 */
class ApiEventFactoryTest {

    private static final String PAGE = "<html><body><div id='outer'><div id='inner'></div></div></body></html>";

    @Test
    void createdEventIsDispatchedThroughCaptureTargetAndBubble() {
        try (WebViewSurface s = EnderEngine.html(PAGE).size(50, 50).build()) {
            Document d = s.dom();
            Element outer = d.getElementById("outer");
            Element inner = d.getElementById("inner");
            List<String> log = new ArrayList<>();
            outer.addEventListener("ping", e -> log.add("outer-bubble:" + e.getEventPhase()));
            inner.addEventListener("ping", e -> log.add("inner-target:" + e.getEventPhase()));
            Event ev = EnderEngine.createEvent("ping", true, true);
            assertEquals("ping", ev.getType());
            assertTrue(ev.isBubbles());
            assertTrue(ev.isCancelable());
            assertTrue(inner.dispatchEvent(ev));
            assertEquals(List.of("inner-target:" + Event.AT_TARGET, "outer-bubble:" + Event.BUBBLING_PHASE), log);
        }
    }

    @Test
    void stopPropagationAndPreventDefaultWorkOnCreatedEvents() {
        try (WebViewSurface s = EnderEngine.html(PAGE).size(50, 50).build()) {
            Document d = s.dom();
            Element outer = d.getElementById("outer");
            Element inner = d.getElementById("inner");
            List<String> log = new ArrayList<>();
            outer.addEventListener("ping", e -> log.add("outer"));
            inner.addEventListener("ping", e -> {
                log.add("inner");
                e.stopPropagation();
                e.preventDefault();
            });
            Event ev = EnderEngine.createEvent("ping", true, true);
            assertFalse(inner.dispatchEvent(ev), "preventDefault された cancelable イベントは false");
            assertEquals(List.of("inner"), log, "stopPropagation で親へ届かない");
            assertTrue(ev.isDefaultPrevented());

            Event plain = EnderEngine.createEvent("ping", true, false);
            plain.preventDefault();
            assertFalse(plain.isDefaultPrevented(), "cancelable でなければ preventDefault は効かない");
        }
    }

    @Test
    void abortControllerRemovesAListenerThroughTheSignal() {
        try (WebViewSurface s = EnderEngine.html(PAGE).size(50, 50).build()) {
            Element inner = s.dom().getElementById("inner");
            AbortSignal.Controller controller = EnderEngine.createAbortController();
            assertFalse(controller.getSignal().isAborted());
            int[] count = {0};
            inner.addEventListener("ping", e -> count[0]++, new AddEventListenerOptions(false, false, false, controller.getSignal()));
            inner.dispatchEvent(EnderEngine.createEvent("ping", false, false));
            assertEquals(1, count[0]);
            controller.abort("done");
            assertTrue(controller.getSignal().isAborted());
            assertEquals("done", controller.getSignal().getReason());
            inner.dispatchEvent(EnderEngine.createEvent("ping", false, false));
            assertEquals(1, count[0], "中止した signal のリスナーは外れる");
        }
    }
}
