package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.gui.EnderScreenDelegate;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class WebControlInteractionTest {

    @Test
    @DisplayName("Inputタグ: 文字タイピング時にDOM inputイベントが発火し、テキストが更新される")
    void testInputTypingDispatchesDomInputEvent() {
        String sessionId = "test-input-typing";
        try (UiEventFixture fixture = new UiEventFixture(sessionId)) {
            fixture.load("<div style=\"width:300px;height:200px\">"
                    + "<input id=\"test_txt\" type=\"text\" value=\"\" oninput=\"on_typed\" style=\"width:150px;height:30px\">"
                    + "</div>");

            EnderScreenDelegate delegate = new EnderScreenDelegate(sessionId, new ReactiveStateStore(sessionId));
            try {
                fixture.attachActionRecorder();
                delegate.createHiddenEditBox(null, null);

                double[] center = fixture.center("test_txt");
                boolean clicked = delegate.handleMouseClicked(null, null, center[0], center[1], 0, null, null);
                assertTrue(clicked, "Input要素をクリックでフォーカスできること");
                delegate.handleMouseReleased(center[0], center[1], 0, null);

                SessionContext session = ClientWebManager.getSession(sessionId);
                assertNotNull(session);
                assertEquals("test_txt_input", session.activeInputId());

                List<String> eventLog = new ArrayList<>();
                HtmlNode inputNode = fixture.node("test_txt");
                inputNode.addEventListener("beforeinput", ev -> eventLog.add("beforeinput"));
                inputNode.addEventListener("input", ev -> eventLog.add("input"));

                boolean typed = delegate.handleCharTyped('h', 0, null);
                assertTrue(typed, "charTyped が消費されること");
                assertEquals("h", delegate.getInputValue("test_txt"));
                assertTrue(eventLog.contains("beforeinput"), "beforeinput が発火すること");
                assertTrue(eventLog.contains("input"), "DOM input イベントが発火すること");
                assertTrue(fixture.log().stream().anyMatch(l -> l.contains("on_typed")), "oninput アクションが届くこと");

                // 2文字目を入力
                eventLog.clear();
                delegate.handleCharTyped('i', 0, null);
                assertEquals("hi", delegate.getInputValue("test_txt"));
                assertTrue(eventLog.contains("input"), "2文字目でも DOM input イベントが発火すること");
            } finally {
                delegate.onClose();
            }
        }
    }

    @Test
    @DisplayName("Inputタグ: Spaceキー押下時にアクティベート（クリック）されず、空白文字が正常に入力される")
    void testInputSpaceTypingDoesNotActivate() {
        String sessionId = "test-input-space";
        try (UiEventFixture fixture = new UiEventFixture(sessionId)) {
            fixture.load("<div style=\"width:300px;height:200px\">"
                    + "<input id=\"space_txt\" type=\"text\" value=\"\" onclick=\"clicked_input\" style=\"width:150px;height:30px\">"
                    + "</div>");

            EnderScreenDelegate delegate = new EnderScreenDelegate(sessionId, new ReactiveStateStore(sessionId));
            try {
                fixture.attachActionRecorder();
                delegate.createHiddenEditBox(null, null);

                double[] center = fixture.center("space_txt");
                delegate.handleMouseClicked(null, null, center[0], center[1], 0, null, null);
                delegate.handleMouseReleased(center[0], center[1], 0, null);

                // 初期クリックログをクリア
                fixture.log().clear();

                // Space キーを押下
                boolean keyHandled = delegate.handleKeyPressed(null, null, GLFW.GLFW_KEY_SPACE, 0, 0, false, null, null, null);
                assertTrue(keyHandled, "Spaceキーが消費されること（フォールスルーしない）");

                // クリックアクションが発火していないこと
                assertFalse(fixture.log().stream().anyMatch(l -> l.contains("clicked_input")),
                        "Spaceキー押下でクリックが誤発火しないこと");

                // Space 文字のタイピング
                delegate.handleCharTyped(' ', 0, null);
                assertEquals(" ", delegate.getInputValue("space_txt"));
            } finally {
                delegate.onClose();
            }
        }
    }

    @Test
    @DisplayName("Inputタグ: フォーカス中にEscapeキーを押すと入力欄がアンフォーカスされ消費される")
    void testInputEscapeUnfocusesInput() {
        String sessionId = "test-input-escape";
        try (UiEventFixture fixture = new UiEventFixture(sessionId)) {
            fixture.load("<div style=\"width:300px;height:200px\">"
                    + "<input id=\"esc_txt\" type=\"text\" value=\"abc\" style=\"width:150px;height:30px\">"
                    + "</div>");

            EnderScreenDelegate delegate = new EnderScreenDelegate(sessionId, new ReactiveStateStore(sessionId));
            try {
                delegate.createHiddenEditBox(null, null);

                double[] center = fixture.center("esc_txt");
                delegate.handleMouseClicked(null, null, center[0], center[1], 0, null, null);
                delegate.handleMouseReleased(center[0], center[1], 0, null);

                SessionContext session = ClientWebManager.getSession(sessionId);
                assertNotNull(session);
                assertFalse(session.activeInputId().isEmpty(), "フォーカスされていること");

                // Escape 押下
                boolean escHandled = delegate.handleKeyPressed(null, null, GLFW.GLFW_KEY_ESCAPE, 0, 0, false, null, null, null);
                assertTrue(escHandled, "Escapeが消費されること（画面を閉じずにアンフォーカス）");
                assertTrue(session.activeInputId().isEmpty(), "activeInputIdが解除されること");
            } finally {
                delegate.onClose();
            }
        }
    }

    @Test
    @DisplayName("Inputタグ: Backspaceキーで文字削除と input イベントが発火する")
    void testInputBackspaceDeletesAndFiresInputEvent() {
        String sessionId = "test-input-backspace";
        try (UiEventFixture fixture = new UiEventFixture(sessionId)) {
            fixture.load("<div style=\"width:300px;height:200px\">"
                    + "<input id=\"bs_txt\" type=\"text\" value=\"ab\" oninput=\"on_bs\" style=\"width:150px;height:30px\">"
                    + "</div>");

            EnderScreenDelegate delegate = new EnderScreenDelegate(sessionId, new ReactiveStateStore(sessionId));
            try {
                fixture.attachActionRecorder();
                delegate.createHiddenEditBox(null, null);

                double[] center = fixture.center("bs_txt");
                delegate.handleMouseClicked(null, null, center[0], center[1], 0, null, null);
                delegate.handleMouseReleased(center[0], center[1], 0, null);

                assertEquals("ab", delegate.getInputValue("bs_txt"));
                fixture.log().clear();

                // Backspace押下
                boolean bsHandled = delegate.handleKeyPressed(null, null, GLFW.GLFW_KEY_BACKSPACE, 0, 0, false, null, null, null);
                assertTrue(bsHandled, "Backspaceが消費されること");
                assertEquals("a", delegate.getInputValue("bs_txt"));
                assertTrue(fixture.log().stream().anyMatch(l -> l.contains("on_bs")), "Backspaceでinputイベントが発火すること");
            } finally {
                delegate.onClose();
            }
        }
    }

    @Test
    @DisplayName("Inputタグ: Enterキーで入力値が submit され change イベントが発火する")
    void testInputEnterSubmits() {
        String sessionId = "test-input-enter";
        try (UiEventFixture fixture = new UiEventFixture(sessionId)) {
            fixture.load("<div style=\"width:300px;height:200px\">"
                    + "<input id=\"ent_txt\" type=\"text\" value=\"hello\" onchange=\"on_done\" style=\"width:150px;height:30px\">"
                    + "</div>");

            EnderScreenDelegate delegate = new EnderScreenDelegate(sessionId, new ReactiveStateStore(sessionId));
            try {
                fixture.attachActionRecorder();
                delegate.createHiddenEditBox(null, null);

                double[] center = fixture.center("ent_txt");
                delegate.handleMouseClicked(null, null, center[0], center[1], 0, null, null);
                delegate.handleMouseReleased(center[0], center[1], 0, null);

                AtomicReference<String> submittedValue = new AtomicReference<>();
                boolean enterHandled = delegate.handleKeyPressed(null, null, GLFW.GLFW_KEY_ENTER, 0, 0, false,
                        null, (id, val) -> submittedValue.set(val), null);
                assertTrue(enterHandled, "Enterが消費されること");
                assertEquals("hello", submittedValue.get(), "Enterで入力値が送信されること");
                assertTrue(fixture.log().stream().anyMatch(l -> l.contains("on_done")), "changeイベントが発火すること");
            } finally {
                delegate.onClose();
            }
        }
    }

    @Test
    @DisplayName("Selectタグ: クリックで開き、選択肢クリックで値が更新されドロップダウンが閉じる")
    void testSelectOpenAndClickOption() {
        String sessionId = "test-select-click";
        try (UiEventFixture fixture = new UiEventFixture(sessionId)) {
            fixture.load("<div style=\"width:300px;height:200px\">"
                    + "<select id=\"mode_sel\" onchange=\"mode_changed\" style=\"width:120px;height:25px\">"
                    + "<option value=\"first\">First</option>"
                    + "<option value=\"second\">Second</option>"
                    + "</select>"
                    + "</div>");

            EnderScreenDelegate delegate = new EnderScreenDelegate(sessionId, new ReactiveStateStore(sessionId));
            try {
                fixture.attachActionRecorder();
                assertFalse(delegate.selectWidget().isOpen(), "初期状態では閉じていること");

                double[] center = fixture.center("mode_sel");
                boolean opened = delegate.handleMouseClicked(null, null, center[0], center[1], 0, null, null);
                assertTrue(opened, "Select押下が消費されること");
                delegate.handleMouseReleased(center[0], center[1], 0, null);

                assertTrue(delegate.selectWidget().isOpen(), "クリック後にドロップダウンが開いていること");
                assertEquals("mode_sel", delegate.selectWidget().openSelectBaseId());

                // 2番目のオプション（Second）をクリック
                double optX = center[0];
                double optY = center[1] + 25 / 2.0 + 2 + 18 + 9;
                assertTrue(delegate.selectWidget().isWithinPopup(optX, optY), "クリック座標がポップアップ内であること");

                AtomicBoolean changedCalled = new AtomicBoolean(false);
                boolean optionClicked = delegate.handleMouseClicked(null, null, optX, optY, 0,
                        (id, val) -> changedCalled.set(true), null);
                assertTrue(optionClicked, "オプションクリックが消費されること");
                delegate.handleMouseReleased(optX, optY, 0, null);

                assertFalse(delegate.selectWidget().isOpen(), "選択後にドロップダウンが閉じること");
                assertEquals("second", fixture.node("mode_sel").attrs.get("value"), "値が second に更新されること");
                assertTrue(fixture.log().stream().anyMatch(l -> l.contains("mode_changed")), "onchange アクションが届くこと");
            } finally {
                delegate.onClose();
            }
        }
    }

    @Test
    @DisplayName("Selectタグ: 開いた状態でSelect本体を再度クリックすると閉じる（トグルクローズ）")
    void testSelectToggleCloseOnClick() {
        String sessionId = "test-select-toggle";
        try (UiEventFixture fixture = new UiEventFixture(sessionId)) {
            fixture.load("<div style=\"width:300px;height:200px\">"
                    + "<select id=\"toggle_sel\" style=\"width:120px;height:25px\">"
                    + "<option value=\"a\">A</option>"
                    + "<option value=\"b\">B</option>"
                    + "</select>"
                    + "</div>");

            EnderScreenDelegate delegate = new EnderScreenDelegate(sessionId, new ReactiveStateStore(sessionId));
            try {
                double[] center = fixture.center("toggle_sel");

                // 1回目クリック: 開く
                delegate.handleMouseClicked(null, null, center[0], center[1], 0, null, null);
                delegate.handleMouseReleased(center[0], center[1], 0, null);
                assertTrue(delegate.selectWidget().isOpen(), "1回目クリックで開くこと");

                // 2回目クリック: 本体を再クリックして閉じる
                delegate.handleMouseClicked(null, null, center[0], center[1], 0, null, null);
                delegate.handleMouseReleased(center[0], center[1], 0, null);
                assertFalse(delegate.selectWidget().isOpen(), "本体再クリックで閉じていること（再オープンしない）");
            } finally {
                delegate.onClose();
            }
        }
    }

    @Test
    @DisplayName("Selectタグ: 開いた状態でキーボード操作（矢印キーで移動、Enterで確定、Escで閉じる）")
    void testSelectKeyboardNavigation() {
        String sessionId = "test-select-kbd";
        try (UiEventFixture fixture = new UiEventFixture(sessionId)) {
            fixture.load("<div style=\"width:300px;height:200px\">"
                    + "<select id=\"kbd_sel\" onchange=\"kbd_changed\" style=\"width:120px;height:25px\">"
                    + "<option value=\"v1\">V1</option>"
                    + "<option value=\"v2\">V2</option>"
                    + "<option value=\"v3\">V3</option>"
                    + "</select>"
                    + "</div>");

            EnderScreenDelegate delegate = new EnderScreenDelegate(sessionId, new ReactiveStateStore(sessionId));
            try {
                double[] center = fixture.center("kbd_sel");
                delegate.handleMouseClicked(null, null, center[0], center[1], 0, null, null);
                delegate.handleMouseReleased(center[0], center[1], 0, null);
                assertTrue(delegate.selectWidget().isOpen());
                assertEquals(0, delegate.selectWidget().highlightedIndex(), "初期ハイライトは0番目");

                // ArrowDown
                boolean downHandled = delegate.handleKeyPressed(null, null, GLFW.GLFW_KEY_DOWN, 0, 0, false, null, null, null);
                assertTrue(downHandled);
                assertEquals(1, delegate.selectWidget().highlightedIndex(), "ArrowDownで1番目に移動");

                // Escape で閉じるテスト
                boolean escHandled = delegate.handleKeyPressed(null, null, GLFW.GLFW_KEY_ESCAPE, 0, 0, false, null, null, null);
                assertTrue(escHandled);
                assertFalse(delegate.selectWidget().isOpen(), "Escで閉じること");

                // 再び開いて Enter 確定テスト
                delegate.handleMouseClicked(null, null, center[0], center[1], 0, null, null);
                delegate.handleMouseReleased(center[0], center[1], 0, null);
                assertTrue(delegate.selectWidget().isOpen());

                delegate.handleKeyPressed(null, null, GLFW.GLFW_KEY_DOWN, 0, 0, false, null, null, null); // index 1 (v2)
                delegate.handleKeyPressed(null, null, GLFW.GLFW_KEY_DOWN, 0, 0, false, null, null, null); // index 2 (v3)
                assertEquals(2, delegate.selectWidget().highlightedIndex());

                boolean enterHandled = delegate.handleKeyPressed(null, null, GLFW.GLFW_KEY_ENTER, 0, 0, false, null, null, null);
                assertTrue(enterHandled);
                assertFalse(delegate.selectWidget().isOpen(), "Enterで閉じること");
                assertEquals("v3", fixture.node("kbd_sel").attrs.get("value"), "v3 が確定コミットされること");
            } finally {
                delegate.onClose();
            }
        }
    }

    @Test
    @DisplayName("Selectタグ: オプションクリック時のマウスリリースが下の背景ボタンに漏洩しない")
    void testSelectOptionClickDoesNotLeakMouseReleaseToUnderlyingElement() {
        String sessionId = "test-select-leak";
        try (UiEventFixture fixture = new UiEventFixture(sessionId)) {
            // select の真下に重なる位置に button を配置
            fixture.load("<div style=\"position:relative;width:300px;height:200px\">"
                    + "<select id=\"top_sel\" style=\"position:absolute;left:20px;top:20px;width:120px;height:25px\">"
                    + "<option value=\"o1\">Option 1</option>"
                    + "<option value=\"o2\">Option 2</option>"
                    + "</select>"
                    + "<button id=\"under_btn\" onclick=\"leak_clicked\" style=\"position:absolute;left:20px;top:45px;width:120px;height:50px\">Under</button>"
                    + "</div>");

            EnderScreenDelegate delegate = new EnderScreenDelegate(sessionId, new ReactiveStateStore(sessionId));
            try {
                double[] selCenter = fixture.center("top_sel");
                delegate.handleMouseClicked(null, null, selCenter[0], selCenter[1], 0, null, null);
                delegate.handleMouseReleased(selCenter[0], selCenter[1], 0, null);
                assertTrue(delegate.selectWidget().isOpen());

                // オプション2の位置をクリック（under_btn の上に重なっている領域）
                double optX = selCenter[0];
                double optY = selCenter[1] + 25 / 2.0 + 2 + 18 + 9;

                fixture.log().clear();
                delegate.handleMouseClicked(null, null, optX, optY, 0, null, null);
                delegate.handleMouseReleased(optX, optY, 0, null);

                assertFalse(delegate.selectWidget().isOpen(), "ドロップダウンは閉じていること");
                assertEquals("o2", fixture.node("top_sel").attrs.get("value"), "Option 2 が選択されたこと");
                assertFalse(fixture.log().stream().anyMatch(l -> l.contains("leak_clicked")),
                        "マウスリリースが下のボタンに漏洩してクリックが誤発火しないこと");
            } finally {
                delegate.onClose();
            }
        }
    }

    @Test
    @DisplayName("Selectタグ: ドロップダウン展開中はポップアップ領域がホバー保護される")
    void testSelectDropdownHoverProtection() {
        String sessionId = "test-select-hover";
        try (UiEventFixture fixture = new UiEventFixture(sessionId)) {
            fixture.load("<div style=\"width:300px;height:200px\">"
                    + "<select id=\"hov_sel\" style=\"width:120px;height:25px\">"
                    + "<option value=\"h1\">H1</option>"
                    + "<option value=\"h2\">H2</option>"
                    + "</select>"
                    + "</div>");

            EnderScreenDelegate delegate = new EnderScreenDelegate(sessionId, new ReactiveStateStore(sessionId));
            try {
                double[] center = fixture.center("hov_sel");
                assertFalse(delegate.isSelectDropdownHovered(center[0], center[1]));

                delegate.handleMouseClicked(null, null, center[0], center[1], 0, null, null);
                delegate.handleMouseReleased(center[0], center[1], 0, null);
                assertTrue(delegate.selectWidget().isOpen());

                double popupOptY = center[1] + 25 / 2.0 + 2 + 10;
                assertTrue(delegate.isSelectDropdownHovered(center[0], popupOptY),
                        "ポップアップ内の座標は isSelectDropdownHovered が true を返すこと");
                assertFalse(delegate.isSelectDropdownHovered(center[0], center[1] - 10),
                        "ポップアップ外の座標は isSelectDropdownHovered が false を返すこと");
            } finally {
                delegate.onClose();
            }
        }
    }
}
