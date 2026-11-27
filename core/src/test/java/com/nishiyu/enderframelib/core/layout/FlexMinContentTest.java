package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.paint.tree.PaintBox;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import com.nishiyu.enderframelib.core.text.model.FontStyle;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * flex アイテムになった横並びの flex コンテナの min-content（CSS Flexbox §9.9.1）と、
 * それに寄与する子の幅（CSS Sizing 3 §5.1）。
 *
 * 何が壊れていたか: 横並びの flex コンテナの min-content を子の最大値で求めていたため、
 * 検索欄（select + input + button）が自動の最小寸法を下回って潰れ、button が隣の要素に重なっていた。
 * button の min-content は内容によらず 90px、{@code min-width: 0} の箱は padding と border まで 0 になっていた。
 */
public class FlexMinContentTest {

    private static final String SEARCH_BAR = """
            <div id="bar" style="display:flex; width:300px; gap:14px;">
              <div id="wrap" style="display:flex; flex:1; gap:6px;">
                <select id="sel" style="width:130px; padding:5px 10px; border:1px solid #000;"><option>All</option></select>
                <input id="in" type="text" style="flex:1; min-width:0; padding:5px 10px; border:1px solid #000;" />
                <button id="btn" style="padding:5px 12px; border:1px solid #000;">Search</button>
              </div>
              <div id="side" style="width:200px; flex-shrink:0;">side</div>
            </div>
            """;

    private static PaintBox layout(String body) {
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        String html = "<style>* { box-sizing: border-box; margin: 0; }</style>" + body;
        Document doc = compiler.parseDocument(html, "", 854, 480);
        return compiler.layoutDocument(doc, 854, 480, Map.of(), new ScrollState(), FontResolver.FALLBACK, new InteractionState());
    }

    private static PaintBox byId(PaintBox b, String id) {
        if (b.node != null && id.equals(b.node.attrs.get("id"))) return b;
        for (PaintBox c : b.paintChildren()) {
            PaintBox found = byId(c, id);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * 列方向の flex で、容器の内容幅が項目の padding と border より狭いとき、stretch した項目の枠線ボックスは装飾の幅で止まる（内容の幅は負にならない）。
     * 何が壊れていたか: 伸ばす幅をそのまま枠線ボックスにして 0 になり、測り直す箱の組み合わせによって 0 / 10 が入れ替わった（§6-113。差分ファズ nested の種 195）。
     */
    @Test
    void stretchedColumnItemIsNotNarrowerThanItsPaddingAndBorder() {
        for (String sizing : new String[] {"content-box", "border-box"}) {
            PaintBox root = layout("<div style=\"display:flex;flex-direction:column;width:4px\">"
                    + "<div id=\"i\" style=\"box-sizing:" + sizing + ";padding:0 4px;border:1px solid #000;flex:1 1 0;min-width:0\"></div>"
                    + "<div id=\"j\" style=\"box-sizing:" + sizing + ";padding:0 4px;border:1px solid #000\">x</div></div>");
            assertEquals(10f, byId(root, "i").w(), 0.001f, sizing + ": 空の項目");
            assertEquals(10f, byId(root, "j").w(), 0.001f, sizing + ": 文字の項目");
        }
    }

    @Test
    void rowFlexItemDoesNotShrinkBelowTheSumOfItsItems() {
        PaintBox root = layout(SEARCH_BAR);
        PaintBox wrap = byId(root, "wrap");
        PaintBox sel = byId(root, "sel");
        PaintBox in = byId(root, "in");
        PaintBox btn = byId(root, "btn");

        // 自動の最小寸法 = select(130) + gap + input の装飾(22) + gap + button の内容幅
        float expected = 130 + 6 + 22 + 6 + btn.w();
        assertEquals(expected, wrap.w(), 0.5f, "検索欄は子の min-content の和より細くならない");
        assertEquals(130, sel.w(), 0.5f, "指定幅の select は縮まない");
        assertEquals(22, in.w(), 0.5f, "min-width: 0 の input も padding と border の分は残る");
        assertTrue(btn.x() + btn.w() <= wrap.x() + wrap.w() + 0.5f, "button が検索欄の外にはみ出さない");
    }

    @Test
    void buttonMinContentIsItsLabel() {
        PaintBox root = layout("""
                <div style="display:flex; width:10px;">
                  <button id="btn" style="padding:0 4px; border:0;">Go</button>
                </div>
                """);
        PaintBox btn = byId(root, "btn");
        assertTrue(btn.w() < 40, "button の最小幅はラベル（Go）と padding で、固定の 90px ではない: " + btn.w());
    }

    @Test
    void nowrapTextMinContentIsTheWholeLine() {
        PaintBox root = layout("""
                <div style="display:flex; width:10px;">
                  <span id="pill" style="white-space:nowrap;">Cart (3) $419.97</span>
                  <span id="wrapping">Cart (3) $419.97</span>
                </div>
                """);
        PaintBox pill = byId(root, "pill");
        PaintBox wrapping = byId(root, "wrapping");
        assertTrue(pill.w() > wrapping.w() * 2, "nowrap のテキストは 1 行分の幅より縮まない: " + pill.w() + " vs " + wrapping.w());
    }

    @Test
    void selectFitsItsLongestOption() {
        PaintBox root = layout("""
                <select id="sel" style="padding:0; border:0;">
                  <option>A</option>
                  <option>Computers &amp; Laptops</option>
                </select>
                """);
        PaintBox sel = byId(root, "sel");
        float longest = FontFace.FALLBACK.advance("Computers & Laptops ▾",
                new FontStyle(sel.style().fontSizePx(), false, false, 0));
        assertEquals(longest, sel.w(), 0.5f, "閉じた select の幅は最も長い選択肢のラベル");
    }
}
