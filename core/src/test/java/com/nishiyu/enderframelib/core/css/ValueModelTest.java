package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.model.*;

import com.nishiyu.enderframelib.core.css.CssParser.CssTokenizer;
import com.nishiyu.enderframelib.core.css.CssParser.MediaEnvironment;
import com.nishiyu.enderframelib.core.css.StyleSheet.CascadeOrigin;
import com.nishiyu.enderframelib.core.css.StyleSheet.StyleRule;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * W1 の値モデル（指定値 → 算出値、CSS 全域キーワード、var()、アットルール）を固定する。
 *
 * 忠実度コーパスは矩形しか測らないので、色・カスケードの巻き戻し・条件付き規則のように
 * 寸法に出ない振る舞いはここで押さえる。
 */
public class ValueModelTest {

    // ---- 継承するのは算出値

    @Test
    public void testDescendantsInheritComputedFontSizeNotTheEmString() {
        ComputedStyle parent = computeRoot("font-size: 2em");
        assertEquals("32px", parent.get(CssProperty.FONT_SIZE));

        // 子は "2em" ではなく "32px" を受け取るので、32px に 2 を掛け直さない
        ComputedStyle child = computeChild(parent, "");
        assertEquals(32f, child.fontSizePx(), 0.001f);
        ComputedStyle grandChild = computeChild(child, "");
        assertEquals(32f, grandChild.fontSizePx(), 0.001f);
    }

    @Test
    public void testEmLengthsAreAbsolutizedAgainstTheElementsOwnFontSize() {
        ComputedStyle s = computeRoot("font-size: 20px; width: 5em; margin: 1em 0.5rem; line-height: 150%");
        assertEquals("100px", s.get(CssProperty.WIDTH));
        assertEquals("20px", s.get(CssProperty.MARGIN_TOP));
        assertEquals("8px", s.get(CssProperty.MARGIN_RIGHT));
        assertEquals("30px", s.get(CssProperty.LINE_HEIGHT));

        // 単位の無い line-height は数値のまま継承し、子のフォントサイズに掛かる
        ComputedStyle unitless = computeChild(computeRoot("line-height: 1.5"), "font-size: 10px");
        assertEquals("1.5", unitless.get(CssProperty.LINE_HEIGHT));
    }

    @Test
    public void testFontSizeInViewportUnitsUsesTheViewport() {
        ComputedStyle s = StyleResolver.finalizeComputedStyle(decls("font-size: 2vw"), null, null, Map.of(),
                16f, 16f, 320, 200);
        assertEquals(6.4f, s.fontSizePx(), 0.001f);
        assertEquals("6.4px", s.get(CssProperty.FONT_SIZE));
    }

    @Test
    public void testFontSizeKeywordsAndZero() {
        assertEquals(13f, computeRoot("font-size: small").fontSizePx(), 0.001f);
        assertEquals(19.2f, computeRoot("font-size: larger").fontSizePx(), 0.001f);
        // 0 は有効なサイズ（以前は無効扱いで 16px のままだった）
        assertEquals(0f, computeRoot("font-size: 0").fontSizePx(), 0.001f);
    }

    // ---- CSS 全域キーワード

    @Test
    public void testInheritTakesTheParentsComputedValueEvenForNonInheritedProperties() {
        ComputedStyle parent = computeRoot("width: 10em; padding-left: 7px");
        ComputedStyle child = computeChild(parent, "width: inherit; padding-left: inherit");
        assertEquals("160px", child.get(CssProperty.WIDTH));
        assertEquals("7px", child.get(CssProperty.PADDING_LEFT));
    }

    @Test
    public void testInitialAndUnset() {
        ComputedStyle parent = computeRoot("color: red; width: 100px");
        ComputedStyle child = computeChild(parent, "display: initial; color: initial; width: unset");
        assertEquals("inline", child.get(CssProperty.DISPLAY));
        assertEquals("black", child.get(CssProperty.COLOR));
        assertEquals("auto", child.get(CssProperty.WIDTH));

        // unset は継承するプロパティなら inherit
        ComputedStyle unsetColor = computeChild(parent, "color: unset");
        assertEquals("red", unsetColor.get(CssProperty.COLOR));
    }

