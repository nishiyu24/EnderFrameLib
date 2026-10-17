package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.css.CascadeOrigin;
import com.nishiyu.enderframelib.core.css.CssParser;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UA スタイルシートと組み込みテーマが Java の文字列リテラルから resources に移ったことの番人。
 * リソースが欠けると UI が無スタイルで描画されてしまい、しかも例外も出ないため、ここで止める。
 */
public class CssResourcesTest {

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
        assertTrue(css.contains("focus-visible"), "focus-visible の共通定義が無い");
    }
}
