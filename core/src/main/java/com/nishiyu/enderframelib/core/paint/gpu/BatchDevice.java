package com.nishiyu.enderframelib.core.paint.gpu;

/**
 * 描画 API ごとの実装（L3）が満たす、最小の受け口（L2 / RHI）。{@link GpuBatchPacker} が詰めたインスタンスデータを
 * 「転送フェーズ」と「描画フェーズ」に分けて受け取る。コマンドバッファ型の API（Vulkan / DX12 / Metal / WebGPU）でも、
 * 転送はステージングバッファへの書き込み、描画は {@code vkCmdDraw} のような記録に素直に対応する。
 *
 * <ul>
 *   <li>OpenGL: 転送 = データテクスチャへの {@code glTexSubImage2D}、描画 = {@code glDrawArraysInstanced}</li>
 *   <li>Vulkan（想定）: 転送 = host-visible バッファ（リング + フェンス）への書き込み、描画 = {@code vkCmdDraw(4, n, 0, 0)}</li>
 *   <li>頂点バッチ系エンジン（想定）: 描画でインスタンスを四角形のメッシュへ展開（{@code MeshTessellator}）</li>
 * </ul>
 *
 * <p>1 インスタンス = {@link GpuBatchPacker#STRIDE} テクセル × 4 float。{@code data} は呼び出しの間だけ読めばよい
 * （保持してはならない）。呼び出しは必ず {@link #uploadInstances} → {@link #drawInstances} の順。
 */
public interface BatchDevice {

    /** 転送フェーズ: 先頭 {@code instanceCount} 個分（{@code instanceCount * STRIDE * 4} float）を GPU 側のインスタンスデータへ書く。 */
    void uploadInstances(float[] data, int instanceCount);

    /** 描画フェーズ: 直前に転送したインスタンスを、1 回のインスタンス描画で描く。 */
    void drawInstances(int instanceCount);
}
