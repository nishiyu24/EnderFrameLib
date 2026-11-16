package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.api.scene3d.Hit3DResult;
import com.nishiyu.enderframelib.api.scene3d.Scene3DController;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.scene3d.CameraOrbit;
import com.nishiyu.enderframelib.core.scene3d.GltfBinaryLoader;
import com.nishiyu.enderframelib.core.scene3d.MeshHandle;
import com.nishiyu.enderframelib.core.scene3d.Raycaster3D;
import com.nishiyu.enderframelib.core.scene3d.Scene3DArena;
import com.nishiyu.enderframelib.core.scene3d.Scene3DTags;
import com.nishiyu.enderframelib.core.scene3d.Scene3DWorld;
import java.util.Optional;

/** {@link Scene3DController} の実装: {@code <scene-3d>} 1 つの {@link Scene3DWorld} を、公開 API の型だけで操作させる。 */
final class Scene3DControllerImpl implements Scene3DController {
    private final UiView ui;
    private final String elementId;
    private final long worldId;
    private final Scene3DWorld world;
    private final Raycaster3D caster = new Raycaster3D();

    Scene3DControllerImpl(UiView ui, String elementId, long worldId, Scene3DWorld world) {
        this.ui = ui;
        this.elementId = elementId;
        this.worldId = worldId;
        this.world = world;
    }

    @Override public String elementId() { return elementId; }

    // ---------------------------------------------------------------- 入力

    @Override
    public Optional<Hit3DResult> raycast(double localX, double localY) {
        DisplayItem item = sceneItem();
        if (item == null || item.width() <= 0 || item.height() <= 0) return Optional.empty();
        Raycaster3D.Hit hit = new Raycaster3D.Hit();
        if (!caster.pickScreen(world, (float) localX, (float) localY, item.width(), item.height(), hit)) return Optional.empty();
        return Optional.of(result(hit));
    }

    @Override
    public Optional<Hit3DResult> pick(double x, double y) {
        UiView.Scene3DPick p = ui.pickScene3d(x, y);
        if (p == null || p.worldId() != worldId) return Optional.empty();
        return Optional.of(result(p.hit()));
    }

    private static Hit3DResult result(Raycaster3D.Hit h) {
        return new Hit3DResult(h.node, h.entityTag, h.t, h.x, h.y, h.z, h.u, h.v);
    }

    /** 表示リストの中の、この世界の {@code SCENE} 項目（大きさの出どころ）。 */
    private DisplayItem sceneItem() {
        String key = Long.toString(worldId);
        for (DisplayItem it : ui.renderCache()) {
            if (it.type() != ElementType.SCENE || it.events() == null) continue;
            if (key.equals(it.events().get(Scene3DTags.WORLD_ATTR))) return it;
        }
        return null;
    }

    // ---------------------------------------------------------------- 組み立て

    @Override
    public int addBox(double width, double height, double depth, int colorArgb) {
        MeshHandle mesh = world.geometry().createBox((float) width, (float) height, (float) depth);
        return addMesh(mesh, colorArgb);
    }

    @Override
    public int addSphere(double radius, int colorArgb) {
        return addMesh(world.geometry().createSphere((float) radius, 16, 24), colorArgb);
    }

    private int addMesh(MeshHandle mesh, int colorArgb) {
        int node = world.arena().allocateNode();
        world.arena().setMesh(node, mesh.meshId());
        world.arena().setMaterial(node, world.materials().registerColor(colorArgb).materialId());
        return node;
    }

    @Override
    public int[] loadGltf(byte[] data) {
        try {
            return GltfBinaryLoader.load(data, world).rootNodes();
        } catch (GltfBinaryLoader.GltfException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private int alive(int nodeId) {
        if (!world.arena().isAlive(nodeId)) throw new IllegalArgumentException("no such node: " + nodeId);
        return nodeId;
    }

    @Override public void setPosition(int nodeId, double x, double y, double z) {
        world.arena().setPosition(alive(nodeId), (float) x, (float) y, (float) z);
    }

    @Override public void setRotationDegrees(int nodeId, double rx, double ry, double rz) {
        world.arena().setRotationEulerRadians(alive(nodeId), (float) Math.toRadians(rx), (float) Math.toRadians(ry), (float) Math.toRadians(rz));
    }

    @Override public void setScale(int nodeId, double sx, double sy, double sz) {
        world.arena().setScale(alive(nodeId), (float) sx, (float) sy, (float) sz);
    }

    @Override public void setVisible(int nodeId, boolean visible) {
        world.arena().setVisible(alive(nodeId), visible);
    }

    @Override public void removeNode(int nodeId) {
        world.arena().freeNode(alive(nodeId));
    }

    // ---------------------------------------------------------------- カメラ・ライト・見た目

    @Override
    public void setCamera(double ex, double ey, double ez, double tx, double ty, double tz, double fovDegrees) {
        world.camera().lookAt((float) ex, (float) ey, (float) ez, (float) tx, (float) ty, (float) tz, 0f, 1f, 0f);
        world.camera().setPerspective((float) Math.toRadians(fovDegrees), world.camera().aspect(),
                world.camera().nearZ(), world.camera().farZ());
    }

    @Override
    public void orbit(double yawDegrees, double pitchDegrees) {
        CameraOrbit.orbit(world.camera(), (float) Math.toRadians(yawDegrees), (float) Math.toRadians(pitchDegrees));
    }

    @Override
    public void zoom(double factor) {
        CameraOrbit.zoom(world.camera(), (float) factor);
    }

    private static float c(int argb, int shift) { return ((argb >> shift) & 0xFF) / 255f; }

    @Override
    public void setDirectionalLight(double dx, double dy, double dz, int colorArgb, double intensity) {
        world.light().setDirection((float) dx, (float) dy, (float) dz);
        world.light().setColor(c(colorArgb, 16), c(colorArgb, 8), c(colorArgb, 0), (float) intensity);
    }

    @Override
    public void setAmbientLight(int colorArgb, double intensity) {
        world.light().setAmbient(c(colorArgb, 16) * (float) intensity, c(colorArgb, 8) * (float) intensity, c(colorArgb, 0) * (float) intensity);
    }

    @Override
    public boolean addPointLight(double x, double y, double z, int colorArgb, double intensity, double range) {
        return world.light().addPoint((float) x, (float) y, (float) z, c(colorArgb, 16), c(colorArgb, 8), c(colorArgb, 0),
                (float) intensity, (float) range);
    }

    @Override
    public boolean addSpotLight(double x, double y, double z, double dx, double dy, double dz, int colorArgb, double intensity,
                                double range, double innerDegrees, double outerDegrees) {
        return world.light().addSpot((float) x, (float) y, (float) z, (float) dx, (float) dy, (float) dz,
                c(colorArgb, 16), c(colorArgb, 8), c(colorArgb, 0), (float) intensity, (float) range,
                (float) innerDegrees, (float) outerDegrees);
    }

    @Override public void clearExtraLights() { world.light().clearExtras(); }

    @Override public void setBackground(int colorArgb) { world.clearColorArgb(colorArgb); }

    @Override
    public void setBloom(boolean enabled, double threshold, double intensity, double sigmaPixels) {
        world.bloom(enabled, (float) threshold, (float) intensity, (float) sigmaPixels);
    }
}
