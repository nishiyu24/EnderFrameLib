package com.nishiyu.enderframelib.core.box;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CounterStyles;
import com.nishiyu.enderframelib.core.css.GeneratedContentStyles;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.dom.node.ElementTraits;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * リスト項目の {@code ::marker} の箱（Engine v3 M2b-1。旧 {@code BoxTreeBuilder.createMarker} の移植）。{@link BoxNode#pseudo} が
 * {@link BoxNode.Pseudo#MARKER} の {@code inline-block}（UA の上書き: 空白を畳まない・{@code text-transform} なし）で、中身は文字の節 1 つ。
 * {@code list-style-position: inside} なら {@link BoxNode#flow} の先頭に入り行の中の原子になる。{@code outside} なら流れに入らず、
 * レイアウトがリスト項目の左外へ最初の行のベースラインに揃えて置く。
 *
 * <p><b>番号は兄弟の順に依る非局所の値</b>なので、{@link BoxNode#ordinal} に組んだ番号を持ち、親の子の並びを組み直したときに
 * {@link #renumber} が文書順に数え直して、違う番号の項目だけマーカーを作り直す（暗黙の {@code list-item} カウンタ。{@code <ol start>}・
 * {@code reversed}・{@code <li value>}）。番号に依らないマーカー（{@code disc} など・{@code content} の指定）は {@link #NO_ORDINAL}。
 * CSS のカウンタを使う文書は全体を作り直し、番号は {@code Counters} が数える。</p>
 */
final class MarkerBoxes {
    /** 番号に依らないマーカー（数え直さない）。 */
    static final int NO_ORDINAL = Integer.MIN_VALUE;

    private MarkerBoxes() { }

    /** {@code display} が {@code list-item} を含むか（マーカーを持つ箱）。 */
    static boolean listItem(Style st) {
        String d = st.box.display;
        return d != null && d.contains("list-item");
    }

    /** マーカーを流れの中（{@code list-style-position: inside}）に置くか。 */
    static boolean inside(Style owner) {
        return GeneratedContentStyles.markerInside(owner);
    }

    /**
     * リスト項目 {@code owner} のマーカーの箱（作らないなら null）。{@code ordinal} は番号（カウンタを使う文書では {@code Counters} の値、
     * それ以外は仮の値で、親が {@link #renumber} で直す）。{@code prev} は前回のマーカー（同じなら前回の参照を返す）。
     */
    static BoxNode build(HtmlNode owner, Style ownerStyle, BoxNode prev, StyleSource styles, Counters counters, CounterStyles cs,
                         int ordinal) {
        ComputedStyle declared = styles.markerDeclared(owner, ownerStyle);
        String content = declared != null ? declared.typed().rare.content : null;
        String c = content == null ? "" : content.trim();
        if (c.equalsIgnoreCase("none")) return null;
        boolean byType = c.isEmpty() || c.equalsIgnoreCase("normal");
        String type = Style.or(ownerStyle.inherited.listStyleType, "disc");
        String raw = byType ? cs.marker(type, ordinal)
                : PseudoBoxes.evaluate(content, owner, ownerStyle, Quotes.depth(owner, false, counters), counters, cs);
        if (raw == null || raw.isEmpty()) return null;
        // 番号に依るか（1 と 2 で表現が変わる型）。カウンタを使う文書は全体を作り直すので数え直さない
        int ord = byType && counters == null && !Objects.equals(cs.marker(type, 1), cs.marker(type, 2)) ? ordinal : NO_ORDINAL;
        Style st = new ComputedStyle(ownerStyle).derivedMarker(declared).typed();
        if (prev != null) st = st.shareEqualGroups(prev.style);
        BoxKind kind = BoxKind.of(owner, st);
        if (kind == BoxKind.NONE || kind == BoxKind.TEXT) return null;
        return make(owner, st, kind, raw, ord, prev, styles);
    }

    /**
     * 親の子の並び {@code kids} を文書順に数え直し、番号が違うマーカーを持つ項目だけを作り直した並びを返す（変わらなければ同じ参照）。
     * 暗黙の {@code list-item} カウンタ（旧 {@code Counters} と同じ規則）: {@code <ol start>} が最初の値、{@code reversed} は減っていき
     * 既定の最初の値は {@code li} の子の数、{@code <li value>} はその項目の値を置き換える。{@code display: list-item} でない子・箱を作らない
     * 子は数えない。{@code rebuilt[0]} に作り直した箱の数を足す。
     */
    static BoxChildren renumber(HtmlNode parent, BoxChildren kids, StyleSource styles, CounterStyles cs, int[] rebuilt) {
        boolean ol = ElementTraits.isOrderedList(parent.tag);
        boolean reversed = ol && parent.attrs.containsKey("reversed");
        Integer start = ol && parent.attrs.containsKey("start") ? parseInt(parent.attrs.get("start")) : null;
        int v;
        if (start != null) {
            v = start;
        } else if (reversed) {
            v = 0;
            for (HtmlNode c : parent.children) if (c.nodeType == NodeType.ELEMENT && ElementTraits.isListItem(c.tag)) v++;
        } else {
            v = 1;
        }
        int step = reversed ? -1 : 1;
        List<BoxChunk> out = null;
        for (int k = 0, kc = kids.chunkCount(); k < kc; k++) {
            BoxChunk c = kids.chunk(k);
            BoxNode[] items = null;
            for (int i = 0, n = c.size(); i < n; i++) {
                BoxNode b = c.get(i);
                if (b.kind == BoxKind.NONE || b.kind == BoxKind.TEXT || !listItem(b.style)) continue;
                Integer own = b.node.attrs.containsKey("value") ? parseInt(b.node.attrs.get("value")) : null;
                if (own != null) v = own;
                BoxNode m = b.marker;
                if (m != null && m.ordinal != NO_ORDINAL && m.ordinal != v) {
                    String raw = cs.marker(Style.or(b.style.inherited.listStyleType, "disc"), v);
                    BoxNode nm = make(m.node, m.style, m.kind, raw == null ? "" : raw, v, m, styles);
                    if (items == null) {
                        items = new BoxNode[n];
                        for (int j = 0; j < n; j++) items[j] = c.get(j);
                    }
                    items[i] = b.withMarker(nm);
                    rebuilt[0] += 3;
                }
                v += step;
            }
            if (items != null && out == null) {
                out = new ArrayList<>(kc);
                for (int j = 0; j < k; j++) out.add(kids.chunk(j));
            }
            if (out != null) out.add(items != null ? new BoxChunk(items) : c);
        }
        return out == null ? kids : BoxChildren.ofChunks(out);
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static BoxNode make(HtmlNode owner, Style st, BoxKind kind, String raw, int ord, BoxNode prev, StyleSource styles) {
        BoxNode prevText = prev != null && prev.children.size() == 1 ? prev.children.get(0) : null;
        Style ts = styles.text(st);
        if (prevText != null) ts = ts.shareEqualGroups(prevText.style);
        String text = TextContent.of(raw, ts);
        if (prevText != null && prevText.text != null && prevText.text.equals(text)) text = prevText.text;
        if (prev != null && prev.style == st && prev.kind == kind && prev.ordinal == ord && prevText != null && prevText.style == ts
                && prevText.text == text) {
            return prev;
        }
        BoxNode tb = new BoxNode(owner, ts, BoxKind.TEXT, text, BoxChunk.EMPTY, BoxNode.Pseudo.MARKER, null, null, null, null, NO_ORDINAL,
                prevText != null ? prevText.layout : null);
        return new BoxNode(owner, st, kind, null, new BoxChunk(new BoxNode[]{tb}), BoxNode.Pseudo.MARKER, null, null, null, null, ord,
                prev != null ? prev.layout : null);
    }
}
