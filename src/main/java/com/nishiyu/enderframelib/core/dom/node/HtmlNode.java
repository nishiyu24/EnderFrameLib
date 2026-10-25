package com.nishiyu.enderframelib.core.dom.node;

import com.nishiyu.enderframelib.core.dom.*;
import com.nishiyu.enderframelib.core.dom.node.*;
import com.nishiyu.enderframelib.core.dom.parser.*;
import com.nishiyu.enderframelib.core.dom.parser.*;

import com.nishiyu.enderframelib.core.dom.node.HtmlDocument.HtmlDocumentFragment;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument.HtmlComment;
import com.nishiyu.enderframelib.api.dom.CSSStyleDeclaration;
import com.nishiyu.enderframelib.api.dom.DOMException;
import com.nishiyu.enderframelib.api.dom.DOMCollections.DOMTokenList;
import com.nishiyu.enderframelib.api.dom.Document;
import com.nishiyu.enderframelib.api.dom.Element;
import com.nishiyu.enderframelib.api.dom.DOMCollections.HTMLCollection;
import com.nishiyu.enderframelib.api.dom.Node;
import com.nishiyu.enderframelib.api.dom.DOMCollections.NodeList;
import com.nishiyu.enderframelib.core.css.SelectorMatcher.SelectorEngine;
import com.nishiyu.enderframelib.api.event.AbortSignal;
import com.nishiyu.enderframelib.api.event.AddEventListenerOptions;
import com.nishiyu.enderframelib.api.event.Event;
import com.nishiyu.enderframelib.api.event.EventListener;
import com.nishiyu.enderframelib.api.event.EventListenerOptions;
import com.nishiyu.enderframelib.core.event.EventDispatcher;
import com.nishiyu.enderframelib.core.event.EventDispatcher.EventListenerEntry;
import com.nishiyu.enderframelib.core.event.StandardDefaultActions;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * DOM ツリーの基礎となるノードクラス。
 *
 * <p>WHATWG DOM Standard の {@link Node} インターフェースを実装し、
 * 事前検証（Pre-insertion validation）を伴うツリー操作（appendChild, insertBefore, removeChild 等）
 * および ChildNode ミックスイン（remove, before, after, replaceWith）を提供する。</p>
 */
@org.jetbrains.annotations.ApiStatus.Internal
public class HtmlNode implements Node {

    private static final Pattern WHITESPACE_SPLIT = Pattern.compile("\\s+");

    public final NodeType nodeType;
    public final String tag;

    // attrs を真のデータソース（Single Source of Truth）とする。id の増減はドキュメントの索引に反映される
    public final Map<String, String> attrs = new AttributeMap();

    /** 子の増減は、接続中のドキュメントの id 索引に反映される（{@code children} を直接触る経路も含む）。 */
    public final List<HtmlNode> children = new ChildList();
    public String text = "";
    public HtmlNode parent = null;
    public HtmlDocument ownerDocument = null;
    public boolean isPseudoNode = false;
    private final List<EventListenerEntry> eventListeners = new CopyOnWriteArrayList<>();

    // Element ノード用コンストラクタ（"#text" の場合は NodeType.TEXT として扱う）
    public HtmlNode(String tag) {
        this("#text".equals(tag) ? NodeType.TEXT : ("#comment".equals(tag) ? NodeType.COMMENT : NodeType.ELEMENT), tag);
    }

    // 指定タイプ用コンストラクタ
    public HtmlNode(NodeType type, String tag) {
        this.nodeType = type;
        this.tag = tag != null ? tag : "";
    }

    // ==========================================
    // Node インターフェース基本メソッド
    // ==========================================

    @Override
    public short getNodeType() {
        return switch (nodeType) {
            case ELEMENT -> ELEMENT_NODE;
            case TEXT -> TEXT_NODE;
            case COMMENT -> COMMENT_NODE;
            case ROOT -> DOCUMENT_NODE;
            case DOCUMENT_FRAGMENT -> DOCUMENT_FRAGMENT_NODE;
        };
    }

    @Override
    public String getNodeName() {
        return switch (nodeType) {
            case ELEMENT -> tag;
            case TEXT -> "#text";
            case COMMENT -> "#comment";
            case ROOT -> "#document";
            case DOCUMENT_FRAGMENT -> "#document-fragment";
        };
    }

    @Nullable
    @Override
    public Document getOwnerDocument() {
        if (this instanceof Document) return null;
        if (ownerDocument != null) return ownerDocument;
        for (HtmlNode cur = parent; cur != null; cur = cur.parent) {
            if (cur instanceof Document doc) return doc;
            if (cur.ownerDocument != null) return cur.ownerDocument;
        }
        return null;
    }

