package com.nishiyu.enderframelib.core.scene3d;

/**
 * 3D カメラ仕様とゼロアロケーション View/Projection/VP 行列およびフラスタム 6 平面計算。
 *
 * <p>列優先（column-major）16 float 配列を保持し、Gribb/Hartmann 法でフラスタム平面を抽出する。</p>
 */
public final class CameraSpec {
    private float eyeX = 0.0f, eyeY = 0.0f, eyeZ = 3.2f;
    private float targetX = 0.0f, targetY = 0.0f, targetZ = 0.0f;
    private float upX = 0.0f, upY = 1.0f, upZ = 0.0f;
    private float fovYRadians = (float) Math.toRadians(45.0);
    private float aspect = 1.0f;
    private float nearZ = 0.1f;
    private float farZ = 1000.0f;

    private final float[] view = new float[16];
    private final float[] proj = new float[16];
    private final float[] viewProj = new float[16];
    private final float[] frustumPlanes = new float[24];
    private boolean dirty = true;
    private long changes;

    /** 変更の通し回数（単調に増える）。 */
    public long changeCount() { return changes; }

    public void lookAt(float ex, float ey, float ez, float tx, float ty, float tz, float ux, float uy, float uz) {
        this.eyeX = ex; this.eyeY = ey; this.eyeZ = ez;
        this.targetX = tx; this.targetY = ty; this.targetZ = tz;
        this.upX = ux; this.upY = uy; this.upZ = uz;
        this.dirty = true;
        changes++;
    }

    public void setPerspective(float fovYRadians, float aspect, float nearZ, float farZ) {
        this.fovYRadians = Math.max(0.05f, Math.min((float) (Math.PI - 0.05), fovYRadians));
        this.aspect = Math.max(1e-4f, aspect);
        this.nearZ = Math.max(1e-4f, nearZ);
        this.farZ = Math.max(this.nearZ + 0.01f, farZ);
        this.dirty = true;
        changes++;
    }

    public void setAspect(float aspect) {
        float clamped = Math.max(1e-4f, aspect);
        if (Math.abs(clamped - this.aspect) > 1e-6f) {
            this.aspect = clamped;
            this.dirty = true;
            changes++;
        }
    }

    public float eyeX() { return eyeX; }
    public float eyeY() { return eyeY; }
    public float eyeZ() { return eyeZ; }
    public float targetX() { return targetX; }
    public float targetY() { return targetY; }
    public float targetZ() { return targetZ; }
    public float fovYRadians() { return fovYRadians; }
    public float aspect() { return aspect; }
    public float nearZ() { return nearZ; }
    public float farZ() { return farZ; }

    public void ensureUpdated() {
        if (!dirty) return;
        computeView();
        computePerspective();
        multiply4x4(proj, view, viewProj);
        extractFrustumPlanes();
        dirty = false;
    }

    public float[] viewMatrix() { ensureUpdated(); return view; }
    public float[] projMatrix() { ensureUpdated(); return proj; }
    public float[] viewProjMatrix() { ensureUpdated(); return viewProj; }
    public float[] frustumPlanes() { ensureUpdated(); return frustumPlanes; }

    /**
     * ワールド空間 AABB がフラスタムと交差するか（Gribb/Hartmann + p-vertex 判定）。
     */
    public boolean intersectsAabb(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        ensureUpdated();
        for (int p = 0; p < 6; p++) {
            int off = p * 4;
            float a = frustumPlanes[off];
            float b = frustumPlanes[off + 1];
            float c = frustumPlanes[off + 2];
            float d = frustumPlanes[off + 3];
            float px = a >= 0.0f ? maxX : minX;
            float py = b >= 0.0f ? maxY : minY;
            float pz = c >= 0.0f ? maxZ : minZ;
            if (a * px + b * py + c * pz + d < 0.0f) {
                return false;
            }
        }
        return true;
    }

