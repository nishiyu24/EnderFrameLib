package com.nishiyu.enderframelib.core.paint.gl;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuBuffer;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuDevice;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuDeviceContract;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuFrame;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuTexture;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** {@link GlDevice}（OpenGL 実装）に、RecordingGpuDevice と同じ振る舞いの検査を通す。GL を使えない環境ではスキップ。 */
class GlDeviceContractTest extends GpuDeviceContract {
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

    @Override
    protected GpuDevice open() {
        Assumptions.assumeTrue(unavailable == null, "OpenGL を使えない: " + unavailable);
        return new GlDevice();
    }

    @Test
    void bufferWritesReachTheGpuAtTheEndOfTheFrame() {
        GlDevice d = (GlDevice) open();
        GpuBuffer b = d.createBuffer(new GpuDevice.BufferDesc(64, GpuDevice.BufferUsage.RECORD));
        b.map().putInt(0, 0xCAFEBABE).putInt(60, 7);
        d.beginFrame().end();
        ByteBuffer back = org.lwjgl.BufferUtils.createByteBuffer(64).order(java.nio.ByteOrder.nativeOrder());
        d.readBuffer(b, back);
        assertEquals(0xCAFEBABE, back.getInt(0));
        assertEquals(7, back.getInt(60));
    }

    /** ホストの GL 状態を、フレームの前後で変えない（ホストのコンテキストへ相乗りしても壊さない）。 */
    @Test
    void glStateIsRestoredAtTheEndOfTheFrame() {
        GlDevice d = (GlDevice) open();
        GpuTexture t = d.createTexture(GpuDevice.TextureDesc.rgba8(8, 8, true));
        int fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL11.glViewport(1, 2, 3, 4);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glClearColor(0.1f, 0.2f, 0.3f, 0.4f);
        GpuFrame f = d.beginFrame();
        f.beginPass(t, GpuDevice.LoadOp.CLEAR, 0xFFFFFFFF, new int[]{1, 1, 2, 2}).end();
        f.end();
        assertEquals(fbo, GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING));
        int[] vp = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
        assertArrayEquals(new int[]{1, 2, 3, 4}, vp);
        assertFalse(GL11.glIsEnabled(GL11.GL_SCISSOR_TEST));
        float[] c = new float[4];
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, c);
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f, 0.4f}, c, 1e-6f);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL30.glDeleteFramebuffers(fbo);
    }

    @Test
    void depthIsNotYetSupported() {
        GlDevice d = (GlDevice) open();
        assertThrows(UnsupportedOperationException.class, () -> d.createPipeline(new GpuDevice.PipelineDesc("tex", true, true)));
        assertThrows(UnsupportedOperationException.class, () -> d.createTexture(new GpuDevice.TextureDesc(4, 4, GpuDevice.TextureFormat.DEPTH24, true, 1)));
    }
}
