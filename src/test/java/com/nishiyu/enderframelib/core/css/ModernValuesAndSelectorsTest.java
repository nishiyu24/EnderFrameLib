package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.client.renderer.Gradients;
import com.nishiyu.enderframelib.client.renderer.Gradients.ConicGradient;
import com.nishiyu.enderframelib.client.renderer.Gradients.LinearGradient;
import com.nishiyu.enderframelib.client.renderer.Gradients.RadialGradient;
import com.nishiyu.enderframelib.core.css.LengthResolver.Dimension;
import com.nishiyu.enderframelib.core.css.LengthResolver.Metrics;
import com.nishiyu.enderframelib.core.css.LengthResolver.Quantity;
import com.nishiyu.enderframelib.core.css.SelectorMatcher.SelectorEngine;
import com.nishiyu.enderframelib.core.css.StyleSheet.KeyframeBlock;
import com.nishiyu.enderframelib.core.css.StyleSheet.Keyframes;
import com.nishiyu.enderframelib.core.dom.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.dom.HtmlParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R4 のセレクタ（文書・入力の状態で決まる疑似クラス、{@code :nth-child(An+B of S)}）と値
 * （色 Level 4 / 5、数学関数 Level 4、単位、グラデーション、型付きの {@code @keyframes}）を固定する。
 */
public class ModernValuesAndSelectorsTest {

    private static final Metrics METRICS = new Metrics(Float.NaN, 800, 600, 16, 16, 20, 24, Float.NaN, Float.NaN);

    // ---- セレクタ: 入力の状態

    @Test
    public void testPlaceholderShownFollowsTheValue() {
        HtmlNode root = HtmlParser.parseDocument("""
                <input id="empty" placeholder="name">
                <input id="filled" placeholder="name" value="x">
                <input id="plain">
                <textarea id="area" placeholder="memo"></textarea>
                """);
        assertEquals("empty,area", ids(root, ":placeholder-shown"));
    }

    @Test
    public void testReadOnlyAndReadWrite() {
        HtmlNode root = HtmlParser.parseDocument("""
                <input id="rw"><input id="ro" readonly><input id="dis" disabled>
                <div id="plain"></div><div id="ed" contenteditable="true"><span id="in"></span></div>
                <input id="cb" type="checkbox">
                """);
        assertEquals("rw,ed,in", ids(root, ":read-write"));
        assertTrue(ids(root, ":read-only").contains("ro"));
        assertTrue(ids(root, ":read-only").contains("plain"));
        assertFalse(ids(root, ":read-only").contains("rw"));
    }

    @Test
    public void testValidAndInvalidReadConstraintValidation() {
        HtmlNode root = HtmlParser.parseDocument("""
                <form id="f"><input id="req" required><input id="ok" required value="v"></form>
                <input id="em" type="email" value="nope"><input id="em2" type="email" value="a@b.co">
                """);
        String invalid = ids(root, ":invalid");
        assertTrue(invalid.contains("req"));
        assertTrue(invalid.contains("em"));
        // form は子孫の検証対象に妥当でないものがあれば妥当でない
        assertTrue(invalid.contains("f"));
        assertTrue(ids(root, ":valid").contains("ok"));
        assertTrue(ids(root, ":valid").contains("em2"));
        assertFalse(ids(root, ":valid").contains("req"));
    }

    @Test
    public void testInRangeAndOutOfRange() {
        HtmlNode root = HtmlParser.parseDocument("""
                <input id="a" type="number" min="1" max="10" value="5">
                <input id="b" type="number" min="1" max="10" value="50">
                <input id="c" type="number" value="50">
                """);
        // min / max の無い number も範囲内（ブラウザの :in-range と同じ。忠実度コーパス pseudo-form-state が固定）
        assertEquals("a,c", ids(root, ":in-range"));
        assertEquals("b", ids(root, ":out-of-range"));
    }

    // ---- セレクタ: 文書・言語・方向

    @Test
    public void testLangMatchesInheritedLanguageWithWildcardsAndSubtags() {
        HtmlNode root = HtmlParser.parseDocument("""
                <div lang="en-US"><p id="a"></p></div>
                <div lang="ja"><p id="b" lang="fr-CA"></p><p id="c"></p></div>
                """);
        assertEquals("a", ids(root, "p:lang(en)"));
        assertEquals("b", ids(root, "p:lang(fr)"));
        assertEquals("a,b,c", ids(root, "p:lang(en, fr, ja)"));
        assertEquals("c", ids(root, "p:lang(ja)"));
    }

    @Test
    public void testDirFollowsTheDirAttributeAndInherits() {
        HtmlNode root = HtmlParser.parseDocument("""
                <div dir="rtl"><p id="a"></p></div><div><p id="b"></p></div>
                """);
        assertEquals("a", ids(root, "p:dir(rtl)"));
        assertEquals("b", ids(root, "p:dir(ltr)"));
    }

    @Test
    public void testTargetFollowsTheFragment() {
        HtmlNode root = HtmlParser.parseDocument("<p id=\"a\"></p><p id=\"b\"></p>");
        HtmlDocument doc = assertInstanceOf(HtmlDocument.class, root);
        assertEquals("", ids(root, "p:target"));
        doc.setTargetFragment("#b");
        assertEquals("b", ids(root, "p:target"));
    }

