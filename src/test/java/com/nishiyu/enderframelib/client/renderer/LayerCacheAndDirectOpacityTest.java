package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.core.paint.model.BlendMode;
import com.nishiyu.enderframelib.core.paint.model.FilterOp;
import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.ImageFitMode;
import com.nishiyu.enderframelib.core.paint.model.LayerSpec;
import com.nishiyu.enderframelib.core.paint.model.RenderHints;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 不透明度だけのレイヤーを使わずに済む条件（{@code isDirectOpacity}）と、使い回しの項目の同一判定。 */
public class LayerCacheAndDirectOpacityTest {

    private static DisplayItem item(String id, ElementType type, int x, int y, int w, int h, String text, RenderMeta meta) {
        return new DisplayItem(id, id, type, x, y, w, h, text, 0, 0x80FF0000, Map.of(), 0, 1f,
                0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, ImageFitMode.FILL, meta);
    }

    private static DisplayItem withOpacity(DisplayItem i, float opacity) {
        return new DisplayItem(i.id(), i.baseNodeId(), i.type(), i.x(), i.y(), i.width(), i.height(), i.text(), i.color(), i.bgColor(),
                i.events(), i.radius(), opacity, i.translateX(), i.translateY(), i.scaleX(), i.scaleY(), i.rotate(),
                i.transitionDuration(), i.animDef(), i.fitMode(), i.meta());
    }

    private static DisplayItem withTranslate(DisplayItem i, float tx) {
        return new DisplayItem(i.id(), i.baseNodeId(), i.type(), i.x(), i.y(), i.width(), i.height(), i.text(), i.color(), i.bgColor(),
                i.events(), i.radius(), i.opacity(), tx, i.translateY(), i.scaleX(), i.scaleY(), i.rotate(),
                i.transitionDuration(), i.animDef(), i.fitMode(), i.meta());
    }

    private static DisplayItem rect(String id, int x, int y, int w, int h) {
        return item(id, ElementType.RECT, x, y, w, h, "", RenderMeta.EMPTY);
    }

    private static LayerSpec opacity(int span) {
        return new LayerSpec(span, 0.5f, List.of(), BlendMode.NORMAL, false);
    }

    private static DisplayItem anchor(String id, LayerSpec spec) {
        return item(id, ElementType.NODE, 0, 0, 100, 100, "", RenderMeta.EMPTY.withHints(RenderHints.NONE.withLayer(spec)));
    }

    @Test
    void singlePlainBoxCanBeDrawnDirectly() {
        LayerSpec spec = opacity(2);
        List<DisplayItem> items = List.of(anchor("a", spec), rect("a_bg", 0, 0, 28, 28));
        assertTrue(DisplayItemDrawer.isDirectOpacity(spec, 0.5f, items, 0));
    }

    @Test
    void nonOverlappingBoxesCanBeDrawnDirectly() {
        LayerSpec spec = opacity(3);
        List<DisplayItem> items = List.of(anchor("a", spec), rect("l", 0, 0, 10, 10), rect("r", 10, 0, 10, 10));
        assertTrue(DisplayItemDrawer.isDirectOpacity(spec, 0.5f, items, 0), "辺が接するだけは重なりではない");
    }

    @Test
    void overlappingBoxesNeedALayer() {
        LayerSpec spec = opacity(3);
        List<DisplayItem> items = List.of(anchor("a", spec), rect("l", 0, 0, 20, 20), rect("r", 10, 10, 20, 20));
        assertFalse(DisplayItemDrawer.isDirectOpacity(spec, 0.5f, items, 0), "重なりは 1 枚に描かないと二重に透ける");
    }

