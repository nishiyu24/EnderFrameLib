package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.model.*;
import com.nishiyu.enderframelib.core.css.CssSyntax.Declaration;
import com.nishiyu.enderframelib.core.css.LengthResolver.Metrics;
import com.nishiyu.enderframelib.core.css.Selector.RelativeSelector;
import com.nishiyu.enderframelib.core.css.Selector.SelectorToken;
import com.nishiyu.enderframelib.core.css.StyleSheet.CascadeOrigin;
import com.nishiyu.enderframelib.core.css.StyleSheet.ContainerQuery;
import com.nishiyu.enderframelib.core.css.StyleSheet.StyleRule;
import com.nishiyu.enderframelib.core.css.ValueComputer.CssWideKeyword;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;

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
 *
 * カスケードの順は CSS Cascade 5 のとおり、重要度 → オリジン（UA / ユーザー / 作者）→ レイヤー →
 * 詳細度 → 出現順。通常の宣言は後ろほど強く、{@code !important} は反転する。
 * {@code revert} はオリジンを、{@code revert-layer} はレイヤーを 1 つ巻き戻す。
 */
public class StyleResolver {

    public static StyleSet resolveStyleSet(HtmlNode node, Map<String, String> inheritedStyle, StyleSheet sheet) {
        boolean interactive = hasInteractiveRules(node, sheet);
        return new StyleSet(node, inheritedStyle, sheet, interactive);
    }

    /**
     * 指定された状態参照先のもとで、継承・シート規則（レイヤー＋詳細度＋出現順）・インラインスタイル・!important を
     * CSS カスケード順に沿って 1 パスで解決する。
     *
     * @param inheritedStyle 親の<b>算出</b>スタイル。継承するプロパティとカスタムプロパティの初期値になり、
     *                       {@code all: inherit} の参照先にもなる
     */
    public static Map<String, String> resolveDeclarations(HtmlNode node, Map<String, String> inheritedStyle,
                                                          StyleSheet sheet, StateLookup stateLookup) {
        return resolveDeclarations(node, null, inheritedStyle, sheet, stateLookup);
    }

    /**
     * 要素に付く疑似要素。{@code BEFORE} / {@code AFTER} は箱を作り（{@link #GENERATED}）、
     * {@code MARKER} はリスト項目のマーカーの、{@code PLACEHOLDER} は入力欄のプレースホルダーの、
     * {@code SELECTION} は選択範囲の、見た目の規則を受け取る。
     */
    public enum PseudoType {
        BEFORE, AFTER, MARKER, PLACEHOLDER, SELECTION;

        /** 持ち主の子として箱を作る疑似要素（{@code content} が箱を生む）。 */
        public static final List<PseudoType> GENERATED = List.of(BEFORE, AFTER);
    }

