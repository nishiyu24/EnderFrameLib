package com.nishiyu.enderframelib.core.paint.model;


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

    /** {@link #rect} の写しを作らない版。読むだけの呼び出し側（毎フレームのクリップの組み立て）用で、書き換えてはならない。 */
    public float[] rectView() {
        return rect;
    }

    /** 横・縦の角の半径（{@link #rectView} の 4〜11 番。縦が無ければ横と同じ）。読むだけ。最初に問われたときに作って覚える（B12）。 */
    private float[] radiiX, radiiY;

    /** 横の角の半径 4 つ（左上・右上・右下・左下）。半径の無い矩形なら null。書き換えてはならない。 */
    public float[] radiiXView() {
        if (rect.length <= 4) return null;
        float[] rx = radiiX;
        if (rx == null) fillRadii();
        return radiiX;
    }

    /** 縦の角の半径 4 つ。半径の無い矩形なら null。書き換えてはならない。 */
    public float[] radiiYView() {
        if (rect.length <= 4) return null;
        float[] ry = radiiY;
        if (ry == null) fillRadii();
        return radiiY;
    }

    private void fillRadii() {
        float[] rx = new float[4], ry = new float[4];
        for (int i = 0; i < 4; i++) {
            rx[i] = rect.length > 4 + i ? rect[4 + i] : 0f;
            ry[i] = rect.length >= 12 ? rect[8 + i] : rx[i];
        }
        radiiY = ry; // 縦を先に置く（別のスレッドが横だけ見て縦を読んでも、作り直すだけ）
        radiiX = rx;
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
