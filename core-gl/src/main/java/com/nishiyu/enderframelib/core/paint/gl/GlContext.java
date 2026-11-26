package com.nishiyu.enderframelib.core.paint.gl;

import com.nishiyu.enderframelib.core.diag.PipelineStage;
import com.nishiyu.enderframelib.core.diag.StageProfiler;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuDevice;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuTexture;
import com.nishiyu.enderframelib.core.paint.gpu.GpuSurfaceHost;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.ARBTimerQuery;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;

/**
 * GLFW ウィンドウ／共有 GL コンテキストと {@link GlDevice} の所有・切替・表示（blit + swap）・
 * 共有フェンス・GPU タイマークエリだけを担う薄いプラットフォームアダプタ。
 * 描画コマンドの組み立ては一切持たず、{@link com.nishiyu.enderframelib.core.paint.gpu.GpuRenderBackend} へ委譲する。
 */
final class GlContext implements GpuSurfaceHost {
    private static int liveInstances = 0;
    private static GLFWErrorCallback errorCallback;

    private static final int GPU_QUERY_RING = 4;

    private long window;
    private final long shareWith;
    private final boolean hostManaged;
    private GLCapabilities myCaps;
    private GlDevice device;
    private int vao;
    private int blitFbo;

    private int enterDepth;
    private long prevContext;
    private GLCapabilities prevCaps;

    private int[] gpuQueries;
    private final boolean[] gpuQueryPending = new boolean[GPU_QUERY_RING];
    private int gpuQueryNext;
    private boolean gpuQueryOpen;
    private volatile double lastGpuMs = -1;

    private long renderFence;
    private boolean closed;

    GlContext(int widthPx, int heightPx, float dpr, boolean visible, String title, long shareWith, boolean resizable) {
        this.shareWith = shareWith;
        this.hostManaged = shareWith != MemoryUtil.NULL;
        long outerContext = GLFW.glfwGetCurrentContext();
        GLCapabilities outerCaps = currentCaps();
        try {
            initContext(widthPx, heightPx, dpr, visible, title == null ? "EnderFrame" : title, resizable);
            this.myCaps = GL.getCapabilities();
            this.vao = GL30.glGenVertexArrays();
            GL30.glBindVertexArray(vao);
            this.device = new GlDevice();
            this.device.setExclusiveContext(true); // 自前のコンテキスト（ホストの文脈とは別。ホスト共有時も enter/exit で切り替える）
            if (hostManaged) GL11.glFinish();
        } catch (RuntimeException | Error e) {
            if (hostManaged) restoreContext(outerContext, outerCaps);
            close();
            throw e;
        }
        if (hostManaged) restoreContext(outerContext, outerCaps);
    }

    private void initContext(int widthPx, int heightPx, float dpr, boolean visible, String title, boolean resizable) {
        if (!hostManaged) synchronized (GlContext.class) {
            if (liveInstances == 0) {
                errorCallback = GLFWErrorCallback.createPrint(System.err);
                GLFW.glfwSetErrorCallback(errorCallback);
                if (!GLFW.glfwInit()) {
                    throw new IllegalStateException("GLFW を初期化できない（ディスプレイが無い環境かもしれない）");
                }
            }
            liveInstances++;
        }
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, visible ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, resizable ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        int winW = Math.max(1, Math.round(widthPx / dpr));
        int winH = Math.max(1, Math.round(heightPx / dpr));
        window = GLFW.glfwCreateWindow(winW, winH, title, MemoryUtil.NULL, shareWith);
        if (window == MemoryUtil.NULL) {
            throw new IllegalStateException("OpenGL 3.2 のコンテキストを作れない");
        }
        GLFW.glfwMakeContextCurrent(window);
        if (!hostManaged) GLFW.glfwSwapInterval(1);
        GL.createCapabilities();
    }

    private static GLCapabilities currentCaps() {
        try {
            return GL.getCapabilities();
        } catch (IllegalStateException none) {
            return null;
        }
    }

    private static void restoreContext(long context, GLCapabilities caps) {
        GLFW.glfwMakeContextCurrent(context);
        GL.setCapabilities(caps);
    }

    @Override
    public GpuDevice device() {
        return device;
    }

    @Override
    public void enter() {
        if (enterDepth++ > 0) return;
        prevContext = GLFW.glfwGetCurrentContext();
        prevCaps = prevContext != MemoryUtil.NULL ? currentCaps() : null;
        if (prevContext != window) GLFW.glfwMakeContextCurrent(window);
        if (myCaps != null) GL.setCapabilities(myCaps);
    }

    @Override
    public void exit() {
        if (--enterDepth > 0) return;
        enterDepth = 0;
        if (hostManaged) {
            restoreContext(prevContext, prevCaps);
        }
    }

