package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import com.nishiyu.enderframelib.core.paint.DisplayListBuilder;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * K5: 位置の決め直しを後回しにしている間（スクロールだけの更新のあと）の文字の変更が、通常の経路（先に位置を決め直し、
 * 文字の箱だけを測り直す）で、最初から作った結果と同じになること。旧 {@code STALE_TEXT_PATCH}（古い位置の枠で直す近道）の撤去後の保証。
 */
public class StaleTextPatchTest {
    private static String html() {
        StringBuilder sb = new StringBuilder("<style>body{margin:0;font-size:14px} #hud{height:30px;background:#123;color:#fff}"
                + " #sc{height:120px;overflow:auto} .r{height:24px;border-bottom:1px solid #888}</style>"
                + "<div id=hud><span id=t>00:00</span></div><div id=sc>");
        for (int i = 0; i < 60; i++) sb.append("<div class=r id=r").append(i).append(">row ").append(i).append("</div>");
        return sb.append("</div><p id=tail>tail text</p>").toString();
    }

    private static HtmlNode textChild(StandaloneView v, String id) {
        for (HtmlNode c : v.root().getNodeById(id).children) if (c instanceof HtmlText) return c;
        throw new IllegalStateException(id);
    }

    private static String sig(DisplayItem i) {
        return i.id() + "|" + i.baseId() + "|" + i.type() + "|" + i.x() + "," + i.y() + "," + i.width() + "," + i.height() + "|" + i.text() + "|" + i.color() + "|" + i.bgColor();
    }

    private static void assertMatchesFresh(StandaloneView v, String step) {
        UiView ui = v.ui();
        ui.flushInvalidation();
        List<String> actual = new ArrayList<>(), expected = new ArrayList<>();
        for (DisplayItem i : ui.renderCache()) actual.add(sig(i));
        for (DisplayItem i : new DisplayListBuilder(320, 240, ui.document().sheet.keyframes, ui.scrollState()).render(ui.layoutRoot(), false)) expected.add(sig(i));
        assertEquals(expected, actual, step);
    }

    @Test
    void textChangeWhileScrollLayoutIsDeferredMatchesFreshBuild() {
        try (StandaloneView v = new StandaloneView(html(), 320, 240, FontResolver.FALLBACK); var be = new MutationScenario.NoDraw()) {
            long now = 1000;
            v.frame(now, be, true);
            boolean sawStale = false;
            for (int step = 0; step < 12; step++) {
                v.mouseMove(40, 60, be.sessionId());
                v.scroll(0, step % 3 == 2 ? -1 : 2, be.sessionId());
                now += 16;
                v.frame(now, be, true);
                sawStale |= v.ui().layoutStale;
                ((HtmlText) textChild(v, "t")).setData("00:" + (10 + step));  // 毎秒のタイマー相当: 文字だけの更新
                if (step % 4 == 3) ((HtmlText) textChild(v, "tail")).setData("tail text " + step + " wraps? ".repeat(step));
                now += 16;
                v.frame(now, be, true);
                assertMatchesFresh(v, "step " + step);
            }
            assertTrue(sawStale, "場面が成り立っていない（位置の決め直しの後回しが起きていない）");
        }
    }
}
