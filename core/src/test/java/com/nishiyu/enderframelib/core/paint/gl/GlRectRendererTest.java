package com.nishiyu.enderframelib.core.paint.gl;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.kernel.gpu.GpuSceneSync;
import com.nishiyu.enderframelib.core.kernel.gpu.PropertyTrees;
import com.nishiyu.enderframelib.core.kernel.gpu.SimdTileSoftwareRenderer;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

/**
 * 新経路（{@link GlTreeStore} → {@link GlRectRenderer}）で描いた画素を、CPU の {@link SimdTileSoftwareRenderer} と突き合わせる。
 * 表示項目 → {@link GpuSceneSync} → GPU の端から端までを通す。GL を使えない環境ではスキップ。
 */
class GlRectRendererTest {
    private static final int W = 200, H = 150;
    private static LwjglGlRenderBackend host;
    private static String unavailable;
    private static int vao, fbo, tex;

    @BeforeAll
    static void setUp() {
        try {
            host = new LwjglGlRenderBackend(8, 8);
            GLFW.glfwMakeContextCurrent(host.windowHandle());
            GL.createCapabilities();
            vao = GL30.glGenVertexArrays();
            GL30.glBindVertexArray(vao);
            tex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, W, H, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            fbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
            assertEquals(GL30.GL_FRAMEBUFFER_COMPLETE, GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER));
        } catch (Throwable t) {
            unavailable = t.toString();
            if (host != null) { host.close(); host = null; }
        }
    }

    @AfterAll
    static void tearDown() {
        if (host != null) host.close();
    }

    private static void requireGl() { Assumptions.assumeTrue(host != null, "OpenGL を使えない: " + unavailable); }

    private static GlTreeStore store(boolean persistent) {
        if (persistent) Assumptions.assumeTrue(GlVramDevice.supportsPersistentMapping(), "永続マップ非対応");
        return new GlTreeStore(256, 16, 256, 256, persistent);
    }

    /** 新経路で描いて、プリマルチプライド ARGB の画素を読み戻す。 */
    private static int[] drawGpu(GlRectRenderer rr, GlTreeStore st, int count, float frameMs) {
        st.beginFrame();
        st.flush();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL11.glViewport(0, 0, W, H);
        GL11.glClearColor(0, 0, 0, 0);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        rr.draw(st, count, frameMs, W, H);
        st.endFrame();
        IntBuffer px = BufferUtils.createIntBuffer(W * H);
        GL11.glReadPixels(0, 0, W, H, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, px);
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        int[] out = new int[W * H];
        px.get(out);
        return out;
    }

    private static void assertClose(int[] expect, int[] actual, int tol, String msg) {
        int bad = 0;
        String first = null;
        for (int i = 0; i < expect.length; i++) {
            for (int sh = 0; sh < 32; sh += 8) {
                int a = (expect[i] >>> sh) & 0xFF, b = (actual[i] >>> sh) & 0xFF;
                if (Math.abs(a - b) > tol) { bad++; if (first == null) first = "画素(" + i % W + "," + i / W + ") ch" + sh + " CPU=" + a + " GPU=" + b; }
            }
        }
        assertEquals(0, bad, msg + ": " + first);
    }

    private static DisplayItem item(String id, int x, int y, int w, int h, int argb, int radius, float opacity,
                                    float tx, float ty, float sx, float sy, float rot) {
        return new DisplayItem(id, id, ElementType.RECT, x, y, w, h, null, 0, argb, null, radius, opacity,
                tx, ty, sx, sy, rot, 0, null, null, null);
    }

    private static int[] cpu(List<int[]> rects) { // x y w h argb radius*2 opacity*1000
        SimdTileSoftwareRenderer c = new SimdTileSoftwareRenderer(W, H);
        for (int[] r : rects) c.fillRoundRect(r[0], r[1], r[2], r[3], r[5] / 2f, r[4], r[6] / 1000f);
        c.render();
        return c.pixels();
    }

    private void randomScene(boolean persistent) {
        requireGl();
        try (GlTreeStore st = store(persistent); GlRectRenderer rr = new GlRectRenderer()) {
            GpuSceneSync sync = new GpuSceneSync(st.trees);
            Random r = new Random(42);
            List<DisplayItem> items = new ArrayList<>();
            List<int[]> rects = new ArrayList<>();
            rects.clear();
            items.clear();
            for (int i = 0; i < 70; i++) {
                int x = r.nextInt(W + 20) - 10, y = r.nextInt(H + 20) - 10, w = 4 + r.nextInt(90), h = 4 + r.nextInt(70);
                int rad = r.nextInt(3) == 0 ? 0 : r.nextInt(Math.min(w, h) / 2 + 1);
                int argb = r.nextInt() | (r.nextInt(3) == 0 ? 0xFF000000 : 0);
                items.add(item("r" + i, x, y, w, h, argb, rad, 1f, 0, 0, 1, 1, 0));
                rects.add(new int[]{x, y, w, h, argb, rad * 2, 1000});
            }
            int n = sync.sync(items);
            assertEquals(70, n);
            assertClose(cpu(rects), drawGpu(rr, st, n, 0f), 2, (persistent ? "persistent" : "subdata") + " 角丸矩形 70 個");
        }
    }

    @Test void roundedRectsMatchCpuRendererPersistent() { randomScene(true); }
    @Test void roundedRectsMatchCpuRendererSubData() { randomScene(false); }

    @Test
    void translateOpacityAndOrderGoThroughTheGpuTrees() {
        requireGl();
        try (GlTreeStore st = store(true); GlRectRenderer rr = new GlRectRenderer()) {
            GpuSceneSync sync = new GpuSceneSync(st.trees);
            List<DisplayItem> items = new ArrayList<>(List.of(
                    item("a", 20, 20, 80, 60, 0xFFFF0000, 6, 1f, 0, 0, 1, 1, 0),
                    item("b", 50, 40, 80, 60, 0xFF0000FF, 0, 0.5f, 25, 10, 1, 1, 0)));
            int n = sync.sync(items);
            List<int[]> expect = List.of(new int[]{20, 20, 80, 60, 0xFFFF0000, 12, 1000}, new int[]{75, 50, 80, 60, 0xFF0000FF, 0, 500});
            assertClose(cpu(expect), drawGpu(rr, st, n, 0f), 2, "移動・不透明度");

            // 並べ替え: 描画順だけが変わる（レコードは書き換えず order 表だけ）
            items = new ArrayList<>(List.of(items.get(1), items.get(0)));
            n = sync.sync(items);
            assertClose(cpu(List.of(expect.get(1), expect.get(0))), drawGpu(rr, st, n, 0f), 2, "描画順");

            // 消えた項目は描かれない
            n = sync.sync(new ArrayList<>(List.of(items.get(1))));
            assertClose(cpu(List.of(expect.get(0))), drawGpu(rr, st, n, 0f), 2, "削除");
        }
    }

    @Test
    void samplerUnitsAreSetOnceAndRepeatedDrawsStayIdentical() {
        requireGl();
        try (GlTreeStore st = store(true); GlRectRenderer rr = new GlRectRenderer()) {
            GpuSceneSync sync = new GpuSceneSync(st.trees);
            int n = sync.sync(new ArrayList<>(List.of(
                    item("a", 20, 20, 80, 60, 0xFFFF0000, 6, 1f, 0, 0, 1, 1, 0),
                    item("b", 50, 40, 80, 60, 0xFF0000FF, 0, 0.5f, 25, 10, 1, 1, 0))));
            int[] first = drawGpu(rr, st, n, 0f);
            assertEquals(14, rr.samplerSetCount(), "サンプラーは最初の描画で 14 個");
            for (int i = 0; i < 5; i++) assertArrayEquals(first, drawGpu(rr, st, n, 0f), "描画 " + i);
            assertEquals(14, rr.samplerSetCount(), "重ねて描いても設定し直さない");
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        }
    }

    @Test
    void scaleAndRotationPivotAroundTheCenter() {
        requireGl();
        try (GlTreeStore st = store(true); GlRectRenderer rr = new GlRectRenderer()) {
            GpuSceneSync sync = new GpuSceneSync(st.trees);
            // 20x10 を (40,30) に置き 2 倍: 中心 (50,35) の 40x20 → x 30..70, y 25..45
            int n = sync.sync(new ArrayList<>(List.of(item("s", 40, 30, 20, 10, 0xFF00AA00, 0, 1f, 0, 0, 2, 2, 0))));
            assertClose(cpu(List.of(new int[]{30, 25, 40, 20, 0xFF00AA00, 0, 1000})), drawGpu(rr, st, n, 0f), 2, "拡大");
            // 40x10 を (30,30) に置き 90°: 中心 (50,35) の 10x40 → x 45..55, y 15..55
            n = sync.sync(new ArrayList<>(List.of(item("s", 30, 30, 40, 10, 0xFF00AA00, 0, 1f, 0, 0, 1, 1, (float) (Math.PI / 2)))));
            assertClose(cpu(List.of(new int[]{45, 15, 10, 40, 0xFF00AA00, 0, 1000})), drawGpu(rr, st, n, 0f), 2, "90 度回転");
        }
    }

    @Test
    void animationRunsOnTheGpuWithoutAnyCpuWork() {
        requireGl();
        try (GlTreeStore st = store(true); GlRectRenderer rr = new GlRectRenderer()) {
            GpuSceneSync sync = new GpuSceneSync(st.trees);
            PropertyTrees t = st.trees;
            DisplayItem it = item("anim", 10, 20, 40, 30, 0xFF3366CC, 0, 0.5f, 80, 0, 1, 1, 0);
            int n = sync.sync(new ArrayList<>(List.of(it)));
            int prim = sync.primitiveSlot(it.key());
            int tr = sync.transformSlot(prim), ef = sync.effectSlot(prim);
            // 変換: 中心 (30,35) から目標 +80 へ 100 ms で線形。不透明度: 0 から 0.5 へ同じ時刻で
            t.startTransformAnimation(tr, 30, 35, 1, 1, 0, 0f, 100f, PropertyTrees.EASE_LINEAR);
            int timing = t.addTransform(PropertyTrees.NONE, 0, 0, 1, 1, 0);
            t.startTransformAnimation(timing, 0, 0, 1, 1, 0, 0f, 100f, PropertyTrees.EASE_LINEAR);
            t.startOpacityAnimation(ef, 0f, timing);
            st.flush();
            for (int frame : new int[]{0, 25, 50, 100, 400}) {
                float k = Math.min(1f, frame / 100f);
                int x = 10 + Math.round(80 * k);
                int[] gpu = drawGpu(rr, st, n, frame);
                // 位置が整数にならない時刻は、中心を挟む 2 通りのどちらかに丸まる範囲で比べる（25, 50 は整数）
                assertClose(cpu(List.of(new int[]{x, 20, 40, 30, 0xFF3366CC, 0, Math.round(500 * k)})), gpu, 2, "frame " + frame);
            }
            int sent = st.flush();
            assertEquals(0, sent, "フレームを進めても CPU は何も書き換えない");
        }
    }

    @Test
    void sameSceneTwiceUploadsNothing() {
        requireGl();
        try (GlTreeStore st = store(false); GlRectRenderer rr = new GlRectRenderer()) {
            GpuSceneSync sync = new GpuSceneSync(st.trees);
            List<DisplayItem> items = new ArrayList<>();
            for (int i = 0; i < 40; i++) items.add(item("q" + i, i * 4, i * 3, 30, 20, 0xFF000000 | i * 5000, 3, 1f, 0, 0, 1, 1, 0));
            int n = sync.sync(items);
            int[] first = drawGpu(rr, st, n, 0f);
            n = sync.sync(items);
            assertEquals(0, st.flush(), "同じ内容の再同期は GPU へ 0 バイト");
            assertArrayEquals(first, drawGpu(rr, st, n, 16f));
        }
    }
}
