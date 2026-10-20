package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.common.CssResources;
import com.nishiyu.enderframelib.core.css.StyleSheet.CascadeOrigin;
import com.nishiyu.enderframelib.core.css.CssParser;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.RenderPipeline;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

/**
 * UA スタイルシートと組み込みテーマが Java の文字列リテラルから resources に移ったことの番人。
 * リソースが欠けると UI が無スタイルで描画されてしまい、しかも例外も出ないため、ここで止める。
 *
 * 加えて「UA の既定値の出所が ua.css だけであること」を固定する。
 * 何が壊れていたか: プレビュー用の enderframe-devkit.css が UA 相当の宣言を
 * 独自の値で持っていたため（{@code * { box-sizing: border-box }}、
 * {@code :root { font-size: 14px }}、button / input / table / .mc-slot …）、
 * ブラウザプレビューで作った見た目がゲーム内で再現しなかった。
 */
public class CssResourcesTest {

    private static final String PREVIEW = "/assets/enderframelib/preview/";

    @Test
    public void testUserAgentStyleSheetLoadsAndParses() {
        String css = CssResources.load(CssResources.USER_AGENT);
        assertFalse(css.isEmpty(), "ua.css が見つからない");

        StyleSheet sheet = CssParser.parse(css, 1920, CascadeOrigin.USER_AGENT);
        assertTrue(sheet.rules.size() > 40, "UA ルール数: " + sheet.rules.size());

        // UA シートのルールはすべて USER_AGENT オリジンでなければ作者スタイルを上書きしてしまう
        for (var rule : sheet.rules) {
            assertEquals(CascadeOrigin.USER_AGENT, rule.specificity.origin(), rule.selector);
        }
    }

    @Test
    public void testBuiltInThemesResolveFromResources() {
        for (String name : new String[]{"dark", "vanilla", "brass"}) {
            String css = CssResources.load(CssResources.themePath(name));
            assertFalse(css.isEmpty(), name + ".css が見つからない");
            assertTrue(css.contains(".mc-panel"), name + " にテーマの中核クラスが無い");
        }
    }

    @Test
    public void testDevKitPreviewStyleSheetLoads() {
        String css = CssResources.load(CssResources.DEV_KIT);
        assertFalse(css.isEmpty(), "enderframe-devkit.css が見つからない");
    }

    /**
     * プレビュー CSS に要素セレクタが無いこと。
     * 要素の既定値は ua.css が唯一の出所で、プレビュー側はクラス / id / 属性セレクタだけを持つ。
     */
    @Test
    public void testDevKitPreviewStyleSheetHasNoUserAgentDeclarations() {
        List<String> typeSelectors = new ArrayList<>();
        for (var rule : CssParser.parse(CssResources.load(CssResources.DEV_KIT), 1920, CascadeOrigin.AUTHOR).rules) {
            for (String simple : rule.selector.split("[\\s,>+~]+")) {
                if (simple.isEmpty()) continue;
                char head = simple.charAt(0);
                if (head != '.' && head != '#' && head != '[' && head != '*' && head != ':') {
                    typeSelectors.add(rule.selector);
                }
            }
        }
        assertTrue(typeSelectors.isEmpty(),
                "プレビュー CSS が要素の既定値を上書きしている（ua.css に寄せる）: " + typeSelectors);
    }

    /** プレビューページがゲーム内と同じ順序で UA シートとテーマを読むこと。 */
    @Test
    public void testPlaygroundLinksTheUserAgentSheetFirst() {
        String html = readResource(PREVIEW + "preview-playground.html");
        int ua = html.indexOf("enderframe-ua.css");
        int theme = html.indexOf("theme-dark.css");
        int devkit = html.indexOf("enderframe-devkit.css");
        assertTrue(ua >= 0, "UA シートを読んでいない");
        assertTrue(ua < theme, "UA シートはテーマより先に読む");
        assertTrue(theme < devkit, "テーマはプレビュー CSS より先に読む");
    }

    /**
     * 忠実度ダンプページの差し込み口が残っていること。
     * ここが改名されると {@code WebDevKitExporter} の replace が黙って no-op になり、
     * コーパスも UA シートも埋め込まれないページが出る。
     */
    @Test
    public void testFidelityDumpTemplateKeepsItsPlaceholders() {
        String html = readResource(PREVIEW + "fidelity-dump.html");
        assertTrue(html.contains("/*__UA_CSS__*/ \"\""), "UA シートの差し込み口が無い");
        assertTrue(html.contains("/*__CASES__*/ {}"), "コーパスの差し込み口が無い");
    }

    private static String readResource(String path) {
        try (InputStream is = CssResourcesTest.class.getResourceAsStream(path)) {
            assertTrue(is != null, "リソースが無い: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
