package com.nishiyu.enderframelib.wpt;

import com.nishiyu.enderframelib.core.raster.Pixmap;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WPT の reftest の合格の一覧のラチェット（M2c-3 で経路を 1 本にしたので、これが唯一の一覧）。テストも参照も
 * {@link WptReftestRatchetTest#render}（箱の木 → 断片木 → 描画用の木 → 表示リスト）で描いて比べる。
 *
 * <p>合格の一覧は {@code reftest-passing-v3.txt} にラチェットで固定する（落ちたら回帰、増えたら {@code -Dwpt.v3.update=true} で締める）。
 * 判定するのは一覧を作った OS（Windows）だけ（reftest は文字の幅に左右され、他の OS では通る・通らないが入れ替わる）。</p>
 */
class WptV3BaselineTest {
    private static final Path PASSING_V3 = WptReftestRatchetTest.DIR.resolve("reftest-passing-v3.txt");

    static Pixmap render(String html) {
        return WptReftestRatchetTest.render(html);
    }

    static WptReftestRatchetTest.Verdict run(String test, String ref) {
        try {
            String t = WptReftestRatchetTest.read(test);
            int[] f = WptReftestRatchetTest.fuzzy(t);
            return WptReftestRatchetTest.compare(render(t), render(WptReftestRatchetTest.read(ref)), f[0], f[1]);
        } catch (Throwable e) {
            return WptReftestRatchetTest.Verdict.ERROR;
        }
    }

    /**
     * 調べる用: {@code -Dwpt.v3.diag=<id>[,<id>...]} の組を、テストと参照の 2 枚の PNG にして
     * {@code build/wpt-v3-diag/} へ書く。
     */
    @Test
    void diagnose() throws IOException {
        String ids = System.getProperty("wpt.v3.diag");
        org.junit.jupiter.api.Assumptions.assumeTrue(ids != null && !ids.isBlank());
        Path out = Path.of("build/wpt-v3-diag");
        Files.createDirectories(out);
        Map<String, String> pairs = WptReftestRatchetTest.manifest();
        for (String id : ids.split(",")) {
            String t = WptReftestRatchetTest.read(id), r = WptReftestRatchetTest.read(pairs.get(id));
            String base = id.replaceAll("[^A-Za-z0-9.-]", "_");
            WptReftestRatchetTest.writePng(render(t), out.resolve(base + ".v3-test.png"));
            WptReftestRatchetTest.writePng(render(r), out.resolve(base + ".v3-ref.png"));
        }
    }

    @Test
    void newPathPassingListDoesNotRegress() throws IOException {
        Map<String, String> pairs = WptReftestRatchetTest.manifest();
        TreeSet<String> hangs = WptReftestRatchetTest.readList(WptReftestRatchetTest.HANGS);
        TreeSet<String> passing = new TreeSet<>();
        Map<WptReftestRatchetTest.Verdict, Integer> tally = new TreeMap<>();
        Map<String, int[]> bySpec = new TreeMap<>();
        for (var e : pairs.entrySet()) {
            WptReftestRatchetTest.Verdict v = hangs.contains(e.getKey()) ? WptReftestRatchetTest.Verdict.HANG : run(e.getKey(), e.getValue());
            tally.merge(v, 1, Integer::sum);
            int[] c = bySpec.computeIfAbsent(WptReftestRatchetTest.spec(e.getKey()), k -> new int[2]);
            c[1]++;
            if (v == WptReftestRatchetTest.Verdict.PASS) {
                passing.add(e.getKey());
                c[0]++;
            }
        }
        System.out.println("WPT-V3 " + passing.size() + "/" + pairs.size() + " " + tally
                + " bySpec(合格/全体)=" + bySpec.entrySet().stream().map(x -> x.getKey() + " " + x.getValue()[0] + "/" + x.getValue()[1]).toList());

        if (Boolean.getBoolean("wpt.v3.update")) {
            List<String> out = new ArrayList<>();
            out.add("# 合格している reftest（BoxTreeCompiler → FragmentLayout → PaintTreeBuilder → TreePainter。WptV3BaselineTest が固定）");
            out.addAll(passing);
            Files.write(PASSING_V3, out, StandardCharsets.UTF_8);
            System.out.println("WPT-V3 一覧を更新: " + passing.size());
            return;
        }
        TreeSet<String> locked = WptReftestRatchetTest.readList(PASSING_V3);
        assertTrue(locked.size() >= 1, "一覧が空（-Dwpt.v3.update=true で作る）");
        boolean enforce = Boolean.getBoolean("wpt.reftest.strict") || System.getProperty("os.name", "").toLowerCase().contains("win");
        TreeSet<String> regressed = new TreeSet<>(locked);
        regressed.removeAll(passing);
        TreeSet<String> gained = new TreeSet<>(passing);
        gained.removeAll(locked);
        if (!enforce) {
            System.out.println("WPT-V3（" + System.getProperty("os.name") + " では一覧を判定しない）回帰候補=" + regressed + " 新規合格=" + gained);
            return;
        }
        assertEquals(new TreeSet<String>(), regressed, "新経路で合格していた reftest が落ちた（回帰）");
        assertEquals(new TreeSet<String>(), gained, "新経路で新しく通った reftest が一覧に無い（-Dwpt.v3.update=true で一覧を締める）");
    }
}
