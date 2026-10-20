package com.nishiyu.enderframelib.network;

import com.nishiyu.enderframelib.network.HtmlNodeSerializer;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.GoldenFiles;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.RenderPipeline;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

/**
 * {@code new HtmlNode("#text")} や {@link HtmlNodeSerializer} のデシリアライズ経路で
 * テキストノード ({@link NodeType#TEXT}) や属性・レイアウト結果が失われないことを検証するテスト。
 */
public class HtmlNodeSerializerTest {

    /**
     * HtmlNodeSerializer.deserialize と同じ経路（タグ名文字列からの復元）を模擬する。
     */
    private static HtmlNode simulateNetworkRoundTrip(HtmlNode src, HtmlNode parent) {
        String tagName = src.tag != null ? src.tag : "";
        HtmlNode node = "#text".equals(tagName)
                ? new com.nishiyu.enderframelib.core.dom.node.HtmlText(src.text != null ? src.text : "")
                : new com.nishiyu.enderframelib.core.dom.node.HtmlElement(tagName);
        node.setId(src.getId() != null ? src.getId() : "");
        node.text = src.text != null ? src.text : "";
        node.parent = parent;
        for (String c : src.getClasses()) {
            node.addClass(c);
        }
        node.attrs.putAll(src.attrs);
        for (HtmlNode child : src.children) {
            node.children.add(simulateNetworkRoundTrip(child, node));
        }
        return node;
    }

    private static void collectVisibleTextBoxes(LayoutBox box, List<LayoutBox> out) {
        if ("none".equals(box.style.get("display"))) return;
        if ("select".equals(box.node.tag) || "option".equals(box.node.tag)) return;
        if (box.node.nodeType == NodeType.TEXT && box.displayText != null && !box.displayText.isBlank()) {
            out.add(box);
        }
        for (LayoutBox child : box.children) {
            collectVisibleTextBoxes(child, out);
        }
    }

    private static void collectSelectNodes(LayoutBox box, List<HtmlNode> out) {
        if ("select".equals(box.node.tag)) {
            out.add(box.node);
        }
        for (LayoutBox child : box.children) {
            collectSelectNodes(child, out);
        }
    }

    @Test
    public void testTextNodeConstructorAndRoundTripPreserveNodeType() {
        HtmlNode viaStringCtor = new HtmlNode("#text");
        viaStringCtor.text = "direct text";
        assertEquals(NodeType.TEXT, viaStringCtor.nodeType,
                "new HtmlNode(\"#text\") でも NodeType.TEXT になること");
        assertEquals("direct text", viaStringCtor.getTextContent());

        HtmlNode elem = new HtmlNode("div");
        assertEquals(NodeType.ELEMENT, elem.nodeType);
    }

    @Test
    public void testShowcaseHtmlProducesNonZeroTextBoxesAfterNetworkRoundTrip() {
        String showcaseHtml = GoldenFiles.readFixture("showcase.html");
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument(showcaseHtml, "", 800, 600);

        // サーバー → クライアントの dom_sync 経路と同様に全ノードを再構築する
        doc.root = simulateNetworkRoundTrip(doc.root, null);

        AtomicReference<LayoutBox> capturedRootBox = new AtomicReference<>();
        RenderPipeline.ILayoutBoxRenderer prevRenderer = UiDocumentCompiler.clientBoxRenderer;
        UiDocumentCompiler.clientBoxRenderer = (rootBox, w, h, kf, ss) -> {
            capturedRootBox.set(rootBox);
            return List.<DisplayItem>of();
        };
        try {
            parser.renderDocument(
                    doc,
                    800,
                    600,
                    Map.of(),
                    new ScrollState(),
                    FontResolver.FALLBACK
            );
        } finally {
            UiDocumentCompiler.clientBoxRenderer = prevRenderer;
        }

        LayoutBox rootBox = capturedRootBox.get();

        assertNotNull(rootBox, "レイアウトツリーが構築されていること");

        List<LayoutBox> textBoxes = new ArrayList<>();
        collectVisibleTextBoxes(rootBox, textBoxes);

        assertFalse(textBoxes.isEmpty(), "showcase.html のネットワーク同期後にテキストボックスが 1 つも存在しない");

        // すべての可視テキストボックスが w > 0 かつ h > 0（または inlineFragments 非空）であること
        long renderableCount = textBoxes.stream()
                .filter(b -> (b.w > 0 && b.h > 0) || !b.inlineFragments.isEmpty())
                .count();
        assertEquals(textBoxes.size(), renderableCount,
                "すべての可視テキストボックスが描画可能なサイズまたは inlineFragments を持つこと");

        assertTrue(textBoxes.stream().anyMatch(b -> b.displayText.contains("EnderFrame")),
                "showcase.html のヘッダーテキストがレイアウトツリーに含まれていること");

        List<HtmlNode> selectNodes = new ArrayList<>();
        collectSelectNodes(rootBox, selectNodes);
        assertFalse(selectNodes.isEmpty(), "showcase.html に <select> 要素が存在すること");
        for (HtmlNode select : selectNodes) {
            assertTrue(select.children.stream().anyMatch(opt -> !opt.getTextContent().isBlank()),
                    "<select> 内の <option> の getTextContent() が空でないこと");
        }
    }
}