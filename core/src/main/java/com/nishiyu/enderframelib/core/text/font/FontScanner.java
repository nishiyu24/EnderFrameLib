package com.nishiyu.enderframelib.core.text.font;

import com.nishiyu.enderframelib.core.text.font.SystemFonts.Face;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FT_MM_Var;
import org.lwjgl.util.freetype.FT_SfntName;
import org.lwjgl.util.freetype.FT_Var_Axis;
import org.lwjgl.util.freetype.FT_Var_Named_Style;
import org.lwjgl.util.freetype.FreeType;
import org.lwjgl.util.freetype.TT_OS2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * OS の書体フォルダを走査し、書体ファイルを 1 つずつ FreeType で開いて {@link Face} の一覧を作る
 * （Windows の 500 ファイル程度で 250〜400 ms）。結果は {@link FontCatalog} がディスクへ書き、次の起動では
 * フォルダが変わっていなければ走査を飛ばす。
 *
 * <p>走査の結果は、一覧と一緒に「中身を列挙したフォルダとその更新時刻」を返す。フォルダの更新時刻は中の項目の
 * 追加・削除・改名で変わるので、目録が古いかどうかを全ファイルを開かずに判定できる（fontconfig のキャッシュと同じ判定）。
 */
public final class FontScanner {

    /** フォルダを掘る深さ（根を 0 として、この深さの項目までを見る）。 */
    static final int MAX_DEPTH = 4;

    /**
     * 走査の規則の版。面の読み方（ファミリ名・太さ・可変フォントの扱い）を変えたら上げる。
     * 目録はこの版が一致するときだけ使う。
     */
    static final int RULES_VERSION = 1;

    private FontScanner() { }

    /**
     * 中身を列挙したフォルダとその更新時刻（ナノ秒、フォルダが無ければ {@link #MISSING}）。
     * 根のフォルダは無くても記録する（後で作られたら目録を捨てる）。
     */
    public record DirStamp(Path dir, long modified) {}

    /** フォルダが無いときの {@link DirStamp#modified}。 */
    public static final long MISSING = Long.MIN_VALUE;

    /** 走査の結果。{@code faces} は根の順、根の中はパスの昇順（同じ入力なら毎回同じ順）。 */
    public record Result(List<Face> faces, List<DirStamp> dirs) {
        public Result {
            faces = List.copyOf(faces);
            dirs = List.copyOf(dirs);
        }
    }

    /** OS ごとの書体フォルダ（利用者ごとの書体フォルダを含む）。 */
    public static List<Path> defaultDirectories() {
        List<Path> dirs = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", "");
        if (os.contains("win")) {
            String windir = System.getenv().getOrDefault("WINDIR", "C:\\Windows");
            dirs.add(Path.of(windir, "Fonts"));
            String local = System.getenv("LOCALAPPDATA");
            if (local != null) dirs.add(Path.of(local, "Microsoft", "Windows", "Fonts"));
        } else if (os.contains("mac")) {
            dirs.add(Path.of("/System/Library/Fonts"));
            dirs.add(Path.of("/Library/Fonts"));
            dirs.add(Path.of(home, "Library", "Fonts"));
        } else {
            dirs.add(Path.of("/usr/share/fonts"));
            dirs.add(Path.of("/usr/local/share/fonts"));
            dirs.add(Path.of(home, ".fonts"));
            dirs.add(Path.of(home, ".local", "share", "fonts"));
        }
        return dirs;
    }

