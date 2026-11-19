package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 差分パッチ（{@link Painter#tryPatchItems}）へ渡す「動いている項目」は、項目自身の補間ではなく姿勢の鎖で決まること（§6-104・T2b）。
 * 回る節の下の背景は自分では止まっているが、親が回るので姿勢はフレームごとに変わる。以前はこの部品が渡されず、GPU が前の姿勢の
 * 図形を使い続けた（CPU は差分パッチを使わないので画素の比較では GPU でしか見えなかった。ここでは GPU なしで走査の入力を調べる）。
 */
class MovingChainPatchTest {

    private static final String HTML = "<style>body{margin:0}@keyframes spin{from{transform:rotate(0deg)}to{transform:rotate(360deg)}}"
            + "#spin{position:absolute;left:40px;top:40px;width:120px;height:120px;animation:spin 2s linear infinite}"
            + "#child{width:60px;height:30px;background:#c33;border:2px solid #333}#still{position:absolute;left:220px;top:40px;width:50px;height:50px;background:#3c3}</style>"
            + "<div id=\"spin\"><div id=\"child\">x</div></div><div id=\"still\"></div>";

    private static final class NoDraw implements RenderBackend {
        @Override public void render(List<DisplayItem> items, long nowMs) { for (DisplayItem it : items) CssAnimator.getValuesAt("moving-chain", it, nowMs); }
        @Override public com.nishiyu.enderframelib.core.raster.Pixmap pixmap() { return new com.nishiyu.enderframelib.core.raster.Pixmap(1, 1); }
        @Override public float devicePixelRatio() { return 1f; }
        @Override public String sessionId() { return "moving-chain"; }
        @Override public void close() { }
    }

    @Test
    void itemsUnderAMovingNodeAreHandedToTheDiffPatch() {
        List<DisplayItem> items;
        try (StandaloneView view = new StandaloneView(HTML, 320, 240, FontResolver.FALLBACK); var be = new NoDraw()) {
            view.frame(1000, be, true);
            items = view.ui().renderCache();
        }
        PaintSceneDriver d = new PaintSceneDriver("moving-chain-patch");
        Map<Integer, String> prevPose = new HashMap<>();
        List<String> missed = new ArrayList<>();
        int[] patchCalls = {0}, changed = {0};
        long[] t = {5000};
        Painter painter = new Painter() {
            @Override public void syncLayers(List<PaintSceneDriver.Group> want) { }
            @Override public void drawItem(DisplayItem el, int index, float flat) { prevPose.put(index, d.resolveAt(index).pose().toString()); }
            @Override public boolean tryPatchItems(List<DisplayItem> list, int[] indices, int count) {
                patchCalls[0]++;
                java.util.Set<Integer> handed = new java.util.HashSet<>();
                for (int k = 0; k < count; k++) handed.add(indices[k]);
                for (var e : prevPose.entrySet()) {
                    int i = e.getKey();
                    String now = d.resolveAt(i).pose().toString();
                    if (!now.equals(e.getValue())) {
                        changed[0]++;
                        if (!handed.contains(i)) missed.add("t=" + t[0] + " " + list.get(i).id());
                        e.setValue(now);
                    }
                }
                return true;
            }
        };
        for (int f = 0; f < 6; f++) {
            d.run(items, t[0], Matrix2D.IDENTITY, painter);
            t[0] += 100;
        }
        assertTrue(patchCalls[0] >= 4, "同じリストのフレームは差分パッチへ回る前提: " + patchCalls[0]);
        assertTrue(changed[0] > 0, "回る節の下の部品の姿勢がフレームごとに変わる前提");
        assertEquals(List.of(), missed);
    }
}
