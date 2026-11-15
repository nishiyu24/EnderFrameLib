package com.nishiyu.enderframelib.core.kernel.gpu.shader;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/** G2: シェーダーの源は版の行を持たず、前置き（{@link Glsl}）が付けるだけ。API ごとの違いは前置きに閉じる。 */
class ShaderSourcesTest {
    @Test
    void sourcesCarryNoVersionLineAndThePreambleAddsExactlyOne() throws Exception {
        int n = 0;
        for (Field f : ShaderSources.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) continue;
            String src = (String) f.get(null);
            n++;
            assertFalse(src.contains("#version"), f.getName() + ": 源に版の行を書かない");
            String full = Glsl.gl150(src);
            assertTrue(full.startsWith("#version 150\n"), f.getName());
            assertEquals(1, full.split("#version", -1).length - 1, f.getName() + ": 版の行は前置きの 1 つだけ");
        }
        assertEquals(8, n, "ANIMATION・矩形 2・テクスチャ 2・合成・3D 2");
    }

    @Test
    void assembledShadersAreWhatTheRenderersCompile() {
        assertEquals(Glsl.gl150(ShaderSources.ANIMATION + ShaderSources.RECT_VERTEX), com.nishiyu.enderframelib.core.kernel.gpu.GpuRectShader.VERTEX);
        assertEquals(Glsl.gl150(ShaderSources.RECT_FRAGMENT), com.nishiyu.enderframelib.core.kernel.gpu.GpuRectShader.FRAGMENT);
        assertSame(ShaderSources.ANIMATION, com.nishiyu.enderframelib.core.kernel.gpu.GpuAnimation.GLSL);
    }

    @Test
    void everyEntryPointIsDeclaredOnce() throws Exception {
        for (String name : new String[]{"RECT_VERTEX", "RECT_FRAGMENT", "TEX_VERT", "TEX_FRAG", "FX_FRAG", "SCENE3D_VERTEX", "SCENE3D_FRAGMENT"}) {
            String src = (String) ShaderSources.class.getField(name).get(null);
            assertEquals(1, src.split("void\\s+main\\s*\\(\\s*\\)", -1).length - 1, name);
        }
    }
}
