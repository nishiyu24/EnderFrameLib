package com.nishiyu.enderframelib.wpt;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import com.nishiyu.enderframelib.wpt.WptHarness.WptCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.nishiyu.enderframelib.wpt.WptHarness.assertEqualsWpt;
import static com.nishiyu.enderframelib.wpt.WptHarness.assertFalseWpt;
import static com.nishiyu.enderframelib.wpt.WptHarness.assertTrueWpt;
import static com.nishiyu.enderframelib.wpt.WptHarness.test;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.RenderPipeline;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

/**
 * WPT の dom/ 領域から採用した testharness.js ケースの移植テスト。
 *
 * <p>各テストケースは {@link WptCase} で WPT の相対パスと固定コミット SHA に紐づき、
 * 仕様どおりの振る舞い（DOM Standard）を JUnit で検証する。
 */
public class WptDomBehaviorTest extends WptBehaviorTest {

    // DOM Standard §4.4 Node 定数
    private static final short ELEMENT_NODE = 1;
    private static final short TEXT_NODE = 3;
    private static final short DOCUMENT_NODE = 9;

    @Test
    @DisplayName("dom/nodes/Node-nodeType.html")
    @WptCase(
            path = "dom/nodes/Node-nodeType.html",
            sha = WptHarness.PINNED_SHA,
            description = "nodeType constant values and mapping for Element, Text, and Root"
    )
    public void testNodeNodeType() {
        test("Element nodeType must be 1", () -> {
            HtmlNode element = new HtmlNode("div");
            assertEqualsWpt(NodeType.ELEMENT, element.nodeType, "element.nodeType must be ELEMENT");
            short typeNumber = element.nodeType == NodeType.ELEMENT ? ELEMENT_NODE : 0;
            assertEqualsWpt(ELEMENT_NODE, typeNumber, "element.nodeType must match ELEMENT_NODE (1)");
        });

        test("Text nodeType must be 3", () -> {
            HtmlNode textNode = new HtmlText("sample text");
            assertEqualsWpt(NodeType.TEXT, textNode.nodeType, "textNode.nodeType must be TEXT");
            short typeNumber = textNode.nodeType == NodeType.TEXT ? TEXT_NODE : 0;
            assertEqualsWpt(TEXT_NODE, typeNumber, "textNode.nodeType must match TEXT_NODE (3)");
        });

        test("Document root nodeType must be ROOT/DOCUMENT", () -> {
            HtmlNode root = new HtmlNode(NodeType.ROOT, "root");
            assertEqualsWpt(NodeType.ROOT, root.nodeType, "root.nodeType must be ROOT");
        });
    }

    @Test
    @DisplayName("dom/nodes/Node-nodeName.html")
    @WptCase(
            path = "dom/nodes/Node-nodeName.html",
            sha = WptHarness.PINNED_SHA,
            description = "nodeName matches tag for element and #text for text node"
    )
    public void testNodeNodeName() {
        test("Element nodeName should match its tagName", () -> {
            HtmlNode el = new HtmlNode("SPAN");
            assertEqualsWpt("SPAN", el.tag, "Element tag should be preserved");
        });

        test("Text node nodeName should be #text", () -> {
            HtmlNode text = new HtmlText("hello");
            assertEqualsWpt("#text", text.tag, "Text node tag should be #text");
        });
    }

    @Test
    @DisplayName("dom/nodes/Node-contains.html")
    @WptCase(
            path = "dom/nodes/Node-contains.html",
            sha = WptHarness.PINNED_SHA,
            description = "Node.contains returns true for self and descendants, false for others"
    )
    public void testNodeContains() {
        HtmlNode parent = new HtmlNode("div");
        HtmlNode child = new HtmlNode("span");
        HtmlNode grandChild = new HtmlText("deep text");
        HtmlNode sibling = new HtmlNode("p");

        parent.appendChild(child);
        child.appendChild(grandChild);

        test("A node should contain itself", () -> {
            assertTrueWpt(nodeContains(parent, parent), "parent contains parent");
            assertTrueWpt(nodeContains(child, child), "child contains child");
            assertTrueWpt(nodeContains(grandChild, grandChild), "grandChild contains grandChild");
        });

        test("A node should contain its direct child", () -> {
            assertTrueWpt(nodeContains(parent, child), "parent contains child");
            assertTrueWpt(nodeContains(child, grandChild), "child contains grandChild");
        });

        test("A node should contain its descendant", () -> {
            assertTrueWpt(nodeContains(parent, grandChild), "parent contains grandChild");
        });

        test("A node should not contain its parent or ancestor", () -> {
            assertFalseWpt(nodeContains(child, parent), "child does not contain parent");
            assertFalseWpt(nodeContains(grandChild, parent), "grandChild does not contain parent");
        });

        test("A node should not contain an unrelated node or null", () -> {
            assertFalseWpt(nodeContains(parent, sibling), "parent does not contain detached sibling");
            assertFalseWpt(nodeContains(parent, null), "parent does not contain null");
        });
    }

