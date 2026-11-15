package com.nishiyu.enderframelib.core.kernel.gpu;

import com.nishiyu.enderframelib.core.kernel.gpu.shader.ShaderSources;

/**
 * GPU シェーダーが行うアニメーション・スクロール評価の GLSL。本番はこのシェーダーだけが評価を行う。
 *
 * <p>シェーダーは {@code uFrameTimeMs} と {@link PropertyTrees} のレコードだけから現在の行列・不透明度を決める。
 * 同じ手順の CPU 参照実装は {@code src/test} 側にある（テストで GPU の結果と突き合わせる）。</p>
 */
public final class GpuAnimation {
    private GpuAnimation() {}

    /**
     * 同じ評価をするシェーダー断片（GLSL 1.50 / OpenGL 3.2 core で動く）。先頭に {@code #version 150} を付けて使う。
     * レコードはバッファテクスチャで読む: 浮動小数は {@code R32F}、整数は {@code R32I} の 2 つのビューを同じバッファに張る
     * （1.50 にはビット再解釈の関数が無いため）。添字は「レコード先頭 + バイトオフセット / 4」。
     * {@link PropertyTrees} のレコード配置と一致させること（テストが GPU の結果を Java 参照と突き合わせる）。
     */
    public static final String GLSL = ShaderSources.ANIMATION;
}