    /**
     * 疑似要素の算出スタイル。要素と同じカスケード（詳細度・宣言順・{@code !important}・ショートハンドの展開・
     * {@link ValueComputer}）を通し、継承元は持ち主の要素の算出スタイル。
     *
     * 何が壊れていたか: 以前は初回のビルドだけで、一致したルールの宣言を詳細度を見ずに {@code putAll} で
     * 重ね、合成した {@code <span>} の {@code style} 属性に焼き込んでいた。再スタイルでは擬似要素のルールを
     * すべて読み飛ばしていたので、{@code :hover::after} の色や内容が状態に追従しなかった。焼き込んだ span は
     * 要素のルール（{@code span { ... }}）にも当たっていた。
     *
     * @param node                持ち主の要素
     * @param parentComputedStyle 持ち主の算出スタイル
     * @param rootFontSize        rem の基準
     * @return 箱を作るなら算出スタイル。{@code ::before} / {@code ::after} は {@code content} が {@code none} / {@code normal}、
     *         または {@code display: none} なら null（CSS 2.2 §12.1）。{@code ::marker} / {@code ::placeholder} は
     *         規則が 1 つも掛からなければ null
     */
    public static ComputedStyle resolvePseudoStyle(HtmlNode node, PseudoType type, StyleSheet sheet,
                                                   StateLookup stateLookup,
                                                   ComputedStyle parentComputedStyle, Map<String, String> env,
                                                   float rootFontSize, float rootLineHeight, int viewportW, int viewportH) {
        if (node == null || type == null || sheet == null || parentComputedStyle == null) return null;
        if (node.nodeType != NodeType.ELEMENT || node.isPseudoNode) return null;
        Map<String, String> parent = parentComputedStyle.asMap();
        Map<String, String> raw = resolveDeclarations(node, type, parent, sheet, stateLookup);
        StateLookup lookup = stateLookup != null ? stateLookup : SelectorMatcher.STATIC_ATTRIBUTES;
        // 疑似要素にとっては、持ち主の要素もコンテナになり得る（cqw などの基準）
        ContainerBox container = lookup.nearestSizeContainer(node);

        if (type != PseudoType.BEFORE && type != PseudoType.AFTER) {
            if (!declaresAnything(raw, parent)) return null;
            return finalizeComputedStyle(raw, sheet, env, parent, parentComputedStyle.fontSizePx(), rootFontSize,
                    rootLineHeight, viewportW, viewportH, container);
        }

        if (!raw.containsKey(CssProperty.CONTENT)) return null; // content は継承しないので、宣言が無ければ none
        ComputedStyle computed = finalizeComputedStyle(raw, sheet, env, parent,
                parentComputedStyle.fontSizePx(), rootFontSize, rootLineHeight, viewportW, viewportH, container);
        String content = computed.get(CssProperty.CONTENT);
        if (content == null) return null;
        content = content.trim();
        if (content.isEmpty() || content.equals("none") || content.equals("normal")) return null;
        if ("none".equals(computed.get(CssProperty.DISPLAY))) return null;
        return computed;
    }

