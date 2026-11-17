package com.nishiyu.enderframelib.core.dom;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import org.junit.jupiter.api.Test;

/** {@link HtmlParser#xhtmlToHtml}: XHTML の書き方のうち、HTML として読むと意味が変わるものだけを書き換える。 */
class XhtmlToHtmlTest {

    @Test
    void nonVoidSelfClosingBecomesEmptyElement() {
        assertEquals("<div class=\"a\"></div><div></div><span x='/>'></span>",
                HtmlParser.xhtmlToHtml("<div class=\"a\"/><div /><span x='/>'/>").replace("<div ></div>", "<div></div>"));
    }

    @Test
    void voidSelfClosingIsKept() {
        assertEquals("<br/><img src=\"a\" /><meta charset=\"utf-8\"/>", HtmlParser.xhtmlToHtml("<br/><img src=\"a\" /><meta charset=\"utf-8\"/>"));
    }

    @Test
    void cdataInStyleIsUnwrapped() {
        assertEquals("<style type=\"text/css\">\n div { color: green }\n </style>",
                HtmlParser.xhtmlToHtml("<style type=\"text/css\"><![CDATA[\n div { color: green }\n ]]></style>"));
    }

    @Test
    void cdataInTextIsEscaped() {
        assertEquals("<p>a&lt;b&amp;c</p>", HtmlParser.xhtmlToHtml("<p><![CDATA[a<b&c]]></p>"));
    }

    @Test
    void commentsAndProseAreUntouched() {
        String s = "<?xml version=\"1.0\"?><!-- <div/> --><p>1 < 2 &amp; 3</p>";
        assertEquals(s, HtmlParser.xhtmlToHtml(s));
    }
}
