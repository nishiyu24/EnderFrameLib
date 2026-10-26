package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument.HtmlDocumentFragment;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import com.nishiyu.enderframelib.network.DomMutationRecord;
import com.nishiyu.enderframelib.network.HtmlNodeSerializer;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R9: {@code <template>} の中身を不活性な {@link HtmlDocumentFragment} に置く。
 * 期待値は Edge 156 の {@code template.content} / {@code innerHTML} / {@code outerHTML} / {@code cloneNode}。
 */
public class TemplateElementTest {

    @Test
    public void parserPutsContentsInSeparateFragment() {
        HtmlNode root = HtmlParser.parseFragment(
                "<template id=t><div id=inside>hi</div><span>there</span></template>", "body");
        HtmlElement template = (HtmlElement) root.getElementById("t");

        assertNotNull(template);
        assertTrue(template.children.isEmpty(), "template 要素自身は子を持たない");
        HtmlDocumentFragment content = template.getContent();
        assertNotNull(content);
        assertNull(content.getParentNode(), "content は要素の子ではない");
        assertEquals(2, content.children.size());
        assertEquals("div", content.children.get(0).tag);
        assertEquals("inside", content.children.get(0).getId());
        assertEquals("hi", content.children.get(0).getTextContent());
        assertEquals("span", content.children.get(1).tag);
        assertEquals("", template.getTextContent(), "template.textContent は中身を返さない");
    }

    @Test
    public void nestedTemplateHasItsOwnFragment() {
        HtmlNode root = HtmlParser.parseFragment(
                "<template id=outer><template id=inner><b>x</b></template></template>", "body");
        HtmlElement outer = (HtmlElement) root.getElementById("outer");
        HtmlElement inner = (HtmlElement) outer.getContent().children.get(0);

        assertEquals(1, outer.getContent().children.size());
        assertTrue(inner.children.isEmpty());
        assertEquals(1, inner.getContent().children.size());
        assertEquals("b", inner.getContent().children.get(0).tag);
    }

    @Test
    public void parserClosesTemplateWithOpenDescendants() {
        HtmlNode root = HtmlParser.parseFragment(
                "<template id=t><div>a</template><p>after</p>", "body");
        HtmlElement template = (HtmlElement) root.getElementById("t");

        assertEquals(1, template.getContent().children.size());
        assertEquals("div", template.getContent().children.get(0).tag);
        assertEquals("p", root.children.get(1).tag, "</template> の後の要素は template の外");
    }

    @Test
    public void templateInnerHtmlAllowsTableParts() {
        HtmlElement template = new HtmlElement("template");
        template.setInnerHTML("<tr><td>x</td></tr>");

        assertTrue(template.children.isEmpty());
        assertEquals(1, template.getContent().children.size());
        assertEquals("tr", template.getContent().children.get(0).tag);
    }

    @Test
    public void innerAndOuterHtmlSerializeContent() {
        HtmlElement template = new HtmlElement("template");
        template.getContent().appendChild(new HtmlElement("b"));

        assertEquals("<b></b>", template.getInnerHTML());
        assertEquals("<template><b></b></template>", template.getOuterHTML());

        template.setInnerHTML("<i>y</i>");
        assertTrue(template.children.isEmpty());
        assertEquals(1, template.getContent().children.size());
        assertEquals("i", template.getContent().children.get(0).tag);
        assertEquals("y", template.getContent().children.get(0).getTextContent());
    }

    @Test
    public void textContentReadsEmptyAndWritesContent() {
        HtmlElement template = new HtmlElement("template");
        template.getContent().appendChild(new HtmlElement("b"));
        assertEquals("", template.getTextContent());

        template.setTextContent("x");
        assertTrue(template.children.isEmpty(), "setTextContent は要素の子を作らない");
        assertEquals("x", template.getContent().getTextContent());
        assertEquals("", template.getTextContent());
    }

    @Test
    public void cloneNodeCopiesContentOnlyWhenDeep() {
        HtmlElement template = new HtmlElement("template");
        HtmlElement item = new HtmlElement("span");
        item.text = "x";
        template.getContent().appendChild(item);

        HtmlElement shallow = (HtmlElement) template.cloneNode(false);
        assertTrue(shallow.children.isEmpty());
        assertTrue(shallow.getContent().children.isEmpty(), "浅い複製は中身を持たない");

        HtmlElement deep = (HtmlElement) template.cloneNode(true);
        assertTrue(deep.children.isEmpty());
        assertEquals(1, deep.getContent().children.size());
        assertNotSame(item, deep.getContent().children.get(0));
        assertEquals("span", deep.getContent().children.get(0).tag);
        assertEquals("x", deep.getContent().children.get(0).text);
    }

    @Test
    public void networkSerializersRoundTripContent() {
        HtmlElement template = new HtmlElement("template");
        template.setId("t");
        HtmlElement item = new HtmlElement("div");
        item.setId("inside");
        item.text = "hi";
        template.getContent().appendChild(item);

        HtmlElement restored = (HtmlElement) HtmlNodeSerializer.deserialize(HtmlNodeSerializer.serialize(template), null);
        assertTrue(restored.children.isEmpty());
        assertEquals(1, restored.getContent().children.size());
        assertEquals("div", restored.getContent().children.get(0).tag);
        assertEquals("inside", restored.getContent().children.get(0).getId());
        assertEquals("hi", restored.getContent().children.get(0).text);

        CompoundTag duplicated = DomMutationRecord.HtmlNodeSerializer.serialize(template);
        HtmlElement restoredByMutation = (HtmlElement) DomMutationRecord.HtmlNodeSerializer.deserialize(duplicated, null);
        assertTrue(restoredByMutation.children.isEmpty());
        assertEquals(1, restoredByMutation.getContent().children.size());
        assertEquals("inside", restoredByMutation.getContent().children.get(0).getId());
    }

    @Test
    public void bindingsReadContentAndRenderRows() {
        HtmlNode root = HtmlParser.parseFragment(
                "<div id=list data-list=items><template><span>{{name}}</span></template></div>", "body");
        HtmlElement target = (HtmlElement) root.getElementById("list");
        HtmlElement template = (HtmlElement) target.children.get(0);

        List<HtmlNode> extracted = Bindings.extractTemplateChildren(target);
        assertEquals(1, extracted.size());
        assertEquals("span", extracted.get(0).tag);
        assertEquals("{{name}}", extracted.get(0).getTextContent());

        assertTrue(Bindings.renderVirtualList(target, template,
                List.of(Map.of("name", "A"), Map.of("name", "B")), 100, 20, 400, 5));
        assertEquals(1, target.children.stream().filter(c -> "template".equals(c.tag)).count());
        List<HtmlNode> rows = target.children.stream().filter(c -> "span".equals(c.tag)).toList();
        assertEquals(2, rows.size());
        assertEquals("A", rows.get(0).getTextContent());
        assertEquals("B", rows.get(1).getTextContent());
        assertTrue(template.getContent().children.get(0).getTextContent().contains("{{name}}"),
                "描画は雛形ではなく複製を書き換える");
    }

    @Test
    public void documentQueriesDoNotSeeTemplateContent() {
        HtmlNode documentRoot = HtmlParser.parseDocument("<template id=t><div id=inside>x</div></template>");
        assertNull(documentRoot.getElementById("inside"), "文書の id 索引に template の中身は入らない");
        HtmlElement template = (HtmlElement) documentRoot.querySelector("template");
        assertNotNull(template);
        assertEquals("inside", template.getContent().children.get(0).getId());
        assertInstanceOf(HtmlDocument.class, documentRoot);
    }
}
