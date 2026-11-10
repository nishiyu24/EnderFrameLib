package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.core.css.CssParser;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 解析済みスタイルシートの雛形のキャッシュ（{@link DocumentLoader#sheetTemplateCache}）が、毎回解析する場合と
 * 同じシート・同じ描画を返し、写しどうしが互いに影響しないこと。
 */
class StyleSheetTemplateCacheTest {

    @AfterEach
    void restore() {
        DocumentLoader.sheetTemplateCache = true;
    }

    /** シートの中身を、規則の順・詳細度・宣言・状態・メディア・レイヤーまで文字列にする。 */
    private static String dump(StyleSheet sheet) {
        StringBuilder sb = new StringBuilder();
        for (StyleSheet.StyleRule r : sheet.rules) {
            sb.append(r.selector).append('|').append(r.specificity).append('|').append(r.properties)
                    .append('|').append(r.stateMask).append(r.isHover).append(r.isActive).append(r.isFocus)
                    .append(r.isBefore).append(r.isAfter).append('|').append(r.pseudoElement)
                    .append('|').append(r.layer).append('|').append(r.mediaQueries).append(r.mediaActive)
                    .append('|').append(r.containerQueries).append('|').append(r.parsedSelector().text())
                    .append('|').append(sheet.layers.rankOf(r.layer)).append('\n');
        }
        sb.append("kf=").append(sheet.keyframes.keySet()).append(" cs=").append(sheet.counterStyles.keySet())
                .append(" vars=").append(sheet.variables).append(" reg=").append(sheet.registeredProperties.keySet())
                .append(" env=").append(sheet.mediaEnvironment()).append(" layersEmpty=").append(sheet.layers.isEmpty());
        return sb.toString();
    }

    private static final String CSS = """
            @layer base, theme;
            @layer base { a { color: red } }
            @layer theme { a { color: blue } }
            @media (min-width: 500px) { .wide { width: 500px } }
            @media (max-width: 300px) { .narrow { width: 100px } }
            .a:hover, .b::before { content: "x"; color: #123 }
            li::marker { color: green }
            input::placeholder { color: gray }
            @keyframes spin { from { opacity: 0 } to { opacity: 1 } }
            @property --x { syntax: '<length>'; inherits: false; initial-value: 0px }
            @counter-style dots { system: cyclic; symbols: '*' }
            :root { --c: 1 }
            """;

    @Test
    void cachedSheetsHaveTheSameContentAsFreshlyParsedOnes() {
        for (int[] vp : new int[][]{{320, 200}, {800, 600}, {200, 200}}) {
            DocumentLoader.sheetTemplateCache = false;
            String fresh = dump(DocumentLoader.buildStyleSheet(CSS, vp[0], vp[1]));
            DocumentLoader.sheetTemplateCache = true;
            String first = dump(DocumentLoader.buildStyleSheet(CSS, vp[0], vp[1])); // 雛形を作る
            String second = dump(DocumentLoader.buildStyleSheet(CSS, vp[0], vp[1])); // 雛形の写し
            assertEquals(fresh, first, "初回 @" + vp[0]);
            assertEquals(fresh, second, "写し @" + vp[0]);
        }
    }

    @Test
    void copiesAreIndependent() {
        DocumentLoader.sheetTemplateCache = true;
        StyleSheet a = DocumentLoader.buildStyleSheet(CSS, 800, 600);
        StyleSheet b = DocumentLoader.buildStyleSheet(CSS, 800, 600);
        StyleSheet c = DocumentLoader.buildStyleSheet(CSS, 800, 600);
        assertNotSame(b.rules.get(0), c.rules.get(0));
        String before = dump(c);
        // b を書き換える: 規則の追加・削除、宣言の書き換え、メディア環境の変更、変数と層の追加
        b.rules.remove(0);
        b.rules.get(0).properties.put("margin", "99px");
        b.updateMediaEnvironment(CssParser.MediaEnvironment.of(100, 100));
        b.variables.put("--z", "1");
        b.layers.declare(List.of("extra"));
        a.rules.clear();
        assertEquals(before, dump(c), "別の写しは変わらない");
        assertEquals(before, dump(DocumentLoader.buildStyleSheet(CSS, 800, 600)), "雛形も変わらない");
    }

    @Test
    void fontFaceAndImportAreNeverCached() {
        List<String> seen = new ArrayList<>();
        java.util.function.Consumer<String> saved = CssParser.fontFaceCallback;
        CssParser.fontFaceCallback = seen::add;
        try {
            String css = "@font-face { font-family: X; src: url(x.ttf) } p { color: red }";
            DocumentLoader.buildStyleSheet(css, 320, 200);
            DocumentLoader.buildStyleSheet(css, 320, 200);
            assertEquals(2, seen.size(), "@font-face は解析のたびに登録フックが呼ばれる（キャッシュで飛ばさない）");
        } finally {
            CssParser.fontFaceCallback = saved;
        }
    }

    private static List<DisplayItem> render(String html, int w, int h, boolean cache) {
        DocumentLoader.sheetTemplateCache = cache;
        StandaloneView v = new StandaloneView(html, w, h, FontResolver.FALLBACK);
        v.ui().flushInvalidation();
        return new ArrayList<>(v.ui().renderCache());
    }

    @Test
    void renderingIsIdenticalOnTheFidelityCorpus() {
        int cases = 0;
        for (String name : FidelityDumper.caseNames()) {
            String html = FidelityDumper.caseHtml(name);
            for (int[] vp : new int[][]{{320, 200}, {800, 600}}) {
                List<DisplayItem> expected = render(html, vp[0], vp[1], false);
                render(html, vp[0], vp[1], true); // 雛形を作る
                List<DisplayItem> actual = render(html, vp[0], vp[1], true); // 雛形の写しで描く
                assertEquals(expected.size(), actual.size(), name + ": 項目の数");
                for (int i = 0; i < expected.size(); i++) assertEquals(expected.get(i), actual.get(i), name + ": 項目 " + i);
            }
            cases++;
        }
        assertTrue(cases > 100, "コーパスが読めている: " + cases);
    }
}
