package com.nishiyu.enderframelib.core.scene3d;

import com.nishiyu.enderframelib.core.kernel.gpu.PersistentVramBuffer;

/**
 * 1 つの {@code <scene-3d>} 要素に対応する GL 非依存 3D ワールド。
 */
public final class Scene3DWorld {
    private final long worldId;
    private final Scene3DArena arena = new Scene3DArena();
    private final GeometryPool geometry = new GeometryPool();
    private final MaterialPool materials = new MaterialPool();
    private final CameraSpec camera = new CameraSpec();
    private final LightDesc light = new LightDesc();
    private final Transform3DSystem transformSystem = new Transform3DSystem();
    private final InstancedBatcher batcher = new InstancedBatcher();
    private final Scene3DUploadPlan uploadPlan = new Scene3DUploadPlan();
    private int clearColorArgb = 0x00000000;
    private int msaaSamples = 0;
    private long settings;
    private boolean bloom;
    private float bloomThreshold = 1f, bloomIntensity = 0.8f, bloomSigma = 12f;

    public Scene3DWorld() {
        this(0L);
    }

    public Scene3DWorld(long worldId) {
        this.worldId = worldId;
    }

    public long worldId() { return worldId; }
    public Scene3DArena arena() { return arena; }
    public GeometryPool geometry() { return geometry; }
    public MaterialPool materials() { return materials; }
    public CameraSpec camera() { return camera; }
    public LightDesc light() { return light; }
    public Transform3DSystem transformSystem() { return transformSystem; }
    public InstancedBatcher batcher() { return batcher; }
    public Scene3DUploadPlan uploadPlan() { return uploadPlan; }

    public int clearColorArgb() { return clearColorArgb; }
    public Scene3DWorld clearColorArgb(int argb) { this.clearColorArgb = argb; settings++; return this; }

    /**
     * 見た目に効く変更の通し回数（ノード・カメラ・ライト・形・材質・背景色・MSAA の変更で増える。単調）。値が前回と同じなら、
     * 前回描いた絵から変わっていないので描き直さなくてよい（静止した 3D ビューポートの再描画を飛ばす）。
     */
    public long changeCount() {
        return arena.changeCount() + camera.changeCount() + light.changeCount() + geometry.version() + materials.count() + settings;
    }

    /**
     * HDR Bloom。有効にすると HDR（浮動小数点）で描き、{@code threshold}（輝度の最大成分）を超えた分を {@code sigmaPx}（画素）の
     * 広がりでぼかして {@code intensity} 倍で足し、ACES で 8 bit にする。無効（既定）ならこれまでどおり 8 bit で直接描く。
     * 発光（{@link MaterialDesc#emissiveStrength()}）や鏡面のハイライトが 1 を超えて光る。
     */
    public Scene3DWorld bloom(boolean enabled, float threshold, float intensity, float sigmaPx) {
        this.bloom = enabled;
        this.bloomThreshold = Math.max(0f, threshold);
        this.bloomIntensity = Math.max(0f, intensity);
        this.bloomSigma = Math.max(0.5f, sigmaPx);
        settings++;
        return this;
    }

    public Scene3DWorld bloom(boolean enabled) {
        return bloom(enabled, bloomThreshold, bloomIntensity, bloomSigma);
    }

    public boolean bloomEnabled() { return bloom; }
    public float bloomThreshold() { return bloomThreshold; }
    public float bloomIntensity() { return bloomIntensity; }
    public float bloomSigma() { return bloomSigma; }

    public int msaaSamples() { return msaaSamples; }
    public Scene3DWorld msaaSamples(int samples) {
        this.msaaSamples = Math.max(0, samples);
        settings++;
        return this;
    }

    public Scene3DUploadPlan updateAndPrepare(int viewportWidth, int viewportHeight) {
        if (viewportWidth > 0 && viewportHeight > 0) {
            camera.setAspect((float) viewportWidth / (float) viewportHeight);
        }
        transformSystem.update(arena, geometry);
        batcher.preparePlan(arena, geometry, materials, camera, uploadPlan);
        return uploadPlan;
    }

    public int packInstances(PersistentVramBuffer instanceBuffer) {
        return packInstances(instanceBuffer, 0);
    }

    /** {@code firstSlot} 番目のスロットから詰める（1 フレームに複数のワールドを描くときの、範囲の分割）。 */
    public int packInstances(PersistentVramBuffer instanceBuffer, int firstSlot) {
        return batcher.writeInstances(arena, materials, uploadPlan, instanceBuffer, firstSlot);
    }
}
