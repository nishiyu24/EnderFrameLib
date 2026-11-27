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
 * 属性の上の便利な操作は {@link NodeAttributes}、イベントは {@link NodeEvents}、木の変更（挿入・削除・ChildNode）・文書順・複製は {@link TreeOps}。</p>
 */
@org.jetbrains.annotations.ApiStatus.Internal
public class HtmlNode implements Node {

    public final NodeType nodeType;
    public final String tag;

    /**
     * attrs を真のデータソース（Single Source of Truth）とする。id の増減はドキュメントの索引に反映される。
     * テキスト・コメントの節は子を持てず、属性もほぼ持たない（鍵の {@code id} だけ）ので、共有の空の不変の表を指し、
     * 最初に書くときに作る（A16②: 節ごとに表 2 つ・64 バイトを確保していた）。書き込みは {@link #attrsForWrite()} を通す
     * （共有の空の表へ直接 {@code put} すると {@link UnsupportedOperationException}）。読み取りは従来どおりこの欄。
     */
    public Map<String, String> attrs;

    /** 書き込める属性の表（テキスト・コメントの節はここで初めて作る）。 */
    public Map<String, String> attrsForWrite() {
        Map<String, String> a = attrs;
        if (a == NO_ATTRS) attrs = a = new AttributeMap(this);
        return a;
    }

    private static final Map<String, String> NO_ATTRS = Collections.emptyMap();
    private static final List<HtmlNode> NO_CHILDREN = Collections.emptyList();

    /** {@code on*} イベント属性を 1 つでも持つか（走査時の反復子確保を避けるための O(attrs) 無確保判定）。 */
    public boolean hasEventAttributes() {
        return attrs instanceof AttributeMap map && map.hasEventAttribute();
    }

    /** 子の増減は、接続中のドキュメントの id 索引に反映される（{@code children} を直接触る経路も含む）。 */
    public final List<HtmlNode> children;
    public String text = "";
    public HtmlNode parent = null;
    public HtmlDocument ownerDocument = null;
    public boolean isPseudoNode = false;

    /**
     * まれにしか使わない欄（A16②: 節 1 つの大きさを 112 → 80 バイトにするため、使うときだけ作る）。
     * イベントリスナー・木の根の番号の空間・スクロール内容と part の鍵のキャッシュ・インライン style の解析結果。
     */
    static final class Rare {
        List<EventListenerEntry> eventListeners;
        NodeIds rootIds;
        String scrollContentKeyBase, scrollContentKeyCache;
        String partKeyBase;
        String[] partKeyCache;
        Object inlineStyleCache;
    }

    private Rare rare;

    Rare rare() {
        Rare r = rare;
        return r != null ? r : (rare = new Rare());
    }

    /** インライン {@code style} 属性の解析済みキャッシュ（{@link #attrVersion()} に紐づく）。無ければ null。 */
    public Object inlineStyleCache() {
        Rare r = rare;
        return r != null ? r.inlineStyleCache : null;
    }

    public void setInlineStyleCache(Object cache) {
        if (cache == null && rare == null) return;
        rare().inlineStyleCache = cache;
    }

    // ---- 番号（NodeNumbering が振る） ----
    /** 木の番号の空間上の番号。まだ振られていなければ -1。 */
    int nodeId = -1;
    /** {@link #nodeId} を振った番号の空間。 */
    NodeIds ids;
    /** 明示 id の無い節の鍵（{@code ~番号}）。番号を振り直したら捨てる。 */
    String derivedKey;
    /** 子の並びに載っている節か。疑似要素・匿名等の仮想節は親の子の並びに載らず番号を持たない。 */
    boolean listed;
    /** 同じ木の中で移動している最中（外れても番号を手放さない）。 */
    boolean movingInTree;
    /**
     * 親の子の並びの中の位置（載っていなければ -1）。{@link ChildList} が変更のたびに保つので、前後の兄弟・挿入位置を
     * O(1) で引ける（以前は {@code DocumentArena} のオフヒープの兄弟の列に写して二重に持っていた）。
     */
    int siblingIndex = -1;

