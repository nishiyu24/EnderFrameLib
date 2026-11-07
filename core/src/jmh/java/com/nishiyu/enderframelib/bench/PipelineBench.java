package com.nishiyu.enderframelib.bench;

import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.DisplayListBuilder;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.openjdk.jmh.annotations.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * パイプライン各段のマイクロベンチ（JMH）。段の切り方は {@code PerformanceBenchmarkTest.measureDocument} と同じ:
 * 再スタイル / 再レイアウト / ディスプレイリスト生成 / スクロール 1 段。加えて、箱の木を一から作る {@link #fullLayout}。
 *
 * <p>JUnit 内の平均と違い、フォークした JVM で JIT のウォームアップと外れ値を分けて測る。
 * {@code -prof gc} を付ければ 1 操作あたりの割り当て量（B/op）も出る（{@code ./gradlew :core:jmh -Pjmh.args="-prof gc"}）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xmx1g"})
@State(Scope.Thread)
public class PipelineBench {

    @Param({Workloads.SHOWCASE, Workloads.GUIDEBOOK, Workloads.LARGE_LIST})
    public String doc;

    private final UiDocumentCompiler parser = new UiDocumentCompiler();
    private Document document;
    private LayoutBox root;
    private HtmlNode scroller;

    @Setup(Level.Trial)
    public void setUp() {
        document = parser.parseDocument(Workloads.html(doc), "", Workloads.VIEWPORT_W, Workloads.VIEWPORT_H);
        scroller = document.root.getElementById("scroller");
        root = parser.layoutDocument(document, Workloads.VIEWPORT_W, Workloads.VIEWPORT_H, Map.of(), new ScrollState(),
                FontResolver.FALLBACK, new InteractionState());
    }

    /** 箱の木を一から作って測り、配置する（差分なし）。 */
    @Benchmark
    public LayoutBox fullLayout() {
        return parser.layoutDocument(document, Workloads.VIEWPORT_W, Workloads.VIEWPORT_H, Map.of(), new ScrollState(),
                FontResolver.FALLBACK, new InteractionState());
    }

    @Benchmark
    public Object restyle() {
        return parser.restyleLayoutTree(root, document, Workloads.VIEWPORT_W, Workloads.VIEWPORT_H, Map.of(), new InteractionState());
    }

    @Benchmark
    public void relayout() {
        parser.runLayoutPasses(root, Workloads.VIEWPORT_W, Workloads.VIEWPORT_H, new ScrollState(), FontResolver.FALLBACK);
    }

    @Benchmark
    public List<DisplayItem> displayList() {
        return new DisplayListBuilder(Workloads.VIEWPORT_W, Workloads.VIEWPORT_H, document.sheet.keyframes, new ScrollState())
                .render(root);
    }

    /** スクロールコンテナを 20px 動かした再レイアウトとディスプレイリスト生成（コンテナが無い文書は動かさない）。 */
    @Benchmark
    public List<DisplayItem> scrollStep() {
        ScrollState scroll = new ScrollState();
        scroll.setScroll(scroller, 0, 20);
        parser.runLayoutPasses(root, Workloads.VIEWPORT_W, Workloads.VIEWPORT_H, scroll, FontResolver.FALLBACK);
        return new DisplayListBuilder(Workloads.VIEWPORT_W, Workloads.VIEWPORT_H, document.sheet.keyframes, scroll).render(root);
    }
}
