package com.nishiyu.enderframelib.core.kernel.gpu.rhi;

/**
 * デバイスの能力。描画側は「何ができるか」だけを見て経路を選び、API の名前は見ない。
 *
 * @param persistentMap       永続マップ（書き込み専用の GPU メモリへ直接書く）が使える
 * @param storageBuffer       storage buffer が使える（無ければレコード列は texel buffer で引く）
 * @param compute             計算シェーダーが使える
 * @param timestampQuery      GPU 側の時間計測が使える
 * @param maxTextureSize      テクスチャの一辺の最大
 * @param msaa                マルチサンプルの描画先が使える
 * @param maxSamples          対応する最大 MSAA サンプル数
 * @param bottomUpFramebuffer 描画先とテクスチャの v=0 が下端原点か（GL は true、Vulkan 等の左上原点は false）
 * @param zeroToOneDepth      クリップ空間の深度が [0, 1] か（GL は false [-1, 1]、Vulkan 等は true）
 */
public record GpuCaps(boolean persistentMap, boolean storageBuffer, boolean compute, boolean timestampQuery,
                      int maxTextureSize, boolean msaa, int maxSamples,
                      boolean bottomUpFramebuffer, boolean zeroToOneDepth) {
    public GpuCaps(boolean persistentMap, boolean storageBuffer, boolean compute, boolean timestampQuery,
                   int maxTextureSize, boolean msaa) {
        this(persistentMap, storageBuffer, compute, timestampQuery, maxTextureSize, msaa, msaa ? 4 : 1, true, false);
    }

    public GpuCaps(boolean persistentMap, boolean storageBuffer, boolean compute, boolean timestampQuery,
                   int maxTextureSize, boolean msaa, int maxSamples) {
        this(persistentMap, storageBuffer, compute, timestampQuery, maxTextureSize, msaa, maxSamples, true, false);
    }
}
