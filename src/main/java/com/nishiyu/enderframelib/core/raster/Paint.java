package com.nishiyu.enderframelib.core.raster;

/**
 * 塗りの色の源（単色・グラデーション・パターン）。Canvas 2D の {@code fillStyle} / {@code strokeStyle} の値にあたる。
 */
public abstract class Paint {

    /** 画素ごとに色を返す計算器。座標はデバイス（出力の画素）の座標、返す色は事前乗算済みの ARGB。 */
    public interface Shader {
        int colorAt(double deviceX, double deviceY);

        /** 常に同じ色を返すなら、その色。そうでなければ {@code -1} ではなく {@link #isSolid()} を偽にする。 */
        default boolean isSolid() {
            return false;
        }

        default int solidColor() {
            return 0;
        }
    }

    /**
     * @param toDevice 塗りの座標系（ユーザー空間）からデバイスへの変換
     */
    abstract Shader shader(Matrix2D toDevice);

    public static Paint solid(int straightArgb) {
        return new Solid(straightArgb);
    }

    /** 単色。 */
    public static final class Solid extends Paint {
        private final int straightArgb;
        private final int premultiplied;

        public Solid(int straightArgb) {
            this.straightArgb = straightArgb;
            this.premultiplied = Pixmap.premultiply(straightArgb);
        }

        public int argb() {
            return straightArgb;
        }

        @Override
        Shader shader(Matrix2D toDevice) {
            final int pm = premultiplied;
            return new Shader() {
                @Override
                public int colorAt(double x, double y) {
                    return pm;
                }

                @Override
                public boolean isSolid() {
                    return true;
                }

                @Override
                public int solidColor() {
                    return pm;
                }
            };
        }
    }
}
