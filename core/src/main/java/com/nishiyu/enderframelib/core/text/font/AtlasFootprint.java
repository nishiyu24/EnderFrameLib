package com.nishiyu.enderframelib.core.text.font;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 字形の置き場 1 つが GPU に載せた字形の数とピクセル数。置き場の VRAM の見積もりの元になる。
 *
 * Minecraft の字形のテクスチャ（{@code FontTexture}）は 256px 四方で、色なしの字形は 1 ピクセル 1 バイト
 * （輝度）。置き場は字形が溜まるとテクスチャを足すので、使用量は字形の面積から見積もる
 * （{@link #PACKING_OVERHEAD} は詰め込みの隙間と字形どうしの余白。{@code AtlasFootprintTest} が実際の書体で確かめている）。
 */
public final class AtlasFootprint {

    /** {@code FontTexture} 1 枚の一辺（px）。 */
    public static final int SHEET_SIZE = 256;
    /** 色なしの字形の 1 ピクセルあたりのバイト数。 */
    public static final int BYTES_PER_PIXEL = 1;
    /** 字形の面積に対するテクスチャの使用量の比（隙間と余白の見込み）。 */
    public static final double PACKING_OVERHEAD = 1.3;

    private final AtomicLong glyphs = new AtomicLong();
    private final AtomicLong pixels = new AtomicLong();

    /** 幅 × 高さの字形を 1 つ載せた。 */
    public void recordGlyph(int width, int height) {
        glyphs.incrementAndGet();
        pixels.addAndGet((long) width * height);
    }

    public long glyphCount() {
        return glyphs.get();
    }

    public long glyphPixels() {
        return pixels.get();
    }

    /** 確保されているテクスチャの枚数の見積もり（1 字でも載せていれば最低 1 枚）。 */
    public int estimatedSheets() {
        return sheetsFor(pixels.get(), glyphs.get());
    }

    public long estimatedBytes() {
        return (long) estimatedSheets() * SHEET_SIZE * SHEET_SIZE * BYTES_PER_PIXEL;
    }

    static int sheetsFor(long glyphPixels, long glyphCount) {
        if (glyphCount <= 0) return 0;
        long sheetPixels = (long) SHEET_SIZE * SHEET_SIZE;
        return (int) Math.max(1, (long) Math.ceil(glyphPixels * PACKING_OVERHEAD / sheetPixels));
    }
}
