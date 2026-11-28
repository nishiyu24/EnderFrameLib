package com.nishiyu.enderframelib.wpt;

import com.nishiyu.enderframelib.common.TestBoxes;

import static org.junit.jupiter.api.Assertions.*;

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
 * WPT の参照画像比較（reftest）の部分集合（flexbox・grid・text の 260 組。選び方は {@code select_reftests.py}）の道具: コーパスの読み込み・
 * 描画・判定・診断と、判定の論理の単体。合格の一覧のラチェットは {@link WptV3BaselineTest}（M2c-3 で経路を 1 本にしたので一覧も 1 つ）。
 *
 * <ul>
 *   <li>「空白どうしで一致」は合格にしない（何も描けていないのを見逃さない）。</li>
 * </ul>
 * 描画は OS の書体（{@link #FONTS}）・800×600・等倍・透明な地。許容差は {@code <meta name=fuzzy>} に従う（無ければ完全一致）。
 */
class WptReftestRatchetTest {
    static final Path DIR = Path.of("core/src/test/resources/wpt/reftests");
    private static final Path MANIFEST = DIR.resolve("manifest.tsv");
    /** レイアウトが終わらない（エンジンの不具合）組。走らせない。直したら一覧から外す。 */
    static final Path HANGS = DIR.resolve("reftest-hangs.txt");
    static final int W = 800, H = 600;
    /**
     * レイアウトの書体。描画はシステム書体の字形で描くので、測定もシステム書体でないと、文字列の区切り方が違うテストと参照
     * （{@code un<span></span>bro} と {@code unbro}）で字の位置が食い違う（{@link FontResolver#FALLBACK} は概算の幅しか返さない）。
     */
    static final FontResolver FONTS = com.nishiyu.enderframelib.core.text.font.SystemFonts.system();
    static final long T0 = 10_000L;
    private static final Pattern FUZZY = Pattern.compile("<meta[^>]+name=[\"']?fuzzy[\"']?[^>]*content=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAXDIFF = Pattern.compile("maxDifference=(\\d+)(?:-(\\d+))?");
    private static final Pattern TOTALPX = Pattern.compile("totalPixels=(\\d+)(?:-(\\d+))?");

    /** 結果: 合格 / 不一致 / 空白 / 例外 / 時間切れ（レイアウトが終わらない）。 */
    enum Verdict { PASS, MISMATCH, BLANK, ERROR, HANG }

    /** 文書を読む。{@code .xht} / {@code .xhtml} はブラウザと同じく XHTML として扱う（{@link com.nishiyu.enderframelib.core.dom.parser.HtmlParser#xhtmlToHtml}）。 */
    static String read(String rel) throws IOException {
        String s = Files.readString(DIR.resolve(rel), StandardCharsets.UTF_8);
        return rel.endsWith(".xht") || rel.endsWith(".xhtml") ? com.nishiyu.enderframelib.core.dom.parser.HtmlParser.xhtmlToHtml(s) : s;
    }

    /** 文書を描く（箱の木 → 断片木 → 描画用の木 → 表示リスト → ソフトウェア描画。テストも参照も同じ経路）。 */
    static Pixmap render(String html) {
        com.nishiyu.enderframelib.core.EngineContext.current().updateHost(x -> x.withPixelSizeFallback(com.nishiyu.enderframelib.core.paint.software.ImageSizes.STANDARD));
        com.nishiyu.enderframelib.common.Document doc = new com.nishiyu.enderframelib.common.UiDocumentCompiler().parseDocument(html, "", W, H);
        com.nishiyu.enderframelib.core.dom.InteractionState state = new com.nishiyu.enderframelib.core.dom.InteractionState();
        if (doc.root instanceof com.nishiyu.enderframelib.core.dom.node.HtmlDocument hd) hd.setInteractionState(state);
        SoftwareRenderBackend b = new SoftwareRenderBackend(W, H, 1f);
        b.render(com.nishiyu.enderframelib.core.paint.RenderFrame.of(com.nishiyu.enderframelib.common.FrameRender.document(doc, W, H, Map.of(), new com.nishiyu.enderframelib.core.layout.model.ScrollState(),
                FONTS, state).items(), T0));
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

    static Map<String, String> manifest() throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        for (String line : Files.readAllLines(MANIFEST, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] p = line.split("\t");
            assertEquals(2, p.length, "manifest の行が不正: " + line);
            m.put(p[0], p[1]);
        }
        return m;
    }

    static TreeSet<String> readList(Path file) throws IOException {
        TreeSet<String> out = new TreeSet<>();
        if (!Files.exists(file)) return out;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) if (!line.isBlank() && !line.startsWith("#")) out.add(line.trim());
        return out;
    }

    static String spec(String id) {
        return id.split("/")[1];
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
                var root = TestBoxes.root(compiler.layoutDocument(doc, W, H, Map.of(), new com.nishiyu.enderframelib.core.layout.model.ScrollState(),
                        FONTS, new com.nishiyu.enderframelib.core.dom.InteractionState()));
                StringBuilder sb = new StringBuilder("WPT-DUMP " + rel + "\n");
                dump(root, 0, sb);
                System.out.println(sb);
            }
        }
    }

    private static void dump(TestBoxes.Box b, int depth, StringBuilder sb) {
        sb.append("  ".repeat(depth)).append(b.isAnonymous ? "(anon)" : b.node == null ? "?" : b.node.tag)
                .append(b.node != null && b.node.getAttribute("id") != null ? "#" + b.node.getAttribute("id") : "")
                .append(String.format(" [%.1f,%.1f %.1fx%.1f] m=%.0f,%.0f,%.0f,%.0f", b.x(), b.y(), b.w(), b.h(), b.mT(), b.mR(), b.mB(), b.mL()));
        sb.append(" runs=").append(b.runs().size());
        sb.append('\n');
        for (var c : b.paintChildren()) dump(c, depth + 1, sb);
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

    static void writePng(Pixmap p, Path file) throws IOException {
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
