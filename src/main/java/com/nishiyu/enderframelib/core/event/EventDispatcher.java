package com.nishiyu.enderframelib.core.event;

import com.nishiyu.enderframelib.core.event.CoreEvent.DomEvent;
import com.nishiyu.enderframelib.api.dom.EventTarget;
import com.nishiyu.enderframelib.api.event.AbortSignal;
import com.nishiyu.enderframelib.api.event.Event;
import com.nishiyu.enderframelib.api.event.EventListener;
import com.nishiyu.enderframelib.api.event.EventListenerOptions;
import com.nishiyu.enderframelib.core.dom.EventForwardDeclaration;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import com.nishiyu.enderframelib.api.dom.DOMException;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * WHATWG DOM Standard §2.9 に準拠したイベントディスパッチャ。
 *
 * 伝播順序:
 * 1. キャプチャフェーズ (CAPTURING_PHASE): root -> target の親
 * 2. ターゲットフェーズ (AT_TARGET): target (capture リスナーおよび bubble リスナーを登録順に実行)
 * 3. バブリングフェーズ (BUBBLING_PHASE): target の親 -> root (bubbles == true の場合)
 * 4. 既定動作 (Default Action): !defaultPrevented の場合に実行
 */
public class EventDispatcher {

    /**
     * イベントを対象ノードにディスパッチする。
     */
    public static boolean dispatchEvent(
            HtmlNode target,
            Event event,
            BiConsumer<HtmlNode, String> inlineCallback,
            DefaultActionHandler defaultHandler
    ) {
        return dispatchEvent(target, event, inlineCallback, null, defaultHandler);
    }

    /**
     * @param forwardCallback 伝播経路の節が持つ {@link EventForwardDeclaration} のうち、このイベントの種別に
     *                        合うものを、{@code on*} 属性と同じ位置（各節のリスナーの後）で受け取る。{@code null} で無効
     */
    public static boolean dispatchEvent(
            HtmlNode target,
            Event event,
            BiConsumer<HtmlNode, String> inlineCallback,
            BiConsumer<HtmlNode, EventForwardDeclaration> forwardCallback,
            DefaultActionHandler defaultHandler
    ) {
        if (target == null || event == null) return false;

        CoreEvent coreEvent = event instanceof CoreEvent ce ? ce : null;
        if (coreEvent != null) {
            coreEvent.setTarget(target);
        }

        // ターゲットからルートまでのパス（composedPath 用）
        List<HtmlNode> composedPath = new ArrayList<>();
        HtmlNode cur = target;
        while (cur != null) {
            composedPath.add(cur);
            cur = cur.parent;
        }

        if (coreEvent != null) {
            coreEvent.setComposedPath(new ArrayList<>(composedPath));
        }

        // ルートからターゲットまでの祖先チェーン（キャプチャフェーズ用）
        List<HtmlNode> chain = new ArrayList<>(composedPath);
        Collections.reverse(chain); // [root, ..., parent, target]

        String eventType = event.getType();
        String attrName = "on" + eventType.toLowerCase(Locale.ROOT);

        // 1. キャプチャフェーズ (root から target の親まで)
        setPhase(event, Event.CAPTURING_PHASE);
        for (int i = 0; i < chain.size() - 1; i++) {
            HtmlNode node = chain.get(i);
            setCurrentTarget(event, node);

            invokeListeners(node, event, true);
            if (isStopped(event)) break;
        }

        // 2. ターゲットフェーズ (target)
        if (!isStopped(event)) {
            setPhase(event, Event.AT_TARGET);
            setCurrentTarget(event, target);

            invokeListenersAtTarget(target, event);

            // インライン on* 属性の実行
            if (!isImmediateStopped(event) && inlineCallback != null && target.attrs != null && target.attrs.containsKey(attrName)) {
                String action = target.attrs.get(attrName);
                if (action != null && !action.isEmpty()) {
                    inlineCallback.accept(target, action);
                }
            }
            if (!isImmediateStopped(event)) {
                forwardDeclared(target, eventType, forwardCallback);
            }
        }

        // 3. バブリングフェーズ (target の親から root まで遡る)
        if (event.isBubbles() && !isStopped(event)) {
            setPhase(event, Event.BUBBLING_PHASE);
            for (int i = chain.size() - 2; i >= 0; i--) {
                HtmlNode node = chain.get(i);
                setCurrentTarget(event, node);

                invokeListeners(node, event, false);

                // インライン on* 属性の実行
                if (!isImmediateStopped(event) && inlineCallback != null && node.attrs != null && node.attrs.containsKey(attrName)) {
                    String action = node.attrs.get(attrName);
                    if (action != null && !action.isEmpty()) {
                        inlineCallback.accept(node, action);
                    }
                }
                if (!isImmediateStopped(event)) {
                    forwardDeclared(node, eventType, forwardCallback);
                }

                if (isStopped(event)) break;
            }
        }

        // 伝播終了
        setPhase(event, Event.NONE);
        setCurrentTarget(event, null);

        // 4. 既定動作 (Default Action) の実行
        if (!event.isDefaultPrevented() && defaultHandler != null) {
            defaultHandler.executeDefaultAction(event);
        }

        return !event.isDefaultPrevented();
    }