    @Nullable
    @Override
    public Node getParentNode() {
        return parent;
    }

    @Nullable
    @Override
    public Element getParentElement() {
        return parent instanceof Element el ? el : null;
    }

    @Override
    public NodeList getChildNodes() {
        return new HtmlCollections.LiveChildNodeList(() -> children);
    }

    @Nullable
    @Override
    public Node getFirstChild() {
        return children.isEmpty() ? null : children.get(0);
    }

    @Nullable
    @Override
    public Node getLastChild() {
        return children.isEmpty() ? null : children.get(children.size() - 1);
    }

    @Nullable
    @Override
    public Node getPreviousSibling() {
        if (parent == null) return null;
        int idx = parent.children.indexOf(this);
        return idx > 0 ? parent.children.get(idx - 1) : null;
    }

    @Nullable
    @Override
    public Node getNextSibling() {
        if (parent == null) return null;
        int idx = parent.children.indexOf(this);
        return (idx >= 0 && idx + 1 < parent.children.size()) ? parent.children.get(idx + 1) : null;
    }

    @Override
    public boolean hasChildNodes() {
        return !children.isEmpty();
    }

    @Override
    public boolean isConnected() {
        for (HtmlNode cur = this; cur != null; cur = cur.parent) {
            if (cur instanceof Document || cur.nodeType == NodeType.ROOT) return true;
        }
        return false;
    }

    @Nullable
    @Override
    public String getTextContent() {
        if (nodeType == NodeType.TEXT || nodeType == NodeType.COMMENT) {
            return text;
        }
        if (nodeType == NodeType.ROOT) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (HtmlNode child : children) {
            if (child.nodeType != NodeType.COMMENT) {
                String cText = child.getTextContent();
                if (cText != null) sb.append(cText);
            }
        }
        return sb.toString();
    }

    @Override
    public void setTextContent(@Nullable String textContent) {
        if (nodeType == NodeType.ROOT) {
            return;
        }
        if (nodeType == NodeType.TEXT || nodeType == NodeType.COMMENT) {
            String oldVal = this.text;
            this.text = textContent != null ? textContent : "";
            MutationObserverManager.queueCharacterData(this, oldVal);
            notifyDomChanged(null);
            return;
        }
        // 要素やフラグメントの場合: 既存の子を全削除し、非空なら Text ノードを1つ追加
        List<Node> removed = new ArrayList<>(children);
        for (HtmlNode child : new ArrayList<>(children)) {
            removeChild(child);
        }
        if (textContent != null && !textContent.isEmpty()) {
            HtmlText txtNode = new HtmlText(textContent);
            // 描画要素の id と親子の連鎖（mc-parent）は節の id で引くので、空の id を作らない。
            // 空のままだと同じく空の id の節どうしが取り違えられ、祖先の transform などが掛からない
            if (!getId().isEmpty()) txtNode.setId(getId() + "_txt");
            appendChild(txtNode);
        }
    }

    // ==========================================
    // DOM Standard ツリー操作（事前検証付き）
    // ==========================================

    @Override
    public Node appendChild(Node node) {
        return insertBefore(node, null);
    }

    @Override
    public Node insertBefore(Node node, @Nullable Node child) {
        if (!(node instanceof HtmlNode newChild)) {
            throw new IllegalArgumentException("Node must be an instance of HtmlNode");
        }
        HtmlNode refChild = (HtmlNode) child;

        // 1. 事前検証 (Pre-insertion validation)
        ensureCanInsert(newChild, refChild);

        // DocumentFragment の場合はその子ノード群を順番に挿入
        if (newChild.nodeType == NodeType.DOCUMENT_FRAGMENT) {
            List<HtmlNode> fragChildren = new ArrayList<>(newChild.children);
            for (HtmlNode fragChild : fragChildren) {
                insertBefore(fragChild, refChild);
            }
            return newChild;
        }

        Node prevSibling = (refChild != null) ? refChild.getPreviousSibling() : getLastChild();
        Node nextSibling = refChild;

        // すでに親がある場合は旧親から取り外す
        if (newChild.parent != null) {
            newChild.parent.removeChild(newChild);
        }

        int insertIndex = refChild != null ? children.indexOf(refChild) : children.size();
        if (insertIndex < 0) insertIndex = children.size();

        newChild.parent = this;
        children.add(insertIndex, newChild);
        propagateDocument(newChild, findDocument());

        // MutationObserver 通知
        MutationObserverManager.queueChildList(this, List.of(newChild), Collections.emptyList(), prevSibling, nextSibling);
        notifyChildListChanged(insertIndex);

        return newChild;
    }

