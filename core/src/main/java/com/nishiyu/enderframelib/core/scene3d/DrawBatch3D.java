package com.nishiyu.enderframelib.core.scene3d;

/**
 * インスタンスバッチされた 1 ドローコール単位。ベースカラー画像が違う材質は同じバッチに入れない（1 ドローで 1 枚の画像を読む）。
 */
public record DrawBatch3D(
        int meshId,
        BlendMode3D blendMode,
        int baseInstance,
        int instanceCount,
        boolean doubleSided,
        Texture3D texture) {

    public DrawBatch3D(int meshId, BlendMode3D blendMode, int baseInstance, int instanceCount, boolean doubleSided) {
        this(meshId, blendMode, baseInstance, instanceCount, doubleSided, null);
    }
}
