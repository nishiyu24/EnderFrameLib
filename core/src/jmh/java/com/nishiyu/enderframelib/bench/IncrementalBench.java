package com.nishiyu.enderframelib.bench;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * 実際の更新の経路（{@link StandaloneView} の無効化 → 差分レイアウト → 表示リスト）を、{@code large-list}（500 行）で測る。
 * M3 ゲートの「リスト 1 行挿入 &lt; 0.05 ms」用。
 *
 * <pre>./gradlew :core:jmh -Pjmh.args="IncrementalBench -f 1 -wi 3 -i 5 -prof gc"</pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(1)
@State(Scope.Thread)
public class IncrementalBench {

    /** fractional: 行の高さが小数（line-height 由来）/ integer: 行の高さが整数（後続の行が整数ぶんずれる）。 */
    @Param({"fractional", "integer"})
    public String rows;

    private StandaloneView view;
    private HtmlNode list;
    private HtmlNode row;
    private int n;

    @Setup(Level.Trial)
    public void setUp() {
        String html = Workloads.html(Workloads.LARGE_LIST);
        if (rows.equals("integer")) html = html.replace("li { padding: 4px;", "li { height: 24px; line-height: 20px; padding: 0 4px;");
        view = new StandaloneView(html, Workloads.VIEWPORT_W, Workloads.VIEWPORT_H,
                FontResolver.FALLBACK);
        view.ui().flushInvalidation();
        row = view.root().getNodeById("item-250");
        list = row.parent;
    }

    /** 1 行の文字を、同じ大きさのまま差し替える。 */
    @Benchmark
    public Object textSameSize() {
        row.updateTextContent((n++ & 1) == 0 ? "Row item #250 with description A" : "Row item #250 with description B");
        view.ui().flushInvalidation();
        return view.ui().renderCache();
    }

    /** 真ん中に 1 行を挿入して、すぐ削除する（挿入 + 削除で 2 回の更新。1 回あたりは半分）。 */
    @Benchmark
    public Object insertAndRemoveRow() {
        HtmlNode added = new HtmlElement("li");
        added.updateTextContent("inserted row " + n++);
        list.insertBefore(added, row);
        view.ui().flushInvalidation();
        list.removeChild(added);
        view.ui().flushInvalidation();
        return view.ui().renderCache();
    }
}