    @Override
    public Node removeChild(Node child) {
        if (!(child instanceof HtmlNode targetChild)) {
            throw new IllegalArgumentException("Node must be an instance of HtmlNode");
        }
        if (targetChild.parent != this || !children.contains(targetChild)) {
            throw new DOMException("The node to be removed is not a child of this node.", "NotFoundError");
        }

        Node prevSibling = targetChild.getPreviousSibling();
        Node nextSibling = targetChild.getNextSibling();

        int removedIndex = children.indexOf(targetChild);
        children.remove(targetChild);
        targetChild.parent = null;

        // MutationObserver 通知
        MutationObserverManager.queueChildList(this, Collections.emptyList(), List.of(targetChild), prevSibling, nextSibling);
        notifyChildListChanged(removedIndex);

        return targetChild;
    }

    @Override
    public Node replaceChild(Node node, Node child) {
        if (!(node instanceof HtmlNode newChild) || !(child instanceof HtmlNode oldChild)) {
            throw new IllegalArgumentException("Nodes must be instances of HtmlNode");
        }
        if (oldChild.parent != this || !children.contains(oldChild)) {
            throw new DOMException("The node to be replaced is not a child of this node.", "NotFoundError");
        }

        Node next = oldChild.getNextSibling();
        ensureCanInsert(newChild, oldChild);
        removeChild(oldChild);
        insertBefore(newChild, next);
        return oldChild;
    }

    /**
     * DOM Standard §4.2.3 事前検証アルゴリズム。
     */
    private void ensureCanInsert(HtmlNode newChild, @Nullable HtmlNode refChild) {
        if (this.nodeType != NodeType.ELEMENT && this.nodeType != NodeType.DOCUMENT_FRAGMENT && this.nodeType != NodeType.ROOT) {
            throw new DOMException("This node cannot have children.", "HierarchyRequestError");
        }
        if (newChild == this || newChild.contains(this)) {
            throw new DOMException("The new child element contains the parent.", "HierarchyRequestError");
        }
        if (refChild != null && refChild.parent != this) {
            throw new DOMException("The reference node is not a child of this node.", "NotFoundError");
        }
        if (newChild.nodeType == NodeType.ROOT) {
            throw new DOMException("Cannot insert a Document as a child.", "HierarchyRequestError");
        }

        // Document への挿入規則
        if (this.nodeType == NodeType.ROOT || this instanceof Document) {
            if (newChild.nodeType == NodeType.TEXT) {
                throw new DOMException("Cannot insert Text node directly into Document.", "HierarchyRequestError");
            }
            if (newChild.nodeType == NodeType.ELEMENT) {
                for (HtmlNode c : children) {
                    if (c.nodeType == NodeType.ELEMENT && c != refChild) {
                        throw new DOMException("Document can have at most one Element child.", "HierarchyRequestError");
                    }
                }
            } else if (newChild.nodeType == NodeType.DOCUMENT_FRAGMENT) {
                int elCount = 0;
                for (HtmlNode fc : newChild.children) {
                    if (fc.nodeType == NodeType.TEXT) {
                        throw new DOMException("Cannot insert DocumentFragment containing Text into Document.", "HierarchyRequestError");
                    }
                    if (fc.nodeType == NodeType.ELEMENT) elCount++;
                }
                if (elCount > 1) {
                    throw new DOMException("DocumentFragment has multiple Element children.", "HierarchyRequestError");
                }
                if (elCount == 1) {
                    for (HtmlNode c : children) {
                        if (c.nodeType == NodeType.ELEMENT && c != refChild) {
                            throw new DOMException("Document can have at most one Element child.", "HierarchyRequestError");
                        }
                    }
                }
            }
        }
    }

    /**
     * この節が属する部分木の所有者。祖先へ向かって最初の {@code data-owner} 属性が決め、
     * 無ければ文書の既定、文書に接続していない木は {@link Ownership#CLIENT}。
     */
    public Ownership ownership() {
        for (HtmlNode current = this; current != null; current = current.parent) {
            Ownership declared = current.attrs != null ? Ownership.parse(current.attrs.get(Ownership.ATTRIBUTE)) : null;
            if (declared != null) return declared;
        }
        HtmlDocument document = findDocument();
        return document != null ? document.getDefaultOwnership() : Ownership.CLIENT;
    }

    private HtmlDocument findDocument() {
        if (this instanceof HtmlDocument doc) return doc;
        if (ownerDocument != null) return ownerDocument;
        for (HtmlNode cur = this; cur != null; cur = cur.parent) {
            if (cur instanceof HtmlDocument doc) return doc;
            if (cur.ownerDocument != null) return cur.ownerDocument;
        }
        return null;
    }

    private static void propagateDocument(HtmlNode node, HtmlDocument doc) {
        if (node == null) return;
        node.ownerDocument = doc;
        for (HtmlNode c : node.children) {
            propagateDocument(c, doc);
        }
    }

