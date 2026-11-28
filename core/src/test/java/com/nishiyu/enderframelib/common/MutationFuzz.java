package com.nishiyu.enderframelib.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 差分ファズ（ENGINE_REARCHITECTURE_PLAN T3 ③）: 乱数の変更列（{@link MutationScenario}）の毎手で、増分で作った表示リストが同じ時刻で
 * 前回の木と記憶を全部捨てて（{@code RELAYOUT}）全面を組み直した結果と一致するかを比べる。ずれた種は {@code mutation-seeds.txt} に足し、以後は
 * {@link MutationSeedRegressionTest} が毎回の CI で固定の種として回す（見つかった不具合がそのまま回帰テストになる）。
 *
 * <p>夜間（{@code full-tests.yml}）は {@code ./gradlew :core:mutationFuzz} で新しい種を回す。種の始まりは日付から決めるので、毎晩違う種になる。
 * <ul>
 *   <li>{@code -Pfuzz.seeds=N}: 文書ごとに回す種の数（既定 24）</li>
 *   <li>{@code -Pfuzz.start=S}: 最初の種（既定は日付から）</li>
 *   <li>{@code -Pfuzz.record}: ずれた種を {@code core/src/test/resources/mutation-seeds.txt} へ直接足す（手元で使う）</li>
 * </ul>
 * ずれた種は {@code core/build/fuzz/new-seeds.txt} にも、表へそのまま貼れる形で書く（CI の成果物）。1 つでもずれたら終了コード 1。</p>
 */
public final class MutationFuzz {
    private MutationFuzz() { }

    /** 変更列を流す文書。 */
    public record Doc(String name, String html, String[] classes, String[] styles, int steps) { }

    public static final String CARDS = """
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
    public static final String[] CARD_CLASSES = {"card item", "card item a", "card item b", "card item hot", "card item m", "card item big", "t", "t wide", "v", ""};
    public static final String[] CARD_STYLES = {"", "width:80px", "color:red", "padding:10px", "border-radius:20px", "font-size:18px", "display:none", "opacity:.5"};

    public static final String LAYOUTS = """
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
    public static final String[] LAYOUT_CLASSES = {"fx", "gr", "fl", "w", "p", "w p", "rel", "p rel", "", "fx p"};
    public static final String[] LAYOUT_STYLES = {"", "width:90px", "display:flex", "display:grid", "float:right", "padding:9px", "margin-left:11px", "height:50px", "font-size:17px", "display:none", "gap:2px"};

