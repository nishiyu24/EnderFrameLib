package com.nishiyu.enderframelib.core.paint;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code filter} の解析と色の行列、{@code mix-blend-mode} の式（Filter Effects 1 / Compositing 1 の定義と、
 * ブラウザが返す既知の値）。シェーダーと参照描画はこの式と同じものを使う。
 */
public class FilterAndBlendTest {

    private static final FilterOp.Context CTX = new FilterOp.Context(1600, 900, 16, 16, 0xFF112233);

    @Test
    public void parsesEveryFunction() {
        List<FilterOp> ops = FilterOp.parse("blur(4px) opacity(50%) drop-shadow(2px 3px 4px red)", CTX);
        assertEquals(3, ops.size());
        assertEquals(FilterOp.Kind.BLUR, ops.get(0).kind());
        assertEquals(4f, ops.get(0).amount(), 0.001f);
        assertEquals(FilterOp.Kind.OPACITY, ops.get(1).kind());
        assertEquals(0.5f, ops.get(1).amount(), 0.001f);
        FilterOp shadow = ops.get(2);
        assertEquals(FilterOp.Kind.DROP_SHADOW, shadow.kind());
        assertEquals(2f, shadow.dx(), 0.001f);
        assertEquals(3f, shadow.dy(), 0.001f);
        assertEquals(4f, shadow.amount(), 0.001f);
        assertEquals(0xFFFF0000, shadow.color());
    }

    @Test
    public void dropShadowColorMayComeFirstAndDefaultsToCurrentColor() {
        FilterOp first = FilterOp.parse("drop-shadow(red 1px 2px)", CTX).getFirst();
        assertEquals(0xFFFF0000, first.color());
        assertEquals(1f, first.dx(), 0.001f);
        FilterOp none = FilterOp.parse("drop-shadow(1px 2px)", CTX).getFirst();
        assertEquals(0xFF112233, none.color());
        assertEquals(0f, none.amount(), 0.001f);
    }

    @Test
    public void noneAndInvalidValuesGiveNoFilters() {
        assertTrue(FilterOp.parse("none", CTX).isEmpty());
        assertTrue(FilterOp.parse("", CTX).isEmpty());
        assertTrue(FilterOp.parse(null, CTX).isEmpty());
        assertTrue(FilterOp.parse("blur(2px) wibble(1)", CTX).isEmpty(), "1 つでも読めない関数があれば値全体が無効");
        assertTrue(FilterOp.parse("blur(-1px)", CTX).isEmpty());
        assertTrue(FilterOp.parse("brightness(-1)", CTX).isEmpty());
        assertTrue(FilterOp.parse("hue-rotate(10)", CTX).isEmpty(), "角度の単位は必須（0 だけ省ける）");
        assertTrue(FilterOp.parse("url(#f)", CTX).isEmpty());
    }

    @Test
    public void adjacentColorFunctionsFoldIntoOneMatrixInOrder() {
        // 先に brightness(0.5)、次に invert(1): C' = 1 - 0.5 C
        List<FilterOp> ops = FilterOp.parse("brightness(0.5) invert(1)", CTX);
        assertEquals(1, ops.size());
        float[] out = FilterOp.applyColor(ops.getFirst().matrix(), new float[]{1f, 0.5f, 0f});
        assertArrayEquals(new float[]{0.5f, 0.75f, 1f}, out, 0.001f);
        // 逆順は C' = 0.5 (1 - C)
        float[] reversed = FilterOp.applyColor(FilterOp.parse("invert(1) brightness(0.5)", CTX).getFirst().matrix(), new float[]{1f, 0.5f, 0f});
        assertArrayEquals(new float[]{0f, 0.25f, 0.5f}, reversed, 0.001f);
        // 間に blur があれば畳まない
        assertEquals(3, FilterOp.parse("brightness(2) blur(1px) invert(1)", CTX).size());
    }

