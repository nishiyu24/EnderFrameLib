package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.CssSyntax.ComponentValue;
import com.nishiyu.enderframelib.core.css.CssSyntax.Declaration;
import com.nishiyu.enderframelib.core.css.CssSyntax.Item;
import com.nishiyu.enderframelib.core.css.CssSyntax.Token;
import com.nishiyu.enderframelib.core.css.CssSyntax.Type;
import com.nishiyu.enderframelib.core.css.StyleSheet.CascadeOrigin;
import com.nishiyu.enderframelib.core.css.StyleSheet.ContainerQuery;
import com.nishiyu.enderframelib.core.css.StyleSheet.KeyframeBlock;
import com.nishiyu.enderframelib.core.css.StyleSheet.Keyframes;
import com.nishiyu.enderframelib.core.css.StyleSheet.StyleRule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * CSS テキストから {@link StyleSheet} を組み立てる入口。
 *
 * 実際の処理は {@link CssSyntax}（トークン → コンポーネント値 → 規則・宣言）→ {@link CssRuleParser}（積み上げ）→
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

    /**
     * {@code @media} の評価と {@code @import} の解決に使う環境を指定してパースする。
     * {@code @media} の条件は規則に付けて残し、ビューポートが変わったときは
     * {@link StyleSheet#updateMediaEnvironment} が条件だけを評価し直す（CSS を読み直さない）。
     */
    public static StyleSheet parse(String css, MediaEnvironment env, CascadeOrigin origin) {
        StyleSheet sheet = new StyleSheet();
        sheet.updateMediaEnvironment(env);
        if (css == null || css.isEmpty()) return sheet;

        // @font-face はフック側が CSS 全体を見て解釈する（src の URL 解決などが必要なため）
        if (fontFaceCallback != null && css.toLowerCase(Locale.ROOT).contains("@font-face")) {
            fontFaceCallback.accept(css);
        }

        CssRuleParser.parseInto(css, sheet, env, origin);
        sheet.updateMediaEnvironment(env);

        // 安定ソートなので、詳細度が同じルールは記述順（後勝ち）が保たれる
        sheet.sortCascade();
        return sheet;
    }

    /**
     * 互換のための薄い窓口。切り出し・分割は {@link CssSyntax} のトークンの上で行い、
     * 文字列リテラル・{@code url()}・コメントの扱いは仕様どおり（各所で数え直さない）。
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
            for (CssSyntax.Rule rule : CssSyntax.parseRuleList(css)) {
                if (rule.isAtRule()) {
                    String prelude = "@" + rule.atName();
                    String params = rule.preludeText();
                    if (!params.isEmpty()) prelude += " " + params;
                    if (!rule.hasBlock()) {
                        if (rule.atName().equalsIgnoreCase("import")) blocks.add(new Block(prelude, null));
                        continue;
                    }
                    blocks.add(new Block(prelude, CssSyntax.serialize(rule.block())));
                } else {
                    blocks.add(new Block(rule.preludeText(), CssSyntax.serialize(rule.block())));
                }
            }
            return blocks;
        }

        /**
         * 宣言ブロックの本文を プロパティ -> 値 に分解する。
         * 値に {@code !important} が付いていればそのまま残す。宣言順を保つため LinkedHashMap を返す。
         */
        public static Map<String, String> parseDeclarations(String body) {
            Map<String, String> out = new LinkedHashMap<>();
            if (body == null || body.isEmpty()) return out;
            for (Declaration d : CssSyntax.parseDeclarationList(body)) {
                String value = d.valueText();
                out.remove(d.name());
                out.put(d.name(), d.important() ? value + " !important" : value);
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
         * 文字列・丸括弧・角括弧・波括弧の外側にある {@code sep}（{@code ,} {@code ;} {@code :} のいずれか）でのみ分割する。
         * 区切り文字自体は結果に含まれず、各要素の前後の空白は除く。
         */
        public static List<String> splitTopLevel(String s, char sep) {
            List<String> out = new ArrayList<>();
            if (s == null || s.isEmpty()) return out;
            Type separator = switch (sep) {
                case ',' -> Type.COMMA;
                case ';' -> Type.SEMICOLON;
                case ':' -> Type.COLON;
                default -> throw new IllegalArgumentException("区切りは , ; : のいずれか: " + sep);
            };
            for (List<ComponentValue> part : CssSyntax.split(CssSyntax.parseComponentValues(s), separator)) {
                out.add(CssSyntax.serialize(part));
            }
            return out;
        }
    }

    /**
     * {@link CssSyntax} の規則・宣言を {@link StyleSheet} に積み上げるパーサ。
     *
     * 入れ子（CSS ネスト）・{@code @layer}・{@code @container}・{@code @media} / {@code @supports} の入れ子・
     * {@code @property}・型付きの {@code @keyframes} を扱う。条件付き規則は、中の規則に条件を付けて平らに積む。
     */
    public static final class CssRuleParser {

        /** @media / @supports / @import のネストが異常に深い CSS で再帰が暴走しないための上限。 */
        private static final int MAX_AT_RULE_DEPTH = 16;

        private CssRuleParser() {}

        /**
         * 規則を積むときの文脈。外側のアットルールから引き継ぐ条件と、入れ子の親のセレクタ。
         *
         * @param layer      {@code @layer} の道筋（層に入っていなければ空）
         * @param media      外側の {@code @media} の条件（外側から順）
         * @param containers 外側の {@code @container} の条件（外側から順）
         * @param parents    入れ子の親の規則のセレクタ（{@code &} の指す先）。最上位は null
         */
        private record Scope(List<String> layer, List<String> media, List<ContainerQuery> containers,
                             List<Selector> parents) {

            static Scope root() {
                return new Scope(List.of(), List.of(), List.of(), null);
            }

            Scope inLayer(List<String> path) {
                return new Scope(path, media, containers, parents);
            }

            Scope withMedia(String condition) {
                List<String> next = new ArrayList<>(media);
                next.add(condition);
                return new Scope(layer, List.copyOf(next), containers, parents);
            }

            Scope withContainer(ContainerQuery query) {
                List<ContainerQuery> next = new ArrayList<>(containers);
                next.add(query);
                return new Scope(layer, media, List.copyOf(next), parents);
            }

            Scope nestedIn(List<Selector> selectors) {
                return new Scope(layer, media, containers, selectors);
            }
        }

        /** パースの間だけ要る入力（読み込み先のシート・環境・オリジン）。 */
        private record Context(StyleSheet sheet, MediaEnvironment env, CascadeOrigin origin) {}

        public static void parseInto(String css, StyleSheet sheet, int rootW, CascadeOrigin origin) {
            parseInto(css, sheet, MediaEnvironment.of(rootW, 0), origin);
        }

        public static void parseInto(String css, StyleSheet sheet, MediaEnvironment env, CascadeOrigin origin) {
            parseStylesheet(css, new Context(sheet, env, origin), Scope.root(), 0);
        }

        private static void parseStylesheet(String css, Context ctx, Scope scope, int depth) {
            if (depth > MAX_AT_RULE_DEPTH) return;

            // @import はシートの先頭（@charset と @layer の文を除く他の規則より前）にあるときだけ有効（CSS の規定）
            boolean importsAllowed = true;
            for (CssSyntax.Rule rule : CssSyntax.parseRuleList(css)) {
                if (!rule.isAtRule()) {
                    importsAllowed = false;
                    handleStyleRule(rule, ctx, scope, depth);
                    continue;
                }
                String name = rule.atName().toLowerCase(Locale.ROOT);
                if (name.equals("import")) {
                    if (importsAllowed) handleImport(rule, ctx, scope, depth);
                } else if (name.equals("charset")) {
                    continue;
                } else if (name.equals("layer") && !rule.hasBlock()) {
                    declareLayers(rule, ctx, scope);
                } else {
                    importsAllowed = false;
                    handleAtRule(rule, ctx, scope, depth);
                }
            }
        }

        /** 条件付きアットルールの中身。最上位の文脈なら規則の列、入れ子の文脈なら宣言と入れ子の規則の列。 */
        private static void parseConditionalBody(List<ComponentValue> body, Context ctx, Scope scope, int depth) {
            if (depth > MAX_AT_RULE_DEPTH) return;
            if (scope.parents() == null) {
                for (CssSyntax.Rule rule : CssSyntax.parseRuleList(body, false)) {
                    if (rule.isAtRule()) handleAtRule(rule, ctx, scope, depth);
                    else handleStyleRule(rule, ctx, scope, depth);
                }
            } else {
                parseStyleBlock(CssSyntax.parseBlockContents(body), scope.parents(), ctx, scope, depth);
            }
        }

        // ---- @import

        /**
         * {@code @import <url> [layer | layer(<name>)]? [supports(<condition>)]? <media-query-list>?;}。
         * 読み込んだシートは @import を書いた位置に、同じオリジンの規則として入る。層を指定すればその層に入り、
         * {@code supports()} が偽なら読まない。メディアクエリは規則に付けて残す（ビューポートが変わっても読み直さない）。
         */
        private static void handleImport(CssSyntax.Rule rule, Context ctx, Scope scope, int depth) {
            if (ctx.env().importResolver() == null) return;
            List<ComponentValue> prelude = CssSyntax.trim(rule.prelude());
            if (prelude.isEmpty()) return;

            String url = importUrl(prelude.getFirst());
            if (url == null) return;
            List<ComponentValue> rest = CssSyntax.trim(prelude.subList(1, prelude.size()));

            Scope target = scope;
            int i = 0;
            if (i < rest.size() && isIdent(rest.get(i), "layer")) {
                target = target.inLayer(appendLayer(ctx, scope.layer(), List.of(ctx.sheet().layers.newAnonymousName())));
                i++;
            } else if (i < rest.size() && rest.get(i) instanceof CssSyntax.Function f && f.is("layer")) {
                List<String> path = layerPath(f.args());
                if (path == null) return;
                target = target.inLayer(appendLayer(ctx, scope.layer(), path));
                i++;
            }
            while (i < rest.size() && CssSyntax.isWhitespace(rest.get(i))) i++;
            if (i < rest.size() && rest.get(i) instanceof CssSyntax.Function f && f.is("supports")) {
                if (!evaluateImportSupports(f)) return;
                i++;
            }
            String media = CssSyntax.serialize(CssSyntax.trim(rest.subList(Math.min(i, rest.size()), rest.size())));
            if (!media.isEmpty()) target = target.withMedia(media);

            String imported = ctx.env().importResolver().apply(url);
            if (imported != null && !imported.isEmpty()) parseStylesheet(imported, ctx, target, depth + 1);
        }

        private static String importUrl(ComponentValue first) {
            if (first instanceof CssSyntax.Function f && f.is("url")) {
                List<ComponentValue> args = CssSyntax.trim(f.args());
                Token t = args.size() == 1 ? CssSyntax.tokenOf(args.getFirst()) : null;
                return t != null && t.type() == Type.STRING ? t.value() : null;
            }
            Token t = CssSyntax.tokenOf(first);
            if (t == null) return null;
            return t.type() == Type.STRING || t.type() == Type.URL ? t.value() : null;
        }

        /** {@code supports(display: grid)} は宣言の形、{@code supports(not (display: grid))} は条件の形。 */
        private static boolean evaluateImportSupports(CssSyntax.Function f) {
            String text = CssSyntax.serialize(f.args()).trim();
            boolean declaration = false;
            for (ComponentValue v : f.args()) {
                if (CssSyntax.isToken(v, Type.COLON)) {
                    declaration = true;
                    break;
                }
            }
            return AtRuleProcessor.evaluateSupports(declaration ? "(" + text + ")" : text);
        }

        private static boolean isIdent(ComponentValue v, String name) {
            Token t = CssSyntax.tokenOf(v);
            return t != null && t.isIdent(name);
        }

        // ---- @layer

        /** {@code @layer a, b.c;}。名前を登録して順を決めるだけで、規則は生まない。 */
        private static void declareLayers(CssSyntax.Rule rule, Context ctx, Scope scope) {
            for (List<ComponentValue> name : CssSyntax.split(rule.prelude(), Type.COMMA)) {
                List<String> path = layerPath(name);
                if (path != null) appendLayer(ctx, scope.layer(), path);
            }
        }

        /** {@code a.b} のような層の名前を道筋にする。名前でなければ null。 */
        private static List<String> layerPath(List<ComponentValue> name) {
            List<String> path = new ArrayList<>();
            boolean expectIdent = true;
            for (ComponentValue v : CssSyntax.trim(name)) {
                Token t = CssSyntax.tokenOf(v);
                if (t == null) return null;
                if (expectIdent) {
                    if (t.type() != Type.IDENT) return null;
                    path.add(t.value());
                } else if (!t.isDelim('.')) {
                    return null;
                }
                expectIdent = !expectIdent;
            }
            return path.isEmpty() || expectIdent ? null : path;
        }

        /** 現在の層の下に道筋を足した完全な道筋を登録して返す。 */
        private static List<String> appendLayer(Context ctx, List<String> base, List<String> path) {
            List<String> full = new ArrayList<>(base);
            full.addAll(path);
            List<String> immutable = List.copyOf(full);
            ctx.sheet().layers.declare(immutable);
            return immutable;
        }

        // ---- アットルール

        private static void handleAtRule(CssSyntax.Rule rule, Context ctx, Scope scope, int depth) {
            String name = rule.atName().toLowerCase(Locale.ROOT);
            switch (name) {
                case "media" -> {
                    if (rule.hasBlock()) {
                        String condition = rule.preludeText();
                        parseConditionalBody(rule.block(), ctx, condition.isEmpty() ? scope : scope.withMedia(condition), depth + 1);
                    }
                }
                case "supports" -> {
                    if (rule.hasBlock() && AtRuleProcessor.evaluateSupports(rule.preludeText())) {
                        parseConditionalBody(rule.block(), ctx, scope, depth + 1);
                    }
                }
                case "layer" -> handleLayerBlock(rule, ctx, scope, depth);
                case "container" -> {
                    ContainerQuery query = containerQuery(rule);
                    if (query != null && rule.hasBlock()) {
                        parseConditionalBody(rule.block(), ctx, scope.withContainer(query), depth + 1);
                    }
                }
                case "keyframes", "-webkit-keyframes" -> collectKeyframes(rule, ctx.sheet());
                case "property" -> registerProperty(rule, ctx.sheet());
                case "font-face" -> {
                    // フォント登録は CssParser.fontFaceCallback が CSS 全体を受け取って処理する
                }
                default -> {
                    // 未対応のアットルール（@page / @scope など）は無視する
                }
            }
        }

        private static void handleLayerBlock(CssSyntax.Rule rule, Context ctx, Scope scope, int depth) {
            if (!rule.hasBlock()) return;
            List<String> path;
            if (CssSyntax.trim(rule.prelude()).isEmpty()) {
                path = List.of(ctx.sheet().layers.newAnonymousName());
            } else {
                path = layerPath(rule.prelude());
                if (path == null) return;
            }
            parseConditionalBody(rule.block(), ctx, scope.inLayer(appendLayer(ctx, scope.layer(), path)), depth + 1);
        }

        /** {@code @container [name]? <condition>}。名前は条件の前の識別子（{@code not} / {@code and} / {@code or} を除く）。 */
        private static ContainerQuery containerQuery(CssSyntax.Rule rule) {
            List<ComponentValue> prelude = CssSyntax.trim(rule.prelude());
            if (prelude.isEmpty()) return null;
            String name = "";
            int from = 0;
            Token first = CssSyntax.tokenOf(prelude.getFirst());
            if (first != null && first.type() == Type.IDENT) {
                String word = first.value().toLowerCase(Locale.ROOT);
                if (!word.equals("not") && !word.equals("and") && !word.equals("or")) {
                    name = first.value();
                    from = 1;
                }
            }
            String condition = CssSyntax.serialize(CssSyntax.trim(prelude.subList(from, prelude.size())));
            return condition.isEmpty() ? null : ContainerQuery.of(name, condition);
        }

        // ---- @keyframes

        /**
         * {@code @keyframes} を型付きのブロック列にする。キーフレームのセレクタは {@code from} / {@code to} /
         * 0〜100 の割合（カンマで複数）。読めないセレクタのブロックは捨てる。{@code !important} の宣言と
         * {@code animation-*} は無効（CSS Animations 1 §4.2）。
         */
        private static void collectKeyframes(CssSyntax.Rule rule, StyleSheet sheet) {
            if (!rule.hasBlock()) return;
            List<ComponentValue> prelude = CssSyntax.trim(rule.prelude());
            if (prelude.size() != 1) return;
            Token nameToken = CssSyntax.tokenOf(prelude.getFirst());
            if (nameToken == null || (nameToken.type() != Type.IDENT && nameToken.type() != Type.STRING)) return;
            String name = nameToken.value();

            List<KeyframeBlock> blocks = new ArrayList<>();
            for (CssSyntax.Rule step : CssSyntax.parseRuleList(rule.block(), false)) {
                if (step.isAtRule()) continue;
                List<Float> stops = keyframeStops(step.prelude());
                if (stops == null) continue;
                Map<String, String> declarations = new LinkedHashMap<>();
                for (Item item : CssSyntax.parseBlockContents(step.block())) {
                    Declaration d = item.declaration();
                    if (d == null || d.important() || d.name().startsWith("animation")) continue;
                    declarations.remove(d.name());
                    declarations.put(d.name(), d.valueText());
                }
                for (float stop : stops) blocks.add(new KeyframeBlock(stop, new LinkedHashMap<>(declarations)));
            }
            sheet.keyframes.put(name, new Keyframes(name, List.copyOf(blocks)));
        }

        private static List<Float> keyframeStops(List<ComponentValue> prelude) {
            List<Float> out = new ArrayList<>();
            for (List<ComponentValue> stop : CssSyntax.split(prelude, Type.COMMA)) {
                if (stop.size() != 1) return null;
                Token t = CssSyntax.tokenOf(stop.getFirst());
                if (t == null) return null;
                if (t.type() == Type.IDENT && t.isIdent("from")) {
                    out.add(0f);
                } else if (t.type() == Type.IDENT && t.isIdent("to")) {
                    out.add(100f);
                } else if (t.type() == Type.PERCENTAGE && t.number() >= 0 && t.number() <= 100) {
                    out.add((float) t.number());
                } else {
                    return null;
                }
            }
            return out.isEmpty() ? null : out;
        }

        // ---- @property

        private static void registerProperty(CssSyntax.Rule rule, StyleSheet sheet) {
            if (!rule.hasBlock()) return;
            List<ComponentValue> prelude = CssSyntax.trim(rule.prelude());
            if (prelude.size() != 1) return;
            Token nameToken = CssSyntax.tokenOf(prelude.getFirst());
            if (nameToken == null || nameToken.type() != Type.IDENT) return;

            Map<String, String> descriptors = new LinkedHashMap<>();
            for (Item item : CssSyntax.parseBlockContents(rule.block())) {
                if (item.declaration() != null) {
                    descriptors.put(item.declaration().name().toLowerCase(Locale.ROOT), item.declaration().valueText());
                }
            }
            CustomPropertyRegistration registration =
                    CustomPropertyRegistration.fromDescriptors(nameToken.value(), descriptors);
            // 無効な @property は無視する（先に登録されたものは残る）。同じ名前は後のものが勝つ
            if (registration != null) sheet.registeredProperties.put(registration.name(), registration);
        }

        // ---- スタイル規則

        private static void handleStyleRule(CssSyntax.Rule rule, Context ctx, Scope scope, int depth) {
            if (depth > MAX_AT_RULE_DEPTH || !rule.hasBlock()) return;

            List<String> selectorTexts = new ArrayList<>();
            for (List<ComponentValue> part : CssSyntax.split(rule.prelude(), Type.COMMA)) {
                selectorTexts.add(CssSyntax.serialize(part));
            }
            // セレクタリストに無効なセレクタが 1 つでもあれば規則全体を捨てる（Selectors 4 §3.1）。
            // 何が壊れていたか: 以前は未知の擬似クラスを読み飛ばしたので、規則が全要素に掛かっていた
            List<Selector> selectors = new ArrayList<>();
            for (String text : selectorTexts) {
                Selector parsed = scope.parents() == null
                        ? SelectorParser.parse(text, ctx.origin(), false)
                        : SelectorParser.parseNested(text, ctx.origin(), false, scope.parents());
                if (!parsed.isValid()) return;
                selectors.add(parsed);
            }

            collectRootVariables(selectors, selectorTexts, rule.block(), scope, ctx.sheet());
            parseStyleBlock(CssSyntax.parseBlockContents(rule.block()), selectors, ctx, scope, depth);
        }

        /**
         * スタイル規則の中身を宣言の塊と入れ子の規則に分けて積む。宣言は、入れ子の規則を挟むたびに
         * 別の規則に分ける（CSS Nesting: 入れ子の規則の後ろに書いた宣言は、その後ろの順になる）。
         */
        private static void parseStyleBlock(List<Item> items, List<Selector> selectors, Context ctx,
                                            Scope scope, int depth) {
            Map<String, String> normal = new LinkedHashMap<>();
            Map<String, String> important = new LinkedHashMap<>();
            Scope nested = scope.nestedIn(selectors);

            for (Item item : items) {
                if (item.declaration() != null) {
                    Declaration d = item.declaration();
                    Map<String, String> target = d.important() ? important : normal;
                    target.remove(d.name());
                    target.put(d.name(), d.valueText());
                    continue;
                }
                flush(selectors, normal, important, ctx, scope);
                normal = new LinkedHashMap<>();
                important = new LinkedHashMap<>();
                CssSyntax.Rule nestedRule = item.rule();
                if (nestedRule.isAtRule()) handleAtRule(nestedRule, ctx, nested, depth + 1);
                else handleStyleRule(nestedRule, ctx, nested, depth + 1);
            }
            flush(selectors, normal, important, ctx, scope);
        }

        /** 通常宣言と {@code !important} 宣言は別の規則として積み、詳細度の重要度の印でカスケードの勝敗を決める。 */
        private static void flush(List<Selector> selectors, Map<String, String> normal, Map<String, String> important,
                                  Context ctx, Scope scope) {
            for (Selector selector : selectors) {
                if (!normal.isEmpty()) addRule(ctx.sheet(), selector, false, normal, scope);
                if (!important.isEmpty()) addRule(ctx.sheet(), selector, true, important, scope);
            }
        }

        private static void addRule(StyleSheet sheet, Selector selector, boolean important,
                                    Map<String, String> properties, Scope scope) {
            StyleRule rule = new StyleRule(important ? selector.withImportant(true) : selector);
            rule.properties.putAll(properties);
            rule.layer = scope.layer();
            rule.mediaQueries = scope.media();
            rule.containerQueries = scope.containers();
            sheet.rules.add(rule);
        }

        /** 最上位の {@code :root} 規則のカスタムプロパティは、シート全体の変数表に集める。 */
        private static void collectRootVariables(List<Selector> selectors, List<String> texts,
                                                 List<ComponentValue> block, Scope scope, StyleSheet sheet) {
            if (scope.parents() != null) return;
            boolean isRootRule = false;
            for (String s : texts) {
                if (s.contains(":root")) {
                    isRootRule = true;
                    break;
                }
            }
            if (!isRootRule) return;
            for (Item item : CssSyntax.parseBlockContents(block)) {
                Declaration d = item.declaration();
                if (d != null && d.name().startsWith("--")) sheet.variables.put(d.name(), d.valueText());
            }
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

        public MediaEnvironment withSize(int newWidth, int newHeight) {
            return new MediaEnvironment(newWidth, newHeight, prefersDark, prefersReducedMotion, importResolver);
        }
    }
}
