package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * R9 の小さな項目。期待値は Edge 156 ヘッドレスの実測。
 */
public class R9MiscLayoutTest {

    private static final int VIEWPORT_W = 400;
    private static final int VIEWPORT_H = 300;

    @Test
    public void testIntrinsicKeywordsAsHeightBehaveAsAuto() {
        // block-size の min-content / max-content / fit-content は auto と同じ（CSS Sizing 3 §5.1）
        for (String keyword : new String[]{"min-content", "max-content", "fit-content", "fit-content(100px)"}) {
            LayoutBox root = layout("<div id=a style='height:" + keyword + ";width:100px'><div style='height:30px'></div></div>");
            assertEquals(30, find(root, "a").h, 0.01f, keyword);
        }
    }

    @Test
    public void testColumnFlexAutoMinHeightUsesContentWhenSpecifiedHeightIsLarger() {
        // 指定 200px より内容 30px が低いので、自動の最小寸法は 30px（Flexbox §4.5）。加重縮小は 200:20 で配る
        LayoutBox root = layout("<div style='display:flex;flex-direction:column;height:100px;width:100px'>"
                + "<div id=a style='height:200px'><div style='height:30px'></div></div>"
                + "<div id=b style='height:20px'></div></div>");
        assertEquals(100f * 200 / 220, find(root, "a").h, 0.05f);
        assertEquals(100f * 20 / 220, find(root, "b").h, 0.05f);
    }

    @Test
    public void testColumnFlexAutoMinHeightStopsAtContent() {
        // 縮小の結果が内容の高さ 80px を下回るなら 80px で止まる
        LayoutBox root = layout("<div style='display:flex;flex-direction:column;height:100px;width:100px'>"
                + "<div id=a style='height:200px'><div style='height:80px'></div></div>"
                + "<div id=b style='height:20px'></div></div>");
        assertEquals(90.91f, find(root, "a").h, 0.05f);
        LayoutBox tight = layout("<div style='display:flex;flex-direction:column;height:60px;width:100px'>"
                + "<div id=a style='height:200px'><div style='height:80px'></div></div></div>");
        assertEquals(80, find(tight, "a").h, 0.01f);
    }

    @Test
    public void testCollapsedRowTakesNoSpace() {
        LayoutBox root = layout("<table id=t style='border-spacing:0;border-collapse:separate'>"
                + "<tr id=r1><td id=a style='padding:0;height:20px;width:30px'></td></tr>"
                + "<tr id=r2 style='visibility:collapse'><td id=b style='padding:0;height:40px;width:30px'></td></tr>"
                + "<tr id=r3><td id=c style='padding:0;height:20px;width:30px'></td></tr></table>");
        assertEquals(40, find(root, "t").h, 0.01f);
        assertEquals(20, find(root, "c").y - find(root, "a").y, 0.01f);
        assertEquals(0, find(root, "r2").h, 0.01f);
    }

    @Test
    public void testCollapsedColumnTakesNoWidth() {
        LayoutBox root = layout("<table id=t style='border-spacing:0'>"
                + "<col><col style='visibility:collapse'><col>"
                + "<tr><td id=a style='padding:0;height:10px;width:30px'></td>"
                + "<td id=b style='padding:0;height:10px;width:50px'></td>"
                + "<td id=c style='padding:0;height:10px;width:20px'></td></tr></table>");
        assertEquals(50, find(root, "t").w, 0.01f);
        assertEquals(30, find(root, "c").x - find(root, "a").x, 0.01f);
    }

    @Test
    public void testHiddenBorderWinsOverWiderNeighbor() {
        String cells = "<td id=a style='padding:0;width:30px;height:10px;border:4px solid'></td>"
                + "<td id=b style='padding:0;width:30px;height:10px;border:2px %s'></td>";
        LayoutBox plain = layout("<table style='border-collapse:collapse'><tr>" + cells.formatted("solid") + "</tr></table>");
        assertEquals(2, find(plain, "a").bR, 0.01f);
        LayoutBox hidden = layout("<table style='border-collapse:collapse'><tr>" + cells.formatted("hidden") + "</tr></table>");
        assertEquals(0, find(hidden, "a").bR, 0.01f);
        assertEquals(0, find(hidden, "b").bL, 0.01f);
    }

    private static LayoutBox layout(String html) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("<style>body{margin:0}</style>" + html, "", VIEWPORT_W, VIEWPORT_H);
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
