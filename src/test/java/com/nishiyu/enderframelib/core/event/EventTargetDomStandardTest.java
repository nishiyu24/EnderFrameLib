package com.nishiyu.enderframelib.core.event;

import com.nishiyu.enderframelib.core.event.EventDispatcher.DomAbortController;
import com.nishiyu.enderframelib.api.event.UIEvents;

import com.nishiyu.enderframelib.api.event.AbortSignal;
import com.nishiyu.enderframelib.api.event.AddEventListenerOptions;
import com.nishiyu.enderframelib.api.event.Event;
import com.nishiyu.enderframelib.api.event.EventListener;
import com.nishiyu.enderframelib.api.event.EventListenerOptions;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.RenderPipeline;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

public class EventTargetDomStandardTest {

    @Test
    @DisplayName("addEventListener と removeEventListener の基本動作")
    public void testAddAndRemoveEventListener() {
        HtmlElement target = new HtmlElement("div");
        AtomicInteger count = new AtomicInteger(0);
        EventListener listener = event -> count.incrementAndGet();

        target.addEventListener("custom", listener);
        target.dispatchEvent(new CoreEvent("custom", false, false));
        assertEquals(1, count.get(), "リスナーが1回実行される");

        target.removeEventListener("custom", listener);
        target.dispatchEvent(new CoreEvent("custom", false, false));
        assertEquals(1, count.get(), "削除後はリスナーが実行されない");
    }

    @Test
    @DisplayName("同一 (type, listener, capture) の重複登録防止 (WHATWG DOM §2.7)")
    public void testDeduplicateIdenticalListener() {
        HtmlElement target = new HtmlElement("div");
        AtomicInteger count = new AtomicInteger(0);
        EventListener listener = event -> count.incrementAndGet();

        target.addEventListener("click", listener);
        target.addEventListener("click", listener); // 重複登録の試み

        target.dispatchEvent(new CoreEvent("click", true, true));
        assertEquals(1, count.get(), "重複登録は無視され、リスナーは1度しか実行されない");
    }

    @Test
    @DisplayName("イベント伝播フェーズ順序: キャプチャ -> ターゲット -> バブリング")
    public void testCapturingAndBubblingOrder() {
        HtmlElement parent = new HtmlElement("div");
        HtmlElement child = new HtmlElement("span");
        parent.appendChild(child);

        List<String> order = new ArrayList<>();

        // 親ノードのリスナー (キャプチャとバブル)
        parent.addEventListener("test", ev -> {
            order.add("parent:capture:phase=" + ev.getEventPhase());
        }, AddEventListenerOptions.builder().capture(true).build());

        parent.addEventListener("test", ev -> {
            order.add("parent:bubble:phase=" + ev.getEventPhase());
        }, AddEventListenerOptions.builder().capture(false).build());

        // 子ノード（ターゲット）のリスナー
        child.addEventListener("test", ev -> {
            order.add("child:listener1:phase=" + ev.getEventPhase());
        });

        child.addEventListener("test", ev -> {
            order.add("child:listener2:phase=" + ev.getEventPhase());
        }, AddEventListenerOptions.builder().capture(true).build());

        CoreEvent event = new CoreEvent("test", true, true);
        child.dispatchEvent(event);

        List<String> expected = List.of(
                "parent:capture:phase=" + Event.CAPTURING_PHASE,
                "child:listener1:phase=" + Event.AT_TARGET,
                "child:listener2:phase=" + Event.AT_TARGET,
                "parent:bubble:phase=" + Event.BUBBLING_PHASE
        );
        assertEquals(expected, order, "キャプチャ -> ターゲット -> バブリングの順に実行されること");
    }

    @Test
    @DisplayName("stopPropagation による祖先へのバブリング停止")
    public void testStopPropagation() {
        HtmlElement parent = new HtmlElement("div");
        HtmlElement child = new HtmlElement("button");
        parent.appendChild(child);

        AtomicBoolean parentCalled = new AtomicBoolean(false);
        AtomicBoolean childCalled = new AtomicBoolean(false);

        parent.addEventListener("click", ev -> parentCalled.set(true));
        child.addEventListener("click", ev -> {
            childCalled.set(true);
            ev.stopPropagation();
        });

        child.dispatchEvent(new CoreEvent("click", true, true));

        assertTrue(childCalled.get(), "子のハンドラは呼ばれる");
        assertFalse(parentCalled.get(), "親へのバブリングは停止される");
    }

