package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.StyleSheet.CascadeOrigin;
import com.nishiyu.enderframelib.core.css.StyleSheet.StyleRule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * CSS テキストから {@link StyleSheet} を組み立てる入口。
 *
 * 実際の処理は {@link CssTokenizer}（切り出し）→ {@link CssRuleParser}（積み上げ）→
 * {@link SelectorParser}（セレクタ）に分かれていて、このクラスはその組み立てだけを行う。
 */
public final class CssParser {

    /** {@code @font-face} を含む CSS を受け取ってフォントを登録するフック（クライアント側から注入）。 */
    public static Consumer<String> fontFaceCallback = null;

    private CssParser() {}

    /** 作者スタイルシートとしてパースする。 */
    public static StyleSheet parse(String css, int rootW) {
        return parse(css, rootW, CascadeOrigin.AUTHOR);
    }

    public static StyleSheet parse(String css, int rootW, CascadeOrigin origin) {
        return parse(css, MediaEnvironment.of(rootW, 0), origin);
    }

    /** {@code @media} の評価と {@code @import} の解決に使う環境を指定してパースする。 */
    public static StyleSheet parse(String css, MediaEnvironment env, CascadeOrigin origin) {
        StyleSheet sheet = new StyleSheet();
        if (css == null || css.isEmpty()) return sheet;

        // @font-face はフック側が CSS 全体を見て解釈する（src の URL 解決などが必要なため）
        if (fontFaceCallback != null && css.toLowerCase().contains("@font-face")) {
            fontFaceCallback.accept(css);
        }

        CssRuleParser.parseInto(css, sheet, env, origin);

        // 安定ソートなので、詳細度が同じルールは記述順（後勝ち）が保たれる
        sheet.rules.sort(StyleRule::compareSpecificity);
        return sheet;
    }

    /**
     * CSS を「プレリュード ＋ 波括弧ブロック」の列に切り出すトークナイザ。
     *
     * 以前の CSS_PATTERN 正規表現 ("([^{]+)\\{([^}]+)}") は、ネストした波括弧
     * (@media / @keyframes)・文字列リテラル・url() の中身を区別できなかった。
     * ここでは文字単位に走査して以下を正しく扱う。
     *
     * - ネストした波括弧（本文を丸ごと取り出し、呼び出し側が再帰的に処理する）
     * - '...' / "..." の文字列リテラル（中の波括弧・セミコロン・カンマは区切りではない）
     * - url(...)（引用符なしでもセミコロンや波括弧を含み得る）
     * - コメント（文字列リテラルの中のものは削除しない）
     */
    public static final class CssTokenizer {

        private CssTokenizer() {}

        /**
         * 1 つの波括弧ブロック。
         * prelude は波括弧の前の部分（セレクタリスト、または @media などのアットルール）、
         * body は波括弧の中身（ネストしたブロックを含み得る生テキスト）。
         * 波括弧を持たない文のアットルール（{@code @import ...;}）は body が null。
         */
        public record Block(String prelude, String body) {
            /** {@code @import} のように {@code ;} で終わるアットルールなら true。 */
            public boolean isStatement() {
                return body == null;
            }

            /** @media などのアットルールなら true。 */
            public boolean isAtRule() {
                return prelude.startsWith("@");
            }

            /** アットルール名（"@media" など）。アットルールでなければ空文字列。 */
            public String atRuleName() {
                if (!isAtRule()) return "";
                int end = 1;
                while (end < prelude.length() && !Character.isWhitespace(prelude.charAt(end))
                        && prelude.charAt(end) != '(' && prelude.charAt(end) != '{') {
                    end++;
                }
                return prelude.substring(0, end);
            }

            /** アットルールの引数部（アットルール名を除いた条件式など）。 */
            public String atRuleParams() {
                return isAtRule() ? prelude.substring(atRuleName().length()).trim() : "";
            }
        }

