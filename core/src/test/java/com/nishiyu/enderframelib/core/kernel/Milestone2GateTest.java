package com.nishiyu.enderframelib.core.kernel;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** エンジン再設計 Milestone 2 の番人（機械的ゲート）。コメント行は数えない。 */
class Milestone2GateTest {

    private static final Path MAIN = Path.of("core/src/main/java/com/nishiyu/enderframelib");

    private static int countCode(String relative, String needle) throws IOException {
        int n = 0;
        for (String line : Files.readString(MAIN.resolve(relative)).split("\n")) {
            String t = line.trim();
            if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) continue;
            if (t.contains(needle)) n++;
        }
        return n;
    }

    @Test
    void noStringFormatInStyleResolverOrValueComputer() throws IOException {
        assertEquals(0, countCode("core/css/StyleResolver.java", "String.format"));
        assertEquals(0, countCode("core/css/ValueComputer.java", "String.format"));
        assertEquals(0, countCode("core/css/ValueInterpolator.java", "String.format"));
        assertEquals(0, countCode("core/css/ColorParser.java", "String.format"));
        assertEquals(0, countCode("core/css/CustomPropertyRegistration.java", "String.format"));
    }

    @Test
    void computedStyleHasNoArraysCopyOf() throws IOException {
        assertEquals(0, countCode("core/css/ComputedStyle.java", "Arrays.copyOf"));
    }

    @Test
    void styleResolverDoesNotScanAllRulesForInteractivity() throws IOException {
        String resolver = Files.readString(MAIN.resolve("core/css/StyleResolver.java"));
        int at = resolver.indexOf("public static boolean hasInteractiveRules");
        assertTrue(at > 0);
        String body = resolver.substring(at, resolver.indexOf("}", at));
        assertFalse(body.contains("sheet.rules"), "hasInteractiveRules が全規則を走査している");
        assertTrue(body.contains("StyleStateAutomaton"));
    }

    @Test
    void kernelClassesExist() {
        for (String f : new String[]{"kernel/style/PackedCssValue.java",
                "kernel/compiler/StyleStateAutomaton.java",
                "kernel/compiler/BindingPatchTable.java"}) {
            assertTrue(Files.exists(MAIN.resolve("core").resolve(f)), f);
        }
    }

    @Test
    void styleStructsDuplicateLayerStaysRemoved() throws IOException {
        assertFalse(Files.exists(MAIN.resolve("core/kernel/style/StyleStructs.java")),
                "StyleStructs は本流から未参照の重複層だったので撤去した（§9.5.8）。復活させない");
        try (var files = Files.walk(MAIN)) {
            for (var f : (Iterable<java.nio.file.Path>) files.filter(x -> x.toString().endsWith(".java"))::iterator) {
                assertFalse(Files.readString(f).contains("StyleStructs"), f + " が StyleStructs を参照している");
            }
        }
    }
}
