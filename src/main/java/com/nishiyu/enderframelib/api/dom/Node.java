package com.nishiyu.enderframelib.api.dom;

import com.nishiyu.enderframelib.api.dom.DOMCollections.NodeList;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The base interface from which a number of DOM API objects inherit.
 *
 * <p>Conforms to WHATWG DOM Standard §4.4 Node.</p>
 */
@ApiStatus.AvailableSince("1.0")
public interface Node extends EventTarget {

    // Node type constants
    short ELEMENT_NODE = 1;
    short ATTRIBUTE_NODE = 2;
    short TEXT_NODE = 3;
    short CDATA_SECTION_NODE = 4;
    short ENTITY_REFERENCE_NODE = 5;
    short ENTITY_NODE = 6;
    short PROCESSING_INSTRUCTION_NODE = 7;
    short COMMENT_NODE = 8;
    short DOCUMENT_NODE = 9;
    short DOCUMENT_TYPE_NODE = 10;
    short DOCUMENT_FRAGMENT_NODE = 11;
    short NOTATION_NODE = 12;

    // Document position constants
    short DOCUMENT_POSITION_DISCONNECTED = 0x01;
    short DOCUMENT_POSITION_PRECEDING = 0x02;
    short DOCUMENT_POSITION_FOLLOWING = 0x04;
    short DOCUMENT_POSITION_CONTAINS = 0x08;
    short DOCUMENT_POSITION_CONTAINED_BY = 0x10;
    short DOCUMENT_POSITION_IMPLEMENTATION_SPECIFIC = 0x20;

    /**
     * Returns the type of this node.
     */
    short getNodeType();

    /**
     * Returns the name of this node (e.g. tag name in uppercase for Element, "#text", "#comment", "#document").
     */
    String getNodeName();

    /**
     * Returns the Document that owns this node, or {@code null} if this is the Document itself.
     */
    @Nullable
    Document getOwnerDocument();

    /**
     * Returns the parent node of this node, or {@code null} if it has no parent.
     */
    @Nullable
    Node getParentNode();

    /**
     * Returns the parent element of this node, or {@code null} if it has no parent or parent is not an Element.
     */
    @Nullable
    Element getParentElement();

    /**
     * Returns a live {@link NodeList} containing all child nodes of this node.
     */
    NodeList getChildNodes();

    /**
     * Returns the first child of this node, or {@code null} if this node has no children.
     */
    @Nullable
    Node getFirstChild();

    /**
     * Returns the last child of this node, or {@code null} if this node has no children.
     */
    @Nullable
    Node getLastChild();

    /**
     * Returns the previous sibling of this node, or {@code null} if none exists.
     */
    @Nullable
    Node getPreviousSibling();

    /**
     * Returns the next sibling of this node, or {@code null} if none exists.
     */
    @Nullable
    Node getNextSibling();

    /**
     * Returns {@code true} if this node has any children.
     */
    boolean hasChildNodes();

    /**
     * Returns {@code true} if this node is connected to a Document.
     */
    boolean isConnected();

    /**
     * Returns the textual content of this node and its descendants.
     */
    @Nullable
    String getTextContent();

    /**
     * Sets the textual content of this node, replacing any existing children.
     */
    void setTextContent(@Nullable String textContent);

    /**
     * Adds the specified node to the end of the list of children of this node.
     */
    Node appendChild(Node node);

    /**
     * Inserts a node before a reference child as a child of this node.
     */
    Node insertBefore(Node node, @Nullable Node child);

    /**
     * Removes a child node from this node.
     */
    Node removeChild(Node child);

    /**
     * Replaces a child node with a new node.
     */
    Node replaceChild(Node node, Node child);

    /**
     * Returns a duplicate of this node.
     *
     * @param deep if {@code true}, descendants are recursively cloned.
     */
    Node cloneNode(boolean deep);

    /**
     * Returns a shallow duplicate of this node.
     */
    default Node cloneNode() {
        return cloneNode(false);
    }

    /**
     * Returns {@code true} if this node contains the specified other node.
     */
    boolean contains(@Nullable Node other);

    /**
     * Compares the position of this node with another node in the document.
     */
    short compareDocumentPosition(Node other);

    /**
     * Returns the root node of this node's tree.
     */
    Node getRootNode();

    // ----------------------------------------------------
    // ChildNode mixin methods (§4.2.7)
    // ----------------------------------------------------

    /**
     * Removes this node from its parent.
     */
    void remove();

    /**
     * Inserts nodes just before this node in its parent's children.
     */
    void before(Node... nodes);

    /**
     * Inserts nodes just after this node in its parent's children.
     */
    void after(Node... nodes);

    /**
     * Replaces this node with the specified nodes.
     */
    void replaceWith(Node... nodes);
}
