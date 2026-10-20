package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.Selector.ElementState;
import com.nishiyu.enderframelib.core.css.Selector.DocumentPseudoClass;
import com.nishiyu.enderframelib.core.css.Selector.RelativeSelector;
import com.nishiyu.enderframelib.core.css.Selector.SelectorToken;
import com.nishiyu.enderframelib.core.css.StyleSheet.StyleRule;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * セレクタから作る無効化セット: 「どの class / id / 属性 / 状態の変化が、変化した要素から見てどの範囲の要素の
 * スタイルを変え得るか」の表（Blink の invalidation set に当たる）。
 *
 * 何が壊れていたか: 以前は何かが変わると文書全体のスタイルを作り直す（{@code markAllDirty}）か、
 * 状態の変わった要素の部分木だけを汚すかのどちらかで、{@code .a + .b} や {@code .a:has(.b)} のように
 * 兄弟や祖先に効く規則は、後者では取りこぼし、前者では過剰だった。
 * ここでは規則ごとに、変化する特徴（class・id・属性・状態）が複合セレクタのどこにあるかと、そこから主語（選ばれる要素）
 * までの結合子の連なりを辿り、影響が及ぶ範囲を {@link Relation} の集合として持つ。
 * 再スタイルの側（R5 のインクリメンタル・パイプライン）は、変化した要素について
 * {@link #forClass} などを引き、返った範囲の要素だけを汚せばよい。
 */
public final class InvalidationSets {

    /**
     * 変化した要素 X から見て、スタイルが変わり得る要素の範囲。
     */
    public enum Relation {
        /** X 自身。 */
        SELF,
        /** X の子孫（子を含む）。{@code .x .y}・{@code .x > .y} の {@code .x} が変わったとき。 */
        DESCENDANTS,
        /** X の後続の兄弟。{@code .x + .y}・{@code .x ~ .y} の {@code .x} が変わったとき。 */
        FOLLOWING_SIBLINGS,
        /** X の後続の兄弟とその子孫。{@code .x + .y .z} の {@code .x} が変わったとき。 */
        FOLLOWING_SIBLINGS_SUBTREE,
        /** X の祖先。{@code .p:has(.x)} の {@code .x} が変わったとき。 */
        ANCESTORS,
        /** X の先行する兄弟。{@code .p:has(+ .x)} の {@code .x} が変わったとき。 */
        PRECEDING_SIBLINGS,
        /** 範囲を絞れない（{@code :has()} の持ち主が主語でないなど）。文書全体を見る。 */
        WHOLE_TREE
    }

    private final Map<String, Set<Relation>> byClass = new HashMap<>();
    private final Map<String, Set<Relation>> byId = new HashMap<>();
    private final Map<String, Set<Relation>> byAttribute = new HashMap<>();
    private final Map<Integer, Set<Relation>> byState = new HashMap<>();
    /** 兄弟の増減・順序の変化（{@code :first-child}・{@code :nth-child()}・{@code :empty}・兄弟結合子）が及ぶ範囲。 */
    private final Set<Relation> structure = EnumSet.noneOf(Relation.class);
    /** 入力の値や検証の状態（{@code :valid}・{@code :placeholder-shown} など）が及ぶ範囲。 */
    private final Set<Relation> formState = EnumSet.noneOf(Relation.class);

    private InvalidationSets() {}

    /** 規則の並びから無効化セットを作る。 */
    public static InvalidationSets build(List<StyleRule> rules) {
        InvalidationSets sets = new InvalidationSets();
        for (StyleRule rule : rules) {
            sets.addSelector(rule.parsedSelector(), EnumSet.of(Relation.SELF), false);
        }
        return sets;
    }

    // ==========================================
    // 問い合わせ
    // ==========================================

    /** class {@code name} を付け外ししたときに影響が及ぶ範囲。どの規則も読まない class なら空。 */
    public Set<Relation> forClass(String name) {
        return union(byClass.get(name), byAttribute.get("class"));
    }

    public Set<Relation> forId(String id) {
        return union(byId.get(id), byAttribute.get("id"));
    }

    /** 属性 {@code name}（大文字小文字は区別しない）の値が変わったときに影響が及ぶ範囲。 */
    public Set<Relation> forAttribute(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        Set<Relation> result = union(byAttribute.get(lower));
        if (lower.equals("class") || lower.equals("id")) return result;
        if (isFormAttribute(lower)) result.addAll(formState);
        return result;
    }

    /** {@link ElementState} のビットのどれかが変わったときに影響が及ぶ範囲。 */
    public Set<Relation> forState(int stateBits) {
        Set<Relation> result = EnumSet.noneOf(Relation.class);
        for (Map.Entry<Integer, Set<Relation>> e : byState.entrySet()) {
            if ((e.getKey() & stateBits) != 0) result.addAll(e.getValue());
        }
        return result;
    }

    /** 子の挿入・削除・並べ替えで、兄弟や親のスタイルが変わり得る範囲。 */
    public Set<Relation> forStructureChange() {
        return EnumSet.copyOf(structure.isEmpty() ? EnumSet.noneOf(Relation.class) : structure);
    }

    /** どの規則も、変化を見張る必要のない（class・属性・状態・構造のどれにも依存しない）シートか。 */
    public boolean isEmpty() {
        return byClass.isEmpty() && byId.isEmpty() && byAttribute.isEmpty() && byState.isEmpty()
                && structure.isEmpty() && formState.isEmpty();
    }

    private static Set<Relation> union(Set<Relation>... sets) {
        Set<Relation> result = EnumSet.noneOf(Relation.class);
        for (Set<Relation> s : sets) {
            if (s != null) result.addAll(s);
        }
        return result;
    }

    private static boolean isFormAttribute(String name) {
        return switch (name) {
            case "value", "placeholder", "required", "pattern", "min", "max", "step", "minlength", "maxlength",
                 "readonly", "disabled", "type", "contenteditable", "checked", "multiple" -> true;
            default -> false;
        };
    }

    // ==========================================
    // 作る
    // ==========================================

    /**
     * セレクタの複合セレクタを主語から左へ辿り、各複合セレクタの特徴を、そこから主語までの関係つきで記録する。
     *
     * @param relation  この {@code selector} の主語が、記録の起点（変化する要素）から見てどの範囲か
     * @param viaHas    {@code :has()} の中のセレクタなら、変化は祖先・先行する兄弟に及ぶ
     */
    private void addSelector(Selector selector, Set<Relation> relation, boolean viaHas) {
        List<SelectorToken> compounds = selector.compounds();
        List<Character> combinators = selector.combinators();
        Set<Relation> current = relation;
        for (int i = compounds.size() - 1; i >= 0; i--) {
            addCompound(compounds.get(i), current, i == compounds.size() - 1, viaHas);
            if (i > 0) current = compose(combinators.get(i - 1), current);
        }
    }

    /**
     * 左の複合セレクタの要素 X が、右の複合セレクタの要素 M と結合子 {@code c} の関係にあるとき、
     * M から見た範囲 {@code fromM} を X から見た範囲にする。
     */
    private static Set<Relation> compose(char c, Set<Relation> fromM) {
        Set<Relation> out = EnumSet.noneOf(Relation.class);
        for (Relation r : fromM) {
            if (c == '+' || c == '~') {
                switch (r) {
                    case SELF -> out.add(Relation.FOLLOWING_SIBLINGS);
                    case DESCENDANTS -> out.add(Relation.FOLLOWING_SIBLINGS_SUBTREE);
                    case FOLLOWING_SIBLINGS, FOLLOWING_SIBLINGS_SUBTREE -> out.add(r);
                    // :has() の持ち主は X と同じ親の下にある（X の祖先の祖先）ので、祖先への範囲は変わらない
                    case ANCESTORS -> out.add(Relation.ANCESTORS);
                    default -> out.add(Relation.WHOLE_TREE);
                }
            } else {
                // 子孫・子の結合子: X は M の祖先なので、M から見た範囲が子孫・兄弟なら X の子孫に収まる。
                // :has() の持ち主が M の祖先（または先行する兄弟）なら、X の祖先（または先行する兄弟）でもある
                switch (r) {
                    case ANCESTORS -> out.add(Relation.ANCESTORS);
                    case PRECEDING_SIBLINGS -> out.add(Relation.PRECEDING_SIBLINGS);
                    case WHOLE_TREE -> out.add(Relation.WHOLE_TREE);
                    default -> out.add(Relation.DESCENDANTS);
                }
            }
        }
        return out;
    }

    private void addCompound(SelectorToken token, Set<Relation> relation, boolean isSubject, boolean viaHas) {
        Set<Relation> scope = viaHas ? hasScope(relation) : relation;
        if (token.id != null) add(byId, token.id, scope);
        for (String c : token.classes) add(byClass, c, scope);
        for (Selector.AttrMatcher m : token.attrMatchers) add(byAttribute, m.name(), scope);
        for (int bit = 0; bit < 31; bit++) {
            int mask = 1 << bit;
            if ((token.stateFlags & mask) != 0 || (token.absentStateFlags & mask) != 0) {
                byState.computeIfAbsent(mask, k -> EnumSet.noneOf(Relation.class)).addAll(scope);
            }
        }
        if (token.isEmpty || !token.nths.isEmpty() || token.isRoot) structure.addAll(structureScope(scope));
        for (DocumentPseudoClass pseudo : token.documentPseudoClasses) {
            addDocumentPseudoClass(pseudo, scope);
        }
        if (!token.langRanges.isEmpty()) add(byAttribute, "lang", subtreeOf(scope));
        if (!token.directions.isEmpty()) add(byAttribute, "dir", subtreeOf(scope));
        if (token.isLink) add(byAttribute, "href", scope);
        if (token.isRequired || token.isOptional) add(byAttribute, "required", scope);

        for (Selector not : token.notSelectors) addSelector(not, scope, viaHas);
        for (List<Selector> group : token.anyOfSelectors) {
            for (Selector s : group) addSelector(s, scope, viaHas);
        }
        for (Selector.Nth nth : token.nths) {
            for (Selector s : nth.of()) addSelector(s, structureScope(scope), viaHas);
        }
        for (List<RelativeSelector> group : token.hasSelectors) {
            for (RelativeSelector relative : group) {
                // :has() の中の変化は、持ち主（このセレクタの複合セレクタ）の側から見ると祖先・先行する兄弟に及ぶ。
                // 持ち主が主語でなければ、そこから先の関係を正確には追えないので文書全体にする
                Set<Relation> inner = isSubject ? ownerScope(relative.combinator()) : EnumSet.of(Relation.WHOLE_TREE);
                addSelector(relative.selector(), inner, true);
            }
        }
    }

    /** 兄弟の増減が及ぶ範囲: 主語が自身・子孫・兄弟のどれでも、その親の子の並びが変わるので兄弟側に広げる。 */
    private static Set<Relation> structureScope(Set<Relation> scope) {
        Set<Relation> out = EnumSet.noneOf(Relation.class);
        out.add(Relation.SELF);
        out.add(Relation.FOLLOWING_SIBLINGS_SUBTREE);
        out.add(Relation.PRECEDING_SIBLINGS);
        out.add(Relation.ANCESTORS);
        for (Relation r : scope) {
            if (r == Relation.WHOLE_TREE) out.add(Relation.WHOLE_TREE);
        }
        return out;
    }

    /** {@code lang} / {@code dir} は継承するので、変わった要素の部分木に及ぶ。 */
    private static Set<Relation> subtreeOf(Set<Relation> scope) {
        Set<Relation> out = EnumSet.copyOf(scope);
        out.add(Relation.SELF);
        out.add(Relation.DESCENDANTS);
        return out;
    }

    /** {@code :has()} の相対セレクタの主語が持ち主から見てどこか → 変化した主語から見た持ち主の範囲。 */
    private static Set<Relation> ownerScope(char combinator) {
        return (combinator == '+' || combinator == '~')
                ? EnumSet.of(Relation.PRECEDING_SIBLINGS, Relation.ANCESTORS)
                : EnumSet.of(Relation.ANCESTORS);
    }

    /** {@code :has()} の中にある特徴の変化範囲。{@code relation} は既に持ち主からの向きに直してある。 */
    private static Set<Relation> hasScope(Set<Relation> relation) {
        return relation;
    }

    private void addDocumentPseudoClass(DocumentPseudoClass pseudo, Set<Relation> scope) {
        switch (pseudo) {
            case TARGET, DEFINED -> add(byAttribute, "id", scope);
            default -> formState.addAll(scope);
        }
    }

    private static void add(Map<String, Set<Relation>> map, String key, Set<Relation> scope) {
        map.computeIfAbsent(key, k -> EnumSet.noneOf(Relation.class)).addAll(scope);
    }
}