    @Override
    public boolean enableGpuTiming() {
        if (closed || myCaps == null || !(myCaps.OpenGL33 || myCaps.GL_ARB_timer_query)) return false;
        if (gpuQueries != null) return true;
        enter();
        try {
            gpuQueries = new int[GPU_QUERY_RING];
            for (int i = 0; i < GPU_QUERY_RING; i++) gpuQueries[i] = GL15.glGenQueries();
        } finally {
            exit();
        }
        return true;
    }

    @Override
    public double lastGpuMillis() {
        return lastGpuMs;
    }

    private void gpuCollect() {
        for (int i = 0; i < GPU_QUERY_RING; i++) {
            if (!gpuQueryPending[i]) continue;
            if (GL15.glGetQueryObjecti(gpuQueries[i], GL15.GL_QUERY_RESULT_AVAILABLE) == GL11.GL_FALSE) continue;
            long ns = myCaps.OpenGL33 ? GL33.glGetQueryObjecti64(gpuQueries[i], GL15.GL_QUERY_RESULT)
                    : ARBTimerQuery.glGetQueryObjecti64(gpuQueries[i], GL15.GL_QUERY_RESULT);
            gpuQueryPending[i] = false;
            lastGpuMs = ns / 1e6;
            StageProfiler.record(PipelineStage.GPU, ns);
        }
    }

    @Override
    public void beginTiming() {
        if (gpuQueries == null) return;
        gpuCollect();
        if (gpuQueryPending[gpuQueryNext]) return;
        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, gpuQueries[gpuQueryNext]);
        gpuQueryOpen = true;
    }

    @Override
    public void endTiming() {
        if (!gpuQueryOpen) return;
        GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
        gpuQueryOpen = false;
        gpuQueryPending[gpuQueryNext] = true;
        gpuQueryNext = (gpuQueryNext + 1) % GPU_QUERY_RING;
    }

    @Override
    public void publishFrame() {
        if (!hostManaged) return;
        if (renderFence != 0) {
            GL32.glDeleteSync(renderFence);
            renderFence = 0;
        }
        if (myCaps == null || !myCaps.OpenGL32) {
            GL11.glFinish();
            return;
        }
        renderFence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        GL11.glFlush();
    }

    @Override
    public void waitForRender() {
        if (closed || renderFence == 0) return;
        GL32.glWaitSync(renderFence, 0, GL32.GL_TIMEOUT_IGNORED);
    }

    @Override
    public void waitForHost(long fence) {
        if (closed || fence == 0L || myCaps == null || !myCaps.OpenGL32) return;
        enter();
        try {
            GL32.glWaitSync(fence, 0, GL32.GL_TIMEOUT_IGNORED);
        } finally {
            exit();
        }
    }

    @Override
    public void finish() {
        if (closed) return;
        enter();
        try {
            GL11.glFinish();
        } finally {
            exit();
        }
    }

    @Override
    public void present(GpuTexture colorTarget, int width, int height) {
        if (hostManaged) throw new IllegalStateException("共有コンテキストには表示するウィンドウが無い");
        GLFW.glfwMakeContextCurrent(window);
        int[] w = new int[1], h = new int[1];
        GLFW.glfwGetFramebufferSize(window, w, h);
        if (blitFbo == 0) blitFbo = GL30.glGenFramebuffers();
        int texId = (int) device.nativeHandle(colorTarget);
        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, blitFbo);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texId, 0);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, w[0], h[0], GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        if (blend) GL11.glEnable(GL11.GL_BLEND);
        if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
        GLFW.glfwSwapBuffers(window);
    }

    @Override
    public boolean shouldClose() {
        return GLFW.glfwWindowShouldClose(window);
    }

    @Override
    public void pollEvents() {
        GLFW.glfwPollEvents();
    }

    @Override
    public long windowHandle() {
        return window;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (window != MemoryUtil.NULL) {
            enter();
            if (blitFbo != 0) {
                GL30.glDeleteFramebuffers(blitFbo);
                blitFbo = 0;
            }
            if (vao != 0) {
                GL30.glDeleteVertexArrays(vao);
                vao = 0;
            }
            if (gpuQueries != null) {
                for (int q : gpuQueries) GL15.glDeleteQueries(q);
                gpuQueries = null;
            }
            if (renderFence != 0) {
                GL32.glDeleteSync(renderFence);
                renderFence = 0;
            }
            if (device != null) {
                device.close();
                device = null;
            }
            if (hostManaged) GL11.glFinish();
            exit();
            if (!hostManaged) GLFW.glfwMakeContextCurrent(MemoryUtil.NULL);
            GLFW.glfwDestroyWindow(window);
            window = MemoryUtil.NULL;
        }
        if (!hostManaged) synchronized (GlContext.class) {
            if (liveInstances > 0 && --liveInstances == 0) {
                GLFW.glfwTerminate();
                if (errorCallback != null) {
                    GLFW.glfwSetErrorCallback(null);
                    errorCallback.free();
                    errorCallback = null;
                }
            }
        }
    }
}
