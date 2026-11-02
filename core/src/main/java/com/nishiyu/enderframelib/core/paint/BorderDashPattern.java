package com.nishiyu.enderframelib.core.paint;



/** 破線 1 周分の割り付け。{@code count} 個の周期に分け、周期ごとに長さ {@code dash} の線（または直径 {@code dash} の点）を中央に置く。 */
public record BorderDashPattern(int count, float period, float dash, boolean round) {}
