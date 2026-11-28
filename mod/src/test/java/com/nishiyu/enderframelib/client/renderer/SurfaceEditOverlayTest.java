package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.scene.SceneLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 面の編集中のキャレット（{@link SurfaceEditOverlay}）は、欄の位置をシーンから引く（M3b-4c-2b-3b-2b-1。表示項目の列は読まない）:
 * キャレットは欄の内側にあり、欄を含むコンテナをスクロールするとキャレットも同じだけ動く。
 */
class SurfaceEditOverlayTest {

    @Test
    void caretSitsInsideTheLocatedFieldAndFollowsScrolling() {
        SessionContext session = new SessionContext("surfaceCaretSession");
        try {
            session.loadLocalHtml("<html><body style='margin:0'><div id='box' style='width:200px;height:80px;overflow:auto'>"
                    + "<div style='height:40px'></div><input id='f' value='hello' style='width:120px;height:24px'>"
                    + "<div style='height:300px'></div></div></body></html>", 300, 200);
            session.flushInvalidation();
            session.activateInput("f_input", "f");

            SceneLocation field = session.locate("f");
            assertNotNull(field, "欄がシーンにある");
            DisplayItem caret = caretOf(session);
            assertTrue(caret.x() >= field.x() && caret.x() <= field.x() + field.w(), "キャレットは欄の横の範囲に: " + caret);
            assertTrue(caret.y() >= field.y() && caret.y() + caret.height() <= field.y() + field.h() + 1, "キャレットは欄の縦の範囲に: " + caret);

            HtmlNode box = session.document().root.getElementById("box");
            session.scrollState().setScroll(box, 0f, 30f);
            session.flushInvalidation();
            DisplayItem scrolled = caretOf(session);
            assertEquals(caret.x(), scrolled.x(), "横には動かない");
            assertEquals(caret.y() - 30, scrolled.y(), 1, "コンテナのスクロールの分だけ上へ動く");
        } finally {
            session.close();
        }
    }

    private static DisplayItem caretOf(SessionContext session) {
        List<DisplayItem> items = SurfaceEditOverlay.caretItems(session, "surfaceCaretSession");
        return items.stream().filter(i -> i.id().endsWith("_caret")).findFirst()
                .orElseThrow(() -> new AssertionError("キャレットの項目が無い: " + items));
    }
}
