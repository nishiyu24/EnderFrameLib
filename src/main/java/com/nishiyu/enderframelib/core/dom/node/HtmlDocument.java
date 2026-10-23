package com.nishiyu.enderframelib.core.dom.node;

import com.nishiyu.enderframelib.core.dom.*;
import com.nishiyu.enderframelib.core.dom.node.*;
import com.nishiyu.enderframelib.core.dom.parser.*;
import com.nishiyu.enderframelib.core.dom.parser.*;

import com.nishiyu.enderframelib.api.dom.DOMNodes.Comment;
import com.nishiyu.enderframelib.api.dom.Document;
import com.nishiyu.enderframelib.api.dom.DOMNodes.DocumentFragment;
import com.nishiyu.enderframelib.api.dom.Element;
import com.nishiyu.enderframelib.api.dom.DOMCollections.HTMLCollection;
import com.nishiyu.enderframelib.api.dom.Node;
import com.nishiyu.enderframelib.api.dom.DOMCollections.NodeList;
import com.nishiyu.enderframelib.api.dom.DOMNodes.Text;
import com.nishiyu.enderframelib.api.event.Event;
import com.nishiyu.enderframelib.core.css.SelectorMatcher.SelectorEngine;
import com.nishiyu.enderframelib.core.event.CoreEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.nishiyu.enderframelib.api.dom.DOMException;

/**
 * WHATWG DOM Standard の {@link Document} インターフェースを実装したクラス。
 *
 * <p>文書に接続している節の {@code id} 属性を索引（{@code nodesById}）に持ち、線形探索なしで引き当てられる。
 * 索引の更新は {@link HtmlNode} の子の増減と {@code id} 属性の変更が行うので、
 * {@code children} / {@code attrs} を直接触る経路でも索引は古くならない。</p>
 */
public class HtmlDocument extends HtmlNode implements Document {

    /** id 属性 → その id を持つ接続中の節（文書順は問わず、引くときに最初のものを選ぶ）。 */
    private final Map<String, List<HtmlNode>> nodesById = new HashMap<>();
    private String title = "";
    private InteractionState interactionState = new InteractionState();
    private InlineActionSink inlineActionSink = null;
    private Ownership defaultOwnership = Ownership.CLIENT;

    /**
     * 文書の外へ出ていく入口。イベントの伝播が終わり、{@code preventDefault} されていなければ、
     * 伝播経路の節が持つ {@code on*} 属性のアクション名と {@link EventForwardDeclaration} の宣言が、順にここへ渡る。
     * 文書の外（描画やネットワーク）がアクション名の意味を決めるので、DOM 側は名前と宣言を渡すだけにする。
     */
    public interface InlineActionSink {
        /** {@code onclick="アクション名"} のような属性ハンドラ。 */
        void run(HtmlNode node, Event event, String action);

        /** {@code data-forward} で宣言された、サーバーへ転送するイベント。 */
        default void forward(HtmlNode node, Event event, EventForwardDeclaration declaration) {}
    }

    public void setInlineActionSink(@Nullable InlineActionSink sink) {
        this.inlineActionSink = sink;
    }

    @Nullable
    public InlineActionSink inlineActionSink() {
        return inlineActionSink;
    }

    /** 属性で所有者を宣言していない部分木の所有者。サーバーと同期する文書だけ {@link Ownership#SERVER}。 */
    public Ownership getDefaultOwnership() {
        return defaultOwnership;
    }

    public void setDefaultOwnership(Ownership ownership) {
        this.defaultOwnership = Objects.requireNonNull(ownership, "ownership must not be null");
    }

    public HtmlDocument() {
        super(NodeType.ROOT, "root");
        this.ownerDocument = this;
    }

    // ==========================================
    // :target と :defined の元になる文書の状態
    // ==========================================

    /** 文書の URL のフラグメント識別子（{@code #} の後ろ）。空ならターゲットは無い。 */
    private String targetFragment = "";
    /** {@code customElements.define} で登録した、ハイフンを含む要素名（小文字）。 */
    private final java.util.Set<String> definedCustomElements = new java.util.HashSet<>();
    private java.util.function.BiConsumer<HtmlNode, HtmlNode> targetChangeListener = null;

    /** 現在のフラグメント識別子。 */
    public String getTargetFragment() {
        return targetFragment;
    }

