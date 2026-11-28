package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.BlendMode;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.FilterOp;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
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
 * Phase 3b ②: レイヤーに描いて合成する箱がディスプレイリストにどう出るか。
 *
 * グループの不透明度・{@code filter}・{@code mix-blend-mode}・{@code isolation} が、スタッキングコンテキストの錨に
 * {@link LayerSpec}（{@code span} 付き）として載ること、効果の無い箱には載らないこと（FBO を使わない文書は何も変わらない）、
 * {@code span} が錨から子孫の最後までを過不足なく指すこと。
 */
public class LayerDisplayListTest {

    private static final int VIEWPORT_W = 320;
    private static final int VIEWPORT_H = 240;

    @Test
    public void groupOpacityMarksTheAnchorWithTheItemsOfItsSubtree() {
        Rendered r = render("""
                <div id="before" style="height: 10px; background: red;"></div>
                <div id="g" style="opacity: 0.5;">
                  <div id="a" style="height: 20px; background: blue;"></div>
                  <div id="b" style="height: 20px; background: green;"></div>
                </div>
                <div id="g2" style="opacity: 0.5; height: 10px; background: red;"></div>
                """);
        LayerSpec layer = layerOf(r, "g");
        assertNotNull(layer, "不透明度 0.5 の箱がレイヤーになっていない");
        assertEquals(0.5f, layer.opacity(), 0.001f);
        assertTrue(layer.filters().isEmpty());
        assertEquals(BlendMode.NORMAL, layer.blend());
        assertFalse(layer.isolate());

        // 層の中身は錨の子孫（列の階層）。前後の兄弟は入らない
        int start = indexOf(r, "g");
        assertTrue(inside(r, indexOf(r, "a_bg"), start));
        assertTrue(inside(r, indexOf(r, "b_bg"), start));
        for (int j = 0; j < r.elements.size(); j++) {
            String id = r.elements.get(j).id();
            if (id.startsWith("before") || id.startsWith("g2")) assertFalse(inside(r, j, start), id);
        }
        // 子孫は錨の直後に描画順で続き（層にまとめて描ける）、その次は次のコンテキストの錨（付録 E の 8）
        int last = start;
        while (last + 1 < r.elements.size() && inside(r, last + 1, start)) last++;
        for (int j = last + 1; j < r.elements.size(); j++) assertFalse(inside(r, j, start), "子孫が描画順で続いていない");
        assertEquals("g2", r.elements.get(last + 1).id(), "子孫の直後は次のコンテキストの錨");
    }

    @Test
    public void boxesWithoutEffectsGetNoLayer() {
        Rendered r = render("""
                <div id="a" style="height: 20px; background: blue; opacity: 1;">
                  <div id="b" style="position: relative; height: 10px; background: red;">text</div>
                </div>
                <div id="c" style="height: 20px; transform: translateX(4px);"></div>
                """);
        assertTrue(r.elements.stream().allMatch(e -> e.hints().layer() == null), "効果の無い文書にレイヤーがある");
    }

    @Test
    public void nestedLayersNestTheirSpans() {
        Rendered r = render("""
                <div id="outer" style="opacity: 0.8;">
                  <div id="inner" style="opacity: 0.5; filter: blur(2px);">
                    <div id="leaf" style="height: 10px; background: red;"></div>
                  </div>
                  <div id="sibling" style="height: 10px; background: blue;"></div>
                </div>
                """);
        LayerSpec outer = layerOf(r, "outer");
        LayerSpec inner = layerOf(r, "inner");
        assertNotNull(outer);
        assertNotNull(inner);
        int o = indexOf(r, "outer"), i = indexOf(r, "inner");
        assertTrue(inside(r, i, o), "内側のレイヤーの錨が外側の子孫でない");
        assertTrue(inside(r, indexOf(r, "leaf_bg"), i) && inside(r, indexOf(r, "leaf_bg"), o), "葉は両方の層に入る");
        // 兄弟（通常フローのブロック）は外側の中、内側の外
        int sibling = indexOf(r, "sibling");
        assertTrue(inside(r, sibling, o));
        assertFalse(inside(r, sibling, i), "兄弟が内側のレイヤーに入っている");
        assertEquals(1, inner.filters().size());
        assertEquals(FilterOp.Kind.BLUR, inner.filters().getFirst().kind());
    }

