package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.BlendMode;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.FilterOp;
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

        // span は錨から子孫の最後まで。直後の項目は子孫でない
        int start = indexOf(r, "g");
        List<DisplayItem> inside = r.elements.subList(start, start + layer.span());
        assertTrue(inside.stream().anyMatch(e -> e.id().equals("a_bg")));
        assertTrue(inside.stream().anyMatch(e -> e.id().equals("b_bg")));
        assertTrue(inside.stream().noneMatch(e -> e.id().startsWith("before") || e.id().startsWith("g2")));
        // 不透明度のある箱は位置指定と同じ層（付録 E の 8）に描かれるので、span の直後は次のコンテキストの錨
        assertEquals("g2", r.elements.get(start + layer.span()).id(), "span の直後は次のコンテキストの錨");
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
        assertTrue(i > o && i + inner.span() <= o + outer.span(), "内側のレイヤーが外側の span に収まっていない");
        // 兄弟（通常フローのブロック）は外側の中、内側の外（内側は位置指定と同じ層で、兄弟より後に描かれる）
        int sibling = indexOf(r, "sibling");
        assertTrue(sibling > o && sibling < o + outer.span());
        assertTrue(sibling < i || sibling >= i + inner.span(), "兄弟が内側のレイヤーに入っている");
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
}
