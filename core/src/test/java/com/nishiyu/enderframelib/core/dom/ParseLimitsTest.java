package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import com.nishiyu.enderframelib.core.dom.parser.ParseLimits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** パーサーの資源上限: 既定で実用サイズを通し、超過は strict で例外、lenient で切り捨て。 */
public class ParseLimitsTest {

    private static int count(HtmlNode n, String tag) {
        int c = tag.equals(n.tag) ? 1 : 0;
        for (HtmlNode ch : n.children) c += count(ch, tag);
        return c;
    }

    @Test
    void defaultLimitsKeepTenThousandRows() {
        StringBuilder sb = new StringBuilder("<ul>");
        for (int i = 0; i < 10_000; i++) sb.append("<li>row</li>");
        HtmlNode root = HtmlParser.parse(sb.toString());
        assertEquals(10_000, count(root, "li"));
    }

    @Test
    void defaultDepthKeeps150Levels() {
        String html = "<div>".repeat(150) + "<span>leaf</span>" + "</div>".repeat(150);
        assertEquals(1, count(HtmlParser.parse(html), "span"));
    }

    @Test
    void lenientTruncates() {
        ParseLimits small = new ParseLimits(10, 256, false);
        HtmlNode root = HtmlParser.parse("<p>a</p>".repeat(100), small);
        assertTrue(count(root, "p") < 100);
    }

    @Test
    void strictTokenLimitThrows() {
        ParseLimits small = new ParseLimits(10, 256, true);
        assertThrows(ParseLimits.HtmlLimitExceededException.class,
                () -> HtmlParser.parse("<p>a</p>".repeat(100), small));
    }

    @Test
    void strictDepthLimitThrows() {
        ParseLimits shallow = new ParseLimits(1000, 5, true);
        assertThrows(ParseLimits.HtmlLimitExceededException.class,
                () -> HtmlParser.parse("<div>".repeat(20), shallow));
    }
}
