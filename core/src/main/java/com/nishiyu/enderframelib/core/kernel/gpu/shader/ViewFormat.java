package com.nishiyu.enderframelib.core.kernel.gpu.shader;

/** レコード列のバッファを、シェーダーの{@code samplerBuffer} が引く形（同じバッファの別の読み方を別のサンプラーに）。 */
public enum ViewFormat { FLOAT32, INT32, HALF16, RGBA_FLOAT32, RGBA_INT32 }
