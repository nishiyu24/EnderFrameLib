package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.PaintOffsets;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 移動量だけが書き換わる表示リスト（スクロール）を、索引を作り直さず差分で走査しても、
 * 毎回作り直したときと走査の出力（描く項目・姿勢・不透明度・層の鎖）が完全に同じになること。
 */
class PaintSceneDriverPatchTest {
    private static final int W = 320, H = 240;

    private static String html() {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 60; i++) rows.append("<div class=\"row\" id=\"r").append(i).append("\">row ").append(i).append("</div>");
        StringBuilder side = new StringBuilder();
        for (int i = 0; i < 25; i++) side.append("<div class=\"row g\" id=\"s").append(i).append("\">side ").append(i).append("</div>");
        return "<style>body{margin:0;font-size:13px}.row{height:24px;margin:2px;padding:2px 6px;background:#eef;border:1px solid #99a}"
                + ".g{opacity:.8}"
                + "#outer{position:absolute;left:0;top:0;width:190px;height:230px;overflow-y:auto;border:2px solid #333;border-radius:8px}"
                + "#side{position:absolute;left:200px;top:0;width:110px;height:110px;overflow:scroll;opacity:.9;filter:blur(0.5px)}</style>"
                + "<div id=\"outer\">" + rows + "</div><div id=\"side\">" + side + "</div>";
    }

    /** 描画先に何もしないバックエンド（ヒットテストが読む補間状態だけ更新する）。 */
    private static final class NoDraw implements RenderBackend {
        private final String session = "patch-test-view";
        @Override public void render(List<DisplayItem> items, long nowMs) {
            for (DisplayItem it : items) CssAnimator.getValuesAt(session, it, nowMs);
        }
        @Override public com.nishiyu.enderframelib.core.raster.Pixmap pixmap() { return new com.nishiyu.enderframelib.core.raster.Pixmap(1, 1); }
        @Override public float devicePixelRatio() { return 1f; }
        @Override public String sessionId() { return session; }
        @Override public void close() { }
    }

    /** 走査の出力を 1 本の文字列の列にする。 */
    private static List<String> trace(PaintSceneDriver d, List<DisplayItem> items, PaintOffsets offsets, long now) {
        List<String> out = new ArrayList<>();
        d.run(items, offsets, now, Matrix2D.IDENTITY, new Painter() {
            @Override public void syncLayers(List<PaintSceneDriver.Group> want) {
                StringBuilder sb = new StringBuilder("layers:");
                for (var g : want) sb.append(g.id()).append('@').append(g.opacity()).append(',');
                out.add(sb.toString());
            }
            @Override public void drawItem(DisplayItem el, int index, float flat) {
                var r = d.resolveAt(index);
                out.add(el.id() + "|" + index + "|" + flat + "|" + r.pose() + "|" + r.opacity() + "|" + r.residual()
                        + "|" + offsets.translateX(el) + "," + offsets.translateY(el));
            }
        });
        return out;
    }

    @Test
    void incrementalTraversalMatchesAFullRebuildEveryFrame() {
        // スクロールの移動量は外部表の PaintOffsets へ入り、不変リストの項目は同じまま（C6）。各フレームで、前のフレームから引き継いだ
        // 索引（差分で更新）と、そのフレームで作り直した索引の走査が、その場で同じ出力になることを確かめる
        StandaloneView view = new StandaloneView(html(), W, H, FontResolver.FALLBACK);
        try (var backend = new NoDraw()) {
            Random rnd = new Random(9);
            long now = 1000;
            view.frame(now, backend, true);
            String sid = backend.sessionId();
            PaintSceneDriver incremental = new PaintSceneDriver("drv-incremental");
            int patchedFrames = 0, frames = 80;
            long t = 5000;
            for (int step = 0; step < frames; step++) {
                view.mouseMove(rnd.nextBoolean() ? 60 : 250, rnd.nextInt(H), sid);
                view.scroll(0, rnd.nextInt(7) - 3, sid);
                now += 16;
                view.frame(now, backend, true);
                List<DisplayItem> items = view.ui().renderCache();
                PaintOffsets offsets = view.ui().paintOffsets().snapshot();
                t += 16;
                List<String> expect = trace(new PaintSceneDriver("drv-rebuilt-" + step), items, offsets, t);
                long gen = incremental.generation();
                List<String> actual = trace(incremental, items, offsets, t);
                if (incremental.generation() == gen && step > 0) patchedFrames++;
                assertEquals(expect, actual, "frame " + step);
            }
            assertTrue(patchedFrames > frames / 2, "索引が差分で済んでいない: " + patchedFrames + "/" + frames);
        } finally {
            view.close();
        }
    }
}
