package com.nishiyu.enderframelib.core.text.font;

import org.lwjgl.CLongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.freetype.FT_Bitmap;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FT_GlyphSlot;
import org.lwjgl.util.freetype.FT_MM_Var;
import org.lwjgl.util.freetype.FT_Matrix;
import org.lwjgl.util.freetype.FreeType;
import org.lwjgl.util.freetype.TT_HoriHeader;
import org.lwjgl.util.freetype.TT_OS2;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ラスタライズした 1 字の被覆率（0〜255、1 バイト 1 ピクセル、行優先）。
 *
 * @param left 原点から字形の左端までの距離（デバイスピクセル、右が正）
 * @param top  ベースラインから字形の上端までの高さ（デバイスピクセル、上が正）
 */
public record GlyphBitmap(int width, int height, int left, int top, byte[] coverage) {

    public static final GlyphBitmap EMPTY = new GlyphBitmap(0, 0, 0, 0, new byte[0]);

    public boolean isEmpty() {
        return width <= 0 || height <= 0;
    }

    public int coverageAt(int x, int y) {
        return coverage[y * width + x] & 0xFF;
    }

    /**
     * 文字色の明るさに合わせて被覆率を補正したもの（Windows の DirectWrite と同じ考え方）。
     *
     * 画面の合成は sRGB のまま行われるので、被覆率をそのまま使うと暗い背景の明るい字が細く薄く見える
     * （Chrome の 40〜65% の濃さだった）。DirectWrite は被覆率を強調（enhanced contrast）してから
     * ガンマ 2.2 の線形空間で合成する。その結果と同じ色になる被覆率に置き換える:
     * 明るい字は e(c)^(1/2.2)、暗い字は 1 - (1 - e(c))^(1/2.2)。e(c) = c(1+k)/(1+kc)、k = 1.5。
     */
    public GlyphBitmap withTextContrast(boolean lightText) {
        int[] table = lightText ? LIGHT_TEXT : DARK_TEXT;
        byte[] out = new byte[coverage.length];
        for (int i = 0; i < out.length; i++) out[i] = (byte) table[coverage[i] & 0xFF];
        return new GlyphBitmap(width, height, left, top, out);
    }

    private static final float ENHANCED_CONTRAST = 1.5f;
    private static final float GAMMA = 2.2f;
    private static final int[] LIGHT_TEXT = contrastTable(true);
    private static final int[] DARK_TEXT = contrastTable(false);

    private static int[] contrastTable(boolean light) {
        int[] t = new int[256];
        for (int i = 0; i < 256; i++) {
            float c = i / 255f;
            float e = c * (1 + ENHANCED_CONTRAST) / (1 + ENHANCED_CONTRAST * c);
            float v = light ? (float) Math.pow(e, 1 / GAMMA) : 1 - (float) Math.pow(1 - e, 1 / GAMMA);
            t[i] = Math.clamp(Math.round(v * 255), 0, 255);
        }
        return t;
    }
}
