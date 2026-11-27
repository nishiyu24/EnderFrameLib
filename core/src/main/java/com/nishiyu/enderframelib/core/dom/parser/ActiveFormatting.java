package com.nishiyu.enderframelib.core.dom.parser;

import static com.nishiyu.enderframelib.core.dom.parser.TagSets.FOSTER_TARGETS;
import static com.nishiyu.enderframelib.core.dom.parser.TagSets.SPECIAL;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.parser.TreeBuilder.Scope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * active formatting elements の一覧（§13.2.4.3）と、誤って入れ子になった書式要素の終了タグで木を組み替える
 * adoption agency（§13.2.6.4.7）。開いている要素のスタックは {@link TreeBuilder} と共有する。
 */
final class ActiveFormatting {

    private final TreeBuilder b;
    private final List<HtmlNode> stack;
    /** マーカーは {@link FormatEntry#marker} が true の項目。 */
    final List<FormatEntry> formatting = new ArrayList<>();

    ActiveFormatting(TreeBuilder b) {
        this.b = b;
        this.stack = b.stack;
    }

    /** 一覧の項目。マーカーは {@code node} がその要素（閉じたら項目ごと捨てる）、書式要素は作った時の属性を持つ。 */
    static final class FormatEntry {
        final boolean marker;
        final HtmlNode node;
        final String tag;
        final Map<String, String> attrs;

        FormatEntry(boolean marker, HtmlNode node, String tag, Map<String, String> attrs) {
            this.marker = marker;
            this.node = node;
            this.tag = tag;
            this.attrs = attrs;
        }
    }

    /** 持ち主の要素がスタックから消えたマーカーと、それ以降の項目を捨てる（マーカー以降を掃除する手順の代わり）。 */
    void pruneFormatting() {
        for (int i = formatting.size() - 1; i >= 0; i--) {
            FormatEntry e = formatting.get(i);
            if (e.marker && !stack.contains(e.node)) {
                while (formatting.size() > i) formatting.removeLast();
            }
        }
    }

    /** 最後のマーカーより後で、{@code tag} の最後の項目。 */
    FormatEntry lastFormatting(String tag) {
        pruneFormatting();
        for (int i = formatting.size() - 1; i >= 0; i--) {
            FormatEntry e = formatting.get(i);
            if (e.marker) return null;
            if (e.tag.equals(tag)) return e;
        }
        return null;
    }

    void pushMarker(HtmlNode owner) {
        formatting.add(new FormatEntry(true, owner, owner.tag, Map.of()));
    }

    /** 書式要素を一覧に入れる。同じ要素名・同じ属性が最後のマーカー以降に 3 つあれば最も古いものを捨てる（Noah's Ark）。 */
    void pushFormatting(HtmlNode node, Map<String, String> attrs) {
        pruneFormatting();
        Map<String, String> copy = new LinkedHashMap<>(attrs);
        int same = 0, oldest = -1;
        for (int i = formatting.size() - 1; i >= 0; i--) {
            FormatEntry e = formatting.get(i);
            if (e.marker) break;
            if (e.tag.equals(node.tag) && e.attrs.equals(copy)) {
                same++;
                oldest = i;
            }
        }
        if (same >= 3) formatting.remove(oldest);
        formatting.add(new FormatEntry(false, node, node.tag, copy));
    }

    /** 一覧の書式要素のうちスタックに無いものを、開き直す（§13.2.4.3 の再構築）。 */
    void reconstructFormatting() {
        pruneFormatting();
        if (formatting.isEmpty()) return;
        FormatEntry last = formatting.getLast();
        if (last.marker || stack.contains(last.node)) return;
        int i = formatting.size() - 1;
        while (i > 0) {
            FormatEntry e = formatting.get(i - 1);
            if (e.marker || stack.contains(e.node)) break;
            i--;
        }
        for (; i < formatting.size(); i++) {
            FormatEntry old = formatting.get(i);
            HtmlNode fresh = b.insertElement(old.tag, old.attrs, false);
            formatting.set(i, new FormatEntry(false, fresh, old.tag, old.attrs));
        }
    }

