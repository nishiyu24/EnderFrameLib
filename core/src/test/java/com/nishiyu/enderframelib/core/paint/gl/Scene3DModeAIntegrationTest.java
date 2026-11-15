package com.nishiyu.enderframelib.core.paint.gl;

import com.nishiyu.enderframelib.api.EnderEngine;
import com.nishiyu.enderframelib.api.WebViewSurface;
import com.nishiyu.enderframelib.common.EngineSurface;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.scene3d.MaterialDesc;
import com.nishiyu.enderframelib.core.scene3d.MeshHandle;
import com.nishiyu.enderframelib.core.scene3d.Scene3DWorld;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class Scene3DModeAIntegrationTest {

    private static void populateUprightAndRotatedScene(Scene3DWorld world) {
        world.clearColorArgb(0xFF182230);
        world.camera().lookAt(0, 0, 4.2f, 0, 0, 0, 0, 1, 0);
        world.camera().setPerspective((float) Math.toRadians(45.0), 1.0f, 0.1f, 50.0f);

        MeshHandle cube = world.geometry().createBox(1.5f, 1.5f, 1.5f);
        MaterialDesc rotatedAmber = world.materials().register(0xFFF5A623, 0.1f, 0.45f, 0, 0f, null, false, false);
        int centerCube = world.arena().allocateNode();
        world.arena().setRotationEulerRadians(centerCube, 0.35f, 0.65f, 0.15f);
        world.arena().setMesh(centerCube, cube.meshId());
        world.arena().setMaterial(centerCube, rotatedAmber.materialId());

        // +Y 上端と -Y 下端に無光沢の色マーカーを置き、sy < 0 合成で上下反転していないことを検証（コーナーは 3D 背景色のまま残る幅）
        MeshHandle bar = world.geometry().createBox(1.2f, 0.45f, 0.2f);
        MaterialDesc redTop = world.materials().register(0xFFEF233C, 0f, 0.5f, 0, 0f, null, false, true);
        MaterialDesc blueBottom = world.materials().register(0xFF2B6CB0, 0f, 0.5f, 0, 0f, null, false, true);

        int topMarker = world.arena().allocateNode();
        world.arena().setPosition(topMarker, 0f, 1.15f, 0.5f);
        world.arena().setMesh(topMarker, bar.meshId());
        world.arena().setMaterial(topMarker, redTop.materialId());

        int bottomMarker = world.arena().allocateNode();
        world.arena().setPosition(bottomMarker, 0f, -1.15f, 0.5f);
        world.arena().setMesh(bottomMarker, bar.meshId());
        world.arena().setMaterial(bottomMarker, blueBottom.materialId());
    }

    private static int[] renderViaGpuOffscreen(String html, int width, int height) {
        AtomicReference<int[]> outPixels = new AtomicReference<>();
        EnderEngine.Builder builder = EnderEngine.html(html)
                .size(width, height)
                .target(EnderEngine.Target.GPU_OFFSCREEN)
                .clearColor(0xFF05070A)
                .sink((pixels, pw, ph) -> outPixels.set(pixels.clone()));

        WebViewSurface surface;
        try {
            surface = builder.build();
        } catch (Throwable t) {
            Assumptions.abort("GPU_OFFSCREEN unavailable in this environment: " + t);
            return new int[0];
        }
        try {
            EngineSurface es = (EngineSurface) surface;
            Scene3DWorld world = es.getOrCreateScene3dWorld("s1");
            assertNotNull(world, "<scene-3d id='s1'> must resolve to a Scene3DWorld");
            populateUprightAndRotatedScene(world);

            assertTrue(surface.frame(100L, true));
            if (es.backend() instanceof LwjglGlRenderBackend gl) {
                assertNotNull(gl.scene3dRenderer(), "GlScene3DRenderer must be initialized on GPU_OFFSCREEN");
                assertEquals(3, gl.scene3dRenderer().lastInstanceCount());
                assertTrue(gl.scene3dRenderer().lastDrawCalls() >= 1);
            }
            int[] pixels = outPixels.get();
            assertNotNull(pixels);
            assertEquals(width * height, pixels.length);
            return pixels;
        } finally {
            surface.close();
        }
    }

    @Test
    void clippedRoundedRotatedCubeCompositeMatchesInteriorAndStaysUpright() {
        int size = 160;
        String unclippedHtml = """
                <html><body style="margin:0;background:#05070a;">
                <scene-3d id="s1" style="display:block;width:160px;height:160px;"></scene-3d>
                </body></html>
                """;
        String clippedHtml = """
                <html><body style="margin:0;background:#05070a;">
                <div style="width:160px;height:160px;overflow:hidden;border-radius:40px;background:#05070a;">
                  <scene-3d id="s1" style="display:block;width:160px;height:160px;border-radius:40px;"></scene-3d>
                </div>
                </body></html>
                """;

        int[] unclipped = renderViaGpuOffscreen(unclippedHtml, size, size);
        int[] clipped = renderViaGpuOffscreen(clippedHtml, size, size);

        // 上下反転していないか (top marker=Red, bottom marker=Blue)
        int topPx = clipped[24 * size + 80];
        int bottomPx = clipped[136 * size + 80];
        assertTrue(((topPx >>> 16) & 0xFF) > (topPx & 0xFF) + 80,
                "Top of <scene-3d> should be red (+Y), got 0x" + Integer.toHexString(topPx));
        assertTrue((bottomPx & 0xFF) > ((bottomPx >>> 16) & 0xFF) + 60,
                "Bottom of <scene-3d> should be blue (-Y), got 0x" + Integer.toHexString(bottomPx));

        // 回転キューブが中央に描画されていること
        int centerPx = clipped[80 * size + 80];
        assertNotEquals(0xFF182230, centerPx, "Center pixel should show shaded rotated cube");
        assertNotEquals(0xFF05070A, centerPx);

        // border-radius:40px の外側コーナー (2, 2) は親の背景色 #05070a にクリップされていること
        int cornerClipped = clipped[2 * size + 2];
        int cornerUnclipped = unclipped[2 * size + 2];
        assertEquals(0xFF05070A, cornerClipped, "Rounded corner outside radius must stay clipped to background");
        assertEquals(0xFF182230, cornerUnclipped, "Unclipped scene fills corner with 3D clear color");

        // クリップ内の中心矩形 [40..120, 40..120] は非クリップ版とピクセル一致すること
        int diffs = 0;
        for (int y = 40; y < 120; y++) {
            for (int x = 40; x < 120; x++) {
                if (clipped[y * size + x] != unclipped[y * size + x]) diffs++;
            }
        }
        assertEquals(0, diffs, "Interior pixels inside rounded clip should match unclipped 3D output");
    }

    @Test
    void softwareAndFallbackDeepClipsResolveSceneWorldSafely() {
        String html = """
                <html><body style="margin:0;background:#112233;">
                <scene-3d id="s1" style="display:block;width:120px;height:120px;border-radius:16px;"></scene-3d>
                </body></html>
                """;
        AtomicReference<int[]> swPixels = new AtomicReference<>();
        try (WebViewSurface sw = EnderEngine.html(html)
                .size(120, 120)
                .target(EnderEngine.Target.SOFTWARE)
                .clearColor(0xFF112233)
                .sink((px, pw, ph) -> swPixels.set(px.clone()))
                .build()) {
            EngineSurface es = (EngineSurface) sw;
            Scene3DWorld world = es.getOrCreateScene3dWorld("s1");
            assertNotNull(world);
            populateUprightAndRotatedScene(world);

            assertInstanceOf(SoftwareRenderBackend.class, es.backend());
            SoftwareRenderBackend backend = (SoftwareRenderBackend) es.backend();
            // data-scene-world (nodeId) から Scene3DWorld を解決できる経路の検証（描画自体は X3-2 まで安全に no-op）
            assertTrue(sw.frame(100L, true));
            assertNotNull(swPixels.get());
            assertSame(world, es.scene3dWorld(world.worldId()));
            assertSame(world, backend.scene3dWorld(world.worldId()));
        }

        // GPU_OFFSCREEN 側で >4 段クリップによる readback Pixmap フォールバックでもクラッシュせず上下正しく描かれること
        String deepClipHtml = """
                <html><body style="margin:0;background:#05070a;">
                <div style="width:120px;height:120px;overflow:hidden;">
                  <div style="width:120px;height:120px;overflow:hidden;">
                    <div style="width:120px;height:120px;overflow:hidden;">
                      <div style="width:120px;height:120px;overflow:hidden;">
                        <div style="width:120px;height:120px;overflow:hidden;">
                          <scene-3d id="s1" style="display:block;width:120px;height:120px;"></scene-3d>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
                </body></html>
                """;
        int[] fbPixels = renderViaGpuOffscreen(deepClipHtml, 120, 120);
        int topPx = fbPixels[20 * 120 + 60];
        assertTrue(((topPx >>> 16) & 0xFF) > (topPx & 0xFF) + 60,
                "Readback fallback with >4 clips must preserve upright orientation, got 0x" + Integer.toHexString(topPx));
    }
}
