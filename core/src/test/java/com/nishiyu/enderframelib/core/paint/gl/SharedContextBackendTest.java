package com.nishiyu.enderframelib.core.paint.gl;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GLCapabilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ホスト（Minecraft など）のコンテキストと共有する {@link LwjglGlRenderBackend#sharedWith} の契約を確かめる。
 * ホストは「GLFW を自分で初期化し、自分のコンテキストを現在にしている」アプリを模した別の GL バックエンドで代用する。
 */
class SharedContextBackendTest {

    private static LwjglGlRenderBackend host;
    private static String unavailable;

    @BeforeAll
    static void probe() {
        try {
            host = new LwjglGlRenderBackend(8, 8);
        } catch (Throwable t) {
            unavailable = t.toString();
        }
    }

    @AfterAll
    static void cleanup() {
        if (host != null) host.close();
    }

    private static void requireGl() {
        Assumptions.assumeTrue(host != null, "OpenGL を使えない: " + unavailable);
    }

    private static final String HTML = "<html><body style='margin:0'>"
            + "<div style='width:8px;height:8px;background:#ff0000'></div>"
            + "<div style='width:8px;height:8px;background:#0000ff'></div></body></html>";

    /** ホストのコンテキストを現在にして、その状態（コンテキストと GLCapabilities）を返す。 */
    private static GLCapabilities makeHostCurrent() {
        GLFW.glfwMakeContextCurrent(host.windowHandle());
        GL.createCapabilities();
        return GL.getCapabilities();
    }

    @Test
    void rendersIntoATextureTheHostCanSampleAndRestoresHostState() {
        requireGl();
        GLCapabilities hostCaps = makeHostCurrent();
        LwjglGlRenderBackend shared = LwjglGlRenderBackend.sharedWith(host.windowHandle(), 8, 16, 1f);
        try {
            assertEquals(host.windowHandle(), GLFW.glfwGetCurrentContext(), "生成後もホストのコンテキストが現在");
            assertSame(hostCaps, GL.getCapabilities(), "生成後もホストの GLCapabilities");

            SoftwareRendering.renderHtml(HTML, 8, 16, 10_000L, shared);

            assertEquals(host.windowHandle(), GLFW.glfwGetCurrentContext(), "描画後もホストのコンテキストが現在");
            assertSame(hostCaps, GL.getCapabilities(), "描画後もホストの GLCapabilities");
            assertEquals(8, shared.textureWidth());
            assertEquals(16, shared.textureHeight());

            // ホストのコンテキストから共有テクスチャを直接読む（GL の向き＝下端が先頭）
            shared.waitForRender();
            int[] raw = new int[8 * 16];
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, shared.colorTexture());
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, raw);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            assertEquals(0xFF0000FF, raw[0 * 8 + 4], "下の行（GL の先頭）は青");
            assertEquals(0xFFFF0000, raw[15 * 8 + 4], "上の行は赤");

            // CPU への読み戻しも、向きを直した結果が同じ
            Pixmap p = shared.pixmap();
            assertEquals(0xFFFF0000, p.get(4, 0));
            assertEquals(0xFF0000FF, p.get(4, 15));
            assertEquals(host.windowHandle(), GLFW.glfwGetCurrentContext(), "読み戻し後もホストのコンテキスト");
        } finally {
            shared.close();
        }
        assertEquals(host.windowHandle(), GLFW.glfwGetCurrentContext(), "close 後もホストのコンテキストが現在");
        assertSame(hostCaps, GL.getCapabilities());
    }

    @Test
    void closingTheSharedBackendDoesNotTerminateGlfwOrBreakTheHost() {
        requireGl();
        makeHostCurrent();
        for (int i = 0; i < 3; i++) {
            LwjglGlRenderBackend b = LwjglGlRenderBackend.sharedWith(host.windowHandle(), 8, 8, 1f);
            SoftwareRendering.renderHtml(HTML, 8, 8, 10_000L, b);
            b.close();
        }
        // ホストはまだ描けて、読み戻せる
        SoftwareRendering.renderHtml(HTML, 8, 8, 10_000L, host);
        assertEquals(0xFFFF0000, host.pixmap().get(4, 0));
        assertNotEquals(0, host.colorTexture());
    }

    @Test
    void hostGlObjectsSurviveBesideTheSharedBackend() {
        requireGl();
        makeHostCurrent();
        int hostTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, hostTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 2, 2, 0, GL12.GL_BGRA,
                GL12.GL_UNSIGNED_INT_8_8_8_8_REV, new int[]{1, 2, 3, 4});
        int hostFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, hostFbo);
        LwjglGlRenderBackend b = LwjglGlRenderBackend.sharedWith(host.windowHandle(), 8, 8, 1f);
        try {
            SoftwareRendering.renderHtml(HTML, 8, 8, 10_000L, b);
            // ホスト側のバインドは、共有バックエンドの影響を受けない（コンテキストごとに状態が別）
            assertEquals(hostFbo, GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING));
            assertEquals(hostTex, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D));
        } finally {
            b.close();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GL30.glDeleteFramebuffers(hostFbo);
            GL11.glDeleteTextures(hostTex);
        }
        assertTrue(GLFW.glfwGetCurrentContext() == host.windowHandle());
    }

    @Test
    void waitForRenderIsSafeAndPixelsAreCompleteWithoutGlFinish() {
        requireGl();
        makeHostCurrent();
        LwjglGlRenderBackend b = LwjglGlRenderBackend.sharedWith(host.windowHandle(), 8, 16, 1f);
        try {
            b.waitForRender(); // 未描画でも無害
            for (int i = 0; i < 20; i++) { // 毎フレームのフェンス入れ替えでリークや不整合が出ない
                SoftwareRendering.renderHtml(HTML, 8, 16, 10_000L, b);
                b.waitForRender();
                int[] raw = new int[8 * 16];
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, b.colorTexture());
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, raw);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                assertEquals(0xFFFF0000, raw[15 * 8 + 4], "frame " + i);
                assertEquals(0xFF0000FF, raw[4], "frame " + i);
            }
        } finally {
            b.close();
        }
        b.waitForRender(); // close 後も無害
    }
}
