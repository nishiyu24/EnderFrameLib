package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * K5: スクロールだけの更新の位置の決め直し（{@code RenderPipeline.repositionLayout}）が、配置の鍵で記憶を使い回しても
 * 同じスクロール量で最初から作った結果と同じ表示リストになること。入れ子のスクロール・sticky・位置指定・flex・相対位置を含む文書で、
 * 乱数のホイールとスクロール位置の直接の設定を混ぜる。
 */
public class RepositionReuseTest {
    private static String html() {
        StringBuilder sb = new StringBuilder("""
                <style>
                  body { margin: 0; font: 13px sans-serif }
                  #top { position: sticky; top: 0; height: 24px; background: #234; color: #fff }
                  #main { height: 180px; overflow: auto; position: relative; border: 1px solid #444 }
                  .row { height: 22px; border-bottom: 1px solid #ccc }
                  .rel { position: relative; left: 3px; top: 1px }
                  .abs { position: absolute; right: 4px; top: 40px; width: 30px; height: 18px; background: #f90 }
                  #inner { height: 90px; overflow: auto; margin: 6px; background: #eef }
                  #flex { display: flex; height: 70px; overflow: auto; gap: 4px }
                  #flex > div { flex: 0 0 120px; background: #dfd }
                  #fx { position: fixed; right: 2px; bottom: 2px; width: 40px; height: 20px; background: #c33 }
                  #side { height: 60px; overflow-y: scroll; width: 140px }
                </style>
                <div id=fx>fx</div><div id=top>sticky header <span id=clock>00:00</span></div><div id=main>
                """);
        for (int i = 0; i < 40; i++) {
            sb.append("<div class='row").append(i % 7 == 0 ? " rel" : "").append("' id=r").append(i).append(">row ").append(i);
            if (i % 11 == 3) sb.append("<span class=abs>a").append(i).append("</span>");
            sb.append("</div>");
            if (i == 12) {
                sb.append("<div id=inner>");
                for (int k = 0; k < 14; k++) sb.append("<div class=row id=in").append(k).append(">inner ").append(k).append("</div>");
                sb.append("</div>");
            }
            if (i == 20) {
                sb.append("<div id=flex>");
                for (int k = 0; k < 9; k++) sb.append("<div id=fl").append(k).append(">card ").append(k).append("</div>");
                sb.append("</div>");
            }
        }
        sb.append("</div><div id=side>");
        for (int i = 0; i < 12; i++) sb.append("<div class=row id=s").append(i).append(">side ").append(i).append("</div>");
        return sb.append("</div><p id=tail>tail</p>").toString();
    }

    private static final int W = 320, H = 240;

    /** 同じ文書を最初から作り、同じスクロール量を与えて描いた画素。 */
    private static int[] freshPixels(String html, Map<HtmlNode, float[]> scroll) {
        try (StandaloneView f = new StandaloneView(html, W, H, FontResolver.FALLBACK); var be = new SoftwareRenderBackend(W, H, 1f)) {
            f.frame(1000, be, true);
            for (var e : scroll.entrySet()) {
                HtmlNode n = f.root().getNodeById(e.getKey().getId());
                assertTrue(n != null, "スクロールの節が見つからない " + e.getKey().getId());
                f.ui().scrollState().setScroll(n, e.getValue()[0], e.getValue()[1]);
            }
            f.ui().requestReposition();
            f.frame(1016, be, true);
            return be.pixmap().pixels().clone();
        }
    }

    @Test
    void scrollOnlyUpdatesMatchAFreshBuildAtTheSameOffsets() {
        String html = html();
        for (long seed = 1; seed <= 10; seed++) {
            Random rnd = new Random(seed);
            try (StandaloneView v = new StandaloneView(html, W, H, FontResolver.FALLBACK); var be = new SoftwareRenderBackend(W, H, 1f)) {
                long now = 1000;
                v.frame(now, be, true);
                for (int step = 0; step < 30; step++) {
                    switch (rnd.nextInt(4)) {
                        case 0, 1 -> {
                            v.mouseMove(10 + rnd.nextInt(280), 30 + rnd.nextInt(180), be.sessionId());
                            v.scroll(0, rnd.nextInt(7) - 3, be.sessionId());
                        }
                        case 2 -> {
                            String[] ids = {"main", "inner", "flex", "side"};
                            HtmlNode n = v.root().getNodeById(ids[rnd.nextInt(ids.length)]);
                            v.ui().scrollState().setScroll(n, 0, rnd.nextInt(200));
                            v.ui().requestReposition();
                        }
                        default -> { /* 時間だけ進める */ }
                    }
                    now += 16;
                    v.frame(now, be, true);
                    int[] live = be.pixmap().pixels();
                    assertTrue(java.util.Arrays.equals(freshPixels(html, v.ui().scrollState().snapshot()), live), "seed " + seed + " step " + step + " " + v.ui().scrollState().snapshot().values().stream().map(java.util.Arrays::toString).toList());
                }
            }
        }
    }

    /**
     * 位置の指定〔{@code left}・{@code top}〕だけが変わった再配置は、スクロールだけの更新ではない。配置の鍵は箱自身の inset を見ないので、
     * ここで配置を使い回すと動かない〔K5 の最初の版が根のテストで見つかった退行〕。
     */
    @Test
    void positionOnlyStyleChangesStillMoveThePositionedBoxes() {
        String html = "<style>body{margin:0} #w{position:relative;width:300px;height:200px} #a{position:absolute;left:10px;top:10px;width:40px;height:30px;background:#c00}"
                + " #b{position:relative;left:0;top:0;width:40px;height:30px;background:#06c}</style><div id=w><div id=a>a</div><div id=b>b</div></div>";
        try (StandaloneView v = new StandaloneView(html, W, H, FontResolver.FALLBACK); var be = new SoftwareRenderBackend(W, H, 1f)) {
            long now = 1000;
            v.frame(now, be, true);
            for (int step = 0; step < 12; step++) {
                var a = (com.nishiyu.enderframelib.core.dom.node.HtmlElement) v.root().getNodeById("a");
                var b = (com.nishiyu.enderframelib.core.dom.node.HtmlElement) v.root().getNodeById("b");
                a.setAttribute("style", "left:" + (10 + step * 17) + "px;top:" + (10 + step * 9) + "px");
                b.setAttribute("style", "left:" + (step * 5) + "px;top:" + (step * 7) + "px");
                now += 16;
                v.frame(now, be, true);
                int[] live = be.pixmap().pixels().clone();
                try (StandaloneView f = new StandaloneView(html, W, H, FontResolver.FALLBACK); var fbe = new SoftwareRenderBackend(W, H, 1f)) {
                    ((com.nishiyu.enderframelib.core.dom.node.HtmlElement) f.root().getNodeById("a")).setAttribute("style", a.getAttribute("style"));
                    ((com.nishiyu.enderframelib.core.dom.node.HtmlElement) f.root().getNodeById("b")).setAttribute("style", b.getAttribute("style"));
                    f.frame(1000, fbe, true);
                    assertTrue(java.util.Arrays.equals(fbe.pixmap().pixels(), live), "step " + step);
                }
            }
        }
    }
}
