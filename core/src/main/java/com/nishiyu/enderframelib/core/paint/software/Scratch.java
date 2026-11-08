package com.nishiyu.enderframelib.core.paint.software;

/** 実行時の作業表（区間の表）。タイルを処理するスレッドごとに 1 つ。 */
final class Scratch {
    final int[] a = new int[Fast.MAX + Fast.MAX_ROUND], b = new int[Fast.MAX + Fast.MAX_ROUND], c = new int[Fast.MAX + Fast.MAX_ROUND];
    final double[] out = new double[2];
}
