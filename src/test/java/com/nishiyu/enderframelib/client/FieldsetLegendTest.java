package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * fieldset と legend（HTML §15.3.13）。描画される legend は通常フローに入らず、上の枠線に重ねて置かれる。
 *
 * 何が壊れていたか: 以前は legend を普通のブロックとして fieldset の内容の先頭に置いていたので、
 * legend が枠の内側に入り、中身が legend の高さと上の padding の分だけ下がっていた。
 */
public class FieldsetLegendTest {

    /**
     * fieldset.html（UA の既定・枠線より低い legend・マージン・指定の高さ・2 つ目の legend・float や絶対配置の legend・
     * auto マージン・折り返す legend・flex / grid の fieldset など）を Chrome の実測と比べる。
     */
    @Test
    void layoutMatchesChrome() throws Exception {
        SystemFonts fonts = ChromeRects.requireFonts("Arial");
        Map<String, ChromeRects.Rect> expected = ChromeRects.chrome("/fieldset-chrome/fieldset-800.txt");
        Map<String, ChromeRects.Rect> actual = ChromeRects.engine("src/test/resources/fieldset-chrome/fieldset.html", 800, 1800, fonts);
        List<String> failures = ChromeRects.differences(expected, actual, 0.5f);
        assertTrue(expected.size() > 60, "比べた要素が少なすぎる: " + expected.size());
        assertTrue(failures.isEmpty(), failures.size() + "/" + expected.size() + " 個の要素が Chrome と違う:\n" + String.join("\n", failures));
    }

    /**
     * 枠線は legend の後ろを描かず（左・右・下の 3 つのクリップで描く）、legend が枠線より高ければ
     * 枠線と背景は legend の中央の高さから始まる。
     */
    @Test
    void borderSkipsTheLegendAndStartsAtItsMiddle() {
        String html = """
                <html><head><style>body { margin: 0; } legend { padding: 0; }</style></head><body>
                <fieldset id="fs" style="margin: 0; border: 2px solid #f00; padding: 10px; background: #00f; width: 200px">
                  <legend id="lg" style="height: 20px; width: 50px">t</legend><div style="height: 30px"></div>
                </fieldset></body></html>""";
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(html, "", 400, 300);
        LayoutBox root = compiler.layoutDocument(doc, 400, 300, Map.of(), new ScrollState(), FontResolver.FALLBACK);
        List<DisplayItem> items = new DisplayListBuilder(400, 300, doc.sheet.keyframes, new ScrollState()).render(root);

        LayoutBox fs = find(root, "fs");
        LayoutBox lg = find(root, "lg");
        assertEquals(0, fs.y, 0.01f);
        assertEquals(0, lg.y, 0.01f, "legend は fieldset の上端に揃う（枠線より高い）");
        assertEquals(12, lg.x, 0.01f, "legend は内容ボックスの左端（枠線 2 + padding 10）");
        assertEquals(20 + 10 + 30 + 10 + 2, fs.h, 0.01f, "高さ = legend + padding + 内容 + padding + 下の枠線");

        DisplayItem bg = items.stream().filter(i -> i.id().equals("fs_bg")).findFirst().orElseThrow();
        assertEquals(9, bg.y(), "背景は legend の中央（(20 - 2) / 2）から塗る");

        List<DisplayItem> borders = items.stream().filter(i -> i.baseId().equals("fs") && i.id().contains("_b_")).toList();
        assertFalse(borders.isEmpty());
        for (DisplayItem part : borders) {
            ClipRect clip = part.meta().clipRect();
            assertTrue(clip != null, "枠線の部品はクリップを持つ: " + part.id());
            boolean coversLegend = clip.minX() < lg.x + lg.w && clip.maxX() > lg.x && clip.minY() < lg.y + lg.h;
            assertFalse(coversLegend, "枠線が legend の後ろに描かれる: " + part.id() + " " + clip);
        }
        DisplayItem top = borders.stream().filter(i -> i.id().contains("_b_top")).findFirst().orElseThrow();
        assertEquals(9, top.y(), "上の枠線は legend の中央の高さ");
    }

    private static LayoutBox find(LayoutBox b, String id) {
        if (b.node != null && !b.isAnonymous && id.equals(b.node.attrs.get("id"))) return b;
        for (LayoutBox c : b.children) {
            LayoutBox f = find(c, id);
            if (f != null) return f;
        }
        return null;
    }
}
