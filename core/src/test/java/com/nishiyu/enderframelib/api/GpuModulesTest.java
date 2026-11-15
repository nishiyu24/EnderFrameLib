package com.nishiyu.enderframelib.api;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.paint.gpu.GpuApiKind;
import com.nishiyu.enderframelib.core.paint.gpu.GpuBackendProvider;
import com.nishiyu.enderframelib.core.paint.gpu.GpuBackends;
import java.util.List;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** G4: 実装モジュール（core-gl / core-vulkan / core-glfw）が ServiceLoader で見つかり、公開 API から使える。 */
class GpuModulesTest {

    private static final String PAGE =
            "<body style=\"margin:0;background:#2040a0\"><div style=\"width:40px;height:30px;background:#e0a020\"></div></body>";

    @Test
    void providersAreDiscoveredInPriorityOrder() {
        List<GpuBackendProvider> found = GpuBackends.providers();
        assertEquals(List.of("gl", "vulkan"), found.stream().map(GpuBackendProvider::id).toList());
        assertEquals(List.of(GpuApiKind.GL, GpuApiKind.VULKAN), found.stream().map(GpuBackendProvider::api).toList());
    }

    @Test
    void windowHostIsProvidedByCoreGlfw() {
        assertTrue(ServiceLoader.load(WindowHost.class, WindowHost.class.getClassLoader()).findFirst().isPresent());
    }

    @Test
    void textureHandleValidatesItsFields() {
        assertThrows(NullPointerException.class, () -> new TextureHandle(null, 1, 2, 2));
        assertThrows(IllegalArgumentException.class, () -> new TextureHandle(TextureKind.OPENGL_TEXTURE, 1, 0, 2));
        var h = new TextureHandle(TextureKind.VULKAN_IMAGE, 5L, 3, 4);
        assertEquals(new TextureHandle(TextureKind.VULKAN_IMAGE, 5L, 3, 4), h);
    }

    @Test
    void softwareSurfaceHasNoTexture() {
        try (WebViewSurface s = EnderEngine.html(PAGE).size(64, 48).build()) {
            s.frame(0, true);
            assertTrue(s.texture().isEmpty());
        }
    }

    @Test
    void gpuTargetWithGlGivesAnOpenGlTextureOfThePixelSize() {
        WebViewSurface s = buildOrSkip(EnderEngine.html(PAGE).size(64, 48).devicePixelRatio(2f)
                .target(EnderEngine.Target.GPU).gpu(GpuApi.GL));
        try (s) {
            s.frame(0, true);
            TextureHandle t = s.texture().orElseThrow();
            assertEquals(TextureKind.OPENGL_TEXTURE, t.kind());
            assertTrue(t.handle() != 0);
            assertEquals(128, t.width());
            assertEquals(96, t.height());
        }
    }

    /** GL が実際に使えるか（GL が使えない環境 = ヘッドレスの CI などでは AUTO は Vulkan へ進む）。 */
    private static boolean glUsable() {
        try (WebViewSurface s = EnderEngine.html(PAGE).size(16, 16).target(EnderEngine.Target.GPU).gpu(GpuApi.GL).build()) {
            return true;
        } catch (IllegalStateException | LinkageError e) {
            return false;
        }
    }

    @Test
    void autoPicksGlAndTheOffscreenAliasMeansGl() {
        Assumptions.assumeTrue(glUsable(), "GL が使えない環境（AUTO は次の実装へ進む）");
        for (EnderEngine.Builder b : List.of(
                EnderEngine.html(PAGE).size(64, 48).target(EnderEngine.Target.GPU),
                EnderEngine.html(PAGE).size(64, 48).target(EnderEngine.Target.GPU_OFFSCREEN).gpu(GpuApi.VULKAN))) {
            try (WebViewSurface s = buildOrSkip(b)) {
                s.frame(0, true);
                assertEquals(TextureKind.OPENGL_TEXTURE, s.texture().orElseThrow().kind(),
                        "AUTO は GL を先に試す。GPU_OFFSCREEN は gpu() を無視して GL");
            }
        }
    }

    @Test
    void autoFallsThroughToAnotherApiWhenGlIsUnavailable() {
        Assumptions.assumeFalse(glUsable(), "GL が使える環境では上のテストが見る");
        WebViewSurface s = buildOrSkip(EnderEngine.html(PAGE).size(64, 48).target(EnderEngine.Target.GPU));
        try (s) {
            s.frame(0, true);
            assertEquals(TextureKind.VULKAN_IMAGE, s.texture().orElseThrow().kind(), "GL が無ければ AUTO は Vulkan");
        }
    }

    @Test
    void gpuTargetWithVulkanGivesAVulkanImage() {
        WebViewSurface s = buildOrSkip(EnderEngine.html(PAGE).size(64, 48).target(EnderEngine.Target.GPU).gpu(GpuApi.VULKAN));
        try (s) {
            s.frame(0, true);
            TextureHandle t = s.texture().orElseThrow();
            assertEquals(TextureKind.VULKAN_IMAGE, t.kind());
            assertTrue(t.handle() != 0);
            assertEquals(64, t.width());
        }
    }

    @Test
    void resizeKeepsTheSameApiAndRefreshesTheHandleSize() {
        WebViewSurface s = buildOrSkip(EnderEngine.html(PAGE).size(64, 48).target(EnderEngine.Target.GPU).gpu(GpuApi.GL));
        try (s) {
            s.frame(0, true);
            s.resize(80, 60);
            s.frame(16, true);
            TextureHandle t = s.texture().orElseThrow();
            assertEquals(TextureKind.OPENGL_TEXTURE, t.kind());
            assertEquals(80, t.width());
            assertEquals(60, t.height());
        }
    }

    @Test
    void hostDeviceRejectsNullWindow() {
        assertThrows(IllegalArgumentException.class, () -> EnderEngine.html(PAGE).hostDevice(0));
    }

    private static WebViewSurface buildOrSkip(EnderEngine.Builder b) {
        try {
            return b.build();
        } catch (IllegalStateException | LinkageError e) {
            Assumptions.abort("GPU unavailable in this environment: " + e);
            return null;
        }
    }
}
