package com.nishiyu.enderframelib.core.paint;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShaderGradientEmulatorAtan2Test {
    @Test
    void matchesMathAtan2EverywhereOnTheCircle() {
        Random r = new Random(7);
        for (int i = 0; i < 200_000; i++) {
            double x = (r.nextDouble() - 0.5) * 2000, y = (r.nextDouble() - 0.5) * 2000;
            assertEquals(Math.atan2(y, x), ShaderGradientEmulator.atan2(y, x), 1e-9, x + "," + y);
        }
    }

    @Test
    void axesAndZero() {
        double[] v = {0.0, -0.0, 1, -1, 5, -5};
        for (double y : v) for (double x : v) assertEquals(Math.atan2(y, x), ShaderGradientEmulator.atan2(y, x), 1e-9, y + "," + x);
    }
}
