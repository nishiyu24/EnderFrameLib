package com.nishiyu.enderframelib.core.dom.node;

import com.nishiyu.enderframelib.api.dom.DOMException;
import com.nishiyu.enderframelib.api.dom.DOMNodes.DocumentFragment;
import com.nishiyu.enderframelib.api.dom.Node;
import com.nishiyu.enderframelib.api.dom.Range;

import java.util.ArrayList;
import java.util.List;

/**
 * WHATWG DOM Standard §5 の {@link Range}。生きた範囲で、{@link HtmlDocument} が節の挿入・削除・文字の変更のたびに
 * 境界点を直す（{@link HtmlDocument#rangesNodeInserted} など）。
 *
 * 境界点の比較・内容の取り出し・複製・挿入は、仕様の手順をそのまま写している。
 */
public final class HtmlRange implements Range {

    private final HtmlDocument document;
    HtmlNode startNode;
    int startOffset;
    HtmlNode endNode;
    int endOffset;

    /** 文書の先頭（文書節の 0）に折りたたまれた範囲を作り、文書に登録する。 */
    public HtmlRange(HtmlDocument document) {
        this.document = document;
        this.startNode = document;
        this.endNode = document;
        document.registerRange(this);
    }

    // ---- 境界点

    @Override
    public Node getStartContainer() {
        return startNode;
    }

    @Override
    public int getStartOffset() {
        return startOffset;
    }

    @Override
    public Node getEndContainer() {
        return endNode;
    }

    @Override
    public int getEndOffset() {
        return endOffset;
    }

    @Override
    public boolean isCollapsed() {
        return startNode == endNode && startOffset == endOffset;
    }

    @Override
    public Node getCommonAncestorContainer() {
        HtmlNode a = startNode;
        while (a != null && !isInclusiveAncestor(a, endNode)) a = a.parent;
        return a;
    }

    @Override
    public void setStart(Node node, int offset) {
        HtmlNode n = checkBoundary(node, offset);
        if (root(n) != root(startNode) || compare(n, offset, endNode, endOffset) > 0) {
            endNode = n;
            endOffset = offset;
        }
        startNode = n;
        startOffset = offset;
    }

    @Override
    public void setEnd(Node node, int offset) {
        HtmlNode n = checkBoundary(node, offset);
        if (root(n) != root(startNode) || compare(n, offset, startNode, startOffset) < 0) {
            startNode = n;
            startOffset = offset;
        }
        endNode = n;
        endOffset = offset;
    }

    @Override
    public void setStartBefore(Node node) {
        HtmlNode p = parentOrThrow(node);
        setStart(p, indexOf((HtmlNode) node));
    }

    @Override
    public void setStartAfter(Node node) {
        HtmlNode p = parentOrThrow(node);
        setStart(p, indexOf((HtmlNode) node) + 1);
    }

    @Override
    public void setEndBefore(Node node) {
        HtmlNode p = parentOrThrow(node);
        setEnd(p, indexOf((HtmlNode) node));
    }

    @Override
    public void setEndAfter(Node node) {
        HtmlNode p = parentOrThrow(node);
        setEnd(p, indexOf((HtmlNode) node) + 1);
    }

    @Override
    public void collapse(boolean toStart) {
        if (toStart) {
            endNode = startNode;
            endOffset = startOffset;
        } else {
            startNode = endNode;
            startOffset = endOffset;
        }
    }

    @Override
    public void selectNode(Node node) {
        HtmlNode p = parentOrThrow(node);
        int index = indexOf((HtmlNode) node);
        startNode = p;
        startOffset = index;
        endNode = p;
        endOffset = index + 1;
    }

    @Override
    public void selectNodeContents(Node node) {
        HtmlNode n = checkBoundary(node, 0);
        startNode = n;
        startOffset = 0;
        endNode = n;
        endOffset = length(n);
    }

