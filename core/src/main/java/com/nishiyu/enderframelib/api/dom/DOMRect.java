package com.nishiyu.enderframelib.api.dom;

import org.jetbrains.annotations.ApiStatus;

/**
 * 矩形（CSSOM View の DOMRectReadOnly）。座標は文書のビューポート左上を原点とする px。
 *
 * <p>幅・高さは負にならない（ブラウザの負の幅の正規化は行わない）。</p>
 */
@ApiStatus.AvailableSince("1.0")
public record DOMRect(float x, float y, float width, float height) {

    public static final DOMRect EMPTY = new DOMRect(0, 0, 0, 0);

    public float top() {
        return y;
    }

    public float left() {
        return x;
    }

    public float right() {
        return x + width;
    }

    public float bottom() {
        return y + height;
    }

    public boolean isEmpty() {
        return width <= 0 || height <= 0;
    }

    /** 2 つの矩形の共通部分。重ならなければ幅・高さ 0 の矩形。 */
    public DOMRect intersect(DOMRect other) {
        float l = Math.max(x, other.x);
        float t = Math.max(y, other.y);
        float r = Math.min(right(), other.right());
        float b = Math.min(bottom(), other.bottom());
        return r > l && b > t ? new DOMRect(l, t, r - l, b - t) : new DOMRect(l, t, 0, 0);
    }
}