    /** この節が属する木の根が {@link HtmlDocument} ならそれ。切り離された木では null。 */
    /**
     * DOM の操作（{@code setAttribute}・{@code appendChild}・{@code textContent} など）で、この節が変わったことを
     * つながっている文書に知らせる。文書は対話状態に汚れの印を付け、持ち主のセッションに再描画を求める。
     * サーバーの変異もクライアントのスクリプトも同じ入口を通るので、どちらから変えても同じ無効化になる。
     *
     * @param attributeName 属性の変化ならその名前、子の並び・文字の変化なら null
     */
    void notifyDomChanged(@Nullable String attributeName) {
        HtmlDocument doc = connectedDocument();
        if (doc != null) doc.nodeChanged(this, attributeName);
    }

    /** 子の挿入・削除を知らせる。{@code index} は増減があった子の位置（削除なら削除前の位置）。 */
    private void notifyChildListChanged(int index) {
        HtmlDocument doc = connectedDocument();
        if (doc != null) doc.childListChanged(this, index);
    }

    private void notifyAttributeChanged(String name, @Nullable String oldValue, @Nullable String newValue) {
        HtmlDocument doc = connectedDocument();
        if (doc != null) doc.attributeChanged(this, name, oldValue, newValue);
    }

    private HtmlDocument connectedDocument() {
        HtmlNode top = this;
        while (top.parent != null) top = top.parent;
        return top instanceof HtmlDocument doc ? doc : null;
    }

    /** {@code child} の部分木が、この節の子として加わった。接続中なら id 索引に載せる。 */
    private void childAttached(HtmlNode child) {
        HtmlDocument doc = connectedDocument();
        if (doc != null && child != null) doc.indexSubtree(child);
    }

    /** {@code child} の部分木が、この節の子から外れた。接続中なら id 索引から外す。 */
    private void childDetached(HtmlNode child) {
        HtmlDocument doc = connectedDocument();
        if (doc != null && child != null) doc.unindexSubtree(child);
    }

    /** 自分の {@code id} 属性が変わった。接続中なら id 索引を付け替える。 */
    private void idAttributeChanged(String oldId, String newId) {
        HtmlDocument doc = connectedDocument();
        if (doc != null && this != doc) doc.reindexNode(this, oldId, newId);
    }

    /**
     * 子の並び。直接 {@code add} / {@code remove} する呼び出し（パーサ・バインディング）も、
     * DOM のツリー操作と同じく id 索引へ反映するため、変更の入口をここに集める。
     */
    private final class ChildList extends ArrayList<HtmlNode> {

        @Override
        public boolean add(HtmlNode node) {
            super.add(node);
            childAttached(node);
            return true;
        }

        @Override
        public void add(int index, HtmlNode node) {
            super.add(index, node);
            childAttached(node);
        }

        @Override
        public boolean addAll(Collection<? extends HtmlNode> nodes) {
            List<HtmlNode> added = new ArrayList<>(nodes);
            boolean changed = super.addAll(added);
            added.forEach(HtmlNode.this::childAttached);
            return changed;
        }

        @Override
        public boolean addAll(int index, Collection<? extends HtmlNode> nodes) {
            List<HtmlNode> added = new ArrayList<>(nodes);
            boolean changed = super.addAll(index, added);
            added.forEach(HtmlNode.this::childAttached);
            return changed;
        }

        @Override
        public HtmlNode set(int index, HtmlNode node) {
            HtmlNode replaced = super.set(index, node);
            childDetached(replaced);
            childAttached(node);
            return replaced;
        }

        @Override
        public HtmlNode remove(int index) {
            HtmlNode removed = super.remove(index);
            childDetached(removed);
            return removed;
        }

        @Override
        public boolean remove(Object node) {
            boolean removed = super.remove(node);
            if (removed) childDetached((HtmlNode) node);
            return removed;
        }

        @Override
        public boolean removeIf(Predicate<? super HtmlNode> filter) {
            List<HtmlNode> removed = new ArrayList<>();
            for (HtmlNode node : this) {
                if (filter.test(node)) removed.add(node);
            }
            boolean changed = super.removeIf(removed::contains);
            removed.forEach(HtmlNode.this::childDetached);
            return changed;
        }

        @Override
        public boolean removeAll(Collection<?> nodes) {
            return removeIf(nodes::contains);
        }

        @Override
        public boolean retainAll(Collection<?> nodes) {
            return removeIf(node -> !nodes.contains(node));
        }

        @Override
        public void clear() {
            List<HtmlNode> removed = new ArrayList<>(this);
            super.clear();
            removed.forEach(HtmlNode.this::childDetached);
        }

