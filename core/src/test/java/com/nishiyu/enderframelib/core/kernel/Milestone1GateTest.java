package com.nishiyu.enderframelib.core.kernel;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** エンジン再設計 Milestone 1 の番人（機械的ゲート）: 合成 id の文字列生成と WeakHashMap の状態表を持ち込ませない。 */
class Milestone1GateTest {

    private static final Path MAIN = Path.of("core/src/main/java/com/nishiyu/enderframelib");

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }

    @Test
    void htmlParserGeneratesNoGenOrTxtIds() throws IOException {
        String parser = read("core/dom/parser/HtmlParser.java");
        assertFalse(parser.contains("\"gen_\""), "HtmlParser が gen_ を作っている");
        assertFalse(parser.contains("\"txt_\""), "HtmlParser が txt_ を作っている");
        assertFalse(parser.contains("class NodeId"), "文字列 id のカウンタ（NodeId）が残っている");
        assertFalse(read("common/CustomTagExpander.java").contains("NodeId"), "CustomTagExpander が合成 id を振っている");
    }

    @Test
    void interactionStateHasNoWeakHashMap() throws IOException {
        String state = read("core/dom/InteractionState.java");
        // コメントでの言及は許す。コードとして使っていないこと
        for (String line : state.split("\n")) {
            String t = line.trim();
            if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) continue;
            assertFalse(t.contains("WeakHashMap"), "InteractionState に WeakHashMap が残っている: " + t);
        }
    }

    @Test
    void noMainCodeDependsOnSyntheticIdPrefixes() throws IOException {
        try (var files = Files.walk(Path.of("core/src/main/java"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String text = Files.readString(p);
                assertFalse(text.contains("startsWith(\"gen_\")"), p + " が gen_ 接頭辞に依存している");
                assertFalse(text.contains("startsWith(\"txt_\")"), p + " が txt_ 接頭辞に依存している");
            }
        }
    }
}
