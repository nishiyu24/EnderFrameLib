package com.nishiyu.enderframelib.api.dom;

import com.nishiyu.enderframelib.api.dom.DOMCollections.DOMTokenList;
import com.nishiyu.enderframelib.api.dom.DOMCollections.NodeList;
import com.nishiyu.enderframelib.api.dom.DOMCollections.HTMLCollection;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * An interface representing an element in an HTML or XML document.
 *
 * <p>Conforms to WHATWG DOM Standard §4.9 Element and ParentNode mixin.</p>
 */
@ApiStatus.AvailableSince("1.0")
public interface Element extends Node {

    /**
     * Returns the HTML/XML tag name for this element.
     */
    String getTagName();

    /**
     * Returns the value of the element's {@code id} attribute.
     */
    String getId();

    /**
     * Sets the value of the element's {@code id} attribute.
     */
    void setId(@Nullable String id);

    /**
     * Returns the value of the element's {@code class} attribute.
     */
    String getClassName();

    /**
     * Sets the value of the element's {@code class} attribute.
     */
    void setClassName(@Nullable String className);

    /**
     * Returns a live {@link DOMTokenList} of the element's class names.
     */
    DOMTokenList getClassList();

    /**
     * Returns a map of custom data attributes ({@code data-*}) on this element.
     */
    Map<String, String> getDataset();

    /**
     * Returns the inline style declaration for this element.
     */
    CSSStyleDeclaration getStyle();

    /**
     * Returns the value of a specified attribute on the element, or {@code null} if it does not exist.
     */
    @Nullable
    String getAttribute(String qualifiedName);

    /**
     * Sets the value of an attribute on the specified element.
     */
    void setAttribute(String qualifiedName, String value);

    /**
     * Removes the specified attribute from the element.
     */
    void removeAttribute(String qualifiedName);

    /**
     * Toggles a boolean attribute on the element.
     */
    boolean toggleAttribute(String qualifiedName);

    /**
     * If force is {@code true}, adds attribute; if {@code false}, removes attribute.
     */
    boolean toggleAttribute(String qualifiedName, boolean force);

    /**
     * Returns {@code true} if the specified attribute exists on the element.
     */
    boolean hasAttribute(String qualifiedName);

    /**
     * Returns {@code true} if the element has any attributes.
     */
    boolean hasAttributes();

    /**
     * Returns the attribute names of the element as a list of strings.
     */
    List<String> getAttributeNames();

    /**
     * Gets the HTML serialization of the element's descendants.
     */
    String getInnerHTML();

    /**
     * Sets the HTML serialization of the element's descendants, replacing all children.
     */
    void setInnerHTML(String html);

    /**
     * Gets the serialized HTML fragment representing the element and its descendants.
     */
    String getOuterHTML();

    /**
     * Replaces the element itself with the parsed result of the given HTML string.
     */
    void setOuterHTML(String html);

    // ----------------------------------------------------
    // Element traversal & ParentNode mixin methods (§4.2.6)
    // ----------------------------------------------------

    /**
     * Returns a live {@link HTMLCollection} containing all child elements.
     */
    HTMLCollection getChildren();

    /**
     * Returns the first child that is an Element, or {@code null} if there is none.
     */
    @Nullable
    Element getFirstElementChild();

    /**
     * Returns the last child that is an Element, or {@code null} if there is none.
     */
    @Nullable
    Element getLastElementChild();

    /**
     * Returns the number of child elements.
     */
    int getChildElementCount();

    /**
     * Returns the element immediately preceding this element in its parent's children list.
     */
    @Nullable
    Element getPreviousElementSibling();

    /**
     * Returns the element immediately following this element in its parent's children list.
     */
    @Nullable
    Element getNextElementSibling();

    /**
     * Inserts a set of Node objects before the first child of the parent.
     */
    void prepend(Node... nodes);

    /**
     * Inserts a set of Node objects after the last child of the parent.
     */
    void append(Node... nodes);

    /**
     * Replaces the existing children of a Node with a specified new set of children.
     */
    void replaceChildren(Node... nodes);

    // ----------------------------------------------------
    // Query & Matching (§4.2.6 & Selectors)
    // ----------------------------------------------------

    /**
     * Returns the first element that matches the specified selector string within descendants.
     */
    @Nullable
    Element querySelector(String selectors);

    /**
     * Returns a static {@link NodeList} representing a list of the document's elements that match the specified group of selectors.
     */
    NodeList querySelectorAll(String selectors);

    /**
     * Checks whether the element would be selected by the specified selector string.
     */
    boolean matches(String selectors);

    /**
     * Traverses the element and its parents (heading toward the document root) until it finds a node that matches the specified CSS selector.
     */
    @Nullable
    Element closest(String selectors);

    /**
     * Returns an {@link HTMLCollection} of elements with the given tag name.
     */
    HTMLCollection getElementsByTagName(String qualifiedName);

    /**
     * Returns an {@link HTMLCollection} containing all elements that have all of the specified class names.
     */
    HTMLCollection getElementsByClassName(String classNames);

    // ----------------------------------------------------
    // Focus (§7.5.3)
    // ----------------------------------------------------

    /**
     * Gives focus to the element.
     */
    void focus();

    /**
     * Removes keyboard focus from the current element.
     */
    void blur();

    // ----------------------------------------------------
    // Form & Constraint Validation (§4.10)
    // ----------------------------------------------------

    /**
     * Returns the validity states of this element.
     */
    ValidityState getValidity();

    /**
     * Returns whether the element's value satisfies its validation constraints.
     * Fires an {@code invalid} event at the element if false.
     */
    boolean checkValidity();

    /**
     * Checks whether the element satisfies its constraints and reports the problem to the user if not.
     */
    boolean reportValidity();

    /**
     * Sets a custom error, so that the element would fail to validate.
     */
    void setCustomValidity(String error);

    /**
     * Returns whether the element will participate in constraint validation.
     */
    boolean isWillValidate();

    /**
     * Returns the error message that would be shown to the user if the element was to be validated.
     */
    String getValidationMessage();

    /**
     * Requests form submission with no submitter.
     */
    default void requestSubmit() {
        requestSubmit(null);
    }

    /**
     * Requests form submission with the specified submitter button.
     */
    void requestSubmit(@Nullable Element submitter);

    /**
     * Resets the form to its initial state.
     */
    void reset();
}