package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 宣言的バインディングの走査（旧 {@code DeclarativeBindingManager}）: 部分木の {@code data-bind}・{@code data-list}・
 * {@code data-hud}・{@code data-tracker} を読み、購読の登録を {@link Handler} へ渡す。チャンネルは祖先の
 * {@code data-channel-<キー>} か {@code data-channel} から決める。M5-3a で {@link Bindings} から出した（解釈の 1 か所の一部）。
 */
public final class DeclarativeBindings {

    private DeclarativeBindings() {}


    public interface Handler {
        void bindData(String targetId, String channel, String path, String targetAttr);
        void bindVirtualList(String targetId, String channel, HtmlNode templateNode, int itemW, int itemH);
        void bindHud(String targetId, String origDisplay, HtmlNode templateRoot, List<String> requireNbt);
        void bindTracker(HtmlNode templateNode, double radius, double yOffset, String targetType, List<String> requireNbt);
    }

    public static void scan(HtmlNode rootNode, Handler handler) {
        if (rootNode == null || handler == null) return;

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
            handler.bindData(rootNode.key(), channel, path, targetAttr);
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
                handler.bindVirtualList(rootNode.key(), channel, templateNode, itemW, itemH);
            }
        }

        if (rootNode.attrs.containsKey("data-hud")) {
            String origDisplay = rootNode.attrs.getOrDefault("data-hud-display", "flex");
            List<String> requireNbt = new ArrayList<>();
            if (rootNode.attrs.containsKey("data-hud-require-nbt")) {
                String[] nbtKeys = rootNode.attrs.get("data-hud-require-nbt").split(",");
                for (String key : nbtKeys) requireNbt.add(key.trim());
            }
            handler.bindHud(rootNode.key(), origDisplay, rootNode, requireNbt);
        }

        if (rootNode.attrs.containsKey("data-tracker")) {
            double radius = 24.0;
            double yOffset = 0.8;
            String targetType = "living";
            List<String> requireNbt = new ArrayList<>();

            if (rootNode.attrs.containsKey("data-tracker-radius")) {
                try { radius = Double.parseDouble(rootNode.attrs.get("data-tracker-radius")); } catch (Exception ignored) {}
            }
            if (rootNode.attrs.containsKey("data-tracker-y-offset")) {
                try { yOffset = Double.parseDouble(rootNode.attrs.get("data-tracker-y-offset")); } catch (Exception ignored) {}
            }
            if (rootNode.attrs.containsKey("data-tracker-type")) {
                targetType = rootNode.attrs.get("data-tracker-type");
            }
            if (rootNode.attrs.containsKey("data-tracker-require-nbt")) {
                String[] nbtKeys = rootNode.attrs.get("data-tracker-require-nbt").split(",");
                for (String key : nbtKeys) requireNbt.add(key.trim());
            }

            handler.bindTracker(rootNode, radius, yOffset, targetType, requireNbt);
        }

        for (HtmlNode child : rootNode.children) {
            scan(child, handler);
        }
    }

    private static String resolveChannel(HtmlNode node, String bindKey) {
        String attrName = "data-channel-" + bindKey.toLowerCase(Locale.ROOT);
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
            if ("template".equalsIgnoreCase(child.tag)) return child;
        }
        return null;
    }
}
