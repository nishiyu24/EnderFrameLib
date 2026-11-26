package com.nishiyu.enderframelib.core.dom.node;

import com.nishiyu.enderframelib.api.dom.DOMException;
import com.nishiyu.enderframelib.api.dom.Document;
import com.nishiyu.enderframelib.api.dom.Node;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument.HtmlDocumentFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * 木の変更と形の規則（挿入・削除・置き換えと事前検証・ChildNode ミックスイン・文書順の比較・複製）。
 * {@link HtmlNode} から分けた（M5-1）。子の並びへの反映（id 索引・番号・セレクタの索引・位置）は {@link ChildList} が受け持つ。
 */
final class TreeOps {
    private TreeOps() {}

    static Node insertBefore(HtmlNode parent, Node node, Node child) {
        if (!(node instanceof HtmlNode newChild)) {
            throw new IllegalArgumentException("Node must be an instance of HtmlNode");
        }
        HtmlNode refChild = (HtmlNode) child;
        ensureCanInsert(parent, newChild, refChild);

        // DocumentFragment の場合はその子ノード群を順番に挿入
        if (newChild.nodeType == NodeType.DOCUMENT_FRAGMENT) {
            for (HtmlNode fragChild : new ArrayList<>(newChild.children)) parent.insertBefore(fragChild, refChild);
            return newChild;
        }

        Node prevSibling = refChild != null ? refChild.getPreviousSibling() : parent.getLastChild();
        // すでに親がある場合は旧親から取り外す（挿入先にも番号の空間があるなら番号と状態を保つ）
        if (newChild.parent != null) {
            if (NodeNumbering.treeIds(parent, false) != null && newChild.hasNodeId()) newChild.movingInTree = true;
            try {
                newChild.parent.removeChild(newChild);
            } finally {
                newChild.movingInTree = false;
            }
        }

        int insertIndex = refChild != null ? parent.children.indexOf(refChild) : parent.children.size();
        if (insertIndex < 0) insertIndex = parent.children.size();
        newChild.parent = parent;
        parent.children.add(insertIndex, newChild);
        propagateDocument(newChild, parent.findDocument());
        if (parent.ownerDocument != null) parent.ownerDocument.rangesNodeInserted(parent, insertIndex, 1);

        MutationObserverManager.queueChildList(parent, List.of(newChild), List.of(), prevSibling, refChild);
        parent.notifyChildListChanged(insertIndex);
        return newChild;
    }

    static Node removeChild(HtmlNode parent, Node child) {
        if (!(child instanceof HtmlNode target)) {
            throw new IllegalArgumentException("Node must be an instance of HtmlNode");
        }
        int removedIndex = target.parent == parent ? parent.children.indexOf(target) : -1;
        if (removedIndex < 0) {
            throw new DOMException("The node to be removed is not a child of this node.", "NotFoundError");
        }
        Node prevSibling = target.getPreviousSibling();
        Node nextSibling = target.getNextSibling();
        if (parent.ownerDocument != null) parent.ownerDocument.rangesNodeRemoving(target, parent, removedIndex);
        parent.children.remove(removedIndex);
        target.parent = null;

        MutationObserverManager.queueChildList(parent, List.of(), List.of(target), prevSibling, nextSibling);
        parent.notifyChildListChanged(removedIndex);
        return target;
    }

    static Node replaceChild(HtmlNode parent, Node node, Node child) {
        if (!(node instanceof HtmlNode newChild) || !(child instanceof HtmlNode oldChild)) {
            throw new IllegalArgumentException("Nodes must be instances of HtmlNode");
        }
        if (oldChild.parent != parent || !parent.children.contains(oldChild)) {
            throw new DOMException("The node to be replaced is not a child of this node.", "NotFoundError");
        }
        Node next = oldChild.getNextSibling();
        ensureCanInsert(parent, newChild, oldChild);
        parent.removeChild(oldChild);
        parent.insertBefore(newChild, next);
        return oldChild;
    }

    static void propagateDocument(HtmlNode node, HtmlDocument doc) {
        if (node == null) return;
        node.ownerDocument = doc;
        for (HtmlNode c : node.children) propagateDocument(c, doc);
        if (node instanceof HtmlElement el) {
            HtmlDocumentFragment content = el.peekTemplateContent();
            if (content != null) {
                content.ownerDocument = doc;
                for (HtmlNode c : content.children) propagateDocument(c, doc);
            }
        }
    }

    // ---- ChildNode ミックスイン ----

    static void before(HtmlNode self, Node... nodes) {
        if (self.parent == null || nodes == null) return;
        for (Node n : nodes) self.parent.insertBefore(n, self);
    }

    static void after(HtmlNode self, Node... nodes) {
        if (self.parent == null || nodes == null) return;
        Node next = self.getNextSibling();
        for (Node n : nodes) self.parent.insertBefore(n, next);
    }

    static void replaceWith(HtmlNode self, Node... nodes) {
        if (self.parent == null) return;
        HtmlNode p = self.parent;
        Node next = self.getNextSibling();
        p.removeChild(self);
        if (nodes != null) {
            for (Node n : nodes) p.insertBefore(n, next);
        }
    }

