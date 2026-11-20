package com.nishiyu.enderframelib.wpt;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * WPT の参照画像比較（reftest）の部分集合（flexbox・grid・text の 260 組。選び方は {@code select_reftests.py}）を
 * ソフトウェア描画に通し、合格した組をラチェットで固定する（ENGINE_REARCHITECTURE_PLAN B1⑤・§1.4 条件 d）。
 *
 * <ul>
 *   <li>合格の一覧 {@code reftest-passing.txt} に載っている組が落ちたら回帰（失敗）。</li>
 *   <li>載っていない組が通ったら、一覧を締める（失敗。{@code -Dwpt.reftest.update=true} で一覧を作り直す）。</li>
 *   <li>「空白どうしで一致」は合格にしない（何も描けていないのを見逃さない）。</li>
 * </ul>
 * 描画は固定フォント（{@link FontResolver#FALLBACK}）・800×600・等倍・透明な地。許容差は {@code <meta name=fuzzy>} に従う（無ければ完全一致）。
 */
class WptReftestRatchetTest {
    private static final Path DIR = Path.of("src/test/resources/wpt/reftests");
    private static final Path MANIFEST = DIR.resolve("manifest.tsv");
    private static final Path PASSING = DIR.resolve("reftest-passing.txt");
    /** レイアウトが終わらない（エンジンの不具合）組。走らせない。直したら一覧から外す。 */
    private static final Path HANGS = DIR.resolve("reftest-hangs.txt");
    private static final int W = 800, H = 600;
    /**
     * レイアウトの書体。描画はシステム書体の字形で描くので、測定もシステム書体でないと、文字列の区切り方が違うテストと参照
     * （{@code un<span></span>bro} と {@code unbro}）で字の位置が食い違う（{@link FontResolver#FALLBACK} は概算の幅しか返さない）。
     */
    private static final FontResolver FONTS = com.nishiyu.enderframelib.core.text.font.SystemFonts.system();
    private static final long T0 = 10_000L;
    private static final Pattern FUZZY = Pattern.compile("<meta[^>]+name=[\"']?fuzzy[\"']?[^>]*content=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAXDIFF = Pattern.compile("maxDifference=(\\d+)(?:-(\\d+))?");
    private static final Pattern TOTALPX = Pattern.compile("totalPixels=(\\d+)(?:-(\\d+))?");

    /** 結果: 合格 / 不一致 / 空白 / 例外 / 時間切れ（レイアウトが終わらない）。 */
    enum Verdict { PASS, MISMATCH, BLANK, ERROR, HANG }

    /** 文書を読む。{@code .xht} / {@code .xhtml} はブラウザと同じく XHTML として扱う（{@link com.nishiyu.enderframelib.core.dom.parser.HtmlParser#xhtmlToHtml}）。 */
    private static String read(String rel) throws IOException {
        String s = Files.readString(DIR.resolve(rel), StandardCharsets.UTF_8);
        return rel.endsWith(".xht") || rel.endsWith(".xhtml") ? com.nishiyu.enderframelib.core.dom.parser.HtmlParser.xhtmlToHtml(s) : s;
    }

    private static Pixmap render(String html) {
        SoftwareRenderBackend b = new SoftwareRenderBackend(W, H, 1f);
        b.render(SoftwareRendering.displayList(html, W, H, FONTS), T0);
        return b.pixmap();
    }

    /** {@code <meta name=fuzzy content="maxDifference=0-2;totalPixels=0-20">} の上限（無ければ {0, 0}）。 */
    static int[] fuzzy(String html) {
        Matcher m = FUZZY.matcher(html);
        if (!m.find()) return new int[]{0, 0};
        String c = m.group(1);
        Matcher a = MAXDIFF.matcher(c), b = TOTALPX.matcher(c);
        int maxDiff = a.find() ? Integer.parseInt(a.group(2) != null ? a.group(2) : a.group(1)) : 0;
        int total = b.find() ? Integer.parseInt(b.group(2) != null ? b.group(2) : b.group(1)) : 0;
        return new int[]{maxDiff, total};
    }

    /** premultiplied ARGB を白い地（ブラウザのキャンバスの既定）へ合成した不透明色。 */
    static int overWhite(int pm) {
        int ia = 255 - (pm >>> 24);
        int r = Math.min(255, ((pm >>> 16) & 0xFF) + ia), g = Math.min(255, ((pm >>> 8) & 0xFF) + ia), b = Math.min(255, (pm & 0xFF) + ia);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    /**
     * 両者を白い地に合成して比べる（WPT はキャンバスが白い状態で画面を撮る）。「何も描けていない」の判定は合成前に取る:
     * テストが何も描いていなければ {@link Verdict#BLANK}（白地に白を描いた組は描いたものとして数える）。
     */
    static Verdict compare(Pixmap test, Pixmap ref, int maxDiff, int totalPixels) {
        boolean anyInk = false;
        int differing = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int a0 = test.get(x, y), r0 = ref.get(x, y);
                if (a0 != 0) anyInk = true;
                int a = overWhite(a0), r = overWhite(r0);
                if (a == r) continue;
                int d = 0;
                for (int sh = 0; sh < 32; sh += 8) d = Math.max(d, Math.abs(((a >>> sh) & 0xFF) - ((r >>> sh) & 0xFF)));
                if (d > maxDiff) return Verdict.MISMATCH;
                differing++;
                if (differing > totalPixels) return Verdict.MISMATCH;
            }
        }
        return anyInk ? Verdict.PASS : Verdict.BLANK;
    }

    /** 1 組の上限（これを超えたら HANG）。Java は走っているスレッドを止められないので、HANG の組は通常の実行では走らせない。 */
    private static final long TIMEOUT_MS = 20_000;

    static Verdict runWithTimeout(String test, String ref) {
        java.util.concurrent.FutureTask<Verdict> task = new java.util.concurrent.FutureTask<>(() -> run(test, ref));
        Thread t = new Thread(task, "reftest-" + test);
        t.setDaemon(true);
        t.start();
        try {
            return task.get(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return Verdict.HANG;
        } catch (Exception e) {
            return Verdict.ERROR;
        }
    }

    static Verdict run(String test, String ref) {
        try {
            String t = read(test);
            int[] f = fuzzy(t);
            return compare(render(t), render(read(ref)), f[0], f[1]);
        } catch (Throwable e) {
            return Verdict.ERROR;
        }
    }

    private static Map<String, String> manifest() throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        for (String line : Files.readAllLines(MANIFEST, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] p = line.split("\t");
            assertEquals(2, p.length, "manifest の行が不正: " + line);
            m.put(p[0], p[1]);
        }
        return m;
    }

    private static TreeSet<String> readList(Path file) throws IOException {
        TreeSet<String> out = new TreeSet<>();
        if (!Files.exists(file)) return out;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) if (!line.isBlank() && !line.startsWith("#")) out.add(line.trim());
        return out;
    }

    private static String spec(String id) {
        return id.split("/")[1];
    }

    @Test
    void passingSetIsLockedAndNeverRegresses() throws IOException {
        Map<String, String> pairs = manifest();
        assertTrue(pairs.size() >= 250, "コーパスが小さすぎる: " + pairs.size());
        TreeSet<String> passing = new TreeSet<>();
        Map<Verdict, Integer> tally = new TreeMap<>();
        Map<String, int[]> bySpec = new TreeMap<>(); // {合格, 全体}
        boolean update = Boolean.getBoolean("wpt.reftest.update");
        TreeSet<String> knownHangs = readList(HANGS);
        TreeSet<String> hangs = new TreeSet<>();
        for (var e : pairs.entrySet()) {
            Verdict v = !update && knownHangs.contains(e.getKey()) ? Verdict.HANG : update ? runWithTimeout(e.getKey(), e.getValue()) : run(e.getKey(), e.getValue());
            if (v == Verdict.HANG) hangs.add(e.getKey());
            tally.merge(v, 1, Integer::sum);
            int[] c = bySpec.computeIfAbsent(spec(e.getKey()), k -> new int[2]);
            c[1]++;
            if (v == Verdict.PASS) {
                passing.add(e.getKey());
                c[0]++;
            }
        }
        System.out.println("WPT-REFTEST " + passing.size() + "/" + pairs.size() + " " + tally + " bySpec(pass/total)="
                + bySpec.entrySet().stream().map(x -> x.getKey() + " " + x.getValue()[0] + "/" + x.getValue()[1]).toList());

        if (Boolean.getBoolean("wpt.reftest.update")) {
            List<String> out = new ArrayList<>();
            out.add("# 合格している reftest（WptReftestRatchetTest が固定。落ちたら回帰、増えたら -Dwpt.reftest.update=true で締める）");
            out.addAll(passing);
            Files.write(PASSING, out, StandardCharsets.UTF_8);
            List<String> h = new ArrayList<>();
            h.add("# レイアウトが終わらない reftest（エンジンの不具合。走らせない。直したら外す）");
            h.addAll(hangs);
            Files.write(HANGS, h, StandardCharsets.UTF_8);
            System.out.println("WPT-REFTEST 一覧を更新: " + passing.size());
            return;
        }
        TreeSet<String> locked = readList(PASSING);

        TreeSet<String> regressed = new TreeSet<>(locked);
        regressed.removeAll(passing);
        TreeSet<String> gained = new TreeSet<>(passing);
        gained.removeAll(locked);
        assertTrue(locked.size() >= 1, "一覧が空");
        // 合格の一覧は、この開発機（Windows）の書体・描画で固定している。reftest は文字の幅に左右されるので、他の OS では
        // 通る・通らないが入れ替わる（CI の Linux で「新しく通った」「落ちた」が出て、毎回ビルドが赤くなっていた）。
        // 一覧を判定に使うのは一覧を作った OS だけにして、他では差分を出力するだけにする（-Dwpt.reftest.strict=true で強制）
        boolean enforce = Boolean.getBoolean("wpt.reftest.strict") || System.getProperty("os.name", "").toLowerCase().contains("win");
        if (!enforce) {
            System.out.println("WPT-REFTEST（" + System.getProperty("os.name") + " では一覧を判定しない）回帰候補=" + regressed + " 新規合格=" + gained);
            return;
        }
        assertEquals(new TreeSet<String>(), regressed, "合格していた reftest が落ちた（回帰）");
        assertEquals(new TreeSet<String>(), gained, "新しく通った reftest が一覧に無い（-Dwpt.reftest.update=true で一覧を締める）");
    }

    /**
     * 不一致の原因分類のための診断（A11①）。{@code -Dwpt.reftest.diag=true} のときだけ走り、合格しなかった組ごとに
     * 差のある画素数・差の範囲・両者のインクの範囲を {@code build/wpt-diag/summary.tsv} へ、画像を {@code build/wpt-diag/png/} へ書く。
     */
    @Test
    void diagnoseFailures() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("wpt.reftest.diag"));
        Path out = Path.of("build/wpt-diag");
        Path png = out.resolve("png");
        Files.createDirectories(png);
        // 前回の画像は消す（今回合格した組の古い画像が残ると、一覧と食い違う）
        try (var old = Files.list(png)) {
            for (Path f : old.toList()) Files.deleteIfExists(f);
        }
        List<String> rows = new ArrayList<>();
        rows.add("verdict\tdiffPx\tdiffBox\ttestInk\trefInk\ttest\terror");
        for (var e : manifest().entrySet()) {
            String id = e.getKey();
            String row;
            try {
                String t = read(id);
                int[] f = fuzzy(t);
                Pixmap a = render(t), r = render(read(e.getValue()));
                Verdict v = compare(a, r, f[0], f[1]);
                if (v == Verdict.PASS) continue;
                int diff = 0, x0 = W, y0 = H, x1 = -1, y1 = -1;
                for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if (overWhite(a.get(x, y)) != overWhite(r.get(x, y))) {
                    diff++;
                    x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y);
                }
                row = v + "\t" + diff + "\t" + box(x0, y0, x1, y1) + "\t" + ink(a) + "\t" + ink(r) + "\t" + id + "\t";
                String name = id.replace('/', '_');
                writePng(a, png.resolve(name + ".test.png"));
                writePng(r, png.resolve(name + ".ref.png"));
            } catch (Throwable ex) {
                row = "ERROR\t\t\t\t\t" + id + "\t" + ex;
            }
            rows.add(row);
        }
        Files.write(out.resolve("summary.tsv"), rows, StandardCharsets.UTF_8);
        System.out.println("WPT-DIAG " + (rows.size() - 1) + " 件 → " + out.toAbsolutePath());
    }

    /** 調査用: {@code -Dwpt.reftest.dump=<名前の一部>} に合う組のテストと参照の箱の木を標準出力へ書く。 */
    @Test
    void dumpBoxes() throws IOException {
        String key = System.getProperty("wpt.reftest.dump");
        org.junit.jupiter.api.Assumptions.assumeTrue(key != null && !key.isEmpty());
        Map<String, String> pairs = manifest();
        if (Files.isRegularFile(Path.of(key))) pairs = Map.of(Path.of(key).toAbsolutePath().toString(), Path.of(key).toAbsolutePath().toString());
        for (var e : pairs.entrySet()) {
            if (!e.getKey().contains(key) && !Files.isRegularFile(Path.of(key))) continue;
            for (String rel : e.getKey().equals(e.getValue()) ? List.of(e.getKey()) : List.of(e.getKey(), e.getValue())) {
                var compiler = new com.nishiyu.enderframelib.common.UiDocumentCompiler();
                var doc = compiler.parseDocument(read(rel), "", W, H);
                var root = compiler.layoutDocument(doc, W, H, Map.of(), new com.nishiyu.enderframelib.core.layout.model.ScrollState(),
                        FONTS, new com.nishiyu.enderframelib.core.dom.InteractionState());
                StringBuilder sb = new StringBuilder("WPT-DUMP " + rel + "\n");
                dump(root, 0, sb);
                System.out.println(sb);
            }
        }
    }

    private static void dump(com.nishiyu.enderframelib.core.layout.LayoutBox b, int depth, StringBuilder sb) {
        sb.append("  ".repeat(depth)).append(b.isAnonymous ? "(anon)" : b.node == null ? "?" : b.node.tag)
                .append(b.node != null && b.node.getAttribute("id") != null ? "#" + b.node.getAttribute("id") : "")
                .append(String.format(" [%.1f,%.1f %.1fx%.1f] m=%.0f,%.0f,%.0f,%.0f", b.x(), b.y(), b.w(), b.h(), b.mT(), b.mR(), b.mB(), b.mL()));
        for (var f : b.inlineFragments) sb.append(String.format(" {'%s' %.1f,%.1f %.1fx%.1f}", f.text == null ? "" : f.text.replace("\n", "\\n"), f.x, f.y, f.w, f.h));
        sb.append('\n');
        for (var c : b.children) dump(c, depth + 1, sb);
    }

    private static String box(int x0, int y0, int x1, int y1) {
        return x1 < 0 ? "-" : x0 + "," + y0 + "-" + x1 + "," + y1;
    }

    private static String ink(Pixmap p) {
        int x0 = W, y0 = H, x1 = -1, y1 = -1;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if (p.get(x, y) != 0) {
            x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y);
        }
        return box(x0, y0, x1, y1);
    }

    private static void writePng(Pixmap p, Path file) throws IOException {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(W, H, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        int[] px = new int[W * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) px[y * W + x] = overWhite(p.get(x, y));
        img.setRGB(0, 0, W, H, px, 0, W);
        javax.imageio.ImageIO.write(img, "png", file.toFile());
    }

    // ---- 判定ロジックの単体 ----

    private static Pixmap solid(int argb) {
        Pixmap p = new Pixmap(W, H);
        for (int y = 0; y < 10; y++) for (int x = 0; x < 10; x++) p.set(x, y, argb);
        return p;
    }

    @Test
    void identicalInkPasses() {
        assertEquals(Verdict.PASS, compare(solid(0xFF00FF00), solid(0xFF00FF00), 0, 0));
    }

    @Test
    void differenceFailsUnlessFuzzyAllowsIt() {
        assertEquals(Verdict.MISMATCH, compare(solid(0xFF00FF00), solid(0xFF00FE00), 0, 0));
        assertEquals(Verdict.PASS, compare(solid(0xFF00FF00), solid(0xFF00FE00), 1, 100));
        assertEquals(Verdict.MISMATCH, compare(solid(0xFF00FF00), solid(0xFF00FE00), 1, 99), "差のある画素数が上限を超える");
    }

    @Test
    void blankAgainstBlankIsNotAPass() {
        assertEquals(Verdict.BLANK, compare(new Pixmap(W, H), new Pixmap(W, H), 0, 0));
    }

    @Test
    void whiteInkMatchesTheWhiteCanvas() {
        // ブラウザのキャンバスは白い。白地に白を描いたテストは何も描かない参照と一致する（描いているので空白扱いにはしない）
        assertEquals(Verdict.PASS, compare(solid(0xFFFFFFFF), new Pixmap(W, H), 0, 0));
        assertEquals(Verdict.BLANK, compare(new Pixmap(W, H), solid(0xFFFFFFFF), 0, 0));
    }

    @Test
    void fuzzyMetaIsParsed() {
        assertArrayEquals(new int[]{2, 20}, fuzzy("<meta name=fuzzy content=\"maxDifference=0-2;totalPixels=0-20\">"));
        assertArrayEquals(new int[]{0, 0}, fuzzy("<html></html>"));
    }

    @Test
    void theEngineDistinguishesDifferentRenderings() {
        String a = "<html><body style='margin:0'><div style='width:50px;height:50px;background:green'></div></body></html>";
        String b = "<html><body style='margin:0'><div style='width:60px;height:50px;background:green'></div></body></html>";
        assertEquals(Verdict.PASS, compare(render(a), render(a), 0, 0));
        assertEquals(Verdict.MISMATCH, compare(render(a), render(b), 0, 0));
    }
}
