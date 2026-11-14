package com.nishiyu.enderframelib.core.raster.svg;

import com.nishiyu.enderframelib.core.css.CssParser;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** SVG の要素へスタイルシートの規則を当てる（提示属性 &lt; スタイルシート &lt; style 属性）。 */
class SvgStyleCascadeTest {

    private static Pixmap draw(String svg) {
        SvgImage img = SvgImage.parse(svg);
        assertNotNull(img);
        return img.rasterize(20, 20, 0xFF000000, null);
    }

    private static int center(Pixmap p) {
        return p.getStraight(10, 10);
    }

    private static final String RECT = "<rect id='r' class='c' width='20' height='20'";

    @Test
    void classRuleInsideSvgStyleElement() {
        assertEquals(0xFFFF0000, center(draw("<svg width='20' height='20'><style>.c{fill:#f00}</style>" + RECT + "/></svg>")));
    }

    @Test
    void typeSelectorAndInheritanceThroughGroup() {
        // g の fill は子へ継承される
        assertEquals(0xFF00FF00, center(draw("<svg width='20' height='20'><style>g{fill:#0f0}</style><g>" + RECT + "/></g></svg>")));
    }

    @Test
    void stylesheetBeatsPresentationAttributeButNotStyleAttribute() {
        String css = "<style>.c{fill:#0000ff}</style>";
        assertEquals(0xFF0000FF, center(draw("<svg width='20' height='20'>" + css + RECT + " fill='#ff0000'/></svg>")));
        assertEquals(0xFFFFFF00, center(draw("<svg width='20' height='20'>" + css + RECT + " fill='#ff0000' style='fill:#ffff00'/></svg>")));
    }

    @Test
    void specificityAndOrderDecideBetweenRules() {
        // id は class より強い。同じ詳細度なら後の規則
        assertEquals(0xFF00FF00, center(draw("<svg width='20' height='20'><style>#r{fill:#0f0}.c{fill:#f00}</style>" + RECT + "/></svg>")));
        assertEquals(0xFF0000FF, center(draw("<svg width='20' height='20'><style>.c{fill:#f00}.c{fill:#00f}</style>" + RECT + "/></svg>")));
    }

    @Test
    void importantRuleBeatsPresentationAttribute() {
        assertEquals(0xFF00FF00, center(draw("<svg width='20' height='20'><style>rect{fill:#0f0!important}</style>" + RECT + " fill='#f00'/></svg>")));
    }

    @Test
    void descendantSelectorAndCurrentColor() {
        Pixmap p = draw("<svg width='20' height='20'><style>g.on rect{fill:currentColor;color:#f0f}</style><g class='on'>" + RECT + "/></g></svg>");
        assertEquals(0xFFFF00FF, center(p));
        Pixmap off = draw("<svg width='20' height='20'><style>g.on rect{fill:#f0f}</style><g class='off'>" + RECT + "/></g></svg>");
        assertEquals(0xFF000000, center(off), "一致しない規則は当たらない（既定は黒）");
    }

    @Test
    void strokeAndOpacityPropertiesFromRules() {
        Pixmap p = draw("<svg width='20' height='20'><style>.c{fill:none;stroke:#f00;stroke-width:4}</style>" + RECT + "/></svg>");
        assertEquals(0xFFFF0000, p.getStraight(1, 10));
        assertEquals(0, center(p), "fill: none");
    }

    @Test
    void displayNoneFromRuleHidesElement() {
        assertEquals(0, center(draw("<svg width='20' height='20'><style>.c{display:none}</style>" + RECT + "/></svg>")));
    }

    @Test
    void stateAndPseudoRulesAreIgnored() {
        Pixmap p = draw("<svg width='20' height='20'><style>.c:hover{fill:#f00}.c::before{fill:#f00}</style>" + RECT + " fill='#0f0'/></svg>");
        assertEquals(0xFF00FF00, center(p));
    }

    @Test
    void externalSheetAppliesToInlineTree() {
        HtmlNode tree = HtmlParser.parse("<svg width='20' height='20'>" + RECT + "/></svg>");
        HtmlNode svg = find(tree, "svg");
        StyleSheet sheet = CssParser.parse("svg .c{fill:#0ff}", 800);
        assertEquals(0xFF00FFFF, SvgRenderer.render(svg, 20, 20, 0xFF000000, null, sheet).getStraight(10, 10));
        assertEquals(0xFF000000, SvgRenderer.render(svg, 20, 20, 0xFF000000, null, null).getStraight(10, 10));
    }

    @Test
    void signatureChangesWhenTheSheetChangesTheResult() {
        HtmlNode svg = find(HtmlParser.parse("<div class='dark'><svg width='20' height='20'>" + RECT + "/></svg></div>"), "svg");
        long none = SvgRenderer.signature(svg, null);
        long a = SvgRenderer.signature(svg, CssParser.parse(".c{fill:#f00}", 800));
        long b = SvgRenderer.signature(svg, CssParser.parse(".c{fill:#00f}", 800));
        assertNotEquals(none, a);
        assertNotEquals(a, b);
        assertEquals(a, SvgRenderer.signature(svg, CssParser.parse(".c{fill:#f00}", 800)), "同じ規則なら同じ署名");
        // 規則が当たらない要素なら署名は変わらない（無関係な規則でキャッシュを無効にしない）
        assertEquals(none, SvgRenderer.signature(svg, CssParser.parse(".other{fill:#00f}", 800)));
    }

    private static HtmlNode find(HtmlNode n, String tag) {
        if (tag.equals(n.tag)) return n;
        for (HtmlNode c : n.children) {
            HtmlNode h = find(c, tag);
            if (h != null) return h;
        }
        return null;
    }
}
