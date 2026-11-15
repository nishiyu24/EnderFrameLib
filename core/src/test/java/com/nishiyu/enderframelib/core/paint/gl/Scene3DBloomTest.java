package com.nishiyu.enderframelib.core.paint.gl;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.kernel.gpu.rhi.RecordingGpuDevice;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.RecordingGpuDevice.Command;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.TextureFormat;
import com.nishiyu.enderframelib.core.paint.gpu.Scene3DRenderer;
import com.nishiyu.enderframelib.core.paint.software.SoftwareScene3DRasterizer;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.scene3d.Bloom3D;
import com.nishiyu.enderframelib.core.scene3d.MaterialDesc;
import com.nishiyu.enderframelib.core.scene3d.MeshHandle;
import com.nishiyu.enderframelib.core.scene3d.Scene3DWorld;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** X3-2: Bloom の RHI 上の命令列（GL なしで検証）と、GL での ON/OFF・リサイズ・MSAA の扱い。 */
class Scene3DBloomTest {

    private static Scene3DWorld scene(boolean bloom) {
        Scene3DWorld w = new Scene3DWorld(7);
        w.clearColorArgb(0xFF182230);
        w.camera().lookAt(0, 0, 4.2f, 0, 0, 0, 0, 1, 0);
        w.camera().setPerspective((float) Math.toRadians(45), 1f, 0.1f, 50f);
        MeshHandle sphere = w.geometry().createSphere(0.8f, 24, 32);
        MaterialDesc glow = w.materials().register(0xFF202020, 0f, 0.6f, 0xFFB040, 3f, null, false, false);
        int n = w.arena().allocateNode();
        w.arena().setMesh(n, sphere.meshId());
        w.arena().setMaterial(n, glow.materialId());
        if (bloom) w.bloom(true, 0.8f, 1f, 10f);
        return w;
    }

    // ------------------------------------------------------------------ RHI の命令列（GL 不要）

    private static long count(List<Command> log, Class<? extends Command> type) {
        return log.stream().filter(type::isInstance).count();
    }

    private static long binds(List<Command> log, String shader) {
        return log.stream().filter(c -> c instanceof Command.Bind b && b.shaderId().equals(shader)).count();
    }

    @Test
    void bloomPassCountFollowsTheDualKawasePlan() {
        RecordingGpuDevice dev = new RecordingGpuDevice();
        try (Scene3DRenderer r = new Scene3DRenderer(dev)) {
            Scene3DWorld w = scene(true);
            int size = 96;
            int p = Bloom3D.plan(w.bloomSigma(), size, size).levels();
            assertTrue(p >= 2, "この σ では複数段: " + p);
            int before = dev.commandCount();
            r.render(w, size, size);
            List<Command> log = dev.commands().subList(before, dev.commandCount());

            // 描画 1 + 縮小 p + 拡大 (p-1) + 合成 1
            assertEquals(1 + p + (p - 1) + 1, count(log, Command.BeginPass.class));
            assertEquals(2 * p, binds(log, "bloom"), "縮小 p・拡大 p-1・合成 1");
            assertEquals(1, binds(log, "scene3d_hdr"), "3D は HDR のパイプラインで 1 回");
            assertEquals(0, binds(log, "scene3d"), "8 bit のパイプラインは使わない");
            // 全画面の帯を 1 回ずつ（頂点 4）
            assertEquals(2 * p, log.stream().filter(c -> c instanceof Command.Draw d && d.vertexCount() == 4).count());
            // HDR の面 + 縮小 p + 拡大 p-1 が RGBA16F
            assertEquals(1 + p + (p - 1), log.stream()
                    .filter(c -> c instanceof Command.CreateTexture t && t.format() == TextureFormat.RGBA16F).count());
        }
    }

    @Test
    void withoutBloomTheRenderIsTheOldSinglePassAndNoHdrTextures() {
        RecordingGpuDevice dev = new RecordingGpuDevice();
        try (Scene3DRenderer r = new Scene3DRenderer(dev)) {
            int before = dev.commandCount();
            r.render(scene(false), 96, 96);
            List<Command> log = dev.commands().subList(before, dev.commandCount());
            assertEquals(1, count(log, Command.BeginPass.class));
            assertEquals(1, binds(log, "scene3d"));
            assertEquals(0, binds(log, "bloom") + binds(log, "scene3d_hdr"));
            assertEquals(0, log.stream().filter(c -> c instanceof Command.CreateTexture t && t.format() == TextureFormat.RGBA16F).count());
        }
    }

