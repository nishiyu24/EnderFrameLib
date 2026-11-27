package com.nishiyu.enderframelib.core.dom.node;


import com.nishiyu.enderframelib.api.dom.CSSStyleDeclaration;
import com.nishiyu.enderframelib.api.dom.DOMRect;
import com.nishiyu.enderframelib.api.dom.DOMException;
import com.nishiyu.enderframelib.api.dom.DOMCollections.DOMTokenList;
import com.nishiyu.enderframelib.api.dom.Element;
import com.nishiyu.enderframelib.api.dom.DOMCollections.HTMLCollection;
import com.nishiyu.enderframelib.api.dom.Node;
import com.nishiyu.enderframelib.api.dom.DOMCollections.NodeList;
import com.nishiyu.enderframelib.api.dom.ValidityState;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument.HtmlDocumentFragment;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * WHATWG DOM Standard の {@link Element} インターフェースを実装したクラス。
 *
 * <p>{@code classList} は {@link HtmlDOMTokenList}、{@code style} は {@link HtmlCSSStyleDeclaration}、dialog / popover の
 * Top Layer の操作は {@link TopLayerOps}、制約の検証とフォームは {@link ConstraintValidator} / {@link FormController} にある。</p>
 */
public class HtmlElement extends HtmlNode implements Element {

    private HtmlDOMTokenList classList = null;
    private HtmlCSSStyleDeclaration style = null;
    private String customValidityMessage = "";
    /** {@code <template>} の不活性な中身。テンプレート以外では {@code null}。 */
    private HtmlDocumentFragment templateContent = null;

    public HtmlElement(String tag) {
        super(NodeType.ELEMENT, tag);
    }

    /** {@code template} 要素か。{@code cloneNode} と直列化が中身の置き場を切り替える。 */
    public boolean isTemplateElement() {
        return "template".equalsIgnoreCase(tag);
    }

    /**
     * {@code <template>} の中身（HTML §4.12.3 の template contents）。
     * 文書の木には入らず、{@code document.querySelector} も見つけない。テンプレート以外では {@code null}。
     */
    @Nullable
    public HtmlDocumentFragment getContent() {
        if (!isTemplateElement()) return null;
        if (templateContent == null) {
            templateContent = new HtmlDocumentFragment();
        }
        HtmlDocument owner = getOwnerDocument() instanceof HtmlDocument doc ? doc : null;
        if (templateContent.ownerDocument != owner) templateContent.ownerDocument = owner;
        return templateContent;
    }

    /** 中身を作らずに読む（クローンと直列化のための内部用）。 */
    @Nullable
    HtmlDocumentFragment peekTemplateContent() {
        return isTemplateElement() ? templateContent : null;
    }

    @Override
    public String getTagName() {
        return tag;
    }

    @Override
    public String getClassName() {
        String val = getAttribute("class");
        return val != null ? val : "";
    }

    @Override
    public void setClassName(@Nullable String className) {
        if (className == null || className.isEmpty()) {
            removeAttribute("class");
        } else {
            setAttribute("class", className);
        }
    }

    @Override
    public DOMTokenList getClassList() {
        if (classList == null) {
            classList = new HtmlDOMTokenList(this, "class");
        }
        return classList;
    }

    @Override
    public CSSStyleDeclaration getStyle() {
        if (style == null) {
            style = new HtmlCSSStyleDeclaration(this);
        }
        return style;
    }

    void syncStyleFromAttribute(String styleText) {
        if (style != null) {
            style.syncFromAttribute(styleText);
        }
    }

    // ==========================================
    // innerHTML / outerHTML
    // ==========================================

    @Override
    public String getInnerHTML() {
        return DomUtils.getInnerHTML(this);
    }

    @Override
    public void setInnerHTML(String html) {
        // template の innerHTML は中身（content）を書き換える。要素の子ではない
        HtmlNode container = isTemplateElement() ? getContent() : this;
        // 既存の子をすべて removeChild で切り離す（MutationObserver通知と idMap 解除のため）
        List<HtmlNode> currentChildren = new ArrayList<>(container.children);
        for (HtmlNode child : currentChildren) {
            container.removeChild(child);
        }
        if (html != null && !html.trim().isEmpty()) {
            HtmlNode parsedRoot = DomUtils.innerHtmlParser != null
                    ? DomUtils.innerHtmlParser.apply(html, this.tag)
                    : NodeServices.get().parseFragment(html, this.tag);
            if (parsedRoot != null) {
                List<HtmlNode> newChildren = new ArrayList<>(parsedRoot.children);
                for (HtmlNode child : newChildren) {
                    container.appendChild(child);
                }
            }
        }
    }

