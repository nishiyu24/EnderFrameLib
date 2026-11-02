package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.model.*;

import com.nishiyu.enderframelib.core.css.StyleResolver.PseudoType;
import com.nishiyu.enderframelib.core.css.StyleSheet.CascadeOrigin;
import com.nishiyu.enderframelib.core.css.StyleSheet.Specificity;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * パース済みのセレクタ 1 本（カンマで区切られた 1 要素）。
 *
 * compounds と combinators は対になっていて、combinators.get(i) は
 * compounds.get(i) と compounds.get(i + 1) の間の結合子（' ' / '>' / '+' / '~'）を表す。
 */
public record Selector(
        String text,
        List<SelectorToken> compounds,
        List<Character> combinators,
        PseudoType pseudoElement,
        boolean isHover,
        boolean isActive,
        boolean isFocus,
        int stateMask,
        Specificity specificity
) {

    public Selector(
            String text,
            List<SelectorToken> compounds,
            List<Character> combinators,
            PseudoType pseudoElement,
            boolean isHover,
            boolean isActive,
            boolean isFocus,
            Specificity specificity
    ) {
        this(text, compounds, combinators, pseudoElement, isHover, isActive, isFocus,
                (isHover ? ElementState.HOVER : 0)
                        | (isActive ? ElementState.ACTIVE : 0)
                        | (isFocus ? ElementState.FOCUS : 0),
                specificity);
    }

    public boolean isBefore() {
        return pseudoElement == PseudoType.BEFORE;
    }

    public boolean isAfter() {
        return pseudoElement == PseudoType.AFTER;
    }

    /** {@code :has()} を（{@code :is()} / {@code :not()} の中も含めて）使っているか。 */
    public boolean usesHas() {
        for (SelectorToken token : compounds) {
            if (!token.hasSelectors.isEmpty()) return true;
            for (Selector nested : token.notSelectors) {
                if (nested.usesHas()) return true;
            }
            for (List<Selector> group : token.anyOfSelectors) {
                for (Selector nested : group) {
                    if (nested.usesHas()) return true;
                }
            }
        }
        return false;
    }

    /** 右端の複合セレクタ（セレクタが選ぶ要素＝主語）。 */
    public SelectorToken subject() {
        return compounds.getLast();
    }

    public boolean matches(HtmlNode node) {
        return SelectorMatcher.matches(this, node);
    }

    public boolean matches(HtmlNode node, StateLookup stateLookup) {
        return SelectorMatcher.matches(this, node, stateLookup);
    }

    /** 重要度だけを差し替えた同等のセレクタを返す（通常宣言と !important 宣言で同じセレクタを共有する）。 */
    public Selector withImportant(boolean important) {
        return new Selector(text, compounds, combinators, pseudoElement, isHover, isActive, isFocus,
                stateMask, specificity.withImportant(important));
    }

    /** オリジンだけを差し替えた同等のセレクタを返す（UA → 作者の昇格などに使う）。 */
    public Selector withOrigin(CascadeOrigin origin) {
        return new Selector(text, compounds, combinators, pseudoElement, isHover, isActive, isFocus,
                stateMask, specificity.withOrigin(origin));
    }

    /**
     * 構文として正しいか。無効なセレクタは何にも一致せず、セレクタリストに 1 つでもあれば規則全体が捨てられる
     * （Selectors 4 §3.1）。{@code :is()} / {@code :where()} の中の無効な引数は寛容に捨てるのでここには響かない。
     */
    public boolean isValid() {
        for (SelectorToken t : compounds) {
            if (t.invalid) return false;
        }
        return true;
    }

    /**
     * {@code :nth-child(An+B)} 系の位置の条件 1 つ。{@code index} は 1 始まりで、
     * {@code ofType} なら同じ型の兄弟だけを、{@code fromEnd} なら末尾から数える。
     * {@code :first-child} は (0, 1)、{@code :last-child} は (0, 1, 末尾から)。
     */
    public record Nth(int a, int b, boolean ofType, boolean fromEnd, List<Selector> of) {

        /** {@code of S} の絞り込みが無い位置の条件。 */
        public Nth(int a, int b, boolean ofType, boolean fromEnd) {
            this(a, b, ofType, fromEnd, List.of());
        }

        /** {@code :nth-child(2n of .a)} のように、数える兄弟を {@code S} に一致するものだけに絞るか。 */
        public boolean hasFilter() {
            return !of.isEmpty();
        }

        public boolean matchesIndex(int index) {
            if (a == 0) return index == b;
            int diff = index - b;
            return diff % a == 0 && diff / a >= 0;
        }
    }

    /**
     * 属性セレクタ 1 つ。{@code op} は {@code 0}（存在のみ）・{@code '='}・{@code '^'}・{@code '$'}・{@code '*'}・
     * {@code '~'}・{@code '|'}。{@code ignoreCase} は {@code i} 修飾子。
     *
     * 何が壊れていたか: 以前は演算子ごとの {@code Map<名前, 値>} に入れていたので、同じ属性への 2 つ目の条件
     * （{@code [class*="a"][class*="b"]}）が 1 つ目を上書きし、最後の 1 つしか評価されなかった。
     */
    public record AttrMatcher(String name, char op, String value, boolean ignoreCase) {
        public boolean matches(String actual) {
            if (actual == null) return false;
            if (op == 0) return true;
            String v = ignoreCase ? value.toLowerCase(Locale.ROOT) : value;
            String a = ignoreCase ? actual.toLowerCase(Locale.ROOT) : actual;
            return switch (op) {
                case '=' -> a.equals(v);
                // 空の値への ^= / $= / *= / ~= は何にも一致しない（Selectors 4 §6.2〜6.3）
                case '^' -> !v.isEmpty() && a.startsWith(v);
                case '$' -> !v.isEmpty() && a.endsWith(v);
                case '*' -> !v.isEmpty() && a.contains(v);
                case '~' -> {
                    if (v.isEmpty() || v.chars().anyMatch(Character::isWhitespace)) yield false;
                    for (String word : a.trim().split("\\s+")) {
                        if (word.equals(v)) yield true;
                    }
                    yield false;
                }
                case '|' -> a.equals(v) || a.startsWith(v + "-");
                default -> false;
            };
        }
    }

    /**
     * {@code :has()} の相対セレクタ 1 つ。{@code combinator} は {@code :has()} の持ち主（アンカー）から
     * {@code selector} の左端の複合セレクタへの結合子（{@code ' '} / {@code '>'} / {@code '+'} / {@code '~'}）。
     */
    public record RelativeSelector(char combinator, Selector selector) {}

    /** 文書や入力の状態を読んで照合する疑似クラス（構造・属性・状態ビットでは決まらないもの）。 */
    public enum DocumentPseudoClass {
        PLACEHOLDER_SHOWN, READ_ONLY, READ_WRITE, VALID, INVALID, IN_RANGE, OUT_OF_RANGE, DEFINED, TARGET
    }

    /**
     * 複合セレクタ（結合子で区切られた 1 単位、例: {@code div#main.active[data-x]:first-child}）のデータ。
     *
     * 以前はこのクラスのコンストラクタが正規表現と {@code contains} + {@code replace} による
     * 破壊的な文字列手術でパースしていた。現在は {@link SelectorParser} が実トークナイズして
     * このオブジェクトを組み立てるだけで、ここはデータと照合の入口に限定されている。
     */
    public static class SelectorToken {

        public String rawSelector = "";
        public String tag = "*";
        public String id = null;
        public List<String> classes = new ArrayList<>();
        public List<AttrMatcher> attrMatchers = new ArrayList<>();

        /** {@code :not()} の引数（セレクタリスト）。どれか 1 つに一致すれば不一致。 */
        public List<Selector> notSelectors = new ArrayList<>();
        /** {@code :is()} / {@code :where()} 1 つにつき 1 リスト。各リストのどれか 1 つに一致しなければならない。 */
        public List<List<Selector>> anyOfSelectors = new ArrayList<>();
        public List<Nth> nths = new ArrayList<>();
        /** {@code :has()} 1 つにつき 1 リスト。各リストのどれか 1 つに一致する子孫・兄弟が要る。 */
        public List<List<RelativeSelector>> hasSelectors = new ArrayList<>();
        /** 文書や入力の状態で決まる疑似クラス。すべてに当てはまらなければならない。 */
        public List<DocumentPseudoClass> documentPseudoClasses = new ArrayList<>();
        /** {@code :lang()} 1 つにつき言語範囲のリスト（小文字）。どれか 1 つに合えばよい。 */
        public List<List<String>> langRanges = new ArrayList<>();
        /** {@code :dir()} の向き（{@code ltr} / {@code rtl}）。 */
        public List<String> directions = new ArrayList<>();

        /**
         * 構文が無効（未知の擬似クラス・解釈できない An+B・余計な文字など）。何にも一致しない。
         *
         * 何が壊れていたか: 以前は未知の擬似クラスや {@code :nth-child(3n)} を「条件なし」として読み飛ばしたので、
         * セレクタが全要素に一致していた。
         */
        public boolean invalid = false;
        /** 描かない擬似要素（{@code ::placeholder} など）。構文は正しいが要素には一致しない。 */
        public boolean unrenderedPseudoElement = false;

        /** この複合セレクタが要求する {@link ElementState} ビット集合。 */
        public int stateFlags = 0;
        /** 立っていてはならない {@link ElementState} ビット集合（{@code :enabled} の DISABLED）。 */
        public int absentStateFlags = 0;

        public boolean parsedHover = false;
        public boolean parsedActive = false;
        public boolean parsedFocus = false;
        public boolean parsedFocusVisible = false;
        public boolean parsedFocusWithin = false;
        public boolean isChecked = false;
        public boolean isDisabled = false;
        public boolean isIndeterminate = false;
        public boolean isOpen = false;
        public boolean isEnabled = false;
        public boolean isRequired = false;
        public boolean isOptional = false;
        /** :link / :any-link（href を持つ a / area） */
        public boolean isLink = false;
        /** :visited（履歴を持たないので何にも一致しない） */
        public boolean isVisited = false;
        public boolean isEmpty = false;
        /** :root（文書のルート要素） */
        public boolean isRoot = false;

        public SelectorToken() {}

        /** この複合セレクタ（{@code :not()} / {@code :is()} の中も含む）の照合が依存する状態ビット集合。 */
        public int dependentStateMask() {
            int mask = stateFlags | absentStateFlags;
            for (Selector s : notSelectors) mask |= s.stateMask();
            for (List<Selector> group : anyOfSelectors) {
                for (Selector s : group) mask |= s.stateMask();
            }
            for (List<RelativeSelector> group : hasSelectors) {
                for (RelativeSelector r : group) mask |= r.selector().stateMask();
            }
            for (Nth nth : nths) {
                for (Selector s : nth.of()) mask |= s.stateMask();
            }
            return mask;
        }

        /** 後方互換用。セレクタ文字列から複合セレクタ 1 つをパースする。 */
        public SelectorToken(String sel) {
            SelectorParser.parseCompoundInto(sel, this);
        }

        public boolean matches(HtmlNode node) {
            return SelectorMatcher.matchesCompound(this, node);
        }

        public boolean matches(HtmlNode node, StateLookup stateLookup) {
            return SelectorMatcher.matchesCompound(this, node, stateLookup);
        }
    }
}
