package com.nishiyu.enderframelib.api;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.api.scene3d.Hit3DResult;
import com.nishiyu.enderframelib.api.scene3d.Scene3DController;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** X3-3: 公開 API {@code WebViewSurface#scene3d(id)}（ヘッドレスの面で、描画・ピック・文書との同居・失敗時の不変性）。 */
class Scene3DControllerTest {

    private static final String PAGE = "<html><body style='margin:0;background:#202020'>"
            + "<scene-3d id='view' style='position:absolute;left:20px;top:10px;width:120px;height:100px' data-background='#101820'>"
            + "<camera-3d position='0 0 4' look-at='0 0 0'></camera-3d>"
            + "<light-3d type='directional' direction='0 0 -1' intensity='1'></light-3d>"
            + "<light-3d type='ambient' intensity='0.3'></light-3d>"
            + "<mesh-3d id='doc' shape='box' size='1' color='#00ff00' position='-1.4 0 0'></mesh-3d>"
            + "</scene-3d>"
            + "<div id='cover' style='position:absolute;left:150px;top:10px;width:40px;height:40px;background:#f00'></div>"
            + "</body></html>";

    private static WebViewSurface surface() {
        return EnderEngine.html(PAGE).size(200, 130).clearColor(0xFF000000).build();
    }

    private static int px(WebViewSurface s, int x, int y) { return s.pixels()[y * s.pixelWidth() + x]; }

    @Test
    void noSuchSceneGivesAnEmptyController() {
        try (WebViewSurface s = surface()) {
            assertTrue(s.scene3d("nope").isEmpty());
            assertTrue(s.scene3d("cover").isEmpty(), "scene-3d でない要素");
            assertTrue(s.scene3d(null).isEmpty());
            assertTrue(s.scene3d("view").isPresent());
        }
    }

    @Test
    void nodesAddedThroughTheApiAreDrawnNextToTheDocumentsOwn() {
        try (WebViewSurface s = surface()) {
            Scene3DController c = s.scene3d("view").orElseThrow();
            assertEquals("view", c.elementId());
            c.setDirectionalLight(0, 0, -1, 0xFFFFFFFF, 1.0);
            int box = c.addBox(1.2, 1.2, 1.2, 0xFFFF0000);
            c.setPosition(box, 1.4, 0, 0);
            assertTrue(s.frame(0, true));
            // 領域は (20,10)-(140,110)。中心 (80,60)。文書の緑の箱は左、API の赤い箱は右
            int left = px(s, 80 - 35, 60), right = px(s, 80 + 35, 60);
            assertTrue(((left >> 8) & 0xFF) > 100 && ((left >> 16) & 0xFF) < 60, "左は文書の緑: " + Integer.toHexString(left));
            assertTrue(((right >> 16) & 0xFF) > 100 && ((right >> 8) & 0xFF) < 60, "右は API の赤: " + Integer.toHexString(right));
        }
    }

    @Test
    void raycastReturnsTheNodeTheApiAdded() {
        try (WebViewSurface s = surface()) {
            Scene3DController c = s.scene3d("view").orElseThrow();
            int box = c.addBox(1.2, 1.2, 1.2, 0xFFFF0000);
            s.frame(0, true); // 一度配置して要素の大きさを確定する
            Optional<Hit3DResult> hit = c.raycast(60, 50); // 要素の中心
            assertTrue(hit.isPresent());
            assertEquals(box, hit.get().nodeId());
            assertEquals(0.6, hit.get().z(), 1e-3, "手前の面（z = +0.6）");
            assertTrue(hit.get().distance() > 3.0 && hit.get().distance() < 4.0);
            assertTrue(c.raycast(2, 2).isEmpty(), "角は何にも当たらない");
        }
    }

    @Test
    void pickRespectsTwoDElementsAboveTheScene() {
        try (WebViewSurface s = surface()) {
            Scene3DController c = s.scene3d("view").orElseThrow();
            c.addBox(1.2, 1.2, 1.2, 0xFFFF0000);
            s.frame(0, true);
            assertTrue(c.pick(80, 60).isPresent(), "シーンの中心");
            assertTrue(c.pick(30, 20).isEmpty(), "角は外れ");
            assertTrue(c.pick(170, 30).isEmpty(), "カバー（別の 2D 要素）の上は 3D ではない");
        }
    }

    @Test
    void cameraLightsAndBackgroundCanBeChanged() {
        try (WebViewSurface s = surface()) {
            Scene3DController c = s.scene3d("view").orElseThrow();
            c.setBackground(0xFF0000FF);
            s.frame(0, true);
            int blue = px(s, 25, 15);
            assertTrue((blue & 0xFF) > 200 && ((blue >> 16) & 0xFF) < 40, "背景が青: " + Integer.toHexString(blue));

            int before = px(s, 80 - 35, 60);
            c.setDirectionalLight(0, 0, 1, 0xFFFFFFFF, 1.0); // 裏から照らす → 手前の面は暗く
            c.setAmbientLight(0xFF000000, 0.0);
            s.frame(16, false);
            int dark = px(s, 80 - 35, 60);
            assertTrue(((dark >> 8) & 0xFF) < ((before >> 8) & 0xFF) / 2, "ライトを変えると絵が変わる: " + Integer.toHexString(before) + " → " + Integer.toHexString(dark));

            assertTrue(c.addPointLight(-1.4, 0, 2, 0xFFFFFFFF, 6.0, 8.0));
            s.frame(32, false);
            int lit = px(s, 80 - 35, 60);
            assertTrue(((lit >> 8) & 0xFF) > ((dark >> 8) & 0xFF) + 40, "ポイントライトで明るくなる");
            c.clearExtraLights();

            c.setCamera(0, 0, 8, 0, 0, 0, 30);
            c.setBloom(true, 0.8, 0.5, 6.0);
            assertTrue(s.frame(48, false), "カメラとブルームの変更も描き直しを起こす");
        }
    }

    @Test
    void aBadGltfIsRejectedAndTheSceneIsUntouched() {
        try (WebViewSurface s = surface()) {
            Scene3DController c = s.scene3d("view").orElseThrow();
            assertThrows(IllegalArgumentException.class, () -> c.loadGltf(new byte[]{1, 2, 3, 4}));
            assertThrows(IllegalArgumentException.class, () -> c.loadGltf("{}".getBytes()));
            s.frame(0, true);
            assertTrue(c.raycast(60, 50).isEmpty(), "失敗したので何も増えていない（文書の箱は左にある）");
        }
    }

    @Test
    void unknownNodesAreRejectedAndRemovedNodesDisappear() {
        try (WebViewSurface s = surface()) {
            Scene3DController c = s.scene3d("view").orElseThrow();
            assertThrows(IllegalArgumentException.class, () -> c.setPosition(9999, 0, 0, 0));
            int box = c.addBox(1.2, 1.2, 1.2, 0xFFFF0000);
            s.frame(0, true);
            assertTrue(c.raycast(60, 50).isPresent());
            c.setVisible(box, false);
            assertTrue(c.raycast(60, 50).isEmpty(), "隠したら当たらない");
            c.setVisible(box, true);
            c.removeNode(box);
            assertTrue(c.raycast(60, 50).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> c.removeNode(box), "二重に外せない");
        }
    }

    @Test
    void aStaticSceneIsNotRedrawnButAnApiChangeIs() {
        try (WebViewSurface s = surface()) {
            Scene3DController c = s.scene3d("view").orElseThrow();
            assertTrue(s.frame(0, false));
            assertFalse(s.frame(16, false), "静止した 3D は描き直さない（StaticFrameGate）");
            assertFalse(s.needsFrame());
            int box = c.addBox(1, 1, 1, 0xFFFFFFFF);
            assertTrue(s.needsFrame(), "API でノードを足したら次のフレームが要る");
            assertTrue(s.frame(32, false));
            c.setRotationDegrees(box, 0, 30, 0);
            assertTrue(s.frame(48, false));
            assertFalse(s.frame(64, false));
        }
    }
}
