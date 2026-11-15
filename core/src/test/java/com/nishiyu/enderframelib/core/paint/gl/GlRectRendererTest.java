package com.nishiyu.enderframelib.core.paint.gl;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.kernel.gpu.GpuSceneSync;
import com.nishiyu.enderframelib.core.kernel.gpu.PropertyTrees;
import com.nishiyu.enderframelib.core.kernel.gpu.SimdTileSoftwareRenderer;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuDevice;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuFrame;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuPass;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuTexture;
import com.nishiyu.enderframelib.core.paint.gpu.GpuTreeStore;
import com.nishiyu.enderframelib.core.paint.gpu.RectRenderer;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

/**
 * 新経路（{@link GpuTreeStore} → {@link RectRenderer}）で描いた画素を、CPU の {@link SimdTileSoftwareRenderer} と突き合わせる。
 * 表示項目 → {@link GpuSceneSync} → GPU の端から端までを通す。GL を使えない環境ではスキップ。
 */
class GlRectRendererTest {
    private static final int W = 200, H = 150;
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
            if (host != null) { host.close(); host = null; }
        }
    }

    @AfterAll
    static void tearDown() {
        if (host != null) host.close();
    }

    private static void requireGl() { Assumptions.assumeTrue(host != null, "OpenGL を使えない: " + unavailable); }

    private static final class GpuEnv implements AutoCloseable {
        final GlDevice dev;
        final GpuTreeStore st;
        final RectRenderer rr;
        final GpuTexture target;
        final GpuTexture dummy;

        GpuEnv(boolean persistent) {
            if (persistent) Assumptions.assumeTrue(GlDevice.supportsPersistentMapping(), "永続マップ非対応");
            this.dev = new GlDevice(persistent, 3);
            this.st = new GpuTreeStore(dev, 256, 16, 256, 256);
            this.rr = new RectRenderer(dev);
            this.target = dev.createTexture(GpuDevice.TextureDesc.rgba8(W, H, true));
            this.dummy = dev.createTexture(GpuDevice.TextureDesc.rgba8(1, 1, false));
        }

        @Override
        public void close() {
            dummy.close();
            target.close();
            rr.close();
            st.close();
        }
    }

    /** 新経路で描いて、プリマルチプライド ARGB の画素を読み戻す。 */
    private static int[] drawGpu(GpuEnv e, int count, float frameMs) {
        e.st.flush();
        GpuFrame f = e.dev.beginFrame();
        GpuPass p = f.beginPass(e.target, GpuDevice.LoadOp.CLEAR, 0, null);
        e.rr.draw(p, e.st, e.dummy, e.dummy, 0, count, frameMs, W, H, true);
        p.end();
        ByteBuffer rgba = ByteBuffer.allocateDirect(W * H * 4);
        f.readback(e.target, new int[]{0, 0, W, H}, rgba);
        f.end();
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        int[] out = new int[W * H];
        for (int i = 0; i < out.length; i++) {
            int r = rgba.get(i * 4) & 0xFF;
            int g = rgba.get(i * 4 + 1) & 0xFF;
            int b = rgba.get(i * 4 + 2) & 0xFF;
            int a = rgba.get(i * 4 + 3) & 0xFF;
            out[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
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
        try (GpuEnv e = new GpuEnv(persistent)) {
            GpuSceneSync sync = new GpuSceneSync(e.st.trees);
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
            assertClose(cpu(rects), drawGpu(e, n, 0f), 2, (persistent ? "persistent" : "subdata") + " 角丸矩形 70 個");
        }
    }

    @Test void roundedRectsMatchCpuRendererPersistent() { randomScene(true); }
    @Test void roundedRectsMatchCpuRendererSubData() { randomScene(false); }

    @Test
    void translateOpacityAndOrderGoThroughTheGpuTrees() {
        requireGl();
        try (GpuEnv e = new GpuEnv(true)) {
            GpuSceneSync sync = new GpuSceneSync(e.st.trees);
            List<DisplayItem> items = new ArrayList<>(List.of(
                    item("a", 20, 20, 80, 60, 0xFFFF0000, 6, 1f, 0, 0, 1, 1, 0),
                    item("b", 50, 40, 80, 60, 0xFF0000FF, 0, 0.5f, 25, 10, 1, 1, 0)));
            int n = sync.sync(items);
            List<int[]> expect = List.of(new int[]{20, 20, 80, 60, 0xFFFF0000, 12, 1000}, new int[]{75, 50, 80, 60, 0xFF0000FF, 0, 500});
            assertClose(cpu(expect), drawGpu(e, n, 0f), 2, "移動・不透明度");

            // 並べ替え: 描画順だけが変わる（レコードは書き換えず order 表だけ）
            items = new ArrayList<>(List.of(items.get(1), items.get(0)));
            n = sync.sync(items);
            assertClose(cpu(List.of(expect.get(1), expect.get(0))), drawGpu(e, n, 0f), 2, "描画順");

            // 消えた項目は描かれない
            n = sync.sync(new ArrayList<>(List.of(items.get(1))));
            assertClose(cpu(List.of(expect.get(0))), drawGpu(e, n, 0f), 2, "削除");
        }
    }

    @Test
    void samplerUnitsAreSetOnceAndRepeatedDrawsStayIdentical() {
        requireGl();
        try (GpuEnv e = new GpuEnv(true)) {
            GpuSceneSync sync = new GpuSceneSync(e.st.trees);
            int n = sync.sync(new ArrayList<>(List.of(
                    item("a", 20, 20, 80, 60, 0xFFFF0000, 6, 1f, 0, 0, 1, 1, 0),
                    item("b", 50, 40, 80, 60, 0xFF0000FF, 0, 0.5f, 25, 10, 1, 1, 0))));
            int[] first = drawGpu(e, n, 0f);
            assertEquals(14, e.dev.samplerUniformSets(e.rr.pipeline()), "サンプラーは最初の描画で 14 個");
            for (int i = 0; i < 5; i++) assertArrayEquals(first, drawGpu(e, n, 0f), "描画 " + i);
            assertEquals(14, e.dev.samplerUniformSets(e.rr.pipeline()), "重ねて描いても設定し直さない");
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        }
    }

    @Test
    void scaleAndRotationPivotAroundTheCenter() {
        requireGl();
        try (GpuEnv e = new GpuEnv(true)) {
            GpuSceneSync sync = new GpuSceneSync(e.st.trees);
            // 20x10 を (40,30) に置き 2 倍: 中心 (50,35) の 40x20 → x 30..70, y 25..45
            int n = sync.sync(new ArrayList<>(List.of(item("s", 40, 30, 20, 10, 0xFF00AA00, 0, 1f, 0, 0, 2, 2, 0))));
            assertClose(cpu(List.of(new int[]{30, 25, 40, 20, 0xFF00AA00, 0, 1000})), drawGpu(e, n, 0f), 2, "拡大");
            // 40x10 を (30,30) に置き 90°: 中心 (50,35) の 10x40 → x 45..55, y 15..55
            n = sync.sync(new ArrayList<>(List.of(item("s", 30, 30, 40, 10, 0xFF00AA00, 0, 1f, 0, 0, 1, 1, (float) (Math.PI / 2)))));
            assertClose(cpu(List.of(new int[]{45, 15, 10, 40, 0xFF00AA00, 0, 1000})), drawGpu(e, n, 0f), 2, "90 度回転");
        }
    }

    @Test
    void animationRunsOnTheGpuWithoutAnyCpuWork() {
        requireGl();
        try (GpuEnv e = new GpuEnv(true)) {
            GpuSceneSync sync = new GpuSceneSync(e.st.trees);
            PropertyTrees t = e.st.trees;
            DisplayItem it = item("anim", 10, 20, 40, 30, 0xFF3366CC, 0, 0.5f, 80, 0, 1, 1, 0);
            int n = sync.sync(new ArrayList<>(List.of(it)));
            int prim = sync.primitiveSlot(it.key());
            int tr = sync.transformSlot(prim), ef = sync.effectSlot(prim);
            // 変換: 中心 (30,35) から目標 +80 へ 100 ms で線形。不透明度: 0 から 0.5 へ同じ時刻で
            t.startTransformAnimation(tr, 30, 35, 1, 1, 0, 0f, 100f, PropertyTrees.EASE_LINEAR);
            int timing = t.addTransform(PropertyTrees.NONE, 0, 0, 1, 1, 0);
            t.startTransformAnimation(timing, 0, 0, 1, 1, 0, 0f, 100f, PropertyTrees.EASE_LINEAR);
            t.startOpacityAnimation(ef, 0f, timing);
            e.st.flush();
            for (int frame : new int[]{0, 25, 50, 100, 400}) {
                float k = Math.min(1f, frame / 100f);
                int x = 10 + Math.round(80 * k);
                int[] gpu = drawGpu(e, n, frame);
                // 位置が整数にならない時刻は、中心を挟む 2 通りのどちらかに丸まる範囲で比べる（25, 50 は整数）
                assertClose(cpu(List.of(new int[]{x, 20, 40, 30, 0xFF3366CC, 0, Math.round(500 * k)})), gpu, 2, "frame " + frame);
            }
            int sent = e.st.flush();
            assertEquals(0, sent, "フレームを進めても CPU は何も書き換えない");
        }
    }

    @Test
    void sameSceneTwiceUploadsNothing() {
        requireGl();
        try (GpuEnv e = new GpuEnv(false)) {
            GpuSceneSync sync = new GpuSceneSync(e.st.trees);
            List<DisplayItem> items = new ArrayList<>();
            for (int i = 0; i < 40; i++) items.add(item("q" + i, i * 4, i * 3, 30, 20, 0xFF000000 | i * 5000, 3, 1f, 0, 0, 1, 1, 0));
            int n = sync.sync(items);
            int[] first = drawGpu(e, n, 0f);
            n = sync.sync(items);
            assertEquals(0, e.st.flush(), "同じ内容の再同期は GPU へ 0 バイト");
            assertArrayEquals(first, drawGpu(e, n, 16f));
        }
    }

    /** draw 冒頭の状態保存（glIsEnabled ×1 + glGetInteger ×4）の実コストの測定。省く価値があるかの判断材料。 */
    @Test
    void measureBlendStateQueryCost() {
        requireGl();
        int n = 20_000;
        long sink = 0;
        for (int w = 0; w < 3; w++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                sink += GL11.glIsEnabled(GL11.GL_BLEND) ? 1 : 0;
                sink += GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_SRC_RGB) + GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_DST_RGB);
                sink += GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_SRC_ALPHA) + GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_DST_ALPHA);
            }
            double us = (System.nanoTime() - t0) / 1000.0 / n;
            System.out.println("[blend-state-query] " + us + " us/draw (sink=" + sink + ")");
        }
    }
}