    @Override
    public String getOuterHTML() {
        return DomUtils.getOuterHTML(this);
    }

    @Override
    public void setOuterHTML(String html) {
        if (this.parent == null) {
            throw new DOMException("This element has no parent node.", "NoModificationAllowedError");
        }
        HtmlNode p = this.parent;
        Node next = this.getNextSibling();
        p.removeChild(this);
        if (html != null && !html.trim().isEmpty()) {
            HtmlNode parsedRoot = DomUtils.innerHtmlParser != null
                    ? DomUtils.innerHtmlParser.apply(html, p.tag)
                    : NodeServices.get().parseFragment(html, p.tag);
            if (parsedRoot != null) {
                for (HtmlNode child : new ArrayList<>(parsedRoot.children)) {
                    p.insertBefore(child, next);
                }
            }
        }
    }

    // ==========================================
    // ParentNode & Element 走査
    // ==========================================

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

    @Nullable
    @Override
    public Element getPreviousElementSibling() {
        if (parent == null) return null;
        int idx = indexInParent();
        for (int i = idx - 1; i >= 0; i--) {
            HtmlNode node = parent.children.get(i);
            if (node instanceof Element el) return el;
        }
        return null;
    }

    @Nullable
    @Override
    public Element getNextElementSibling() {
        if (parent == null) return null;
        int idx = indexInParent();
        if (idx < 0) return null;
        for (int i = idx + 1; i < parent.children.size(); i++) {
            HtmlNode node = parent.children.get(i);
            if (node instanceof Element el) return el;
        }
        return null;
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

    // ==========================================
    // 検索・マッチング
    // ==========================================

    @Nullable
    @Override
    public HtmlElement querySelector(String selectors) {
        HtmlNode found = NodeServices.get().querySelector(this, selectors);
        return found instanceof HtmlElement he ? he : null;
    }

    @Override
    public NodeList querySelectorAll(String selectors) {
        List<HtmlNode> nodes = NodeServices.get().querySelectorAll(this, selectors);
        return new HtmlCollections.StaticNodeList(nodes);
    }

    @Override
    public boolean matches(String selectors) {
        if (selectors == null || selectors.trim().isEmpty()) return false;
        return NodeServices.get().matches(this, selectors);
    }

    @Nullable
    @Override
    public Element closest(String selectors) {
        if (selectors == null || selectors.trim().isEmpty()) return null;
        for (HtmlNode cur = this; cur != null; cur = cur.parent) {
            if (cur instanceof Element el && el.matches(selectors)) {
                return el;
            }
        }
        return null;
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

    private String defaultValue = null;
    private boolean defaultValueInitialized = false;
    private Boolean defaultChecked = null;

    public String getDefaultValue() {
        if (!defaultValueInitialized) {
            defaultValue = attrs.get("value");
            defaultValueInitialized = true;
        }
        return defaultValue != null ? defaultValue : "";
    }

    public void setDefaultValue(String val) {
        this.defaultValue = val;
        this.defaultValueInitialized = true;
    }

    public boolean isDefaultChecked() {
        if (defaultChecked == null) {
            defaultChecked = attrs.containsKey("checked");
        }
        return defaultChecked;
    }

    public void setDefaultChecked(boolean checked) {
        this.defaultChecked = checked;
    }

    // ==========================================
    // Focus (§7.5.3)
    // ==========================================

    // ==========================================
    // CSSOM View
    // ==========================================

    @Nullable
    private LayoutQuery layoutQuery() {
        return getOwnerDocument() instanceof HtmlDocument doc ? doc.layoutQuery() : null;
    }

    @Nullable
    private LayoutQuery.BoxMetrics boxMetrics() {
        LayoutQuery query = layoutQuery();
        return query != null ? query.metrics(this) : null;
    }

    @Override
    public DOMRect getBoundingClientRect() {
        LayoutQuery.BoxMetrics m = boxMetrics();
        return m == null ? DOMRect.EMPTY : new DOMRect(m.x(), m.y(), m.w(), m.h());
    }

    @Override
    public List<DOMRect> getClientRects() {
        LayoutQuery.BoxMetrics m = boxMetrics();
        return m == null ? List.of() : List.of(new DOMRect(m.x(), m.y(), m.w(), m.h()));
    }

    @Override
    public int getOffsetWidth() {
        LayoutQuery.BoxMetrics m = boxMetrics();
        return m == null ? 0 : Math.round(m.w());
    }

    @Override
    public int getOffsetHeight() {
        LayoutQuery.BoxMetrics m = boxMetrics();
        return m == null ? 0 : Math.round(m.h());
    }

    @Override
    public int getOffsetLeft() {
        return offsetFromParent(true);
    }

    @Override
    public int getOffsetTop() {
        return offsetFromParent(false);
    }

    private int offsetFromParent(boolean horizontal) {
        LayoutQuery query = layoutQuery();
        LayoutQuery.BoxMetrics m = query != null ? query.metrics(this) : null;
        if (m == null) return 0;
        LayoutQuery.BoxMetrics parent = m.offsetParent() != null ? query.metrics(m.offsetParent()) : null;
        if (parent == null) return Math.round(horizontal ? m.x() : m.y());
        // offsetParent の padding edge（border の内側）から測る
        return Math.round(horizontal ? m.x() - parent.x() - parent.bL() : m.y() - parent.y() - parent.bT());
    }

    @Nullable
    @Override
    public Element getOffsetParent() {
        LayoutQuery.BoxMetrics m = boxMetrics();
        return m != null && m.offsetParent() instanceof Element e ? e : null;
    }

    @Override
    public int getClientWidth() {
        LayoutQuery.BoxMetrics m = boxMetrics();
        return m == null ? 0 : Math.round(m.clientWidth());
    }

    @Override
    public int getClientHeight() {
        LayoutQuery.BoxMetrics m = boxMetrics();
        return m == null ? 0 : Math.round(m.clientHeight());
    }

    @Override
    public int getClientLeft() {
        LayoutQuery.BoxMetrics m = boxMetrics();
        return m == null ? 0 : Math.round(m.bL());
    }

    @Override
    public int getClientTop() {
        LayoutQuery.BoxMetrics m = boxMetrics();
        return m == null ? 0 : Math.round(m.bT());
    }

    @Override
    public int getScrollWidth() {
        LayoutQuery.BoxMetrics m = boxMetrics();
        return m == null ? 0 : Math.round(m.fullScrollWidth());
    }

    @Override
    public int getScrollHeight() {
        LayoutQuery.BoxMetrics m = boxMetrics();
        return m == null ? 0 : Math.round(m.fullScrollHeight());
    }

    @Override
    public float getScrollLeft() {
        LayoutQuery query = layoutQuery();
        return query == null ? 0 : query.scrollLeft(this);
    }

    @Override
    public float getScrollTop() {
        LayoutQuery query = layoutQuery();
        return query == null ? 0 : query.scrollTop(this);
    }

    @Override
    public void setScrollLeft(float x) {
        LayoutQuery query = layoutQuery();
        if (query != null) query.scrollTo(this, x, query.scrollTop(this));
    }

    @Override
    public void setScrollTop(float y) {
        LayoutQuery query = layoutQuery();
        if (query != null) query.scrollTo(this, query.scrollLeft(this), y);
    }

    @Override
    public void scrollTo(float x, float y) {
        LayoutQuery query = layoutQuery();
        if (query != null) query.scrollTo(this, x, y);
    }

    @Override
    public void scrollIntoView() {
        LayoutQuery query = layoutQuery();
        if (query != null) query.scrollIntoView(this);
    }

    @Nullable
    @Override
    public DOMRect getContentRect() {
        LayoutQuery.BoxMetrics m = boxMetrics();
        if (m == null) return null;
        float w = Math.max(0, m.w() - m.bL() - m.bR() - m.pL() - m.pR());
        float h = Math.max(0, m.h() - m.bT() - m.bB() - m.pT() - m.pB());
        return new DOMRect(m.pL(), m.pT(), w, h);
    }

    @Override
    public DOMRect getVisibleClientRect(@Nullable Element root) {
        LayoutQuery query = layoutQuery();
        if (query == null) return DOMRect.EMPTY;
        return query.visibleRect(this, root instanceof HtmlNode node ? node : null);
    }

    // ==========================================
    // Top Layer（HTML §4.11 dialog / §6.12 popover。中身は TopLayerOps）
    // ==========================================

    /** {@code dialog.show()}: モードレスで開く（{@code open} 属性だけ）。 */
    public void show() {
        TopLayerOps.show(this);
    }

    /** {@code dialog.showModal()}: Top Layer に載せて開く（{@code :modal}）。すでに開いていれば何もしない。 */
    public void showModal() {
        TopLayerOps.showModal(this);
    }

    /** この要素が不活性（{@code inert} 属性の配下、またはモーダル {@code <dialog>} の外側）か。 */
    public boolean isInert() {
        return TopLayerOps.isInert(this);
    }

    /** {@code dialog.close()}。閉じたら {@code close} イベントを送る。 */
    public void close() {
        TopLayerOps.close(this);
    }

    /** {@code showModal()} で開いていて、まだ閉じていないか（{@code :modal}）。 */
    public boolean isModalOpen() {
        return TopLayerOps.isModalOpen(this);
    }

    /** {@code popover} 属性を持ち、表示中か（{@code :popover-open}）。 */
    public boolean isPopoverOpen() {
        return TopLayerOps.isPopoverOpen(this);
    }

    /** {@code popover} 属性を持つ要素を表示する（Top Layer に載る）。 */
    public void showPopover() {
        TopLayerOps.setPopoverOpen(this, true);
    }

    public void hidePopover() {
        TopLayerOps.setPopoverOpen(this, false);
    }

    /** @return 呼び出し後に表示中か */
    public boolean togglePopover() {
        return TopLayerOps.togglePopover(this);
    }

    @Override
    public void focus() {
        if (hasAttribute("disabled") || isInert()) return;
        com.nishiyu.enderframelib.api.dom.Document ownerDoc = getOwnerDocument();
        if (ownerDoc instanceof HtmlDocument doc) {
            doc.setFocusedElement(this, false);
            return;
        }
        HtmlNode root = this;
        while (root.parent != null) root = root.parent;
        if (root instanceof HtmlDocument doc) {
            doc.setFocusedElement(this, false);
        }
    }

    @Override
    public void blur() {
        com.nishiyu.enderframelib.api.dom.Document ownerDoc = getOwnerDocument();
        if (ownerDoc instanceof HtmlDocument doc) {
            if (doc.getActiveElement() == this) {
                doc.setFocusedElement(null, false);
            }
            return;
        }
        HtmlNode root = this;
        while (root.parent != null) root = root.parent;
        if (root instanceof HtmlDocument doc && doc.getActiveElement() == this) {
            doc.setFocusedElement(null, false);
        }
    }

    // ==========================================
    // Form & Constraint Validation (§4.10)
    // ==========================================

    @Override
    public ValidityState getValidity() {
        return ConstraintValidator.computeValidity(this, customValidityMessage);
    }

    @Override
    public boolean checkValidity() {
        if ("form".equalsIgnoreCase(this.tag)) {
            return FormController.checkFormValidity(this);
        }
        ValidityState state = getValidity();
        if (!state.isValid()) {
            dispatchEvent(new CoreEvent("invalid", false, true));
            return false;
        }
        return true;
    }

    @Override
    public boolean reportValidity() {
        if ("form".equalsIgnoreCase(this.tag)) {
            return FormController.reportFormValidity(this);
        }
        boolean valid = checkValidity();
        if (!valid) {
            focus();
        }
        return valid;
    }

    @Override
    public void setCustomValidity(String error) {
        this.customValidityMessage = error != null ? error : "";
    }

    @Override
    public boolean isWillValidate() {
        return ConstraintValidator.willValidate(this);
    }

    @Override
    public String getValidationMessage() {
        return ConstraintValidator.getValidationMessage(this, getValidity(), customValidityMessage);
    }

    @Override
    public void requestSubmit(@Nullable Element submitter) {
        HtmlElement sub = submitter instanceof HtmlElement el ? el : null;
        FormController.requestSubmit(this, sub);
    }

    @Override
    public void reset() {
        FormController.resetForm(this);
    }
}
