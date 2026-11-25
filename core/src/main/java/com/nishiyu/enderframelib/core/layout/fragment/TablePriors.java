package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 表の整形（{@link TableFlow}）が前回の表の断片から引く、セルとキャプションの候補（Engine v3 M2b-4）。照合は箱の同一性、無ければ同じ節
 * （匿名の箱は持ち主の節を共有するので箱の同一性だけ）。セルは伸ばした断片と、中身の高さで整形した元の断片（{@link FlowChunk#slots}）を
 * 候補の集まり（{@link LayoutCache.Slots}）にする。1 回の整形の間だけの作業域。
 */
final class TablePriors {
    private final Map<BoxNode, LayoutCache.Slots> byBox = new IdentityHashMap<>();
    private final Map<HtmlNode, LayoutCache.Slots> byNode = new IdentityHashMap<>();
    private final Map<BoxNode, LayoutFragment> singles = new IdentityHashMap<>();
    private final Map<HtmlNode, LayoutFragment> singlesByNode = new IdentityHashMap<>();

    static TablePriors of(LayoutFragment prior) {
        TablePriors p = new TablePriors(); // 空の表は確保が小さいので共有しない（プロセス共有の可変の値を持たない）
        if (prior == null || prior.flow == null) return p;
        for (FlowChunk c : prior.flow.chunks) p.visit(c, 0);
        return p;
    }

    private void visit(FlowChunk c, int depth) {
        for (int i = 0; i < c.frags.length; i++) {
            LayoutFragment f = c.frags[i];
            if (f.box == null) continue;
            switch (f.box.kind) {
                case TABLE_ROW_GROUP, TABLE_ROW -> {
                    if (depth < 2 && f.flow != null) for (FlowChunk k : f.flow.chunks) visit(k, depth + 1);
                }
                case TABLE_CELL -> {
                    LayoutFragment[] others = c.slots != null ? c.slots[i] : null;
                    byBox.put(f.box, new LayoutCache.Slots(f, others));
                    if (f.box.pseudo == BoxNode.Pseudo.NONE) byNode.put(f.box.node, new LayoutCache.Slots(f, others));
                }
                default -> {
                    singles.put(f.box, f);
                    if (f.box.pseudo == BoxNode.Pseudo.NONE) singlesByNode.put(f.box.node, f);
                }
            }
        }
    }

    LayoutCache.Slots slots(BoxNode cell) {
        LayoutCache.Slots sl = byBox.get(cell);
        if (sl == null && cell.pseudo == BoxNode.Pseudo.NONE) sl = byNode.get(cell.node);
        return sl != null ? sl : new LayoutCache.Slots(null, null);
    }

    LayoutFragment single(BoxNode box) {
        LayoutFragment f = singles.get(box);
        return f != null ? f : box.pseudo == BoxNode.Pseudo.NONE ? singlesByNode.get(box.node) : null;
    }
}
