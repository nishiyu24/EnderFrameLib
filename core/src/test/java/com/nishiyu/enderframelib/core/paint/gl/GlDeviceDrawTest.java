package com.nishiyu.enderframelib.core.paint.gl;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuBuffer;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuDevice;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuFrame;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuPass;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuPipeline;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuTexture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** G1 段 2: {@link GlDevice} のパイプラインと描画（{@code tex} プログラム。画面と同じ位置の画素を範囲だけ重ねる）。 */
class GlDeviceDrawTest {
    private static LwjglGlRenderBackend host;
    private static String unavailable;

    @BeforeAll
    static void setUp() {
        try {
            host = new LwjglGlRenderBackend(8, 8);
            GLFW.glfwMakeContextCurrent(host.windowHandle());
            GL.createCapabilities();
        } catch (Throwable t) {
            unavailable = t.toString();
            if (host != null) {
                host.close();
                host = null;
            }
        }
    }

    @AfterAll
    static void tearDown() {
        if (host != null) {
            GLFW.glfwMakeContextCurrent(0);
            host.close();
        }
    }

    private static GlDevice open() {
        Assumptions.assumeTrue(unavailable == null, "OpenGL を使えない: " + unavailable);
        return new GlDevice();
    }

    private static ByteBuffer uniforms(float x0, float y0, float x1, float y1, float vw, float vh, float opacity, int flip) {
        ByteBuffer u = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder());
        u.putFloat(x0).putFloat(y0).putFloat(x1).putFloat(y1).putFloat(vw).putFloat(vh).putFloat(opacity).putInt(flip);
        u.flip();
        return u;
    }

    private static int px(ByteBuffer rgba, int w, int x, int y) {
        int o = (y * w + x) * 4;
        return ((rgba.get(o) & 0xFF) << 24) | ((rgba.get(o + 1) & 0xFF) << 16) | ((rgba.get(o + 2) & 0xFF) << 8) | (rgba.get(o + 3) & 0xFF);
    }

    private static ByteBuffer read(GpuDevice d, GpuTexture t, int w, int h) {
        GpuFrame f = d.beginFrame();
        ByteBuffer out = ByteBuffer.allocateDirect(w * h * 4);
        f.readback(t, new int[]{0, 0, w, h}, out);
        f.end();
        return out;
    }

    /** 8x8 のソース: 左上 (1,1) は赤、右下 (6,6) は青、他は緑。 */
    private static GpuTexture source(GpuDevice d) {
        GpuTexture src = d.createTexture(GpuDevice.TextureDesc.rgba8(8, 8, true));
        ByteBuffer px = ByteBuffer.allocateDirect(8 * 8 * 4);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int rgba = (x == 1 && y == 1) ? 0xFF0000FF : (x == 6 && y == 6) ? 0x0000FFFF : 0x00FF00FF;
                px.putInt(rgba);
            }
        }
        px.flip();
        GpuFrame f = d.beginFrame();
        f.upload(src, 0, 0, 8, 8, px);
        f.end();
        return src;
    }

    @Test
    void drawsTheSelectedRegionOfTheSourceAtTheSamePosition() {
        GlDevice d = open();
        GpuTexture src = source(d), dst = d.createTexture(GpuDevice.TextureDesc.rgba8(8, 8, true));
        GpuPipeline p = d.createPipeline(new GpuDevice.PipelineDesc("tex", true, false));
        GpuFrame f = d.beginFrame();
        GpuPass pass = f.beginPass(dst, GpuDevice.LoadOp.CLEAR, 0x00000000, null);
        pass.bind(p, new GpuPass.Bindings(new GpuBuffer[0], new GpuTexture[]{src}, uniforms(1, 1, 7, 7, 8, 8, 1f, 0)));
        pass.drawInstanced(4, 1, 0);
        pass.end();
        f.end();
        ByteBuffer out = read(d, dst, 8, 8);
        assertEquals(0xFF0000FF, px(out, 8, 1, 1), "左上の赤が同じ位置（上から数える）に出る");
        assertEquals(0x0000FFFF, px(out, 8, 6, 6), "右下の青");
        assertEquals(0x00FF00FF, px(out, 8, 3, 4));
        assertEquals(0x00000000, px(out, 8, 0, 0), "範囲の外は描かない");
        assertEquals(0x00000000, px(out, 8, 7, 7));
        assertEquals(0x00000000, px(out, 8, 0, 3));
    }

    @Test
    void opacityScalesThePremultipliedColor() {
        GlDevice d = open();
        GpuTexture src = source(d), dst = d.createTexture(GpuDevice.TextureDesc.rgba8(8, 8, true));
        GpuPipeline p = d.createPipeline(new GpuDevice.PipelineDesc("tex", true, false));
        GpuFrame f = d.beginFrame();
        GpuPass pass = f.beginPass(dst, GpuDevice.LoadOp.CLEAR, 0x00000000, null);
        pass.bind(p, new GpuPass.Bindings(new GpuBuffer[0], new GpuTexture[]{src}, uniforms(0, 0, 8, 8, 8, 8, 0.5f, 0)));
        pass.drawInstanced(4, 1, 0);
        pass.end();
        f.end();
        int c = px(read(d, dst, 8, 8), 8, 1, 1);
        assertEquals(128, (c >>> 24) & 0xFF, 1, "R");
        assertEquals(0, (c >>> 16) & 0xFF);
        assertEquals(128, c & 0xFF, 1, "A");
    }

    @Test
    void premultipliedBlendComposesOverWhatIsThere() {
        GlDevice d = open();
        GpuTexture src = source(d), dst = d.createTexture(GpuDevice.TextureDesc.rgba8(8, 8, true));
        GpuPipeline over = d.createPipeline(new GpuDevice.PipelineDesc("tex", true, false));
        GpuPipeline replace = d.createPipeline(new GpuDevice.PipelineDesc("tex", false, false));
        GpuFrame f = d.beginFrame();
        GpuPass pass = f.beginPass(dst, GpuDevice.LoadOp.CLEAR, 0xFF0000FF, null); // 不透明な青
        pass.bind(over, new GpuPass.Bindings(new GpuBuffer[0], new GpuTexture[]{src}, uniforms(0, 0, 8, 8, 8, 8, 0.5f, 0)));
        pass.drawInstanced(4, 1, 0);
        pass.end();
        f.end();
        int c = px(read(d, dst, 8, 8), 8, 3, 3); // 緑の半透明を青の上へ
        assertEquals(0, (c >>> 24) & 0xFF, 1, "R");
        assertEquals(128, (c >>> 16) & 0xFF, 1, "G = 0.5");
        assertEquals(128, (c >>> 8) & 0xFF, 1, "B = 1 - 0.5");
        assertEquals(255, c & 0xFF, "A");
        // 合成なし（置き換え）
        f = d.beginFrame();
        pass = f.beginPass(dst, GpuDevice.LoadOp.CLEAR, 0xFF0000FF, null);
        pass.bind(replace, new GpuPass.Bindings(new GpuBuffer[0], new GpuTexture[]{src}, uniforms(0, 0, 8, 8, 8, 8, 1f, 0)));
        pass.drawInstanced(4, 1, 0);
        pass.end();
        f.end();
        assertEquals(0x00FF00FF, px(read(d, dst, 8, 8), 8, 3, 3));
    }

    @Test
    void flipReadsTheSourceUpsideDown() {
        GlDevice d = open();
        GpuTexture src = source(d), dst = d.createTexture(GpuDevice.TextureDesc.rgba8(8, 8, true));
        GpuPipeline p = d.createPipeline(new GpuDevice.PipelineDesc("tex", true, false));
        GpuFrame f = d.beginFrame();
        GpuPass pass = f.beginPass(dst, GpuDevice.LoadOp.CLEAR, 0x00000000, null);
        pass.bind(p, new GpuPass.Bindings(new GpuBuffer[0], new GpuTexture[]{src}, uniforms(0, 0, 8, 8, 8, 8, 1f, 1)));
        pass.drawInstanced(4, 1, 0);
        pass.end();
        f.end();
        ByteBuffer out = read(d, dst, 8, 8);
        assertEquals(0xFF0000FF, px(out, 8, 1, 6), "赤は (1,1) から (1,6) へ（8 行の上下反転）");
        assertEquals(0x0000FFFF, px(out, 8, 6, 1));
    }

    @Test
    void drawingRestoresTheHostGlState() {
        GlDevice d = open();
        GpuTexture src = source(d), dst = d.createTexture(GpuDevice.TextureDesc.rgba8(8, 8, true));
        GpuPipeline p = d.createPipeline(new GpuDevice.PipelineDesc("tex", true, false));
        int hostProgram = GL20.glCreateProgram();
        int hv = GL20.glCreateShader(GL20.GL_VERTEX_SHADER), hf = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(hv, "#version 150\nvoid main() { gl_Position = vec4(0.0); }\n");
        GL20.glShaderSource(hf, "#version 150\nout vec4 o;\nvoid main() { o = vec4(1.0); }\n");
        GL20.glCompileShader(hv);
        GL20.glCompileShader(hf);
        GL20.glAttachShader(hostProgram, hv);
        GL20.glAttachShader(hostProgram, hf);
        GL20.glLinkProgram(hostProgram);
        assertNotEquals(0, GL20.glGetProgrami(hostProgram, GL20.GL_LINK_STATUS), "ホスト側の作業用プログラム");
        int hostVao = GL30.glGenVertexArrays();
        int hostTex = GL11.glGenTextures();
        GL20.glUseProgram(hostProgram);
        GL30.glBindVertexArray(hostVao);
        GL13.glActiveTexture(GL13.GL_TEXTURE3);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, hostTex);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE3);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        GpuFrame f = d.beginFrame();
        GpuPass pass = f.beginPass(dst, GpuDevice.LoadOp.CLEAR, 0, null);
        pass.bind(p, new GpuPass.Bindings(new GpuBuffer[0], new GpuTexture[]{src}, uniforms(0, 0, 8, 8, 8, 8, 1f, 0)));
        pass.drawInstanced(4, 1, 0);
        pass.end();
        f.end();
        assertEquals(hostProgram, GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM));
        assertEquals(hostVao, GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING));
        assertEquals(GL13.GL_TEXTURE3, GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE));
        assertEquals(hostTex, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D));
        assertFalse(GL11.glIsEnabled(GL11.GL_BLEND));
        assertEquals(GL11.GL_SRC_ALPHA, GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_SRC_RGB));
        assertEquals(GL11.GL_ONE, GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_DST_RGB));
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        assertEquals(0, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), "ユニット 0 に結んだ作業用テクスチャは戻っている");
        GL20.glUseProgram(0);
        GL30.glBindVertexArray(0);
    }

    @Test
    void baseInstanceIsRefusedOnGl32() {
        GlDevice d = open();
        GpuTexture src = source(d), dst = d.createTexture(GpuDevice.TextureDesc.rgba8(8, 8, true));
        GpuPipeline p = d.createPipeline(new GpuDevice.PipelineDesc("tex", true, false));
        GpuFrame f = d.beginFrame();
        GpuPass pass = f.beginPass(dst, GpuDevice.LoadOp.LOAD, 0, null);
        pass.bind(p, new GpuPass.Bindings(new GpuBuffer[0], new GpuTexture[]{src}, uniforms(0, 0, 8, 8, 8, 8, 1f, 0)));
        assertThrows(UnsupportedOperationException.class, () -> pass.drawInstanced(4, 1, 5));
        pass.end();
        f.end();
    }
}
