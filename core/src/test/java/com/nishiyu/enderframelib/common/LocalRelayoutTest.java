package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 課題1（DOM 変更が全体 RELAYOUT になる）の基準測定。ゲージ幅（{@code style="width:N%"}）のバインディングを
 * 大きな文書で毎フレーム更新したときの費用を記録する。局所レイアウトの導入前後で比べる。
 */
public class LocalRelayoutTest {
    private static final int ITERS = System.getenv("LOCAL_ITERS") != null ? Integer.parseInt(System.getenv("LOCAL_ITERS")) : 200;

    private static String html(int rows) {
        StringBuilder h = new StringBuilder("<style>body{margin:0;font-size:12px}.row{height:16px}"
                + ".bar{width:200px;height:10px;overflow:hidden;background:#333}"
                + ".fill{height:10px;background:#0f0}</style>"
                + "<div class=bar id=bar><div class=fill id=fill style=\"width:50%\"></div></div>");
        for (int i = 0; i < rows; i++) h.append("<div class=row><span>item ").append(i).append("</span> <b>x</b></div>");
        return h.toString();
    }

    @AfterEach
    void restore() {
        com.nishiyu.enderframelib.core.EngineContext.global().inPlaceRestyleRelayout = true;
        UiView.changeStampsEnabled = true;
    }

    private static String mixed() {
        return "<style>body{margin:0;font-size:12px}.bar{width:200px;height:10px;background:#333}"
                + ".fill{height:10px;background:#0f0}.flex{display:flex;width:300px}.flex>div{flex:1;border:1px solid red}"
                + ".grid{display:grid;grid-template-columns:1fr 1fr;width:240px}.abs{position:relative;height:40px}"
                + ".abs>i{position:absolute;left:10%;top:2px;width:30%;height:10px;background:blue}</style>"
                + "<div class=bar><div class=fill id=fill style=\"width:50%\"></div></div>"
                + "<div class=flex id=flex><div id=a>alpha beta</div><div id=b style=\"flex:2\">gamma</div><div>d</div></div>"
                + "<div class=grid id=grid><div>one</div><div id=g2>two three four</div></div>"
                + "<div class=abs id=abs><i id=ai></i></div>"
                + "<p id=p style=\"margin:4px;padding:2px\">paragraph <span id=s>text</span> more text to wrap around</p>";
    }

    private static String sig(DisplayItem i) {
        return i.id() + "|" + i.type() + "|" + i.x() + "," + i.y() + "," + i.width() + "," + i.height()
                + "|" + i.text() + "|" + i.color() + "|" + i.bgColor();
    }

    private static List<String> sigs(StandaloneView v) {
        v.ui().flushInvalidation();
        List<String> out = new ArrayList<>();
        for (DisplayItem i : v.ui().renderCache()) out.add(sig(i));
        return out;
    }

    @Test
    public void inPlaceRelayoutMatchesRebuildAndIsTaken() {
        StandaloneView inPlace = new StandaloneView(mixed(), 400, 500, FontResolver.FALLBACK);
        StandaloneView rebuilt = new StandaloneView(mixed(), 400, 500, FontResolver.FALLBACK);
        sigs(inPlace);
        sigs(rebuilt);
        long hits = UiView.INPLACE_RELAYOUT_HITS.get();
        int localTaken = 0;
        String[][] steps = {
                {"fill", "style", "width:10%"}, {"fill", "style", "width:90%;background:red"}, {"a", "style", "width:150px"},
                {"b", "style", "flex:5"}, {"g2", "style", "padding:8px"}, {"ai", "style", "left:50%;width:10px"},
                {"p", "style", "margin:20px;padding:10px;width:120px"}, {"s", "style", "margin-left:30px"},
                {"flex", "style", "width:100px"}, {"fill", "style", "width:0"}, {"p", "style", ""},
                {"p", "style", "display:none"}, {"p", "style", "display:block;height:50px"}, {"s", "style", "font-size:20px"},
        };
        for (String[] s : steps) {
            inPlace.root().getNodeById(s[0]).setAttribute(s[1], s[2]);
            long before = UiView.INPLACE_RELAYOUT_HITS.get();
            List<String> a = sigs(inPlace);
            if (UiView.INPLACE_RELAYOUT_HITS.get() > before && RenderPipeline.lastRelayoutWasLocal()) localTaken++;
            com.nishiyu.enderframelib.core.EngineContext.global().inPlaceRestyleRelayout = false;
            try {
                rebuilt.root().getNodeById(s[0]).setAttribute(s[1], s[2]);
                assertEquals(sigs(rebuilt), a, String.join(" ", s));
            } finally {
                com.nishiyu.enderframelib.core.EngineContext.global().inPlaceRestyleRelayout = true;
            }
        }
        assertTrue(localTaken >= 5, "経路だけの印付けで済んだ回数: " + localTaken);
        long taken = UiView.INPLACE_RELAYOUT_HITS.get() - hits;
        assertTrue(taken >= 6, "箱の木を作り直さない経路に乗った回数: " + taken);
    }

