package com.nishiyu.enderframelib.core.dom.node;

import com.nishiyu.enderframelib.core.dom.node.HtmlDocument.HtmlDocumentFragment;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument.HtmlComment;
import com.nishiyu.enderframelib.api.dom.Document;
import com.nishiyu.enderframelib.api.dom.Element;
import com.nishiyu.enderframelib.api.dom.Node;
import com.nishiyu.enderframelib.api.dom.DOMCollections.NodeList;
import com.nishiyu.enderframelib.api.event.AddEventListenerOptions;
import com.nishiyu.enderframelib.api.event.Event;
import com.nishiyu.enderframelib.api.event.EventListener;
import com.nishiyu.enderframelib.api.event.EventListenerOptions;
import com.nishiyu.enderframelib.core.dom.node.EventDispatcher.EventListenerEntry;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * DOM ツリーの基礎となるノードクラス。
 *
 * <p>WHATWG DOM Standard の {@link Node} インターフェースを実装し、
 * 事前検証（Pre-insertion validation）を伴うツリー操作（appendChild, insertBefore, removeChild 等）
 * および ChildNode ミックスイン（remove, before, after, replaceWith）を提供する。</p>
 *
 * <p>木の形の正本は子の並び（{@link #children}）と {@link #parent} だけ。節に付く他の情報は役割ごとに分けた（M5-1）:
 * 番号は {@link NodeNumbering}（空間は {@link NodeIds}）、セレクタの足切りは {@link SelectorIndex}、
 * 属性の上の便利な操作は {@link NodeAttributes}、イベントは {@link NodeEvents}、木の変更（挿入・削除・ChildNode）・文書順・複製は {@link TreeOps}。
 * 節の欄（木の形・番号・鍵・セレクタの足切り・属性の版）と、その手入れ（文書への通知・子の付け外し）は上位の {@link NodeState} にある
 * （M5-3a）。このクラスは DOM の API の入口。</p>
 */
@org.jetbrains.annotations.ApiStatus.Internal
public class HtmlNode extends NodeState implements Node {

    /** 派生鍵の接頭辞。作者の id にまず現れない文字で始める。 */
    public static final char DERIVED_KEY_PREFIX = '~';
    /** テキスト節の派生鍵の接頭辞（{@code ~t番号}）。描画では文字の節だけ位置を持たないので、鍵から区別できるようにする。 */
    public static final String DERIVED_TEXT_KEY_PREFIX = "~t";

    // Element ノード用コンストラクタ（"#text" の場合は NodeType.TEXT として扱う）
    public HtmlNode(String tag) {
        this("#text".equals(tag) ? NodeType.TEXT : ("#comment".equals(tag) ? NodeType.COMMENT : NodeType.ELEMENT), tag);
    }

    // 指定タイプ用コンストラクタ
    public HtmlNode(NodeType type, String tag) {
        super(type, tag);
        SelectorIndex.init(this);
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
        return NodeText.textContent(this);
    }

    @Override
    public void setTextContent(@Nullable String textContent) {
        NodeText.setTextContent(this, textContent);
    }

    /**
     * {@link #setTextContent} と同じ結果の文字にするが、要素の子が空でない Text 1 つだけのときは、その Text の文字を
     * 書き換えるだけにする（Text 節を作り直さない。{@link NodeText#updateTextContent}）。
     */
    public void updateTextContent(@Nullable String textContent) {
        NodeText.updateTextContent(this, textContent);
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
        return TreeOps.insertBefore(this, node, child);
    }

    @Override
    public Node removeChild(Node child) {
        return TreeOps.removeChild(this, child);
    }

    @Override
    public Node replaceChild(Node node, Node child) {
        return TreeOps.replaceChild(this, node, child);
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
        return TreeOps.compareDocumentPosition(this, other);
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
        return TreeOps.cloneNode(this, deep);
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
        if (parent != null) parent.removeChild(this);
    }

    @Override
    public void before(Node... nodes) {
        TreeOps.before(this, nodes);
    }

    @Override
    public void after(Node... nodes) {
        TreeOps.after(this, nodes);
    }

    @Override
    public void replaceWith(Node... nodes) {
        TreeOps.replaceWith(this, nodes);
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
            attrsForWrite().put("id", id.trim());
        }
    }

    public List<String> getClasses() {
        return NodeAttributes.classes(this);
    }

    public boolean hasClass(String className) {
        return SelectorIndex.hasClass(this, className);
    }

    public void addClass(String... classNames) {
        NodeAttributes.addClass(this, classNames);
    }

    public void removeClass(String... classNames) {
        NodeAttributes.removeClass(this, classNames);
    }

    public boolean toggleClass(String className) {
        return NodeAttributes.toggleClass(this, className);
    }

    public String getAttribute(String name) {
        return this.attrs.get(name);
    }

    public void setAttribute(String name, String value) {
        NodeAttributes.set(this, name, value);
    }

    public void removeAttribute(String name) {
        NodeAttributes.remove(this, name);
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
        return NodeAttributes.dataset(this);
    }

    public void setStyleProperty(String property, String value) {
        NodeAttributes.setStyleProperty(this, property, value);
    }

    public void removeStyleProperty(String property) {
        setStyleProperty(property, null);
    }

    /** インライン {@code style} 属性に宣言された値を返す。未宣言なら {@code null}。 */
    public @Nullable String getStyleProperty(String property) {
        return NodeAttributes.styleProperty(this, property);
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
        HtmlNode derived = NodeNumbering.resolveDerivedKey(this, targetId);
        if (derived != null && (!elementOnly || derived instanceof Element) && this.contains(derived)) return derived;
        HtmlDocument doc = connectedDocument();
        return doc != null ? doc.lookupIndexed(targetId, elementOnly, this) : TreeOps.scanById(this, targetId, elementOnly);
    }

    public HtmlElement querySelector(String selector) {
        if (selector == null || selector.trim().isEmpty()) return null;
        HtmlNode found = NodeServices.get().querySelector(this, selector);
        return found instanceof HtmlElement he ? he : null;
    }

    public NodeList querySelectorAll(String selector) {
        if (selector == null || selector.trim().isEmpty()) {
            return new HtmlCollections.StaticNodeList(Collections.emptyList());
        }
        return new HtmlCollections.StaticNodeList(NodeServices.get().querySelectorAll(this, selector));
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
        NodeEvents.add(this, type, listener, options);
    }

    @Override
    public void removeEventListener(String type, EventListener listener, EventListenerOptions options) {
        NodeEvents.remove(this, type, listener, options);
    }

    @Override
    public boolean dispatchEvent(Event event) {
        return dispatchEvent(event, ev -> StandardDefaultActions.execute(ev, null));
    }

    /**
     * 既定動作を {@code defaultActions} に差し替えて配送する。リンク遷移やフォーム送信のように
     * 呼び出し側の文脈（画面・セッション）が要る既定動作を、同じ伝播経路で走らせるための入口（{@link NodeEvents#dispatch}）。
     */
    public boolean dispatchEvent(Event event, EventDispatcher.DefaultActionHandler defaultActions) {
        return NodeEvents.dispatch(this, findDocument(), event, defaultActions);
    }

    public List<EventListenerEntry> getEventListenersSnapshot() {
        return NodeEvents.snapshot(this);
    }
}
