package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code direction: rtl} のブロックの配置と、行の高さより低い行の文字が作るスクロール範囲。
 *
 * 何が壊れていたか: {@code direction} を読まなかったので、{@code dir="rtl"} のブロックの子は幅が余っても左に寄り
 * （ブラウザは右に寄る）、{@code text-align: start / end} も左右が入れ替わらなかった。
 */
public class DirectionLayoutTest {

    private static final int VIEWPORT = 400;

    private static LayoutBox layout(String html, FontResolver fonts) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument(html, "", VIEWPORT, VIEWPORT);
        return parser.layoutDocument(doc, VIEWPORT, VIEWPORT, Map.of(), new ScrollState(), fonts);
    }

    private static LayoutBox find(LayoutBox box, String id) {
        if (box.node != null && id.equals(box.node.getId())) return box;
        for (LayoutBox child : box.elementChildren) {
            LayoutBox hit = find(child, id);
            if (hit != null) return hit;
        }
        return null;
    }

    private static float firstTextX(LayoutBox box) {
        for (LayoutBox.InlineFragment f : box.inlineFragments) if (f.isText() && !f.text.isBlank()) return f.x;
        for (LayoutBox c : box.children) {
            float x = firstTextX(c);
            if (!Float.isNaN(x)) return x;
        }
        return Float.NaN;
    }

    private static final String RTL_BLOCKS =
            "<div id=\"p\" style=\"width:300px\" dir=\"rtl\"><div id=\"a\" style=\"width:100px;height:10px\"></div>"
                    + "<div id=\"b\" style=\"width:100px;height:10px;margin-right:20px\"></div>"
                    + "<div id=\"c\" style=\"width:100px;height:10px;margin-right:auto\"></div>"
                    + "<div id=\"d\" style=\"height:10px\"></div>"
                    + "<div id=\"e\" style=\"width:100px;height:10px;margin:0 auto\"></div></div>"
                    + "<div id=\"q\" style=\"width:300px\"><div id=\"f\" style=\"width:100px;height:10px\"></div></div>";

    @Test
    void blockChildrenInRtlContainerSitAtTheRightEdge() {
        LayoutBox root = layout(RTL_BLOCKS, FontResolver.FALLBACK);
        float parentLeft = find(root, "p").x;
        // 幅 100 の子は、幅 300 の rtl の箱の右端（左に 200 のあき）
        assertEquals(parentLeft + 200, find(root, "a").x, 0.01f);
        // 右マージン 20 を残して右に寄る
        assertEquals(parentLeft + 180, find(root, "b").x, 0.01f);
        // 右マージンが auto なら余りは右が受ける（ltr と同じ左寄せ）
        assertEquals(parentLeft, find(root, "c").x, 0.01f);
        // 幅が auto なら幅いっぱいで寄せる余地がない
        assertEquals(parentLeft, find(root, "d").x, 0.01f);
        assertEquals(300f, find(root, "d").w, 0.01f);
        // 両側 auto は中央
        assertEquals(parentLeft + 100, find(root, "e").x, 0.01f);
        // ltr の箱は左に寄ったまま
        assertEquals(find(root, "q").x, find(root, "f").x, 0.01f);
    }

    @Test
    void dirAttributeMapsToTheDirectionProperty() {
        LayoutBox root = layout("<div id=\"p\" dir=\"rtl\"><div id=\"k\">x</div></div><div id=\"l\" dir=\"ltr\"></div>", FontResolver.FALLBACK);
        assertEquals("rtl", find(root, "p").style.get("direction"));
        assertEquals("rtl", find(root, "k").style.get("direction"), "direction は継承される");
        assertEquals("ltr", find(root, "l").style.get("direction"));
    }

    @Test
    void startAndEndFollowTheDirectionOfTheText() {
        String html = "<div id=\"s\" style=\"width:200px;direction:rtl\">x</div>"
                + "<div id=\"t\" style=\"width:200px;direction:rtl;text-align:end\">x</div>"
                + "<div id=\"u\" style=\"width:200px;text-align:end\">x</div>"
                + "<div id=\"v\" style=\"width:200px\">x</div>"
                + "<div id=\"w\" style=\"width:200px;direction:rtl;text-align:left\">x</div>";
        LayoutBox root = layout(html, FontResolver.FALLBACK);
        float left = find(root, "v").x;
        float xs = firstTextX(find(root, "s"));
        float xt = firstTextX(find(root, "t"));
        float xu = firstTextX(find(root, "u"));
        float xv = firstTextX(find(root, "v"));
        float xw = firstTextX(find(root, "w"));
        assertEquals(left, xv, 0.01f, "ltr の start は左");
        assertTrue(xs > left + 150, "rtl の start は右: " + xs);
        assertEquals(left, xt, 0.01f, "rtl の end は左");
        assertTrue(xu > left + 150, "ltr の end は右: " + xu);
        assertEquals(left, xw, 0.01f, "明示した left は向きによらない");
    }

    @Test
    void textAlignKeywordsResolveToPhysicalSides() {
        assertEquals(TextAlign.LEFT, TextAlign.START.physical(false));
        assertEquals(TextAlign.RIGHT, TextAlign.START.physical(true));
        assertEquals(TextAlign.RIGHT, TextAlign.END.physical(false));
        assertEquals(TextAlign.LEFT, TextAlign.END.physical(true));
        assertEquals(TextAlign.CENTER, TextAlign.CENTER.physical(true));
        assertEquals(TextAlign.LEFT, TextAlign.LEFT.physical(true));
    }

    @Test
    void textBelowTheLineBoxCountsTowardsScrollHeight() {
        SystemFonts system = SystemFonts.system().withLocale(Locale.ENGLISH);
        Assumptions.assumeTrue(system.hasFamily("Arial"), "Arial が無い環境");
        FontResolver fonts = system::resolve;
        // 行の高さ 10px。Arial 16px の内容領域（ascent + descent）は行より高いので、文字は行の外にはみ出す
        LayoutBox root = layout("<div id=\"box\" style=\"width:200px;line-height:10px;font-family:Arial;font-size:16px\">a<br>b</div>", fonts);
        LayoutBox box = find(root, "box");
        assertEquals(20f, box.h, 0.01f);
        var face = fonts.resolve("Arial");
        var style = new com.nishiyu.enderframelib.core.text.model.FontStyle(16, false, false, 0);
        float ascent = face.ascent(style);
        float descent = face.descent(style);
        assertTrue(ascent + descent > 10, "前提: 内容領域が行の高さより高い");
        // 2 行目: 行の上端 10、半リーディング floor((10 - (ascent + descent)) / 2)、ベースライン = 上端 + 半リーディング + ascent、
        // 内容領域の下端 = ベースライン + descent
        float expected = 10 + (float) Math.floor((10 - (ascent + descent)) / 2f) + ascent + descent;
        assertEquals(expected, box.scrollHeight, 0.01f, "最終行の文字の内容領域の下端がスクロール範囲に入る");
        assertTrue(box.scrollHeight > box.h);
    }

    @Test
    void textThatFitsItsLineBoxDoesNotAddScrollOverflow() {
        SystemFonts system = SystemFonts.system().withLocale(Locale.ENGLISH);
        Assumptions.assumeTrue(system.hasFamily("Arial"), "Arial が無い環境");
        FontResolver fonts = system::resolve;
        LayoutBox root = layout("<div id=\"box\" style=\"width:200px;line-height:24px;font-family:Arial;font-size:16px\">a<br>b</div>", fonts);
        LayoutBox box = find(root, "box");
        assertEquals(box.h, box.scrollHeight, 0.01f);
    }
}
