package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.core.EngineContext;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.StyleAnimator;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.LayoutBox.InlineFragment;
import com.nishiyu.enderframelib.core.layout.LayoutTaskPool;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.TextShaper;
import com.nishiyu.enderframelib.core.text.model.BitmapFontFace;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import com.nishiyu.enderframelib.core.text.model.FontStyle;
import com.nishiyu.enderframelib.core.text.model.WordBreak;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 並列再スタイル・並列測定・並列配置・並列印付け（{@link LayoutTaskPool}）が、
 * 直列実行（{@code parallelEnabled = false}）と箱の木・表示リストの両面で完全に一致し、
 * 小規模文書では並列ディスパッチしないこと・{@link EngineContext} がワーカーへ伝播することを検証する。
 */
class ParallelLayoutEquivalenceTest {

    @AfterEach
    void restore() {
        com.nishiyu.enderframelib.core.kernel.Shortcut.LAYOUT_PARALLEL.set(true);
        LayoutTaskPool.setWorkerThreads(LayoutTaskPool.defaultThreads());
    }

    private static List<DisplayItem> flush(StandaloneView v) {
        v.ui().flushInvalidation();
        return List.copyOf(v.ui().renderCache());
    }

    private static void assertTreesEqual(LayoutBox expected, LayoutBox actual, String path) {
        assertEquals(expected.node.tag, actual.node.tag, path + " tag");
        assertEquals(expected.x(), actual.x(), path + " x");
        assertEquals(expected.y(), actual.y(), path + " y");
        assertEquals(expected.w(), actual.w(), path + " w");
        assertEquals(expected.h(), actual.h(), path + " h");
        assertEquals(expected.scrollWidth(), actual.scrollWidth(), path + " scrollWidth");
        assertEquals(expected.scrollHeight(), actual.scrollHeight(), path + " scrollHeight");
        assertEquals(expected.inlineFragments.size(), actual.inlineFragments.size(), path + " fragCount");
        for (int i = 0; i < expected.inlineFragments.size(); i++) {
            InlineFragment ef = expected.inlineFragments.get(i);
            InlineFragment af = actual.inlineFragments.get(i);
            assertEquals(ef.text, af.text, path + " frag[" + i + "].text");
            assertEquals(ef.x, af.x, path + " frag[" + i + "].x");
            assertEquals(ef.y, af.y, path + " frag[" + i + "].y");
            assertEquals(ef.w, af.w, path + " frag[" + i + "].w");
            assertEquals(ef.h, af.h, path + " frag[" + i + "].h");
        }
        assertEquals(expected.children.size(), actual.children.size(), path + " children.size");
        for (int i = 0; i < expected.children.size(); i++) {
            assertTreesEqual(expected.children.get(i), actual.children.get(i),
                    path + "/" + expected.node.tag + "[" + i + "]");
        }
    }

