package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.Selector.ElementState;
import com.nishiyu.enderframelib.core.css.Selector.AttrMatcher;
import com.nishiyu.enderframelib.core.css.Selector.DocumentPseudoClass;
import com.nishiyu.enderframelib.core.css.Selector.Nth;
import com.nishiyu.enderframelib.core.css.Selector.RelativeSelector;
import com.nishiyu.enderframelib.core.css.Selector.SelectorToken;
import com.nishiyu.enderframelib.core.css.StyleSheet.StyleRule;
import com.nishiyu.enderframelib.core.dom.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.HtmlElement;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.dom.HtmlNode.NodeType;
import com.nishiyu.enderframelib.core.dom.ConstraintValidator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

/**
 * パース済みセレクタと DOM ノードの照合。
 * 以前は {@link StyleRule} と {@link SelectorToken} に分かれて埋まっていた照合ロジックをここに集約した。
 *
 * 対話状態（:hover / :active / :focus / :focus-visible / :focus-within）および
 * フォーム状態（:checked / :disabled / :indeterminate / :open）は {@link StateLookup} 経由で
 * 複合セレクタごとに照合する。
 *
 * 何が壊れていたか: 結合子（子孫・間接兄弟）の左側は最も近い一致で確定させていて、後戻りしなかった。
 * {@code .a > .b .c} は、{@code .c} に最も近い {@code .b} の親が {@code .a} でなければ、
 * もっと外側の {@code .b}（親が {@code .a}）があっても一致しなかった。いまは全候補を後戻りして試す。
 */
public final class SelectorMatcher {

    /** コンテナの種類（{@code container-type}）。{@code NORMAL} は問い合わせの対象にならない。 */
    public enum ContainerType { NORMAL, INLINE_SIZE, SIZE }

    /**
     * 問い合わせコンテナの寸法・名前・カスタムプロパティの算出値。
     * 寸法の問い合わせに答えられるのは {@code container-type} が {@code normal} でない要素で、寸法はレイアウトの結果
     * （内容ボックス、px。測っていなければ NaN）。{@code style()} の問い合わせにはどの要素も答えられる。
     */
    public record ContainerBox(float width, float height, ContainerType type, List<String> names,
                               Map<String, String> customProperties) {

        /** コンテナが無いときの寸法（幅も高さも NaN）。{@code cqw} などの単位はビューポートに戻る。 */
        public static final ContainerBox NONE_SIZE =
                new ContainerBox(Float.NaN, Float.NaN, ContainerType.NORMAL, List.of(), Map.of());

        /** 寸法の問い合わせ（{@code @container (min-width: …)}）に答えられるコンテナか。 */
        public boolean isSizeContainer() {
            return type != ContainerType.NORMAL;
        }

        public boolean hasName(String name) {
            return name.isEmpty() || names.contains(name);
        }
    }

    /** 任意の DOM ノードに対する現在の {@link ElementState} を返す関数。 */
    @FunctionalInterface
    public interface StateLookup {
        ElementState stateOf(HtmlNode node);

        /** {@code node} をコンテナとして見たときの種類・寸法・カスタムプロパティ。算出スタイルが無ければ null。 */
        default ContainerBox containerOf(HtmlNode node) {
            return null;
        }

        /**
         * {@code from} から祖先へたどって、最初の（寸法の問い合わせに答えられる）コンテナ。
         * {@code cqw} などのコンテナ単位の基準になる。無ければ {@link ContainerBox#NONE_SIZE}。
         */
        default ContainerBox nearestSizeContainer(HtmlNode from) {
            for (HtmlNode cur = from; cur != null; cur = cur.parent) {
                ContainerBox box = containerOf(cur);
                if (box != null && box.isSizeContainer()) return box;
            }
            return ContainerBox.NONE_SIZE;
        }
    }

    /** 実行時の対話状態が無い場合、DOM ノードの静的属性だけから状態を導出する既定の参照先。 */
    public static final StateLookup STATIC_ATTRIBUTES = ElementState::fromNodeAttributes;

    private SelectorMatcher() {}

