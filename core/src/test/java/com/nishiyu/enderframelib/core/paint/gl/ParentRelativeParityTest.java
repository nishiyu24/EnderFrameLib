package com.nishiyu.enderframelib.core.paint.gl;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.EngineContext;
import com.nishiyu.enderframelib.core.paint.DisplayListBuilder;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * B3 親相対 DisplayItem の画素等価性・無再構築・無 appendTranslated 検証テスト。
 */
class ParentRelativeParityTest {
    private static final long T0 = 10_000L;
    private static final int W = 800, H = 600;
    private static final boolean ORIG_PARENT_REL = EngineContext.current().parentRelativeDisplayItems;

    @AfterEach
    void restoreFlag() {
        EngineContext.current().parentRelativeDisplayItems = ORIG_PARENT_REL;
    }

    private static Pixmap renderSw(String html, int w, int h, boolean parentRel, double scrollY, double hoverX, double hoverY) {
        EngineContext.current().parentRelativeDisplayItems = parentRel;
        try (StandaloneView v = new StandaloneView(html, w, h, FontResolver.FALLBACK);
             SoftwareRenderBackend sw = new SoftwareRenderBackend(w, h, 1f)) {
            if (scrollY != 0) v.scroll(0, scrollY, sw.sessionId());
            if (hoverX >= 0) v.mouseMove(hoverX, hoverY, sw.sessionId());
            v.frame(T0, sw, true);
            return sw.pixmap().copy();
        }
    }

    private static Pixmap renderGl(String html, int w, int h, boolean parentRel) {
        try (LwjglGlRenderBackend ignored = new LwjglGlRenderBackend(8, 8)) {
        } catch (Throwable t) {
            Assumptions.abort("OpenGL を使えない: " + t);
        }
        EngineContext.current().parentRelativeDisplayItems = parentRel;
        try (StandaloneView v = new StandaloneView(html, w, h, FontResolver.FALLBACK);
             LwjglGlRenderBackend gl = new LwjglGlRenderBackend(w, h, 1f)) {
            v.frame(T0, gl, true);
            return gl.pixmap().copy();
        }
    }

    private static void assertExactPixmapEquals(String label, Pixmap expected, Pixmap actual) {
        assertPixmapDiffWithin(label, expected, actual, 0, 0);
    }

    private static void assertPixmapDiffWithin(String label, Pixmap expected, Pixmap actual, int maxChanDiff, int maxDiffPixels) {
        int[] ep = expected.pixels();
        int[] ap = actual.pixels();
        assertEquals(ep.length, ap.length, label + " pixel array length");
        int diffs = 0;
        int worstDiff = 0;
        StringBuilder first = new StringBuilder();
        for (int i = 0; i < ep.length; i++) {
            int e = ep[i], a = ap[i];
            if (e != a) {
                diffs++;
                for (int sh = 0; sh < 32; sh += 8) {
                    worstDiff = Math.max(worstDiff, Math.abs(((e >>> sh) & 0xFF) - ((a >>> sh) & 0xFF)));
                }
                if (diffs <= 8) {
                    int x = i % expected.width(), y = i / expected.width();
                    first.append(String.format(" (%d,%d: exp=%08x act=%08x)", x, y, e, a));
                }
            }
        }
        assertTrue(worstDiff <= maxChanDiff && diffs <= maxDiffPixels,
                label + " worstDiff=" + worstDiff + " (limit " + maxChanDiff + "), diffs=" + diffs + " (limit " + maxDiffPixels + ")" + first);
    }

    @Test
    void scenariosMatchBitForBitSwAndGl() throws IOException {
        for (String name : List.of("showcase", "large-list", "varied-list")) {
            String html = GpuBaselineTest.doc(name);
            assertExactPixmapEquals("SW " + name,
                    renderSw(html, W, H, false, 0, -1, -1),
                    renderSw(html, W, H, true, 0, -1, -1));
            if ("showcase".equals(name)) {
                // GL linear-gradient evaluates (fragCoord - boxOrigin) * gA + gA.z in float32 FMA; 0.5 ULP flips <= 1 LSB on boundary pixels
                assertPixmapDiffWithin("GL " + name,
                        renderGl(html, W, H, false),
                        renderGl(html, W, H, true), 1, 30);
            } else {
                assertExactPixmapEquals("GL " + name,
                        renderGl(html, W, H, false),
                        renderGl(html, W, H, true));
            }
        }
    }

