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
 * 開いた書体の 1 面。字形の有無・送り幅・縦の寸法とラスタライズを受け持つ。
 *
 * 寸法は Chrome（Windows / DirectWrite）と同じ取り方をする:
 * ascent / descent は OS/2 の winAscent / winDescent（USE_TYPO_METRICS が立っていれば typo の値）、
 * 行間は GDI の外部行間（hhea の lineGap から win と hhea の差を引いたもの）。
 * どれもピクセルに直してから整数に丸める（Blink の SimpleFontData と同じ）。
 */
public final class LoadedFace {

    /** 斜体を合成するときの傾き（tan）。DirectWrite の斜体シミュレーションに近い値。 */
    static final float SYNTHETIC_OBLIQUE_SKEW = 0.2f;

    /**
     * FreeType（LWJGL、Minecraft の TrueType 書体と同じ実装）は同じ FT_Face を複数のスレッドから同時に触れないので、
     * 書体の操作はすべてこのロックの中で行う。レイアウト（測定）と描画（ラスタライズ）が別のスレッドから来ても
     * 安全にするため、ロックは 1 つに絞っている。
     */
    public static final Object LOCK = new Object();
    private static long library;

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

    private static final Map<SystemFonts.Face, LoadedFace> OPEN = new ConcurrentHashMap<>();

    private final SystemFonts.Face record;
    private final FT_Face face;
    private final int unitsPerEm;
    private final float ascentEm;
    private final float descentEm;
    private final float lineGapEm;
    private final float xHeightEm;
    private final float notdefAdvanceEm;
    private final float underlinePositionEm;
    private final float underlineThicknessEm;
    private final float strikeoutPositionEm;
    private final float strikeoutThicknessEm;

    /** 送り幅（em）の表。BMP は 256 字ずつのページ、それ以外は Map。NaN は未計算。 */
    private final float[][] advancePages = new float[256][];
    private final Map<Integer, Float> advanceOther = new ConcurrentHashMap<>();
    /** 字形番号の表（番号 + 1、0 は未確認）。BMP は 256 字ずつのページ。 */
    private final int[][] glyphPages = new int[256][];
    private final Map<Integer, Integer> glyphOther = new ConcurrentHashMap<>();
    private volatile KerningTable kerning;

    private long currentCharSize = -1;
    private boolean currentOblique;
    private int currentSubpixel;

    /** 字の原点の横の端数の段数（1/4 ピクセル）。 */
    public static final int SUBPIXEL_STEPS = 4;

    private LoadedFace(SystemFonts.Face record, FT_Face face) {
        this.record = record;
        this.face = face;
        synchronized (LOCK) {
            this.unitsPerEm = Math.max(1, Short.toUnsignedInt(face.units_per_EM()));
            TT_OS2 os2 = TT_OS2.createSafe(FreeType.FT_Get_Sfnt_Table(face, FreeType.FT_SFNT_OS2));
            TT_HoriHeader hhea = TT_HoriHeader.createSafe(FreeType.FT_Get_Sfnt_Table(face, FreeType.FT_SFNT_HHEA));
            float upem = unitsPerEm;
            int hheaAsc = hhea != null ? hhea.Ascender() : face.ascender();
            int hheaDesc = hhea != null ? -hhea.Descender() : -face.descender();
            int hheaGap = hhea != null ? hhea.Line_Gap() : Math.max(0, face.height() - hheaAsc - hheaDesc);
            float asc, desc, gap;
            if (os2 != null && (os2.fsSelection() & (1 << 7)) != 0) {
                asc = os2.sTypoAscender();
                desc = -os2.sTypoDescender();
                gap = os2.sTypoLineGap();
            } else if (os2 != null && (os2.usWinAscent() != 0 || os2.usWinDescent() != 0)) {
                asc = Short.toUnsignedInt(os2.usWinAscent());
                desc = Short.toUnsignedInt(os2.usWinDescent());
                gap = Math.max(0, hheaGap - ((asc + desc) - (hheaAsc + hheaDesc)));
            } else {
                asc = hheaAsc;
                desc = hheaDesc;
                gap = hheaGap;
            }
            this.ascentEm = asc / upem;
            this.descentEm = desc / upem;
            this.lineGapEm = gap / upem;
            float xh = os2 != null && os2.version() >= 2 ? os2.sxHeight() : 0;
            this.xHeightEm = xh > 0 ? xh / upem : ascentEm * 0.5f;
            this.notdefAdvanceEm = rawAdvance(0) / upem;
            // 下線は post 表、打ち消し線は OS/2 表の位置と太さ（Chrome と同じ出所）
            int ulThick = face.underline_thickness();
            this.underlinePositionEm = -face.underline_position() / upem;
            this.underlineThicknessEm = (ulThick > 0 ? ulThick : upem / 14f) / upem;
            int soSize = os2 != null ? os2.yStrikeoutSize() : 0;
            int soPos = os2 != null ? os2.yStrikeoutPosition() : 0;
            this.strikeoutThicknessEm = (soSize > 0 ? soSize : ulThick > 0 ? ulThick : upem / 14f) / upem;
            this.strikeoutPositionEm = (soPos > 0 ? soPos : xHeightEm * upem / 2f) / upem;
        }
    }

