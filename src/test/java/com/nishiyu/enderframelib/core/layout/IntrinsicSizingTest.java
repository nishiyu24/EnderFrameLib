package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.StyleResolver;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutContext.FormattingContext;
import com.nishiyu.enderframelib.core.layout.LayoutContext.ScrollState;
import com.nishiyu.enderframelib.core.layout.LayoutContext.Viewport;
import com.nishiyu.enderframelib.core.text.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内在サイズ（min-content / max-content）キーワードと flex の基本サイズ。
 *
 * 何が壊れていたか: {@code min-width: min-content} は長さとして解析できず 0 px になり、
 * ua.css がボタンと入力欄に付けているため flex の shrink でそれらが潰れていた。
 */
public class IntrinsicSizingTest {

    private static final Viewport VIEWPORT = new Viewport(1920, 1080);

    @Test
    public void testMinContentKeywordKeepsTheButtonFromCollapsing() {
        LayoutBox row = resolvedBox("div", "display", "flex", "flex-direction", "row",
                "width", "40px", "height", "30px");
        LayoutBox button = resolvedBox("button", "min-width", "min-content");
        LayoutBox filler = resolvedBox("div", "display", "block", "width", "200px", "height", "10px");
        row.children.add(button);
        row.children.add(filler);

        LayoutContext ctx = newContext();
        row.measure(800, 600, blockRoot(), ctx);
        row.layout(0, 0, 800, 600, ctx);

        assertTrue(button.w > 0, "min-width: min-content が 0 px として扱われている: " + button.w);
        assertEquals(button.getMinContentWidth(ctx), button.w,
                "min-content 幅より狭く縮められている");
    }

    @Test
    public void testMaxContentKeywordDoesNotCapTheWidth() {
        LayoutBox box = resolvedBox("div", "display", "block", "width", "120px",
                "height", "10px", "max-width", "max-content");

        LayoutContext ctx = newContext();
        box.measure(800, 600, blockRoot(), ctx);

        assertEquals(120, box.w, "max-width: max-content が上限として誤って効いている");
    }

    @Test
    public void testFlexOneSplitsTheRowEvenly() {
        // ua.css の `th, td { flex: 1 }` と同じ形。flex: 1 の 2 列は等分される。
        LayoutBox row = resolvedBox("div", "display", "flex", "flex-direction", "row",
                "width", "200px", "height", "20px");
        LayoutBox a = resolvedBox("div", "flex", "1", "height", "20px");
        LayoutBox b = resolvedBox("div", "flex", "1", "height", "20px");
        row.children.add(a);
        row.children.add(b);

        LayoutContext ctx = newContext();
        row.measure(800, 600, blockRoot(), ctx);
        row.layout(0, 0, 800, 600, ctx);

        assertEquals(100, a.w, "1 列目が等分されていない");
        assertEquals(100, b.w, "2 列目が等分されていない");
        assertEquals(100, b.x, "2 列目の位置が等分前提になっていない");
    }

    private static LayoutContext newContext() {
        return new LayoutContext(VIEWPORT, src -> new int[]{32, 32}, new ScrollState(), FontResolver.FALLBACK);
    }

    private static FormattingContext blockRoot() {
        return new FormattingContext("block", "stretch", false);
    }

    private static LayoutBox resolvedBox(String tag, String... keyValues) {
        Map<String, String> raw = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            raw.put(keyValues[i], keyValues[i + 1]);
        }
        return new LayoutBox(new HtmlNode(tag), StyleResolver.finalizeComputedStyle(raw, null, null));
    }
}
