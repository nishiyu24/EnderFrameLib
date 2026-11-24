package com.nishiyu.enderframelib.wpt;

import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.box.BoxTreeCompiler;
import com.nishiyu.enderframelib.core.box.StyleSource;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.FragmentProjection;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.fragment.FragmentLayout;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutFragment;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutSession;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.DisplayListBuilder;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
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
 * M2b の進捗の物差し（計画書 §3.4.1 M2b-0）: 同じ WPT の reftest を、新経路（{@code BoxTreeCompiler} → {@code FragmentLayout} →
 * 一時アダプタ {@link FragmentProjection} → 旧経路と同じ {@link DisplayListBuilder}）で描いて比べる。テスト側も参照側も新経路で描く。
 *
 * <p>合格の一覧は {@code reftest-passing-v3.txt} にラチェットで固定する（落ちたら回帰、増えたら {@code -Dwpt.v3.update=true} で締める）。
 * 旧経路の一覧と同じく、判定するのは一覧を作った OS（Windows）だけ。M2b の各段で合格数を増やし、M2b-6 で旧経路の 160 以上に届かせる。</p>
 */
class WptV3BaselineTest {
    private static final Path PASSING_V3 = WptReftestRatchetTest.DIR.resolve("reftest-passing-v3.txt");

    /** 新経路で描く。 */
    static Pixmap render(String html) {
        int w = WptReftestRatchetTest.W, h = WptReftestRatchetTest.H;
        com.nishiyu.enderframelib.core.EngineContext.current().updateHost(x -> x.withPixelSizeFallback(com.nishiyu.enderframelib.core.paint.software.ImageSizes.STANDARD));
        Document doc = new UiDocumentCompiler().parseDocument(html, "", w, h);
        InteractionState state = new InteractionState();
        if (doc.root instanceof com.nishiyu.enderframelib.core.dom.node.HtmlDocument hd) hd.setInteractionState(state);
        BoxNode boxes = new BoxTreeCompiler(new StyleSource(doc.sheet, state, Map.of(), w, h)).compile(doc.root, null);
        LayoutSession session = new LayoutSession(WptReftestRatchetTest.FONTS);
        LayoutFragment frag = new FragmentLayout(session).layout(boxes, w, h);
        LayoutBox root = FragmentProjection.project(frag, session);
        SoftwareRenderBackend b = new SoftwareRenderBackend(w, h, 1f);
        b.render(new DisplayListBuilder(w, h, doc.sheet.keyframes, new ScrollState()).render(root), WptReftestRatchetTest.T0);
        return b.pixmap();
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
     * 調べる用: {@code -Dwpt.v3.diag=<id>[,<id>...]} の組を、新経路のテスト・新経路の参照・旧経路のテストの 3 枚の PNG にして
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
            SoftwareRenderBackend b = new SoftwareRenderBackend(WptReftestRatchetTest.W, WptReftestRatchetTest.H, 1f);
            b.render(com.nishiyu.enderframelib.common.SoftwareRendering.displayList(t, WptReftestRatchetTest.W, WptReftestRatchetTest.H, WptReftestRatchetTest.FONTS), WptReftestRatchetTest.T0);
            WptReftestRatchetTest.writePng(b.pixmap(), out.resolve(base + ".legacy-test.png"));
        }
    }

    @Test
    void newPathPassingListDoesNotRegress() throws IOException {
        Map<String, String> pairs = WptReftestRatchetTest.manifest();
        TreeSet<String> hangs = WptReftestRatchetTest.readList(WptReftestRatchetTest.HANGS);
        TreeSet<String> legacy = WptReftestRatchetTest.readList(WptReftestRatchetTest.PASSING);
        TreeSet<String> passing = new TreeSet<>();
        Map<WptReftestRatchetTest.Verdict, Integer> tally = new TreeMap<>();
        Map<String, int[]> bySpec = new TreeMap<>();
        for (var e : pairs.entrySet()) {
            WptReftestRatchetTest.Verdict v = hangs.contains(e.getKey()) ? WptReftestRatchetTest.Verdict.HANG : run(e.getKey(), e.getValue());
            tally.merge(v, 1, Integer::sum);
            int[] c = bySpec.computeIfAbsent(WptReftestRatchetTest.spec(e.getKey()), k -> new int[3]);
            c[2]++;
            if (legacy.contains(e.getKey())) c[1]++;
            if (v == WptReftestRatchetTest.Verdict.PASS) {
                passing.add(e.getKey());
                c[0]++;
            }
        }
        TreeSet<String> onlyLegacy = new TreeSet<>(legacy);
        onlyLegacy.removeAll(passing);
        TreeSet<String> onlyV3 = new TreeSet<>(passing);
        onlyV3.removeAll(legacy);
        System.out.println("WPT-V3 " + passing.size() + "/" + pairs.size() + " (旧経路 " + legacy.size() + ") " + tally
                + " bySpec(v3/旧/全体)=" + bySpec.entrySet().stream().map(x -> x.getKey() + " " + x.getValue()[0] + "/" + x.getValue()[1] + "/" + x.getValue()[2]).toList());
        System.out.println("WPT-V3 旧経路だけ合格=" + onlyLegacy.size() + " 新経路だけ合格=" + onlyV3);

        if (Boolean.getBoolean("wpt.v3.update")) {
            List<String> out = new ArrayList<>();
            out.add("# 新経路（M2b の BoxTreeCompiler → FragmentLayout → 一時アダプタ）で合格している reftest（WptV3BaselineTest が固定）");
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
