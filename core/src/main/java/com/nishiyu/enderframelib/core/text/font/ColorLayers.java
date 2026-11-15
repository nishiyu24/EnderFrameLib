package com.nishiyu.enderframelib.core.text.font;

/**
 * カラー字形のレイヤー（COLR v0 / CPAL）。下のレイヤーから順に重ねる。{@code foreground[i]} が true のレイヤーは
 * パレットの色ではなく文字の色（{@code color}）で塗る（COLR の 0xFFFF）。
 */
public record ColorLayers(int[] glyphs, int[] argb, boolean[] foreground) {
    public int count() {
        return glyphs.length;
    }
}