    @Test
    void textImagesAndBordersNeedALayer() {
        LayerSpec spec = opacity(2);
        assertFalse(DisplayItemDrawer.isDirectOpacity(spec, 0.5f,
                List.of(anchor("a", spec), item("t", ElementType.TEXT, 0, 0, 20, 20, "hi", RenderMeta.EMPTY)), 0));
        assertFalse(DisplayItemDrawer.isDirectOpacity(spec, 0.5f,
                List.of(anchor("a", spec), item("i", ElementType.IMAGE, 0, 0, 20, 20, "x.png", RenderMeta.EMPTY)), 0));
        assertFalse(DisplayItemDrawer.isDirectOpacity(spec, 0.5f,
                List.of(anchor("a", spec), item("b", ElementType.ROUNDED_BORDER, 0, 0, 20, 20, "", RenderMeta.EMPTY)), 0));
        // 背景と文字が 1 つの項目に入っている（RECT でも文字を持つ）ものも重なる
        assertFalse(DisplayItemDrawer.isDirectOpacity(spec, 0.5f,
                List.of(anchor("a", spec), item("r", ElementType.RECT, 0, 0, 20, 20, "label", RenderMeta.EMPTY)), 0));
    }

    @Test
    void otherEffectsNeedALayer() {
        List<DisplayItem> body = List.of(rect("r", 0, 0, 10, 10));
        for (LayerSpec spec : List.of(
                new LayerSpec(2, 0.5f, List.of(FilterOp.blur(2f)), BlendMode.NORMAL, false),
                new LayerSpec(2, 0.5f, List.of(), BlendMode.MULTIPLY, false),
                new LayerSpec(2, 0.5f, List.of(), BlendMode.NORMAL, true))) {
            assertFalse(DisplayItemDrawer.isDirectOpacity(spec, 0.5f, List.of(anchor("a", spec), body.get(0)), 0));
        }
    }

    @Test
    void anOpacityThatIsAlreadyOneNeedsNoLayerAtAll() {
        LayerSpec spec = opacity(2);
        assertFalse(DisplayItemDrawer.isDirectOpacity(spec, 1f, List.of(anchor("a", spec), rect("r", 0, 0, 10, 10)), 0),
                "不透明度 1 は呼び出し側がレイヤーを求めない。ここでは直接描画の対象にしない");
    }

    @Test
    void aNestedLayerAnchorInsideNeedsTheOuterLayer() {
        LayerSpec inner = opacity(2);
        LayerSpec outer = opacity(4);
        List<DisplayItem> items = List.of(anchor("o", outer), rect("o_bg", 0, 0, 50, 50), anchor("i", inner), rect("i_bg", 60, 0, 10, 10));
        assertFalse(DisplayItemDrawer.isDirectOpacity(outer, 0.5f, items, 0),
                "中のレイヤーの合成は外の不透明度を受け取れない");
    }

    @Test
    void cacheRequestRecognizesTheSameItemInstancesOnly() {
        DisplayItem a = rect("a", 0, 0, 10, 10), b = rect("b", 0, 0, 10, 10);
        LayerCompositor.CacheRequest req = new LayerCompositor.CacheRequest("s|a", List.of(a, b), 0, 2, 1L);
        assertTrue(req.sameItems(new DisplayItem[]{a, b}));
        // 同じ内容でも別のインスタンスなら、作り直された項目として使い回さない
        assertFalse(req.sameItems(new DisplayItem[]{rect("a", 0, 0, 10, 10), b}));
        assertFalse(req.sameItems(new DisplayItem[]{a}));
    }

    @Test
    void probeLayerTracksChildPaintOffsetsWithoutInvalidatingAnchorOffset() {
        LayerSpec spec = new LayerSpec(3, 0.5f, List.of(FilterOp.blur(2f)), BlendMode.NORMAL, false);
        DisplayItem a = anchor("a", spec);
        DisplayItem childNode = item("cn", ElementType.NODE, 0, 0, 20, 20, "", RenderMeta.EMPTY);
        DisplayItem child = rect("c", 0, 0, 10, 10);
        var offsets = new com.nishiyu.enderframelib.core.paint.model.PaintOffsets();
        var baseProbe = DisplayItemDrawer.probeLayer(List.of(a, childNode, child), 0, spec, "sig-off-" + System.nanoTime(), "", null, 1f, offsets);
        offsets.set("a", 40f, 0f);
        assertEquals(baseProbe.signature(), DisplayItemDrawer.probeLayer(List.of(a, childNode, child), 0, spec, "sig-off-" + System.nanoTime(), "", null, 1f, offsets).signature(),
                "錨の外部平行移動は中身の要約を変えない");
        offsets.set("cn", 12f, 0f);
        var childMovedProbe = DisplayItemDrawer.probeLayer(List.of(a, childNode, child), 0, spec, "sig-off-" + System.nanoTime(), "", null, 1f, offsets);
        assertNotEquals(baseProbe.signature(), childMovedProbe.signature(), "子要素の外部平行移動は中身の要約を変える");
        assertTrue(childMovedProbe.boxRect() == null, "子要素が外部平行移動で動いた時は固定の boxRect を使わない");
    }

