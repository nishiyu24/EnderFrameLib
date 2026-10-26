package com.nishiyu.enderframelib.core.paint.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FBO プールと文字の置き場の VRAM の配分。 */
public class VramBudgetTest {

    @Test
    void layerCountsColorDepthAndStencil() {
        assertEquals(1920L * 1080 * 8, VramBudget.layerBytes(1920, 1080));
        assertEquals(0, VramBudget.layerBytes(0, 100));
    }

    @Test
    void fullHdGetsSeveralNestedLayers() {
        int layers = VramBudget.maxLayers(1920, 1080, 0);
        assertTrue(layers >= VramBudget.MIN_LAYERS, "入れ子の効果が数段回る: " + layers);
        assertTrue(layers <= VramBudget.MAX_LAYERS);
    }

    @Test
    void hugeFramebufferStillKeepsTheMinimum() {
        long budget = VramBudget.fboBudgetBytes(7680, 4320, 0);
        assertEquals(VramBudget.layerBytes(7680, 4320) * VramBudget.MIN_LAYERS, budget);
    }

    @Test
    void largerAtlasShrinksTheFboBudgetButNeverBelowTheReserve() {
        long empty = VramBudget.fboBudgetBytes(1280, 720, 0);
        long reserveOnly = VramBudget.fboBudgetBytes(1280, 720, VramBudget.ATLAS_RESERVE);
        long bigAtlas = VramBudget.fboBudgetBytes(1280, 720, VramBudget.ATLAS_CEILING);
        assertEquals(empty, reserveOnly, "予約の範囲内では変わらない");
        assertTrue(bigAtlas <= reserveOnly);
    }

    @Test
    void atlasCeilingTriggersEviction() {
        assertFalse(VramBudget.atlasOverCeiling(VramBudget.ATLAS_CEILING));
        assertTrue(VramBudget.atlasOverCeiling(VramBudget.ATLAS_CEILING + 1));
    }
}