    /** セレクタ全体（結合子を含む）が静的属性のみで node に一致するか。 */
    public static boolean matches(Selector selector, HtmlNode node) {
        return matches(selector, node, STATIC_ATTRIBUTES);
    }

    /** セレクタ全体（結合子を含む）が指定の要素状態のもとで node に一致するか。 */
    public static boolean matches(Selector selector, HtmlNode node, StateLookup stateLookup) {
        StateLookup lookup = stateLookup != null ? stateLookup : STATIC_ATTRIBUTES;
        if (selector.compounds().isEmpty()) return false;
        return matchesChain(selector, selector.compounds().size() - 1, node,
                (t, n) -> matchesCompound(t, n, lookup), null);
    }

    /**
     * セレクタの先頭から {@code index} 番目までの複合セレクタ（{@code index} 番目を node に当てる）が、
     * 状態の条件をすべて満たせるものとみなしたときに一致するか。
     *
     * 「node の状態が変わると、どこかの要素の照合が変わり得るか」の判定に使う。{@code .parent:hover .child} の
     * {@code .parent:hover} は {@code .parent} に当たり、右側（{@code .child}）は照合しない。
     */
    public static boolean matchesPrefixIgnoringState(Selector selector, int index, HtmlNode node) {
        if (index < 0 || index >= selector.compounds().size()) return false;
        return matchesChain(selector, index, node, SelectorMatcher::matchesCompoundIgnoringState, null);
    }

    /** 状態の条件を満たせるものとみなした複合セレクタの照合。状態を含む {@code :not()} はどちらにもなり得るので通す。 */
    private static boolean matchesCompoundIgnoringState(SelectorToken token, HtmlNode node) {
        for (Selector not : token.notSelectors) {
            if (not.stateMask() == 0 && matches(not, node, STATIC_ATTRIBUTES)) return false;
        }
        for (List<Selector> group : token.anyOfSelectors) {
            boolean any = false;
            for (Selector s : group) {
                if (s.stateMask() != 0 || matches(s, node, STATIC_ATTRIBUTES)) {
                    any = true;
                    break;
                }
            }
            if (!any) return false;
        }
        // :has() は子孫の状態にも左右されるので、状態を無視した照合では一致し得るものとして通す
        return matchesCompoundWithoutInteractiveState(token, node, STATIC_ATTRIBUTES, false);
    }

    /**
     * {@code :has()} の持ち主（アンカー）。相対セレクタの左端の複合セレクタが、アンカーから
     * {@code combinator} の関係にある要素に当たったときだけ、そのセレクタは一致とみなす。
     */
    private record Anchor(HtmlNode element, char combinator) {
        boolean accepts(HtmlNode leftmost) {
            return switch (combinator) {
                case '>' -> leftmost.parent == element;
                case '+' -> previousElementSibling(leftmost) == element;
                case '~' -> {
                    for (HtmlNode s = previousElementSibling(leftmost); s != null; s = previousElementSibling(s)) {
                        if (s == element) yield true;
                    }
                    yield false;
                }
                default -> {
                    for (HtmlNode p = leftmost.parent; p != null; p = p.parent) {
                        if (p == element) yield true;
                    }
                    yield false;
                }
            };
        }
    }

    /**
     * {@code index} 番目の複合セレクタを {@code node} に当て、左へ結合子をたどって先頭まで一致するか。
     * 子孫・間接兄弟の結合子は、条件を満たす全候補を後戻りして試す。
     */
    private static boolean matchesChain(Selector selector, int index, HtmlNode node,
                                        BiPredicate<SelectorToken, HtmlNode> compoundTest, Anchor anchor) {
        List<SelectorToken> compounds = selector.compounds();
        List<Character> combinators = selector.combinators();
        if (node == null || !compoundTest.test(compounds.get(index), node)) return false;
        if (index == 0) return anchor == null || anchor.accepts(node);

        char combinator = combinators.get(index - 1);
        switch (combinator) {
            case '>': {
                return matchesChain(selector, index - 1, node.parent, compoundTest, anchor);
            }
            case '+': {
                return matchesChain(selector, index - 1, previousElementSibling(node), compoundTest, anchor);
            }
            case '~': {
                for (HtmlNode s = previousElementSibling(node); s != null; s = previousElementSibling(s)) {
                    if (matchesChain(selector, index - 1, s, compoundTest, anchor)) return true;
                }
                return false;
            }
            default: { // ' ' 子孫結合子
                for (HtmlNode p = node.parent; p != null; p = p.parent) {
                    if (matchesChain(selector, index - 1, p, compoundTest, anchor)) return true;
                }
                return false;
            }
        }
    }

