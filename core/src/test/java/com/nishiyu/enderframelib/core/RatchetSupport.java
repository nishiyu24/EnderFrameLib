package com.nishiyu.enderframelib.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 構造のラチェット（ENGINE_REARCHITECTURE_PLAN T1）が共有する部品: ソース木の場所、「鍵 | 値 | …」の表の読み書き、更新モード。
 *
 * <p>表は {@code core/src/test/resources} の文字列ファイル。{@code #} で始まる行は注釈で、書き直しても残る。
 * {@code ./gradlew :core:test --tests "*RatchetTest" -Dratchet.update=true} で、各表を現在の実測値へ書き直す
 * （差分を目で確かめてからコミットする。上限を上げる書き直しは、理由をコミットの説明に書く）。</p>
 */
final class RatchetSupport {
    private RatchetSupport() {}

    /** 表を現在値で書き直すモード。 */
    static final boolean UPDATE = Boolean.getBoolean("ratchet.update");

    /** {@code core/src/main/java/com/nishiyu/enderframelib}（作業ディレクトリがルートでも {@code core} でもよい）。 */
    static Path mainRoot() {
        Path p = Path.of("core/src/main/java/com/nishiyu/enderframelib");
        return Files.isDirectory(p) ? p : Path.of("src/main/java/com/nishiyu/enderframelib");
    }

    /** 表のファイルの場所（{@code core/src/test/resources/<name>}）。 */
    static Path table(String name) {
        Path p = Path.of("core/src/test/resources", name);
        return Files.isDirectory(Path.of("core/src/test/resources")) ? p : Path.of("src/test/resources", name);
    }

    /** {@code root} の下の {@code .java} を、{@code root} からの相対パス（区切りは {@code /}）の順で返す。 */
    static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    static String rel(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    /** 表を読む: 鍵 → 残りの欄（前後の空白は除く）。欄の数が {@code columns} でない行は失敗。 */
    static Map<String, String[]> read(Path file, int columns) throws IOException {
        Map<String, String[]> out = new LinkedHashMap<>();
        if (!Files.exists(file)) return out;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] f = line.split("\\|", columns);
            if (f.length != columns) throw new IllegalStateException(file + ": 「" + columns + " 欄」でない行: " + line);
            String[] rest = new String[columns - 1];
            for (int i = 1; i < columns; i++) rest[i - 1] = f[i].trim();
            if (out.put(f[0].trim(), rest) != null) throw new IllegalStateException(file + ": 鍵が重複: " + f[0].trim());
        }
        return out;
    }

    /** 表を書き直す。既存の先頭の注釈（{@code #} の行）は残し、行は渡した順で書く。 */
    static void write(Path file, String defaultHeader, List<String> rows) throws IOException {
        List<String> header = new ArrayList<>();
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.startsWith("#")) header.add(line);
                else if (!line.isBlank()) break;
            }
        }
        if (header.isEmpty()) header.addAll(List.of(defaultHeader.split("\n")));
        StringBuilder sb = new StringBuilder();
        for (String h : header) sb.append(h).append('\n');
        for (String r : rows) sb.append(r).append('\n');
        Files.createDirectories(file.getParent());
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    /** 文字列リテラルとコメントを空白に置き換えた本文（行の位置は保つ）。検査器が注釈や文字列の中を拾わないように。 */
    static String stripCommentsAndStrings(String src, boolean keepStrings) {
        StringBuilder out = new StringBuilder(src.length());
        int n = src.length();
        for (int i = 0; i < n; ) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') { out.append(' '); i++; }
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                out.append("  ");
                i += 2;
                while (i < n && !(src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/')) { out.append(src.charAt(i) == '\n' ? '\n' : ' '); i++; }
                if (i < n) { out.append("  "); i += 2; }
            } else if (c == '"' && i + 2 < n && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
                // テキストブロック
                out.append("\"\"\"");
                i += 3;
                while (i < n && !(src.startsWith("\"\"\"", i))) { char d = src.charAt(i); out.append(keepStrings || d == '\n' ? d : ' '); i++; }
                if (i < n) { out.append("\"\"\""); i += 3; }
            } else if (c == '"' || c == '\'') {
                out.append(c);
                i++;
                while (i < n && src.charAt(i) != c && src.charAt(i) != '\n') {
                    if (src.charAt(i) == '\\' && i + 1 < n) { out.append(keepStrings ? src.substring(i, i + 2) : "  "); i += 2; continue; }
                    out.append(keepStrings ? src.charAt(i) : ' ');
                    i++;
                }
                if (i < n && src.charAt(i) == c) { out.append(c); i++; }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** 合成のソースを置く使い捨てのディレクトリを作り、{@code body} の後で消す。 */
    static void withTempSources(Map<String, String> files, IoConsumer<Path> body) throws IOException {
        Path dir = Files.createTempDirectory("ratchet");
        try {
            for (var e : files.entrySet()) {
                Path f = dir.resolve(e.getKey());
                Files.createDirectories(f.getParent());
                Files.writeString(f, e.getValue(), StandardCharsets.UTF_8);
            }
            body.accept(dir);
        } finally {
            try (Stream<Path> w = Files.walk(dir)) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @FunctionalInterface
    interface IoConsumer<T> {
        void accept(T t) throws IOException;
    }
}
