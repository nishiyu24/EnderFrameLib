package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.common.TestItems;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.scene.DisplayScene;
import com.nishiyu.enderframelib.core.paint.scene.DynamicSceneState;
import com.nishiyu.enderframelib.core.paint.scene.SceneHit;
import com.nishiyu.enderframelib.core.paint.scene.SceneHitTest;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 描画シーンのヒットテスト（W8）。ポインタ制御（pointer-events・可視性・クリップ・
 * 影の非遮蔽）が、どの要素がクリックを受けるかにそのまま効くことを確かめる。
 */
public class HitTestAndOcclusionTest {

    private static final int VIEWPORT_W = 320;
    private static final int VIEWPORT_H = 200;

    private record SceneCtx(DisplayScene scene, DynamicSceneState dyn) {}

    @Test
    public void testPointerEventsNoneOverlayPassesClicksThrough() {
        SceneCtx index = index("""
                <button id="btn" onclick="act" style="position: absolute; left: 0; top: 0; width: 100px; height: 40px;"></button>
                <div id="overlay" style="position: absolute; left: 0; top: 0; width: 200px; height: 100px; background-color: rgb(255, 255, 255); pointer-events: none;"></div>
                """);
        SceneHit hit = hit(index, 50, 20);
        assertNotNull(hit);
        assertEquals("btn", hit.baseId());
    }

    @Test
    public void testOpaqueOverlayWithoutPointerEventsNoneStillOccludes() {
        SceneCtx index = index("""
                <button id="btn" onclick="act" style="position: absolute; left: 0; top: 0; width: 100px; height: 40px;"></button>
                <div id="overlay" style="position: absolute; left: 0; top: 0; width: 200px; height: 100px; background-color: rgb(255, 255, 255);"></div>
                """);
        SceneHit hit = hit(index, 50, 20);
        assertNotNull(hit);
        assertEquals("overlay", hit.baseId());
    }

    @Test
    public void testVisibilityHiddenElementDoesNotReceiveClicks() {
        SceneCtx index = index("""
                <button id="btn" onclick="act" style="position: absolute; left: 0; top: 0; width: 100px; height: 40px;"></button>
                <div id="cover" onclick="cover" style="position: absolute; left: 0; top: 0; width: 200px; height: 100px; background-color: rgb(255, 255, 255); visibility: hidden;">
                  <div id="shown" onclick="shown" style="visibility: visible; width: 20px; height: 20px; margin-left: 150px; background-color: rgb(0, 0, 0);"></div>
                </div>
                """);
        assertEquals("btn", hit(index, 50, 20).baseId());
        // 隠れた箱の中の visible な子はクリックを受ける
        assertEquals("shown", hit(index, 160, 10).baseId());
    }

    @Test
    public void testShadowIsTransparentToHitTestingAndNormalizesToItsOwner() {
        SceneCtx index = index("""
                <button id="btn" onclick="act" style="position: absolute; left: 0; top: 60px; width: 100px; height: 40px;"></button>
                <div id="card" onclick="card" style="position: absolute; left: 0; top: 0; width: 100px; height: 50px; background-color: rgb(255, 255, 255); box-shadow: 0 20px 6px rgb(0, 0, 0);"></div>
                """);
        // 影はボタンの上に掛かっているが、クリックはボタンに届く
        SceneHit hit = hit(index, 50, 65);
        assertNotNull(hit);
        assertEquals("btn", hit.baseId());
        // 影の番号付きの部品は持ち主の id を持つ（以前は "_shadow_1" を剥がせなかった）
        List<DisplayItem> shadows = render("""
                <div id="card" style="position: absolute; left: 0; top: 0; width: 100px; height: 50px; background-color: rgb(255, 255, 255); box-shadow: 0 20px 6px rgb(0, 0, 0), inset 0 2px 3px rgb(0, 0, 0); border-radius: 6px;"></div>
                """).stream().filter(e -> e.id().startsWith("card_shadow")).toList();
        assertFalse(shadows.isEmpty());
        assertTrue(shadows.stream().allMatch(e -> e.baseId().equals("card")), "影の部品の持ち主は card");
    }

    /**
     * 何が壊れていたか: 描画 ID の末尾を文字列で剥がして持ち主を推測していたため、作者が
     * {@code id="user_img"} や {@code id="search_input"} と書くと別の節（"user" / "search"）に化け、
     * クリックもホバーも届かなかった。持ち主は項目が直接持つので、綴りには依存しない。
     */
    @Test
    public void testNodeIdsThatLookLikeRenderPartSuffixesKeepTheirOwnIdentity() {
        String[] ids = {"user_img", "search_input", "price_label", "a_bg", "x_sb_v", "x_sb_vt", "c_ph", "pane_textarea", "s_shadow_1", "b_b_top"};
        StringBuilder html = new StringBuilder();
        for (int i = 0; i < ids.length; i++) {
            html.append("<button id=\"").append(ids[i]).append("\" onclick=\"act\" style=\"position: absolute; left: ")
                    .append(i * 20).append("px; top: 0; width: 18px; height: 18px; background-color: rgb(255, 255, 255);\"></button>");
        }
        SceneCtx index = index(html.toString());
        for (int i = 0; i < ids.length; i++) {
            SceneHit hit = hit(index, i * 20 + 9, 9);
            assertNotNull(hit, ids[i]);
            assertEquals(ids[i], hit.baseId(), "持ち主の節の id が書き換わった");
        }
        // ボタンの背景の部品は持ち主の id に _bg を足した綴り
        DisplayItem item = render(html.toString()).stream().filter(e -> e.id().equals("user_img_bg")).findFirst().orElseThrow();
        assertEquals("user_img", item.baseId());
        assertEquals("_bg", item.partSuffix());
        assertTrue(item.isPart("_bg"));
        // 節の id が部品の接尾辞と同じ綴りでも、節自身の項目は部品ではない
        DisplayItem self = render(html.toString()).stream().filter(e -> e.id().equals("x_sb_v") && e.type() == ElementType.NODE).findFirst().orElseThrow();
        assertFalse(self.isPart("_sb_v"));
        assertEquals("", self.partSuffix());
    }

