package com.nishiyu.enderframelib.core.layout.fragment;

/**
 * 包含ブロックが置いた流れの外の断片（{@code position: absolute / fixed}）。{@code x}, {@code y} は包含ブロックの断片の
 * border-box 基準の、この断片の border-box の左上。不変。
 *
 * <p>{@code margins} は {@code position-try} の候補が余白を入れ替えたときの使った余白 {上, 右, 下, 左}（{@code flip-block} など。G8）。
 * スタイルどおりなら null。</p>
 */
public record PlacedFragment(LayoutFragment frag, float x, float y, float[] margins) {
    public PlacedFragment(LayoutFragment frag, float x, float y) {
        this(frag, x, y, null);
    }
}
