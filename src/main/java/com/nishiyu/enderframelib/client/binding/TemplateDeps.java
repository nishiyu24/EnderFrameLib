package com.nishiyu.enderframelib.client.binding;

import com.nishiyu.enderframelib.core.dom.HtmlNode;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * テンプレート木の各ノードについて、その部分木がどのストアキーに依存するかを索引する。
 *
 * <p>{@link WebStateStore} は以前、キーが 1 つ変わるたびに文書全体を歩き直していた。
 * テンプレート木はリロードまで不変なので、依存関係を 1 度だけ測っておけば、
 * 変更されたキーに関係しない部分木を丸ごと飛ばせる。
 *
 * <p>判定は常に安全側（依存の過剰検出）に倒してある。余分なキーが混じると
 * 枝刈りが弱くなるだけで、更新の取りこぼしは起きない。
 */
public final class TemplateDeps {

    /** {@code {{key}}}（{@code {{@env}}} は除く）。 */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{(?!@)([^}]+)}}");
    /** {@code item in list} 形式の data-for 式。 */
    private static final Pattern FOR_EXPR_PATTERN = Pattern.compile("^([a-zA-Z0-9_]+)\\s+in\\s+([a-zA-Z0-9_.-]+)$");
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_.-]*");

    /**
     * 「どのキーが変わっても再適用が必要」を表す依存マーカー。
     * i18n 属性や {@code {{@env}}} プレースホルダのように、ストアのキーとは
     * 無関係に値が変わりうるノードに付く。
     *
     * <p>祖先へは伝播するが他のキーを潰さない。つまり「この部分木は毎回辿る
     * 必要がある」だけを意味し、兄弟の部分木は従来どおり枝刈りできる。
     */
    public static final String ALWAYS = "*";

    private final Map<HtmlNode, Set<String>> subtreeDeps = new IdentityHashMap<>();
    private HtmlNode indexedRoot = null;

    /**
     * {@code root} の索引を（まだなら）作り、ルートの依存集合を返す。
     * ルートが差し替わっていれば索引を作り直す。
     */
    public Set<String> index(HtmlNode root) {
        if (root == null) return Set.of(ALWAYS);
        if (indexedRoot != root) {
            subtreeDeps.clear();
            indexedRoot = root;
            build(root);
        }
        return depsOf(root);
    }

    /**
     * 索引済みノードの部分木依存集合。索引に無いノード（リスト行のクローンなど）は
     * {@code null} を返し、呼び出し側は枝刈りせず全走査する。
     */
    public Set<String> lookup(HtmlNode node) {
        return node != null ? subtreeDeps.get(node) : null;
    }

    public void clear() {
        subtreeDeps.clear();
        indexedRoot = null;
    }

    /**
     * {@code deps} が {@code changedKeys} のどれにも関係しないなら true
     * （= その部分木は飛ばせる）。
     */
    public static boolean canSkip(Set<String> deps, Set<String> changedKeys) {
        return deps != null
                && !deps.contains(ALWAYS)
                && java.util.Collections.disjoint(deps, changedKeys);
    }

    private Set<String> depsOf(HtmlNode node) {
        Set<String> deps = subtreeDeps.get(node);
        return deps != null ? deps : Set.of(ALWAYS);
    }

    /** 部分木の依存キーを後順で集めて記録し、その集合を返す。 */
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

    /** ノード自身（子は含まない）が参照するストアキーを {@code out} に加える。 */
    static void collectOwnDeps(HtmlNode node, Set<String> out) {
        addPlaceholderDeps(node.text, out);

        for (Map.Entry<String, String> entry : node.attrs.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            addPlaceholderDeps(value, out);
            if (value == null) continue;

            switch (name) {
                // i18n は言語設定で変わるため、ストアのキーとは独立に再適用が必要。
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
                case "data-if", "mc-if", "data-show" -> addExpressionDeps(value, out);
                case "data-class" -> {
                    for (String item : value.split(",")) {
                        int colon = item.indexOf(':');
                        if (colon > 0) addExpressionDeps(item.substring(colon + 1), out);
                    }
                }
                case "data-for", "mc-for" -> {
                    Matcher fm = FOR_EXPR_PATTERN.matcher(value.trim());
                    addKey(fm.matches() ? fm.group(2).trim() : value.trim(), out);
                }
                default -> { }
            }
        }
    }

    private static void addPlaceholderDeps(String text, Set<String> out) {
        if (text == null || !text.contains("{{")) return;
        // {{@fps}} のような環境プレースホルダはストアのキーでは駆動されない。
        if (text.contains("{{@")) out.add(ALWAYS);
        Matcher m = PLACEHOLDER_PATTERN.matcher(text);
        while (m.find()) {
            addKey(m.group(1).trim(), out);
        }
    }

    /** 条件式に現れる識別子をすべて依存キーとして拾う（安全側の過剰検出）。 */
    private static void addExpressionDeps(String expr, Set<String> out) {
        if (expr == null) return;
        Matcher m = IDENTIFIER_PATTERN.matcher(expr);
        while (m.find()) {
            addKey(m.group(), out);
        }
    }

    /** キーそのものと、ドット区切りの先頭セグメントの両方を登録する。 */
    private static void addKey(String key, Set<String> out) {
        if (key == null || key.isEmpty()) return;
        out.add(key);
        int dot = key.indexOf('.');
        if (dot > 0) out.add(key.substring(0, dot));
    }
}
