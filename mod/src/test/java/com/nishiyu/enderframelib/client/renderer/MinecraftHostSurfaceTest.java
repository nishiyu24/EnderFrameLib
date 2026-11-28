package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Minecraft の中の面をコアだけで描くための振り分け（M3a）: Minecraft だけが描ける中身は {@link MinecraftHostTextures} が
 * テクスチャにしてコアへ渡し、それ以外（画像・動画・キャンバス・流体・効果のアイコン・システムの書体の文字）はコアが描く。
 * ここが漏れると、その中身は黙って消える（旧レンダラへ落とす経路はもう無い）。
 */
class MinecraftHostSurfaceTest {

    private static List<DisplayItem> items(String body) {
        return SoftwareRendering.displayList("<html><body style='margin:0'>" + body + "</body></html>", 300, 200);
    }

    private static DisplayItem first(List<DisplayItem> list, ElementType type) {
        return list.stream().filter(i -> i.type() == type).findFirst().orElseThrow(() -> new AssertionError(type + " の項目が無い"));
    }

    @Test
    void gameModelsAreDrawnByMinecraft() {
        assertEquals("item", MinecraftHostTextures.kindOf(first(items("<img src='item:minecraft:stone*3' style='display:block;width:20px;height:20px'>"), ElementType.IMAGE)));
        assertEquals("entity", MinecraftHostTextures.kindOf(first(items("<img src='entity:minecraft:pig' style='display:block;width:20px;height:20px'>"), ElementType.IMAGE)));
        assertEquals("player", MinecraftHostTextures.kindOf(first(items("<img src='player:Steve' style='display:block;width:20px;height:20px'>"), ElementType.IMAGE)));
    }

    @Test
    void mediaTheCoreCanLoadStaysWithTheCore() {
        for (String src : new String[]{"fluid:minecraft:water", "effect:minecraft:speed", "https://example.com/a.png",
                "minecraft:textures/gui/icons.png", "data:image/png;base64,AAAA"}) {
            DisplayItem img = first(items("<img src='" + src + "' style='display:block;width:20px;height:20px'>"), ElementType.IMAGE);
            assertNull(MinecraftHostTextures.kindOf(img), src + " はコアが画素を読んで描く（MinecraftMediaBridge）");
        }
        assertNull(MinecraftHostTextures.kindOf(first(items("<canvas width='20' height='20'></canvas>"), ElementType.CANVAS)));
        assertNull(MinecraftHostTextures.kindOf(first(items("<video src='https://example.com/a.mp4' style='display:block;width:20px;height:20px'></video>"), ElementType.VIDEO)));
    }

    @Test
    void mcSceneIsDrawnByMinecraftButScene3dByTheCore() {
        DisplayItem mc = first(items("<mc-scene id='s' data-blocks='0,0,0:minecraft:stone' style='display:block;width:80px;height:60px'></mc-scene>"),
                ElementType.SCENE);
        assertEquals("scene", MinecraftHostTextures.kindOf(mc));
        List<DisplayItem> builtIn = items("<scene-3d style='display:block;width:80px;height:60px'></scene-3d>");
        builtIn.stream().filter(i -> i.type() == ElementType.SCENE)
                .forEach(i -> assertNull(MinecraftHostTextures.kindOf(i), "<scene-3d> はコアの 3D の描画"));
    }

    @Test
    void onlyBitmapFontTextIsDrawnByMinecraft() {
        List<DisplayItem> list = items("<div style='font-family:minecraft;font-size:16px'>Pixel</div>"
                + "<div style='font-family:sans-serif;font-size:16px'>Outline</div>"
                + "<input value='typed' style='font-family:minecraft;width:100px'>");
        DisplayItem pixel = list.stream().filter(i -> "Pixel".equals(i.text())).findFirst().orElseThrow();
        assertEquals("text", MinecraftHostTextures.kindOf(pixel), "Minecraft の書体の文字");
        DisplayItem outline = list.stream().filter(i -> "Outline".equals(i.text())).findFirst().orElseThrow();
        assertNull(MinecraftHostTextures.kindOf(outline), "システムの書体の文字はコアが描く");
        DisplayItem input = first(list, ElementType.INPUT);
        assertNull(MinecraftHostTextures.kindOf(input), "入力欄はキャレットの計算と同じ輪郭の書体でコアが描く");
        list.stream().filter(i -> i.type() == ElementType.RECT && (i.text() == null || i.text().isEmpty()))
                .forEach(i -> assertNull(MinecraftHostTextures.kindOf(i), "中身の無い箱"));
    }

