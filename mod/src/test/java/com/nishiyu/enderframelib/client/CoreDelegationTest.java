package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.gui.EnderScreenDelegate;
import com.nishiyu.enderframelib.client.gui.TextareaEditor;
import com.nishiyu.enderframelib.client.gui.WebFocusNavigator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Minecraft 側の WebFocusNavigator・TextareaEditor・選択ポップアップが、core の規則（窓と同じ）で動くこと。 */
public class CoreDelegationTest {

    @Test
    @DisplayName("Tab 順は core の UiFocus に従う（tabindex の正の値が先・負と disabled は飛ばす）")
    void tabOrderFollowsCore() {
        String sessionId = "delegation-tab";
        try (UiEventFixture fixture = new UiEventFixture(sessionId)) {
            fixture.load("<div style=\"width:400px\">"
                    + "<button id=\"a\" style=\"width:80px;height:20px\">a</button>"
                    + "<button id=\"b\" tabindex=\"2\" style=\"width:80px;height:20px\">b</button>"
                    + "<button id=\"c\" tabindex=\"1\" style=\"width:80px;height:20px\">c</button>"
                    + "<button id=\"skip\" tabindex=\"-1\" style=\"width:80px;height:20px\">s</button>"
                    + "<button id=\"off\" disabled style=\"width:80px;height:20px\">o</button>"
                    + "</div>");
            WebFocusNavigator nav = new WebFocusNavigator(sessionId);
            List<String> visited = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                assertTrue(nav.cycleSequentialFocus(null, false, c -> visited.add(c.node().getId())));
            }
            assertEquals(List.of("c", "b", "a", "c"), visited, "1 → 2 → 0（文書順）→ 回り込み");
            assertEquals("c", nav.focusedId());
            assertTrue(nav.cycleSequentialFocus(null, true, c -> {}));
            assertEquals("a", nav.focusedId(), "Shift+Tab は逆順");
        }
    }

    @Test
    @DisplayName("select: 無効な選択肢は選べず、optgroup の中も開ける")
    void selectPopupSkipsDisabledOptions() {
        String sessionId = "delegation-select";
        try (UiEventFixture fixture = new UiEventFixture(sessionId)) {
            fixture.load("<div style=\"width:300px;height:200px\">"
                    + "<select id=\"s\" style=\"width:120px;height:25px\">"
                    + "<option value=\"a\">A</option>"
                    + "<option value=\"b\" disabled>B</option>"
                    + "<optgroup label=\"g\"><option value=\"c\">C</option></optgroup>"
                    + "</select></div>");
            EnderScreenDelegate delegate = new EnderScreenDelegate(sessionId, new ReactiveStateStore(sessionId));
            try {
                double[] center = fixture.center("s");
                delegate.handleMouseClicked(null, null, center[0], center[1], 0, null, null);
                delegate.handleMouseReleased(center[0], center[1], 0, null);
                var widget = delegate.selectWidget();
                assertTrue(widget.isOpen());
                assertEquals(0, widget.highlightedIndex());
                widget.moveHighlight(1);
                assertEquals(2, widget.highlightedIndex(), "無効な B は飛ばして、optgroup の C へ");
                widget.setHighlightedIndex(1);
                assertEquals(2, widget.highlightedIndex(), "無効な項目は直接指定もできない");
            } finally {
                delegate.onClose();
            }
        }
    }

    @Test
    @DisplayName("textarea: Ctrl+Backspace は単語単位・Enter は自動インデント・Esc で編集を抜ける")
    void textareaEditingFollowsCoreRules() {
        String sessionId = "delegation-textarea";
        try (UiEventFixture fixture = new UiEventFixture(sessionId)) {
            fixture.load("<div style=\"width:300px;height:200px\">"
                    + "<textarea id=\"ta\" style=\"width:200px;height:100px\">foo bar</textarea></div>");
            TextareaEditor editor = new TextareaEditor(sessionId);
            var session = ClientWebManager.getSession(sessionId);
            var item = session.elementById("ta");
            assertNotNull(item);
            editor.focus(null, item, -1, -1); // 末尾にキャレット
            List<String> changes = new ArrayList<>();

            assertTrue(editor.handleKeyPressed(GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_MOD_CONTROL, (id, v) -> changes.add(v), null));
            assertEquals("foo ", editor.getValue("ta"), "Ctrl+Backspace で単語ごと消える");

            editor.setValue("ta", "  ab");
            var renderId = session.activeInputId();
            TextareaEditor.TextEditState.setTextareaCursor(sessionId, renderId, 4);
            assertTrue(editor.handleKeyPressed(GLFW.GLFW_KEY_ENTER, 0, (id, v) -> changes.add(v), null));
            assertEquals("  ab\n  ", editor.getValue("ta"), "直前の行のインデントを引き継ぐ");

            assertTrue(editor.handleKeyPressed(GLFW.GLFW_KEY_LEFT, 0, null, null));
            assertEquals(6, TextareaEditor.TextEditState.getTextareaCursor(sessionId, renderId, -1));
            assertFalse(editor.handleKeyPressed(GLFW.GLFW_KEY_F5, 0, null, null), "扱わないキーは使わない");

            assertTrue(editor.handleKeyPressed(GLFW.GLFW_KEY_ESCAPE, 0, null, null));
            assertFalse(editor.isEditing());
        }
    }
}