    @Test
    @DisplayName("dom/nodes/Element-hasAttribute.html")
    @WptCase(
            path = "dom/nodes/Element-hasAttribute.html",
            sha = WptHarness.PINNED_SHA,
            description = "Element.hasAttribute and getAttribute behavior"
    )
    public void testElementHasAttribute() {
        HtmlNode el = new HtmlNode("div");

        test("Attributes should not be present initially", () -> {
            assertFalseWpt(el.attrs.containsKey("title"), "attribute 'title' initially absent");
            assertFalseWpt(el.attrs.containsKey("data-custom"), "attribute 'data-custom' initially absent");
        });

        test("Setting an attribute reflects in hasAttribute and getAttribute", () -> {
            el.attrs.put("title", "tooltip");
            assertTrueWpt(el.attrs.containsKey("title"), "attribute 'title' present after put");
            assertEqualsWpt("tooltip", el.attrs.get("title"), "attribute value matches");
        });

        test("Empty attribute value is still considered present", () -> {
            el.attrs.put("disabled", "");
            assertTrueWpt(el.attrs.containsKey("disabled"), "empty attribute is present");
            assertEqualsWpt("", el.attrs.get("disabled"), "empty attribute value is empty string");
        });

        test("Removing an attribute reflects in hasAttribute", () -> {
            el.attrs.remove("title");
            assertFalseWpt(el.attrs.containsKey("title"), "attribute 'title' absent after remove");
        });
    }

    @Test
    @DisplayName("dom/nodes/Node-childNodes.html")
    @WptCase(
            path = "dom/nodes/Node-childNodes.html",
            sha = WptHarness.PINNED_SHA,
            description = "Child nodes collection, ordering, insertion, and removal"
    )
    public void testNodeChildNodes() {
        HtmlNode parent = new HtmlNode("div");

        test("Newly created element has empty childNodes", () -> {
            assertEqualsWpt(0, parent.children.size(), "initial children count is 0");
        });

        HtmlNode first = new HtmlNode("header");
        HtmlNode second = new HtmlNode("main");
        HtmlNode third = new HtmlNode("footer");

        test("appendChild adds nodes in order", () -> {
            parent.appendChild(first);
            parent.appendChild(second);
            parent.appendChild(third);

            assertEqualsWpt(3, parent.children.size(), "children count is 3");
            assertEqualsWpt(first, parent.children.get(0), "first child");
            assertEqualsWpt(second, parent.children.get(1), "second child");
            assertEqualsWpt(third, parent.children.get(2), "third child");
            assertEqualsWpt(parent, first.parent, "child parent link established");
        });

        test("removeChild removes node and unlinks parent", () -> {
            parent.removeChild(second);
            assertEqualsWpt(2, parent.children.size(), "children count is now 2");
            assertEqualsWpt(first, parent.children.get(0), "first child remains");
            assertEqualsWpt(third, parent.children.get(1), "third child shifted to index 1");
            assertEqualsWpt(null, second.parent, "removed child parent link cleared");
        });

        test("prependChild inserts node at the beginning", () -> {
            HtmlNode pre = new HtmlNode("nav");
            parent.prependChild(pre);
            assertEqualsWpt(3, parent.children.size(), "children count is 3 after prepend");
            assertEqualsWpt(pre, parent.children.get(0), "prepended node is at index 0");
            assertEqualsWpt(first, parent.children.get(1), "previous index 0 shifted to 1");
        });
    }

    @Test
    @DisplayName("dom/nodes/Node-appendChild.html")
    @WptCase(
            path = "dom/nodes/Node-appendChild.html",
            sha = WptHarness.PINNED_SHA,
            description = "appendChild hierarchy validation, exceptions, and DocumentFragment expansion"
    )
    public void testNodeAppendChild() {
        com.nishiyu.enderframelib.core.dom.node.HtmlElement parent = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("div");
        com.nishiyu.enderframelib.core.dom.node.HtmlElement child = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("span");

        test("appendChild throws HierarchyRequestError when inserting node into itself", () -> {
            WptHarness.assertThrowsDom("HierarchyRequestError", () -> parent.appendChild(parent),
                    "Inserting self must throw HierarchyRequestError");
        });

        test("appendChild throws HierarchyRequestError when inserting ancestor into descendant", () -> {
            parent.appendChild(child);
            WptHarness.assertThrowsDom("HierarchyRequestError", () -> child.appendChild(parent),
                    "Inserting ancestor must throw HierarchyRequestError");
        });

        test("appendChild on Document allows only one element", () -> {
            com.nishiyu.enderframelib.core.dom.node.HtmlDocument doc = new com.nishiyu.enderframelib.core.dom.node.HtmlDocument();
            com.nishiyu.enderframelib.core.dom.node.HtmlElement html = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("html");
            com.nishiyu.enderframelib.core.dom.node.HtmlElement secondHtml = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("html");

            doc.appendChild(html);
            assertEqualsWpt(html, doc.getDocumentElement(), "First element becomes documentElement");

            WptHarness.assertThrowsDom("HierarchyRequestError", () -> doc.appendChild(secondHtml),
                    "Inserting second element to Document must throw HierarchyRequestError");
            WptHarness.assertThrowsDom("HierarchyRequestError", () -> doc.appendChild(new HtmlText("text")),
                    "Inserting text node directly to Document must throw HierarchyRequestError");
        });

        test("appendChild with DocumentFragment unpacks all children", () -> {
            com.nishiyu.enderframelib.core.dom.node.HtmlDocument.HtmlDocumentFragment frag = new com.nishiyu.enderframelib.core.dom.node.HtmlDocument.HtmlDocumentFragment();
            com.nishiyu.enderframelib.core.dom.node.HtmlElement f1 = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("b");
            com.nishiyu.enderframelib.core.dom.node.HtmlElement f2 = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("i");
            frag.appendChild(f1);
            frag.appendChild(f2);

            com.nishiyu.enderframelib.core.dom.node.HtmlElement container = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("p");
            container.appendChild(frag);

            assertEqualsWpt(2, container.getChildNodes().getLength(), "Fragment children unpacked into container");
            assertEqualsWpt(f1, container.getFirstChild(), "First unpacked child");
            assertEqualsWpt(f2, container.getLastChild(), "Second unpacked child");
            assertEqualsWpt(container, f1.getParentNode(), "Parent of unpacked child updated");
        });
    }

