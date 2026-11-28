package com.nishiyu.enderframelib.client.renderer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.nishiyu.enderframelib.core.paint.ExcludedNodes;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * 面の描き方の除く節（M3b-4c-2b-1）: HUD は {@code data-mount-entity} の要素を描かない（列を絞らずフレームの値で省く）。
 * ほかの面は何も除かず、1 項目ずつ変えても除く節は変わらない。
 */
class SurfaceStyleTest {

    @Test
    void hudExcludesElementsMountedToEntities() {
        assertEquals(new ExcludedNodes("data-mount-entity"), SurfaceStyle.hud().exclude());
        assertEquals(SurfaceStyle.MOUNTED_TO_ENTITY, SurfaceStyle.hud().withHovered("x").withAudio(SurfaceStyle.Audio.NONE)
                .withLod(0xFF000000, 1.0).exclude(), "1 項目ずつ変えても除く節は保つ");
    }

    @Test
    void otherSurfacesExcludeNothing() {
        for (SurfaceStyle s : new SurfaceStyle[]{SurfaceStyle.gui(true), SurfaceStyle.billboard(), SurfaceStyle.world(0),
                SurfaceStyle.item(0), SurfaceStyle.overlay(0), SurfaceStyle.world(0).withAudio(SurfaceStyle.Audio.at(BlockPos.ZERO))}) {
            assertNull(s.exclude(), s.toString());
        }
    }
}