    @Test
    public void filterAloneMakesALayerAtFullOpacity() {
        Rendered r = render("<div id=\"f\" style=\"filter: grayscale(1) brightness(1.2); height: 10px; background: red;\"></div>");
        LayerSpec layer = layerOf(r, "f");
        assertNotNull(layer);
        assertEquals(1f, layer.opacity(), 0.001f);
        // 隣り合う色の関数は 1 つの行列に畳まれる
        assertEquals(1, layer.filters().size());
        assertEquals(FilterOp.Kind.COLOR_MATRIX, layer.filters().getFirst().kind());
    }

    @Test
    public void invalidFilterIsIgnored() {
        Rendered r = render("<div id=\"f\" style=\"filter: blur(2px) nonsense(3); height: 10px;\"></div>");
        assertNull(layerOf(r, "f"), "読めない filter はフィルターなし（レイヤーも作らない）");
    }

    @Test
    public void mixBlendModeMakesTheBoxALayerAndIsolatesItsStackingContextParent() {
        Rendered r = render("""
                <div id="parent" style="position: relative; z-index: 0; height: 40px; background: white;">
                  <div id="child" style="mix-blend-mode: multiply; height: 20px; background: red;"></div>
                </div>
                """);
        LayerSpec child = layerOf(r, "child");
        assertNotNull(child);
        assertEquals(BlendMode.MULTIPLY, child.blend());
        // 親はスタッキングコンテキストで、混合する子を含むので、混合を閉じ込めるためにレイヤーになる
        LayerSpec parent = layerOf(r, "parent");
        assertNotNull(parent, "混合する子を含むスタッキングコンテキストがレイヤーになっていない");
        assertTrue(parent.isolate());
        assertEquals(BlendMode.NORMAL, parent.blend());
    }

    @Test
    public void blendingChildOfAPlainBoxIsolatesOnlyAtTheNearestStackingContext() {
        // 間の div はスタッキングコンテキストでない。混合は外側のコンテキスト（outer）の中で起こる
        Rendered r = render("""
                <div id="outer" style="isolation: isolate;">
                  <div id="plain"><div id="child" style="mix-blend-mode: screen; height: 10px; background: red;"></div></div>
                </div>
                """);
        assertTrue(layerOf(r, "outer").isolate());
        assertNull(layerOf(r, "plain"));
        assertEquals(BlendMode.SCREEN, layerOf(r, "child").blend());
    }

    @Test
    public void isolationWithoutBlendingDescendantsNeedsNoLayer() {
        Rendered r = render("<div id=\"i\" style=\"isolation: isolate; height: 10px;\"><div style=\"height: 5px;\"></div></div>");
        assertNull(layerOf(r, "i"));
    }

    @Test
    public void innerBlendIsNotCountedAcrossAnInnerStackingContext() {
        // blend は inner の中で閉じる。outer は inner（opacity で SC）自体が混合しなければ隔離は要らない
        Rendered r = render("""
                <div id="outer" style="isolation: isolate; height: 20px;">
                  <div id="inner" style="position: relative; z-index: 1;">
                    <div id="deep" style="mix-blend-mode: multiply; height: 10px; background: red;"></div>
                  </div>
                </div>
                """);
        assertNull(layerOf(r, "outer"), "内側のコンテキストが閉じ込める混合を外側が数えている");
        assertTrue(layerOf(r, "inner").isolate());
    }

    @Test
    public void animatedOpacityMakesALayerEvenWhenTheStaticValueIsOne() {
        Rendered r = render("""
                <style>
                  @keyframes fade { from { opacity: 0; } to { opacity: 1; } }
                  #a { animation: fade 1s infinite; height: 10px; background: red; }
                </style>
                <div id="a"></div>
                """);
        assertNotNull(layerOf(r, "a"), "opacity を動かす animation の箱がレイヤーになっていない");
    }

    @Test
    public void nodeOpacityStaysOnTheAnchorForTheTransitionMachinery() {
        Rendered r = render("<div id=\"g\" style=\"opacity: 0.25; height: 10px; background: red;\"></div>");
        DisplayItem anchor = r.elements.stream().filter(e -> e.id().equals("g")).findFirst().orElseThrow();
        assertEquals(ElementType.NODE, anchor.type());
        assertEquals(0.25f, anchor.opacity(), 0.001f);
        assertEquals(0.25f, anchor.hints().layer().opacity(), 0.001f);
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

    private static int indexOf(Rendered r, String id) {
        for (int i = 0; i < r.elements.size(); i++) if (r.elements.get(i).id().equals(id)) return i;
        return -1;
    }

    private static LayerSpec layerOf(Rendered r, String id) {
        int i = indexOf(r, id);
        return i < 0 ? null : r.elements.get(i).hints().layer();
    }
}
