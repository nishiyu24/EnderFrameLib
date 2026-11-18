package com.nishiyu.enderframelib.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * クラスの形のラチェット（ENGINE_REARCHITECTURE_PLAN T1 ②③）。コンパイル済みのクラスをリフレクションで数える（正規表現より正確）。
 *
 * <p>ソースのファイルごと（最上位の型と、その public なメンバー型）に 4 つの値を数える:</p>
 * <ol>
 *   <li><b>pubMut</b>: 外から書ける public なインスタンス欄（static でも final でもない。外側の型もすべて public のもの）。
 *       A7（{@code LayoutBox}）の合否線に使う。</li>
 *   <li><b>methods</b>: 最上位の型が宣言するメソッドの数（合成・橋渡しのメソッド〔ラムダの本体など〕は除く）。神クラスの目安。</li>
 *   <li><b>setters</b>: {@code public static void setXxx(...)}。static な配線（A13・S1 で 0 にする）。</li>
 *   <li><b>pubStatic</b>: public static で final でない欄。グローバルな切り替え・状態（S1 で {@code EngineFlags} へ移す）。</li>
 * </ol>
 * どれかが 0 でない（methods は {@value #METHODS_FREE} を超える）ファイルは {@code class-shape-ceilings.txt} に載せ、値を上限にする。
 * 表に無いファイルは pubMut・setters・pubStatic が 0、methods が {@value #METHODS_FREE} 以下。
 * 減ったら表も下げる（methods だけ {@value #METHODS_SLACK} 個の余裕）。書き直しは {@code -Dratchet.update=true}。
 */
class ClassShapeRatchetTest {

    static final int METHODS_FREE = 100;
    static final int METHODS_SLACK = 5;
    private static final String TABLE = "class-shape-ceilings.txt";
    private static final String HEADER = """
            # ClassShapeRatchetTest の表（ENGINE_REARCHITECTURE_PLAN T1 ②③）: ソースの相対パス | pubMut | methods | setters | pubStatic
            #   pubMut    = public で static でも final でもないインスタンス欄（外側の型も public のもの）
            #   methods   = 最上位の型が宣言するメソッド数（合成のメソッドを除く）。100 以下なら 0 と書く
            #   setters   = public static void setXxx(...)（static な配線）
            #   pubStatic = public static で final でない欄（グローバルな切り替え・状態）
            # 増やさない。減ったら下げる（-Dratchet.update=true で現在値へ書き直す）。""";

    record Shape(int pubMut, int methods, int setters, int pubStatic) {
        static final Shape ZERO = new Shape(0, 0, 0, 0);

        /** 表に載せる値（methods は {@link #METHODS_FREE} 以下なら 0）。 */
        Shape tracked() {
            return new Shape(pubMut, methods > METHODS_FREE ? methods : 0, setters, pubStatic);
        }

        boolean isZero() { return equals(ZERO); }

        String row() { return pubMut + " | " + methods + " | " + setters + " | " + pubStatic; }
    }

    /** 1 つの最上位の型を数える。 */
    static Shape measure(Class<?> top) {
        int[] acc = new int[4];
        int methods = 0;
        for (Method m : top.getDeclaredMethods()) if (!m.isSynthetic() && !m.isBridge()) methods++;
        accumulate(top, true, acc);
        return new Shape(acc[0], methods, acc[2], acc[3]);
    }

    private static void accumulate(Class<?> c, boolean outerPublic, int[] acc) {
        boolean pub = outerPublic && Modifier.isPublic(c.getModifiers());
        for (Field f : c.getDeclaredFields()) {
            int mod = f.getModifiers();
            if (f.isSynthetic() || !Modifier.isPublic(mod) || Modifier.isFinal(mod)) continue;
            if (Modifier.isStatic(mod)) acc[3]++;
            else if (pub) acc[0]++;
        }
        for (Method m : c.getDeclaredMethods()) {
            int mod = m.getModifiers();
            if (m.isSynthetic() || !Modifier.isPublic(mod) || !Modifier.isStatic(mod)) continue;
            if (m.getReturnType() == void.class && m.getName().length() > 3 && m.getName().startsWith("set")
                    && Character.isUpperCase(m.getName().charAt(3)) && m.getParameterCount() > 0) acc[2]++;
        }
        for (Class<?> inner : c.getDeclaredClasses()) accumulate(inner, pub, acc);
    }

    /** ソースのファイル → 形（すべて 0 のファイルも含む）。読み込めなかった型は {@code errors} へ。 */
    static Map<String, Shape> measureAll(Path root, String basePackage, List<String> errors) throws IOException {
        Map<String, Shape> out = new TreeMap<>();
        ClassLoader cl = ClassShapeRatchetTest.class.getClassLoader();
        for (Path p : RatchetSupport.javaFiles(root)) {
            String rel = RatchetSupport.rel(root, p);
            if (rel.endsWith("package-info.java") || rel.endsWith("module-info.java")) continue;
            String fqn = basePackage + "." + rel.substring(0, rel.length() - ".java".length()).replace('/', '.');
            try {
                out.put(rel, measure(Class.forName(fqn, false, cl)));
            } catch (ClassNotFoundException | LinkageError e) {
                errors.add(fqn + ": " + e);
            }
        }
        return out;
    }

    static List<String> check(Map<String, Shape> actual, Map<String, Shape> ceilings) {
        List<String> bad = new ArrayList<>();
        for (var e : actual.entrySet()) {
            Shape a = e.getValue().tracked();
            Shape cap = ceilings.getOrDefault(e.getKey(), Shape.ZERO);
            String k = e.getKey();
            if (a.pubMut > cap.pubMut) bad.add(k + ": public な可変欄が増えた " + a.pubMut + " > " + cap.pubMut + "（読み取り口にする。状態は型の中に閉じる）");
            if (a.setters > cap.setters) bad.add(k + ": static な setter が増えた " + a.setters + " > " + cap.setters + "（面ごとの文脈〔EngineContext・HostServices〕で渡す。A13・S1）");
            if (a.pubStatic > cap.pubStatic) bad.add(k + ": public static の可変欄が増えた " + a.pubStatic + " > " + cap.pubStatic + "（切り替えは EngineFlags へ。S1）");
            int capMethods = cap.methods == 0 ? METHODS_FREE : cap.methods;
            if (a.methods > capMethods) bad.add(k + ": メソッドが増えた " + e.getValue().methods + " > " + capMethods + "（責務を別の型へ分ける。A8）");
            boolean shrunk = a.pubMut < cap.pubMut || a.setters < cap.setters || a.pubStatic < cap.pubStatic
                    || (cap.methods > 0 && (a.methods == 0 || a.methods < cap.methods - METHODS_SLACK));
            if (shrunk) bad.add(k + ": 減ったので表を下げる " + cap.row() + " → " + a.row() + (a.isZero() ? "（すべて 0 なので表から外す）" : ""));
        }
        for (String k : ceilings.keySet()) if (!actual.containsKey(k)) bad.add(k + ": 表にあるが無くなったファイル（表から外す）");
        return bad;
    }

    private static Map<String, Shape> readCeilings() throws IOException {
        Map<String, Shape> out = new TreeMap<>();
        for (var e : RatchetSupport.read(RatchetSupport.table(TABLE), 5).entrySet()) {
            String[] v = e.getValue();
            out.put(e.getKey(), new Shape(Integer.parseInt(v[0]), Integer.parseInt(v[1]), Integer.parseInt(v[2]), Integer.parseInt(v[3])));
        }
        return out;
    }

    private static Map<String, Shape> actual() throws IOException {
        List<String> errors = new ArrayList<>();
        Map<String, Shape> a = measureAll(RatchetSupport.mainRoot(), "com.nishiyu.enderframelib", errors);
        assertTrue(errors.isEmpty(), "読み込めない型（検査が効かない）:\n  " + String.join("\n  ", errors));
        assertTrue(a.size() > 300, "走査した型が少なすぎる（検査器の故障）: " + a.size());
        return a;
    }

    @Test
    void classShapesOnlyShrink() throws IOException {
        Map<String, Shape> actual = actual();
        if (RatchetSupport.UPDATE) {
            List<String> rows = new ArrayList<>();
            actual.entrySet().stream().filter(e -> !e.getValue().tracked().isZero())
                    .sorted((x, y) -> {
                        int c = Integer.compare(y.getValue().tracked().methods, x.getValue().tracked().methods);
                        return c != 0 ? c : Integer.compare(y.getValue().pubMut + y.getValue().pubStatic, x.getValue().pubMut + x.getValue().pubStatic);
                    })
                    .forEach(e -> rows.add(e.getKey() + " | " + e.getValue().tracked().row()));
            RatchetSupport.write(RatchetSupport.table(TABLE), HEADER, rows);
        }
        List<String> bad = check(actual, readCeilings());
        assertTrue(bad.isEmpty(), "クラスの形のラチェット（core/src/test/resources/" + TABLE + "）:\n  " + String.join("\n  ", bad));
    }

    /** 合計（計画書の T1・A7・A13・S1 で使う値）を出す。合否は {@link #classShapesOnlyShrink} が見る。 */
    @Test
    void totalsAreReported() throws IOException {
        int pubMut = 0, setters = 0, pubStatic = 0, godClasses = 0;
        for (Shape s : actual().values()) {
            pubMut += s.pubMut;
            setters += s.setters;
            pubStatic += s.pubStatic;
            if (s.methods > METHODS_FREE) godClasses++;
        }
        System.out.println("CLASSSHAPE pubMut=" + pubMut + " setters=" + setters + " pubStatic=" + pubStatic + " methodsOver" + METHODS_FREE + "=" + godClasses);
        assertTrue(pubMut > 0 && pubStatic > 0, "検査器の故障（0 は既知の実態と合わない）");
    }

    // ------------------------------------------------------------------ 検査器自身の検証

    public static class Sample {
        public int a;                       // pubMut
        public final int b = 0;             // final: 対象外
        int c;                              // public でない: 対象外
        public static int d;                // pubStatic
        public static volatile boolean e;   // pubStatic
        public static final int F = 1;      // 対象外
        public static void setThing(int x) {}   // setter
        public static void set(int x) {}        // 名前が set だけ: 対象外
        public static int setOther(int x) { return x; } // void でない: 対象外
        public void setInstance(int x) {}       // static でない: 対象外
        void m1() { Runnable r = () -> {}; r.run(); } // ラムダの本体（合成）は数えない

        public static class Nested {
            public int g;                   // pubMut（外側も public）
            public static void setNested(String s) {} // setter
        }

        private static class Hidden {
            public int h;                   // 外側が private: 対象外
            public static int i;            // pubStatic（static は外側の可視性に依らず数える）
        }
    }

    @Test
    void measureCountsOnlyTheIntendedMembers() {
        Shape s = measure(Sample.class);
        assertEquals(2, s.pubMut, "pubMut");
        assertEquals(2, s.setters, "setters");
        assertEquals(3, s.pubStatic, "pubStatic");
        assertEquals(5, s.methods, "methods（setThing・set・setOther・setInstance・m1。ラムダの本体とメンバー型のメソッドは数えない）");
    }

    @Test
    void checkerCatchesGrowthShrinkAndUnlisted() {
        Map<String, Shape> caps = Map.of("A.java", new Shape(3, 120, 1, 2), "Gone.java", new Shape(1, 0, 0, 0));
        Map<String, Shape> actual = Map.of(
                "A.java", new Shape(4, 110, 1, 2),   // pubMut 増 ＋ methods が余裕を超えて減
                "B.java", new Shape(0, 101, 0, 0),   // 表に無いのに 100 超
                "C.java", new Shape(0, 50, 0, 0));   // 問題なし
        List<String> bad = check(actual, caps);
        assertEquals(4, bad.size(), String.join("\n", bad));
        assertTrue(check(Map.of("A.java", new Shape(3, 117, 1, 2)), Map.of("A.java", new Shape(3, 120, 1, 2))).isEmpty(), "余裕の範囲の減少は通す");
    }
}
