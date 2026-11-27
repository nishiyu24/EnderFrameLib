package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.core.paint.tree.PaintBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** S2: ネイティブ部品・埋め込みの固有寸法（{@code ElementSizing}）を、実際のレイアウト経由で固定する。 */
class ElementSizingTest {

    private static PaintBox layout(String html) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument(
                "<html><head><style>body { margin: 0; } * { padding: 0; border: 0; }</style></head><body>" + html + "</body></html>",
                "", 400, 300);
        return parser.layoutDocument(doc, 400, 300, Map.of(), new ScrollState(), FontResolver.FALLBACK);
    }

    private static PaintBox find(PaintBox box, String id) {
        if (id.equals(box.node.attrs.get("id"))) return box;
        for (PaintBox c : TestBoxes.elements(box)) {
            PaintBox hit = find(c, id);
            if (hit != null) return hit;
        }
        return null;
    }

    private static PaintBox box(String html, String id) {
        PaintBox b = find(layout(html), id);
        assertNotNull(b, id);
        return b;
    }

    @Test
    void progressSizeComesFromUserAgentStyle() {
        PaintBox p = box("<progress id=p></progress>", "p");
        assertEquals(16f, p.h(), 0.01f, "UA スタイルの高さ（寸法の既定 12 + 装飾より優先）");
        assertEquals(160f, p.w(), 0.01f, "UA スタイルの幅");
    }

    @Test
    void inputWidthFollowsSizeAttributeAndCheckable() {
        assertEquals(90f, box("<input id=i>", "i").w(), 0.01f);
        assertEquals(8f * 20, box("<input id=i size=20>", "i").w(), 0.01f);
        assertEquals(90f, box("<input id=i size=oops>", "i").w(), 0.01f, "数値でない size は既定のまま");
        float check = box("<input id=i type=checkbox>", "i").w();
        assertTrue(check > 0 && check < 40, "チェック類は小さな固定寸法: " + check);
        assertEquals(check, box("<input id=i type=radio>", "i").w(), 0.01f);
    }

    @Test
    void inputAndSelectHeightsDifferFromCheckable() {
        float text = box("<input id=i>", "i").h();
        float select = box("<select id=s><option>a</option></select>", "s").h();
        float check = box("<input id=i type=checkbox>", "i").h();
        assertTrue(text > 0 && select > 0 && check > 0);
        assertTrue(check <= text, "チェック類の高さは文字入力以下");
    }

    @Test
    void selectWidthIsLongestOption() {
        float shortW = box("<select id=s><option>a</option></select>", "s").w();
        float longW = box("<select id=s><option>a</option><optgroup><option>a much longer label</option></optgroup></select>", "s").w();
        assertTrue(longW > shortW, shortW + " < " + longW);
    }

    @Test
    void canvasUsesAttributesAndDefaults() {
        PaintBox def = box("<canvas id=c></canvas>", "c");
        assertEquals(300f, def.w(), 0.01f);
        assertEquals(150f, def.h(), 0.01f);
        PaintBox sized = box("<canvas id=c width=120 height=40></canvas>", "c");
        assertEquals(120f, sized.w(), 0.01f);
        assertEquals(40f, sized.h(), 0.01f);
        PaintBox bad = box("<canvas id=c width=99999 height=x></canvas>", "c");
        assertEquals(300f, bad.w(), 0.01f, "範囲外・不正は既定値");
        assertEquals(150f, bad.h(), 0.01f);
    }

    @Test
    void inlineSvgUsesWidthHeightOrDefaults() {
        PaintBox s = box("<svg id=s width=80 height=60></svg>", "s");
        assertEquals(80f, s.w(), 0.01f);
        assertEquals(60f, s.h(), 0.01f);
        PaintBox d = box("<svg id=s></svg>", "s");
        assertEquals(300f, d.w(), 0.01f);
        assertEquals(150f, d.h(), 0.01f);
    }
}
