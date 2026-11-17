package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutTaskPool;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 並列実行の決定性を、乱数で作った変更列で確かめる（ENGINE_REARCHITECTURE_PLAN A5③）。
 *
 * <p>並列ディスパッチが起きる大きさの文書（兄弟 60〜140 個）に、同じ変更列
 * （{@code style} 属性・本文・子の追加と削除・幅変更）を直列の面と並列の面へ同じ順に適用し、毎ステップ表示リストが完全一致することを見る。
 * ワーカー数は 2 / 4 / 既定。1 ワーカーあたりの変更は既定 {@value #DEFAULT_SEEDS} 本 × {@value #STEPS} ステップ ≒ 1,000 回
 * （{@code -Dparallel.stress.seeds=} で増やせる）。</p>
 */
class ParallelMutationStressTest {

    private static final int DEFAULT_SEEDS = 125;
    private static final int STEPS = 8;

    private static final String[] STYLES = {
            "", "width:40%", "width:180px", "max-width:240px", "min-width:90px", "padding:4px 8px", "margin:3px;padding:2px",
            "border:2px solid #555;padding:3px", "font-size:19px", "font-size:11px;letter-spacing:1px", "display:flex;gap:6px",
            "display:grid;grid-template-columns:80px 1fr", "display:block", "height:60px;overflow:hidden", "line-height:1.8",
            "float:left;width:70px", "display:none", "opacity:0.5;background:#246"};
    private static final String[] TEXTS = {"short", "日本語の長めの文章がここに入ります wrap wrap", "overflow-word-example-without-break",
            "two lines of wrapped text for the list item", "a", ""};

    @AfterEach
    void restore() {
        LayoutTaskPool.parallelEnabled = true;
        LayoutTaskPool.setWorkerThreads(LayoutTaskPool.defaultThreads());
    }

    private static String html(Random r) {
        int children = 60 + r.nextInt(80);
        StringBuilder sb = new StringBuilder("<style>body{margin:4px;font-size:14px}.wrap{display:block}</style><div class='wrap'>");
        for (int i = 0; i < children; i++) {
            sb.append("<div style='").append(STYLES[r.nextInt(STYLES.length)]).append("'>");
            int inner = 1 + r.nextInt(3);
            for (int j = 0; j < inner; j++) sb.append("<span>item ").append(i).append('-').append(j).append(" 日本語 wrap</span>");
            sb.append("</div>");
        }
        return sb.append("</div>").toString();
    }

    private static List<DisplayItem> flush(StandaloneView v) {
        v.ui().flushInvalidation();
        List<DisplayItem> out = new ArrayList<>();
        for (DisplayItem it : v.ui().renderCache()) out.add(it.frozen());
        return out;
    }

    private static HtmlElement wrap(HtmlNode root) {
        if (root instanceof HtmlElement el && el.hasClass("wrap")) return el;
        for (HtmlNode c : root.children) {
            HtmlElement hit = wrap(c);
            if (hit != null) return hit;
        }
        return null;
    }

    private static List<HtmlElement> elementChildren(HtmlElement parent) {
        List<HtmlElement> out = new ArrayList<>();
        for (HtmlNode c : parent.children) if (c instanceof HtmlElement e) out.add(e);
        return out;
    }

    /** 変更を 1 つ適用する。{@code r} の消費は面によらず同じ（直列と並列で同じ列になる）。 */
    private static void mutate(StandaloneView v, Random r, int[] size) {
        HtmlElement w = wrap(v.ui().rootNode());
        List<HtmlElement> kids = elementChildren(w);
        int kind = r.nextInt(10);
        int idx = r.nextInt(kids.size());
        String style = STYLES[r.nextInt(STYLES.length)];
        String text = TEXTS[r.nextInt(TEXTS.length)];
        int newW = 320 + r.nextInt(500);
        switch (kind) {
            case 0, 1, 2, 3 -> kids.get(idx).setAttribute("style", style);
            case 4, 5 -> {
                List<HtmlElement> spans = elementChildren(kids.get(idx));
                if (!spans.isEmpty()) spans.get(0).setTextContent(text);
            }
            case 6 -> {
                HtmlElement n = (HtmlElement) kids.get(idx).cloneNode(true);
                n.setAttribute("style", style);
                w.appendChild(n);
            }
            case 7 -> {
                if (kids.size() > 50) w.removeChild(kids.get(idx));
            }
            case 8 -> {
                size[0] = newW;
                v.ui().recomputeLayout(size[0], size[1]);
            }
            default -> {
                for (int i = 0; i < 5; i++) kids.get((idx + i * 7) % kids.size()).setAttribute("style", STYLES[(i + idx) % STYLES.length]);
            }
        }
    }

    private void runWith(int threads) {
        int seeds = Integer.getInteger("parallel.stress.seeds", DEFAULT_SEEDS);
        if (threads > 0) LayoutTaskPool.setWorkerThreads(threads);
        LayoutTaskPool.resetParallelDispatchCount();
        for (long seed = 0; seed < seeds; seed++) {
            String doc = html(new Random(seed * 7919 + 13));
            Random rs = new Random(seed), rp = new Random(seed);
            int[] ss = {720, 540}, sp = {720, 540};
            LayoutTaskPool.parallelEnabled = false;
            StandaloneView serial = new StandaloneView(doc, ss[0], ss[1], FontResolver.FALLBACK);
            List<DisplayItem> expected = flush(serial);
            LayoutTaskPool.parallelEnabled = true;
            StandaloneView parallel = new StandaloneView(doc, sp[0], sp[1], FontResolver.FALLBACK);
            assertEquals(expected, flush(parallel), "threads " + threads + " seed " + seed + " initial");
            for (int step = 0; step < STEPS; step++) {
                LayoutTaskPool.parallelEnabled = false;
                mutate(serial, rs, ss);
                expected = flush(serial);
                LayoutTaskPool.parallelEnabled = true;
                mutate(parallel, rp, sp);
                assertEquals(expected, flush(parallel), "threads " + threads + " seed " + seed + " step " + step);
            }
        }
    }

    @Test
    void twoWorkersMatchSerialUnderRandomMutations() { runWith(2); }

    @Test
    void fourWorkersMatchSerialUnderRandomMutations() { runWith(4); }

    @Test
    void defaultWorkersMatchSerialUnderRandomMutations() {
        runWith(0);
        // 並列の経路を実際に通ったことの確認（直列のままなら比較が空振りになる）
        org.junit.jupiter.api.Assertions.assertTrue(LayoutTaskPool.parallelDispatchCount() > 0 || LayoutTaskPool.workerCount() < 2,
                "並列ディスパッチが 1 回も起きていない");
    }
}
