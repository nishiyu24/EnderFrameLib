package com.nishiyu.enderframelib.core.scene3d;

/**
 * 3D マテリアル記述子。{@code baseColorMap} があるときは、基本色（{@code baseColorArgb}）に画像の色を掛ける（glTF の baseColorTexture と同じ）。
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
        boolean unlit,
        Texture3D baseColorMap,
        boolean mapRepeat) {

    /** 画像なし。 */
    public MaterialDesc(int materialId, int baseColorArgb, float metallic, float roughness, int emissiveRgb,
                        float emissiveStrength, BlendMode3D blendMode, boolean doubleSided, boolean unlit) {
        this(materialId, baseColorArgb, metallic, roughness, emissiveRgb, emissiveStrength, blendMode, doubleSided, unlit, null, false);
    }

    public static MaterialDesc ofColor(int materialId, int baseColorArgb) {
        int alpha = (baseColorArgb >>> 24) & 0xFF;
        BlendMode3D mode = alpha < 255 ? BlendMode3D.TRANSPARENT : BlendMode3D.OPAQUE;
        return new MaterialDesc(materialId, baseColorArgb, 0.0f, 0.55f, 0, 0.0f, mode, false, false);
    }
}