    @Test
    public void testDefinedMatchesStandardElementsAndOnlyRegisteredCustomElements() {
        HtmlNode root = HtmlParser.parseDocument("<div id=\"a\"></div><my-card id=\"c\"></my-card>");
        HtmlDocument doc = assertInstanceOf(HtmlDocument.class, root);
        assertEquals("a", ids(root, "div:defined"));
        assertEquals("", ids(root, "my-card:defined"));
        doc.defineCustomElement("my-card");
        assertEquals("c", ids(root, "my-card:defined"));
    }

    @Test
    public void testNthChildOfSelector() {
        HtmlNode root = HtmlParser.parseDocument("""
                <ul><li id="1" class="x"></li><li id="2"></li><li id="3" class="x"></li><li id="4" class="x"></li></ul>
                """);
        assertEquals("3", ids(root, "li:nth-child(2 of .x)"));
        assertEquals("1,4", ids(root, "li:nth-last-child(odd of .x)"));
    }

    @Test
    public void testHasWithLogicalCombinations() {
        HtmlNode root = HtmlParser.parseDocument("""
                <div id="a"><b></b></div><div id="b"><i></i></div><div id="c"></div>
                """);
        assertEquals("a,b", ids(root, "div:has(b, i)"));
        assertEquals("c", ids(root, "div:not(:has(*))"));
    }

    // ---- 色 Level 4 / 5

    @Test
    public void testColorLevel4Functions() {
        assertEquals("#00ff00", color("hwb(120 0% 0%)"));
        assertEquals("#808080", color("hwb(0 50% 50%)"));
        assertEquals("#ffffff", color("lab(100 0 0)"));
        assertEquals("#000000", color("lch(0 0 0)"));
        assertEquals("#ffffff", color("oklab(1 0 0)"));
        assertEquals("#000000", color("oklch(0 0 0)"));
        assertEquals("#ff0000", color("color(srgb 1 0 0)"));
        assertEquals("#ffffff", color("color(srgb-linear 1 1 1)"));
        assertFalse(color("color(display-p3 1 0 0)").isEmpty());
    }

    @Test
    public void testOklchHueSelectsDistinctColors() {
        int red = ColorParser.parseColor("oklch(0.628 0.2577 29.23)");
        // sRGB の赤の oklch。丸めで 1 だけずれうる
        assertEquals(0xFF, (red >> 16) & 0xFF, 2);
        assertEquals(0, (red >> 8) & 0xFF, 3);
        assertEquals(0, red & 0xFF, 3);
    }

    @Test
    public void testColorMixAndRelativeColors() {
        assertEquals("#800080", color("color-mix(in srgb, red, blue)"));
        assertEquals("#4000bf", color("color-mix(in srgb, red 25%, blue)"));
        assertEquals("#ff0000", color("rgb(from red r g b)"));
        assertEquals("#1a2030", color("rgb(from #102030 calc(r + 10) g b)"));
        assertEquals("#00ff00", color("hsl(from lime h s l)"));
    }

    @Test
    public void testInvalidColorsAreRejected() {
        assertFalse(ColorParser.isColor("hwb(red)"));
        assertFalse(ColorParser.isColor("color-mix(in srgb, red)"));
        assertFalse(ColorParser.isColor("notacolor"));
    }

    // ---- 数学関数 Level 4

    @Test
    public void testRoundModRem() {
        assertEquals(15, px("round(up, 12.3px, 5px)"), 1e-6);
        assertEquals(10, px("round(down, 12.3px, 5px)"), 1e-6);
        assertEquals(10, px("round(nearest, 12.3px, 5px)"), 1e-6);
        assertEquals(10, -px("round(to-zero, -12.3px, 5px)"), 1e-6);
        assertEquals(2, px("mod(-7px, 3px)"), 1e-6);
        assertEquals(-1, px("rem(-7px, 3px)"), 1e-6);
        assertEquals(1, px("mod(7px, 3px)"), 1e-6);
    }

    @Test
    public void testAbsSignAndTrigonometry() {
        assertEquals(5, px("abs(-5px)"), 1e-6);
        Quantity sign = LengthResolver.evaluateMath("sign(-3px)", METRICS);
        assertNotNull(sign);
        assertEquals(-1, sign.value(), 1e-9);
        assertEquals(Dimension.NUMBER, sign.dimension());
        assertEquals(1, number("sin(90deg)"), 1e-9);
        assertEquals(1, number("cos(0deg)"), 1e-9);
        assertEquals(1, number("tan(45deg)"), 1e-9);
        assertEquals(8, number("pow(2, 3)"), 1e-9);
        assertEquals(3, number("calc(hypot(3, 4) - 2)"), 1e-9);
        assertEquals(Math.PI, number("calc(pi * 1)"), 1e-9);
        Quantity angle = LengthResolver.evaluateMath("atan2(1, 1)", METRICS);
        assertEquals(Dimension.ANGLE, angle.dimension());
        assertEquals(45, angle.value(), 1e-9);
    }

