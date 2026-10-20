package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.core.dom.DomUtils;
import com.nishiyu.enderframelib.core.dom.TemplateEngine;
import com.nishiyu.enderframelib.core.dom.HtmlElement;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.dom.HtmlParser;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ClientPubSubManager {
    public static java.util.function.Consumer<String> subscriptionSender = null;

    private static final Map<String, Map<String, Object>> clientCache = new ConcurrentHashMap<>();

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
        VirtualListBinding binding = new VirtualListBinding(targetId, channel, templateNode != null ? templateNode.cloneNode() : new HtmlNode("template"), itemW, itemH);
        list.removeIf(b -> b.targetId().equals(targetId));
        list.add(binding);
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

        if (!dataChanged) return;

        for (SessionContext session : ClientWebManager.sessions()) {
            if (session.subscriptions().contains(channel)) {
                applyBindingsToDOM(session.sessionId(), channel);
            }
        }
    }

    private static void applyBindingsToDOM(String sessionId, String triggeredChannel) {
        HtmlNode vRoot = ClientWebManager.getRootNode(sessionId);
        if (vRoot == null) return;

        boolean changedAny = false;

        SessionContext session = ClientWebManager.getSession(sessionId);
        if (session == null) return;

        List<VirtualListBinding> vLists = session.virtualListBindings();
        if (!vLists.isEmpty()) {
            for (VirtualListBinding vBind : vLists) {
                if (!triggeredChannel.equals(vBind.channel)) continue;

                HtmlNode targetNode = vRoot.getElementById(vBind.targetId);
                if (targetNode == null) continue;

                List<Map<String, String>> dataList = getStorageListAsMap(vBind.channel);
                targetNode.children.removeIf(node -> !node.tag.equals("template"));
                changedAny = true;

                if (dataList != null) {
                    int scrollY = 0;
                    try { scrollY = (int) Float.parseFloat(targetNode.attrs.getOrDefault("data-scroll-y", "0")); } catch (Exception ignored) {}

                    int estimatedContainerW = DEFAULT_CONTAINER_WIDTH;
                    if (targetNode.attrs.containsKey("data-container-width")) {
                        try { estimatedContainerW = Integer.parseInt(targetNode.attrs.get("data-container-width")); } catch (Exception ignored) {}
                    }
                    int itemsPerRow = Math.max(1, estimatedContainerW / Math.max(1, vBind.itemW));
                    int totalRows = (int) Math.ceil((double) dataList.size() / itemsPerRow);

                    int visibleRows = DEFAULT_VISIBLE_ROWS;
                    if (targetNode.attrs.containsKey("data-visible-rows")) {
                        try { visibleRows = Integer.parseInt(targetNode.attrs.get("data-visible-rows")); } catch (Exception ignored) {}
                    }
                    int startRow = Math.max(0, scrollY / Math.max(1, vBind.itemH));
                    int endRow = Math.min(totalRows, startRow + visibleRows);

                    int topPadding = startRow * vBind.itemH;
                    int bottomPadding = Math.max(0, totalRows - endRow) * vBind.itemH;

                    HtmlNode topSpacer = new HtmlElement("div");
                    topSpacer.attrs.put("style", "width: 100%; height: " + topPadding + "px; flex-shrink: 0;");
                    topSpacer.parent = targetNode;
                    targetNode.children.add(topSpacer);

                    HtmlNode templateRoot = vBind.templateNode != null ? vBind.templateNode.cloneNode() : new HtmlNode("template");
                    int startIndex = startRow * itemsPerRow;
                    int endIndex = Math.min(dataList.size(), endRow * itemsPerRow);

                    for (int i = startIndex; i < endIndex; i++) {
                        for (HtmlNode tmpl : templateRoot.children) {
                            HtmlNode itemNode = tmpl.cloneNode();
                            // --- 修正箇所: localData に一度代入することで、事実上の final を保証する ---
                            Map<String, String> localData = dataList.get(i);
                            TemplateEngine.applyTemplate(itemNode, text -> TemplateEngine.resolve(text, localData, null));
                            itemNode.parent = targetNode;
                            targetNode.children.add(itemNode);
                        }
                    }

                    HtmlNode botSpacer = new HtmlElement("div");
                    botSpacer.attrs.put("style", "width: 100%; height: " + bottomPadding + "px; flex-shrink: 0;");
                    botSpacer.parent = targetNode;
                    targetNode.children.add(botSpacer);

                    targetNode.attrs.put("data-scroll-h", String.valueOf(totalRows * vBind.itemH));
                }
            }
        }

        List<DataBinding> dBindings = session.dataBindings();
        if (!dBindings.isEmpty()) {
            for (DataBinding dBind : dBindings) {
                if (!triggeredChannel.equals(dBind.channel)) continue;

                HtmlNode targetNode = vRoot.getElementById(dBind.targetId);
                if (targetNode == null) continue;

                String val = getValue(dBind.channel, dBind.path);

                if ("text".equals(dBind.targetAttr)) {
                    if (!Objects.equals(targetNode.getTextContent(), val)) {
                        targetNode.setTextContent(val);
                        changedAny = true;
                    }
                } else if (dBind.targetAttr.startsWith("style.")) {
                    String styleKey = dBind.targetAttr.substring(6);
                    String prevStyle = targetNode.attrs.getOrDefault("style", "");
                    targetNode.setStyleProperty(styleKey, val);
                    if (!Objects.equals(prevStyle, targetNode.attrs.getOrDefault("style", ""))) {
                        changedAny = true;
                    }
                } else {
                    String prevVal = targetNode.attrs.get(dBind.targetAttr);
                    if (!Objects.equals(prevVal, val)) {
                        DomUtils.updateAttribute(targetNode, dBind.targetAttr, val, false);
                        changedAny = true;
                    }
                }
            }
        }

        if (changedAny) {
            ClientWebManager.requestRender(sessionId);
        }
    }

    public static String getValue(String channel, String path) {
        Map<String, Object> data = clientCache.get(channel);
        if (data != null && data.containsKey(path)) {
            return String.valueOf(data.get(path));
        }
        return "";
    }

    public static List<Map<String, String>> getStorageListAsMap(String channel) {
        Map<String, Object> data = clientCache.get(channel);
        if (data == null) return null;

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