package com.nishiyu.enderframelib.core.text.font;

import com.nishiyu.enderframelib.core.raster.Path2D;
import org.lwjgl.util.freetype.FT_Face;

/**
 * COLR v1（グラデーション・変形・合成を持つカラー字形）のペイントを、色付きのビットマップにする実装の口。
 * 実装は 2D キャンバスを持つ上のパッケージ（{@code core.raster.canvas}）にあり、{@link java.util.ServiceLoader} で見つける
 * （書体のパッケージが、キャンバスを持つパッケージを参照しないため。S5: {@code raster} ↔ {@code text.font} の循環の解体）。
 * 実装が見つからなければ v1 のペイントは描かず、呼び出し側が v0 / 単色へ戻す。
 */
public interface ColrV1Renderer {
    /** 描く最大の em の大きさ（画素）。これより大きいときは描かない（呼び出し側が v0 / 単色へ戻す）。 */
    float MAX_PIXEL_SIZE = 512f;

    /** 結果。{@code bitmap} は墨の外接矩形だけ。{@code usedForeground} は前景色を使ったか（前景色ごとに覚えを分ける）。 */
    record Result(ColorBitmap bitmap, boolean usedForeground) {}

    /** 描く面の情報（{@link LoadedFace} が渡す）。 */
    interface Face {
        /** 設計単位（1 em の単位数）。 */
        int unitsPerEm();

        /** 設計単位の字形の輪郭。 */
        Path2D glyphOutline(int glyphIndex);
    }

    /**
     * {@code glyphIndex} の COLR v1 ペイントを {@code pixelSize}（em の画素数）で描く。v1 のペイントを持たない・描けないときは
     * null。{@link LoadedFace} の面ロックの中から呼ばれる。
     */
    Result render(Face owner, FT_Face face, int glyphIndex, float pixelSize, int foregroundArgb);
}
