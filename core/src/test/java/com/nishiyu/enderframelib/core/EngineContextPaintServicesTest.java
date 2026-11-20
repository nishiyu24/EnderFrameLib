package com.nishiyu.enderframelib.core;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.paint.PaintServices;
import com.nishiyu.enderframelib.core.raster.svg.SvgImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** A13: {@code PaintServices} の差し込み（SVG・固有サイズ・画素数の既定）が面ごとの文脈（HostServices）にあり、面同士で混ざらない。 */
class EngineContextPaintServicesTest {
    private static final SvgImage RED = SvgImage.parse("<svg xmlns='http://www.w3.org/2000/svg' width='4' height='4'><rect width='4' height='4' fill='red'/></svg>");
    private static final SvgImage BLUE = SvgImage.parse("<svg xmlns='http://www.w3.org/2000/svg' width='8' height='8'><rect width='8' height='8' fill='blue'/></svg>");

    private static EngineContext ctx(SvgImage svg, int w, int h, int pw, int ph) {
        EngineContext c = new EngineContext();
        c.updateHost(host -> host.withSvgSource(src -> svg)
                .withIntrinsicSizeResolver(u -> w < 0 ? null : new int[] {w, h})
                .withPixelSizeFallback(u -> new int[] {pw, ph}));
        return c;
    }

    @Test
    void eachContextSeesItsOwnServices() {
        EngineContext a = ctx(RED, 10, 20, 1, 2);
        EngineContext b = ctx(BLUE, -1, -1, 3, 4);
        EngineContext.runWith(a, () -> {
            assertSame(RED, PaintServices.svg("x.svg"));
            assertArrayEquals(new int[] {10, 20}, PaintServices.intrinsicSize("u"));
            assertArrayEquals(new int[] {10, 20}, PaintServices.imagePixelSize("u"));
        });
        EngineContext.runWith(b, () -> {
            assertSame(BLUE, PaintServices.svg("x.svg"));
            assertNull(PaintServices.intrinsicSize("u"));
            assertArrayEquals(new int[] {3, 4}, PaintServices.imagePixelSize("u"), "固有サイズが無ければ画素数の既定");
        });
        // スコープの外（global）は別面の値を見ない
        assertNotSame(RED, PaintServices.svg("x.svg"));
        assertNotSame(BLUE, PaintServices.svg("x.svg"));
        assertNull(PaintServices.intrinsicSize("u"));
    }

    @Test
    void twoThreadsRunningAtTheSameTimeDoNotMix() throws Exception {
        EngineContext a = ctx(RED, 10, 20, 1, 2);
        EngineContext b = ctx(BLUE, 30, 40, 3, 4);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Thread[] ts = new Thread[2];
        for (int t = 0; t < 2; t++) {
            EngineContext mine = t == 0 ? a : b;
            SvgImage want = t == 0 ? RED : BLUE;
            int w = t == 0 ? 10 : 30;
            ts[t] = new Thread(() -> {
                try {
                    go.await();
                    EngineContext.runWith(mine, () -> {
                        for (int i = 0; i < 20_000; i++) {
                            if (PaintServices.svg("s") != want || PaintServices.intrinsicSize("u")[0] != w) {
                                failure.compareAndSet(null, "別の面の値を読んだ（反復 " + i + "）");
                                return;
                            }
                        }
                    });
                } catch (InterruptedException e) {
                    failure.compareAndSet(null, e.toString());
                }
            });
            ts[t].start();
        }
        go.countDown();
        for (Thread t : ts) t.join();
        assertNull(failure.get());
    }

    @Test
    void updatingOneContextLeavesGlobalAndOthersAlone() {
        EngineContext a = new EngineContext();
        EngineContext.runWith(a, () -> EngineContext.current().updateHost(h -> h.withSvgSource(s -> RED)));
        assertSame(RED, callWith(a));
        assertNotSame(RED, PaintServices.svg("q"));
        assertNull(EngineContext.global().host().svgSource());
    }

    private static SvgImage callWith(EngineContext c) {
        var out = new SvgImage[1];
        EngineContext.runWith(c, () -> out[0] = PaintServices.svg("q"));
        return out[0];
    }

    /** 番人: static の差し込み口（setter・volatile 欄）が PaintServices に戻らない。 */
    @Test
    void paintServicesStaysStateless() throws Exception {
        String src = Files.readString(Path.of("core/src/main/java/com/nishiyu/enderframelib/core/paint/PaintServices.java"));
        assertFalse(src.contains("public static void set"), "static setter が戻った");
        assertEquals(1, src.split("static volatile", -1).length - 1, "static volatile は文脈の引き口 1 つだけ");
        assertFalse(Files.readString(Path.of("core/src/main/java/com/nishiyu/enderframelib/core/paint/software/ImageSizes.java")).contains("installDefault"));
    }
}