    /** 局所あり/なしを 1 フレームごとに交互に切り替えて、CPU 時間で前後比較する（負荷の揺れを相殺する）。 */
    @Test
    public void benchmarkInPlaceVsRebuildInterleaved() {
        java.lang.management.ThreadMXBean mx = java.lang.management.ManagementFactory.getThreadMXBean();
        for (int round = 0; round < 3; round++) {
            StandaloneView v = new StandaloneView(html(1500), 800, 600, FontResolver.FALLBACK);
            v.ui().flushInvalidation();
            HtmlNode fill = v.root().getNodeById("fill");
            for (int i = 0; i < 40; i++) {
                com.nishiyu.enderframelib.core.EngineContext.global().inPlaceRestyleRelayout = (i % 2 == 0);
                fill.setAttribute("style", "width:" + (i % 100) + "%");
                v.ui().flushInvalidation();
            }
            long on = 0, off = 0;
            int n = 250;
            for (int i = 0; i < n; i++) {
                boolean local = (i % 2 == 0);
                com.nishiyu.enderframelib.core.EngineContext.global().inPlaceRestyleRelayout = local;
                fill.setAttribute("style", "width:" + (i % 100) + "%");
                long c0 = mx.getCurrentThreadCpuTime();
                v.ui().flushInvalidation();
                long d = mx.getCurrentThreadCpuTime() - c0;
                if (local) on += d; else off += d;
            }
            System.out.println("[inplace-vs-rebuild] round " + round + ": in-place=" + on / 1_000_000 + "ms rebuild=" + off / 1_000_000
                    + "ms (cpu, " + n / 2 + " frames each) ratio=" + String.format("%.2f", (double) on / off));
        }
    }

    private static List<String> fullSigs(StandaloneView v) {
        v.ui().flushInvalidation();
        List<String> out = new ArrayList<>();
        for (DisplayItem i : v.ui().renderCache()) {
            String fh = i.meta() != null ? (i.meta().fontHeight() + "/" + i.meta().lineHeight()) : "";
            out.add(sig(i) + "|" + fh);
        }
        return out;
    }

