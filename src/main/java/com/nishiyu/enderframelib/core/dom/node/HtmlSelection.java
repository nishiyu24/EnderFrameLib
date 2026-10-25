package com.nishiyu.enderframelib.core.dom.node;

import com.nishiyu.enderframelib.api.dom.DOMException;
import com.nishiyu.enderframelib.api.dom.Node;
import com.nishiyu.enderframelib.api.dom.Range;
import com.nishiyu.enderframelib.api.dom.Selection;
import com.nishiyu.enderframelib.core.event.CoreEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Selection API の {@link Selection}。範囲を 1 つだけ持ち（Chrome と同じ）、その範囲は生きた {@link HtmlRange}。
 * {@code anchor} が範囲の終点側にあるとき（後ろ向きに選んだとき）は {@link #backward}。
 * 範囲が変わるたびに文書へ {@code selectionchange} を送る。
 */
public final class HtmlSelection implements Selection {

    private final HtmlDocument document;
    @Nullable
    private HtmlRange range;
    private boolean backward;

    HtmlSelection(HtmlDocument document) {
        this.document = document;
    }

    @Nullable
    @Override
    public Node getAnchorNode() {
        if (range == null) return null;
        return backward ? range.endNode : range.startNode;
    }

    @Override
    public int getAnchorOffset() {
        if (range == null) return 0;
        return backward ? range.endOffset : range.startOffset;
    }

    @Nullable
    @Override
    public Node getFocusNode() {
        if (range == null) return null;
        return backward ? range.startNode : range.endNode;
    }

    @Override
    public int getFocusOffset() {
        if (range == null) return 0;
        return backward ? range.startOffset : range.endOffset;
    }

    @Override
    public boolean isCollapsed() {
        return range == null || range.isCollapsed();
    }

    @Override
    public int getRangeCount() {
        return range == null ? 0 : 1;
    }

    @Override
    public String getType() {
        return range == null ? "None" : range.isCollapsed() ? "Caret" : "Range";
    }

    @Override
    public String getDirection() {
        if (range == null || range.isCollapsed()) return "none";
        return backward ? "backward" : "forward";
    }

    @Override
    public Range getRangeAt(int index) {
        if (index != 0 || range == null) throw new DOMException("The index " + index + " is not a valid range index.", "IndexSizeError");
        return range;
    }

    @Override
    public void addRange(Range added) {
        // 文書の中にある範囲だけを、まだ範囲が無いときに限って受け付ける（仕様）
        if (range != null || !(added instanceof HtmlRange r) || !isInDocument(r.startNode)) return;
        range = r;
        backward = false;
        changed();
    }

    @Override
    public void removeRange(Range removed) {
        if (range != removed) throw new DOMException("The given range is not in the selection.", "NotFoundError");
        removeAllRanges();
    }

    @Override
    public void removeAllRanges() {
        if (range == null) return;
        range = null;
        backward = false;
        changed();
    }

    @Override
    public void empty() {
        removeAllRanges();
    }

    @Override
    public void collapse(@Nullable Node node, int offset) {
        if (node == null) {
            removeAllRanges();
            return;
        }
        HtmlNode n = (HtmlNode) node;
        if (offset < 0 || offset > HtmlRange.length(n)) {
            throw new DOMException("The offset " + offset + " is larger than the node's length.", "IndexSizeError");
        }
        if (!isInDocument(n)) return;
        HtmlRange r = new HtmlRange(document);
        r.startNode = n;
        r.startOffset = offset;
        r.endNode = n;
        r.endOffset = offset;
        range = r;
        backward = false;
        changed();
    }

    @Override
    public void collapseToStart() {
        requireRange();
        range.collapse(true);
        backward = false;
        changed();
    }

    @Override
    public void collapseToEnd() {
        requireRange();
        range.collapse(false);
        backward = false;
        changed();
    }

    @Override
    public void extend(Node node, int offset) {
        requireRange();
        HtmlNode n = (HtmlNode) node;
        if (offset < 0 || offset > HtmlRange.length(n)) {
            throw new DOMException("The offset " + offset + " is larger than the node's length.", "IndexSizeError");
        }
        // anchor は動かさず、focus を新しい位置へ（向きは anchor との前後で決まる）
        Node anchorNode = getAnchorNode();
        int anchorOffset = getAnchorOffset();
        setBaseAndExtent(anchorNode, anchorOffset, node, offset);
    }

    @Override
    public void setBaseAndExtent(Node anchorNode, int anchorOffset, Node focusNode, int focusOffset) {
        HtmlNode a = (HtmlNode) anchorNode;
        HtmlNode f = (HtmlNode) focusNode;
        if (anchorOffset < 0 || anchorOffset > HtmlRange.length(a) || focusOffset < 0 || focusOffset > HtmlRange.length(f)) {
            throw new DOMException("The offset is larger than the node's length.", "IndexSizeError");
        }
        if (!isInDocument(a) || !isInDocument(f)) return;
        HtmlRange r = new HtmlRange(document);
        if (HtmlRange.compare(a, anchorOffset, f, focusOffset) <= 0) {
            r.startNode = a; r.startOffset = anchorOffset; r.endNode = f; r.endOffset = focusOffset;
            backward = false;
        } else {
            r.startNode = f; r.startOffset = focusOffset; r.endNode = a; r.endOffset = anchorOffset;
            backward = true;
        }
        range = r;
        changed();
    }

    @Override
    public void selectAllChildren(Node node) {
        HtmlNode n = (HtmlNode) node;
        if (!isInDocument(n)) return;
        HtmlRange r = new HtmlRange(document);
        r.startNode = n;
        r.startOffset = 0;
        r.endNode = n;
        r.endOffset = HtmlRange.length(n);
        range = r;
        backward = false;
        changed();
    }

    @Override
    public void deleteFromDocument() {
        if (range == null) return;
        range.deleteContents();
        changed();
    }

    @Override
    public boolean containsNode(Node node, boolean allowPartialContainment) {
        if (range == null) return false;
        HtmlNode n = (HtmlNode) node;
        if (allowPartialContainment) return range.intersectsNode(n);
        HtmlNode parent = n.parent;
        if (parent == null) return false;
        int index = parent.children.indexOf(n);
        return HtmlRange.compare(n, 0, range.startNode, range.startOffset) > 0
                && HtmlRange.compare(n, HtmlRange.length(n), range.endNode, range.endOffset) < 0
                || (parent == range.startNode && parent == range.endNode && index >= range.startOffset && index < range.endOffset);
    }

    @Override
    public String toString() {
        return range == null ? "" : range.toString();
    }

    private void requireRange() {
        if (range == null) throw new DOMException("This Selection object doesn't have any Ranges.", "InvalidStateError");
    }

    private boolean isInDocument(HtmlNode node) {
        HtmlNode top = node;
        while (top.parent != null) top = top.parent;
        return top == document;
    }

    private void changed() {
        document.dispatchEvent(new CoreEvent("selectionchange", false, false));
    }
}