    public static final String NESTED = """
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
    public static final String[] NESTED_CLASSES = {"box c1", "box c2", "box c3", "box pc", "box fit", "row", "col", "g2", "wrapf", "box lbl", ""};
    public static final String[] NESTED_STYLES = {"", "width:90px", "min-width:100px", "flex:1 1 auto", "flex-basis:50%", "padding:7px", "gap:9px",
            "grid-template-columns:1fr 2fr", "font-size:17px", "display:none", "max-width:80px", "margin-left:6px", "flex-direction:column"};

    public static String scrollList() {
        StringBuilder sb = new StringBuilder("<style>body{margin:0;font-size:13px}#sc{width:300px;height:200px;overflow-y:auto}"
                + ".row{height:24px;margin:2px;padding:2px 6px;background:#eef}.row:hover{background:#fdd}.hl{background:#cfc}.tall{height:60px}</style><div id=\"sc\">");
        for (int i = 0; i < 70; i++) sb.append("<div class=\"row\" id=\"r").append(i).append("\">row ").append(i).append(" text</div>");
        return sb.append("</div>").toString();
    }
    public static final String[] LIST_CLASSES = {"row", "row hl", "row tall", "row hl tall", ""};
    public static final String[] LIST_STYLES = {"", "color:red", "height:40px", "padding:8px", "opacity:.6", "display:none"};

    public static final Map<String, Doc> DOCS = new LinkedHashMap<>();
    static {
        DOCS.put("layouts", new Doc("layouts", LAYOUTS, LAYOUT_CLASSES, LAYOUT_STYLES, 45));
        DOCS.put("cards", new Doc("cards", CARDS, CARD_CLASSES, CARD_STYLES, 45));
        DOCS.put("nested", new Doc("nested", NESTED, NESTED_CLASSES, NESTED_STYLES, 60));
    }

    static final Path SEEDS_FILE = Path.of("core/src/test/resources/mutation-seeds.txt");

    private static List<String> sigs(StandaloneView v) {
        List<String> out = new ArrayList<>();
        for (var it : TestItems.of(v.ui())) out.add(MutationScenario.sig(it));
        return out;
    }

    /**
     * 種 {@code seed} の変更列を {@code steps} 手流し、最初に増分と全面が食い違った手の説明を返す（一致すれば null）。
     * 全面は同じ時刻（{@code StandaloneView.lastNow}）で回す（時間が進むとトランジションが進んで近道と無関係な差が出る。§6-79）。
     */
    public static String firstMismatch(Doc doc, long seed, int steps) {
        String[] found = {null};
        var saved = MutationScenario.STEP_HOOK;
        MutationScenario.STEP_HOOK = (step, view) -> {
            if (found[0] != null) return;
            List<String> live = sigs(view);
            // 全体の作り直しの要求（RELAYOUT）は前回の木と記憶を捨てるので、記憶に頼らない結果と比べられる
            view.ui().markInvalidation(UiView.InvalidationLevel.RELAYOUT);
            try (var be = new MutationScenario.NoDraw()) {
                var f = StandaloneView.class.getDeclaredField("lastNow");
                f.setAccessible(true);
                view.frame(f.getLong(view), be, true);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            List<String> full = sigs(view);
            if (!live.equals(full)) {
                String detail = live.size() != full.size() ? "（項目数 " + live.size() + " ≠ " + full.size() + "）" : "";
                for (int i = 0; i < Math.min(live.size(), full.size()); i++) {
                    if (!live.get(i).equals(full.get(i))) { detail = "（" + live.get(i) + " ≠ " + full.get(i) + "）"; break; }
                }
                found[0] = doc.name() + "/" + seed + " 手 " + step + detail;
            }
        };
        try {
            MutationScenario.run(doc.html(), doc.classes(), doc.styles(), seed, steps, false);
        } finally {
            MutationScenario.STEP_HOOK = saved;
        }
        return found[0];
    }

    /** 表の 1 行（文書 | 種 | 手数 | 由来）。由来の中の区切り文字は置き換え、長い説明は切る。 */
    static String seedLine(Doc doc, long seed, String origin) {
        String o = origin.replace('|', '/');
        if (o.length() > 160) o = o.substring(0, 160) + "…";
        return doc.name() + " | " + seed + " | " + doc.steps() + " | " + o;
    }

    /** 表に載っている種（文書名/種）。 */
    static java.util.Set<String> recordedSeeds() throws IOException {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (!Files.exists(SEEDS_FILE)) return out;
        for (String l : Files.readAllLines(SEEDS_FILE, StandardCharsets.UTF_8)) {
            if (l.isBlank() || l.startsWith("#")) continue;
            String[] f = l.split("\\s*\\|\\s*", 3);
            if (f.length >= 2) out.add(f[0].trim() + "/" + f[1].trim());
        }
        return out;
    }

    public static void main(String[] args) throws IOException {
        int seeds = Integer.getInteger("fuzz.seeds", 24);
        long start = Long.getLong("fuzz.start", 1_000_000L + LocalDate.now().toEpochDay() * 1_000L);
        boolean record = Boolean.getBoolean("fuzz.record");
        boolean gha = System.getenv("GITHUB_ACTIONS") != null;
        List<String> found = new ArrayList<>();
        int runs = 0;
        for (Doc doc : DOCS.values()) {
            for (long seed = start; seed < start + seeds; seed++) {
                runs++;
                String bad = firstMismatch(doc, seed, doc.steps());
                if (bad == null) continue;
                System.out.println("MISMATCH " + bad);
                if (gha) System.out.println("::error title=mutationFuzz::" + bad + " → mutation-seeds.txt に「" + seedLine(doc, seed, "夜間ファズ " + LocalDate.now()) + "」を足して直す");
                found.add(seedLine(doc, seed, "夜間ファズ " + LocalDate.now() + "（" + bad + "）"));
            }
        }
        Path out = Path.of("core/build/fuzz/new-seeds.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, found, StandardCharsets.UTF_8);
        if (record && !found.isEmpty()) {
            java.util.Set<String> known = recordedSeeds();
            List<String> fresh = found.stream().filter(l -> {
                String[] f = l.split("\\s*\\|\\s*", 3);
                return !known.contains(f[0] + "/" + f[1]);
            }).toList();
            Files.write(SEEDS_FILE, fresh, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            System.out.println("recorded " + fresh.size() + " new seed(s) into " + SEEDS_FILE + "（表に既にある " + (found.size() - fresh.size()) + " 件は足さない）");
        }
        System.out.println((found.isEmpty() ? "FUZZ OK" : "FUZZ FOUND " + found.size()) + " (" + runs + " runs, seeds " + start + ".." + (start + seeds - 1) + ")");
        System.exit(found.isEmpty() ? 0 : 1);
    }
}
