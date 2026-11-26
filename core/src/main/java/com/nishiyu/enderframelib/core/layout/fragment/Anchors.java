package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 包含ブロックの中のアンカー（Engine v3 M2b-5。CSS Anchor Positioning 1 §2）。流れの外の子を置く包含ブロックが、自分の中身（流れの
 * 塊・行・同じ回に先に置いた流れの外の断片）から {@code anchor-name} の要素と、ポップオーバーの呼び出し元（{@code popovertarget}）を
 * 探し、矩形を包含ブロックの border-box 基準で返す。
 *
 * <p>断片は不変で絶対座標を持たないので、包含ブロックの外にあるアンカーは見つからない（仕様の「受け入れられるアンカー」は包含ブロック
 * の中の要素が主。文書の根が包含ブロックなら文書全体）。名前が重なれば文書順で最初のもの（旧 {@code LayoutReads.findAnchor} と同じ）。
 * 表は最初の問い合わせで 1 回だけ作り、流れの外の断片を置くたびに足す（使わない包含ブロックでは作らない）。</p>
 *
 * <p>アンカーごとに、包含ブロックから降りる途中で通ったスクロールコンテナ（包含ブロック自身は含まない）を覚える（{@link Hit#scrollers}。
 * G8）。矩形は全てのスクロールコンテナが初期位置にあるときの位置で、覚えたスクロール量でずらすのは {@link AnchorPlacement}。</p>
 */
final class Anchors {
    private final ChunkedFlow flow;
    private final LineFragment[] lines;
    private final float insetL, insetT;
    private static final BoxNode[] NO_SCROLLERS = new BoxNode[0];

    /** アンカーの矩形 {x, y, 幅, 高さ} と、その上の（包含ブロックより下の）スクロールコンテナ（外から順）。 */
    record Hit(float[] rect, BoxNode[] scrollers) { }

    private Map<String, Hit> named, invokers;
    /** インライン要素の行ごとの箱を囲む矩形（同じ要素の箱を集める。表を作る間だけ）。 */
    private Map<BoxNode, float[]> spans;
    /** 表を作る前に置いた流れの外の断片（作るときに文書順の後ろとして足す）。 */
    private List<PlacedFragment> early;

    private Anchors(ChunkedFlow flow, LineFragment[] lines, float insetL, float insetT) {
        this.flow = flow;
        this.lines = lines;
        this.insetL = insetL;
        this.insetT = insetT;
    }

    /** 中身が流れ {@code flow} と行 {@code lines}（内容ボックスの左上が border-box の {@code (insetL, insetT)}）の包含ブロック。 */
    static Anchors of(ChunkedFlow flow, LineFragment[] lines, float insetL, float insetT) {
        return new Anchors(flow, lines, insetL, insetT);
    }

    /** 名前 {@code name} のアンカー（無ければ null）。 */
    Hit named(String name) {
        build();
        return named.get(name);
    }

    /** id が {@code id} のポップオーバーを呼び出す要素（{@code popovertarget}）の矩形（暗黙のアンカー。無ければ null）。 */
    Hit invoker(String id) {
        build();
        return invokers.get(id);
    }

    /** 同じ回に置いた流れの外の断片を、後ろの断片のアンカーにする（文書順で先のもの）。 */
    void placed(PlacedFragment p) {
        if (named != null) {
            collect(p.frag(), p.x(), p.y(), NO_SCROLLERS);
        } else {
            if (early == null) early = new java.util.ArrayList<>();
            early.add(p);
        }
    }

    private void build() {
        if (named != null) return;
        named = new HashMap<>();
        invokers = new HashMap<>();
        spans = new java.util.IdentityHashMap<>();
        if (lines != null) lines(lines, insetL, insetT, NO_SCROLLERS);
        if (flow != null) flow.forEach((c, x, y) -> collect(c, insetL + x, insetT + y, NO_SCROLLERS));
        if (early != null) for (PlacedFragment p : early) collect(p.frag(), p.x(), p.y(), NO_SCROLLERS);
        early = null;
    }

    private void collect(LayoutFragment f, float x, float y, BoxNode[] scrollers) {
        BoxNode b = f.box;
        if (b != null && b.pseudo == BoxNode.Pseudo.NONE) record(b, new float[]{x, y, f.width, f.height}, scrollers);
        // スクロールコンテナの中身（流れの子と、包含ブロックがそのコンテナの流れの外の子）はコンテナと一緒にスクロールする
        BoxNode[] inner = b != null && b.style.facts.scrollContainer ? with(scrollers, b) : scrollers;
        float cx = x + f.insetLeft, cy = y + f.insetTop;
        if (f.lines != null) lines(f.lines, cx, cy, inner);
        if (f.flow != null) f.flow.forEach((c, x1, y1) -> collect(c, cx + x1, cy + y1, inner));
        if (f.oof != null) for (PlacedFragment p : f.oof) collect(p.frag(), x + p.x(), y + p.y(), inner);
    }

    private static BoxNode[] with(BoxNode[] chain, BoxNode b) {
        BoxNode[] out = java.util.Arrays.copyOf(chain, chain.length + 1);
        out[chain.length] = b;
        return out;
    }

    private void lines(LineFragment[] ls, float ox, float oy, BoxNode[] scrollers) {
        for (LineFragment l : ls) {
            float lx = ox + l.x(), ly = oy + l.y();
            for (PlacedFragment a : l.atoms()) collect(a.frag(), lx + a.x(), ly + a.y(), scrollers);
            for (LineFragment.Span sp : l.spans()) { // インライン要素は行ごとの箱を囲む矩形
                if (sp.box().pseudo != BoxNode.Pseudo.NONE) continue;
                float[] r = {lx + sp.x(), ly + sp.y(), sp.width(), sp.height()};
                float[] prev = spans.get(sp.box());
                if (prev != null) {
                    float x0 = Math.min(prev[0], r[0]), y0 = Math.min(prev[1], r[1]);
                    prev[2] = Math.max(prev[0] + prev[2], r[0] + r[2]) - x0;
                    prev[3] = Math.max(prev[1] + prev[3], r[1] + r[3]) - y0;
                    prev[0] = x0;
                    prev[1] = y0;
                } else {
                    spans.put(sp.box(), r);
                    record(sp.box(), r, scrollers);
                }
            }
        }
    }

    private void record(BoxNode b, float[] rect, BoxNode[] scrollers) {
        List<String> ns = names(b);
        boolean invokes = b.popover != null && b.popover.invokes() != null;
        if (ns.isEmpty() && !invokes) return;
        Hit h = new Hit(rect, scrollers);
        for (String n : ns) named.putIfAbsent(n, h);
        if (invokes) invokers.putIfAbsent(b.popover.invokes(), h);
    }

    private static List<String> names(BoxNode b) {
        String v = b.style.rare.anchorName;
        if (v == null || v.isBlank() || "none".equalsIgnoreCase(v.trim())) return List.of();
        return List.of(v.trim().split("[\\s,]+"));
    }
}
