package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;

import java.util.List;

/**
 * 子パッチ（{@link ChildSlicePatch}）の③: 足す子の項目を入れる位置（ブロックの層・インラインの層それぞれの項目の位置と描画順）を決める。
 * §6-119 で手がかりの探し方（祖先へたどる・描画順で選ぶ）が増えたので {@code ChildSlicePatch} から切り出した。
 *
 * <p>描画は「ブロックの層 → float の層 → インラインの層」の順（{@link PaintOrder}）。手がかりは同じ層の中で木の順に前後する、今描かれている箱。
 * 結果は {@link #insertBlockPos} ほか 4 つの欄に置く。1 つのビルダーに 1 つ（呼び出しごとに確保しない）。</p>
 */
final class ChildPatchInsertion {
    private final DisplayListBuilder b;

    /** 結果: 両層の挿入位置（項目の位置と描画順）。 */
    int insertBlockPos, insertBlockOrder, insertInlinePos, insertInlineOrder;
    /** 1 回の {@link #find} の間だけ使う入力。 */
    private LayoutBox insertParent;
    private boolean insertsInline, insertsBlock;

    ChildPatchInsertion(DisplayListBuilder builder) {
        this.b = builder;
    }

    /**
     * 両層の挿入位置（項目の位置と描画順）を決める。範囲外・手がかりが無いなら false（呼び出し側が全体を作り直す）。
     * {@code rem*} は①で消した区間（無ければ開始 -1）、{@code insertsBlock} / {@code insertsInline} は足す項目がその層にあるか。
     */
    boolean find(LayoutBox parentBox, List<LayoutBox> added, LayoutBox afterBox, int remBlockStart, int remBlockEnd, int remBlockOrderStart, int remBlockCount,
                 int remInlineStart, int remInlineOrderStart, boolean insertsBlock, boolean insertsInline) {
        this.insertParent = parentBox;
        this.insertsBlock = insertsBlock;
        this.insertsInline = insertsInline;
        insertBlockPos = insertBlockOrder = insertInlinePos = insertInlineOrder = -1;
        int removedBlockItemsLen = remBlockStart >= 0 ? (remBlockEnd - remBlockStart) : 0;

        if (remBlockStart >= 0) {
            insertBlockPos = remBlockStart;
            insertBlockOrder = remBlockOrderStart;
        }

        if (remInlineStart >= 0) {
            insertInlinePos = remInlineStart - removedBlockItemsLen;
            insertInlineOrder = remInlineOrderStart - remBlockCount;
        }

        if (insertBlockPos < 0 || insertInlinePos < 0) {
            int afterIdx = (parentBox.children != null && afterBox != null) ? parentBox.children.indexOf(afterBox) : -1;
            if (afterBox != null && afterIdx >= 0) {
                if (insertBlockPos < 0) blockInsertAfter(parentBox, added, afterIdx);
                if (insertInlinePos < 0) inlineInsertAfter(parentBox, added, afterIdx);
            } else {
                // 先頭挿入または afterBox 不明
                if (insertBlockPos < 0) blockInsertAtHead(parentBox, added);
                if (insertInlinePos < 0) inlineInsertAtHead(parentBox, added);
            }
        }

        // 境界値チェックと安全なフォールバック
        List<DisplayItem> list = b.currentDisplayList;
        int boxes = b.renderedBoxes.size();
        return !(insertBlockPos < 0 || insertBlockPos > list.size()
                || insertBlockOrder < 0 || insertBlockOrder > boxes
                || insertInlinePos < 0 || insertInlinePos > list.size()
                || insertInlineOrder < 0 || insertInlineOrder > boxes);
    }

    /**
     * 今の表示リストで描かれている箱か。描かれなくなった箱（寸法 0 になった表など）は前回の dlEnd / dlOrder を持ち続けるので、
     * 番号が今の描画順の一覧でその箱を指すことまで確かめる（§6-113。layouts の種 137: tbody を消して 0×0 になった表の古い番号が挿入位置になった）。
     */
    private boolean drawn(LayoutBox x) {
        return x.dlEnd >= 0 && x.dlOrder >= 0 && x.dlOrder < b.renderedBoxes.size() && b.renderedBoxes.get(x.dlOrder) == x;
    }

