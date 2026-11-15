package com.nishiyu.enderframelib.core.scene3d;

/**
 * インスタンスバッチされた 1 ドローコール単位。
 */
public record DrawBatch3D(
        int meshId,
        BlendMode3D blendMode,
        int baseInstance,
        int instanceCount,
        boolean doubleSided) {
}
