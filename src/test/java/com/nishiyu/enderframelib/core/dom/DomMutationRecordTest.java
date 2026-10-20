package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.dom.node.*;
import com.nishiyu.enderframelib.core.dom.parser.*;

import com.nishiyu.enderframelib.network.DomMutationRecord;
import com.nishiyu.enderframelib.network.HtmlNodeSerializer;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
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
 * R3: DOM 変異レコード（{@link DomMutationRecord}）が
 * 実 DOM ツリーへ直接適用される挙動を検証するテスト。
 */
public class DomMutationRecordTest {

    private static HtmlNode el(String tag, String id) {
        HtmlNode n = new HtmlElement(tag);
        n.setId(id);
        return n;
    }

    private static HtmlNode tree(String rootId, HtmlNode... children) {
        HtmlNode root = el("div", rootId);
        for (HtmlNode c : children) root.appendChild(c);
        return root;
    }

    private static String shape(HtmlNode parent) {
        StringBuilder sb = new StringBuilder();
        for (HtmlNode c : parent.children) {
            if (!sb.isEmpty()) sb.append(",");
            sb.append(c.tag).append("#").append(c.getId());
        }
        return sb.toString();
    }

    @Test
    public void testTextMutation() {
        HtmlNode root = tree("root", el("p", "p1"));
        HtmlNode p = root.children.get(0);
        p.setTextContent("before");

        List<HtmlNode> dirties = new ArrayList<>();
        DomMutationRecord record = DomMutationRecord.text("p1", "after");
        assertTrue(record.applyTo(root, dirties::add));

        assertEquals("after", p.getTextContent());
        assertEquals(List.of(p), dirties);

        // 同値なら変更なし
        assertFalse(record.applyTo(root, dirties::add));
    }

    @Test
    public void testAttributeMutation() {
        HtmlNode root = tree("root", el("span", "s1"));
        HtmlNode span = root.children.get(0);

        List<HtmlNode> dirties = new ArrayList<>();

        // 属性追加
        DomMutationRecord addAttr = DomMutationRecord.attribute("s1", "title", "hello", false);
        assertTrue(addAttr.applyTo(root, dirties::add));
        assertEquals("hello", span.attrs.get("title"));

        // 属性更新
        DomMutationRecord updateAttr = DomMutationRecord.attribute("s1", "title", "world", false);
        assertTrue(updateAttr.applyTo(root, dirties::add));
        assertEquals("world", span.attrs.get("title"));

        // 属性削除
        DomMutationRecord removeAttr = DomMutationRecord.attribute("s1", "title", null, true);
        assertTrue(removeAttr.applyTo(root, dirties::add));
        assertFalse(span.attrs.containsKey("title"));
    }

    @Test
    public void testStyleMutation() {
        HtmlNode root = tree("root", el("div", "d1"));
        HtmlNode div = root.children.get(0);

        DomMutationRecord setStyle = DomMutationRecord.style("d1", "color", "red");
        assertTrue(setStyle.applyTo(root, null));
        assertTrue(div.attrs.get("style").contains("color: red"));
    }

    @Test
    public void testClassMutation() {
        HtmlNode root = tree("root", el("div", "d1"));
        HtmlNode div = root.children.get(0);

        // add
        assertTrue(DomMutationRecord.className("d1", "active", "add").applyTo(root, null));
        assertTrue(div.hasClass("active"));

        // toggle (on -> off)
        assertTrue(DomMutationRecord.className("d1", "active", "toggle").applyTo(root, null));
        assertFalse(div.hasClass("active"));

        // toggle (off -> on)
        assertTrue(DomMutationRecord.className("d1", "active", "toggle").applyTo(root, null));
        assertTrue(div.hasClass("active"));

        // remove
        assertTrue(DomMutationRecord.className("d1", "active", "remove").applyTo(root, null));
        assertFalse(div.hasClass("active"));
    }

    @Test
    public void testInsertChildMutation() {
        HtmlNode root = tree("root", el("span", "a"), el("span", "c"));
        HtmlNode newB = el("span", "b");
        CompoundTag bTag = HtmlNodeSerializer.serialize(newB);

        // c の直前に b を挿入
        DomMutationRecord insert = DomMutationRecord.insertChild("root", "c", bTag);
        assertTrue(insert.applyTo(root, null));
        assertEquals("span#a,span#b,span#c", shape(root));

        // 末尾に d を挿入
        HtmlNode newD = el("span", "d");
        CompoundTag dTag = HtmlNodeSerializer.serialize(newD);
        DomMutationRecord insertEnd = DomMutationRecord.insertChild("root", null, dTag);
        assertTrue(insertEnd.applyTo(root, null));
        assertEquals("span#a,span#b,span#c,span#d", shape(root));
    }

    @Test
    public void testRemoveChildMutation() {
        HtmlNode root = tree("root", el("span", "a"), el("span", "b"), el("span", "c"));
        HtmlNode b = root.children.get(1);

        DomMutationRecord remove = DomMutationRecord.removeChild("root", "b");
        assertTrue(remove.applyTo(root, null));
        assertEquals("span#a,span#c", shape(root));
        assertNull(b.parent, "削除されたノードの parent ポインタはクリアされる");
    }

    @Test
    public void testInnerHtmlMutation() {
        DomUtils.innerHtmlParser = (html, parent) -> {
            HtmlNode container = new HtmlElement("div");
            HtmlNode n = new HtmlElement("strong");
            n.setId("bold");
            n.setTextContent("bold text");
            container.appendChild(n);
            return container;
        };

        HtmlNode root = tree("root", el("div", "content"));
        DomMutationRecord innerHtml = DomMutationRecord.innerHtml("content", "<strong>bold text</strong>");
        assertTrue(innerHtml.applyTo(root, null));

        HtmlNode content = root.children.get(0);
        assertEquals(1, content.children.size());
        assertEquals("bold", content.children.get(0).getId());
    }

    @Test
    public void testNbtSerializationRoundTrip() {
        DomMutationRecord orig = DomMutationRecord.attribute("target", "data-test", "val1", false);
        CompoundTag tag = orig.toTag();
        DomMutationRecord restored = DomMutationRecord.fromTag(tag);

        assertEquals(orig.getType(), restored.getType());
        assertEquals(orig.getTargetId(), restored.getTargetId());
        assertEquals(orig.getName(), restored.getName());
        assertEquals(orig.getValue(), restored.getValue());
        assertEquals(orig.getMode(), restored.getMode());
    }

    @Test
    public void testLegacyPatchFormatCompatibility() {
        // 旧 dom_patch 形式の CompoundTag
        CompoundTag legacyTag = new CompoundTag();
        legacyTag.putString("TargetId", "node1");
        legacyTag.putString("Op", "attr");
        legacyTag.putString("Key", "title");
        legacyTag.putString("Value", "tooltip");
        legacyTag.putBoolean("Remove", false);

        DomMutationRecord mut = DomMutationRecord.fromTag(legacyTag);
        assertEquals(DomMutationRecord.Type.ATTRIBUTE, mut.getType());
        assertEquals("node1", mut.getTargetId());
        assertEquals("title", mut.getName());
        assertEquals("tooltip", mut.getValue());

        HtmlNode root = tree("root", el("div", "node1"));
        assertTrue(mut.applyTo(root, null));
        assertEquals("tooltip", root.children.get(0).attrs.get("title"));
    }
}