    /** 疑似要素（::before / ::after）の文字の節は文書に繋がらない。節の番号で鍵を作ると、複数の疑似要素で鍵が衝突して文字が消えた。 */
    @Test
    void pseudoElementTextKeysDoNotCollide() {
        String twoSiblings = "<html><head><style>.b::before{content:'b';background:yellow}</style></head>"
                + "<body><div class='b'>x</div><div class='b'>x</div></body></html>";
        assertExactPixmapEquals("pseudo siblings", renderSw(twoSiblings, 400, 200, false, 0, -1, -1), renderSw(twoSiblings, 400, 200, true, 0, -1, -1));
        String flex = """
                <html><head><style>
                .fc { display: flex; align-items: flex-end; justify-content: space-between; height: 50px; width: 300px; margin-bottom: 2px; background: lightgray; }
                div.b::before { align-self: center; content: 'b'; background: yellow; }
                div.a::after { align-self: center; content: 'a'; background: lightblue; }
                </style></head><body>
                <div class="fc b">x<div>y</div>z</div>
                <div class="fc a">x<div>y</div>z</div>
                <div class="fc b a">x<div>y</div>z</div>
                </body></html>
                """;
        assertExactPixmapEquals("pseudo flex items", renderSw(flex, 400, 200, false, 0, -1, -1), renderSw(flex, 400, 200, true, 0, -1, -1));
    }

    /**
     * 大きい文書（窓付きの表示リスト）で、窓の外の箱が省かれたまま、動いている子（アニメーション中の絶対配置）だけが残る。
     * 子の座標を省かれた親からの相対にすると、親を引けず根の座標で描かれて、画面の左上へ崩れる（ShowcaseApp で再現した不具合）。
     */
    @Test
    void culledAncestorOfAnimatedChildKeepsParity() throws IOException {
        String html = java.nio.file.Files.readString(java.nio.file.Path.of("core/demo/resources/showcase.html"));
        int frames = 4;
        Pixmap[][] px = new Pixmap[2][frames];
        for (int mode = 0; mode < 2; mode++) {
            EngineContext.current().parentRelativeDisplayItems = mode == 1;
            try (StandaloneView v = new StandaloneView(html, 1280, 800, FontResolver.FALLBACK);
                 SoftwareRenderBackend sw = new SoftwareRenderBackend(1280, 800, 1f)) {
                var grid = v.document().getElementById("grid");
                for (int g = 0; g < 1000; g++) { // 箱が窓付きにする数を超える
                    var cell = v.document().createElement("div");
                    cell.setClassName("px");
                    grid.appendChild(cell);
                }
                var clock = v.document().getElementById("clock");
                for (int i = 0; i < frames; i++) {
                    if (i >= 1) clock.setTextContent("12:00:" + (10 + i));
                    v.frame(i * 100L, sw, true);
                    px[mode][i] = sw.pixmap().copy();
                }
            }
        }
        for (int i = 0; i < frames; i++) assertPixmapDiffWithin("frame " + i, px[0][i], px[1][i], 1, 200);
    }

    @Test
    void richFeaturesMatchBitForBit() {
        String html = """
                <html><head><style>
                body { margin: 8px; font-size: 14px; background: #1e222a; color: #eee; }
                .card { padding: 10px 14px; margin: 8px; border: 2px solid #557; border-radius: 10px;
                        background: linear-gradient(135deg, #2a3140, #1c222d);
                        box-shadow: 2px 4px 8px rgba(0,0,0,0.45); transform: rotate(2deg) scale(1.02); }
                .card:hover { background: #3b465c; }
                .skew { transform: skewX(6deg); padding: 6px; background: #354; border-radius: 4px; }
                .clip { width: 220px; height: 64px; overflow: hidden; border: 1px solid #88a; padding: 6px; }
                .scroll { width: 260px; height: 90px; overflow-y: auto; border: 1px solid #79a; padding: 6px; }
                .row { padding: 6px; margin: 4px 0; background: #2d3544; text-shadow: 1px 1px 2px #000; }
                fieldset { border: 2px solid #89a; border-radius: 6px; padding: 8px 12px; margin-top: 8px; }
                legend { padding: 0 6px; background: #1e222a; color: #bdf; font-weight: bold; }
                .glass { backdrop-filter: blur(4px); background: rgba(255,255,255,0.12); padding: 8px; }
                </style></head><body>
                <div class="card"><span class="skew">Rotated + Skewed + Shadow</span> Inline <b>fragment A</b> and <i>fragment B</i> across <span style="padding:2px 6px;border-radius:4px;background:#446">pill</span></div>
                <div class="clip"><div class="glass">Clipped backdrop-filter glass box with overflow content beyond bounds</div></div>
                <div class="scroll">
                  <div class="row">Row 1</div><div class="row">Row 2</div><div class="row">Row 3</div>
                  <div class="row">Row 4</div><div class="row">Row 5</div><div class="row">Row 6</div>
                </div>
                <fieldset><legend>Section Title</legend><span>Fieldset body content</span></fieldset>
                </body></html>
                """;
        // Rotated (.card rotate(2deg)) + box-shadow/linear-gradient produces <= 1 LSB rounding difference on pixel (419,49)
        assertPixmapDiffWithin("rich default SW", renderSw(html, 480, 400, false, 0, -1, -1), renderSw(html, 480, 400, true, 0, -1, -1), 1, 2);
        assertPixmapDiffWithin("rich scroll+hover SW", renderSw(html, 480, 400, false, 35, 100, 50), renderSw(html, 480, 400, true, 35, 100, 50), 1, 2);
        assertPixmapDiffWithin("rich resized SW", renderSw(html, 360, 420, false, 0, -1, -1), renderSw(html, 360, 420, true, 0, -1, -1), 1, 2);
    }