    // ---- セレクタの足切り（SelectorIndex が保つ） ----
    /** class 属性の語の atom。 */
    int[] classAtoms;
    /** タグ・id・class の Bloom。 */
    long selfBloom;
    /** 自分と子の並びに載った子孫の {@link #selfBloom} の和の上位集合。 */
    long subtreeBloom;

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
        this.nodeType = type;
        this.tag = tag != null ? tag : "";
        boolean leaf = type == NodeType.TEXT || type == NodeType.COMMENT;
        this.attrs = leaf ? NO_ATTRS : new AttributeMap(this);
        this.children = leaf ? NO_CHILDREN : new ChildList(this);
        SelectorIndex.init(this);
    }

    /** この節が木の根のときだけ持つ番号の空間（文書は最初の子が付いたとき、断片は鍵を問われたときに作る）。 */
    NodeIds rootIds() {
        Rare r = rare;
        return r != null ? r.rootIds : null;
    }

    NodeIds ensureRootIds() {
        Rare r = rare();
        return r.rootIds != null ? r.rootIds : (r.rootIds = new NodeIds());
    }

    /** イベントリスナーの並び（無ければ {@code create} のときだけ作り、それ以外は null）。 */
    List<EventListenerEntry> eventListeners(boolean create) {
        Rare r = create ? rare() : rare;
        if (r == null) return null;
        if (r.eventListeners == null && create) r.eventListeners = new CopyOnWriteArrayList<>();
        return r.eventListeners;
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

    HtmlDocument findDocument() {
        if (this instanceof HtmlDocument doc) return doc;
        if (ownerDocument != null) return ownerDocument;
        for (HtmlNode cur = this; cur != null; cur = cur.parent) {
            if (cur instanceof HtmlDocument doc) return doc;
            if (cur.ownerDocument != null) return cur.ownerDocument;
        }
        return null;
    }

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
    void notifyChildListChanged(int index) {
        HtmlDocument doc = connectedDocument();
        if (doc != null) doc.childListChanged(this, index);
    }

    void notifyAttributeChanged(String name, @Nullable String oldValue, @Nullable String newValue) {
        HtmlDocument doc = connectedDocument();
        if (doc != null) doc.attributeChanged(this, name, oldValue, newValue);
    }

    /** この節が属する木の根が {@link HtmlDocument} ならそれ。切り離された木では null。 */
    HtmlDocument connectedDocument() {
        HtmlNode top = this;
        while (top.parent != null) top = top.parent;
        return top instanceof HtmlDocument doc ? doc : null;
    }

    /** {@code child} の部分木が、この節の子として加わった。接続中なら id 索引に載せ、番号とセレクタの索引を保つ。 */
    void childAttached(HtmlNode child) {
        if (child == null) return;
        child.listed = true;
        HtmlDocument doc = connectedDocument();
        if (doc != null) doc.indexSubtree(child);
        SelectorIndex.attached(this, child);
        NodeNumbering.attached(this, child);
    }

    /** {@code child} の部分木が、この節の子から外れた。接続中なら id 索引から外し、番号を返す。 */
    void childDetached(HtmlNode child) {
        if (child == null) return;
        child.listed = false;
        HtmlDocument doc = connectedDocument();
        if (doc != null) doc.unindexSubtree(child);
        NodeNumbering.detached(child);
    }

    /** 子の並びを根へ向かって辿った先。仮想の節は自分が根。 */
    HtmlNode treeTop() {
        HtmlNode top = this;
        while (top.parent != null && top.listed) top = top.parent;
        return top;
    }

    /** 描画が作る仮想の節か（{@code parent} は持つが親の子の並びには載らない節とその子孫）。番号は振らない。 */
    public boolean isVirtualNode() {
        HtmlNode top = parent != null && listed ? treeTop() : this;
        return top.parent != null && !top.listed;
    }

    /** 親の子の並びの中の位置（O(1)）。子の並びに載っていなければ -1。 */
    public int indexInParent() {
        HtmlNode p = parent;
        if (p == null || !listed) return -1;
        int i = siblingIndex;
        return i >= 0 && i < p.children.size() && p.children.get(i) == this ? i : p.children.indexOf(this);
    }

    /** 番号を持っているか（番号を振らずに調べる）。 */
    public boolean hasNodeId() {
        return NodeNumbering.numberedIn(this, ids);
    }

    /** class / id 属性が変わった。セレクタの索引を作り直す。 */
    void identityAttributeChanged() {
        SelectorIndex.identityChanged(this);
    }

    /** 番号。無ければ（切り離された木でも）ここで木ごと番号を振る。 */
    public int nodeId() {
        return NodeNumbering.idOf(this);
    }

    /**
     * 描画項目の整数鍵に使う節の番号。文書に繋がっていない節（描画が作る仮想の節・疑似要素の文字など）は、節ごとに別の木の
     * 空間で同じ番号（1）が振られて鍵が衝突するので 0（文字列の ID から鍵を導く）を返す。
     */
    public int paintHandle() {
        return treeTop() instanceof HtmlDocument ? nodeId() : 0;
    }

    /** {@link #nodeId()} を振った番号の空間。 */
    public NodeIds nodeIds() {
        nodeId();
        return ids;
    }

    /**
     * 描画・ネットワークで節を指す鍵。明示した {@code id} があればそれ、無ければ番号から導く {@code ~番号}。
     * 文字列を属性に書き込まない（{@link #getId()} は作者が書いた id だけを返す）。
     */
    public String key() {
        return NodeNumbering.key(this);
    }

    /** {@link #key()} に {@code "_scrollc"} を付けたスクロール内容ノード用 ID をキャッシュして返す。 */
    public String scrollContentKey() {
        return NodeNumbering.scrollContentKey(this);
    }

    /** 描画部品 ID（{@code id + suffix}）をキャッシュして返す（スロット 0..25）。 */
    public String partKey(int slot, String suffix) {
        return NodeNumbering.partKey(this, slot, suffix);
    }

    /** 静的領域の大きさ（{@link #sealNodeIds} で閉じた番号の数）。木の根に対して呼ぶ。 */
    public int staticNodeCount() {
        nodeId();
        return ids.staticCount();
    }

    /**
     * ネットワーク越しに受け取った番号で、この木の番号を振り直す（サーバーの鍵とクライアントの鍵を揃える）。
     * {@code idOf} が負を返す節には、空いている番号を新しく振る。
     */
    public void restoreNodeIds(java.util.function.ToIntFunction<HtmlNode> idOf, int staticCount) {
        NodeNumbering.restore(this, idOf, staticCount);
    }

    /** 文書のパースが終わった。番号を行きがけ順に振り直し、静的領域として閉じる（サーバーとクライアントで鍵が揃う）。 */
    public void sealNodeIds() {
        NodeNumbering.seal(this);
    }

    /** 自分の {@code id} 属性が変わった。接続中なら id 索引を付け替える。 */
    void idAttributeChanged(String oldId, String newId) {
        HtmlDocument doc = connectedDocument();
        if (doc != null && this != doc) doc.reindexNode(this, oldId, newId);
    }

    /** 属性の版（自分）と、木の根に置く非スタイル属性の版。 */
    int attrVersion, nonStyleAttrVer;

    /** 数値バインディングの直接書き込み値を持つ節の重ね先（無ければ null）。 */
    public InlineStyleOverlay inlineOverlay;

    /** 直接書き込み値が変わった。属性は変わらないが、属性に依存する算出結果の使い回しは捨てさせる。 */
    public void touchInlineOverlay() {
        attrVersion++;
        this.treeTop().nonStyleAttrVer++;
    }

    /** 属性を書き換えるたびに増える版番号（属性に依存する算出結果の使い回しの目印）。 */
    public int attrVersion() {
        return attrVersion;
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