    @Test
    public void testShorthandWithCssWideKeywordReachesEveryLonghand() {
        ComputedStyle parent = computeRoot("margin: 1px 2px 3px 4px");
        ComputedStyle child = computeChild(parent, "margin: inherit");
        assertEquals("1px", child.get(CssProperty.MARGIN_TOP));
        assertEquals("4px", child.get(CssProperty.MARGIN_LEFT));
    }

    @Test
    public void testRevertRollsBackToTheUserAgentValue() {
        StyleSheet sheet = sheet("div { display: block; color: green; }", "div { display: flex; color: red; } div { display: revert; color: revert; }");
        Map<String, String> raw = StyleResolver.resolveDeclarations(new HtmlNode("div"), Map.of(), sheet, null);
        assertEquals("block", raw.get(CssProperty.DISPLAY));
        assertEquals("green", raw.get(CssProperty.COLOR));
    }

    @Test
    public void testRevertWithoutUserAgentValueBehavesLikeUnset() {
        StyleSheet sheet = sheet("", "div { width: 50px; } div { width: revert; }");
        Map<String, String> raw = StyleResolver.resolveDeclarations(new HtmlNode("div"), Map.of(), sheet, null);
        assertNull(raw.get(CssProperty.WIDTH), "UA に値が無ければ初期値（宣言なし）に戻る");
    }

    @Test
    public void testAllRevertAndAllInitial() {
        StyleSheet sheet = sheet("div { display: block; }", "div { display: flex; width: 50px; all: revert; }");
        Map<String, String> raw = StyleResolver.resolveDeclarations(new HtmlNode("div"), Map.of(), sheet, null);
        assertEquals("block", raw.get(CssProperty.DISPLAY));
        assertNull(raw.get(CssProperty.WIDTH));

        StyleSheet initial = sheet("div { display: block; }", "div { all: initial; width: 10px; }");
        Map<String, String> raw2 = StyleResolver.resolveDeclarations(new HtmlNode("div"),
                Map.of(CssProperty.COLOR, "red"), initial, null);
        assertEquals("inline", raw2.get(CssProperty.DISPLAY));
        assertEquals("black", raw2.get(CssProperty.COLOR), "all: initial は継承するプロパティも初期値に戻す");
        assertEquals("10px", raw2.get(CssProperty.WIDTH), "all の後の宣言は効く");
    }

    @Test
    public void testCurrentColorResolvesToTheElementsColor() {
        ComputedStyle s = computeRoot("color: #ff0000; border-top-color: currentcolor; box-shadow: 0 0 2px currentColor");
        assertEquals("#ff0000", s.get(CssProperty.BORDER_TOP_COLOR));
        assertEquals("0 0 2px #ff0000", s.get(CssProperty.BOX_SHADOW));

        ComputedStyle child = computeChild(computeRoot("color: blue"), "color: currentcolor");
        assertEquals("blue", child.get(CssProperty.COLOR), "color 自身の currentcolor は inherit");
    }

    // ---- var()

    @Test
    public void testVarFallbackMayContainParenthesesAndNestedVar() {
        ComputedStyle s = computeRoot("color: var(--missing, rgb(1, 2, 3)); width: var(--a, var(--b, 12px))");
        assertEquals("rgb(1, 2, 3)", s.get(CssProperty.COLOR));
        assertEquals("12px", s.get(CssProperty.WIDTH));
    }

    @Test
    public void testVarChainsResolveThroughCustomProperties() {
        ComputedStyle s = computeRoot("--base: 4px; --double: calc(var(--base) * 2); padding-top: var(--double)");
        assertEquals("calc(4px * 2)", s.get(CssProperty.PADDING_TOP));
        assertEquals("calc(4px * 2)", s.get("--double"), "カスタムプロパティの算出値は参照を解いた値");
    }

    @Test
    public void testCyclicOrUndefinedVarMakesTheDeclarationUnset() {
        ComputedStyle parent = computeRoot("color: green");
        ComputedStyle child = computeChild(parent,
                "--a: var(--b); --b: var(--a); width: var(--a); color: var(--undefined)");
        assertNull(child.get("--a"), "循環したカスタムプロパティは無効");
        assertEquals("auto", child.get(CssProperty.WIDTH), "無効な宣言は unset（width は初期値）");
        assertEquals("green", child.get(CssProperty.COLOR), "無効な宣言は unset（color は継承）");
    }