    /** フォルダ（下位フォルダを含む）の書体ファイルをすべて読む。読めないファイル・フォルダは飛ばす。 */
    public static Result scan(List<Path> roots) {
        List<Face> faces = new ArrayList<>();
        List<DirStamp> dirs = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                dirs.add(new DirStamp(root, MISSING));
                continue;
            }
            List<Path> files = new ArrayList<>();
            walk(root, files, dirs);
            Collections.sort(files);
            for (Path p : files) readFile(p, faces);
        }
        return new Result(faces, dirs);
    }

    /** {@code dir} の今の更新時刻（{@link DirStamp#modified} と同じ単位）。フォルダでなければ {@link #MISSING}。 */
    static long stamp(Path dir) {
        try {
            BasicFileAttributes a = Files.readAttributes(dir, BasicFileAttributes.class);
            return a.isDirectory() ? nanos(a.lastModifiedTime()) : MISSING;
        } catch (IOException | RuntimeException e) {
            return MISSING;
        }
    }

    /** ファイル（フォルダでなくてよい）の今の更新時刻。読めなければ {@link #MISSING}。 */
    static long fileStamp(Path file) {
        try {
            return nanos(Files.getLastModifiedTime(file));
        } catch (IOException | RuntimeException e) {
            return MISSING;
        }
    }

    private static long nanos(FileTime t) {
        java.time.Instant i = t.toInstant();
        return Math.addExact(Math.multiplyExact(i.getEpochSecond(), 1_000_000_000L), i.getNano());
    }

    private static void walk(Path root, List<Path> files, List<DirStamp> dirs) {
        try {
            Files.walkFileTree(root, EnumSet.noneOf(java.nio.file.FileVisitOption.class), MAX_DEPTH,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            // 列挙の前に時刻を採る。列挙の後に足されたファイルは、次の起動で時刻の違いとして見つかる
                            dirs.add(new DirStamp(dir, nanos(attrs.lastModifiedTime())));
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (!attrs.isDirectory() && isFontFile(file)) files.add(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE; // 読めない項目は無いものとして扱う
                        }
                    });
        } catch (IOException | RuntimeException e) {
            // 根が読めなければ、その根の書体は無いものとして扱う（それまでに集めた分は使う）
        }
    }

    private static boolean isFontFile(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc") || n.endsWith(".otc");
    }

    // ---- 1 ファイルの読み取り

    private static final int NAME_FAMILY = 1;
    private static final int NAME_TYPOGRAPHIC_FAMILY = 16;
    private static final long TAG_WGHT = tag("wght");
    private static final long TAG_ITAL = tag("ital");

    private static void readFile(Path path, List<Face> out) {
        FT_Face first = LoadedFace.openFace(path, 0);
        if (first == null) return;
        long count;
        synchronized (LoadedFace.LOCK) {
            count = first.num_faces();
        }
        LoadedFace.closeFace(first);
        for (int i = 0; i < count; i++) {
            FT_Face face = LoadedFace.openFace(path, i);
            if (face == null) continue;
            try {
                synchronized (LoadedFace.LOCK) {
                    readFace(path, i, face, out);
                }
            } finally {
                LoadedFace.closeFace(face);
            }
        }
    }

    private static void readFace(Path path, int index, FT_Face face, List<Face> out) {
        if (!LoadedFace.isUsableFace(face)) return;
        Set<String> names = familyNames(face);
        if (names.isEmpty()) return;

        TT_OS2 os2 = TT_OS2.createSafe(FreeType.FT_Get_Sfnt_Table(face, FreeType.FT_SFNT_OS2));
        int weight = os2 != null ? Short.toUnsignedInt(os2.usWeightClass()) : 400;
        if (weight < 1 || weight > 1000) weight = 400;
        int widthClass = os2 != null ? Short.toUnsignedInt(os2.usWidthClass()) : 5;
        boolean italic = (face.style_flags() & FreeType.FT_STYLE_FLAG_ITALIC) != 0
                || (os2 != null && (os2.fsSelection() & (1 | 1 << 9)) != 0);
        String display = face.family_nameString() + " " + face.style_nameString();

        if ((face.face_flags() & FreeType.FT_FACE_FLAG_MULTIPLE_MASTERS) != 0
                && addNamedInstances(path, index, face, names, widthClass, italic, out)) {
            return;
        }
        out.add(new Face(path, index, names, display, weight, italic, widthClass));
    }

    /**
     * 可変フォントは名前付きインスタンス（Thin〜Black）を別々の面として載せる。
     * Chrome（Windows）も可変フォントをこの単位で照合する（600 を指定しても SemiBold が無ければ Bold になる）。
     */
    private static boolean addNamedInstances(Path path, int index, FT_Face face, Set<String> names,
                                             int widthClass, boolean italic, List<Face> out) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pmm = stack.mallocPointer(1);
            if (FreeType.FT_Get_MM_Var(face, pmm) != 0) return false;
            FT_MM_Var mm = FT_MM_Var.create(pmm.get(0));
            try {
                int axes = mm.num_axis();
                int styles = mm.num_namedstyles();
                if (styles <= 0) return false;
                int wghtAxis = -1, italAxis = -1;
                FT_Var_Axis.Buffer axis = mm.axis();
                for (int a = 0; a < axes; a++) {
                    if (axis.get(a).tag() == TAG_WGHT) wghtAxis = a;
                    if (axis.get(a).tag() == TAG_ITAL) italAxis = a;
                }
                FT_Var_Named_Style.Buffer named = mm.namedstyle();
                for (int s = 0; s < styles; s++) {
                    var coords = named.get(s).coords(axes);
                    int weight = wghtAxis >= 0 ? Math.round(coords.get(wghtAxis) / 65536f) : 400;
                    boolean instItalic = italic || (italAxis >= 0 && coords.get(italAxis) >= 65536);
                    out.add(new Face(path, ((long) (s + 1) << 16) | index, names,
                            face.family_nameString() + " #" + (s + 1), weight, instItalic, widthClass));
                }
                return true;
            } finally {
                FreeType.FT_Done_MM_Var(LoadedFace.library(), mm);
            }
        }
    }

    private static Set<String> familyNames(FT_Face face) {
        Set<String> names = new LinkedHashSet<>();
        String ftFamily = face.family_nameString();
        if (ftFamily != null && !ftFamily.isBlank()) names.add(ftFamily.trim().toLowerCase(Locale.ROOT));
        int count = FreeType.FT_Get_Sfnt_Name_Count(face);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FT_SfntName name = FT_SfntName.malloc(stack);
            for (int i = 0; i < count; i++) {
                if (FreeType.FT_Get_Sfnt_Name(face, i, name) != 0) continue;
                int id = Short.toUnsignedInt(name.name_id());
                if (id != NAME_FAMILY && id != NAME_TYPOGRAPHIC_FAMILY) continue;
                String decoded = decode(name);
                if (decoded != null && !decoded.isBlank()) names.add(decoded.trim().toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private static String decode(FT_SfntName name) {
        int platform = Short.toUnsignedInt(name.platform_id());
        int encoding = Short.toUnsignedInt(name.encoding_id());
        ByteBuffer raw = name.string();
        byte[] bytes = new byte[name.string_len()];
        raw.get(0, bytes);
        if (platform == 0 || (platform == 3 && (encoding == 1 || encoding == 10))) {
            return new String(bytes, StandardCharsets.UTF_16BE);
        }
        if (platform == 1 && encoding == 0) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
        return null;
    }

    private static long tag(String s) {
        return ((long) s.charAt(0) << 24) | ((long) s.charAt(1) << 16) | ((long) s.charAt(2) << 8) | s.charAt(3);
    }
}