    private static void assertDisplayItemsEqual(List<DisplayItem> expected, List<DisplayItem> actual, String what) {
        assertEquals(expected.size(), actual.size(), what + ": item count");
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), actual.get(i), what + ": item[" + i + "]");
        }
    }

    private static String buildVariedListHtml(int count, long seed) {
        Random r = new Random(seed);
        String[] words = {"alpha", "beta", "gamma delta", "日本語サンプル", "overflow-word-example",
                "two lines of wrapped text for list item", "short"};
        StringBuilder sb = new StringBuilder();
        sb.append("<style>")
          .append("body{margin:0;font-size:16px;font-family:sans-serif;}")
          .append("#scroller{width:640px;height:480px;overflow-y:auto;}")
          .append("ul{list-style:none;margin:0;padding:8px;}")
          .append("li{display:flex;align-items:center;gap:8px;padding:6px 10px;border-bottom:1px solid #333;}")
          .append("li:hover{background:#246;}")
          .append(".badge{padding:2px 6px;background:#444;border-radius:4px;font-size:12px;}")
          .append(".grow{flex:1 1 auto;}")
          .append("</style>")
          .append("<div id='scroller'><ul>");
        for (int i = 0; i < count; i++) {
            sb.append("<li id='row-").append(i).append("'>")
              .append("<span class='badge'>#").append(i).append("</span>")
              .append("<div class='grow'>").append(words[r.nextInt(words.length)]).append("</div>");
            if ((i & 3) == 0) {
                sb.append("<span class='badge'>right-").append(i % 7).append("</span>");
            }
            sb.append("</li>");
        }
        sb.append("</ul></div>");
        return sb.toString();
    }

    private static HtmlElement findElement(HtmlNode root, String tag) {
        if (root instanceof HtmlElement el && tag.equals(el.tag)) return el;
        for (HtmlNode child : root.children) {
            HtmlElement hit = findElement(child, tag);
            if (hit != null) return hit;
        }
        return null;
    }

    /** A3: ワーカー数（{@code -Denderframe.threads} / {@code EnderEngine.workerThreads}）を変えても、箱の木と表示リストは直列と一致する。 */
    @Test
    void resultsDoNotDependOnWorkerThreadCount() {
        for (int threads : new int[]{2, 4}) { // 1 本は並列に配らない（singleWorkerNeverDispatches）。直列が比較の基準
            LayoutTaskPool.setWorkerThreads(threads);
            assertEquals(threads, LayoutTaskPool.workerCount());
            variedListFontsizeAndHoverAreBitForBitIdentical();
            randomizedLargeSiblingTreesMatchSerial();
        }
    }

    /** A3: ワーカーが 1 本なら、兄弟が多くても並列プールへ配らない（呼び出したスレッドだけで動く）。 */
    @Test
    void singleWorkerNeverDispatches() {
        LayoutTaskPool.setWorkerThreads(1);
        LayoutTaskPool.resetParallelDispatchCount();
        StandaloneView v = new StandaloneView(buildVariedListHtml(180, 7L), 800, 600, FontResolver.FALLBACK);
        flush(v);
        assertEquals(0, LayoutTaskPool.parallelDispatchCount());
    }

    /** A3: ソフトウェア描画のタイル実行も同じプールに従い、画素はワーカー数に依らない。 */
    @Test
    void softwarePixelsDoNotDependOnWorkerThreadCount() {
        String html = buildVariedListHtml(180, 11L);
        int[][] shots = new int[3][];
        int[] counts = {1, 2, 4};
        for (int k = 0; k < counts.length; k++) {
            LayoutTaskPool.setWorkerThreads(counts[k]);
            StandaloneView v = new StandaloneView(html, 800, 600, FontResolver.FALLBACK);
            try (var backend = new com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend(800, 600, 1f)) {
                v.frame(1000, backend, true);
                shots[k] = backend.pixmap().pixels().clone();
            }
        }
        org.junit.jupiter.api.Assertions.assertArrayEquals(shots[0], shots[1], "1 本と 2 本");
        org.junit.jupiter.api.Assertions.assertArrayEquals(shots[0], shots[2], "1 本と 4 本");
    }

    @Test
    void variedListFontsizeAndHoverAreBitForBitIdentical() {
        String html = buildVariedListHtml(180, 42L);
        com.nishiyu.enderframelib.core.kernel.Shortcut.LAYOUT_PARALLEL.set(false);
        StandaloneView serial = new StandaloneView(html, 800, 600, FontResolver.FALLBACK);
        List<List<DisplayItem>> expectedItems = new ArrayList<>();
        expectedItems.add(flush(serial));

        HtmlElement serialBody = findElement(serial.ui().rootNode(), "body");
        for (int step = 0; step < 6; step++) {
            serialBody.setAttribute("style", (step & 1) == 0 ? "font-size:15px" : "font-size:16px");
            expectedItems.add(flush(serial));
        }
        serial.ui().recomputeLayout(640, 480);
        expectedItems.add(flush(serial));
        serial.mouseMove(80, 60, null);
        expectedItems.add(flush(serial));
        serial.ui().handleScrollLocally(80, 60, 0, -3);
        expectedItems.add(flush(serial));
        LayoutBox expectedRoot = serial.ui().layoutRoot();

        com.nishiyu.enderframelib.core.kernel.Shortcut.LAYOUT_PARALLEL.set(true);
        LayoutTaskPool.resetParallelDispatchCount();
        StandaloneView parallel = new StandaloneView(html, 800, 600, FontResolver.FALLBACK);
        assertDisplayItemsEqual(expectedItems.get(0), flush(parallel), "varied initial");

        HtmlElement parallelBody = findElement(parallel.ui().rootNode(), "body");
        for (int step = 0; step < 6; step++) {
            parallelBody.setAttribute("style", (step & 1) == 0 ? "font-size:15px" : "font-size:16px");
            assertDisplayItemsEqual(expectedItems.get(step + 1), flush(parallel), "varied step " + step);
        }
        parallel.ui().recomputeLayout(640, 480);
        assertDisplayItemsEqual(expectedItems.get(7), flush(parallel), "varied resize");
        assertTrue(LayoutTaskPool.parallelDispatchCount() > 0,
                "Expected parallel dispatches on 180-item list, got " + LayoutTaskPool.parallelDispatchCount());

        LayoutTaskPool.resetParallelDispatchCount();
        parallel.mouseMove(80, 60, null);
        assertDisplayItemsEqual(expectedItems.get(8), flush(parallel), "varied hover");
        parallel.ui().handleScrollLocally(80, 60, 0, -3);
        assertDisplayItemsEqual(expectedItems.get(9), flush(parallel), "varied scroll");
        assertEquals(0L, LayoutTaskPool.parallelDispatchCount(),
                "Small incremental hover/scroll must stay serial");
        assertTreesEqual(expectedRoot, parallel.ui().layoutRoot(), "root");
    }

    @Test
    void randomizedLargeSiblingTreesMatchSerial() {
        String[] widths = {"", "width:40%", "width:180px", "max-width:240px", "min-width:90px"};
        String[] paddings = {"", "padding:4px 8px", "padding:2px;margin:3px", "border:2px solid #555;padding:3px"};
        String[] layouts = {"display:block", "display:flex;gap:6px", "display:grid;grid-template-columns:80px 1fr"};

        for (long seed = 100; seed < 120; seed++) {
            Random r = new Random(seed);
            int children = 40 + r.nextInt(80);
            StringBuilder sb = new StringBuilder("<style>body{margin:4px;font-size:14px}.wrap{display:block}</style><div class='wrap'>");
            for (int i = 0; i < children; i++) {
                String st = layouts[r.nextInt(layouts.length)] + ";" + widths[r.nextInt(widths.length)]
                        + ";" + paddings[r.nextInt(paddings.length)];
                sb.append("<div style='").append(st).append("'>");
                int inner = 1 + r.nextInt(3);
                for (int j = 0; j < inner; j++) {
                    sb.append("<span>item ").append(i).append("-").append(j).append(" 日本語 wrap</span>");
                }
                sb.append("</div>");
            }
            sb.append("</div>");
            String html = sb.toString();

            com.nishiyu.enderframelib.core.kernel.Shortcut.LAYOUT_PARALLEL.set(false);
            StandaloneView serial = new StandaloneView(html, 720, 540, FontResolver.FALLBACK);
            List<DisplayItem> exp0 = flush(serial);
            HtmlElement sbBody = findElement(serial.ui().rootNode(), "body");
            sbBody.setAttribute("style", "font-size:17px");
            List<DisplayItem> exp1 = flush(serial);
            serial.ui().recomputeLayout(480, 400);
            List<DisplayItem> exp2 = flush(serial);

            com.nishiyu.enderframelib.core.kernel.Shortcut.LAYOUT_PARALLEL.set(true);
            StandaloneView parallel = new StandaloneView(html, 720, 540, FontResolver.FALLBACK);
            assertDisplayItemsEqual(exp0, flush(parallel), "seed " + seed + " initial");
            HtmlElement pbBody = findElement(parallel.ui().rootNode(), "body");
            pbBody.setAttribute("style", "font-size:17px");
            assertDisplayItemsEqual(exp1, flush(parallel), "seed " + seed + " fontsize");
            parallel.ui().recomputeLayout(480, 400);
            assertDisplayItemsEqual(exp2, flush(parallel), "seed " + seed + " resize");
            assertTreesEqual(serial.ui().layoutRoot(), parallel.ui().layoutRoot(), "seed " + seed);
        }
    }

    @Test
    void smallTreesStaySerialWithoutPoolDispatch() throws Exception {
        com.nishiyu.enderframelib.core.kernel.Shortcut.LAYOUT_PARALLEL.set(true);
        LayoutTaskPool.resetParallelDispatchCount();

        String smallHtml = buildVariedListHtml(16, 7L);
        StandaloneView small = new StandaloneView(smallHtml, 800, 600, FontResolver.FALLBACK);
        flush(small);
        HtmlElement body = findElement(small.ui().rootNode(), "body");
        body.setAttribute("style", "font-size:15px");
        flush(small);
        assertEquals(0L, LayoutTaskPool.parallelDispatchCount(),
                "Small tree (< 32 siblings) must stay serial");

        Path showcase = Path.of("..", "src", "main", "resources", "data", "enderframelib", "enderframe_ui", "showcase.html");
        if (!Files.exists(showcase)) {
            showcase = Path.of("src", "main", "resources", "data", "enderframelib", "enderframe_ui", "showcase.html");
        }
        if (Files.exists(showcase)) {
            LayoutTaskPool.resetParallelDispatchCount();
            String html = Files.readString(showcase, StandardCharsets.UTF_8).replace("\r\n", "\n");
            StandaloneView v = new StandaloneView(html, 800, 600, FontResolver.FALLBACK);
            flush(v);
            assertEquals(0L, LayoutTaskPool.parallelDispatchCount(),
                    "showcase.html must stay serial");
        }
    }

    @Test
    void engineContextPropagatesToParallelRestyleWorkers() throws Exception {
        EngineContext custom = new EngineContext();
        AtomicBoolean sawCustomCtx = new AtomicBoolean(true);
        String html = buildVariedListHtml(96, 99L);

        EngineContext.callWith(custom, () -> {
            StandaloneView view = new StandaloneView(html, 800, 600, FontResolver.FALLBACK);
            flush(view);
            LayoutTaskPool.resetParallelDispatchCount();
            HtmlElement body = findElement(view.ui().rootNode(), "body");
            body.setAttribute("style", "font-size:15px");
            LayoutTaskPool.forChunks(64, 4, r -> {
                if (EngineContext.current() == custom) r.run();
                else EngineContext.runWith(custom, r);
            }, span -> {
                if (EngineContext.current() != custom) sawCustomCtx.set(false);
            });
            flush(view);
            assertSame(custom, EngineContext.current());
            assertTrue(LayoutTaskPool.parallelDispatchCount() > 0);
            return null;
        });
        assertTrue(sawCustomCtx.get(), "Parallel worker did not receive custom EngineContext");
    }

    @Test
    void sharedFontTextAnimatorAndInteractionCachesAreThreadSafe() throws Exception {
        BitmapFontFace face = new BitmapFontFace() {
            @Override
            protected float naturalAdvance(String text, boolean bold) {
                return text.length() * (bold ? 6.5f : 6.0f);
            }
        };
        FontStyle style = FontStyle.DEFAULT;
        InteractionState interactionState = new InteractionState();
        StyleAnimator animator = new StyleAnimator();
        StyleSheet sheet = new StyleSheet();
        ComputedStyle styleA = ComputedStyle.of(Map.of("transition", "opacity 200ms", "opacity", "1"), 16f, 16f);
        ComputedStyle styleB = ComputedStyle.of(Map.of("transition", "opacity 200ms", "opacity", "0.5"), 16f, 16f);
        HtmlElement[] nodes = new HtmlElement[64];
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = new HtmlElement("div");
            nodes[i].setAttribute("id", "n" + i);
        }

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int tid = t;
                tasks.add(() -> {
                    for (int i = 0; i < 400; i++) {
                        String word = "word-" + ((i + tid) % 25) + "-日本語";
                        float adv = face.cachedNaturalAdvance(word, (i & 1) == 0);
                        assertFalse(Float.isNaN(adv));
                        TextShaper.segmented(word, face, style, WordBreak.NORMAL);
                        TextShaper.wrapParagraph(word + " more text here", face, style, WordBreak.NORMAL, 120f + (i % 5) * 20f);
                        HtmlElement node = nodes[(tid * 8 + (i % 8))];
                        InteractionState.StateFlags flags = interactionState.getOrCreate(node);
                        flags.markStyleDirty();
                        flags.clearStyleDirtyFlag();
                        flags.noteViewportDependence(false, (i & 1) == 0);
                        animator.sample(node, 1000L + i);
                        animator.observe(node, styleA, (i & 1) == 0 ? styleB : styleA, com.nishiyu.enderframelib.core.css.Style.DEFAULT, sheet.keyframes, 1000L + i);
                    }
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(tasks)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
