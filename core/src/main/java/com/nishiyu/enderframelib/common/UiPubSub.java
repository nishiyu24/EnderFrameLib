package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.Bindings;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * チャンネル単位のデータ（pub/sub）の受け口。届いた差分をキャッシュへ畳み込み、
 * そのチャンネルを購読している {@link UiView} の {@code data-bind} / 仮想リストへ反映する。
 * 購読要求の送信（サーバーへのパケットなど）は {@link #subscriptionSender} でホストが差し込む。
 */
public class UiPubSub {

    public static final int DEFAULT_CONTAINER_WIDTH = 400;
    public static final int DEFAULT_VISIBLE_ROWS = 15;

    public record DataBinding(String targetId, String channel, String path, String targetAttr) {}

    public record VirtualListBinding(String targetId, String channel, HtmlNode templateNode, int itemW, int itemH) {}

    public volatile Consumer<String> subscriptionSender = null;

    private final Map<String, Map<String, Object>> clientCache = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<Map<String, Object>>>> channelListeners = new ConcurrentHashMap<>();

    public void requestSubscribe(UiView view, String channel) {
        view.subscriptions().add(channel);
        Consumer<String> sender = subscriptionSender;
        if (sender != null) sender.accept(channel);
    }

    public void registerDataBinding(UiView view, String targetId, String channel, String path, String targetAttr) {
        List<DataBinding> list = view.dataBindings();
        list.removeIf(b -> b.targetId().equals(targetId) && b.targetAttr().equals(targetAttr));
        list.add(new DataBinding(targetId, channel, path, targetAttr));
    }

    public void registerVirtualList(UiView view, String targetId, String channel, String templateHtml, int itemW, int itemH) {
        HtmlNode parsed = (templateHtml != null && !templateHtml.isEmpty())
                ? HtmlParser.parseFragment(templateHtml, "template") : new HtmlNode("template");
        registerVirtualList(view, targetId, channel, parsed, itemW, itemH);
    }

    public void registerVirtualList(UiView view, String targetId, String channel, HtmlNode templateNode, int itemW, int itemH) {
        List<VirtualListBinding> list = view.virtualListBindings();
        VirtualListBinding binding = new VirtualListBinding(targetId, channel,
                templateNode != null ? templateNode.cloneNode(true) : new HtmlNode("template"), itemW, itemH);
        list.removeIf(b -> b.targetId().equals(targetId));
        list.add(binding);
    }

    public void addChannelListener(String channel, Consumer<Map<String, Object>> listener) {
        if (channel == null || listener == null) return;
        channelListeners.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public void removeChannelListener(String channel, Consumer<Map<String, Object>> listener) {
        if (channel == null || listener == null) return;
        List<Consumer<Map<String, Object>>> list = channelListeners.get(channel);
        if (list != null) list.remove(listener);
    }

    public Map<String, Object> getChannelData(String channel) {
        if (channel == null) return Collections.emptyMap();
        Map<String, Object> data = clientCache.get(channel);
        return data != null ? Collections.unmodifiableMap(data) : Collections.emptyMap();
    }

    public void clearAll() {
        clientCache.clear();
        channelListeners.clear();
    }

    /** 差分（または全量）を取り込み、変化があれば購読中の {@code views} の DOM を更新する。 */
    public void onReceiveUpdate(String channel, boolean isFull, Map<String, Object> diffData, Iterable<? extends UiView> views) {
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

        List<Consumer<Map<String, Object>>> listeners = channelListeners.get(channel);
        if (listeners != null && !listeners.isEmpty()) {
            for (Consumer<Map<String, Object>> listener : listeners) {
                listener.accept(diffData);
            }
        }

        if (!dataChanged) return;

        for (UiView view : views) {
            if (view.subscriptions().contains(channel)) {
                applyBindings(view, channel);
            }
        }
    }

    private void applyBindings(UiView view, String triggeredChannel) {
        HtmlNode vRoot = view.rootNode();
        if (vRoot == null) return;

        boolean changedAny = false;
        // 仮想リストは DOM の変更通知を経ない経路があるので明示的に再レイアウトを求める。
        // 文字・属性のバインディングは DOM の変更通知が無効化を決める（文字だけなら TEXT_PATCH の高速経路に乗る）
        boolean forceRender = false;

        for (VirtualListBinding vBind : view.virtualListBindings()) {
            if (!triggeredChannel.equals(vBind.channel())) continue;
            HtmlNode targetNode = vRoot.getElementById(vBind.targetId());
            if (targetNode == null) continue;
            List<Map<String, String>> dataList = getStorageListAsMap(vBind.channel());
            if (Bindings.renderVirtualList(targetNode, vBind.templateNode(), dataList, vBind.itemW(), vBind.itemH(),
                    DEFAULT_CONTAINER_WIDTH, DEFAULT_VISIBLE_ROWS)) {
                changedAny = true;
                forceRender = true;
            }
        }

        for (DataBinding binding : view.dataBindings()) {
            if (!triggeredChannel.equals(binding.channel())) continue;
            HtmlNode targetNode = vRoot.getElementById(binding.targetId());
            if (targetNode == null) continue;
            String val = getStorageValue(binding.channel(), binding.path());
            if (val != null) {
                if ("text".equals(binding.targetAttr())) {
                    targetNode.updateTextContent(val);
                } else {
                    targetNode.setAttribute(binding.targetAttr(), val);
                }
                changedAny = true;
            }
        }

        if (changedAny && forceRender) {
            view.requestRender();
        }
    }

    private String getStorageValue(String channel, String path) {
        Map<String, Object> data = clientCache.get(channel);
        if (data == null) return null;
        Object v = data.get(path);
        return v != null ? String.valueOf(v) : null;
    }

    private List<Map<String, String>> getStorageListAsMap(String channel) {
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
}
