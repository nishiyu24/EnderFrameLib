package com.nishiyu.enderframelib.bench;

import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.FrameRender;
import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.RenderBackend;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.openjdk.jmh.annotations.*;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 1 フレームのマイクロベンチ（JMH。M2c-3 で一本化した経路）: 文書を一から組んで描く {@link #fullFrame} と、同じビューで
 * {@code :hover} を切り替えた増分の 1 フレーム {@link #hoverFrame}。描画まで含めて測る（計画書 §1.3 決定 G）。
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

    private Document document;
    private StandaloneView view;
    private HtmlNode hoverTarget;
    private boolean hoverOn;
    private long now = 1000;

    private static final RenderBackend NO_DRAW = new RenderBackend() {
        @Override public void render(com.nishiyu.enderframelib.core.paint.PreparedFrame frame) { }
        @Override public com.nishiyu.enderframelib.core.raster.Pixmap pixmap() { throw new UnsupportedOperationException(); }
        @Override public float devicePixelRatio() { return 1f; }
    };

    private static HtmlNode firstButton(HtmlNode n) {
        if ("button".equals(n.tag)) return n;
        for (HtmlNode c : n.children) {
            HtmlNode r = firstButton(c);
            if (r != null) return r;
        }
        return null;
    }

    @Setup(Level.Trial)
    public void setUp() {
        document = new UiDocumentCompiler().parseDocument(Workloads.html(doc), "", Workloads.VIEWPORT_W, Workloads.VIEWPORT_H);
        view = new StandaloneView(Workloads.html(doc), Workloads.VIEWPORT_W, Workloads.VIEWPORT_H, FontResolver.FALLBACK);
        hoverTarget = firstButton(view.root());
        for (int i = 0; i < 4; i++) hoverFrame();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        view.close();
    }

    /** 文書を一から組んで描く（前回の木も記憶も無い 1 回だけのフレーム）。 */
    @Benchmark
    public List<DisplayItem> fullFrame() {
        return FrameRender.document(document, Workloads.VIEWPORT_W, Workloads.VIEWPORT_H, FontResolver.FALLBACK).items();
    }

    /** {@code :hover} を 1 回切り替えた増分の 1 フレーム（{@code <button>} が無い文書は何もしない）。 */
    @Benchmark
    public Object hoverFrame() {
        if (hoverTarget == null) return null;
        hoverOn = !hoverOn;
        view.ui().setHoveredElement(hoverOn ? hoverTarget.key() : "");
        view.frame(now += 16, NO_DRAW, true);
        return view.ui().renderCache();
    }
}