    @Test
    public void fontSizeChangeUsesInPlaceRelayoutAndMatchesFreshRebuild() {
        java.util.function.BiFunction<String, String, String> makeHtml = (bodyFs, flexFs) ->
                "<html><head><style>"
                        + "body{margin:0;font-size:" + bodyFs + ";}"
                        + ".card{padding:0.5em;margin-bottom:0.25em;border:1px solid #444;}"
                        + ".flex{display:flex;width:280px;gap:0.5em;}"
                        + ".flex>div{flex:1;}"
                        + ".grid{display:grid;grid-template-columns:1fr 1fr;width:240px;}"
                        + ".tag::before{content:'TAG';margin-right:0.5em;color:red;}"
                        + "li::marker{color:blue;}"
                        + "table{border-collapse:collapse;margin-top:0.5em;}"
                        + "td{border:1px solid #666;padding:0.25em;}"
                        + "</style></head><body id='body'>"
                        + "<div class='card tag'>prefix <div id='inner'>inner block</div> tail</div>"
                        + "<div id='flex' class='flex' style='font-size:" + flexFs + "'>"
                        + "  <div>wrapping flex item alpha beta gamma</div>"
                        + "  <div>second flex column</div>"
                        + "</div>"
                        + "<div class='grid'><div>grid one</div><div>grid two three</div></div>"
                        + "<ul><li id='li1'>list entry</li></ul>"
                        + "</body></html>";

        StandaloneView view = new StandaloneView(makeHtml.apply("16px", "16px"), 420, 600, FontResolver.FALLBACK);
        fullSigs(view);

        String[][] steps = {
                {"body", "22px", "16px"},
                {"body", "14px", "16px"},
                {"flex", "14px", "20px"},
                {"body", "16px", "20px"},
                {"flex", "16px", "12px"},
        };
        for (String[] step : steps) {
            long structBefore = UiView.RESTYLE_STRUCTURAL.get();
            long inplaceBefore = UiView.INPLACE_RELAYOUT_HITS.get();
            if ("body".equals(step[0])) {
                view.root().getNodeById("body").setAttribute("style", "font-size:" + step[1]);
            } else {
                view.root().getNodeById("flex").setAttribute("style", "font-size:" + step[2]);
            }
            List<String> actual = fullSigs(view);
            assertEquals(0L, UiView.RESTYLE_STRUCTURAL.get() - structBefore,
                    "font-size 変化で structural 再構築が走らない: " + String.join(" ", step));
            assertEquals(1L, UiView.INPLACE_RELAYOUT_HITS.get() - inplaceBefore,
                    "font-size 変化は in-place relayout に乗る: " + String.join(" ", step));
            StandaloneView fresh = new StandaloneView(makeHtml.apply(step[1], step[2]), 420, 600, FontResolver.FALLBACK);
            assertEquals(fullSigs(fresh), actual, "font-size 更新後の一覧が全再構築と一致する");
        }
    }

    @Test
    public void structureAffectingChangesStillTriggerStructuralRebuild() {
        String html = "<html><head><style>"
                + "body{margin:0;font-size:16px;}"
                + ".pseudo::before{content:'A';}"
                + ".pseudo.alt::before{content:'BB';}"
                + "</style></head><body>"
                + "<div id='d' style='display:block'>one</div>"
                + "<div id='w' style='white-space:normal'>  spaced  </div>"
                + "<div id='p' class='pseudo'>text</div>"
                + "<ul><li id='li' style='list-style-type:disc'>item</li></ul>"
                + "</body></html>";
        StandaloneView view = new StandaloneView(html, 400, 400, FontResolver.FALLBACK);
        fullSigs(view);

        String[][] structuralMutations = {
                {"d", "style", "display:inline"},
                {"d", "style", "display:block;float:left"},
                {"w", "style", "white-space:pre"},
                {"p", "class", "pseudo alt"},
                {"li", "style", "list-style-type:none"},
        };
        for (String[] mut : structuralMutations) {
            long structBefore = UiView.RESTYLE_STRUCTURAL.get();
            view.root().getNodeById(mut[0]).setAttribute(mut[1], mut[2]);
            fullSigs(view);
            assertTrue(UiView.RESTYLE_STRUCTURAL.get() > structBefore,
                    "構造に影響するプロパティ変更では structural が増える: " + String.join(" ", mut));
        }
    }

    @Test
    public void benchmarkGaugeWidthInLargeDocument() {
        StandaloneView v = new StandaloneView(html(1500), 800, 600, FontResolver.FALLBACK);
        v.ui().flushInvalidation();
        HtmlNode fill = v.root().getNodeById("fill");
        for (int i = 0; i < 30; i++) { fill.setAttribute("style", "width:" + (i % 100) + "%"); v.ui().flushInvalidation(); }
        long stamped0 = com.nishiyu.enderframelib.core.paint.DisplayListBuilder.STAT_STAMPED.sum();
        long t0 = System.nanoTime();
        for (int i = 0; i < ITERS; i++) { fill.setAttribute("style", "width:" + (i % 100) + "%"); v.ui().flushInvalidation(); }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("[local-relayout benchmark] 1500 rows, 200 style-width updates: " + ms + "ms; stamped-skips/frame=" + (com.nishiyu.enderframelib.core.paint.DisplayListBuilder.STAT_STAMPED.sum() - stamped0) / ITERS + "; " + v.ui().lastBreakdown());
    }
}
