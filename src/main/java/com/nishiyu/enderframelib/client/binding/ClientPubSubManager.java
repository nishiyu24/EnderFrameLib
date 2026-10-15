package com.nishiyu.enderframelib.client.binding;

import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.core.dom.DomUtils;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.dom.HtmlParser;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClientPubSubManager {
    // パケット送信コールバック
    public static java.util.function.Consumer<String> subscriptionSender = null;

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([a-zA-Z0-9_.-]+)}}");
    private static final Map<String, Map<String, Object>> clientCache = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> activeSessions = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastRequestTime = new ConcurrentHashMap<>();
    private static final Map<String, List<DataBinding>> dataBindings = new ConcurrentHashMap<>();
    private static final Map<String, List<VirtualListBinding>> virtualListBindings = new ConcurrentHashMap<>();

    public static void requestSubscribe(String sessionId, String channel) {
        activeSessions.computeIfAbsent(channel, k -> new HashSet<>()).add(sessionId);
        if (subscriptionSender != null) subscriptionSender.accept(channel);
    }

    public static void requestSubscribeThrottle(String channel) {
        long now = System.currentTimeMillis();
        if (now - lastRequestTime.getOrDefault(channel, 0L) > 3000) {
            lastRequestTime.put(channel, now);
            if (subscriptionSender != null) subscriptionSender.accept(channel);
        }
    }

    public static void registerDataBinding(String sessionId, String targetId, String channel, String path, String targetAttr) {
        dataBindings.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(new DataBinding(targetId, channel, path, targetAttr));
    }

    public static void registerVirtualList(String sessionId, String targetId, String channel, String templateHtml, int itemW, int itemH) {
        virtualListBindings.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(new VirtualListBinding(targetId, channel, templateHtml, itemW, itemH));
    }

    public static void clearSession(String sessionId) {
        dataBindings.remove(sessionId);
        virtualListBindings.remove(sessionId);
        for (Set<String> sessions : activeSessions.values()) {
            sessions.remove(sessionId);
        }
    }

    public static void onReceiveUpdate(String channel, boolean isFull, Map<String, Object> diffData) {
        Map<String, Object> current = clientCache.computeIfAbsent(channel, k -> new ConcurrentHashMap<>());

        if (isFull) {
            current.clear();
            for (Map.Entry<String, Object> entry : diffData.entrySet()) {
                if (entry.getValue() != null) current.put(entry.getKey(), entry.getValue());
            }
        } else {
            for (Map.Entry<String, Object> entry : diffData.entrySet()) {
                if (entry.getValue() == null) current.remove(entry.getKey());
                else current.put(entry.getKey(), entry.getValue());
            }
        }

        Set<String> sessions = activeSessions.get(channel);
        if (sessions != null && !sessions.isEmpty()) {
            for (String sessionId : sessions) {
                applyBindingsToDOM(sessionId, channel);
            }
        }
    }

    private static void applyBindingsToDOM(String sessionId, String triggeredChannel) {
        HtmlNode vRoot = ClientWebManager.getVirtualRoot(sessionId);
        if (vRoot == null) return;

        boolean changedAny = false;

        List<VirtualListBinding> vLists = virtualListBindings.get(sessionId);
        if (vLists != null) {
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

                    int estimatedContainerW = 400;
                    int itemsPerRow = Math.max(1, estimatedContainerW / vBind.itemW);
                    int totalRows = (int) Math.ceil((double) dataList.size() / itemsPerRow);

                    int startRow = Math.max(0, scrollY / vBind.itemH);
                    int visibleRows = 15;
                    int endRow = Math.min(totalRows, startRow + visibleRows);

                    int topPadding = startRow * vBind.itemH;
                    int bottomPadding = Math.max(0, totalRows - endRow) * vBind.itemH;

                    HtmlNode topSpacer = new HtmlNode("div");
                    topSpacer.attrs.put("style", "width: 100%; height: " + topPadding + "px; flex-shrink: 0;");
                    topSpacer.parent = targetNode;
                    targetNode.children.add(topSpacer);

                    HtmlNode templateRoot = HtmlParser.parse(vBind.templateHtml);
                    int startIndex = startRow * itemsPerRow;
                    int endIndex = Math.min(dataList.size(), endRow * itemsPerRow);

                    for (int i = startIndex; i < endIndex; i++) {
                        for (HtmlNode tmpl : templateRoot.children) {
                            HtmlNode itemNode = tmpl.cloneNode();
                            applyTemplateData(itemNode, dataList.get(i));
                            itemNode.parent = targetNode;
                            targetNode.children.add(itemNode);
                        }
                    }

                    HtmlNode botSpacer = new HtmlNode("div");
                    botSpacer.attrs.put("style", "width: 100%; height: " + bottomPadding + "px; flex-shrink: 0;");
                    botSpacer.parent = targetNode;
                    targetNode.children.add(botSpacer);

                    targetNode.attrs.put("data-scroll-h", String.valueOf(totalRows * vBind.itemH));
                    targetNode.markDirty();
                }
            }
        }

        List<DataBinding> dBindings = dataBindings.get(sessionId);
        if (dBindings != null) {
            for (DataBinding dBind : dBindings) {
                if (!triggeredChannel.equals(dBind.channel)) continue;

                HtmlNode targetNode = vRoot.getElementById(dBind.targetId);
                if (targetNode == null) continue;

                String val = getValue(dBind.channel, dBind.path);

                if ("text".equals(dBind.targetAttr)) {
                    targetNode.setTextContent(val);
                } else if (dBind.targetAttr.startsWith("style.")) {
                    String styleKey = dBind.targetAttr.substring(6);
                    targetNode.setStyleProperty(styleKey, val);
                } else {
                    DomUtils.updateAttribute(targetNode, dBind.targetAttr, val, false);
                }
                changedAny = true;
            }
        }

        if (changedAny) {
            ClientWebManager.requestRender(sessionId);
        }
    }

    private static void applyTemplateData(HtmlNode node, Map<String, String> data) {
        if (node.text != null && node.text.contains("{{")) {
            node.text = replacePlaceholders(node.text, data);
        }
        for (String key : new ArrayList<>(node.attrs.keySet())) {
            String val = node.attrs.get(key);
            if (val != null && val.contains("{{")) {
                DomUtils.updateAttribute(node, key, replacePlaceholders(val, data), false);
            }
        }
        if (node.attrs.containsKey("id")) {
            node.id = node.attrs.get("id");
        }
        for (HtmlNode child : node.children) {
            applyTemplateData(child, data);
        }
    }

    private static String replacePlaceholders(String text, Map<String, String> data) {
        Matcher m = PLACEHOLDER_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String replacement = data.getOrDefault(key, "");
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
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

    private record DataBinding(String targetId, String channel, String path, String targetAttr) {}
    private record VirtualListBinding(String targetId, String channel, String templateHtml, int itemW, int itemH) {}
}