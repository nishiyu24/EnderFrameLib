package com.nishiyu.enderframelib.core.dom;

import static com.nishiyu.enderframelib.core.dom.Bindings.FOR_EXPR_PATTERN;
import static com.nishiyu.enderframelib.core.dom.Bindings.splitI18n;

import com.nishiyu.enderframelib.core.dom.node.HostSyntax;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * キー単位の依存追跡（旧 {@code ReactiveStateStore.TemplateDeps}。M5-2 で {@link Bindings} の入れ子から出した）。
 * 部分木ごとに、テンプレートが読む状態のキーの集合を覚える。{@link #ALWAYS} はどのキーが変わっても解き直す印。
 */
public final class TemplateDeps {

    private static final Pattern DEP_PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{(?!@)([^}]+)}}");
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_.-]*");

    public static final String ALWAYS = "*";

    private final Map<HtmlNode, Set<String>> subtreeDeps = new IdentityHashMap<>();
    private HtmlNode indexedRoot = null;

    public Set<String> index(HtmlNode root) {
        if (root == null) return Set.of(ALWAYS);
        if (indexedRoot != root) {
            subtreeDeps.clear();
            indexedRoot = root;
            build(root);
        }
        return depsOf(root);
    }

    public Set<String> lookup(HtmlNode node) {
        return node != null ? subtreeDeps.get(node) : null;
    }

    public void clear() {
        subtreeDeps.clear();
        indexedRoot = null;
    }

    public static boolean canSkip(Set<String> deps, Set<String> changedKeys) {
        return deps != null
                && !deps.contains(ALWAYS)
                && Collections.disjoint(deps, changedKeys);
    }

    private Set<String> depsOf(HtmlNode node) {
        Set<String> deps = subtreeDeps.get(node);
        return deps != null ? deps : Set.of(ALWAYS);
    }

    private Set<String> build(HtmlNode node) {
        Set<String> deps = new HashSet<>();
        collectOwnDeps(node, deps);
        for (HtmlNode child : node.children) {
            deps.addAll(build(child));
        }
        Set<String> frozen = Set.copyOf(deps);
        subtreeDeps.put(node, frozen);
        return frozen;
    }

    static void collectOwnDeps(HtmlNode node, Set<String> out) {
        addPlaceholderDeps(node.text, out);

        for (Map.Entry<String, String> entry : node.attrs.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            addPlaceholderDeps(value, out);
            if (value == null) continue;

            switch (HostSyntax.canonicalOf(name)) {
                case "data-i18n", "data-i18n-placeholder", "data-i18n-title" -> out.add(ALWAYS);
                case "data-model", "data-list" -> addKey(value.trim(), out);
                case "data-bind" -> {
                    for (String part : value.split(",")) {
                        String key = part.trim();
                        int colon = key.indexOf(':');
                        if (colon > 0) key = key.substring(colon + 1).trim();
                        addKey(key, out);
                    }
                }
                case "data-if", "data-show" -> addExpressionDeps(value, out);
                case "data-class" -> {
                    for (String item : value.split(",")) {
                        int colon = item.indexOf(':');
                        if (colon > 0) addExpressionDeps(item.substring(colon + 1), out);
                    }
                }
                case "data-for" -> {
                    Matcher fm = FOR_EXPR_PATTERN.matcher(value.trim());
                    addKey(fm.matches() ? fm.group(2).trim() : value.trim(), out);
                }
                default -> { }
            }
        }
    }

    private static void addPlaceholderDeps(String text, Set<String> out) {
        if (text == null || !text.contains("{{")) return;
        if (text.contains("{{@")) out.add(ALWAYS);
        Matcher m = DEP_PLACEHOLDER_PATTERN.matcher(text);
        while (m.find()) {
            String expr = m.group(1).trim();
            if (expr.startsWith("i18n:")) {
                // 翻訳キーは依存ではなく、引数が依存。言語が変わったときの作り直しは @lang に任せる
                List<String> parts = splitI18n(expr.substring(5));
                for (int i = 1; i < parts.size(); i++) {
                    String arg = parts.get(i);
                    if (!arg.startsWith("'") && !arg.startsWith("\"")) addKey(arg, out);
                }
                continue;
            }
            addKey(expr, out);
        }
    }

    private static void addExpressionDeps(String expr, Set<String> out) {
        if (expr == null) return;
        Matcher m = IDENTIFIER_PATTERN.matcher(expr);
        while (m.find()) {
            addKey(m.group(), out);
        }
    }

    private static void addKey(String key, Set<String> out) {
        if (key == null || key.isEmpty()) return;
        out.add(key);
        int dot = key.indexOf('.');
        if (dot > 0) out.add(key.substring(0, dot));
    }
}
