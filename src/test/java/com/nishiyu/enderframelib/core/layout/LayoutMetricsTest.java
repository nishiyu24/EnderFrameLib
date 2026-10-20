package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.layout.TextShaper.FontFace;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.StyleResolver;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext.FormattingContext;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext.ScrollState;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext.Viewport;
import com.nishiyu.enderframelib.core.layout.TextShaper.FontFace.FontResolver;
import com.nishiyu.enderframelib.core.layout.TextShaper.FontFace.FontStyle;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * テキストの正規化と行送りの解釈が 1 箇所に揃っていることを固定する。
 *
 * 何が壊れていたか:
 * - {@code text-transform} は描画側だけが適用していたため、測定は変換前の文字列を測っていた。
 *   インラインフラグメント経路では変換そのものが抜けていた。
 * - {@code line-height} は描画側に px と数値だけを見る別実装があり、{@code %} / {@code em} /
 *   {@code normal} でレイアウトの予約高さと描画の行送りが食い違っていた。
 */
public class LayoutMetricsTest {

    @Test
    public void testTextTransformIsAppliedToTheMeasuredString() {
        LayoutBox box = textBox("hello world", "text-transform", "uppercase");
        assertEquals("HELLO WORLD", box.displayText, "測定対象の文字列が変換されていない");
    }

    @Test
    public void testTextTransformSupportsLowercaseAndCapitalize() {
        assertEquals("hello world", textBox("HELLO WORLD", "text-transform", "lowercase").displayText);
        assertEquals("Hello World", textBox("hello world", "text-transform", "capitalize").displayText);
        assertEquals("hello world", textBox("hello world", "text-transform", "none").displayText);
    }

    @Test
    public void testTransformedTextIsMeasuredAtItsTransformedWidth() {
        LayoutBox plain = textBox("iiii", "font-size", "16px");
        LayoutBox upper = textBox("iiii", "font-size", "16px", "text-transform", "uppercase");

        LayoutContext ctx = new LayoutContext(new Viewport(1920, 1080), src -> new int[]{32, 32},
                new ScrollState(), FontResolver.FALLBACK);
        plain.measure(800, 600, new FormattingContext("block", "stretch", false), ctx);
        upper.measure(800, 600, new FormattingContext("block", "stretch", false), ctx);

        // FontFace.FALLBACK では 'i' は 3px、'I' は 7px 扱い。変換後の幅で測れていれば広くなる。
        // 幅は送り幅そのもの（W2 で切り上げをやめた。丸めは描画の 1 箇所だけ）
        FontStyle font = new FontStyle(16.0f, false, false, 0f);
        assertEquals(FontFace.FALLBACK.advance("IIII", font), upper.w, 0.001);
        assertEquals(FontFace.FALLBACK.advance("iiii", font), plain.w, 0.001);
    }

    @Test
    public void testLineHeightResolvesEveryUnitAgainstTheFontSize() {
        FontStyle font = new FontStyle(20.0f, false, false, 0f);
        FontFace face = FontFace.FALLBACK;

        assertEquals(30.0f, LayoutMetrics.lineHeightPx(style("line-height", "1.5"), face, font), 0.0001f);
        // % と em は算出値の段で要素自身の font-size に対して px に解かれる（子はその px を継承する）
        assertEquals(30.0f, LayoutMetrics.lineHeightPx(style("font-size", "20px", "line-height", "150%"), face, font), 0.0001f);
        assertEquals(30.0f, LayoutMetrics.lineHeightPx(style("font-size", "20px", "line-height", "1.5em"), face, font), 0.0001f);
        // 長さ指定は長さのまま。以前は 20/16 の倍率に直され font-size に比例していた
        assertEquals(20.0f, LayoutMetrics.lineHeightPx(style("line-height", "20px"), face, font), 0.0001f);
        // normal は書体の ascent + descent + 行間から決まる（固定値 1.375 ではない）
        assertEquals(face.normalLineHeight(font),
                LayoutMetrics.lineHeightPx(style("line-height", "normal"), face, font), 0.0001f);
        assertEquals(face.normalLineHeight(font),
                LayoutMetrics.lineHeightPx(style("font-size", "16px"), face, font), 0.0001f);
    }

    private static LayoutBox textBox(String text, String... keyValues) {
        return new LayoutBox(new com.nishiyu.enderframelib.core.dom.HtmlDocument.HtmlText(text), style(keyValues));
    }

    private static ComputedStyle style(String... keyValues) {
        Map<String, String> raw = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            raw.put(keyValues[i], keyValues[i + 1]);
        }
        return StyleResolver.finalizeComputedStyle(raw, null, null);
    }
}