package com.nishiyu.enderframelib.core.dom.node;

import com.nishiyu.enderframelib.core.dom.*;
import com.nishiyu.enderframelib.core.dom.node.*;
import com.nishiyu.enderframelib.core.dom.parser.*;
import com.nishiyu.enderframelib.core.dom.parser.*;

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
import com.nishiyu.enderframelib.core.css.SelectorMatcher;
import com.nishiyu.enderframelib.core.css.SelectorMatcher.SelectorEngine;
import com.nishiyu.enderframelib.core.event.CoreEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * WHATWG DOM Standard の {@link Element} インターフェースを実装したクラス。
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
                    : HtmlParser.parseFragment(html, this.tag);
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
                    : HtmlParser.parseFragment(html, p.tag);
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
        int idx = parent.children.indexOf(this);
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
        int idx = parent.children.indexOf(this);
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
        HtmlNode found = SelectorEngine.querySelector(this, selectors);
        return found instanceof HtmlElement he ? he : null;
    }

    @Override
    public NodeList querySelectorAll(String selectors) {
        List<HtmlNode> nodes = SelectorEngine.querySelectorAll(this, selectors);
        return new HtmlCollections.StaticNodeList(nodes);
    }

    @Override
    public boolean matches(String selectors) {
        if (selectors == null || selectors.trim().isEmpty()) return false;
        for (com.nishiyu.enderframelib.core.css.Selector s : SelectorEngine.getOrParse(selectors)) {
            if (SelectorMatcher.matches(s, this)) return true;
        }
        return false;
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

    @Override
    public void focus() {
        if (hasAttribute("disabled")) return;
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

    /**
     * Concrete implementation of {@link DOMTokenList} backed by an {@link HtmlElement}'s class attribute.
     */
    public static class HtmlDOMTokenList implements DOMTokenList {

        private static final Pattern WHITESPACE_SPLIT = Pattern.compile("\\s+");
        private final HtmlElement element;
        private final String attributeName;

        public HtmlDOMTokenList(HtmlElement element, String attributeName) {
            this.element = element;
            this.attributeName = attributeName;
        }

        private List<String> parseTokens() {
            String val = element.getAttribute(attributeName);
            if (val == null || val.trim().isEmpty()) {
                return new ArrayList<>();
            }
            String[] parts = WHITESPACE_SPLIT.split(val.trim());
            Set<String> unique = new LinkedHashSet<>();
            for (String p : parts) {
                if (!p.isEmpty()) unique.add(p);
            }
            return new ArrayList<>(unique);
        }

        private void syncTokens(List<String> tokens) {
            if (tokens.isEmpty()) {
                element.removeAttribute(attributeName);
            } else {
                element.setAttribute(attributeName, String.join(" ", tokens));
            }
        }

        private static void validateToken(String token) {
            if (token == null || token.isEmpty()) {
                throw new DOMException("The token provided must not be empty.", "SyntaxError");
            }
            for (int i = 0; i < token.length(); i++) {
                if (Character.isWhitespace(token.charAt(i))) {
                    throw new DOMException("The token provided contains whitespace characters.", "InvalidCharacterError");
                }
            }
        }

        @Override
        public int getLength() {
            return parseTokens().size();
        }

        @Nullable
        @Override
        public String item(int index) {
            List<String> tokens = parseTokens();
            if (index < 0 || index >= tokens.size()) return null;
            return tokens.get(index);
        }

        @Override
        public boolean contains(String token) {
            validateToken(token);
            return parseTokens().contains(token);
        }

        @Override
        public void add(String... tokens) {
            if (tokens == null || tokens.length == 0) return;
            for (String t : tokens) {
                validateToken(t);
            }
            List<String> current = parseTokens();
            boolean changed = false;
            for (String t : tokens) {
                if (!current.contains(t)) {
                    current.add(t);
                    changed = true;
                }
            }
            if (changed) syncTokens(current);
        }

        @Override
        public void remove(String... tokens) {
            if (tokens == null || tokens.length == 0) return;
            for (String t : tokens) {
                validateToken(t);
            }
            List<String> current = parseTokens();
            boolean changed = false;
            for (String t : tokens) {
                if (current.remove(t)) {
                    changed = true;
                }
            }
            if (changed) syncTokens(current);
        }

        @Override
        public boolean toggle(String token) {
            validateToken(token);
            List<String> current = parseTokens();
            if (current.contains(token)) {
                current.remove(token);
                syncTokens(current);
                return false;
            } else {
                current.add(token);
                syncTokens(current);
                return true;
            }
        }

        @Override
        public boolean toggle(String token, boolean force) {
            validateToken(token);
            List<String> current = parseTokens();
            if (force) {
                if (!current.contains(token)) {
                    current.add(token);
                    syncTokens(current);
                }
                return true;
            } else {
                if (current.remove(token)) {
                    syncTokens(current);
                }
                return false;
            }
        }

        @Override
        public boolean replace(String oldToken, String newToken) {
            validateToken(oldToken);
            validateToken(newToken);
            List<String> current = parseTokens();
            int idx = current.indexOf(oldToken);
            if (idx < 0) return false;
            current.set(idx, newToken);
            syncTokens(current);
            return true;
        }

        @Override
        public String getValue() {
            String val = element.getAttribute(attributeName);
            return val != null ? val : "";
        }

        @Override
        public void setValue(String value) {
            if (value == null || value.isEmpty()) {
                element.removeAttribute(attributeName);
            } else {
                element.setAttribute(attributeName, value);
            }
        }

        @Override
        public Iterator<String> iterator() {
            return parseTokens().iterator();
        }
    }

    /**
     * Concrete implementation of {@link CSSStyleDeclaration} backed by an {@link HtmlElement}'s style attribute.
     */
    public static class HtmlCSSStyleDeclaration implements CSSStyleDeclaration {

        private record PropertyEntry(String value, String priority) {}

        private final HtmlElement element;
        private final Map<String, PropertyEntry> properties = new LinkedHashMap<>();
        private boolean isSyncing = false;

        public HtmlCSSStyleDeclaration(HtmlElement element) {
            this.element = element;
            syncFromAttribute(element.getAttribute("style"));
        }

        public void syncFromAttribute(@Nullable String styleText) {
            if (isSyncing) return;
            properties.clear();
            if (styleText == null || styleText.trim().isEmpty()) return;

            for (String decl : styleText.split(";")) {
                int colon = decl.indexOf(':');
                if (colon <= 0) continue;
                String prop = decl.substring(0, colon).trim().toLowerCase();
                String val = decl.substring(colon + 1).trim();
                if (prop.isEmpty()) continue;

                String priority = "";
                if (val.toLowerCase().endsWith("!important")) {
                    priority = "important";
                    val = val.substring(0, val.length() - 10).trim();
                }
                properties.put(prop, new PropertyEntry(val, priority));
            }
        }

        private void syncToAttribute() {
            if (isSyncing) return;
            isSyncing = true;
            try {
                if (properties.isEmpty()) {
                    element.removeAttribute("style");
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<String, PropertyEntry> entry : properties.entrySet()) {
                        sb.append(entry.getKey()).append(": ").append(entry.getValue().value());
                        if ("important".equalsIgnoreCase(entry.getValue().priority())) {
                            sb.append(" !important");
                        }
                        sb.append("; ");
                    }
                    element.setAttribute("style", sb.toString().trim());
                }
            } finally {
                isSyncing = false;
            }
        }

        @Override
        public String getCssText() {
            String attr = element.getAttribute("style");
            return attr != null ? attr : "";
        }

        @Override
        public void setCssText(String cssText) {
            syncFromAttribute(cssText);
            syncToAttribute();
        }

        @Override
        public int getLength() {
            return properties.size();
        }

        @Nullable
        @Override
        public String item(int index) {
            if (index < 0 || index >= properties.size()) return null;
            return new ArrayList<>(properties.keySet()).get(index);
        }

        @Override
        public String getPropertyValue(String property) {
            if (property == null) return "";
            PropertyEntry entry = properties.get(property.trim().toLowerCase());
            return entry != null ? entry.value() : "";
        }

        @Override
        public String getPropertyPriority(String property) {
            if (property == null) return "";
            PropertyEntry entry = properties.get(property.trim().toLowerCase());
            return entry != null ? entry.priority() : "";
        }

        @Override
        public void setProperty(String property, @Nullable String value, @Nullable String priority) {
            if (property == null || property.trim().isEmpty()) return;
            String name = property.trim().toLowerCase();
            if (value == null || value.trim().isEmpty()) {
                removeProperty(name);
                return;
            }
            String pri = priority != null && "important".equalsIgnoreCase(priority.trim()) ? "important" : "";
            properties.put(name, new PropertyEntry(value.trim(), pri));
            syncToAttribute();
        }

        @Override
        public String removeProperty(String property) {
            if (property == null) return "";
            String name = property.trim().toLowerCase();
            PropertyEntry removed = properties.remove(name);
            if (removed != null) {
                syncToAttribute();
                return removed.value();
            }
            return "";
        }
    }
}
