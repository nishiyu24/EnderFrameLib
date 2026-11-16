package com.nishiyu.enderframelib.core.scene3d;

import java.util.ArrayList;
import java.util.List;

/**
 * 3D マテリアルプール。ID 0 にはデフォルト白色マテリアルを事前登録する。
 */
public final class MaterialPool {
    private final List<MaterialDesc> materials = new ArrayList<>();

    public MaterialPool() {
        materials.add(MaterialDesc.ofColor(0, 0xFFFFFFFF));
    }

    public synchronized MaterialDesc register(int baseColorArgb, float metallic, float roughness,
                                              int emissiveRgb, float emissiveStrength,
                                              BlendMode3D blendMode, boolean doubleSided, boolean unlit) {
        int id = materials.size();
        MaterialDesc desc = new MaterialDesc(
                id, baseColorArgb, metallic, roughness,
                emissiveRgb, emissiveStrength,
                blendMode == null ? BlendMode3D.OPAQUE : blendMode,
                doubleSided, unlit);
        materials.add(desc);
        return desc;
    }

    /** ベースカラー画像つき。{@code repeat} は UV が 0〜1 の外でタイル状に繰り返すか（false は端の色が伸びる）。 */
    public synchronized MaterialDesc register(int baseColorArgb, float metallic, float roughness,
                                              int emissiveRgb, float emissiveStrength,
                                              BlendMode3D blendMode, boolean doubleSided, boolean unlit,
                                              Texture3D baseColorMap, boolean repeat) {
        int id = materials.size();
        MaterialDesc desc = new MaterialDesc(
                id, baseColorArgb, metallic, roughness,
                emissiveRgb, emissiveStrength,
                blendMode == null ? BlendMode3D.OPAQUE : blendMode,
                doubleSided, unlit, baseColorMap, repeat);
        materials.add(desc);
        return desc;
    }

    public synchronized MaterialDesc registerColor(int baseColorArgb) {
        int id = materials.size();
        MaterialDesc desc = MaterialDesc.ofColor(id, baseColorArgb);
        materials.add(desc);
        return desc;
    }

    public MaterialDesc get(int materialId) {
        if (materialId < 0 || materialId >= materials.size()) {
            return materials.get(0);
        }
        return materials.get(materialId);
    }

    public int count() {
        return materials.size();
    }
}