        @Override
        protected void removeRange(int fromIndex, int toIndex) {
            List<HtmlNode> removed = new ArrayList<>(subList(fromIndex, toIndex));
            super.removeRange(fromIndex, toIndex);
            removed.forEach(HtmlNode.this::childDetached);
        }

        @Override
        public void replaceAll(UnaryOperator<HtmlNode> operator) {
            List<HtmlNode> before = new ArrayList<>(this);
            super.replaceAll(operator);
            before.forEach(HtmlNode.this::childDetached);
            forEach(HtmlNode.this::childAttached);
        }
    }

    /**
     * 属性の表。{@code id} の増減だけを見て id 索引を保つ。{@code put} / {@code remove} 以外の
     * 変更（{@code compute} 系・{@code replace} 系）も、前後の {@code id} を比べて同じ扱いにする。
     */
    private final class AttributeMap extends LinkedHashMap<String, String> {

        private <R> R trackingId(Supplier<R> mutation) {
            String before = super.get("id");
            R result = mutation.get();
            String after = super.get("id");
            if (!Objects.equals(before, after)) idAttributeChanged(before, after);
            return result;
        }

        @Override
        public String put(String key, String value) {
            return trackingId(() -> super.put(key, value));
        }

        @Override
        public String putIfAbsent(String key, String value) {
            return trackingId(() -> super.putIfAbsent(key, value));
        }

        @Override
        public void putAll(Map<? extends String, ? extends String> entries) {
            trackingId(() -> {
                super.putAll(entries);
                return null;
            });
        }

        @Override
        public String remove(Object key) {
            return trackingId(() -> super.remove(key));
        }

        @Override
        public boolean remove(Object key, Object value) {
            return trackingId(() -> super.remove(key, value));
        }

        @Override
        public void clear() {
            trackingId(() -> {
                super.clear();
                return null;
            });
        }

        @Override
        public String replace(String key, String value) {
            return trackingId(() -> super.replace(key, value));
        }

        @Override
        public boolean replace(String key, String oldValue, String newValue) {
            return trackingId(() -> super.replace(key, oldValue, newValue));
        }

        @Override
        public void replaceAll(BiFunction<? super String, ? super String, ? extends String> function) {
            trackingId(() -> {
                super.replaceAll(function);
                return null;
            });
        }

        @Override
        public String computeIfAbsent(String key, Function<? super String, ? extends String> mapping) {
            return trackingId(() -> super.computeIfAbsent(key, mapping));
        }

        @Override
        public String computeIfPresent(String key, BiFunction<? super String, ? super String, ? extends String> remapping) {
            return trackingId(() -> super.computeIfPresent(key, remapping));
        }

        @Override
        public String compute(String key, BiFunction<? super String, ? super String, ? extends String> remapping) {
            return trackingId(() -> super.compute(key, remapping));
        }

        @Override
        public String merge(String key, String value, BiFunction<? super String, ? super String, ? extends String> remapping) {
            return trackingId(() -> super.merge(key, value, remapping));
        }
    }

    // ==========================================
    // 比較・複製・階層判定
    // ==========================================

    @Override
    public boolean contains(@Nullable Node other) {
        if (other == null) return false;
        if (other == this) return true;
        for (Node cur = other; cur != null; cur = cur.getParentNode()) {
            if (cur == this) return true;
        }
        return false;
    }

    @Override
    public short compareDocumentPosition(Node other) {
        if (other == null) return DOCUMENT_POSITION_DISCONNECTED;
        if (this == other) return 0;

        List<Node> thisChain = getAncestorChain(this);
        List<Node> otherChain = getAncestorChain(other);

        if (thisChain.get(0) != otherChain.get(0)) {
            // 異なるツリー
            short order = (short) (System.identityHashCode(this) < System.identityHashCode(other)
                    ? DOCUMENT_POSITION_PRECEDING : DOCUMENT_POSITION_FOLLOWING);
            return (short) (DOCUMENT_POSITION_DISCONNECTED | DOCUMENT_POSITION_IMPLEMENTATION_SPECIFIC | order);
        }

        if (this.contains(other)) {
            return (short) (DOCUMENT_POSITION_CONTAINED_BY | DOCUMENT_POSITION_FOLLOWING);
        }
        if (other.contains(this)) {
            return (short) (DOCUMENT_POSITION_CONTAINS | DOCUMENT_POSITION_PRECEDING);
        }

        // 共通の祖先を見つける
        int i = 0;
        while (i < thisChain.size() && i < otherChain.size() && thisChain.get(i) == otherChain.get(i)) {
            i++;
        }
        Node commonParent = thisChain.get(i - 1);
        Node thisBranch = thisChain.get(i);
        Node otherBranch = otherChain.get(i);

        if (commonParent instanceof HtmlNode parentHtml) {
            int thisIdx = parentHtml.children.indexOf(thisBranch);
            int otherIdx = parentHtml.children.indexOf(otherBranch);
            return thisIdx < otherIdx ? DOCUMENT_POSITION_FOLLOWING : DOCUMENT_POSITION_PRECEDING;
        }
        return DOCUMENT_POSITION_DISCONNECTED;
    }

