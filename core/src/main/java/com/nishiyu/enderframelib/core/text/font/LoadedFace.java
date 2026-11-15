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
     * 書体の「一覧」と共有の FT_Library（書体ファイルの走査・{@code @font-face} の検査）、および開いた面の登録表を守るロック。
     * 開いた面そのものの操作（測定・字形番号・ラスタライズ）はこのロックを取らず、面ごとの {@link #lock} だけで行うので、
     * 別々の書体の測定と描画は互いを待たない。取る順は常に {@code LOCK} → 面の {@code lock}（逆は取らない）。
     */
    public static final Object LOCK = new Object();
    private static long library;

    private static final Map<SystemFonts.Face, LoadedFace> OPEN = new ConcurrentHashMap<>();

    private final SystemFonts.Face record;
    /**
     * この面だけのロック。FreeType は FT_Library 単位でしか並列に使えない（同じ Library の面を同時に触ると壊れる）ので、
     * 面ごとに自分専用の FT_Library（{@link #ownLibrary}）を持ち、面の操作はこのロックの中で行う。
     */
    private final Object lock = new Object();
    /** この面専用の FT_Library。ハンドルを開いている間だけ有効（{@link #release()} で閉じ、開き直すと作り直す）。 */
    private long ownLibrary;
    /** 開いている FreeType のハンドル。{@link #release()} で閉じ、次に使うとき {@link #ensureOpen()} が開き直す。 */
    private FT_Face face;
    /** メモリから開いた書体（{@code @font-face}）の実体。FreeType はハンドルが生きている間これを参照し続ける。 */
    private ByteBuffer memory;
    private volatile long lastUseNanos = System.nanoTime();
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

    private LoadedFace(SystemFonts.Face record, Opened opened) {
        this.record = record;
        this.face = opened.face();
        this.memory = opened.memory();
        this.ownLibrary = opened.library();
        FT_Face face = opened.face();
        synchronized (lock) {
            int rawUpem = Short.toUnsignedInt(face.units_per_EM());
            this.unitsPerEm = Math.max(1, rawUpem);
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
            if ((rawUpem == 0 || (asc == 0 && desc == 0)) && face.num_fixed_sizes() > 0) {
                float strikeAsc = 0.8f, strikeDesc = 0.2f;
                if (FreeType.FT_Select_Size(face, 0) == 0) {
                    currentCharSize = -1;
                    float em = strikeEm(face.available_sizes().get(0));
                    if (em > 0) {
                        var sm = face.size().metrics();
                        float a = (sm.ascender() / 64f) / em;
                        float d = (-sm.descender() / 64f) / em;
                        if (a > 0 || d > 0) {
                            strikeAsc = a;
                            strikeDesc = d;
                        }
                    }
                }
                this.ascentEm = strikeAsc;
                this.descentEm = strikeDesc;
                this.lineGapEm = 0f;
            } else {
                this.ascentEm = asc / upem;
                this.descentEm = desc / upem;
                this.lineGapEm = gap / upem;
            }
            float xh = os2 != null && os2.version() >= 2 ? os2.sxHeight() : 0;
            this.xHeightEm = xh > 0 ? xh / upem : ascentEm * 0.5f;
            long notdefRaw = rawUpem > 0 ? rawAdvance(0) : 0;
            this.notdefAdvanceEm = notdefRaw > 0 ? notdefRaw / upem
                    : (face.num_fixed_sizes() > 0 ? strikeAdvanceEm(0) : 0f);
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

    /** アウトライン（拡大縮小可）またはカラービットマップ（{@code CBDT} / {@code sbix}）を持つ書体か。 */
    static boolean isUsableFace(FT_Face face) {
        long flags = face.face_flags();
        return (flags & FreeType.FT_FACE_FLAG_SCALABLE) != 0
                || ((flags & FreeType.FT_FACE_FLAG_COLOR) != 0 && face.num_fixed_sizes() > 0);
    }

    /** 開いたハンドルと、メモリから開いたときの実体（ハンドルより先に解放してはいけない）と、その面専用の FT_Library。 */
    private record Opened(FT_Face face, ByteBuffer memory, long library) {}

    /** 面専用の FT_Library を作る。失敗は {@link IllegalStateException}。 */
    private static long newLibrary() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer lib = stack.mallocPointer(1);
            int err = FreeType.FT_Init_FreeType(lib);
            if (err != 0) throw new IllegalStateException("FT_Init_FreeType failed: " + err);
            return lib.get(0);
        }
    }

    /** 面専用の Library で開く。他のスレッドの面とは何も共有しないので全域ロックは要らない。 */
    private static Opened openRecord(SystemFonts.Face record) {
        long lib = newLibrary();
        byte[] data = record.data() != null ? record.data().bytes() : null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer out = stack.mallocPointer(1);
            if (data == null) {
                if (FreeType.FT_New_Face(lib, record.path().toString(), record.faceIndex(), out) != 0) {
                    FreeType.FT_Done_FreeType(lib);
                    return null;
                }
                return new Opened(FT_Face.create(out.get(0)), null, lib);
            }
            ByteBuffer buf = org.lwjgl.system.MemoryUtil.memAlloc(data.length);
            buf.put(0, data);
            if (FreeType.FT_New_Memory_Face(lib, buf, record.faceIndex(), out) != 0) {
                org.lwjgl.system.MemoryUtil.memFree(buf);
                FreeType.FT_Done_FreeType(lib);
                return null;
            }
            return new Opened(FT_Face.create(out.get(0)), buf, lib);
        }
    }

    /** ハンドルが閉じていれば開き直す。{@link #lock} の中で呼ぶ。開けなければ {@link IllegalStateException}。 */
    private void ensureOpen() {
        lastUseNanos = System.nanoTime();
        if (face != null) return;
        Opened opened = openRecord(record);
        if (opened == null) throw new IllegalStateException("Cannot reopen font " + record.displayName());
        face = opened.face();
        memory = opened.memory();
        ownLibrary = opened.library();
        currentCharSize = -1;
        currentOblique = false;
        currentSubpixel = 0;
    }

    /**
     * FreeType のハンドルを閉じる。測った寸法・送り幅・字形番号の表は残るので、次に使うまでは何も失わない
     * （使うと開き直す）。字形を描かない面がハンドルを持ち続けないようにするためのもの。
     */
    public void release() {
        synchronized (lock) {
            if (face == null) return;
            FreeType.FT_Done_Face(face);
            face = null;
            if (ownLibrary != 0) {
                FreeType.FT_Done_FreeType(ownLibrary);
                ownLibrary = 0;
            }
            if (memory != null) {
                org.lwjgl.system.MemoryUtil.memFree(memory);
                memory = null;
            }
        }
    }

    /** ハンドルを開いている面の数。 */
    public static int openHandleCount() {
        int n = 0;
        for (LoadedFace f : OPEN.values()) if (f.hasHandle()) n++;
        return n;
    }

    private boolean hasHandle() {
        synchronized (lock) {
            return face != null;
        }
    }

    /** {@code idleNanos} 以上使われていない面のハンドルを閉じる。閉じた数を返す。 */
    public static int releaseIdle(long idleNanos) {
        long now = System.nanoTime();
        int closed = 0;
        for (LoadedFace f : OPEN.values()) {
            // 使われた直後（idle でない）の面は待たずに飛ばす。release は面のロックを取るので、測定中の面とは競合しない
            if (now - f.lastUseNanos >= idleNanos && f.hasHandle()) {
                f.release();
                closed++;
            }
        }
        return closed;
    }

    /** 一覧から外した面（登録を消した {@code @font-face}）のハンドルを閉じて忘れる。 */
    static void forget(java.util.Collection<SystemFonts.Face> records) {
        synchronized (LOCK) {
            for (SystemFonts.Face r : records) {
                LoadedFace f = OPEN.remove(r);
                if (f != null) f.release();
            }
        }
    }

    /** すべての面のハンドルを閉じ、一覧からも外す（資源の再読み込みやクライアントの終了で呼ぶ）。 */
    public static void releaseAll() {
        synchronized (LOCK) {
            for (LoadedFace f : OPEN.values()) f.release();
            OPEN.clear();
        }
    }

    /** 面を開く（同じ面は 1 度だけ開いて共有する）。開けなければ null。 */
    public static LoadedFace open(SystemFonts.Face record) {
        LoadedFace cached = OPEN.get(record);
        if (cached != null) return cached;
        synchronized (LOCK) {
            cached = OPEN.get(record);
            if (cached != null) return cached;
            Opened opened = openRecord(record);
            if (opened == null) return null;
            LoadedFace loaded = new LoadedFace(record, opened);
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
            synchronized (lock) {
                k = kerning;
                if (k == null) {
                    ensureOpen();
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
            FreeType.FT_Done_MM_Var(ownLibrary, mm);
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
        synchronized (lock) {
            ensureOpen();
            int gi = FreeType.FT_Get_Char_Index(face, codePoint);
            if (gi == 0) return notdefAdvanceEm;
            long raw = Short.toUnsignedInt(face.units_per_EM()) > 0 ? rawAdvance(gi) : 0;
            if (raw > 0) return raw / (float) unitsPerEm;
            if ((face.face_flags() & FreeType.FT_FACE_FLAG_SCALABLE) == 0 && face.num_fixed_sizes() > 0) {
                return strikeAdvanceEm(gi);
            }
            return raw / (float) unitsPerEm;
        }
    }

    /** ビットマップのみの書体向け: 固定サイズのストライクから送り幅（em）を取る。{@link #lock} の中で呼ぶ。 */
    private float strikeAdvanceEm(int gi) {
        int count = face.num_fixed_sizes();
        var sizes = face.available_sizes();
        if (sizes == null || count <= 0) return 0f;
        int idx = 0;
        float em = strikeEm(sizes.get(0));
        for (int i = 1; i < count; i++) {
            float e = strikeEm(sizes.get(i));
            if (e > em) {
                em = e;
                idx = i;
            }
        }
        if (em <= 0f || FreeType.FT_Select_Size(face, idx) != 0) return 0f;
        currentCharSize = -1;
        if (FreeType.FT_Load_Glyph(face, gi, FreeType.FT_LOAD_COLOR) == 0 && face.glyph() != null) {
            float advPx = face.glyph().advance().x() / 64f;
            if (advPx > 0f) return advPx / em;
        }
        int w = sizes.get(idx).width();
        return w > 0 ? w / em : 1f;
    }

    private static float strikeEm(org.lwjgl.util.freetype.FT_Bitmap_Size s) {
        long yp = s.y_ppem();
        if (yp > 0) return yp / 64f;
        int h = s.height();
        return h > 0 ? (float) h : s.width();
    }

    private int glyphIndex(int codePoint) {
        synchronized (lock) {
            ensureOpen();
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
        return render(codePoint, pixelSize, syntheticBold, syntheticItalic, subpixel, FreeType.FT_RENDER_MODE_NORMAL);
    }

    /**
     * 輪郭からの符号付き距離場（SDF）で 1 字を描く。{@link #rasterize} と同じ輪郭・位置（端数・擬似太字・擬似斜体）を使い、
     * 画素の値は「128 = 輪郭、内側ほど大きく外側ほど小さい」、1 画素の値は {@link #SDF_SPREAD} 画素の距離を 128 段階で表す。
     * 字の外側へ {@link #SDF_SPREAD} 画素ぶん広がるので、{@code left / top / width / height} は通常のものより大きい。
     * 縁取り（{@code -webkit-text-stroke}）や拡大縮小・回転に強い描画に使う。
     */
    public GlyphBitmap rasterizeSdf(int codePoint, float pixelSize, boolean syntheticBold, boolean syntheticItalic, int subpixel) {
        return render(codePoint, pixelSize, syntheticBold, syntheticItalic, subpixel, FreeType.FT_RENDER_MODE_SDF);
    }

    /** FreeType の SDF の既定の広がり（画素）。 */
    public static final int SDF_SPREAD = 8;

    private GlyphBitmap render(int codePoint, float pixelSize, boolean syntheticBold, boolean syntheticItalic, int subpixel, int mode) {
        synchronized (lock) {
            ensureOpen();
            return renderIndex(FreeType.FT_Get_Char_Index(face, codePoint), pixelSize, syntheticBold, syntheticItalic, subpixel, mode);
        }
    }

    /**
     * 字形番号で 1 字をラスタライズする（カラー字形のレイヤーなど、文字に結び付かない字形用）。
     * 擬似太字・擬似斜体は無し。
     */
    public GlyphBitmap rasterizeGlyphIndex(int glyphIndex, float pixelSize, int subpixel) {
        synchronized (lock) {
            ensureOpen();
            return renderIndex(glyphIndex, pixelSize, false, false, subpixel, FreeType.FT_RENDER_MODE_NORMAL);
        }
    }

    // ==========================================
    // 字形の輪郭（Canvas の回転した文字・縁取り文字）
    // ==========================================

    private final Map<Integer, com.nishiyu.enderframelib.core.raster.Path2D> outlineCache = new ConcurrentHashMap<>();

    /** 設計単位（1 em = {@link #unitsPerEm()}）。 */
    public int unitsPerEm() {
        return unitsPerEm;
    }

    /**
     * 字形番号の輪郭。座標は設計単位で y が上向き（ヒンティング・拡縮・{@code FT_Set_Transform} の影響を受けない）。
     * 2 次（TrueType）・3 次（CFF）の曲線を保ち、無い字形・空白は空のパス。返したパスは読み取り専用として扱う。
     */
    public com.nishiyu.enderframelib.core.raster.Path2D glyphOutline(int glyphIndex) {
        com.nishiyu.enderframelib.core.raster.Path2D cached = outlineCache.get(glyphIndex);
        if (cached != null) return cached;
        com.nishiyu.enderframelib.core.raster.Path2D p = loadOutline(glyphIndex);
        if (outlineCache.size() > 8192) outlineCache.clear();
        outlineCache.put(glyphIndex, p);
        return p;
    }

    private com.nishiyu.enderframelib.core.raster.Path2D loadOutline(int gi) {
        com.nishiyu.enderframelib.core.raster.Path2D path = new com.nishiyu.enderframelib.core.raster.Path2D();
        synchronized (lock) {
            ensureOpen();
            int flags = FreeType.FT_LOAD_NO_SCALE | FreeType.FT_LOAD_NO_HINTING | FreeType.FT_LOAD_NO_BITMAP
                    | FreeType.FT_LOAD_IGNORE_TRANSFORM;
            if (FreeType.FT_Load_Glyph(face, gi, flags) != 0) return path;
            FT_GlyphSlot slot = face.glyph();
            if (slot == null) return path;
            org.lwjgl.util.freetype.FT_Outline o = slot.outline();
            int nc = o.n_contours(), np = o.n_points();
            if (nc <= 0 || np <= 0) return path;
            org.lwjgl.util.freetype.FT_Vector.Buffer pts = o.points();
            ByteBuffer tags = o.tags();
            java.nio.ShortBuffer ends = o.contours();
            double[] x = new double[np], y = new double[np];
            boolean[] on = new boolean[np], cubic = new boolean[np];
            for (int i = 0; i < np; i++) {
                org.lwjgl.util.freetype.FT_Vector v = pts.get(i);
                x[i] = v.x();
                y[i] = v.y();
                int t = tags.get(i);
                on[i] = (t & 1) != 0;
                cubic[i] = (t & 1) == 0 && (t & 2) != 0;
            }
            int start = 0;
            for (int c = 0; c < nc; c++) {
                int end = ends.get(c) & 0xFFFF;
                if (end >= np) break;
                addContour(path, x, y, on, cubic, start, end);
                start = end + 1;
            }
        }
        return path;
    }

    /** 1 本の輪郭（{@code first..last}）をパスへ。off 曲線点が続くときの中点の補い方は TrueType の規則どおり。 */
    private static void addContour(com.nishiyu.enderframelib.core.raster.Path2D path, double[] x, double[] y, boolean[] on,
                                   boolean[] cubic, int first, int last) {
        int n = last - first + 1;
        if (n < 1) return;
        double sx, sy;
        int from, count;
        if (on[first]) {
            sx = x[first];
            sy = y[first];
            from = first + 1;
            count = n - 1;
        } else if (on[last]) {
            sx = x[last];
            sy = y[last];
            from = first;
            count = n - 1;
        } else {
            sx = (x[first] + x[last]) / 2;
            sy = (y[first] + y[last]) / 2;
            from = first;
            count = n;
        }
        path.moveTo(sx, sy);
        boolean hasConic = false;
        double qx = 0, qy = 0;
        int cubicPending = 0;
        double c1x = 0, c1y = 0, c2x = 0, c2y = 0;
        for (int k = 0; k < count; k++) {
            int i = from + k;
            if (on[i]) {
                if (cubicPending == 2) {
                    path.cubicTo(c1x, c1y, c2x, c2y, x[i], y[i]);
                    cubicPending = 0;
                } else if (hasConic) {
                    path.quadTo(qx, qy, x[i], y[i]);
                    hasConic = false;
                } else {
                    path.lineTo(x[i], y[i]);
                }
            } else if (cubic[i]) {
                if (cubicPending == 0) {
                    c1x = x[i];
                    c1y = y[i];
                    cubicPending = 1;
                } else {
                    c2x = x[i];
                    c2y = y[i];
                    cubicPending = 2;
                }
            } else {
                if (hasConic) {
                    double mx = (qx + x[i]) / 2, my = (qy + y[i]) / 2;
                    path.quadTo(qx, qy, mx, my);
                }
                qx = x[i];
                qy = y[i];
                hasConic = true;
            }
        }
        if (cubicPending == 2) path.cubicTo(c1x, c1y, c2x, c2y, sx, sy);
        else if (hasConic) path.quadTo(qx, qy, sx, sy);
        path.closePath();
    }

    // ==========================================
    // カラー字形（COLR v0 / CPAL）
    // ==========================================

    /**
     * カラー字形のレイヤー。下のレイヤーから順に重ねる。{@code foreground[i]} が true のレイヤーは
     * パレットの色ではなく文字の色（{@code color}）で塗る（COLR の 0xFFFF）。
     */
    public record ColorLayers(int[] glyphs, int[] argb, boolean[] foreground) {
        public int count() {
            return glyphs.length;
        }
    }

    private static final ColorLayers NO_COLOR = new ColorLayers(new int[0], new int[0], new boolean[0]);

    /**
     * COLR v1 またはカラービットマップ（{@code CBDT}/{@code sbix}）の絵文字を描いたビットマップ（事前乗算 ARGB）。
     * {@code left} は原点から左端、{@code top} は原点から上端の論理画素数（{@link GlyphBitmap} と同じ向き）、
     * {@code width}/{@code height} は描画先の大きさ。
     */
    public record ColorBitmap(com.nishiyu.enderframelib.core.raster.Pixmap pixmap, float left, float top, float width, float height) {
        public ColorBitmap(com.nishiyu.enderframelib.core.raster.Pixmap pixmap, int left, int top) {
            this(pixmap, left, top, pixmap.width(), pixmap.height());
        }
    }

    private record StrikeBitmap(com.nishiyu.enderframelib.core.raster.Pixmap pixmap, int left, int top, float strikeEm) {}

    private static final ColorBitmap NO_BITMAP = new ColorBitmap(new com.nishiyu.enderframelib.core.raster.Pixmap(1, 1), 0, 0);
    private static final StrikeBitmap NO_STRIKE = new StrikeBitmap(new com.nishiyu.enderframelib.core.raster.Pixmap(1, 1), 0, 0, 1f);
    /** (字形番号, 大きさ) → v1 / ビットマップ絵文字（描けない・持たない字形は {@link #NO_BITMAP}）。 */
    private final Map<Long, ColorBitmap> colorBitmapCache = new ConcurrentHashMap<>();
    /** (字形番号, ストライク番号) → 元のカラービットマップ（同じストライクを異なる大きさで共有）。 */
    private final Map<Long, StrikeBitmap> strikeBitmapCache = new ConcurrentHashMap<>();

    /**
     * {@code codePoint} が COLR v1 のペイント、またはカラービットマップ（{@code CBDT}/{@code sbix}）を持つなら、
     * {@code pixelSize}（em の画素数）で描いたビットマップ。COLR v0 のレイヤーがあるときは v1 だけを返し、
     * 無ければビットマップストライクを試す（v0・単色より優先順: COLR v1 → COLR v0 → ビットマップ → 単色）。
     */
    public ColorBitmap colorBitmap(int codePoint, float pixelSize) {
        int gid = glyphId(codePoint);
        if (gid == 0) return null;
        long key = ((long) gid << 32) | (Float.floatToIntBits(pixelSize) & 0xFFFFFFFFL);
        ColorBitmap c = colorBitmapCache.get(key);
        if (c == null) {
            ColorBitmap rendered;
            synchronized (lock) {
                ensureOpen();
                rendered = ColrV1Painter.render(this, face, gid, pixelSize);
            }
            if (rendered == null && colorLayers(codePoint) == null) {
                synchronized (lock) {
                    ensureOpen();
                    rendered = renderBitmapStrike(gid, pixelSize);
                }
            }
            c = rendered != null ? rendered : NO_BITMAP;
            if (colorBitmapCache.size() > 4096) colorBitmapCache.clear();
            colorBitmapCache.put(key, c);
        }
        return c == NO_BITMAP ? null : c;
    }

    /** {@code CBDT}/{@code CBLC}・{@code sbix} のカラービットマップストライクを最適な大きさで読み込み、{@code pixelSize} へ縮尺する。 */
    private ColorBitmap renderBitmapStrike(int gid, float pixelSize) {
        if (!(pixelSize > 0f) || pixelSize > 4096f) return null;
        if ((face.face_flags() & FreeType.FT_FACE_FLAG_COLOR) == 0) return null;
        int count = face.num_fixed_sizes();
        var sizes = face.available_sizes();
        if (sizes == null || count <= 0) return null;

        int bestIdx = -1;
        float bestEm = 0f;
        for (int i = 0; i < count; i++) {
            float em = strikeEm(sizes.get(i));
            if (em <= 0f) continue;
            if (bestIdx < 0) {
                bestIdx = i;
                bestEm = em;
            } else if (em >= pixelSize) {
                if (bestEm < pixelSize || em < bestEm) {
                    bestIdx = i;
                    bestEm = em;
                }
            } else if (bestEm < pixelSize && em > bestEm) {
                bestIdx = i;
                bestEm = em;
            }
        }
        if (bestIdx < 0) return null;

        long strikeKey = ((long) gid << 32) | (bestIdx & 0xFFFFFFFFL);
        StrikeBitmap sb = strikeBitmapCache.get(strikeKey);
        if (sb == null) {
            sb = loadStrikeBitmap(gid, bestIdx, bestEm);
            if (strikeBitmapCache.size() > 4096) strikeBitmapCache.clear();
            strikeBitmapCache.put(strikeKey, sb);
        }
        if (sb == NO_STRIKE) return null;
        float scale = pixelSize / sb.strikeEm();
        return new ColorBitmap(sb.pixmap(), sb.left() * scale, sb.top() * scale,
                sb.pixmap().width() * scale, sb.pixmap().height() * scale);
    }

    private StrikeBitmap loadStrikeBitmap(int gid, int strikeIdx, float strikeEm) {
        if (FreeType.FT_Select_Size(face, strikeIdx) != 0) return NO_STRIKE;
        currentCharSize = -1;
        if (currentOblique || currentSubpixel != 0) {
            FreeType.FT_Set_Transform(face, null, null);
            currentOblique = false;
            currentSubpixel = 0;
        }
        if (FreeType.FT_Load_Glyph(face, gid, FreeType.FT_LOAD_COLOR) != 0) return NO_STRIKE;
        FT_GlyphSlot slot = face.glyph();
        if (slot == null) return NO_STRIKE;
        if (slot.format() != FreeType.FT_GLYPH_FORMAT_BITMAP
                && FreeType.FT_Render_Glyph(slot, FreeType.FT_RENDER_MODE_NORMAL) != 0) {
            return NO_STRIKE;
        }
        FT_Bitmap bitmap = slot.bitmap();
        if (bitmap.pixel_mode() != FreeType.FT_PIXEL_MODE_BGRA) return NO_STRIKE;
        int w = bitmap.width();
        int h = bitmap.rows();
        if (w <= 0 || h <= 0) return NO_STRIKE;
        int pitch = bitmap.pitch();
        int absPitch = Math.abs(pitch);
        ByteBuffer buf = bitmap.buffer(absPitch * h);
        com.nishiyu.enderframelib.core.raster.Pixmap pm = new com.nishiyu.enderframelib.core.raster.Pixmap(w, h);
        for (int y = 0; y < h; y++) {
            int rowBase = (pitch >= 0 ? y : (h - 1 - y)) * absPitch;
            for (int x = 0; x < w; x++) {
                int off = rowBase + x * 4;
                int b = buf.get(off) & 0xFF;
                int g = buf.get(off + 1) & 0xFF;
                int r = buf.get(off + 2) & 0xFF;
                int a = buf.get(off + 3) & 0xFF;
                pm.set(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return new StrikeBitmap(pm, slot.bitmap_left(), slot.bitmap_top(), strikeEm);
    }
    /** 字形番号 → レイヤー（カラー字形でなければ {@link #NO_COLOR}）。 */
    private final Map<Integer, ColorLayers> colorCache = new ConcurrentHashMap<>();

    /** {@code codePoint} がカラー字形（COLR v0 のレイヤーを持つ）ならそのレイヤー、そうでなければ null。 */
    public ColorLayers colorLayers(int codePoint) {
        int gid = glyphId(codePoint);
        if (gid == 0) return null;
        ColorLayers c = colorCache.get(gid);
        if (c == null) {
            c = loadColorLayers(gid);
            colorCache.put(gid, c);
        }
        return c == NO_COLOR ? null : c;
    }

    private ColorLayers loadColorLayers(int gid) {
        synchronized (lock) {
            ensureOpen();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                org.lwjgl.util.freetype.FT_Palette_Data data = org.lwjgl.util.freetype.FT_Palette_Data.malloc(stack);
                if (FreeType.FT_Palette_Data_Get(face, data) != 0 || data.num_palettes() == 0) return NO_COLOR;
                PointerBuffer paletteOut = stack.mallocPointer(1);
                if (FreeType.FT_Palette_Select(face, (short) 0, paletteOut) != 0 || paletteOut.get(0) == 0) return NO_COLOR;
                int entries = data.num_palette_entries() & 0xFFFF;
                org.lwjgl.util.freetype.FT_Color.Buffer palette = org.lwjgl.util.freetype.FT_Color.create(paletteOut.get(0), entries);

                java.nio.IntBuffer glyph = stack.mallocInt(1);
                java.nio.IntBuffer colorIndex = stack.mallocInt(1);
                org.lwjgl.util.freetype.FT_LayerIterator it = org.lwjgl.util.freetype.FT_LayerIterator.calloc(stack);
                java.util.ArrayList<int[]> layers = new java.util.ArrayList<>();
                while (FreeType.FT_Get_Color_Glyph_Layer(face, gid, glyph, colorIndex, it)) {
                    int ci = colorIndex.get(0) & 0xFFFF;
                    int argb = 0;
                    boolean fg = ci == 0xFFFF;
                    if (!fg) {
                        if (ci >= entries) continue;
                        org.lwjgl.util.freetype.FT_Color c = palette.get(ci);
                        argb = ((c.alpha() & 0xFF) << 24) | ((c.red() & 0xFF) << 16) | ((c.green() & 0xFF) << 8) | (c.blue() & 0xFF);
                    }
                    layers.add(new int[]{glyph.get(0), argb, fg ? 1 : 0});
                    if (layers.size() > 512) break;
                }
                if (layers.isEmpty()) return NO_COLOR;
                int n = layers.size();
                int[] glyphs = new int[n], colors = new int[n];
                boolean[] fgs = new boolean[n];
                for (int i = 0; i < n; i++) {
                    int[] l = layers.get(i);
                    glyphs[i] = l[0];
                    colors[i] = l[1];
                    fgs[i] = l[2] != 0;
                }
                return new ColorLayers(glyphs, colors, fgs);
            }
        }
    }

    /** {@link #lock} の中で呼ぶ。 */
    private GlyphBitmap renderIndex(int gi, float pixelSize, boolean syntheticBold, boolean syntheticItalic, int subpixel, int mode) {
        {
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
            if (FreeType.FT_Render_Glyph(slot, mode) != 0) return GlyphBitmap.EMPTY;
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
