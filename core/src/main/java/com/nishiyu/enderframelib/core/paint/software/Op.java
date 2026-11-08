package com.nishiyu.enderframelib.core.paint.software;

/**
 * 画素を書く命令。記録（姿勢・クリップ・範囲の決定。描画先の範囲の更新もここ）は呼び出しスレッドで順に行い、画素への書き込みは
 * {@link TileQueue} が 64×64 のタイルごとに（並列に）、命令の順で実行する。命令同士は同じ画素を触るときだけ順序が効く。
 * 命令は不変の値と純粋な関数だけを持つ（記録後に書き換わる状態を読まない）。
 */
abstract class Op {
    int[] px;
    int w;
    /** 書く範囲（描画先の画素。x1・y1 は含まない）。 */
    int x0, y0, x1, y1;

    /** 範囲 {@code [ax0,ax1) × [ay0,ay1)}（この命令の範囲に収まる）の画素を書く。 */
    abstract void run(int ax0, int ay0, int ax1, int ay1, Scratch s);
}
