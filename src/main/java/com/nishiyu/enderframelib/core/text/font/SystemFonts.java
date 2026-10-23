package com.nishiyu.enderframelib.core.text.font;

import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * システムに入っている書体を CSS の {@code font-family} で引く（ブラウザがローカルの書体を使うのと同じ）。
 *
 * <ul>
 *   <li>一覧: OS の書体フォルダを読み、ファミリ名から書体の面を引けるようにする（最初に使われたときに 1 度だけ。
 *       Windows の 500 ファイル程度で数十〜百数十ミリ秒）。</li>
 *   <li>照合: ファミリの中から太さ・斜体に最も近い面を選ぶ（CSS Fonts 4 §5.2）。</li>
 *   <li>総称ファミリ: {@code sans-serif} などを Chrome（Windows）の既定の書体に割り当てる（言語ごと）。</li>
 *   <li>解決: {@code font-family} の並びのうちインストールされている書体と代替書体から {@link OutlineFontFace} を作る。
 *       どれも無ければ標準フォント。システムに書体が 1 つも無ければ {@link FontFace#FALLBACK}。</li>
 * </ul>
 */
public final class SystemFonts implements FontResolver {

    /**
     * 書体の 1 面（ファイル内の書体番号、可変フォントなら名前付きインスタンス）。
     *
     * @param faceIndex   FreeType に渡す番号。可変フォントの名前付きインスタンスは {@code (n << 16) | 書体番号}
     * @param familyNames 照合に使うファミリ名（小文字）。名前テーブルの family（nameID 1）と
     *                    typographic family（nameID 16）を全言語分持つ（{@code メイリオ} でも {@code Meiryo} でも引ける）
     * @param weight      太さ（OS/2 usWeightClass、可変フォントのインスタンスは wght 軸の値）
     * @param widthClass  幅（OS/2 usWidthClass、5 が通常）
     */
    public record Face(Path path, long faceIndex, Set<String> familyNames, String displayName,
                       int weight, boolean italic, int widthClass) {}

    /** {@code font-family} の 1 つの名前。{@code generic} は総称ファミリのとき true（名前は小文字）。 */
    public record FamilyEntry(String name, boolean generic) {}

    /** 照合の結果と合成の要否。 */
    record Match(Face face, boolean syntheticBold, boolean syntheticItalic) {}

    /** {@code font-family} が無いときの書体（ブラウザの「標準フォント」）を表すキー。 */
    static final String STANDARD = "-standard";

    public static final Set<String> GENERIC_FAMILIES = Set.of(
            "serif", "sans-serif", "monospace", "cursive", "fantasy", "system-ui", "math", "emoji", "fangsong",
            "ui-serif", "ui-sans-serif", "ui-monospace", "ui-rounded");

    private static volatile SystemFonts system;

    private final Map<String, List<Face>> byFamily;
    private final boolean japanese;
    private final Map<String, FontFace> cache = new ConcurrentHashMap<>();

    public SystemFonts(List<Face> faces, Locale locale) {
        this(index(faces), locale);
    }

    private SystemFonts(Map<String, List<Face>> byFamily, Locale locale) {
        this.byFamily = byFamily;
        this.japanese = locale != null && "ja".equals(locale.getLanguage());
    }

    /** OS の書体と OS の言語の既定（Chrome の既定の書体設定と同じ言語の値）。 */
    public static SystemFonts system() {
        SystemFonts s = system;
        if (s == null) {
            synchronized (SystemFonts.class) {
                s = system;
                if (s == null) {
                    s = new SystemFonts(scan(defaultFontDirectories()), Locale.getDefault());
                    system = s;
                }
            }
        }
        return s;
    }

    /** 同じ書体の一覧で、総称ファミリの割り当てだけ別の言語にしたもの。 */
    public SystemFonts withLocale(Locale locale) {
        return new SystemFonts(byFamily, locale);
    }

    // ---- 解決

    @Override
    public FontFace resolve(String fontFamily) {
        return cache.computeIfAbsent(fontFamily == null ? "" : fontFamily, this::create);
    }

    /** {@code font-family} の名前（総称ファミリを含む）がこの環境で使えるか。 */
    public boolean isAvailable(FamilyEntry entry) {
        if (entry.generic()) {
            for (String c : genericCandidates(entry.name())) if (hasFamily(c)) return true;
            return false;
        }
        return hasFamily(entry.name());
    }

    private FontFace create(String fontFamily) {
        if (byFamily.isEmpty()) return FontFace.FALLBACK;
        List<String> families = new ArrayList<>();
        for (FamilyEntry e : parseFamilyList(fontFamily)) {
            if (e.generic()) {
                addFirstInstalled(families, genericCandidates(e.name()));
            } else if (hasFamily(e.name())) {
                families.add(e.name());
            }
        }
        // 並びのどれも無いときは標準フォント（CSS Fonts 4 §5.2 の手順 6: UA の既定）
        if (families.isEmpty()) addFirstInstalled(families, genericCandidates(STANDARD));

        List<String> fallbacks = new ArrayList<>();
        for (List<String> group : fallbackGroups()) addFirstInstalled(fallbacks, group);
        if (families.isEmpty() && fallbacks.isEmpty()) return FontFace.FALLBACK;
        return new OutlineFontFace(this, families, fallbacks);
    }

    private void addFirstInstalled(List<String> out, List<String> candidates) {
        for (String c : candidates) {
            if (hasFamily(c)) {
                if (!out.contains(c)) out.add(c);
                return;
            }
        }
    }

    // ---- 一覧

    /** ファミリ名（大文字小文字を区別しない）に属する面。無ければ空。 */
    public List<Face> family(String name) {
        if (name == null) return List.of();
        return byFamily.getOrDefault(name.trim().toLowerCase(Locale.ROOT), List.of());
    }

    public boolean hasFamily(String name) {
        return !family(name).isEmpty();
    }

    public boolean isEmpty() {
        return byFamily.isEmpty();
    }

    private static Map<String, List<Face>> index(List<Face> faces) {
        Map<String, List<Face>> map = new HashMap<>();
        for (Face face : faces) {
            for (String name : face.familyNames()) map.computeIfAbsent(name, k -> new ArrayList<>()).add(face);
        }
        map.replaceAll((k, v) -> List.copyOf(v));
        return Collections.unmodifiableMap(map);
    }

    /** OS ごとの書体フォルダ（利用者ごとの書体フォルダを含む）。 */
    public static List<Path> defaultFontDirectories() {
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

    /** フォルダ（下位フォルダを含む）の書体ファイルをすべて読む。読めないファイルは飛ばす。 */
    public static List<Face> scan(List<Path> directories) {
        List<Face> faces = new ArrayList<>();
        for (Path dir : directories) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.walk(dir, 4)) {
                files.filter(SystemFonts::isFontFile).sorted().forEach(p -> readFile(p, faces));
            } catch (IOException | RuntimeException ignored) {
                // 読めないフォルダは無いものとして扱う
            }
        }
        return faces;
    }

    private static boolean isFontFile(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc") || n.endsWith(".otc");
    }

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
        if ((face.face_flags() & FreeType.FT_FACE_FLAG_SCALABLE) == 0) return;
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

    // ---- 照合（CSS Fonts 4 §5.2 の手順 4）

    /**
     * 1 つのファミリの中から、指定の太さ・斜体に最も近い面を選ぶ。
     * 幅（font-stretch）は通常幅だけを扱うので、通常幅に最も近い面の集まりから選ぶ。
     * 照合した面に無い太さ・斜体は合成する（§5.3 / font-synthesis の初期値）。
     */
    static Match match(List<Face> family, int weight, boolean italic) {
        if (family == null || family.isEmpty()) return null;

        int bestWidth = family.stream().map(Face::widthClass)
                .min(Comparator.comparingInt(w -> Math.abs(w - 5))).orElse(5);
        List<Face> candidates = new ArrayList<>();
        for (Face f : family) if (f.widthClass() == bestWidth) candidates.add(f);

        // font-style: 斜体を求めるなら斜体の面、無ければ立体。立体を求めるなら立体、無ければ斜体
        List<Face> styled = new ArrayList<>();
        for (Face f : candidates) if (f.italic() == italic) styled.add(f);
        if (styled.isEmpty()) styled = candidates;

        Face chosen = matchWeight(styled, weight);
        return new Match(chosen, weight >= 600 && chosen.weight() < 600, italic && !chosen.italic());
    }

    /**
     * font-weight の照合。
     * 400〜500: 指定以上 500 までを昇順、次に指定未満を降順、次に 500 より上を昇順。
     * 400 未満: 指定以下を降順、次に指定より上を昇順。
     * 500 より上: 指定以上を昇順、次に指定未満を降順。
     */
    static Face matchWeight(List<Face> faces, int desired) {
        for (Face f : faces) if (f.weight() == desired) return f;
        if (desired >= 400 && desired <= 500) {
            Face up = closestAbove(faces, desired, 500);
            if (up != null) return up;
            Face down = closestBelow(faces, desired);
            if (down != null) return down;
            return closestAbove(faces, desired, Integer.MAX_VALUE);
        }
        if (desired < 400) {
            Face down = closestBelow(faces, desired);
            return down != null ? down : closestAbove(faces, desired, Integer.MAX_VALUE);
        }
        Face up = closestAbove(faces, desired, Integer.MAX_VALUE);
        return up != null ? up : closestBelow(faces, desired);
    }

    private static Face closestAbove(List<Face> faces, int desired, int limit) {
        Face best = null;
        for (Face f : faces) {
            if (f.weight() > desired && f.weight() <= limit && (best == null || f.weight() < best.weight())) best = f;
        }
        return best;
    }

    private static Face closestBelow(List<Face> faces, int desired) {
        Face best = null;
        for (Face f : faces) {
            if (f.weight() < desired && (best == null || f.weight() > best.weight())) best = f;
        }
        return best;
    }

    // ---- font-family の値（CSS Fonts 4 §2.1）

    /**
     * {@code font-family} の値を名前の並びに分ける。引用符で囲んだ名前はそのまま、囲まない名前は空白を 1 つに詰める。
     * 総称ファミリ（{@code sans-serif} など）は引用符で囲まないときだけ総称として扱う。
     */
    public static List<FamilyEntry> parseFamilyList(String value) {
        List<FamilyEntry> out = new ArrayList<>();
        if (value == null) return out;
        int i = 0;
        int n = value.length();
        while (i < n) {
            while (i < n && (Character.isWhitespace(value.charAt(i)) || value.charAt(i) == ',')) i++;
            if (i >= n) break;
            char c = value.charAt(i);
            if (c == '"' || c == '\'') {
                int end = value.indexOf(c, i + 1);
                if (end < 0) end = n;
                String name = value.substring(i + 1, end).trim();
                if (!name.isEmpty()) out.add(new FamilyEntry(name, false));
                i = end + 1;
                while (i < n && value.charAt(i) != ',') i++;
            } else {
                int end = value.indexOf(',', i);
                if (end < 0) end = n;
                String name = value.substring(i, end).trim().replaceAll("\\s+", " ");
                String lower = name.toLowerCase(Locale.ROOT);
                if (!name.isEmpty()) {
                    out.add(GENERIC_FAMILIES.contains(lower) ? new FamilyEntry(lower, true) : new FamilyEntry(name, false));
                }
                i = end;
            }
        }
        return out;
    }

    // ---- 総称ファミリ
    //
    // 値は Chrome（Windows）の既定の設定に合わせ、言語ごとに持つ。候補を順に試し、インストールされている最初のものを使う
    // （Windows 以外の書体も後ろに並べてある）。日本語の値は Chrome の DevTools（CSS.getPlatformFontsForNode）で
    // 確かめた実際の書体: 標準と sans-serif が Noto Sans JP、serif が Noto Serif JP、monospace が BIZ UDGothic、
    // system-ui が Yu Gothic UI、収録の無い字の代替は Noto Sans JP → Segoe UI Symbol。

    private static final List<String> SANS_TAIL = List.of("Helvetica", "Liberation Sans", "DejaVu Sans", "Noto Sans");
    private static final List<String> SERIF_TAIL = List.of("Times", "Liberation Serif", "DejaVu Serif", "Noto Serif");
    private static final List<String> MONO_TAIL = List.of("Courier New", "Menlo", "Liberation Mono", "DejaVu Sans Mono", "Noto Sans Mono");
    private static final List<String> SYSTEM_TAIL = List.of("Segoe UI", "Helvetica Neue", "Cantarell", "Ubuntu", "Noto Sans");

    private static final Map<String, List<String>> JAPANESE = Map.of(
            STANDARD, join(List.of("Noto Sans JP", "Meiryo", "Yu Gothic", "MS PGothic", "Hiragino Sans"), SANS_TAIL),
            "sans-serif", join(List.of("Noto Sans JP", "Meiryo", "Yu Gothic", "MS PGothic", "Hiragino Sans"), SANS_TAIL),
            "serif", join(List.of("Noto Serif JP", "Yu Mincho", "MS PMincho", "Hiragino Mincho ProN"), SERIF_TAIL),
            "monospace", join(List.of("BIZ UDGothic", "MS Gothic", "Osaka-Mono"), MONO_TAIL),
            "system-ui", join(List.of("Yu Gothic UI", "Meiryo UI"), SYSTEM_TAIL));

    private static final Map<String, List<String>> DEFAULT = Map.of(
            STANDARD, join(List.of("Times New Roman"), SERIF_TAIL),
            "sans-serif", join(List.of("Arial"), SANS_TAIL),
            "serif", join(List.of("Times New Roman"), SERIF_TAIL),
            "monospace", join(List.of("Consolas"), MONO_TAIL),
            "system-ui", join(List.of("Segoe UI"), SYSTEM_TAIL));

    private static final Map<String, List<String>> COMMON = Map.of(
            "cursive", List.of("Comic Sans MS", "Apple Chancery", "URW Chancery L"),
            "fantasy", List.of("Impact", "Papyrus"),
            "emoji", List.of("Segoe UI Emoji", "Apple Color Emoji", "Noto Color Emoji", "Noto Emoji"),
            "math", List.of("Cambria Math", "STIX Two Math", "Latin Modern Math"),
            "fangsong", List.of("FangSong", "STFangsong"));

    /** 日本語の字の代替。Chrome と同じく、インストールされている最初の 1 つだけを使う。 */
    private static final List<String> JAPANESE_FALLBACK = List.of(
            "Noto Sans JP", "Meiryo", "Yu Gothic", "MS Gothic", "Hiragino Sans", "Noto Sans CJK JP");
    /** 記号・他の言語の字の代替。字形を持つものを順に試す。 */
    private static final List<String> COMMON_FALLBACK = List.of(
            "Segoe UI Symbol", "Segoe UI Emoji", "Segoe UI", "Microsoft YaHei", "Microsoft JhengHei", "Malgun Gothic",
            "Nirmala UI", "Leelawadee UI", "Ebrima", "Gadugi", "Segoe UI Historic", "Arial Unicode MS",
            "Noto Sans", "Noto Sans Symbols", "Noto Sans Symbols 2", "DejaVu Sans", "Apple Symbols", "Arial");

    /** 総称ファミリ（または {@link #STANDARD}）の候補。知らない名前は空。 */
    List<String> genericCandidates(String generic) {
        String key = switch (generic) {
            case "ui-sans-serif" -> "sans-serif";
            case "ui-serif" -> "serif";
            case "ui-monospace" -> "monospace";
            case "ui-rounded" -> "system-ui";
            default -> generic;
        };
        List<String> byLocale = (japanese ? JAPANESE : DEFAULT).get(key);
        if (byLocale != null) return byLocale;
        return COMMON.getOrDefault(key, List.of());
    }

    /**
     * 指定のファミリに字形が無いときに試す書体（システムの代替書体）。グループの並びで、
     * 各グループからはインストールされている最初の 1 つだけを使う。
     * 日本語環境の Chrome は Noto Sans JP → Segoe UI Symbol の順に探す（☺ や ⚿ は Segoe UI Symbol になる）。
     */
    List<List<String>> fallbackGroups() {
        List<List<String>> groups = new ArrayList<>();
        if (japanese) groups.add(JAPANESE_FALLBACK);
        for (String f : COMMON_FALLBACK) groups.add(List.of(f));
        if (!japanese) groups.add(JAPANESE_FALLBACK);
        return groups;
    }

    private static List<String> join(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        for (String s : b) if (!out.contains(s)) out.add(s);
        return List.copyOf(out);
    }
}
