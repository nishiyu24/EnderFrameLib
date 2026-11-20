package com.nishiyu.enderframelib.core.raster;

import com.nishiyu.enderframelib.core.raster.awt.AwtImageDecoder;

import java.io.IOException;
import java.util.List;

/**
 * 圧縮画像（PNG / JPEG / GIF など）を画素へ復号する受け口（L0。AWT / ImageIO を知らない）。
 * 既定の実装 {@link #standard()} は JDK の ImageIO を使う L3 の {@link AwtImageDecoder} で、
 * AWT の無い環境・他のエンジンでは、面ごとの文脈（{@code HostServices#withImageDecoder}）で差し替える。
 */
public interface ImageDecoder {

    /** 非事前乗算の ARGB 画素（行優先）。 */

    /** GIF の 1 フレーム。位置・大きさは論理画面内の矩形、{@code disposal} は 0=なし・2=背景に戻す・3=前の内容に戻す。 */
    record GifFrame(int left, int top, int width, int height, int[] argb, int delayMs, int disposal) {}

    /** 論理画面の大きさとフレーム列（画素の合成はしない。{@link GifAnimation} が行う）。 */
    record GifStream(int width, int height, List<GifFrame> frames) {}

    /** 1 枚の画像を復号する。対応しない形式・壊れた画像は null。 */
    DecodedBitmap decode(byte[] bytes) throws IOException;

    /**
     * GIF のフレームを取り出す。GIF でない・壊れている・フレーム数が {@code maxFrames} か総画素数が
     * {@code maxTotalPixels} を超えるときは null（超過は画素を読む前に判定すること）。
     */
    GifStream decodeGif(byte[] bytes, int maxFrames, long maxTotalPixels);

    /** 文脈（面）が差し込む復号器の読み口（{@code HostServices} が実装する）。null なら標準（JDK 版）。 */
    interface Source {
        ImageDecoder imageDecoder();
    }

    /** このスレッドの文脈の復号器（差し込みが無ければ JDK 版）。 */
    static ImageDecoder standard() {
        ImageDecoder d = Holder.source.get().imageDecoder();
        return d != null ? d : Holder.DEFAULT;
    }

    /** 「このスレッドの文脈の値」を引く口を結ぶ（{@code EngineContext} が 1 回だけ呼ぶ）。以前は static の {@code use}（プロセス全体の差し替え）。 */
    static void bindSource(java.util.function.Supplier<Source> contextSource) {
        Holder.source = contextSource == null ? () -> () -> null : contextSource;
    }

    final class Holder {
        private static final ImageDecoder DEFAULT = new AwtImageDecoder();
        private static volatile java.util.function.Supplier<Source> source = () -> () -> null;

        private Holder() {}
    }
}
