package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.CssParser.CssTokenizer;
import com.nishiyu.enderframelib.core.css.Selector.SelectorToken;
import com.nishiyu.enderframelib.core.css.StyleSheet.CascadeOrigin;
import com.nishiyu.enderframelib.core.css.StyleSheet.StyleRule;
import com.nishiyu.enderframelib.core.css.ValueComputer.CssWideKeyword;
import com.nishiyu.enderframelib.core.dom.HtmlNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * カスケード（どの宣言が勝つか）と、その結果から算出スタイルを確定する段。
 *
 * プロパティごとの知識（継承・初期値・ショートハンド・無効化種別）は
 * {@link PropertyRegistry} が持ち、ここには置かない。
 */
public class StyleResolver {

    public static StyleSet resolveStyleSet(HtmlNode node, Map<String, String> inheritedStyle, StyleSheet sheet) {
        boolean interactive = hasInteractiveRules(node, sheet);
        return new StyleSet(node, inheritedStyle, sheet, interactive);
    }

    /**
     * 指定された状態参照先のもとで、継承・シート規則（詳細度＋出現順）・インラインスタイル・!important を
     * CSS カスケード順に沿って 1 パスで解決する。
     *
     * @param inheritedStyle 親の<b>算出</b>スタイル。継承するプロパティとカスタムプロパティの初期値になり、
     *                       {@code all: inherit} の参照先にもなる
     */
    public static Map<String, String> resolveDeclarations(HtmlNode node, Map<String, String> inheritedStyle,
                                                          StyleSheet sheet, SelectorMatcher.StateLookup stateLookup) {
        SelectorMatcher.StateLookup lookup = stateLookup != null ? stateLookup : SelectorMatcher.STATIC_ATTRIBUTES;
        Map<String, String> parent = inheritedStyle != null ? inheritedStyle : Map.of();
        Map<String, String> result = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : parent.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("--") || inheritsAsLonghand(key)) result.put(key, entry.getValue());
        }

        // revert の巻き戻し先。UA オリジンの宣言だけでカスケードした結果（継承値を含む）
        Map<String, String> uaValues = new LinkedHashMap<>(result);

        Map<String, String> inlineNormal = null;
        Map<String, String> inlineImportant = null;
        if (node != null && node.attrs.containsKey("style")) {
            inlineNormal = new LinkedHashMap<>();
            inlineImportant = new LinkedHashMap<>();
            for (Map.Entry<String, String> decl : CssTokenizer.parseDeclarations(node.attrs.get("style")).entrySet()) {
                String val = decl.getValue();
                String stripped = stripImportant(val);
                if (stripped != null) {
                    inlineImportant.put(decl.getKey(), stripped);
                } else {
                    inlineNormal.put(decl.getKey(), val);
                }
            }
        }

        if (sheet != null && node != null) {
            // 1. 通常宣言（UA -> Author、詳細度＋出現順）
            for (StyleRule rule : sheet.rules) {
                if (!rule.isBefore && !rule.isAfter && !rule.specificity.important() && rule.matches(node, lookup)) {
                    if (rule.specificity.origin() == CascadeOrigin.USER_AGENT) {
                        applyDeclarations(result, rule.properties, null, parent);
                        applyDeclarations(uaValues, rule.properties, null, parent);
                    } else {
                        applyDeclarations(result, rule.properties, uaValues, parent);
                    }
                }
            }
        }

        // 2. インラインの通常宣言（Author 通常より強く、!important より弱い）
        if (inlineNormal != null && !inlineNormal.isEmpty()) {
            applyDeclarations(result, inlineNormal, uaValues, parent);
        }

        if (sheet != null && node != null) {
            // 3. 作者の !important 宣言
            for (StyleRule rule : sheet.rules) {
                if (!rule.isBefore && !rule.isAfter && rule.specificity.important()
                        && rule.specificity.origin() != CascadeOrigin.USER_AGENT
                        && rule.matches(node, lookup)) {
                    applyDeclarations(result, rule.properties, uaValues, parent);
                }
            }
        }

        // 4. インラインの !important 宣言
        if (inlineImportant != null && !inlineImportant.isEmpty()) {
            applyDeclarations(result, inlineImportant, uaValues, parent);
        }

        if (sheet != null && node != null) {
            // 5. UA の !important 宣言（CSS カスケードで最優先）
            for (StyleRule rule : sheet.rules) {
                if (!rule.isBefore && !rule.isAfter && rule.specificity.important()
                        && rule.specificity.origin() == CascadeOrigin.USER_AGENT
                        && rule.matches(node, lookup)) {
                    applyDeclarations(result, rule.properties, null, parent);
                }
            }
        }

        return result;
    }

    /** 継承の初期値として親から受け取るのはロングハンドだけ（ショートハンドは展開済みの結果が届く）。 */
    private static boolean inheritsAsLonghand(String key) {
        PropertyRegistry.Property p = PropertyRegistry.get(key);
        return p != null && p.inherited() && !p.isShorthand();
    }

    /**
     * 宣言を宣言順に適用する。
     *
     * ショートハンドを適用するときは、先行するルールのロングハンド（入れ子のショートハンドを含む）を
     * 消してから置く。こうしないと算出時の展開が {@code putIfAbsent} なので、後に書いた
     * ショートハンドが前に書いたロングハンドに負ける。
     *
     * @param revertSource {@code revert} の巻き戻し先。UA オリジンの宣言を適用するときは null
     *                     （UA 自身の {@code revert} は {@code unset} になる）
     */
    private static void applyDeclarations(Map<String, String> target, Map<String, String> decls,
                                          Map<String, String> revertSource, Map<String, String> parent) {
        for (Map.Entry<String, String> decl : decls.entrySet()) {
            String key = decl.getKey();
            String value = decl.getValue();
            CssWideKeyword keyword = CssWideKeyword.of(value);

            if ("all".equals(key)) {
                if (keyword != null) applyAll(target, keyword, revertSource, parent);
                continue;
            }
            if (keyword == CssWideKeyword.REVERT && revertSource != null) {
                revert(target, key, revertSource);
                continue;
            }

            PropertyRegistry.Property p = PropertyRegistry.get(key);
            if (p != null && p.expander() != null && keyword == null && !value.contains("var(")
                    && p.expander().apply(value.trim()).isEmpty()) {
                continue; // 解釈できないショートハンドは宣言ごと無効（先行するロングハンドを消さない）
            }
            for (String sub : PropertyRegistry.subproperties(key)) target.remove(sub);
            target.put(key, keyword == CssWideKeyword.REVERT ? "unset" : value);
        }
    }

    private static void revert(Map<String, String> target, String key, Map<String, String> source) {
        List<String> keys = new ArrayList<>();
        keys.add(key);
        keys.addAll(PropertyRegistry.subproperties(key));
        for (String k : keys) {
            String v = source.get(k);
            if (v != null) target.put(k, v);
            else target.remove(k);
        }
    }

    /** {@code all} はカスタムプロパティと {@code direction} 以外のすべてを書き換える。 */
    private static void applyAll(Map<String, String> target, CssWideKeyword keyword,
                                 Map<String, String> revertSource, Map<String, String> parent) {
        target.keySet().removeIf(k -> !k.startsWith("--") && !"direction".equals(k));
        if (keyword == CssWideKeyword.REVERT && revertSource != null) {
            revertSource.forEach((k, v) -> {
                if (!k.startsWith("--") && !"direction".equals(k)) target.put(k, v);
            });
            return;
        }
        for (String longhand : PropertyRegistry.allLonghands()) {
            boolean fromParent = keyword == CssWideKeyword.INHERIT
                    || (keyword != CssWideKeyword.INITIAL && PropertyRegistry.isInherited(longhand));
            String value = fromParent ? parent.get(longhand) : null;
            if (value == null) value = PropertyRegistry.initialValue(longhand);
            if (value != null) target.put(longhand, value);
        }
    }

    private static String stripImportant(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        int bang = trimmed.lastIndexOf('!');
        if (bang < 0) return null;
        if (!trimmed.substring(bang + 1).trim().equalsIgnoreCase("important")) return null;
        return trimmed.substring(0, bang).trim();
    }

    /**
     * このノードがシート内の対話疑似クラス（:hover / :active / :focus*）を含む複合セレクタの
     * 対象になり得るかを判定する。
     */
    public static boolean hasInteractiveRules(HtmlNode node, StyleSheet sheet) {
        if (node == null || sheet == null) return false;
        for (StyleRule rule : sheet.rules) {
            if ((rule.stateMask & ElementState.INTERACTIVE_MASK) == 0) continue;
            for (SelectorToken token : rule.parsedSelector().compounds()) {
                int mask = token.stateFlags;
                for (SelectorToken nt : token.notSelectors) mask |= nt.stateFlags;
                if ((mask & ElementState.INTERACTIVE_MASK) != 0
                        && SelectorMatcher.matchesCompoundWithoutInteractiveState(token, node)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 2 つの算出スタイルの差分がレイアウト（measure / layout）の再実行を要するかどうかを判定する。
     * ペイント専用プロパティしか変わっていなければ false を返し、repaint のみで済ませる。
     */
    public static boolean isLayoutAffectingChange(ComputedStyle oldStyle, ComputedStyle newStyle) {
        if (oldStyle == null || newStyle == null) return true;
        if (Float.compare(oldStyle.fontSizePx(), newStyle.fontSizePx()) != 0
                || Float.compare(oldStyle.rootFontSizePx(), newStyle.rootFontSizePx()) != 0) {
            return true;
        }
        Map<String, String> m1 = oldStyle.asMap();
        Map<String, String> m2 = newStyle.asMap();
        if (m1.equals(m2)) return false;

        for (Map.Entry<String, String> e : m1.entrySet()) {
            String key = e.getKey();
            if (!java.util.Objects.equals(e.getValue(), m2.get(key)) && !PropertyRegistry.isPaintOnly(key)) {
                return true;
            }
        }
        for (Map.Entry<String, String> e : m2.entrySet()) {
            String key = e.getKey();
            if (!m1.containsKey(key) && !PropertyRegistry.isPaintOnly(key)) {
                return true;
            }
        }
        return false;
    }

    public static ComputedStyle finalizeComputedStyle(Map<String, String> rawStyle, StyleSheet sheet, Map<String, String> env) {
        return finalizeComputedStyle(rawStyle, sheet, env, ComputedStyle.DEFAULT_FONT_SIZE, ComputedStyle.DEFAULT_FONT_SIZE);
    }

    /**
     * 親のフォントサイズを基準に算出スタイルを確定する（親の算出スタイルとビューポートが分からない呼び出し用）。
     * {@code inherit} は初期値になり、{@code font-size} の {@code vw} / {@code vh} は 0 で解かれる。
     */
    public static ComputedStyle finalizeComputedStyle(Map<String, String> rawStyle, StyleSheet sheet,
                                                      Map<String, String> env,
                                                      float parentFontSize, float rootFontSize) {
        return finalizeComputedStyle(rawStyle, sheet, env, Map.of(), parentFontSize, rootFontSize, 0, 0);
    }

    /**
     * カスケードの結果から算出スタイルを確定する。
     *
     * 順序は CSS と同じで、{@code var()} の置換 → ショートハンドの展開 → 算出値
     * （CSS 全域キーワード・{@code font-size}・{@code em} / {@code rem}・{@code currentcolor}）。
     *
     * @param parentStyle    親の算出スタイル（{@code inherit} の参照先）
     * @param parentFontSize 親のフォントサイズ（px）。{@code font-size} 自身の em / % の基準
     * @param rootFontSize   ルート要素のフォントサイズ（px）。{@code rem} の基準
     */
    public static ComputedStyle finalizeComputedStyle(Map<String, String> rawStyle, StyleSheet sheet,
                                                      Map<String, String> env, Map<String, String> parentStyle,
                                                      float parentFontSize, float rootFontSize,
                                                      int viewportW, int viewportH) {
        Map<String, String> style = new LinkedHashMap<>(rawStyle);
        substituteVariables(style, rawStyle, sheet, env);
        PropertyRegistry.expandShorthands(style);
        ValueComputer.compute(style, new ValueComputer.Context(
                parentStyle != null ? parentStyle : Map.of(), parentFontSize, rootFontSize, viewportW, viewportH));
        return ComputedStyle.of(style, parentFontSize, rootFontSize);
    }

    /**
     * カスタムプロパティの参照を解く。参照先は要素自身（継承分を含む）→ {@code :root} の変数表 →
     * 埋め込み側の環境変数（{@code --name} を {@code name} で引く）の順。
     * 算出時に無効になった宣言は {@code unset} に、無効なカスタムプロパティは削除する。
     */
    private static void substituteVariables(Map<String, String> style, Map<String, String> raw,
                                            StyleSheet sheet, Map<String, String> env) {
        VarResolver vars = new VarResolver(name -> {
            String v = raw.get(name);
            if (v == null && sheet != null) v = sheet.variables.get(name);
            if (v == null && env != null) v = env.get(name.substring(2));
            // 空のカスタムプロパティは未定義と同じに扱う（フォールバックが効く。従来の挙動）
            return v == null || v.isBlank() ? null : v;
        });
        for (String key : new ArrayList<>(style.keySet())) {
            String value = style.get(key);
            if (key.startsWith("--")) {
                String resolved = vars.resolveCustomProperty(key);
                if (resolved == null) style.remove(key);
                else style.put(key, resolved);
            } else if (value != null && value.contains("var(")) {
                String resolved = vars.substitute(value);
                style.put(key, resolved != null ? resolved : "unset");
            }
        }
    }

    /**
     * 1 つの DOM ノードに対するスタイル解決の文脈。
     *
     * 以前は {@code base / hover / active / focus} の 4 マップに分けて {@code putAll} で重ねていたため、
     * 詳細度や出現順に関わらず {@code :hover} が基底スタイルに勝ち、{@code :active} が {@code :focus} に勝っていた。
     * 現在は状態をカスケードの入力（{@link SelectorMatcher.StateLookup}）として渡し、
     * 詳細度と出現順に従った単一のカスケードで解決する。
     */
    public static class StyleSet {

        private final HtmlNode node;
        private final Map<String, String> inheritedStyle;
        private final StyleSheet sheet;
        private final boolean hasInteractiveRules;

        public StyleSet(HtmlNode node, Map<String, String> inheritedStyle, StyleSheet sheet, boolean hasInteractiveRules) {
            this.node = node;
            this.inheritedStyle = inheritedStyle != null ? new LinkedHashMap<>(inheritedStyle) : Map.of();
            this.sheet = sheet;
            this.hasInteractiveRules = hasInteractiveRules;
        }

        /** 対象ノード自身の要素状態を指定して、適用すべき宣言をカスケード解決して返す。 */
        public Map<String, String> resolve(ElementState state) {
            ElementState effective = ElementState.fromNodeAttributes(node)
                    .with(state != null ? state.flags() : 0);
            return resolve(n -> n == node ? effective : ElementState.fromNodeAttributes(n));
        }

        /** 文書ツリーの要素状態参照先を指定して、適用すべき宣言をカスケード解決して返す。 */
        public Map<String, String> resolve(SelectorMatcher.StateLookup stateLookup) {
            return StyleResolver.resolveDeclarations(node, inheritedStyle, sheet, stateLookup);
        }

        /** このノードが :hover / :active / :focus* 規則の対象になり得るか。 */
        public boolean hasInteractiveRules() {
            return hasInteractiveRules;
        }
    }

    /**
     * {@code var()} の置換。
     *
     * 何が壊れていたか: 以前は {@code var\(\s*(--[\w-]+)(?:\s*,\s*([^)]+))?\s*\)} の正規表現で
     * 置き換えを 10 回まで繰り返していた。フォールバックに括弧を含む値
     * （{@code var(--c, rgb(0, 0, 0))}）や入れ子の {@code var()} が途中の {@code )} で切れ、
     * 循環参照（{@code --a: var(--b); --b: var(--a)}）は 10 回回って未解決の文字列のまま残った。
     * 未定義でフォールバックも無い参照は空文字列に置き換わり、宣言が黙って空になっていた。
     *
     * ここでは括弧の深さを数えて引数を切り出し、参照先のカスタムプロパティを再帰的に解く。
     * 循環に入ったもの、未定義でフォールバックも無いものは「算出時に無効」として null を返す
     * （呼び出し側はその宣言を {@code unset} として扱う。CSS の invalid at computed-value time）。
     */
    static final class VarResolver {

        /** 置換結果がこの長さを超えたら無効にする（{@code --a: var(--b) var(--b)} の倍々ゲーム対策）。 */
        private static final int MAX_LENGTH = 65536;

        private final Function<String, String> lookup;

        /**
         * @param lookup カスタムプロパティ名（{@code --x}）→ 置換前の値。未定義なら null
         */
        VarResolver(Function<String, String> lookup) {
            this.lookup = lookup;
        }

        /** {@code var()} を含まない値に置き換える。算出時に無効なら null。 */
        String substitute(String value) {
            return substitute(value, new HashSet<>());
        }

        /** カスタムプロパティ {@code name} の算出値（参照を解いた値）。循環・未定義なら null。 */
        String resolveCustomProperty(String name) {
            return resolveVariable(name, new HashSet<>());
        }

        private String substitute(String value, Set<String> resolving) {
            if (value == null) return null;
            if (!value.contains("var(")) return value;

            StringBuilder out = new StringBuilder();
            int i = 0;
            int len = value.length();
            while (i < len) {
                int start = indexOfVar(value, i);
                if (start < 0) {
                    out.append(value, i, len);
                    break;
                }
                out.append(value, i, start);
                int open = start + 3; // "var" の後ろの '('
                int close = matchingParen(value, open);
                if (close < 0) return null; // 閉じていない var( は構文として無効

                String args = value.substring(open + 1, close);
                int comma = topLevelComma(args);
                String name = (comma >= 0 ? args.substring(0, comma) : args).trim();
                String fallback = comma >= 0 ? args.substring(comma + 1).trim() : null;
                if (!name.startsWith("--")) return null;

                String resolved = resolveVariable(name, resolving);
                if (resolved == null && fallback != null) resolved = substitute(fallback, resolving);
                if (resolved == null) return null;

                out.append(resolved);
                if (out.length() > MAX_LENGTH) return null;
                i = close + 1;
            }
            return out.toString();
        }

        private String resolveVariable(String name, Set<String> resolving) {
            if (!resolving.add(name)) return null; // 循環
            try {
                String raw = lookup.apply(name);
                return raw == null ? null : substitute(raw, resolving);
            } finally {
                resolving.remove(name);
            }
        }

        /** 識別子の途中（{@code --my-var(} など）ではない {@code var(} の位置。 */
        private static int indexOfVar(String s, int from) {
            int idx = s.indexOf("var(", from);
            while (idx > 0) {
                char prev = s.charAt(idx - 1);
                if (!Character.isLetterOrDigit(prev) && prev != '-' && prev != '_') break;
                idx = s.indexOf("var(", idx + 1);
            }
            return idx;
        }

        private static int matchingParen(String s, int open) {
            int depth = 0;
            char quote = 0;
            for (int i = open; i < s.length(); i++) {
                char c = s.charAt(i);
                if (quote != 0) {
                    if (c == quote) quote = 0;
                } else if (c == '"' || c == '\'') {
                    quote = c;
                } else if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
            return -1;
        }

        private static int topLevelComma(String s) {
            int depth = 0;
            char quote = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (quote != 0) {
                    if (c == quote) quote = 0;
                } else if (c == '"' || c == '\'') {
                    quote = c;
                } else if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                } else if (c == ',' && depth == 0) {
                    return i;
                }
            }
            return -1;
        }
    }
}
