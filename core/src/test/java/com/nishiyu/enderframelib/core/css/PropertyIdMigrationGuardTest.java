package com.nishiyu.enderframelib.core.css;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.type.TypeMirror;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/**
 * Milestone 2: 算出スタイルの読み取りは添字（{@code CssProperty.Id.*}）で行う、という移行が崩れないこと。
 * 本番のソースを型つきで解析し（受け手の型まで見る）、次を禁じる。
 * <ol>
 *   <li>{@code Map} など {@code ComputedStyle} 以外の受け手に {@code CssProperty.Id.*} を渡す
 *       （{@code Map.get(Object)} は int を受け付けてしまい、コンパイルは通るのに黙って常に null を返す）</li>
 *   <li>{@code ComputedStyle} の読み取りに名前の文字列を渡す（ハッシュ引きに戻る）。名前を実行時に決める
 *       {@code StyleAnimator}（トランジションの対象名）だけは許す</li>
 * </ol>
 */
class PropertyIdMigrationGuardTest {
    private static final Set<String> READERS = Set.of("get", "getOrDefault", "containsKey", "length", "lengthOrDefault",
            "color", "number", "keywordOf", "keywordFresh", "keyword", "is");
    /** {@code StyleTable} の添字版（引数が {@code int}）。{@code Map.get(Object)} などとは別のメソッドで、取り違えても型エラーになる。 */
    private static final Set<String> TABLE_ID_METHODS = Set.of("textById", "putById", "putValueById", "putIfAbsentById",
            "putIfAbsentValueById");
    /** 名前が実行時に決まる（利用者の {@code transition-property} など）ため、文字列の版を使う場所。 */
    private static final Set<String> RUNTIME_NAME_FILES = Set.of("StyleAnimator.java");

    @Test
    void readsUseIdsAndOnlyOnComputedStyle() throws Exception {
        Path src = Stream.of(Path.of("core/src/main/java"), Path.of("src/main/java")).filter(Files::isDirectory).findFirst().orElseThrow();
        List<Path> files;
        try (Stream<Path> w = Files.walk(src)) {
            files = w.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
        Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(files);
        JavacTask task = (JavacTask) compiler.getTask(null, fm, d -> {},
                List.of("-classpath", System.getProperty("java.class.path"), "-proc:none", "-encoding", "UTF-8"), null, units);
        Iterable<? extends CompilationUnitTree> trees = task.parse();
        task.analyze();
        Trees trees2 = Trees.instance(task);
        List<String> violations = new ArrayList<>();
        int[] idCalls = {0};
        for (CompilationUnitTree cu : trees) {
            String file = Path.of(cu.getSourceFile().getName()).getFileName().toString();
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree n, Void v) {
                    if (!n.getArguments().isEmpty() && n.getMethodSelect() instanceof MemberSelectTree ms) {
                        String method = ms.getIdentifier().toString();
                        ExpressionTree first = n.getArguments().get(0);
                        boolean idArg = first.toString().startsWith("CssProperty.Id.");
                        TypeMirror recv = trees2.getTypeMirror(new TreePath(getCurrentPath(), ms.getExpression()));
                        boolean onStyle = recv != null && recv.toString().endsWith("core.css.ComputedStyle");
                        // 作業表（StyleTable）の添字版（引数が int で、Map の Object 引数には化けない）だけは添字を渡してよい
                        boolean onTableIdMethod = recv != null && recv.toString().endsWith("core.css.StyleTable")
                                && TABLE_ID_METHODS.contains(method);
                        long line = cu.getLineMap().getLineNumber(trees2.getSourcePositions().getStartPosition(cu, n));
                        if (idArg) {
                            idCalls[0]++;
                            if (!onStyle && !onTableIdMethod) violations.add(file + ":" + line + " " + ms + "(" + first + ") の受け手が " + recv + "（ComputedStyle ではない）");
                        } else if (onStyle && READERS.contains(method) && !RUNTIME_NAME_FILES.contains(file)) {
                            TypeMirror t = trees2.getTypeMirror(new TreePath(getCurrentPath(), first));
                            if (t != null && t.toString().equals("java.lang.String")) {
                                violations.add(file + ":" + line + " " + ms + "(" + first + ") は名前の文字列で読んでいる（CssProperty.Id.* を使う）");
                            }
                        }
                    }
                    return super.visitMethodInvocation(n, v);
                }
            }.scan(cu, null);
        }
        assertTrue(idCalls[0] > 200, "解析が空振りしていない（添字での読み取りが 200 箇所以上ある）: " + idCalls[0]);
        assertTrue(violations.isEmpty(), "\n" + String.join("\n", violations));
    }
}
