package com.nishiyu.enderframelib.core.dom;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.HashMap;

/**
 * 仮想DOMツリー(virtualNode)の変更を、実DOMツリー(realNode)に差分適用するエンジン
 */
public class DomDiffEngine {

    /**
     * @return 実DOMに変更（パッチ）が適用された場合は true
     */
    public static boolean diffAndPatch(HtmlNode realNode, HtmlNode virtualNode) {
        if (realNode == null || virtualNode == null) return false;
        boolean changed = false;

        // 1. テキストと内部フラグのDiff
        if (!Objects.equals(realNode.text, virtualNode.text)) {
            realNode.text = virtualNode.text != null ? virtualNode.text : "";
            changed = true;
        }
        if (realNode.isPseudoNode != virtualNode.isPseudoNode) {
            realNode.isPseudoNode = virtualNode.isPseudoNode;
            changed = true;
        }

        // 2. 属性(Attributes)のDiff
        Iterator<String> it = realNode.attrs.keySet().iterator();
        while (it.hasNext()) {
            String key = it.next();
            if (!virtualNode.attrs.containsKey(key)) {
                it.remove();
                changed = true;
            }
        }
        for (Map.Entry<String, String> entry : virtualNode.attrs.entrySet()) {
            String key = entry.getKey();
            String vVal = entry.getValue();
            String rVal = realNode.attrs.get(key);
            if (!Objects.equals(rVal, vVal)) {
                realNode.attrs.put(key, vVal);
                changed = true;
            }
        }

        // 3. 子要素(Children)のDiff (キーベース)

        // 旧子要素のマップを作成 (ID をキーにする。IDがない場合はインデックスなどをフォールバックにするのも手ですが、今回は簡易化のためIDが存在する前提、または位置フォールバックを入れます)
        Map<String, HtmlNode> realChildrenMap = new HashMap<>();
        for (HtmlNode child : realNode.children) {
            String id = child.getId();
            if (!id.isEmpty()) {
                realChildrenMap.put(id, child);
            }
        }

        // 新しい子要素リストを構築
        boolean childrenChanged = false;
        for (int i = 0; i < virtualNode.children.size(); i++) {
            HtmlNode vChild = virtualNode.children.get(i);
            String vId = vChild.getId();

            // 現在のインデックスにある実ノードを取得
            HtmlNode currentRChild = (i < realNode.children.size()) ? realNode.children.get(i) : null;

            HtmlNode rChildToUse = null;

            // IDが一致するものをマップから探す（キーベース照合）
            if (!vId.isEmpty() && realChildrenMap.containsKey(vId)) {
                rChildToUse = realChildrenMap.get(vId);
                // 場所が違えば移動してきた
                if (currentRChild != rChildToUse) {
                    childrenChanged = true;
                }
            } else if (currentRChild != null && currentRChild.tag.equals(vChild.tag) && currentRChild.getId().equals(vId)) {
                // マップで見つからなくても、現在の位置のものが一致すればそのまま使う
                rChildToUse = currentRChild;
            }

            // 見つからなかった、またはタグが違う場合は新しくクローン
            if (rChildToUse == null || !rChildToUse.tag.equals(vChild.tag)) {
                HtmlNode newRealChild = vChild.cloneNode();
                newRealChild.parent = realNode;

                // リストに挿入/追加
                if (i < realNode.children.size()) {
                    realNode.children.add(i, newRealChild);
                } else {
                    realNode.children.add(newRealChild);
                }
                changed = true;
                childrenChanged = true;
            } else {
                // 既存ノードを再利用してパッチ
                if (diffAndPatch(rChildToUse, vChild)) {
                    changed = true;
                }
                // もし位置がずれていたらリスト上で移動
                if (currentRChild != rChildToUse) {
                    realNode.children.remove(rChildToUse);
                    realNode.children.add(i, rChildToUse);
                    changed = true;
                }
            }
        }

        // 超過分の削除（不要になったノードを刈り取る）
        while (realNode.children.size() > virtualNode.children.size()) {
            HtmlNode removed = realNode.children.removeLast();
            removed.parent = null; // C-4: 削除時に親ポインタをクリア
            changed = true;
        }

        return changed || childrenChanged;
    }
}