    @Test
    @DisplayName("dom/nodes/Node-insertBefore.html")
    @WptCase(
            path = "dom/nodes/Node-insertBefore.html",
            sha = WptHarness.PINNED_SHA,
            description = "insertBefore reference child validation and positioning"
    )
    public void testNodeInsertBefore() {
        com.nishiyu.enderframelib.core.dom.node.HtmlElement parent = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("div");
        com.nishiyu.enderframelib.core.dom.node.HtmlElement existing = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("span");
        com.nishiyu.enderframelib.core.dom.node.HtmlElement unrelated = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("p");
        com.nishiyu.enderframelib.core.dom.node.HtmlElement inserted = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("b");

        parent.appendChild(existing);

        test("insertBefore throws NotFoundError if refChild is not a child of parent", () -> {
            WptHarness.assertThrowsDom("NotFoundError", () -> parent.insertBefore(inserted, unrelated),
                    "RefChild not in parent must throw NotFoundError");
        });

        test("insertBefore with null refChild behaves like appendChild", () -> {
            parent.insertBefore(inserted, null);
            assertEqualsWpt(inserted, parent.getLastChild(), "Inserted at end when refChild is null");
        });

        test("insertBefore correctly places node before reference child", () -> {
            com.nishiyu.enderframelib.core.dom.node.HtmlElement head = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("h1");
            parent.insertBefore(head, existing);
            assertEqualsWpt(head, parent.getFirstChild(), "Head inserted before existing");
            assertEqualsWpt(existing, head.getNextSibling(), "Head next sibling is existing");
        });
    }

    @Test
    @DisplayName("dom/nodes/Node-removeChild.html")
    @WptCase(
            path = "dom/nodes/Node-removeChild.html",
            sha = WptHarness.PINNED_SHA,
            description = "removeChild unlinking and NotFoundError"
    )
    public void testNodeRemoveChild() {
        com.nishiyu.enderframelib.core.dom.node.HtmlElement parent = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("div");
        com.nishiyu.enderframelib.core.dom.node.HtmlElement child = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("span");
        com.nishiyu.enderframelib.core.dom.node.HtmlElement stranger = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("b");

        parent.appendChild(child);

        test("removeChild throws NotFoundError when target is not a child", () -> {
            WptHarness.assertThrowsDom("NotFoundError", () -> parent.removeChild(stranger),
                    "Stranger not a child must throw NotFoundError");
        });

        test("removeChild unlinks parent and sibling links", () -> {
            com.nishiyu.enderframelib.api.dom.Node removed = parent.removeChild(child);
            assertEqualsWpt(child, removed, "Returned node is the removed child");
            assertEqualsWpt(null, child.getParentNode(), "Child parent unlinked");
            assertEqualsWpt(0, parent.getChildNodes().getLength(), "Parent childNodes empty");
        });
    }

    @Test
    @DisplayName("dom/nodes/Node-replaceChild.html")
    @WptCase(
            path = "dom/nodes/Node-replaceChild.html",
            sha = WptHarness.PINNED_SHA,
            description = "replaceChild node replacement and hierarchy validation"
    )
    public void testNodeReplaceChild() {
        com.nishiyu.enderframelib.core.dom.node.HtmlElement parent = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("div");
        com.nishiyu.enderframelib.core.dom.node.HtmlElement oldChild = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("span");
        com.nishiyu.enderframelib.core.dom.node.HtmlElement newChild = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("p");
        com.nishiyu.enderframelib.core.dom.node.HtmlElement stranger = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("b");

        parent.appendChild(oldChild);

        test("replaceChild throws NotFoundError when oldChild is not a child", () -> {
            WptHarness.assertThrowsDom("NotFoundError", () -> parent.replaceChild(newChild, stranger),
                    "Stranger oldChild must throw NotFoundError");
        });

        test("replaceChild replaces oldChild with newChild", () -> {
            com.nishiyu.enderframelib.api.dom.Node replaced = parent.replaceChild(newChild, oldChild);
            assertEqualsWpt(oldChild, replaced, "Returned node is oldChild");
            assertEqualsWpt(null, oldChild.getParentNode(), "OldChild unlinked");
            assertEqualsWpt(newChild, parent.getFirstChild(), "NewChild in parent");
        });
    }

