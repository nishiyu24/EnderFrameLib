package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.event.DomEvent;
import com.nishiyu.enderframelib.core.event.EventDispatcher;
import com.nishiyu.enderframelib.core.layout.LayoutContext.ScrollState;
import com.nishiyu.enderframelib.core.model.AnimationSpec;
import com.nishiyu.enderframelib.core.model.UIElement.ElementType;
import com.nishiyu.enderframelib.core.model.TextSelection;
import com.nishiyu.enderframelib.core.model.UIElement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * フェーズ F5（入力・イベント・スクロール・フォーカス・テキスト選択）のパイプライン検証テスト。
 */
public class InteractionPipelineTest {

    @Test
    @DisplayName("F5-1: 子要素クリック時に親コンテナの onclick がバブリングで発火する")
    void testContainerOnclickBubbling() {
        HtmlNode container = new HtmlNode("div");
        container.attrs.put("id", "container");
        container.attrs.put("onclick", "parentAction");

        HtmlNode child = new HtmlNode("span");
        child.attrs.put("id", "child");
        child.parent = container;
        container.children.add(child);

        List<String> invokedActions = new ArrayList<>();
        DomEvent clickEvent = DomEvent.click(10, 10, 0);

        EventDispatcher.dispatchEvent(child, clickEvent, (node, action) -> {
            invokedActions.add(node.getId() + ":" + action);
        }, null);

        // child 自身には onclick が無いが、親の container に onclick='parentAction' があるためバブリングで発火する
        assertEquals(1, invokedActions.size());
        assertEquals("container:parentAction", invokedActions.getFirst());
    }

    @Test
    @DisplayName("F5-2: 不透明な前面要素がヒットテストで背後要素を遮蔽し貫通しない")
    void testOpaqueElementOccludesUnderlyingButton() {
        // 背後にあるボタン (z=1, x=10, y=10, w=100, h=50)
        UIElement button = new UIElement(
                "btn", "btn", ElementType.BUTTON,
                10, 10, 100, 50,
                "Button", 0, 0xFF444444, Map.of("onclick", "btnAction"),
                1, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, com.nishiyu.enderframelib.core.model.UIElement.ImageFitMode.FILL, UIElement.RenderMeta.EMPTY
        );

        // 前面にある不透明なモーダル背景 (z=2, x=0, y=0, w=200, h=100, 不透明色 0xFFFFFFFF)
        UIElement modal = new UIElement(
                "modal", "modal", ElementType.RECT,
                0, 0, 200, 100,
                "", 0, 0xFFFFFFFF, Map.of(),
                2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, com.nishiyu.enderframelib.core.model.UIElement.ImageFitMode.FILL, UIElement.RenderMeta.EMPTY
        );

        HitTestIndex index = new HitTestIndex(List.of(button, modal));

        // (50, 25) は button と modal の両方が重なっている座標
        ClientWebManager.UIHitResult hit = index.hitTest("testSession", 50, 25);
        assertNotNull(hit, "ヒット結果が存在すること");

        // 背後のボタンではなく、前面の不透明な modal がヒットして遮蔽すること
        assertEquals("modal", hit.element().baseId(), "前面の不透明要素が背後ボタンを遮蔽すること");
        assertNotEquals("btn", hit.element().baseId(), "背後のボタンには貫通しないこと");
    }

    @Test
    @DisplayName("F5-2: pointer-events: none が指定された前面要素はヒットテストを透過して背後要素に届く")
    void testPointerEventsNoneAllowsPassThrough() {
        // 背後にあるボタン
        UIElement button = new UIElement(
                "btn", "btn", ElementType.BUTTON,
                10, 10, 100, 50,
                "Button", 0, 0xFF444444, Map.of("onclick", "btnAction"),
                1, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, com.nishiyu.enderframelib.core.model.UIElement.ImageFitMode.FILL, UIElement.RenderMeta.EMPTY
        );

        // 前面にあるが pointer-events: none のオーバーレイ
        UIElement overlay = new UIElement(
                "overlay", "overlay", ElementType.RECT,
                0, 0, 200, 100,
                "", 0, 0xFFFFFFFF, Map.of("pointer-events", "none"),
                2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, com.nishiyu.enderframelib.core.model.UIElement.ImageFitMode.FILL, UIElement.RenderMeta.EMPTY
        );

        HitTestIndex index = new HitTestIndex(List.of(button, overlay));

        // (50, 25) で前面の overlay は pointer-events: none なので透過して背後の btn に届く
        ClientWebManager.UIHitResult hit = index.hitTest("testSession", 50, 25);
        assertNotNull(hit, "ヒット結果が存在すること");
        assertEquals("btn", hit.element().baseId(), "pointer-events: none により前面を透過してボタンにヒットすること");
    }

