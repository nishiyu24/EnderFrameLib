package com.nishiyu.enderframelib.core.layout;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.software.SoftwareScene3DRasterizer;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.scene3d.Scene3DWorld;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

/** X3-3: 宣言的 3D（{@code <scene-3d>} の子要素 → {@link Scene3DWorld}）。属性の読み取り・差分更新・消えた要素の解放・束縛・描画。 */
class Scene3DDeclarativeTest {

    private static final String SCENE = "<scene-3d id='s' style='width:64px;height:64px' data-background='#101820'>"
            + "<camera-3d position='0 0 4' look-at='0 0 0' fov='45'></camera-3d>"
            + "<light-3d type='directional' direction='0 0 -1' color='#ffffff' intensity='1'></light-3d>"
            + "<light-3d type='ambient' color='#ffffff' intensity='0.2'></light-3d>"
            + "<mesh-3d id='hero' shape='box' size='1.5' color='#ff0000' position='0 0 0'></mesh-3d>"
            + "</scene-3d>";

    private static StandaloneView view(String html) {
        StandaloneView v = new StandaloneView("<div>" + html + "</div>", 200, 200, FontResolver.FALLBACK);
        v.ui().flushInvalidation();
        return v;
    }

    private static Scene3DWorld world(StandaloneView v) {
        Scene3DWorld w = v.ui().scene3dWorldById("s");
        assertNotNull(w, "scene-3d の世界ができている");
        return w;
    }

    private static void set(StandaloneView v, String id, String attr, String value) {
        HtmlNode n = v.ui().findNode(id);
        assertNotNull(n, id);
        com.nishiyu.enderframelib.core.dom.parser.DomUtils.updateAttribute(n, attr, value, false);
        v.ui().requestRestyle();
        v.ui().flushInvalidation();
    }

    @Test
    void childrenBecomeNodesCameraAndLights() {
        StandaloneView v = view(SCENE);
        Scene3DWorld w = world(v);
        assertEquals(1, w.arena().aliveCount(), "mesh-3d が 1 ノード");
        assertEquals(4f, w.camera().eyeZ(), 1e-5f);
        assertEquals(45f, (float) Math.toDegrees(w.camera().fovYRadians()), 1e-3f);
        assertEquals(0.2f, w.light().ambientR(), 1e-5f);
        assertEquals(-1f, w.light().dirZ(), 1e-5f);
        assertEquals(0, w.light().extraCount());
        assertEquals(0xFF101820, w.clearColorArgb());
    }

    @Test
    void theDeclaredSceneRendersWhatItDescribes() {
        Scene3DWorld w = world(view(SCENE));
        Pixmap p = SoftwareScene3DRasterizer.render(w, 64, 64);
        int c = p.get(32, 32);
        assertTrue(((c >> 16) & 0xFF) > 150 && (c & 0xFF) < 60, "中央に赤い箱: " + Integer.toHexString(c));
        assertEquals(0xFF101820, p.get(2, 2), "背景色");
    }

    @Test
    void noChangeMeansNoWorldChange() {
        StandaloneView v = view(SCENE);
        Scene3DWorld w = world(v);
        long before = w.changeCount();
        v.ui().requestRestyle();
        v.ui().flushInvalidation();
        v.ui().flushInvalidation();
        assertEquals(before, w.changeCount(), "記述が同じなら世界は触らない（静止した 3D は描き直されない）");
    }

    @Test
    void anAttributeChangeUpdatesOnlyThatNode() {
        StandaloneView v = view(SCENE);
        Scene3DWorld w = world(v);
        long meshes = w.geometry().version();
        set(v, "hero", "position", "1.5 0 0");
        assertEquals(1, w.arena().aliveCount(), "ノードは作り直さない");
        assertEquals(meshes, w.geometry().version(), "形も作り直さない");
        Pixmap p = SoftwareScene3DRasterizer.render(w, 64, 64);
        assertEquals(0xFF101820, p.get(20, 32), "元の位置は空いた");
        int moved = p.get(52, 32);
        assertTrue(((moved >> 16) & 0xFF) > 100, "右へ動いた: " + Integer.toHexString(moved));
    }

    @Test
    void changingTheSameShapeBackAndForthDoesNotGrowThePools() {
        StandaloneView v = view(SCENE);
        Scene3DWorld w = world(v);
        set(v, "hero", "color", "#00ff00");
        int materials = w.materials().count();
        long geometry = w.geometry().version();
        for (int i = 0; i < 6; i++) {
            set(v, "hero", "color", i % 2 == 0 ? "#ff0000" : "#00ff00");
        }
        assertEquals(materials, w.materials().count(), "赤・緑を行き来しても材質は増えない（同じ記述は共有）");
        assertEquals(geometry, w.geometry().version());
    }

