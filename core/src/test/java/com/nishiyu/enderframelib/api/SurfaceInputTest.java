package com.nishiyu.enderframelib.api;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.api.event.UIEvents;
import com.nishiyu.enderframelib.common.Keys;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** WebViewSurface の入力: 左・右・中ボタン、修飾キー、クリップボード。 */
class SurfaceInputTest {

    private static final String PAGE =
            "<html><body style=\"margin:0\">"
                    + "<button id=\"b\" style=\"display:block;width:80px;height:30px\">x</button>"
                    + "<input id=\"i\" style=\"position:absolute;left:0px;top:50px;width:100px;height:24px\">"
                    + "</body></html>";

    private static WebViewSurface surface() {
        return EnderEngine.html(PAGE).size(160, 100).build();
    }

    /** 全種類のマウスイベントを 1 つのログへ。"type:button" の形。 */
    private static List<String> record(WebViewSurface s, String id) {
        List<String> log = new ArrayList<>();
        for (String type : List.of("mousedown", "mouseup", "click", "dblclick", "contextmenu", "auxclick")) {
            s.dom().getElementById(id).addEventListener(type, ev -> {
                short b = ev instanceof UIEvents.Mouse m ? m.getButton() : -9;
                log.add(type + ":" + b);
            });
        }
        return log;
    }

    private static void click(WebViewSurface s, MouseButton b, double x, double y, long t) {
        s.mouseMove(x, y);
        s.frame(t, false);
        s.mouseButton(b, true);
        s.mouseButton(b, false);
        s.frame(t + 1, false);
    }

    @Test
    void leftClickDeliversClickButSwallowsNoOtherButtons() {
        try (WebViewSurface s = surface()) {
            List<String> log = record(s, "b");
            s.frame(0, true);
            click(s, MouseButton.LEFT, 10, 10, 10);
            assertEquals(List.of("mousedown:0", "mouseup:0", "click:0"), log);
        }
    }

    @Test
    void rightClickDeliversContextmenuAndAuxclick() {
        try (WebViewSurface s = surface()) {
            List<String> log = record(s, "b");
            s.frame(0, true);
            click(s, MouseButton.RIGHT, 10, 10, 10);
            assertEquals(List.of("mousedown:2", "mouseup:2", "contextmenu:2", "auxclick:2"), log);
        }
    }

    @Test
    void middleClickDeliversAuxclickOnly() {
        try (WebViewSurface s = surface()) {
            List<String> log = record(s, "b");
            s.frame(0, true);
            click(s, MouseButton.MIDDLE, 10, 10, 10);
            assertEquals(List.of("mousedown:1", "mouseup:1", "auxclick:1"), log);
        }
    }

    @Test
    void defaultMouseButtonOverloadIsTheLeftButton() {
        try (WebViewSurface s = surface()) {
            List<String> log = record(s, "b");
            s.frame(0, true);
            s.mouseMove(10, 10);
            s.frame(1, false);
            s.mouseButton(true);
            s.mouseButton(false);
            assertTrue(log.contains("click:0"), log.toString());
        }
    }

    @Test
    void anotherButtonPressedWhileOneIsHeldIsIgnored() {
        try (WebViewSurface s = surface()) {
            List<String> log = record(s, "b");
            s.frame(0, true);
            s.mouseMove(10, 10);
            s.frame(1, false);
            s.mouseButton(MouseButton.RIGHT, true);
            s.mouseButton(MouseButton.MIDDLE, true);
            s.mouseButton(MouseButton.MIDDLE, false);
            s.mouseButton(MouseButton.RIGHT, false);
            assertEquals(List.of("mousedown:2", "mouseup:2", "contextmenu:2", "auxclick:2"), log);
        }
    }

    @Test
    void releaseWithoutAPressIsIgnored() {
        try (WebViewSurface s = surface()) {
            List<String> log = record(s, "b");
            s.frame(0, true);
            s.mouseMove(10, 10);
            s.mouseButton(MouseButton.RIGHT, false);
            assertTrue(log.isEmpty(), log.toString());
        }
    }

