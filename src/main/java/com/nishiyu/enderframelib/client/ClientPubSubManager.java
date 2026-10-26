package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.core.dom.parser.DomUtils;
import com.nishiyu.enderframelib.core.dom.Bindings;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class ClientPubSubManager {
    public static java.util.function.Consumer<String> subscriptionSender = null;

    private static final Map<String, Map<String, Object>> clientCache = new ConcurrentHashMap<>();
    private static final Map<String, List<Consumer<Map<String, Object>>>> CHANNEL_LISTENERS = new ConcurrentHashMap<>();

    public static final int DEFAULT_CONTAINER_WIDTH = 400;
    public static final int DEFAULT_VISIBLE_ROWS = 15;

    public static void requestSubscribe(String sessionId, String channel) {
        ClientWebManager.getOrCreateSession(sessionId).subscriptions().add(channel);
        if (subscriptionSender != null) subscriptionSender.accept(channel);
    }

    public static void registerDataBinding(String sessionId, String targetId, String channel, String path, String targetAttr) {
        List<DataBinding> list = ClientWebManager.getOrCreateSession(sessionId).dataBindings();
        DataBinding binding = new DataBinding(targetId, channel, path, targetAttr);
        list.removeIf(b -> b.targetId().equals(targetId) && b.targetAttr().equals(targetAttr));
        list.add(binding);
    }

    public static void registerVirtualList(String sessionId, String targetId, String channel, String templateHtml, int itemW, int itemH) {
        HtmlNode parsedTemplate = (templateHtml != null && !templateHtml.isEmpty()) ? HtmlParser.parseFragment(templateHtml, "template") : new HtmlNode("template");
        registerVirtualList(sessionId, targetId, channel, parsedTemplate, itemW, itemH);
    }

    public static void registerVirtualList(String sessionId, String targetId, String channel, HtmlNode templateNode, int itemW, int itemH) {
        List<VirtualListBinding> list = ClientWebManager.getOrCreateSession(sessionId).virtualListBindings();
        VirtualListBinding binding = new VirtualListBinding(targetId, channel, templateNode != null ? templateNode.cloneNode(true) : new HtmlNode("template"), itemW, itemH);
        list.removeIf(b -> b.targetId().equals(targetId));
        list.add(binding);
    }

    /** チャンネルごとの更新リスナーを登録する（S11: ar_sync などの予約チャンネル処理向け）。 */
    public static void addChannelListener(String channel, Consumer<Map<String, Object>> listener) {
        if (channel == null || listener == null) return;
        CHANNEL_LISTENERS.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public static void removeChannelListener(String channel, Consumer<Map<String, Object>> listener) {
        if (channel == null || listener == null) return;
        List<Consumer<Map<String, Object>>> list = CHANNEL_LISTENERS.get(channel);
        if (list != null) list.remove(listener);
    }

    public static Map<String, Object> getChannelData(String channel) {
        if (channel == null) return Collections.emptyMap();
        Map<String, Object> data = clientCache.get(channel);
        return data != null ? Collections.unmodifiableMap(data) : Collections.emptyMap();
    }

    public static void clearAll() {
        clientCache.clear();
        CHANNEL_LISTENERS.clear();
    }

    public static void onReceiveUpdate(String channel, boolean isFull, Map<String, Object> diffData) {
        Map<String, Object> current = clientCache.computeIfAbsent(channel, k -> new ConcurrentHashMap<>());

        boolean dataChanged = false;
        if (isFull) {
            if (!current.equals(diffData)) {
                current.clear();
                for (Map.Entry<String, Object> entry : diffData.entrySet()) {
                    if (entry.getValue() != null) current.put(entry.getKey(), entry.getValue());
                }
                dataChanged = true;
            }
        } else {
            for (Map.Entry<String, Object> entry : diffData.entrySet()) {
                if (entry.getValue() == null) {
                    if (current.remove(entry.getKey()) != null) dataChanged = true;
                } else {
                    Object prev = current.put(entry.getKey(), entry.getValue());
                    if (!Objects.equals(prev, entry.getValue())) dataChanged = true;
                }
            }
        }

        List<Consumer<Map<String, Object>>> listeners = CHANNEL_LISTENERS.get(channel);
        if (listeners != null && !listeners.isEmpty()) {
            for (Consumer<Map<String, Object>> listener : listeners) {
                listener.accept(diffData);
            }
        }

        if (!dataChanged) return;

        for (SessionContext session : ClientWebManager.sessions()) {
            if (session.subscriptions().contains(channel)) {
                applyBindingsToDOM(session.sessionId(), channel);
            }
        }
    }

    private static void applyBindingsToDOM(String sessionId, String triggeredChannel) {
        SessionContext session = ClientWebManager.getSession(sessionId);
        if (session == null) return;
        HtmlNode vRoot = session.rootNode();
        if (vRoot == null) return;

        boolean changedAny = false;

        List<VirtualListBinding> vLists = session.virtualListBindings();
        if (!vLists.isEmpty()) {
            for (VirtualListBinding vBind : vLists) {
                if (!triggeredChannel.equals(vBind.channel)) continue;

                HtmlNode targetNode = vRoot.getElementById(vBind.targetId);
                if (targetNode == null) continue;

                List<Map<String, String>> dataList = getStorageListAsMap(vBind.channel);
                if (Bindings.renderVirtualList(targetNode, vBind.templateNode, dataList, vBind.itemW, vBind.itemH, DEFAULT_CONTAINER_WIDTH, DEFAULT_VISIBLE_ROWS)) {
                    changedAny = true;
                }
            }
        }

        List<DataBinding> dBindings = session.dataBindings();
        if (!dBindings.isEmpty()) {
            for (DataBinding binding : dBindings) {
                if (!triggeredChannel.equals(binding.channel)) continue;

                HtmlNode targetNode = vRoot.getElementById(binding.targetId);
                if (targetNode == null) continue;

                String val = getStorageValue(binding.channel, binding.path);
                if (val != null) {
                    if ("text".equals(binding.targetAttr)) {
                        targetNode.setTextContent(val);
                        changedAny = true;
                    } else {
                        targetNode.setAttribute(binding.targetAttr, val);
                        changedAny = true;
                    }
                }
            }
        }

        if (changedAny) {
            session.requestRender();
        }
    }

    private static String getStorageValue(String channel, String path) {
        Map<String, Object> data = clientCache.get(channel);
        if (data == null) return null;
        Object v = data.get(path);
        return v != null ? String.valueOf(v) : null;
    }

    private static List<Map<String, String>> getStorageListAsMap(String channel) {
        Map<String, Object> data = clientCache.get(channel);
        if (data == null) return Collections.emptyList();

        List<Map<String, String>> list = new ArrayList<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            Map<String, String> item = new HashMap<>();
            item.put("id", entry.getKey());
            item.put("count", String.valueOf(entry.getValue()));
            list.add(item);
        }

        list.sort((a, b) -> {
            try {
                int c1 = Integer.parseInt(a.get("count"));
                int c2 = Integer.parseInt(b.get("count"));
                if (c1 != c2) return Integer.compare(c2, c1);
            } catch (Exception ignored) {}
            return a.get("id").compareTo(b.get("id"));
        });
        return list;
    }

    record DataBinding(String targetId, String channel, String path, String targetAttr) {}
    record VirtualListBinding(String targetId, String channel, HtmlNode templateNode, int itemW, int itemH) {}
}