    /** ブロック層: afterBox 以前の描画済みボックスを逆順探索し、無ければ親・後続の最初のブロックへ。 */
    private void blockInsertAfter(LayoutBox parentBox, List<LayoutBox> added, int afterIdx) {
        LayoutBox lastBlock = null;
        for (int i = afterIdx; i >= 0; i--) {
            LayoutBox c = parentBox.children.get(i);
            List<LayoutBox> blockPhase = b.blockPhaseOf(c);
            for (int k = blockPhase.size() - 1; k >= 0; k--) {
                LayoutBox x = blockPhase.get(k);
                if (drawn(x)) {
                    lastBlock = x;
                    break;
                }
            }
            if (lastBlock != null) break;
            if (drawn(c) && !c.display.isInlineLevel() && !PaintSupport.isAtomicInline(c) && !com.nishiyu.enderframelib.core.layout.BlockLayoutEngine.isFloating(c) && !PaintOrder.isPositioned(c)) {
                lastBlock = c;
                break;
            }
        }
        if (lastBlock != null) {
            insertBlockPos = lastBlock.dlEnd;
            insertBlockOrder = lastBlock.dlOrder + 1;
        } else if (parentBox.dlEnd >= 0 && parentBox.dlOrder >= 0) {
            insertBlockPos = parentBox.dlEnd;
            insertBlockOrder = parentBox.dlOrder + 1;
        } else {
            // afterBox 以降の最初のブロックボックスを前方探索
            LayoutBox firstBlock = null;
            for (int i = afterIdx + 1; i < parentBox.children.size(); i++) {
                LayoutBox c = parentBox.children.get(i);
                if (added.contains(c)) continue;
                firstBlock = firstPlacedBlock(c);
                if (firstBlock != null) break;
            }
            placeBlockBefore(firstBlock, parentBox);
        }
    }

    /** インライン層: afterBox 以前の描画済みボックスを逆順探索し、無ければ後続の最初のインライン、それも無ければブロック層の位置。 */
    private void inlineInsertAfter(LayoutBox parentBox, List<LayoutBox> added, int afterIdx) {
        LayoutBox lastInline = null;
        for (int i = afterIdx; i >= 0 && lastInline == null; i--) {
            lastInline = lastDrawn(b.inlinePhaseOf(parentBox.children.get(i)));
        }
        if (lastInline != null) {
            insertInlinePos = lastInline.dlEnd;
            insertInlineOrder = lastInline.dlOrder + 1;
        } else {
            // afterBox 以降の最初のインラインボックスを前方探索
            LayoutBox firstInline = null;
            for (int i = afterIdx + 1; i < parentBox.children.size(); i++) {
                LayoutBox c = parentBox.children.get(i);
                if (added.contains(c)) continue;
                firstInline = firstPlacedInline(c);
                if (firstInline != null) break;
            }
            placeInlineBefore(firstInline);
        }
    }

    private void blockInsertAtHead(LayoutBox parentBox, List<LayoutBox> added) {
        LayoutBox firstBlock = null;
        if (parentBox.children != null) {
            for (LayoutBox c : parentBox.children) {
                if (added.contains(c)) continue;
                firstBlock = firstPlacedBlock(c);
                if (firstBlock != null) break;
            }
        }
        placeBlockBefore(firstBlock, parentBox);
    }

    private void inlineInsertAtHead(LayoutBox parentBox, List<LayoutBox> added) {
        LayoutBox firstInline = null;
        if (parentBox.children != null) {
            for (LayoutBox c : parentBox.children) {
                if (added.contains(c)) continue;
                firstInline = firstPlacedInline(c);
                if (firstInline != null) break;
            }
        }
        placeInlineBefore(firstInline);
    }

