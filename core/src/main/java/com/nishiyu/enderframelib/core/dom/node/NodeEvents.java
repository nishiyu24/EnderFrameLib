package com.nishiyu.enderframelib.core.dom.node;

import com.nishiyu.enderframelib.api.event.AbortSignal;
import com.nishiyu.enderframelib.api.event.AddEventListenerOptions;
import com.nishiyu.enderframelib.api.event.Event;
import com.nishiyu.enderframelib.api.event.EventListener;
import com.nishiyu.enderframelib.api.event.EventListenerOptions;
import com.nishiyu.enderframelib.core.dom.node.EventDispatcher.EventListenerEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 節の EventTarget の実装（リスナーの登録と配送の入口）。{@link HtmlNode} から分けた（M5-1）。 */
final class NodeEvents {
    private NodeEvents() {}

    static void add(HtmlNode n, String type, EventListener listener, AddEventListenerOptions options) {
        if (type == null || type.isEmpty() || listener == null) return;
        boolean capture = options != null && options.isCapture();
        boolean once = options != null && options.isOnce();
        boolean passive = options != null && options.isPassive();
        AbortSignal signal = options != null ? options.getSignal() : null;
        if (signal != null && signal.isAborted()) return;

        // 同一 (type, listener, capture) の重複登録を防止 (§2.7)
        List<EventListenerEntry> listeners = n.eventListeners(true);
        for (EventListenerEntry existing : listeners) {
            if (existing.type().equalsIgnoreCase(type)
                    && Objects.equals(existing.listener(), listener)
                    && existing.capture() == capture) {
                return;
            }
        }
        listeners.add(new EventListenerEntry(type, listener, capture, once, passive, signal));
        if (signal != null) {
            signal.addEventListener("abort", ev -> n.removeEventListener(type, listener, options));
        }
    }

    static void remove(HtmlNode n, String type, EventListener listener, EventListenerOptions options) {
        if (type == null || type.isEmpty() || listener == null) return;
        boolean capture = options != null && options.isCapture();
        List<EventListenerEntry> listeners = n.eventListeners(false);
        if (listeners == null) return;
        listeners.removeIf(entry -> entry.type().equalsIgnoreCase(type)
                && Objects.equals(entry.listener(), listener)
                && entry.capture() == capture);
    }

    static List<EventListenerEntry> snapshot(HtmlNode n) {
        List<EventListenerEntry> listeners = n.eventListeners(false);
        return listeners == null || listeners.isEmpty() ? List.of() : new ArrayList<>(listeners);
    }

    /**
     * 既定動作を {@code defaultActions} に差し替えて配送する。{@code on*} 属性のアクション名と
     * {@link EventForwardDeclaration} の宣言は、文書の {@link HtmlDocument.InlineActionSink} へ渡る。ただし渡るのは
     * 伝播がすべて終わってからで、どこかのリスナーが {@code preventDefault} していれば渡らない
     * （クライアントのリスナーが先に走り、取り消されなければ文書の外へ出す）。
     */
    static boolean dispatch(HtmlNode n, HtmlDocument document, Event event,
                            EventDispatcher.DefaultActionHandler defaultActions) {
        HtmlDocument.InlineActionSink sink = document != null ? document.inlineActionSink() : null;
        if (sink == null) return EventDispatcher.dispatchEvent(n, event, null, defaultActions);

        DeferredRunnables deferredOutbound = new DeferredRunnables();
        return EventDispatcher.dispatchEvent(n, event,
                (node, action) -> deferredOutbound.add(() -> sink.run(node, event, action)),
                (node, declaration) -> deferredOutbound.add(() -> sink.forward(node, event, declaration)),
                ev -> {
                    deferredOutbound.runAll();
                    if (defaultActions != null) defaultActions.executeDefaultAction(ev);
                });
    }

    private static final class DeferredRunnables {
        private List<Runnable> items;

        void add(Runnable action) {
            if (items == null) items = new ArrayList<>(2);
            items.add(action);
        }

        void runAll() {
            if (items == null) return;
            for (int i = 0, n = items.size(); i < n; i++) items.get(i).run();
        }
    }
}