    /** 複合セレクタ 1 単位が静的属性のみで node に一致するか。 */
    public static boolean matchesCompound(SelectorToken token, HtmlNode node) {
        return matchesCompound(token, node, STATIC_ATTRIBUTES);
    }

    /** 複合セレクタ 1 単位が指定の要素状態のもとで node に一致するか。 */
    public static boolean matchesCompound(SelectorToken token, HtmlNode node, StateLookup stateLookup) {
        StateLookup lookup = stateLookup != null ? stateLookup : STATIC_ATTRIBUTES;

        if (!matchesCompoundWithoutInteractiveState(token, node, lookup, true)) return false;

        for (Selector not : token.notSelectors) {
            if (matches(not, node, lookup)) return false;
        }
        for (List<Selector> group : token.anyOfSelectors) {
            boolean any = false;
            for (Selector s : group) {
                if (matches(s, node, lookup)) {
                    any = true;
                    break;
                }
            }
            if (!any) return false;
        }

        if (token.stateFlags != 0 || token.absentStateFlags != 0) {
            ElementState state = lookup.stateOf(node);
            if (state == null) state = ElementState.NONE;
            if (state.hasAny(token.absentStateFlags)) return false;

            int nonFocusWithin = token.stateFlags & ~ElementState.FOCUS_WITHIN;
            if (nonFocusWithin != 0 && !state.has(nonFocusWithin)) return false;

            if ((token.stateFlags & ElementState.FOCUS_WITHIN) != 0) {
                // :focus-within は祖先方向の照合（自身に FOCUS_WITHIN が立っているか、子孫に FOCUS があるとき成立）。
                // 以前は :focus と同一フラグだったため、子孫ではなく自分のフォーカスで誤って成立していた。
                if (!state.focusWithin() && !hasFocusedDescendant(node, lookup)) return false;
            }
        }

        return true;
    }

    /**
     * 状態（:hover / :checked など）と {@code :not()} / {@code :is()} を除いた構造・属性・文書の条件が node に一致するか。
     */
    public static boolean matchesCompoundWithoutInteractiveState(SelectorToken token, HtmlNode node) {
        return matchesCompoundWithoutInteractiveState(token, node, STATIC_ATTRIBUTES, true);
    }

    private static boolean matchesCompoundWithoutInteractiveState(SelectorToken token, HtmlNode node,
                                                                  StateLookup lookup, boolean evaluateHas) {
        if (node == null) return false;
        if (token.invalid || token.unrenderedPseudoElement || token.isVisited) return false;
        // :root は文書要素（ルートの子の html）だけ。W7 までは文書に html が無いことがあったので、
        // パーサの合成ノードにも一致させていた（html を書いた文書では :root の宣言が 2 つの箱に掛かっていた）
        if (token.isRoot && !isDocumentElement(node)) return false;
        if (token.id != null && !token.id.equals(node.getId())) return false;
        if (!token.tag.equals("*") && !token.tag.equals(node.tag)) return false;

        for (String c : token.classes) {
            if (!node.hasClass(c)) return false;
        }

        if (token.isEmpty && hasContent(node)) return false;
        if (token.isLink && !isLink(node)) return false;
        if (token.isEnabled && !isFormControl(node)) return false;
        if ((token.isRequired || token.isOptional) && !isRequirable(node)) return false;
        if (token.isRequired && !node.attrs.containsKey("required")) return false;
        if (token.isOptional && node.attrs.containsKey("required")) return false;
        for (AttrMatcher m : token.attrMatchers) {
            if (!m.matches(node.attrs.get(m.name()))) return false;
        }
        for (DocumentPseudoClass pseudo : token.documentPseudoClasses) {
            if (!matchesDocumentPseudoClass(pseudo, node)) return false;
        }
        for (List<String> ranges : token.langRanges) {
            if (!matchesLanguage(ranges, languageOf(node))) return false;
        }
        for (String direction : token.directions) {
            if (!direction.equals(directionOf(node))) return false;
        }
        if (!matchesStructuralPosition(token, node, lookup)) return false;
        if (evaluateHas) {
            for (List<RelativeSelector> group : token.hasSelectors) {
                if (!matchesHas(group, node, lookup)) return false;
            }
        }
        return true;
    }