    @Test
    public void testRoundedBorderPartIsNeverTheHitTarget() {
        SceneCtx index = index("""
                <div id="frame" onclick="frame" style="position: absolute; left: 0; top: 0; width: 100px; height: 40px; border: 2px solid rgb(255, 255, 255); border-radius: 6px;"></div>
                """);
        // 枠の上をクリックしても、受け口は枠の部品ではなく要素本体（スクロールバーではない）
        SceneHit hit = hit(index, 1, 20);
        assertNotNull(hit);
        assertEquals("frame", hit.baseId());
        assertFalse(hit.isScrollbar());
    }

    @Test
    public void testClippedChildIsNotHitOutsideItsScrollContainer() {
        SceneCtx index = index("""
                <div id="box" style="height: 20px; overflow-y: auto;">
                  <button id="inner" onclick="inner" style="display: block; width: 100px; height: 60px;">I</button>
                </div>
                """);
        assertNotNull(hit(index, 10, 10));
        SceneHit outside = hit(index, 10, 40);
        String outsideId = outside == null ? null : outside.baseId();
        assertNotEquals("inner", outsideId, "overflow-y: auto の外にはみ出した子がクリックを受けている");
    }

    // ==========================================

    @Test
    public void testScrollContainerIsFoundThroughAnAncestorTransform() {
        // 左上 (100, 0) に置いた 100x50 のスクロール箱を、親が 2 倍に拡大している。画面上では x 100..300・y 0..100
        SceneCtx index = index("""
                <div id="zoom" style="position: absolute; left: 0; top: 0; width: 160px; height: 100px; transform: scale(2); transform-origin: 0 0;">
                  <div id="list" style="margin-left: 50px; width: 100px; height: 50px; overflow-y: auto;">
                    <div style="height: 200px;"></div>
                  </div>
                </div>
                """);
        assertEquals(List.of("list"), scrollIds(index, 250, 80), "拡大後の画面座標でも箱の中なら見つかる");
        assertEquals(List.of(), scrollIds(index, 80, 80), "箱の左（拡大後は x < 100）は箱ではない");
        assertEquals(List.of(), scrollIds(index, 250, 150), "拡大後の箱の下（y > 100）は箱ではない");
    }

    @Test
    public void testScrollContainerHiddenByAnAncestorClipIsNotFound() {
        SceneCtx index = index("""
                <div id="frame" style="width: 100px; height: 30px; overflow: hidden;">
                  <div id="list" style="width: 100px; height: 80px; overflow-y: auto;">
                    <div style="height: 300px;"></div>
                  </div>
                </div>
                """);
        assertEquals(List.of("list"), scrollIds(index, 50, 10));
        assertEquals(List.of(), scrollIds(index, 50, 60), "祖先の overflow: hidden に隠れた下半分ではホイールを受けない");
    }

    private static SceneHit hit(SceneCtx ctx, double x, double y) {
        return SceneHitTest.hitTest(ctx.scene(), SceneHitTest.Context.of(ctx.dyn()), x, y);
    }

    private static List<String> scrollIds(SceneCtx index, double x, double y) {
        return SceneHitTest.scrollContainersAt(index.scene(), SceneHitTest.Context.of(index.dyn()), x, y).stream()
                .map(SceneHit::baseId)
                .filter(id -> !id.startsWith("~") && !id.equals("zoom") && !id.equals("frame"))
                .distinct().toList();
    }

    private static List<DisplayItem> render(String html) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument(
                "<html><head><style>body { margin: 0; }</style></head><body>" + html + "</body></html>", "", VIEWPORT_W, VIEWPORT_H);
        return TestItems.of(com.nishiyu.enderframelib.common.FrameRender.document(doc, VIEWPORT_W, VIEWPORT_H, Map.of(), new ScrollState(), FontResolver.FALLBACK, new InteractionState()));
    }

    private static SceneCtx index(String html) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument(
                "<html><head><style>body { margin: 0; }</style></head><body>" + html + "</body></html>", "", VIEWPORT_W, VIEWPORT_H);
        DisplayScene scene = parser.layoutDocument(doc, VIEWPORT_W, VIEWPORT_H, Map.of(), new ScrollState(), FontResolver.FALLBACK);
        return new SceneCtx(scene, DynamicSceneState.of(null, VIEWPORT_W, VIEWPORT_H));
    }
}