    @Test
    void paintRequestFreezesMutablePaintOffsetsAtHostBoundary() {
        var mutable = new com.nishiyu.enderframelib.core.paint.model.PaintOffsets();
        mutable.set("w", 10f, 20f);
        var req = new PaintRequest(List.of(), mutable, 100, 100, new com.mojang.blaze3d.vertex.PoseStack(),
                null, 0, 0, true, true, true, 0f, 0f, "", 0, false, null, false, false, "s", null, false, false);
        assertTrue(req.paintOffsets().isImmutable());
        mutable.set("w", 99f, 99f);
        assertEquals(10f, req.paintOffsets().dx("w"));
        assertEquals(20f, req.paintOffsets().dy("w"));
    }

    private static long signature(DisplayItem anchor, DisplayItem child, LayerSpec spec) {
        return DisplayItemDrawer.probeLayer(List.of(anchor, child), 0, spec, "sig-test-" + System.nanoTime(), "", null, 1f).signature();
    }

    @Test
    void fadingTheAnchorKeepsTheCachedPictureButFadingAChildDoesNot() {
        LayerSpec spec = new LayerSpec(2, 0.5f, List.of(FilterOp.blur(2f)), BlendMode.NORMAL, false);
        DisplayItem a = anchor("a", spec);
        DisplayItem child = rect("c", 0, 0, 10, 10);
        // 錨の不透明度は合成のときに掛けるだけ。フェード中も中身の絵は同じなので、要約は変わらない
        assertEquals(signature(a, child, spec), signature(withOpacity(a, 0.2f), child, spec));
        // 子の不透明度は中身の絵に入る
        assertNotEquals(signature(a, child, spec), signature(a, withOpacity(child, 0.2f), spec));
    }

    @Test
    void movingTheAnchorKeepsTheCachedPictureButMovingAChildDoesNot() {
        LayerSpec spec = new LayerSpec(2, 0.5f, List.of(FilterOp.blur(2f)), BlendMode.NORMAL, false);
        DisplayItem a = anchor("a", spec);
        DisplayItem child = rect("c", 0, 0, 10, 10);
        // 錨の移動は箱の写り（変換）として別に比べ、保持した絵を動かして貼る。絵の要約には入れない
        assertEquals(signature(a, child, spec), signature(withTranslate(a, 40f), child, spec));
        assertNotEquals(signature(a, child, spec), signature(a, withTranslate(child, 40f), spec));
    }

    @Test
    void boxRectIgnoresNodeItemsWithLocalOriginGeometry() {
        LayerSpec spec = new LayerSpec(3, 0.5f, List.of(FilterOp.blur(2f)), BlendMode.NORMAL, false);
        DisplayItem a = item("a", ElementType.NODE, 100, 60, 120, 80, "", RenderMeta.EMPTY.withHints(RenderHints.NONE.withLayer(spec)));
        // 文字の塊の NODE は座標が (0, 0)。何も描かないので、外接矩形に入れると矩形が画面の左上まで伸びてしまう
        DisplayItem textNode = item("txt", ElementType.NODE, 0, 0, 28, 24, "", RenderMeta.EMPTY);
        DisplayItem glyphs = item("txt_0", ElementType.TEXT, 110, 70, 28, 24, "hi", RenderMeta.EMPTY);
        float[] rect = DisplayItemDrawer.probeLayer(List.of(a, textNode, glyphs), 0, spec, "rect-test-" + System.nanoTime(), "", null, 1f).boxRect();
        org.junit.jupiter.api.Assertions.assertArrayEquals(new float[]{100f, 60f, 220f, 140f}, rect);
    }
}
