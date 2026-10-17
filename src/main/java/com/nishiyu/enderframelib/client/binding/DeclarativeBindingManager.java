package com.nishiyu.enderframelib.client.binding;

import com.nishiyu.enderframelib.core.dom.HtmlNode;
import java.util.List;
import java.util.Objects;

public class DeclarativeBindingManager {

    public interface IHudRegistrationCallback {
        void registerHudTemplate(String sessionId, String containerId, String origDisplay, HtmlNode templateRoot, List<String> requireNbt);
        void registerTrackerTemplate(String sessionId, HtmlNode templateNode, double radius, double yOffset, String targetType, List<String> requireNbt);
    }

    public static IHudRegistrationCallback hudCallback = null;

    public static void initializeBindings(String sessionId, HtmlNode rootNode) {
        if (rootNode == null) return;

        if (rootNode.attrs.containsKey("data-bind")) {
            String bindDefinition = rootNode.attrs.get("data-bind");
            String targetAttr = "text";
            String path = bindDefinition;

            if (bindDefinition.contains(":")) {
                String[] parts = bindDefinition.split(":", 2);
                targetAttr = parts[0].trim();
                path = parts[1].trim();
            }
            String channel = resolveChannel(rootNode, path);
            ClientPubSubManager.registerDataBinding(sessionId, rootNode.getId(), channel, path, targetAttr);
            ClientPubSubManager.requestSubscribe(sessionId, channel);
        }

        if (rootNode.attrs.containsKey("data-list")) {
            String listPath = rootNode.attrs.get("data-list");
            HtmlNode templateNode = findTemplateChild(rootNode);

            if (templateNode != null) {
                int itemW = 36;
                int itemH = 36;
                try { itemW = Integer.parseInt(rootNode.attrs.getOrDefault("data-item-width", "36")); } catch (Exception ignored) {}
                try { itemH = Integer.parseInt(rootNode.attrs.getOrDefault("data-item-height", "36")); } catch (Exception ignored) {}
                String channel = resolveChannel(rootNode, listPath);
                ClientPubSubManager.registerVirtualList(sessionId, rootNode.getId(), channel, templateNode, itemW, itemH);
                ClientPubSubManager.requestSubscribe(sessionId, channel);
            }
        }

        if (rootNode.attrs.containsKey("data-hud") && hudCallback != null) {
            String origDisplay = rootNode.attrs.getOrDefault("data-hud-display", "flex");
            java.util.List<String> requireNbt = new java.util.ArrayList<>();
            if (rootNode.attrs.containsKey("data-hud-require-nbt")) {
                String[] nbtKeys = rootNode.attrs.get("data-hud-require-nbt").split(",");
                for (String key : nbtKeys) requireNbt.add(key.trim());
            }
            hudCallback.registerHudTemplate(sessionId, rootNode.getId(), origDisplay, rootNode, requireNbt);
        }

        if (rootNode.attrs.containsKey("data-tracker") && hudCallback != null) {
            double radius = 24.0;
            double yOffset = 0.8;
            String targetType = "living";
            java.util.List<String> requireNbt = new java.util.ArrayList<>();

            if (rootNode.attrs.containsKey("data-tracker-radius")) radius = Double.parseDouble(rootNode.attrs.get("data-tracker-radius"));
            if (rootNode.attrs.containsKey("data-tracker-y-offset")) yOffset = Double.parseDouble(rootNode.attrs.get("data-tracker-y-offset"));
            if (rootNode.attrs.containsKey("data-tracker-type")) targetType = rootNode.attrs.get("data-tracker-type");
            if (rootNode.attrs.containsKey("data-tracker-require-nbt")) {
                String[] nbtKeys = rootNode.attrs.get("data-tracker-require-nbt").split(",");
                for (String key : nbtKeys) requireNbt.add(key.trim());
            }

            hudCallback.registerTrackerTemplate(sessionId, rootNode, radius, yOffset, targetType, requireNbt);
        }

        for (HtmlNode child : rootNode.children) {
            initializeBindings(sessionId, child);
        }
    }

    private static String resolveChannel(HtmlNode node, String bindKey) {
        String attrName = "data-channel-" + bindKey.toLowerCase(java.util.Locale.ROOT);
        HtmlNode current = node;
        while (current != null) {
            if (current.attrs.containsKey(attrName)) {
                return current.attrs.get(attrName);
            }
            current = current.parent;
        }
        return Objects.requireNonNull(node).attrs.getOrDefault("data-channel", bindKey);
    }

    private static HtmlNode findTemplateChild(HtmlNode parent) {
        for (HtmlNode child : parent.children) {
            if (child.tag.equals("template")) return child;
        }
        return null;
    }

    private static String serializeToSimpleHtml(HtmlNode node) {
        if (node.tag.equals("#text")) return node.text;

        StringBuilder sb = new StringBuilder();
        if (!node.tag.equals("template")) {
            sb.append("<").append(node.tag);
            for (java.util.Map.Entry<String, String> attr : node.attrs.entrySet()) {
                sb.append(" ").append(attr.getKey()).append("=\"").append(attr.getValue()).append("\"");
            }
            sb.append(">");
        }

        for (HtmlNode child : node.children) {
            sb.append(serializeToSimpleHtml(child));
        }

        if (!node.tag.equals("template")) {
            sb.append("</").append(node.tag).append(">");
        }
        return sb.toString();
    }
}