    private static List<Node> getAncestorChain(Node node) {
        List<Node> chain = new ArrayList<>();
        for (Node cur = node; cur != null; cur = cur.getParentNode()) {
            chain.add(0, cur);
        }
        return chain;
    }

    @Override
    public Node getRootNode() {
        Node cur = this;
        while (cur.getParentNode() != null) {
            cur = cur.getParentNode();
        }
        return cur;
    }

    @Override
    public HtmlNode cloneNode(boolean deep) {
        HtmlNode copy = createNodeInstance();
        copy.attrs.putAll(this.attrs);
        copy.text = this.text;
        copy.isPseudoNode = this.isPseudoNode;
        if (deep) {
            for (HtmlNode child : this.children) {
                HtmlNode childCopy = child.cloneNode(true);
                childCopy.parent = copy;
                copy.children.add(childCopy);
            }
        }
        return copy;
    }

    @Override
    public HtmlNode cloneNode() {
        return cloneNode(false);
    }

    protected HtmlNode createNodeInstance() {
        if (this instanceof HtmlElement) return new HtmlElement(this.tag);
        if (this instanceof HtmlText) return new HtmlText(this.text);
        if (this instanceof HtmlComment) return new HtmlComment(this.text);
        if (this instanceof HtmlDocument) return new HtmlDocument();
        if (this instanceof HtmlDocumentFragment) return new HtmlDocumentFragment();
        return new HtmlNode(this.nodeType, this.tag);
    }

    // ==========================================
    // ChildNode ミックスイン実装
    // ==========================================

    @Override
    public void remove() {
        if (this.parent != null) {
            this.parent.removeChild(this);
        }
    }

    @Override
    public void before(Node... nodes) {
        if (this.parent == null || nodes == null) return;
        for (Node n : nodes) {
            this.parent.insertBefore(n, this);
        }
    }

    @Override
    public void after(Node... nodes) {
        if (this.parent == null || nodes == null) return;
        Node next = this.getNextSibling();
        for (Node n : nodes) {
            this.parent.insertBefore(n, next);
        }
    }

    @Override
    public void replaceWith(Node... nodes) {
        if (this.parent == null) return;
        HtmlNode p = this.parent;
        Node next = this.getNextSibling();
        p.removeChild(this);
        if (nodes != null) {
            for (Node n : nodes) {
                p.insertBefore(n, next);
            }
        }
    }

    // ==========================================
    // 属性・プロパティ・クラス・スタイル互換 API
    // ==========================================

    public String getId() {
        return attrs.getOrDefault("id", "");
    }

    public void setId(@Nullable String id) {
        if (id == null || id.trim().isEmpty()) {
            attrs.remove("id");
        } else {
            attrs.put("id", id.trim());
        }
    }

    public List<String> getClasses() {
        String classStr = attrs.get("class");
        if (classStr == null || classStr.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(WHITESPACE_SPLIT.split(classStr.trim())));
    }

    public boolean hasClass(String className) {
        if (className == null) return false;
        return getClasses().contains(className.trim());
    }

    public void addClass(String... classNames) {
        List<String> currentClasses = getClasses();
        boolean modified = false;
        for (String cn : classNames) {
            if (cn == null) continue;
            for (String part : WHITESPACE_SPLIT.split(cn.trim())) {
                if (!part.isEmpty() && !currentClasses.contains(part)) {
                    currentClasses.add(part);
                    modified = true;
                }
            }
        }
        if (modified) syncClassAttribute(currentClasses);
    }

    public void removeClass(String... classNames) {
        List<String> currentClasses = getClasses();
        boolean modified = false;
        for (String cn : classNames) {
            if (cn == null) continue;
            for (String part : WHITESPACE_SPLIT.split(cn.trim())) {
                if (currentClasses.remove(part)) {
                    modified = true;
                }
            }
        }
        if (modified) syncClassAttribute(currentClasses);
    }

    public boolean toggleClass(String className) {
        if (className == null || className.trim().isEmpty()) return false;
        String clean = className.trim();
        List<String> currentClasses = getClasses();

        if (currentClasses.contains(clean)) {
            currentClasses.remove(clean);
            syncClassAttribute(currentClasses);
            return false;
        } else {
            currentClasses.add(clean);
            syncClassAttribute(currentClasses);
            return true;
        }
    }

