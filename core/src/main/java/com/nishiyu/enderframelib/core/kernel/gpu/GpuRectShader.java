package com.nishiyu.enderframelib.core.kernel.gpu;

import com.nishiyu.enderframelib.core.kernel.gpu.shader.Glsl;
import com.nishiyu.enderframelib.core.kernel.gpu.shader.ShaderSources;

/**
 * プリミティブ（角丸矩形の塗りと角丸の枠線）を {@link PropertyTrees} から直接描くシェーダー（GLSL 1.50）。GL を知らない文字列だけ。
 *
 * <p>頂点シェーダーは {@code gl_InstanceID} → 描画順の表 → プリミティブのスロットを引き、矩形と変換・効果だけを読んで四角形を置く。
 * 変換は {@link GpuAnimation#GLSL} の {@code efWorld}（アニメ・スクロール込み）、不透明度は {@code efOpacity}。
 * フラグメントシェーダーが、従来の {@code LwjglGlRenderBackend} のシェーダーと同じ式（{@code sdRR}・{@code cover}・
 * 枠線の辺の判定）で被覆率を出し、スロットのレコードから半径・内側の矩形・幅・色を読む。
 * 出力はプリマルチプライドで、合成は {@code glBlendFunc(ONE, ONE_MINUS_SRC_ALPHA)}。</p>
 *
 * <p>レコードの配置は {@link PropertyTrees}（128 B）。float 添字 = バイト / 4、半精度添字 = バイト / 2。クリップ・破線・
 * 傾きのある姿勢は未対応。クリップは最大 4 個（従来経路と同じ上限）。</p>
 */
public final class GpuRectShader {
    private GpuRectShader() {}

    public static final String VERTEX = Glsl.gl150(ShaderSources.ANIMATION + ShaderSources.RECT_VERTEX);

    public static final String FRAGMENT = Glsl.gl150(ShaderSources.RECT_FRAGMENT);
}
