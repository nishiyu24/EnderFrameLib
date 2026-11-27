package com.nishiyu.enderframelib.core.dom.node;

/**
 * Top Layer（HTML §4.11 {@code dialog} / §6.12 {@code popover}）の操作の中身。{@link HtmlElement} の同名の公開メソッドが呼ぶ。
 * 開いているモーダル・ポップオーバーと不活性の判定は文書の {@link DocumentState} が持つ。
 */
final class TopLayerOps {

    private TopLayerOps() {}

    private static HtmlDocument documentOf(HtmlElement el) {
        HtmlNode root = el;
        while (root.parent != null) root = root.parent;
        return root instanceof HtmlDocument doc ? doc : null;
    }

    private static DocumentState stateOf(HtmlElement el) {
        HtmlDocument doc = documentOf(el);
        return doc != null ? doc.interactionState() : null;
    }

    private static boolean isDialog(HtmlElement el) {
        return "dialog".equalsIgnoreCase(el.tag);
    }

    static void show(HtmlElement el) {
        if (!isDialog(el)) return;
        if (!el.hasAttribute("open")) el.setAttribute("open", "");
    }

    static void showModal(HtmlElement el) {
        if (!isDialog(el) || el.hasAttribute("open")) return;
        HtmlDocument doc = documentOf(el);
        DocumentState st = doc != null ? doc.interactionState() : null;
        el.setAttribute("open", "");
        if (st != null) st.setModal(el, true);
        if (doc != null) {
            doc.clearInertFocusAndPointer();
            HtmlElement af = findAutofocus(el, st);
            if (af != null) af.focus();
        }
    }

    private static HtmlElement findAutofocus(HtmlNode node, DocumentState st) {
        for (HtmlNode child : node.children) {
            if (child instanceof HtmlElement el) {
                if (el.hasAttribute("autofocus") && !el.hasAttribute("disabled") && !el.hasAttribute("hidden")
                        && (st == null || !st.isInert(el))) {
                    return el;
                }
                HtmlElement nested = findAutofocus(el, st);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    static boolean isInert(HtmlElement el) {
        DocumentState st = stateOf(el);
        if (st != null) return st.isInert(el);
        for (HtmlNode cur = el; cur != null; cur = cur.parent) {
            if (cur.attrs != null && cur.attrs.containsKey("inert")) return true;
        }
        return false;
    }

    static void close(HtmlElement el) {
        if (!isDialog(el) || !el.hasAttribute("open")) return;
        DocumentState st = stateOf(el);
        el.removeAttribute("open");
        if (st != null) st.setModal(el, false);
        el.dispatchEvent(new CoreEvent("close", false, false));
    }

    static boolean isModalOpen(HtmlElement el) {
        DocumentState st = stateOf(el);
        return st != null && st.isModal(el);
    }

    static boolean isPopoverOpen(HtmlElement el) {
        if (!el.hasAttribute("popover")) return false;
        DocumentState st = stateOf(el);
        return st != null && st.isPopoverOpen(el);
    }

    static void setPopoverOpen(HtmlElement el, boolean open) {
        if (!el.hasAttribute("popover")) return;
        DocumentState st = stateOf(el);
        if (st != null) st.setPopoverOpen(el, open);
    }

    static boolean togglePopover(HtmlElement el) {
        if (!el.hasAttribute("popover")) return false;
        DocumentState st = stateOf(el);
        if (st == null) return false;
        boolean open = !st.isPopoverOpen(el);
        st.setPopoverOpen(el, open);
        return open;
    }
}