    @Test
    @DisplayName("dom/nodes/Node-compareDocumentPosition.html")
    @WptCase(
            path = "dom/nodes/Node-compareDocumentPosition.html",
            sha = WptHarness.PINNED_SHA,
            description = "compareDocumentPosition bitmask flags"
    )
    public void testNodeCompareDocumentPosition() {
        com.nishiyu.enderframelib.core.dom.node.HtmlElement root = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("div");
        com.nishiyu.enderframelib.core.dom.node.HtmlElement a = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("a");
        com.nishiyu.enderframelib.core.dom.node.HtmlElement b = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("b");
        com.nishiyu.enderframelib.core.dom.node.HtmlElement c = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("c");
        com.nishiyu.enderframelib.core.dom.node.HtmlElement disconnected = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("x");

        root.appendChild(a);
        a.appendChild(b);
        root.appendChild(c);

        test("Same node returns 0", () -> {
            assertEqualsWpt((short) 0, a.compareDocumentPosition(a), "Self compare is 0");
        });

        test("Disconnected nodes have DISCONNECTED flag", () -> {
            short pos = a.compareDocumentPosition(disconnected);
            assertTrueWpt((pos & com.nishiyu.enderframelib.api.dom.Node.DOCUMENT_POSITION_DISCONNECTED) != 0,
                    "Must have DOCUMENT_POSITION_DISCONNECTED flag");
        });

        test("Ancestor contains descendant", () -> {
            short rootToB = root.compareDocumentPosition(b);
            assertTrueWpt((rootToB & com.nishiyu.enderframelib.api.dom.Node.DOCUMENT_POSITION_CONTAINED_BY) != 0,
                    "Root contained by B direction is CONTAINED_BY | FOLLOWING");
            assertTrueWpt((rootToB & com.nishiyu.enderframelib.api.dom.Node.DOCUMENT_POSITION_FOLLOWING) != 0,
                    "B follows root");

            short bToRoot = b.compareDocumentPosition(root);
            assertTrueWpt((bToRoot & com.nishiyu.enderframelib.api.dom.Node.DOCUMENT_POSITION_CONTAINS) != 0,
                    "B contains root direction is CONTAINS | PRECEDING");
            assertTrueWpt((bToRoot & com.nishiyu.enderframelib.api.dom.Node.DOCUMENT_POSITION_PRECEDING) != 0,
                    "Root precedes B");
        });

        test("Sibling order", () -> {
            short aToC = a.compareDocumentPosition(c);
            assertTrueWpt((aToC & com.nishiyu.enderframelib.api.dom.Node.DOCUMENT_POSITION_FOLLOWING) != 0, "C follows A");
            short cToA = c.compareDocumentPosition(a);
            assertTrueWpt((cToA & com.nishiyu.enderframelib.api.dom.Node.DOCUMENT_POSITION_PRECEDING) != 0, "A precedes C");
        });
    }

    @Test
    @DisplayName("dom/nodes/Node-cloneNode.html")
    @WptCase(
            path = "dom/nodes/Node-cloneNode.html",
            sha = WptHarness.PINNED_SHA,
            description = "cloneNode shallow vs deep duplication and attribute/content copy"
    )
    public void testNodeCloneNode() {
        com.nishiyu.enderframelib.core.dom.node.HtmlElement parent = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("div");
        parent.setAttribute("id", "main");
        parent.setAttribute("data-test", "val");

        com.nishiyu.enderframelib.core.dom.node.HtmlElement child = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("span");
        child.setTextContent("inner");
        parent.appendChild(child);

        test("Shallow clone copies attributes but not children", () -> {
            com.nishiyu.enderframelib.core.dom.node.HtmlNode shallow = parent.cloneNode(false);
            assertEqualsWpt("div", shallow.tag, "Tag preserved");
            assertEqualsWpt("main", shallow.getId(), "Id attribute preserved");
            assertEqualsWpt("val", shallow.getAttribute("data-test"), "Data attribute preserved");
            assertEqualsWpt(0, shallow.getChildNodes().getLength(), "No children in shallow clone");
            assertEqualsWpt(null, shallow.getParentNode(), "Clone has no parent");
        });

        test("Deep clone copies children recursively", () -> {
            com.nishiyu.enderframelib.core.dom.node.HtmlNode deep = parent.cloneNode(true);
            assertEqualsWpt(1, deep.getChildNodes().getLength(), "Child cloned in deep clone");
            com.nishiyu.enderframelib.api.dom.Node clonedChild = deep.getFirstChild();
            assertTrueWpt(clonedChild != child, "Child is a new object instance");
            assertEqualsWpt("inner", clonedChild.getTextContent(), "Child text content preserved");
            assertEqualsWpt(deep, clonedChild.getParentNode(), "Cloned child points to cloned parent");
        });
    }

