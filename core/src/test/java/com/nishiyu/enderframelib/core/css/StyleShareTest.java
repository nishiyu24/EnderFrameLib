package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** スタイル解決の使い回し（{@link StyleResolver.ShareCache}）が、使わないときと項目ごとに同じ結果になること。 */
class StyleShareTest {

    private static final String HTML = """
            <style>
              :root { --gap: 6px; --c: #36c }
              body { margin: 0; font-size: 14px; color: #222 }
              .row { height: 18px; margin-bottom: var(--gap); color: var(--c) }
              .row b { color: red; font-size: 1.5em }
              .row:nth-child(odd) { background: #eee }
              .row:hover { background: #cde }
              ul li { padding-left: 2vw }
              .wide { width: 50vw }
              @media (min-width: 300px) { .row i { color: green } }
              input { border: 1px solid #999 }
              .imp { color: blue !important }
            </style>
            <div class=row><span>a</span> <b>x</b> <i>y</i></div>
            <div class=row><span>b</span> <b>x</b> <i>y</i></div>
            <div class=row style="color: purple"><span>c</span> <b>x</b></div>
            <div class=row hidden><span>d</span></div>
            <div class="row imp"><span>e</span></div>
            <div class=row><span>f</span> <b style="font-size: 10px">x</b></div>
            <ul><li>one</li><li>two</li><li class=wide>three</li></ul>
            <input type=text value=v><input type=text value=w>
            <div class=row><span>g</span> <b>x</b> <i>y</i></div>
            """;

    @AfterEach
    void restore() {
        StyleResolver.ShareCache.enabled = true;
    }

    private static List<String> items(boolean share, int w) {
        StyleResolver.ShareCache.enabled = share;
        StandaloneView v = new StandaloneView(HTML, w, 400, FontResolver.FALLBACK);
        v.ui().flushInvalidation();
        List<String> out = new ArrayList<>();
        for (DisplayItem i : v.ui().renderCache()) {
            out.add(i.id() + "|" + i.type() + "|" + i.x() + "," + i.y() + "," + i.width() + "," + i.height()
                    + "|" + i.text() + "|" + i.color() + "|" + i.bgColor());
        }
        return out;
    }

    @Test
    void sharedResolutionMatchesUnsharedItemByItem() {
        for (int w : new int[]{200, 320, 800}) {
            List<String> plain = items(false, w);
            long before = StyleResolver.ShareCache.totalHits();
            List<String> shared = items(true, w);
            assertTrue(StyleResolver.ShareCache.totalHits() > before, "同じ形の要素は使い回されるはず (w=" + w + ")");
            assertTrue(plain.size() > 20);
            assertEquals(plain, shared, "w=" + w);
        }
    }

    @Test
    void restyleAfterHoverMatchesUnshared() {
        List<List<String>> results = new ArrayList<>();
        for (boolean share : new boolean[]{false, true}) {
            StyleResolver.ShareCache.enabled = share;
            StandaloneView v = new StandaloneView(HTML, 400, 400, FontResolver.FALLBACK);
            v.ui().flushInvalidation();
            v.mouseMove(10, 5, "t");
            v.ui().flushInvalidation();
            List<String> out = new ArrayList<>();
            for (DisplayItem i : v.ui().renderCache()) out.add(i.id() + "|" + i.x() + "," + i.y() + "|" + i.bgColor() + "|" + i.color());
            results.add(out);
        }
        assertEquals(results.get(0), results.get(1));
    }
}
