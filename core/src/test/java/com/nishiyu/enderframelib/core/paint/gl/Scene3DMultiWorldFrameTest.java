package com.nishiyu.enderframelib.core.paint.gl;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.RenderBackend;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.paint.vulkan.VkRenderBackend;
import com.nishiyu.enderframelib.core.scene3d.MaterialDesc;
import com.nishiyu.enderframelib.core.scene3d.MeshHandle;
import com.nishiyu.enderframelib.core.scene3d.Scene3DWorld;
import java.util.function.Supplier;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 回帰: 1 フレームに複数の {@code <scene-3d>} を描くとき、先のワールドが後のワールドの instance データで描かれていた
 * （全ワールドが同じ instance バッファの範囲へ書き、描画の実行は書き込みの後になるため）。
 * ワールドごとに範囲を分け、途中で容量を広げても先のワールドの描画が壊れないことを、ソフトウェアとの一致で確かめる。
 */
class Scene3DMultiWorldFrameTest {

    private static final String HTML = "<body style=\"margin:0;display:flex\">"
            + "<scene-3d id=\"a\" style=\"width:100px;height:100px\"></scene-3d>"
            + "<scene-3d id=\"b\" style=\"width:100px;height:100px\"></scene-3d>"
            + "<scene-3d id=\"c\" style=\"width:100px;height:100px\"></scene-3d></body>";

    private static void fill(Scene3DWorld w, int color, int spheres) {
        w.clearColorArgb(0xFF101820);
        w.camera().lookAt(0, 0, 9, 0, 0, 0, 0, 1, 0);
        w.camera().setPerspective(0.8f, 1f, 0.1f, 40f);
        MeshHandle m = w.geometry().createSphere(0.5f, 12, 16);
        MaterialDesc mat = w.materials().register(color, 0.2f, 0.4f, 0, 0f, null, false, false);
        for (int i = 0; i < spheres; i++) {
            int n = w.arena().allocateNode();
            // 球を 20 × n の格子に散らす（数が多くても画面に収まる）
            w.arena().setPosition(n, (i % 20 - 9.5f) * 0.42f, ((i / 20) % 20 - 9.5f) * 0.42f, 0);
            w.arena().setScale(n, 0.35f, 0.35f, 0.35f);
            w.arena().setMesh(n, m.meshId());
            w.arena().setMaterial(n, mat.materialId());
        }
    }

    private static int[] render(Supplier<RenderBackend> make, int[] counts) {
        StandaloneView v = new StandaloneView(HTML, 300, 100);
        fill(v.scene3dWorldById("a"), 0xFFFF2020, counts[0]);
        fill(v.scene3dWorldById("b"), 0xFF2020FF, counts[1]);
        fill(v.scene3dWorldById("c"), 0xFF20FF20, counts[2]);
        try (RenderBackend be = make.get()) {
            if (be instanceof SoftwareRenderBackend sw) sw.scene3dWorlds(v::scene3dWorld);
            else if (be instanceof LwjglGlRenderBackend gl) gl.scene3dWorlds(v::scene3dWorld);
            else ((VkRenderBackend) be).scene3dWorlds(v::scene3dWorld);
            v.frame(1000, be, true);
            return be.pixmap().pixels().clone();
        }
    }

    private static int farFrom(int[] a, int[] b) {
        int far = 0;
        for (int i = 0; i < a.length; i++) {
            int d = 0;
            for (int s = 0; s <= 24; s += 8) d = Math.max(d, Math.abs(((a[i] >>> s) & 255) - ((b[i] >>> s) & 255)));
            if (d > 8) far++;
        }
        return far;
    }

    private static void check(String name, Supplier<RenderBackend> make, int[] counts) {
        int[] sw = render(() -> new SoftwareRenderBackend(300, 100, 1f), counts);
        int[] gpu;
        try {
            gpu = render(make, counts);
        } catch (Throwable t) {
            Assumptions.abort(name + " unavailable: " + t);
            return;
        }
        int far = farFrom(gpu, sw);
        assertTrue(far < sw.length * 0.005, name + " がソフトウェアと食い違う画素: " + far + " / " + sw.length
                + "（counts=" + java.util.Arrays.toString(counts) + "）");
    }

    @Test
    void glDrawsEachWorldWithItsOwnInstances() {
        check("GL", () -> new LwjglGlRenderBackend(300, 100, 1f), new int[]{1, 1, 1});
    }

    @Test
    void glSurvivesGrowingTheInstanceBufferMidFrame() {
        // 初期容量は 256。a=1 の後ろへ b=300 を置くと途中で広げる。続く c も正しく描かれる
        check("GL", () -> new LwjglGlRenderBackend(300, 100, 1f), new int[]{1, 300, 5});
    }

    @Test
    void vulkanDrawsEachWorldWithItsOwnInstances() {
        check("Vulkan", () -> new VkRenderBackend(300, 100, 1f), new int[]{1, 1, 1});
        check("Vulkan", () -> new VkRenderBackend(300, 100, 1f), new int[]{1, 300, 5});
    }
}
