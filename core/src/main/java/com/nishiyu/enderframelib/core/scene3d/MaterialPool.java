package com.nishiyu.enderframelib.core.scene3d;

import java.util.ArrayList;
import java.util.List;

/**
 * 3D マテリアルプール。ID 0 にはデフォルト白色マテリアルを事前登録する。
 */
public final class MaterialPool {
    private final List<MaterialDesc> materials = new ArrayList<>();
    private long edits;

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

    /**
     * 登録済みの材質を同じ番号のまま差し替える（その番号を使う全ノードの見た目が変わる）。{@code desc.materialId()} は
     * {@code materialId} と同じでなければならない。
     */
    public synchronized void replace(int materialId, MaterialDesc desc) {
        if (materialId < 0 || materialId >= materials.size()) throw new IllegalArgumentException("no such material: " + materialId);
        if (desc.materialId() != materialId) throw new IllegalArgumentException("materialId mismatch");
        materials.set(materialId, desc);
        edits++;
    }

    /** {@link #replace} の回数（見た目の変更検出用。登録の増加は {@link #count()} が表す）。 */
    public long edits() { return edits; }

    public int count() {
        return materials.size();
    }
}
