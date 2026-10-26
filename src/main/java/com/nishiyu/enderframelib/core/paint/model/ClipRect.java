package com.nishiyu.enderframelib.core.paint.model;

import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;

/**
 * クリップ矩形と角丸情報（角ごとの横の半径 4 値 + 縦の半径 4 値）。
 * <p>
 * {@code ownerId} は、この矩形を自分の箱で狭めた要素の id（背景レイヤーなど、要素自身の箱で切るとき）。
 * その要素が動く（transform / animation）と、矩形も一緒に動かなければならない。{@code null} は
 * 動かない祖先（スクロール領域など）のクリップで、描画側は要素の変形の分だけ矩形を逆にずらして画面に留める。
 * <p>
 * 縦の半径（{@code *V}）が全部 0 なら円の角で、縦は横と同じ。楕円の角が 1 つでもあれば、縦も全部の角に入る。
 * <p>
 * 上の矩形は祖先のクリップを 1 つに合成したもの（ヒットテストと、変形の無い描画が使う）。{@link #chain} は合成する前の
 * 1 つ 1 つ（外側から内側へ）で、それぞれ持ち主の要素と、その要素の空間での矩形を持つ。合成した矩形は、祖先どうしの
 * 変形が違う（動く祖先の中の overflow・回る要素と角丸の祖先）と、どの空間にも属さなくなって正しく当てられない。
 * 描画側は連鎖を、1 つずつ要素の空間へ直してから当てる（{@code ClipResolver}）。空なら合成した矩形だけを使う。
 */
public record ClipRect(
        float minX, float minY, float maxX, float maxY,
        float radiusTopLeft, float radiusTopRight, float radiusBottomRight, float radiusBottomLeft,
        String ownerId,
        float radiusTopLeftV, float radiusTopRightV, float radiusBottomRightV, float radiusBottomLeftV,
        java.util.List<Part> chain
) {
    public static final ClipRect EMPTY = null;

    /**
     * 連鎖の 1 つ。{@code rect} は矩形 + 横の半径 4 つ（楕円の角があれば縦の半径 4 つが続く 12 値。{@link #toArray} と同じ並び）で、
     * {@code ownerId} の要素の空間（レイアウトの座標）で持つ。持ち主の変形（移動・回転・拡縮）に付いていく。
     * {@code ownerId} が null なら動かない（フレームの基準の空間）。
     */
    public static final class Part {
        private final float[] rect;
        private final String ownerId;

        public Part(float[] rect, String ownerId) {
            this.rect = rect.clone();
            this.ownerId = ownerId;
        }

        public float[] rect() {
            return rect.clone();
        }

        public String ownerId() {
            return ownerId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Part p && java.util.Arrays.equals(rect, p.rect) && java.util.Objects.equals(ownerId, p.ownerId);
        }

        @Override
        public int hashCode() {
            return 31 * java.util.Arrays.hashCode(rect) + java.util.Objects.hashCode(ownerId);
        }
    }

    public ClipRect {
        chain = chain == null || chain.isEmpty() ? java.util.List.of() : java.util.List.copyOf(chain);
    }

    public ClipRect(float minX, float minY, float maxX, float maxY,
                    float radiusTopLeft, float radiusTopRight, float radiusBottomRight, float radiusBottomLeft,
                    String ownerId,
                    float radiusTopLeftV, float radiusTopRightV, float radiusBottomRightV, float radiusBottomLeftV) {
        this(minX, minY, maxX, maxY, radiusTopLeft, radiusTopRight, radiusBottomRight, radiusBottomLeft, ownerId,
                radiusTopLeftV, radiusTopRightV, radiusBottomRightV, radiusBottomLeftV, java.util.List.of());
    }

    public ClipRect(float minX, float minY, float maxX, float maxY,
                    float radiusTopLeft, float radiusTopRight, float radiusBottomRight, float radiusBottomLeft,
                    String ownerId) {
        this(minX, minY, maxX, maxY, radiusTopLeft, radiusTopRight, radiusBottomRight, radiusBottomLeft, ownerId, 0f, 0f, 0f, 0f);
    }

    public ClipRect(float minX, float minY, float maxX, float maxY,
                    float radiusTopLeft, float radiusTopRight, float radiusBottomRight, float radiusBottomLeft) {
        this(minX, minY, maxX, maxY, radiusTopLeft, radiusTopRight, radiusBottomRight, radiusBottomLeft, null);
    }

    public ClipRect withOwner(String ownerId) {
        return new ClipRect(minX, minY, maxX, maxY, radiusTopLeft, radiusTopRight, radiusBottomRight, radiusBottomLeft, ownerId,
                radiusTopLeftV, radiusTopRightV, radiusBottomRightV, radiusBottomLeftV, chain);
    }

    /** 連鎖を差し替える（合成した矩形はそのまま）。 */
    public ClipRect withChain(java.util.List<Part> newChain) {
        return new ClipRect(minX, minY, maxX, maxY, radiusTopLeft, radiusTopRight, radiusBottomRight, radiusBottomLeft, ownerId,
                radiusTopLeftV, radiusTopRightV, radiusBottomRightV, radiusBottomLeftV, newChain);
    }

    /** この矩形の連鎖に、{@code ownerId} の要素の空間の矩形 {@code rect}（{@link #toArray} の並び）を 1 つ足したもの。 */
    public java.util.List<Part> chainPlus(float[] rect, String owner) {
        java.util.List<Part> out = new java.util.ArrayList<>(chain);
        out.add(new Part(rect, owner));
        return out;
    }

    /** 縦の半径が横と違う角があるか（縦が全部 0 なら円の角）。 */
    public boolean isElliptical() {
        return radiusTopLeftV != 0 || radiusTopRightV != 0 || radiusBottomRightV != 0 || radiusBottomLeftV != 0;
    }

    /** 矩形 + 横の半径 4 つ。楕円の角があれば縦の半径 4 つが続く 12 値。 */
    public float[] toArray() {
        if (!isElliptical()) {
            return new float[]{minX, minY, maxX, maxY, radiusTopLeft, radiusTopRight, radiusBottomRight, radiusBottomLeft};
        }
        return new float[]{minX, minY, maxX, maxY, radiusTopLeft, radiusTopRight, radiusBottomRight, radiusBottomLeft,
                radiusTopLeftV, radiusTopRightV, radiusBottomRightV, radiusBottomLeftV};
    }

    public static ClipRect fromArray(float[] arr) {
        if (arr == null || arr.length < 4) return null;
        float rtl = arr.length >= 5 ? arr[4] : 0f;
        float rtr = arr.length >= 6 ? arr[5] : rtl;
        float rbr = arr.length >= 7 ? arr[6] : rtl;
        float rbl = arr.length >= 8 ? arr[7] : rtl;
        if (arr.length >= 12) {
            return new ClipRect(arr[0], arr[1], arr[2], arr[3], rtl, rtr, rbr, rbl, null, arr[8], arr[9], arr[10], arr[11]);
        }
        return new ClipRect(arr[0], arr[1], arr[2], arr[3], rtl, rtr, rbr, rbl);
    }

    public boolean contains(double x, double y) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY;
    }
}
