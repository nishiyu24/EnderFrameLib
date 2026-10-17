package com.nishiyu.enderframelib.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * core/ が Minecraft / NeoForge / 上位層に依存しない純 Java のリーフであることを守る番人。
 * これが無いと、次に追加されたコードで MC への import がすぐ戻ってくる。
 */
public class ArchitectureTest {

    private static final Path CORE = Paths.get("src/main/java/com/nishiyu/enderframelib/core");

    /** core/ から参照してはいけないパッケージ（import でも完全修飾名でも禁止） */
    private static final String[] FORBIDDEN = {
            "net.minecraft.",
            "net.neoforged.",
            "com.mojang.",
            "com.nishiyu.enderframelib.client.",
            "com.nishiyu.enderframelib.server.",
            "com.nishiyu.enderframelib.common.",
            "com.nishiyu.enderframelib.network.",
    };

    @Test
    public void testCorePackageIsPureJava() throws IOException {
        // パスが見つからない＝実行ディレクトリが想定外。黙ってスキップすると番人が無力化するので失敗させる
        assertTrue(Files.isDirectory(CORE),
                "core パッケージが見つかりません: " + CORE.toAbsolutePath()
                        + " (テストはプロジェクトルートを作業ディレクトリとして実行してください)");

        List<String> violations = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(CORE)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList()) {
                String content = Files.readString(path);
                for (String forbidden : FORBIDDEN) {
                    if (content.contains(forbidden)) {
                        violations.add(CORE.relativize(path) + " → " + forbidden);
                    }
                }
            }
        }

        if (!violations.isEmpty()) {
            fail("【アーキテクチャ違反】 core/ が外部(Minecraft/NeoForge/上位層)に依存しています:\n  "
                    + String.join("\n  ", violations)
                    + "\n依存が必要な場合は core/ 側にインターフェース（フック）を定義し、上位層から注入してください。");
        }
    }
}
