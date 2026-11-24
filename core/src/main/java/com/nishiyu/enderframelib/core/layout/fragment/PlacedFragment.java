package com.nishiyu.enderframelib.core.layout.fragment;

/**
 * 包含ブロックが置いた流れの外の断片（{@code position: absolute / fixed}）。{@code x}, {@code y} は包含ブロックの断片の
 * border-box 基準の、この断片の border-box の左上。不変。
 */
public record PlacedFragment(LayoutFragment frag, float x, float y) { }