    @Test
    void pointLightsAndSpotsFillTheExtraLightList() {
        StandaloneView v = view("<scene-3d id='s' style='width:64px;height:64px'>"
                + "<light-3d type='point' position='1 2 3' color='#ff0000' intensity='4' range='9'></light-3d>"
                + "<light-3d type='spot' position='0 3 0' direction='0 -1 0' inner='10' outer='20'></light-3d>"
                + "</scene-3d>");
        Scene3DWorld w = world(v);
        assertEquals(2, w.light().extraCount());
        float[] d = w.light().extraData();
        assertEquals(1f, d[0], 1e-5f);
        assertEquals(9f, d[3], 1e-5f);
        assertEquals(4f, d[4], 1e-5f, "色 × 強さ");
        assertEquals(0f, w.light().intensity(), "主ライトの記述が無いので消える");
    }

    @Test
    void groupsMoveTheirChildrenAndRemovedElementsAreFreed() {
        StandaloneView v = view("<scene-3d id='s' style='width:64px;height:64px' data-background='#000000'>"
                + "<camera-3d position='0 0 6'></camera-3d>"
                + "<group-3d id='g' position='1 0 0'>"
                + "<mesh-3d id='a' shape='sphere' radius='0.4'></mesh-3d>"
                + "<mesh-3d id='b' shape='plane' size='1 1' position='0 1 0'></mesh-3d>"
                + "</group-3d></scene-3d>");
        Scene3DWorld w = world(v);
        assertEquals(3, w.arena().aliveCount(), "group + 子 2 つ");
        int a = -1;
        for (int i = 0; i < w.arena().highWater(); i++) if (w.arena().meshId(i) >= 0 && w.arena().parentOf(i) >= 0) { a = i; break; }
        assertTrue(a >= 0, "子はグループの下にいる");

        HtmlNode b = v.ui().findNode("b");
        b.parent.removeChild(b);
        v.ui().requestRestyle();
        v.ui().flushInvalidation();
        assertEquals(2, w.arena().aliveCount(), "消えた要素のノードは解放される");

        HtmlNode g = v.ui().findNode("g");
        g.parent.removeChild(g);
        v.ui().requestRestyle();
        v.ui().flushInvalidation();
        assertEquals(0, w.arena().aliveCount(), "グループを消せば子も一緒に（二重解放なし）");
    }

    @Test
    void invalidAttributesFallBackToDefaultsWithoutThrowing() {
        StandaloneView v = view("<scene-3d id='s' style='width:64px;height:64px'>"
                + "<camera-3d position='a b c' fov='wide'></camera-3d>"
                + "<mesh-3d shape='teapot' size='x y' color='nonsense' position='1 2'></mesh-3d>"
                + "<mesh-3d src='not-a-model'></mesh-3d>"
                + "</scene-3d>");
        Scene3DWorld w = world(v);
        assertEquals(2, w.arena().aliveCount());
        assertEquals(5f, w.camera().eyeZ(), 1e-5f, "既定のカメラ位置");
        SoftwareScene3DRasterizer.render(w, 32, 32); // 描いても落ちない
    }

    @Test
    void anImageAttributeTexturesTheMesh() throws Exception {
        byte[] png = com.nishiyu.enderframelib.core.raster.PngEncoder.encode(2, 1, new int[]{0xFFFF0000, 0xFF0000FF});
        String uri = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png);
        StandaloneView v = view("<scene-3d id='s' style='width:64px;height:64px' data-background='#000000'>"
                + "<camera-3d position='0 0 3.2'></camera-3d>"
                + "<light-3d type='ambient' color='#ffffff' intensity='1'></light-3d>"
                + "<mesh-3d shape='box' size='2 2 0.2' unlit texture='" + uri + "'></mesh-3d></scene-3d>");
        Pixmap p = SoftwareScene3DRasterizer.render(world(v), 64, 64);
        int left = p.get(22, 32), right = p.get(42, 32);
        assertTrue(((left >> 16) & 0xFF) > 200 && (left & 0xFF) < 60, "左は赤: " + Integer.toHexString(left));
        assertTrue((right & 0xFF) > 200 && ((right >> 16) & 0xFF) < 60, "右は青: " + Integer.toHexString(right));
    }

    @Test
    void aSceneThatDisappearsDropsItsSyncState() {
        StandaloneView v = view(SCENE);
        world(v);
        HtmlNode s = v.ui().findNode("s");
        s.parent.removeChild(s);
        v.ui().requestRestyle();
        v.ui().flushInvalidation();
        assertNull(v.ui().scene3dWorldById("s"), "要素が無い");
    }
}