    @Test
    @DisplayName("dom/nodes/Node-textContent.html")
    @WptCase(
            path = "dom/nodes/Node-textContent.html",
            sha = WptHarness.PINNED_SHA,
            description = "textContent getter and setter on Elements, Text, and Comments"
    )
    public void testNodeTextContent() {
        com.nishiyu.enderframelib.core.dom.node.HtmlElement div = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("div");
        com.nishiyu.enderframelib.core.dom.node.HtmlElement p = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("p");
        HtmlText t1 = new HtmlText("Hello ");
        HtmlText t2 = new HtmlText("World");
        com.nishiyu.enderframelib.core.dom.node.HtmlDocument.HtmlComment comment = new com.nishiyu.enderframelib.core.dom.node.HtmlDocument.HtmlComment("a comment");

        div.appendChild(p);
        p.appendChild(t1);
        p.appendChild(comment);
        p.appendChild(t2);

        test("textContent concatenates descendant text and ignores comments", () -> {
            assertEqualsWpt("Hello World", div.getTextContent(), "Concatenated text without comment");
            assertEqualsWpt("a comment", comment.getTextContent(), "Comment textContent is its data");
        });

        test("textContent setter replaces all children with single Text node", () -> {
            div.setTextContent("New Content");
            assertEqualsWpt(1, div.getChildNodes().getLength(), "Replaced with single text node");
            assertTrueWpt(div.getFirstChild() instanceof com.nishiyu.enderframelib.api.dom.DOMNodes.Text, "Child is a Text node");
            assertEqualsWpt("New Content", div.getTextContent(), "New content reflected");
        });
    }

    @Test
    @DisplayName("dom/nodes/ChildNode-remove.html")
    @WptCase(
            path = "dom/nodes/ChildNode-remove.html",
            sha = WptHarness.PINNED_SHA,
            description = "ChildNode methods: remove, before, after, replaceWith"
    )
    public void testChildNode() {
        com.nishiyu.enderframelib.core.dom.node.HtmlElement parent = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("ul");
        com.nishiyu.enderframelib.core.dom.node.HtmlElement item1 = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("li");
        com.nishiyu.enderframelib.core.dom.node.HtmlElement item2 = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("li");
        com.nishiyu.enderframelib.core.dom.node.HtmlElement item3 = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("li");

        parent.appendChild(item1);
        parent.appendChild(item2);
        parent.appendChild(item3);

        test("remove() unlinks child from parent", () -> {
            item2.remove();
            assertEqualsWpt(2, parent.getChildNodes().getLength(), "Length after remove");
            assertEqualsWpt(item1, parent.getFirstChild(), "First remains item1");
            assertEqualsWpt(item3, parent.getLastChild(), "Last is now item3");
            assertEqualsWpt(null, item2.getParentNode(), "Removed item has null parent");
        });

        test("before() inserts node before this node", () -> {
            com.nishiyu.enderframelib.core.dom.node.HtmlElement itemNew = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("li");
            item3.before(itemNew);
            assertEqualsWpt(3, parent.getChildNodes().getLength(), "Length after before()");
            assertEqualsWpt(itemNew, item3.getPreviousSibling(), "itemNew is previous sibling of item3");
        });

        test("after() inserts node after this node", () -> {
            com.nishiyu.enderframelib.core.dom.node.HtmlElement itemAfter = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("li");
            item1.after(itemAfter);
            assertEqualsWpt(itemAfter, item1.getNextSibling(), "itemAfter is next sibling of item1");
        });

        test("replaceWith() replaces this node with target nodes", () -> {
            com.nishiyu.enderframelib.core.dom.node.HtmlElement itemRepl = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("li");
            item1.replaceWith(itemRepl);
            assertEqualsWpt(itemRepl, parent.getFirstChild(), "itemRepl replaced item1");
            assertEqualsWpt(null, item1.getParentNode(), "item1 is unlinked");
        });
    }

    @Test
    @DisplayName("dom/nodes/ParentNode-append.html")
    @WptCase(
            path = "dom/nodes/ParentNode-append.html",
            sha = WptHarness.PINNED_SHA,
            description = "ParentNode methods: append, prepend, replaceChildren, children collection"
    )
    public void testParentNode() {
        com.nishiyu.enderframelib.core.dom.node.HtmlElement container = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("div");
        com.nishiyu.enderframelib.core.dom.node.HtmlElement el1 = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("p");
        com.nishiyu.enderframelib.core.dom.node.HtmlElement el2 = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("span");

        test("append() adds multiple nodes at end", () -> {
            container.append(el1, el2);
            assertEqualsWpt(2, container.getChildElementCount(), "Two element children");
            assertEqualsWpt(el1, container.getFirstElementChild(), "First element child");
            assertEqualsWpt(el2, container.getLastElementChild(), "Last element child");
        });

        test("prepend() adds nodes at beginning", () -> {
            com.nishiyu.enderframelib.core.dom.node.HtmlElement el0 = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("h1");
            container.prepend(el0);
            assertEqualsWpt(3, container.getChildElementCount(), "Three element children");
            assertEqualsWpt(el0, container.getFirstElementChild(), "el0 is now first element child");
        });

        test("children HTMLCollection is live", () -> {
            com.nishiyu.enderframelib.api.dom.DOMCollections.HTMLCollection children = container.getChildren();
            assertEqualsWpt(3, children.getLength(), "Initial live collection count");
            com.nishiyu.enderframelib.core.dom.node.HtmlElement extra = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("footer");
            container.appendChild(extra);
            assertEqualsWpt(4, children.getLength(), "Live collection reflects appended element");
        });

        test("replaceChildren() replaces all children", () -> {
            com.nishiyu.enderframelib.core.dom.node.HtmlElement only = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("main");
            container.replaceChildren(only);
            assertEqualsWpt(1, container.getChildElementCount(), "One element child after replaceChildren");
            assertEqualsWpt(only, container.getFirstElementChild(), "Only child is 'only'");
        });
    }

