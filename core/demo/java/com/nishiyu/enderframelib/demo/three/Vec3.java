package com.nishiyu.enderframelib.demo.three;

/**
 * 3 成分のベクトル（位置・回転（ラジアンのオイラー角）・拡大率）。値を変えると、結び付いたノードへすぐ反映される
 * （Three.js の {@code mesh.position.set(...)} と同じ使い方）。
 */
public final class Vec3 {
    private float x, y, z;
    private final Runnable onChange;

    Vec3(float x, float y, float z, Runnable onChange) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.onChange = onChange;
    }

    public Vec3 set(double x, double y, double z) {
        this.x = (float) x;
        this.y = (float) y;
        this.z = (float) z;
        onChange.run();
        return this;
    }

    /** 3 成分とも同じ値にする（拡大率の一様な変更など）。 */
    public Vec3 set(double all) {
        return set(all, all, all);
    }

    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }

    public Vec3 x(double v) { return set(v, y, z); }
    public Vec3 y(double v) { return set(x, v, z); }
    public Vec3 z(double v) { return set(x, y, v); }

    /** 各成分へ足す。 */
    public Vec3 add(double dx, double dy, double dz) {
        return set(x + dx, y + dy, z + dz);
    }

    float fx() { return x; }
    float fy() { return y; }
    float fz() { return z; }
}
