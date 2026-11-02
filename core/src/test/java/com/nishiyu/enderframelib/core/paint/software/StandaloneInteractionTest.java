package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 5-5: キー入力・フォーム・フォーカス・タイマーが、Minecraft も GL も無しで窓のビューから動くことの確認。 */
class StandaloneInteractionTest {

    private static final String FORM = "<html><body style='margin:0'>"
            + "<input id='a' type='text' style='position:absolute;left:0;top:0;width:100px;height:20px'>"
            + "<input id='b' type='text' style='position:absolute;left:0;top:30px;width:100px;height:20px'>"
            + "<input id='c' type='checkbox' style='position:absolute;left:0;top:60px;width:20px;height:20px'>"
            + "<button id='btn' style='position:absolute;left:0;top:90px;width:60px;height:20px'>go</button>"
            + "<div id='hidden' style='display:none'><input id='h' type='text'></div>"
            + "</body></html>";

    private static HtmlNode node(StandaloneView v, String id) {
        return v.root().getNodeById(id);
    }

    @Test
    void tabMovesFocusInDocumentOrderAndSkipsHidden() {
        StandaloneView v = new StandaloneView(FORM, 200, 200);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(200, 200, 1f)) {
            v.frame(0, cpu, true);
            assertEquals("", v.focusedId());
            v.keyDown(GLFW.GLFW_KEY_TAB, 0);
            assertEquals("a", v.focusedId());
            v.keyDown(GLFW.GLFW_KEY_TAB, 0);
            assertEquals("b", v.focusedId());
            v.keyDown(GLFW.GLFW_KEY_TAB, 0);
            v.keyDown(GLFW.GLFW_KEY_TAB, 0);
            assertEquals("btn", v.focusedId());
            v.keyDown(GLFW.GLFW_KEY_TAB, 0);
            assertEquals("a", v.focusedId(), "display:none の入力欄は飛ばして一周する");
            v.keyDown(GLFW.GLFW_KEY_TAB, GLFW.GLFW_MOD_SHIFT);
            assertEquals("btn", v.focusedId(), "Shift+Tab は逆順");
        }
    }

    @Test
    void clickingFocusesAndTypingEditsTheValue() {
        StandaloneView v = new StandaloneView(FORM, 200, 200);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(200, 200, 1f)) {
            v.frame(0, cpu, true);
            String s = cpu.sessionId();
            v.mouseMove(10, 40, s);
            v.mouseButton(true, s);
            v.mouseButton(false, s);
            assertEquals("b", v.focusedId());

            List<String> inputs = new ArrayList<>();
            node(v, "b").addEventListener("input", e -> inputs.add(node(v, "b").attrs.get("value")));
            assertTrue(v.charTyped('h'));
            assertTrue(v.charTyped('i'));
            assertEquals("hi", node(v, "b").attrs.get("value"));
            assertEquals(List.of("h", "hi"), inputs);
            v.keyDown(GLFW.GLFW_KEY_BACKSPACE, 0);
            assertEquals("h", node(v, "b").attrs.get("value"));
            v.frame(16, cpu, false);
            assertTrue(v.items().stream().anyMatch(i -> i.text() != null && i.text().contains("h")), "表示にも反映される");

            v.mouseMove(150, 150, s); // 何も無い所
            v.mouseButton(true, s);
            v.mouseButton(false, s);
            assertEquals("", v.focusedId(), "フォーカスできない所のクリックで外れる");
            assertFalse(v.charTyped('x'));
        }
    }

    @Test
    void keydownCanBePrevented() {
        StandaloneView v = new StandaloneView(FORM, 200, 200);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(200, 200, 1f)) {
            v.frame(0, cpu, true);
            node(v, "a").addEventListener("keydown", e -> e.preventDefault());
            v.keyDown(GLFW.GLFW_KEY_TAB, 0);
            assertEquals("a", v.focusedId());
            v.keyDown(GLFW.GLFW_KEY_TAB, 0);
            assertEquals("a", v.focusedId(), "keydown の preventDefault でフォーカスが動かない");
            assertTrue(v.charTyped('q'));
        }
    }

    @Test
    void enterFiresChangeOnTextAndSpaceTogglesCheckbox() {
        StandaloneView v = new StandaloneView(FORM, 200, 200);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(200, 200, 1f)) {
            v.frame(0, cpu, true);
            AtomicInteger changes = new AtomicInteger();
            node(v, "a").addEventListener("change", e -> changes.incrementAndGet());
            v.keyDown(GLFW.GLFW_KEY_TAB, 0);
            v.charTyped('z');
            v.keyDown(GLFW.GLFW_KEY_ENTER, 0);
            assertEquals(1, changes.get());

            v.keyDown(GLFW.GLFW_KEY_TAB, 0);
            v.keyDown(GLFW.GLFW_KEY_TAB, 0);
            assertEquals("c", v.focusedId());
            assertFalse(node(v, "c").attrs.containsKey("checked"));
            v.keyDown(GLFW.GLFW_KEY_SPACE, 0);
            assertTrue(node(v, "c").attrs.containsKey("checked"));
            v.keyDown(GLFW.GLFW_KEY_SPACE, 0);
            assertFalse(node(v, "c").attrs.containsKey("checked"));
        }
    }

    @Test
    void enterOnButtonClicksIt() {
        StandaloneView v = new StandaloneView(FORM, 200, 200);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(200, 200, 1f)) {
            v.frame(0, cpu, true);
            AtomicInteger clicks = new AtomicInteger();
            node(v, "btn").addEventListener("click", e -> clicks.incrementAndGet());
            for (int i = 0; i < 4; i++) v.keyDown(GLFW.GLFW_KEY_TAB, 0);
            assertEquals("btn", v.focusedId());
            v.keyDown(GLFW.GLFW_KEY_ENTER, 0);
            assertEquals(1, clicks.get());
        }
    }

    @Test
    void focusStyleAppliesWhileFocused() {
        String html = "<html><head><style>#a{position:absolute;left:0;top:0;width:50px;height:50px;background:#ff0000}"
                + "#a:focus{background:#0000ff}</style></head><body style='margin:0'><button id='a'></button></body></html>";
        StandaloneView v = new StandaloneView(html, 60, 60);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(60, 60, 1f)) {
            v.frame(0, cpu, true);
            assertEquals(0xFF0000, cpu.pixmap().getStraight(25, 25) & 0xFFFFFF);
            v.keyDown(GLFW.GLFW_KEY_TAB, 0);
            assertTrue(v.needsFrame());
            assertTrue(v.frame(16, cpu, false));
            assertEquals(0x0000FF, cpu.pixmap().getStraight(25, 25) & 0xFFFFFF);
        }
    }

    @Test
    void animationFramesAndTimersRunInFrame() {
        StandaloneView v = new StandaloneView(FORM, 200, 200);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(200, 200, 1f)) {
            v.frame(0, cpu, true);
            assertFalse(v.needsFrame());
            AtomicInteger raf = new AtomicInteger();
            List<Double> stamps = new ArrayList<>();
            v.timers().requestAnimationFrame(t -> {
                raf.incrementAndGet();
                stamps.add(t);
            });
            assertTrue(v.needsFrame(), "登録があれば次のフレームが要る");
            v.frame(100, cpu, false);
            assertEquals(1, raf.get());
            assertEquals(100.0, stamps.get(0));
            v.frame(116, cpu, false);
            assertEquals(1, raf.get(), "requestAnimationFrame は 1 回きり");

            AtomicInteger timeout = new AtomicInteger();
            v.timers().setTimeout(() -> {
                timeout.incrementAndGet();
                node(v, "a").setAttribute("value", "timer");
                v.ui().requestRender();
            }, 0);
            v.frame(200, cpu, false);
            assertEquals(1, timeout.get());
            assertTrue(v.timers().isEmpty(), "済んだタイマーは消える");
            v.frame(216, cpu, false);
            assertTrue(v.items().stream().anyMatch(i -> "timer".equals(i.text())), "タイマーでの DOM 変更が表示に出る");
        }
    }

    @Test
    void timerChangeRepaintsWithoutInput() {
        String html = "<html><body style='margin:0'><div id='b' style='position:absolute;left:0;top:0;width:50px;height:50px;background:#ff0000'></div></body></html>";
        StandaloneView v = new StandaloneView(html, 60, 60);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(60, 60, 1f)) {
            v.frame(0, cpu, true);
            v.timers().setTimeout(() -> v.ui().updateNodeStyleLocally("b", "background", "#00ff00"), 0);
            assertTrue(v.frame(10, cpu, false));
            assertEquals(0x00FF00, cpu.pixmap().getStraight(25, 25) & 0xFFFFFF);
        }
    }

    @Test
    void rangeSliderDragsWithThePointer() {
        String html = "<html><body style='margin:0'><input id='r' type='range' min='0' max='100' value='0' "
                + "style='position:absolute;left:0;top:0;width:116px;height:20px'></body></html>";
        StandaloneView v = new StandaloneView(html, 200, 50);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(200, 50, 1f)) {
            v.frame(0, cpu, true);
            String s = cpu.sessionId();
            v.mouseMove(8, 10, s);
            v.mouseButton(true, s);
            v.mouseMove(190, 10, s); // 右端の外まで引いても最大値で止まる
            v.mouseButton(false, s);
            assertEquals("100", node(v, "r").attrs.get("value"));
        }
    }

    @Test
    void closeClearsTimersAndDocument() {
        StandaloneView v = new StandaloneView(FORM, 200, 200);
        v.timers().setTimeout(() -> {}, 10_000);
        v.close();
        assertTrue(v.timers().isEmpty());
        assertNull(v.ui().document());
    }
}