    @Test
    void resizeDoesNotTriggerExtentOrPositionMatchFailsOrAppendTranslated() throws IOException {
        EngineContext.current().parentRelativeDisplayItems = true;
        // 1. large-list width resize: zero MATCHFAIL[3/4] and zero appendTranslated
        try (StandaloneView v = new StandaloneView(GpuBaselineTest.doc("large-list"), W, H, FontResolver.FALLBACK);
             SoftwareRenderBackend sw = new SoftwareRenderBackend(W, H, 1f)) {
            v.frame(T0, sw, true);
            long mf3Before = DisplayListBuilder.STAT_MATCH_FAIL[3].sum();
            long mf4Before = DisplayListBuilder.STAT_MATCH_FAIL[4].sum();
            long appBefore = DisplayListBuilder.STAT_APPEND_TRANSLATED.sum();

            for (int w : new int[]{780, 760, 740, 720, 760, 800}) {
                v.resize(w, H);
                v.frame(T0 + 16, sw, true);
            }
            assertEquals(0L, DisplayListBuilder.STAT_MATCH_FAIL[3].sum() - mf3Before, "large-list MATCHFAIL[3] parentPosDiff must be 0");
            assertEquals(0L, DisplayListBuilder.STAT_MATCH_FAIL[4].sum() - mf4Before, "large-list MATCHFAIL[4] boxExtentDiff must be 0");
            assertEquals(0L, DisplayListBuilder.STAT_APPEND_TRANSLATED.sum() - appBefore, "large-list STAT_APPEND_TRANSLATED must be 0 on resize");
        }

        // 2. varied-list width resize: zero MATCHFAIL[3] and zero MATCHFAIL[4] even with wrapping + subpixel line heights
        try (StandaloneView v = new StandaloneView(GpuBaselineTest.doc("varied-list"), W, H, FontResolver.FALLBACK);
             SoftwareRenderBackend sw = new SoftwareRenderBackend(W, H, 1f)) {
            v.frame(T0, sw, true);
            long mf3Before = DisplayListBuilder.STAT_MATCH_FAIL[3].sum();
            long mf4Before = DisplayListBuilder.STAT_MATCH_FAIL[4].sum();
            long appBefore = DisplayListBuilder.STAT_APPEND_TRANSLATED.sum();

            for (int w : new int[]{780, 760, 740, 720, 760, 800}) {
                v.resize(w, H);
                v.frame(T0 + 16, sw, true);
            }
            assertEquals(0L, DisplayListBuilder.STAT_MATCH_FAIL[3].sum() - mf3Before, "varied-list MATCHFAIL[3] parentPosDiff must be 0");
            assertEquals(0L, DisplayListBuilder.STAT_MATCH_FAIL[4].sum() - mf4Before, "varied-list MATCHFAIL[4] boxExtentDiff must be 0");
            // Height resize on varied-list also triggers zero appendTranslated
            long appHeightBefore = DisplayListBuilder.STAT_APPEND_TRANSLATED.sum();
            for (int h : new int[]{560, 520, 580, H}) {
                v.resize(W, h);
                v.frame(T0 + 32, sw, true);
            }
            assertEquals(0L, DisplayListBuilder.STAT_APPEND_TRANSLATED.sum() - appHeightBefore, "varied-list height resize appendTranslated must be 0");
            long totalApp = DisplayListBuilder.STAT_APPEND_TRANSLATED.sum() - appBefore;
            assertTrue(totalApp < 100, "varied-list subpixel rounding appendTranslated stays bounded, got " + totalApp);
        }
    }
}