    // ---- :has()

    /** {@code :has()} の相対セレクタのうち 1 つでも、{@code anchor} から見て一致する要素があるか。 */
    private static boolean matchesHas(List<RelativeSelector> group, HtmlNode anchor, StateLookup lookup) {
        for (RelativeSelector relative : group) {
            Selector selector = relative.selector();
            Anchor boundary = new Anchor(anchor, relative.combinator());
            boolean[] found = {false};
            forEachCandidate(anchor, relative.combinator(), candidate -> {
                if (!found[0] && matchesChain(selector, selector.compounds().size() - 1, candidate,
                        (t, n) -> matchesCompound(t, n, lookup), boundary)) {
                    found[0] = true;
                }
            });
            if (found[0]) return true;
        }
        return false;
    }

    /** 相対セレクタの主語になり得る要素: 子孫は {@code anchor} の部分木、兄弟は後続の兄弟とその部分木。 */
    private static void forEachCandidate(HtmlNode anchor, char combinator, java.util.function.Consumer<HtmlNode> action) {
        if (combinator == '+' || combinator == '~') {
            if (anchor.parent == null) return;
            boolean after = false;
            for (HtmlNode sibling : anchor.parent.children) {
                if (sibling == anchor) {
                    after = true;
                } else if (after && isCountableElement(sibling)) {
                    visitSubtree(sibling, action);
                }
            }
            return;
        }
        for (HtmlNode child : anchor.children) {
            if (isCountableElement(child)) visitSubtree(child, action);
        }
    }

    private static void visitSubtree(HtmlNode root, java.util.function.Consumer<HtmlNode> action) {
        action.accept(root);
        for (HtmlNode child : root.children) {
            if (isCountableElement(child)) visitSubtree(child, action);
        }
    }

    // ---- 文書や入力の状態で決まる疑似クラス

    private static boolean matchesDocumentPseudoClass(DocumentPseudoClass pseudo, HtmlNode node) {
        return switch (pseudo) {
            case PLACEHOLDER_SHOWN -> isPlaceholderShown(node);
            case READ_WRITE -> isReadWrite(node);
            case READ_ONLY -> !isReadWrite(node);
            case VALID -> validity(node) == Boolean.TRUE;
            case INVALID -> validity(node) == Boolean.FALSE;
            case IN_RANGE -> rangeState(node) == Boolean.TRUE;
            case OUT_OF_RANGE -> rangeState(node) == Boolean.FALSE;
            case DEFINED -> !(documentOf(node) instanceof HtmlDocument doc) || doc.isElementDefined(node.tag);
            case TARGET -> documentOf(node) instanceof HtmlDocument doc && doc.getTargetElement() == node;
        };
    }

    private static HtmlNode documentOf(HtmlNode node) {
        return node.getOwnerDocument() instanceof HtmlNode doc ? doc : null;
    }

    /** {@code placeholder} を持つ入力欄で、値が空のとき（HTML §4.16.3）。 */
    private static boolean isPlaceholderShown(HtmlNode node) {
        if (!(node instanceof HtmlElement el)) return false;
        boolean textarea = "textarea".equals(node.tag);
        boolean input = "input".equals(node.tag) && supportsPlaceholder(inputType(node));
        if (!textarea && !input) return false;
        String placeholder = node.attrs.get("placeholder");
        return placeholder != null && !placeholder.isEmpty() && ConstraintValidator.getControlValue(el).isEmpty();
    }

    private static boolean supportsPlaceholder(String type) {
        return switch (type) {
            case "text", "search", "url", "tel", "email", "password", "number" -> true;
            default -> false;
        };
    }

