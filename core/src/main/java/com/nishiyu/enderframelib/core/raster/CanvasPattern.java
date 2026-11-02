package com.nishiyu.enderframelib.core.raster;

/**
 * 画像を塗りにするもの。Canvas 2D の {@code CanvasPattern} と、{@code drawImage} の内部で使う。
 * 画像の座標系（画素）から塗りの座標系への変換 {@link #setTransform} を持つ。
 */
public final class CanvasPattern extends Paint {

    private final Pixmap image;
    private final int sx;
    private final int sy;
    private final int sw;
    private final int sh;
    private final boolean repeatX;
    private final boolean repeatY;
    private boolean smoothing = true;
    private Matrix2D transform = Matrix2D.IDENTITY;

    /** @param repetition {@code repeat} / {@code repeat-x} / {@code repeat-y} / {@code no-repeat}（空と {@code null} は repeat） */
    public CanvasPattern(Pixmap image, String repetition) {
        this(image, 0, 0, image.width(), image.height(), repetition);
    }

    CanvasPattern(Pixmap image, int sx, int sy, int sw, int sh, String repetition) {
        this.image = image;
        this.sx = sx;
        this.sy = sy;
        this.sw = sw;
        this.sh = sh;
        String r = repetition == null || repetition.isEmpty() ? "repeat" : repetition;
        switch (r) {
            case "repeat" -> {
                repeatX = true;
                repeatY = true;
            }
            case "repeat-x" -> {
                repeatX = true;
                repeatY = false;
            }
            case "repeat-y" -> {
                repeatX = false;
                repeatY = true;
            }
            case "no-repeat" -> {
                repeatX = false;
                repeatY = false;
            }
            default -> throw new IllegalArgumentException("SyntaxError: repetition " + repetition);
        }
    }

    public CanvasPattern setTransform(Matrix2D transform) {
        this.transform = transform == null ? Matrix2D.IDENTITY : transform;
        return this;
    }

    CanvasPattern smoothing(boolean smoothing) {
        this.smoothing = smoothing;
        return this;
    }

    @Override
    Shader shader(Matrix2D toDevice) {
        Matrix2D inv = toDevice.multiply(transform).invert();
        if (inv == null || sw <= 0 || sh <= 0) return (x, y) -> 0;
        final int[] px = image.pixels();
        final int iw = image.width();
        return (dx, dy) -> {
            double u = inv.applyX(dx, dy);
            double v = inv.applyY(dx, dy);
            if (!repeatX && (u < 0 || u >= sw)) {
                if (u < -0.5 || u >= sw + 0.5) return 0;
            }
            if (!repeatY && (v < 0 || v >= sh)) {
                if (v < -0.5 || v >= sh + 0.5) return 0;
            }
            if (!smoothing) {
                int ix = wrap((int) Math.floor(u), sw, repeatX);
                int iy = wrap((int) Math.floor(v), sh, repeatY);
                return px[(sy + iy) * iw + sx + ix];
            }
            double fu = u - 0.5;
            double fv = v - 0.5;
            int x0 = (int) Math.floor(fu);
            int y0 = (int) Math.floor(fv);
            double tx = fu - x0;
            double ty = fv - y0;
            int c00 = px[(sy + wrap(y0, sh, repeatY)) * iw + sx + wrap(x0, sw, repeatX)];
            int c10 = px[(sy + wrap(y0, sh, repeatY)) * iw + sx + wrap(x0 + 1, sw, repeatX)];
            int c01 = px[(sy + wrap(y0 + 1, sh, repeatY)) * iw + sx + wrap(x0, sw, repeatX)];
            int c11 = px[(sy + wrap(y0 + 1, sh, repeatY)) * iw + sx + wrap(x0 + 1, sw, repeatX)];
            if (c00 == c10 && c00 == c01 && c00 == c11) return c00;
            double w00 = (1 - tx) * (1 - ty);
            double w10 = tx * (1 - ty);
            double w01 = (1 - tx) * ty;
            double w11 = tx * ty;
            int a = (int) Math.round((c00 >>> 24) * w00 + (c10 >>> 24) * w10 + (c01 >>> 24) * w01 + (c11 >>> 24) * w11);
            int r = (int) Math.round(((c00 >> 16) & 0xFF) * w00 + ((c10 >> 16) & 0xFF) * w10
                    + ((c01 >> 16) & 0xFF) * w01 + ((c11 >> 16) & 0xFF) * w11);
            int g = (int) Math.round(((c00 >> 8) & 0xFF) * w00 + ((c10 >> 8) & 0xFF) * w10
                    + ((c01 >> 8) & 0xFF) * w01 + ((c11 >> 8) & 0xFF) * w11);
            int b = (int) Math.round((c00 & 0xFF) * w00 + (c10 & 0xFF) * w10 + (c01 & 0xFF) * w01 + (c11 & 0xFF) * w11);
            return (a << 24) | (Math.min(r, a) << 16) | (Math.min(g, a) << 8) | Math.min(b, a);
        };
    }

    private static int wrap(int i, int size, boolean repeat) {
        if (repeat) {
            int m = i % size;
            return m < 0 ? m + size : m;
        }
        return i < 0 ? 0 : (i >= size ? size - 1 : i);
    }
}
