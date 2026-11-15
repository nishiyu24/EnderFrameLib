package com.nishiyu.enderframelib.core.kernel.gpu.rhi;

/**
 * デバイスの能力。描画側は「何ができるか」だけを見て経路を選び、API の名前は見ない。
 *
 * @param persistentMap   永続マップ（書き込み専用の GPU メモリへ直接書く）が使える
 * @param storageBuffer   storage buffer が使える（無ければレコード列は texel buffer で引く）
 * @param compute         計算シェーダーが使える
 * @param timestampQuery  GPU 側の時間計測が使える
 * @param maxTextureSize  テクスチャの一辺の最大
 * @param msaa            マルチサンプルの描画先が使える
 */
public record GpuCaps(boolean persistentMap, boolean storageBuffer, boolean compute, boolean timestampQuery,
                      int maxTextureSize, boolean msaa) {
}