    // ---- ショートハンド

    @Test
    public void testLaterShorthandOverridesEarlierLonghandInTheCascade() {
        StyleSheet sheet = sheet("", ".a { flex-grow: 5; } .a.b { flex: 1; }");
        HtmlNode node = new HtmlNode("div");
        node.attrs.put("class", "a b");
        ComputedStyle s = StyleResolver.finalizeComputedStyle(
                StyleResolver.resolveDeclarations(node, Map.of(), sheet, null), sheet, null);
        assertEquals("1", s.get(CssProperty.FLEX_GROW));
    }

    @Test
    public void testInvalidShorthandDoesNotClearEarlierLonghands() {
        StyleSheet sheet = sheet("", ".a { margin-top: 5px; } .a.b { margin: 1px 2px 3px 4px 5px; }");
        HtmlNode node = new HtmlNode("div");
        node.attrs.put("class", "a b");
        Map<String, String> raw = StyleResolver.resolveDeclarations(node, Map.of(), sheet, null);
        assertEquals("5px", raw.get(CssProperty.MARGIN_TOP));
        assertNull(raw.get(CssProperty.MARGIN));
    }

    @Test
    public void testBorderComponentsInAnyOrder() {
        ComputedStyle s = computeRoot("border: solid 2px red");
        assertEquals("2px", s.get(CssProperty.BORDER_TOP_WIDTH));
        assertEquals("solid", s.get("border-top-style"));
        assertEquals("red", s.get(CssProperty.BORDER_LEFT_COLOR));

        // 2 値でも色が取れる（以前は 3 値以上のときだけ末尾を色にしていた）
        assertEquals("blue", computeRoot("border-bottom: 1px blue").get(CssProperty.BORDER_BOTTOM_COLOR));
    }

    @Test
    public void testFontShorthand() {
        ComputedStyle s = computeRoot("font: italic bold 20px/1.5 \"Fancy Font\", serif");
        assertEquals("italic", s.get(CssProperty.FONT_STYLE));
        assertEquals("bold", s.get(CssProperty.FONT_WEIGHT));
        assertEquals("20px", s.get(CssProperty.FONT_SIZE));
        assertEquals("1.5", s.get(CssProperty.LINE_HEIGHT));
        assertEquals("\"Fancy Font\", serif", s.get(CssProperty.FONT_FAMILY));
    }

    @Test
    public void testRegistryDrivesInheritanceAndInvalidation() {
        assertTrue(PropertyRegistry.isInherited(CssProperty.COLOR));
        assertFalse(PropertyRegistry.isInherited(CssProperty.WIDTH));
        assertTrue(PropertyRegistry.isPaintOnly(CssProperty.BACKGROUND_COLOR));
        assertFalse(PropertyRegistry.isPaintOnly(CssProperty.PADDING_TOP));
        assertFalse(PropertyRegistry.isPaintOnly("some-unknown-property"), "未登録は再レイアウト側に倒す");
        assertTrue(PropertyRegistry.subproperties(CssProperty.BORDER).contains(CssProperty.BORDER_TOP_WIDTH));
    }

    // ---- アットルール

    @Test
    public void testMediaFeaturesBeyondWidth() {
        MediaEnvironment landscape = MediaEnvironment.of(800, 400);
        assertTrue(AtRuleProcessor.evaluateMediaQuery("(orientation: landscape)", landscape));
        assertFalse(AtRuleProcessor.evaluateMediaQuery("(orientation: portrait)", landscape));
        assertTrue(AtRuleProcessor.evaluateMediaQuery("(prefers-color-scheme: light)", landscape));
        assertFalse(AtRuleProcessor.evaluateMediaQuery("(prefers-color-scheme: dark)", landscape));
        assertTrue(AtRuleProcessor.evaluateMediaQuery("screen and (min-height: 300px)", landscape));
        assertFalse(AtRuleProcessor.evaluateMediaQuery("print", landscape));
        assertTrue(AtRuleProcessor.evaluateMediaQuery("not print", landscape));
        assertTrue(AtRuleProcessor.evaluateMediaQuery("(400px <= width <= 900px)", landscape));
        assertFalse(AtRuleProcessor.evaluateMediaQuery("(width > 800px)", landscape));
        assertTrue(AtRuleProcessor.evaluateMediaQuery("not (max-width: 600px)", landscape));
        assertFalse(AtRuleProcessor.evaluateMediaQuery("(some-future-feature: 1)", landscape), "未知の特性は偽");
    }

