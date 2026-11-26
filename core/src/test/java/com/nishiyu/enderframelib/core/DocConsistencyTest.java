package com.nishiyu.enderframelib.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 文書と実装のずれを機械的に見つける（ENGINE_REARCHITECTURE_PLAN A4）。
 *
 * <p>2026-11-17 の点検で、{@code core/README.md} が存在しないクラス（{@code api.ui.React}・{@code useState}）を例示し、
 * 設計書が存在しないファイル（{@code core/MEASUREMENT.md}・{@code STANDALONE_RENDERING.md}）へリンクし、性能値が文書ごとに
 * 食い違っていた。どれも利用者が「無いものを探す」ことになるので、次の 3 つを検査する。</p>
 * <ol>
 *   <li>追跡している {@code .md} の相対リンクが実在し、追跡対象外（{@code .gitignore}）の文書を指していない</li>
 *   <li>{@code core/README.md} の {@code java} のコード例がコンパイルできる</li>
 *   <li>{@code core/README.md} に書いた性能値（{@code `… ms`}）が、出所である {@code BENCHMARK.md} にある</li>
 * </ol>
 * 作業ディレクトリはリポジトリ直下（{@code core/build.gradle} の {@code test} が設定する）。
 */
class DocConsistencyTest {

    private static final Path ROOT = Path.of("").toAbsolutePath();
    private static final Path CORE_README = ROOT.resolve("core/README.md");
    private static final Path BENCHMARK = ROOT.resolve("docs/BENCHMARK.md");
    private static final Path ARCHIVED_PLANS = ROOT.resolve("docs/plan/archive");

