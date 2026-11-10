package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.dom.ReplacedKind;
import com.nishiyu.enderframelib.core.dom.CustomElementPainter;
import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** ホスト置換要素の差し込み（CustomElementPainter）: 参照先の解決・シーン寸法・描画経路。 */
class CustomElementPainterTest {

    @AfterEach
    void reset() {
        ReplacedElements.resetToDefaults();
    }

    private static List<DisplayItem> render(String html) {
        StandaloneView view = new StandaloneView(html, 320, 240, FontResolver.FALLBACK);
        view.ui().flushInvalidation();
        return view.ui().renderCache();
    }

    private static DisplayItem first(List<DisplayItem> items, ElementType t) {
        return items.stream().filter(i -> i.type() == t).findFirst().orElse(null);
    }

    @Test
    void defaultPaintersKeepTheMinecraftSrcRules() {
        CustomElementPainter fluid = ReplacedElements.painterOf("mc-fluid");
        assertEquals("fluid:minecraft:water", fluid.resolveSrc(Map.of("fluid", "minecraft:water"), ""));
        assertEquals("fluid:already", fluid.resolveSrc(Map.of("fluid", "x"), "fluid:already"));
        assertEquals("fluid:", fluid.resolveSrc(Map.of(), ""), "属性も src も無いとき（従来どおり接頭辞のみ）");
        CustomElementPainter entity = ReplacedElements.painterOf("mc-entity");
        assertEquals("entity:@self", entity.resolveSrc(Map.of(), ""));
        assertEquals("entity:@target", entity.resolveSrc(Map.of("entity", "@target"), ""));
        assertNull(ReplacedElements.painterOf("img"));
    }

    @Test
    void hostPainterResolvesSrcForRendering() {
        ReplacedElements.register("x-gauge", new CustomElementPainter() {
            @Override public ReplacedKind kind() { return ReplacedKind.IMAGE_LIKE; }
            @Override public String resolveSrc(Map<String, String> attrs, String src) {
                return "gauge:" + attrs.getOrDefault("kind", "none");
            }
        });
        DisplayItem img = first(render("<div><x-gauge kind=power style='width:20px;height:10px'></x-gauge></div>"), ElementType.IMAGE);
        assertNotNull(img, "IMAGE_LIKE として画像項目が出る");
        assertEquals("gauge:power", img.text());
    }

    @Test
    void hostSceneUsesPainterDimensions() {
        ReplacedElements.register("x-view", new CustomElementPainter() {
            @Override public ReplacedKind kind() { return ReplacedKind.SCENE; }
            @Override public int sceneHeight() { return 77; }
        });
        DisplayItem scene = first(render("<div style='width:200px'><x-view></x-view></div>"), ElementType.SCENE);
        assertNotNull(scene, "SCENE として描かれる");
        assertEquals(77, scene.height());
        assertEquals(200, scene.width());
    }

    @Test
    void defaultSceneKeepsOldSize() {
        DisplayItem scene = first(render("<div style='width:200px'><mc-scene></mc-scene></div>"), ElementType.SCENE);
        assertNotNull(scene);
        assertEquals(120, scene.height());
    }

    @Test
    void invalidPainterIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ReplacedElements.register("x-a", (CustomElementPainter) null));
        assertThrows(IllegalArgumentException.class, () -> ReplacedElements.register("x-a", new CustomElementPainter() {
            @Override public ReplacedKind kind() { return null; }
        }));
    }

    @Test
    void measurementPaintAndDispatchNoLongerNameHostTags() throws Exception {
        java.nio.file.Path core = java.nio.file.Path.of("core/src/main/java/com/nishiyu/enderframelib/core");
        for (String f : List.of("layout/BoxMeasurer.java", "paint/ControlPainter.java", "paint/DisplayListBuilder.java")) {
            String s = java.nio.file.Files.readString(core.resolve(f));
            for (String t : List.of("\"mc-scene\"", "\"mc-fluid\"", "\"mc-entity\"", "\"fluid:\"", "\"entity:\"")) {
                assertFalse(s.contains(t), f + " が " + t + " を直書きしている（CustomElementPainter 経由にする）");
            }
        }
    }
}