    @Override
    public short compareBoundaryPoints(short how, Range sourceRange) {
        if (how < START_TO_START || how > END_TO_START) {
            throw new DOMException("The comparison method provided must be one of START_TO_START, START_TO_END, END_TO_END, END_TO_START.", "NotSupportedError");
        }
        if (!(sourceRange instanceof HtmlRange other)) throw new IllegalArgumentException("Range must be an HtmlRange");
        if (root(startNode) != root(other.startNode)) {
            throw new DOMException("The two Ranges are not in the same tree.", "WrongDocumentError");
        }
        HtmlNode thisNode, otherNode;
        int thisOffset, otherOffset;
        switch (how) {
            case START_TO_START -> { thisNode = startNode; thisOffset = startOffset; otherNode = other.startNode; otherOffset = other.startOffset; }
            case START_TO_END -> { thisNode = endNode; thisOffset = endOffset; otherNode = other.startNode; otherOffset = other.startOffset; }
            case END_TO_END -> { thisNode = endNode; thisOffset = endOffset; otherNode = other.endNode; otherOffset = other.endOffset; }
            default -> { thisNode = startNode; thisOffset = startOffset; otherNode = other.endNode; otherOffset = other.endOffset; }
        }
        return (short) compare(thisNode, thisOffset, otherNode, otherOffset);
    }

    @Override
    public boolean isPointInRange(Node node, int offset) {
        HtmlNode n = checkBoundary(node, offset);
        if (root(n) != root(startNode)) return false;
        return compare(n, offset, startNode, startOffset) >= 0 && compare(n, offset, endNode, endOffset) <= 0;
    }

    @Override
    public short comparePoint(Node node, int offset) {
        HtmlNode n = checkBoundary(node, offset);
        if (root(n) != root(startNode)) throw new DOMException("The node is not in the same tree as the Range.", "WrongDocumentError");
        if (compare(n, offset, startNode, startOffset) < 0) return -1;
        if (compare(n, offset, endNode, endOffset) > 0) return 1;
        return 0;
    }

    @Override
    public boolean intersectsNode(Node node) {
        HtmlNode n = (HtmlNode) node;
        if (root(n) != root(startNode)) return false;
        HtmlNode p = n.parent;
        if (p == null) return true;
        int offset = indexOf(n);
        return compare(p, offset, endNode, endOffset) < 0 && compare(p, offset + 1, startNode, startOffset) > 0;
    }

