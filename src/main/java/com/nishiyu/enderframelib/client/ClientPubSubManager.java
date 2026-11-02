package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.common.UiPubSub;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

import java.util.Map;
import java.util.function.Consumer;

/**
 * pub/sub の静的な入口。データのキャッシュ・リスナー・DOM への反映はコアの {@link UiPubSub} が持ち、
 * ここはセッション台帳（{@link ClientWebManager}）から対象のセッションを引くことと、
 * 購読要求のパケット送信口（{@link #subscriptionSender}）だけを受け持つ。
 */
public class ClientPubSubManager {
    /** 購読要求の送信口。{@code null} の間は送らない。 */
    public static Consumer<String> subscriptionSender = null;

    public static final int DEFAULT_CONTAINER_WIDTH = UiPubSub.DEFAULT_CONTAINER_WIDTH;
    public static final int DEFAULT_VISIBLE_ROWS = UiPubSub.DEFAULT_VISIBLE_ROWS;

    private static final UiPubSub CORE = new UiPubSub();

    static {
        CORE.subscriptionSender = channel -> {
            Consumer<String> sender = subscriptionSender;
            if (sender != null) sender.accept(channel);
        };
    }

    public static void requestSubscribe(String sessionId, String channel) {
        CORE.requestSubscribe(ClientWebManager.getOrCreateSession(sessionId), channel);
    }

    public static void registerDataBinding(String sessionId, String targetId, String channel, String path, String targetAttr) {
        CORE.registerDataBinding(ClientWebManager.getOrCreateSession(sessionId), targetId, channel, path, targetAttr);
    }

    public static void registerVirtualList(String sessionId, String targetId, String channel, String templateHtml, int itemW, int itemH) {
        CORE.registerVirtualList(ClientWebManager.getOrCreateSession(sessionId), targetId, channel, templateHtml, itemW, itemH);
    }

    public static void registerVirtualList(String sessionId, String targetId, String channel, HtmlNode templateNode, int itemW, int itemH) {
        CORE.registerVirtualList(ClientWebManager.getOrCreateSession(sessionId), targetId, channel, templateNode, itemW, itemH);
    }

    /** チャンネルごとの更新リスナーを登録する（S11: ar_sync などの予約チャンネル処理向け）。 */
    public static void addChannelListener(String channel, Consumer<Map<String, Object>> listener) {
        CORE.addChannelListener(channel, listener);
    }

    public static void removeChannelListener(String channel, Consumer<Map<String, Object>> listener) {
        CORE.removeChannelListener(channel, listener);
    }

    public static Map<String, Object> getChannelData(String channel) {
        return CORE.getChannelData(channel);
    }

    public static void clearAll() {
        CORE.clearAll();
    }

    public static void onReceiveUpdate(String channel, boolean isFull, Map<String, Object> diffData) {
        CORE.onReceiveUpdate(channel, isFull, diffData, ClientWebManager.sessions());
    }
}