    @Test
    public void testSupportsConditions() {
        assertTrue(AtRuleProcessor.evaluateSupports("(display: grid)"));
        assertFalse(AtRuleProcessor.evaluateSupports("(made-up-property: 1)"));
        assertTrue(AtRuleProcessor.evaluateSupports("not (made-up-property: 1)"));
        assertTrue(AtRuleProcessor.evaluateSupports("(display: flex) and (gap: 4px)"));
        assertTrue(AtRuleProcessor.evaluateSupports("(made-up: 1) or (color: red)"));
        assertTrue(AtRuleProcessor.evaluateSupports("selector(a > b)"));
        assertTrue(AtRuleProcessor.evaluateSupports("(--custom: anything)"));

        StyleSheet sheet = CssParser.parse("@supports (made-up: 1) { .x { color: red; } } "
                + "@supports (color: red) { .y { color: red; } }", 800);
        assertEquals(1, sheet.rules.size());
        assertEquals(".y", sheet.rules.getFirst().selector);
    }

    @Test
    public void testImportResolvesAtTheTopOfTheSheetOnly() {
        Map<String, String> files = new HashMap<>();
        files.put("a.css", ".from-a { color: red; }");
        files.put("wide.css", ".wide { color: red; }");
        files.put("late.css", ".late { color: red; }");
        MediaEnvironment env = MediaEnvironment.of(400, 300).withImportResolver(files::get);

        StyleSheet sheet = CssParser.parse("@import url(\"a.css\"); @import 'wide.css' (min-width: 1000px); "
                + ".own { color: blue; } @import 'late.css';", env, CascadeOrigin.AUTHOR);
        assertEquals(2, sheet.activeRuleCount());
        assertTrue(sheet.rules.stream().anyMatch(r -> r.selector.equals(".from-a")));
        // メディアが合わない @import の規則はシートに残るが掛からない（ビューポートが変われば掛かる）
        assertTrue(sheet.rules.stream().anyMatch(r -> r.selector.equals(".wide") && !r.mediaActive));
        assertTrue(sheet.rules.stream().noneMatch(r -> r.selector.equals(".late")), "規則の後の @import は無効");
    }

    // ---- W10: 色

    @Test
    public void testHexColorsWithAlphaAreRgba() {
        // 以前は #RRGGBBAA を整数のまま ARGB にしていたので、半透明の赤が不透明の暗い青になっていた
        assertEquals(0x80FF0000, ColorParser.parseColor("#ff000080"));
        assertEquals(0x88FF0000, ColorParser.parseColor("#f008"));
        assertEquals(0xFFAABBCC, ColorParser.parseColor("#abc"));
        assertEquals(0xFF123456, ColorParser.parseColor("#123456"));
        assertFalse(ColorParser.isColor("#12345"));
        assertFalse(ColorParser.isColor("#ggg"));
    }

    @Test
    public void testNamedColorsAreTheCssValues() {
        // 以前は Minecraft のチャットの色（red = #FF5555、gray = #AAAAAA）で、orange なども足りなかった
        assertEquals(0xFFFF0000, ColorParser.parseColor("red"));
        assertEquals(0xFF008000, ColorParser.parseColor("green"));
        assertEquals(0xFF0000FF, ColorParser.parseColor("blue"));
        assertEquals(0xFF808080, ColorParser.parseColor("gray"));
        assertEquals(0xFF800080, ColorParser.parseColor("purple"));
        assertEquals(0xFFFFA500, ColorParser.parseColor("orange"));
        assertEquals(0xFFFFD700, ColorParser.parseColor("Gold"));
        assertEquals(0xFF663399, ColorParser.parseColor("rebeccapurple"));
        assertEquals(148, ColorParser.namedColorCount());
        assertEquals(0x00000000, ColorParser.parseColor("transparent"));
        assertTrue(ColorParser.isColor("currentColor"));
        assertFalse(ColorParser.isColor("dark_red"));
    }

