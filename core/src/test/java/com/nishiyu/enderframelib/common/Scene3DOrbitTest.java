package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.scene3d.CameraOrbit;
import com.nishiyu.enderframelib.core.scene3d.CameraSpec;
import com.nishiyu.enderframelib.core.scene3d.Scene3DWorld;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

/** X3-3: {@code data-orbit} の {@code <scene-3d>} をドラッグで回し、ホイールで寄せる（純粋なカメラ計算と、実際の入力経路）。 */
class Scene3DOrbitTest {

    private static CameraSpec cam() {
        CameraSpec c = new CameraSpec();
        c.lookAt(0, 0, 5, 0, 0, 0, 0, 1, 0);
        return c;
    }

    private static float dist(CameraSpec c) {
        float x = c.eyeX() - c.targetX(), y = c.eyeY() - c.targetY(), z = c.eyeZ() - c.targetZ();
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    // ------------------------------------------------------------------ カメラ計算

    @Test
    void orbitingKeepsTheDistanceAndTheTarget() {
        CameraSpec c = cam();
        CameraOrbit.orbit(c, 0.7f, 0.3f);
        assertEquals(5f, dist(c), 1e-4f);
        assertEquals(0f, c.targetX(), 0f);
        assertEquals(0f, c.targetY(), 0f);
        assertEquals(0f, c.targetZ(), 0f);
    }

    @Test
    void aPositiveYawMovesTheCameraTowardPlusXAndAPositivePitchUp() {
        CameraSpec a = cam();
        CameraOrbit.orbit(a, (float) Math.toRadians(90), 0f);
        assertEquals(5f, a.eyeX(), 1e-4f);
        assertEquals(0f, a.eyeZ(), 1e-4f);
        CameraSpec b = cam();
        CameraOrbit.orbit(b, 0f, (float) Math.toRadians(30));
        assertEquals(2.5f, b.eyeY(), 1e-4f);
        assertEquals(0f, b.eyeX(), 1e-4f);
    }

    @Test
    void pitchStopsShortOfTheVerticalSoTheViewNeverFlips() {
        CameraSpec c = cam();
        CameraOrbit.orbit(c, 0f, 10f);
        assertTrue(c.eyeY() < 5f && c.eyeY() > 4.9f, "真上の手前で止まる: " + c.eyeY());
        CameraOrbit.orbit(c, 0f, -20f);
        assertTrue(c.eyeY() > -5f && c.eyeY() < -4.9f);
        assertFalse(Float.isNaN(c.viewMatrix()[0]));
    }

    @Test
    void zoomScalesTheDistanceWithinTheLimits() {
        CameraSpec c = cam();
        CameraOrbit.zoom(c, 0.5f);
        assertEquals(2.5f, dist(c), 1e-4f);
        CameraOrbit.zoom(c, 1e-9f);
        assertEquals(CameraOrbit.MIN_DISTANCE, dist(c), 1e-5f);
        CameraOrbit.zoom(c, 1e12f);
        assertEquals(CameraOrbit.MAX_DISTANCE, dist(c), 1f);
        float before = dist(c);
        CameraOrbit.zoom(c, 0f);
        CameraOrbit.zoom(c, Float.NaN);
        CameraOrbit.zoom(c, -2f);
        assertEquals(before, dist(c), 0f, "不正な倍率は無視");
    }

    // ------------------------------------------------------------------ 入力

    private static final String HTML = "<html><body style='margin:0'>"
            + "<scene-3d id='s' %s style='position:absolute;left:100px;top:50px;width:200px;height:100px'>"
            + "<camera-3d position='0 0 6' look-at='0 0 0'></camera-3d>"
            + "<mesh-3d shape='box' size='2'></mesh-3d></scene-3d>"
            + "<button id='b' style='position:absolute;left:10px;top:10px;width:60px;height:30px'>x</button>"
            + "</body></html>";

    private static StandaloneView view(String sceneAttrs) {
        StandaloneView v = new StandaloneView(String.format(HTML, sceneAttrs), 400, 300, FontResolver.FALLBACK);
        v.frame(1000, new NullBackend(), true);
        return v;
    }

    private static final class NullBackend implements com.nishiyu.enderframelib.core.paint.RenderBackend {
        @Override public void render(com.nishiyu.enderframelib.core.paint.PreparedFrame frame) { }
        @Override public com.nishiyu.enderframelib.core.raster.Pixmap pixmap() { throw new UnsupportedOperationException(); }
        @Override public float devicePixelRatio() { return 1f; }
    }

    @Test
    void draggingInsideAnOrbitSceneTurnsTheCamera() {
        StandaloneView v = view("data-orbit='true'");
        Scene3DWorld w = v.scene3dWorldById("s");
        assertEquals(6f, w.camera().eyeZ(), 1e-4f);
        v.mouseMove(200, 100, "t");
        v.mouseButton(true, "t");
        v.mouseMove(250, 100, "t"); // 右へ 50 px
        v.mouseButton(false, "t");
        assertTrue(w.camera().eyeX() < -1f, "右へドラッグするとカメラは左へ回り込む: " + w.camera().eyeX());
        assertEquals(6f, Math.sqrt(w.camera().eyeX() * w.camera().eyeX() + w.camera().eyeZ() * w.camera().eyeZ()), 1e-3f, "距離は一定");
    }

    @Test
    void dragEndsWhenTheButtonIsReleased() {
        StandaloneView v = view("data-orbit='true'");
        Scene3DWorld w = v.scene3dWorldById("s");
        v.mouseMove(200, 100, "t");
        v.mouseButton(true, "t");
        v.mouseMove(220, 100, "t");
        v.mouseButton(false, "t");
        float x = w.camera().eyeX();
        v.mouseMove(260, 100, "t"); // 離した後の移動は回さない
        assertEquals(x, w.camera().eyeX(), 0f);
    }

    @Test
    void aSceneWithoutOrbitIgnoresDragAndWheel() {
        StandaloneView v = view("");
        Scene3DWorld w = v.scene3dWorldById("s");
        v.mouseMove(200, 100, "t");
        v.mouseButton(true, "t");
        v.mouseMove(260, 120, "t");
        v.mouseButton(false, "t");
        assertEquals(0f, w.camera().eyeX(), 0f);
        assertEquals(6f, w.camera().eyeZ(), 1e-4f);
        v.scroll(0, 3, "t");
        assertEquals(6f, w.camera().eyeZ(), 1e-4f);
    }

    @Test
    void aDragThatStartsOutsideTheSceneNeverTurnsIt() {
        StandaloneView v = view("data-orbit='true'");
        Scene3DWorld w = v.scene3dWorldById("s");
        v.mouseMove(30, 25, "t"); // ボタンの上で押して
        v.mouseButton(true, "t");
        v.mouseMove(200, 100, "t"); // シーンの上へ引きずる
        v.mouseMove(260, 100, "t");
        v.mouseButton(false, "t");
        assertEquals(0f, w.camera().eyeX(), 0f);
    }

    @Test
    void theWheelZoomsAnOrbitScene() {
        StandaloneView v = view("data-orbit='true'");
        Scene3DWorld w = v.scene3dWorldById("s");
        v.mouseMove(200, 100, "t");
        assertTrue(v.scroll(0, 2, "t"));
        assertEquals(6f * 0.81f, w.camera().eyeZ(), 1e-3f, "上へ 2 ノッチで 0.9² 倍へ近づく");
        v.scroll(0, -2, "t");
        assertEquals(6f, w.camera().eyeZ(), 1e-3f);
    }

    @Test
    void anOrbitChangeMakesTheViewRedraw() {
        StandaloneView v = view("data-orbit='true'");
        assertFalse(v.frame(1016, new NullBackend(), false), "静止していれば描かない");
        v.mouseMove(200, 100, "t");
        v.mouseButton(true, "t");
        v.mouseMove(230, 110, "t");
        v.mouseButton(false, "t");
        assertTrue(v.needsFrame(), "回したら次のフレームが要る");
        assertTrue(v.frame(1032, new NullBackend(), false));
    }
}
