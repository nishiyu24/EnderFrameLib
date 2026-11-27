package com.nishiyu.enderframelib.core.paint.scene;

/**
 * {@link SceneLayer} 自身の描画命令の型付き表現（M3b-4 でバックエンドが直接消費する命令列の枠。M3b-2 では {@link #EMPTY} を置く）。
 */
public sealed interface DrawOp permits DrawOp.None {
    DrawOp[] EMPTY = new DrawOp[0];

    record None() implements DrawOp { }
}