    private static String inputType(HtmlNode node) {
        String type = node.attrs.get("type");
        return type == null || type.isBlank() ? "text" : type.trim().toLowerCase(Locale.ROOT);
    }

    /** 利用者が値を編集できる要素（HTML §4.16.3 の :read-write）。 */
    private static boolean isReadWrite(HtmlNode node) {
        if (node.nodeType != NodeType.ELEMENT) return false;
        boolean editableControl = switch (node.tag) {
            case "textarea" -> true;
            case "input" -> switch (inputType(node)) {
                case "text", "search", "url", "tel", "email", "password", "number", "date", "month", "week",
                     "time", "datetime-local" -> true;
                default -> false;
            };
            default -> false;
        };
        if (editableControl) {
            return !node.attrs.containsKey("readonly") && !node.attrs.containsKey("disabled");
        }
        // contenteditable は編集ホストとその子孫
        for (HtmlNode cur = node; cur != null; cur = cur.parent) {
            String value = cur.attrs.get("contenteditable");
            if (value != null) {
                String v = value.trim().toLowerCase(Locale.ROOT);
                if (v.isEmpty() || v.equals("true") || v.equals("plaintext-only")) return true;
                if (v.equals("false")) return false;
            }
        }
        return false;
    }

    /**
     * 制約検証の結果。検証の対象でない要素は null、対象なら妥当かどうか。
     * {@code form} / {@code fieldset} は子孫の検証対象がすべて妥当なら妥当。
     */
    private static Boolean validity(HtmlNode node) {
        if (!(node instanceof HtmlElement el)) return null;
        if ("form".equals(node.tag) || "fieldset".equals(node.tag)) {
            return allControlsValid(node);
        }
        if (!ConstraintValidator.willValidate(el)) return null;
        return el.getValidity().isValid();
    }

    private static boolean allControlsValid(HtmlNode container) {
        for (HtmlNode child : container.children) {
            if (child instanceof HtmlElement el && ConstraintValidator.willValidate(el) && !el.getValidity().isValid()) {
                return false;
            }
            if (child.nodeType == NodeType.ELEMENT && !allControlsValid(child)) return false;
        }
        return true;
    }

    /** 範囲の制約。範囲が当てはまらない要素は null、当てはまるなら範囲内かどうか。 */
    private static Boolean rangeState(HtmlNode node) {
        if (!(node instanceof HtmlElement el) || !"input".equals(node.tag)) return null;
        switch (inputType(node)) {
            case "number", "range", "date", "month", "week", "time", "datetime-local" -> {
            }
            default -> {
                return null;
            }
        }
        // min / max を書いていない number なども範囲内として扱う（ブラウザの :in-range と同じ）
        if (!ConstraintValidator.willValidate(el) || node.attrs.containsKey("readonly")) return null;
        var state = el.getValidity();
        return !(state.isRangeOverflow() || state.isRangeUnderflow());
    }

    // ---- :lang() / :dir()

    /** 要素の言語。自分か最も近い祖先の {@code lang}（{@code xml:lang}）。無ければ空。 */
    private static String languageOf(HtmlNode node) {
        for (HtmlNode cur = node; cur != null; cur = cur.parent) {
            String lang = cur.attrs.get("lang");
            if (lang == null) lang = cur.attrs.get("xml:lang");
            if (lang != null) return lang.trim().toLowerCase(Locale.ROOT);
        }
        return "";
    }

    private static boolean matchesLanguage(List<String> ranges, String language) {
        for (String range : ranges) {
            if (languageMatches(range, language)) return true;
        }
        return false;
    }

    /** RFC 4647 の拡張フィルタリング（{@code en} は {@code en-US} に、{@code *-US} は {@code en-US} に合う）。 */
    static boolean languageMatches(String range, String language) {
        if (language.isEmpty()) return range.isEmpty();
        if (range.equals("*")) return true;
        String[] r = range.split("-");
        String[] t = language.split("-");
        if (!r[0].equals("*") && !r[0].equals(t[0])) return false;
        int ri = 1;
        int ti = 1;
        while (ri < r.length) {
            if (r[ri].equals("*")) {
                ri++;
            } else if (ti >= t.length) {
                return false;
            } else if (r[ri].equals(t[ti])) {
                ri++;
                ti++;
            } else if (t[ti].length() == 1) {
                return false;
            } else {
                ti++;
            }
        }
        return true;
    }

