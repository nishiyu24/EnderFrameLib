package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.paint.RenderBackend;
import com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.scene3d.MeshHandle;
import com.nishiyu.enderframelib.core.scene3d.Scene3DWorld;
import java.util.Arrays;
import java.util.function.Supplier;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 回帰: DOM が変わらなくても、{@code <scene-3d>} の中身（ノード・カメラ・ライト）が変われば描き直す。
 * 以前は {@link StandaloneView#frame} が「変化なし」と判断して、タイマーで回している 3D が止まって見えた。
 * 逆に、何も変わらない静止シーンは描き直さない（毎フレーム GPU / CPU を使わない）。
 */
class Scene3DRedrawTest {

    private static final String HTML =
            "<body style=\"margin:0\"><scene-3d id=\"s\" style=\"width:100px;height:100px;display:block\"></scene-3d></body>";

    private static Scene3DWorld populate(StandaloneView v, int[] nodeOut) {
        Scene3DWorld w = v.scene3dWorldById("s");
        w.camera().lookAt(0, 0, 4, 0, 0, 0, 0, 1, 0);
        w.camera().setPerspective(0.8f, 1f, 0.1f, 20f);
        MeshHandle b = w.geometry().createBox(1.5f, 1.5f, 1.5f);
        int n = w.arena().allocateNode();
        w.arena().setMesh(n, b.meshId());
        w.arena().setRotationEulerRadians(n, 0.3f, 0.4f, 0f);
        nodeOut[0] = n;
        return w;
    }

    private static void spinsAndRests(Supplier<RenderBackend> backendFactory) {
        StandaloneView v = new StandaloneView(HTML, 100, 100);
        int[] node = new int[1];
        Scene3DWorld w = populate(v, node);
        try (RenderBackend be = backendFactory.get()) {
            be.getClass(); // 生成の失敗はここで例外
            // ワールドの引き当てを渡す
            if (be instanceof SoftwareRenderBackend sw) sw.scene3dWorlds(v::scene3dWorld);
            else ((LwjglGlRenderBackend) be).scene3dWorlds(v::scene3dWorld);

            assertTrue(v.frame(1000, be, true));
            int first = Arrays.hashCode(be.pixmap().pixels());

            // 静止: 何も変えなければ描き直さない
            assertFalse(v.frame(1016, be, false), "変化が無いのに描き直した");
            assertFalse(v.needsFrame());

            // 回す（DOM は不変）: 描き直され、絵が変わる
            int prev = first;
            for (int i = 1; i <= 5; i++) {
                w.arena().setRotationEulerRadians(node[0], 0.3f, 0.4f + i * 0.3f, 0f);
                assertTrue(v.needsFrame(), "3D が変わったのに needsFrame が false");
                assertTrue(v.frame(1000 + i * 50L, be, false), "回転したのに描き直していない (i=" + i + ")");
                int h = Arrays.hashCode(be.pixmap().pixels());
                assertNotEquals(prev, h, "絵が変わっていない (i=" + i + ")");
                prev = h;
            }
            // カメラ・ライトでも描き直す
            w.camera().lookAt(1, 0, 4, 0, 0, 0, 0, 1, 0);
            assertTrue(v.frame(2000, be, false), "カメラの変更で描き直す");
            w.light().setColor(0.2f, 0.2f, 0.2f, 1f);
            assertTrue(v.frame(2050, be, false), "ライトの変更で描き直す");
            assertFalse(v.frame(2100, be, false), "また静止");
        }
    }

    @Test
    void softwareRedrawsOnlyWhenTheSceneChanges() {
        spinsAndRests(() -> new SoftwareRenderBackend(100, 100, 1f));
    }

    @Test
    void glRedrawsOnlyWhenTheSceneChanges() {
        Supplier<RenderBackend> f = () -> {
            try {
                return new LwjglGlRenderBackend(100, 100, 1f);
            } catch (Throwable t) {
                Assumptions.abort("GL unavailable: " + t);
                return null;
            }
        };
        spinsAndRests(f);
    }

    @Test
    void changeCountOnlyGrowsOnVisibleChanges() {
        Scene3DWorld w = new Scene3DWorld();
        long c0 = w.changeCount();
        assertEquals(c0, w.changeCount(), "読むだけでは増えない");
        int n = w.arena().allocateNode();
        long c1 = w.changeCount();
        assertTrue(c1 > c0);
        w.arena().setPosition(n, 1, 2, 3);
        long c2 = w.changeCount();
        assertTrue(c2 > c1);
        w.camera().lookAt(0, 0, 1, 0, 0, 0, 0, 1, 0);
        long c3 = w.changeCount();
        assertTrue(c3 > c2);
        w.light().setAmbient(0.1f, 0.1f, 0.1f);
        assertTrue(w.changeCount() > c3);
        long c4 = w.changeCount();
        w.updateAndPrepare(64, 64); // 描画の準備（アスペクトの反映・行列の更新）は「変更」ではない
        w.updateAndPrepare(64, 64);
        long c5 = w.changeCount();
        w.updateAndPrepare(64, 64);
        assertEquals(c5, w.changeCount(), "準備を繰り返しても増えない");
        assertTrue(c5 >= c4);
    }
}