    @Test
    public void testMathMixesUnitsAndRejectsTypeMismatch() {
        assertEquals(26, px("calc(1em + 10px)"), 1e-6);
        assertEquals(20, px("min(3em, 20px)"), 1e-6);
        assertEquals(30, px("max(10px, 3 * 10px)"), 1e-6);
        assertTrue(LengthResolver.evaluateMath("calc(1px + 1deg)", METRICS) == null);
        assertTrue(LengthResolver.evaluateMath("round(up, 1px, 1deg)", METRICS) == null);
    }

    // ---- 単位

    @Test
    public void testLineHeightViewportAndContainerUnits() {
        assertEquals(40, LengthResolver.parseDim("2lh", METRICS), 1e-4);
        assertEquals(48, LengthResolver.parseDim("2rlh", METRICS), 1e-4);
        assertEquals(60, LengthResolver.parseDim("10svh", METRICS), 1e-4);
        assertEquals(60, LengthResolver.parseDim("10lvh", METRICS), 1e-4);
        assertEquals(60, LengthResolver.parseDim("10dvh", METRICS), 1e-4);
        assertEquals(80, LengthResolver.parseDim("10svw", METRICS), 1e-4);
        assertEquals(80, LengthResolver.parseDim("10cqw", METRICS), 1e-4);
        Metrics inContainer = new Metrics(Float.NaN, 800, 600, 16, 16, 20, 24, 200, 100);
        assertEquals(20, LengthResolver.parseDim("10cqw", inContainer), 1e-4);
        assertEquals(10, LengthResolver.parseDim("10cqh", inContainer), 1e-4);
        assertEquals(10, LengthResolver.parseDim("10cqmin", inContainer), 1e-4);
        assertEquals(20, LengthResolver.parseDim("10cqmax", inContainer), 1e-4);
        assertTrue(LengthResolver.parseDim("2cap", METRICS) > 0);
        assertTrue(LengthResolver.parseDim("2ch", METRICS) > 0);
        assertTrue(LengthResolver.parseDim("2ex", METRICS) > 0);
    }

    // ---- グラデーション

    @Test
    public void testConicGradientWithFromAndPosition() {
        Gradients.Gradient g = Gradients.parse("conic-gradient(from 90deg at 25% 75%, red, blue 50%, lime)");
        ConicGradient conic = assertInstanceOf(ConicGradient.class, g);
        assertEquals(90, conic.from(), 1e-9);
        assertEquals(List.of("25%", "75%"), conic.center());
        assertEquals(3, conic.stops().size());
        assertFalse(conic.repeating());
    }

    @Test
    public void testRepeatingRadialAndRepeatingLinear() {
        RadialGradient radial = assertInstanceOf(RadialGradient.class,
                Gradients.parse("repeating-radial-gradient(circle at center, red 0, blue 10px)"));
        assertTrue(radial.repeating());
        assertTrue(radial.circle());
        LinearGradient linear = assertInstanceOf(LinearGradient.class,
                Gradients.parse("repeating-linear-gradient(45deg, red, blue 20px)"));
        assertTrue(linear.repeating());
        assertEquals(45, linear.angle(), 1e-9);
    }

    @Test
    public void testColorHintAndInterpolationSpace() {
        LinearGradient g = assertInstanceOf(LinearGradient.class,
                Gradients.parse("linear-gradient(in oklch, red, 30%, blue)"));
        assertEquals(3, g.stops().size());
        assertTrue(g.stops().get(1).hint());
        assertEquals("30%", g.stops().get(1).position());
        assertEquals("oklch", g.colorSpace());
    }

    // ---- 型付きの @keyframes

    @Test
    public void testKeyframesAreTypedBlocksWithPercentages() {
        StyleSheet sheet = CssParser.parse("""
                @keyframes fade { from { opacity: 0; } 50%, 75% { opacity: .5; color: red } to { opacity: 1 } }
                """, 400);
        Keyframes frames = sheet.keyframes.get("fade");
        assertNotNull(frames);
        List<Float> percents = frames.blocks().stream().map(KeyframeBlock::percent).collect(Collectors.toList());
        assertEquals(List.of(0f, 50f, 75f, 100f), percents);
        assertEquals("0", frames.blocks().getFirst().declarations().get("opacity"));
        assertEquals("red", frames.blocks().get(1).declarations().get("color"));
    }

    // ---- helpers

    private static String ids(HtmlNode root, String selector) {
        return SelectorEngine.querySelectorAll(root, selector).stream()
                .map(HtmlNode::getId).filter(id -> !id.startsWith("gen_") && !id.startsWith("txt_"))
                .collect(Collectors.joining(","));
    }

    private static String color(String value) {
        return ColorParser.isColor(value) ? ColorParser.format(ColorParser.parseColor(value)) : "";
    }

    private static Double px(String expression) {
        Quantity q = LengthResolver.evaluateMath(expression, METRICS);
        assertNotNull(q, expression);
        return q.value();
    }

    private static double number(String expression) {
        Quantity q = LengthResolver.evaluateMath(expression, METRICS);
        assertNotNull(q, expression);
        return q.value();
    }
}
