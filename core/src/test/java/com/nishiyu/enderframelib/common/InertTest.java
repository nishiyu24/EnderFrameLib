package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** {@code inert} 属性とモーダル {@code <dialog>} による背面不活性化（ヒットテスト・ポインタ・フォーカス・Tab 順・スクロール）。 */
public class InertTest {

    private static List<String> tabIds(StandaloneView v) {
        List<String> ids = new ArrayList<>();
        for (UiFocus.Candidate c : UiFocus.tabOrder(v.ui())) {
            ids.add(c.node().getId());
        }
        return ids;
    }

    private static void clickAt(StandaloneView v, String sessionId, double x, double y) {
        v.mouseMove(x, y, sessionId);
        v.mouseButton(true, sessionId);
        v.mouseButton(false, sessionId);
    }

    @Test
    public void inertAttributeBlocksPointerHitTestFocusAndTabOrder() {
        String html = "<html><body style='margin:0'>"
                + "<button id='a' style='position:absolute;left:0;top:0;width:80px;height:30px'>A</button>"
                + "<div id='wrap' inert style='position:absolute;left:0;top:40px;width:80px;height:40px'>"
                + "  <button id='b' style='width:80px;height:30px'>B</button>"
                + "</div>"
                + "<button id='c' style='position:absolute;left:0;top:90px;width:80px;height:30px'>C</button>"
                + "</body></html>";
        StandaloneView v = new StandaloneView(html, 300, 200);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(300, 200, 1f)) {
            v.frame(0, cpu, true);
            String sid = cpu.sessionId();

            HtmlElement b = (HtmlElement) v.ui().rootNode().getElementById("b");
            AtomicInteger bClicks = new AtomicInteger();
            b.addEventListener("click", e -> bClicks.incrementAndGet());

            assertTrue(b.isInert());
            assertFalse(UiFocus.isMouseFocusable(b));
            assertNull(UiFocus.nearestFocusable(b));
            assertEquals(List.of("a", "c"), tabIds(v), "inert な要素は Tab 順から除外");

            // inert 上のクリック・ホバーは効かず、フォーカスも移らない
            assertNull(v.ui().hitTest(20, 50));
            clickAt(v, sid, 20, 50);
            assertEquals(0, bClicks.get());
            assertNull(v.ui().interactionState().hoveredNode());
            assertEquals("", v.focusedId());

            // プログラムからの focus() も無効
            b.focus();
            assertEquals("", v.focusedId());

            // Tab 順は a -> c -> a を巡る
            v.keyDown(GLFW.GLFW_KEY_TAB, 0);
            assertEquals("a", v.focusedId());
            v.keyDown(GLFW.GLFW_KEY_TAB, 0);
            assertEquals("c", v.focusedId());
            v.keyDown(GLFW.GLFW_KEY_TAB, 0);
            assertEquals("a", v.focusedId());

            // inert を外せば操作可能に戻る
            ((HtmlElement) v.ui().rootNode().getElementById("wrap")).removeAttribute("inert");
            v.frame(16, cpu, true);
            assertFalse(b.isInert());
            assertEquals(List.of("a", "b", "c"), tabIds(v));
            clickAt(v, sid, 20, 50);
            assertEquals(1, bClicks.get());
            assertEquals("b", v.focusedId());

            // フォーカス中に inert を付けるとフォーカスが外れる
            List<String> events = new ArrayList<>();
            b.addEventListener("blur", e -> events.add("blur"));
            b.addEventListener("focusout", e -> events.add("focusout"));
            ((HtmlElement) v.ui().rootNode().getElementById("wrap")).setAttribute("inert", "");
            assertEquals("", v.focusedId());
            assertTrue(events.contains("blur") && events.contains("focusout"));
        }
    }

    @Test
    public void inertOverlayPassesHitTestThroughToNonInertElementBehind() {
        String html = "<html><body style='margin:0'>"
                + "<button id='under' style='position:absolute;left:10px;top:10px;width:80px;height:30px'>Under</button>"
                + "<div id='over' inert style='position:absolute;left:10px;top:10px;width:80px;height:30px;background:#f00'>Over</div>"
                + "</body></html>";
        StandaloneView v = new StandaloneView(html, 200, 100);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(200, 100, 1f)) {
            v.frame(0, cpu, true);
            AtomicInteger underClicks = new AtomicInteger();
            v.ui().rootNode().getElementById("under").addEventListener("click", e -> underClicks.incrementAndGet());
            // ボタンの右下の隅を撃つ。中央付近は文字（"Under"）の項目に当たりうる位置で、書体の幅（OS ごとに違う）で結果が変わる
            var hit = v.ui().hitTest(86, 36);
            assertNotNull(hit);
            assertEquals("under", hit.baseId());
            clickAt(v, cpu.sessionId(), 86, 36);
            assertEquals(1, underClicks.get());
        }
    }

    @Test
    public void modalDialogMakesBackgroundInertEvenWithTransparentBackdrop() {
        String html = "<html><head><style>"
                + "body{margin:0}"
                + "dialog::backdrop{background:transparent}"
                + "</style></head><body>"
                + "<button id='bg' style='position:absolute;left:10px;top:10px;width:80px;height:30px'>BG</button>"
                + "<div id='scroller' style='position:absolute;left:10px;top:50px;width:80px;height:40px;overflow:auto'>"
                + "  <div style='height:200px'>tall</div>"
                + "</div>"
                + "<dialog id='dlg' style='width:120px;height:80px;padding:0;border:0'>"
                + "  <button id='in1' style='width:50px;height:20px'>In1</button>"
                + "  <button id='in2' autofocus style='width:50px;height:20px'>In2</button>"
                + "</dialog>"
                + "</body></html>";
        StandaloneView v = new StandaloneView(html, 300, 200);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(300, 200, 1f)) {
            v.frame(0, cpu, true);
            String sid = cpu.sessionId();

            HtmlElement bg = (HtmlElement) v.ui().rootNode().getElementById("bg");
            HtmlElement dlg = (HtmlElement) v.ui().rootNode().getElementById("dlg");
            HtmlElement scroller = (HtmlElement) v.ui().rootNode().getElementById("scroller");
            AtomicInteger bgClicks = new AtomicInteger();
            bg.addEventListener("click", e -> bgClicks.incrementAndGet());

            // 先に背面ボタンへフォーカスしておく
            bg.focus();
            assertEquals("bg", v.focusedId());

            // showModal で背面が不活性になり、autofocus にフォーカスが移る
            dlg.showModal();
            v.frame(16, cpu, true);

            assertTrue(bg.isInert());
            assertFalse(dlg.isInert());
            assertEquals("in2", v.focusedId(), "autofocus がモーダル内でフォーカスされる");
            assertEquals(List.of("in1", "in2"), tabIds(v), "Tab 順はモーダル内に閉じ込められる");

            // 透明 backdrop でも背面のボタンはヒットテスト・クリック・フォーカス・ホイールを受けない
            assertNull(v.ui().hitTest(30, 20));
            clickAt(v, sid, 30, 20);
            assertEquals(0, bgClicks.get());
            bg.focus();
            assertNotEquals("bg", v.focusedId());

            v.mouseMove(30, 60, sid);
            assertFalse(v.scroll(0, -1, sid));
            assertEquals(0f, v.ui().scrollState().getScrollY(scroller), 0.01f);

            // Tab はモーダル内だけを循環する
            v.ui().setFocusedElement("in1", true);
            v.keyDown(GLFW.GLFW_KEY_TAB, 0);
            assertEquals("in2", v.focusedId());
            v.keyDown(GLFW.GLFW_KEY_TAB, 0);
            assertEquals("in1", v.focusedId());

            // 閉じれば背面の操作性が戻る
            dlg.close();
            v.frame(32, cpu, true);
            assertFalse(bg.isInert());
            assertEquals(List.of("bg"), tabIds(v));
            clickAt(v, sid, 30, 20);
            assertEquals(1, bgClicks.get());
            assertEquals("bg", v.focusedId());
        }
    }

    @Test
    public void stackedModalDialogsAndPopoverAboveModal() {
        String html = "<html><body style='margin:0'>"
                + "<button id='bg' style='position:absolute;left:0;top:0;width:60px;height:20px'>bg</button>"
                + "<dialog id='d1' style='width:120px;height:80px'><button id='b1' style='width:40px;height:20px'>b1</button></dialog>"
                + "<dialog id='d2' style='width:120px;height:80px'><button id='b2' style='width:40px;height:20px'>b2</button>"
                + "  <div id='p' popover><button id='bp' style='width:40px;height:20px'>bp</button></div>"
                + "</dialog>"
                + "</body></html>";
        StandaloneView v = new StandaloneView(html, 300, 200);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(300, 200, 1f)) {
            v.frame(0, cpu, true);
            HtmlElement bg = (HtmlElement) v.ui().rootNode().getElementById("bg");
            HtmlElement d1 = (HtmlElement) v.ui().rootNode().getElementById("d1");
            HtmlElement d2 = (HtmlElement) v.ui().rootNode().getElementById("d2");
            HtmlElement b1 = (HtmlElement) v.ui().rootNode().getElementById("b1");
            HtmlElement b2 = (HtmlElement) v.ui().rootNode().getElementById("b2");
            HtmlElement p = (HtmlElement) v.ui().rootNode().getElementById("p");
            HtmlElement bp = (HtmlElement) v.ui().rootNode().getElementById("bp");

            d1.showModal();
            v.frame(16, cpu, true);
            assertEquals(List.of("b1"), tabIds(v));

            // 2 つ目のモーダルを開くと 1 つ目も不活性になる
            d2.showModal();
            v.frame(32, cpu, true);
            assertTrue(bg.isInert());
            assertTrue(b1.isInert());
            assertFalse(b2.isInert());
            assertEquals(List.of("b2"), tabIds(v));

            // モーダル上で popover を開けば、その popover 内も操作可能
            p.showPopover();
            v.frame(48, cpu, true);
            assertFalse(bp.isInert());
            assertEquals(List.of("b2", "bp"), tabIds(v));

            // Esc で手前の popover -> d2 -> d1 の順に閉じる
            v.keyDown(GLFW.GLFW_KEY_ESCAPE, 0);
            assertFalse(p.isPopoverOpen());
            assertTrue(d2.isModalOpen());
            v.keyDown(GLFW.GLFW_KEY_ESCAPE, 0);
            assertFalse(d2.isModalOpen());
            assertTrue(d1.isModalOpen());
            v.frame(64, cpu, true);
            assertFalse(b1.isInert());
            assertEquals(List.of("b1"), tabIds(v));
        }
    }
}