    /**
     * 検査する文書: リポジトリ直下・mod/ 直下・docs/ と mod/docs/ の全体（計画書と ARCHIVE を含む）・追跡している core 配下の README。
     * {@code docs/plan/archive/} は過去の計画の原文（当時のパス・消えた型へのリンクをそのまま残す読み物）なので見ない。
     */
    private static List<Path> trackedDocs() throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path dir : List.of(ROOT, ROOT.resolve("mod"))) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().forEach(out::add);
            }
        }
        for (Path docs : List.of(ROOT.resolve("docs"), ROOT.resolve("mod/docs"))) {
            if (!Files.isDirectory(docs)) continue;
            try (Stream<Path> s = Files.walk(docs)) {
                s.filter(p -> p.getFileName().toString().endsWith(".md"))
                        .filter(p -> !p.startsWith(ARCHIVED_PLANS))
                        .sorted().forEach(out::add);
            }
        }
        out.add(CORE_README);
        out.add(ROOT.resolve("core/src/bench-crossengine/README.md"));
        return out;
    }

    private static final Pattern LINK = Pattern.compile("(?<!!)\\[[^\\]\\n]*\\]\\(([^)\\s]+)\\)");
    private static final Pattern FENCE = Pattern.compile("(?m)^```.*?^```[ \\t]*$", Pattern.DOTALL);
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\n]*`");

    /** {@code .gitignore} の「core 配下の .md は README 以外追跡しない」に当たるか（clone した人の手元には無い文書）。 */
    static boolean untrackedCoreDoc(Path target) {
        Path rel = ROOT.relativize(target.normalize());
        return rel.startsWith("core") && rel.getFileName().toString().endsWith(".md")
                && !rel.getFileName().toString().equals("README.md");
    }

    /** 文書のリンク切れ（実在しない・追跡対象外を指す）を「文書: リンク（理由）」の一覧で返す。 */
    static List<String> brokenLinks(Path doc, String text) {
        List<String> bad = new ArrayList<>();
        String prose = INLINE_CODE.matcher(FENCE.matcher(text).replaceAll("")).replaceAll("");
        Matcher m = LINK.matcher(prose);
        while (m.find()) {
            String href = m.group(1);
            String lower = href.toLowerCase(Locale.ROOT);
            if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:") || href.startsWith("#")) continue;
            String file = href;
            int hash = file.indexOf('#');
            if (hash >= 0) file = file.substring(0, hash);
            if (file.isEmpty()) continue;
            Path target;
            if (lower.startsWith("file:")) {
                bad.add(ROOT.relativize(doc) + ": " + href + "（マシン固有の絶対パス）");
                continue;
            }
            target = doc.getParent().resolve(file.replace("%20", " ")).normalize();
            if (!Files.exists(target)) {
                bad.add(ROOT.relativize(doc) + ": " + href + "（存在しない）");
            } else if (target.startsWith(ROOT) && untrackedCoreDoc(target)) {
                bad.add(ROOT.relativize(doc) + ": " + href + "（追跡対象外の文書。clone した人の手元には無い）");
            }
        }
        return bad;
    }

    @Test
    void relativeLinksInTrackedDocsResolve() throws IOException {
        List<String> bad = new ArrayList<>();
        for (Path doc : trackedDocs()) {
            if (!Files.exists(doc)) continue;
            bad.addAll(brokenLinks(doc, Files.readString(doc, StandardCharsets.UTF_8)));
        }
        assertTrue(bad.isEmpty(), "文書のリンク切れ（リンク先を直すか、リンクをやめて文章で説明する）:\n  " + String.join("\n  ", bad));
    }

    @Test
    void linkCheckerSeesMissingAndUntrackedTargets() {
        Path doc = ROOT.resolve("core/README.md");
        List<String> bad = brokenLinks(doc, "see [a](NO_SUCH_FILE.md) and [b](../docs/plan/ENGINE_REARCHITECTURE_PLAN.md) and [c](../README.md) and `[d](x.md)`");
        assertTrue(bad.stream().anyMatch(s -> s.contains("NO_SUCH_FILE.md")), bad.toString());
        assertTrue(bad.stream().noneMatch(s -> s.contains("ENGINE_REARCHITECTURE_PLAN.md")), "docs/ の計画書は追跡している: " + bad);
        // core 配下の README 以外の .md は追跡しない（作業メモ）。手元にあっても、リンク先にしてはいけない
        assertTrue(untrackedCoreDoc(ROOT.resolve("core/NOTES.md")));
        assertFalse(untrackedCoreDoc(ROOT.resolve("core/README.md")));
        assertFalse(untrackedCoreDoc(ROOT.resolve("docs/plan/ENGINE_REARCHITECTURE_PLAN.md")));
        assertTrue(bad.stream().noneMatch(s -> s.contains("../README.md")), bad.toString());
        assertTrue(bad.stream().noneMatch(s -> s.contains("x.md")), "コードの中はリンクとして数えない: " + bad);
    }

    // ---- README のコード例 ----

    private static final Pattern JAVA_BLOCK = Pattern.compile("(?m)^```java[ \\t]*\\R(.*?)^```[ \\t]*$", Pattern.DOTALL);
    private static final Pattern IMPORT_LINE = Pattern.compile("(?m)^\\s*import\\s+[^;]+;\\s*$");
    private static final Pattern PUBLIC_CLASS = Pattern.compile("public\\s+(?:final\\s+)?class\\s+(\\w+)");

    static List<String> javaBlocks(String markdown) {
        List<String> out = new ArrayList<>();
        Matcher m = JAVA_BLOCK.matcher(markdown.replace("\r\n", "\n"));
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** クラス 1 つのソースになった例。断片は、README の文脈にある名前（{@code html}・{@code surface} など）を引数に持つメソッドへ包む。 */
    record Snippet(String className, String source) {}

    static Snippet wrap(String block, int index) {
        Matcher pc = PUBLIC_CLASS.matcher(block);
        if (pc.find()) return new Snippet(pc.group(1), block);
        StringBuilder imports = new StringBuilder();
        Matcher im = IMPORT_LINE.matcher(block);
        while (im.find()) imports.append(im.group().trim()).append('\n');
        String body = IMPORT_LINE.matcher(block).replaceAll("");
        String name = "ReadmeSnippet" + index;
        String src = "import com.nishiyu.enderframelib.api.*;\n"
                + imports
                + "class " + name + " {\n"
                // 例が前提にする名前は欄にする（例の中で同じ名前の局所変数を宣言しても、欄なら隠すだけで誤りにならない）
                + "    String html;\n    WebViewSurface surface;\n    com.nishiyu.enderframelib.api.dom.Element rootElement;\n    double x, y;\n"
                + "    static void upload(int[] px, int w, int h) {}\n"
                + "    void run() throws Exception {\n"
                + body
                + "\n    }\n}\n";
        return new Snippet(name, src);
    }

    /** コンパイルの誤り（無ければ空）。 */
    static List<String> compileErrors(Snippet snippet) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assumptions.assumeTrue(compiler != null, "JDK のコンパイラが無い（JRE で実行している）");
        Path out = Files.createTempDirectory("readme-snippet");
        try {
            JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///" + snippet.className() + ".java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return snippet.source();
                }
            };
            DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
            List<String> options = List.of("-proc:none", "-d", out.toString(), "-cp", System.getProperty("java.class.path"));
            compiler.getTask(new StringWriter(), null, diags, options, null, List.of(file)).call();
            List<String> errors = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) errors.add("行 " + d.getLineNumber() + ": " + d.getMessage(Locale.ROOT));
            }
            return errors;
        } finally {
            try (Stream<Path> s = Files.walk(out)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void readmeJavaExamplesCompile() throws IOException {
        List<String> blocks = javaBlocks(Files.readString(CORE_README, StandardCharsets.UTF_8));
        assertTrue(blocks.size() >= 5, "core/README.md の java のコード例が見つからない（数: " + blocks.size() + "）");
        List<String> bad = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            Snippet s = wrap(blocks.get(i), i + 1);
            List<String> errors = compileErrors(s);
            if (!errors.isEmpty()) bad.add("コード例 " + (i + 1) + "（" + firstLine(blocks.get(i)) + "）\n      " + String.join("\n      ", errors));
        }
        assertTrue(bad.isEmpty(), "core/README.md のコード例がコンパイルできない（実在する API に直す）:\n  " + String.join("\n  ", bad));
    }

    @Test
    void exampleCheckerRejectsApisThatDoNotExist() throws IOException {
        // 実際に README に載っていた誤り: 存在しないクラスとフック
        String block = "import static com.nishiyu.enderframelib.api.ui.React.*;\n\nvar count = useState(0);\n";
        if (!compileErrors(wrap(block, 99)).isEmpty()) return;
        fail("存在しない API を使った例がコンパイルを通った（検査が効いていない）");
    }

    private static String firstLine(String block) {
        for (String line : block.split("\n")) if (!line.isBlank()) return line.trim();
        return "";
    }

    // ---- README の性能値 ----

    private static final Pattern MS_VALUE = Pattern.compile("`(\\d+(?:\\.\\d+)?(?:[–-]\\d+(?:\\.\\d+)?)? ms)`");

    static Set<String> msValues(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = MS_VALUE.matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    @Test
    void readmePerformanceNumbersComeFromBenchmarkDoc() throws IOException {
        String readme = Files.readString(CORE_README, StandardCharsets.UTF_8);
        String bench = Files.readString(BENCHMARK, StandardCharsets.UTF_8);
        Set<String> values = msValues(readme);
        assertTrue(values.size() >= 3, "core/README.md の性能値（`… ms`）が見つからない");
        List<String> missing = new ArrayList<>();
        for (String v : values) if (!bench.contains("`" + v + "`")) missing.add(v);
        assertTrue(missing.isEmpty(), "core/README.md の性能値が BENCHMARK.md（出所）に無い。測り直したら両方を同じ値にする: " + missing);
    }
}