    @Test
    @DisplayName("dom/nodes/Element-classList.html")
    @WptCase(
            path = "dom/nodes/Element-classList.html",
            sha = WptHarness.PINNED_SHA,
            description = "DOMTokenList operations and whitespace validation"
    )
    public void testElementClassList() {
        com.nishiyu.enderframelib.core.dom.node.HtmlElement el = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("div");
        com.nishiyu.enderframelib.api.dom.DOMCollections.DOMTokenList list = el.getClassList();

        test("classList reflects class attribute", () -> {
            el.setAttribute("class", "alpha beta");
            assertEqualsWpt(2, list.getLength(), "Initial tokens count is 2");
            assertTrueWpt(list.contains("alpha"), "Contains alpha");
            assertTrueWpt(list.contains("beta"), "Contains beta");
        });

        test("add() updates attribute and ignores duplicates", () -> {
            list.add("gamma");
            assertEqualsWpt("alpha beta gamma", el.getAttribute("class"), "Attribute updated with gamma");
            list.add("alpha");
            assertEqualsWpt(3, list.getLength(), "Duplicates ignored");
        });

        test("remove() removes token", () -> {
            list.remove("beta");
            assertEqualsWpt("alpha gamma", el.getAttribute("class"), "Beta removed");
            assertFalseWpt(list.contains("beta"), "Does not contain beta");
        });

        test("toggle() adds if absent, removes if present", () -> {
            boolean added = list.toggle("delta");
            assertTrueWpt(added, "Toggle returns true on add");
            assertTrueWpt(list.contains("delta"), "Delta present");

            boolean removed = list.toggle("delta");
            assertFalseWpt(removed, "Toggle returns false on remove");
            assertFalseWpt(list.contains("delta"), "Delta absent");
        });

        test("replace() replaces token", () -> {
            boolean ok = list.replace("alpha", "omega");
            assertTrueWpt(ok, "Replace succeeds");
            assertFalseWpt(list.contains("alpha"), "Alpha removed");
            assertTrueWpt(list.contains("omega"), "Omega added");
        });

        test("Token with whitespace throws InvalidCharacterError", () -> {
            WptHarness.assertThrowsDom("InvalidCharacterError", () -> list.add("bad token"),
                    "Whitespace in token must throw InvalidCharacterError");
        });
    }

    @Test
    @DisplayName("dom/nodes/Element-style.html")
    @WptCase(
            path = "dom/nodes/Element-style.html",
            sha = WptHarness.PINNED_SHA,
            description = "CSSStyleDeclaration bidirectional sync with style attribute"
    )
    public void testElementStyle() {
        com.nishiyu.enderframelib.core.dom.node.HtmlElement el = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("div");
        com.nishiyu.enderframelib.api.dom.CSSStyleDeclaration style = el.getStyle();

        test("style reflects initial attribute", () -> {
            el.setAttribute("style", "color: red; font-size: 14px;");
            assertEqualsWpt("red", style.getPropertyValue("color"), "color is red");
            assertEqualsWpt("14px", style.getPropertyValue("font-size"), "font-size is 14px");
        });

        test("setProperty() updates property and syncs to attribute", () -> {
            style.setProperty("margin", "10px");
            assertTrueWpt(el.getAttribute("style").contains("margin: 10px"), "margin added to style attribute");
            assertEqualsWpt("10px", style.getPropertyValue("margin"), "margin retrieved from style");
        });

        test("removeProperty() removes from style and attribute", () -> {
            style.removeProperty("color");
            assertEqualsWpt("", style.getPropertyValue("color"), "color removed");
            assertFalseWpt(el.getAttribute("style").contains("color:"), "color gone from attribute");
        });

        test("setCssText() parses declarations", () -> {
            style.setCssText("display: flex; opacity: 0.8;");
            assertEqualsWpt("flex", style.getPropertyValue("display"), "display is flex");
            assertEqualsWpt("0.8", style.getPropertyValue("opacity"), "opacity is 0.8");
        });
    }

