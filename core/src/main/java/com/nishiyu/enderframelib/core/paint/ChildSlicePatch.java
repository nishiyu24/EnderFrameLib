package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.DisplayListBuilder.SubtreeSlices;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * 子の挿入・削除の差分パッチ（{@link DisplayListBuilder#patchChildSlices}）。A8 で {@code DisplayListBuilder} から切り出した。
 *
 * <p>表示リストは「ブロック層」と「インライン層」の 2 つのスライスの並びで、子の増減は次の順に反映する:
 * ①消える子のスライスを除く ②増える子のスライスを描く ③挿入位置を決める ④⑤両層へ差し込み、後ろの箱の区間をずらす
 * ⑥描画順を振り直す ⑦位置が動いた後続の箱の項目を平行移動する ⑧祖先の高さ・スクロール量 ⑨スクロールバー ⑩ルートの背景。
 * どこかで安全に続けられないと分かったら {@code null} を返し、呼び出し側が全体を作り直す。</p>
 *
 * <p>1 つのビルダーに 1 つ（呼び出しごとに確保しない）。呼び出しの間の状態は欄に持ち、{@link #apply} の先頭で初期化する。
 * 以前は 1 メソッド約 1,000 行・バイトコード 11,940 バイトで、HotSpot の上限（8,000）を超えて JIT されなかった。</p>
 */
final class ChildSlicePatch {
    private final DisplayListBuilder b;

    // 1 回の apply の間だけ使う状態（apply の先頭で初期化）
    private int remBlockStart, remBlockEnd, remBlockOrderStart, remBlockCount;
    private int remInlineStart, remInlineEnd, remInlineOrderStart, remInlineCount;
    private int insertBlockPos, insertBlockOrder, insertInlinePos, insertInlineOrder;
    private LayoutBox lastParentForClip;
    private ClipRect cachedParentClip;
    // 平行移動する箱 1 つぶんの座標系（translateFollowingBox が書き、下請けが読む。箱ごとに確保しない）:
    // 自身の原点（cur*）・親の原点（curP*）・項目の基準（part*）と記憶の基準（oldPart*）
    private boolean relMode, hasRelOrigin;
    private int curOx, curOy, curPox, curPoy, partOx, partOy, oldPartOx, oldPartOy;

    ChildSlicePatch(DisplayListBuilder builder) {
        this.b = builder;
    }

    List<DisplayItem> apply(LayoutBox parentBox, List<LayoutBox> removed, List<LayoutBox> added, LayoutBox afterBox) {
        if (!b.canPatchChildSlices(parentBox, removed, added, afterBox) || !BoxMemo.sizesFollowable(b.boxMemo, parentBox)) {
            DisplayListBuilder.STAT_SLICE_PATCH_FALLBACKS.increment();
            return null;
        }
        boolean relMode = DisplayListBuilder.parentRelativeSource.getAsBoolean();
        b.parentRelative = relMode;
        b.order.setParentRelative(relMode);
        b.order.clearClips();
        remBlockStart = remBlockEnd = remBlockOrderStart = -1;
        remBlockCount = 0;
        remInlineStart = remInlineEnd = remInlineOrderStart = -1;
        remInlineCount = 0;
        lastParentForClip = null;
        cachedParentClip = null;

        // 1. 削除スライスの特定と除去（ブロック層・インライン層）
        if (!removed.isEmpty() && !removeSlices(removed)) return null;

        // 2. 追加ボックスの描画スライス生成（ブロック層・インライン層）
        List<DisplayItem> addedBlockItems = new PaintSupport.CaptureList(16);
        List<LayoutBox> addedBlockBoxes = new ArrayList<>();
        List<DisplayItem> addedInlineItems = new PaintSupport.CaptureList(16);
        List<LayoutBox> addedInlineBoxes = new ArrayList<>();
        b.decor.setDevicePixelRatio(b.currentRoot.devicePixelRatio);
        ClipRect clip = b.order.contentClipOf(parentBox);
        String parentId = b.contentParentIdOf(parentBox);

        boolean parentMoving = b.hasMovingAncestor(parentBox);
        for (LayoutBox a : added) {
            b.renderSubtreeSlices(a, clip, parentId, parentMoving, addedBlockItems, addedBlockBoxes, addedInlineItems, addedInlineBoxes);
        }

        // 3. 挿入位置の決定（ブロック層・インライン層）
        if (!findInsertPositions(parentBox, added, afterBox)) return null;

        // 4. ブロック層の挿入と後続シフト
        int remBlockLen = remBlockStart >= 0 ? (remBlockEnd - remBlockStart) : 0;
        splice(addedBlockItems, addedBlockBoxes, insertBlockPos, insertBlockOrder, remBlockLen);

        // ブロック層の変更がインライン層の挿入位置に及ぼす影響を反映
        int actualInlinePos = insertInlinePos + addedBlockItems.size();
        int actualInlineOrder = insertInlineOrder + addedBlockBoxes.size();

        // 5. インライン層の挿入と後続シフト
        int remInlineLen = remInlineStart >= 0 ? (remInlineEnd - remInlineStart) : 0;
        splice(addedInlineItems, addedInlineBoxes, actualInlinePos, actualInlineOrder, remInlineLen);

        // 6. dlOrder の正規化（挿入位置以降のみ）
        List<LayoutBox> renderedBoxes = b.renderedBoxes;
        int startShiftOrder = Math.max(0, Math.min(insertBlockOrder, actualInlineOrder) - 1);
        for (int k = startShiftOrder, n = renderedBoxes.size(); k < n; k++) {
            renderedBoxes.get(k).dlOrder = k;
        }

        // 7. ボックスの座標変化（dx, dy）のアイテムへの反映（挿入位置以降のみ）
        for (int k = startShiftOrder, n = renderedBoxes.size(); k < n; k++) {
            LayoutBox box = renderedBoxes.get(k);
            BoxMemo m = b.boxMemo.get(box);
            if (m != null) translateFollowingBox(box, m, relMode);
        }

        // 8. 祖先ボックスの寸法変化（親要素・body・html の NODE や背景）および scrollWidth/scrollHeight 変化を反映
        syncAncestorExtents(parentBox);

        // 8b. スクロールバーのトラック・つまみ寸法の同期（祖先コンテナおよび動いたスクロールコンテナ）
        if (!syncScrollbars(parentBox, relMode)) return null;

        // 9. bg_root の寸法同期
        syncRootBackground();

        b.generation++;
        b.buildSerial++;
        b.buildEpoch = LayoutBox.paintEpoch();
        b.lastElementCount = b.currentDisplayList.size();
        DisplayListBuilder.STAT_CHILD_SLICE_PATCH_HITS.increment();
        return b.currentDisplayList;
    }

    // ------------------------------------------------------------------ 1. 削除

    /** 消える子のスライスを両層から除く。続けられないとき false。 */
    private boolean removeSlices(List<LayoutBox> removed) {
        List<DisplayItem> list = b.currentDisplayList;
        List<LayoutBox> renderedBoxes = b.renderedBoxes;
        SubtreeSlices remSlices = new SubtreeSlices();
        for (LayoutBox r : removed) b.collectSubtreeSlices(r, remSlices);

        // インライン層の削除区間
        if (!remSlices.inlineBoxes.isEmpty()) {
            int minS = Integer.MAX_VALUE, maxE = Integer.MIN_VALUE;
            int minO = Integer.MAX_VALUE, maxO = Integer.MIN_VALUE;
            for (LayoutBox x : remSlices.inlineBoxes) {
                if (x.dlStart >= 0 && x.dlEnd >= x.dlStart) {
                    minS = Math.min(minS, x.dlStart);
                    maxE = Math.max(maxE, x.dlEnd);
                }
                if (x.dlOrder >= 0) {
                    minO = Math.min(minO, x.dlOrder);
                    maxO = Math.max(maxO, x.dlOrder);
                }
            }
            if (minS != Integer.MAX_VALUE) {
                remInlineStart = minS;
                remInlineEnd = maxE;
                remInlineOrderStart = minO;
                remInlineCount = maxO - minO + 1;
            }
        }

        // ブロック層の削除区間
        if (!remSlices.blockBoxes.isEmpty()) {
            int minS = Integer.MAX_VALUE, maxE = Integer.MIN_VALUE;
            int minO = Integer.MAX_VALUE, maxO = Integer.MIN_VALUE;
            for (LayoutBox x : remSlices.blockBoxes) {
                if (x.dlStart >= 0 && x.dlEnd >= x.dlStart) {
                    minS = Math.min(minS, x.dlStart);
                    maxE = Math.max(maxE, x.dlEnd);
                }
                if (x.dlOrder >= 0) {
                    minO = Math.min(minO, x.dlOrder);
                    maxO = Math.max(maxO, x.dlOrder);
                }
            }
            if (minS != Integer.MAX_VALUE) {
                remBlockStart = minS;
                remBlockEnd = maxE;
                remBlockOrderStart = minO;
                remBlockCount = maxO - minO + 1;
            }
        }

        // 境界チェック
        if (remInlineStart >= 0 && (remInlineEnd > list.size() || remInlineOrderStart + remInlineCount > renderedBoxes.size())) {
            return false;
        }
        if (remBlockStart >= 0 && (remBlockEnd > list.size() || remBlockOrderStart + remBlockCount > renderedBoxes.size())) {
            return false;
        }

        // 後ろの層から先に削除（通常はインライン層が後ろ）
        if (remInlineStart >= 0 && remBlockStart >= 0) {
            if (remInlineStart >= remBlockEnd) {
                list.subList(remInlineStart, remInlineEnd).clear();
                renderedBoxes.subList(remInlineOrderStart, remInlineOrderStart + remInlineCount).clear();
                list.subList(remBlockStart, remBlockEnd).clear();
                renderedBoxes.subList(remBlockOrderStart, remBlockOrderStart + remBlockCount).clear();
            } else if (remBlockStart >= remInlineEnd) {
                list.subList(remBlockStart, remBlockEnd).clear();
                renderedBoxes.subList(remBlockOrderStart, remBlockOrderStart + remBlockCount).clear();
                list.subList(remInlineStart, remInlineEnd).clear();
                renderedBoxes.subList(remInlineOrderStart, remInlineOrderStart + remInlineCount).clear();
            } else {
                return false; // 重複
            }
        } else if (remInlineStart >= 0) {
            list.subList(remInlineStart, remInlineEnd).clear();
            renderedBoxes.subList(remInlineOrderStart, remInlineOrderStart + remInlineCount).clear();
        } else if (remBlockStart >= 0) {
            list.subList(remBlockStart, remBlockEnd).clear();
            renderedBoxes.subList(remBlockOrderStart, remBlockOrderStart + remBlockCount).clear();
        }

        for (LayoutBox x : remSlices.inlineBoxes) { x.clearDlSlice(); b.scrollBoxes.remove(x); }
        for (LayoutBox x : remSlices.blockBoxes) { x.clearDlSlice(); b.scrollBoxes.remove(x); }
        if (b.windowedBuild) {
            Set<LayoutBox> goneBoxes = Collections.newSetFromMap(new IdentityHashMap<>());
            for (LayoutBox r : removed) DisplayListBuilder.markSubtree(r, goneBoxes);
            b.culledBoxes.removeIf(goneBoxes::contains);
        }
        return true;
    }

    // ------------------------------------------------------------------ 3. 挿入位置

    /** 両層の挿入位置（項目の位置と描画順）を決める。範囲外なら false。 */
    private boolean findInsertPositions(LayoutBox parentBox, List<LayoutBox> added, LayoutBox afterBox) {
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

    /** ブロック層: afterBox 以前の描画済みボックスを逆順探索し、無ければ親・後続の最初のブロックへ。 */
    private void blockInsertAfter(LayoutBox parentBox, List<LayoutBox> added, int afterIdx) {
        LayoutBox lastBlock = null;
        for (int i = afterIdx; i >= 0; i--) {
            LayoutBox c = parentBox.children.get(i);
            List<LayoutBox> blockPhase = b.blockPhaseOf(c);
            for (int k = blockPhase.size() - 1; k >= 0; k--) {
                LayoutBox x = blockPhase.get(k);
                if (x.dlEnd >= 0 && x.dlOrder >= 0) {
                    lastBlock = x;
                    break;
                }
            }
            if (lastBlock != null) break;
            if (c.dlEnd >= 0 && c.dlOrder >= 0 && !c.display.isInlineLevel() && !PaintSupport.isAtomicInline(c)) {
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
        for (int i = afterIdx; i >= 0; i--) {
            LayoutBox c = parentBox.children.get(i);
            List<LayoutBox> inlinePhase = b.inlinePhaseOf(c);
            for (int k = inlinePhase.size() - 1; k >= 0; k--) {
                LayoutBox x = inlinePhase.get(k);
                if (x.dlEnd >= 0 && x.dlOrder >= 0) {
                    lastInline = x;
                    break;
                }
            }
            if (lastInline != null) break;
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
        if (c.dlStart >= 0 && c.dlOrder >= 0 && !c.display.isInlineLevel() && !PaintSupport.isAtomicInline(c)) return c;
        return null;
    }

    /** 子 {@code c} のインライン層で最初の描画済みの箱。 */
    private LayoutBox firstPlacedInline(LayoutBox c) {
        for (LayoutBox x : b.inlinePhaseOf(c)) {
            if (x.dlStart >= 0 && x.dlOrder >= 0) return x;
        }
        return null;
    }

    private void placeBlockBefore(LayoutBox firstBlock, LayoutBox parentBox) {
        if (firstBlock != null) {
            insertBlockPos = firstBlock.dlStart;
            insertBlockOrder = firstBlock.dlOrder;
        } else {
            insertBlockPos = parentBox.dlEnd >= 0 ? parentBox.dlEnd : 0;
            insertBlockOrder = parentBox.dlOrder >= 0 ? parentBox.dlOrder + 1 : 0;
        }
    }

    private void placeInlineBefore(LayoutBox firstInline) {
        if (firstInline != null) {
            insertInlinePos = firstInline.dlStart;
            insertInlineOrder = firstInline.dlOrder;
        } else {
            insertInlinePos = insertBlockPos;
            insertInlineOrder = insertBlockOrder;
        }
    }

    // ------------------------------------------------------------------ 4・5. 差し込み

    /** 1 つの層へ、描いたスライスを差し込み、後ろの箱の区間を「足した数 − 消した数」だけずらす。 */
    private void splice(List<DisplayItem> addedItems, List<LayoutBox> addedBoxes, int pos, int orderAt, int remLen) {
        List<LayoutBox> renderedBoxes = b.renderedBoxes;
        int delta = addedItems.size() - remLen;
        if (!addedItems.isEmpty()) {
            for (LayoutBox x : addedBoxes) {
                x.dlStart += pos;
                x.dlEnd += pos;
            }
            b.currentDisplayList.addAll(pos, addedItems);
            renderedBoxes.addAll(orderAt, addedBoxes);
            for (LayoutBox ab : addedBoxes) if (ab.node != null && PaintOrder.factsOf(ab.style).scrollbars()) b.scrollBoxes.add(ab);
            if (delta != 0) {
                for (int k = orderAt + addedBoxes.size(), n = renderedBoxes.size(); k < n; k++) {
                    LayoutBox succ = renderedBoxes.get(k);
                    succ.dlStart += delta;
                    succ.dlEnd += delta;
                }
            }
        } else if (remLen > 0) {
            for (int k = orderAt, n = renderedBoxes.size(); k < n; k++) {
                LayoutBox succ = renderedBoxes.get(k);
                succ.dlStart -= remLen;
                succ.dlEnd -= remLen;
            }
        }
    }

    // ------------------------------------------------------------------ 7. 後続の箱の平行移動

    /** 記憶のある後続の箱が動いていれば、その項目を新しい位置へ動かし、記憶を今のリストに揃える。 */
    private void translateFollowingBox(LayoutBox box, BoxMemo m, boolean relMode) {
        List<DisplayItem> list = b.currentDisplayList;
        // 表示リストの中の項目は、記憶の位置から appliedExactDx/appliedExactDy だけ動いている（箱の位置は記憶の基準を足して見る）
        final float ax = m.appliedFragOnly ? 0f : m.appliedExactDx, ay = m.appliedFragOnly ? 0f : m.appliedExactDy;
        final float fx = m.appliedExactDx, fy = m.appliedExactDy;
        float dx = (m.nums != null && m.nums.length >= 2) ? (box.x - (m.nums[0] + ax)) : 0;
        float dy = (m.nums != null && m.nums.length >= 2) ? (box.y - (m.nums[1] + ay)) : 0;
        boolean isTextNode = (box.node != null && box.node.nodeType == NodeType.TEXT);
        if (dx == 0 && dy == 0 && !box.inlineFragments.isEmpty() && m.fragNums != null && m.fragNums.length >= 2) {
            LayoutBox.InlineFragment f0 = box.inlineFragments.get(0);
            dx = f0.x - (m.fragNums[0] + fx);
            dy = f0.y - (m.fragNums[1] + fy);
        }
        if (dx == 0 && dy == 0) return;
        if (box.dlStart < 0 || box.dlEnd > list.size() || box.dlStart > box.dlEnd) return;
        DisplayListBuilder.STAT_TRANSLATED.increment();

        // 親クリップのキャッシュ（親が同一なら再計算スキップ）
        ClipRect expectedClip;
        if (box.parent == lastParentForClip) {
            expectedClip = cachedParentClip;
        } else {
            expectedClip = b.order.contentClipOf(box.parent);
            lastParentForClip = box.parent;
            cachedParentClip = expectedClip;
        }

        this.relMode = relMode;
        curOx = Math.round(box.x);
        curOy = Math.round(box.y);
        hasRelOrigin = relMode && PaintOrder.boxHasOwnNode(box);
        curPox = 0;
        curPoy = 0;
        if (relMode) {
            String bParentId = m.parentId != null ? m.parentId : b.parentIdForBox(box);
            LayoutBox pNode = PaintOrder.effectiveParentNodeBox(box, bParentId);
            curPox = pNode != null ? Math.round(pNode.x) : 0;
            curPoy = pNode != null ? Math.round(pNode.y) : 0;
        }
        partOx = hasRelOrigin ? curOx : curPox;
        partOy = hasRelOrigin ? curOy : curPoy;
        oldPartOx = hasRelOrigin ? m.originX : m.parentOriginX;
        oldPartOy = hasRelOrigin ? m.originY : m.parentOriginY;

        int itemCount = box.dlEnd - box.dlStart;
        if (isTextNode && !box.inlineFragments.isEmpty()) shiftTextItems(box, m, expectedClip, fx, fy, itemCount);
        else shiftBlockItems(box, m, expectedClip, ax, ay);

        rebaseItemCoords(box, m, itemCount);
        resetMemoToList(box, m, expectedClip, itemCount);
    }

    /** 文字の節: 断片ごとの TEXT と、節の NODE・装飾の項目を動かす。 */
    private void shiftTextItems(LayoutBox box, BoxMemo m, ClipRect expectedClip, float fx, float fy, int itemCount) {
        List<DisplayItem> list = b.currentDisplayList;
        int numFrags = box.inlineFragments.size();
        if (numFrags == 1 && itemCount == 1 && list.get(box.dlStart).type() == ElementType.TEXT) {
            // 最頻ケース: 単一フラグメント・単一TEXTアイテム（リスト行のテキストなど）
            LayoutBox.InlineFragment f0 = box.inlineFragments.get(0);
            int newTop = Math.round(f0.y - partOy);
            int newLeft = Math.round(f0.x - partOx);
            int newH = Math.round(f0.y + f0.h - partOy) - newTop;
            int newW = Math.round(f0.x + f0.w - partOx) - newLeft;
            DisplayItem it = list.get(box.dlStart);
            RenderMeta meta = rebaseClip(it.meta(), m, expectedClip);
            list.set(box.dlStart, rebuilt(it, newLeft, newTop, newW, newH, meta));
            return;
        }
        // 複数フラグメントまたは装飾アンカー・シャドウを含むケース
        List<LayoutBox.InlineFragment> validFrags = new ArrayList<>(numFrags);
        for (int fi = 0; fi < numFrags; fi++) {
            LayoutBox.InlineFragment f = box.inlineFragments.get(fi);
            if (f.text != null && !f.text.isBlank()) validFrags.add(f);
        }
        int textItemCount = 0;
        for (int idx = box.dlStart; idx < box.dlEnd; idx++) {
            if (list.get(idx).type() == ElementType.TEXT) textItemCount++;
        }
        if (textItemCount == validFrags.size()) {
            int fIdx = 0;
            LayoutBox.InlineFragment f0 = validFrags.get(0);
            int baseTop = Math.round(f0.y - partOy);
            int baseLeft = Math.round(f0.x - partOx);
            float oldFragY = m.fragNums != null && m.fragNums.length >= 2 ? m.fragNums[1] + fy : f0.y;
            float oldFragX = m.fragNums != null && m.fragNums.length >= 2 ? m.fragNums[0] + fx : f0.x;
            int oldBaseTop = Math.round(oldFragY - oldPartOy);
            int oldBaseLeft = Math.round(oldFragX - oldPartOx);
            for (int idx = box.dlStart; idx < box.dlEnd; idx++) {
                DisplayItem it = list.get(idx);
                RenderMeta meta = rebaseClip(it.meta(), m, expectedClip);
                if (it.type() == ElementType.TEXT) {
                    LayoutBox.InlineFragment f = validFrags.get(fIdx++);
                    int newTop = Math.round(f.y - partOy);
                    int newLeft = Math.round(f.x - partOx);
                    int newH = Math.round(f.y + f.h - partOy) - newTop;
                    int newW = Math.round(f.x + f.w - partOx) - newLeft;
                    list.set(idx, rebuilt(it, newLeft, newTop, newW, newH, meta));
                } else if (it.id() != null && box.node != null && it.id().equals(box.node.key())) {
                    list.set(idx, rebuilt(it, curOx - curPox, curOy - curPoy, it.width(), it.height(), meta));
                } else {
                    int newY = baseTop + (it.y() - oldBaseTop);
                    int newX = baseLeft + (it.x() - oldBaseLeft);
                    list.set(idx, rebuilt(it, newX, newY, it.width(), it.height(), meta));
                }
            }
        } else {
            LayoutBox.InlineFragment f0 = box.inlineFragments.get(0);
            int targetTop = Math.round(f0.y - partOy);
            int targetLeft = Math.round(f0.x - partOx);
            float oldFragY = m.fragNums != null && m.fragNums.length >= 2 ? m.fragNums[1] + fy : f0.y;
            float oldFragX = m.fragNums != null && m.fragNums.length >= 2 ? m.fragNums[0] + fx : f0.x;
            int oldTop = Math.round(oldFragY - oldPartOy);
            int oldLeft = Math.round(oldFragX - oldPartOx);
            int targetH = Math.round(f0.y + f0.h - partOy) - targetTop;
            int targetW = Math.round(f0.x + f0.w - partOx) - targetLeft;
            int oldHInt = Math.round(oldFragY + f0.h - oldPartOy) - oldTop;
            int oldWInt = Math.round(oldFragX + f0.w - oldPartOx) - oldLeft;
            for (int idx = box.dlStart; idx < box.dlEnd; idx++) {
                DisplayItem it = list.get(idx);
                RenderMeta meta = rebaseClip(it.meta(), m, expectedClip);
                if (it.id() != null && box.node != null && it.id().equals(box.node.key())) {
                    list.set(idx, rebuilt(it, curOx - curPox, curOy - curPoy, it.width(), it.height(), meta));
                } else {
                    int newY = targetTop + (it.y() - oldTop);
                    int newX = targetLeft + (it.x() - oldLeft);
                    int newH = (it.height() == oldHInt) ? targetH : it.height();
                    int newW = (it.width() == oldWInt) ? targetW : it.width();
                    list.set(idx, rebuilt(it, newX, newY, newW, newH, meta));
                }
            }
        }
    }

    /** ブロック要素: 箱の移動量と寸法の変化を項目へ写す（下・右の枠線は端へ付ける）。 */
    private void shiftBlockItems(LayoutBox box, BoxMemo m, ClipRect expectedClip, float ax, float ay) {
        List<DisplayItem> list = b.currentDisplayList;
        int targetTop = Math.round(box.y - partOy);
        int targetLeft = Math.round(box.x - partOx);
        float oldBoxY = m.nums != null && m.nums.length >= 2 ? m.nums[1] + ay : box.y;
        float oldBoxX = m.nums != null && m.nums.length >= 2 ? m.nums[0] + ax : box.x;
        float oldBoxH = m.nums != null && m.nums.length >= 4 ? m.nums[3] : box.h;
        float oldBoxW = m.nums != null && m.nums.length >= 4 ? m.nums[2] : box.w;
        int oldTop = Math.round(oldBoxY - oldPartOy);
        int oldLeft = Math.round(oldBoxX - oldPartOx);
        int targetH = Math.round(box.y + box.h - partOy) - targetTop; // 項目の座標系（絶対なら 0、親相対なら親の原点）で見た下端から上端を引く（旧い式は、絶対座標で箱の位置を二重に引いて負になった）
        int targetW = Math.round(box.x + box.w - partOx) - targetLeft;
        int oldHInt = Math.round(oldBoxY + oldBoxH - oldPartOy) - oldTop;
        int oldWInt = Math.round(oldBoxX + oldBoxW - oldPartOx) - oldLeft;
        int shiftX = targetLeft - oldLeft;
        int shiftY = targetTop - oldTop;
        boolean sameDim = (targetH == oldHInt && targetW == oldWInt);

        for (int idx = box.dlStart; idx < box.dlEnd; idx++) {
            DisplayItem it = list.get(idx);
            int newX, newY, newW, newH;
            if (hasRelOrigin && idx == box.dlStart && it.type() == ElementType.NODE && it.hints().relativeOrigin()) {
                newX = curOx - curPox;
                newY = curOy - curPoy;
                newW = targetW;
                newH = targetH;
            } else if (sameDim) {
                newX = it.x() + shiftX;
                newY = it.y() + shiftY;
                newW = it.width();
                newH = it.height();
            } else {
                newY = targetTop + (it.y() - oldTop);
                newX = targetLeft + (it.x() - oldLeft);
                newH = (it.height() == oldHInt) ? targetH : it.height();
                newW = (it.width() == oldWInt) ? targetW : it.width();
                if (it.id() != null && it.id().endsWith("_b_bottom")) {
                    newY = (targetTop + targetH) - it.height();
                } else if (it.id() != null && it.id().endsWith("_b_right")) {
                    newX = (targetLeft + targetW) - it.width();
                }
            }
            RenderMeta meta = rebaseClip(it.meta(), m, expectedClip);
            if (meta != null && meta.hints() != null && meta.hints().scrollable()) {
                meta = meta.withHints(meta.hints().withScroll(box.scrollWidth, box.scrollHeight));
            }
            list.set(idx, rebuilt(it, newX, newY, newW, newH, meta));
        }
    }

    /** 小数の座標の記憶（{@code itemCoords}）を新しい位置へ進める。丸めが今の項目と合わなければ捨てる。 */
    private void rebaseItemCoords(LayoutBox box, BoxMemo m, int itemCount) {
        if (m.itemCoords == null) return;
        if (!(m.items != null && m.itemCoords.length == m.items.size() * 4 && itemCount >= m.items.size())) {
            m.itemCoords = null;
            return;
        }
        List<DisplayItem> list = b.currentDisplayList;
        int odx = relMode ? (partOx - oldPartOx) : 0;
        int ody = relMode ? (partOy - oldPartOy) : 0;
        float boxShiftX = (m.nums != null && m.nums.length >= 2) ? ((box.x - m.nums[0]) - odx) : 0f;
        float boxShiftY = (m.nums != null && m.nums.length >= 2) ? ((box.y - m.nums[1]) - ody) : 0f;
        float fragShiftX = (!box.inlineFragments.isEmpty() && m.fragNums != null && m.fragNums.length >= 2)
                ? ((box.inlineFragments.get(0).x - m.fragNums[0]) - odx) : boxShiftX;
        float fragShiftY = (!box.inlineFragments.isEmpty() && m.fragNums != null && m.fragNums.length >= 2)
                ? ((box.inlineFragments.get(0).y - m.fragNums[1]) - ody) : boxShiftY;
        for (int i = 0, nItems = m.items.size(); i < nItems; i++) {
            DisplayItem cur = list.get(box.dlStart + i);
            float nx, ny;
            if (hasRelOrigin && i == 0 && cur.type() == ElementType.NODE && cur.hints().relativeOrigin()) {
                nx = box.x - curPox;
                ny = box.y - curPoy;
            } else {
                boolean isFragItem = !box.inlineFragments.isEmpty() && BoxMemo.movesWithFragment(cur);
                nx = m.itemCoords[i * 4] + (isFragItem ? fragShiftX : boxShiftX);
                ny = m.itemCoords[i * 4 + 1] + (isFragItem ? fragShiftY : boxShiftY);
            }
            float fw = m.itemCoords[i * 4 + 2], fh = m.itemCoords[i * 4 + 3];
            int rx = Math.round(nx), ry = Math.round(ny);
            int rw = Math.round(nx + fw) - rx, rh = Math.round(ny + fh) - ry;
            if (rx != cur.x() || ry != cur.y() || rw != cur.width() || rh != cur.height()) {
                m.itemCoords = null;
                return;
            }
            m.itemCoords[i * 4] = nx;
            m.itemCoords[i * 4 + 1] = ny;
        }
    }

    /** 記憶の基準（位置・原点・スクロール量・クリップ・断片の位置・項目）を今のリストの状態に揃える。 */
    private void resetMemoToList(LayoutBox box, BoxMemo m, ClipRect expectedClip, int itemCount) {
        List<DisplayItem> list = b.currentDisplayList;
        m.appliedDx = m.appliedDy = 0; // 記憶を今のリストの状態に揃える（下で項目も取り直す）
        m.appliedExactDx = m.appliedExactDy = 0f;
        m.appliedOriginDx = m.appliedOriginDy = 0;
        m.appliedFragOnly = false;
        m.appliedNonUniform = false;
        if (m.nums != null && m.nums.length >= 2) {
            m.nums[0] = box.x;
            m.nums[1] = box.y;
        }
        if (relMode) {
            m.originX = curOx;
            m.originY = curOy;
            m.parentOriginX = curPox;
            m.parentOriginY = curPoy;
        }
        m.scrollW = box.scrollWidth;
        m.scrollH = box.scrollHeight;
        if (expectedClip != null) {
            m.clip = expectedClip;
        }
        if (!box.inlineFragments.isEmpty() && m.fragNums != null && m.fragNums.length >= 2) {
            for (int i = 0; i < box.inlineFragments.size() && (i * 8 + 1) < m.fragNums.length; i++) {
                m.fragNums[i * 8] = box.inlineFragments.get(i).x;
                m.fragNums[i * 8 + 1] = box.inlineFragments.get(i).y;
            }
        }
        if (itemCount > 0) {
            int memoEnd = memoEndOf(box);
            int mLen = memoEnd - box.dlStart;
            DisplayItem[] arr = new DisplayItem[mLen];
            for (int ai = 0; ai < mLen; ai++) {
                arr[ai] = list.get(box.dlStart + ai);
            }
            m.items = java.util.Arrays.asList(arr);
        }
        box.touchPaint();
    }

    /** 箱の記憶が覆う項目の終わり（スクロールする中身の節は箱の記憶に含めない）。 */
    private int memoEndOf(LayoutBox box) {
        int memoEnd = box.dlEnd;
        PaintOrder.ScrollContent content = b.order.scrollContentOf(box);
        if (content != null && memoEnd > box.dlStart && content.contentId.equals(b.currentDisplayList.get(memoEnd - 1).id())) {
            memoEnd--;
        }
        return memoEnd;
    }

    // ------------------------------------------------------------------ 8. 祖先の寸法

    private void syncAncestorExtents(LayoutBox parentBox) {
        List<DisplayItem> list = b.currentDisplayList;
        for (LayoutBox anc = parentBox; anc != null; anc = anc.parent) {
            BoxMemo am = b.boxMemo.get(anc);
            if (am == null || am.nums == null || am.nums.length < 4) continue;
            float oldH = am.nums[3];
            float newH = anc.h;
            boolean hChanged = Float.compare(oldH, newH) != 0;
            boolean scrollChanged = Float.compare(am.scrollH, anc.scrollHeight) != 0 || Float.compare(am.scrollW, anc.scrollWidth) != 0;
            if (!((hChanged || scrollChanged) && anc.dlStart >= 0 && anc.dlEnd > anc.dlStart)) continue;
            int targetTop = Math.round(anc.y);
            int targetH = Math.round(anc.y + newH) - targetTop;
            int oldTop = Math.round(am.nums[1]);
            int oldHInt = Math.round(am.nums[1] + oldH) - oldTop;
            int dh = targetH - oldHInt;
            for (int idx = anc.dlStart; idx < anc.dlEnd; idx++) {
                DisplayItem it = list.get(idx);
                RenderMeta meta = it.meta();
                if (meta != null && meta.hints() != null && meta.hints().scrollable()) {
                    meta = meta.withHints(meta.hints().withScroll(anc.scrollWidth, anc.scrollHeight));
                }
                boolean itemChanged = false;
                int nextH = it.height();
                int nextY = it.y();
                if (hChanged) {
                    if (it.type() == ElementType.NODE) {
                        nextH = targetH;
                        itemChanged = true;
                    } else if (it.type() == ElementType.RECT && it.id() != null && (it.id().endsWith("_b_top") || it.id().endsWith("_b_bottom"))) {
                        // 上下の枠線は太さを保ち、下の線は箱の下端へ動く（高さに dh を足すと、太さが負になる）
                        if (it.id().endsWith("_b_bottom")) nextY = it.y() + dh;
                        itemChanged = true;
                    } else if (it.type() == ElementType.RECT || it.type() == ElementType.ROUNDED_BORDER) {
                        nextH = (it.height() == oldHInt) ? targetH : it.height() + dh;
                        itemChanged = true;
                    }
                }
                if (meta != it.meta()) {
                    itemChanged = true;
                }
                if (itemChanged) {
                    list.set(idx, rebuilt(it, it.x(), nextY, it.width(), nextH, meta));
                }
            }
            am.nums[3] = newH;
            am.scrollH = anc.scrollHeight;
            am.scrollW = anc.scrollWidth;
            if (anc.dlStart >= 0 && anc.dlEnd >= anc.dlStart) {
                int memoEnd = memoEndOf(anc);
                BoxMemo.syncApplied(am); // 項目を今のリストから取り直すので、基準の位置も動いた分だけ進める
                if (hChanged) am.itemCoords = null;
                am.items = List.copyOf(list.subList(anc.dlStart, memoEnd));
                am.itemsMutable = false;
            }
            anc.touchPaint();
        }
    }

    // ------------------------------------------------------------------ 8b. スクロールバー

    /** 祖先と描画済みのスクロールコンテナのつまみ・トラックを今の寸法へ。出現・消滅があれば false（全体を作り直す）。 */
    private boolean syncScrollbars(LayoutBox parentBox, boolean relMode) {
        Set<LayoutBox> scrollbarBoxes = Collections.newSetFromMap(new IdentityHashMap<>());
        for (LayoutBox anc = parentBox; anc != null; anc = anc.parent) {
            if (anc.node != null && PaintOrder.factsOf(anc.style).scrollbars()) scrollbarBoxes.add(anc);
        }
        // 全箱ではなく、描画時に集めたスクロール可能な箱だけを見る（取り残しは dlOrder が今の並びと合うかで除く）
        List<LayoutBox> renderedBoxes = b.renderedBoxes;
        for (LayoutBox x : b.scrollBoxes) {
            if (x.dlOrder >= 0 && x.dlOrder < renderedBoxes.size() && renderedBoxes.get(x.dlOrder) == x) scrollbarBoxes.add(x);
        }
        for (LayoutBox anc : scrollbarBoxes) {
            if (!syncScrollbarsOf(anc, relMode)) return false;
        }
        return true;
    }

    private boolean syncScrollbarsOf(LayoutBox anc, boolean relMode) {
        List<DisplayItem> list = b.currentDisplayList;
        float scrollH = anc.scrollHeight;
        float scrollW = anc.scrollWidth;
        boolean wantsV = scrollH > anc.h + 2 && anc.h > 12;
        boolean wantsH = scrollW > anc.w + 2 && anc.w > 12;
        // 6 部品は 64-bit 整数鍵（nodeHandle<<16 | 部品番号）で引く。文字列 equals を全項目 × 6 回しない
        int sbHandle = anc.node.nodeId();
        boolean byKey = sbHandle > 0;
        long vtKey = byKey ? DisplayItem.keyOf(sbHandle, DisplayItem.PART_SCROLLBAR_TRACK_V) : 0L;
        long vKey = byKey ? DisplayItem.keyOf(sbHandle, DisplayItem.PART_SCROLLBAR_V) : 0L;
        long svKey = byKey ? DisplayItem.keyOf(sbHandle, DisplayItem.PART_SCROLLBAR_NODE_V) : 0L;
        long htKey = byKey ? DisplayItem.keyOf(sbHandle, DisplayItem.PART_SCROLLBAR_TRACK_H) : 0L;
        long hKey = byKey ? DisplayItem.keyOf(sbHandle, DisplayItem.PART_SCROLLBAR_H) : 0L;
        long shKey = byKey ? DisplayItem.keyOf(sbHandle, DisplayItem.PART_SCROLLBAR_NODE_H) : 0L;
        String vtId = byKey ? null : anc.node.key() + "_sb_vt";
        String vId = byKey ? null : anc.node.key() + "_sb_v";
        String svId = byKey ? null : anc.node.key() + "_scrollv";
        String htId = byKey ? null : anc.node.key() + "_sb_ht";
        String hId = byKey ? null : anc.node.key() + "_sb_h";
        String shId = byKey ? null : anc.node.key() + "_scrollh";
        int vtIdx = -1, vIdx = -1, svIdx = -1;
        int htIdx = -1, hIdx = -1, shIdx = -1;
        int sbGrace = 3;
        PaintOrder.ScrollContent content = b.order.scrollContentOf(anc);
        for (int i = list.size() - 1; i >= 0; i--) {
            DisplayItem item = list.get(i);
            if (byKey) {
                long k = item.key();
                if (vtIdx < 0 && k == vtKey) vtIdx = i;
                else if (vIdx < 0 && k == vKey) vIdx = i;
                else if (svIdx < 0 && k == svKey) svIdx = i;
                else if (htIdx < 0 && k == htKey) htIdx = i;
                else if (hIdx < 0 && k == hKey) hIdx = i;
                else if (shIdx < 0 && k == shKey) shIdx = i;
            } else {
                if (vtIdx < 0 && vtId.equals(item.id())) vtIdx = i;
                if (vIdx < 0 && vId.equals(item.id())) vIdx = i;
                if (svIdx < 0 && svId.equals(item.id())) svIdx = i;
                if (htIdx < 0 && htId.equals(item.id())) htIdx = i;
                if (hIdx < 0 && hId.equals(item.id())) hIdx = i;
                if (shIdx < 0 && shId.equals(item.id())) shIdx = i;
            }
            // 6 部品は「縦（vt, sv, v）→ 横（ht, sh, h）」の順に連続して置かれ、持ち主の内容の後ろ（多くは表示リストの末尾付近）にある。
            // 欲しい軸の部品が全部見つかったら、番号の小さい側に隣り合う高々 3 件（反対軸の取り残し）だけ確かめて打ち切る。
            // 欲しい軸が無いときは、無いことを示すため従来どおり全走査する。
            boolean haveV = !wantsV || (vtIdx >= 0 && vIdx >= 0 && (content == null || svIdx >= 0));
            boolean haveH = !wantsH || (htIdx >= 0 && hIdx >= 0 && (content == null || shIdx >= 0));
            if (haveV && haveH && (wantsV || wantsH)) {
                if (--sbGrace < 0) break;
            } else if (haveV && haveH && (content == null || (svIdx >= 0 && shIdx >= 0))) {
                break;
            }
        }
        if (wantsV != (vIdx >= 0) || wantsH != (hIdx >= 0)) {
            // スクロールバーの出現・消滅はアイテム数増減を伴うため安全にフル描画へフォールバック
            return false;
        }
        ClipRect expectedClip = b.order.contentClipOf(anc.parent);
        int ancOx = (relMode && PaintOrder.boxHasOwnNode(anc)) ? Math.round(anc.x) : 0;
        int ancOy = (relMode && PaintOrder.boxHasOwnNode(anc)) ? Math.round(anc.y) : 0;
        int barThickness = 4;
        if (wantsV && vtIdx >= 0 && vIdx >= 0) {
            float curY = b.scrollState != null ? b.scrollState.getScrollY(anc.node) : 0f;
            float trackX = anc.x + anc.w - anc.bR - barThickness - 2;
            float trackY = anc.y + anc.bT + 2;
            float trackH = anc.h - anc.bT - anc.bB - 4;
            float thumbH = Math.round(Math.max(12, trackH * (anc.h / scrollH))); // 整数: つまみの位置で丸めの高さが揺れない（平行移動と作り直しで同じ大きさ）
            float maxScrollY = Math.max(1f, scrollH - anc.h);
            float thumbY = trackY + (trackH - thumbH) * Math.clamp(curY / maxScrollY, 0f, 1f);

            int vtLeft = Math.round(trackX - ancOx);
            int vtTop = Math.round(trackY - ancOy);
            setBounds(vtIdx, vtLeft, vtTop, Math.round(trackX - ancOx + barThickness) - vtLeft, Math.round(trackY - ancOy + trackH) - vtTop, expectedClip);

            if (content != null && svIdx >= 0) {
                content.thumbVId = svId;
                content.ky = (trackH - thumbH) / maxScrollY;
                content.fy0 = thumbY - Math.round(thumbY);
                setNodeBounds(svIdx, anc, ancOx, ancOy, expectedClip);
            }

            int vLeft = Math.round(trackX - ancOx);
            int vTop = Math.round(thumbY - ancOy);
            setBounds(vIdx, vLeft, vTop, Math.round(trackX - ancOx + barThickness) - vLeft, Math.round(thumbY - ancOy + thumbH) - vTop, expectedClip);
        }
        if (wantsH && htIdx >= 0 && hIdx >= 0) {
            float curX = b.scrollState != null ? b.scrollState.getScrollX(anc.node) : 0f;
            float trackX = anc.x + anc.bL + 2;
            float trackY = anc.y + anc.h - anc.bB - barThickness - 2;
            float trackW = anc.w - anc.bL - anc.bR - 4;
            float thumbW = Math.round(Math.max(12, trackW * (anc.w / scrollW)));
            float maxScrollX = Math.max(1f, scrollW - anc.w);
            float thumbX = trackX + (trackW - thumbW) * Math.clamp(curX / maxScrollX, 0f, 1f);

            int htLeft = Math.round(trackX - ancOx);
            int htTop = Math.round(trackY - ancOy);
            setBounds(htIdx, htLeft, htTop, Math.round(trackX - ancOx + trackW) - htLeft, Math.round(trackY - ancOy + barThickness) - htTop, expectedClip);

            if (content != null && shIdx >= 0) {
                content.thumbHId = shId;
                content.kx = (trackW - thumbW) / maxScrollX;
                content.fx0 = thumbX - Math.round(thumbX);
                setNodeBounds(shIdx, anc, ancOx, ancOy, expectedClip);
            }

            int hLeft = Math.round(thumbX - ancOx);
            int hTop = Math.round(trackY - ancOy);
            setBounds(hIdx, hLeft, hTop, Math.round(thumbX - ancOx + thumbW) - hLeft, Math.round(trackY - ancOy + barThickness) - hTop, expectedClip);
        }
        return true;
    }

    /** つまみの節（スクロールする中身の移動用の NODE）を箱の外形へ。 */
    private void setNodeBounds(int idx, LayoutBox anc, int ancOx, int ancOy, ClipRect expectedClip) {
        int left = Math.round(anc.x - ancOx);
        int top = Math.round(anc.y - ancOy);
        setBounds(idx, left, top, Math.round(anc.x - ancOx + anc.w) - left, Math.round(anc.y - ancOy + anc.h) - top, expectedClip);
    }

    /** {@code idx} の項目の外形を書き、クリップがあれば親の中身のクリップへ付け替える。 */
    private void setBounds(int idx, int x, int y, int w, int h, ClipRect expectedClip) {
        DisplayItem it = b.currentDisplayList.get(idx);
        RenderMeta meta = it.meta();
        if (meta != null && meta.clipRect() != null && expectedClip != null) {
            meta = meta.withClip(expectedClip);
        }
        b.currentDisplayList.set(idx, rebuilt(it, x, y, w, h, meta));
    }

    // ------------------------------------------------------------------ 9. ルートの背景

    private void syncRootBackground() {
        List<DisplayItem> list = b.currentDisplayList;
        if (list.isEmpty()) return;
        DisplayItem first = list.get(0);
        if ("bg_root".equals(first.id()) && b.currentRoot != null) {
            int expectedH = Math.round(Math.max(b.currentRoot.h, (float) b.rootH));
            if (first.height() != expectedH) {
                list.set(0, rebuilt(first, first.x(), first.y(), first.width(), expectedH, first.meta()));
            }
        }
    }

    // ------------------------------------------------------------------ 共通

    /** 項目のクリップを、親の中身のクリップ（{@code expectedClip}）へ付け替える（親相対なら部分クリップの基準を移す）。 */
    private RenderMeta rebaseClip(RenderMeta meta, BoxMemo m, ClipRect expectedClip) {
        if (meta != null && meta.clipRect() != null && expectedClip != null && !expectedClip.equals(meta.clipRect())) {
            ClipRect nextClip = relMode ? PaintSupport.rebaseSubClip(meta.clipRect(), m.clip, expectedClip, curOx, curOy) : expectedClip;
            if (!BoxMemo.sameClip(nextClip, meta.clipRect())) meta = meta.withClip(nextClip);
        }
        return meta;
    }

    /**
     * 外形とメタだけを変えた項目を作り直す（ほかの欄はそのまま。塗りのセルは値から作り直す）。
     * {@link DisplayItem#withBoundsAndMeta} と違い、変化が無くても新しい実体を返す（切り出し前の挙動を保つ）。
     */
    static DisplayItem rebuilt(DisplayItem it, int x, int y, int w, int h, RenderMeta meta) {
        return new DisplayItem(
                it.id(), it.baseNodeId(), it.type(),
                x, y, w, h,
                it.text(), it.color(), it.bgColor(), it.events(),
                it.radius(), it.opacity(), it.baseTranslateX(), it.baseTranslateY(),
                it.scaleX(), it.scaleY(), it.rotate(), it.transitionDuration(),
                it.animDef(), it.fitMode(), meta, it.key());
    }
}
