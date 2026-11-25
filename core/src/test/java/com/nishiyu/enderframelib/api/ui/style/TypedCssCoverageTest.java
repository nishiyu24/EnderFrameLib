package com.nishiyu.enderframelib.api.ui.style;

import com.nishiyu.enderframelib.api.EnderEngine;
import com.nishiyu.enderframelib.api.WebViewSurface;
import com.nishiyu.enderframelib.api.dom.Element;
import com.nishiyu.enderframelib.core.css.PropertyRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 型付きスタイルの生成物（{@code Keyword}・{@code CssProperties}。{@code scripts/GenTypedCss.java}）が {@code PropertyRegistry} と揃っていること。
 * 登録表にプロパティや列挙子を足して生成器を再実行し忘れると、ここで落ちる。
 */
class TypedCssCoverageTest {

    /** 生成器と同じ規則（プロパティ名 → 関数名）。 */
    private static String method(String property) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : property.toCharArray()) {
            if (c == '-') {
                up = true;
                continue;
            }
            sb.append(up ? Character.toUpperCase(c) : c);
            up = false;
        }
        String r = sb.toString();
        return r.equals("float") ? "cssFloat" : r;
    }

    @Test
    void everyRegisteredPropertyHasATypedFunction() {
        List<String> missing = new ArrayList<>();
        for (PropertyRegistry.Property p : PropertyRegistry.all()) {
            if (p.name().startsWith("-")) continue; // ベンダー接頭辞は公開しない
            boolean found = Arrays.stream(Css.class.getMethods())
                    .anyMatch(m -> m.getName().equals(method(p.name())) && Modifier.isStatic(m.getModifiers())
                            && m.getReturnType() == Declaration.class);
            if (!found) missing.add(p.name());
        }
        assertTrue(missing.isEmpty(), "関数の無いプロパティ（scripts/GenTypedCss.java を再実行）: " + missing);
    }

    @Test
    void keywordEnumsMatchTheEngineEnums() throws ReflectiveOperationException {
        for (PropertyRegistry.Property p : PropertyRegistry.all()) {
            if (p.keywords() == null || p.name().startsWith("-")) continue;
            Class<?> api = Class.forName(Keyword.class.getName() + "$" + p.keywords().getSimpleName());
            List<String> expected = new ArrayList<>();
            for (Object c : p.keywords().getEnumConstants()) {
                expected.add(((Enum<?>) c).name().toLowerCase(Locale.ROOT).replace('_', '-'));
            }
            List<String> actual = new ArrayList<>();
            for (Object c : api.getEnumConstants()) actual.add(((CssValue) c).css());
            assertEquals(expected, actual, p.name() + " の列挙（scripts/GenTypedCss.java を再実行）");
            Method m = Css.class.getMethod(method(p.name()), api);
            assertEquals(Declaration.class, m.getReturnType(), p.name());
        }
    }

    /** 算出値が書いた値と違って正しいもの（相対の値は親から解決される。親は既定の 400・text-align: start・ltr）。 */
    private static final Map<String, String> RELATIVE = Map.of("font-weight:bolder", "bold", "font-weight:lighter", "100",
            "text-align:match-parent", "left"); // match-parent は親の start を親の ltr で解いた left（CSS Text 3 §7.1）

    /** 生成した全キーワード（登録表の列挙と、生成器の表で補った {@code display} など）が、エンジンにそのまま受け入れられること（算出値が同じ）。 */
    @Test
    void tableKeywordsAreAcceptedByTheEngine() throws ReflectiveOperationException {
        Map<String, Class<?>> byProperty = new TreeMap<>();
        for (Method m : Css.class.getMethods()) {
            if (m.getReturnType() == Declaration.class && m.getParameterCount() == 1 && m.getParameterTypes()[0].getEnclosingClass() == Keyword.class
                    && m.getParameterTypes()[0] != Keyword.None.class) {
                byProperty.put(m.getName(), m.getParameterTypes()[0]);
            }
        }
        List<String> rejected = new ArrayList<>();
        try (WebViewSurface surface = EnderEngine.empty().size(200, 200).build()) {
            Element body = surface.dom().getBody();
            for (var e : byProperty.entrySet()) {
                for (Object c : e.getValue().getEnumConstants()) {
                    Declaration d = (Declaration) Css.class.getMethod(e.getKey(), e.getValue()).invoke(null, c);
                    if (d.property().equals("font-family")) continue; // 総称名は UA が実フォントへ解決する
                    PropertyRegistry.Property prop = PropertyRegistry.get(d.property());
                    if (prop != null && prop.isShorthand()) continue; // ショートハンドは算出スタイルに残らない（ロングハンド側で確かめる）
                    Element el = surface.dom().createElement("div");
                    body.appendChild(el);
                    el.getStyle().setProperty(d.property(), d.currentCss());
                    surface.frame(0, true);
                    String computed = surface.dom().getComputedStyle(el).getPropertyValue(d.property());
                    String expected = RELATIVE.getOrDefault(d.property() + ":" + d.currentCss(), d.currentCss());
                    if (!expected.equals(computed)) rejected.add(d.property() + ": " + d.currentCss() + " → " + computed);
                    body.removeChild(el);
                }
            }
        }
        assertTrue(rejected.isEmpty(), "エンジンが受け付けない（算出値が違う）キーワード:\n  " + String.join("\n  ", rejected));
    }

    @Test
    void generatedSourcesStayUnderTheClassSizeRule() throws java.io.IOException {
        Path dir = Path.of("core/src/main/java/com/nishiyu/enderframelib/api/ui/style");
        List<String> generated = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path src : files.toList()) {
                List<String> lines = Files.readAllLines(src);
                if (!lines.getFirst().startsWith("// 生成物")) continue;
                generated.add(src.getFileName().toString());
                assertTrue(lines.size() <= 600, src.getFileName() + " は " + lines.size() + " 行");
            }
        }
        assertTrue(generated.contains("Keyword.java") && generated.contains("BoxProperties.java"), "生成物が見つからない: " + generated);
        // 継承の鎖の末尾を Css が継承している（群を足したら Css の親も付け替える）
        Class<?> c = Css.class.getSuperclass();
        List<String> chain = new ArrayList<>();
        for (; c != Object.class; c = c.getSuperclass()) chain.add(c.getSimpleName() + ".java");
        assertEquals(generated.stream().filter(n -> n.endsWith("Properties.java")).sorted().toList(),
                chain.stream().sorted().toList(), "Css が全ての群を継承していること");
    }
}
