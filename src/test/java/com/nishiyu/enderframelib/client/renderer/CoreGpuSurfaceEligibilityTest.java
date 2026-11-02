package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** コアの GPU バックエンドに任せてよい面かの判定（ここが甘いと、描けない要素が黙って消える）。 */
class CoreGpuSurfaceEligibilityTest {

    private static List<DisplayItem> items(String body) {
        return SoftwareRendering.displayList("<html><body style='margin:0'>" + body + "</body></html>", 200, 100);
    }

    @Test
    void plainBoxesAndTextAreDrawable() {
        assertTrue(CoreGpuSurface.drawable(items(
                "<div style='width:50px;height:20px;background:#f00;border-radius:6px;box-shadow:0 2px 4px #000'>Hello</div>")));
    }

    @Test
    void canvasIsDrawable() {
        List<DisplayItem> list = items("<canvas width='20' height='20'></canvas>");
        assertTrue(list.stream().anyMatch(i -> i.type() == ElementType.CANVAS), "キャンバスの項目がある");
        assertTrue(CoreGpuSurface.drawable(list));
    }

    @Test
    void canvasPixelsAreHandedToTheCoreAndRefreshedOnChange() {
        Canvas2DContext ctx = new Canvas2DContext();
        java.util.Map<com.nishiyu.enderframelib.core.raster.Pixmap, Long> seen = new java.util.WeakHashMap<>();
        assertNull(McMediaImages.canvasImage(ctx, seen), "何も描いていなければ描かない");
        ctx.fillRect(0, 0, 4, 4, 0xFFFF0000);
        var img = McMediaImages.canvasImage(ctx, seen);
        assertNotNull(img);
        assertEquals(300f, img.cssW());
        int v1 = img.pixmap().version();
        assertEquals(v1, McMediaImages.canvasImage(ctx, seen).pixmap().version(), "変わっていなければ版は進まない");
        ctx.fillRect(4, 4, 2, 2, 0xFF00FF00);
        assertTrue(McMediaImages.canvasImage(ctx, seen).pixmap().version() > v1, "描き足せば版が進む");
    }

    @Test
    void videoIsDrawable() {
        List<DisplayItem> list = items("<video src='https://example.com/a.mp4' style='display:block;width:20px;height:20px'></video>");
        assertTrue(list.stream().anyMatch(i -> i.type() == ElementType.VIDEO), "動画の項目がある");
        assertTrue(CoreGpuSurface.drawable(list));
    }

    @Test
    void networkAndTextureImagesAreDrawable() {
        assertTrue(CoreGpuSurface.drawable(items("<img src='https://example.com/a.png' width='20' height='20'>")));
        assertTrue(CoreGpuSurface.drawable(items("<img src='minecraft:textures/gui/icons.png' width='20' height='20'>")));
        assertTrue(CoreGpuSurface.drawable(items("<img src='data:image/png;base64,AAAA' width='20' height='20'>")));
    }

    @Test
    void entityFluidItemImagesAreNotDrawable() {
        for (String src : new String[]{"entity:minecraft:pig", "item:minecraft:stone",
                "player:Steve", "minecraft:textures/gui/sprites/x"}) {
            List<DisplayItem> list = items("<img src='" + src + "' style='display:block;width:20px;height:20px'>");
            assertTrue(list.stream().anyMatch(i -> i.type() == ElementType.IMAGE), src + " は画像の項目になる");
            assertFalse(CoreGpuSurface.drawable(list), src);
        }
    }

    @Test
    void fluidImagesAreDrawable() {
        assertTrue(CoreGpuSurface.drawable(items("<img src='fluid:minecraft:water' style='display:block;width:20px;height:20px'>")));
    }

    @Test
    void fluidTintMultipliesChannelsAndTreatsZeroAlphaAsOpaque() {
        // ABGR(白・不透明) × ARGB(0xFF336699) → 0xFF336699
        assertEquals(0xFF336699, McMediaImages.tintArgb(0xFFFFFFFF, 0xFF336699));
        assertEquals(0xFF336699, McMediaImages.tintArgb(0xFFFFFFFF, 0x00336699), "アルファ 0 は不透明扱い");
        assertEquals(0x80000000, McMediaImages.tintArgb(0x80000000, 0xFFFFFFFF));
    }

    @Test
    void effectIconsAreDrawable() {
        assertTrue(CoreGpuSurface.drawable(items("<img src='effect:minecraft:speed' style='display:block;width:20px;height:20px'>")));
    }

    @Test
    void hoverStyledElementsAreDrawable() {
        List<DisplayItem> list = items("<style>.b:hover{background:#0f0}</style>"
                + "<div class='b' style='width:20px;height:20px;background:#f00'></div>");
        assertNotNull(list.stream().filter(DisplayItem::hasHover).findFirst().orElse(null), "hover 規則のある箱は hasHover になる");
        assertTrue(CoreGpuSurface.drawable(list), "ホバーの見た目は項目に反映されるので、コアが描ける");
    }

    @Test
    void hoverStateIsReflectedInItemsSoCoreDrawsTheHoveredLook() {
        com.nishiyu.enderframelib.common.UiDocumentCompiler parser = new com.nishiyu.enderframelib.common.UiDocumentCompiler();
        com.nishiyu.enderframelib.common.Document doc = parser.parseDocument(
                "<style>#b{width:20px;height:20px;background:#ff0000}#b:hover{background:#00ff00}</style><div id='b'></div>", "", 200, 100);
        com.nishiyu.enderframelib.core.dom.InteractionState state = new com.nishiyu.enderframelib.core.dom.InteractionState();
        var scroll = new com.nishiyu.enderframelib.core.layout.model.ScrollState();
        var root = parser.layoutDocument(doc, 200, 100, java.util.Map.of(), scroll,
                com.nishiyu.enderframelib.core.text.model.FontResolver.FALLBACK, state);
        state.setHoveredNode(doc.root.getElementById("b"));
        parser.restyleLayoutTree(root, doc, 200, 100, java.util.Map.of(), state);
        List<DisplayItem> hovered = new com.nishiyu.enderframelib.core.paint.DisplayListBuilder(200, 100, doc.sheet.keyframes, scroll).render(root);
        assertTrue(CoreGpuSurface.drawable(hovered));
        assertTrue(hovered.stream().anyMatch(i -> "b".equals(i.baseId()) && (i.bgColor() & 0xFFFFFF) == 0x00FF00),
                "ホバー中は緑の背景の項目が出る（コアはその項目をそのまま描く）");
    }
}
