package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表の列幅の {@code %}（R9）。セルと列要素の {@code width: N%} が、表の幅に対する割合の列になること。
 * 枠線と border-spacing は 0 にして、割合の計算だけを見る。
 */
public class TablePercentColumnTest {

    private static final int VIEWPORT_W = 400;
    private static final int VIEWPORT_H = 200;

    private static final String TABLE = "display: table; border-spacing: 0; ";
    private static final String CELL = "display: table-cell; padding: 0; ";

    @Test
    public void testPercentColumnsTakeShareOfDefiniteTableWidth() {
        LayoutBox root = layout("""
                <div id="t" style="%swidth: 300px;"><div style="display: table-row;">
                  <div id="a" style="%swidth: 25%%; height: 10px;"></div>
                  <div id="b" style="%swidth: 75%%; height: 10px;"></div>
                </div></div>
                """.formatted(TABLE, CELL, CELL));
        assertEquals(75, find(root, "a").w, 0.01f);
        assertEquals(225, find(root, "b").w, 0.01f);
        assertEquals(75, find(root, "b").x - find(root, "a").x, 0.01f);
    }

    @Test
    public void testAutoColumnTakesTheRemainder() {
        LayoutBox root = layout("""
                <div id="t" style="%swidth: 300px;"><div style="display: table-row;">
                  <div id="a" style="%swidth: 40%%; height: 10px;"></div>
                  <div id="b" style="%sheight: 10px;"></div>
                </div></div>
                """.formatted(TABLE, CELL, CELL));
        assertEquals(120, find(root, "a").w, 0.01f);
        assertEquals(180, find(root, "b").w, 0.01f);
    }

    @Test
    public void testPercentColumnNeverShrinksBelowItsMinContent() {
        LayoutBox root = layout("""
                <div id="t" style="%swidth: 200px;"><div style="display: table-row;">
                  <div id="a" style="%swidth: 10%%; height: 10px;"><div style="width: 60px; height: 5px;"></div></div>
                  <div id="b" style="%sheight: 10px;"></div>
                </div></div>
                """.formatted(TABLE, CELL, CELL));
        // 10% は 20px だが、中身の 60px が最小幅
        assertEquals(60, find(root, "a").w, 0.01f);
        assertEquals(140, find(root, "b").w, 0.01f);
    }

    @Test
    public void testColumnElementPercent() {
        LayoutBox root = layout("""
                <table id="t" style="width: 200px; border: 0; border-spacing: 0;">
                  <col style="width: 30%"><col>
                  <tr><td id="a" style="padding: 0; height: 10px;"></td><td id="b" style="padding: 0; height: 10px;"></td></tr>
                </table>
                """);
        assertEquals(60, find(root, "a").w, 0.01f);
        assertEquals(140, find(root, "b").w, 0.01f);
    }

    @Test
    public void testPercentSumOverHundredIsScaledDown() {
        LayoutBox root = layout("""
                <div id="t" style="%swidth: 300px;"><div style="display: table-row;">
                  <div id="a" style="%swidth: 60%%; height: 10px;"></div>
                  <div id="b" style="%swidth: 60%%; height: 10px;"></div>
                </div></div>
                """.formatted(TABLE, CELL, CELL));
        assertEquals(150, find(root, "a").w, 0.01f);
        assertEquals(150, find(root, "b").w, 0.01f);
    }

    @Test
    public void testAutoWidthTableGrowsToFitPercentColumnContent() {
        // 割合の列 (50%) の max-content が 100px なので、表は 200px 以上に広がる
        LayoutBox root = layout("""
                <div id="t" style="%s"><div style="display: table-row;">
                  <div id="a" style="%swidth: 50%%; height: 10px;"><div style="width: 100px; height: 5px;"></div></div>
                  <div id="b" style="%sheight: 10px;"><div style="width: 20px; height: 5px;"></div></div>
                </div></div>
                """.formatted(TABLE, CELL, CELL));
        assertTrue(find(root, "t").w >= 200 - 0.01f, "w=" + find(root, "t").w);
        assertEquals(find(root, "t").w / 2, find(root, "a").w, 0.01f);
    }

    @Test
    public void testFixedLayoutResolvesPercentAgainstTableWidth() {
        LayoutBox root = layout("""
                <div id="t" style="%swidth: 200px; table-layout: fixed;"><div style="display: table-row;">
                  <div id="a" style="%swidth: 25%%; height: 10px;"></div>
                  <div id="b" style="%sheight: 10px;"></div>
                </div></div>
                """.formatted(TABLE, CELL, CELL));
        assertEquals(50, find(root, "a").w, 0.01f);
        assertEquals(150, find(root, "b").w, 0.01f);
    }

    @Test
    public void testCollapsedTableClientBoxKeepsFullSize() {
        LayoutBox root = layout("""
                <div id="t" style="display: table; border-collapse: collapse;"><div style="display: table-row;">
                  <div id="a" style="display: table-cell; width: 40px; height: 20px; border: 4px solid #000;"></div>
                </div></div>
                """);
        LayoutBox t = find(root, "t");
        assertTrue(t.isCollapsedTable());
        assertTrue(t.scrollWidth <= t.w, "scrollWidth=" + t.scrollWidth + " w=" + t.w);
    }

    private static LayoutBox layout(String html) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("<style>body { margin: 0; }</style>" + html, "", VIEWPORT_W, VIEWPORT_H);
        return parser.layoutDocument(doc, VIEWPORT_W, VIEWPORT_H, Map.of(), new ScrollState(), FontResolver.FALLBACK);
    }

    private static LayoutBox find(LayoutBox box, String id) {
        if (id.equals(box.node.attrs.get("id"))) return box;
        for (LayoutBox child : box.elementChildren) {
            LayoutBox hit = find(child, id);
            if (hit != null) return hit;
        }
        return null;
    }
}
