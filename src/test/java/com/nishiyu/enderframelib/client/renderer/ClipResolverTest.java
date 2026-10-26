package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * クリップの連鎖を項目の空間へ直す計算（{@link ClipResolver}）と、描画項目へ連鎖が載ること。
 */
public class ClipResolverTest {

    private static Matrix4f translate(float x, float y) {
        return new Matrix4f().translation(x, y, 0f);
    }

    private static ClipRect.Part part(float x0, float y0, float x1, float y1, String owner, float r) {
        return new ClipRect.Part(new float[]{x0, y0, x1, y1, r, r, r, r}, owner);
    }

    @Test
    void aClipThatStaysPutFollowsBackToTheItemInsideAMovingRail() {
        // 動かない親（view）の overflow 矩形 [0, 1600] と、-1300 動いたレール（の中の項目）。
        // 項目の空間（レイアウトの座標）から見ると、切り口は +1300 ずれた [1300, 2900]。レイアウトの座標のままだと [0, 1600]
        Matrix4f base = new Matrix4f();
        Matrix4f eff = translate(-1300, 0);
        ClipResolver.Result r = ClipResolver.resolve(List.of(part(0, 0, 1600, 300, "view", 0f)), base, eff, id -> new Matrix4f());
        assertNotNull(r.local());
        assertEquals(1300f, r.local()[0], 1e-3f);
        assertEquals(2900f, r.local()[2], 1e-3f);
        assertNull(r.transformed());
        assertTrue(r.screen().isEmpty());
    }

    @Test
    void aClipOwnedByAnElementThatMovesWithTheItemIsUnchanged() {
        // 持ち主（自分の箱で切る背景など）が項目と同じ姿勢で動くなら、切り口も一緒に動くので、項目の空間では変わらない
        Matrix4f eff = translate(40, -10);
        ClipResolver.Result r = ClipResolver.resolve(List.of(part(10, 20, 110, 70, "me", 0f)), new Matrix4f(), eff, id -> new Matrix4f(eff));
        assertEquals(10f, r.local()[0], 1e-3f);
        assertEquals(110f, r.local()[2], 1e-3f);
    }

    @Test
    void moreThanOneTranslationOnlyClipAreIntersectedInTheItemSpace() {
        Matrix4f eff = translate(-100, 0);
        // 動かない外側 [0, 400]（項目の空間では [100, 500]）と、レールに付いて動く内側 [50, 350]（持ち主 = レール。項目の空間では [50, 350]）
        ClipResolver.Result r = ClipResolver.resolve(
                List.of(part(0, 0, 400, 100, null, 0f), part(50, 0, 350, 100, "rail", 0f)),
                new Matrix4f(), eff, id -> new Matrix4f(eff));
        assertEquals(100f, r.local()[0], 1e-3f);
        assertEquals(350f, r.local()[2], 1e-3f);
    }

    @Test
    void aRotatedItemUnderARoundedStaticClipGetsAShaderClipInTheOwnersSpace() {
        // 90 度回った項目（原点まわり）を、動かない角丸の祖先 [0, 100] で切る。矩形では表せないので、
        // 持ち主の空間の角丸矩形と、項目の空間から持ち主の空間への変換（回転）をシェーダーへ渡す
        Matrix4f eff = new Matrix4f().rotateZ((float) Math.toRadians(90));
        ClipResolver.Result r = ClipResolver.resolve(List.of(part(0, 0, 100, 100, null, 20f)), new Matrix4f(), eff, id -> new Matrix4f());
        assertNull(r.local(), "平行移動だけの違いは無い");
        assertNotNull(r.transformed());
        ClipResolver.Transformed t = r.transformed();
        assertEquals(20f, t.rect()[4], 1e-3f, "角丸の半径は持ち主の空間のまま（正確）");
        // 項目の空間の (1, 0) は、90 度回って持ち主の空間の (0, 1)
        float x = t.m00() * 1 + t.m10() * 0 + t.tx();
        float y = t.m01() * 1 + t.m11() * 0 + t.ty();
        assertEquals(0f, x, 1e-4f);
        assertEquals(1f, y, 1e-4f);
        assertEquals(1f, t.distanceScale(), 1e-4f);
        assertEquals(1, r.screen().size(), "持ち主の空間は軸平行なので、画面の矩形としてもシザーで切る");
    }

    @Test
    void aScaledItemMapsDistancesBackThroughTheScale() {
        Matrix4f eff = new Matrix4f().scaling(2f, 2f, 1f);
        ClipResolver.Result r = ClipResolver.resolve(List.of(part(0, 0, 100, 100, null, 10f)), new Matrix4f(), eff, id -> new Matrix4f());
        // 項目の長さ 1 は、持ち主の空間で 2。持ち主の空間の距離は半分にして項目の空間の距離にする
        assertEquals(0.5f, r.transformed().distanceScale(), 1e-4f);
    }

