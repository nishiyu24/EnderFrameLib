package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * B9 M1 のラチェット: 「前の項目といまの項目が変わったか」の判定は {@link ItemDiff} だけに置く。
 * 項目の表現を変える（変わりやすい欄を列へ移す）とき、判定が散らばっていると見落とすため。
 */
class ItemDiffRatchetTest {
    private static final List<String> ROOTS = List.of("core/src/main", "core-gl/src/main", "core-vulkan/src/main", "src/main");

    /** 項目同士の同一性比較（{@code old != cur}・{@code prevItems.get(i) == items.get(i)} など）。 */
    private static final Pattern IDENTITY = Pattern.compile(
            "\\b(old|prev\\w*|was|seenItem|oldItem)\\b(\\.get\\([^)]*\\))?\\s*[!=]=\\s*(cur\\w*|is|el|items\\.get\\([^)]*\\))\\b");
    /** 配列の要素との比較（{@code spanEl[index] == el}・{@code list.get(i) != items[i]}）。 */
    private static final Pattern ARRAY = Pattern.compile(
            "\\]\\s*[!=]=\\s*(el|item|it|cur\\w*|prev\\w*|is|was)\\b|\\b(el|item|it|cur\\w*|prev\\w*)\\b\\s*[!=]=\\s*\\w+\\[|\\.get\\([^)]*\\)\\s*[!=]=\\s*\\w+\\[");
    private static final Pattern REVERSED = Pattern.compile(
            "\\b(cur\\w*|is|el)\\b\\s*[!=]=\\s*(old|prev\\w*|was|seenItem|oldItem)\\b(\\.get\\([^)]*\\))?");
    /** ItemDiff に移した判定の定義が、別の場所に戻っていないか。 */
    private static final Pattern DEFINITION = Pattern.compile(
            "\\b(boolean)\\s+(sameStructure|sameGeometryWithoutTransition|hasFx|hasFxLayer|sameKeyAndType)\\s*\\(");

    @Test
    void itemComparisonLivesOnlyInItemDiff() throws IOException {
        List<String> hits = new ArrayList<>();
        for (String root : ROOTS) {
            Path r = Path.of(root);
            if (!Files.isDirectory(r)) continue;
            try (Stream<Path> files = Files.walk(r)) {
                for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    if (f.getFileName().toString().equals("ItemDiff.java")) continue;
                    List<String> lines = Files.readAllLines(f);
                    boolean usesItems = lines.stream().anyMatch(l -> l.contains("DisplayItem"));
                    for (int i = 0; i < lines.size(); i++) {
                        String l = lines.get(i);
                        String code = l.contains("//") ? l.substring(0, l.indexOf("//")) : l;
                        if (code.contains("null")) continue;
                        if (DEFINITION.matcher(code).find()
                                || (usesItems && (IDENTITY.matcher(code).find() || REVERSED.matcher(code).find() || ARRAY.matcher(code).find()))) {
                            hits.add(f + ":" + (i + 1) + "  " + l.trim());
                        }
                    }
                }
            }
        }
        // McMediaImages の last != now は項目ではなく画像の世代の比較（DisplayItem を扱うファイルなので許す）
        hits.removeIf(h -> h.contains("McMediaImages.java") && h.contains("last != now"));
        assertTrue(hits.isEmpty(), "項目同士の比較・判定の定義は ItemDiff へ置く: " + hits);
    }

    /** ラチェット自身が、典型的な書き方を検出できること（検出漏れで素通りしない）。 */
    @Test
    void patternsDetectTypicalViolations() {
        for (String bad : List.of("if (old != cur) {", "itemChanged = prevItems.get(i) != cur;", "if (was == is) continue;",
                "if (state.seenItem != el) {", "if (cur != old && x) {", "return cur == prevItems.get(i);")) {
            assertTrue(IDENTITY.matcher(bad).find() || REVERSED.matcher(bad).find(), bad);
        }
        assertTrue(ARRAY.matcher("if (spanEl[index] == el && x) {").find());
        assertTrue(ARRAY.matcher("if (list.get(from + i) != items[i]) return false;").find());
        assertTrue(DEFINITION.matcher("private static boolean sameStructure(DisplayItem a, DisplayItem b) {").find());
        assertFalse(IDENTITY.matcher("if (ItemDiff.changed(old, cur)) {").find());
    }
}
