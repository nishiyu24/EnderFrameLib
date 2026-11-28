package com.nishiyu.enderframelib.demo.three;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Three.js 風の {@link Scene3D} API: 物体の追加・変形・材質の変更・クリック/ホバー・ドラッグ後のクリック抑止・オービット。 */
class Scene3DApiTest {

    private static final String HTML = "<html><body style='margin:0'>"
            + "<scene-3d id='s' style='position:absolute;left:100px;top:50px;width:200px;height:100px'></scene-3d>"
            + "</body></html>";

    private static final class NullBackend implements com.nishiyu.enderframelib.core.paint.RenderBackend {
        @Override public void render(com.nishiyu.enderframelib.core.paint.PreparedFrame frame) { }
        @Override public com.nishiyu.enderframelib.core.raster.Pixmap pixmap() { throw new UnsupportedOperationException(); }
        @Override public float devicePixelRatio() { return 1f; }
    }

    private StandaloneView view;
    private Scene3D scene;

    private Mesh setUp() {
        view = new StandaloneView(HTML, 400, 300, FontResolver.FALLBACK);
        scene = Scene3D.of(view, "s");
        scene.camera().position(0, 0, 6).lookAt(0, 0, 0).fov(40);
        Mesh box = new Mesh(Geometry.box(2, 2, 2), new Material(0xFF0000));
        scene.add(box);
        view.frame(1000, new NullBackend(), true);
        return box;
    }

    private void click(double x, double y) {
        view.mouseMove(x, y, "t");
        view.mouseButton(true, "t");
        view.mouseButton(false, "t");
    }

    @Test
    void missingSceneIsAnError() {
        StandaloneView v = new StandaloneView(HTML, 400, 300, FontResolver.FALLBACK);
        assertThrows(IllegalArgumentException.class, () -> Scene3D.of(v, "nope"));
    }

    @Test
    void positionAndMaterialChangesReachTheWorld() {
        Mesh box = setUp();
        var world = view.scene3dWorldById("s");
        long before = world.changeCount();
        box.position.set(1, 2, 3);
        assertTrue(world.changeCount() > before);
        var trs = world.arena().localTrs();
        assertEquals(1f, trs.get(box.node * 10), 0f);
        before = world.changeCount();
        box.material().color(0x00FF00).metalness(0.9);
        assertTrue(world.changeCount() > before, "材質の変更も再描画のきっかけになる");
        assertEquals(0xFF00FF00, world.materials().get(world.arena().materialId(box.node)).baseColorArgb());
    }

    @Test
    void clickingAMeshFiresItsListenerWithTheHit() {
        Mesh box = setUp();
        List<Scene3D.Hit> hits = new ArrayList<>();
        box.onClick(hits::add);
        click(200, 100); // シーンの中心
        assertEquals(1, hits.size());
        assertSame(box, hits.get(0).object());
        assertTrue(hits.get(0).distance() > 0);
        click(105, 55); // 隅（何も無い）
        assertEquals(1, hits.size());
    }

    @Test
    void hoverFiresEnterAndLeave() {
        Mesh box = setUp();
        List<String> log = new ArrayList<>();
        box.onPointerEnter(h -> log.add("enter")).onPointerLeave(h -> log.add("leave"));
        view.mouseMove(200, 100, "t");
        scene.updateHover();
        view.mouseMove(205, 100, "t");
        scene.updateHover();
        view.mouseMove(105, 55, "t"); // 間引かれて mousemove が来なくても、見直しで外れたと分かる
        scene.updateHover();
        assertEquals(List.of("enter", "leave"), log);
    }

    @Test
    void aDragThatOrbitsTheCameraIsNotAClick() {
        Mesh box = setUp();
        scene.orbitControls(true);
        int[] clicks = {0};
        box.onClick(h -> clicks[0]++);
        double x0 = scene.camera().position()[0];
        view.mouseMove(200, 100, "t");
        view.mouseButton(true, "t");
        view.mouseMove(240, 100, "t");
        view.mouseButton(false, "t");
        assertNotEquals(x0, scene.camera().position()[0], "ドラッグでカメラが回る");
        assertEquals(0, clicks[0]);
    }

    @Test
    void removedObjectsAreNoLongerDrawnOrPicked() {
        Mesh box = setUp();
        int[] clicks = {0};
        box.onClick(h -> clicks[0]++);
        scene.remove(box);
        click(200, 100);
        assertEquals(0, clicks[0]);
    }
}