    private void syncClassAttribute(List<String> classList) {
        if (classList.isEmpty()) {
            this.removeAttribute("class");
        } else {
            this.setAttribute("class", String.join(" ", classList));
        }
    }

    public String getAttribute(String name) {
        return this.attrs.get(name);
    }

    public void setAttribute(String name, String value) {
        if (name == null) return;
        String oldVal = this.attrs.get(name);
        String newVal = value != null ? value : "";
        this.attrs.put(name, newVal);

        if (this instanceof HtmlElement el && "style".equals(name)) {
            el.syncStyleFromAttribute(newVal);
        }

        MutationObserverManager.queueAttributes(this, name, oldVal);
        notifyAttributeChanged(name, oldVal, newVal);
    }

    public void removeAttribute(String name) {
        if (name == null || !this.attrs.containsKey(name)) return;
        String oldVal = this.attrs.remove(name);

        if (this instanceof HtmlElement el && "style".equals(name)) {
            el.syncStyleFromAttribute(null);
        }

        MutationObserverManager.queueAttributes(this, name, oldVal);
        notifyAttributeChanged(name, oldVal, null);
    }

    public boolean toggleAttribute(String name) {
        return toggleAttribute(name, !hasAttribute(name));
    }

    public boolean toggleAttribute(String name, boolean force) {
        if (name == null) return false;
        if (force) {
            setAttribute(name, "");
            return true;
        } else {
            removeAttribute(name);
            return false;
        }
    }

    public boolean hasAttribute(String name) {
        return name != null && this.attrs.containsKey(name);
    }

    public boolean hasAttributes() {
        return !this.attrs.isEmpty();
    }

    public List<String> getAttributeNames() {
        return new ArrayList<>(this.attrs.keySet());
    }

