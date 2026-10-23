package com.nishiyu.enderframelib.client.renderer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** {@code transform} の平行移動の読み取り（単位なしの 0 を含む）。 */
public class TransformParseTest {

    private static float[] parse(String transform) {
        return DisplayListBuilder.parseTransformProps(transform, 200f, 40f, 0f, 0f, 1f, 1f, 0f);
    }

    @Test
    public void translateWithUnitlessZeroSecondArgument() {
        float[] tr = parse("translate(-50%, 0)");
        assertEquals(-100f, tr[0], 0.001f, "translate(-50%, 0) の X は箱の幅の -50%");
        assertEquals(0f, tr[1], 0.001f);
    }

    @Test
    public void translateWithUnitlessZeroFirstArgument() {
        float[] tr = parse("translate(0, -50%)");
        assertEquals(0f, tr[0], 0.001f);
        assertEquals(-20f, tr[1], 0.001f, "translate(0, -50%) の Y は箱の高さの -50%");
    }

    @Test
    public void translateWithUnits() {
        float[] tr = parse("translate(-50%, -50%)");
        assertEquals(-100f, tr[0], 0.001f);
        assertEquals(-20f, tr[1], 0.001f);

        tr = parse("translate(12px, 4px)");
        assertEquals(12f, tr[0], 0.001f);
        assertEquals(4f, tr[1], 0.001f);

        tr = parse("translate(10px)");
        assertEquals(10f, tr[0], 0.001f);
        assertEquals(0f, tr[1], 0.001f);
    }

    @Test
    public void translateAxisFunctionsAcceptUnitlessZero() {
        float[] tr = parse("translateX(0) translateY(-25%)");
        assertEquals(0f, tr[0], 0.001f);
        assertEquals(-10f, tr[1], 0.001f);
    }

    @Test
    public void translateCombinedWithScale() {
        float[] tr = parse("translate(-50%, 0) scale(2)");
        assertEquals(-100f, tr[0], 0.001f);
        assertEquals(2f, tr[2], 0.001f);
        assertEquals(2f, tr[3], 0.001f);
    }
}
