package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.StyleResolver;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.dom.HtmlParser;
import com.nishiyu.enderframelib.core.text.FontMetrics;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LayoutGoldenTest {

    private static final Viewport VIEWPORT = new Viewport(1920, 1080);

    /** 決定的なテストにするため、フォント幅も画像サイズも純Javaのダミーで固定する */
    private static LayoutContext newContext() {
        return new LayoutContext(
                VIEWPORT,
                src -> new int[]{32, 32},
                new ScrollState(),
                FontMetrics.FALLBACK
        );
    }

    private static FormattingContext blockRoot() {
        return new FormattingContext("block", "stretch", false);
    }

    private static ComputedStyle style(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return new ComputedStyle(map);
    }

    private static LayoutBox box(String tag, ComputedStyle style) {
        return new LayoutBox(new HtmlNode(tag), style);
    }

    @Test
    public void testBasicBlockLayout() {
        HtmlNode root = HtmlParser.parse("<div></div>");
        HtmlNode div = root.children.get(0);

        LayoutBox box = new LayoutBox(div, style(
                "display", "block",
                "width", "100px",
                "height", "50px",
                "margin-top", "10px",
                "margin-left", "10px"
        ));

        LayoutContext ctx = newContext();
        box.measure(800, 600, blockRoot(), ctx);
        box.layout(0, 0, 800, 600, ctx);

        assertEquals(10, box.x); // x = parentX(0) + marginLeft(10)
        assertEquals(10, box.y); // y = parentY(0) + marginTop(10)
        assertEquals(100, box.w);
        assertEquals(50, box.h);
    }

    @Test
    public void testShorthandMarginAndPaddingReachTheLayout() {
        // UIParser と同じ経路（StyleResolver 経由）で算出スタイルを作り、
        // "margin: 10px" / "padding: 5px" がレイアウトに効くことを確認する
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("display", "block");
        raw.put("width", "100px");
        raw.put("height", "50px");
        raw.put("margin", "10px");
        raw.put("padding", "5px");

        LayoutBox box = new LayoutBox(new HtmlNode("div"),
                StyleResolver.finalizeComputedStyle(raw, null, null));

        LayoutContext ctx = newContext();
        box.measure(800, 600, blockRoot(), ctx);
        box.layout(0, 0, 800, 600, ctx);

        assertEquals(10, box.mT);
        assertEquals(10, box.mL);
        assertEquals(5, box.pT);
        assertEquals(5, box.pL);
        assertEquals(10, box.x);
        assertEquals(10, box.y);
    }

    private static LayoutBox resolvedBox(String tag, String... keyValues) {
        Map<String, String> raw = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            raw.put(keyValues[i], keyValues[i + 1]);
        }
        return new LayoutBox(new HtmlNode(tag), StyleResolver.finalizeComputedStyle(raw, null, null));
    }

    @Test
    public void testGapShorthandReachesBothAxes() {
        LayoutBox box = resolvedBox("div", "display", "flex", "gap", "4px 12px", "width", "200px");
        box.measure(800, 600, blockRoot(), newContext());

        assertEquals(4, box.rowGap);
        assertEquals(12, box.columnGap);
    }

    @Test
    public void testRowGapSeparatesFlexColumnChildren() {
        // gap: 4px 12px → 主軸(縦)は row-gap(4px) が効き、column-gap(12px) ではない
        LayoutBox parent = resolvedBox("div", "display", "flex", "flex-direction", "column",
                "gap", "4px 12px", "width", "200px");
        LayoutBox a = resolvedBox("div", "display", "block", "height", "20px");
        LayoutBox b = resolvedBox("div", "display", "block", "height", "20px");
        parent.children.add(a);
        parent.children.add(b);

        LayoutContext ctx = newContext();
        parent.measure(800, 600, blockRoot(), ctx);
        parent.layout(0, 0, 800, 600, ctx);

        assertEquals(0, a.y);
        assertEquals(24, b.y);                 // 20 + row-gap(4)
        assertEquals(44, parent.h);            // 20 + 4 + 20
    }

    @Test
    public void testColumnGapSeparatesFlexRowChildren() {
        LayoutBox parent = resolvedBox("div", "display", "flex", "flex-direction", "row",
                "gap", "10px", "width", "200px", "height", "50px");
        LayoutBox a = resolvedBox("div", "display", "block", "width", "30px", "height", "20px");
        LayoutBox b = resolvedBox("div", "display", "block", "width", "30px", "height", "20px");
        parent.children.add(a);
        parent.children.add(b);

        LayoutContext ctx = newContext();
        parent.measure(800, 600, blockRoot(), ctx);
        parent.layout(0, 0, 800, 600, ctx);

        assertEquals(0, a.x);
        assertEquals(40, b.x);                 // 30 + column-gap(10)
    }

    @Test
    public void testTwoValueGapUsesTheCorrectAxisInFlexRow() {
        // gap: 4px 12px → 主軸(横)は 12px、交差軸(行送り)は 4px
        LayoutBox parent = resolvedBox("div", "display", "flex", "flex-direction", "row",
                "gap", "4px 12px", "width", "200px", "height", "50px");
        LayoutBox a = resolvedBox("div", "display", "block", "width", "30px", "height", "20px");
        LayoutBox b = resolvedBox("div", "display", "block", "width", "30px", "height", "20px");
        parent.children.add(a);
        parent.children.add(b);

        LayoutContext ctx = newContext();
        parent.measure(800, 600, blockRoot(), ctx);
        parent.layout(0, 0, 800, 600, ctx);

        assertEquals(42, b.x);                 // 30 + column-gap(12)、row-gap(4) ではない
    }

    @Test
    public void testGridUsesColumnGapHorizontallyAndRowGapVertically() {
        LayoutBox grid = resolvedBox("div", "display", "grid", "grid-template-columns", "50px 50px",
                "gap", "4px 12px", "width", "200px");
        LayoutBox a = resolvedBox("div", "display", "block", "height", "20px");
        LayoutBox b = resolvedBox("div", "display", "block", "height", "20px");
        LayoutBox c = resolvedBox("div", "display", "block", "height", "20px");
        grid.children.add(a);
        grid.children.add(b);
        grid.children.add(c);

        LayoutContext ctx = newContext();
        grid.measure(800, 600, blockRoot(), ctx);
        grid.layout(0, 0, 800, 600, ctx);

        assertEquals(0, a.x);
        assertEquals(62, b.x);   // 50 + column-gap(12)
        assertEquals(0, c.x);    // 2 列なので 3 つ目は次の行の先頭
        assertEquals(24, c.y);   // 20 + row-gap(4)
    }

    @Test
    public void testRowGapSeparatesBlockChildren() {
        LayoutBox parent = resolvedBox("div", "display", "block", "gap", "6px", "width", "200px");
        LayoutBox a = resolvedBox("div", "display", "block", "height", "20px");
        LayoutBox b = resolvedBox("div", "display", "block", "height", "20px");
        parent.children.add(a);
        parent.children.add(b);

        LayoutContext ctx = newContext();
        parent.measure(800, 600, blockRoot(), ctx);
        parent.layout(0, 0, 800, 600, ctx);

        assertEquals(26, b.y);
    }

    @Test
    public void testBorderBoxIsTheDefault() {
        // box-sizing の既定は border-box。padding は width の内側に入る
        LayoutBox borderBox = box("div", style("display", "block", "width", "100px",
                "height", "40px", "padding-left", "10px", "padding-right", "10px"));
        LayoutBox contentBox = box("div", style("display", "block", "box-sizing", "content-box",
                "width", "100px", "height", "40px", "padding-left", "10px", "padding-right", "10px"));

        LayoutContext ctx = newContext();
        borderBox.measure(800, 600, blockRoot(), ctx);
        contentBox.measure(800, 600, blockRoot(), ctx);

        assertEquals(100, borderBox.w);
        assertEquals(120, contentBox.w);
    }

    @Test
    public void testBlockChildrenStackVertically() {
        LayoutBox parent = box("div", style("display", "block", "width", "200px"));
        LayoutBox a = box("div", style("display", "block", "height", "20px"));
        LayoutBox b = box("div", style("display", "block", "height", "30px", "margin-top", "5px"));
        parent.children.add(a);
        parent.children.add(b);

        LayoutContext ctx = newContext();
        parent.measure(800, 600, blockRoot(), ctx);
        parent.layout(0, 0, 800, 600, ctx);

        assertEquals(0, a.y);
        assertEquals(25, b.y);          // 20(a の高さ) + 5(margin-top)
        assertEquals(200, a.w);         // 親の内幅いっぱいに伸びる
        // 子の積み上げ高さ: 20 + margin-top(5) + 30。マージン相殺は未実装である現状を固定する
        assertEquals(55, parent.h);
    }

    @Test
    public void testAbsolutePositioningUsesContainingBlock() {
        LayoutBox parent = box("div", style("display", "block", "position", "relative",
                "width", "200px", "height", "100px"));
        LayoutBox child = box("div", style("display", "block", "position", "absolute",
                "left", "10px", "top", "20px", "width", "30px", "height", "40px"));
        parent.children.add(child);

        LayoutContext ctx = newContext();
        parent.measure(800, 600, blockRoot(), ctx);
        parent.layout(100, 100, 800, 600, ctx);

        assertEquals(110, child.x);
        assertEquals(120, child.y);
        assertEquals(30, child.w);
        assertEquals(40, child.h);
    }

    @Test
    public void testDisplayNoneCollapses() {
        LayoutBox parent = box("div", style("display", "block", "width", "200px"));
        LayoutBox hidden = box("div", style("display", "none", "height", "50px"));
        LayoutBox visible = box("div", style("display", "block", "height", "20px"));
        parent.children.add(hidden);
        parent.children.add(visible);

        LayoutContext ctx = newContext();
        parent.measure(800, 600, blockRoot(), ctx);
        parent.layout(0, 0, 800, 600, ctx);

        assertEquals(0, hidden.w);
        assertEquals(0, hidden.h);
        assertEquals(0, visible.y);
        assertEquals(20, parent.h);
    }

    @Test
    public void testTextWrapsWithinAvailableWidth() {
        HtmlNode text = HtmlNode.createTextNode("hello world hello world");
        LayoutBox box = new LayoutBox(text, style("font-size", "16px"));

        LayoutContext ctx = newContext();
        box.measure(60, 600, blockRoot(), ctx);

        assertTrue(box.textLines.size() > 1, "狭い幅では折り返される: " + box.textLines);
        assertTrue(box.w <= 60, "折り返し後の幅は制限内に収まる: " + box.w);
    }

    @Test
    public void testScrollStateIsReadFromContextNotDom() {
        LayoutBox parent = box("div", style("display", "block", "width", "200px", "height", "100px"));
        HtmlNode parentNode = parent.node;
        parentNode.setId("scroller");
        LayoutBox child = box("div", style("display", "block", "height", "20px"));
        parent.children.add(child);

        LayoutContext ctx = new LayoutContext(VIEWPORT, src -> new int[]{32, 32}, new ScrollState(), FontMetrics.FALLBACK);
        ctx.scrollState().setScroll("scroller", 0, 30);

        parent.measure(800, 600, blockRoot(), ctx);
        parent.layout(0, 0, 800, 600, ctx);

        assertEquals(-30, child.y); // スクロール量だけ内側がずれる
        // DOM への書き戻しが起きていないこと (B-3)
        assertTrue(parentNode.attrs.keySet().stream().noneMatch(k -> k.startsWith("data-scroll")));
    }
}