    /** DOM Standard §4.2.3 事前検証アルゴリズム。 */
    static void ensureCanInsert(HtmlNode parent, HtmlNode newChild, HtmlNode refChild) {
        if (parent.nodeType != NodeType.ELEMENT && parent.nodeType != NodeType.DOCUMENT_FRAGMENT
                && parent.nodeType != NodeType.ROOT) {
            throw new DOMException("This node cannot have children.", "HierarchyRequestError");
        }
        if (newChild == parent || newChild.contains(parent)) {
            throw new DOMException("The new child element contains the parent.", "HierarchyRequestError");
        }
        if (refChild != null && refChild.parent != parent) {
            throw new DOMException("The reference node is not a child of this node.", "NotFoundError");
        }
        if (newChild.nodeType == NodeType.ROOT) {
            throw new DOMException("Cannot insert a Document as a child.", "HierarchyRequestError");
        }
        if (parent.nodeType != NodeType.ROOT && !(parent instanceof Document)) return;

        // Document への挿入規則
        if (newChild.nodeType == NodeType.TEXT) {
            throw new DOMException("Cannot insert Text node directly into Document.", "HierarchyRequestError");
        }
        int elements = 0;
        if (newChild.nodeType == NodeType.ELEMENT) {
            elements = 1;
        } else if (newChild.nodeType == NodeType.DOCUMENT_FRAGMENT) {
            for (HtmlNode fc : newChild.children) {
                if (fc.nodeType == NodeType.TEXT) {
                    throw new DOMException("Cannot insert DocumentFragment containing Text into Document.", "HierarchyRequestError");
                }
                if (fc.nodeType == NodeType.ELEMENT) elements++;
            }
            if (elements > 1) {
                throw new DOMException("DocumentFragment has multiple Element children.", "HierarchyRequestError");
            }
        }
        if (elements == 1) {
            for (HtmlNode c : parent.children) {
                if (c.nodeType == NodeType.ELEMENT && c != refChild) {
                    throw new DOMException("Document can have at most one Element child.", "HierarchyRequestError");
                }
            }
        }
    }

    static short compareDocumentPosition(HtmlNode self, Node other) {
        if (other == null) return Node.DOCUMENT_POSITION_DISCONNECTED;
        if (self == other) return 0;

        List<Node> thisChain = ancestorChain(self);
        List<Node> otherChain = ancestorChain(other);
        if (thisChain.get(0) != otherChain.get(0)) {
            // 異なるツリー
            short order = (short) (System.identityHashCode(self) < System.identityHashCode(other)
                    ? Node.DOCUMENT_POSITION_PRECEDING : Node.DOCUMENT_POSITION_FOLLOWING);
            return (short) (Node.DOCUMENT_POSITION_DISCONNECTED | Node.DOCUMENT_POSITION_IMPLEMENTATION_SPECIFIC | order);
        }
        if (self.contains(other)) {
            return (short) (Node.DOCUMENT_POSITION_CONTAINED_BY | Node.DOCUMENT_POSITION_FOLLOWING);
        }
        if (other.contains(self)) {
            return (short) (Node.DOCUMENT_POSITION_CONTAINS | Node.DOCUMENT_POSITION_PRECEDING);
        }
        // 共通の祖先を見つける
        int i = 0;
        while (i < thisChain.size() && i < otherChain.size() && thisChain.get(i) == otherChain.get(i)) i++;
        Node commonParent = thisChain.get(i - 1);
        if (commonParent instanceof HtmlNode parentHtml) {
            int thisIdx = parentHtml.children.indexOf(thisChain.get(i));
            int otherIdx = parentHtml.children.indexOf(otherChain.get(i));
            return thisIdx < otherIdx ? Node.DOCUMENT_POSITION_FOLLOWING : Node.DOCUMENT_POSITION_PRECEDING;
        }
        return Node.DOCUMENT_POSITION_DISCONNECTED;
    }

    private static List<Node> ancestorChain(Node node) {
        List<Node> chain = new ArrayList<>();
        for (Node cur = node; cur != null; cur = cur.getParentNode()) chain.add(cur);
        java.util.Collections.reverse(chain);
        return chain;
    }

    /** 切り離された木の id の線形走査。文書順で最初に一致する節を返す。 */
    static HtmlNode scanById(HtmlNode scope, String targetId, boolean elementOnly) {
        for (HtmlNode child : scope.children) {
            if (targetId.equals(child.getId()) && (!elementOnly || child instanceof com.nishiyu.enderframelib.api.dom.Element)) {
                return child;
            }
            HtmlNode found = scanById(child, targetId, elementOnly);
            if (found != null) return found;
        }
        return null;
    }

    static HtmlNode cloneNode(HtmlNode source, boolean deep) {
        HtmlNode copy = source.createNodeInstance();
        if (!source.attrs.isEmpty()) copy.attrsForWrite().putAll(source.attrs);
        copy.text = source.text;
        copy.isPseudoNode = source.isPseudoNode;
        if (!deep) return copy;
        cloneChildren(source, copy);
        // template の中身は子ではなく content にある
        if (source instanceof HtmlElement el && el.isTemplateElement()) {
            HtmlDocumentFragment content = el.peekTemplateContent();
            if (content != null) cloneChildren(content, ((HtmlElement) copy).getContent());
        }
        return copy;
    }

    private static void cloneChildren(HtmlNode from, HtmlNode to) {
        for (HtmlNode child : from.children) {
            HtmlNode childCopy = child.cloneNode(true);
            childCopy.parent = to;
            to.children.add(childCopy);
        }
    }
}
