package com.nishiyu.enderframelib.core.kernel.gpu.shader;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/** G2: シェーダーの源は版の行を持たず、前置き（{@link Glsl}）が付けるだけ。API ごとの違いは前置きに閉じ、ビルド時に SPIR-V を検証する。 */
class ShaderSourcesTest {
    private static final String SPIRV_DIR = "com/nishiyu/enderframelib/core/kernel/gpu/shader/spirv/";

    @Test
    void sourcesCarryNoVersionLineAndThePreambleAddsExactlyOne() throws Exception {
        int n = 0;
        for (Field f : ShaderSources.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) continue;
            String src = (String) f.get(null);
            n++;
            assertFalse(src.contains("#version"), f.getName() + ": 源に版の行を書かない");
            String gl = Glsl.gl150(src);
            assertTrue(gl.startsWith("#version 150\n"), f.getName());
            assertEquals(1, gl.split("#version", -1).length - 1, f.getName() + ": GL 版の行は前置きの 1 つだけ");
            String vk = Glsl.vk450(src);
            assertTrue(vk.startsWith("#version 450\n"), f.getName());
            assertEquals(1, vk.split("#version", -1).length - 1, f.getName() + ": VK 版の行は前置きの 1 つだけ");
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

    @Test
    void everyProgramHasValidStd140LayoutAndBuildTimeSpirvWithoutShadercAtRuntime() throws Exception {
        // runtime / test クラスパスに lwjgl-shaderc が混入していないこと（ビルド時専用）
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.lwjgl.util.shaderc.Shaderc"));

        ClassLoader cl = ShaderSourcesTest.class.getClassLoader();
        for (ShaderProgram p : ShaderPrograms.all()) {
            // std140 UBO レイアウト検証
            int[] offsets = p.std140Offsets();
            assertEquals(p.uniforms().size(), offsets.length, p.id());
            int prevEnd = 0;
            for (int i = 0; i < offsets.length; i++) {
                ShaderProgram.Uniform u = p.uniforms().get(i);
                assertEquals(0, offsets[i] % u.type().std140Align, p.id() + "." + u.name() + " align");
                assertTrue(offsets[i] >= prevEnd, p.id() + "." + u.name() + " non-overlap");
                prevEnd = offsets[i] + u.bytes();
            }
            if (!p.uniforms().isEmpty()) {
                assertTrue(p.uniformBlockBytes() >= prevEnd, p.id() + " blockBytes");
                assertEquals(0, p.uniformBlockBytes() % 16, p.id() + " 16B padded");
            }

            // GL / VK 双方で PassParams と SPIR-V バイナリが揃っていること
            for (String stage : new String[]{"vert", "frag"}) {
                String body = stage.equals("vert") ? p.vertexBody() : p.fragmentBody();
                assertTrue(Glsl.gl150(body).contains("PassParams"), p.id() + "." + stage + " gl PassParams");
                assertTrue(Glsl.vk450(body).contains("PassParams"), p.id() + "." + stage + " vk PassParams");

                String spvPath = SPIRV_DIR + p.id() + "." + stage + ".spv";
                byte[] spv;
                try (InputStream in = cl.getResourceAsStream(spvPath)) {
                    assertNotNull(in, "SPIR-V リソースが見つからない: " + spvPath);
                    spv = in.readAllBytes();
                }
                assertTrue(spv.length >= 20 && (spv.length % 4) == 0, spvPath + " size=" + spv.length);
                ByteBuffer hdr = ByteBuffer.wrap(spv, 0, 20).order(ByteOrder.LITTLE_ENDIAN);
                assertEquals(0x07230203, hdr.getInt(0), spvPath + " magic");
                assertTrue(hdr.getInt(4) >= 0x00010000, spvPath + " version");
            }
        }
    }
}
