package com.nishiyu.enderframelib.core.css;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * M1a: {@code varied-list} 型の多様な文書の全要素で、型付き {@link Style} の群がどれだけ参照共有されるかを測る。
 *
 * <ul>
 *   <li>構造の番人: 継承群は子へ参照共有される（文書順の親子で、値が同じなら別インスタンスを作らない）。
 *       宣言の無い群は {@code DEFAULT} 1 個に畳まれる。</li>
 *   <li>保持ヒープ（旧 {@code ComputedStyle} との比較）は {@code -Dstyle.footprint.print=true} で出す観測値で、ここでは落とさない。
 *       計画書 §4 の KPI（{@code varied-list} 40.2 → ≤ 18 MB）は M1c（旧型の撤去後）の全体実測で判定する。
 *       M1a 時点の観測では、群をカスケード側で共有しない型付き群は旧表現（弱参照インターンで丸ごと共有）より大きい。
 *       これが M1b で「カスケードの一致規則の結果を {@code Style} ごと参照共有する」設計を要する根拠。</li>
 * </ul>
 */
class StyleFootprintTest {

    private static long usedAfterGc() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 4; i++) {
            System.gc();
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return rt.totalMemory() - rt.freeMemory();
    }

    private static String variedList(int n) {
        StringBuilder sb = new StringBuilder("<html><body style='font-family:sans-serif'><ul>");
        for (int i = 0; i < n; i++) {
            sb.append("<li class='c").append(i % 17).append("' style='color:#").append(String.format("%06x", (i * 2654435761L) & 0xFFFFFF))
                    .append(";font-size:").append(10 + i % 9).append("px;margin-left:").append(i % 13).append("px;")
                    .append(i % 3 == 0 ? "background-color:#eee;border-radius:4px;" : "")
                    .append(i % 5 == 0 ? "display:flex;gap:4px;" : "")
                    .append("'><span>item ").append(i).append("</span><b>x</b></li>");
        }
        return sb.append("</ul></body></html>").toString();
    }

    private record Pair(ComputedStyle old, Style typed, Style parent) {}

    private static void collect(HtmlNode n, InteractionState st, List<Pair> out, Style parent, boolean typed) {
        ComputedStyle cs = st.computedStyleOf(n);
        Style s = parent;
        if (cs != null) {
            s = typed ? Style.of(cs, parent) : null;
            out.add(new Pair(cs, s, parent));
        }
        for (HtmlNode c : n.children) collect(c, st, out, s, typed);
    }

    /** 親と値が同じなのに別の実体の継承群（共有の取りこぼし。組み立てが作った算出スタイルそのもので数える）。 */
    private static int unsharedEqualInherited(List<Pair> pairs) {
        int n = 0;
        for (Pair p : pairs) {
            if (p.parent() == null) continue;
            var own = p.old().typed().inherited;
            if (own != p.parent().inherited && own.equals(p.parent().inherited)) n++;
        }
        return n;
    }

    private static int identities(List<Pair> pairs, java.util.function.Function<Style, Object> group) {
        Set<Object> s = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Pair p : pairs) s.add(group.apply(p.typed));
        return s.size();
    }

    private static int distinctByValue(List<Pair> pairs, java.util.function.Function<Style, Object> group) {
        Set<Object> s = new HashSet<>();
        for (Pair p : pairs) s.add(group.apply(p.typed));
        return s.size();
    }

    @Test
    void groupsAreSharedByReferenceAcrossTheDocument() {
        int n = 2000;
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(variedList(n), "", 800, 600);
        InteractionState state = new InteractionState();
        compiler.layoutDocument(doc, 800, 600, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);

        List<Pair> pairs = new ArrayList<>();
        collect(doc.root, state, pairs, null, true);
        int elements = pairs.size();
        assertTrue(elements > 3 * n, "要素数: " + elements);

        int inhIds = identities(pairs, s -> s.inherited), inhVals = distinctByValue(pairs, s -> s.inherited);
        // 継承群: 値が同じなら親子で同一インスタンス。li ごとに色とフォントが違い、b だけ太字で別（計 2n 個前後）。span と文字は親を共有する
        assertTrue(inhIds <= 2 * n + 64, "継承群のインスタンス数 " + inhIds + " / 要素 " + elements);
        assertEquals(0, unsharedEqualInherited(pairs), "継承群が親子で共有されていない（値が同じなのに別の実体）");
        assertTrue(inhVals <= inhIds);
        // 宣言の無い群は DEFAULT 1 個に畳まれる（table-layout を宣言する要素は無い）
        assertEquals(1, identities(pairs, s -> s.table));

        if (true) { // 観測値（落とさない）。測りは 2 秒ほど
            System.out.println("[style footprint] elements=" + elements
                    + " inherited ids/vals=" + inhIds + "/" + inhVals
                    + " box=" + identities(pairs, s -> s.box) + "/" + distinctByValue(pairs, s -> s.box)
                    + " visual=" + identities(pairs, s -> s.visual) + "/" + distinctByValue(pairs, s -> s.visual)
                    + " flexGrid=" + identities(pairs, s -> s.flexGrid) + "/" + distinctByValue(pairs, s -> s.flexGrid)
                    + " rare=" + identities(pairs, s -> s.rare) + "/" + distinctByValue(pairs, s -> s.rare));
            long oldBytes = measure(doc, state, false), newBytes = measure(doc, state, true);
            System.out.println("[style footprint] old(ComputedStyle)=" + oldBytes / 1024 + " KiB new(Style)=" + newBytes / 1024
                    + " KiB perElement old=" + oldBytes / elements + " B new=" + newBytes / elements + " B");
        }
    }

    /** 文書の全要素の算出スタイルを作り直し（旧は ComputedStyle の写し、新は Style）、保持ヒープの増分を返す。 */
    private static long measure(Document doc, InteractionState state, boolean typed) {
        List<Pair> src = new ArrayList<>();
        collect(doc.root, state, src, null, false);
        long before = usedAfterGc();
        List<Object> keep = new ArrayList<>(src.size());
        Style prev = null;
        for (Pair p : src) {
            if (typed) {
                prev = Style.of(p.old, prev);
                keep.add(prev);
            } else {
                keep.add(new ComputedStyle(p.old.asMap(), p.old.fontSizePx(), p.old.rootFontSizePx()));
            }
        }
        long after = usedAfterGc();
        assertEquals(src.size(), keep.size());
        return Math.max(0, after - before);
    }
}