    private void computeView() {
        float fx = targetX - eyeX;
        float fy = targetY - eyeY;
        float fz = targetZ - eyeZ;
        float fLen = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (fLen < 1e-6f) {
            fx = 0; fy = 0; fz = -1;
        } else {
            fx /= fLen; fy /= fLen; fz /= fLen;
        }

        // side = normalize(f x up)
        float sx = fy * upZ - fz * upY;
        float sy = fz * upX - fx * upZ;
        float sz = fx * upY - fy * upX;
        float sLen = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (sLen < 1e-6f) {
            // up が forward と平行な場合のフォールバック
            sx = 1; sy = 0; sz = 0;
        } else {
            sx /= sLen; sy /= sLen; sz /= sLen;
        }

        // u = side x f
        float ux = sy * fz - sz * fy;
        float uy = sz * fx - sx * fz;
        float uz = sx * fy - sy * fx;

        view[0] = sx;  view[4] = sy;  view[8] = sz;   view[12] = -(sx * eyeX + sy * eyeY + sz * eyeZ);
        view[1] = ux;  view[5] = uy;  view[9] = uz;   view[13] = -(ux * eyeX + uy * eyeY + uz * eyeZ);
        view[2] = -fx; view[6] = -fy; view[10] = -fz; view[14] = (fx * eyeX + fy * eyeY + fz * eyeZ);
        view[3] = 0f;  view[7] = 0f;  view[11] = 0f;  view[15] = 1.0f;
    }

    private void computePerspective() {
        float f = 1.0f / (float) Math.tan(fovYRadians * 0.5);
        float invNf = 1.0f / (nearZ - farZ);
        for (int i = 0; i < 16; i++) proj[i] = 0.0f;
        proj[0] = f / aspect;
        proj[5] = f;
        proj[10] = (farZ + nearZ) * invNf;
        proj[11] = -1.0f;
        proj[14] = (2.0f * farZ * nearZ) * invNf;
    }

    private static void multiply4x4(float[] a, float[] b, float[] out) {
        for (int col = 0; col < 4; col++) {
            int c4 = col * 4;
            float b0 = b[c4], b1 = b[c4 + 1], b2 = b[c4 + 2], b3 = b[c4 + 3];
            out[c4]     = a[0] * b0 + a[4] * b1 + a[8] * b2 + a[12] * b3;
            out[c4 + 1] = a[1] * b0 + a[5] * b1 + a[9] * b2 + a[13] * b3;
            out[c4 + 2] = a[2] * b0 + a[6] * b1 + a[10] * b2 + a[14] * b3;
            out[c4 + 3] = a[3] * b0 + a[7] * b1 + a[11] * b2 + a[15] * b3;
        }
    }

    private void extractFrustumPlanes() {
        // Left: r3 + r0
        setNormalizedPlane(0, viewProj[3] + viewProj[0], viewProj[7] + viewProj[4], viewProj[11] + viewProj[8], viewProj[15] + viewProj[12]);
        // Right: r3 - r0
        setNormalizedPlane(1, viewProj[3] - viewProj[0], viewProj[7] - viewProj[4], viewProj[11] - viewProj[8], viewProj[15] - viewProj[12]);
        // Bottom: r3 + r1
        setNormalizedPlane(2, viewProj[3] + viewProj[1], viewProj[7] + viewProj[5], viewProj[11] + viewProj[9], viewProj[15] + viewProj[13]);
        // Top: r3 - r1
        setNormalizedPlane(3, viewProj[3] - viewProj[1], viewProj[7] - viewProj[5], viewProj[11] - viewProj[9], viewProj[15] - viewProj[13]);
        // Near: r3 + r2
        setNormalizedPlane(4, viewProj[3] + viewProj[2], viewProj[7] + viewProj[6], viewProj[11] + viewProj[10], viewProj[15] + viewProj[14]);
        // Far: r3 - r2
        setNormalizedPlane(5, viewProj[3] - viewProj[2], viewProj[7] - viewProj[6], viewProj[11] - viewProj[10], viewProj[15] - viewProj[14]);
    }

    private void setNormalizedPlane(int planeIdx, float a, float b, float c, float d) {
        float len = (float) Math.sqrt(a * a + b * b + c * c);
        int off = planeIdx * 4;
        if (len > 1e-8f) {
            float inv = 1.0f / len;
            frustumPlanes[off] = a * inv;
            frustumPlanes[off + 1] = b * inv;
            frustumPlanes[off + 2] = c * inv;
            frustumPlanes[off + 3] = d * inv;
        } else {
            frustumPlanes[off] = 0f;
            frustumPlanes[off + 1] = 0f;
            frustumPlanes[off + 2] = 0f;
            frustumPlanes[off + 3] = 1f;
        }
    }
}
