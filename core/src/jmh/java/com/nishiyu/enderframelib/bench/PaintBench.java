package com.nishiyu.enderframelib.bench;

import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.DisplayListBuilder;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.openjdk.jmh.annotations.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 完成したディスプレイリストを CPU ラスタで {@code Pixmap} に描く時間（GL を使わないので CI でも測れる）。 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xmx1g"})
@State(Scope.Thread)
public class PaintBench {

    @Param({Workloads.SHOWCASE, Workloads.GUIDEBOOK, Workloads.LARGE_LIST})
    public String doc;

    private SoftwareRenderBackend cpu;
    private List<DisplayItem> items;
    private long now = 1000;

    @Setup(Level.Trial)
    public void setUp() {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        var document = parser.parseDocument(Workloads.html(doc), "", Workloads.VIEWPORT_W, Workloads.VIEWPORT_H);
        LayoutBox root = parser.layoutDocument(document, Workloads.VIEWPORT_W, Workloads.VIEWPORT_H, Map.of(), new ScrollState(),
                FontResolver.FALLBACK, new InteractionState());
        items = new DisplayListBuilder(Workloads.VIEWPORT_W, Workloads.VIEWPORT_H, document.sheet.keyframes, new ScrollState()).render(root);
        cpu = new SoftwareRenderBackend(Workloads.VIEWPORT_W, Workloads.VIEWPORT_H, 1f);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        cpu.close();
    }

    @Benchmark
    public int softwarePaint() {
        cpu.render(items, now += 16);
        return cpu.pixmap().pixels()[0];
    }
}
