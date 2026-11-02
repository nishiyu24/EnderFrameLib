package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.common.SelectPopup;
import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.TextEditing;
import com.nishiyu.enderframelib.common.UiFocus;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 5-6: フォーカス移動・テキスト編集・select ポップアップが、Minecraft も GL も無しで動くことの確認。 */
class StandaloneEditingTest {

    private static final int CTRL = GLFW.GLFW_MOD_CONTROL, SHIFT = GLFW.GLFW_MOD_SHIFT;

    // ---- TextEditing（純粋な編集規則） ----

    private static String apply(TextEditing.State s, String text, int key, int mods, boolean multi, TextEditing.Clipboard cb) {
        TextEditing.Result r = TextEditing.key(s, text, key, mods, multi, cb);
        if (r.changed()) {
            s.caret = r.caret();
            s.anchor = r.anchor();
            return r.text();
        }
        s.caret = r.caret();
        s.anchor = r.anchor();
        return text;
    }

    @Test
    void caretMovesAndEditsInTheMiddle() {
        TextEditing.State s = new TextEditing.State(5);
        String t = "hello";
        t = apply(s, t, GLFW.GLFW_KEY_LEFT, 0, false, null);
        t = apply(s, t, GLFW.GLFW_KEY_LEFT, 0, false, null);
        assertEquals(3, s.caret);
        TextEditing.Result r = TextEditing.type(s, t, "X");
        assertEquals("helXlo", r.text());
        assertEquals(4, r.caret());
        s.caret = s.anchor = 0;
        t = apply(s, t, GLFW.GLFW_KEY_DELETE, 0, false, null);
        assertEquals("ello", t);
        t = apply(s, t, GLFW.GLFW_KEY_BACKSPACE, 0, false, null);
        assertEquals("ello", t, "先頭の Backspace は何も消さない");
        apply(s, t, GLFW.GLFW_KEY_END, 0, false, null);
        assertEquals(4, s.caret);
        apply(s, t, GLFW.GLFW_KEY_HOME, 0, false, null);
        assertEquals(0, s.caret);
    }

    @Test
    void shiftExtendsSelectionAndTypingReplacesIt() {
        TextEditing.State s = new TextEditing.State(0);
        String t = "abcdef";
        apply(s, t, GLFW.GLFW_KEY_RIGHT, SHIFT, false, null);
        apply(s, t, GLFW.GLFW_KEY_RIGHT, SHIFT, false, null);
        assertEquals("ab", s.selected(t));
        TextEditing.Result r = TextEditing.type(s, t, "Z");
        assertEquals("Zcdef", r.text());
        assertEquals(1, r.caret());
        // 選択があるときの矢印は、端へ寄せて解除する
        apply(s, t, GLFW.GLFW_KEY_LEFT, 0, false, null);
        assertFalse(s.hasSelection());
        assertEquals(0, s.caret);
    }

    @Test
    void wordMovementAndDeletion() {
        TextEditing.State s = new TextEditing.State(0);
        String t = "foo bar  baz";
        apply(s, t, GLFW.GLFW_KEY_RIGHT, CTRL, false, null);
        assertEquals(3, s.caret);
        apply(s, t, GLFW.GLFW_KEY_RIGHT, CTRL, false, null);
        assertEquals(7, s.caret);
        apply(s, t, GLFW.GLFW_KEY_LEFT, CTRL, false, null);
        assertEquals(4, s.caret);
        s.caret = s.anchor = t.length();
        t = apply(s, t, GLFW.GLFW_KEY_BACKSPACE, CTRL, false, null);
        assertEquals("foo bar  ", t);
    }

