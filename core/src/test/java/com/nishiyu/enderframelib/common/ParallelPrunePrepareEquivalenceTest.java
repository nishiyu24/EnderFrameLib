package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.LayoutTaskPool;
import com.nishiyu.enderframelib.core.paint.DisplayListBuilder;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * B11: 画面外の行を部分木ごと省く判定（{@code cullableSubtree}）と箱ごとの記録を、表示リストの組み立ての前に並列で済ませても
 * （並列が有効なとき。無効なら従来の直列の判定）、全行の測り直し（{@code font-size}）・スクロール・文字の更新・ホバーを挟んだ
 * 各フレームの表示リストと、箱の「省いた」記録（{@code paintCulled} / {@code dlStart}）が、直列の判定と同じになることを確かめる。
 */
class ParallelPrunePrepareEquivalenceTest {

    @AfterEach
    void restore() {
        com.nishiyu.enderframelib.core.kernel.Shortcut.LAYOUT_PARALLEL.set(true);
        LayoutTaskPool.setWorkerThreads(LayoutTaskPool.defaultThreads());
    }

    private static String html() {
        Random r = new Random(5);
        String[] words = {"alpha", "beta gamma", "日本語の行", "a longer line of text that wraps in a narrow row", "x"};
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 900; i++) {
            rows.append("<div class=\"row\" id=\"r").append(i).append("\"><span class=\"tag\">#").append(i).append("</span> ")
                    .append(words[r.nextInt(words.length)]);
            if (i % 5 == 0) rows.append("<div class=\"sub\"><b>sub</b> ").append(words[r.nextInt(words.length)]).append("</div>");
            if (i % 9 == 0) rows.append("<div class=\"scroll\">").append("<p>inner</p>".repeat(3)).append("</div>");
            rows.append("<span class=\"v\" id=\"v").append(i).append("\">").append(i * 7 % 100).append("</span></div>");
        }
        return "<style>body{margin:0;font-size:14px}"
                + ".row{padding:3px 6px;border-bottom:1px solid #ccc;width:300px}"
                + ".row:hover{background:#eef}"
                + ".tag{color:#06c}.sub{margin-left:12px;font-size:0.9em}"
                + ".scroll{height:24px;overflow:auto}"
                + ".v{float:right}</style>"
                + "<div id=\"list\">" + rows + "</div>";
    }

    /** 1 回分の実行で、各フレームの表示リストと、全部の箱の省いた記録を返す。 */
    private static List<List<Object>> run(boolean parallel) {
        com.nishiyu.enderframelib.core.kernel.Shortcut.LAYOUT_PARALLEL.set(parallel);
        if (parallel) LayoutTaskPool.setWorkerThreads(4);
        List<List<Object>> frames = new ArrayList<>();
        StandaloneView view = new StandaloneView(html(), 640, 420, FontResolver.FALLBACK);
        try (var backend = new MutationScenario.NoDraw()) {
            long now = 1000;
            view.frame(now, backend, true);
            String sid = backend.sessionId();
            HtmlElement list = (HtmlElement) view.ui().domDocument().getElementById("list");
            Random rnd = new Random(17);
            for (int i = 0; i < 40; i++) {
                switch (i % 4) {
                    case 0 -> list.setAttribute("style", (i & 4) == 0 ? "font-size:15px" : "font-size:14px"); // 全行の測り直し
                    case 1 -> view.scroll(0, i % 8 == 1 ? 7 : -3, sid);
                    case 2 -> {
                        var v = view.ui().domDocument().getElementById("v" + rnd.nextInt(900));
                        if (v != null) v.updateTextContent(Integer.toString(rnd.nextInt(10_000)));
                    }
                    default -> view.mouseMove(20 + rnd.nextInt(280), 20 + rnd.nextInt(380), sid);
                }
                now += 16;
                view.frame(now, backend, true);
                List<Object> sig = new ArrayList<>();
                for (DisplayItem it : view.ui().renderCache()) sig.add(it.frozen());
                collectCulled(view.ui().layoutRoot(), sig);
                frames.add(sig);
            }
        } finally {
            view.close();
        }
        return frames;
    }

    private static void collectCulled(LayoutBox b, List<Object> out) {
        out.add((b.node != null ? b.node.key() : "?") + ":" + b.paintCulled + ":" + b.dlStart + ":" + b.dlEnd);
        for (LayoutBox c : b.children) collectCulled(c, out);
    }

    @Test
    void preparedPruneMatchesTheSerialDecision() {
        List<List<Object>> serial = run(false); // 並列が無効なら先の判定もしない（直列の判定が基準）
        long prepared0 = DisplayListBuilder.STAT_PREPARED_SUBTREES.sum();
        long pruned0 = DisplayListBuilder.STAT_PRUNED_SUBTREES.sum();
        List<List<Object>> prepared = run(true);
        assertTrue(DisplayListBuilder.STAT_PRUNED_SUBTREES.sum() > pruned0, "部分木の省略が働いていない（テストの前提が崩れている）");
        assertTrue(DisplayListBuilder.STAT_PREPARED_SUBTREES.sum() > prepared0, "先の並列の判定が働いていない（テストの前提が崩れている）");
        assertEquals(serial.size(), prepared.size());
        for (int i = 0; i < serial.size(); i++) {
            assertEquals(serial.get(i), prepared.get(i), "並列・先の判定あり frame " + i);
        }
    }
}
