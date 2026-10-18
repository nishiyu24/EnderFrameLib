package com.nishiyu.enderframelib.core.event;

import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.event.DomEvent.EventPhase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * W3C DOM Level 2 / DOM Level 3 Event 仕様に準拠したイベントディスパッチャ。
 *
 * 伝播順序:
 * 1. キャプチャフェーズ (CAPTURING_PHASE): root -> target の親
 * 2. ターゲットフェーズ (AT_TARGET): target
 * 3. バブリングフェーズ (BUBBLING_PHASE): target の親 -> root (bubbles == true の場合)
 * 4. 既定動作 (Default Action): !defaultPrevented の場合に実行
 */
public class EventDispatcher {

    /**
     * イベントを対象ノードにディスパッチする。
     *
     * @param target         イベントの発生元ノード
     * @param event          ディスパッチするイベントオブジェクト
     * @param inlineCallback インライン属性（onclick 等）のアクションを実行するコールバック (node, actionName)
     * @param defaultHandler 既定動作ハンドラ（null 可）
     * @return イベントがキャンセル（preventDefault）されずに完了した場合は true、キャンセルされた場合は false
     */
    public static boolean dispatchEvent(
            HtmlNode target,
            DomEvent event,
            BiConsumer<HtmlNode, String> inlineCallback,
            DefaultActionHandler defaultHandler
    ) {
        if (target == null || event == null) return false;

        event.setTarget(target);

        // ルートからターゲットまでの祖先チェーンを構築
        List<HtmlNode> chain = new ArrayList<>();
        HtmlNode cur = target;
        while (cur != null) {
            chain.add(cur);
            cur = cur.parent;
        }
        Collections.reverse(chain); // [root, ..., parent, target]

        String attrName = "on" + event.type().toLowerCase();

        // 1. キャプチャフェーズ (root から target の親まで)
        event.setEventPhase(EventPhase.CAPTURING_PHASE);
        for (int i = 0; i < chain.size() - 1; i++) {
            HtmlNode node = chain.get(i);
            event.setCurrentTarget(node);
            // ※ 将来キャプチャリスナが登録されている場合はここで呼び出す
            if (event.isPropagationStopped()) break;
        }

        // 2. ターゲットフェーズ (target)
        if (!event.isPropagationStopped()) {
            event.setEventPhase(EventPhase.AT_TARGET);
            event.setCurrentTarget(target);

            // インライン on* 属性の実行
            if (inlineCallback != null && target.attrs != null && target.attrs.containsKey(attrName)) {
                String action = target.attrs.get(attrName);
                if (action != null && !action.isEmpty()) {
                    inlineCallback.accept(target, action);
                }
            }
        }

        // 3. バブリングフェーズ (target の親から root まで遡る)
        if (event.bubbles() && !event.isPropagationStopped()) {
            event.setEventPhase(EventPhase.BUBBLING_PHASE);
            for (int i = chain.size() - 2; i >= 0; i--) {
                HtmlNode node = chain.get(i);
                event.setCurrentTarget(node);

                // インライン on* 属性の実行
                if (inlineCallback != null && node.attrs != null && node.attrs.containsKey(attrName)) {
                    String action = node.attrs.get(attrName);
                    if (action != null && !action.isEmpty()) {
                        inlineCallback.accept(node, action);
                    }
                }

                if (event.isPropagationStopped()) break;
            }
        }

        // 4. 既定動作 (Default Action) の実行
        if (!event.isDefaultPrevented() && defaultHandler != null) {
            defaultHandler.executeDefaultAction(event);
        }

        return !event.isDefaultPrevented();
    }

    /**
     * DOM イベントリスナ。
     */
    @FunctionalInterface
    public interface EventListener {
        void handleEvent(DomEvent event);
    }

    /**
     * イベントの既定動作（Default Action）を実行するハンドラ。
     * キャンセル（preventDefault）されていない場合に、イベントディスパッチの最終段で呼び出される。
     */
    @FunctionalInterface
    public interface DefaultActionHandler {
        void executeDefaultAction(DomEvent event);
    }
}
