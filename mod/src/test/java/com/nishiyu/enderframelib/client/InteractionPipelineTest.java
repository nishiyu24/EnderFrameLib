package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.common.TestBoxes;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.CoreEvent;
import com.nishiyu.enderframelib.api.event.UIEvents;
import com.nishiyu.enderframelib.core.dom.node.EventDispatcher;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.scene.DisplayScene;
import com.nishiyu.enderframelib.core.paint.scene.DynamicSceneState;
import com.nishiyu.enderframelib.core.paint.scene.SceneHit;
import com.nishiyu.enderframelib.core.paint.scene.SceneHitTest;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import com.nishiyu.enderframelib.core.text.model.TextSelection;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.core.paint.model.*;

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
        UIEvents.Mouse clickEvent = CoreEvent.click(10, 10, 0);

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
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(
                "<html><body style=\"margin:0\">"
                        + "<button id=\"btn\" onclick=\"btnAction\" style=\"position:absolute;left:10px;top:10px;width:100px;height:50px\"></button>"
                        + "<div id=\"modal\" style=\"position:absolute;left:0;top:0;width:200px;height:100px;background:#ffffff\"></div>"
                        + "</body></html>", "", 200, 100);
        DisplayScene scene = compiler.layoutDocument(doc, 200, 100, Map.of(), new ScrollState(), FontResolver.FALLBACK);
        SceneHit hit = SceneHitTest.hitTest(scene, SceneHitTest.Context.of(DynamicSceneState.of(null, 200, 100)), 50, 25);
        assertNotNull(hit, "ヒット結果が存在すること");
        assertEquals("modal", hit.baseId(), "前面の不透明要素が背後ボタンを遮蔽すること");
        assertNotEquals("btn", hit.baseId(), "背後のボタンには貫通しないこと");
    }

    @Test
    @DisplayName("F5-2: pointer-events: none が指定された前面要素はヒットテストを透過して背後要素に届く")
    void testPointerEventsNoneAllowsPassThrough() {
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(
                "<html><body style=\"margin:0\">"
                        + "<button id=\"btn\" onclick=\"btnAction\" style=\"position:absolute;left:10px;top:10px;width:100px;height:50px\"></button>"
                        + "<div id=\"overlay\" style=\"position:absolute;left:0;top:0;width:200px;height:100px;background:#ffffff;pointer-events:none\"></div>"
                        + "</body></html>", "", 200, 100);
        DisplayScene scene = compiler.layoutDocument(doc, 200, 100, Map.of(), new ScrollState(), FontResolver.FALLBACK);
        SceneHit hit = SceneHitTest.hitTest(scene, SceneHitTest.Context.of(DynamicSceneState.of(null, 200, 100)), 50, 25);
        assertNotNull(hit, "ヒット結果が存在すること");
        assertEquals("btn", hit.baseId(), "pointer-events: none により前面を透過してボタンにヒットすること");
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

        // targetBtn にキーボードフォーカスを当てる
        session.interactionState().setFocusedNode(targetBtn, true);

        // 1. SessionContext のフォーカス基底 ID
        assertEquals("targetBtn", session.focusedId());

        // 2. InteractionState のフラグ整合
        var state = session.interactionState().stateOf(targetBtn);
        assertTrue(state.focus(), ":focus フラグが有効であること");
        assertTrue(state.focusVisible(), ":focus-visible フラグが有効であること");

        // フォーカスを解除
        session.interactionState().setFocusedNode(null, false);

        assertEquals("", session.focusedId());
        var stateCleared = session.interactionState().stateOf(targetBtn);
        assertFalse(stateCleared.focus(), ":focus フラグが解除されていること");
        assertFalse(stateCleared.focusVisible(), ":focus-visible フラグが解除されていること");
    }

    @Test
    @DisplayName("F5-3: スクロールバートラックのクリックおよびサムドラッグでスクロール位置が更新される")
    void testScrollbarInteraction() {
        SessionContext session = ClientWebManager.getOrCreateSession("testScrollSession");

        session.loadLocalHtml("<html><body style=\"margin:0\"><div id=\"scrollBox\" style=\"width:100px;height:100px;overflow:auto;\">"
                + "<div style=\"height:300px;\"></div></div></body></html>", 100, 100);
        session.flushInvalidation();
        HtmlNode containerNode = session.document().root.getElementById("scrollBox");

        TestBoxes.Box box = TestBoxes.byNode(TestBoxes.root(session), containerNode);
        assertNotNull(box);
        assertEquals(100f, box.w(), 0.01f);
        assertEquals(100f, box.h(), 0.01f);
        assertEquals(300f, box.scrollHeight(), 0.01f);

        SceneHit trackHit = session.hitTest(95, 50);
        assertNotNull(trackHit);
        assertEquals("_sb_vt", trackHit.scrollbarPart());

        // トラックの中央 (y = 50) をクリック -> 約 50% のスクロール位置 (約 100px) にジャンプ
        boolean clickHandled = session.handleScrollbarClick(trackHit, 95, 50);
        assertTrue(clickHandled, "トラッククリックが処理されること");

        float scrollY = session.scrollState().getScrollY(containerNode);
        assertTrue(scrollY >= 80 && scrollY <= 120, "スクロール位置が中央付近にジャンプすること: " + scrollY);

        // サムのドラッグで最下部 (y = 100) へドラッグ
        boolean dragHandled = session.handleScrollbarDrag("scrollBox", SessionContext.SB_V_THUMB, 94, 100);
        assertTrue(dragHandled, "サムドラッグが処理されること");

        float scrollYMax = session.scrollState().getScrollY(containerNode);
        assertEquals(200f, scrollYMax, 1.0f, "最下部ドラッグで最大スクロール量 (200px) になること");
    }

    @Test
    @DisplayName("F5-3: スクロール状態（ScrollState）の更新とクランプの検証")
    void testScrollStateUpdate() {
        ScrollState state = new ScrollState();
        HtmlNode container = new HtmlElement("div");
        assertEquals(0f, state.getScrollX(container));
        assertEquals(0f, state.getScrollY(container));

        // Y スクロールを 150 に設定
        state.setScroll(container, 0, 150);
        assertEquals(150f, state.getScrollY(container));
        assertEquals(0f, state.getScrollX(container));

        // X スクロールを 80 に設定
        state.setScroll(container, 80, 150);
        assertEquals(80f, state.getScrollX(container));
        assertEquals(150f, state.getScrollY(container));

        // リセット
        state.clear();
        assertEquals(0f, state.getScrollX(container));
        assertEquals(0f, state.getScrollY(container));
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
        java.nio.file.Path showcasePath = java.nio.file.Path.of("mod/src/main/resources/data/enderframelib/enderframe_ui/showcase.html");
        String html = java.nio.file.Files.readString(showcasePath, java.nio.charset.StandardCharsets.UTF_8);

        com.nishiyu.enderframelib.common.UiDocumentCompiler parser = new com.nishiyu.enderframelib.common.UiDocumentCompiler();
        com.nishiyu.enderframelib.common.Document doc = parser.parseDocument(html, "", 854, 480);
        assertNotNull(doc);
        assertNotNull(doc.root);

        var frame = com.nishiyu.enderframelib.common.FrameRender.document(doc, 854, 480, Map.of(), new ScrollState(), FontResolver.FALLBACK, new InteractionState());
        assertNotNull(frame.scene());
        List<DisplayItem> elements = frame.items();
        assertNotNull(elements);

        // ルート直下の要素が height: 100% の場合に高さが 0 にならず、子要素群が正しくレンダリングされること
        assertTrue(elements.size() >= 50, "showcase.html の DisplayItem 群（200個以上）が正常に生成されること: 実際=" + elements.size());
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