    @Test
    public void colorMatricesMatchTheSpecification() {
        float[] white = {1f, 1f, 1f};
        float[] red = {1f, 0f, 0f};
        assertArrayEquals(white, FilterOp.applyColor(m("grayscale(1)"), white), 0.001f);
        // 完全なグレースケールの赤は輝度 0.2126
        assertArrayEquals(new float[]{0.2126f, 0.2126f, 0.2126f}, FilterOp.applyColor(m("grayscale(1)"), red), 0.001f);
        // 半分は元の色との平均
        assertArrayEquals(new float[]{0.6063f, 0.1063f, 0.1063f}, FilterOp.applyColor(m("grayscale(0.5)"), red), 0.001f);
        assertArrayEquals(new float[]{0f, 1f, 1f}, FilterOp.applyColor(m("invert(1)"), red), 0.001f);
        assertArrayEquals(new float[]{0.5f, 0.5f, 0.5f}, FilterOp.applyColor(m("invert(0.5)"), red), 0.001f);
        // sepia(1) の赤
        assertArrayEquals(new float[]{0.393f, 0.349f, 0.272f}, FilterOp.applyColor(m("sepia(1)"), red), 0.001f);
        // brightness と contrast
        assertArrayEquals(new float[]{0.5f, 0.25f, 0f}, FilterOp.applyColor(m("brightness(0.5)"), new float[]{1f, 0.5f, 0f}), 0.001f);
        assertArrayEquals(new float[]{0.5f, 0.5f, 0.5f}, FilterOp.applyColor(m("contrast(0)"), red), 0.001f);
        assertArrayEquals(new float[]{1f, 0f, 0f}, FilterOp.applyColor(m("contrast(5)"), red), 0.001f);
        // saturate(0) は輝度のグレー、hue-rotate(0) は恒等、hue-rotate(360deg) も恒等
        assertArrayEquals(new float[]{0.213f, 0.213f, 0.213f}, FilterOp.applyColor(m("saturate(0)"), red), 0.001f);
        assertTrue(FilterOp.isIdentity(m("hue-rotate(0deg)")));
        assertTrue(FilterOp.isIdentity(m("hue-rotate(360deg)")));
        // 赤を 120deg 回すと緑寄り
        float[] rotated = FilterOp.applyColor(m("hue-rotate(120deg)"), red);
        assertTrue(rotated[1] > rotated[0] && rotated[1] > rotated[2], "赤を 120° 回しても緑が最大にならない");
    }

    private static float[] m(String filter) {
        return FilterOp.parse(filter, CTX).getFirst().matrix();
    }

    // ---- 混合 ----

    private static final float[] CB = {0.8f, 0.4f, 0.2f};
    private static final float[] CS = {0.5f, 0.5f, 0.25f};

    @Test
    public void separableBlendModesMatchTheirDefinitions() {
        assertArrayEquals(new float[]{0.4f, 0.2f, 0.05f}, BlendMode.blend(BlendMode.MULTIPLY, CB, CS), 0.0001f);
        assertArrayEquals(new float[]{0.9f, 0.7f, 0.4f}, BlendMode.blend(BlendMode.SCREEN, CB, CS), 0.0001f);
        assertArrayEquals(new float[]{0.5f, 0.4f, 0.2f}, BlendMode.blend(BlendMode.DARKEN, CB, CS), 0.0001f);
        assertArrayEquals(new float[]{0.8f, 0.5f, 0.25f}, BlendMode.blend(BlendMode.LIGHTEN, CB, CS), 0.0001f);
        assertArrayEquals(new float[]{0.3f, 0.1f, 0.05f}, BlendMode.blend(BlendMode.DIFFERENCE, CB, CS), 0.0001f);
        assertArrayEquals(new float[]{0.5f, 0.5f, 0.35f}, BlendMode.blend(BlendMode.EXCLUSION, CB, CS), 0.0001f);
        // overlay は背景が基準の hard-light、背景 0.8 / 前景 0.5: screen(0.5, 2*0.8-1=0.6)... = hardLight(cs, cb)
        assertEquals(hardLight(0.5f, 0.8f), BlendMode.blend(BlendMode.OVERLAY, CB, CS)[0], 0.0001f);
        assertEquals(hardLight(0.8f, 0.5f), BlendMode.blend(BlendMode.HARD_LIGHT, CB, CS)[0], 0.0001f);
        // color-dodge: cb / (1 - cs)
        assertEquals(0.8f / 0.5f > 1 ? 1f : 0.8f / 0.5f, BlendMode.blend(BlendMode.COLOR_DODGE, CB, CS)[0], 0.0001f);
        assertEquals(0.4f / 0.5f, BlendMode.blend(BlendMode.COLOR_DODGE, CB, CS)[1], 0.0001f);
        // color-burn: 1 - (1 - cb) / cs
        assertEquals(1f - 0.2f / 0.5f, BlendMode.blend(BlendMode.COLOR_BURN, CB, CS)[0], 0.0001f);
        // soft-light: cs <= 0.5 側
        assertEquals(0.8f - (1f - 1f) * 0.8f * 0.2f, BlendMode.blend(BlendMode.SOFT_LIGHT, CB, CS)[0], 0.0001f);
        assertEquals(0.2f - (1f - 0.5f) * 0.2f * 0.8f, BlendMode.blend(BlendMode.SOFT_LIGHT, CB, CS)[2], 0.0001f);
    }

