package com.nishiyu.enderframelib.api.dom;

import com.nishiyu.enderframelib.api.dom.DOMNodes.DocumentFragment;
import com.nishiyu.enderframelib.api.dom.DOMNodes.Text;
import com.nishiyu.enderframelib.api.dom.DOMNodes.Comment;
import com.nishiyu.enderframelib.api.dom.DOMCollections.NodeList;
import com.nishiyu.enderframelib.api.dom.DOMCollections.HTMLCollection;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * An interface representing any web page loaded in the browser and serving as an entry point into the page's content.
 *
 * <p>Conforms to WHATWG DOM Standard §4.5 Document.</p>
 */
@ApiStatus.AvailableSince("1.0")
public interface Document extends Node {

    /**
     * Returns the {@link Element} that is the root element of the document (typically {@code <html>}).
     */
    @Nullable
    Element getDocumentElement();

    /**
     * Returns the {@link Element} that is the {@code <body>} or {@code <frameset>} element of the document.
     */
    @Nullable
    Element getBody();

    /**
     * Returns the {@link Element} that is the {@code <head>} element of the document.
     */
    @Nullable
    Element getHead();

    /**
     * Returns the title of the document.
     */
    String getTitle();

    /**
     * Sets the title of the document.
     */
    void setTitle(String title);

    /**
     * Creates the HTML element specified by tagName.
     */
    Element createElement(String tagName);

    /**
     * Creates a new {@link Text} node.
     */
    Text createTextNode(String data);

    /**
     * Creates a new {@link Comment} node.
     */
    Comment createComment(String data);

    /**
     * Creates a new empty {@link DocumentFragment} into which DOM nodes can be added.
     */
    DocumentFragment createDocumentFragment();

    /**
     * Returns an {@link Element} object representing the element whose id property matches the specified string.
     *
     * <p>Implementations should maintain an O(1) id index map.</p>
     */
    @Nullable
    Element getElementById(String elementId);

    /**
     * Returns the first {@link Element} within the document that matches the specified selector, or group of selectors.
     */
    @Nullable
    Element querySelector(String selectors);

    /**
     * Returns a static {@link NodeList} representing a list of the document's elements that match the specified group of selectors.
     */
    NodeList querySelectorAll(String selectors);

    /**
     * Returns a live {@link HTMLCollection} of elements with the given tag name.
     */
    HTMLCollection getElementsByTagName(String qualifiedName);

    /**
     * Returns a live {@link HTMLCollection} containing all elements that have all of the specified class names.
     */
    HTMLCollection getElementsByClassName(String classNames);

    /**
     * Returns the {@link Element} within the DOM that currently has focus.
     */
    @Nullable
    Element getActiveElement();

    /** 要素の算出スタイル（読み取り専用）。{@code width}・{@code height}・余白は、レイアウト済みなら使用値（px）。 */
    CSSStyleDeclaration getComputedStyle(Element element);

    /** ビューポートの幅・高さ（{@code window.innerWidth} / {@code innerHeight}）。レイアウト前は 0。 */
    int getInnerWidth();

    int getInnerHeight();

    /** メディアクエリを今のメディア環境で評価する。 */
    MediaQueryList matchMedia(String query);

    /** レイアウトや位置が確定するたびに呼ばれる内部の購読口。ResizeObserver と IntersectionObserver が使う。 */
    @ApiStatus.Internal
    interface LayoutObserver {
        void layoutUpdated();
    }

    @ApiStatus.Internal
    void addLayoutObserver(LayoutObserver observer);

    @ApiStatus.Internal
    void removeLayoutObserver(LayoutObserver observer);
}
