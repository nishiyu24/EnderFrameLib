package com.nishiyu.enderframelib.core.paint.model;

import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;

/**
 * 連鎖の 1 つ。{@code rect} は矩形 + 横の半径 4 つ（楕円の角があれば縦の半径 4 つが続く 12 値。{@link #toArray} と同じ並び）で、
 * {@code ownerId} の要素の空間（レイアウトの座標）で持つ。持ち主の変形（移動・回転・拡縮）に付いていく。
 * {@code ownerId} が null なら動かない（フレームの基準の空間）。
 */
public final class ClipChainPart {
    private final float[] rect;
    private final String ownerId;
    private final int ownerOriginX;
    private final int ownerOriginY;

    public ClipChainPart(float[] rect, String ownerId) {
        this(rect, ownerId, 0, 0);
    }

    public ClipChainPart(float[] rect, String ownerId, int ownerOriginX, int ownerOriginY) {
        this.rect = rect.clone();
        this.ownerId = ownerId;
        this.ownerOriginX = ownerOriginX;
        this.ownerOriginY = ownerOriginY;
    }

    public int ownerOriginX() {
        return ownerOriginX;
    }

    public int ownerOriginY() {
        return ownerOriginY;
    }

    public float[] rect() {
        return rect.clone();
    }

    public int rectLength() {
        return rect.length;
    }

    public float rectAt(int index) {
        return rect[index];
    }

    public String ownerId() {
        return ownerId;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ClipChainPart p && java.util.Arrays.equals(rect, p.rect) && java.util.Objects.equals(ownerId, p.ownerId);
    }

    @Override
    public int hashCode() {
        return 31 * java.util.Arrays.hashCode(rect) + java.util.Objects.hashCode(ownerId);
    }
}
