package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertNull;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

/**
 * オラクルが見つけた 2 件の不整合（最初から作った面と増分の面の食い違い）の固定。
 * ①内在の幅が前回の測定の枠・余白〔{@code pL}・{@code bL}〕を読んでいた: 測っていない箱は 0・使い回した箱は前の値で、
 * shrink-to-fit の親の中の fieldset の幅が面によって違った（{@code IntrinsicSizing.inlineDecoration}）。
 * ②空白だけで畳まれた文字の箱（断片なし）の高さ 1 行ぶんがスクロール範囲に入っていた（{@code ScrollExtentCalc}）。
 */
class IntrinsicAndScrollOracleTest {
    private static final String HTML = """
            <style>body{margin:0;font:12px sans-serif} .row{margin:2px;padding:2px;border:1px solid #888}
            .fl{float:left;width:30px;height:20px} .fr{float:right;width:30px;height:20px}</style>
            <fieldset id=f><legend id=lg>L</legend><div id=f1 class="row fl">z</div><div id=f2>w</div></fieldset>
            """;

    private static void check(String legendClass, String bodyClass) {
        try (StandaloneView v = new StandaloneView(HTML, 320, 240, FontResolver.FALLBACK); var be = new MutationScenario.NoDraw()) {
            v.frame(1000, be, true);
            if (legendClass != null) ((HtmlElement) v.root().getNodeById("lg")).setAttribute("class", legendClass);
            v.frame(1016, be, true);
            if (bodyClass != null) {
                ((HtmlElement) v.root().getNodeById("f").parent).setAttribute("class", bodyClass);
                v.frame(1032, be, true);
            }
            assertNull(FrameOracle.firstDifference(v), legendClass + " / " + bodyClass);
        }
    }

    @Test void bodyFloatOnly() { check(null, "row fl"); }
    @Test void legendBecomesFloat() { check("row fr", null); }
    @Test void legendFloatThenShrinkToFit() { check("row fr", "row fl"); }
    @Test void legendFloatLeftThenShrinkToFit() { check("row fl", "row fl"); }
    @Test void legendPlainThenShrinkToFit() { check("row", "row fl"); }

    @Test void collapsedWhitespaceTextAddsNoScrollExtent() {
        try (StandaloneView v = new StandaloneView("<div id=a><div id=b>q</div>\n</div><p>x</p>", 320, 240, FontResolver.FALLBACK); var be = new MutationScenario.NoDraw()) {
            v.frame(1000, be, true);
            var b = v.root().getNodeById("b");
            b.parent.removeChild(b);
            v.frame(1016, be, true);
            assertNull(FrameOracle.firstDifference(v));
        }
    }
}
