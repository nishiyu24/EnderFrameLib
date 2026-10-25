package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.HitTestIndex;
import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.RenderPipeline;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

/**
 * 描画リストから組んだヒットテスト（W8）。描画側が部品に載せたもの（pointer-events・可視性・クリップ・
 * 影の id）が、どの要素がクリックを受けるかにそのまま効くことを確かめる。
 */
public class HitTestAndOcclusionTest {

    private static final int VIEWPORT_W = 320;
    private static final int VIEWPORT_H = 200;

    @Test
    public void testPointerEventsNoneOverlayPassesClicksThrough() {
        HitTestIndex index = index("""
                <button id="btn" onclick="act" style="position: absolute; left: 0; top: 0; width: 100px; height: 40px;"></button>
                <div id="overlay" style="position: absolute; left: 0; top: 0; width: 200px; height: 100px; background-color: rgb(255, 255, 255); pointer-events: none;"></div>
                """);
        SessionContext.UIHitResult hit = index.hitTest("w8", 50, 20);
        assertNotNull(hit);
        assertEquals("btn", ClientWebManager.getBaseNodeId(hit.element().id()));
    }

    @Test
    public void testOpaqueOverlayWithoutPointerEventsNoneStillOccludes() {
        HitTestIndex index = index("""
                <button id="btn" onclick="act" style="position: absolute; left: 0; top: 0; width: 100px; height: 40px;"></button>
                <div id="overlay" style="position: absolute; left: 0; top: 0; width: 200px; height: 100px; background-color: rgb(255, 255, 255);"></div>
                """);
        SessionContext.UIHitResult hit = index.hitTest("w8", 50, 20);
        assertNotNull(hit);
        assertEquals("overlay", ClientWebManager.getBaseNodeId(hit.element().id()));
    }

    @Test
    public void testVisibilityHiddenElementDoesNotReceiveClicks() {
        HitTestIndex index = index("""
                <button id="btn" onclick="act" style="position: absolute; left: 0; top: 0; width: 100px; height: 40px;"></button>
                <div id="cover" onclick="cover" style="position: absolute; left: 0; top: 0; width: 200px; height: 100px; background-color: rgb(255, 255, 255); visibility: hidden;">
                  <div id="shown" onclick="shown" style="visibility: visible; width: 20px; height: 20px; margin-left: 150px; background-color: rgb(0, 0, 0);"></div>
                </div>
                """);
        assertEquals("btn", ClientWebManager.getBaseNodeId(index.hitTest("w8", 50, 20).element().id()));
        // 隠れた箱の中の visible な子はクリックを受ける
        assertEquals("shown", ClientWebManager.getBaseNodeId(index.hitTest("w8", 160, 10).element().id()));
    }

    @Test
    public void testShadowIsTransparentToHitTestingAndNormalizesToItsOwner() {
        HitTestIndex index = index("""
                <button id="btn" onclick="act" style="position: absolute; left: 0; top: 60px; width: 100px; height: 40px;"></button>
                <div id="card" onclick="card" style="position: absolute; left: 0; top: 0; width: 100px; height: 50px; background-color: rgb(255, 255, 255); box-shadow: 0 20px 6px rgb(0, 0, 0);"></div>
                """);
        // 影はボタンの上に掛かっているが、クリックはボタンに届く
        SessionContext.UIHitResult hit = index.hitTest("w8", 50, 65);
        assertNotNull(hit);
        assertEquals("btn", ClientWebManager.getBaseNodeId(hit.element().id()));
        // 影の番号付きの部品は持ち主の id に戻る（以前は "_shadow_1" を剥がせなかった）
        assertEquals("card", ClientWebManager.getBaseNodeId("card_shadow_3"));
        assertEquals("card", ClientWebManager.getBaseNodeId("card_shadow_in12"));
        assertEquals("card", ClientWebManager.getBaseNodeId("card_b_round"));
    }

    @Test
    public void testRoundedBorderPartIsNeverTheHitTarget() {
        HitTestIndex index = index("""
                <div id="frame" onclick="frame" style="position: absolute; left: 0; top: 0; width: 100px; height: 40px; border: 2px solid rgb(255, 255, 255); border-radius: 6px;"></div>
                """);
        // 枠の上をクリックしても、受け口は枠の部品ではなく箱の _bg（枠線ボックスいっぱい）
        SessionContext.UIHitResult hit = index.hitTest("w8", 1, 20);
        assertNotNull(hit);
        assertEquals("frame_bg", hit.element().id());
    }

    @Test
    public void testClippedChildIsNotHitOutsideItsScrollContainer() {
        HitTestIndex index = index("""
                <div id="box" style="height: 20px; overflow-y: auto;">
                  <button id="inner" onclick="inner" style="display: block; width: 100px; height: 60px;">I</button>
                </div>
                """);
        assertNotNull(index.hitTest("w8", 10, 10));
        SessionContext.UIHitResult outside = index.hitTest("w8", 10, 40);
        String outsideId = outside == null ? null : ClientWebManager.getBaseNodeId(outside.element().id());
        assertNotEquals("inner", outsideId, "overflow-y: auto の外にはみ出した子がクリックを受けている");
    }

    // ==========================================

    @Test
    public void testScrollContainerIsFoundThroughAnAncestorTransform() {
        // 左上 (100, 0) に置いた 100x50 のスクロール箱を、親が 2 倍に拡大している。画面上では x 100..300・y 0..100
        HitTestIndex index = index("""
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
        HitTestIndex index = index("""
                <div id="frame" style="width: 100px; height: 30px; overflow: hidden;">
                  <div id="list" style="width: 100px; height: 80px; overflow-y: auto;">
                    <div style="height: 300px;"></div>
                  </div>
                </div>
                """);
        assertEquals(List.of("list"), scrollIds(index, 50, 10));
        assertEquals(List.of(), scrollIds(index, 50, 60), "祖先の overflow: hidden に隠れた下半分ではホイールを受けない");
    }

    private static List<String> scrollIds(HitTestIndex index, double x, double y) {
        return index.scrollContainersAt("w12", x, y).stream()
                .map(h -> ClientWebManager.getBaseNodeId(h.element().id()))
                .filter(id -> !id.startsWith("gen_") && !id.equals("zoom") && !id.equals("frame"))
                .distinct().toList();
    }

    private static HitTestIndex index(String html) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument(
                "<html><head><style>body { margin: 0; }</style></head><body>" + html + "</body></html>", "", VIEWPORT_W, VIEWPORT_H);
        LayoutBox root = parser.layoutDocument(doc, VIEWPORT_W, VIEWPORT_H, Map.of(), new ScrollState(), FontResolver.FALLBACK);
        List<DisplayItem> elements = new DisplayListBuilder(VIEWPORT_W, VIEWPORT_H, doc.sheet.keyframes, new ScrollState()).render(root);
        return new HitTestIndex(elements);
    }
}