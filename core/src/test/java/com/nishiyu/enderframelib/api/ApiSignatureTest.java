package com.nishiyu.enderframelib.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 公開 API の署名のラチェット（ENGINE_REARCHITECTURE_PLAN T4）。{@code api.*} の公開・protected の型と、そのコンストラクタ・メソッド・欄の
 * 署名（総称型・throws・修飾子を含む）を {@code api-signatures.txt} と比べ、1 行でも違えば失敗する。
 *
 * <p>{@code ApiBoundaryTest}（内部型が漏れていないか）とは別に、意図しない破壊的変更（引数の型・戻り値・修飾子の変化、型や
 * メソッドの削除）と、気付かない追加を止める。意図した変更なら {@code ./gradlew :core:test --tests '*ApiSignatureTest' -Papi.update}
 * で表を書き直し、差分を目で見てからコミットする。</p>
 */
class ApiSignatureTest {

    static final Path SOURCE_ROOT = Path.of("core/src/main/java");
    static final String API_PACKAGE = "com.nishiyu.enderframelib.api";
    /** 表では、読みやすさのために型の名前からこの接頭辞を除く（{@code api.dom.Element} のように書く）。 */
    static final String PREFIX = "com.nishiyu.enderframelib.";
    static final Path TABLE = Path.of("core/src/test/resources/api-signatures.txt");
    static final String HEADER = "# 公開 API の署名（ENGINE_REARCHITECTURE_PLAN T4。ApiSignatureTest が生成・検査する）。手で書き換えない。\n"
            + "# 更新: ./gradlew :core:test --tests '*ApiSignatureTest' -Papi.update（差分を目で見てからコミット）。\n"
            + "# 形式: 型の行（総称の宣言・上位の型）の下に、字下げしてメンバーの行（Java の toGenericString。名前の com.nishiyu.enderframelib. は省く）。型もメンバーも名前順。";

