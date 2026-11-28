package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.nishiyu.enderframelib.common.Keys;
import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

/**
 * 部分の描き直し（M3b-4b-4a）で、シーンの違いに現れない変化も塗り直されること: シーンの外から足した重ね描き（キャレット。前の位置と今の位置）と、
 * 利用者の描画上の平行移動（{@code UiView#setPaintOffset}）。毎フレーム、新しい描画器で全面に描いた画素と比べる。
 */
class PartialRepaintOutsideSceneTest {
    private static final int W = 320, H = 160;

    @Test
    void movingTheCaretRepaintsItsOldAndNewPlace() {
        String html = "<style>body{margin:0;font:16px sans-serif}input{position:absolute;left:20px;top:20px;width:240px;height:28px}"
                + "#o{position:absolute;left:20px;top:90px;width:60px;height:30px;background:#36c}</style>"
                + "<input id=f value='hello caret world'><div id=o></div>";
        try (PartialRepaintRealDocumentsTest.Twin twin = new PartialRepaintRealDocumentsTest.Twin(W, H);
             StandaloneView v = new StandaloneView(html, W, H, FontResolver.FALLBACK)) {
            long t = 1000;
            v.frame(t, twin, true);
            v.mouseMove(200, 34, twin.sessionId());
            v.mouseButton(true, twin.sessionId());
            v.mouseButton(false, twin.sessionId());
            v.frame(t += 16, twin, true);
            long partialBefore = twin.partialBackend.partialFrames();
            for (int i = 0; i < 8; i++) {
                v.keyDown(Keys.LEFT, 0);
                v.keyUp(Keys.LEFT, 0);
                v.frame(t += 16, twin, true);
            }
            check(twin, 8);
            assertTrue(twin.partialBackend.partialFrames() - partialBefore >= 6, "キャレットだけの変化が差分で描かれていない");
        }
    }

    /** インスペクタの枠（重ね描き）がポインタの下の要素を移る。シーンは変わらない（{@code :hover} の規則が無い）ので、前の枠は重ね描きの前の範囲でだけ消える。 */
    @Test
    void inspectorBoxMovingBetweenElementsErasesTheOldBox() {
        String html = "<style>body{margin:0}#a{position:absolute;left:10px;top:10px;width:80px;height:50px;background:#c33}"
                + "#b{position:absolute;left:200px;top:90px;width:80px;height:50px;background:#36c}</style><div id=a></div><div id=b></div>";
        try (PartialRepaintRealDocumentsTest.Twin twin = new PartialRepaintRealDocumentsTest.Twin(W, H);
             StandaloneView v = new StandaloneView(html, W, H, FontResolver.FALLBACK)) {
            long t = 1000;
            v.setInspectorEnabled(true);
            v.frame(t, twin, true);
            long partialBefore = twin.partialBackend.partialFrames();
            double[][] at = {{40, 30}, {240, 110}, {40, 30}, {300, 20}, {240, 110}};
            for (double[] p : at) {
                v.mouseMove(p[0], p[1], twin.sessionId());
                v.frame(t += 16, twin, true);
            }
            check(twin, 6);
            assertTrue(twin.partialBackend.partialFrames() - partialBefore >= 4, "インスペクタの枠だけの変化が差分で描かれていない");
        }
    }

    @Test
    void userPaintOffsetChangesRepaintTheMovedElement() {
        String html = "<style>body{margin:0}#m{position:absolute;left:20px;top:20px;width:60px;height:40px;background:#c33}"
                + "#o{position:absolute;left:200px;top:90px;width:60px;height:30px;background:#36c}</style><div id=m></div><div id=o></div>";
        try (PartialRepaintRealDocumentsTest.Twin twin = new PartialRepaintRealDocumentsTest.Twin(W, H);
             StandaloneView v = new StandaloneView(html, W, H, FontResolver.FALLBACK)) {
            long t = 1000;
            v.frame(t, twin, true);
            v.frame(t += 16, twin, true);
            HtmlNode m = v.root().getNodeById("m");
            String nodeItem = v.ui().relativeElements().stream().filter(it -> it.type() == ElementType.NODE
                    && DisplayItem.nodeHandleOf(it.key()) == m.nodeId()).findFirst().orElseThrow().id();
            for (int i = 1; i <= 4; i++) {
                v.ui().setPaintOffset(nodeItem, 30f * i, 10f * i);
                v.frame(t += 16, twin, true);
                v.frame(t += 16, twin, true); // 何も変わらない続きのフレーム（差分）
            }
            check(twin, 8);
        }
    }

    private static void check(PartialRepaintRealDocumentsTest.Twin twin, int minFrames) {
        if (!twin.report.isEmpty()) fail(twin.report.toString());
        assertTrue(twin.frames >= minFrames, "比べたフレームが少ない: " + twin.frames);
    }
}
