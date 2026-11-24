package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.dom.node.ChangeLedger;
import com.nishiyu.enderframelib.core.dom.node.ChangeLedger.Kind;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * 凍結した変更台帳の読み手（K5b L1）。台帳の事実〔節・種別・詳細〕から、段が要る形を<b>導く</b>。入口別の集合
 * 〔旧 {@code pendingTextNodes}・{@code pendingAttrNodes}〕を持たず、台帳だけが変更の入口になる。
 */
public final class ChangeReads {
    private ChangeReads() { }

    /** 文字の節の変更を 1 つの再スタイルで測り直す最大数（超えたら全体の再レイアウトの方が安い）。 */
    public static final int MAX_TEXT_NODES = 256;

    private static final List<HtmlNode> NONE = List.of();

    /**
     * 凍結した世代で文字が変わった節（重複なし）。無ければ空。台帳があふれた・{@link #MAX_TEXT_NODES} を超えたときは null
     * 〔個々に頼らず全体を作り直す〕。
     */
    public static List<HtmlNode> texts(ChangeLedger ledger) {
        if (ledger.frozenOverflow()) return null;
        return collect(ledger, Kind.TEXT, (n, d) -> true, MAX_TEXT_NODES);
    }

    /**
     * 凍結した世代で、描画だけに効く属性が変わった節（重複なし）。{@code paintOnly} は（節, 属性名）で判定する
     * 〔呼び出し側の分類。見た目の属性は再スタイルの経路が見るので含めない〕。
     */
    public static List<HtmlNode> paintAttrs(ChangeLedger ledger, BiPredicate<HtmlNode, String> paintOnly) {
        if (ledger.frozenOverflow()) return NONE;
        return collect(ledger, Kind.ATTR, paintOnly, Integer.MAX_VALUE);
    }

    private static List<HtmlNode> collect(ChangeLedger ledger, Kind kind, BiPredicate<HtmlNode, String> accept, int max) {
        int size = ledger.frozenSize();
        if (size == 0) return NONE;
        List<HtmlNode> out = null;
        Set<HtmlNode> seen = null;
        for (int i = 0; i < size; i++) {
            if (!ledger.frozenIs(i, kind)) continue;
            HtmlNode node = ledger.frozenNode(i);
            if (node == null || !accept.test(node, ledger.frozenDetail(i))) continue;
            if (out == null) out = new ArrayList<>(4);
            if (out.size() < 8) {
                boolean dup = false;
                for (int j = 0, n = out.size(); j < n && !dup; j++) dup = out.get(j) == node;
                if (dup) continue;
            } else {
                if (seen == null) { seen = Collections.newSetFromMap(new IdentityHashMap<>()); seen.addAll(out); }
                if (!seen.add(node)) continue;
            }
            out.add(node);
            if (out.size() > max) return null;
        }
        return out == null ? NONE : out;
    }
}