    static long library() {
        synchronized (LOCK) {
            if (library == 0) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    PointerBuffer lib = stack.mallocPointer(1);
                    int err = FreeType.FT_Init_FreeType(lib);
                    if (err != 0) throw new IllegalStateException("FT_Init_FreeType failed: " + err);
                    library = lib.get(0);
                }
            }
            return library;
        }
    }

    /**
     * 書体ファイルの {@code faceIndex} 番目を開く。可変フォントの名前付きインスタンスは
     * {@code (インスタンス番号 << 16) | 書体番号}（FreeType の規約）。開けなければ null。
     */
    static FT_Face openFace(Path path, long faceIndex) {
        synchronized (LOCK) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer face = stack.mallocPointer(1);
                if (FreeType.FT_New_Face(library(), path.toString(), faceIndex, face) != 0) return null;
                return FT_Face.create(face.get(0));
            }
        }
    }

    static void closeFace(FT_Face face) {
        if (face == null) return;
        synchronized (LOCK) {
            FreeType.FT_Done_Face(face);
        }
    }

    /** 面を開く（同じ面は 1 度だけ開いて共有する）。開けなければ null。 */
    public static LoadedFace open(SystemFonts.Face record) {
        LoadedFace cached = OPEN.get(record);
        if (cached != null) return cached;
        synchronized (LOCK) {
            cached = OPEN.get(record);
            if (cached != null) return cached;
            FT_Face face = openFace(record.path(), record.faceIndex());
            if (face == null) return null;
            LoadedFace loaded = new LoadedFace(record, face);
            OPEN.put(record, loaded);
            return loaded;
        }
    }

    public SystemFonts.Face record() {
        return record;
    }

    /** ascent（px、丸め済み）。 */
    public float ascent(float fontSizePx) {
        return Math.round(ascentEm * fontSizePx);
    }

    /** descent（px、丸め済み）。 */
    public float descent(float fontSizePx) {
        return Math.round(descentEm * fontSizePx);
    }

    /** 行間（px、丸め済み）。 */
    public float lineGap(float fontSizePx) {
        return Math.round(lineGapEm * fontSizePx);
    }

    public float xHeight(float fontSizePx) {
        return xHeightEm * fontSizePx;
    }

    /** 下線の上端のベースラインからの距離（px、下が正）。 */
    public float underlineOffset(float fontSizePx) {
        return underlinePositionEm * fontSizePx;
    }

    /** 下線の太さ（px、1 以上）。 */
    public float underlineThickness(float fontSizePx) {
        return Math.max(1f, Math.round(underlineThicknessEm * fontSizePx));
    }

    /** 打ち消し線の中心のベースラインからの高さ（px、上が正）。 */
    public float strikeoutPosition(float fontSizePx) {
        return strikeoutPositionEm * fontSizePx;
    }

    public float strikeoutThickness(float fontSizePx) {
        return Math.max(1f, Math.round(strikeoutThicknessEm * fontSizePx));
    }

    /** この面に {@code codePoint} の字形があるか。 */
    public boolean hasGlyph(int codePoint) {
        return glyphId(codePoint) != 0;
    }

    /** {@code codePoint} の字形番号。無ければ 0（.notdef）。 */
    public int glyphId(int codePoint) {
        if (codePoint < 0x10000) {
            int[] page = glyphPages[codePoint >>> 8];
            if (page == null) {
                page = new int[256];
                glyphPages[codePoint >>> 8] = page;
            }
            int v = page[codePoint & 0xFF];
            if (v == 0) {
                v = glyphIndex(codePoint) + 1;
                page[codePoint & 0xFF] = v;
            }
            return v - 1;
        }
        return glyphOther.computeIfAbsent(codePoint, this::glyphIndex);
    }

    /** 字形の組 {@code first} → {@code second} の詰め（em）。 */
    public float kerningEm(int firstGlyph, int secondGlyph) {
        KerningTable k = kerning;
        if (k == null) {
            synchronized (LOCK) {
                k = kerning;
                if (k == null) {
                    k = KerningTable.parse(loadTable("GPOS"), loadTable("kern"), loadTable("GDEF"), normalizedCoords());
                    kerning = k;
                }
            }
        }
        if (k.isEmpty()) return 0;
        return k.adjustment(firstGlyph, secondGlyph) / (float) unitsPerEm;
    }

    /** 可変フォントのインスタンスの正規化座標（avar 適用後）。可変フォントでなければ null。 */
    private float[] normalizedCoords() {
        if ((face.face_flags() & FreeType.FT_FACE_FLAG_MULTIPLE_MASTERS) == 0 || (record.faceIndex() >>> 16) == 0) return null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pmm = stack.mallocPointer(1);
            if (FreeType.FT_Get_MM_Var(face, pmm) != 0) return null;
            FT_MM_Var mm = FT_MM_Var.create(pmm.get(0));
            int axes = mm.num_axis();
            FreeType.FT_Done_MM_Var(library(), mm);
            CLongBuffer coords = stack.mallocCLong(axes);
            if (FreeType.FT_Get_Var_Blend_Coordinates(face, coords) != 0) return null;
            float[] out = new float[axes];
            for (int i = 0; i < axes; i++) out[i] = coords.get(i) / 65536f;
            return out;
        }
    }

    /** sfnt の表の中身。無ければ null。 */
    private byte[] loadTable(String tag) {
        long t = ((long) tag.charAt(0) << 24) | ((long) tag.charAt(1) << 16) | ((long) tag.charAt(2) << 8) | tag.charAt(3);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            CLongBuffer len = stack.mallocCLong(1);
            len.put(0, 0);
            if (FreeType.FT_Load_Sfnt_Table(face, t, 0, null, len) != 0 || len.get(0) <= 0) return null;
            int n = (int) len.get(0);
            ByteBuffer buf = org.lwjgl.system.MemoryUtil.memAlloc(n);
            try {
                if (FreeType.FT_Load_Sfnt_Table(face, t, 0, buf, len) != 0) return null;
                byte[] out = new byte[n];
                buf.get(0, out);
                return out;
            } finally {
                org.lwjgl.system.MemoryUtil.memFree(buf);
            }
        }
    }

    /** 送り幅（em）。字形が無ければ .notdef の送り幅。ヒンティングの無い設計上の値。 */
    public float advanceEm(int codePoint) {
        if (codePoint < 0x10000) {
            float[] page = advancePages[codePoint >>> 8];
            if (page == null) {
                page = new float[256];
                java.util.Arrays.fill(page, Float.NaN);
                advancePages[codePoint >>> 8] = page;
            }
            float v = page[codePoint & 0xFF];
            if (Float.isNaN(v)) {
                v = computeAdvanceEm(codePoint);
                page[codePoint & 0xFF] = v;
            }
            return v;
        }
        return advanceOther.computeIfAbsent(codePoint, this::computeAdvanceEm);
    }

    private float computeAdvanceEm(int codePoint) {
        synchronized (LOCK) {
            int gi = FreeType.FT_Get_Char_Index(face, codePoint);
            if (gi == 0) return notdefAdvanceEm;
            return rawAdvance(gi) / (float) unitsPerEm;
        }
    }

    private int glyphIndex(int codePoint) {
        synchronized (LOCK) {
            return FreeType.FT_Get_Char_Index(face, codePoint);
        }
    }

    /** 設計単位の送り幅（可変フォントのインスタンスでは変化量を反映した値）。 */
    private long rawAdvance(int glyphIndex) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            CLongBuffer adv = stack.mallocCLong(1);
            if (FreeType.FT_Get_Advance(face, glyphIndex, FreeType.FT_LOAD_NO_SCALE, adv) != 0) return 0;
            return adv.get(0);
        }
    }

    /**
     * {@code pixelSize}（デバイスピクセルでの em の大きさ）で 1 字をラスタライズする。
     * ヒンティングは縦方向だけ（DirectWrite の既定の描き方に近い）、被覆率はグレースケール。
     */
    public GlyphBitmap rasterize(int codePoint, float pixelSize, boolean syntheticBold, boolean syntheticItalic) {
        return rasterize(codePoint, pixelSize, syntheticBold, syntheticItalic, 0);
    }

    /**
     * {@code subpixel}（0 から {@link #SUBPIXEL_STEPS} - 1）は原点の横の端数（1/{@value #SUBPIXEL_STEPS} ピクセル単位）。
     * 字の原点をピクセルに丸めず端数の位置で描くので、字間が不揃いにならない（Chrome のサブピクセル位置決めと同じ）。
     */
    public GlyphBitmap rasterize(int codePoint, float pixelSize, boolean syntheticBold, boolean syntheticItalic, int subpixel) {
        synchronized (LOCK) {
            int gi = FreeType.FT_Get_Char_Index(face, codePoint);
            long charSize = Math.max(64, Math.round(pixelSize * 64));
            if (charSize != currentCharSize) {
                if (FreeType.FT_Set_Char_Size(face, 0, charSize, 72, 72) != 0) return GlyphBitmap.EMPTY;
                currentCharSize = charSize;
            }
            if (syntheticItalic != currentOblique || subpixel != currentSubpixel) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    FT_Matrix m = FT_Matrix.malloc(stack);
                    m.xx(0x10000).xy(syntheticItalic ? Math.round(SYNTHETIC_OBLIQUE_SKEW * 0x10000) : 0).yx(0).yy(0x10000);
                    org.lwjgl.util.freetype.FT_Vector delta = org.lwjgl.util.freetype.FT_Vector.malloc(stack);
                    delta.x(subpixel * 64L / SUBPIXEL_STEPS).y(0);
                    FreeType.FT_Set_Transform(face, m, delta);
                }
                currentOblique = syntheticItalic;
                currentSubpixel = subpixel;
            }
            int flags = FreeType.FT_LOAD_NO_BITMAP | FreeType.FT_FT_LOAD_TARGET_LIGHT;
            if (FreeType.FT_Load_Glyph(face, gi, flags) != 0) return GlyphBitmap.EMPTY;
            FT_GlyphSlot slot = face.glyph();
            if (slot == null) return GlyphBitmap.EMPTY;
            if (syntheticBold) {
                // Skia の擬似太字と同じ太らせ量（em の 1/24）。送り幅は変えない（Chrome も変えない）
                FreeType.FT_Outline_Embolden(slot.outline(), Math.max(1, Math.round(pixelSize * 64 / 24f)));
            }
            if (FreeType.FT_Render_Glyph(slot, FreeType.FT_RENDER_MODE_NORMAL) != 0) return GlyphBitmap.EMPTY;
            FT_Bitmap bitmap = slot.bitmap();
            int w = bitmap.width();
            int h = bitmap.rows();
            if (w <= 0 || h <= 0) return GlyphBitmap.EMPTY;
            int pitch = bitmap.pitch();
            ByteBuffer buf = bitmap.buffer(Math.abs(pitch) * h);
            byte[] out = new byte[w * h];
            for (int y = 0; y < h; y++) {
                int row = pitch >= 0 ? y : (h - 1 - y);
                buf.get(row * Math.abs(pitch), out, y * w, w);
            }
            return new GlyphBitmap(w, h, slot.bitmap_left(), slot.bitmap_top(), out);
        }
    }

    @Override
    public String toString() {
        return "LoadedFace[" + record.displayName() + " w" + record.weight() + (record.italic() ? " italic" : "") + "]";
    }
}