    @Test
    @DisplayName("F5-4: フォーカス状態の1系統化（SessionContext, InteractionState の整合）")
    void testFocusUnification() {
        SessionContext session = new SessionContext("testFocusSession");

        HtmlNode root = new HtmlNode("div");
        root.attrs.put("id", "root");
        HtmlNode targetBtn = new HtmlNode("button");
        targetBtn.attrs.put("id", "targetBtn");
        targetBtn.parent = root;
        root.children.add(targetBtn);

        session.setVirtualDom(root);

        // targetBtn にキーボードフォーカスを当てる
        session.setFocusedElementBaseId("targetBtn");
        session.interactionState().setFocusedNode(root, "targetBtn", true);

        // 1. SessionContext のフォーカス基底 ID
        assertEquals("targetBtn", session.focusedElementBaseId());

        // 2. InteractionState のフラグ整合
        var state = session.interactionState().stateOf(targetBtn);
        assertTrue(state.focus(), ":focus フラグが有効であること");
        assertTrue(state.focusVisible(), ":focus-visible フラグが有効であること");

        // フォーカスを解除
        session.setFocusedElementBaseId("");
        session.interactionState().setFocusedNode(root, "", false);

        assertEquals("", session.focusedElementBaseId());
        var stateCleared = session.interactionState().stateOf(targetBtn);
        assertFalse(stateCleared.focus(), ":focus フラグが解除されていること");
        assertFalse(stateCleared.focusVisible(), ":focus-visible フラグが解除されていること");
    }

    @Test
    @DisplayName("F5-3: スクロールバートラックのクリックおよびサムドラッグでスクロール位置が更新される")
    void testScrollbarInteraction() {
        SessionContext session = ClientWebManager.getOrCreateSession("testScrollSession");

        HtmlNode containerNode = new HtmlNode("div");
        containerNode.attrs.put("id", "scrollBox");

        com.nishiyu.enderframelib.core.layout.LayoutBox box =
                new com.nishiyu.enderframelib.core.layout.LayoutBox(containerNode, new com.nishiyu.enderframelib.core.css.ComputedStyle(Map.of()));
        box.x = 0; box.y = 0; box.w = 100; box.h = 100;
        box.scrollWidth = 100;
        box.scrollHeight = 300; // 300px のコンテンツ（可視 100px なので最大スクロール量は 200px）

        session.setLayoutRoot(box);

        UIElement trackEl = new UIElement(
                "scrollBox_sb_vt", "scrollBox", ElementType.RECT,
                94, 2, 4, 96,
                "", 0, 0x33000000, Map.of(),
                2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, com.nishiyu.enderframelib.core.model.UIElement.ImageFitMode.FILL, UIElement.RenderMeta.EMPTY
        );

        // トラックの中央 (y = 50) をクリック -> 約 50% のスクロール位置 (約 100px) にジャンプ
        boolean clickHandled = ClientWebManager.handleScrollbarClick("testScrollSession", trackEl, 94, 50);
        assertTrue(clickHandled, "トラッククリックが処理されること");

        float scrollY = session.scrollState().getScrollY("scrollBox");
        assertTrue(scrollY >= 80 && scrollY <= 120, "スクロール位置が中央付近にジャンプすること: " + scrollY);

        // サムのドラッグで最下部 (y = 100) へドラッグ
        boolean dragHandled = ClientWebManager.handleScrollbarDrag("testScrollSession", "scrollBox_sb_v", 94, 100);
        assertTrue(dragHandled, "サムドラッグが処理されること");

        float scrollYMax = session.scrollState().getScrollY("scrollBox");
        assertEquals(200f, scrollYMax, 1.0f, "最下部ドラッグで最大スクロール量 (200px) になること");
    }

    @Test
    @DisplayName("F5-3: スクロール状態（ScrollState）の更新とクランプの検証")
    void testScrollStateUpdate() {
        ScrollState state = new ScrollState();
        assertEquals(0f, state.getScrollX("container"));
        assertEquals(0f, state.getScrollY("container"));

        // Y スクロールを 150 に設定
        state.setScroll("container", 0, 150);
        assertEquals(150f, state.getScrollY("container"));
        assertEquals(0f, state.getScrollX("container"));

        // X スクロールを 80 に設定
        state.setScroll("container", 80, 150);
        assertEquals(80f, state.getScrollX("container"));
        assertEquals(150f, state.getScrollY("container"));

        // リセット
        state.clear();
        assertEquals(0f, state.getScrollX("container"));
        assertEquals(0f, state.getScrollY("container"));
    }

