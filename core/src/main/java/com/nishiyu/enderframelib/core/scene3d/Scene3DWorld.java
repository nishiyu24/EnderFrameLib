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
        return batcher.writeInstances(arena, materials, uploadPlan, instanceBuffer);
    }
}
