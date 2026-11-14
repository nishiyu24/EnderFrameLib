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
        // -Dcold.sample=1: メインスレッドのスタックを 1 ms ごとに採って、開く処理の中身（クラスロード・初期化を含む）を集計する
        final java.util.Map<String, Integer> incl = new java.util.TreeMap<>();
        final java.util.Map<String, Integer> top = new java.util.TreeMap<>();
        final int[] total = {0};
        Thread sampler = null;
        if (System.getProperty("cold.sample") != null) {
            final Thread main = Thread.currentThread();
            sampler = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    StackTraceElement[] st = main.getStackTrace();
                    if (st.length > 0) {
                        total[0]++;
                        top.merge(st[0].getClassName() + "." + st[0].getMethodName(), 1, Integer::sum);
                        java.util.Set<String> seen = new java.util.HashSet<>();
                        for (StackTraceElement e : st) {
                            String k = e.getClassName() + "." + e.getMethodName();
                            if (e.getClassName().startsWith("com.nishiyu") && seen.add(k)) incl.merge(k, 1, Integer::sum);
                        }
                    }
                    try { Thread.sleep(1); } catch (InterruptedException e) { return; }
                }
            });
            sampler.setDaemon(true);
            sampler.start();
        }
        // -Dcold.prewarm=join: 温め（別スレッド）の完了を待ってから開く。=bg: 温めを始めてすぐ開く（並走）。計測値は open だけ
        String pw = System.getProperty("cold.prewarm");
        long pwStart = System.nanoTime();
        if (pw != null) {
            java.util.concurrent.CompletableFuture<Void> f = com.nishiyu.enderframelib.common.EnginePrewarm.start();
            if (pw.equals("join")) f.join();
            System.out.printf("PREWARM %s %.1f ms%n", pw, (System.nanoTime() - pwStart) / 1e6);
        }
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
        if (sampler != null) {
            sampler.interrupt();
            System.out.println("SAMPLES " + total[0]);
            incl.entrySet().stream().sorted((a, b2) -> b2.getValue() - a.getValue()).limit(Integer.getInteger("cold.n", 45))
                    .forEach(e -> System.out.println("INCL " + e.getValue() + " " + e.getKey()));
            top.entrySet().stream().sorted((a, b2) -> b2.getValue() - a.getValue()).limit(15)
                    .forEach(e -> System.out.println("TOP " + e.getValue() + " " + e.getKey()));
        }
        v.close();
    }
}