    /**
     * 要素の向き（HTML §3.2.6）。{@code dir} は自分か最も近い祖先のもの（{@code auto} は本文の最初の強い文字で決める）、
     * どこにも無ければ {@code ltr}。
     */
    private static String directionOf(HtmlNode node) {
        for (HtmlNode cur = node; cur != null; cur = cur.parent) {
            String dir = cur.attrs.get("dir");
            if (dir != null) {
                String d = dir.trim().toLowerCase(Locale.ROOT);
                if (d.equals("ltr") || d.equals("rtl")) return d;
                if (d.equals("auto")) return directionFromText(cur);
            } else if ("bdi".equals(cur.tag)) {
                return directionFromText(cur);
            }
        }
        return "ltr";
    }

    private static String directionFromText(HtmlNode node) {
        String text = node.getTextContent();
        if (text != null) {
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                byte type = Character.getDirectionality(cp);
                if (type == Character.DIRECTIONALITY_RIGHT_TO_LEFT || type == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                    return "rtl";
                }
                if (type == Character.DIRECTIONALITY_LEFT_TO_RIGHT) return "ltr";
                i += Character.charCount(cp);
            }
        }
        return "ltr";
    }

    private static boolean hasFocusedDescendant(HtmlNode node, StateLookup stateLookup) {
        if (node == null || stateLookup == null) return false;
        for (HtmlNode child : node.children) {
            ElementState childState = stateLookup.stateOf(child);
            if (childState != null && (childState.focus() || childState.focusWithin())) return true;
            if (hasFocusedDescendant(child, stateLookup)) return true;
        }
        return false;
    }

    // ---- 位置（:nth-*）

    private static boolean matchesStructuralPosition(SelectorToken token, HtmlNode node, StateLookup lookup) {
        for (Nth nth : token.nths) {
            // :nth-child(An+B of S) は S に合う要素だけが対象
            if (nth.hasFilter() && !matchesAnyOf(nth.of(), node, lookup)) return false;
            int index = siblingIndex(node, nth, lookup);
            if (index <= 0 || !nth.matchesIndex(index)) return false;
        }
        return true;
    }

    private static boolean matchesAnyOf(List<Selector> selectors, HtmlNode node, StateLookup lookup) {
        for (Selector s : selectors) {
            if (matches(s, node, lookup)) return true;
        }
        return false;
    }

    /**
     * 要素の兄弟の中での 1 始まりの位置。{@code ofType} なら同じ型の兄弟の中で、{@code of S} があれば
     * {@code S} に合う兄弟の中で数える。親が無ければ 1。
     */
    private static int siblingIndex(HtmlNode node, Nth nth, StateLookup lookup) {
        if (node.parent == null) return 1;
        int count = 0;
        int found = -1;
        for (HtmlNode child : node.parent.children) {
            if (!isCountableElement(child) || (nth.ofType() && !child.tag.equals(node.tag))) continue;
            if (nth.hasFilter() && !matchesAnyOf(nth.of(), child, lookup)) continue;
            count++;
            if (child == node) found = count;
        }
        if (found < 0) return -1;
        return nth.fromEnd() ? count - found + 1 : found;
    }

    private static boolean isLink(HtmlNode node) {
        return ("a".equals(node.tag) || "area".equals(node.tag)) && node.attrs.containsKey("href");
    }

    /** :enabled / :disabled の対象（HTML §4.16.3 の「無効にできる」要素）。 */
    private static boolean isFormControl(HtmlNode node) {
        return switch (node.tag) {
            case "button", "input", "select", "textarea", "optgroup", "option", "fieldset" -> true;
            default -> false;
        };
    }

    /** :required / :optional の対象。 */
    private static boolean isRequirable(HtmlNode node) {
        return switch (node.tag) {
            case "input", "select", "textarea" -> true;
            default -> false;
        };
    }

    private static boolean isDocumentElement(HtmlNode node) {
        return node.nodeType == NodeType.ELEMENT && "html".equals(node.tag)
                && (node.parent == null || node.parent.nodeType == NodeType.ROOT);
    }

    private static boolean hasContent(HtmlNode node) {
        if (node.text != null && !node.text.trim().isEmpty()) return true;
        for (HtmlNode c : node.children) {
            if (c.isPseudoNode) continue;
            if (c.nodeType != NodeType.TEXT) return true;
            if (c.text != null && !c.text.trim().isEmpty()) return true;
        }
        return false;
    }

    private static HtmlNode previousElementSibling(HtmlNode node) {
        if (node == null || node.parent == null) return null;
        HtmlNode prev = null;
        for (HtmlNode child : node.parent.children) {
            if (child == node) return prev;
            if (isCountableElement(child)) prev = child;
        }
        return null;
    }

    private static boolean isCountableElement(HtmlNode node) {
        return node.nodeType == NodeType.ELEMENT && !node.tag.equals("template") && !node.isPseudoNode;
    }

    /**
     * {@code querySelector} / {@code querySelectorAll} の実体。
     *
     * パース済みセレクタをキャッシュするため、呼び出しごとにセレクタを再トークナイズしない。
     * {@code core/dom} が {@code core/css} の内部型を直接 new する循環もここで断っている。
     */
    public static final class SelectorEngine {

        /** 信頼できない作者 HTML がキーになり得るため上限を設ける。 */
        private static final int MAX_CACHED_SELECTORS = 512;

        private static final Map<String, List<Selector>> SELECTOR_CACHE = new ConcurrentHashMap<>();

        private SelectorEngine() {}

        /**
         * セレクタリスト（{@code "a:link, a:visited"}）をパースする。無効なセレクタが 1 つでもあれば、
         * ブラウザが SyntaxError を投げる代わりに空のリスト（何にも一致しない）を返す。
         *
         * 何が壊れていたか: 以前はカンマを読み飛ばして 1 本のセレクタとして照合していた。
         */
        public static List<Selector> getOrParse(String selector) {
            String clean = selector == null ? "" : selector.trim();
            List<Selector> cached = SELECTOR_CACHE.get(clean);
            if (cached != null) return cached;

            List<Selector> parsed = new ArrayList<>();
            for (String part : CssParser.CssTokenizer.splitSelectorList(clean)) {
                Selector s = SelectorParser.parse(part);
                if (!s.isValid()) {
                    parsed.clear();
                    break;
                }
                parsed.add(s);
            }
            parsed = List.copyOf(parsed);
            if (SELECTOR_CACHE.size() < MAX_CACHED_SELECTORS) {
                SELECTOR_CACHE.put(clean, parsed);
            }
            return parsed;
        }

        /** 疑似要素（{@code ::before} など）は要素ではないので、{@code querySelector} には当たらない。 */
        private static boolean matchesAny(List<Selector> selectors, HtmlNode node) {
            for (Selector s : selectors) {
                if (s.pseudoElement() == null && SelectorMatcher.matches(s, node)) return true;
            }
            return false;
        }

        public static HtmlNode querySelector(HtmlNode root, String selector) {
            List<Selector> selectors = getOrParse(selector);
            return selectors.isEmpty() ? null : querySelectorInternal(root, selectors);
        }

        private static HtmlNode querySelectorInternal(HtmlNode node, List<Selector> selectors) {
            if (matchesAny(selectors, node)) return node;
            for (HtmlNode child : node.children) {
                HtmlNode found = querySelectorInternal(child, selectors);
                if (found != null) return found;
            }
            return null;
        }

        public static List<HtmlNode> querySelectorAll(HtmlNode root, String selector) {
            List<HtmlNode> results = new ArrayList<>();
            List<Selector> selectors = getOrParse(selector);
            if (!selectors.isEmpty()) querySelectorAllInternal(root, selectors, results);
            return results;
        }

        private static void querySelectorAllInternal(HtmlNode node, List<Selector> selectors, List<HtmlNode> results) {
            if (matchesAny(selectors, node)) results.add(node);
            for (HtmlNode child : node.children) {
                querySelectorAllInternal(child, selectors, results);
            }
        }

        public static void clearCache() {
            SELECTOR_CACHE.clear();
        }
    }
}