    /** 子 {@code c} のブロック層で最初の描画済みの箱（無ければ、ブロックレベルの {@code c} 自身が描画済みならそれ）。 */
    private LayoutBox firstPlacedBlock(LayoutBox c) {
        for (LayoutBox x : b.blockPhaseOf(c)) {
            if (x.dlStart >= 0 && x.dlOrder >= 0) return x;
        }
        if (c.dlStart >= 0 && c.dlOrder >= 0 && !c.display.isInlineLevel() && !PaintSupport.isAtomicInline(c) && !com.nishiyu.enderframelib.core.layout.BlockLayoutEngine.isFloating(c) && !PaintOrder.isPositioned(c)) return c;
        return null;
    }

    /** 子 {@code c} のインライン層で最初に描かれる箱。 */
    private LayoutBox firstPlacedInline(LayoutBox c) {
        return firstDrawn(b.inlinePhaseOf(c));
    }

    /**
     * インラインの層の箱の一覧のうち、描画順で最後（最初）に描かれる箱。アトミックな箱（flex の項目など）の中は「ブロック → float → インライン」の順に
     * 描くので、木の順の最後（中のブロック）が描画順の最後とは限らない（§6-119。差分ファズ nested の種 811: flex の項目 d3 の最後の子のブロック z17 の
     * 直後に入れて、その前の兄弟の文字 ~t47 より前に入った）。
     */
    private LayoutBox lastDrawn(List<LayoutBox> phase) {
        LayoutBox best = null;
        for (int k = 0, n = phase.size(); k < n; k++) {
            LayoutBox x = phase.get(k);
            if (drawn(x) && (best == null || x.dlOrder > best.dlOrder)) best = x;
        }
        return best;
    }

    private LayoutBox firstDrawn(List<LayoutBox> phase) {
        LayoutBox best = null;
        for (int k = 0, n = phase.size(); k < n; k++) {
            LayoutBox x = phase.get(k);
            if (drawn(x) && (best == null || x.dlOrder < best.dlOrder)) best = x;
        }
        return best;
    }

    private void placeBlockBefore(LayoutBox firstBlock, LayoutBox parentBox) {
        if (firstBlock != null) {
            insertBlockPos = firstBlock.dlStart;
            insertBlockOrder = firstBlock.dlOrder;
        } else if (!blockAnchorOutside(parentBox)) {
            // 手がかりが無い。足すブロックの層の項目が無ければ位置は使わないので従来どおり、あれば全体を作り直す（範囲外で find が false）
            insertBlockPos = insertsBlock ? -1 : parentBox.dlEnd >= 0 ? parentBox.dlEnd : 0;
            insertBlockOrder = insertsBlock ? -1 : parentBox.dlOrder >= 0 ? parentBox.dlOrder + 1 : 0;
        }
    }

    /**
     * 親とその子にブロックの層の手がかりが無いとき、祖先へたどる: 木の順で直前は「描かれている祖先自身の項目」か「祖先の前の兄弟のブロックの層」、
     * 直後は「祖先の後ろの兄弟のブロックの層」。以前は描かれていない親（float しか持たず高さ 0）でリストの先頭 0 に入れた（§6-119）。
     * float・位置指定・スタッキングコンテキスト・アトミックな箱は中身の層を自分の区間に閉じる（自分の項目の直後が中のブロックの層の始まり）。
     */
    private boolean blockAnchorOutside(LayoutBox parentBox) {
        for (LayoutBox cur = parentBox; cur != null; cur = cur.parent) {
            boolean closed = com.nishiyu.enderframelib.core.layout.BlockLayoutEngine.isFloating(cur) || PaintOrder.isPositioned(cur)
                    || b.order.createsStackingContext(cur) || PaintSupport.isAtomicInline(cur);
            if (!cur.isAnonymous && (closed || !cur.display.isInlineLevel()) && drawn(cur)) {
                insertBlockPos = cur.dlEnd;
                insertBlockOrder = cur.dlOrder + 1;
                return true;
            }
            if (closed || cur.parent == null) return false;
            List<LayoutBox> sibs = cur.parent.children;
            int idx = sibs.indexOf(cur);
            for (int i = idx - 1; i >= 0; i--) {
                LayoutBox x = lastDrawn(b.blockPhaseOf(sibs.get(i)));
                if (x != null) {
                    insertBlockPos = x.dlEnd;
                    insertBlockOrder = x.dlOrder + 1;
                    return true;
                }
            }
            for (int i = idx + 1, n = sibs.size(); i < n; i++) {
                LayoutBox x = firstDrawn(b.blockPhaseOf(sibs.get(i)));
                if (x != null) {
                    insertBlockPos = x.dlStart;
                    insertBlockOrder = x.dlOrder;
                    return true;
                }
            }
        }
        return false;
    }

