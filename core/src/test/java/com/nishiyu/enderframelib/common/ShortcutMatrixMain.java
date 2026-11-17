package com.nishiyu.enderframelib.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 近道の切り分けスイッチ（{@code -Denderframe.*.no* / .off}）の組み合わせ検証（ENGINE_REARCHITECTURE_PLAN A6）。
 *
 * <p>同じ乱数の変更列（クラス・style・文字・子の増減・マウス・リサイズ・ホイール・時間）を、「既定」「各スイッチを 1 つだけ切る」
 * 「ペアワイズの組み合わせ（どの 2 つのスイッチの 4 通りの on/off も、いずれかの構成が通る）」の各構成で別の JVM に走らせ、
 * 毎手の表示リストの署名とソフトウェア描画の画素の写しが既定と完全一致することを確かめる。スイッチは {@code static final} で読むので、
 * 構成ごとに JVM を立てる。</p>
 *
 * <pre>
 * ./gradlew :core:shortcutMatrix                          全構成（数分）
 * ./gradlew :core:shortcutMatrix -Pmatrix.only=noshift    名前に含まれるスイッチの単独の構成だけ
 * ./gradlew :core:shortcutMatrix -Pmatrix.extra="-Denderframe.x=true"   全構成へ足す JVM 引数（検査が効くことの確認用）
 * </pre>
 */
public final class ShortcutMatrixMain {
    private ShortcutMatrixMain() { }

    private static final Pattern SWITCH = Pattern.compile("Boolean\\.getBoolean\\(\\s*\"(enderframe\\.[a-z.]+?\\.(?:no[a-z]+|off))\"\\s*\\)");

    /** GPU が要る面のスイッチ（CI の GPU なし環境では効果が無い）は対象外。 */
    private static boolean inScope(String name) {
        return !name.startsWith("enderframe.gl.") && !name.startsWith("enderframe.gpu.");
    }

