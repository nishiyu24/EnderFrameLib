package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import com.nishiyu.enderframelib.core.dom.parser.MarkdownParser;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 幾何の一致の掃引（計画書 §3.4.2-6）: WPT の reftest の文書（テストと参照）と同梱の実文書の全ての要素の箱の位置と大きさを、
 * DOM の節ごとに旧経路（{@code LayoutBox}）と新経路（断片木）で比べ（許容 1/64 px）、全ての箱が一致した文書の一覧をラチェットで
 * 固定する（{@code geometry-sweep-matching.txt}。増えたら {@code -Dgeom.sweep.update=true} で締める）。
 *
 * <p><b>旧経路は正解の基準ではない</b>（負の余白の相殺など仕様からの逸脱を持つ）。これは画素の合否より細かい「差の在りか」の
 * 物差しで、新経路を既定にする前（M2c）に実文書の差を潰すのに使う。旧経路の逸脱が原因で一覧から外すときは計画書に記録する。
 * 比べるのは両方に箱がある要素だけ（新経路のインライン要素は行の断片へ平らに入るので箱を持たない。疑似要素は比べない）。</p>
 */
class GeometrySweepTest {
    private static final float TOL = 1f / 64f + 1e-4f;
    private static final Path WPT = Path.of("src/test/resources/wpt/reftests");
    private static final Path LIST = WPT.resolve("geometry-sweep-matching.txt");
    private static final List<String> REAL_DOCS = List.of("showcase.html", "voidwave.html", "surface_test.html", "animation_test.html",
            "media_test.html", "hud_test.html", "item_block_test.html", "machine_ui.html", "guidebook.md");
    private static final int W = 800, H = 600;

    @Test
    void matchingDocumentsDoNotRegress() throws IOException {
        Map<String, String> docs = documents();
        TreeSet<String> matching = new TreeSet<>();
        Map<String, Integer> realDiffs = new LinkedHashMap<>();
        int compared = 0, mismatched = 0;
        for (var e : docs.entrySet()) {
            int[] r;
            try {
                r = compare(e.getValue());
            } catch (RuntimeException | StackOverflowError ex) {
                r = new int[]{0, -1};
            }
            compared += r[0];
            if (r[1] > 0) mismatched += r[1];
            if (r[0] > 0 && r[1] == 0) matching.add(e.getKey());
            if (REAL_DOCS.contains(e.getKey())) realDiffs.put(e.getKey(), r[1]);
        }
        System.out.println("GEOM " + matching.size() + "/" + docs.size() + " 文書が全ての箱で一致（比べた箱 " + compared + "・ずれた箱 "
                + mismatched + "） 実文書のずれた箱=" + realDiffs);
        if (Boolean.getBoolean("geom.sweep.update")) {
            List<String> out = new ArrayList<>();
            out.add("# 旧経路と新経路で全ての要素の箱の位置と大きさが一致した文書（GeometrySweepTest が固定。旧経路は正解の基準ではない）");
            out.addAll(matching);
            Files.write(LIST, out, StandardCharsets.UTF_8);
            return;
        }
        TreeSet<String> locked = new TreeSet<>();
        if (Files.exists(LIST)) {
            for (String l : Files.readAllLines(LIST, StandardCharsets.UTF_8)) if (!l.isBlank() && !l.startsWith("#")) locked.add(l.trim());
        }
        TreeSet<String> regressed = new TreeSet<>(locked);
        regressed.removeAll(matching);
        TreeSet<String> gained = new TreeSet<>(matching);
        gained.removeAll(locked);
        assertEquals(new TreeSet<String>(), regressed, "旧経路と幾何が一致していた文書がずれた");
        assertEquals(new TreeSet<String>(), gained, "新しく一致した文書が一覧に無い（-Dgeom.sweep.update=true で締める）");
    }

    /** 調べる用: {@code -Dgeom.sweep.diag=<文書>[,...]} のずれた箱を出す。 */
    @Test
    void diagnose() throws IOException {
        String ids = System.getProperty("geom.sweep.diag");
        org.junit.jupiter.api.Assumptions.assumeTrue(ids != null && !ids.isBlank());
        Map<String, String> docs = documents();
        for (String id : ids.split(",")) {
            List<String> diffs = new ArrayList<>();
            compare(docs.get(id), diffs);
            System.out.println("GEOM-DIAG " + id + " " + diffs.size() + "\n  " + String.join("\n  ", diffs.subList(0, Math.min(40, diffs.size()))));
        }
    }