    // ---- 内容

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (startNode == endNode && startNode.nodeType == NodeType.TEXT) {
            return startNode.text.substring(startOffset, endOffset);
        }
        if (startNode.nodeType == NodeType.TEXT) sb.append(startNode.text, startOffset, startNode.text.length());
        HtmlNode common = (HtmlNode) getCommonAncestorContainer();
        appendContainedText(common, sb);
        if (endNode.nodeType == NodeType.TEXT && endNode != startNode) sb.append(endNode.text, 0, endOffset);
        return sb.toString();
    }

    private void appendContainedText(HtmlNode node, StringBuilder sb) {
        for (HtmlNode child : node.children) {
            if (child.nodeType == NodeType.TEXT && isContained(child)) sb.append(child.text);
            else if (child.nodeType != NodeType.TEXT) appendContainedText(child, sb);
        }
    }

    @Override
    public void deleteContents() {
        if (isCollapsed()) return;
        if (startNode == endNode && isCharacterData(startNode)) {
            replaceData(startNode, startOffset, endOffset - startOffset, "");
            return;
        }
        List<HtmlNode> toRemove = new ArrayList<>();
        HtmlNode common = (HtmlNode) getCommonAncestorContainer();
        for (HtmlNode node : descendants(common)) {
            if (isContained(node) && !isContained(node.parent)) toRemove.add(node);
        }
        HtmlNode newNode;
        int newOffset;
        if (isInclusiveAncestor(startNode, endNode)) {
            newNode = startNode;
            newOffset = startOffset;
        } else {
            HtmlNode reference = startNode;
            while (reference.parent != null && !isInclusiveAncestor(reference.parent, endNode)) reference = reference.parent;
            newNode = reference.parent;
            newOffset = indexOf(reference) + 1;
        }
        if (isCharacterData(startNode)) replaceData(startNode, startOffset, length(startNode) - startOffset, "");
        for (HtmlNode node : toRemove) node.parent.removeChild(node);
        if (isCharacterData(endNode)) replaceData(endNode, 0, endOffset, "");
        startNode = newNode;
        startOffset = newOffset;
        endNode = newNode;
        endOffset = newOffset;
    }

    @Override
    public DocumentFragment extractContents() {
        return takeContents(true);
    }

    @Override
    public DocumentFragment cloneContents() {
        return takeContents(false);
    }

    /** 仕様の extract / clone の手順。{@code extract} のとき文書から取り除き、そうでなければ複製する。 */
    private DocumentFragment takeContents(boolean extract) {
        HtmlDocument.HtmlDocumentFragment fragment = (HtmlDocument.HtmlDocumentFragment) document.createDocumentFragment();
        if (isCollapsed()) return fragment;

        if (startNode == endNode && isCharacterData(startNode)) {
            HtmlNode clone = startNode.cloneNode(false);
            clone.ownerDocument = document;
            clone.text = startNode.text.substring(startOffset, endOffset);
            fragment.appendChild(clone);
            if (extract) replaceData(startNode, startOffset, endOffset - startOffset, "");
            return fragment;
        }

        HtmlNode common = (HtmlNode) getCommonAncestorContainer();
        HtmlNode firstPartial = null;
        if (!isInclusiveAncestor(startNode, endNode)) {
            for (HtmlNode child : common.children) if (isPartiallyContained(child)) { firstPartial = child; break; }
        }
        HtmlNode lastPartial = null;
        if (!isInclusiveAncestor(endNode, startNode)) {
            for (int i = common.children.size() - 1; i >= 0; i--) {
                if (isPartiallyContained(common.children.get(i))) { lastPartial = common.children.get(i); break; }
            }
        }
        List<HtmlNode> contained = new ArrayList<>();
        for (HtmlNode child : common.children) if (isContained(child)) contained.add(child);

        HtmlNode newNode;
        int newOffset;
        if (isInclusiveAncestor(startNode, endNode)) {
            newNode = startNode;
            newOffset = startOffset;
        } else {
            HtmlNode reference = startNode;
            while (reference.parent != null && !isInclusiveAncestor(reference.parent, endNode)) reference = reference.parent;
            newNode = reference.parent;
            newOffset = indexOf(reference) + 1;
        }

        if (firstPartial != null) {
            if (isCharacterData(firstPartial)) {
                HtmlNode clone = startNode.cloneNode(false);
                clone.ownerDocument = document;
                clone.text = startNode.text.substring(startOffset);
                fragment.appendChild(clone);
                if (extract) replaceData(startNode, startOffset, length(startNode) - startOffset, "");
            } else {
                HtmlNode clone = firstPartial.cloneNode(false);
                clone.ownerDocument = document;
                fragment.appendChild(clone);
                HtmlRange sub = new HtmlRange(document);
                sub.startNode = startNode;
                sub.startOffset = startOffset;
                sub.endNode = firstPartial;
                sub.endOffset = length(firstPartial);
                clone.appendChild(sub.takeContents(extract));
            }
        }
        for (HtmlNode node : contained) {
            if (extract) {
                fragment.appendChild(node);
            } else {
                HtmlNode copy = node.cloneNode(true);
                copy.ownerDocument = document;
                fragment.appendChild(copy);
            }
        }
        if (lastPartial != null) {
            if (isCharacterData(lastPartial)) {
                HtmlNode clone = endNode.cloneNode(false);
                clone.ownerDocument = document;
                clone.text = endNode.text.substring(0, endOffset);
                fragment.appendChild(clone);
                if (extract) replaceData(endNode, 0, endOffset, "");
            } else {
                HtmlNode clone = lastPartial.cloneNode(false);
                clone.ownerDocument = document;
                fragment.appendChild(clone);
                HtmlRange sub = new HtmlRange(document);
                sub.startNode = lastPartial;
                sub.startOffset = 0;
                sub.endNode = endNode;
                sub.endOffset = endOffset;
                clone.appendChild(sub.takeContents(extract));
            }
        }
        if (extract) {
            startNode = newNode;
            startOffset = newOffset;
            endNode = newNode;
            endOffset = newOffset;
        }
        return fragment;
    }

    @Override
    public void insertNode(Node node) {
        HtmlNode inserted = (HtmlNode) node;
        if (startNode.nodeType == NodeType.COMMENT
                || (startNode.nodeType == NodeType.TEXT && startNode.parent == null)
                || inserted == startNode) {
            throw new DOMException("The new child element contains the parent.", "HierarchyRequestError");
        }
        HtmlNode reference;
        if (startNode.nodeType == NodeType.TEXT) reference = startNode;
        else reference = startOffset < startNode.children.size() ? startNode.children.get(startOffset) : null;
        HtmlNode parent = reference == null ? startNode : reference.parent;
        if (startNode.nodeType == NodeType.TEXT) reference = (HtmlNode) ((HtmlText) startNode).splitText(startOffset);
        if (inserted == reference) reference = reference == null ? null : (HtmlNode) reference.getNextSibling();
        if (inserted.parent != null) inserted.parent.removeChild(inserted);
        int newOffset = reference == null ? length(parent) : indexOf(reference);
        newOffset += inserted.nodeType == NodeType.DOCUMENT_FRAGMENT ? inserted.children.size() : 1;
        parent.insertBefore(inserted, reference);
        if (isCollapsed()) {
            endNode = parent;
            endOffset = newOffset;
        }
    }

    @Override
    public void surroundContents(Node newParent) {
        HtmlNode np = (HtmlNode) newParent;
        if (np.nodeType == NodeType.ROOT || np.nodeType == NodeType.DOCUMENT_FRAGMENT) {
            throw new DOMException("Invalid node type for surroundContents.", "InvalidNodeTypeError");
        }
        // 部分的にしか含まれない非 Text 節があると、範囲は 1 つの親に収まらない
        for (HtmlNode n : List.of(startNode, endNode)) {
            if (n.nodeType != NodeType.TEXT && n != getCommonAncestorContainer() && isPartiallyContained(n)) {
                throw new DOMException("The Range has partially selected a non-Text node.", "InvalidStateError");
            }
        }
        DocumentFragment fragment = extractContents();
        while (!np.children.isEmpty()) np.removeChild(np.children.getFirst());
        insertNode(np);
        np.appendChild(fragment);
        selectNode(np);
    }

    @Override
    public Range cloneRange() {
        HtmlRange copy = new HtmlRange(document);
        copy.startNode = startNode;
        copy.startOffset = startOffset;
        copy.endNode = endNode;
        copy.endOffset = endOffset;
        return copy;
    }

    @Override
    public void detach() {
        // 仕様どおり何もしない
    }

    // ---- 生きた範囲の追従（HtmlDocument から呼ばれる）

    void onInsert(HtmlNode parent, int index, int count) {
        if (startNode == parent && startOffset > index) startOffset += count;
        if (endNode == parent && endOffset > index) endOffset += count;
    }

    void onRemove(HtmlNode removed, HtmlNode parent, int index) {
        if (isInclusiveAncestor(removed, startNode)) {
            startNode = parent;
            startOffset = index;
        }
        if (isInclusiveAncestor(removed, endNode)) {
            endNode = parent;
            endOffset = index;
        }
        if (startNode == parent && startOffset > index) startOffset--;
        if (endNode == parent && endOffset > index) endOffset--;
    }

    void onReplaceData(HtmlNode node, int offset, int count, int newLength) {
        if (startNode == node && startOffset > offset && startOffset <= offset + count) startOffset = offset;
        else if (startNode == node && startOffset > offset + count) startOffset += newLength - count;
        if (endNode == node && endOffset > offset && endOffset <= offset + count) endOffset = offset;
        else if (endNode == node && endOffset > offset + count) endOffset += newLength - count;
    }

    void onSplitText(HtmlNode node, int offset, HtmlNode newNode) {
        if (startNode == node && startOffset > offset) {
            startNode = newNode;
            startOffset -= offset;
        }
        if (endNode == node && endOffset > offset) {
            endNode = newNode;
            endOffset -= offset;
        }
    }

    // ---- 手順の部品

    private boolean isContained(HtmlNode node) {
        if (node == null || root(node) != root(startNode)) return false;
        return compare(node, 0, startNode, startOffset) > 0 && compare(node, length(node), endNode, endOffset) < 0;
    }

    private boolean isPartiallyContained(HtmlNode node) {
        boolean startInside = isInclusiveAncestor(node, startNode);
        boolean endInside = isInclusiveAncestor(node, endNode);
        return startInside != endInside;
    }

    private void replaceData(HtmlNode node, int offset, int count, String data) {
        String old = node.text;
        int end = Math.min(old.length(), offset + count);
        int removed = end - offset;
        node.text = old.substring(0, offset) + data + old.substring(end);
        document.rangesReplaceData(node, offset, removed, data.length());
        node.notifyDomChanged(null);
    }

    private static boolean isCharacterData(HtmlNode node) {
        return node.nodeType == NodeType.TEXT || node.nodeType == NodeType.COMMENT;
    }

    static int length(HtmlNode node) {
        return isCharacterData(node) ? node.text.length() : node.children.size();
    }

    private static int indexOf(HtmlNode node) {
        return node.parent == null ? 0 : node.parent.children.indexOf(node);
    }

    private static HtmlNode parentOrThrow(Node node) {
        HtmlNode parent = ((HtmlNode) node).parent;
        if (parent == null) throw new DOMException("The given Node has no parent.", "InvalidNodeTypeError");
        return parent;
    }

    private static HtmlNode checkBoundary(Node node, int offset) {
        HtmlNode n = (HtmlNode) node;
        if (offset < 0 || offset > length(n)) {
            throw new DOMException("The offset " + offset + " is larger than the node's length (" + length(n) + ").", "IndexSizeError");
        }
        return n;
    }

    private static HtmlNode root(HtmlNode node) {
        while (node.parent != null) node = node.parent;
        return node;
    }

    static boolean isInclusiveAncestor(HtmlNode ancestor, HtmlNode node) {
        for (HtmlNode n = node; n != null; n = n.parent) if (n == ancestor) return true;
        return false;
    }

    private static List<HtmlNode> descendants(HtmlNode node) {
        List<HtmlNode> out = new ArrayList<>();
        for (HtmlNode child : node.children) {
            out.add(child);
            out.addAll(descendants(child));
        }
        return out;
    }

    /** 境界点 (a, offsetA) を (b, offsetB) と比べる（DOM Standard §5.2）。前なら -1、同じなら 0、後なら 1。 */
    static int compare(HtmlNode a, int offsetA, HtmlNode b, int offsetB) {
        if (a == b) return Integer.compare(offsetA, offsetB);
        if (isFollowing(a, b)) return -compare(b, offsetB, a, offsetA);
        if (isInclusiveAncestor(a, b)) {
            HtmlNode child = b;
            while (child.parent != a) child = child.parent;
            if (indexOf(child) < offsetA) return 1;
        }
        return -1;
    }

    /** {@code a} が {@code b} より文書順で後ろ（b の子孫でも a の子孫でもなく、後に現れる）か。 */
    private static boolean isFollowing(HtmlNode a, HtmlNode b) {
        if (isInclusiveAncestor(b, a)) return true;   // a は b の子孫: 子孫は祖先の後
        if (isInclusiveAncestor(a, b)) return false;
        List<HtmlNode> pathA = path(a);
        List<HtmlNode> pathB = path(b);
        int i = 0;
        while (i < pathA.size() && i < pathB.size() && pathA.get(i) == pathB.get(i)) i++;
        if (i == 0 || i >= pathA.size() || i >= pathB.size()) return false;
        HtmlNode parent = pathA.get(i - 1);
        return parent.children.indexOf(pathA.get(i)) > parent.children.indexOf(pathB.get(i));
    }

    private static List<HtmlNode> path(HtmlNode node) {
        List<HtmlNode> out = new ArrayList<>();
        for (HtmlNode n = node; n != null; n = n.parent) out.addFirst(n);
        return out;
    }
}
