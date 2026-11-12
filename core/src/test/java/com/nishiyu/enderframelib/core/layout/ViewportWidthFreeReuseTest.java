package com.nishiyu.enderframelib.core.layout;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Q6: 制約が同じで、部分木が箱の外の寸法（ビューポート単位・絶対配置・画像の候補選び）を読まないなら、
 * ビューポートの「幅」だけが変わっても測定を使い回す。結果は毎回一から組んだ結果と一致し、測り直しは増えない（flex の項目の 2 段の測定のように制約が毎回変わる箱には効かない）。
 */
class ViewportWidthFreeReuseTest {

    private static final int H = 600;

    @AfterEach
    void restore() {
        LayoutBox.viewportWidthFreeReuse = true;
    }

    private static String dump(LayoutBox b) {
        StringBuilder sb = new StringBuilder();
        dump(b, sb, 0);
        return sb.toString();
    }

    private static void dump(LayoutBox b, StringBuilder sb, int d) {
        sb.append(d).append(' ').append(b.node.tag).append(' ').append(b.x).append(',').append(b.y).append(' ')
                .append(b.w).append('x').append(b.h).append('\n');
        for (LayoutBox c : b.children) dump(c, sb, d + 1);
    }

    /** 固定幅の行（幅に依らない）・vw の行・画像・絶対配置・fill の行が混在する文書。 */
    private static String html() {
        StringBuilder sb = new StringBuilder("<html><head><style>body{margin:0}.f{width:300px;padding:4px;border:1px solid #888}"
                + ".v{width:20vw}.a{position:relative;width:200px}</style></head><body>");
        for (int i = 0; i < 40; i++) {
            sb.append("<div class=\"f\">fixed row ").append(i).append(" some text <b>bold</b></div>");
            if (i % 5 == 0) sb.append("<div class=\"v\">viewport row ").append(i).append("</div>");
            if (i % 7 == 0) sb.append("<div class=\"a\">rel <span style=\"position:absolute;right:0;top:0\">abs</span></div>");
            if (i % 9 == 0) sb.append("<img src=\"a.png\" width=\"50\" height=\"20\" sizes=\"100vw\">");
            // flex の項目は幅が親から渡される（flex-basis）。中の行の制約は、ビューポートの幅が変わっても同じ
            sb.append("<div style=\"display:flex\"><div style=\"flex:0 0 260px\"><p>item para ").append(i)
                    .append(" wraps inside the item <i>italic</i></p><p>second para</p></div><div style=\"flex:1\">grow ").append(i).append("</div></div>");
            sb.append("<p>fill row ").append(i).append(" with enough words to wrap in the narrow viewports we test here</p>");
        }
        return sb.append("</body></html>").toString();
    }

    private static final int[] WIDTHS = {800, 799, 800, 640, 641, 900, 899, 500, 501, 800};

    private static List<String> chain(UiDocumentCompiler parser, com.nishiyu.enderframelib.common.Document doc) {
        InteractionState state = new InteractionState();
        LayoutBox previous = null;
        List<String> out = new ArrayList<>();
        for (int w : WIDTHS) {
            LayoutBox inc = previous == null
                    ? parser.layoutDocument(doc, w, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state)
                    : parser.layoutDocument(doc, w, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state, previous);
            out.add(dump(inc));
            previous = inc;
        }
        return out;
    }

    @Test
    void resultMatchesFreshLayoutAndNeverMeasuresMore() {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        var doc = parser.parseDocument(html(), "", 800, H);

        LayoutBox.viewportWidthFreeReuse = false;
        long c0 = LayoutBox.measurementsComputed();
        List<String> off = chain(parser, doc);
        long offCount = LayoutBox.measurementsComputed() - c0;

        LayoutBox.viewportWidthFreeReuse = true;
        long c1 = LayoutBox.measurementsComputed();
        List<String> on = chain(parser, doc);
        long onCount = LayoutBox.measurementsComputed() - c1;

        for (int i = 0; i < WIDTHS.length; i++) {
            assertEquals(off.get(i), on.get(i), "幅 " + WIDTHS[i] + " (手 " + i + ")");
            LayoutBox fresh = parser.layoutDocument(doc, WIDTHS[i], H, Map.of(), new ScrollState(), FontResolver.FALLBACK, new InteractionState());
            assertEquals(dump(fresh), on.get(i), "一から組んだ結果と一致 幅 " + WIDTHS[i]);
        }
        System.out.println("[Q6] measurements off=" + offCount + " on=" + onCount);
        assertTrue(onCount <= offCount, "測り直しは増えない: off=" + offCount + " on=" + onCount);
    }
}
