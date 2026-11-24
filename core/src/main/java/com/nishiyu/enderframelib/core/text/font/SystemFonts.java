package com.nishiyu.enderframelib.core.text.font;

import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * システムに入っている書体を CSS の {@code font-family} で引く（ブラウザがローカルの書体を使うのと同じ）。
 *
 * <ul>
 *   <li>一覧: OS の書体フォルダの面をファミリ名から引けるようにする（最初に使われたときに 1 度だけ）。
 *       全ファイルを開く走査（{@link FontScanner}。Windows の 500 ファイル程度で 250〜400 ms）は、ディスクの目録
 *       （{@link FontCatalog}）がフォルダの今の状態と合っていれば飛ばす。</li>
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
                       int weight, boolean italic, int widthClass, MemoryFont data) {

        /** ファイルから読む書体（OS の書体）。 */
        public Face(Path path, long faceIndex, Set<String> familyNames, String displayName,
                    int weight, boolean italic, int widthClass) {
            this(path, faceIndex, familyNames, displayName, weight, italic, widthClass, null);
        }
    }

    /**
     * メモリ上の書体ファイル（{@code @font-face} で読み込んだもの）。同じ実体かどうかは参照で見分ける
     * （配列の中身を比べない）ので、{@link Face} を {@link java.util.Map} の鍵にしても高くつかない。
     */
    public static final class MemoryFont {
        private final byte[] bytes;

        public MemoryFont(byte[] bytes) {
            this.bytes = bytes;
        }

        public byte[] bytes() {
            return bytes;
        }
    }

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
    private volatile int cachedWebVersion = WebFonts.version();

    public SystemFonts(List<Face> faces, Locale locale) {
        this(index(faces), locale);
    }

    private SystemFonts(Map<String, List<Face>> byFamily, Locale locale) {
        this.byFamily = byFamily;
        this.japanese = locale != null && "ja".equals(locale.getLanguage());
    }

    /** 持っている面の全部（同じ面が複数のファミリ名で引かれても 1 回だけ）。 */
    public List<Face> faces() {
        java.util.Set<Face> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        List<Face> out = new ArrayList<>();
        for (List<Face> list : byFamily.values()) {
            for (Face f : list) if (seen.add(f)) out.add(f);
        }
        return out;
    }

    /**
     * {@code extra} の面を足した別の目録を返す（この目録は変えない）。{@code extra} と同じファミリ名の既存の面は外す
     * （足した側が先: CSS Fonts 4 §4.1 の {@code @font-face} と同じ優先）。
     */
    public SystemFonts withFaces(List<Face> extra) {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Face f : extra) names.addAll(f.familyNames());
        List<Face> merged = new ArrayList<>();
        for (Face f : faces()) {
            boolean shadowed = false;
            for (String n : f.familyNames()) if (names.contains(n)) { shadowed = true; break; }
            if (!shadowed) merged.add(f);
        }
        merged.addAll(extra);
        return new SystemFonts(merged, japanese ? Locale.JAPANESE : Locale.ENGLISH);
    }

    /** OS の書体と OS の言語の既定（Chrome の既定の書体設定と同じ言語の値）。 */
    public static SystemFonts system() {
        SystemFonts s = system;
        if (s == null) {
            synchronized (SystemFonts.class) {
                s = system;
                if (s == null) {
                    s = load(FontScanner.defaultDirectories(), FontCatalog.defaultLocation(), Locale.getDefault());
                    system = s;
                }
            }
        }
        return s;
    }

    /**
     * {@code roots} の書体から目録を作る。{@code catalog} の目録がフォルダの今の状態と合っていれば走査せずにそれを読み、
     * 合っていなければ走査して目録を書き直す（{@link FontCatalog}）。{@code catalog} が null なら毎回走査する。
     */
    public static SystemFonts load(List<Path> roots, Path catalog, Locale locale) {
        return new SystemFonts(FontCatalog.load(roots, catalog).faces(), locale);
    }

    private static volatile Thread warmUp;

    /**
     * 書体の一覧の走査（Windows の 500 ファイル程度で数十〜百数十ミリ秒）を裏のスレッドで始める。
     * 初めて文字を測るフレームで止まらないよう、クライアントの起動時に呼ぶ。何度呼んでも走査は 1 回だけ。
     */
    public static void warmUpAsync() {
        if (system != null || warmUp != null) return;
        synchronized (SystemFonts.class) {
            if (system != null || warmUp != null) return;
            Thread t = new Thread(() -> {
                try {
                    system().resolve(null);
                } catch (Throwable ignored) {
                    // 走査に失敗しても、初めて測るときに同じ処理が走り、そこで報告される
                }
            }, "EnderFrame-FontScan");
            t.setDaemon(true);
            warmUp = t;
            t.start();
        }
    }

    /** 同じ書体の一覧で、総称ファミリの割り当てだけ別の言語にしたもの。 */
    public SystemFonts withLocale(Locale locale) {
        return new SystemFonts(byFamily, locale);
    }

    // ---- 解決

    @Override
    public FontFace resolve(String fontFamily) {
        int webVersion = WebFonts.version();
        if (webVersion != cachedWebVersion) {
            cache.clear();
            cachedWebVersion = webVersion;
        }
        return cache.computeIfAbsent(fontFamily == null ? "" : fontFamily, this::create);
    }

    /** {@link #clearResolved} の回数。{@link #resolveGeneration} に入れて、覚えを捨てたら外の覚えも外れるようにする。 */
    private final java.util.concurrent.atomic.AtomicInteger clears = new java.util.concurrent.atomic.AtomicInteger();

    /** {@code @font-face} の登録の版と、覚えを捨てた回数の組。どちらかが進むと同じ名前でも別の書体を返しうる。 */
    @Override
    public long resolveGeneration() {
        return ((long) (WebFonts.version() & 0x7FFF_FFFF) << 32) | (clears.get() & 0xFFFF_FFFFL);
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
        if (byFamily.isEmpty() && !WebFonts.any()) return FontFace.FALLBACK;
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
        String key = name.trim().toLowerCase(Locale.ROOT);
        List<Face> web = WebFonts.family(key);
        if (!web.isEmpty()) return web;
        return byFamily.getOrDefault(key, List.of());
    }

    public boolean hasFamily(String name) {
        return !family(name).isEmpty();
    }

    public boolean isEmpty() {
        return byFamily.isEmpty() && !WebFonts.any();
    }

    /**
     * 解決結果の覚えを捨てる。{@code @font-face} の登録が変わると同じ {@code font-family} の指す書体が変わるので、
     * 登録の版が進んだことに気づいたときに呼ぶ（{@link #resolve} が自分で確かめる）。
     */
    public void clearResolved() {
        // 先に捨ててから世代を進める（逆だと、新しい世代を読んだ側が捨てる前の古い書体を覚えうる）
        cache.clear();
        clears.incrementAndGet();
    }

    private static Map<String, List<Face>> index(List<Face> faces) {
        Map<String, List<Face>> map = new HashMap<>();
        for (Face face : faces) {
            for (String name : face.familyNames()) map.computeIfAbsent(name, k -> new ArrayList<>()).add(face);
        }
        map.replaceAll((k, v) -> List.copyOf(v));
        return Collections.unmodifiableMap(map);
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
