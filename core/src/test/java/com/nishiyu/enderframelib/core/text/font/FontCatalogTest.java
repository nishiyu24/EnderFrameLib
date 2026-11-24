package com.nishiyu.enderframelib.core.text.font;

import com.nishiyu.enderframelib.core.text.font.FontScanner.DirStamp;
import com.nishiyu.enderframelib.core.text.font.SystemFonts.Face;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 書体の目録（Track P1）: 走査の結果をそのまま写し、フォルダが変わったら必ず捨てること。
 * 目録から作った {@link SystemFonts} が走査から作ったものと同じ面を同じ順で持つ（解決の結果が目録の有無で変わらない）ことを確かめる。
 */
class FontCatalogTest {

    @TempDir
    Path tmp;

    /** 走査の代わり: 根のフォルダの今の状態から決まった面を返し、呼ばれた回数を数える（FreeType を使わない）。 */
    private static final class FakeScanner implements Function<List<Path>, FontScanner.Result> {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public FontScanner.Result apply(List<Path> roots) {
            calls.incrementAndGet();
            List<Face> faces = new ArrayList<>();
            List<DirStamp> dirs = new ArrayList<>();
            for (Path root : roots) {
                dirs.add(new DirStamp(root, FontScanner.stamp(root)));
                if (!Files.isDirectory(root)) continue;
                try (Stream<Path> s = Files.list(root)) {
                    for (Path p : s.sorted().toList()) {
                        if (Files.isDirectory(p)) {
                            dirs.add(new DirStamp(p, FontScanner.stamp(p)));
                            continue;
                        }
                        String n = p.getFileName().toString();
                        faces.add(new Face(p, 0, new LinkedHashSet<>(List.of(n.toLowerCase(Locale.ROOT), "alias " + n)),
                                n + " Regular", 400, false, 5));
                        faces.add(new Face(p, (1L << 16), new LinkedHashSet<>(List.of(n.toLowerCase(Locale.ROOT))),
                                n + " #1", 700, true, 4));
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            return new FontScanner.Result(faces, dirs);
        }
    }

    private Path fontsDir() throws IOException {
        Path d = Files.createDirectories(tmp.resolve("fonts"));
        Files.writeString(d.resolve("a.ttf"), "x");
        Files.writeString(d.resolve("b.otf"), "y");
        bump(d);
        return d;
    }

    @Test
    void secondLoadReadsTheCatalogAndYieldsTheSameFacesInTheSameOrder() throws IOException {
        Path fonts = fontsDir();
        List<Path> roots = List.of(fonts, tmp.resolve("missing-root"));
        Path catalog = tmp.resolve("cache").resolve(FontCatalog.FILE_NAME);
        FakeScanner scanner = new FakeScanner();

        FontCatalog.Loaded first = FontCatalog.load(roots, catalog, scanner);
        assertFalse(first.fromCatalog());
        assertTrue(Files.isRegularFile(catalog), "走査の後に目録を書く");

        FontCatalog.Loaded second = FontCatalog.load(roots, catalog, scanner);
        assertTrue(second.fromCatalog(), "フォルダが変わっていなければ目録を読む");
        assertEquals(1, scanner.calls.get(), "2 回目は走査しない");
        assertEquals(first.faces(), second.faces(), "目録は走査の結果を順序ごと写す");
        for (int i = 0; i < first.faces().size(); i++) {
            assertEquals(List.copyOf(first.faces().get(i).familyNames()), List.copyOf(second.faces().get(i).familyNames()),
                    "ファミリ名の並びも保つ");
        }
    }

    @Test
    void addingRemovingOrTouchingAFolderInvalidatesTheCatalog() throws IOException {
        Path fonts = fontsDir();
        List<Path> roots = List.of(fonts);
        Path catalog = tmp.resolve(FontCatalog.FILE_NAME);
        FakeScanner scanner = new FakeScanner();
        FontCatalog.load(roots, catalog, scanner);

        // 書体の追加（フォルダの更新時刻が変わる。時刻の分解能に依らないよう、明示的に動かす）
        Files.writeString(fonts.resolve("c.ttf"), "z");
        bump(fonts);
        FontCatalog.Loaded added = FontCatalog.load(roots, catalog, scanner);
        assertFalse(added.fromCatalog(), "書体を足したら走査し直す");
        assertTrue(added.faces().stream().anyMatch(f -> f.path().getFileName().toString().equals("c.ttf")));
        assertTrue(FontCatalog.load(roots, catalog, scanner).fromCatalog(), "書き直した目録は次に使える");

        // 書体の削除
        Files.delete(fonts.resolve("a.ttf"));
        bump(fonts);
        FontCatalog.Loaded removed = FontCatalog.load(roots, catalog, scanner);
        assertFalse(removed.fromCatalog());
        assertTrue(removed.faces().stream().noneMatch(f -> f.path().getFileName().toString().equals("a.ttf")));

        // 下位フォルダの中の変化（下位フォルダの時刻だけが変わる）
        Path sub = Files.createDirectories(fonts.resolve("sub"));
        bump(sub);
        bump(fonts);
        FontCatalog.load(roots, catalog, scanner);
        assertTrue(FontCatalog.load(roots, catalog, scanner).fromCatalog());
        bump(sub);
        assertFalse(FontCatalog.load(roots, catalog, scanner).fromCatalog(), "下位フォルダの変化でも捨てる");
    }

    @Test
    void aRootThatAppearsLaterInvalidatesTheCatalog() throws IOException {
        Path later = tmp.resolve("later");
        List<Path> roots = List.of(fontsDir(), later);
        Path catalog = tmp.resolve(FontCatalog.FILE_NAME);
        FakeScanner scanner = new FakeScanner();
        FontCatalog.load(roots, catalog, scanner);
        assertTrue(FontCatalog.load(roots, catalog, scanner).fromCatalog());

        Files.createDirectories(later);
        Files.writeString(later.resolve("d.ttf"), "w");
        FontCatalog.Loaded l = FontCatalog.load(roots, catalog, scanner);
        assertFalse(l.fromCatalog(), "無かった根のフォルダが作られたら走査し直す");
        assertTrue(l.faces().stream().anyMatch(f -> f.path().getFileName().toString().equals("d.ttf")));
    }

    @Test
    void differentRootsOrVersionsOrBrokenFilesAreNotUsed() throws IOException {
        Path fonts = fontsDir();
        Path catalog = tmp.resolve(FontCatalog.FILE_NAME);
        FakeScanner scanner = new FakeScanner();
        FontCatalog.load(List.of(fonts), catalog, scanner);

        assertNull(FontCatalog.read(catalog, List.of(fonts, tmp.resolve("other"))), "根の並びが違えば使わない");
        assertNull(FontCatalog.read(catalog, List.of()), "根の並びが違えば使わない");
        assertNotNull(FontCatalog.read(catalog, List.of(fonts)));

        String text = Files.readString(catalog, StandardCharsets.UTF_8);
        String header = text.substring(0, text.indexOf('\n')).strip();
        Files.writeString(catalog, text.replace(header,
                FontCatalog.MAGIC + "\t" + FontCatalog.FORMAT_VERSION + "\t" + (FontScanner.RULES_VERSION + 1)));
        assertNull(FontCatalog.read(catalog, List.of(fonts)), "走査の規則の版が違えば使わない");

        Files.writeString(catalog, text + "F\tp.ttf\tnot-a-number\t400\t0\t5\tdisplay\tname\n");
        assertNull(FontCatalog.read(catalog, List.of(fonts)), "壊れた行があれば使わない");
        Files.writeString(catalog, text + "X\tunknown record\n");
        assertNull(FontCatalog.read(catalog, List.of(fonts)), "知らない記録があれば使わない");
        Files.writeString(catalog, text.substring(0, text.indexOf("\nD\t") + 1));
        assertNull(FontCatalog.read(catalog, List.of(fonts)), "フォルダの記録が無ければ使わない（途中で切れた目録）");
        Files.writeString(catalog, text);
        assertNotNull(FontCatalog.read(catalog, List.of(fonts)));

        Files.writeString(catalog, "");
        assertNull(FontCatalog.read(catalog, List.of(fonts)));
        FontCatalog.Loaded rescanned = FontCatalog.load(List.of(fonts), catalog, scanner);
        assertFalse(rescanned.fromCatalog(), "壊れた目録は走査し直して書き直す");
        assertTrue(FontCatalog.load(List.of(fonts), catalog, scanner).fromCatalog());
    }

    @Test
    void namesWithTabsNewlinesAndBackslashesRoundTrip() throws IOException {
        Path fonts = Files.createDirectories(tmp.resolve("f"));
        bump(fonts);
        Path weird = fonts.resolve("we\\ird.ttf");
        List<Face> faces = List.of(new Face(weird, 3, new LinkedHashSet<>(List.of("tab\tname", "line\nname", "back\\slash", "メイリオ")),
                "disp\r\nlay", 350, true, 7));
        FontScanner.Result r = new FontScanner.Result(faces, List.of(new DirStamp(fonts, FontScanner.stamp(fonts))));
        Path catalog = tmp.resolve(FontCatalog.FILE_NAME);
        FontCatalog.write(catalog, List.of(fonts), r);
        List<Face> back = FontCatalog.read(catalog, List.of(fonts));
        assertEquals(faces, back);
        assertEquals(List.copyOf(faces.get(0).familyNames()), List.copyOf(back.get(0).familyNames()));
        for (String s : List.of("", "a", "\\", "\t", "a\\tb", "\\\\t", "x\ny\rz")) {
            assertEquals(List.of(s, s), FontCatalog.split(FontCatalog.escape(s) + "\t" + FontCatalog.escape(s)), s);
        }
    }

    @Test
    void noCatalogLocationMeansScanEveryTime() throws IOException {
        Path fonts = fontsDir();
        FakeScanner scanner = new FakeScanner();
        assertFalse(FontCatalog.load(List.of(fonts), null, scanner).fromCatalog());
        assertFalse(FontCatalog.load(List.of(fonts), null, scanner).fromCatalog());
        assertEquals(2, scanner.calls.get());
    }

    @Test
    void unwritableCatalogStillReturnsTheScan() throws IOException {
        Path fonts = fontsDir();
        Path blocker = Files.writeString(tmp.resolve("blocker"), "file, not a folder");
        FontCatalog.Loaded l = FontCatalog.load(List.of(fonts), blocker.resolve(FontCatalog.FILE_NAME), new FakeScanner());
        assertFalse(l.fromCatalog());
        assertEquals(4, l.faces().size(), "目録が書けなくても走査の結果は使う");
        try (Stream<Path> s = Files.list(tmp)) {
            assertTrue(s.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "一時ファイルを残さない");
        }
    }

    /**
     * 走査の直前・直後に変わったフォルダ（目録の書き込みから {@link FontCatalog#SETTLE_NANOS} 以内）は信用しない。
     * 時刻の分解能が粗いファイルシステム（HFS+ 1 秒・FAT 2 秒）では、走査と同じ時刻の刻みの中で足された書体が時刻を変えず、
     * 古い目録を使い続けるため（Linux の CI で見つけた、時刻の一致による見逃しと同じ形）。
     */
    @Test
    void foldersChangedRightBeforeTheCatalogWasWrittenAreNotTrusted() throws IOException {
        Path fonts = Files.createDirectories(tmp.resolve("fresh"));
        Files.writeString(fonts.resolve("a.ttf"), "x"); // 時刻は今（目録の書き込みの直前）
        List<Path> roots = List.of(fonts);
        Path catalog = tmp.resolve(FontCatalog.FILE_NAME);
        FakeScanner scanner = new FakeScanner();
        assertFalse(FontCatalog.load(roots, catalog, scanner).fromCatalog());
        assertFalse(FontCatalog.load(roots, catalog, scanner).fromCatalog(), "書き込みの直前に変わったフォルダの目録は使わない");
        bump(fonts); // 落ち着いたフォルダ
        assertFalse(FontCatalog.load(roots, catalog, scanner).fromCatalog());
        assertTrue(FontCatalog.load(roots, catalog, scanner).fromCatalog(), "落ち着いた後に書き直した目録は使う");
    }

    @Test
    void defaultLocationIsAPerUserCacheFile() {
        Path p = FontCatalog.defaultLocation();
        if (p != null) assertEquals(FontCatalog.FILE_NAME, p.getFileName().toString());
    }

    /**
     * 実際の書体ファイル（OS の書体を一時フォルダへ写したもの）で、走査と目録から作った {@link SystemFonts} の
     * 面と解決の結果が一致すること。OS に書体が無い環境では飛ばす。
     */
    @Test
    void realFontsResolveIdenticallyFromScanAndFromCatalog() throws IOException {
        Optional<Path> source = firstOsFont();
        assumeTrue(source.isPresent(), "OS の書体が無い");
        Path fonts = Files.createDirectories(tmp.resolve("real"));
        Files.copy(source.get(), fonts.resolve(source.get().getFileName()));
        bump(fonts);
        Path catalog = tmp.resolve(FontCatalog.FILE_NAME);
        List<Path> roots = List.of(fonts);

        FontCatalog.Loaded scanned = FontCatalog.load(roots, catalog);
        assumeTrue(!scanned.faces().isEmpty(), "写した書体が読めない");
        assertFalse(scanned.fromCatalog());
        FontCatalog.Loaded cached = FontCatalog.load(roots, catalog);
        assertTrue(cached.fromCatalog());
        assertEquals(scanned.faces(), cached.faces());
        assertEquals(scanned.faces(), FontScanner.scan(roots).faces(), "目録を経ない走査と同じ");

        SystemFonts a = new SystemFonts(scanned.faces(), Locale.ENGLISH);
        SystemFonts b = SystemFonts.load(roots, catalog, Locale.ENGLISH);
        Set<String> families = new LinkedHashSet<>();
        for (Face f : scanned.faces()) families.addAll(f.familyNames());
        for (String fam : families) {
            assertEquals(a.family(fam), b.family(fam), fam);
            for (int w : new int[] {100, 400, 600, 900}) {
                for (boolean it : new boolean[] {false, true}) {
                    assertEquals(SystemFonts.match(a.family(fam), w, it), SystemFonts.match(b.family(fam), w, it), fam + " " + w + " " + it);
                }
            }
        }
        // 同じファイルは同じ番号で開ける（目録のパスと面の番号がそのまま使える）
        Face first = cached.faces().get(0);
        var opened = LoadedFace.openFace(first.path(), first.faceIndex());
        assertNotNull(opened);
        LoadedFace.closeFace(opened);
    }

    @Test
    void systemUsesTheCatalogLoader() {
        // system() は目録の経路で作る（同じ JVM では 1 回だけ）。中身はこの環境の書体なので、同一性だけを見る
        assertSame(SystemFonts.system(), SystemFonts.system());
    }

    private static Optional<Path> firstOsFont() throws IOException {
        for (Path dir : FontScanner.defaultDirectories()) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.walk(dir, 3)) {
                Optional<Path> p = s.filter(Files::isRegularFile)
                        .filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ttf"))
                        .filter(f -> {
                            try {
                                return Files.size(f) < 2_000_000;
                            } catch (IOException e) {
                                return false;
                            }
                        })
                        .sorted().findFirst();
                if (p.isPresent()) return p;
            }
        }
        return Optional.empty();
    }

    private long bumps;

    /**
     * フォルダの更新時刻を、毎回違う過去の時刻（2020 年より前、10 秒刻み）へ動かす。時刻の分解能・切り捨てで前回と同じ値に
     * ならず（Linux では「今 + 5 秒」の切り捨てが前回の値と一致して変化を見逃した）、目録の書き込みの直前の変化
     * （{@link FontCatalog#SETTLE_NANOS}）にも当たらない。
     */
    private void bump(Path dir) throws IOException {
        bumps++;
        Files.setLastModifiedTime(dir, FileTime.fromMillis(1_577_836_800_000L - 10_000L * bumps));
    }
}
