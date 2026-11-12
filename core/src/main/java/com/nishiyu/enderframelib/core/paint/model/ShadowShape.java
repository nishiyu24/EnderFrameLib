package com.nishiyu.enderframelib.core.paint.model;



/**
 * ぼかした影の部品の形。外側の影は部品の矩形（ぼかしが消える所まで）から内側へ {@code SHADOW_REACH} だけ縮めた所が影の形で、
 * 箱そのもの（{@code x0..y1}・角丸）は影を抜く「穴」。内側の影は {@code x0..y1}・角丸が影の無い「穴」で、部品の矩形（パディングボックス）のうち
 * その外が影になる。
 *
 * @param inset 内側の影か
 */
public record ShadowShape(boolean inset, float x0, float y0, float x1, float y1,
                          float rTL, float rTR, float rBR, float rBL,
                          float rTLv, float rTRv, float rBRv, float rBLv) {
    /** 円の角（縦の半径は横と同じ）。 */
    public ShadowShape(boolean inset, float x0, float y0, float x1, float y1,
                       float rTL, float rTR, float rBR, float rBL) {
        this(inset, x0, y0, x1, y1, rTL, rTR, rBR, rBL, rTL, rTR, rBR, rBL);
    }

    /** 座標を (dx, dy) だけ平行移動した新しい ShadowShape を返す。 */
    public ShadowShape offset(float dx, float dy) {
        if (dx == 0 && dy == 0) return this;
        return new ShadowShape(inset, x0 + dx, y0 + dy, x1 + dx, y1 + dy,
                rTL, rTR, rBR, rBL, rTLv, rTRv, rBRv, rBLv);
    }
}
