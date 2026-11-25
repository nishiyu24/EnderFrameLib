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
 */
final class Anchors {
    private final ChunkedFlow flow;
    private final LineFragment[] lines;
    private final float insetL, insetT;
    private Map<String, float[]> named, invokers;
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

    /** 名前 {@code name} のアンカーの {x, y, 幅, 高さ}（無ければ null）。 */
    float[] named(String name) {
        build();
        return named.get(name);
    }

    /** id が {@code id} のポップオーバーを呼び出す要素（{@code popovertarget}）の矩形（暗黙のアンカー。無ければ null）。 */
    float[] invoker(String id) {
        build();
        return invokers.get(id);
    }

    /** 同じ回に置いた流れの外の断片を、後ろの断片のアンカーにする（文書順で先のもの）。 */
    void placed(PlacedFragment p) {
        if (named != null) {
            collect(p.frag(), p.x(), p.y());
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
        if (lines != null) lines(lines, insetL, insetT);
        if (flow != null) flow.forEach((c, x, y) -> collect(c, insetL + x, insetT + y));
        if (early != null) for (PlacedFragment p : early) collect(p.frag(), p.x(), p.y());
        early = null;
    }

    private void collect(LayoutFragment f, float x, float y) {
        BoxNode b = f.box;
        if (b != null && b.pseudo == BoxNode.Pseudo.NONE) record(b, new float[]{x, y, f.width, f.height});
        float cx = x + f.insetLeft, cy = y + f.insetTop;
        if (f.lines != null) lines(f.lines, cx, cy);
        if (f.flow != null) f.flow.forEach((c, x1, y1) -> collect(c, cx + x1, cy + y1));
        if (f.oof != null) for (PlacedFragment p : f.oof) collect(p.frag(), x + p.x(), y + p.y());
    }

    private void lines(LineFragment[] ls, float ox, float oy) {
        for (LineFragment l : ls) {
            float lx = ox + l.x(), ly = oy + l.y();
            for (PlacedFragment a : l.atoms()) collect(a.frag(), lx + a.x(), ly + a.y());
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
                    record(sp.box(), r);
                }
            }
        }
    }

    private void record(BoxNode b, float[] rect) {
        for (String n : names(b)) named.putIfAbsent(n, rect);
        if (b.popover != null && b.popover.invokes() != null) invokers.putIfAbsent(b.popover.invokes(), rect);
    }

    private static List<String> names(BoxNode b) {
        String v = b.style.rare.anchorName;
        if (v == null || v.isBlank() || "none".equalsIgnoreCase(v.trim())) return List.of();
        return List.of(v.trim().split("[\\s,]+"));
    }
}
