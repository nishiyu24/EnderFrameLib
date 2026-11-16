package com.nishiyu.enderframelib.common.three;

/**
 * 形（{@link Geometry}）と材質（{@link Material}）を持つ物体。
 *
 * <pre>
 * Mesh ball = new Mesh(Geometry.sphere(0.4), new Material().color(0x4B8FE0).metalness(0.8).roughness(0.3));
 * ball.position.set(1, 0, 0);
 * scene.add(ball);
 * </pre>
 */
public final class Mesh extends Object3D {
    private final Geometry geometry;
    private Material material;

    public Mesh(Geometry geometry, Material material) {
        this.geometry = geometry;
        this.material = material;
    }

    public Geometry geometry() { return geometry; }
    public Material material() { return material; }

    /** 材質を差し替える（共有している他のメッシュには影響しない）。 */
    public Mesh material(Material m) {
        this.material = m;
        if (scene != null) bind(scene, node);
        return this;
    }

    @Override
    void bind(Scene3D s, int n) {
        s.world().arena().setMesh(n, geometry.meshIdIn(s.world()));
        s.world().arena().setMaterial(n, material.idIn(s.world()));
    }
}
