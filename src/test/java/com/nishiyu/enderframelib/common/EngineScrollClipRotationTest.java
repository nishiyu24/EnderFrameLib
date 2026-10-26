package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.renderer.CssAnimator;
import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.BoxDecorations;
import com.nishiyu.enderframelib.core.paint.Transforms;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 長いスクロールするサイトで見つかった 3 つのエンジンの不具合:
 * 一周（360°）の回転が止まって見えた、sticky があるとスクロールするほどスクロール範囲が伸び続けた、
 * 円（角丸の箱）が祖先のクリップにかかると形が歪んだ。
 */
public class EngineScrollClipRotationTest {

    private static final Transforms.Context CTX = new Transforms.Context(100, 100, 800, 600, 16, 16);

    private static float rot(String transform) {
        return Transforms.toTrs(Transforms.parse(transform, CTX))[4];
    }

    @Test
    void fullTurnsOfRotationKeepTheirWinding() {
        // rotate(0deg) → rotate(360deg) のアニメーションが一周するには、360° が 0° に畳まれてはいけない
        assertEquals(360f, rot("rotate(360deg)"), 1e-3f);
        assertEquals(720f, rot("rotate(720deg)"), 1e-3f);
        assertEquals(-360f, rot("rotate(-360deg)"), 1e-3f);
        assertEquals(450f, rot("translate(10px, 5px) rotate(450deg)"), 1e-3f);
        assertEquals(360f, rot("rotate(1turn)"), 1e-3f);
        // これまでの値は変わらない
        assertEquals(0f, rot("rotate(0deg)"), 1e-3f);
        assertEquals(90f, rot("rotate(90deg)"), 1e-3f);
        assertEquals(-90f, rot("rotate(-90deg)"), 1e-3f);
        assertEquals(0f, rot("scale(-1, -1)"), 1e-3f, "負の拡大は反転のまま（180° に化けさせない）");
        assertEquals(30f, Transforms.toTrs(Transforms.compose(null, "30deg", null, null, CTX))[4], 1e-3f);
        assertEquals(360f, Transforms.toTrs(Transforms.compose(null, "360deg", null, null, CTX))[4], 1e-3f);
    }

    @Test
    void aFullTurnKeyframeAnimationReallyAdvances() {
        String src = "<style>@keyframes spin{0%{transform:rotate(0deg)}100%{transform:rotate(360deg)}}"
                + ".a{width:40px;height:40px;animation:spin 12s linear infinite}</style><div id=\"a\" class=\"a\"></div>";
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(src, "", 400, 300);
        ScrollState ss = new ScrollState();
        LayoutBox root = compiler.layoutDocument(doc, 400, 300, Map.of(), ss, FontResolver.FALLBACK, new InteractionState());
        List<DisplayItem> items = new DisplayListBuilder(400, 300, doc.sheet.keyframes, ss).render(root);
        DisplayItem a = items.stream().filter(i -> i.animDef() != null && !i.animDef().isEmpty()).findFirst().orElseThrow();
        String session = "spin-winding-test";
        ClientWebManager.clearSession(session);
        long t0 = 1_000_000L;
        assertEquals(0f, CssAnimator.getValuesAt(session, a, t0).rot(), 0.01f);
        assertEquals(90f, CssAnimator.getValuesAt(session, a, t0 + 3000).rot(), 0.01f);
        assertEquals(270f, CssAnimator.getValuesAt(session, a, t0 + 9000).rot(), 0.01f);
        ClientWebManager.clearSession(session);
    }

    private static LayoutBox findScroller(LayoutBox b) {
        if (b.isScrollContainer() && b.scrollHeight > 1500) return b;
        for (LayoutBox c : b.children) {
            LayoutBox r = findScroller(c);
            if (r != null) return r;
        }
        return null;
    }

