package com.nishiyu.enderframelib.core.layout.fragment;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同梱の実文書の幾何（M2b-3 の完了条件。計画書 §3.4.2-6）: {@link GeometrySweepTest} と同じく全ての要素の箱を旧経路と比べ、
 * ずれた箱が全て {@code geometry-sweep-deviations.txt} に記録したものと同じ（道筋の集合と両方の経路の値が一致）で、どれも
 * 旧経路の逸脱か丸めの分類（{@link #CATEGORIES}）を持つことを固定する。
 *
 * <p>旧経路は正解の基準ではないので、旧経路の逸脱は新経路へ写さずに記録する。記録は新経路の値も持つので、新経路の変更で
 * 実文書の箱が 1 つでも動けば落ちる（動いた理由を確かめてから {@code -Dgeom.sweep.update=true} で書き直す。書き直しは分類を
 * 道筋ごとに引き継ぎ、新しい行は {@code ?} になるので、分類を付けるまで落ちる）。分類は原因を再現の文書で確かめて付ける
 * （{@code GeometrySweepTest#diagnose}）。</p>
 */
class GeometryDeviationsTest {
    private static final Path FILE = Path.of("src/test/resources/wpt/reftests/geometry-sweep-deviations.txt");
    /** 記録の値は小数 2 桁で持つ（丸めの誤差 0.005 ＋ 余裕）。 */
    private static final float EPS = 0.006f;

    /** 分類（L は旧経路の逸脱、U は新経路の設計上の丸め）。 */
    static final Map<String, String> CATEGORIES = categories();

    private static Map<String, String> categories() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("L1", "旧経路の内在サイズが要素の最後の文字の後の letter-spacing を含める（行を組むときは含めない。新経路は両方とも含めない）");
        m.put("L2", "旧経路の max-content が閉じる要素の後ろの畳まれる空白を二重に数える（行を組むときは畳む）");
        m.put("L3", "旧経路の flex の基準の大きさが容器の空きの幅で頭打ちになる（Flexbox §9.2 3E は max-content）");
        m.put("L4", "旧経路の交差軸の fit-content が min-content を下限にしない（align-items: center の項目が空きの幅に縮む）");
        m.put("L5", "旧経路の最初のフレームが CSS アニメーションを当てない（新経路は 0% のキーフレームの値）");
        m.put("L6", "旧経路が置換要素の flex 項目を指定の大きさより小さく縮める（自動の最小の大きさは指定の大きさ。Flexbox §4.5）");
        m.put("L7", "旧経路の min-content が要素の境目を折り返しの機会にする（UAX #14 は閉じる要素の直後の読点の前で折り返さない）");
        m.put("L8", "旧経路のグリッドの項目の寄与が % の幅を 9999 px の仮の空間に対して解く（LayoutMetrics.UNBOUNDED_WIDTH）");
        m.put("U1", "新経路は位置を入れ子の段ごとに LayoutUnit（1/64 px）へ丸め、浮動小数のままの旧経路と 2/64 px ほどずれる");
        return m;
    }

    @Test
    void realDocumentsDifferOnlyByRecordedDeviations() throws IOException {
        Map<String, String> docs = GeometrySweepTest.documents();
        Map<String, Map<String, String[]>> recorded = read();
        List<String> out = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String doc : GeometrySweepTest.REAL_DOCS) {
            List<GeometrySweepTest.Diff> diffs = new ArrayList<>();
            GeometrySweepTest.compare(docs.get(doc), diffs, false);
            counts.put(doc, diffs.size());
            Map<String, String[]> rec = recorded.getOrDefault(doc, Map.of());
            TreeSet<String> seen = new TreeSet<>();
            for (GeometrySweepTest.Diff d : diffs) {
                seen.add(d.path());
                String[] r = rec.get(d.path());
                out.add(doc + "\t" + d.path() + "\t" + (r != null ? r[0] : "?") + "\t" + GeometrySweepTest.Diff.rect(d.legacy()) + "\t"
                        + GeometrySweepTest.Diff.rect(d.v3()) + "\t" + d.label());
                if (r == null) {
                    problems.add("記録に無いずれ: " + doc + " " + d.describe());
                } else if (!close(r[1], d.legacy()) || !close(r[2], d.v3())) {
                    problems.add("記録と値が違う: " + doc + " " + d.describe() + " 記録 legacy=" + r[1] + " v3=" + r[2]);
                }
            }
            for (String p : rec.keySet()) if (!seen.contains(p)) problems.add("記録したずれが無くなった（一致した）: " + doc + " " + p);
        }
        System.out.println("GEOM-DEV 実文書のずれた箱=" + counts);
        if (Boolean.getBoolean("geom.sweep.update")) {
            write(out);
            return;
        }
        assertEquals(List.of(), problems.subList(0, Math.min(30, problems.size())),
                problems.size() + " 件（旧経路の逸脱を確かめてから -Dgeom.sweep.update=true で書き直し、新しい行に分類を付ける）");
        for (Map<String, String[]> rec : recorded.values()) {
            for (var e : rec.entrySet()) {
                for (String c : e.getValue()[0].split("\\+")) {
                    assertTrue(CATEGORIES.containsKey(c), "分類の無い・知らない分類の行: " + e.getKey() + " " + e.getValue()[0]);
                }
            }
        }
    }

    private static boolean close(String rect, float[] v) {
        String[] p = rect.replace('x', ',').replace(' ', ',').split(",");
        for (int i = 0; i < 4; i++) if (Math.abs(Float.parseFloat(p[i]) - v[i]) > EPS) return false;
        return true;
    }

    /** 文書 → 道筋 → {分類, 旧経路, 新経路}。 */
    private static Map<String, Map<String, String[]>> read() throws IOException {
        Map<String, Map<String, String[]>> m = new HashMap<>();
        if (!Files.exists(FILE)) return m;
        for (String l : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
            if (l.isBlank() || l.startsWith("#")) continue;
            String[] f = l.split("\t");
            m.computeIfAbsent(f[0], k -> new LinkedHashMap<>()).put(f[1], new String[]{f[2], f[3], f[4]});
        }
        return m;
    }

    private static void write(List<String> rows) throws IOException {
        List<String> out = new ArrayList<>();
        out.add("# 同梱の実文書で旧経路と新経路の箱がずれた要素（GeometryDeviationsTest が固定。計画書 §3.4.1 M2b-3）");
        out.add("# 文書<TAB>DOM の道筋<TAB>分類（+ で複数）<TAB>旧経路 x,y 幅x高さ<TAB>新経路 x,y 幅x高さ<TAB>要素の印（調べる用）");
        out.add("# 分類（後ろの箱が上の箱のずれで動いただけの行も、元のずれの分類を持つ）:");
        for (var e : CATEGORIES.entrySet()) out.add("#   " + e.getKey() + ": " + e.getValue());
        out.addAll(rows);
        Files.write(FILE, out, StandardCharsets.UTF_8);
    }
}
