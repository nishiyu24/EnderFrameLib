package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** SVG の外来要素: 自己終了タグで閉じ、HTML の暗黙終了を受けない（HTML §13.2.6.5）。 */
class ForeignContentTest {

    private static List<HtmlNode> elements(HtmlNode parent) {
        return parent.children.stream().filter(c -> c.nodeType == NodeType.ELEMENT).toList();
    }

    @Test
    void selfClosingSvgChildrenAreSiblings() {
        HtmlNode root = HtmlParser.parse("<svg><rect/><circle /><path d='M0 0'/></svg>");
        HtmlNode svg = elements(root).getFirst();
        assertEquals("svg", svg.tag);
        assertEquals(List.of("rect", "circle", "path"), elements(svg).stream().map(e -> e.tag).toList());
        assertEquals(0, elements(elements(svg).getFirst()).size());
    }

    @Test
    void selfClosingSvgRootDoesNotSwallowFollowingSiblings() {
        HtmlNode root = HtmlParser.parse("<svg width='5'/><p>after</p>");
        assertEquals(List.of("svg", "p"), elements(root).stream().map(e -> e.tag).toList());
    }

    @Test
    void nestedGroupsKeepTheirStructure() {
        HtmlNode root = HtmlParser.parse("<svg><g><rect/><g><circle/></g></g><line/></svg>");
        HtmlNode svg = elements(root).getFirst();
        assertEquals(List.of("g", "line"), elements(svg).stream().map(e -> e.tag).toList());
        HtmlNode g = elements(svg).getFirst();
        assertEquals(List.of("rect", "g"), elements(g).stream().map(e -> e.tag).toList());
    }

    @Test
    void imageInsideSvgIsNotRenamedToImg() {
        HtmlNode svg = elements(HtmlParser.parse("<svg><image href='a.png'/></svg>")).getFirst();
        assertEquals("image", elements(svg).getFirst().tag);
    }

    @Test
    void htmlSelfClosingSyntaxStillIgnoredOutsideForeignContent() {
        HtmlNode root = HtmlParser.parse("<div/><span>x</span>");
        // HTML では <div/> は開始タグ。span は div の子になる
        HtmlNode div = elements(root).getFirst();
        assertEquals("div", div.tag);
        assertEquals(1, elements(div).size());
    }
}
