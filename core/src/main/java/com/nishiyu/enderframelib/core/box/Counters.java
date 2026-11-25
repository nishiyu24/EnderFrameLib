package com.nishiyu.enderframelib.core.box;

import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.dom.node.ElementTraits;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CSS のカウンタの実体の積み上げ（Engine v3 M2b-1。旧 {@code BoxTreeBuilder.Counters} のアルゴリズムの移植）。
 * {@link BoxTreeCompiler} が文書順に辿りながら、要素ごとに {@code counter-reset} → {@code counter-increment} → {@code counter-set}
 * を当てる（CSS Lists 3 の順）。同名の実体は後から作られたもの（内側）が見え、{@code counters()} は外側から全部を読む。
 * {@code counter-reset} の有効範囲は要素自身・子孫・後続の兄弟とその子孫なので、親の子を数え終えたら {@link #release} で閉じる。
 *
 * <p>1 回のコンパイルの間だけ使う作業域（コンパイラの実例に閉じる）。カウンタを使う文書は全体を作り直すので、
 * 途中の部分木を飛ばして値が狂うことは無い。</p>
 */
final class Counters {
    /** リスト項目が暗黙に数えるカウンタ（CSS Lists 3）。 */
    static final String LIST_ITEM = "list-item";

    private final List<String> names = new ArrayList<>();
    private final List<Integer> values = new ArrayList<>();
    /** 逆順に数える {@code list-item}（{@code <ol reversed>}）の実体の位置。 */
    private final Set<Integer> reversed = new HashSet<>();

    /** 引用符の入れ子の深さ（文書順に積み上がる。カウンタの範囲とは別で、子を抜けても戻さない）。 */
    int quoteDepth;

    void clear() {
        quoteDepth = 0;
        names.clear();
        values.clear();
        reversed.clear();
    }

    int mark() {
        return names.size();
    }

    void release(int mark) {
        while (names.size() > mark) {
            names.removeLast();
            values.removeLast();
            reversed.remove(names.size());
        }
    }

    int value(String name) {
        int i = innermost(name);
        return i < 0 ? 0 : values.get(i);
    }

    List<Integer> values(String name) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) if (names.get(i).equals(name)) out.add(values.get(i));
        return out;
    }

    /** 要素（または疑似要素。{@code node} は持ち主）の宣言を当てる。{@code listItem} は {@code display: list-item} か。 */
    void apply(HtmlNode node, Style st, boolean listItem) {
        if (node.nodeType == NodeType.TEXT) return;
        String rs = st.rare.counterReset, inc = st.rare.counterIncrement, set = st.rare.counterSet;
        if (none(rs) && none(inc) && none(set)) {
            if (!listItem) return;
            int i = innermost(LIST_ITEM);
            increment(LIST_ITEM, i >= 0 && reversed.contains(i) ? -1 : 1);
            Integer v = node.attrs.containsKey("value") ? parseInt(node.attrs.get("value")) : null;
            if (v != null) set(LIST_ITEM, v);
            return;
        }
        Map<String, Integer> resets = parseList(rs, 0);
        boolean ol = ElementTraits.isOrderedList(node.tag);
        if (resets.containsKey(LIST_ITEM) && ol && node.attrs.containsKey("start")) {
            Integer start = parseInt(node.attrs.get("start"));
            if (start != null) resets.put(LIST_ITEM, start - 1);
        }
        boolean rev = resets.containsKey(LIST_ITEM) && ol && node.attrs.containsKey("reversed");
        if (rev) {
            Integer start = node.attrs.containsKey("start") ? parseInt(node.attrs.get("start")) : null;
            resets.put(LIST_ITEM, (start != null ? start : listItemChildren(node)) + 1);
        }
        for (var r : resets.entrySet()) {
            reset(r.getKey(), r.getValue());
            if (rev && r.getKey().equals(LIST_ITEM)) reversed.add(names.size() - 1);
        }
        Map<String, Integer> increments = parseList(inc, 1);
        if (listItem && !increments.containsKey(LIST_ITEM)) {
            int i = innermost(LIST_ITEM);
            increments.put(LIST_ITEM, i >= 0 && reversed.contains(i) ? -1 : 1);
        }
        increments.forEach(this::increment);
        Map<String, Integer> sets = parseList(set, 0);
        if (listItem && !sets.containsKey(LIST_ITEM) && node.attrs.containsKey("value")) {
            Integer v = parseInt(node.attrs.get("value"));
            if (v != null) sets.put(LIST_ITEM, v);
        }
        sets.forEach(this::set);
    }

    private static int listItemChildren(HtmlNode list) {
        int count = 0;
        for (HtmlNode c : list.children) if (c.nodeType == NodeType.ELEMENT && "li".equalsIgnoreCase(c.tag)) count++;
        return count;
    }

    private void reset(String name, int value) {
        names.add(name);
        values.add(value);
    }

    private void increment(String name, int by) {
        int i = innermost(name);
        if (i < 0) reset(name, by);
        else values.set(i, values.get(i) + by);
    }

    private void set(String name, int value) {
        int i = innermost(name);
        if (i < 0) reset(name, value);
        else values.set(i, value);
    }

    private int innermost(String name) {
        for (int i = names.size() - 1; i >= 0; i--) if (names.get(i).equals(name)) return i;
        return -1;
    }

    private static boolean none(String v) {
        if (v == null) return true;
        String t = v.trim();
        return t.isEmpty() || t.equalsIgnoreCase("none");
    }

    /** {@code "a 2 b"} → {@code {a=2, b=既定値}}。 */
    private static Map<String, Integer> parseList(String value, int dflt) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (none(value)) return out;
        String pending = null;
        for (String token : value.trim().split("\\s+")) {
            Integer n = parseInt(token);
            if (n != null && pending != null) {
                out.put(pending, n);
                pending = null;
            } else if (n == null) {
                if (pending != null) out.put(pending, dflt);
                pending = token;
            }
        }
        if (pending != null) out.put(pending, dflt);
        return out;
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }
}
