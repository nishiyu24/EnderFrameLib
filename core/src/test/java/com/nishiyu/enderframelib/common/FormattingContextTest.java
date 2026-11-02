package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.LayoutBox.Display;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import com.nishiyu.enderframelib.core.text.model.FontStyle;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.RenderPipeline;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

/**
 * 整形コンテキストの網羅（W4）の検証。
 *
 * 矩形に出る差は忠実度コーパスがブラウザと突き合わせている。ここでは、コーパスの約束
 * （ua.css を上書きする・字幅に依存させない）の外にある振る舞いを固定する:
 * ua.css 経由の HTML の表、float の横を流れる行、セルの縦揃え、インラインの中の絶対配置。
 * W11 の分: 確定高さの伝搬の装飾の扱い、文字を含む強制改行の shrink-to-fit、column wrap と order。
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

    // ==========================================
    // W11: 確定高さの伝搬・強制改行の shrink-to-fit・column wrap
    // ==========================================

    @Test
    public void testPercentHeightInsideStretchedBorderBoxItemUsesItsContentBox() {
        LayoutBox root = layout("""
                <div style="display: flex; height: 100px;">
                  <div id="item" style="box-sizing: border-box; padding: 10px 0; border-top: 5px solid;"><div id="c" style="height: 50%;"></div></div>
                </div>
                """);
        assertEquals(100, find(root, "item").h, 0.01f);
        // 基準は stretch した高さから上下の装飾を引いた内容ボックス（100 - 10 - 10 - 5）
        assertEquals(37.5f, find(root, "c").h, 0.01f);
    }

    @Test
    public void testPercentHeightUnderIndefiniteBlockStaysUnresolved() {
        // 高さが auto のブロックの中の % は基準を持たない（以前と同じ。stretch や inset だけが基準を足す）
        LayoutBox root = layout("""
                <div style="height: 100px;"><div><div id="c" style="height: 50%;"></div></div></div>
                """);
        assertEquals(0, find(root, "c").h, 0.01f);
    }

    @Test
    public void testShrinkToFitWidthBreaksAtLineBreaksInText() {
        LayoutBox root = layout("""
                <div style="font-size: 16px;">
                  <span id="br" style="display: inline-block;">aaaaaaaa<br>bb</span>
                  <pre id="pre" style="display: inline-block; margin: 0;">bb
                aaaaaa</pre>
                </div>
                """);
        FontStyle font = new FontStyle(16f, false, false, 0f);
        // 以前は改行を区切らず、全行を横に繋げた幅になっていた
        assertEquals(FontFace.FALLBACK.advance("aaaaaaaa", font), find(root, "br").w, 0.01f);
        assertEquals(FontFace.FALLBACK.advance("aaaaaa", font), find(root, "pre").w, 0.01f);
    }

    @Test
    public void testColumnWrapBreaksInOrderAndSumsColumnWidths() {
        LayoutBox root = layout("""
                <div id="c" style="display: inline-flex; flex-direction: column; flex-wrap: wrap; height: 30px;">
                  <div id="a" style="width: 10px; height: 20px; order: 2;"></div>
                  <div id="b" style="width: 15px; height: 20px; order: 1;"></div>
                </div>
                """);
        LayoutBox c = find(root, "c"), a = find(root, "a"), b = find(root, "b");
        assertEquals(25, c.w, 0.01f);
        // order の順に列を作る: b が 1 列目、a が 2 列目
        assertEquals(c.x, b.x, 0.01f);
        assertEquals(c.x + 15, a.x, 0.01f);
        assertEquals(c.y, a.y, 0.01f);
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