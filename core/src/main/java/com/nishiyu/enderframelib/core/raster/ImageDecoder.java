package com.nishiyu.enderframelib.core.raster;

import com.nishiyu.enderframelib.core.raster.awt.AwtImageDecoder;

import java.io.IOException;
import java.util.List;

/**
 * 圧縮画像（PNG / JPEG / GIF など）を画素へ復号する受け口（L0。AWT / ImageIO を知らない）。
 * 既定の実装 {@link #standard()} は JDK の ImageIO を使う L3 の {@link AwtImageDecoder} で、
 * AWT の無い環境・他のエンジンでは {@link #use(ImageDecoder)} で差し替える。
 */
public interface ImageDecoder {

    /** 非事前乗算の ARGB 画素（行優先）。 */
    record Bitmap(int width, int height, int[] argb) {}

    /** GIF の 1 フレーム。位置・大きさは論理画面内の矩形、{@code disposal} は 0=なし・2=背景に戻す・3=前の内容に戻す。 */
    record GifFrame(int left, int top, int width, int height, int[] argb, int delayMs, int disposal) {}

    /** 論理画面の大きさとフレーム列（画素の合成はしない。{@link GifAnimation} が行う）。 */
    record GifStream(int width, int height, List<GifFrame> frames) {}

    /** 1 枚の画像を復号する。対応しない形式・壊れた画像は null。 */
    Bitmap decode(byte[] bytes) throws IOException;

    /**
     * GIF のフレームを取り出す。GIF でない・壊れている・フレーム数が {@code maxFrames} か総画素数が
     * {@code maxTotalPixels} を超えるときは null（超過は画素を読む前に判定すること）。
     */
    GifStream decodeGif(byte[] bytes, int maxFrames, long maxTotalPixels);

    static ImageDecoder standard() {
        return Holder.current;
    }

    /** 以後の既定を差し替える（null で JDK 版に戻す）。起動時に 1 回呼ぶ想定。 */
    static void use(ImageDecoder decoder) {
        Holder.current = decoder != null ? decoder : new AwtImageDecoder();
    }

    final class Holder {
        private static volatile ImageDecoder current = new AwtImageDecoder();

        private Holder() {}
    }
}
