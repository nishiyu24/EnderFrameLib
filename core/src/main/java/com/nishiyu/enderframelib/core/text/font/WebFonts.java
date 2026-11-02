package com.nishiyu.enderframelib.core.text.font;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FreeType;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@code @font-face} で読み込んだ書体の登録簿。
 *
 * 登録した TTF / OTF は OS の書体と同じ {@link SystemFonts.Face} として {@link SystemFonts} の照合に載るので、
 * 測定（{@link OutlineFontFace}）も描画（{@code OutlineTextRenderer}）も OS の書体と同じ経路を通る。
 * これで OS に依らず（書体ファイルが同じなら）同じ寸法・同じ字形になる。
 * 登録した名前は同名の OS の書体より優先する（CSS Fonts 4 §4.1: {@code @font-face} が先）。
 */
public final class WebFonts {

    private static final Map<String, List<SystemFonts.Face>> BY_FAMILY = new ConcurrentHashMap<>();
    /** 登録・削除のたびに増える。書体の解決結果を覚えている側が、古くなったかを見分ける。 */
    private static volatile int version;

    private WebFonts() {}

    /**
     * 書体ファイルを登録する。FreeType が読めないファイルは登録せず false。
     *
     * @param family {@code @font-face} の {@code font-family}（大文字小文字を区別しない）
     * @param weight {@code font-weight} 記述子（範囲指定は呼び出し側が下限に直す）
     * @param italic {@code font-style} が {@code italic} / {@code oblique} か
     */
    public static boolean register(String family, byte[] data, int weight, boolean italic) {
        if (family == null || family.isBlank() || data == null || data.length == 0) return false;
        String name = family.trim().toLowerCase(Locale.ROOT);
        if (!probe(data)) return false;
        SystemFonts.Face face = new SystemFonts.Face(null, 0, Set.of(name), family.trim() + " (web)",
                Math.clamp(weight, 1, 1000), italic, 5, new SystemFonts.MemoryFont(data));
        List<SystemFonts.Face> faces = BY_FAMILY.computeIfAbsent(name, k -> new CopyOnWriteArrayList<>());
        // 同じ太さ・斜体の再登録は置き換える（ホットリロードで同じ @font-face が何度も来る）
        faces.removeIf(f -> f.weight() == face.weight() && f.italic() == face.italic());
        faces.add(face);
        version++;
        return true;
    }

    /** データが書体として開けるか。 */
    private static boolean probe(byte[] data) {
        synchronized (LoadedFace.LOCK) {
            ByteBuffer buf = MemoryUtil.memAlloc(data.length);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                buf.put(0, data);
                PointerBuffer out = stack.mallocPointer(1);
                if (FreeType.FT_New_Memory_Face(LoadedFace.library(), buf, 0, out) != 0) return false;
                FT_Face face = FT_Face.create(out.get(0));
                boolean scalable = (face.face_flags() & FreeType.FT_FACE_FLAG_SCALABLE) != 0;
                FreeType.FT_Done_Face(face);
                return scalable;
            } finally {
                MemoryUtil.memFree(buf);
            }
        }
    }

    /** 登録した面。無ければ空。 */
    static List<SystemFonts.Face> family(String lowerName) {
        List<SystemFonts.Face> faces = BY_FAMILY.get(lowerName);
        return faces == null ? List.of() : faces;
    }

    public static boolean has(String name) {
        return name != null && !family(name.trim().toLowerCase(Locale.ROOT)).isEmpty();
    }

    static boolean any() {
        return !BY_FAMILY.isEmpty();
    }

    public static int version() {
        return version;
    }

    /** 登録をすべて消す（資源の再読み込み、テスト）。 */
    public static void clear() {
        if (BY_FAMILY.isEmpty()) return;
        List<SystemFonts.Face> removed = new ArrayList<>();
        BY_FAMILY.values().forEach(removed::addAll);
        BY_FAMILY.clear();
        version++;
        LoadedFace.forget(removed);
    }
}
