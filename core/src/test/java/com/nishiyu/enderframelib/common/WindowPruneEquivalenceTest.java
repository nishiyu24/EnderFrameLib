package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.paint.DisplayListBuilder;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 画面外の部分木を箱ごとに分類せず丸ごと省いても（{@link DisplayListBuilder#PRUNE_ENABLED}）、窓の作り直し・文字の更新・
 * ホバーを挟んだ各フレームの表示リストが、省かない場合と同じになることを確かめる。
 */
class WindowPruneEquivalenceTest {
    private static String html() {
        StringBuilder cards = new StringBuilder();
        for (int i = 0; i < 900; i++) {
            cards.append("<div class=\"card\" id=\"c").append(i).append("\"><div class=\"t\"></div><div class=\"b\"><div class=\"n\">item ")
                    .append(i).append("</div><span class=\"p\" id=\"p").append(i).append("\">$").append(10 + i % 90).append("</span></div></div>");
        }
        return "<style>body{margin:0;font-size:13px}"
                + "#sc{position:absolute;left:0;top:0;width:600px;height:400px;overflow-y:auto}"
                + "#list{display:flex;flex-wrap:wrap;gap:8px;padding:8px}"
                + ".card{width:130px;background:#fff;border-radius:8px;overflow:hidden;box-shadow:0 1px 3px #888}"
                + ".card:hover{transform:translateY(-2px)}"
                + ".t{height:50px;background:#9cf}.b{padding:6px}.n{font-weight:bold}.p{color:#070}</style>"
                + "<div id=\"sc\"><div id=\"list\">" + cards + "</div></div>";
    }

    private static List<List<String>> run(boolean prune) {
        boolean saved = DisplayListBuilder.PRUNE_ENABLED;
        DisplayListBuilder.PRUNE_ENABLED = prune;
        try {
            List<List<String>> frames = new ArrayList<>();
            StandaloneView view = new StandaloneView(html(), 640, 420, FontResolver.FALLBACK);
            try (var backend = new MutationScenario.NoDraw()) {
                long now = 1000;
                view.frame(now, backend, true);
                String sid = backend.sessionId();
                view.mouseMove(100, 100, sid);
                Random rnd = new Random(11);
                long rebuilds = UiView.STAT_WINDOW_REBUILD.sum();
                for (int i = 0; i < 90; i++) {
                    int step = i % 30 < 20 ? 6 : -9; // 大きく動かして窓の作り直しを起こす
                    view.scroll(0, step, sid);
                    if (i % 7 == 3) view.mouseMove(40 + rnd.nextInt(500), 40 + rnd.nextInt(300), sid);
                    if (i % 11 == 5) {
                        var p = view.ui().domDocument().getElementById("p" + rnd.nextInt(900));
                        if (p != null) p.updateTextContent("$" + (1000 + rnd.nextInt(9000)));
                    }
                    now += 16;
                    view.frame(now, backend, true);
                    List<String> sig = new ArrayList<>();
                    for (DisplayItem it : view.ui().renderCache()) {
                        sig.add(it.id() + "@" + it.x() + "," + it.y() + "," + it.width() + "," + it.height());
                    }
                    frames.add(sig);
                }
                assertTrue(UiView.STAT_WINDOW_REBUILD.sum() > rebuilds, "窓の作り直しが起きていない（テストの前提が崩れている）");
            } finally {
                view.close();
            }
            return frames;
        } finally {
            DisplayListBuilder.PRUNE_ENABLED = saved;
        }
    }

    @Test
    void prunedWindowBuildsMatchTheFullClassification() {
        long pruned = DisplayListBuilder.STAT_PRUNED_SUBTREES.sum();
        List<List<String>> with = run(true);
        assertTrue(DisplayListBuilder.STAT_PRUNED_SUBTREES.sum() > pruned, "部分木の省略が働いていない（テストの前提が崩れている）");
        List<List<String>> without = run(false);
        assertEquals(without.size(), with.size());
        for (int i = 0; i < without.size(); i++) assertEquals(without.get(i), with.get(i), "frame " + i);
    }
}
