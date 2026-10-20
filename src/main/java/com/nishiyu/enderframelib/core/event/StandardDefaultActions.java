package com.nishiyu.enderframelib.core.event;

import com.nishiyu.enderframelib.api.event.UIEvents;
import com.nishiyu.enderframelib.api.event.Event;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * HTML 標準の既定動作（Default Actions）。
 *
 * a (href), label (for / 包含), summary / details, checkbox / radio,
 * button[type=submit/reset], フォーカス移動, スクロール, テキスト入力などの標準ブラウザ動作を一箇所に集約する。
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

        /** フォーカス移動。 */
        default void setFocus(HtmlNode node) {}

        /** ホイールスクロール。 */
        default void scroll(double deltaX, double deltaY) {}
    }

    public static void execute(Event event, Context ctx) {
        if (event == null || event.isDefaultPrevented()) return;
        Object targetObj = event.getTarget();
        if (!(targetObj instanceof HtmlNode target)) return;

        String type = event.getType().toLowerCase(Locale.ROOT);

        switch (type) {
            case "click" -> handleClick(target, ctx);
            case "wheel" -> handleWheel(event, ctx);
            case "keydown" -> handleKeyDown(event, target, ctx);
        }
    }

    private static void handleClick(HtmlNode target, Context ctx) {
        // フォーカス移動（クリックされたフォーカス可能要素へフォーカス）
        if (isNaturallyFocusable(target) && ctx != null) {
            ctx.setFocus(target);
        }

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
                    HtmlNode control = root.getElementById(targetId);
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

        // 3. input[type=checkbox] / input[type=radio]
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
                // input / change イベントの発火
                target.dispatchEvent(new CoreUIEvent("input", true, false, 0));
                target.dispatchEvent(new CoreUIEvent("change", true, false, 0));
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
                    target.dispatchEvent(new CoreUIEvent("input", true, false, 0));
                    target.dispatchEvent(new CoreUIEvent("change", true, false, 0));
                }
                return;
            }
        }

        // 4. button[type=submit] / button[type=reset] / input[type=submit] / input[type=reset]
        boolean isButton = "button".equalsIgnoreCase(target.tag);
        boolean isInputBtn = "input".equalsIgnoreCase(target.tag)
                && ("submit".equalsIgnoreCase(target.attrs.get("type")) || "reset".equalsIgnoreCase(target.attrs.get("type")));

        if (isButton || isInputBtn) {
            String btnType = target.attrs.getOrDefault("type", isButton ? "submit" : "button").toLowerCase(Locale.ROOT);
            HtmlNode form = findSelfOrAncestor(target, "form");
            if (form != null) {
                if ("submit".equals(btnType)) {
                    if (target instanceof com.nishiyu.enderframelib.api.dom.Element el) {
                        el.requestSubmit(el);
                    }
                    if (ctx != null) {
                        ctx.executeFormAction(form, "onsubmit");
                    }
                    return;
                } else if ("reset".equals(btnType)) {
                    if (form instanceof com.nishiyu.enderframelib.api.dom.Element formEl) {
                        formEl.reset();
                    }
                    if (ctx != null) {
                        ctx.executeFormAction(form, "onreset");
                    }
                    return;
                }
            }
        }

        // 5. a (anchor with href)
        HtmlNode anchorNode = findSelfOrAncestor(target, "a");
        if (anchorNode != null && anchorNode.attrs.containsKey("href")) {
            String href = anchorNode.attrs.get("href");
            // ページ内リンク（#id）は文書のフラグメント識別子を変える。:target の規則がこれを読む
            if (href != null && href.startsWith("#")
                    && anchorNode.getOwnerDocument() instanceof com.nishiyu.enderframelib.core.dom.node.HtmlDocument document) {
                document.setTargetFragment(href);
            }
            if (href != null && !href.isEmpty() && ctx != null) {
                ctx.navigateTo(href);
            }
        }
    }

    private static void handleWheel(Event event, Context ctx) {
        if (event instanceof UIEvents.Wheel we && ctx != null) {
            ctx.scroll(we.getDeltaX(), we.getDeltaY());
        }
    }

    private static void handleKeyDown(Event event, HtmlNode target, Context ctx) {
        if (event instanceof UIEvents.Keyboard ke) {
            String key = ke.getKey();
            if ("Enter".equals(key)) {
                // 入力欄での Enter キーによる暗黙の送信 (Implicit submission)
                if ("input".equalsIgnoreCase(target.tag)) {
                    String inputType = target.attrs.getOrDefault("type", "text").toLowerCase(Locale.ROOT);
                    if ("text".equals(inputType) || "search".equals(inputType) || "url".equals(inputType)
                            || "email".equals(inputType) || "password".equals(inputType)) {
                        HtmlNode form = findSelfOrAncestor(target, "form");
                        if (form instanceof com.nishiyu.enderframelib.api.dom.Element formEl) {
                            formEl.requestSubmit(null);
                        }
                    }
                }
            }
        }
    }

    private static boolean isNaturallyFocusable(HtmlNode node) {
        if (node == null) return false;
        String tag = node.tag != null ? node.tag.toLowerCase(Locale.ROOT) : "";
        if ("a".equals(tag) && node.attrs.containsKey("href")) return true;
        return isFormControl(node);
    }

    private static boolean isFormControl(HtmlNode node) {
        if (node == null) return false;
        String tag = node.tag != null ? node.tag.toLowerCase(Locale.ROOT) : "";
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