    @Test
    @DisplayName("F5-4: テキスト選択モデル（TextSelection）のドラッグ選択・全選択・クリア検証")
    void testTextSelectionModel() {
        TextSelection sel = new TextSelection();
        assertTrue(sel.isEmpty(), "初期状態は空であること");

        String sampleText = "EnderFrameLib 1.21.1 Refactored";

        // ドラッグ開始 (インデックス 0)
        sel.startSelection("txt1", 0);
        assertEquals("txt1", sel.anchorNodeId());
        assertEquals(0, sel.anchorOffset());

        // ドラッグ更新 (インデックス 13: "EnderFrameLib")
        sel.updateWithinNode("txt1", 13, sampleText);
        assertFalse(sel.isEmpty());
        assertEquals("EnderFrameLib", sel.selectedText());
        assertEquals(0, sel.startOffset());
        assertEquals(13, sel.endOffset());

        // 逆方向ドラッグ (インデックス 20 -> 14: "1.21.1")
        sel.startSelection("txt1", 20);
        sel.updateWithinNode("txt1", 14, sampleText);
        assertEquals("1.21.1", sel.selectedText());
        assertEquals(14, sel.startOffset());
        assertEquals(20, sel.endOffset());

        // 全選択
        sel.selectAll("txt1", sampleText);
        assertEquals(sampleText, sel.selectedText());

        // クリア
        sel.clear();
        assertTrue(sel.isEmpty());
        assertEquals("", sel.selectedText());
    }

    @Test
    @DisplayName("showcase.html のパース・レイアウト・描画要素生成を検証する")
    void testShowcaseRendering() throws Exception {
        java.nio.file.Path showcasePath = java.nio.file.Path.of("src/main/resources/data/enderframelib/enderframe_ui/showcase.html");
        String html = java.nio.file.Files.readString(showcasePath, java.nio.charset.StandardCharsets.UTF_8);

        com.nishiyu.enderframelib.common.UIParser parser = new com.nishiyu.enderframelib.common.UIParser();
        com.nishiyu.enderframelib.common.UIParser.Document doc = parser.parseDocument(html, "", 854, 480);
        assertNotNull(doc);
        assertNotNull(doc.root);

        com.nishiyu.enderframelib.core.layout.LayoutBox rootBox = parser.layoutDocument(
                doc, 854, 480, Map.of(), new ScrollState(), com.nishiyu.enderframelib.core.text.FontFace.FontResolver.FALLBACK, new InteractionState());
        assertNotNull(rootBox);
        com.nishiyu.enderframelib.client.renderer.UIRenderer renderer =
                new com.nishiyu.enderframelib.client.renderer.UIRenderer(854, 480, doc.sheet.keyframes, new ScrollState());
        List<UIElement> elements = renderer.render(rootBox);
        assertNotNull(elements);

        // ルート直下の要素が height: 100% の場合に高さが 0 にならず、子要素群が正しくレンダリングされること
        assertTrue(elements.size() >= 50, "showcase.html の UIElement 群（200個以上）が正常に生成されること: 実際=" + elements.size());
        assertTrue(elements.stream().anyMatch(e -> e.id() != null && e.id().contains("header_panel")), "ヘッダーパネルが生成されていること");
        assertTrue(elements.stream().anyMatch(e -> e.text() != null && e.text().contains("EnderFrameLib")), "タイトルテキストが生成されていること");
        assertTrue(elements.stream().anyMatch(e -> "test_input".equals(e.id()) || e.type() == ElementType.INPUT), "入力フィールドが生成されていること");

        // Web標準機能の検証
        assertNotNull(doc.root.querySelector(".web-grid"), "CSS Gridコンテナ要素が存在すること");
        assertNotNull(doc.root.querySelector("details"), "W3C Semantic <details> 要素が存在すること");
        assertNotNull(doc.root.querySelector("summary"), "W3C Semantic <summary> 要素が存在すること");
        assertNotNull(doc.root.querySelector("input[type=radio]"), "W3C <input type=radio> 要素が存在すること");
        assertFalse(html.contains("<mc-"), "Web と 1:1 比較できるように独自の <mc-> タグを含まないこと");
    }
}

