package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.scene3d.Scene3DArena;
import com.nishiyu.enderframelib.core.scene3d.Scene3DWorld;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

/** X3-3: 2D のヒットテスト（SceneHit）を通して、画面の点から {@code <scene-3d>} 内のメッシュを選ぶ。 */
class Scene3DPickTest {
    private static final String HTML = "<html><body style='margin:0'>"
            + "<scene-3d id='s' style='position:absolute;left:100px;top:50px;width:200px;height:100px'></scene-3d>"
            + "%s</body></html>";

    private static StandaloneView view(String extra) {
        StandaloneView v = new StandaloneView(String.format(HTML, extra), 400, 300, FontResolver.FALLBACK);
        v.frame(1000, new NullBackend(), true);
        Scene3DWorld w = v.scene3dWorldById("s");
        int cube = w.geometry().createCube(2f).meshId();
        Scene3DArena a = w.arena();
        int n = a.allocateNode();
        a.setMesh(n, cube);
        a.setEntityTag(n, 42);
        w.camera().lookAt(0, 0, 8, 0, 0, 0, 0, 1, 0);
        w.camera().setPerspective((float) Math.toRadians(60), 2f, 0.1f, 100f);
        return v;
    }

    @Test
    void pickingThroughTheSceneElementFindsTheMesh() {
        StandaloneView v = view("");
        UiView.Scene3DPick p = v.pickScene3d(200, 100); // シーンの中心（left 100 + 100, top 50 + 50）
        assertNotNull(p);
        assertEquals(42, p.hit().entityTag);
        assertEquals(1f, p.hit().z, 1e-3f);
        assertNull(v.pickScene3d(110, 55), "シーンの隅は立方体の外");
        assertNull(v.pickScene3d(350, 250), "シーンの外は 3D ではない");
    }

    @Test
    void anOverlappingTwoDElementWinsOverTheScene() {
        // 立方体は画面で x 188〜212・y 88〜112。右半分（x ≥ 205）だけを 2D の要素で覆う
        StandaloneView v = view("<div style='position:absolute;left:205px;top:75px;width:50px;height:50px;background:#f00'></div>");
        assertNull(v.pickScene3d(208, 100), "上に重なった 2D の要素が先に当たる");
        assertNotNull(v.pickScene3d(195, 100), "覆われていない所は 3D まで届く");
    }

    private static final class NullBackend implements com.nishiyu.enderframelib.core.paint.RenderBackend {
        @Override public void render(com.nishiyu.enderframelib.core.paint.PreparedFrame frame) { }
        @Override public com.nishiyu.enderframelib.core.raster.Pixmap pixmap() { throw new UnsupportedOperationException(); }
        @Override public float devicePixelRatio() { return 1f; }
    }
}
