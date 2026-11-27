package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.CssAnimator;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.resource.PaintResources;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 描画器を閉じると、自分で発行したセッションの補間状態（{@link CssAnimator} の既定の置き場）が捨てられること。
 * 捨てないと状態が握る表示項目ごと描画器を作るたびに溜まり、全テストを 1 本の JVM で流す CI が OOM で落ちた。
 * 名前を借りた描画器（GPU 版の保険の CPU 描画器）は、持ち主の状態を捨ててはいけない。
 */
class BackendSessionReleaseTest {
    private static final String HTML = "<style>body{margin:0}.b{width:40px;height:20px;margin:4px;background:#48c;transition:opacity .2s}</style>"
            + "<div class=\"b\" id=\"a\"></div><div class=\"b\" id=\"b\">x</div><div class=\"b\" id=\"c\"></div>";

    /** 描いた項目のうち、{@code session} に補間状態が残っているものの数。 */
    private static int statesLeft(String session, List<DisplayItem> items) {
        int n = 0;
        for (DisplayItem it : items) if (CssAnimator.peekState(session, it) != null) n++;
        return n;
    }

    @Test
    void closingAnOwnedSessionDropsItsAnimationState() {
        try (StandaloneView v = new StandaloneView(HTML, 200, 100, FontResolver.FALLBACK)) {
            SoftwareRenderBackend sw = new SoftwareRenderBackend(200, 100, 1f);
            v.frame(0L, sw, true);
            List<DisplayItem> items = v.items();
            assertTrue(statesLeft(sw.sessionId(), items) > 0, "描いた項目に補間状態ができていない（前提）");
            sw.close();
            assertEquals(0, statesLeft(sw.sessionId(), items), "閉じた描画器のセッションに補間状態が残った");
        }
    }

    @Test
    void closingABorrowedSessionKeepsTheOwnersState() {
        String session = "borrowed-" + System.identityHashCode(this);
        try (StandaloneView v = new StandaloneView(HTML, 200, 100, FontResolver.FALLBACK)) {
            SoftwareRenderBackend borrower = new SoftwareRenderBackend(200, 100, 1f, session, new PaintResources());
            v.frame(0L, borrower, true);
            List<DisplayItem> items = v.items();
            int before = statesLeft(session, items);
            assertTrue(before > 0, "描いた項目に補間状態ができていない（前提）");
            borrower.close();
            assertEquals(before, statesLeft(session, items), "名前を借りただけの描画器が持ち主の補間状態を捨てた");
        } finally {
            CssAnimator.clearSession(session);
        }
    }
}
