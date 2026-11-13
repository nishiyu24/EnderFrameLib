package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ホバーの再スタイルが箱のスライスを差し替えるとき、スクロールの中身をまとめた節（{@code _scrollc}）を落とさない。
 * 落とすと、その箱の子の項目が親を失い、あとのスクロールで動かずにずれる。
 */
class HoverPatchKeepsScrollNodeTest {
    private static String html() {
        StringBuilder cards = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            cards.append("<div class=\"card\"><div class=\"t\">card ").append(i).append("</div><div class=\"d\">text text text text text</div></div>");
        }
        return "<style>body{margin:0;font-size:12px}"
                + ".card{width:120px;height:60px;margin:6px;background:#eef;overflow:hidden}"
                + ".card:hover{background:#fee;box-shadow:0 2px 6px #888}"
                + "#outer{position:absolute;left:0;top:0;width:300px;height:230px;overflow-y:auto;display:flex;flex-wrap:wrap}</style>"
                + "<div id=\"outer\">" + cards + "</div>";
    }

    private static long contentNodes(List<DisplayItem> items) {
        return items.stream().filter(i -> i.type() == ElementType.NODE && i.id().endsWith("_scrollc")).count();
    }

    @Test
    void hoverAfterScrollKeepsEveryScrollContentNode() {
        StandaloneView view = new StandaloneView(html(), 320, 240, FontResolver.FALLBACK);
        try (var backend = new SoftwareRenderBackend(320, 240)) {
            long now = 1000;
            view.frame(now, backend, true);
            String sid = backend.sessionId();
            long before = contentNodes(view.items());
            assertTrue(before > 1, "前提: カードごとにスクロールの節がある");
            for (int i = 0; i < 12; i++) {
                view.mouseMove(20 + (i % 2) * 130, 30 + (i % 3) * 60, sid);
                if (i % 3 == 0) view.scroll(0, -1, sid);
                for (int k = 0; k < 4; k++) { now += 16; view.frame(now, backend, true); }
                assertEquals(before, contentNodes(view.items()), "手 " + i + ": ホバーの差し替えでスクロールの節が減った");
            }
        } finally {
            view.close();
        }
    }
}
