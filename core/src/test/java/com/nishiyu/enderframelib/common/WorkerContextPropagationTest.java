package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.nishiyu.enderframelib.core.EngineContext;
import com.nishiyu.enderframelib.core.dom.CustomElementPainter;
import com.nishiyu.enderframelib.core.dom.ReplacedKind;
import com.nishiyu.enderframelib.core.kernel.Shortcut;
import com.nishiyu.enderframelib.core.layout.LayoutTaskPool;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A13: 並列のワーカー（{@link LayoutTaskPool}）も、呼び出したスレッドの面の文脈を引き継ぐ。ホストが面に登録した置換要素を、並列の
 * レイアウトでも、直列と同じに扱う（以前は再スタイル以外の並列処理が文脈を引き継がず、ワーカーが global を見ていた）。
 */
class WorkerContextPropagationTest {
    private static final int ROWS = 3 * LayoutTaskPool.PARALLEL_THRESHOLD;
    private static final int THREADS_BEFORE = LayoutTaskPool.workerCount();

    private static final CustomElementPainter ISO = new CustomElementPainter() {
        @Override public ReplacedKind kind() { return ReplacedKind.IMAGE_LIKE; }
    };

    @AfterEach
    void restore() {
        LayoutTaskPool.setWorkerThreads(THREADS_BEFORE);
        Shortcut.LAYOUT_PARALLEL.set(true);
    }

    private static String html() {
        StringBuilder sb = new StringBuilder("<body style=\"margin:0\">");
        for (int i = 0; i < ROWS; i++) {
            sb.append("<div style=\"height:22px;background:#eef\"><x-iso style=\"width:").append(10 + i % 7).append("px;height:12px\"></x-iso></div>");
        }
        return sb.append("</body>").toString();
    }

    /** 面の文脈の中で文書を組み、表示リストの署名を返す。{@code parallel} が false なら並列を止める。 */
    private static List<String> layout(EngineContext ctx, boolean parallel) {
        Shortcut.LAYOUT_PARALLEL.set(parallel);
        List<String> out = new ArrayList<>();
        EngineContext.runWith(ctx, () -> {
            try (StandaloneView v = new StandaloneView(html(), 300, 400, FontResolver.FALLBACK);
                 SoftwareRenderBackend be = new SoftwareRenderBackend(300, 400, 1f)) {
                v.frame(1000, be, true);
                for (var it : v.items()) out.add(MutationScenario.sig(it));
            }
        });
        return out;
    }

    @Test
    void parallelLayoutInAScopedContextMatchesSerialLayout() {
        LayoutTaskPool.setWorkerThreads(4);
        EngineContext withIso = new EngineContext();
        withIso.updateHost(h -> h.withReplacedElement("x-iso", ISO));
        List<String> serial = layout(withIso, false);
        List<String> parallel = layout(withIso, true);
        assertEquals(serial, parallel, "並列のワーカーも面の置換要素を見る");
        // 前提: 置換要素の登録が結果に出る（登録の無い面とは違う）
        assertNotEquals(serial, layout(new EngineContext(), false), "前提: 登録が絵に出る");
    }
}