    /** {@code api} パッケージ以下のソースから、最上位の型の完全名を集める。 */
    static List<String> topLevelTypeNames() throws IOException {
        Path dir = SOURCE_ROOT.resolve(API_PACKAGE.replace('.', '/'));
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(p -> p.toString().endsWith(".java") && !p.getFileName().toString().equals("package-info.java"))
                    .map(p -> SOURCE_ROOT.relativize(p).toString().replace('\\', '/').replace('/', '.'))
                    .map(n -> n.substring(0, n.length() - ".java".length()))
                    .sorted()
                    .toList();
        }
    }

    private static boolean exposed(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    /** 公開・protected の型を、入れ子も含めて集める（外側が公開でない入れ子は外から見えないので入れない）。 */
    private static void collect(Class<?> c, TreeMap<String, Class<?>> out) {
        if (!exposed(c.getModifiers()) || c.isSynthetic() || c.isAnonymousClass()) return;
        out.put(c.getName(), c);
        for (Class<?> nested : c.getDeclaredClasses()) collect(nested, out);
    }

    /** 1 つの型の署名の行（型の行 1 行と、名前順のメンバーの行）。 */
    static List<String> signatureOf(Class<?> c) {
        StringBuilder head = new StringBuilder(c.toGenericString());
        Type sup = c.getGenericSuperclass();
        if (sup != null && sup != Object.class && !c.isEnum() && !c.isRecord()) head.append(" extends ").append(sup.getTypeName());
        Type[] ifs = c.getGenericInterfaces();
        if (ifs.length > 0) {
            head.append(c.isInterface() ? " extends " : " implements ");
            for (int i = 0; i < ifs.length; i++) head.append(i == 0 ? "" : ", ").append(ifs[i].getTypeName());
        }
        List<String> members = new ArrayList<>();
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            if (exposed(k.getModifiers()) && !k.isSynthetic()) members.add(k.toGenericString());
        }
        for (Method m : c.getDeclaredMethods()) {
            if (exposed(m.getModifiers()) && !m.isSynthetic() && !m.isBridge()) members.add(m.toGenericString());
        }
        for (Field f : c.getDeclaredFields()) {
            if (exposed(f.getModifiers()) && !f.isSynthetic()) members.add(f.toGenericString());
        }
        members.sort(null);
        List<String> out = new ArrayList<>(members.size() + 1);
        out.add(head.toString());
        for (String m : members) out.add("    " + m);
        return out;
    }

    /** 今のコードの署名の表（ヘッダを除く行）。 */
    static List<String> currentSignatures() throws IOException, ClassNotFoundException {
        TreeMap<String, Class<?>> types = new TreeMap<>();
        ClassLoader loader = ApiSignatureTest.class.getClassLoader();
        for (String name : topLevelTypeNames()) collect(Class.forName(name, false, loader), types);
        List<String> lines = new ArrayList<>();
        for (Class<?> c : types.values()) for (String l : signatureOf(c)) lines.add(l.replace(PREFIX, ""));
        return lines;
    }

    /** 表とコードの差（表にだけある行は「-」、コードにだけある行は「+」）。順序は表とコードの並び順。 */
    static List<String> diff(List<String> expected, List<String> actual) {
        Set<String> exp = new LinkedHashSet<>(expected), act = new LinkedHashSet<>(actual);
        List<String> out = new ArrayList<>();
        for (String l : exp) if (!act.contains(l)) out.add("- " + l.strip());
        for (String l : act) if (!exp.contains(l)) out.add("+ " + l.strip());
        return out;
    }

    @Test
    void publicApiSignaturesMatchTheTable() throws Exception {
        List<String> actual = currentSignatures();
        assertTrue(actual.size() > 300, "api.* の署名が集まっている: " + actual.size());
        if (Boolean.getBoolean("api.update")) {
            Files.writeString(TABLE, HEADER + "\n" + String.join("\n", actual) + "\n", StandardCharsets.UTF_8);
            System.out.println("api-signatures.txt を書き直した（" + actual.size() + " 行）");
            return;
        }
        assertTrue(Files.exists(TABLE), "表が無い。-Papi.update で作る: " + TABLE);
        List<String> expected = Files.readAllLines(TABLE, StandardCharsets.UTF_8).stream().filter(l -> !l.startsWith("#") && !l.isBlank()).toList();
        if (!expected.equals(actual)) {
            List<String> d = diff(expected, actual);
            fail("公開 API の署名が表と違う（" + d.size() + " 行）。意図した変更なら -Papi.update で表を書き直し、差分を見てからコミットする:\n"
                    + String.join("\n", d.isEmpty() ? List.of("（行は同じで並びだけが違う）") : d));
        }
    }

    // ---- 検査そのものの検査: 署名の変化を見落とさない ----

    @SuppressWarnings("unused")
    public static class FixtureA {
        public void take(int value) { }
        public <T extends Number> List<T> generic(List<? super T> in) { return null; }
        public static final int K = 1;
    }

    @SuppressWarnings("unused")
    public static class FixtureB {
        public void take(long value) { }
        public <T extends Number> List<T> generic(List<? super T> in) { return null; }
        public static final int K = 1;
    }

    @SuppressWarnings("unused")
    public static class FixtureC {
        public void take(int value) throws IOException { }
        public <T extends Number> List<T> generic(List<? super T> in) { return null; }
        public static final int K = 1;
        protected void hook() { }
        void packagePrivateIsNotApi() { }
        private void privateIsNotApi() { }
    }

    private static List<String> members(Class<?> c) {
        return signatureOf(c).stream().skip(1).map(l -> l.strip().replace(c.getName(), "X")).toList();
    }

    @Test
    void changingOneParameterTypeChangesTheSignature() {
        List<String> a = members(FixtureA.class), b = members(FixtureB.class);
        assertNotEquals(a, b);
        assertEquals(List.of("- public void X.take(int)", "+ public void X.take(long)"), diff(a, b));
    }

    @Test
    void throwsGenericsAndVisibilityAreCaptured() {
        List<String> c = members(FixtureC.class);
        assertTrue(c.contains("public void X.take(int) throws java.io.IOException"), c.toString());
        assertTrue(c.contains("public <T extends java.lang.Number> java.util.List<T> X.generic(java.util.List<? super T>)"), c.toString());
        assertTrue(c.contains("protected void X.hook()"), c.toString());
        assertTrue(c.contains("public static final int X.K"), c.toString());
        assertFalse(c.stream().anyMatch(l -> l.contains("IsNotApi")), "package-private と private は API ではない: " + c);
    }
}
