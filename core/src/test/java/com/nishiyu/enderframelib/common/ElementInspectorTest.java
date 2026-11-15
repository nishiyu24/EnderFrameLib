package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** F12 の要素インスペクタ：ポインタの下の要素の箱モデルと算出スタイルを検査し、重ねて描く。 */
public class ElementInspectorTest {

    private static final String PAGE = "<html><body style='margin:0;background:#fff'>"
            + "<div id='box' class='card big' style='position:absolute;left:50px;top:40px;width:100px;height:60px;"
            + "margin:10px;padding:8px;border:4px solid #000;background:#ffffff;color:#112233'>hi</div></body></html>";

    private static int[] frame(StandaloneView view) {
        try (SoftwareRenderBackend backend = new SoftwareRenderBackend(300, 220, 1f)) {
            view.frame(1000, backend, true);
            return backend.pixmap().pixels().clone();
        }
    }

    @Test
    public void f12TogglesAndInspectsTheElementUnderThePointer() {
        StandaloneView view = new StandaloneView(PAGE, 300, 220);
        try {
            assertFalse(view.inspectorEnabled());
            assertNull(view.inspected(), "閉じていれば何も見ない");
            view.keyDown(Keys.F12, 0);
            assertTrue(view.inspectorEnabled());
            view.mouseMove(100, 80, "s");
            ElementInspector.Info info = view.inspected();
            assertNotNull(info);
            assertEquals("div#box.card.big", info.selector());
            assertArrayEquals(new float[]{10, 10, 10, 10}, info.margin());
            assertArrayEquals(new float[]{4, 4, 4, 4}, info.border());
            assertArrayEquals(new float[]{8, 8, 8, 8}, info.padding());
            assertEquals(124f, info.w(), 0.5f, "content 100 + padding 16 + border 8");
            assertEquals("#112233", info.style().get("color"));
            assertEquals("hi", info.text());
            view.keyDown(Keys.F12, 0);
            assertFalse(view.inspectorEnabled());
            assertNull(view.inspected());
        } finally {
            view.close();
        }
    }

    @Test
    public void overlayColoursTheBoxModelRegions() {
        StandaloneView plain = new StandaloneView(PAGE, 300, 220);
        StandaloneView view = new StandaloneView(PAGE, 300, 220);
        try {
            int[] before = frame(plain);
            view.setInspectorEnabled(true);
            view.mouseMove(100, 80, "s");
            int[] after = frame(view);
            ElementInspector.Info info = view.inspected();
            int cx = Math.round(info.x() + info.w() / 2), cy = Math.round(info.y() + info.h() / 2);
            assertNotEquals(before[cy * 300 + cx], after[cy * 300 + cx], "content 領域が色づく");
            int mx = Math.round(info.x()) - 5, my = Math.round(info.y() + info.h() / 2);
            assertNotEquals(before[my * 300 + mx], after[my * 300 + mx], "margin 領域が色づく");
            int ox = 290, oy = 215;
            assertEquals(before[oy * 300 + ox], after[oy * 300 + ox], "要素の外は変わらない");
        } finally {
            plain.close();
            view.close();
        }
    }

    @Test
    public void nothingUnderThePointerOrADisplayNoneNodeInspectsToNull() {
        StandaloneView view = new StandaloneView("<html><body><p id='h' style='display:none'>x</p></body></html>", 300, 220);
        try {
            assertNull(ElementInspector.inspect(view.ui(), null));
            assertNull(ElementInspector.inspect(view.ui(), view.ui().findNode("h")));
            assertTrue(ElementInspector.overlay(null, 300, 220).isEmpty());
        } finally {
            view.close();
        }
    }
}
