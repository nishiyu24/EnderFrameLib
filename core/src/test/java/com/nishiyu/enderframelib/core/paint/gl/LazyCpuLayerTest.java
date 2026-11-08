package com.nishiyu.enderframelib.core.paint.gl;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** GPU 描画で、CPU の画面サイズの画素と重ね用テクスチャを、CPU が実際に描くまで確保しないこと。 */
class LazyCpuLayerTest {

    private static final String HEAD = "<html><body style='margin:0'>";

    private static LwjglGlRenderBackend render(String body) {
        LwjglGlRenderBackend b;
        try {
            b = new LwjglGlRenderBackend(64, 64, 1f);
        } catch (Throwable t) {
            Assumptions.abort("OpenGL を使えない: " + t);
            return null;
        }
        SoftwareRendering.renderHtml(HEAD + body + "</body></html>", 64, 64, 10_000L, b);
        return b;
    }

    @Test
    void shapeOnlyPageNeverAllocatesCpuPixelsOrOverlayTexture() {
        LwjglGlRenderBackend b = render("<div style='width:40px;height:30px;background:#336699;border-radius:6px;border:2px solid #fff'></div>");
        try {
            assertFalse(b.cpuPixelsAllocated(), "矩形だけのページで CPU の画面サイズの画素を確保している");
            assertFalse(b.overlayTextureAllocated(), "矩形だけのページで重ね用テクスチャを確保している");
            assertTrue(b.drawCallCount() > 0, "何も描かれていない");
        } finally {
            b.close();
        }
    }

    @Test
    void cpuLayerFallbackStillAllocatesAndMatchesTheCpuRenderer() {
        // GPU の層の深さの上限を絞って、入れ子の不透明度の層を CPU の層に落とす
        String body = "<div style='opacity:.8;width:50px;height:50px;background:#336699'>"
                + "<div style='opacity:.7;width:40px;height:40px;background:#cc3300'>"
                + "<div style='opacity:.6;width:30px;height:30px;background:#33cc00'>"
                + "<div style='opacity:.5;width:20px;height:20px;background:#ffffff'></div></div></div></div>";
        int saved = LwjglGlRenderBackend.maxLayerDepth;
        LwjglGlRenderBackend b = null;
        try {
            LwjglGlRenderBackend.maxLayerDepth = 3;
            b = render(body);
            assertTrue(b.cpuPixelsAllocated(), "上限を絞ったのに CPU の層に落ちなかった");
            assertTrue(b.overlayTextureAllocated(), "CPU が塗ったのに重ね用テクスチャが無い");
            SoftwareRenderBackend ref = SoftwareRendering.renderHtml(HEAD + body + "</body></html>", 64, 64, 1f, 10_000L);
            var got = b.snapshotTarget();
            int worst = 0;
            for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
                int p = got.get(x, y), q = ref.pixmap().get(x, y);
                for (int sh = 0; sh < 32; sh += 8) worst = Math.max(worst, Math.abs(((p >>> sh) & 0xFF) - ((q >>> sh) & 0xFF)));
            }
            assertTrue(worst <= 3, "CPU 版と画素が違う: 最大差 " + worst);
        } finally {
            LwjglGlRenderBackend.maxLayerDepth = saved;
            if (b != null) b.close();
        }
    }

    @Test
    void softwareBackendAllocatesItsRootOnFirstUse() {
        SoftwareRenderBackend sw = new SoftwareRenderBackend(32, 16, 2f);
        assertFalse(sw.isRootAllocated(), "生成しただけでは確保しない");
        assertEquals(64, sw.pixmap().width());
        assertEquals(32, sw.pixmap().height());
        assertTrue(sw.isRootAllocated());
        SoftwareRenderBackend painted = SoftwareRendering.renderHtml(HEAD + "<div style='width:8px;height:8px;background:#f00'></div></body></html>", 32, 16, 1f, 10_000L);
        assertTrue(painted.isRootAllocated());
        assertEquals(0xFFFF0000, painted.pixmap().get(2, 2));
    }

    @Test
    void sessionAndAnimationQueriesDoNotGoThroughTheCpuRenderer() throws Exception {
        LwjglGlRenderBackend a = render("<div style='width:10px;height:10px;background:#f00'></div>");
        LwjglGlRenderBackend b = render("<div style='width:10px;height:10px;background:#0f0'></div>");
        try {
            assertTrue(a.sessionId().startsWith("gl-"), a.sessionId());
            assertNotEquals(a.sessionId(), b.sessionId());
        } finally {
            a.close();
            b.close();
        }
        // 構造の番人: GPU 版が CPU 描画器へ問い合わせてよいのは、描画（renderOnto）・重ね（dirtyBounds/pixmap/clearDirty）・
        // （フォント／画像は PaintResources を共有）だけ。セッション名とアニメーションの問い合わせは自前
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "core/src/main/java/com/nishiyu/enderframelib/core/paint/gl/LwjglGlRenderBackend.java"));
        for (String banned : new String[]{"cpu.sessionId(", "cpu.hasActiveAnimation(", "cpu.fonts(", "cpu.images("}) {
            assertFalse(src.contains(banned), banned + " が戻っている");
        }
    }

    @Test
    void animationQueryUsesTheBackendsOwnSession() {
        LwjglGlRenderBackend still = render("<div style='width:10px;height:10px;background:#f00'></div>");
        LwjglGlRenderBackend moving = render("<style>@keyframes s{from{opacity:0}to{opacity:1}}</style>"
                + "<div style='width:10px;height:10px;background:#f00;animation:s 1s linear infinite'></div>");
        try {
            var itemsStill = SoftwareRendering.displayList(HEAD + "<div style='width:10px;height:10px;background:#f00'></div></body></html>", 64, 64);
            assertFalse(still.hasActiveAnimation(itemsStill, 10_000L));
            var itemsMoving = SoftwareRendering.displayList(HEAD + "<style>@keyframes s{from{opacity:0}to{opacity:1}}</style>"
                    + "<div style='width:10px;height:10px;background:#f00;animation:s 1s linear infinite'></div></body></html>", 64, 64);
            // 描いたあとの同じバックエンドで、動いている項目を動いていると答える
            moving.render(itemsMoving, 10_000L);
            assertTrue(moving.hasActiveAnimation(itemsMoving, 10_500L));
        } finally {
            still.close();
            moving.close();
        }
    }
}
