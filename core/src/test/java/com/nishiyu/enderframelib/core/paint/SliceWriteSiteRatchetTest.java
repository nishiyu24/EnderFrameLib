package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * S7 ①: {@code LayoutBox.{dlStart, dlEnd, dlOrder, paintCulled, movingMemoEpoch, movingMemoResult}} への
 * 書き込み箇所および {@code clearDlSlice} / {@code withPaintBounds} 呼び出しを 0 に固定する。
 */
class SliceWriteSiteRatchetTest {

    /** 書き込み箇所の上限。S7 完了後は全体で 0。 */
    private static final Map<String, Integer> LIMITS = Map.of();

    private static final Pattern WRITE = Pattern.compile(
            "\\b(?:dlStart|dlEnd|dlOrder|paintCulled|movingMemoEpoch|movingMemoResult)\\s*(?:[-+*/]?=(?!=)|\\+\\+|--)|\\b(?:clearDlSlice|withPaintBounds)\\(");

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
                    e.getKey() + " の LayoutBox 逆流書き込みが " + e.getValue() + " 件（上限 " + limit + "）");
        }
    }

    @Test
    void limitsAreTight() throws Exception {
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

    @Test
    void removedLegacyBackflowFieldsAndMethodsStayRemoved() {
        Set<String> bannedLayoutFields = Set.of(
                "dlStart", "dlEnd", "dlOrder", "paintCulled", "movingMemoEpoch", "movingMemoResult");
        for (Field f : LayoutBox.class.getDeclaredFields()) {
            assertFalse(bannedLayoutFields.contains(f.getName()), "LayoutBox の撤去済み欄が復活: " + f.getName());
        }
        Set<String> bannedLayoutMethods = Set.of("clearDlSlice", "withPaintBounds");
        for (Method m : LayoutBox.class.getDeclaredMethods()) {
            assertFalse(bannedLayoutMethods.contains(m.getName()), "LayoutBox の撤去済みメソッドが復活: " + m.getName());
        }
        Set<String> bannedNodeFields = Set.of("interactionOwner", "interactionFlags");
        for (Field f : HtmlNode.class.getDeclaredFields()) {
            assertFalse(bannedNodeFields.contains(f.getName()), "HtmlNode の撤去済み所有権欄が復活: " + f.getName());
        }
    }
}