    @Test
    @DisplayName("stopImmediatePropagation による同一ノードの後続リスナー停止")
    public void testStopImmediatePropagation() {
        HtmlElement target = new HtmlElement("button");
        List<String> executed = new ArrayList<>();

        target.addEventListener("click", ev -> {
            executed.add("first");
            ev.stopImmediatePropagation();
        });

        target.addEventListener("click", ev -> {
            executed.add("second");
        });

        target.dispatchEvent(new CoreEvent("click", true, true));

        assertEquals(List.of("first"), executed, "同一ノードの後続リスナーは実行されないこと");
    }

    @Test
    @DisplayName("composedPath のターゲットからルートへの包含")
    public void testComposedPath() {
        HtmlElement grandParent = new HtmlElement("div");
        HtmlElement parent = new HtmlElement("div");
        HtmlElement child = new HtmlElement("span");
        grandParent.appendChild(parent);
        parent.appendChild(child);

        CoreEvent event = new CoreEvent("test", true, true);
        child.dispatchEvent(event);

        List<?> path = event.composedPath();
        assertEquals(List.of(child, parent, grandParent), path, "composedPath はターゲットからルートへの順序であること");
    }

    @Test
    @DisplayName("once オプションによる自動解除")
    public void testOnceOption() {
        HtmlElement target = new HtmlElement("button");
        AtomicInteger count = new AtomicInteger(0);

        target.addEventListener("click", ev -> count.incrementAndGet(),
                AddEventListenerOptions.builder().once(true).build());

        target.dispatchEvent(new CoreEvent("click", true, true));
        assertEquals(1, count.get(), "1回目のディスパッチで実行される");

        target.dispatchEvent(new CoreEvent("click", true, true));
        assertEquals(1, count.get(), "once: true のため2回目は自動解除されて実行されない");
    }

    @Test
    @DisplayName("passive: true による preventDefault の抑止")
    public void testPassiveOption() {
        HtmlElement target = new HtmlElement("div");

        target.addEventListener("wheel", ev -> {
            ev.preventDefault(); // passive なので無効化されるべき
        }, AddEventListenerOptions.builder().passive(true).build());

        CoreEvent event = new CoreEvent("wheel", true, true);
        target.dispatchEvent(event);

        assertFalse(event.isDefaultPrevented(), "passive リスナー内の preventDefault() は無視されること");
    }

    @Test
    @DisplayName("AbortSignal によるリスナー解除と中絶済みシグナル")
    public void testAbortSignal() {
        HtmlElement target = new HtmlElement("button");
        AtomicInteger count = new AtomicInteger(0);

        AbortSignal.Controller controller = new DomAbortController();
        target.addEventListener("click", ev -> count.incrementAndGet(),
                AddEventListenerOptions.builder().signal(controller.getSignal()).build());

        target.dispatchEvent(new CoreEvent("click", true, true));
        assertEquals(1, count.get());

        // シグナルを中絶
        controller.abort("User cancelled");
        assertTrue(controller.getSignal().isAborted());
        assertEquals("User cancelled", controller.getSignal().getReason());

        // 2回目のディスパッチ（解除されているはず）
        target.dispatchEvent(new CoreEvent("click", true, true));
        assertEquals(1, count.get(), "abort されたためリスナーは実行されない");

        // 既に abort 済みのシグナルを渡して登録
        target.addEventListener("click", ev -> count.incrementAndGet(),
                AddEventListenerOptions.builder().signal(controller.getSignal()).build());
        target.dispatchEvent(new CoreEvent("click", true, true));
        assertEquals(1, count.get(), "既に abort 済みの場合は登録されないこと");
    }

    @Test
    @DisplayName("CustomEvent による detail データの受け渡し")
    public void testCustomEventWithDetail() {
        HtmlElement target = new HtmlElement("div");
        List<Object> receivedDetails = new ArrayList<>();

        target.addEventListener("item-selected", ev -> {
            if (ev instanceof UIEvents.Custom ce) {
                receivedDetails.add(ce.getDetail());
            }
        });

        CoreEvent.CoreCustomEvent customEvent = new CoreEvent.CoreCustomEvent(
                "item-selected", true, true, "item-123");
        target.dispatchEvent(customEvent);

        assertEquals(List.of("item-123"), receivedDetails, "CustomEvent の detail を取得できること");
    }
}
