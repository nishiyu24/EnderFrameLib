package com.nishiyu.enderframelib.core.layout;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.scene3d.Scene3DTags;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** X3-0: ビルトイン {@code <scene-3d>} の接続（置換要素・測定・識別データ・空シーンの安全性・層の純度）。 */
class Scene3DConnectionTest {

    @AfterEach
    void reset() {
        ReplacedElements.resetToDefaults();
    }

    private static List<DisplayItem> render(String html) {
        StandaloneView view = new StandaloneView(html, 640, 480, FontResolver.FALLBACK);
        view.ui().flushInvalidation();
        return view.ui().renderCache();
    }

    private static DisplayItem scene(List<DisplayItem> items) {
        return items.stream().filter(i -> i.type() == ElementType.SCENE).findFirst().orElse(null);
    }

    @Test
    void sceneTagIsBuiltInReplacedElement() {
        assertTrue(ReplacedElements.isReplacedTag("scene-3d"));
        assertTrue(ReplacedElements.isHostScene("scene-3d"));
        assertFalse(ReplacedElements.isHostImageLike("scene-3d"));
        // SPI の宣言が無くても（resetToDefaults 後も）ビルトインとして残る
        ReplacedElements.resetToDefaults();
        assertTrue(ReplacedElements.isHostScene("scene-3d"));
        // 記述要素は置換要素ではない
        for (String t : Scene3DTags.CHILDREN) assertFalse(ReplacedElements.isReplacedTag(t), t);
    }

    @Test
    void emptySceneMeasuresIntrinsic300x150() {
        DisplayItem s = scene(render("<div><scene-3d></scene-3d></div>"));
        assertNotNull(s, "空の scene-3d でも SCENE 項目が出る（クラッシュしない）");
        assertEquals(300, s.width());
        assertEquals(150, s.height());
    }

    @Test
    void explicitCssSizeWinsOverIntrinsic() {
        DisplayItem s = scene(render("<div><scene-3d style='width:420px;height:280px'></scene-3d></div>"));
        assertNotNull(s);
        assertEquals(420, s.width());
        assertEquals(280, s.height());
    }

    @Test
    void borderAddsToIntrinsicBox() {
        DisplayItem s = scene(render("<div><scene-3d style='border:5px solid red'></scene-3d></div>"));
        assertNotNull(s);
        assertEquals(300, s.width(), "内容は固有サイズのまま（枠は外側へ足される）");
    }

    @Test
    void hostSceneStillFillsParentWidth() {
        DisplayItem s = scene(render("<div style='width:200px'><mc-scene></mc-scene></div>"));
        assertNotNull(s);
        assertEquals(200, s.width(), "ホストの SCENE は従来どおり親の幅へ広がる");
        assertEquals(120, s.height());
    }

    @Test
    void sceneChildrenProduceNoBoxesButSceneStillRenders() {
        String html = "<scene-3d id='p' style='width:200px;height:100px'>"
                + "<camera-3d position='0 2 5'></camera-3d><light-3d type='directional'></light-3d>"
                + "<mesh-3d id='hero' src='a.glb'></mesh-3d><group-3d><mesh-3d></mesh-3d></group-3d>"
                + "<ui-panel-3d selector='#x'></ui-panel-3d></scene-3d><p>after</p>";
        List<DisplayItem> items = render(html);
        assertNotNull(scene(items));
        assertEquals(200, scene(items).width());
        for (DisplayItem i : items) {
            String id = i.id();
            for (String t : Scene3DTags.CHILDREN) assertFalse(id.contains(t), "2D の項目が出ている: " + id);
        }
    }

    @Test
    void sceneItemCarriesWorldIdentity() {
        DisplayItem a = scene(render("<div><scene-3d id='a'></scene-3d></div>"));
        assertNotNull(a);
        assertEquals(Scene3DTags.KIND_3D, a.events().get(Scene3DTags.KIND_ATTR));
        String world = a.events().get(Scene3DTags.WORLD_ATTR);
        assertNotNull(world);
        assertTrue(Long.parseLong(world) >= 0);
    }

    @Test
    void twoScenesGetDistinctWorldIds() {
        List<DisplayItem> items = render("<div><scene-3d id='a'></scene-3d><scene-3d id='b'></scene-3d></div>");
        List<String> ids = items.stream().filter(i -> i.type() == ElementType.SCENE)
                .map(i -> i.events().get(Scene3DTags.WORLD_ATTR)).toList();
        assertEquals(2, ids.size());
        assertNotEquals(ids.get(0), ids.get(1));
    }

    @Test
    void hostSceneHasNoWorldIdentity() {
        DisplayItem s = scene(render("<div><mc-scene></mc-scene></div>"));
        assertNotNull(s);
        assertNull(s.events().get(Scene3DTags.WORLD_ATTR));
        assertNull(s.events().get(Scene3DTags.KIND_ATTR));
    }

    @Test
    void scene3dPackageHasNoUpperLayerOrGlDependency() throws Exception {
        Path dir = Path.of("src/main/java/com/nishiyu/enderframelib/core/scene3d");
        if (!Files.isDirectory(dir)) dir = Path.of("core").resolve(dir);
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String src = Files.readString(p).replaceAll("(?s)/\\*.*?\\*/", "");
                for (String bad : List.of("core.dom", "core.layout", "core.css", "core.paint", "org.lwjgl", "java.awt", "javax.swing")) {
                    assertFalse(src.contains("import " + bad) || src.contains("import static " + bad),
                            p.getFileName() + " が " + bad + " を参照している");
                }
            }
        }
    }
}
