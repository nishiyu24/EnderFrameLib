package com.nishiyu.enderframelib.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ゴールデンファイル（期待出力）の読み書きヘルパー。
 *
 * 意図的な挙動変更でゴールデンを更新したいときは、システムプロパティを付けて実行する:
 *   ./gradlew test -Dgolden.update=true
 * 更新後は必ず差分を目視してからコミットすること。
 */
public final class GoldenFiles {

    private static final Path GOLDEN_DIR = Paths.get("src/test/resources/golden");
    private static final Path FIXTURE_DIR = Paths.get("src/main/resources/data/enderframelib/enderframe_ui");

    private GoldenFiles() {}

    public static boolean isUpdateMode() {
        return Boolean.getBoolean("golden.update");
    }

    /** src/main/resources 配下の実リソースを入力として読む */
    public static String readFixture(String fileName) {
        return readNormalized(FIXTURE_DIR.resolve(fileName));
    }

    /**
     * actual をゴールデンと比較する。ゴールデンが無い場合、または更新モードの場合は書き出す。
     */
    public static void assertMatches(String goldenName, String actual) {
        Path golden = GOLDEN_DIR.resolve(goldenName);
        String normalized = normalize(actual);

        if (isUpdateMode() || !Files.exists(golden)) {
            write(golden, normalized);
            if (!isUpdateMode()) {
                System.out.println("ゴールデンを新規作成しました: " + golden + " — 内容を確認してコミットしてください。");
            }
            return;
        }
        assertEquals(readNormalized(golden), normalized, "ゴールデンとの差分: " + golden);
    }

    private static String readNormalized(Path path) {
        try {
            return normalize(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException("ファイルを読めません: " + path.toAbsolutePath(), e);
        }
    }

    private static void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("ファイルを書けません: " + path.toAbsolutePath(), e);
        }
    }

    /** 改行コードと末尾空白の差で落ちないようにする */
    private static String normalize(String s) {
        return s.replace("\r\n", "\n").stripTrailing();
    }
}