    private static void forwardDeclared(
            HtmlNode node,
            String eventType,
            BiConsumer<HtmlNode, EventForwardDeclaration> forwardCallback
    ) {
        if (forwardCallback == null) return;
        for (EventForwardDeclaration declaration : EventForwardDeclaration.declaredOn(node, eventType)) {
            forwardCallback.accept(node, declaration);
        }
    }

    public static boolean dispatchEvent(HtmlNode target, Event event) {
        return dispatchEvent(target, event, null, null);
    }

    private static void invokeListeners(HtmlNode node, Event event, boolean capturePhase) {
        List<EventListenerEntry> listeners = node.getEventListenersSnapshot();
        for (EventListenerEntry entry : listeners) {
            if (isImmediateStopped(event)) break;
            if (!entry.type().equalsIgnoreCase(event.getType())) continue;
            if (entry.capture() != capturePhase) continue;

            dispatchToListener(node, entry, event);
        }
    }

    private static void invokeListenersAtTarget(HtmlNode target, Event event) {
        List<EventListenerEntry> listeners = target.getEventListenersSnapshot();
        for (EventListenerEntry entry : listeners) {
            if (isImmediateStopped(event)) break;
            if (!entry.type().equalsIgnoreCase(event.getType())) continue;

            dispatchToListener(target, entry, event);
        }
    }

    private static void dispatchToListener(HtmlNode node, EventListenerEntry entry, Event event) {
        if (entry.once()) {
            node.removeEventListener(entry.type(), entry.listener(), EventListenerOptions.of(entry.capture()));
        }

        CoreEvent ce = event instanceof CoreEvent c ? c : null;
        if (ce != null && entry.passive()) {
            ce.setInPassiveListener(true);
        }

        try {
            entry.listener().handleEvent(event);
        } catch (Exception e) {
            // イベントリスナー内の例外はログ記録または伝播
            e.printStackTrace();
        } finally {
            if (ce != null && entry.passive()) {
                ce.setInPassiveListener(false);
            }
        }
    }

    private static void setPhase(Event event, short phase) {
        if (event instanceof CoreEvent ce) {
            ce.setEventPhase(phase);
        } else if (event instanceof DomEvent de) {
            de.setEventPhase(switch (phase) {
                case Event.CAPTURING_PHASE -> DomEvent.EventPhase.CAPTURING_PHASE;
                case Event.AT_TARGET -> DomEvent.EventPhase.AT_TARGET;
                case Event.BUBBLING_PHASE -> DomEvent.EventPhase.BUBBLING_PHASE;
                default -> DomEvent.EventPhase.NONE;
            });
        }
    }

    private static void setCurrentTarget(Event event, EventTarget currentTarget) {
        if (event instanceof CoreEvent ce) {
            ce.setCurrentTarget(currentTarget);
        }
    }

    private static boolean isStopped(Event event) {
        if (event instanceof CoreEvent ce) {
            return ce.isPropagationStopped();
        }
        return false;
    }

    private static boolean isImmediateStopped(Event event) {
        if (event instanceof CoreEvent ce) {
            return ce.isImmediatePropagationStopped();
        }
        return false;
    }

    /**
     * イベントの既定動作（Default Action）を実行するハンドラ。
     */
    @FunctionalInterface
    public interface DefaultActionHandler {
        void executeDefaultAction(Event event);
    }

    /**
     * 登録されたイベントリスナーのエントリ。
     */
    public record EventListenerEntry(
            String type,
            EventListener listener,
            boolean capture,
            boolean once,
            boolean passive,
            @Nullable AbortSignal signal
    ) {
    }

    /**
     * {@link AbortController} および {@link AbortSignal} の具象実装。
     */
    public static class DomAbortController implements AbortSignal.Controller {

        private final DomAbortSignal signal = new DomAbortSignal();

        @Override
        public AbortSignal getSignal() {
            return signal;
        }

        @Override
        public void abort() {
            abort("The operation was aborted.");
        }

        @Override
        public void abort(@Nullable Object reason) {
            signal.triggerAbort(reason != null ? reason : "The operation was aborted.");
        }

        public static class DomAbortSignal implements AbortSignal {

            private boolean aborted = false;
            private Object reason = null;
            private final List<EventListener> abortListeners = new CopyOnWriteArrayList<>();

            @Override
            public boolean isAborted() {
                return aborted;
            }

            @Nullable
            @Override
            public Object getReason() {
                return reason;
            }

            @Override
            public void throwIfAborted() {
                if (aborted) {
                    if (reason instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new DOMException(String.valueOf(reason), "AbortError", DOMException.ABORT_ERR);
                }
            }

            @Override
            public void addEventListener(String type, EventListener listener) {
                if ("abort".equalsIgnoreCase(type) && listener != null) {
                    if (aborted) {
                        // すでに abort されている場合は即座に実行
                        listener.handleEvent(new CoreEvent("abort", false, false));
                    } else {
                        abortListeners.add(listener);
                    }
                }
            }

            @Override
            public void removeEventListener(String type, EventListener listener) {
                if ("abort".equalsIgnoreCase(type) && listener != null) {
                    abortListeners.remove(listener);
                }
            }

            public void triggerAbort(Object abortReason) {
                if (aborted) return;
                this.aborted = true;
                this.reason = abortReason;

                CoreEvent event = new CoreEvent("abort", false, false);
                for (EventListener listener : abortListeners) {
                    try {
                        listener.handleEvent(event);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }
}