    /**
     * フラグメント識別子を変える（{@code <a href="#id">} の既定動作が呼ぶ）。{@code :target} の規則が掛かる
     * 前後の要素を再スタイルできるよう、変わったときは前のターゲットと新しいターゲットをリスナーへ渡す。
     */
    public void setTargetFragment(@Nullable String fragment) {
        String next = fragment == null ? "" : fragment;
        if (next.startsWith("#")) next = next.substring(1);
        if (next.equals(targetFragment)) return;
        HtmlNode before = getTargetElement();
        targetFragment = next;
        if (targetChangeListener != null) targetChangeListener.accept(before, getTargetElement());
    }

    /** フラグメント識別子に一致する id を持つ最初の要素。無ければ null。 */
    @Nullable
    public HtmlNode getTargetElement() {
        if (targetFragment.isEmpty()) return null;
        List<HtmlNode> sameId = nodesById.get(targetFragment);
        return sameId == null || sameId.isEmpty() ? null : sameId.getFirst();
    }

    public void setTargetChangeListener(@Nullable java.util.function.BiConsumer<HtmlNode, HtmlNode> listener) {
        this.targetChangeListener = listener;
    }

    /** ハイフンを含む要素名を、定義済みのカスタム要素として登録する。 */
    public void defineCustomElement(String name) {
        if (name != null) definedCustomElements.add(name.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * {@code :defined} に一致する要素名か。標準の要素と（ハイフンを含まない）未知の要素は常に定義済み、
     * ハイフンを含む名前は {@link #defineCustomElement} で登録されたときだけ定義済み
     * （HTML §4.13.2 の予約名は登録できないので常に定義済み扱い）。
     */
    public boolean isElementDefined(String tagName) {
        if (tagName == null || tagName.indexOf('-') < 0) return true;
        String name = tagName.toLowerCase(java.util.Locale.ROOT);
        return switch (name) {
            case "annotation-xml", "color-profile", "font-face", "font-face-src", "font-face-uri",
                 "font-face-format", "font-face-name", "missing-glyph" -> true;
            default -> definedCustomElements.contains(name);
        };
    }

    /** この文書の対話状態。セッションに属する文書では、セッションの {@link InteractionState} が挿し込まれる。 */
    public InteractionState interactionState() {
        return interactionState;
    }

    public void setInteractionState(InteractionState interactionState) {
        this.interactionState = interactionState != null ? interactionState : new InteractionState();
    }

    /** DOM の操作で文書の中身が変わったときの知らせ先（持ち主のセッション）。 */
    @FunctionalInterface
    public interface DomChangeListener {
        /**
         * @param node          変わった節（子の並びの変化なら親）
         * @param attributeName 属性の変化ならその名前、子の並び・文字の変化なら null
         */
        void onDomChanged(HtmlNode node, @Nullable String attributeName);
    }

    private DomChangeListener domChangeListener;

    public void setDomChangeListener(@Nullable DomChangeListener listener) {
        this.domChangeListener = listener;
    }

    /** {@link HtmlNode#notifyDomChanged} から呼ばれる。汚れの印を付け（算出スタイルを使い回させない）、持ち主に知らせる。 */
    void nodeChanged(HtmlNode node, @Nullable String attributeName) {
        interactionState.markDirty(node);
        DomChangeListener listener = domChangeListener;
        if (listener != null) listener.onDomChanged(node, attributeName);
    }

    @Nullable
    @Override
    public Element getActiveElement() {
        HtmlNode focused = interactionState.focusedNode();
        if (focused instanceof Element element) return element;
        Element body = getBody();
        if (body != null) return body;
        return getDocumentElement();
    }

    /**
     * フォーカス中の要素を更新し、焦点イベントを送る。
     *
     * @return 対話状態かフォーカス先が変わったとき true
     */
    public boolean setFocusedElement(@Nullable Element element, boolean focusVisible) {
        HtmlNode target = element instanceof HtmlNode node ? node : null;
        HtmlNode oldActive = interactionState.focusedNode();
        if (oldActive == target) {
            return target != null && interactionState.setFocusedNode(target, focusVisible);
        }

        interactionState.setFocusedNode(target, focusVisible);

        if (oldActive != null) {
            oldActive.dispatchEvent(new CoreEvent.CoreFocusEvent("focusout", true, false, element));
            oldActive.dispatchEvent(new CoreEvent.CoreFocusEvent("blur", false, false, element));
        }

        if (target != null) {
            target.dispatchEvent(new CoreEvent.CoreFocusEvent("focusin", true, false, oldActive));
            target.dispatchEvent(new CoreEvent.CoreFocusEvent("focus", false, false, oldActive));
        }
        return true;
    }

    /** {@code subtree} の根と子孫を索引に載せる。 */
    void indexSubtree(HtmlNode subtree) {
        index(subtree.getId(), subtree);
        for (HtmlNode child : subtree.children) {
            indexSubtree(child);
        }
    }

    /** {@code subtree} の根と子孫を索引から外す。 */
    void unindexSubtree(HtmlNode subtree) {
        unindex(subtree.getId(), subtree);
        for (HtmlNode child : subtree.children) {
            unindexSubtree(child);
        }
    }

    /** 接続中の節の {@code id} 属性が変わったときの付け替え。 */
    void reindexNode(HtmlNode node, String oldId, String newId) {
        unindex(oldId, node);
        index(newId, node);
    }

    private void index(String id, HtmlNode node) {
        if (id == null || id.isEmpty()) return;
        List<HtmlNode> sameId = nodesById.computeIfAbsent(id, key -> new ArrayList<>(1));
        if (!sameId.contains(node)) sameId.add(node);
    }

    private void unindex(String id, HtmlNode node) {
        if (id == null || id.isEmpty()) return;
        List<HtmlNode> sameId = nodesById.get(id);
        if (sameId == null) return;
        sameId.remove(node);
        if (sameId.isEmpty()) nodesById.remove(id);
    }

    /**
     * 索引から、{@code scope} の部分木にある最初の（文書順で先頭の）節を引く。
     * 索引の外で {@code id} が書き換わった節（属性表のイテレータ越しの削除など）は、ここで取り除く。
     */
    HtmlNode lookupIndexed(String id, boolean elementOnly, HtmlNode scope) {
        List<HtmlNode> sameId = nodesById.get(id);
        if (sameId == null) return null;
        sameId.removeIf(node -> !id.equals(node.getId()));
        HtmlNode first = null;
        for (HtmlNode node : sameId) {
            if (elementOnly && !(node instanceof Element)) continue;
            if (!scope.contains(node)) continue;
            if (first == null || (first.compareDocumentPosition(node) & Node.DOCUMENT_POSITION_PRECEDING) != 0) {
                first = node;
            }
        }
        if (sameId.isEmpty()) nodesById.remove(id);
        return first;
    }

    @Nullable
    @Override
    public Element getDocumentElement() {
        for (HtmlNode child : children) {
            if (child instanceof Element el) {
                if ("html".equalsIgnoreCase(el.getTagName())) return el;
            }
        }
        for (HtmlNode child : children) {
            if (child instanceof Element el) return el;
        }
        return null;
    }

    @Nullable
    @Override
    public Element getBody() {
        Element root = getDocumentElement();
        if (root == null) return null;
        for (Node child : root.getChildNodes()) {
            if (child instanceof Element el && "body".equalsIgnoreCase(el.getTagName())) {
                return el;
            }
        }
        return null;
    }

    @Nullable
    @Override
    public Element getHead() {
        Element root = getDocumentElement();
        if (root == null) return null;
        for (Node child : root.getChildNodes()) {
            if (child instanceof Element el && "head".equalsIgnoreCase(el.getTagName())) {
                return el;
            }
        }
        return null;
    }

    @Override
    public String getTitle() {
        Element head = getHead();
        if (head != null) {
            for (Node child : head.getChildNodes()) {
                if (child instanceof Element el && "title".equalsIgnoreCase(el.getTagName())) {
                    String text = el.getTextContent();
                    return text != null ? text.trim() : "";
                }
            }
        }
        return title;
    }

    @Override
    public void setTitle(String title) {
        this.title = title != null ? title : "";
        Element head = getHead();
        if (head != null) {
            for (Node child : head.getChildNodes()) {
                if (child instanceof Element el && "title".equalsIgnoreCase(el.getTagName())) {
                    el.setTextContent(this.title);
                    return;
                }
            }
            HtmlElement titleEl = new HtmlElement("title");
            titleEl.setTextContent(this.title);
            head.appendChild(titleEl);
        }
    }

    @Override
    public Element createElement(String tagName) {
        HtmlElement el = new HtmlElement(tagName);
        el.ownerDocument = this;
        return el;
    }

    @Override
    public Text createTextNode(String data) {
        HtmlText txt = new HtmlText(data);
        txt.ownerDocument = this;
        return txt;
    }

    @Override
    public Comment createComment(String data) {
        HtmlComment comment = new HtmlComment(data);
        comment.ownerDocument = this;
        return comment;
    }

    @Override
    public DocumentFragment createDocumentFragment() {
        HtmlDocumentFragment frag = new HtmlDocumentFragment();
        frag.ownerDocument = this;
        return frag;
    }

    @Nullable
    @Override
    public HtmlElement querySelector(String selectors) {
        HtmlNode found = SelectorEngine.querySelector(this, selectors);
        return found instanceof HtmlElement he ? he : null;
    }

    @Override
    public NodeList querySelectorAll(String selectors) {
        List<HtmlNode> nodes = SelectorEngine.querySelectorAll(this, selectors);
        return new HtmlCollections.StaticNodeList(nodes);
    }

    @Override
    public HTMLCollection getElementsByTagName(String qualifiedName) {
        String query = qualifiedName != null ? qualifiedName.trim() : "";
        boolean all = "*".equals(query);
        return HtmlCollections.forDescendants(this, el -> all || query.equalsIgnoreCase(el.getTagName()));
    }

    @Override
    public HTMLCollection getElementsByClassName(String classNames) {
        if (classNames == null || classNames.trim().isEmpty()) {
            return new HtmlCollections.LiveHTMLCollection(ArrayList::new);
        }
        String[] targets = classNames.trim().split("\\s+");
        return HtmlCollections.forDescendants(this, el -> {
            for (String t : targets) {
                if (!el.getClassList().contains(t)) return false;
            }
            return true;
        });
    }

    /**
     * WHATWG DOM Standard の {@link Comment} インターフェースを実装したクラス。
     */
    public static class HtmlComment extends HtmlNode implements Comment {

        public HtmlComment(String data) {
            super(NodeType.COMMENT, "#comment");
            this.text = data != null ? data : "";
        }

        @Override
        public String getData() {
            return text;
        }

        @Override
        public void setData(String data) {
            String oldVal = this.text;
            this.text = data != null ? data : "";
            MutationObserverManager.queueCharacterData(this, oldVal);
            notifyDomChanged(null);
        }

        @Override
        public int getLength() {
            return text.length();
        }
    }

    /**
     * WHATWG DOM Standard の {@link DocumentFragment} インターフェースを実装したクラス。
     */
    public static class HtmlDocumentFragment extends HtmlNode implements DocumentFragment {

        public HtmlDocumentFragment() {
            super(NodeType.DOCUMENT_FRAGMENT, "#document-fragment");
        }

        @Override
        public HTMLCollection getChildren() {
            return HtmlCollections.forChildren(this);
        }

        @Nullable
        @Override
        public Element getFirstElementChild() {
            for (HtmlNode child : children) {
                if (child instanceof Element el) return el;
            }
            return null;
        }

        @Nullable
        @Override
        public Element getLastElementChild() {
            for (int i = children.size() - 1; i >= 0; i--) {
                HtmlNode child = children.get(i);
                if (child instanceof Element el) return el;
            }
            return null;
        }

        @Override
        public int getChildElementCount() {
            int count = 0;
            for (HtmlNode child : children) {
                if (child instanceof Element) count++;
            }
            return count;
        }

        @Override
        public void prepend(Node... nodes) {
            if (nodes == null) return;
            Node first = getFirstChild();
            for (Node n : nodes) {
                insertBefore(n, first);
            }
        }

        @Override
        public void append(Node... nodes) {
            if (nodes == null) return;
            for (Node n : nodes) {
                appendChild(n);
            }
        }

        @Override
        public void replaceChildren(Node... nodes) {
            List<HtmlNode> current = new ArrayList<>(this.children);
            for (HtmlNode child : current) {
                removeChild(child);
            }
            if (nodes != null) {
                for (Node n : nodes) {
                    appendChild(n);
                }
            }
        }

        @Nullable
        @Override
        public HtmlElement querySelector(String selectors) {
            HtmlNode found = SelectorEngine.querySelector(this, selectors);
            return found instanceof HtmlElement he ? he : null;
        }

        @Override
        public NodeList querySelectorAll(String selectors) {
            List<HtmlNode> nodes = SelectorEngine.querySelectorAll(this, selectors);
            return new HtmlCollections.StaticNodeList(nodes);
        }
    }
}