    /** {@code core/src/main} を走査して、近道の切り分けスイッチの名前を集める（テストと検査器で共有）。 */
    static List<String> findSwitches(Path mainSources) throws IOException {
        TreeSet<String> out = new TreeSet<>();
        try (Stream<Path> s = Files.walk(mainSources)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = SWITCH.matcher(Files.readString(p, StandardCharsets.UTF_8));
                while (m.find()) if (inScope(m.group(1))) out.add(m.group(1));
            }
        }
        return new ArrayList<>(out);
    }

    /** どの 2 つの因子の (on/off) 4 通りも、いずれかの行が通る配列（貪欲法。種は固定で、同じ入力なら同じ配列）。 */
    static List<boolean[]> pairwise(int n) {
        List<boolean[]> rows = new ArrayList<>();
        boolean[][][][] covered = new boolean[n][n][2][2];
        int remaining = n * (n - 1) / 2 * 4;
        Random r = new Random(20261117L);
        while (remaining > 0) {
            boolean[] best = null;
            int bestGain = -1;
            for (int c = 0; c < 300; c++) {
                boolean[] row = new boolean[n];
                for (int i = 0; i < n; i++) row[i] = r.nextBoolean();
                int gain = 0;
                for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) if (!covered[i][j][row[i] ? 1 : 0][row[j] ? 1 : 0]) gain++;
                if (gain > bestGain) { bestGain = gain; best = row; }
            }
            for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) {
                if (!covered[i][j][best[i] ? 1 : 0][best[j] ? 1 : 0]) { covered[i][j][best[i] ? 1 : 0][best[j] ? 1 : 0] = true; remaining--; }
            }
            rows.add(best);
        }
        return rows;
    }

    // ---- 子 JVM: シナリオを走らせて署名の写しを出す ----

    private static final String CARDS = """
            <style>
              body { margin: 0; font-size: 14px }
              .card { padding: 6px; margin: 3px; border: 1px solid #888; background: #eef; transition: background-color .3s, width .3s }
              .card:hover { background: #fde }
              .list:hover .item { color: #c00 }
              .item:nth-child(odd) { background: #ddd }
              .a { width: 120px } .b { width: 50vw; color: #080 } .hot { background: #fdd; border-radius: 12px }
              .t { width: 100px; transition: width .5s } .t.wide { width: 200px }
              @keyframes morph { from { border-radius: 2px } to { border-radius: 30px } }
              .m { animation: morph 1s infinite alternate }
              .big { font-size: 20px }
              @media (max-width: 300px) { .card { padding: 2px } }
            </style>
            <div class="list" id="l1">
              <div class="card item" id="i1">one <span class="v">v1</span></div>
              <div class="card item a" id="i2">two <b>bold</b></div>
              <div class="card item" id="i3"><div class="t">t</div></div>
              <div class="card item m" id="i4">four</div>
            </div>
            <div class="list" id="l2">
              <div class="card item" id="j1"><span id="s1">x</span> <span class="v">y</span></div>
              <div class="card item" id="j2"><div class="card"><span>deep</span></div></div>
            </div>
            """;
    private static final String[] CARD_CLASSES = {"card item", "card item a", "card item b", "card item hot", "card item m", "card item big", "t", "t wide", "v", ""};
    private static final String[] CARD_STYLES = {"", "width:80px", "color:red", "padding:10px", "border-radius:20px", "font-size:18px", "display:none", "opacity:.5"};

    private static final String LAYOUTS = """
            <style>
              body { margin: 0; font-size: 13px }
              .fx { display: flex; gap: 6px; flex-wrap: wrap } .fx > * { flex: 1 1 60px; min-width: 40px; padding: 3px; background: #def }
              .gr { display: grid; grid-template-columns: repeat(3, 1fr); gap: 4px } .gr > * { background: #fed; padding: 2px }
              .fl { float: left; width: 70px; margin: 2px; background: #cfc } .cl { clear: both }
              table { border-collapse: collapse } td { border: 1px solid #999; padding: 2px 4px }
              .w { width: 40%; max-width: 220px } .p { padding: 8px; border: 2px solid #555 } .rel { position: relative; top: 2px; left: 3px }
              .abs { position: absolute; right: 4px; top: 4px; width: 50px; height: 20px; background: #f99 }
            </style>
            <div class="fx" id="f1"><div id="f1a">alpha beta</div><div id="f1b">gamma</div><div id="f1c">delta epsilon zeta</div></div>
            <div class="gr" id="g1"><div id="g1a">1</div><div id="g1b">22 long</div><div id="g1c">333</div><div id="g1d">4</div><div id="g1e">5</div></div>
            <div id="fl1"><div class="fl">f</div><div class="fl">g</div><p id="para">text beside floats that wraps around the floated boxes for a while</p><div class="cl"></div></div>
            <table id="tb"><tr><td id="t1">a</td><td>bb</td></tr><tr><td>c</td><td id="t2">dddd</td></tr></table>
            <div class="w p rel" id="w1">percent width <span id="w2">inline</span></div><div class="abs" id="ab"></div>
            """;
    private static final String[] LAYOUT_CLASSES = {"fx", "gr", "fl", "w", "p", "w p", "rel", "p rel", "", "fx p"};
    private static final String[] LAYOUT_STYLES = {"", "width:90px", "display:flex", "display:grid", "float:right", "padding:9px", "margin-left:11px", "height:50px", "font-size:17px", "display:none", "gap:2px"};

    /** flex / grid の入れ子（上書き幅・測定の記録の再利用を突く）。 */
    private static final String NESTED = """
            <style>
              body { margin: 0; font-size: 13px }
              .row { display: flex; gap: 4px; padding: 3px; border: 1px solid #99a }
              .col { display: flex; flex-direction: column; gap: 3px } .wrapf { display: flex; flex-wrap: wrap }
              .g2 { display: grid; grid-template-columns: auto 1fr; gap: 3px } .g3 { display: grid; grid-template-columns: repeat(auto-fill, minmax(60px, 1fr)) }
              .c1 { flex: 1 1 0; min-width: 0 } .c2 { flex: 2 1 40px } .c3 { flex: 0 0 70px } .pc { width: 30%; } .fit { width: fit-content }
              .box { padding: 2px 4px; background: #def; border: 1px solid #88a } .lbl { white-space: nowrap }
            </style>
            <div class="row" id="n1"><div class="box c1" id="a1">alpha beta gamma</div><div class="box c2" id="a2">delta <b>epsilon</b></div><div class="box c3" id="a3">z</div></div>
            <div class="col" id="n2"><div class="row" id="b1"><div class="box c1" id="b1a">one two</div><div class="box pc" id="b1b">three</div></div>
              <div class="g2" id="b2"><div class="lbl box" id="b2a">label</div><div class="box" id="b2b">value text that is long enough to wrap in the narrow window</div></div></div>
            <div class="g3" id="n3"><div class="box" id="c1">1</div><div class="box" id="c2">22</div><div class="box" id="c3">333 4</div><div class="box" id="c4">x</div><div class="box" id="c5">yy</div></div>
            <div class="wrapf" id="n4"><div class="box fit" id="d1">fit one</div><div class="box fit" id="d2">fit two longer</div><div class="box c1" id="d3">grow</div></div>
            """;
    private static final String[] NESTED_CLASSES = {"box c1", "box c2", "box c3", "box pc", "box fit", "row", "col", "g2", "wrapf", "box lbl", ""};
    private static final String[] NESTED_STYLES = {"", "width:90px", "min-width:100px", "flex:1 1 auto", "flex-basis:50%", "padding:7px", "gap:9px",
            "grid-template-columns:1fr 2fr", "font-size:17px", "display:none", "max-width:80px", "margin-left:6px", "flex-direction:column"};

    private static String scrollList() {
        StringBuilder sb = new StringBuilder("<style>body{margin:0;font-size:13px}#sc{width:300px;height:200px;overflow-y:auto}"
                + ".row{height:24px;margin:2px;padding:2px 6px;background:#eef}.row:hover{background:#fdd}.hl{background:#cfc}.tall{height:60px}</style><div id=\"sc\">");
        for (int i = 0; i < 70; i++) sb.append("<div class=\"row\" id=\"r").append(i).append("\">row ").append(i).append(" text</div>");
        return sb.append("</div>").toString();
    }
    private static final String[] LIST_CLASSES = {"row", "row hl", "row tall", "row hl tall", ""};
    private static final String[] LIST_STYLES = {"", "color:red", "height:40px", "padding:8px", "opacity:.6", "display:none"};

    /** 名前 → 1 手ごとの写し（食い違った最初の手を示すため）。表示リストの署名は {@code sig:}、画素は {@code pix:}。 */
    private static final Map<String, String> STEPS = new LinkedHashMap<>();
    private static final Map<String, Long> DIGESTS = new LinkedHashMap<>();

    private static void digest(String name, List<List<String>> sigs, List<Integer> pixels) {
        // 調査用: -Dmatrix.dump=cards/4:6 で、その手の表示リストの署名と、そこまでの手順を出す
        String dump = System.getProperty("matrix.dump", "");
        if (dump.startsWith(name + ":")) {
            int step = Integer.parseInt(dump.substring(name.length() + 1));
            for (int i = 0; i <= step && i < MutationScenario.LOG.size(); i++) System.out.println("LOG " + i + " " + MutationScenario.LOG.get(i));
            for (String sig : sigs.get(step)) System.out.println("ITEM " + sig);
            String file = System.getProperty("matrix.dumpfile", "");
            if (!file.isEmpty() && step < MutationScenario.PIXEL_DATA.size()) {
                try (java.io.DataOutputStream o = new java.io.DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(Path.of(file))))) {
                    for (int px : MutationScenario.PIXEL_DATA.get(step)) o.writeInt(px);
                } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
            }
        }
        long hs = 1125899906842597L, hp = 1125899906842597L;
        StringBuilder ss = new StringBuilder(), sp = new StringBuilder();
        for (int i = 0; i < sigs.size(); i++) {
            long sh = sigs.get(i).size();
            for (String s : sigs.get(i)) sh = 31 * sh + s.hashCode();
            hs = 31 * hs + sh;
            ss.append(Long.toHexString(sh)).append(',');
            if (i < pixels.size()) {
                hp = 31 * hp + pixels.get(i);
                sp.append(Integer.toHexString(pixels.get(i))).append(',');
            }
        }
        DIGESTS.put("sig:" + name, hs);
        STEPS.put("sig:" + name, ss.toString());
        if (!pixels.isEmpty()) {
            DIGESTS.put("pix:" + name, hp);
            STEPS.put("pix:" + name, sp.toString());
        }
    }

    private static void child() throws IOException {
        for (long seed = 1; seed <= 8; seed++) {
            digest("cards/" + seed, MutationScenario.run(CARDS, CARD_CLASSES, CARD_STYLES, seed, 45, true), new ArrayList<>(MutationScenario.PIXELS));
            digest("layouts/" + seed, MutationScenario.run(LAYOUTS, LAYOUT_CLASSES, LAYOUT_STYLES, seed, 45, true), new ArrayList<>(MutationScenario.PIXELS));
            digest("nested/" + seed, MutationScenario.run(NESTED, NESTED_CLASSES, NESTED_STYLES, seed * 5 + 1, 60, true), new ArrayList<>(MutationScenario.PIXELS));
            digest("scrolllist/" + seed, MutationScenario.run(scrollList(), LIST_CLASSES, LIST_STYLES, seed, 45, true, true), new ArrayList<>(MutationScenario.PIXELS));
        }
        Path page = Path.of("core/demo/resources/catalog.html");
        if (!Files.exists(page)) page = Path.of("demo/resources/catalog.html");
        if (Files.exists(page)) {
            String html = Files.readString(page);
            String[] classes = {"", "px", "px flash", "row", "card", "stat", "hot", "fill", "bar"};
            String[] styles = {"", "transform:scaleX(.5)", "opacity:.5", "width:120px", "color:red", "display:none", "padding:12px"};
            for (long seed = 1; seed <= 2; seed++) {
                digest("catalog/" + seed, MutationScenario.run(html, classes, styles, seed, 30, true, true), new ArrayList<>(MutationScenario.PIXELS));
            }
        }
        for (var e : DIGESTS.entrySet()) System.out.println("DIGEST " + e.getKey() + " " + e.getValue() + " " + STEPS.get(e.getKey()));
    }

    // ---- 親: 構成ごとに子 JVM を立てて比べる ----

    private record Config(String name, List<String> switches) { }

    /**
     * 表示リストの「表し方」を変えるスイッチ（親相対の座標・スクロール節の項目）。署名が変わるのは設計どおりなので、画素だけを比べる。
     * 増やすときは、署名が変わる理由を書く。
     */
    private static final Map<String, String> REPRESENTATION_SWITCHES = Map.of(
            "enderframe.paint.noparentrelative", "項目の座標が絶対か親相対か",
            "enderframe.paint.noscrollnode", "スクロールする箱の内側を 1 つの節の項目にするか");

    private record Result(Map<String, Long> digests, Map<String, String> steps) { }

    private static Result runChild(Config c, String extra) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-Dstdout.encoding=UTF-8");
        cmd.add("-Xmx1g");
        if (c.name().contains("slow")) cmd.add("-Xint"); // 壁時計への依存の検出: 実行速度が変わっても結果が変わらないこと
        for (String sw : c.switches()) cmd.add("-D" + sw + "=true");
        if (!extra.isEmpty()) cmd.addAll(List.of(extra.split("\\s+")));
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(ShortcutMatrixMain.class.getName());
        cmd.add("child");
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        Map<String, Long> out = new LinkedHashMap<>();
        Map<String, String> steps = new LinkedHashMap<>();
        StringBuilder tail = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("DIGEST ")) {
                    String[] f = line.split(" ");
                    out.put(f[1], Long.parseLong(f[2]));
                    steps.put(f[1], f.length > 3 ? f[3] : "");
                } else if (tail.length() < 4000) tail.append(line).append('\n');
            }
        }
        int exit = p.waitFor();
        if (exit != 0 || out.isEmpty()) throw new IllegalStateException("子 JVM が失敗: " + c.name() + " (exit " + exit + ")\n" + tail);
        return new Result(out, steps);
    }

    private static String firstDiffStep(String a, String b) {
        String[] x = a.split(","), y = b.split(",");
        for (int i = 0; i < Math.min(x.length, y.length); i++) if (!x[i].equals(y[i])) return "手 " + i;
        return x.length != y.length ? "手数の違い" : "?";
    }

    private static final Path KNOWN = Path.of("core/src/test/resources/shortcut-matrix-known.txt");

    private static Map<String, Map<String, String>> readKnown() throws IOException {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (!Files.exists(KNOWN)) return out;
        for (String line : Files.readAllLines(KNOWN, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] f = line.split("\\|", 3);
            if (f.length < 3) throw new IllegalStateException("「スイッチ | シナリオ | 理由」の 3 欄が要る: " + line);
            out.computeIfAbsent(f[0].trim(), k -> new LinkedHashMap<>()).put(f[1].trim(), f[2].trim());
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("child")) {
            child();
            return;
        }
        if (!System.getProperty("matrix.dump", "").isEmpty()) { // 調査用: このプロセスで 1 つのスイッチだけ切って走らせ、その手を出す
            String sw = System.getProperty("matrix.dumpswitch", "");
            if (!sw.isEmpty()) System.setProperty(sw, "true");
            child();
            return;
        }
        String only = System.getProperty("matrix.only", "").trim();
        String extra = System.getProperty("matrix.extra", "").trim();
        int parallel = Integer.getInteger("matrix.parallel", 3);
        Path main = Path.of("core/src/main/java");
        if (!Files.isDirectory(main)) main = Path.of("src/main/java");
        List<String> switches = findSwitches(main);

        List<Config> configs = new ArrayList<>();
        configs.add(new Config("default", List.of()));
        configs.add(new Config("default (repeat: 実行ごとの揺れが無いことの確認)", List.of()));
        if (only.isEmpty() || only.equals("slow")) configs.add(new Config("default (slow: -Xint。壁時計に依らないことの確認)", List.of()));
        for (String sw : switches) if (sw.contains(only)) configs.add(new Config("single " + sw, List.of(sw)));
        if (only.isEmpty()) {
            int k = 0;
            for (boolean[] row : pairwise(switches.size())) {
                List<String> on = new ArrayList<>();
                for (int i = 0; i < row.length; i++) if (row[i]) on.add(switches.get(i));
                configs.add(new Config("pairwise #" + (++k) + " (" + on.size() + " off)", on));
            }
        }
        System.out.println("MATRIX switches=" + switches.size() + " configs=" + configs.size());

        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        Map<Config, Future<Result>> futures = new LinkedHashMap<>();
        for (Config c : configs) futures.put(c, pool.submit(() -> runChild(c, extra)));
        Result base = futures.get(configs.get(0)).get();

        // 各構成で既定と違った「シナリオ名（sig: / pix:）→ 最初の手」
        Map<Config, Map<String, String>> diffs = new LinkedHashMap<>();
        for (var e : futures.entrySet()) {
            Result got = e.getValue().get();
            boolean pixelsOnly = e.getKey().switches().stream().anyMatch(REPRESENTATION_SWITCHES::containsKey);
            Map<String, String> diff = new LinkedHashMap<>();
            for (var d : base.digests().entrySet()) {
                if (pixelsOnly && d.getKey().startsWith("sig:")) continue;
                if (!d.getValue().equals(got.digests().get(d.getKey()))) {
                    // 既知の表は文書の単位（種ごとではない）で持つ: sig:cards/6 → sig:cards
                    String family = d.getKey().substring(0, d.getKey().indexOf('/'));
                    diff.putIfAbsent(family, d.getKey().substring(family.length() + 1) + " の" + firstDiffStep(base.steps().get(d.getKey()), got.steps().get(d.getKey())));
                }
            }
            diffs.put(e.getKey(), diff);
            System.out.println((diff.isEmpty() ? "ok   " : "DIFF ") + e.getKey().name() + (diff.isEmpty() ? "" : " → " + diff));
        }

        // 既知の不一致（単独オフで既定と違うことが分かっているもの）。増えたら失敗、解消したら表から外させる
        Map<String, Map<String, String>> known = readKnown(); // スイッチ → (シナリオ名 → 理由)
        Map<String, Map<String, String>> singles = new LinkedHashMap<>();
        for (var e : diffs.entrySet()) {
            if (e.getKey().switches().size() == 1 && e.getKey().name().startsWith("single ") && !e.getValue().isEmpty()) {
                Map<String, String> m = new LinkedHashMap<>();
                for (String sc : e.getValue().keySet()) m.put(sc, "未調査");
                singles.put(e.getKey().switches().get(0), m);
            }
        }
        if (Boolean.getBoolean("matrix.update")) {
            StringBuilder sb = new StringBuilder("# 近道スイッチを単独で切ると既定と食い違う既知の文書（ShortcutMatrixMain が読む）。形式: スイッチ | 文書名 | 理由\n"
                    + "# この表は減らす方向にだけ動かす。不一致が解消したら行を消す（残っていると失敗）。新しい不一致は失敗する。\n");
            for (var e : singles.entrySet()) for (String sc : e.getValue().keySet()) {
                String reason = known.getOrDefault(e.getKey(), Map.of()).getOrDefault(sc, "未調査");
                sb.append(e.getKey()).append(" | ").append(sc).append(" | ").append(reason).append('\n');
            }
            Files.writeString(KNOWN, sb.toString(), StandardCharsets.UTF_8);
            System.out.println("SHORTCUTMATRIX known list updated: " + KNOWN);
            pool.shutdown();
            return;
        }
        List<String> failures = new ArrayList<>();
        for (var e : diffs.entrySet()) {
            Config c = e.getKey();
            if (c.switches().isEmpty()) {
                if (!e.getValue().isEmpty()) failures.add(c.name() + " → 既定どうしが一致しない（実行ごとの揺れ）: " + e.getValue());
                continue;
            }
            // 許される不一致: 構成の中のスイッチのどれかが、そのシナリオで既知
            for (var d : e.getValue().entrySet()) {
                boolean explained = c.switches().stream().anyMatch(sw -> known.getOrDefault(sw, Map.of()).containsKey(d.getKey()));
                if (!explained) failures.add(c.name() + (c.switches().size() > 1 ? " " + c.switches() : "") + " → 新しい不一致 " + d.getKey() + " " + d.getValue());
            }
        }
        if (only.isEmpty()) { // 一部の構成だけ走らせたときは、解消の検査は意味がない
            for (var k : known.entrySet()) for (String sc : k.getValue().keySet()) {
                if (!singles.getOrDefault(k.getKey(), Map.of()).containsKey(sc)) failures.add(k.getKey() + " の既知の不一致 " + sc + " が解消している（shortcut-matrix-known.txt から行を消す）");
            }
        }
        pool.shutdown();
        if (failures.isEmpty()) {
            System.out.println("SHORTCUTMATRIX OK (" + configs.size() + " configs, " + switches.size() + " switches, " + base.digests().size() + " scenarios each, 既知の不一致 " + known.values().stream().mapToInt(Map::size).sum() + " 件)");
            return;
        }
        System.out.println("SHORTCUTMATRIX FAILED (" + failures.size() + ")");
        for (String f : failures) System.out.println("  " + f);
        System.exit(1);
    }
}
