package com.nishiyu.enderframelib.core.scene3d;

/**
 * シーン内の主要ディレクショナルライトとアンビエントライトの定義。
 */
public final class LightDesc {
    private float dirX = -0.4f;
    private float dirY = -0.8f;
    private float dirZ = -0.45f;
    private float lightR = 1.0f;
    private float lightG = 0.98f;
    private float lightB = 0.95f;
    private float intensity = 1.0f;
    private float ambientR = 0.28f;
    private float ambientG = 0.30f;
    private float ambientB = 0.35f;

    /** 追加ライト（ポイント / スポット）の最大数。シェーダーの配列長（{@code uExtra} = 3 × この数の vec4）と一致させる。 */
    public static final int MAX_EXTRA = 8;
    /** 1 灯あたりの vec4 の数: (位置, 範囲) / (色×強さ, 内側 cos) / (向き, 外側 cos。ポイントは -2)。 */
    public static final int VEC4_PER_EXTRA = 3;
    /** ポイントライトを表す、外側 cos 欄の印（cos は -1 以上なのでスポットと混ざらない）。 */
    public static final float POINT_MARK = -2f;

    private final float[] extra = new float[MAX_EXTRA * VEC4_PER_EXTRA * 4];
    private int extraCount;

    private long changes;

    /** 変更の通し回数（単調に増える）。 */
    public long changeCount() { return changes; }

    public LightDesc() {
        normalizeDirection();
    }

    public void setDirection(float x, float y, float z) {
        changes++;
        this.dirX = x;
        this.dirY = y;
        this.dirZ = z;
        normalizeDirection();
    }

    private void normalizeDirection() {
        float len = (float) Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        if (len > 1e-6f) {
            dirX /= len;
            dirY /= len;
            dirZ /= len;
        } else {
            dirX = 0.0f;
            dirY = -1.0f;
            dirZ = 0.0f;
        }
    }

    public void setColor(float r, float g, float b, float intensity) {
        changes++;
        this.lightR = r;
        this.lightG = g;
        this.lightB = b;
        this.intensity = intensity;
    }

    public void setAmbient(float r, float g, float b) {
        changes++;
        this.ambientR = r;
        this.ambientG = g;
        this.ambientB = b;
    }

    /**
     * ポイントライトを足す。強さは {@code color × intensity}、{@code range} は光が 0 になる距離（逆二乗減衰に窓をかける）。
     * 満杯（{@link #MAX_EXTRA}）なら {@code false}。
     */
    public boolean addPoint(float x, float y, float z, float r, float g, float b, float intensity, float range) {
        return addExtra(x, y, z, r, g, b, intensity, range, 0f, 0f, 0f, 0f, 0f, true);
    }

    /**
     * スポットライトを足す。{@code (dx,dy,dz)} は光が進む向き、{@code innerDeg} 以内は全強度、{@code outerDeg} で 0 になる（半角・度）。
     */
    public boolean addSpot(float x, float y, float z, float dx, float dy, float dz, float r, float g, float b,
                           float intensity, float range, float innerDeg, float outerDeg) {
        float outer = (float) Math.cos(Math.toRadians(Math.min(Math.max(outerDeg, 0.1f), 89.9f)));
        float inner = (float) Math.cos(Math.toRadians(Math.min(Math.max(innerDeg, 0f), 89.9f)));
        inner = Math.max(inner, outer + 1e-3f); // 縁の補間が 0 割りにならない
        return addExtra(x, y, z, r, g, b, intensity, range, dx, dy, dz, inner, outer, false);
    }

    private boolean addExtra(float x, float y, float z, float r, float g, float b, float intensity, float range,
                             float dx, float dy, float dz, float cosInner, float cosOuter, boolean point) {
        if (extraCount >= MAX_EXTRA) return false;
        int o = extraCount * VEC4_PER_EXTRA * 4;
        extra[o] = x; extra[o + 1] = y; extra[o + 2] = z; extra[o + 3] = Math.max(range, 1e-3f);
        extra[o + 4] = r * intensity; extra[o + 5] = g * intensity; extra[o + 6] = b * intensity; extra[o + 7] = cosInner;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!point) {
            if (len > 1e-6f) { dx /= len; dy /= len; dz /= len; } else { dx = 0f; dy = -1f; dz = 0f; }
        }
        extra[o + 8] = dx; extra[o + 9] = dy; extra[o + 10] = dz; extra[o + 11] = point ? POINT_MARK : cosOuter;
        extraCount++;
        changes++;
        return true;
    }

    /** 追加ライトを全部外す。 */
    public void clearExtras() {
        if (extraCount == 0) return;
        extraCount = 0;
        changes++;
    }

    public int extraCount() { return extraCount; }

    /** 追加ライトの生データ（{@code extraCount() × VEC4_PER_EXTRA × 4} 個の float が有効。読み取り専用で使う）。 */
    public float[] extraData() { return extra; }

    public float dirX() { return dirX; }
    public float dirY() { return dirY; }
    public float dirZ() { return dirZ; }
    public float lightR() { return lightR; }
    public float lightG() { return lightG; }
    public float lightB() { return lightB; }
    public float intensity() { return intensity; }
    public float ambientR() { return ambientR; }
    public float ambientG() { return ambientG; }
    public float ambientB() { return ambientB; }
}