    @Test
    public void testRgbAndHslInBothSyntaxes() {
        assertEquals(0x80FF0000, ColorParser.parseColor("rgb(255 0 0 / 50%)"));
        assertEquals(0xFFFF0000, ColorParser.parseColor("rgb(100%, 0%, 0%)"));
        assertEquals(0x4000FF00, ColorParser.parseColor("rgba(0, 255, 0, .25)"));
        assertEquals(0xFF00FF00, ColorParser.parseColor("hsl(120deg 100% 50%)"));
        assertEquals(0xFF00FFFF, ColorParser.parseColor("hsl(0.5turn, 100%, 50%)"));
        assertEquals(0x80FF0000, ColorParser.parseColor("hsla(0, 100%, 50%, 0.5)"));
        assertFalse(ColorParser.isColor("rgb(1, 2)"));
        assertFalse(ColorParser.isColor("rgb(1 2 3, 4)"));
    }

    @Test
    public void testInvalidColorDeclarationIsDroppedByTheCascade() {
        ComputedStyle parent = computeRoot("color: blue");
        // 以前は解けない色を不透明な白で描いていた。宣言が無効なら color は親から継承する
        ComputedStyle child = computeChild(parent, "color: blue; color: notacolor; background-color: nope");
        assertEquals(0xFF0000FF, child.color(CssProperty.COLOR, 0));
        assertNull(child.get(CssProperty.BACKGROUND_COLOR));
        // var() が色にならなければ算出時に無効（unset。color は継承、background-color は初期値）
        ComputedStyle viaVar = computeChild(parent, "--c: 12px; color: var(--c); background-color: var(--c)");
        assertEquals(0xFF0000FF, viaVar.color(CssProperty.COLOR, 0));
        assertEquals("transparent", viaVar.get(CssProperty.BACKGROUND_COLOR));
        // outline-color だけは invert を取る
        assertEquals("invert", computeChild(parent, "outline-color: invert").get("outline-color"));
    }

    @Test
    public void testTransparentColorIsKeptAsTransparent() {
        ComputedStyle s = computeChild(computeRoot("color: blue"), "color: transparent");
        assertEquals(0x00000000, s.color(CssProperty.COLOR, 0xFFFFFFFF));
    }

    // ---- W10: 単位と数式

    @Test
    public void testViewportMinMaxFontAndAbsoluteUnits() {
        // ビューポートは 320 × 200
        assertEquals(100f, LengthResolver.parseDim("50vmin", 0, 320, 200, 16, 16), 0.001f);
        assertEquals(160f, LengthResolver.parseDim("50vmax", 0, 320, 200, 16, 16), 0.001f);
        assertEquals(16f, LengthResolver.parseDim("12pt", 0, 320, 200, 16, 16), 0.001f);
        assertEquals(96f, LengthResolver.parseDim("1in", 0, 320, 200, 16, 16), 0.001f);
        assertEquals(16f, LengthResolver.parseDim("1pc", 0, 320, 200, 16, 16), 0.001f);
        assertEquals(37.795f, LengthResolver.parseDim("1cm", 0, 320, 200, 16, 16), 0.01f);
        assertEquals(20f, LengthResolver.parseDim("2ch", 0, 320, 200, 20, 16), 0.001f);
        assertEquals(10f, LengthResolver.parseDim("1ex", 0, 320, 200, 20, 16), 0.001f);
        // 以前は calc() の中で単位を落とし 50vmin が 50px になっていた
        assertEquals(110f, LengthResolver.parseDim("calc(50vmin + 10px)", 0, 320, 200, 16, 16), 0.001f);
        // 1 回だけ分解した長さも同じ値に解く
        assertEquals(100f, LengthResolver.compile("50vmin").resolve(0, 320, 200, 16, 16), 0.001f);
        assertEquals(16f, LengthResolver.compile("12pt").resolve(0, 320, 200, 16, 16), 0.001f);
        assertEquals(20f, LengthResolver.compile("2ch").resolve(0, 320, 200, 20, 16), 0.001f);
        assertEquals(0f, LengthResolver.compile("10foo").resolve(0, 320, 200, 16, 16), 0.001f);
    }

