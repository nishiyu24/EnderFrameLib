package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.paint.tree.PaintBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * S7 ①: {@link PaintBounds} の復元・ネスト・例外安全、および {@code <fieldset><legend>}・
 * インライン断片の背景描画が箱（描画用の木の見え方 {@code PaintBox}。M2c-3 までは旧 {@code LayoutBox}）の幾何を一切書き換えないことを検証する。
 */
class PaintBoundsTest {

    @Test
    void paintBoundsHandlesNestingZeroEdgesAndExceptionsWithoutMutatingBox() {
        String html = "<style>body{margin:0}"
                + "#b{margin:20px 0 0 10px;box-sizing:border-box;width:100px;height:50px;"
                + "padding:5px 6px 7px 4px;border:solid #000;border-width:2px 3px 4px 1px}"
                + "#o{margin:0 0 0 99px;width:77px;height:66px}</style>"
                + "<div id=b></div><div id=o></div>";
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(html, "", 400, 400);
        PaintBox root = compiler.layoutDocument(doc, 400, 400, Map.of(), new ScrollState(),
                FontResolver.FALLBACK, new InteractionState());
        List<PaintBox> all = new ArrayList<>();
        collectBoxes(root, all);
        PaintBox box = all.stream().filter(b -> b.node != null && "b".equals(b.node.getId())).findFirst().orElseThrow();
        PaintBox other = all.stream().filter(b -> b.node != null && "o".equals(b.node.getId())).findFirst().orElseThrow();

        float bx0 = box.x(), by0 = box.y(), bw0 = box.w(), bh0 = box.h();
        float bpL0 = box.pL(), bbT0 = box.bT(), ox0 = other.x();
        assertEquals(4f, bpL0);
        assertEquals(2f, bbT0);
        assertNotEquals(1f, bx0);
        assertNotEquals(1f, ox0);

        PaintBounds bounds = new PaintBounds();
        bounds.with(box, 1f, 2f, 30f, 40f, true, () -> {
            assertEquals(1f, bounds.x(box));
            assertEquals(2f, bounds.y(box));
            assertEquals(30f, bounds.w(box));
            assertEquals(40f, bounds.h(box));
            assertEquals(0f, bounds.pL(box));
            assertEquals(0f, bounds.bT(box));
            // 対象外の箱は元の値のまま
            assertEquals(ox0, bounds.x(other));
            // 箱自身は書き換わっていない
            assertEquals(bx0, box.x());
            assertEquals(bpL0, box.pL());

            assertThrows(IllegalStateException.class, () -> bounds.with(box, 5f, 25f, 80f, 30f, false, () -> {
                assertEquals(5f, bounds.x(box));
                assertEquals(bpL0, bounds.pL(box));
                assertEquals(bbT0, bounds.bT(box));
                throw new IllegalStateException("simulated failure");
            }));

            assertEquals(1f, bounds.x(box));
            assertEquals(0f, bounds.pL(box));
        });

        assertEquals(bx0, bounds.x(box));
        assertEquals(by0, bounds.y(box));
        assertEquals(bw0, bounds.w(box));
        assertEquals(bh0, bounds.h(box));
        assertEquals(bpL0, bounds.pL(box));
        assertEquals(bbT0, bounds.bT(box));
    }

    @Test
    void fieldsetLegendAndInlineFragmentGradientPaintWithoutMutatingBoxes() {
        String html = "<style>body{margin:0}"
                + "fieldset{margin:10px;border:4px solid #333;padding:8px;background:linear-gradient(#eee,#ccc)}"
                + "legend{padding:2px 6px;font-size:20px}"
                + "span.g{background:linear-gradient(#f00,#00f);padding:2px;border:1px solid #000}"
                + "</style>"
                + "<fieldset id='fs'><legend>Title</legend><div>body</div></fieldset>"
                + "<div style='width:90px'><span id='sp' class='g'>alpha beta gamma delta</span></div>";

        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(html, "", 300, 300);
        PaintBox root = compiler.layoutDocument(doc, 300, 300, Map.of(), new ScrollState(), FontResolver.FALLBACK, new InteractionState());

        List<PaintBox> all = new ArrayList<>();
        collectBoxes(root, all);
        float[][] snapshot = new float[all.size()][];
        for (int i = 0; i < all.size(); i++) {
            PaintBox b = all.get(i);
            snapshot[i] = new float[]{b.x(), b.y(), b.w(), b.h(), b.pL(), b.pT(), b.pR(), b.pB(), b.bL(), b.bT(), b.bR(), b.bB()};
        }

        List<DisplayItem> items = com.nishiyu.enderframelib.common.FrameRender.document(doc, 300, 300, java.util.Map.of(), new com.nishiyu.enderframelib.core.layout.model.ScrollState(), FontResolver.FALLBACK, new com.nishiyu.enderframelib.core.dom.InteractionState()).items();
        assertTrue(items.stream().anyMatch(it -> "fs".equals(it.baseId())));
        assertTrue(items.stream().anyMatch(it -> "sp".equals(it.baseId()) && it.id().contains("_bgl")));

        for (int i = 0; i < all.size(); i++) {
            PaintBox b = all.get(i);
            float[] now = {b.x(), b.y(), b.w(), b.h(), b.pL(), b.pT(), b.pR(), b.pB(), b.bL(), b.bT(), b.bR(), b.bB()};
            assertArrayEquals(snapshot[i], now, "描画後に箱の幾何が変わってはならない: " + b.node.tag);
        }
    }

    private static void collectBoxes(PaintBox b, List<PaintBox> out) {
        out.add(b);
        for (PaintBox c : b.paintChildren()) collectBoxes(c, out);
    }
}