    /** 継承した値のほかに、規則が宣言した値が 1 つでもあるか。 */
    private static boolean declaresAnything(Map<String, String> raw, Map<String, String> parent) {
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String key = e.getKey();
            if (key.startsWith("--")) continue;
            if (!e.getValue().equals(parent.get(key))) return true;
            if (!PropertyRegistry.isInherited(key)) return true;
        }
        return false;
    }

    /**
     * 選択範囲の見た目（{@code ::selection}）。
     *
     * @param color           文字色（ARGB）。規則が無ければ null
     * @param backgroundColor 背景色（ARGB）。規則が無ければ null
     */
    public record SelectionStyle(Integer color, Integer backgroundColor) {
        public static final SelectionStyle NONE = new SelectionStyle(null, null);
    }

    /**
     * 要素の中の選択範囲に掛かる {@code ::selection} の見た目。ハイライト疑似要素は親のハイライトから継承するので、
     * 要素から文書のルートへ祖先をたどり、色ごとに最初に見つかった宣言を使う。
     */
    public static SelectionStyle resolveSelectionStyle(HtmlNode node, StyleSheet sheet,
                                                       StateLookup stateLookup) {
        if (node == null || sheet == null) return SelectionStyle.NONE;
        Integer color = null;
        Integer background = null;
        for (HtmlNode cur = node; cur != null && (color == null || background == null); cur = cur.parent) {
            if (cur.nodeType != NodeType.ELEMENT) continue;
            Map<String, String> declared = resolveDeclarations(cur, PseudoType.SELECTION, Map.of(), sheet, stateLookup);
            expandSelectionColors(declared);
            String c = declared.get(CssProperty.COLOR);
            String b = declared.get(CssProperty.BACKGROUND_COLOR);
            if (color == null && c != null && ColorParser.isColor(c) && !"currentcolor".equalsIgnoreCase(c.trim())) {
                color = ColorParser.parseColor(c);
            }
            if (background == null && b != null && ColorParser.isColor(b) && !"currentcolor".equalsIgnoreCase(b.trim())) {
                background = ColorParser.parseColor(b);
            }
        }
        return color == null && background == null ? SelectionStyle.NONE : new SelectionStyle(color, background);
    }

    /** {@code ::selection { background: red }} のショートハンドから背景色を取り出す。 */
    private static void expandSelectionColors(Map<String, String> declared) {
        String background = declared.get(CssProperty.BACKGROUND);
        if (background == null) return;
        Map<String, String> expanded = ShorthandExpanders.background(background);
        String color = expanded.get(CssProperty.BACKGROUND_COLOR);
        if (color != null) declared.putIfAbsent(CssProperty.BACKGROUND_COLOR, color);
    }

    /** ルールがこの対象（要素自身なら pseudo は null）に掛かるか。 */
    private static boolean targets(StyleRule rule, PseudoType pseudo) {
        return rule.pseudoElement == pseudo;
    }

    /** 規則がいま掛かり得るか: {@code @media} が真で、{@code @container} の条件がすべて満たされる。 */
    private static boolean isConditionMet(StyleRule rule, HtmlNode node, PseudoType pseudo,
                                          StateLookup lookup) {
        if (!rule.mediaActive) return false;
        for (ContainerQuery query : rule.containerQueries) {
            if (!matchesContainerQuery(query, node, pseudo, lookup)) return false;
        }
        return true;
    }

    /**
     * 最も近い、名前が合う問い合わせコンテナの寸法で条件を評価する。コンテナが無い・寸法が分からないときは偽。
     * 疑似要素の持ち主の要素は、その疑似要素にとってのコンテナになり得る。
     */
    private static boolean matchesContainerQuery(ContainerQuery query, HtmlNode node, PseudoType pseudo,
                                                 StateLookup lookup) {
        HtmlNode start = pseudo != null ? node : node.parent;
        for (HtmlNode cur = start; cur != null; cur = cur.parent) {
            ContainerBox box = lookup.containerOf(cur);
            if (box == null || !box.hasName(query.name())) continue;
            // 寸法の問い合わせは寸法を答えられるコンテナ、style() だけの問い合わせは名前が合う最も近い要素が相手
            if (query.styleOnly() || box.isSizeContainer()) {
                return AtRuleProcessor.evaluateContainerQuery(query.condition(), box);
            }
        }
        return false;
    }

    private static Map<String, String> resolveDeclarations(HtmlNode node, PseudoType pseudo,
                                                           Map<String, String> inheritedStyle, StyleSheet sheet,
                                                           StateLookup stateLookup) {
        StateLookup lookup = stateLookup != null ? stateLookup : SelectorMatcher.STATIC_ATTRIBUTES;
        Map<String, String> parent = inheritedStyle != null ? inheritedStyle : Map.of();
        Map<String, String> result = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : parent.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("--") || inheritsAsLonghand(key)) result.put(key, entry.getValue());
        }
        applyRegisteredInitialValues(result, parent, sheet);

        // revert の巻き戻し先。UA オリジンの宣言だけでカスケードした結果（継承値を含む）と、それにユーザーオリジンを足した結果
        Map<String, String> uaValues = new LinkedHashMap<>(result);
        Map<String, String> userValues = new LinkedHashMap<>(result);

        // style 属性を持つ要素は少ないので、インラインの宣言の表は属性があるときだけ作る
        Map<String, String> inlineNormal = Map.of();
        Map<String, String> inlineImportant = Map.of();
        // インラインスタイルは要素自身にだけ掛かる
        if (pseudo == null && node != null && node.attrs.containsKey("style")) {
            inlineNormal = new LinkedHashMap<>();
            inlineImportant = new LinkedHashMap<>();
            for (Declaration decl : CssSyntax.parseDeclarationList(node.attrs.get("style"))) {
                Map<String, String> target = decl.important() ? inlineImportant : inlineNormal;
                target.remove(decl.name());
                target.put(decl.name(), decl.valueText());
            }
        }

        // 規則はカスケード順（弱い順）に並んでいる。通常の宣言 → style 属性の通常の宣言 →
        // 作者の !important → style 属性の !important → ユーザー・UA の !important の順に重ねる。
        CascadeState state = new CascadeState(result, uaValues, userValues, parent);
        boolean inlineNormalDone = false;
        boolean inlineImportantDone = false;
        if (sheet != null && node != null) {
            for (StyleRule rule : sheet.index().candidates(node)) {
                boolean important = rule.specificity.important();
                if (important && !inlineNormalDone) {
                    state.applyInline(inlineNormal);
                    inlineNormalDone = true;
                }
                if (important && !inlineImportantDone && rule.specificity.origin().tier() < CascadeOrigin.AUTHOR.tier()) {
                    state.applyInline(inlineImportant);
                    inlineImportantDone = true;
                }
                if (!targets(rule, pseudo) || !rule.matches(node, lookup) || !isConditionMet(rule, node, pseudo, lookup)) {
                    continue;
                }
                state.apply(rule);
            }
        }
        if (!inlineNormalDone) state.applyInline(inlineNormal);
        if (!inlineImportantDone) state.applyInline(inlineImportant);

        if (pseudo == null && node != null) adjustForAppearance(node, result, uaValues);
        return result;
    }

    private static final String[] SIDES = {"top", "right", "bottom", "left"};
    private static final String[] CORNERS = {"top-left", "top-right", "bottom-right", "bottom-left"};

    /**
     * UA の部品で描くチェックボックス・ラジオボタン（{@code appearance} が none 以外）の算出値の調整。
     * Chrome（LayoutTheme::AdjustStyle）と同じく、作者の枠線・padding・角丸は効かない（0 に戻す）。
     * 枠の色は UA のスタイルシートだけでカスケードした値（状態ごとの部品の色。ua.css の :hover / :checked など）に
     * 置き換え、描画側はこれを部品の輪・強調色として読む。背景色は算出値に残るが描画に使わない。
     *
     * 何が壊れていたか: 以前は作者の border / background / padding がそのまま部品の見た目と大きさに効き、
     * Chrome と違って見えた。
     */
    private static void adjustForAppearance(HtmlNode node, Map<String, String> result, Map<String, String> uaValues) {
        boolean toggle = isNativeToggle(node, result);
        if (!toggle && !isNativeRange(node, result)) return;
        // UA の宣言はショートハンド（border など）のまま入っているので、展開してから辺ごとの色を読む
        Map<String, String> ua = new LinkedHashMap<>(uaValues);
        PropertyRegistry.expandShorthands(ua);
        for (int i = 0; i < 4; i++) {
            String side = SIDES[i];
            if (toggle) {
                result.put("border-" + side + "-width", "0px");
                result.put("padding-" + side, "0px");
                result.put("border-" + CORNERS[i] + "-radius", "0px");
            }
            String uaColor = ua.get("border-" + side + "-color");
            if (uaColor != null) result.put("border-" + side + "-color", uaColor);
        }
    }

    /**
     * {@code appearance} が none でないレンジ。チェックボックスと違い、作者の枠線と padding は大きさに効く（Chrome の実測）が、
     * 描画は部品の溝とつまみだけなので、枠の色だけを UA の状態ごとの強調色に置き換える。
     */
    public static boolean isNativeRange(HtmlNode node, Map<String, String> style) {
        if (!"input".equals(node.tag)) return false;
        if (!"range".equalsIgnoreCase(node.attrs.getOrDefault("type", "").trim())) return false;
        String appearance = style.get(CssProperty.APPEARANCE);
        return appearance != null && !"none".equalsIgnoreCase(appearance.trim());
    }

    /** {@code appearance} が none でないチェックボックス・ラジオボタン。 */
    public static boolean isNativeToggle(HtmlNode node, Map<String, String> style) {
        if (!"input".equals(node.tag)) return false;
        String type = node.attrs.getOrDefault("type", "").trim().toLowerCase(java.util.Locale.ROOT);
        if (!type.equals("checkbox") && !type.equals("radio")) return false;
        String appearance = style.get(CssProperty.APPEARANCE);
        return appearance != null && !"none".equalsIgnoreCase(appearance.trim());
    }

    /**
     * カスケードを重ねていく途中の状態。規則の宣言を、オリジンとレイヤーが変わるたびに巻き戻し先の
     * スナップショットを取りながら、宣言順に適用する。
     */
    private static final class CascadeState {
        private final Map<String, String> result;
        private final Map<String, String> uaValues;
        private final Map<String, String> userValues;
        private final Map<String, String> parent;

        /** 現在のレイヤー（と重要度・オリジン）の始まりの時点の結果。{@code revert-layer} の巻き戻し先。 */
        private Map<String, String> layerBase;
        private CascadeOrigin currentOrigin;
        private int currentLayer;
        private boolean currentImportant;
        private boolean started;

        CascadeState(Map<String, String> result, Map<String, String> uaValues, Map<String, String> userValues,
                     Map<String, String> parent) {
            this.result = result;
            this.uaValues = uaValues;
            this.userValues = userValues;
            this.parent = parent;
            this.layerBase = new LinkedHashMap<>(result);
        }

        void apply(StyleRule rule) {
            StyleSheet.Specificity s = rule.specificity;
            if (!started || s.origin() != currentOrigin || s.layer() != currentLayer || s.important() != currentImportant) {
                layerBase = new LinkedHashMap<>(result);
                currentOrigin = s.origin();
                currentLayer = s.layer();
                currentImportant = s.important();
                started = true;
            }
            switch (s.origin()) {
                case USER_AGENT -> {
                    applyDeclarations(result, rule.properties, null, layerBase, parent);
                    applyDeclarations(uaValues, rule.properties, null, uaValues, parent);
                    applyDeclarations(userValues, rule.properties, null, userValues, parent);
                }
                case USER -> {
                    applyDeclarations(result, rule.properties, uaValues, layerBase, parent);
                    applyDeclarations(userValues, rule.properties, uaValues, userValues, parent);
                }
                default -> applyDeclarations(result, rule.properties, userValues, layerBase, parent);
            }
        }

        /** style 属性の宣言。作者のスタイルの層に入っていない宣言と同じ位置で重なる。 */
        void applyInline(Map<String, String> declarations) {
            if (declarations.isEmpty()) return;
            if (currentOrigin != CascadeOrigin.AUTHOR || currentLayer != StyleSheet.LayerTree.UNLAYERED) {
                layerBase = new LinkedHashMap<>(result);
                currentOrigin = CascadeOrigin.AUTHOR;
                currentLayer = StyleSheet.LayerTree.UNLAYERED;
            }
            applyDeclarations(result, declarations, userValues, layerBase, parent);
        }
    }

    /**
     * 登録済みのカスタムプロパティ（{@code @property}）の初期値と継承の有無を反映する。
     * 継承しないプロパティは親から受け取らず、初期値があれば初期値から始める。継承するプロパティは
     * 親が値を持っていなければ初期値から始める。
     */
    private static void applyRegisteredInitialValues(Map<String, String> result, Map<String, String> parent,
                                                     StyleSheet sheet) {
        if (sheet == null || sheet.registeredProperties.isEmpty()) return;
        for (CustomPropertyRegistration registration : sheet.registeredProperties.values()) {
            String name = registration.name();
            if (!registration.inherits()) result.remove(name);
            if (!result.containsKey(name) && registration.initialValue() != null) {
                result.put(name, registration.initialValue());
            }
        }
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
     * @param revertSource {@code revert} の巻き戻し先（1 つ下のオリジンの結果）。UA オリジンの宣言を適用するときは
     *                     null（UA 自身の {@code revert} は {@code unset} になる）
     * @param layerBase    {@code revert-layer} の巻き戻し先（今のレイヤーの始まりの時点の結果）
     */
    private static void applyDeclarations(Map<String, String> target, Map<String, String> decls,
                                          Map<String, String> revertSource, Map<String, String> layerBase,
                                          Map<String, String> parent) {
        for (Map.Entry<String, String> decl : decls.entrySet()) {
            String key = decl.getKey();
            String value = decl.getValue();
            CssWideKeyword keyword = CssWideKeyword.of(value);

            if ("all".equals(key)) {
                if (keyword != null) applyAll(target, keyword, revertSource, layerBase, parent);
                continue;
            }
            if (keyword == CssWideKeyword.REVERT && revertSource != null) {
                revert(target, key, revertSource);
                continue;
            }
            if (keyword == CssWideKeyword.REVERT_LAYER) {
                revert(target, key, layerBase);
                continue;
            }

            PropertyRegistry.Property p = PropertyRegistry.get(key);
            if (p != null && p.expander() != null && keyword == null && !value.contains("var(")
                    && p.expander().apply(value.trim()).isEmpty()) {
                continue; // 解釈できないショートハンドは宣言ごと無効（先行するロングハンドを消さない）
            }
            if (keyword == null && !value.contains("var(") && !isValidColorValue(p, key, value)) {
                continue; // 解釈できない色も宣言ごと無効（以前は描画が不透明な白で塗っていた）
            }
            for (String sub : PropertyRegistry.subproperties(key)) target.remove(sub);
            target.put(key, keyword == CssWideKeyword.REVERT ? "unset" : value);
        }
    }

    /** 色の型のプロパティなら、値が色として解けるか。色の型でなければ常に真。 */
    private static boolean isValidColorValue(PropertyRegistry.Property p, String key, String value) {
        if (p == null || p.type() != PropertyRegistry.ValueType.COLOR || CssWideKeyword.of(value) != null) return true;
        // outline-color だけは invert を取る（CSS UI 4 §3.4）
        if ("outline-color".equals(key) && "invert".equalsIgnoreCase(value.trim())) return true;
        return ColorParser.isColor(value);
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
                                 Map<String, String> revertSource, Map<String, String> layerBase,
                                 Map<String, String> parent) {
        target.keySet().removeIf(k -> !k.startsWith("--") && !"direction".equals(k));
        Map<String, String> source = keyword == CssWideKeyword.REVERT_LAYER ? layerBase
                : keyword == CssWideKeyword.REVERT ? revertSource : null;
        if (source != null) {
            source.forEach((k, v) -> {
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

    /**
     * このノードの対話状態（:hover / :active / :focus*）が変わると、シート内のどこかの規則の照合が変わり得るか。
     * セレクタのどの位置の複合セレクタでもよい（{@code .parent:hover .child} は {@code .parent} で真）。
     * その複合セレクタより左（祖先・先行する兄弟）も状態を無視して照合し、右側は照合しない（保守的に真）。
     *
     * 何が壊れていたか: 以前は状態を持つ複合セレクタを左側の結合子を見ずに node に当てていたので、
     * {@code .menu .item:hover} が {@code .menu} の外の {@code .item} でも真になっていた。
     */
    public static boolean hasInteractiveRules(HtmlNode node, StyleSheet sheet) {
        if (node == null || sheet == null) return false;
        for (StyleRule rule : sheet.rules) {
            if ((rule.stateMask & ElementState.INTERACTIVE_MASK) == 0) continue;
            List<SelectorToken> compounds = rule.parsedSelector().compounds();
            for (int i = 0; i < compounds.size(); i++) {
                if ((compounds.get(i).dependentStateMask() & ElementState.INTERACTIVE_MASK) != 0
                        && SelectorMatcher.matchesPrefixIgnoringState(rule.parsedSelector(), i, node)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 兄弟結合子（{@code +} / {@code ~}）の左側に状態擬似クラスを持つ規則があるか。
     * あれば、状態の変わった要素の後続の兄弟（とその子孫）も再スタイルしなければならない
     * （{@code input:checked + label}、{@code .tab:hover ~ .panel}）。
     */
    public static boolean hasSiblingStateRules(StyleSheet sheet) {
        if (sheet == null) return false;
        for (StyleRule rule : sheet.rules) {
            if (rule.stateMask != 0 && hasSiblingStateRule(rule.parsedSelector())) return true;
        }
        return false;
    }

    /** {@code :is(input:checked + label) span} のように :is() / :not() の中の兄弟結合子も見る。 */
    private static boolean hasSiblingStateRule(Selector selector) {
        List<SelectorToken> compounds = selector.compounds();
        List<Character> combinators = selector.combinators();
        boolean stateOnLeft = false;
        for (int i = 0; i < compounds.size(); i++) {
            SelectorToken token = compounds.get(i);
            for (Selector nested : token.notSelectors) {
                if (hasSiblingStateRule(nested)) return true;
            }
            for (List<Selector> group : token.anyOfSelectors) {
                for (Selector nested : group) {
                    if (hasSiblingStateRule(nested)) return true;
                }
            }
            for (List<RelativeSelector> group : token.hasSelectors) {
                for (RelativeSelector relative : group) {
                    if (hasSiblingStateRule(relative.selector())) return true;
                }
            }
            if (i >= combinators.size()) break;
            stateOnLeft |= token.dependentStateMask() != 0;
            char c = combinators.get(i);
            if (stateOnLeft && (c == '+' || c == '~')) return true;
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
        return finalizeComputedStyle(rawStyle, sheet, env, parentStyle, parentFontSize, rootFontSize,
                viewportW, viewportH, ContainerBox.NONE_SIZE);
    }

    /**
     * 問い合わせコンテナの寸法を足した算出。{@code cqw} などのコンテナ単位は、この寸法で px に解く。
     *
     * @param container 最も近い祖先の問い合わせコンテナ。無ければ {@link ContainerBox#NONE_SIZE}
     */
    public static ComputedStyle finalizeComputedStyle(Map<String, String> rawStyle, StyleSheet sheet,
                                                      Map<String, String> env, Map<String, String> parentStyle,
                                                      float parentFontSize, float rootFontSize,
                                                      int viewportW, int viewportH,
                                                      ContainerBox container) {
        return finalizeComputedStyle(rawStyle, sheet, env, parentStyle, parentFontSize, rootFontSize, Float.NaN,
                viewportW, viewportH, container);
    }

    /**
     * ルート要素の行の高さを足した算出。{@code rlh} は、この高さ（px）で px に解く。
     *
     * @param rootLineHeight ルート要素の行の高さ（px）。分からなければ NaN（ルートの {@code normal} の高さ）
     */
    public static ComputedStyle finalizeComputedStyle(Map<String, String> rawStyle, StyleSheet sheet,
                                                      Map<String, String> env, Map<String, String> parentStyle,
                                                      float parentFontSize, float rootFontSize, float rootLineHeight,
                                                      int viewportW, int viewportH,
                                                      ContainerBox container) {
        Map<String, String> style = new LinkedHashMap<>(rawStyle);
        float ownFontSize = estimateFontSize(rawStyle, parentFontSize, rootFontSize, viewportW, viewportH);
        float rootLineHeightPx = Float.isNaN(rootLineHeight)
                ? rootFontSize * LengthResolver.NORMAL_LINE_HEIGHT_EM : rootLineHeight;
        Metrics typedMetrics = new Metrics(0, viewportW, viewportH, ownFontSize, rootFontSize,
                ownFontSize * LengthResolver.NORMAL_LINE_HEIGHT_EM, rootLineHeightPx,
                container.width(), container.height());
        substituteVariables(style, rawStyle, sheet, env, parentStyle != null ? parentStyle : Map.of(), typedMetrics);
        PropertyRegistry.expandShorthands(style);
        ValueComputer.compute(style, new ValueComputer.Context(
                parentStyle != null ? parentStyle : Map.of(), parentFontSize, rootFontSize, viewportW, viewportH,
                container.width(), container.height(), rootLineHeight));
        return ComputedStyle.of(style, parentFontSize, rootFontSize);
    }

    /** 登録したカスタムプロパティの絶対化に使う、この要素のフォントサイズ（var() を含むなら親のまま）。 */
    private static float estimateFontSize(Map<String, String> raw, float parentFontSize, float rootFontSize,
                                          int viewportW, int viewportH) {
        String fontSize = raw.get(CssProperty.FONT_SIZE);
        if (fontSize == null || fontSize.contains("var(") || CssWideKeyword.of(fontSize) != null) return parentFontSize;
        return ValueComputer.resolveFontSize(fontSize,
                new ValueComputer.Context(Map.of(), parentFontSize, rootFontSize, viewportW, viewportH,
                        Float.NaN, Float.NaN));
    }

    /**
     * カスタムプロパティの参照を解く。参照先は要素自身（継承分を含む）→ {@code :root} の変数表 →
     * 埋め込み側の環境変数（{@code --name} を {@code name} で引く）の順。
     * 算出時に無効になった宣言は {@code unset} に、無効なカスタムプロパティは削除する。
     *
     * 登録したカスタムプロパティ（{@code @property}）は、解いた値が構文に合うかを確かめて算出値（絶対化した形）にする。
     * 合わなければ算出時に無効で、継承するなら親の算出値、しなければ初期値になる。
     */
    private static void substituteVariables(Map<String, String> style, Map<String, String> raw,
                                            StyleSheet sheet, Map<String, String> env,
                                            Map<String, String> parent, Metrics metrics) {
        Function<String, CustomPropertyRegistration> registrations =
                name -> sheet == null ? null : sheet.registeredProperties.get(name);
        VarResolver vars = new VarResolver(name -> {
            String v = raw.get(name);
            if (v == null && sheet != null) v = sheet.variables.get(name);
            if (v == null && env != null) v = env.get(name.substring(2));
            // 空のカスタムプロパティは未定義と同じに扱う（フォールバックが効く。従来の挙動）
            return v == null || v.isBlank() ? null : v;
        }, (name, resolved) -> {
            CustomPropertyRegistration registration = registrations.apply(name);
            if (registration == null) return resolved;
            String computed = registration.compute(resolved, metrics);
            if (computed != null) return computed;
            // 算出時に無効: 継承するなら親の算出値、そうでなければ初期値
            String fallback = registration.inherits() ? parent.get(name) : null;
            return fallback != null ? fallback : registration.initialValue();
        });
        for (String key : new ArrayList<>(style.keySet())) {
            String value = style.get(key);
            if (key.startsWith("--")) {
                String resolved = vars.resolveCustomProperty(key);
                if (resolved == null) style.remove(key);
                else style.put(key, resolved);
            } else if (value != null && value.contains("var(")) {
                String resolved = vars.substitute(value);
                if (resolved != null && !isValidColorValue(PropertyRegistry.get(key), key, resolved)) resolved = null;
                style.put(key, resolved != null ? resolved : "unset");
            }
        }
    }

    /**
     * 1 つの DOM ノードに対するスタイル解決の文脈。
     *
     * 以前は {@code base / hover / active / focus} の 4 マップに分けて {@code putAll} で重ねていたため、
     * 詳細度や出現順に関わらず {@code :hover} が基底スタイルに勝ち、{@code :active} が {@code :focus} に勝っていた。
     * 現在は状態をカスケードの入力（{@link StateLookup}）として渡し、
     * 詳細度と出現順に従った単一のカスケードで解決する。
     */
    static final class VarResolver {

        /** 置換結果がこの長さを超えたら無効にする（{@code --a: var(--b) var(--b)} の倍々ゲーム対策）。 */
        private static final int MAX_LENGTH = 65536;

        private final Function<String, String> lookup;
        /** 解いた値を、登録済みのプロパティの型で算出値にする（登録が無ければそのまま返す）。 */
        private final java.util.function.BiFunction<String, String, String> finisher;

        /**
         * @param lookup カスタムプロパティ名（{@code --x}）→ 置換前の値。未定義なら null
         */
        VarResolver(Function<String, String> lookup) {
            this(lookup, (name, resolved) -> resolved);
        }

        VarResolver(Function<String, String> lookup, java.util.function.BiFunction<String, String, String> finisher) {
            this.lookup = lookup;
            this.finisher = finisher;
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
                String substituted = raw == null ? null : substitute(raw, resolving);
                return finisher.apply(name, substituted);
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
