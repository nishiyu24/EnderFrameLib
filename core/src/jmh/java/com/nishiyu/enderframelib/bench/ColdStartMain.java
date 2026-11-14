package com.nishiyu.enderframelib.bench;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.RenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

/** 新しい JVM で文書を開き、解析・初回フレーム・最初の数フレームの時間を測る（コールド JVM）。1 JVM につき 1 行出力。 */
public class ColdStartMain {
    private static final class NullBackend implements RenderBackend {
        @Override public void render(java.util.List<com.nishiyu.enderframelib.core.paint.model.DisplayItem> items, long nowMs) { }
        @Override public com.nishiyu.enderframelib.core.raster.Pixmap pixmap() { throw new UnsupportedOperationException(); }
        @Override public float devicePixelRatio() { return 1f; }
    }

    public static void main(String[] args) {
        String doc = args.length > 0 ? args[0] : "showcase";
        long t0 = System.nanoTime();
        String html = Workloads.html(doc);
        long t1 = System.nanoTime();
        StandaloneView v = new StandaloneView(html, Workloads.VIEWPORT_W, Workloads.VIEWPORT_H, FontResolver.FALLBACK);
        long t2 = System.nanoTime();
        NullBackend b = new NullBackend();
        double[] f = new double[10];
        for (int i = 0; i < f.length; i++) {
            long s = System.nanoTime();
            v.frame(1000 + i * 16, b, true);
            f[i] = (System.nanoTime() - s) / 1e6;
        }
        System.out.printf("COLD doc=%s html=%.1f open=%.1f first=%.1f f2=%.1f f3=%.1f f10=%.1f total(open+first)=%.1f ms%n",
                doc, (t1 - t0) / 1e6, (t2 - t1) / 1e6, f[0], f[1], f[2], f[9], (t2 - t1) / 1e6 + f[0]);
        v.close();
    }
}