    @Test
    void theInnermostRoundedClipIsTheOneTheShaderGets() {
        Matrix4f eff = new Matrix4f().rotateZ(0.5f);
        ClipResolver.Result r = ClipResolver.resolve(
                List.of(part(0, 0, 500, 500, null, 0f), part(10, 10, 300, 300, null, 30f), part(20, 20, 200, 200, null, 0f)),
                new Matrix4f(), eff, id -> new Matrix4f());
        assertEquals(30f, r.transformed().rect()[4], 1e-3f, "角丸のあるものを優先する");
        assertEquals(3, r.screen().size(), "軸平行の持ち主は全部シザーでも切る");
    }

    @Test
    void anOwnerThatItselfRotatesProducesNoScissorButStillAShaderClip() {
        // 持ち主の空間が回っている（回る overflow の箱）と、画面の軸平行の矩形では表せない
        Matrix4f ownerPose = new Matrix4f().rotateZ(0.4f);
        ClipResolver.Result r = ClipResolver.resolve(List.of(part(0, 0, 100, 100, "spinner", 8f)), new Matrix4f(), new Matrix4f(), id -> ownerPose);
        assertTrue(r.screen().isEmpty());
        assertNotNull(r.transformed());
    }

    @Test
    void translationOnlyIsRecognizedWithinTolerance() {
        assertTrue(ClipResolver.translationOnly(translate(5, 7)));
        assertTrue(!ClipResolver.translationOnly(new Matrix4f().rotateZ(0.1f)));
        assertTrue(!ClipResolver.translationOnly(new Matrix4f().scaling(1.2f, 1f, 1f)));
    }

    // ---- 描画項目へ連鎖が載る ----

    private static List<DisplayItem> render(String html) {
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(html, "", 500, 400);
        ScrollState ss = new ScrollState();
        LayoutBox root = compiler.layoutDocument(doc, 500, 400, Map.of(), ss, FontResolver.FALLBACK, new InteractionState());
        return new DisplayListBuilder(500, 400, doc.sheet.keyframes, ss).render(root);
    }

    @Test
    void theChainKeepsEveryAncestorClipWithItsOwner() {
        List<DisplayItem> items = render("<div id=\"outer\" style=\"overflow:hidden;width:200px;height:100px\">"
                + "<div id=\"inner\" style=\"overflow:hidden;width:100px;height:50px\">"
                + "<div id=\"leaf\" style=\"width:300px;height:30px;background:red\"></div></div></div>");
        DisplayItem leaf = items.stream().filter(i -> i.id().equals("leaf_bg")).findFirst().orElseThrow();
        ClipRect clip = leaf.meta().clipRect();
        assertNotNull(clip);
        assertEquals(2, clip.chain().size());
        assertEquals("outer", clip.chain().get(0).ownerId());
        assertEquals("inner", clip.chain().get(1).ownerId());
        // 合成した矩形は内側（100x50）
        assertEquals(100f, clip.maxX() - clip.minX(), 0.5f);
        assertEquals(50f, clip.maxY() - clip.minY(), 0.5f);
        // 連鎖の矩形は、それぞれの箱の大きさ
        assertEquals(200f, clip.chain().get(0).rect()[2] - clip.chain().get(0).rect()[0], 0.5f);
        assertEquals(100f, clip.chain().get(1).rect()[2] - clip.chain().get(1).rect()[0], 0.5f);
    }

    @Test
    void anAbsoluteChildUsesTheChainOfItsContainingBlockNotTheTreeParent() {
        // overflow:hidden の箱（位置指定なし）の中の absolute は、包含ブロック（relative の祖先）の連鎖を使う
        List<DisplayItem> items = render("<div id=\"cb\" style=\"position:relative;width:300px;height:200px\">"
                + "<div id=\"clipper\" style=\"overflow:hidden;width:50px;height:50px\">"
                + "<div id=\"abs\" style=\"position:absolute;left:10px;top:10px;width:100px;height:100px;background:blue\"></div></div></div>");
        DisplayItem abs = items.stream().filter(i -> i.id().equals("abs_bg")).findFirst().orElseThrow();
        ClipRect clip = abs.meta().clipRect();
        for (ClipRect.Part p : clip == null ? List.<ClipRect.Part>of() : clip.chain()) {
            assertTrue(!"clipper".equals(p.ownerId()), "木の親（clipper）の overflow は掛からない");
        }
    }
}