    /** 誤って入れ子になった書式要素の終了タグで木を組み替える（外側 8 回・内側 3 回まで）。 */
    void adoptionAgency(String subject) {
        HtmlNode current = b.currentNode();
        if (current.tag.equals(subject) && formatting.stream().noneMatch(e -> !e.marker && e.node == current)) {
            stack.removeLast();
            return;
        }
        for (int outer = 0; outer < 8; outer++) {
            FormatEntry fe = lastFormatting(subject);
            if (fe == null) {
                b.body.anyOtherEndTag(subject);
                return;
            }
            int feIdx = stack.indexOf(fe.node);
            if (feIdx < 0) {
                formatting.remove(fe);
                return;
            }
            if (!b.inScope(subject, Scope.DEFAULT)) return;

            int blockIdx = -1;
            for (int i = feIdx + 1; i < stack.size(); i++) {
                if (SPECIAL.contains(stack.get(i).tag)) {
                    blockIdx = i;
                    break;
                }
            }
            if (blockIdx < 0) {
                while (stack.size() > feIdx) stack.removeLast();
                formatting.remove(fe);
                return;
            }
            HtmlNode furthestBlock = stack.get(blockIdx);
            HtmlNode commonAncestor = stack.get(feIdx - 1);
            int bookmark = formatting.indexOf(fe);

            HtmlNode node = furthestBlock, lastNode = furthestBlock;
            int nodeIdx = blockIdx;
            for (int inner = 1; ; inner++) {
                nodeIdx--;
                node = stack.get(nodeIdx);
                if (node == fe.node) break;
                FormatEntry entry = entryOf(node);
                if (inner > 3 && entry != null) {
                    int at = formatting.indexOf(entry);
                    formatting.remove(at);
                    if (at < bookmark) bookmark--;
                    entry = null;
                }
                if (entry == null) {
                    stack.remove(nodeIdx);
                    continue;
                }
                HtmlNode fresh = b.createElement(entry.tag, entry.attrs);
                FormatEntry replaced = new FormatEntry(false, fresh, entry.tag, entry.attrs);
                int at = formatting.indexOf(entry);
                formatting.set(at, replaced);
                stack.set(nodeIdx, fresh);
                node = fresh;
                if (lastNode == furthestBlock) bookmark = at + 1;
                detach(lastNode);
                lastNode.parent = node;
                node.children.add(lastNode);
                lastNode = node;
            }
            detach(lastNode);
            insertAt(commonAncestor, lastNode);

            HtmlNode fresh = b.createElement(fe.tag, fe.attrs);
            HtmlNode blockHost = TreeBuilder.childHost(furthestBlock);
            for (HtmlNode child : blockHost.children) child.parent = fresh;
            fresh.children.addAll(blockHost.children);
            blockHost.children.clear();
            fresh.parent = blockHost;
            blockHost.children.add(fresh);

            formatting.remove(fe);
            bookmark = Math.min(bookmark, formatting.size());
            formatting.add(bookmark, new FormatEntry(false, fresh, fe.tag, fe.attrs));
            stack.remove(fe.node);
            stack.add(stack.indexOf(furthestBlock) + 1, fresh);
        }
    }

    private FormatEntry entryOf(HtmlNode node) {
        for (FormatEntry e : formatting) if (!e.marker && e.node == node) return e;
        return null;
    }

    private static void detach(HtmlNode node) {
        if (node.parent != null) node.parent.children.remove(node);
        node.parent = null;
    }

    /** {@code target} の子として入れる。表の部品なら表の前に出す（foster parenting）。 */
    private void insertAt(HtmlNode target, HtmlNode node) {
        if (FOSTER_TARGETS.contains(target.tag)) {
            HtmlNode table = b.lastOpen("table");
            if (table != null && table.parent != null) {
                node.parent = table.parent;
                table.parent.children.add(table.parent.children.indexOf(table), node);
                return;
            }
        }
        HtmlNode host = TreeBuilder.childHost(target);
        node.parent = host;
        host.children.add(node);
    }
}