    @Test
    void canvasPixelsAreHandedToTheCoreAndRefreshedOnChange() {
        Canvas2DContext ctx = new Canvas2DContext();
        java.util.Map<com.nishiyu.enderframelib.core.raster.Pixmap, Long> seen = new java.util.WeakHashMap<>();
        assertNull(MinecraftMediaBridge.canvasImage(ctx, seen), "何も描いていなければ描かない");
        ctx.fillRect(0, 0, 4, 4, 0xFFFF0000);
        var img = MinecraftMediaBridge.canvasImage(ctx, seen);
        assertNotNull(img);
        assertEquals(300f, img.cssW());
        int v1 = img.pixmap().version();
        assertEquals(v1, MinecraftMediaBridge.canvasImage(ctx, seen).pixmap().version(), "変わっていなければ版は進まない");
        ctx.fillRect(4, 4, 2, 2, 0xFF00FF00);
        assertTrue(MinecraftMediaBridge.canvasImage(ctx, seen).pixmap().version() > v1, "描き足せば版が進む");
    }

    @Test
    void fluidTintMultipliesChannelsAndTreatsZeroAlphaAsOpaque() {
        // ABGR(白・不透明) × ARGB(0xFF336699) → 0xFF336699
        assertEquals(0xFF336699, MinecraftMediaBridge.tintArgb(0xFFFFFFFF, 0xFF336699));
        assertEquals(0xFF336699, MinecraftMediaBridge.tintArgb(0xFFFFFFFF, 0x00336699), "アルファ 0 は不透明扱い");
        assertEquals(0x80000000, MinecraftMediaBridge.tintArgb(0x80000000, 0xFFFFFFFF));
    }

    @Test
    void hoverStateIsReflectedInItemsSoCoreDrawsTheHoveredLook() {
        com.nishiyu.enderframelib.common.UiDocumentCompiler parser = new com.nishiyu.enderframelib.common.UiDocumentCompiler();
        com.nishiyu.enderframelib.common.Document doc = parser.parseDocument(
                "<style>#b{width:20px;height:20px;background:#ff0000}#b:hover{background:#00ff00}</style><div id='b'></div>", "", 200, 100);
        com.nishiyu.enderframelib.core.dom.InteractionState state = new com.nishiyu.enderframelib.core.dom.InteractionState();
        var scroll = new com.nishiyu.enderframelib.core.layout.model.ScrollState();
        parser.layoutDocument(doc, 200, 100, java.util.Map.of(), scroll, com.nishiyu.enderframelib.core.text.model.FontResolver.FALLBACK, state);
        state.setHoveredNode(doc.root.getElementById("b"));
        List<DisplayItem> hovered = com.nishiyu.enderframelib.common.FrameRender.document(doc, 200, 100, java.util.Map.of(), scroll,
                com.nishiyu.enderframelib.core.text.model.FontResolver.FALLBACK, state).items();
        assertTrue(hovered.stream().anyMatch(i -> "b".equals(i.baseId()) && (i.bgColor() & 0xFFFFFF) == 0x00FF00),
                "ホバー中は緑の背景の項目が出る（コアはその項目をそのまま描く）");
    }

    @Test
    void farWorldSurfacesSwitchToTheSingleColourFill() {
        assertFalse(SurfaceStyle.world(0xF000F0).withLod(0xFF000000, 48 * 48).lodActive(), "64 ブロック以内はコアで描く");
        assertTrue(SurfaceStyle.world(0xF000F0).withLod(0xFF000000, 80 * 80).lodActive(), "64 ブロック超は 1 色で塗る");
    }

    @Test
    void tooltipUsesTheTitleUnlessDisabled() {
        // 要素の位置と部品はシーンから引く（M3b-4c-2b-3b-2b-1。表示項目の列は読まない）
        assertEquals("Hello", tooltipOf("<div id=t title='Hello' style='width:20px;height:20px;background:#f00'></div>"));
        assertEquals("Tip", tooltipOf("<div id=t data-tooltip='Tip' style='width:20px;height:20px;background:#f00'></div>"));
        assertNull(tooltipOf("<div id=t title='false' style='width:20px;height:20px;background:#f00'></div>"));
        assertNull(tooltipOf("<div id=t style='width:20px;height:20px;background:#f00'></div>"));
    }

    private static String tooltipOf(String body) {
        try (var v = new com.nishiyu.enderframelib.common.StandaloneView("<html><body>" + body + "</body></html>", 100, 100,
                com.nishiyu.enderframelib.core.text.model.FontResolver.FALLBACK)) {
            v.frame(1000L, new com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend(100, 100, 1f), true);
            return SurfaceTooltip.textOf(v.ui().locate("t"));
        }
    }
}
