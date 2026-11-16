package com.nishiyu.enderframelib.common.three;

import com.nishiyu.enderframelib.core.scene3d.GeometryPool;
import com.nishiyu.enderframelib.core.scene3d.Scene3DWorld;
import java.util.HashMap;
import java.util.Map;
import java.util.function.ToIntFunction;

/** 形。{@link Mesh} に渡して使う。同じ {@code Geometry} は複数のメッシュ・複数のシーンで共有できる（頂点は世界ごとに 1 度だけ作る）。 */
public final class Geometry {
    private final ToIntFunction<GeometryPool> factory;
    private final Map<Scene3DWorld, Integer> meshIds = new HashMap<>();

    private Geometry(ToIntFunction<GeometryPool> factory) {
        this.factory = factory;
    }

    public static Geometry box(double width, double height, double depth) {
        return new Geometry(p -> p.createBox((float) width, (float) height, (float) depth).meshId());
    }

    public static Geometry cube(double size) {
        return box(size, size, size);
    }

    public static Geometry sphere(double radius) {
        return sphere(radius, 16, 24);
    }

    /** {@code rings} は縦の分割、{@code segments} は横の分割。 */
    public static Geometry sphere(double radius, int rings, int segments) {
        return new Geometry(p -> p.createSphere((float) radius, rings, segments).meshId());
    }

    /** XZ 平面の板（上向き）。 */
    public static Geometry plane(double width, double depth) {
        return new Geometry(p -> p.createPlane((float) width, (float) depth).meshId());
    }

    int meshIdIn(Scene3DWorld world) {
        return meshIds.computeIfAbsent(world, w -> factory.applyAsInt(w.geometry()));
    }
}