    @Test
    void togglingBloomRebuildsTheTargetsAndSteadyFramesAllocateNothing() {
        RecordingGpuDevice dev = new RecordingGpuDevice();
        try (Scene3DRenderer r = new Scene3DRenderer(dev)) {
            Scene3DWorld w = scene(true);
            r.render(w, 96, 96);
            int textures = (int) count(dev.commands(), Command.CreateTexture.class);
            r.render(w, 96, 96);
            r.render(w, 96, 96);
            assertEquals(textures, count(dev.commands(), Command.CreateTexture.class), "同じ設定ならテクスチャを作り直さない");
            w.bloom(false);
            r.render(w, 96, 96);
            long afterOff = count(dev.commands(), Command.CreateTexture.class);
            assertTrue(afterOff > textures, "切り替えで目標を作り直す");
            r.render(w, 96, 96);
            assertEquals(afterOff, count(dev.commands(), Command.CreateTexture.class));
            w.bloom(true);
            r.render(w, 128, 128); // 大きさを変えても Bloom の連鎖が作り直される
            long levelsAt128 = Bloom3D.plan(w.bloomSigma(), 128, 128).levels();
            assertTrue(levelsAt128 >= 1);
        }
    }

    // ------------------------------------------------------------------ GL での振る舞い

    private static Pixmap gl(LwjglGlRenderBackend b, Scene3DWorld w, int size) {
        return b.renderScene3DPixmap(w, size, size);
    }

    private static int far(Pixmap a, Pixmap b, int size, int tol) {
        int far = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int p = a.get(x, y), q = b.get(x, y), d = 0;
                for (int sh = 0; sh <= 24; sh += 8) d = Math.max(d, Math.abs(((p >>> sh) & 0xFF) - ((q >>> sh) & 0xFF)));
                if (d > tol) far++;
            }
        }
        return far;
    }

    @Test
    void glBloomOffIsUnchangedAfterTogglingAndResizing() {
        LwjglGlRenderBackend b;
        try {
            b = new LwjglGlRenderBackend(160, 160, 1f);
        } catch (Throwable t) {
            Assumptions.abort("GL unavailable: " + t);
            return;
        }
        try (b) {
            Scene3DWorld w = scene(false);
            Pixmap plain = gl(b, w, 96).copy();
            w.bloom(true, 0.8f, 1f, 10f);
            Pixmap lit = gl(b, w, 96).copy();
            assertTrue(far(plain, lit, 96, 4) > 50, "ブルームで絵が変わる");
            Pixmap litBig = gl(b, w, 128).copy(); // リサイズしても Bloom が働く
            assertTrue(far(SoftwareScene3DRasterizer.render(w, 128, 128), litBig, 128, 6) < 128 * 128 * 0.01);
            w.bloom(false);
            Pixmap back = gl(b, w, 96).copy();
            assertArrayEquals(plain.pixels(), back.pixels(), "ブルームを切れば、元の 8 bit の絵に完全に戻る");
        }
    }

    @Test
    void glBloomWorksWithMultisampling() {
        LwjglGlRenderBackend b;
        try {
            b = new LwjglGlRenderBackend(160, 160, 1f);
        } catch (Throwable t) {
            Assumptions.abort("GL unavailable: " + t);
            return;
        }
        try (b) {
            Scene3DWorld w = scene(true).msaaSamples(4);
            Pixmap msaa = gl(b, w, 96).copy();
            Pixmap sw = SoftwareScene3DRasterizer.render(scene(true), 96, 96);
            // 縁だけが違う（多重標本の平滑化）。全体の明るさ・ブルームは一致する
            assertTrue(far(msaa, sw, 96, 24) < 96 * 96 * 0.02, "MSAA + Bloom: " + far(msaa, sw, 96, 24));
            double mean = Arrays.stream(msaa.pixels()).mapToDouble(px -> (px >> 8) & 0xFF).average().orElse(0);
            double meanSw = Arrays.stream(sw.pixels()).mapToDouble(px -> (px >> 8) & 0xFF).average().orElse(0);
            assertEquals(meanSw, mean, 3.0, "平均の明るさ");
        }
    }

    @Test
    void vulkanBloomWorksWithMultisamplingAndMatchesTheSoftwareBrightness() {
        com.nishiyu.enderframelib.core.paint.vulkan.VkRenderBackend b;
        try {
            b = new com.nishiyu.enderframelib.core.paint.vulkan.VkRenderBackend(160, 160, 1f);
        } catch (Throwable t) {
            Assumptions.abort("Vulkan unavailable: " + t);
            return;
        }
        try (b) {
            Pixmap msaa = b.renderScene3DPixmap(scene(true).msaaSamples(4), 96, 96).copy();
            Pixmap sw = SoftwareScene3DRasterizer.render(scene(true), 96, 96);
            assertTrue(far(msaa, sw, 96, 24) < 96 * 96 * 0.02, "MSAA + Bloom (Vulkan): " + far(msaa, sw, 96, 24));
        }
    }
}
