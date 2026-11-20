package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.nishiyu.enderframelib.core.EngineContext;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.parser.ParseLimits;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * A13 の通し検証: ホストの差し込み（タグマクロ・属性の別名・解析の上限）が違う 2 つの面を、同じスレッドで交互に・別のスレッドで同時に
 * 描いても、毎フレームの全画素が、その面を 1 つだけで描いたときと一致する（面の間で状態が漏れない）。
 */
class TwoSurfacesIsolationTest {
    private static final int W = 200, H = 120, STEPS = 8;
    private static final String DOC = "<body style=\"margin:0;background:#101820\">"
            + "<x-card></x-card>"
            + "<div id=\"flag\" data-iso=\"1\" style=\"width:30px;height:10px;background:#444\"></div>"
            + "<p id=\"t\" style=\"color:#fff;font-size:12px;margin:4px\">0</p></body>";

    /** 面ごとに違う差し込み: {@code <x-card>} の展開結果（色）・属性の別名・解析の上限。 */
    private static EngineContext surface(String color, int maxTokens) {
        EngineContext c = new EngineContext();
        c.updateHost(h -> h.withTagMacro("x-card", src -> {
            HtmlElement d = new HtmlElement("div");
            d.attrs.put("style", "width:80px;height:40px;background:" + color);
            return List.of(d);
        }).withAttributeAlias("data-iso", "data-if").withParseLimits(new ParseLimits(maxTokens, 256, false)));
        return c;
    }

    /** 1 つの面の描画（面を作るのも、各フレームも、その面の文脈の中で行う）。 */
    private static final class Surface implements AutoCloseable {
        final EngineContext ctx;
        final StandaloneView view;
        final SoftwareRenderBackend backend = new SoftwareRenderBackend(W, H, 1f);
        long now = 1000;

        Surface(EngineContext ctx) {
            this.ctx = ctx;
            StandaloneView[] v = new StandaloneView[1];
            EngineContext.runWith(ctx, () -> v[0] = new StandaloneView(DOC, W, H, FontResolver.FALLBACK));
            this.view = v[0];
        }

        int[] step(int i) {
            int[][] out = new int[1][];
            EngineContext.runWith(ctx, () -> {
                if (i > 0) {
                    HtmlNode t = view.root().getNodeById("t");
                    t.updateTextContent("n" + i);
                }
                now += 16;
                view.frame(now, backend, true);
                out[0] = backend.pixmap().pixels().clone();
            });
            return out[0];
        }

        @Override public void close() {
            view.close();
            backend.close();
        }
    }

    private static List<int[]> solo(EngineContext ctx) {
        List<int[]> out = new ArrayList<>();
        try (Surface s = new Surface(ctx)) {
            for (int i = 0; i < STEPS; i++) out.add(s.step(i));
        }
        return out;
    }

    private static void assertSame(List<int[]> expected, List<int[]> actual, String what) {
        assertEquals(expected.size(), actual.size(), what);
        for (int i = 0; i < expected.size(); i++) assertArrayEquals(expected.get(i), actual.get(i), what + " フレーム " + i);
    }

    @Test
    void theTwoSurfacesActuallyDifferFromEachOther() {
        List<int[]> a = solo(surface("#d33", 5000)), b = solo(surface("#33d", 5000));
        assertFalse(java.util.Arrays.equals(a.get(0), b.get(0)), "前提: 面の差し込み（タグマクロの色）が絵に出る");
    }

    @Test
    void alternatingFramesOnOneThreadMatchEachSurfaceRenderedAlone() {
        EngineContext ca = surface("#d33", 5000), cb = surface("#33d", 4000);
        List<int[]> soloA = solo(ca), soloB = solo(cb);
        List<int[]> a = new ArrayList<>(), b = new ArrayList<>();
        try (Surface sa = new Surface(ca); Surface sb = new Surface(cb)) {
            for (int i = 0; i < STEPS; i++) { // 同じスレッドで 1 フレームずつ交互に
                a.add(sa.step(i));
                b.add(sb.step(i));
            }
        }
        assertSame(soloA, a, "面 A");
        assertSame(soloB, b, "面 B");
    }

    @Test
    void twoThreadsRenderingTheirSurfacesAtTheSameTimeMatchEachRenderedAlone() throws Exception {
        EngineContext ca = surface("#d33", 5000), cb = surface("#33d", 4000);
        List<int[]> soloA = solo(ca), soloB = solo(cb);
        List<List<int[]>> got = new ArrayList<>();
        got.add(null);
        got.add(null);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread[] ts = new Thread[2];
        for (int t = 0; t < 2; t++) {
            int idx = t;
            EngineContext mine = t == 0 ? ca : cb;
            ts[t] = new Thread(() -> {
                try {
                    List<int[]> out = new ArrayList<>();
                    for (int round = 0; round < 3; round++) { // 何度か回して同時の期間を長くする
                        out.clear();
                        try (Surface s = new Surface(mine)) {
                            for (int i = 0; i < STEPS; i++) out.add(s.step(i));
                        }
                    }
                    synchronized (got) { got.set(idx, out); }
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                }
            });
            ts[t].start();
        }
        for (Thread t : ts) t.join();
        assertNull(error.get(), String.valueOf(error.get()));
        assertSame(soloA, got.get(0), "面 A（同時）");
        assertSame(soloB, got.get(1), "面 B（同時）");
    }
}