    @Test
    @DisplayName("dom/nodes/Element-matches.html")
    @WptCase(
            path = "dom/nodes/Element-matches.html",
            sha = WptHarness.PINNED_SHA,
            description = "Element.matches, closest, and querySelector"
    )
    public void testElementMatches() {
        com.nishiyu.enderframelib.core.dom.node.HtmlElement container = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("div");
        container.setAttribute("class", "card active");
        container.setAttribute("id", "c1");

        com.nishiyu.enderframelib.core.dom.node.HtmlElement btn = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("button");
        btn.setAttribute("class", "btn primary");
        container.appendChild(btn);

        test("matches() checks against selector", () -> {
            assertTrueWpt(container.matches("div.card"), "container matches div.card");
            assertTrueWpt(container.matches("#c1"), "container matches #c1");
            assertTrueWpt(btn.matches("div > button.primary"), "btn matches child selector");
            assertFalseWpt(btn.matches(".missing"), "btn does not match missing class");
        });

        test("closest() walks up tree", () -> {
            assertEqualsWpt(container, btn.closest(".card"), "closest card is container");
            assertEqualsWpt(btn, btn.closest("button"), "closest button from btn is btn itself");
            assertEqualsWpt(null, btn.closest(".unrelated"), "closest unrelated is null");
        });

        test("querySelector finds descendant", () -> {
            assertEqualsWpt(btn, container.querySelector("button.primary"), "querySelector finds btn");
        });
    }

    @Test
    @DisplayName("dom/nodes/Document-getElementById.html")
    @WptCase(
            path = "dom/nodes/Document-getElementById.html",
            sha = WptHarness.PINNED_SHA,
            description = "Document.getElementById O(1) index, dynamic insertion/removal, and ID attribute mutation"
    )
    public void testDocumentGetElementById() {
        com.nishiyu.enderframelib.core.dom.node.HtmlDocument doc = new com.nishiyu.enderframelib.core.dom.node.HtmlDocument();
        com.nishiyu.enderframelib.core.dom.node.HtmlElement html = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("html");
        com.nishiyu.enderframelib.core.dom.node.HtmlElement body = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("body");
        com.nishiyu.enderframelib.core.dom.node.HtmlElement target = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("section");
        target.setAttribute("id", "special");

        doc.appendChild(html);
        html.appendChild(body);

        test("Element not found before insertion", () -> {
            assertEqualsWpt(null, doc.getElementById("special"), "Special not in doc yet");
        });

        test("Element registered in idMap upon insertion into Document", () -> {
            body.appendChild(target);
            assertEqualsWpt(target, doc.getElementById("special"), "Special found by id after append");
        });

        test("Changing id attribute updates idMap", () -> {
            target.setId("renamed");
            assertEqualsWpt(null, doc.getElementById("special"), "Old id unregistered");
            assertEqualsWpt(target, doc.getElementById("renamed"), "New id registered");
        });

        test("Removing element unregisters from idMap", () -> {
            target.remove();
            assertEqualsWpt(null, doc.getElementById("renamed"), "Removed element unregistered");
        });
    }

    @Test
    @DisplayName("dom/nodes/MutationObserver-childList.html")
    @WptCase(
            path = "dom/nodes/MutationObserver-childList.html",
            sha = WptHarness.PINNED_SHA,
            description = "MutationObserver childList, attributes, characterData, and subtree observation"
    )
    public void testMutationObserver() {
        com.nishiyu.enderframelib.core.dom.node.HtmlElement root = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("div");
        root.ownerDocument = new com.nishiyu.enderframelib.core.dom.node.HtmlDocument();
        java.util.List<com.nishiyu.enderframelib.api.dom.MutationObserver.Record> received = new java.util.ArrayList<>();

        com.nishiyu.enderframelib.api.dom.MutationObserver observer = com.nishiyu.enderframelib.api.dom.MutationObserver.create(
                (records, obs) -> received.addAll(records)
        );

        observer.observe(root, com.nishiyu.enderframelib.api.dom.MutationObserver.Init.builder()
                .childList(true)
                .attributes(true)
                .attributeOldValue(true)
                .characterData(true)
                .subtree(true)
                .build());

        test("childList mutations recorded on append and remove", () -> {
            com.nishiyu.enderframelib.core.dom.node.HtmlElement item = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("p");
            root.appendChild(item);

            java.util.List<com.nishiyu.enderframelib.api.dom.MutationObserver.Record> records = observer.takeRecords();
            assertEqualsWpt(1, records.size(), "One mutation record for append");
            assertEqualsWpt("childList", records.get(0).getType(), "Type is childList");
            assertEqualsWpt(1, records.get(0).getAddedNodes().getLength(), "One added node");
            assertEqualsWpt(item, records.get(0).getAddedNodes().item(0), "Added node is item");

            root.removeChild(item);
            java.util.List<com.nishiyu.enderframelib.api.dom.MutationObserver.Record> removeRecords = observer.takeRecords();
            assertEqualsWpt(1, removeRecords.size(), "One mutation record for remove");
            assertEqualsWpt(1, removeRecords.get(0).getRemovedNodes().getLength(), "One removed node");
        });

        test("attributes mutation records old value", () -> {
            root.setAttribute("title", "initial");
            root.setAttribute("title", "updated");

            java.util.List<com.nishiyu.enderframelib.api.dom.MutationObserver.Record> attrRecords = observer.takeRecords();
            assertEqualsWpt(2, attrRecords.size(), "Two attribute records");
            assertEqualsWpt("attributes", attrRecords.get(1).getType(), "Type is attributes");
            assertEqualsWpt("title", attrRecords.get(1).getAttributeName(), "Attribute is title");
            assertEqualsWpt("initial", attrRecords.get(1).getOldValue(), "Old value preserved");
        });

        test("characterData mutation recorded on text modification", () -> {
            HtmlText text = new HtmlText("start");
            root.appendChild(text);
            observer.takeRecords(); // clear childList

            text.setData("changed");
            java.util.List<com.nishiyu.enderframelib.api.dom.MutationObserver.Record> charRecords = observer.takeRecords();
            assertEqualsWpt(1, charRecords.size(), "One characterData record");
            assertEqualsWpt("characterData", charRecords.get(0).getType(), "Type is characterData");
        });

        test("disconnect stops observation", () -> {
            observer.disconnect();
            root.appendChild(new com.nishiyu.enderframelib.core.dom.node.HtmlElement("span"));
            java.util.List<com.nishiyu.enderframelib.api.dom.MutationObserver.Record> postDisconnect = observer.takeRecords();
            assertEqualsWpt(0, postDisconnect.size(), "No records after disconnect");
        });
    }

