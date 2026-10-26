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
 */
public record ClipRect(
        float minX, float minY, float maxX, float maxY,
        float radiusTopLeft, float radiusTopRight, float radiusBottomRight, float radiusBottomLeft,
        String ownerId,
        float radiusTopLeftV, float radiusTopRightV, float radiusBottomRightV, float radiusBottomLeftV
) {
    public static final ClipRect EMPTY = null;

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
                radiusTopLeftV, radiusTopRightV, radiusBottomRightV, radiusBottomLeftV);
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