    private void placeInlineBefore(LayoutBox firstInline) {
        if (firstInline != null) {
            insertInlinePos = firstInline.dlStart;
            insertInlineOrder = firstInline.dlOrder;
        } else if (!insertsInline || !inlineAnchorOutside(insertParent)) {
            // 手がかりが無い: インラインの層の始まりは float・位置指定の層より後ろで、ブロックの層の直後とは限らない。
            // 足すインラインの項目が無ければ位置は使わないので従来どおり、あれば全体を作り直す（find が範囲外で false）
            insertInlinePos = insertsInline ? -1 : insertBlockPos;
            insertInlineOrder = insertsInline ? -1 : insertBlockOrder;
        }
    }

    /**
     * 親の子にインラインの層の箱が無いとき、祖先の兄弟（同じ描画の層の中で木の順に前後する箱）のインラインの層の箱を手がかりにする。
     * 描画は「ブロックの層 → float の層 → インラインの層」の順なので、ブロックの層の直後（以前の既定）に入れると、間にある float・位置指定の
     * 項目より前に入る（§6-119。差分ファズ layouts の種 1048: body を消した html に float の z15 があり、足した flex の span の文字が float の前に入った）。
     * 位置指定・float・スタッキングコンテキスト・アトミックな箱は中身の層を自分の区間に閉じるので、そこで探索を止める（見つからなければ false）。
     */
    private boolean inlineAnchorOutside(LayoutBox parentBox) {
        for (LayoutBox anc = parentBox; anc != null && anc.parent != null; anc = anc.parent) {
            if (com.nishiyu.enderframelib.core.layout.BlockLayoutEngine.isFloating(anc) || PaintOrder.isPositioned(anc) || b.order.createsStackingContext(anc) || PaintSupport.isAtomicInline(anc)) return false;
            // 木の順で直前は「自分（祖先）の項目」→「祖先の前の兄弟」。インラインの層で描かれる祖先（span）なら、その項目の直後
            boolean ancInline = anc.display != null && anc.display.isInlineLevel() || anc.node != null && anc.node.nodeType == NodeType.TEXT;
            if (ancInline && drawn(anc)) {
                insertInlinePos = anc.dlEnd;
                insertInlineOrder = anc.dlOrder + 1;
                return true;
            }
            // インラインの中のブロックは、親の span の項目との前後が木の順と一致しないことがあるので、手がかりにせず退く
            if (!ancInline && anc.parent.display != null && anc.parent.display.isInlineLevel()) return false;
            List<LayoutBox> sibs = anc.parent.children;
            int idx = sibs.indexOf(anc);
            for (int i = idx - 1; i >= 0; i--) {
                LayoutBox x = lastDrawn(b.inlinePhaseOf(sibs.get(i)));
                if (x != null) {
                    insertInlinePos = x.dlEnd;
                    insertInlineOrder = x.dlOrder + 1;
                    return true;
                }
            }
            for (int i = idx + 1, n = sibs.size(); i < n; i++) {
                LayoutBox x = firstDrawn(b.inlinePhaseOf(sibs.get(i)));
                if (x != null) {
                    insertInlinePos = x.dlStart;
                    insertInlineOrder = x.dlOrder;
                    return true;
                }
            }
        }
        return false;
    }
}