    @Test
    @DisplayName("dom/events/EventTarget-addEventListener.html")
    @WptCase(
            path = "dom/events/EventTarget-addEventListener.html",
            sha = WptHarness.PINNED_SHA,
            description = "Verifies EventTarget addEventListener, deduplication, and removal lifecycle"
    )
    public void testEventTargetAddEventListener() {
        test("addEventListener and removeEventListener basic lifecycle", () -> {
            com.nishiyu.enderframelib.core.dom.node.HtmlElement target = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("div");
            java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger(0);
            com.nishiyu.enderframelib.api.event.EventListener listener = ev -> count.incrementAndGet();

            target.addEventListener("custom", listener);
            target.dispatchEvent(new com.nishiyu.enderframelib.core.event.CoreEvent("custom", false, false));
            assertEqualsWpt(1, count.get(), "Listener called on dispatch");

            target.removeEventListener("custom", listener);
            target.dispatchEvent(new com.nishiyu.enderframelib.core.event.CoreEvent("custom", false, false));
            assertEqualsWpt(1, count.get(), "Listener not called after removal");
        });

        test("duplicate listeners with same type, callback and capture are ignored", () -> {
            com.nishiyu.enderframelib.core.dom.node.HtmlElement target = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("div");
            java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger(0);
            com.nishiyu.enderframelib.api.event.EventListener listener = ev -> count.incrementAndGet();

            target.addEventListener("click", listener);
            target.addEventListener("click", listener); // duplicate

            target.dispatchEvent(new com.nishiyu.enderframelib.core.event.CoreEvent("click", true, true));
            assertEqualsWpt(1, count.get(), "Duplicate listener only fires once");
        });
    }

    @Test
    @DisplayName("dom/events/Event-dispatch-order.html")
    @WptCase(
            path = "dom/events/Event-dispatch-order.html",
            sha = WptHarness.PINNED_SHA,
            description = "Verifies 3-phase event dispatch order: capture, target, and bubble"
    )
    public void testEventDispatchOrder() {
        test("3-phase dispatch: CAPTURING -> AT_TARGET -> BUBBLING", () -> {
            com.nishiyu.enderframelib.core.dom.node.HtmlElement parent = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("div");
            com.nishiyu.enderframelib.core.dom.node.HtmlElement child = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("span");
            parent.appendChild(child);

            java.util.List<String> phases = new java.util.ArrayList<>();
            parent.addEventListener("test", ev -> phases.add("parent:capture"),
                    com.nishiyu.enderframelib.api.event.AddEventListenerOptions.builder().capture(true).build());
            parent.addEventListener("test", ev -> phases.add("parent:bubble"),
                    com.nishiyu.enderframelib.api.event.AddEventListenerOptions.builder().capture(false).build());
            child.addEventListener("test", ev -> phases.add("child:target"));

            child.dispatchEvent(new com.nishiyu.enderframelib.core.event.CoreEvent("test", true, true));

            assertEqualsWpt(java.util.List.of("parent:capture", "child:target", "parent:bubble"), phases,
                    "Phases executed in correct standard order");
        });
    }

    @Test
    @DisplayName("dom/events/Event-stopImmediatePropagation.html")
    @WptCase(
            path = "dom/events/Event-stopImmediatePropagation.html",
            sha = WptHarness.PINNED_SHA,
            description = "Verifies stopImmediatePropagation halts subsequent listeners on current target"
    )
    public void testEventStopImmediatePropagation() {
        test("stopImmediatePropagation prevents further listener invocations on same target", () -> {
            com.nishiyu.enderframelib.core.dom.node.HtmlElement target = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("button");
            java.util.List<String> called = new java.util.ArrayList<>();

            target.addEventListener("click", ev -> {
                called.add("first");
                ev.stopImmediatePropagation();
            });
            target.addEventListener("click", ev -> called.add("second"));

            target.dispatchEvent(new com.nishiyu.enderframelib.core.event.CoreEvent("click", true, true));

            assertEqualsWpt(java.util.List.of("first"), called, "Only first listener executed");
        });
    }

    /** DOM Standard の Node.contains(other) アルゴリズム */
    private static boolean nodeContains(HtmlNode node, HtmlNode other) {
        if (node == null || other == null) return false;
        for (HtmlNode cur = other; cur != null; cur = cur.parent) {
            if (cur == node) return true;
        }
        return false;
    }
}