    public Map<String, String> getDataset() {
        Map<String, String> dataset = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : attrs.entrySet()) {
            if (entry.getKey().startsWith("data-")) {
                dataset.put(entry.getKey(), entry.getValue());
            }
        }
        return dataset;
    }

    public void setStyleProperty(String property, String value) {
        if (property == null || property.trim().isEmpty()) return;
        Map<String, String> styleMap = parseInlineStyleMap();
        if (value == null || value.trim().isEmpty()) {
            styleMap.remove(property.trim().toLowerCase());
        } else {
            styleMap.put(property.trim().toLowerCase(), value.trim());
        }
        saveInlineStyleMap(styleMap);
    }

    public void removeStyleProperty(String property) {
        setStyleProperty(property, null);
    }

    /** インライン {@code style} 属性に宣言された値を返す。未宣言なら {@code null}。 */
    public @Nullable String getStyleProperty(String property) {
        if (property == null || property.trim().isEmpty()) return null;
        return parseInlineStyleMap().get(property.trim().toLowerCase());
    }

    private Map<String, String> parseInlineStyleMap() {
        Map<String, String> map = new LinkedHashMap<>();
        String raw = this.attrs.getOrDefault("style", "");
        if (!raw.isEmpty()) {
            for (String decl : raw.split(";")) {
                int colon = decl.indexOf(':');
                if (colon > 0) {
                    String k = decl.substring(0, colon).trim().toLowerCase();
                    String v = decl.substring(colon + 1).trim();
                    if (!k.isEmpty()) map.put(k, v);
                }
            }
        }
        return map;
    }

    private void saveInlineStyleMap(Map<String, String> map) {
        if (map.isEmpty()) {
            this.removeAttribute("style");
        } else {
            String styleStr = map.entrySet().stream()
                    .map(e -> e.getKey() + ": " + e.getValue() + ";")
                    .collect(Collectors.joining(" "));
            this.setAttribute("style", styleStr);
        }
    }

    // ==========================================
    // 検索・走査 API
    // ==========================================

    public HtmlElement getElementById(String targetId) {
        return lookupById(targetId, true) instanceof HtmlElement element ? element : null;
    }

    /**
     * {@code id} 属性が一致する節を、種類を問わず引く。テキスト・擬似要素・匿名ボックスの節は
     * 描画とネットワークが使う合成 ID（{@code gen_N_txt} など）を持ち、{@link #getElementById} には現れない。
     * ドキュメントに接続していれば索引から引き、切り離された木では部分木を走査する。
     */
    public HtmlNode getNodeById(String targetId) {
        return lookupById(targetId, false);
    }

    private HtmlNode lookupById(String targetId, boolean elementOnly) {
        if (targetId == null || targetId.isEmpty()) return null;
        if (targetId.equals(getId()) && (!elementOnly || this instanceof Element)) return this;
        HtmlDocument doc = connectedDocument();
        return doc != null ? doc.lookupIndexed(targetId, elementOnly, this) : scanById(this, targetId, elementOnly);
    }

    /** 切り離された木の線形走査。文書順で最初に一致する節を返す。 */
    private static HtmlNode scanById(HtmlNode scope, String targetId, boolean elementOnly) {
        for (HtmlNode child : scope.children) {
            if (targetId.equals(child.getId()) && (!elementOnly || child instanceof Element)) return child;
            HtmlNode found = scanById(child, targetId, elementOnly);
            if (found != null) return found;
        }
        return null;
    }

    public HtmlElement querySelector(String selector) {
        if (selector == null || selector.trim().isEmpty()) return null;
        HtmlNode found = SelectorEngine.querySelector(this, selector);
        return found instanceof HtmlElement he ? he : null;
    }

    public NodeList querySelectorAll(String selector) {
        if (selector == null || selector.trim().isEmpty()) {
            return new HtmlCollections.StaticNodeList(Collections.emptyList());
        }
        return new HtmlCollections.StaticNodeList(SelectorEngine.querySelectorAll(this, selector));
    }

    // ==========================================
    // innerHTML / outerHTML
    // ==========================================

    public void setInnerHTML(String html) {
        DomUtils.setInnerHTML(this, html);
        notifyDomChanged(null);
    }

    public String getInnerHTML() {
        return DomUtils.getInnerHTML(this);
    }

    public String getOuterHTML() {
        return DomUtils.getOuterHTML(this);
    }

    public void prependChild(HtmlNode child) {
        if (child == null) return;
        insertBefore(child, getFirstChild());
    }

    // ==========================================
    // EventTarget インターフェース実装
    // ==========================================

    @Override
    public void addEventListener(String type, EventListener listener, AddEventListenerOptions options) {
        if (type == null || type.isEmpty() || listener == null) return;
        boolean capture = options != null && options.isCapture();
        boolean once = options != null && options.isOnce();
        boolean passive = options != null && options.isPassive();
        AbortSignal signal = options != null ? options.getSignal() : null;

        if (signal != null && signal.isAborted()) {
            return;
        }

        // 同一 (type, listener, capture) の重複登録を防止 (§2.7)
        for (EventListenerEntry existing : eventListeners) {
            if (existing.type().equalsIgnoreCase(type)
                    && Objects.equals(existing.listener(), listener)
                    && existing.capture() == capture) {
                return;
            }
        }

        EventListenerEntry entry = new EventListenerEntry(type, listener, capture, once, passive, signal);
        eventListeners.add(entry);

        if (signal != null) {
            signal.addEventListener("abort", ev -> removeEventListener(type, listener, options));
        }
    }

    @Override
    public void removeEventListener(String type, EventListener listener, EventListenerOptions options) {
        if (type == null || type.isEmpty() || listener == null) return;
        boolean capture = options != null && options.isCapture();
        eventListeners.removeIf(entry ->
                entry.type().equalsIgnoreCase(type)
                        && Objects.equals(entry.listener(), listener)
                        && entry.capture() == capture
        );
    }

    @Override
    public boolean dispatchEvent(Event event) {
        return dispatchEvent(event, ev -> StandardDefaultActions.execute(ev, null));
    }

    /**
     * 既定動作を {@code defaultActions} に差し替えて配送する。リンク遷移やフォーム送信のように
     * 呼び出し側の文脈（画面・セッション）が要る既定動作を、同じ伝播経路で走らせるための入口。
     *
     * <p>{@code on*} 属性のアクション名と {@link EventForwardDeclaration} の宣言は、文書の
     * {@link HtmlDocument.InlineActionSink} へ渡る。ただし渡るのは伝播がすべて終わってからで、
     * どこかのリスナーが {@code preventDefault} していれば渡らない（クライアントのリスナーが先に走り、
     * 取り消されなければ文書の外へ出す）。</p>
     */
    public boolean dispatchEvent(Event event, EventDispatcher.DefaultActionHandler defaultActions) {
        HtmlDocument document = findDocument();
        HtmlDocument.InlineActionSink sink = document != null ? document.inlineActionSink() : null;
        if (sink == null) {
            return EventDispatcher.dispatchEvent(this, event, null, defaultActions);
        }

        List<Runnable> deferredOutbound = new ArrayList<>();
        boolean notPrevented = EventDispatcher.dispatchEvent(
                this,
                event,
                (node, action) -> deferredOutbound.add(() -> sink.run(node, event, action)),
                (node, declaration) -> deferredOutbound.add(() -> sink.forward(node, event, declaration)),
                ev -> {
                    deferredOutbound.forEach(Runnable::run);
                    if (defaultActions != null) defaultActions.executeDefaultAction(ev);
                });
        return notPrevented;
    }

    public List<EventListenerEntry> getEventListenersSnapshot() {
        return new ArrayList<>(eventListeners);
    }
}