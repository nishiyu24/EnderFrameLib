package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.LayoutBox.Display;
import com.nishiyu.enderframelib.core.layout.LayoutContext.ScrollState;
import com.nishiyu.enderframelib.core.text.FontFace.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 整形コンテキストの網羅（W4）の検証。
 *
 * 矩形に出る差は忠実度コーパスがブラウザと突き合わせている。ここでは、コーパスの約束
 * （ua.css を上書きする・字幅に依存させない）の外にある振る舞いを固定する:
 * ua.css 経由の HTML の表、float の横を流れる行、セルの縦揃え、インラインの中の絶対配置。
 */
public class FormattingContextTest {

    private static final int VIEWPORT_W = 320;
    private static final int VIEWPORT_H = 200;

    @Test
    public void testHtmlTableUsesTableLayoutWithColspan() {
        LayoutBox root = layout("""
                <table id="t" style="width: auto; border: 0; margin: 0;">
                  <tr><td id="a" style="padding: 0; width: 30px; height: 10px;"></td><td id="b" style="padding: 0; width: 20px; height: 10px;"></td></tr>
                  <tr><td id="c" colspan="2" style="padding: 0; height: 10px;"></td></tr>
                </table>
                """);
        LayoutBox table = find(root, "t");
        // 以前は ua.css が flex の行として組んでいた
        assertEquals(Display.Inner.TABLE, table.display.inner());
        LayoutBox a = find(root, "a"), b = find(root, "b"), c = find(root, "c");
        // ua.css の border-spacing: 2px（HTML 標準の既定値）がセルの間に入る
        assertEquals(a.x + 30 + 2, b.x, 0.01f);
        assertEquals(30 + 2 + 20, c.w, 0.01f);
        assertEquals(a.x, c.x, 0.01f);
        assertEquals(a.y + 10 + 2, c.y, 0.01f);
    }

    @Test
    public void testCellVerticalAlignMiddleShiftsContent() {
        LayoutBox root = layout("""
                <div style="display: table;"><div style="display: table-row;">
                  <div style="display: table-cell; width: 10px; height: 40px;"></div>
                  <div id="m" style="display: table-cell; width: 10px; vertical-align: middle;"><div id="inner" style="height: 10px;"></div></div>
                </div></div>
                """);
        LayoutBox m = find(root, "m");
        assertEquals(40, m.h, 0.01f);
        assertEquals(m.y + 15, find(root, "inner").y, 0.01f);
    }

    @Test
    public void testLinesFlowBesideFloat() {
        LayoutBox root = layout("""
                <div style="width: 100px; font-size: 0; line-height: 0;">
                  <div style="float: left; width: 40px; height: 30px;"></div>
                  <span id="a" style="display: inline-block; width: 50px; height: 10px;"></span>
                  <span id="b" style="display: inline-block; width: 50px; height: 10px;"></span>
                </div>
                """);
        LayoutBox a = find(root, "a"), b = find(root, "b");
        // 行は float の右の 60px に入り、2 つ目は次の行（まだ float の横）へ折り返す
        assertEquals(40, a.x, 0.01f);
        assertEquals(40, b.x, 0.01f);
        assertEquals(a.y + 10, b.y, 0.01f);
    }

    @Test
    public void testAbsoluteInsideInlineBoxIsPositioned() {
        LayoutBox root = layout("""
                <div style="position: relative; width: 100px; height: 50px;">
                  <span>x<span id="abs" style="position: absolute; left: 7px; top: 5px; width: 3px; height: 3px;"></span></span>
                </div>
                """);
        LayoutBox abs = find(root, "abs");
        // 以前は絶対配置の span がインラインの祖先として平坦化され、位置が決まらなかった
        assertEquals(7, abs.x, 0.01f);
        assertEquals(5, abs.y, 0.01f);
        assertEquals(3, abs.w, 0.01f);
    }

    private static LayoutBox layout(String html) {
        UIParser parser = new UIParser();
        UIParser.Document doc = parser.parseDocument("<style>body { margin: 0; }</style>" + html, "", VIEWPORT_W, VIEWPORT_H);
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
