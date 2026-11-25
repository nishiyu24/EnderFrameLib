package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.Ownership;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * サーバーから再同期で届いた全木を、生きている文書へ採用する（M2c ④ で {@link UiView} から出した。ビューの状態に触れない）。
 * クライアント所有の部分木の付け替えと、節に紐づく状態（スクロール・フォーカス・ホバー）の持ち越し。
 */
final class ServerTreeAdoption {
    private ServerTreeAdoption() { }

    /**
     * 再同期で届いた全木を、生きている文書の中身にする。文書のハンドル（{@code doc.root}）は変えない。
     * クライアント所有（{@code data-owner="client"}）の部分木は捨てず、新しい木の同じ id の位置へ付け替える。
     * 新しい木に同じ id の要素が無ければ、その持ち主がサーバー側で消えたのでクライアント所有の部分木も一緒に消える。
     */
    static void adopt(HtmlNode liveRoot, HtmlNode freshRoot) {
        if (liveRoot == freshRoot) return;

        Map<String, HtmlNode> clientIslands = new LinkedHashMap<>();
        collectClientOwnedIslands(liveRoot, clientIslands);

        for (HtmlNode stale : new ArrayList<>(liveRoot.children)) {
            liveRoot.removeChild(stale);
        }
        for (HtmlNode fresh : new ArrayList<>(freshRoot.children)) {
            liveRoot.appendChild(fresh);
        }

        for (Map.Entry<String, HtmlNode> island : clientIslands.entrySet()) {
            HtmlNode placeholder = liveRoot.getNodeById(island.getKey());
            if (placeholder != null && placeholder.parent != null) {
                placeholder.parent.replaceChild(island.getValue(), placeholder);
            }
        }
    }

    /**
     * 再同期で木を差し替えても戻す、節に紐づく状態（スクロール位置・フォーカス・ホバー）。
     * 状態は節そのものをキーに持つので、作り直された節へは id をたよりに移す。id の無い節は対象外。
     */
    record NodeStateSnapshot(Map<String, float[]> scrolls, String focusedId, boolean focusVisible, String hoveredId) {

        static NodeStateSnapshot capture(HtmlNode root, ScrollState scrollState, InteractionState interaction) {
            Map<String, float[]> scrolls = new LinkedHashMap<>();
            collectScrolls(root, scrollState, scrolls);
            HtmlNode focused = interaction.focusedNode();
            boolean visible = focused != null && interaction.get(focused) != null && interaction.get(focused).isFocusVisible();
            HtmlNode hovered = interaction.hoveredNode();
            return new NodeStateSnapshot(scrolls, focused != null ? focused.key() : "", visible,
                    hovered != null ? hovered.key() : "");
        }

        private static void collectScrolls(HtmlNode node, ScrollState scrollState, Map<String, float[]> out) {
            float x = scrollState.getScrollX(node);
            float y = scrollState.getScrollY(node);
            if ((x != 0 || y != 0) && !node.key().isEmpty()) out.put(node.key(), new float[]{x, y});
            for (HtmlNode child : node.children) collectScrolls(child, scrollState, out);
        }

        void restore(HtmlNode root, ScrollState scrollState, InteractionState interaction) {
            for (Map.Entry<String, float[]> entry : scrolls.entrySet()) {
                HtmlNode node = root.getNodeById(entry.getKey());
                if (node != null) scrollState.setScroll(node, entry.getValue()[0], entry.getValue()[1]);
            }
            if (!focusedId.isEmpty()) {
                HtmlNode node = root.getNodeById(focusedId);
                if (node != null) interaction.setFocusedNode(node, focusVisible);
            }
            if (!hoveredId.isEmpty()) {
                HtmlNode node = root.getNodeById(hoveredId);
                if (node != null) interaction.setHoveredNode(node);
            }
        }
    }

    /** 最上位のクライアント所有の部分木（id を持つもの）を集める。その内側は島ごと運ぶので降りない。 */
    private static void collectClientOwnedIslands(HtmlNode node, Map<String, HtmlNode> islands) {
        for (HtmlNode child : node.children) {
            boolean declaredClientOwned = Ownership.parse(child.attrs.get(Ownership.ATTRIBUTE)) == Ownership.CLIENT;
            if (declaredClientOwned && !child.key().isEmpty()) {
                islands.put(child.key(), child);
            } else {
                collectClientOwnedIslands(child, islands);
            }
        }
    }
}
