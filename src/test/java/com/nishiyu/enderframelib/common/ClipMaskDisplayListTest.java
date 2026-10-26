package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.ClipShape;
import com.nishiyu.enderframelib.core.paint.FilterOp;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.LayerSpec;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3b ③④: {@code clip-path}・{@code mask}・{@code backdrop-filter} がディスプレイリストにどう出るか。
 * 図形の解き方（{@link ClipShape}）は {@code ClipShapeTest}、ここは箱 → {@link LayerSpec} とマスクの項目の並び。
 */
public class ClipMaskDisplayListTest {

    private static final int VIEWPORT_W = 320;
    private static final int VIEWPORT_H = 240;

    @Test
    public void clipPathMakesALayerWithTheShapeInBoxCoordinates() {
        Rendered r = render("<div id=\"a\" style=\"margin: 10px; width: 100px; height: 60px; background: red; clip-path: circle(20px at 50% 50%);\"></div>");
        LayerSpec layer = layerOf(r, "a");
        assertNotNull(layer, "clip-path の箱がレイヤーになっていない");
        ClipShape shape = layer.clip();
        assertNotNull(shape);
        assertEquals(ClipShape.Kind.CIRCLE, shape.kind());
        // 箱は (10, 10) 100x60: 中心は (60, 40)
        assertEquals(60f, shape.data()[0], 0.5f);
        assertEquals(40f, shape.data()[1], 0.5f);
        assertEquals(20f, shape.data()[2], 0.01f);
        assertTrue(layer.needsLayer(1f));
    }

    @Test
    public void invalidClipPathIsIgnored() {
        Rendered r = render("<div id=\"a\" style=\"height: 20px; clip-path: url(#c);\"></div>"
                + "<div id=\"b\" style=\"height: 20px; clip-path: circle(banana);\"></div>");
        assertNull(layerOf(r, "a"));
        assertNull(layerOf(r, "b"));
    }

    @Test
    public void maskImageAppendsMaskItemsAfterTheContent() {
        Rendered r = render("""
                <div id="m" style="width: 100px; height: 50px; mask-image: linear-gradient(to bottom, #000, transparent);">
                  <div id="c" style="height: 20px; background: red;"></div>
                </div>
                """);
        LayerSpec layer = layerOf(r, "m");
        assertNotNull(layer);
        assertTrue(layer.masked(), "マスクの項目が付いていない");
        assertFalse(layer.maskLuminance());
        int start = indexOf(r, "m");
        // 中身（子の背景）は maskFrom より前、マスクの項目は後ろ（スパンの最後まで）
        assertTrue(r.elements.subList(start, start + layer.maskFrom()).stream().anyMatch(e -> e.id().equals("c_bg")));
        List<DisplayItem> maskItems = r.elements.subList(start + layer.maskFrom(), start + layer.span());
        assertFalse(maskItems.isEmpty());
        for (DisplayItem item : maskItems) {
            assertTrue(item.id().startsWith("m_mask"), "マスクの項目の id: " + item.id());
            assertTrue(item.hints().pointerEventsNone(), "マスクの項目がクリックを受ける: " + item.id());
            assertNotNull(item.hints().shaderGradient(), "グラデーションのマスクはシェーダーで描く");
        }
    }

    @Test
    public void maskShorthandReadsPositionSizeAndRepeat() {
        Rendered r = render("<div id=\"m\" style=\"width: 150px; height: 100px; mask: radial-gradient(#000 60%, transparent 62%) 20px 10px / 60px 60px no-repeat;\"></div>");
        LayerSpec layer = layerOf(r, "m");
        assertNotNull(layer, "mask ショートハンドが読めていない");
        assertTrue(layer.masked());
        int start = indexOf(r, "m");
        List<DisplayItem> maskItems = r.elements.subList(start + layer.maskFrom(), start + layer.span());
        assertFalse(maskItems.isEmpty());
        // 位置 (20, 10)・大きさ 60x60 の 1 枚だけ（no-repeat）。mask-origin の既定は border-box なので箱の左上が基準
        DisplayItem first = maskItems.getFirst();
        assertEquals(20, first.x());
        assertEquals(10, first.y());
        assertEquals(60, first.width());
        assertEquals(60, first.height());
    }

