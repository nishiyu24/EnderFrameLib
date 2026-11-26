package com.nishiyu.enderframelib.core.paint.gpu;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.RecordingGpuDevice;
import com.nishiyu.enderframelib.core.paint.gl.CpuLayerFallback;
import com.nishiyu.enderframelib.core.scene3d.MaterialDesc;
import com.nishiyu.enderframelib.core.scene3d.MeshHandle;
import com.nishiyu.enderframelib.core.scene3d.Scene3DWorld;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link GpuRenderBackend} が GL/GLFW コンテキストなしで {@link RecordingGpuDevice} 上で完結し、
 * HTML 規模に対して O(1) のドローコールでフィルタ・ブレンド・クリップ・3D を RHI 命令列へ変換することを検証する。
 */
class GpuRenderBackendRecordingTest {

    private static String rowsHtml(int rows) {
        StringBuilder sb = new StringBuilder(rows * 96 + 128);
        sb.append("<body style=\"margin:0;background:#10141c;font-size:0;line-height:0\">");
        for (int i = 0; i < rows; i++) {
            int r = 40 + (i * 37) % 180;
            int g = 60 + (i * 53) % 180;
            int b = 80 + (i * 71) % 170;
            sb.append("<div style=\"display:inline-block;width:4px;height:4px;border-radius:1px;background:rgb(")
                    .append(r).append(',').append(g).append(',').append(b).append(")\"></div>");
        }
        sb.append("</body>");
        return sb.toString();
    }

    private record Counts(long beginPass, long bind, long draw, long endPass, int instances) {}

    private static Counts recordFrame(int rows) {
        RecordingGpuDevice rec = new RecordingGpuDevice();
        try (GpuRenderBackend backend = new GpuRenderBackend(() -> rec, CpuLayerFallback::new, 800, 600, 1f);
             StandaloneView view = new StandaloneView(rowsHtml(rows), 800, 600, FontResolver.FALLBACK)) {
            rec.clearLog();
            view.frame(1000L, backend, true);
            List<RecordingGpuDevice.Command> cmds = rec.commands();
            int instances = cmds.stream()
                    .filter(RecordingGpuDevice.Command.Draw.class::isInstance)
                    .map(RecordingGpuDevice.Command.Draw.class::cast)
                    .mapToInt(RecordingGpuDevice.Command.Draw::instanceCount)
                    .sum();
            return new Counts(
                    rec.count(RecordingGpuDevice.Command.BeginPass.class),
                    rec.count(RecordingGpuDevice.Command.Bind.class),
                    rec.count(RecordingGpuDevice.Command.Draw.class),
                    rec.count(RecordingGpuDevice.Command.EndPass.class),
                    instances);
        }
    }

    @Test
    void uniformHtmlScenesIssueSameO1DrawCommandsRegardlessOfShapeCount() {
        Counts c500 = recordFrame(500);
        Counts c2000 = recordFrame(2000);

        assertEquals(1, c500.beginPass(), "500 行でもレンダーパス 1 回");
        assertEquals(1, c500.bind(), "500 行でも Bind 1 回");
        assertEquals(1, c500.draw(), "500 行でも Draw 1 回");
        assertEquals(1, c500.endPass(), "500 行でも EndPass 1 回");

        assertEquals(c500.beginPass(), c2000.beginPass(), "2000 行でもパス数同じ");
        assertEquals(c500.bind(), c2000.bind(), "2000 行でも Bind 数同じ");
        assertEquals(c500.draw(), c2000.draw(), "2000 行でも Draw 数同じ");
        assertEquals(c500.endPass(), c2000.endPass(), "2000 行でも EndPass 数同じ");
        assertTrue(c2000.instances() > c500.instances() * 2,
                "インスタンス数は行数に応じて増える: " + c500.instances() + " -> " + c2000.instances());
    }

    @Test
    void filterMixBlendClipPathAndScene3dRecordExpectedRhiCommandsHeadlessly() {
        String html = """
                <body style="margin:0;background:#0e1116">
                  <div style="width:120px;height:80px;background:#336699;filter:blur(6px)"></div>
                  <div style="width:120px;height:80px;background:#cc3344;mix-blend-mode:multiply"></div>
                  <div style="width:100px;height:100px;background:#22aa55;clip-path:circle(40px at 50px 50px)"></div>
                  <scene-3d id="s3d" style="width:128px;height:128px;display:block"></scene-3d>
                </body>
                """;
        RecordingGpuDevice rec = new RecordingGpuDevice();
        try (GpuRenderBackend backend = new GpuRenderBackend(() -> rec, CpuLayerFallback::new, 400, 400, 1f);
             StandaloneView view = new StandaloneView(html, 400, 400, FontResolver.FALLBACK)) {
            backend.resources().scene3dWorlds(view::scene3dWorld);
            Scene3DWorld world = view.scene3dWorldById("s3d");
            assertNotNull(world, "<scene-3d id=s3d> のワールドが取得できること");
            world.clearColorArgb(0xFF102030);
            world.camera().lookAt(0, 0, 4f, 0, 0, 0, 0, 1, 0);
            world.camera().setPerspective((float) Math.toRadians(45.0), 1f, 0.1f, 50f);
            MeshHandle box = world.geometry().createBox(1f, 1f, 1f);
            MaterialDesc mat = world.materials().registerColor(0xFF44CCFF);
            int node = world.arena().allocateNode();
            world.arena().setMesh(node, box.meshId());
            world.arena().setMaterial(node, mat.materialId());

            rec.clearLog();
            view.frame(1000L, backend, true);

            List<String> boundShaders = rec.commands().stream()
                    .filter(RecordingGpuDevice.Command.Bind.class::isInstance)
                    .map(RecordingGpuDevice.Command.Bind.class::cast)
                    .map(RecordingGpuDevice.Command.Bind::shaderId)
                    .distinct()
                    .toList();
            assertTrue(boundShaders.contains("rects"), "rects シェーダが使われる: " + boundShaders);
            assertTrue(boundShaders.contains("fx"), "blur/blend/clip-path で fx シェーダが使われる: " + boundShaders);
            assertTrue(boundShaders.contains("scene3d"), "scene-3d で scene3d シェーダが使われる: " + boundShaders);
            assertTrue(rec.count(RecordingGpuDevice.Command.BeginPass.class) > 1, "複数パスで合成される");
            assertEquals(1, rec.count(RecordingGpuDevice.Command.BindMesh.class), "3D メッシュがバインドされる");
            assertTrue(rec.count(RecordingGpuDevice.Command.DrawIndexed.class) >= 1, "インデックス付き描画が行われる");
        }
    }
}
