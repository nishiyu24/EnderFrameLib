package com.nishiyu.enderframelib.core.paint.model;

/**
 * {@link DisplayItem} の位置・寸法・色・不透明度・変形をまとめる不変の値オブジェクト（B9 の M2 / C6）。
 * すべての欄が {@code final} で、生成後に書き換える口を持たない。描画の上だけの平行移動は
 * {@link PaintOffsets} で項目の外に持つ。
 */
public final class PaintCell {
    final int x, y, width, height;
    final int color, bgColor;
    final int radius;
    final float opacity;
    final float translateX, translateY, scaleX, scaleY, rotate;

    public PaintCell(int x, int y, int width, int height, int color, int bgColor, int radius, float opacity,
                     float translateX, float translateY, float scaleX, float scaleY, float rotate) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.color = color;
        this.bgColor = bgColor;
        this.radius = radius;
        this.opacity = opacity;
        this.translateX = translateX;
        this.translateY = translateY;
        this.scaleX = scaleX;
        this.scaleY = scaleY;
        this.rotate = rotate;
    }

    /** 位置だけ違う写し。 */
    PaintCell withPosition(int newX, int newY) {
        return new PaintCell(newX, newY, width, height, color, bgColor, radius, opacity, translateX, translateY, scaleX, scaleY, rotate);
    }

    PaintCell withBounds(int newX, int newY, int newWidth, int newHeight) {
        return new PaintCell(newX, newY, newWidth, newHeight, color, bgColor, radius, opacity, translateX, translateY, scaleX, scaleY, rotate);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PaintCell c)) return false;
        return x == c.x && y == c.y && width == c.width && height == c.height
                && color == c.color && bgColor == c.bgColor && radius == c.radius
                && Float.compare(opacity, c.opacity) == 0
                && Float.compare(translateX, c.translateX) == 0 && Float.compare(translateY, c.translateY) == 0
                && Float.compare(scaleX, c.scaleX) == 0 && Float.compare(scaleY, c.scaleY) == 0
                && Float.compare(rotate, c.rotate) == 0;
    }

    @Override
    public int hashCode() {
        int h = x;
        h = 31 * h + y;
        h = 31 * h + width;
        h = 31 * h + height;
        h = 31 * h + color;
        h = 31 * h + bgColor;
        h = 31 * h + radius;
        h = 31 * h + Float.hashCode(opacity);
        h = 31 * h + Float.hashCode(translateX);
        h = 31 * h + Float.hashCode(translateY);
        h = 31 * h + Float.hashCode(scaleX);
        h = 31 * h + Float.hashCode(scaleY);
        h = 31 * h + Float.hashCode(rotate);
        return h;
    }

    @Override
    public String toString() {
        return "PaintCell[x=" + x + ", y=" + y + ", w=" + width + ", h=" + height + ", color=" + color + ", bg=" + bgColor
                + ", radius=" + radius + ", opacity=" + opacity + ", t=(" + translateX + "," + translateY + "), s=("
                + scaleX + "," + scaleY + "), rotate=" + rotate + "]";
    }
}