    @Test
    public void testNewUnitsAreAbsolutizedInComputedValues() {
        ComputedStyle s = computeRoot("font-size: 12pt; width: 2ch; height: 1in; margin-left: 1ex");
        assertEquals(16f, s.fontSizePx(), 0.001f);
        assertEquals("16px", s.get(CssProperty.WIDTH));
        assertEquals("96px", s.get(CssProperty.HEIGHT));
        assertEquals("8px", s.get(CssProperty.MARGIN_LEFT));
    }

    @Test
    public void testNestedMathFunctionsInsideCalc() {
        // var() の展開で生じる入れ子。以前は内側の関数を 0 にしていた
        assertEquals(158f, LengthResolver.parseDim("calc(100% - calc(10px + 2em))", 200, 320, 200, 16, 16), 0.001f);
        assertEquals(60f, LengthResolver.parseDim("calc(min(100px, 50%) + 10px)", 100, 320, 200, 16, 16), 0.001f);
        assertEquals(26f, LengthResolver.parseDim("calc(max(10px, 1em) + clamp(5px, 50%, 14px))", 20, 320, 200, 16, 16), 0.001f);
        assertEquals(8f, LengthResolver.parseDim("calc(2 * (3px + 1px))", 0, 320, 200, 16, 16), 0.001f);
        assertEquals(40f, LengthResolver.parseDim("min(calc(50% - 10px), 100px)", 100, 320, 200, 16, 16), 0.001f);
        assertEquals(10f, LengthResolver.parseDim("calc(1e1px)", 0, 320, 200, 16, 16), 0.001f);
        assertEquals(0f, LengthResolver.parseDim("calc(10px + nonsense)", 0, 320, 200, 16, 16), 0.001f);
    }

    @Test
    public void testDivisionByZeroDoesNotLeakInfinityOrNaN() {
        // 以前は Infinity / NaN がそのままレイアウトの座標に入っていた
        float inf = LengthResolver.parseDim("calc(10px / 0)", 0, 320, 200, 16, 16);
        assertTrue(Float.isFinite(inf));
        assertEquals(LengthResolver.LARGEST_LENGTH, inf, 0.001f);
        assertEquals(-LengthResolver.LARGEST_LENGTH, LengthResolver.parseDim("calc(-10px / 0)", 0, 320, 200, 16, 16), 0.001f);
        assertEquals(0f, LengthResolver.parseDim("calc(0px / 0)", 0, 320, 200, 16, 16), 0.001f);
        // 無限大は min() の比較ではそのまま効く
        assertEquals(10f, LengthResolver.parseDim("min(10px, 1px / 0)", 0, 320, 200, 16, 16), 0.001f);
    }

    // ---- 補助

    private static ComputedStyle computeRoot(String declarations) {
        return StyleResolver.finalizeComputedStyle(decls(declarations), null, null, Map.of(), 16f, 16f, 320, 200);
    }

    /** 親の算出スタイルから、カスケード（継承の種まき）と算出までを通す。 */
    private static ComputedStyle computeChild(ComputedStyle parent, String declarations) {
        HtmlNode node = new HtmlNode("div");
        node.attrs.put("style", declarations);
        Map<String, String> raw = StyleResolver.resolveDeclarations(node, parent.asMap(), null, null);
        return StyleResolver.finalizeComputedStyle(raw, null, null, parent.asMap(),
                parent.fontSizePx(), 16f, 320, 200);
    }

    private static Map<String, String> decls(String declarations) {
        return new LinkedHashMap<>(CssTokenizer.parseDeclarations(declarations));
    }

    private static StyleSheet sheet(String uaCss, String authorCss) {
        StyleSheet sheet = CssParser.parse(uaCss, 800, CascadeOrigin.USER_AGENT);
        sheet.rules.addAll(CssParser.parse(authorCss, 800, CascadeOrigin.AUTHOR).rules);
        sheet.rules.sort(StyleRule::compareSpecificity);
        return sheet;
    }
}
