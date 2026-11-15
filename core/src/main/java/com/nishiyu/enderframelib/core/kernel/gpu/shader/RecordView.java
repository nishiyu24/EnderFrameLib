package com.nishiyu.enderframelib.core.kernel.gpu.shader;

/**
 * 記録列の読み口。{@code sampler} は GLSL の変数名、{@code bufferIndex} は {@code GpuBindings#records} の何番目のバッファか、
 * {@code unit} はそのサンプラーのテクスチャユニット（GL）。
 */
public record RecordView(String sampler, int bufferIndex, ViewFormat format, int unit) {
}
