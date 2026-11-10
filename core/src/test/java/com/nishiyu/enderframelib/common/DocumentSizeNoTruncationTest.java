package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** W8: 128 KiB 超の HTML・64 KiB 超の外部 CSS が黙って切り捨てられず、全行が DOM に残る。 */
public class DocumentSizeNoTruncationTest {

    private static int count(HtmlNode n, String tag) {
        int c = tag.equals(n.tag) ? 1 : 0;
        for (HtmlNode ch : n.children) c += count(ch, tag);
        return c;
    }

    private static String rows(int n) {
        StringBuilder sb = new StringBuilder("<html><body><ul>");
        for (int i = 0; i < n; i++) sb.append("<li class=\"row\" id=\"r").append(i).append("\">item ").append(i).append("</li>");
        return sb.append("</ul></body></html>").toString();
    }

    @Test
    void largeHtmlOver128KiBKeepsEveryRow() {
        String html = rows(5_000);
        assertTrue(html.length() > 131072, "前提: 128 KiB を超える入力");
        Document doc = DocumentLoader.parseDocument(html, null, 800, 600);
        assertEquals(5_000, count(doc.root, "li"));
        assertTrue(doc.hasElement("r4999"), "末尾の行が欠落していない");
    }

    @Test
    void twentyThousandRowsOpenWithoutLoss() {
        String html = rows(20_000);
        Document doc = DocumentLoader.parseDocument(html, null, 800, 600);
        assertEquals(20_000, count(doc.root, "li"));
        assertTrue(doc.hasElement("r19999"));
    }

    @Test
    void externalCssOver64KiBIsNotCut() {
        StringBuilder css = new StringBuilder();
        for (int i = 0; i < 4_000; i++) css.append(".c").append(i).append("{color:red}\n");
        css.append("#tail-marker{color:blue}\n");
        assertTrue(css.length() > 65536);
        Document doc = DocumentLoader.parseDocument("<div id=\"tail-marker\">x</div>", css.toString(), 800, 600);
        assertTrue(doc.authorCss().contains("#tail-marker{color:blue}"), "末尾の規則が欠落していない");
    }
}
