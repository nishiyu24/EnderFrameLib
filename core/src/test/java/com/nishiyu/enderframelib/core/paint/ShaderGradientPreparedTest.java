package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.ShaderGradient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 画素ごとに使う {@code Prepared} が、基準の {@code colorOf} と同じ色を返す（直前の色の記憶があっても）。 */
class ShaderGradientPreparedTest {
    private static final int A = 0x80FF2000, B = 0xFF0040FF, OPAQUE = 0xFF102030;

    private static List<ShaderGradient> samples() {
        return List.of(
                ShaderGradient.of(ShaderGradient.LINEAR, 0.01f, 0.02f, -0.5f, 0f, 0f, 1f, 1f, 0f, 0f, A, B, 0),
                ShaderGradient.of(ShaderGradient.LINEAR, 0.02f, 0.0f, 0f, 0f, 0f, 1f, 2f, 0f, 0f, OPAQUE, B, 3),
                ShaderGradient.of(ShaderGradient.LINEAR, 0.05f, 0.01f, 0f, 0f, 0f, 1f, 1f, 0f, 3f, A, OPAQUE, 0),
                ShaderGradient.of(ShaderGradient.RADIAL, 50f, 40f, 0.02f, 0.03f, 0f, 1f, 1f, 0f, 0f, A, B, 1),
                ShaderGradient.of(ShaderGradient.RADIAL, 10f, 10f, 0.05f, 0.05f, 0.2f, 2f, 1f, 0f, 0.5f, OPAQUE, 0x00000000, 2),
                ShaderGradient.of(ShaderGradient.CONIC, 40f, 30f, 0.25f, 0f, 0f, 1f, 1f, 0f, 0f, A, B, 0),
                ShaderGradient.of(ShaderGradient.CONIC, 40f, 30f, 0.1f, 0f, 0.1f, 2f, 1f, 0f, 0f, OPAQUE, A, 3));
    }

    @Test
    void preparedMatchesColorOfOnRandomAndRepeatedPoints() {
        Random r = new Random(11);
        for (ShaderGradient g : samples()) {
            ShaderGradientEmulator.Prepared p = ShaderGradientEmulator.prepare(g);
            for (int i = 0; i < 20_000; i++) {
                // 同じ点を続けて評価して、直前の色の再利用も確かめる
                float x = (r.nextFloat() - 0.2f) * 200, y = (r.nextFloat() - 0.2f) * 150;
                Integer want = ShaderGradientEmulator.colorOf(g, x, y);
                for (int k = 0; k < 2; k++) {
                    assertEquals(want == null ? 0 : want, p.colorOrZero(x, y), g.kind() + " @" + x + "," + y);
                }
            }
        }
    }
}