    private static float hardLight(float cb, float cs) {
        return cs <= 0.5f ? cb * 2f * cs : cb + (2f * cs - 1f) - cb * (2f * cs - 1f);
    }

    @Test
    public void nonSeparableModesKeepTheRightComponents() {
        float[] backdrop = {0.2f, 0.5f, 0.8f};
        float[] source = {0.9f, 0.1f, 0.1f};
        // luminosity: 背景の色相・彩度で、前景の輝度
        float[] lumi = BlendMode.blend(BlendMode.LUMINOSITY, backdrop, source);
        assertEquals(BlendMode.lum(source), BlendMode.lum(lumi), 0.001f);
        // color: 前景の色相・彩度で、背景の輝度
        float[] color = BlendMode.blend(BlendMode.COLOR, backdrop, source);
        assertEquals(BlendMode.lum(backdrop), BlendMode.lum(color), 0.001f);
        // hue: 彩度と輝度は背景のまま
        float[] hue = BlendMode.blend(BlendMode.HUE, backdrop, source);
        assertEquals(BlendMode.sat(backdrop), BlendMode.sat(hue), 0.01f);
        assertEquals(BlendMode.lum(backdrop), BlendMode.lum(hue), 0.001f);
        // saturation: 輝度は背景のまま
        float[] saturation = BlendMode.blend(BlendMode.SATURATION, backdrop, source);
        assertEquals(BlendMode.lum(backdrop), BlendMode.lum(saturation), 0.001f);
        for (float[] c : List.of(lumi, color, hue, saturation)) {
            for (float v : c) assertTrue(v >= -0.0001f && v <= 1.0001f, "範囲外: " + v);
        }
    }

    @Test
    public void compositeFollowsTheAlphaFormula() {
        // 不透明な背景に不透明な前景（multiply）: そのまま B
        float[] opaque = BlendMode.composite(BlendMode.MULTIPLY, CB, 1f, CS, 1f);
        assertArrayEquals(new float[]{0.4f, 0.2f, 0.05f, 1f}, opaque, 0.0001f);
        // 前景が透明なら背景のまま
        float[] clear = BlendMode.composite(BlendMode.MULTIPLY, CB, 1f, CS, 0f);
        assertArrayEquals(new float[]{0.8f, 0.4f, 0.2f, 1f}, clear, 0.0001f);
        // 背景が透明なら混合せず前景（プリマルチプライド）
        float[] onClear = BlendMode.composite(BlendMode.MULTIPLY, CB, 0f, CS, 0.5f);
        assertArrayEquals(new float[]{0.25f, 0.25f, 0.125f, 0.5f}, onClear, 0.0001f);
        // 半透明の前景: 背景と B の間
        float[] half = BlendMode.composite(BlendMode.MULTIPLY, CB, 1f, CS, 0.5f);
        assertEquals(0.5f * 0.4f + 0.5f * 0.8f, half[0], 0.0001f);
        assertEquals(1f, half[3], 0.0001f);
        // normal は通常のアルファ合成
        float[] normal = BlendMode.composite(BlendMode.NORMAL, CB, 1f, CS, 0.25f);
        assertEquals(0.25f * 0.5f + 0.75f * 0.8f, normal[0], 0.0001f);
    }

    @Test
    public void cssNamesRoundTrip() {
        for (BlendMode mode : BlendMode.values()) {
            assertEquals(mode, BlendMode.fromCss(mode.cssName()));
        }
        assertEquals(BlendMode.COLOR_DODGE, BlendMode.fromCss("Color-Dodge"));
        assertEquals(BlendMode.NORMAL, BlendMode.fromCss("unknown"));
        assertEquals(BlendMode.NORMAL, BlendMode.fromCss(null));
        assertFalse(BlendMode.NORMAL.ordinal() != 0, "シェーダーは番号 0 を normal としている");
    }
}
