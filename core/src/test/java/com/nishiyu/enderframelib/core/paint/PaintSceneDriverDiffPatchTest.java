package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 一般の表示リスト（スクロール用の {@link OffsetItemList} でないもの）が、前回と同じ大きさで少数の項目だけ入れ替わったとき、
 * 索引を作り直さず差分で更新する（{@link PaintSceneDriver#patchDiffIndex}）と、毎回作り直したときと走査の出力（描く項目・姿勢・
 * 不透明度・層の鎖）が完全に同じになること。文字・スタイル・ホバーの変化の列から実際の表示リストを取って突き合わせる。
 */
class PaintSceneDriverDiffPatchTest {
    private static final int W = 360, H = 300;

    @AfterEach
    void restore() {
        PaintSceneDriver.patchDiffIndex = true;
    }

    private static String html() {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 40; i++) rows.append("<div class=\"row\" id=\"r").append(i).append("\">row <span id=\"t").append(i).append("\">").append(i).append("</span></div>");
        StringBuilder side = new StringBuilder();
        for (int i = 0; i < 20; i++) side.append("<div class=\"row g\" id=\"s").append(i).append("\">side ").append(i).append("</div>");
        return "<style>body{margin:0;font-size:13px}.row{height:22px;margin:2px;padding:2px 6px;background:#eef;border:1px solid #99a;border-radius:4px}"
                + ".row:hover{background:#fed}.g{opacity:.8}.hot{color:#c00;transform:translateX(3px)}.dim{opacity:.5}"
                + "#outer{position:absolute;left:0;top:0;width:200px;height:280px;overflow-y:auto;border:2px solid #333;border-radius:8px}"
                + "#side{position:absolute;left:220px;top:0;width:120px;height:120px;overflow:scroll;opacity:.9;filter:blur(0.5px)}"
                + "#badge{position:absolute;left:230px;top:200px;width:80px;height:40px;background:#3a3;border-radius:6px}</style>"
                + "<div id=\"outer\">" + rows + "</div><div id=\"side\">" + side + "</div><div id=\"badge\">b</div>";
    }

    /** 描画先に何もしないバックエンド（ヒットテストが読む補間状態だけ更新する）。 */
    private static final class NoDraw implements RenderBackend {
        private final String session = "diff-patch-view";
        @Override public void render(List<DisplayItem> items, long nowMs) {
            for (DisplayItem it : items) CssAnimator.getValuesAt(session, it, nowMs);
        }
        @Override public com.nishiyu.enderframelib.core.raster.Pixmap pixmap() { return new com.nishiyu.enderframelib.core.raster.Pixmap(1, 1); }
        @Override public float devicePixelRatio() { return 1f; }
        @Override public String sessionId() { return session; }
        @Override public void close() { }
    }

    private static List<String> trace(PaintSceneDriver d, List<DisplayItem> items, long now) {
        List<String> out = new ArrayList<>();
        d.run(items, now, Matrix2D.IDENTITY, new PaintSceneDriver.Painter() {
            @Override public void syncLayers(List<PaintSceneDriver.Group> want) {
                StringBuilder sb = new StringBuilder("layers:");
                for (var g : want) sb.append(g.id()).append('@').append(g.opacity()).append(',');
                out.add(sb.toString());
            }
            @Override public void drawItem(DisplayItem el, int index, float flat) {
                var r = d.resolveAt(index);
                out.add(el.id() + "|" + index + "|" + flat + "|" + r.pose() + "|" + r.opacity() + "|" + r.residual() + "|"
                        + el.x() + "," + el.y() + "," + el.width() + "," + el.height() + "|" + el.text() + "|" + el.color() + "|" + el.bgColor());
            }
        });
        return out;
    }

    @Test
    void diffPatchedTraversalMatchesAFullRebuildEveryFrame() {
        List<List<DisplayItem>> frames = new ArrayList<>();
        StandaloneView view = new StandaloneView(html(), W, H, FontResolver.FALLBACK);
        try (var backend = new NoDraw()) {
            Random rnd = new Random(31);
            long now = 1000;
            view.frame(now, backend, true);
            for (int step = 0; step < 200; step++) {
                switch (rnd.nextInt(7)) {
                    case 0 -> {
                        HtmlNode t = view.root().getNodeById("t" + rnd.nextInt(40));
                        if (t != null) t.updateTextContent("v" + rnd.nextInt(1000));
                    }
                    case 1, 5, 6 -> {
                        // hot を必ず反転させる（姿勢が変わる差分: transform と色が変わる。構造は変わらない）
                        HtmlNode r = view.root().getNodeById("r" + rnd.nextInt(40));
                        // hot / dim を反転させる（dim は不透明度＝層のグループが変わる差分。構造は変わらない）
                        if (r instanceof HtmlElement e) {
                            String cls = e.attrs.get("class");
                            String next = rnd.nextBoolean() ? ("row hot".equals(cls) ? "row" : "row hot") : ("row dim".equals(cls) ? "row" : "row dim");
                            e.setAttribute("class", next);
                        }
                    }
                    case 2 -> view.ui().setHoveredElement(view.root().getNodeById("r" + rnd.nextInt(40)).key());
                    case 3 -> {
                        // 構造を変えずに位置と大きさだけが変わる（姿勢の再計算が必要な差分）
                        HtmlNode b = view.root().getNodeById("badge");
                        if (b instanceof HtmlElement e) {
                            e.setAttribute("style", "left:" + (220 + rnd.nextInt(80)) + "px;top:" + (150 + rnd.nextInt(100)) + "px;width:" + (60 + rnd.nextInt(40)) + "px");
                        }
                    }
                    default -> {
                        HtmlNode s = view.root().getNodeById("s" + rnd.nextInt(20));
                        if (s instanceof HtmlElement e) e.setAttribute("style", "color:#" + Integer.toHexString(0x100000 + rnd.nextInt(0xEFFFFF)));
                    }
                }
                now += 16;
                view.frame(now, backend, true);
                frames.add(view.ui().renderCache());
            }
        } finally {
            view.close();
        }

        PaintSceneDriver patched = new PaintSceneDriver("drv-diff-patched");
        PaintSceneDriver rebuilt = new PaintSceneDriver("drv-diff-rebuilt");
        int patchedFrames = 0, comparable = 0;
        long t = 5000;
        for (int f = 0; f < frames.size(); f++) {
            t += 16;
            PaintSceneDriver.patchDiffIndex = false;
            List<String> expect = trace(rebuilt, frames.get(f), t);
            PaintSceneDriver.patchDiffIndex = true;
            long gen = patched.generation();
            List<String> actual = trace(patched, frames.get(f), t);
            if (f > 0 && frames.get(f).size() == frames.get(f - 1).size() && frames.get(f) != frames.get(f - 1)) {
                comparable++;
                if (patched.generation() == gen) patchedFrames++;
            }
            assertEquals(expect, actual, "frame " + f);
        }
        assertTrue(comparable > 80, "同じ大きさで変わるフレームが十分ある: " + comparable);
        assertTrue(patchedFrames > comparable / 2, "索引が差分の更新で済んでいない: " + patchedFrames + "/" + comparable);
    }
}