        /**
         * トップレベルのブロック列に切り出す。波括弧を伴わないアットルールは {@code @import} だけを
         * 文として残し（body が null の {@link Block}）、{@code @charset} などは捨てる。
         */
        public static List<Block> tokenize(String css) {
            List<Block> blocks = new ArrayList<>();
            if (css == null || css.isEmpty()) return blocks;

            String src = stripComments(css);
            int i = 0;
            int len = src.length();
            StringBuilder prelude = new StringBuilder();

            while (i < len) {
                char c = src.charAt(i);

                if (c == '"' || c == '\'') {
                    int end = skipString(src, i);
                    prelude.append(src, i, end);
                    i = end;
                } else if (c == ';') {
                    // @import は文として渡す。それ以外の波括弧を伴わないアットルール（@charset）や迷子の宣言は捨てる
                    String statement = prelude.toString().trim();
                    if (statement.regionMatches(true, 0, "@import", 0, 7)) blocks.add(new Block(statement, null));
                    prelude.setLength(0);
                    i++;
                } else if (c == '{') {
                    int bodyStart = i + 1;
                    int bodyEnd = findBlockEnd(src, bodyStart);
                    blocks.add(new Block(prelude.toString().trim(), src.substring(bodyStart, bodyEnd)));
                    prelude.setLength(0);
                    i = bodyEnd < len ? bodyEnd + 1 : len; // 閉じ波括弧を読み飛ばす
                } else if (c == '}') {
                    // 対応しない閉じ波括弧は無視する
                    prelude.setLength(0);
                    i++;
                } else {
                    prelude.append(c);
                    i++;
                }
            }
            return blocks;
        }

        /**
         * 宣言ブロックの本文を プロパティ -> 値 に分解する。
         * セミコロンの分割は文字列・丸括弧（url() や calc()）を考慮する。
         * 宣言順を保つため LinkedHashMap を返す。
         */
        public static Map<String, String> parseDeclarations(String body) {
            Map<String, String> out = new LinkedHashMap<>();
            if (body == null || body.isEmpty()) return out;

            // style 属性のように未処理のテキストが渡ることもあるため、ここでもコメントを落とす
            for (String decl : splitTopLevel(stripComments(body), ';')) {
                int colon = indexOfTopLevel(decl, ':');
                if (colon <= 0) continue;
                String key = decl.substring(0, colon).trim().toLowerCase();
                String val = decl.substring(colon + 1).trim();
                if (key.isEmpty() || val.isEmpty()) continue;
                out.put(key, val);
            }
            return out;
        }

        /**
         * セレクタリストをカンマで分割する。
         * :not(a, b) や [title=","] の中のカンマでは分割しない。
         */
        public static List<String> splitSelectorList(String selectors) {
            List<String> out = new ArrayList<>();
            for (String s : splitTopLevel(selectors, ',')) {
                String t = s.trim();
                if (!t.isEmpty()) out.add(t);
            }
            return out;
        }

