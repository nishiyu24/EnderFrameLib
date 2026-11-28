package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.ClipShape;
import com.nishiyu.enderframelib.core.paint.model.FilterOp;
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
        // 箱は (10, 10) 100x60: ローカル座標（親相対ノード基準）では中心は (50, 30)
        assertEquals(50f, shape.data()[0], 0.5f);
        assertEquals(30f, shape.data()[1], 0.5f);
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
        int start = indexOf(r, "m"), content = indexOf(r, "c_bg");
        // 中身（子の背景）は層の中身でマスクの印が無く、マスクの項目は錨の子で中身の後ろ（列の階層の印）
        assertTrue(inside(r, content, start) && !r.structure.isMask(content));
        List<DisplayItem> maskItems = maskItemsOf(r, start);
        assertFalse(maskItems.isEmpty());
        assertTrue(r.elements.indexOf(maskItems.getFirst()) > content, "マスクの項目は中身の後ろ");
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
        List<DisplayItem> maskItems = maskItemsOf(r, indexOf(r, "m"));
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
        assertNotNull(layerOf(r, "a"));
        assertNotNull(layerOf(r, "b"));
        assertNotNull(layerOf(r, "c"));
    }

    // ==========================================

    /** 描画器の準備と同じく組んだ列と、その階層（層の中身は錨の子孫、マスクの項目は階層の印。M3b-4c-2b-3b-2a）。 */
    private record Rendered(TestBoxes.Box root, List<DisplayItem> elements, com.nishiyu.enderframelib.core.paint.FrameStructure structure) {}

    private static Rendered render(String html) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument(
                "<html><head><style>body { margin: 0; }</style></head><body>" + html + "</body></html>", "", VIEWPORT_W, VIEWPORT_H);
        TestBoxes.Box root = TestBoxes.root(parser.layoutDocument(doc, VIEWPORT_W, VIEWPORT_H, Map.of(), new ScrollState(), FontResolver.FALLBACK));
        FrameRender.Frame frame = FrameRender.document(doc, VIEWPORT_W, VIEWPORT_H, Map.of(), new ScrollState(), FontResolver.FALLBACK,
                new com.nishiyu.enderframelib.core.dom.InteractionState());
        var composed = new com.nishiyu.enderframelib.core.paint.FrameComposer().compose(frame.renderFrame());
        return new Rendered(root, composed.items(), composed.structure());
    }

    /** 位置 {@code j} の項目が位置 {@code anchor} の錨の子孫か（層の中身）。 */
    private static boolean inside(Rendered r, int j, int anchor) {
        for (int p = r.structure.parent(j), guard = 0; p >= 0 && guard < 256; p = r.structure.parent(p), guard++) if (p == anchor) return true;
        return false;
    }

    /** 錨 {@code anchor} の箱のマスクの項目（印のある、錨の子）。 */
    private static List<DisplayItem> maskItemsOf(Rendered r, int anchor) {
        List<DisplayItem> out = new java.util.ArrayList<>();
        for (int j = 0; j < r.elements.size(); j++) if (r.structure.isMask(j) && r.structure.parent(j) == anchor) out.add(r.elements.get(j));
        return out;
    }

    private static int indexOf(Rendered r, String id) {
        for (int i = 0; i < r.elements.size(); i++) if (r.elements.get(i).id().equals(id)) return i;
        return -1;
    }

    private static LayerSpec layerOf(Rendered r, String id) {
        int i = indexOf(r, id);
        return i < 0 ? null : r.elements.get(i).hints().layer();
    }

    private static TestBoxes.Box find(TestBoxes.Box box, String id) {
        if (id.equals(box.node.attrs.get("id"))) return box;
        for (TestBoxes.Box child : TestBoxes.elements(box)) {
            TestBoxes.Box hit = find(child, id);
            if (hit != null) return hit;
        }
        return null;
    }
}
