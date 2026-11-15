package com.nishiyu.enderframelib.core.scene3d;

/**
 * 3D マテリアル記述子。
 */
public record MaterialDesc(
        int materialId,
        int baseColorArgb,
        float metallic,
        float roughness,
        int emissiveRgb,
        float emissiveStrength,
        BlendMode3D blendMode,
        boolean doubleSided,
        boolean unlit) {

    public static MaterialDesc ofColor(int materialId, int baseColorArgb) {
        int alpha = (baseColorArgb >>> 24) & 0xFF;
        BlendMode3D mode = alpha < 255 ? BlendMode3D.TRANSPARENT : BlendMode3D.OPAQUE;
        return new MaterialDesc(materialId, baseColorArgb, 0.0f, 0.55f, 0, 0.0f, mode, false, false);
    }
}