        /**
         * 文字列・丸括弧・角括弧・波括弧の外側にある sep でのみ分割する。
         * 区切り文字自体は結果に含まれない。
         */
        public static List<String> splitTopLevel(String s, char sep) {
            List<String> out = new ArrayList<>();
            if (s == null || s.isEmpty()) return out;

            int depth = 0;
            int start = 0;
            int i = 0;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '"' || c == '\'') {
                    i = skipString(s, i);
                    continue;
                }
                if (c == '(' || c == '[' || c == '{') depth++;
                else if (c == ')' || c == ']' || c == '}') depth = Math.max(0, depth - 1);
                else if (c == sep && depth == 0) {
                    out.add(s.substring(start, i));
                    start = i + 1;
                }
                i++;
            }
            out.add(s.substring(start));
            return out;
        }

        /** 文字列・括弧の外側にある最初の target の位置。見つからなければ -1。 */
        public static int indexOfTopLevel(String s, char target) {
            int depth = 0;
            int i = 0;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '"' || c == '\'') {
                    i = skipString(s, i);
                    continue;
                }
                if (c == '(' || c == '[' || c == '{') depth++;
                else if (c == ')' || c == ']' || c == '}') depth = Math.max(0, depth - 1);
                else if (c == target && depth == 0) return i;
                i++;
            }
            return -1;
        }

        /**
         * start 位置（開き波括弧の直後）から対応する閉じ波括弧の位置を返す。
         * 見つからなければ文字列長（＝暗黙の閉じ）。
         */
        private static int findBlockEnd(String s, int start) {
            int depth = 1;
            int i = start;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '"' || c == '\'') {
                    i = skipString(s, i);
                    continue;
                }
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return i;
                }
                i++;
            }
            return s.length();
        }

        /** i 位置の引用符で始まる文字列を読み飛ばし、閉じ引用符の次の位置を返す。 */
        private static int skipString(String s, int i) {
            char quote = s.charAt(i);
            int j = i + 1;
            while (j < s.length()) {
                char c = s.charAt(j);
                if (c == '\\') {
                    j += 2;
                    continue;
                }
                if (c == quote) return j + 1;
                j++;
            }
            return s.length();
        }

        /** 文字列リテラルの中を壊さずにコメントを削除する。 */
        public static String stripComments(String css) {
            StringBuilder sb = new StringBuilder(css.length());
            int i = 0;
            while (i < css.length()) {
                char c = css.charAt(i);
                if (c == '"' || c == '\'') {
                    int end = skipString(css, i);
                    sb.append(css, i, end);
                    i = end;
                } else if (c == '/' && i + 1 < css.length() && css.charAt(i + 1) == '*') {
                    int end = css.indexOf("*/", i + 2);
                    i = (end == -1) ? css.length() : end + 2;
                } else {
                    sb.append(c);
                    i++;
                }
            }
            return sb.toString();
        }
    }

    /**
     * トークナイズされたブロック列を {@link StyleSheet} に積み上げるパーサ。
     *
     * {@code @media} は文字列として平坦化せず、ブロックの本文をそのまま再帰的にパースする。
     * （以前は @media の中身を文字列として切り貼りしてから正規表現で再マッチしていた）
     */
    public static final class CssRuleParser {

        /** @media / @supports / @import のネストが異常に深い CSS で再帰が暴走しないための上限。 */
        private static final int MAX_AT_RULE_DEPTH = 16;

        private CssRuleParser() {}

        public static void parseInto(String css, StyleSheet sheet, int rootW, CascadeOrigin origin) {
            parseInto(css, sheet, MediaEnvironment.of(rootW, 0), origin);
        }

        public static void parseInto(String css, StyleSheet sheet, MediaEnvironment env, CascadeOrigin origin) {
            parseInto(css, sheet, env, origin, 0);
        }

        private static void parseInto(String css, StyleSheet sheet, MediaEnvironment env, CascadeOrigin origin, int depth) {
            if (depth > MAX_AT_RULE_DEPTH) return;

            // @import はシートの先頭（他の規則より前）にあるときだけ有効（CSS の規定）
            boolean importsAllowed = true;
            for (CssTokenizer.Block block : CssTokenizer.tokenize(css)) {
                if (block.isStatement()) {
                    if (importsAllowed) handleImport(block, sheet, env, origin, depth);
                    continue;
                }
                importsAllowed = false;
                if (block.isAtRule()) {
                    handleAtRule(block, sheet, env, origin, depth);
                } else {
                    handleStyleRule(block, sheet, origin);
                }
            }
        }

        /**
         * {@code @import url(x.css) <media>?;} / {@code @import "x.css" <media>?;}。
         * 読み込んだシートは @import を書いた位置に、同じオリジンの規則として入る。
         * {@code layer()} / {@code supports()} の付いた @import は読まない（カスケードレイヤーが無いため）。
         */
        private static void handleImport(CssTokenizer.Block block, StyleSheet sheet, MediaEnvironment env,
                                         CascadeOrigin origin, int depth) {
            if (env.importResolver() == null || !"@import".equalsIgnoreCase(block.atRuleName())) return;
            String params = block.atRuleParams();
            String url;
            String rest;
            if (params.regionMatches(true, 0, "url(", 0, 4)) {
                int close = params.indexOf(')');
                if (close < 0) return;
                url = unquote(params.substring(4, close).trim());
                rest = params.substring(close + 1).trim();
            } else if (!params.isEmpty() && (params.charAt(0) == '"' || params.charAt(0) == '\'')) {
                int close = params.indexOf(params.charAt(0), 1);
                if (close < 0) return;
                url = params.substring(1, close);
                rest = params.substring(close + 1).trim();
            } else {
                return;
            }
            String lowerRest = rest.toLowerCase();
            if (lowerRest.startsWith("layer") || lowerRest.startsWith("supports(")) return;
            if (!rest.isEmpty() && !AtRuleProcessor.evaluateMediaQuery(rest, env)) return;

            String imported = env.importResolver().apply(url);
            if (imported != null && !imported.isEmpty()) parseInto(imported, sheet, env, origin, depth + 1);
        }

        private static String unquote(String s) {
            if (s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '\'') && s.charAt(s.length() - 1) == s.charAt(0)) {
                return s.substring(1, s.length() - 1);
            }
            return s;
        }

        private static void handleAtRule(CssTokenizer.Block block, StyleSheet sheet, MediaEnvironment env,
                                         CascadeOrigin origin, int depth) {
            switch (block.atRuleName().toLowerCase()) {
                case "@media" -> {
                    if (AtRuleProcessor.evaluateMediaQuery(block.atRuleParams(), env)) {
                        parseInto(block.body(), sheet, env, origin, depth + 1);
                    }
                }
                case "@supports" -> {
                    if (AtRuleProcessor.evaluateSupports(block.atRuleParams())) {
                        parseInto(block.body(), sheet, env, origin, depth + 1);
                    }
                }
                case "@keyframes", "@-webkit-keyframes" -> collectKeyframes(block, sheet);
                case "@font-face" -> {
                    // フォント登録は CssParser.fontFaceCallback が CSS 全体を受け取って処理する
                }
                default -> {
                    // 未対応のアットルール（@page / @layer など）は無視する
                }
            }
        }

        /**
         * {@code @keyframes} を {@code "0`color: red|100`color: blue|"} 形式に符号化する。
         * （レンダラ側 WebAnimator がこの形式を読む）
         */
        private static void collectKeyframes(CssTokenizer.Block block, StyleSheet sheet) {
            String name = block.atRuleParams().trim();
            if (name.isEmpty()) return;

            StringBuilder encoded = new StringBuilder();
            for (CssTokenizer.Block step : CssTokenizer.tokenize(block.body())) {
                String declarations = step.body().trim();
                for (String stop : CssTokenizer.splitSelectorList(step.prelude())) {
                    String percent = stop.trim().replace("%", "");
                    if (percent.equalsIgnoreCase("from")) percent = "0";
                    if (percent.equalsIgnoreCase("to")) percent = "100";
                    encoded.append(percent).append('`').append(declarations).append('|');
                }
            }
            sheet.keyframes.put(name, encoded.toString());
        }

        private static void handleStyleRule(CssTokenizer.Block block, StyleSheet sheet, CascadeOrigin origin) {
            Map<String, String> declarations = CssTokenizer.parseDeclarations(block.body());
            if (declarations.isEmpty()) return;

            // !important を分離する。通常宣言と important 宣言は別ルールとして積み、
            // Specificity の important フラグでカスケードの勝敗を決める。
            Map<String, String> normal = new LinkedHashMap<>();
            Map<String, String> important = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : declarations.entrySet()) {
                String val = e.getValue();
                String stripped = stripImportant(val);
                if (stripped != null) {
                    important.put(e.getKey(), stripped);
                } else {
                    normal.put(e.getKey(), val);
                }
            }

            List<String> selectors = CssTokenizer.splitSelectorList(block.prelude());
            for (String selectorText : selectors) {
                if (!normal.isEmpty()) {
                    addRule(sheet, selectorText, origin, false, normal);
                }
                if (!important.isEmpty()) {
                    addRule(sheet, selectorText, origin, true, important);
                }
            }

            // :root のカスタムプロパティはシート全体の変数表に集める
            collectRootVariables(selectors, declarations, sheet);
        }

        private static void addRule(StyleSheet sheet, String selectorText, CascadeOrigin origin,
                                    boolean important, Map<String, String> properties) {
            StyleRule rule = new StyleRule(SelectorParser.parse(selectorText, origin, important));
            rule.properties.putAll(properties);
            sheet.rules.add(rule);
        }

        private static void collectRootVariables(List<String> selectors, Map<String, String> declarations,
                                                 StyleSheet sheet) {
            boolean isRootRule = false;
            for (String s : selectors) {
                if (s.contains(":root")) {
                    isRootRule = true;
                    break;
                }
            }
            if (!isRootRule) return;

            for (Map.Entry<String, String> e : declarations.entrySet()) {
                if (e.getKey().startsWith("--")) {
                    String stripped = stripImportant(e.getValue());
                    sheet.variables.put(e.getKey(), stripped != null ? stripped : e.getValue());
                }
            }
        }

        /** {@code !important} が付いていれば、それを除いた値を返す。付いていなければ null。 */
        private static String stripImportant(String value) {
            if (value == null) return null;
            String trimmed = value.trim();
            int bang = trimmed.lastIndexOf('!');
            if (bang < 0) return null;
            if (!trimmed.substring(bang + 1).trim().equalsIgnoreCase("important")) return null;
            return trimmed.substring(0, bang).trim();
        }
    }

    /**
     * スタイルシートを読むときの外部環境（{@code @media} の評価と {@code @import} の解決）。
     *
     * @param width              ビューポートの幅（px）
     * @param height             ビューポートの高さ（px）。0 は「分からない」
     * @param prefersDark        {@code prefers-color-scheme: dark} を真にするか
     * @param prefersReducedMotion {@code prefers-reduced-motion: reduce} を真にするか
     * @param importResolver     {@code @import} の URL → CSS テキスト。null なら {@code @import} を無視する
     */
    public record MediaEnvironment(int width, int height, boolean prefersDark, boolean prefersReducedMotion,
                                   Function<String, String> importResolver) {

        /**
         * 幅と高さだけが分かっている環境。色の好みは light、動きの抑制は無し
         * （どちらも CSS で「好みを示していない」ときの値）。
         */
        public static MediaEnvironment of(int width, int height) {
            return new MediaEnvironment(width, height, false, false, null);
        }

        public MediaEnvironment withImportResolver(Function<String, String> resolver) {
            return new MediaEnvironment(width, height, prefersDark, prefersReducedMotion, resolver);
        }
    }
}
