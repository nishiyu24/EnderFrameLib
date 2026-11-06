package com.nishiyu.enderframelib.core.paint.software;

import java.util.stream.IntStream;

/**
 * 行の範囲を、互いに独立な画素の塗りのために並列で処理する小さな道具。
 * 小さな面は並列にしても得にならないので、その場で順に処理する。
 */
final class Rows {
    private Rows() {}

    /** 画素数がこれ未満の塗りは呼び出しスレッドだけで行う（並列化の手間のほうが大きい）。 */
    static final long PARALLEL_MIN_PIXELS = 60_000;
    /** 1 つのまとまりの行数の下限。 */
    private static final int MIN_ROWS = 8;

    @FunctionalInterface
    interface Body {
        void run(int yFrom, int yTo);
    }

    /** {@code [y0, y1)} を行のまとまりに割り、{@code body} を呼ぶ。まとまり同士は互いに触れない前提。 */
    static void forEach(int y0, int y1, long pixels, Body body) {
        int rows = y1 - y0;
        int cpus = Runtime.getRuntime().availableProcessors();
        if (rows < 2 * MIN_ROWS || cpus < 2 || pixels < PARALLEL_MIN_PIXELS || !PARALLEL) {
            body.run(y0, y1);
            return;
        }
        int chunks = Math.min(cpus * 2, rows / MIN_ROWS);
        IntStream.range(0, chunks).parallel().forEach(k -> body.run(y0 + (int) ((long) rows * k / chunks), y0 + (int) ((long) rows * (k + 1) / chunks)));
    }

    /** 検証用に止められる（{@code -Denderframe.raster.serial}）。 */
    private static final boolean PARALLEL = System.getProperty("enderframe.raster.serial") == null;
}