    /** WPT の文書（テストと参照、時間切れの組を除く）と実文書。 */
    private static Map<String, String> documents() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        TreeSet<String> hangs = new TreeSet<>();
        Path hp = WPT.resolve("reftest-hangs.txt");
        if (Files.exists(hp)) for (String l : Files.readAllLines(hp, StandardCharsets.UTF_8)) if (!l.isBlank() && !l.startsWith("#")) hangs.add(l.trim());
        for (String line : Files.readAllLines(WPT.resolve("manifest.tsv"), StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] p = line.split("\t");
            if (hangs.contains(p[0])) continue;
            for (String rel : p) if (!out.containsKey(rel)) out.put(rel, read(WPT.resolve(rel), rel));
        }
        for (String name : REAL_DOCS) {
            Path p = Path.of("src/main/resources/data/enderframelib/enderframe_ui").resolve(name);
            if (!Files.exists(p)) p = Path.of("src/test/resources/benchmark").resolve(name);
            String text = Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n");
            out.put(name, name.endsWith(".md") ? MarkdownParser.toHtml(text) : text);
        }
        return out;
    }

    private static String read(Path p, String rel) throws IOException {
        String s = Files.readString(p, StandardCharsets.UTF_8);
        return rel.endsWith(".xht") || rel.endsWith(".xhtml") ? HtmlParser.xhtmlToHtml(s) : s;
    }

    /** {比べた箱の数, ずれた箱の数}。 */
    static int[] compare(String html) {
        return compare(html, null);
    }

    private static int[] compare(String html, List<String> diffs) {
        Map<String, float[]> v3 = new LinkedHashMap<>();
        collect(new SpikeView(html, W, H).frame(), 0, 0, v3);
        Map<String, float[]> old = new LinkedHashMap<>();
        try (StandaloneView sv = new StandaloneView(html, W, H, FontResolver.FALLBACK)) {
            sv.frame(1000, new SpikeView.NullBackend(), true);
            LayoutBox root = sv.ui().layoutRoot();
            if (root != null) collect(root, old);
        }
        int compared = 0, bad = 0;
        for (var e : v3.entrySet()) {
            float[] o = old.get(e.getKey());
            if (o == null) continue;
            compared++;
            float[] n = e.getValue();
            for (int i = 0; i < 4; i++) {
                if (Math.abs(o[i] - n[i]) > TOL) {
                    bad++;
                    if (diffs != null) {
                        diffs.add(String.format("%s legacy=%.2f,%.2f %.2fx%.2f v3=%.2f,%.2f %.2fx%.2f", e.getKey(), o[0], o[1], o[2], o[3], n[0], n[1], n[2], n[3]));
                    }
                    break;
                }
            }
        }
        return new int[]{compared, bad};
    }

    /** 断片木の要素の箱（疑似要素を除く）を、DOM の道筋をキーに絶対座標で集める。 */
    private static void collect(LayoutFragment f, float x, float y, Map<String, float[]> out) {
        if (f.box != null && f.box.pseudo == com.nishiyu.enderframelib.core.box.BoxNode.Pseudo.NONE && f.box.node.nodeType == NodeType.ELEMENT) {
            String k = path(f.box.node);
            if (k != null) out.putIfAbsent(k, new float[]{x, y, f.width, f.height});
        }
        float cx = x + f.insetLeft, cy = y + f.insetTop;
        if (f.lines != null) {
            for (LineFragment l : f.lines) for (PlacedFragment a : l.atoms()) collect(a.frag(), cx + l.x() + a.x(), cy + l.y() + a.y(), out);
        }
        if (f.flow != null) f.flow.forEach((c, x1, y1) -> collect(c, cx + x1, cy + y1, out));
        if (f.oof != null) for (PlacedFragment p : f.oof) collect(p.frag(), x + p.x(), y + p.y(), out);
    }

    private static void collect(LayoutBox b, Map<String, float[]> out) {
        if (b.node != null && b.node.nodeType == NodeType.ELEMENT) {
            String k = path(b.node);
            if (k != null) out.putIfAbsent(k, new float[]{b.x(), b.y(), b.w(), b.h()});
        }
        for (LayoutBox c : b.children) collect(c, out);
    }

    /** 根からの子の番号の道筋（DOM の木に無い節は null）。 */
    private static String path(HtmlNode n) {
        StringBuilder sb = new StringBuilder();
        for (HtmlNode c = n; c.parent != null; c = c.parent) {
            int i = c.parent.children.indexOf(c);
            if (i < 0) return null;
            sb.insert(0, "/" + i);
        }
        return n.tag + sb;
    }
}
