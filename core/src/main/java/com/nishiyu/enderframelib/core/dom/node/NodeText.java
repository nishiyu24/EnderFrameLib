package com.nishiyu.enderframelib.core.dom.node;

import java.util.ArrayList;

/** 節の文字の内容（{@code textContent}）の読み書き。{@link HtmlNode} から分けた（M5-1）。 */
final class NodeText {
    private NodeText() {}

    static String textContent(HtmlNode n) {
        if (n.nodeType == NodeType.TEXT || n.nodeType == NodeType.COMMENT) return n.text;
        if (n.nodeType == NodeType.ROOT) return null;
        StringBuilder sb = new StringBuilder();
        for (HtmlNode child : n.children) {
            if (child.nodeType != NodeType.COMMENT) {
                String cText = child.getTextContent();
                if (cText != null) sb.append(cText);
            }
        }
        return sb.toString();
    }

    static void setTextContent(HtmlNode n, String textContent) {
        if (n.nodeType == NodeType.ROOT) return;
        if (n.nodeType == NodeType.TEXT || n.nodeType == NodeType.COMMENT) {
            String oldVal = n.text;
            n.text = textContent != null ? textContent : "";
            MutationObserverManager.queueCharacterData(n, oldVal);
            n.notifyDomChanged(null);
            return;
        }
        // 要素やフラグメントの場合: 既存の子を全削除し、非空なら Text ノードを1つ追加
        // template の textContent は（getter は空でも）中身を書き換える
        HtmlNode container = n instanceof HtmlElement el && el.isTemplateElement() ? el.getContent() : n;
        for (HtmlNode child : new ArrayList<>(container.children)) container.removeChild(child);
        if (textContent != null && !textContent.isEmpty()) {
            HtmlText txtNode = new HtmlText(textContent);
            // 描画要素の id と親子の連鎖（mc-parent）は節の id で引くので、空の id を作らない。
            // 空のままだと同じく空の id の節どうしが取り違えられ、祖先の transform などが掛からない
            txtNode.setId(n.key() + "_txt");
            container.appendChild(txtNode);
        }
    }

    /**
     * {@link #setTextContent} と同じ結果の文字にするが、要素の子が空でない Text 1 つだけのときは、その Text の文字を
     * 書き換えるだけにする（Text 節を作り直さない）。毎フレーム値が変わる HUD の文字やデータバインディング向けで、
     * 箱の木を作り直さずに文字の部分だけを測り直す高速経路（{@code UiView} の TEXT_PATCH）に乗る。
     * 文字が同じなら何もしない。MutationObserver には characterData の変化として届く（childList ではない）。
     */
    static void updateTextContent(HtmlNode n, String textContent) {
        String next = textContent != null ? textContent : "";
        if (n.nodeType == NodeType.TEXT || n.nodeType == NodeType.COMMENT) {
            if (!next.equals(n.text)) setTextContent(n, next);
            return;
        }
        if (n.children.size() == 1 && n.children.get(0) instanceof HtmlText only && !next.isEmpty() && !only.text.isEmpty()) {
            if (!next.equals(only.text)) only.setData(next);
            return;
        }
        n.setTextContent(textContent);
    }
}
