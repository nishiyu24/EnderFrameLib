package com.nishiyu.enderframelib.core.box;

import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.dom.node.ElementTraits;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 引用符（{@code quotes}・{@code open-quote} / {@code close-quote} / {@code no-open-quote} / {@code no-close-quote}。CSS Generated
 * Content 3 §2.1。Engine v3 M2b-1。旧経路は引用符を出さない）。
 *
 * <p>入れ子の深さは文書順に積み上がる非局所の状態。CSS のカウンタを使う文書（全体を作り直し {@link Counters} を文書順に回す）では
 * {@link Counters#quoteDepth} を使い、それ以外は「祖先の {@code q} の数」で近似する（UA の {@code q::before} / {@code q::after} のように
 * 要素ごとに開いて閉じる使い方では文書順の深さと同じ。UA の {@code q} の規則は置かない〔全ての文書で疑似要素のカスケードを解くことに
 * なり確保が数倍に増える〕ので、{@code q} を引用符で囲むのは文書の規則。前の兄弟の閉じない {@code open-quote} は数えない）。近似の深さは祖先の並びだけに
 * 依るので、疑似要素を持ち主の解き直しで作り直す規則のまま増分の結果は全体と同じになる（入れ子が変わるのは木の形の変化）。</p>
 */
final class Quotes {
    /** {@code quotes: auto} の引用符（英語の既定）。 */
    private static final String[] AUTO = {"“", "”", "‘", "’"};

    private Quotes() { }

    /**
     * 疑似要素の {@code content} を評価するときの深さの入れ物（評価で増減する）。{@code after} なら持ち主の {@code ::after}
     * （近似では、自分が {@code q} なら自分の {@code ::before} が開いた分を足す）。
     */
    static int[] depth(HtmlNode owner, boolean after, Counters counters) {
        if (counters != null) return new int[]{counters.quoteDepth, 1};
        int d = 0;
        for (HtmlNode a = owner.parent; a != null; a = a.parent) if (ElementTraits.isQuote(a.tag)) d++;
        if (after && ElementTraits.isQuote(owner.tag)) d++;
        return new int[]{d, 0};
    }

    /** 引用符の語の出力（語でなければ空）。{@code depth[0]} を増減し、カウンタの文書なら {@code Counters} にも書き戻す。 */
    static String keyword(String word, Style st, int[] depth) {
        String w = word.trim().toLowerCase(Locale.ROOT);
        String out;
        switch (w) {
            case "open-quote" -> {
                out = quote(st, depth[0], true);
                depth[0]++;
            }
            case "close-quote" -> {
                if (depth[0] == 0) return "";
                depth[0]--;
                out = quote(st, depth[0], false);
            }
            case "no-open-quote" -> {
                depth[0]++;
                out = "";
            }
            case "no-close-quote" -> {
                if (depth[0] > 0) depth[0]--;
                out = "";
            }
            default -> {
                return "";
            }
        }
        return out;
    }

    /** 深さ {@code d} の開き（{@code open}）・閉じの引用符（組が足りなければ最後の組）。 */
    private static String quote(Style st, int d, boolean open) {
        String[] q = pairs(st == null ? null : st.inherited.quotes);
        if (q.length == 0) return "";
        int k = Math.min(d, q.length / 2 - 1) * 2;
        return open ? q[k] : q[k + 1];
    }

    /** {@code quotes} の値を開き・閉じの並びにする（{@code none} は空、{@code auto} と解けない値は既定）。 */
    static String[] pairs(String v) {
        if (v == null) return AUTO;
        String t = v.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("auto")) return AUTO;
        if (t.equalsIgnoreCase("none")) return new String[0];
        List<String> out = new ArrayList<>();
        for (int i = 0; i < t.length(); ) {
            char c = t.charAt(i);
            if (c != '"' && c != '\'') {
                i++;
                continue;
            }
            StringBuilder sb = new StringBuilder();
            int j = i + 1;
            while (j < t.length() && t.charAt(j) != c) {
                char ch = t.charAt(j);
                if (ch == '\\' && j + 1 < t.length()) ch = t.charAt(++j);
                sb.append(ch);
                j++;
            }
            out.add(sb.toString());
            i = j + 1;
        }
        if (out.size() < 2) return AUTO;
        if (out.size() % 2 == 1) out.remove(out.size() - 1);
        return out.toArray(new String[0]);
    }
}
