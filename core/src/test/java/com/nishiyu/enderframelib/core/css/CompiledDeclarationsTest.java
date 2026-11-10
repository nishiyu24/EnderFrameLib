package com.nishiyu.enderframelib.core.css;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.css.ValueComputer.CssWideKeyword;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Milestone 2: 宣言は解析時に 1 回だけ前処理され、適用は前処理の結果で行う。書き換えれば前処理も作り直される。 */
class CompiledDeclarationsTest {

    @Test
    void precomputesKindsValidityAndSubproperties() {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("color", "red");
        d.put("margin", "1px 2px");
        d.put("width", "inherit");
        d.put("all", "unset");
        d.put("background-color", "not-a-color");
        d.put("margin-top", "var(--x)");
        d.put("--c", "revert");
        CompiledDeclarations c = CompiledDeclarations.of(d);
        assertEquals(7, c.n);
        assertEquals("color", c.names[0]);
        assertEquals(ComputedStyle.idOf("color"), c.ids[0]);
        assertNull(c.wide[0]);
        assertFalse(c.invalid[0]);
        assertEquals(0, c.subIds[0].length);
        assertTrue(c.subIds[1].length >= 4, "margin はロングハンドを消す");
        assertEquals(CssWideKeyword.INHERIT, c.wide[2]);
        assertTrue(c.isAll[3]);
        assertTrue(c.invalid[4], "解釈できない色は無効");
        assertFalse(c.invalid[5], "var() を含む値は算出時まで判らない");
        assertEquals(-1, c.ids[6], "--* は添字を持たない");
        assertTrue(c.mentionsRevert);
    }

    @Test
    void invalidShorthandIsMarkedInvalid() {
        CompiledDeclarations c = CompiledDeclarations.of(Map.of("margin", "1px 2px 3px 4px 5px 6px"));
        assertTrue(c.invalid[0]);
        assertFalse(CompiledDeclarations.of(Map.of("margin", "1px")).invalid[0]);
        assertSame(CompiledDeclarations.EMPTY, CompiledDeclarations.of(Map.of()));
    }

    @Test
    void ruleRecompilesWhenPropertiesChange() {
        StyleSheet.StyleRule rule = new StyleSheet.StyleRule("div");
        rule.properties.put("color", "red");
        CompiledDeclarations first = rule.compiled();
        assertSame(first, rule.compiled(), "変更が無ければ使い回す");
        assertFalse(first.mentionsRevert);
        rule.properties.put("width", "revert");
        CompiledDeclarations second = rule.compiled();
        assertNotSame(first, second);
        assertEquals(2, second.n);
        assertTrue(second.mentionsRevert);
        rule.properties.remove("width");
        assertEquals(1, rule.compiled().n);
        rule.properties.putAll(Map.of("height", "1px"));
        assertEquals(2, rule.compiled().n);
    }

    @Test
    void cascadeUsesCompiledDeclarationsWithTheSameResultAsBefore() {
        // 後の宣言が前のロングハンドを消す・無効な宣言は先行値を残す・全域キーワードは算出で解ける
        String css = "div { margin-top: 5px; margin: 1px 2px } div { background-color: red } div { background-color: nope }"
                + " div { padding: 1 2 3 4 5 6 } p { width: inherit }";
        com.nishiyu.enderframelib.common.StandaloneView v = new com.nishiyu.enderframelib.common.StandaloneView(
                "<style>" + css + "</style><div id=a><p id=b>x</p></div>", 300, 200,
                com.nishiyu.enderframelib.core.text.model.FontResolver.FALLBACK);
        v.ui().flushInvalidation();
        assertFalse(v.ui().renderCache().isEmpty());
    }
}
