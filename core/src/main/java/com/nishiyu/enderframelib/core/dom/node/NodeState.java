package com.nishiyu.enderframelib.core.dom.node;

import com.nishiyu.enderframelib.core.dom.node.EventDispatcher.EventListenerEntry;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 節の欄とその手入れ（M5-3a で {@link HtmlNode} から出した。継ぐのは {@code HtmlNode} だけ）: 木の形（子の並び・親）・属性の表・
 * まれな欄、番号と鍵（{@link NodeNumbering}）・セレクタの足切り（{@link SelectorIndex}）の欄、つながっている文書への変化の知らせと
 * id 索引・番号の付け外し、属性の版。DOM の API は {@link HtmlNode}。公開の欄（{@code children}・{@code parent} など）を持つので
 * 型も公開にする（欄の数は {@code ClassShapeRatchetTest} が数える）。作れるのはパッケージの中だけ。
 */
@org.jetbrains.annotations.ApiStatus.Internal
public abstract class NodeState {

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
        if (a == NO_ATTRS) attrs = a = new AttributeMap(self());
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

    NodeState(NodeType type, String tag) {
        this.nodeType = type;
        this.tag = tag != null ? tag : "";
        boolean leaf = type == NodeType.TEXT || type == NodeType.COMMENT;
        this.attrs = leaf ? NO_ATTRS : new AttributeMap(self());
        this.children = leaf ? NO_CHILDREN : new ChildList(self());
    }

    /** 自分（{@code NodeState} を継ぐのは {@link HtmlNode} だけ）。 */
    final HtmlNode self() {
        return (HtmlNode) this;
    }

    /**
     * この節が属する部分木の所有者。祖先へ向かって最初の {@code data-owner} 属性が決め、
     * 無ければ文書の既定、文書に接続していない木は {@link Ownership#CLIENT}。
     */
    public Ownership ownership() {
        for (HtmlNode current = self(); current != null; current = current.parent) {
            Ownership declared = current.attrs != null ? Ownership.parse(current.attrs.get(Ownership.ATTRIBUTE)) : null;
            if (declared != null) return declared;
        }
        HtmlDocument document = findDocument();
        return document != null ? document.getDefaultOwnership() : Ownership.CLIENT;
    }

    HtmlDocument findDocument() {
        if (this instanceof HtmlDocument doc) return doc;
        if (ownerDocument != null) return ownerDocument;
        for (HtmlNode cur = self(); cur != null; cur = cur.parent) {
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
        if (doc != null) doc.nodeChanged(self(), attributeName);
    }

    /** 子の挿入・削除を知らせる。{@code index} は増減があった子の位置（削除なら削除前の位置）。 */
    void notifyChildListChanged(int index) {
        HtmlDocument doc = connectedDocument();
        if (doc != null) doc.childListChanged(self(), index);
    }

    void notifyAttributeChanged(String name, @Nullable String oldValue, @Nullable String newValue) {
        HtmlDocument doc = connectedDocument();
        if (doc != null) doc.attributeChanged(self(), name, oldValue, newValue);
    }

    /** この節が属する木の根が {@link HtmlDocument} ならそれ。切り離された木では null。 */
    HtmlDocument connectedDocument() {
        HtmlNode top = self();
        while (top.parent != null) top = top.parent;
        return top instanceof HtmlDocument doc ? doc : null;
    }

    /** {@code child} の部分木が、この節の子として加わった。接続中なら id 索引に載せ、番号とセレクタの索引を保つ。 */
    void childAttached(HtmlNode child) {
        if (child == null) return;
        child.listed = true;
        HtmlDocument doc = connectedDocument();
        if (doc != null) doc.indexSubtree(child);
        SelectorIndex.attached(self(), child);
        NodeNumbering.attached(self(), child);
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
        HtmlNode top = self();
        while (top.parent != null && top.listed) top = top.parent;
        return top;
    }

    /** 描画が作る仮想の節か（{@code parent} は持つが親の子の並びには載らない節とその子孫）。番号は振らない。 */
    public boolean isVirtualNode() {
        HtmlNode top = parent != null && listed ? treeTop() : self();
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
        return NodeNumbering.numberedIn(self(), ids);
    }

    /** class / id 属性が変わった。セレクタの索引を作り直す。 */
    void identityAttributeChanged() {
        SelectorIndex.identityChanged(self());
    }

    /** 番号。無ければ（切り離された木でも）ここで木ごと番号を振る。 */
    public int nodeId() {
        return NodeNumbering.idOf(self());
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
        return NodeNumbering.key(self());
    }

    /** {@link #key()} に {@code "_scrollc"} を付けたスクロール内容ノード用 ID をキャッシュして返す。 */
    public String scrollContentKey() {
        return NodeNumbering.scrollContentKey(self());
    }

    /** 描画部品 ID（{@code id + suffix}）をキャッシュして返す（スロット 0..25）。 */
    public String partKey(int slot, String suffix) {
        return NodeNumbering.partKey(self(), slot, suffix);
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
        NodeNumbering.restore(self(), idOf, staticCount);
    }

    /** 文書のパースが終わった。番号を行きがけ順に振り直し、静的領域として閉じる（サーバーとクライアントで鍵が揃う）。 */
    public void sealNodeIds() {
        NodeNumbering.seal(self());
    }

    /** 自分の {@code id} 属性が変わった。接続中なら id 索引を付け替える。 */
    void idAttributeChanged(String oldId, String newId) {
        HtmlDocument doc = connectedDocument();
        if (doc != null && self() != doc) doc.reindexNode(self(), oldId, newId);
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
}
