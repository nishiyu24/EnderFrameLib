package com.nishiyu.enderframelib.core.text.font;

import com.nishiyu.enderframelib.core.text.font.FontScanner.DirStamp;
import com.nishiyu.enderframelib.core.text.font.SystemFonts.Face;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * OS の書体の目録をディスクに置き、起動のたびの全走査（{@link FontScanner#scan}。250〜400 ms）を飛ばす。
 *
 * <p>目録は走査の結果（面の一覧）と、走査で中身を列挙したフォルダの更新時刻を持つ。読むときは、
 * 走査の規則の版・根のフォルダの並びが一致し、記録した全フォルダの今の更新時刻が記録と一致するときだけ使う
 * （書体の追加・削除・改名はそのフォルダの更新時刻を変える）。それ以外は走査して書き直す。
 * 目録は走査の結果をそのまま写したものなので、目録から作った {@link SystemFonts} は走査から作ったものと
 * 同じ面を同じ順で持つ（解決の結果が目録の有無で変わらない）。
 *
 * <p>形式は 1 行 1 記録の UTF-8 の文字列（欄はタブ区切り、欄の中のタブ・改行・{@code \} は {@code \} で逃がす）:
 * <pre>
 * enderframe-font-catalog	{形式の版}	{走査の規則の版}
 * R	{根のフォルダ}                      … 根の数だけ、走査の順
 * D	{更新時刻（ナノ秒）}	{フォルダ}    … 中身を列挙したフォルダ（無い根は時刻 {@link FontScanner#MISSING}）
 * F	{パス}	{面の番号}	{太さ}	{斜体 0/1}	{幅}	{表示名}	{ファミリ名…}  … 面の数だけ、走査の順
 * </pre>
 */
public final class FontCatalog {

    static final String MAGIC = "enderframe-font-catalog";
    static final int FORMAT_VERSION = 1;
    static final String FILE_NAME = "font-catalog-v" + FORMAT_VERSION + ".txt";

    /**
     * フォルダの時刻が目録の書き込みのこれだけ前（ナノ秒）より新しければ、その目録を信用しない。ファイルシステムの時刻の分解能
     * （HFS+ 1 秒・FAT 2 秒）の中で、走査の直後に書体が足されても時刻が変わらず見逃すことを防ぐ（次の走査で書き直す）。
     * 比べるのはファイルの時刻どうしで、時計は読まない。
     */
    static final long SETTLE_NANOS = 2_000_000_000L;

    private FontCatalog() { }

    /** 目録の読み書きの結果。{@code fromCatalog} は目録を使った（走査しなかった）とき true。 */
    public record Loaded(List<Face> faces, boolean fromCatalog) {}

    /**
     * 目録が使えれば目録から、使えなければ走査して目録を書き直して、面の一覧を返す。
     * {@code catalog} が null なら目録を使わずに走査だけする。目録の書き込みの失敗は無視する（次の起動でまた走査する）。
     */
    public static Loaded load(List<Path> roots, Path catalog) {
        return load(roots, catalog, FontScanner::scan);
    }

    /** {@link #load(List, Path)} の走査を差し替えられる形（テスト用）。 */
    static Loaded load(List<Path> roots, Path catalog, Function<List<Path>, FontScanner.Result> scanner) {
        if (catalog != null) {
            List<Face> cached = read(catalog, roots);
            if (cached != null) return new Loaded(cached, true);
        }
        FontScanner.Result scanned = scanner.apply(roots);
        if (catalog != null) write(catalog, roots, scanned);
        return new Loaded(scanned.faces(), false);
    }

    /**
     * 目録の置き場所（利用者ごとのキャッシュのフォルダ）。決められなければ null（目録を使わない）。
     * Windows は {@code %LOCALAPPDATA%\EnderFrame}、macOS は {@code ~/Library/Caches/EnderFrame}、
     * それ以外は {@code $XDG_CACHE_HOME/enderframe}（無ければ {@code ~/.cache/enderframe}）。
     */
    public static Path defaultLocation() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", "");
        Path dir;
        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            if (local == null || local.isBlank()) return null;
            dir = Path.of(local, "EnderFrame");
        } else if (os.contains("mac")) {
            if (home.isBlank()) return null;
            dir = Path.of(home, "Library", "Caches", "EnderFrame");
        } else {
            String xdg = System.getenv("XDG_CACHE_HOME");
            if (xdg != null && !xdg.isBlank()) dir = Path.of(xdg, "enderframe");
            else if (!home.isBlank()) dir = Path.of(home, ".cache", "enderframe");
            else return null;
        }
        return dir.resolve(FILE_NAME);
    }

    // ---- 読む

    /** 目録を読む。無い・壊れている・古い（版・根・フォルダの時刻が違う）なら null。 */
    static List<Face> read(Path catalog, List<Path> roots) {
        if (!Files.isRegularFile(catalog)) return null;
        long written = FontScanner.fileStamp(catalog);
        if (written == FontScanner.MISSING) return null;
        try (BufferedReader in = Files.newBufferedReader(catalog, StandardCharsets.UTF_8)) {
            String header = in.readLine();
            if (!(MAGIC + "\t" + FORMAT_VERSION + "\t" + FontScanner.RULES_VERSION).equals(header)) return null;
            List<Path> recordedRoots = new ArrayList<>();
            List<Face> faces = new ArrayList<>();
            boolean sawDir = false;
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) continue;
                List<String> f = split(line);
                switch (f.get(0)) {
                    case "R" -> {
                        if (f.size() != 2) return null;
                        recordedRoots.add(Path.of(f.get(1)));
                    }
                    case "D" -> {
                        if (f.size() != 3) return null;
                        // 1 つでも時刻が違えば、残りを読まずに古いと判定する
                        long recorded = Long.parseLong(f.get(1));
                        if (FontScanner.stamp(Path.of(f.get(2))) != recorded) return null;
                        // 書き込みの直前に変わったフォルダは、分解能の中で後の変化を見逃しうるので信用しない
                        if (recorded != FontScanner.MISSING && recorded > written - SETTLE_NANOS) return null;
                        sawDir = true;
                    }
                    case "F" -> {
                        if (f.size() < 8) return null;
                        Set<String> names = new LinkedHashSet<>(f.subList(7, f.size()));
                        faces.add(new Face(Path.of(f.get(1)), Long.parseLong(f.get(2)), names, f.get(6),
                                Integer.parseInt(f.get(3)), "1".equals(f.get(4)), Integer.parseInt(f.get(5))));
                    }
                    default -> {
                        return null;
                    }
                }
            }
            if (!sawDir || !recordedRoots.equals(roots)) return null;
            return List.copyOf(faces);
        } catch (IOException | RuntimeException e) {
            return null; // 読めない・壊れた目録は無いものとして走査し直す
        }
    }

    // ---- 書く

    /** 目録を書く。途中の失敗で壊れた目録が残らないよう、同じフォルダの一時ファイルに書いてから置き換える。 */
    static void write(Path catalog, List<Path> roots, FontScanner.Result result) {
        Path tmp = null;
        try {
            Path dir = catalog.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            tmp = Files.createTempFile(dir, FILE_NAME, ".tmp");
            try (BufferedWriter out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                out.write(MAGIC + "\t" + FORMAT_VERSION + "\t" + FontScanner.RULES_VERSION);
                out.write('\n');
                for (Path r : roots) line(out, "R", r.toString());
                for (DirStamp d : result.dirs()) line(out, "D", Long.toString(d.modified()), d.dir().toString());
                for (Face f : result.faces()) {
                    if (f.path() == null) continue; // メモリ上の書体は目録に載せない（走査は作らない）
                    List<String> cols = new ArrayList<>(List.of("F", f.path().toString(), Long.toString(f.faceIndex()),
                            Integer.toString(f.weight()), f.italic() ? "1" : "0", Integer.toString(f.widthClass()),
                            f.displayName()));
                    cols.addAll(f.familyNames());
                    line(out, cols.toArray(String[]::new));
                }
            }
            try {
                Files.move(tmp, catalog, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, catalog, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
        } catch (IOException | RuntimeException e) {
            // 書けなくても走査の結果はそのまま使える。次の起動でまた走査する
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException e) {
                    // 一時ファイルが残っても目録として読まれることはない（名前が違う）
                }
            }
        }
    }

    private static void line(BufferedWriter out, String... cols) throws IOException {
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) out.write('\t');
            out.write(escape(cols[i]));
        }
        out.write('\n');
    }

    static String escape(String s) {
        StringBuilder b = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String rep = switch (c) {
                case '\\' -> "\\\\";
                case '\t' -> "\\t";
                case '\n' -> "\\n";
                case '\r' -> "\\r";
                default -> null;
            };
            if (rep == null) {
                if (b != null) b.append(c);
                continue;
            }
            if (b == null) b = new StringBuilder(s.length() + 8).append(s, 0, i);
            b.append(rep);
        }
        return b == null ? s : b.toString();
    }

    /** タブで欄に分け、逃がした文字を戻す。 */
    static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\t') {
                out.add(cur.toString());
                cur.setLength(0);
            } else if (c == '\\' && i + 1 < line.length()) {
                char n = line.charAt(++i);
                cur.append(switch (n) {
                    case 't' -> '\t';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    default -> n;
                });
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
