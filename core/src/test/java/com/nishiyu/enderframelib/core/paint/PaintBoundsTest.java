package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.TestBoxes;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * S7 ①: {@link PaintBounds} の復元・ネスト・例外安全、および {@code <fieldset><legend>}・
 * インライン断片の背景描画が箱（M3b-3 以降は {@link TestBoxes.Box}）の幾何を一切書き換えないことを検証する。
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
        var scene = compiler.layoutDocument(doc, 400, 400, Map.of(), new ScrollState(),
                FontResolver.FALLBACK, new InteractionState());
        SceneAdapterBox adapterRoot = SceneAdapterBox.root(scene, null);
        SceneAdapterBox sb = findAdapter(adapterRoot, "b");
        SceneAdapterBox so = findAdapter(adapterRoot, "o");
        assertNotNull(sb);
        assertNotNull(so);

        float bx0 = sb.x(), by0 = sb.y(), bw0 = sb.w(), bh0 = sb.h();
        float bpL0 = sb.pL(), bbT0 = sb.bT(), ox0 = so.x();
        assertEquals(4f, bpL0);
        assertEquals(2f, bbT0);
        assertNotEquals(1f, bx0);
        assertNotEquals(1f, ox0);

        PaintBounds bounds = new PaintBounds();
        bounds.with(sb, 1f, 2f, 30f, 40f, true, () -> {
            assertEquals(1f, bounds.x(sb));
            assertEquals(2f, bounds.y(sb));
            assertEquals(30f, bounds.w(sb));
            assertEquals(40f, bounds.h(sb));
            assertEquals(0f, bounds.pL(sb));
            assertEquals(0f, bounds.bT(sb));
            // 対象外の箱は元の値のまま
            assertEquals(ox0, bounds.x(so));
            // 箱自身は書き換わっていない
            assertEquals(bx0, sb.x());
            assertEquals(bpL0, sb.pL());

            assertThrows(IllegalStateException.class, () -> bounds.with(sb, 5f, 25f, 80f, 30f, false, () -> {
                assertEquals(5f, bounds.x(sb));
                assertEquals(bpL0, bounds.pL(sb));
                assertEquals(bbT0, bounds.bT(sb));
                throw new IllegalStateException("simulated failure");
            }));

            assertEquals(1f, bounds.x(sb));
            assertEquals(0f, bounds.pL(sb));
        });

        assertEquals(bx0, bounds.x(sb));
        assertEquals(by0, bounds.y(sb));
        assertEquals(bw0, bounds.w(sb));
        assertEquals(bh0, bounds.h(sb));
        assertEquals(bpL0, bounds.pL(sb));
        assertEquals(bbT0, bounds.bT(sb));
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
        TestBoxes.Box root = TestBoxes.root(compiler.layoutDocument(doc, 300, 300, Map.of(), new ScrollState(), FontResolver.FALLBACK, new InteractionState()));

        List<TestBoxes.Box> all = new ArrayList<>();
        collectBoxes(root, all);
        float[][] snapshot = new float[all.size()][];
        for (int i = 0; i < all.size(); i++) {
            TestBoxes.Box b = all.get(i);
            snapshot[i] = new float[]{b.x(), b.y(), b.w(), b.h(), b.pL(), b.pT(), b.pR(), b.pB(), b.bL(), b.bT(), b.bR(), b.bB()};
        }

        List<DisplayItem> items = com.nishiyu.enderframelib.common.FrameRender.document(doc, 300, 300, java.util.Map.of(), new com.nishiyu.enderframelib.core.layout.model.ScrollState(), FontResolver.FALLBACK, new com.nishiyu.enderframelib.core.dom.InteractionState()).items();
        assertTrue(items.stream().anyMatch(it -> "fs".equals(it.baseId())));
        assertTrue(items.stream().anyMatch(it -> "sp".equals(it.baseId()) && it.id().contains("_bgl")));

        for (int i = 0; i < all.size(); i++) {
            TestBoxes.Box b = all.get(i);
            float[] now = {b.x(), b.y(), b.w(), b.h(), b.pL(), b.pT(), b.pR(), b.pB(), b.bL(), b.bT(), b.bR(), b.bB()};
            assertArrayEquals(snapshot[i], now, "描画後に箱の幾何が変わってはならない: " + b.node.tag);
        }
    }

    private static SceneAdapterBox findAdapter(SceneAdapterBox b, String id) {
        if (b.node != null && id.equals(b.node.getId())) return b;
        for (SceneAdapterBox c : b.paintChildren()) {
            SceneAdapterBox f = findAdapter(c, id);
            if (f != null) return f;
        }
        return null;
    }

    private static void collectBoxes(TestBoxes.Box b, List<TestBoxes.Box> out) {
        out.add(b);
        for (TestBoxes.Box c : b.paintChildren()) collectBoxes(c, out);
    }
}