    @Test
    public void maskModeLuminanceIsCarried() {
        Rendered r = render("<div id=\"m\" style=\"height: 20px; mask-image: linear-gradient(90deg, #fff, #000); mask-mode: luminance;\"></div>");
        assertTrue(layerOf(r, "m").maskLuminance());
    }

    @Test
    public void webkitPrefixedMaskIsAnAlias() {
        Rendered r = render("<div id=\"m\" style=\"height: 20px; -webkit-mask-image: linear-gradient(#000, transparent); -webkit-clip-path: inset(2px);\"></div>");
        LayerSpec layer = layerOf(r, "m");
        assertNotNull(layer);
        assertTrue(layer.masked());
        assertNotNull(layer.clip());
    }

    @Test
    public void backdropFilterIsCarriedWithItsShapeAndNeedsNoLayerByItself() {
        Rendered r = render("<div id=\"g\" style=\"width: 100px; height: 60px; border-radius: 12px; backdrop-filter: blur(6px) brightness(1.2);\"></div>");
        LayerSpec layer = layerOf(r, "g");
        assertNotNull(layer);
        assertTrue(layer.hasBackdrop());
        assertEquals(2, layer.backdrop().size());
        assertEquals(FilterOp.Kind.BLUR, layer.backdrop().getFirst().kind());
        assertEquals(ClipShape.Kind.INSET, layer.backdropShape().kind());
        assertEquals(12f, layer.backdropShape().data()[4], 0.01f);
        // backdrop-filter は箱の後ろを加工して貼るだけなので、箱自身をレイヤーに描く理由にはならない
        assertFalse(layer.needsLayer(1f));
    }

    @Test
    public void clipMaskAndBackdropCreateStackingContexts() {
        Rendered r = render("""
                <div id="a" style="height: 10px; clip-path: inset(1px);"></div>
                <div id="b" style="height: 10px; backdrop-filter: blur(1px);"></div>
                <div id="c" style="height: 10px; mask-image: linear-gradient(#000, transparent);"></div>
                """);
        // どれも層 8（位置指定と同じ層）に描かれるので、通常フローのブロックの背景より後
        List<LayoutBox> order = r.renderer.paintOrder(r.root);
        assertTrue(order.indexOf(find(r.root, "a")) >= 0);
        assertNotNull(layerOf(r, "a"));
        assertNotNull(layerOf(r, "b"));
        assertNotNull(layerOf(r, "c"));
    }

    // ==========================================

    private record Rendered(DisplayListBuilder renderer, LayoutBox root, List<DisplayItem> elements) {}

    private static Rendered render(String html) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument(
                "<html><head><style>body { margin: 0; }</style></head><body>" + html + "</body></html>", "", VIEWPORT_W, VIEWPORT_H);
        LayoutBox root = parser.layoutDocument(doc, VIEWPORT_W, VIEWPORT_H, Map.of(), new ScrollState(), FontResolver.FALLBACK);
        DisplayListBuilder renderer = new DisplayListBuilder(VIEWPORT_W, VIEWPORT_H, doc.sheet.keyframes, new ScrollState());
        return new Rendered(renderer, root, renderer.render(root));
    }

    private static int indexOf(Rendered r, String id) {
        for (int i = 0; i < r.elements.size(); i++) if (r.elements.get(i).id().equals(id)) return i;
        return -1;
    }

    private static LayerSpec layerOf(Rendered r, String id) {
        int i = indexOf(r, id);
        return i < 0 ? null : r.elements.get(i).hints().layer();
    }

    private static LayoutBox find(LayoutBox box, String id) {
        if (id.equals(box.node.attrs.get("id"))) return box;
        for (LayoutBox child : box.elementChildren) {
            LayoutBox hit = find(child, id);
            if (hit != null) return hit;
        }
        return null;
    }
}
