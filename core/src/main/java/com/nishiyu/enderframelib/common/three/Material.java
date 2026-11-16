package com.nishiyu.enderframelib.common.three;

import com.nishiyu.enderframelib.core.scene3d.BlendMode3D;
import com.nishiyu.enderframelib.core.scene3d.MaterialDesc;
import com.nishiyu.enderframelib.core.scene3d.Scene3DWorld;
import java.util.HashMap;
import java.util.Map;

/**
 * PBR（金属度・粗さ）の材質。設定はメソッドチェーンで書け、描画済みのメッシュに対しても、変更が次のフレームから反映される。
 * 色は {@code 0xRRGGBB}（不透明度つきなら {@code 0xAARRGGBB}。上位 8 bit が 0 のときは不透明）。
 *
 * <pre>
 * Material gold = new Material().color(0xE8B04A).metalness(1).roughness(0.2);
 * gold.emissive(0xFFC060, 3);   // 発光（Bloom で光る）
 * </pre>
 */
public final class Material {
    private int color = 0xFFFFFFFF;
    private float metalness = 0f, roughness = 0.55f, emissiveStrength = 0f;
    private int emissive = 0;
    private boolean doubleSided, unlit;
    private final Map<Scene3DWorld, Integer> ids = new HashMap<>();

    public Material() {}

    public Material(int color) {
        color(color);
    }

    public Material color(int rgb) {
        this.color = (rgb >>> 24) == 0 ? 0xFF000000 | rgb : rgb;
        return changed();
    }

    public Material metalness(double v) {
        this.metalness = clamp01(v);
        return changed();
    }

    public Material roughness(double v) {
        this.roughness = clamp01(v);
        return changed();
    }

    /** 発光色と強さ。強さが 1 を超えると HDR になり、Bloom を有効にした世界でにじむ。 */
    public Material emissive(int rgb, double strength) {
        this.emissive = rgb & 0xFFFFFF;
        this.emissiveStrength = (float) Math.max(0, strength);
        return changed();
    }

    public Material doubleSided(boolean v) {
        this.doubleSided = v;
        return changed();
    }

    /** ライトを使わず色をそのまま出す。 */
    public Material unlit(boolean v) {
        this.unlit = v;
        return changed();
    }

    public int color() { return color; }
    public double metalness() { return metalness; }
    public double roughness() { return roughness; }
    public int emissive() { return emissive; }
    public double emissiveStrength() { return emissiveStrength; }

    private static float clamp01(double v) { return (float) Math.max(0, Math.min(1, v)); }

    private MaterialDesc desc(int id) {
        BlendMode3D mode = (color >>> 24) < 255 ? BlendMode3D.TRANSPARENT : BlendMode3D.OPAQUE;
        return new MaterialDesc(id, color, metalness, roughness, emissive, emissiveStrength, mode, doubleSided, unlit);
    }

    private Material changed() {
        ids.forEach((w, id) -> w.materials().replace(id, desc(id)));
        return this;
    }

    int idIn(Scene3DWorld world) {
        return ids.computeIfAbsent(world, w -> {
            int id = w.materials().register(color, metalness, roughness, emissive, emissiveStrength,
                    desc(0).blendMode(), doubleSided, unlit).materialId();
            return id;
        });
    }
}
