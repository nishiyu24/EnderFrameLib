package com.nishiyu.enderframelib.core.event;

import com.nishiyu.enderframelib.core.dom.HtmlNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * HTML 標準の既定動作（Default Actions）。
 *
 * a (href), label (for / 包含), summary / details, checkbox / radio, button[type=submit/reset]
 * などの標準ブラウザ動作を一箇所に集約する。
 */
public class StandardDefaultActions {

    public interface Context {
        /** ノードの属性が変更されたときの通知。 */
        void onAttributeChanged(HtmlNode node, String attrName, String attrValue);

        /** 別要素への合成クリックの再帰ディスパッチ。 */
        void dispatchClickTo(HtmlNode target);

        /** a[href] などのナビゲーション実行。 */
        void navigateTo(String href);

        /** フォームのアクション実行 (onsubmit, onreset 等)。 */
        void executeFormAction(HtmlNode form, String eventType);
    }

    public static void execute(DomEvent event, Context ctx) {
        if (event == null || event.isDefaultPrevented()) return;
        HtmlNode target = event.target();
        if (target == null) return;

        String type = event.type().toLowerCase(Locale.ROOT);

        if ("click".equals(type)) {
            handleClick(target, ctx);
        }
    }

    private static void handleClick(HtmlNode target, Context ctx) {
        // 1. summary / details
        HtmlNode summaryNode = findSelfOrAncestor(target, "summary");
        if (summaryNode != null && summaryNode.parent != null && "details".equalsIgnoreCase(summaryNode.parent.tag)) {
            HtmlNode details = summaryNode.parent;
            boolean wasOpen = details.attrs.containsKey("open");
            if (wasOpen) {
                details.attrs.remove("open");
                if (ctx != null) ctx.onAttributeChanged(details, "open", null);
            } else {
                details.attrs.put("open", "true");
                if (ctx != null) ctx.onAttributeChanged(details, "open", "true");
            }
            return;
        }

        // 2. label (for 属性または包含コントロール)
        HtmlNode labelNode = findSelfOrAncestor(target, "label");
        if (labelNode != null) {
            // target 自体がコントロール（input 等）なら二重発火を防止
            if (!isFormControl(target)) {
                if (labelNode.attrs.containsKey("for")) {
                    String targetId = labelNode.attrs.get("for");
                    HtmlNode root = getRoot(labelNode);
                    HtmlNode control = findNodeById(root, targetId);
                    if (control != null && ctx != null) {
                        ctx.dispatchClickTo(control);
                        return;
                    }
                } else {
                    // label 内の最初の子孫フォームコントロールを探す
                    HtmlNode control = findFirstFormControl(labelNode);
                    if (control != null && ctx != null) {
                        ctx.dispatchClickTo(control);
                        return;
                    }
                }
            }
        }

        // 3. input[type=checkbox]
        if ("input".equalsIgnoreCase(target.tag)) {
            String inputType = target.attrs.getOrDefault("type", "text").toLowerCase(Locale.ROOT);
            if ("checkbox".equals(inputType)) {
                boolean wasChecked = target.attrs.containsKey("checked");
                if (wasChecked) {
                    target.attrs.remove("checked");
                    if (ctx != null) ctx.onAttributeChanged(target, "checked", null);
                } else {
                    target.attrs.put("checked", "true");
                    if (ctx != null) ctx.onAttributeChanged(target, "checked", "true");
                }
                return;
            } else if ("radio".equals(inputType)) {
                if (!target.attrs.containsKey("checked")) {
                    String name = target.attrs.get("name");
                    if (name != null && !name.isEmpty()) {
                        // 同じ name を持つ他の radio を解除
                        HtmlNode root = getRoot(target);
                        List<HtmlNode> radios = new ArrayList<>();
                        findRadiosByName(root, name, radios);
                        for (HtmlNode r : radios) {
                            if (r != target && r.attrs.containsKey("checked")) {
                                r.attrs.remove("checked");
                                if (ctx != null) ctx.onAttributeChanged(r, "checked", null);
                            }
                        }
                    }
                    target.attrs.put("checked", "true");
                    if (ctx != null) ctx.onAttributeChanged(target, "checked", "true");
                }
                return;
            }
        }

        // 4. button[type=submit] / button[type=reset] / input[type=submit]
        boolean isButton = "button".equalsIgnoreCase(target.tag);
        boolean isInputBtn = "input".equalsIgnoreCase(target.tag)
                && ("submit".equalsIgnoreCase(target.attrs.get("type")) || "reset".equalsIgnoreCase(target.attrs.get("type")));

        if (isButton || isInputBtn) {
            String btnType = target.attrs.getOrDefault("type", isButton ? "submit" : "button").toLowerCase(Locale.ROOT);
            HtmlNode form = findSelfOrAncestor(target, "form");
            if (form != null && ctx != null) {
                if ("submit".equals(btnType)) {
                    ctx.executeFormAction(form, "onsubmit");
                    return;
                } else if ("reset".equals(btnType)) {
                    ctx.executeFormAction(form, "onreset");
                    return;
                }
            }
        }

        // 5. a (anchor with href)
        HtmlNode anchorNode = findSelfOrAncestor(target, "a");
        if (anchorNode != null && anchorNode.attrs.containsKey("href")) {
            String href = anchorNode.attrs.get("href");
            if (href != null && !href.isEmpty() && ctx != null) {
                ctx.navigateTo(href);
            }
        }
    }

    private static boolean isFormControl(HtmlNode node) {
        if (node == null) return false;
        String tag = node.tag.toLowerCase(Locale.ROOT);
        return tag.equals("input") || tag.equals("button") || tag.equals("select") || tag.equals("textarea");
    }

    private static HtmlNode findSelfOrAncestor(HtmlNode node, String tag) {
        HtmlNode cur = node;
        while (cur != null) {
            if (tag.equalsIgnoreCase(cur.tag)) return cur;
            cur = cur.parent;
        }
        return null;
    }

    private static HtmlNode getRoot(HtmlNode node) {
        HtmlNode cur = node;
        while (cur.parent != null) cur = cur.parent;
        return cur;
    }

    private static HtmlNode findNodeById(HtmlNode root, String id) {
        if (root == null || id == null) return null;
        if (id.equals(root.getId())) return root;
        for (HtmlNode child : root.children) {
            HtmlNode found = findNodeById(child, id);
            if (found != null) return found;
        }
        return null;
    }

    private static HtmlNode findFirstFormControl(HtmlNode root) {
        for (HtmlNode child : root.children) {
            if (isFormControl(child)) return child;
            HtmlNode nested = findFirstFormControl(child);
            if (nested != null) return nested;
        }
        return null;
    }

    private static void findRadiosByName(HtmlNode root, String name, List<HtmlNode> out) {
        if (root == null) return;
        if ("input".equalsIgnoreCase(root.tag) && "radio".equalsIgnoreCase(root.attrs.get("type"))
                && name.equals(root.attrs.get("name"))) {
            out.add(root);
        }
        for (HtmlNode child : root.children) {
            findRadiosByName(child, name, out);
        }
    }
}