    @Test
    void clipboardCopyCutPasteAndSelectAll() {
        TextEditing.Clipboard cb = TextEditing.Clipboard.inMemory();
        TextEditing.State s = new TextEditing.State(0);
        String t = "hello world";
        apply(s, t, GLFW.GLFW_KEY_A, CTRL, false, cb);
        assertEquals("hello world", s.selected(t));
        apply(s, t, GLFW.GLFW_KEY_C, CTRL, false, cb);
        assertEquals("hello world", cb.get());
        s.anchor = 0;
        s.caret = 5;
        t = apply(s, t, GLFW.GLFW_KEY_X, CTRL, false, cb);
        assertEquals(" world", t);
        assertEquals("hello", cb.get());
        s.caret = s.anchor = t.length();
        t = apply(s, t, GLFW.GLFW_KEY_V, CTRL, false, cb);
        assertEquals(" worldhello", t);
        cb.set("a\r\nb");
        s.caret = s.anchor = 0;
        assertEquals("a b", apply(s, "", GLFW.GLFW_KEY_V, CTRL, false, cb), "1 行の欄では改行を空白にして貼る");
    }

    @Test
    void multilineEnterAndVerticalMovement() {
        TextEditing.State s = new TextEditing.State(0);
        String t = "ab\ncdef\ng";
        s.set(6, false); // "cdef" の 'e' の前（行頭から 3 → 列 3）
        apply(s, t, GLFW.GLFW_KEY_UP, 0, true, null);
        assertEquals(2, s.caret, "短い上の行では行末へ");
        apply(s, t, GLFW.GLFW_KEY_DOWN, 0, true, null);
        assertEquals(5, s.caret, "列 2 を保って下へ");
        apply(s, t, GLFW.GLFW_KEY_HOME, 0, true, null);
        assertEquals(3, s.caret);
        apply(s, t, GLFW.GLFW_KEY_END, 0, true, null);
        assertEquals(7, s.caret);
        TextEditing.Result r = TextEditing.key(s, t, GLFW.GLFW_KEY_ENTER, 0, true, null);
        assertEquals("ab\ncdef\n\ng".length(), r.text().length());
        assertFalse(TextEditing.key(s, t, GLFW.GLFW_KEY_ENTER, 0, false, null).handled(), "1 行の欄の Enter は欄では使わない");
        assertFalse(TextEditing.key(s, t, GLFW.GLFW_KEY_TAB, 0, true, null).handled());
    }

    @Test
    void surrogatePairsAreNotSplit() {
        String t = "a😀b"; // 絵文字（2 char）
        TextEditing.State s = new TextEditing.State(3);
        apply(s, t, GLFW.GLFW_KEY_LEFT, 0, false, null);
        assertEquals(1, s.caret);
        s.set(3, false);
        assertEquals("ab", apply(s, t, GLFW.GLFW_KEY_BACKSPACE, 0, false, null));
    }

    // ---- UiFocus・StandaloneView ----

    private static final String TABS = "<html><body style='margin:0'>"
            + "<button id='p2' tabindex='2' style='position:absolute;left:0;top:0;width:50px;height:20px'>2</button>"
            + "<button id='n1' style='position:absolute;left:0;top:30px;width:50px;height:20px'>n</button>"
            + "<button id='p1' tabindex='1' style='position:absolute;left:0;top:60px;width:50px;height:20px'>1</button>"
            + "<button id='skip' tabindex='-1' style='position:absolute;left:0;top:90px;width:50px;height:20px'>s</button>"
            + "<button id='off' disabled style='position:absolute;left:0;top:120px;width:50px;height:20px'>d</button>"
            + "</body></html>";