    @Test
    void onlyTheLeftButtonMovesFocus() {
        try (WebViewSurface s = surface()) {
            s.frame(0, true);
            click(s, MouseButton.RIGHT, 20, 60, 10);
            assertNull(s.focusedId(), "右クリックではフォーカスしない");
            click(s, MouseButton.LEFT, 20, 60, 20);
            assertEquals("i", s.focusedId());
        }
    }

    @Test
    void rightClickOutsideAnyInteractiveElementDoesNotThrow() {
        try (WebViewSurface s = surface()) {
            s.frame(0, true);
            assertDoesNotThrow(() -> click(s, MouseButton.RIGHT, 150, 95, 10));
        }
    }

    @Test
    void nullButtonIsRejectedAndGlfwNumbersMap() {
        try (WebViewSurface s = surface()) {
            assertThrows(NullPointerException.class, () -> s.mouseButton(null, true));
        }
        assertEquals(MouseButton.LEFT, MouseButton.fromGlfw(0));
        assertEquals(MouseButton.RIGHT, MouseButton.fromGlfw(1));
        assertEquals(MouseButton.MIDDLE, MouseButton.fromGlfw(2));
        assertNull(MouseButton.fromGlfw(3));
        assertNull(MouseButton.fromGlfw(-1));
        assertEquals(1, MouseButton.RIGHT.glfwButton());
    }

    @Test
    void keyModifiersReachTheDomEvent() {
        try (WebViewSurface s = surface()) {
            List<String> log = new ArrayList<>();
            s.dom().addEventListener("keydown", ev -> {
                UIEvents.Keyboard k = (UIEvents.Keyboard) ev;
                log.add(k.getKey() + ":" + k.isCtrlKey() + k.isShiftKey() + k.isAltKey() + k.isMetaKey());
            });
            s.frame(0, true);
            s.keyDown(Keys.A, KeyMods.CONTROL | KeyMods.SHIFT);
            s.keyDown(Keys.A, KeyMods.ALT | KeyMods.SUPER);
            assertEquals(2, log.size(), log.toString());
            assertTrue(log.get(0).endsWith(":truetruefalsefalse"), log.get(0));
            assertTrue(log.get(1).endsWith(":falsefalsetruetrue"), log.get(1));
        }
    }

    @Test
    void keyModConstantsMatchTheEngineValues() {
        assertEquals(Keys.MOD_SHIFT, KeyMods.SHIFT);
        assertEquals(Keys.MOD_CONTROL, KeyMods.CONTROL);
        assertEquals(Keys.MOD_ALT, KeyMods.ALT);
        assertEquals(Keys.MOD_SUPER, KeyMods.SUPER);
    }

    @Test
    void customClipboardIsUsedByCopyCutAndPaste() {
        try (WebViewSurface s = surface()) {
            List<String> writes = new ArrayList<>();
            String[] held = {"pasted"};
            s.setClipboard(new SurfaceClipboard() {
                @Override public String get() { return held[0]; }
                @Override public void set(String text) { writes.add(text); held[0] = text; }
            });
            s.frame(0, true);
            click(s, MouseButton.LEFT, 20, 60, 10);
            assertEquals("i", s.focusedId());
            s.charTyped('a');
            s.charTyped('b');
            s.keyDown(Keys.A, KeyMods.CONTROL);
            s.keyDown(Keys.C, KeyMods.CONTROL);
            assertEquals(List.of("ab"), writes, "コピーはホストのクリップボードへ");
            held[0] = "zz";
            s.keyDown(Keys.V, KeyMods.CONTROL);
            s.keyDown(Keys.A, KeyMods.CONTROL);
            s.keyDown(Keys.X, KeyMods.CONTROL);
            assertEquals(List.of("ab", "zz"), writes, "貼り付けはホストの値を入れ、切り取りで書き戻す");
        }
    }

    @Test
    void clipboardCanBeResetAndNullReadsAreEmpty() {
        try (WebViewSurface s = surface()) {
            s.setClipboard(new SurfaceClipboard() {
                @Override public String get() { return null; }
                @Override public void set(String text) {}
            });
            s.frame(0, true);
            click(s, MouseButton.LEFT, 20, 60, 10);
            assertDoesNotThrow(() -> s.keyDown(Keys.V, KeyMods.CONTROL));
            s.setClipboard(null);
            assertDoesNotThrow(() -> s.keyDown(Keys.V, KeyMods.CONTROL));
        }
    }
}
