package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * C1 / S7 ①の足場: {@code LayoutBox.{dlStart, dlEnd, dlOrder, paintCulled}}（L5 → L4 への書き戻し）の書き込み箇所を固定する。
 * 書き込める場所は表示リストの組み立てとそのパッチだけ（下の上限表）で、増やさない。C1 で断片の記憶へ置き換えるたびに
 * 上限を下げ、最後は 0（{@code LayoutBox} 自身の {@code clearDlSlice} を除く）にする。
 */
class SliceWriteSiteRatchetTest {

    /** 書き込み箇所（代入・複合代入・増減・{@code clearDlSlice()} の呼び出し）の上限。ここに無いファイルは 0。 */
    private static final Map<String, Integer> LIMITS = Map.of(
            // B 案: 断片の経路が旧経路の executeTasks と同じ記録を書く（旧経路の削除で、この行と DisplayListBuilder の書き込みが消える）
            "core/paint/FragmentPaint.java", 2,
            "core/layout/LayoutBox.java", 7);

    private static final Pattern WRITE = Pattern.compile(
            "\\b(?:dlStart|dlEnd|dlOrder|paintCulled)\\s*(?:[-+*/]?=(?!=)|\\+\\+|--)|\\bclearDlSlice\\(");

    @Test
    void writeSitesDoNotGrow() throws Exception {
        Path root = Path.of("core/src/main/java");
        if (!Files.isDirectory(root)) root = Path.of("src/main/java");
        Path base = root.resolve("com/nishiyu/enderframelib");
        Map<String, Integer> actual = new TreeMap<>();
        try (Stream<Path> files = Files.walk(base)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                Matcher m = WRITE.matcher(Files.readString(f));
                int n = 0;
                while (m.find()) n++;
                if (n > 0) actual.put(base.relativize(f).toString().replace('\\', '/'), n);
            }
        }
        for (Map.Entry<String, Integer> e : actual.entrySet()) {
            int limit = LIMITS.getOrDefault(e.getKey(), 0);
            assertTrue(e.getValue() <= limit,
                    e.getKey() + " の LayoutBox.dl* 書き込みが " + e.getValue() + " 件（上限 " + limit + "）。増やさず、C1 の断片の記憶へ置き換える");
        }
    }

    @Test
    void limitsAreTight() throws Exception {
        // 書き込みが減ったら上限も下げる（緩い上限を残さない）
        Path root = Path.of("core/src/main/java");
        if (!Files.isDirectory(root)) root = Path.of("src/main/java");
        Path base = root.resolve("com/nishiyu/enderframelib");
        for (Map.Entry<String, Integer> e : LIMITS.entrySet()) {
            Matcher m = WRITE.matcher(Files.readString(base.resolve(e.getKey())));
            int n = 0;
            while (m.find()) n++;
            assertTrue(n == e.getValue(), e.getKey() + " は " + n + " 件。上限 " + e.getValue() + " を下げる");
        }
    }
}