    @Test
    void positiveTabindexComesFirstAndNegativeOrDisabledAreSkipped() {
        StandaloneView v = new StandaloneView(TABS, 200, 200);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(200, 200, 1f)) {
            v.frame(0, cpu, true);
            List<String> order = new ArrayList<>();
            for (UiFocus.Candidate c : UiFocus.tabOrder(v.ui())) order.add(c.node().getId());
            assertEquals(List.of("p1", "p2", "n1"), order);
            List<String> walked = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                v.keyDown(GLFW.GLFW_KEY_TAB, 0);
                walked.add(v.focusedId());
            }
            assertEquals(List.of("p1", "p2", "n1", "p1"), walked);
            // -1 はクリックならフォーカスできる
            v.mouseMove(10, 100, cpu.sessionId());
            v.mouseButton(true, cpu.sessionId());
            v.mouseButton(false, cpu.sessionId());
            assertEquals("skip", v.focusedId());
        }
    }

    @Test
    void arrowKeysMoveFocusSpatially() {
        String html = "<html><body style='margin:0'>"
                + "<button id='a' style='position:absolute;left:0;top:0;width:50px;height:20px'>a</button>"
                + "<button id='b' style='position:absolute;left:100px;top:0;width:50px;height:20px'>b</button>"
                + "<button id='c' style='position:absolute;left:0;top:60px;width:50px;height:20px'>c</button>"
                + "</body></html>";
        StandaloneView v = new StandaloneView(html, 200, 200);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(200, 200, 1f)) {
            v.frame(0, cpu, true);
            v.keyDown(GLFW.GLFW_KEY_TAB, 0);
            assertEquals("a", v.focusedId());
            v.keyDown(GLFW.GLFW_KEY_RIGHT, 0);
            assertEquals("b", v.focusedId());
            v.keyDown(GLFW.GLFW_KEY_LEFT, 0);
            assertEquals("a", v.focusedId());
            v.keyDown(GLFW.GLFW_KEY_DOWN, 0);
            assertEquals("c", v.focusedId());
            v.keyDown(GLFW.GLFW_KEY_DOWN, 0);
            assertEquals("c", v.focusedId(), "向きの先に何も無ければ動かない");
        }
    }

    private static final String FIELDS = "<html><body style='margin:0;background:#fff'>"
            + "<input id='t' type='text' value='hello' style='position:absolute;left:0;top:0;width:180px;height:24px;font-size:16px;color:#000'>"
            + "<textarea id='ta' style='position:absolute;left:0;top:40px;width:180px;height:80px;font-size:16px'>ab\ncd</textarea>"
            + "</body></html>";

    private static String value(StandaloneView v, String id) {
        return v.root().getNodeById(id).attrs.getOrDefault("value", "");
    }

    @Test
    void viewEditsAtTheCaretWithSelectionAndClipboard() {
        StandaloneView v = new StandaloneView(FIELDS, 200, 200);
        TextEditing.Clipboard cb = TextEditing.Clipboard.inMemory();
        v.setClipboard(cb);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(200, 200, 1f)) {
            v.frame(0, cpu, true);
            String s = cpu.sessionId();
            List<String> inputs = new ArrayList<>();
            v.root().getNodeById("t").addEventListener("input", e -> inputs.add(value(v, "t")));
            // 欄の左端をクリック → キャレットは先頭
            v.mouseMove(2, 12, s);
            v.mouseButton(true, s);
            v.mouseButton(false, s);
            assertEquals("t", v.focusedId());
            assertEquals(0, v.editState(v.root().getNodeById("t")).caret);
            v.charTyped('>');
            assertEquals(">hello", value(v, "t"));
            v.keyDown(GLFW.GLFW_KEY_END, 0);
            v.keyDown(GLFW.GLFW_KEY_LEFT, SHIFT);
            v.keyDown(GLFW.GLFW_KEY_LEFT, SHIFT);
            v.keyDown(GLFW.GLFW_KEY_C, CTRL);
            assertEquals("lo", cb.get());
            v.keyDown(GLFW.GLFW_KEY_BACKSPACE, 0);
            assertEquals(">hel", value(v, "t"));
            v.keyDown(GLFW.GLFW_KEY_V, CTRL);
            assertEquals(">hello", value(v, "t"));
            assertFalse(inputs.isEmpty());
            // 右端より先のクリックは末尾
            v.mouseMove(170, 12, s);
            v.mouseButton(true, s);
            v.mouseButton(false, s);
            assertEquals(6, v.editState(v.root().getNodeById("t")).caret);
        }
    }

    @Test
    void beforeInputCanVetoAnEditAndKeepsTheCaret() {
        StandaloneView v = new StandaloneView(FIELDS, 200, 200);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(200, 200, 1f)) {
            v.frame(0, cpu, true);
            v.keyDown(GLFW.GLFW_KEY_TAB, 0);
            v.root().getNodeById("t").addEventListener("beforeinput", e -> e.preventDefault());
            assertFalse(v.charTyped('x'));
            assertEquals("hello", value(v, "t"));
            assertEquals(5, v.editState(v.root().getNodeById("t")).caret);
        }
    }

    @Test
    void textareaKeepsNewlinesAndMovesVertically() {
        StandaloneView v = new StandaloneView(FIELDS, 200, 200);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(200, 200, 1f)) {
            v.frame(0, cpu, true);
            v.keyDown(GLFW.GLFW_KEY_TAB, 0);
            v.keyDown(GLFW.GLFW_KEY_TAB, 0);
            assertEquals("ta", v.focusedId());
            HtmlNode ta = v.root().getNodeById("ta");
            v.keyDown(GLFW.GLFW_KEY_HOME, CTRL); // 先頭へ
            v.keyDown(GLFW.GLFW_KEY_DOWN, 0);
            assertEquals(3, v.editState(ta).caret);
            v.keyDown(GLFW.GLFW_KEY_ENTER, 0);
            assertEquals("ab\n\ncd", value(v, "ta"));
            v.keyDown(GLFW.GLFW_KEY_UP, 0);
            assertEquals(3, v.editState(ta).caret, "空の 2 行目の列 0");
        }
    }

    @Test
    void caretAndSelectionArePaintedOnTheCanvas() {
        StandaloneView v = new StandaloneView(FIELDS, 200, 200);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(200, 200, 1f)) {
            v.frame(0, cpu, true);
            int[] before = scan(cpu);
            v.keyDown(GLFW.GLFW_KEY_TAB, 0); // 末尾にキャレット
            assertTrue(v.needsFrame());
            assertTrue(v.frame(16, cpu, false));
            int[] withCaret = scan(cpu);
            assertTrue(withCaret[0] < before[0] || withCaret[1] != before[1], "キャレットが描かれる: " + before[0] + "→" + withCaret[0]);
            v.keyDown(GLFW.GLFW_KEY_A, CTRL);
            v.frame(32, cpu, false);
            boolean blue = false;
            for (int x = 4; x < 60 && !blue; x++) {
                int p = cpu.pixmap().getStraight(x, 12);
                blue = (p & 0xFF) > (p >> 16 & 0xFF) + 30;
            }
            assertTrue(blue, "選択範囲が青く塗られる");
        }
    }

    /** 入力欄の帯（y 4..20）で背景以外の画素数と、最も右にある暗い画素の x。 */
    private static int[] scan(SoftwareRenderBackend cpu) {
        int dark = 0, right = -1;
        for (int y = 4; y < 20; y++) {
            for (int x = 0; x < 180; x++) {
                if ((cpu.pixmap().getStraight(x, y) & 0xFFFFFF) < 0x404040) {
                    dark++;
                    right = Math.max(right, x);
                }
            }
        }
        return new int[]{-dark, right};
    }

    // ---- select ----

    private static final String SELECT = "<html><body style='margin:0;background:#fff'>"
            + "<select id='s' style='position:absolute;left:10px;top:10px;width:120px;height:24px'>"
            + "<option value='r'>Red</option><option value='g' disabled>Green</option><option value='b'>Blue</option>"
            + "</select><input id='after' type='text' style='position:absolute;left:10px;top:150px;width:100px;height:20px'>"
            + "</body></html>";

    @Test
    void selectOpensChoosesWithTheMouseAndFiresChange() {
        StandaloneView v = new StandaloneView(SELECT, 200, 200);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(200, 200, 1f)) {
            v.frame(0, cpu, true);
            String s = cpu.sessionId();
            List<String> changes = new ArrayList<>();
            v.root().getNodeById("s").addEventListener("change", e -> changes.add(value(v, "s")));
            v.mouseMove(20, 20, s);
            v.mouseButton(true, s);
            v.mouseButton(false, s);
            SelectPopup pop = v.selectPopup();
            assertTrue(pop.isOpen());
            assertEquals(3, pop.options().size());
            assertTrue(v.frame(16, cpu, false), "開いたら描き直す");
            // 3 行目（Blue）をクリック。ポップアップは select の下 2px: y = 10 + 24 + 2 = 36、行高 18
            v.mouseMove(30, 36 + 1 + 2 * SelectPopup.ROW_HEIGHT + 4, s);
            v.mouseButton(true, s);
            v.mouseButton(false, s);
            assertFalse(pop.isOpen());
            assertEquals("b", value(v, "s"));
            assertEquals(List.of("b"), changes);
        }
    }

    @Test
    void selectKeyboardNavigationSkipsDisabledAndEscapeCloses() {
        StandaloneView v = new StandaloneView(SELECT, 200, 200);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(200, 200, 1f)) {
            v.frame(0, cpu, true);
            v.keyDown(GLFW.GLFW_KEY_TAB, 0);
            assertEquals("s", v.focusedId());
            v.keyDown(GLFW.GLFW_KEY_ENTER, 0);
            assertTrue(v.selectPopup().isOpen());
            assertEquals(0, v.selectPopup().highlightedIndex());
            v.keyDown(GLFW.GLFW_KEY_DOWN, 0);
            assertEquals(2, v.selectPopup().highlightedIndex(), "disabled の Green は飛ばす");
            v.keyDown(GLFW.GLFW_KEY_ESCAPE, 0);
            assertFalse(v.selectPopup().isOpen());
            assertEquals("", value(v, "s"), "Esc では値は変わらない");
            v.keyDown(GLFW.GLFW_KEY_SPACE, 0);
            v.keyDown(GLFW.GLFW_KEY_DOWN, 0);
            v.keyDown(GLFW.GLFW_KEY_ENTER, 0);
            assertEquals("b", value(v, "s"));
            assertFalse(v.selectPopup().isOpen());
            // 開いたまま Tab: 閉じて次へ
            v.keyDown(GLFW.GLFW_KEY_ENTER, 0);
            assertTrue(v.selectPopup().isOpen());
            v.keyDown(GLFW.GLFW_KEY_TAB, 0);
            assertFalse(v.selectPopup().isOpen());
            assertEquals("after", v.focusedId());
        }
    }

    @Test
    void selectPopupIsPaintedOverTheDocumentAndClickOutsideJustCloses() {
        StandaloneView v = new StandaloneView(SELECT, 200, 200);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(200, 200, 1f)) {
            v.frame(0, cpu, true);
            String s = cpu.sessionId();
            assertEquals(0xFFFFFF, cpu.pixmap().getStraight(60, 70) & 0xFFFFFF);
            v.keyDown(GLFW.GLFW_KEY_TAB, 0);
            v.keyDown(GLFW.GLFW_KEY_ENTER, 0);
            v.frame(16, cpu, false);
            assertEquals(0x181825, cpu.pixmap().getStraight(125, 70) & 0xFFFFFF, "ポップアップの背景が重なる");
            v.mouseMove(150, 170, s);
            v.mouseButton(true, s);
            v.mouseButton(false, s);
            assertFalse(v.selectPopup().isOpen());
            assertEquals("", value(v, "s"));
            v.frame(32, cpu, false);
            assertEquals(0xFFFFFF, cpu.pixmap().getStraight(125, 70) & 0xFFFFFF, "閉じたら消える");
        }
    }
}
