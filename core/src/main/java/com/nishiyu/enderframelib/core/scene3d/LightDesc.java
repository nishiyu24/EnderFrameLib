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
