package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.StyleSheet.CascadeOrigin;
import com.nishiyu.enderframelib.core.css.StyleSheet.Specificity;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CssParserGoldenTest {

    @Test
    public void testParseDim() {
        // 各種単位が現状どのようにピクセル値へ変換されているかの固定
        assertEquals(50, LengthResolver.parseDim("50px", 100, 1920, 1080));
        assertEquals(50, LengthResolver.parseDim("50%", 100, 1920, 1080));

        // vh, vw はビューポート基準
        assertEquals(192, LengthResolver.parseDim("10vw", 100, 1920, 1080));
        assertEquals(108, LengthResolver.parseDim("10vh", 100, 1920, 1080));

        // 失敗時フォールバック (0)
        assertEquals(0, LengthResolver.parseDim("invalid", 100, 1920, 1080));
        assertEquals(0, LengthResolver.parseDim("auto", 100, 1920, 1080));
    }

    @Test
    public void testEmIsElementRelativeAndRemIsRootRelative() {
        // フェーズ2: em は要素のフォントサイズ、rem はルートのフォントサイズを基準にする
        assertEquals(40, LengthResolver.parseDim("2em", 100, 1920, 1080, 20.0f, 16.0f));
        assertEquals(32, LengthResolver.parseDim("2rem", 100, 1920, 1080, 20.0f, 16.0f));

        // 引数省略時は双方 16px 相当
        assertEquals(32, LengthResolver.parseDim("2em", 100, 1920, 1080));
    }

    @Test
    public void testCalcAndClamp() {
        assertEquals(60, LengthResolver.parseDim("calc(100% - 40px)", 100, 1920, 1080));
        assertEquals(50, LengthResolver.parseDim("clamp(10px, 100%, 50px)", 200, 1920, 1080));
        assertEquals(10, LengthResolver.parseDim("clamp(10px, 100%, 50px)", 5, 1920, 1080));
    }

    @Test
    public void testColorParsing() {
        // ARGB形式での出力の固定
        assertEquals(0xFFFF0000, ColorParser.parseColor("#ff0000")); // 赤
        assertEquals(0xFFFFFFFF, ColorParser.parseColor("#fff"));    // 白 (短縮)
        assertEquals(0x8000FF00, ColorParser.parseColor("rgba(0, 255, 0, 0.5)")); // 半透明 (0.5 * 255 = 127.5 は 128。ブラウザと同じく丸める)
        assertEquals(0x00000000, ColorParser.parseColor("transparent"));
    }

    @Test
    public void testBoxEdgesShorthand() {
        assertArrayEqualsStr(new String[]{"1px", "1px", "1px", "1px"}, LengthResolver.parseBoxEdgesStr("1px"));
        assertArrayEqualsStr(new String[]{"1px", "2px", "1px", "2px"}, LengthResolver.parseBoxEdgesStr("1px 2px"));
        assertArrayEqualsStr(new String[]{"1px", "2px", "3px", "2px"}, LengthResolver.parseBoxEdgesStr("1px 2px 3px"));
        assertArrayEqualsStr(new String[]{"1px", "2px", "3px", "4px"}, LengthResolver.parseBoxEdgesStr("1px 2px 3px 4px"));
    }

    @Test
    public void testMarginAndPaddingShorthandAreExpanded() {
        // LayoutBox はロングハンド (margin-top 等) しか読まないため、
        // 算出スタイルの時点で 4 辺に展開されていなければならない
        Map<String, String> raw = new HashMap<>();
        raw.put("margin", "1px 2px 3px 4px");
        raw.put("padding", "5px");

        ComputedStyle s = StyleResolver.finalizeComputedStyle(raw, null, null);

        assertEquals(1, s.length("margin-top", 100, 1920, 1080));
        assertEquals(2, s.length("margin-right", 100, 1920, 1080));
        assertEquals(3, s.length("margin-bottom", 100, 1920, 1080));
        assertEquals(4, s.length("margin-left", 100, 1920, 1080));

        for (String dir : new String[]{"top", "right", "bottom", "left"}) {
            assertEquals(5, s.length("padding-" + dir, 100, 1920, 1080));
        }
    }

    @Test
    public void testLonghandWinsOverShorthand() {
        Map<String, String> raw = new HashMap<>();
        raw.put("margin", "10px");
        raw.put("margin-left", "99px");

        ComputedStyle s = StyleResolver.finalizeComputedStyle(raw, null, null);

        assertEquals(99, s.length("margin-left", 100, 1920, 1080));
        assertEquals(10, s.length("margin-top", 100, 1920, 1080));
    }

    @Test
    public void testMarginAutoSurvivesShorthandExpansion() {
        // "margin: 0 auto" による中央寄せが効くこと
        Map<String, String> raw = new HashMap<>();
        raw.put("margin", "0 auto");

        ComputedStyle s = StyleResolver.finalizeComputedStyle(raw, null, null);

        assertEquals("auto", s.get("margin-left"));
        assertEquals("auto", s.get("margin-right"));
        assertEquals("0", s.get("margin-top"));
    }

    @Test
    public void testGapShorthandIsExpandedToBothAxes() {
        Map<String, String> one = new HashMap<>();
        one.put("gap", "8px");
        ComputedStyle s1 = StyleResolver.finalizeComputedStyle(one, null, null);
        assertEquals("8px", s1.get("row-gap"));
        assertEquals("8px", s1.get("column-gap"));

        // gap: <row-gap> <column-gap>
        Map<String, String> two = new HashMap<>();
        two.put("gap", "4px 12px");
        ComputedStyle s2 = StyleResolver.finalizeComputedStyle(two, null, null);
        assertEquals("4px", s2.get("row-gap"));
        assertEquals("12px", s2.get("column-gap"));
    }

    @Test
    public void testGapLonghandsAndGridAliases() {
        Map<String, String> longhand = new HashMap<>();
        longhand.put("gap", "8px");
        longhand.put("column-gap", "20px");
        ComputedStyle s = StyleResolver.finalizeComputedStyle(longhand, null, null);
        assertEquals("8px", s.get("row-gap"));
        assertEquals("20px", s.get("column-gap"), "ロングハンドがショートハンドより優先される");

        Map<String, String> grid = new HashMap<>();
        grid.put("grid-gap", "6px 10px");
        ComputedStyle g = StyleResolver.finalizeComputedStyle(grid, null, null);
        assertEquals("6px", g.get("row-gap"));
        assertEquals("10px", g.get("column-gap"));
    }

    @Test
    public void testSpecificityAndCascade() {
        String css = ".btn { color: blue; } #submit { color: red; } div { color: green; }";
        StyleSheet sheet = CssParser.parse(css, 1920);

        // 詳細度によるソート結果の固定
        // 期待される順序: div (低) -> .btn -> #submit (高)
        assertEquals("div", sheet.rules.get(0).selector);
        assertEquals(".btn", sheet.rules.get(1).selector);
        assertEquals("#submit", sheet.rules.get(2).selector);
    }

    @Test
    public void testSpecificityTupleOrdering() {
        // 文字数カウントではなく (id, class, type) タプルで比較されること
        Specificity id = Specificity.calculate("#a", CascadeOrigin.AUTHOR);
        Specificity cls = Specificity.calculate(".averylongclassname.another", CascadeOrigin.AUTHOR);
        Specificity type = Specificity.calculate("div span p", CascadeOrigin.AUTHOR);

        assertTrue(cls.compareTo(id) < 0, "クラスはIDより弱い");
        assertTrue(type.compareTo(cls) < 0, "型セレクタはクラスより弱い");

        // オリジンは他のどの成分よりも優先される（UA の #id より作者の型セレクタが強い）
        Specificity uaId = Specificity.calculate("#a", CascadeOrigin.USER_AGENT);
        Specificity authorType = Specificity.calculate("div", CascadeOrigin.AUTHOR);
        assertTrue(uaId.compareTo(authorType) < 0, "オリジンが最優先");

        // !important はオリジンよりさらに強い
        Specificity important = Specificity.calculate("div", CascadeOrigin.USER_AGENT).withImportant(true);
        assertTrue(Specificity.calculate("#a", CascadeOrigin.INLINE).compareTo(important) < 0,
                "!important が最優先");
    }

    @Test
    public void testMediaQueryIsAndNotOr() {
        String css = "@media (min-width: 500px) and (max-width: 1000px) { .x { color: red; } }";

        // 範囲内なのでルールが掛かる
        assertEquals(1, CssParser.parse(css, 800).activeRuleCount());

        // 下限割れ・上限超えでは両方とも外れる（OR ではなく AND で評価される）。規則自体はシートに残る
        assertEquals(0, CssParser.parse(css, 400).activeRuleCount());
        assertEquals(0, CssParser.parse(css, 1200).activeRuleCount());
        assertEquals(1, CssParser.parse(css, 400).rules.size());
    }

    @Test
    public void testRootVariablesAreCollected() {
        StyleSheet sheet = CssParser.parse(":root { --accent: #ff0000; } .x { color: var(--accent); }", 1920);
        assertEquals("#ff0000", sheet.variables.get("--accent"));
    }

    private static void assertArrayEqualsStr(String[] expected, String[] actual) {
        assertEquals(String.join("|", expected), String.join("|", actual));
    }
}