    @Test
    void aStickyHeaderWithTextDoesNotStretchTheScrollRangeAsYouScroll() {
        // sticky の中の文字の塊の箱は位置を持たない（x / y は 0）。sticky で祖先が動くとその分だけ箱も平行移動され、
        // スクロール範囲に入ってしまい、スクロールするほど範囲が伸びていた（スクロール位置 + 文字の高さ が下限になる）
        String src = "<style>*{margin:0;padding:0}.sc{position:absolute;left:0;top:0;right:0;bottom:0;overflow-y:auto}"
                + ".nav{position:sticky;top:0;height:40px}.nav span{font-size:16px}.page{height:3000px}</style>"
                + "<div class=\"sc\"><div class=\"nav\"><span>VOIDWAVE</span> <a href=\"#\">link</a></div><div class=\"page\">x</div></div>";
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(src, "", 800, 600);
        ScrollState ss = new ScrollState();
        LayoutBox root = compiler.layoutDocument(doc, 800, 600, Map.of(), ss, FontResolver.FALLBACK, new InteractionState());
        LayoutBox sc = findScroller(root);
        assertNotNull(sc);
        float expected = sc.scrollHeight;
        assertEquals(3040f, expected, 1f, "固定の高さの合計");
        for (int k = 1; k <= 6; k++) {
            ss.setScroll(sc.node, 0, 400f * k);
            RenderPipeline.repositionLayout(root, 800, 600, ss, FontResolver.FALLBACK);
            assertEquals(expected, sc.scrollHeight, 0.5f, "スクロール " + (400 * k) + " でも範囲は同じ");
        }
    }

    @Test
    void anIntersectionCornerInsideTheRoundedShapeStaysSquare() {
        // 100x100 の箱（半径 30）を、角から 20 内側の矩形で切る。交わりの左上の角 (20, 20) は、円弧（中心 (30, 30)・半径 30）の内側にある:
        // 切られていない直角の角なので、半径を付けない（付けると形が歪む）
        float[] square = {0, 0, 100, 100, 30, 30, 30, 30};
        float[] cut = {20, 20, 200, 200};
        float[] out = BoxDecorations.intersectClip(cut, square);
        assertEquals(0f, out[4], 1e-3f, "切られていない直角の角に半径を付けない (r=" + out[4] + ")");
        // 右上の角 (100, 20) は箱の右の辺の上で、円弧（中心 (70, 30)・半径 30）の外。ここは丸めて近似する
        assertTrue(out[5] > 0f && out[5] < 30f, "辺の上の角は切った分だけ小さい半径 (r=" + out[5] + ")");
        // 角のすぐ近く (2, 2) で切ると、交わりの角は円弧の外（角に近い）ので、丸めて近似する
        float[] nearCorner = BoxDecorations.intersectClip(new float[]{2, 2, 200, 200}, square);
        assertTrue(nearCorner[4] > 0f, "円弧の外にある角は丸めて近似する (r=" + nearCorner[4] + ")");
        // 切らないとき（交わりの角が箱の角そのもの）は半径がそのまま
        float[] whole = BoxDecorations.intersectClip(new float[]{-10, -10, 200, 200}, square);
        assertEquals(30f, whole[4], 1e-3f);
    }

    @Test
    void aGradientBoxCrossingAnAncestorClipKeepsItsOwnRoundShape() {
        // 円（半径 = 幅の半分）の背景グラデーションが、overflow:hidden の親から左上へはみ出している。
        // 形（角丸）は箱そのもの、クリップは祖先の矩形のまま。合成した 1 つの角丸矩形にすると、円が歪む
        String src = "<div style=\"position:relative;overflow:hidden;width:100px;height:100px\">"
                + "<div id=\"o\" style=\"position:absolute;left:-60px;top:-60px;width:200px;height:200px;border-radius:50%;"
                + "background:radial-gradient(closest-side, rgb(255,0,0), rgba(255,0,0,0))\"></div></div>";
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(src, "", 400, 300);
        ScrollState ss = new ScrollState();
        LayoutBox root = compiler.layoutDocument(doc, 400, 300, Map.of(), ss, FontResolver.FALLBACK, new InteractionState());
        List<DisplayItem> items = new DisplayListBuilder(400, 300, doc.sheet.keyframes, ss).render(root);
        DisplayItem g = items.stream().filter(i -> i.id().startsWith("o_bgl")).findFirst().orElseThrow();
        assertNotNull(g.meta().borderRadii(), "形は箱の角丸");
        assertEquals(100f, g.meta().borderRadii().topLeft(), 0.01f);
        var clip = g.meta().clipRect();
        assertNotNull(clip, "祖先（親の overflow）のクリップは別に持つ");
        assertNull(clip.ownerId(), "祖先のクリップは、この要素の変形に付いていかない");
        assertEquals(100f, clip.maxX() - clip.minX(), 0.5f, "祖先の矩形のまま（箱と合成しない）");
        assertEquals(0f, clip.radiusTopLeft(), 0.01f, "祖先の矩形に、箱の半径が付かない");
    }
}
