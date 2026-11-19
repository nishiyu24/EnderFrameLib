package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.css.model.AnimationSpec;

import com.nishiyu.enderframelib.core.paint.BoxDecorationPainter;
import com.nishiyu.enderframelib.core.paint.ItemDiff;
import com.nishiyu.enderframelib.core.paint.PaintSceneDriver;
import com.nishiyu.enderframelib.core.paint.PaintSceneDriver.Resolved;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.RenderHints;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;
import com.nishiyu.enderframelib.core.raster.Matrix2D;

import java.util.HashMap;
import java.util.List;

/**
 * ソフトウェア描画の差分描画の計画（{@link SoftwareRenderBackend} の損傷範囲）: 前のフレームの項目と比べて、描き直すタイルと
 * 項目を決める（{@code planDamage}）。決められなければ全面を描く。フレームの状態（前後の外接矩形・クリップ・動きの印）は
 * 描画本体と共有するので持ち主に置き、このクラスは判定の手順だけを持つ。A8 で {@code SoftwareRenderBackend} から切り出した。
 */
final class DamagePlanner {
    private final SoftwareRenderBackend backend;

    DamagePlanner(SoftwareRenderBackend backend) {
        this.backend = backend;
    }

    // ------------------------------------------------------------------ 差分描画の計画

    static boolean isScrollContent(DisplayItem item) {
        if (item == null || item.type() != ElementType.NODE) return false;
        if (DisplayItem.partOrdinalOf(item.key()) == DisplayItem.PART_SCROLL_CONTENT) return true;
        String id = item.id();
        return id != null && id.endsWith(PaintSceneDriver.SCROLL_CONTENT_SUFFIX);
    }

    /**
     * このフレームを差分で描けるか決め、描けるなら {@link SoftwareRenderBackend#damage} に塗り直す範囲を、{@link SoftwareRenderBackend#skipBuf} に描かない項目を入れる。
     * 描けない（初回・項目の増減・種類の変化・アニメーション中・層を要する項目がある）なら false を返す。
     */
    boolean planDamage(List<DisplayItem> items, long nowMs) {
        int n = items.size();
        if (backend.activity.length != n) backend.activity = new byte[n];
        byte maxAct = backend.driver.fillActivity(backend.activity, n, nowMs);
        backend.frameHadActive = maxAct != PaintSceneDriver.ACT_IDLE;
        backend.frameHadGeometryActive = maxAct == PaintSceneDriver.ACT_OPACITY;
        // 色・変形だけが動く項目（hover のトランジションなど）は、項目数が同じなら項目単位で塗り直す
        // （変形は動いた節の子孫の塗り直しに乗る）。不透明度が動く項目（層を要する）や、項目の増減を伴う動きは全面へ戻す
        boolean perItemActivity = backend.prevItems != null && backend.prevItems.size() == n && backend.prevActivity.length == n;
        int why = backend.prevHadGeometryActive || (backend.prevHadActive && !perItemActivity) ? 1 : !com.nishiyu.enderframelib.core.kernel.Shortcut.SOFT_DAMAGE.on() ? 2
                : (!backend.prevValid || backend.prevItems == null) ? 3
                : (backend.prevClearColor != backend.clearColor || backend.prevCullOffscreen != com.nishiyu.enderframelib.core.kernel.Shortcut.SOFT_CULL_OFFSCREEN.on()) ? 4
                : (!stableList(items) || !stableList(backend.prevItems)) ? 5
                : (backend.frameHadActive && (backend.frameHadGeometryActive || !perItemActivity)) ? 6 : 0;
        if (why != 0) {
            backend.fullWhy[why]++;
            resetCurExtents(n);
            return false;
        }

        int oldN = backend.prevItems.size();
        final int seen = backend.prevEpoch;
        boolean sameSize = oldN == n;
        // 次のフレームは、ここまでに書かれたものを見たものとして、後の書き込みだけを変化とみなす
        backend.prevEpoch = com.nishiyu.enderframelib.core.paint.model.PaintCell.currentEpoch();
        int[] oldIndexOf = null;
        if (!sameSize) {
            // サイズ不一致時: 64-bit 整数鍵を用いたインデックスマッピング（ブロック層・インライン層の複数スライスに対応）
            java.util.HashMap<Long, Integer> oldKeyMap = new java.util.HashMap<>(oldN * 2);
            for (int i = 0; i < oldN; i++) {
                DisplayItem it = backend.prevItems.get(i);
                long k = it.key();
                if (k != 0L) oldKeyMap.putIfAbsent(k, i);
            }
            boolean[] oldUsed = new boolean[oldN];
            oldIndexOf = new int[n];
            int matchedCount = 0;
            for (int i = 0; i < n; i++) {
                DisplayItem it = items.get(i);
                long k = it.key();
                Integer prevIdx = (k != 0L) ? oldKeyMap.get(k) : null;
                if (prevIdx != null && !oldUsed[prevIdx] && backend.prevItems.get(prevIdx).type() == it.type()) {
                    oldIndexOf[i] = prevIdx;
                    oldUsed[prevIdx] = true;
                    matchedCount++;
                } else {
                    oldIndexOf[i] = -1;
                }
            }
            if (matchedCount < Math.min(oldN, n) / 2) {
                resetCurExtents(n);
                return false;
            }
            for (int i = 0; i < n; i++) {
                DisplayItem cur = items.get(i);
                if (volatileItem(cur) || groupItem(cur)) {
                    resetCurExtents(n);
                    return false;
                }
            }
            for (int i = 0; i < oldN; i++) {
                DisplayItem old = backend.prevItems.get(i);
                if (volatileItem(old) || groupItem(old)) {
                    resetCurExtents(n);
                    return false;
                }
            }

            backend.damage.reset(backend.raster.width(), backend.raster.height());
            for (int i = 0; i < oldN; i++) {
                if (!oldUsed[i]) addExtent(backend.prevExtent, i, backend.damage);
            }
            int[] newPrevExtent = new int[n * 4];
            int[] newPrevClip = new int[n * 4];
            for (int i = 0; i < n; i++) {
                int oldI = oldIndexOf[i];
                if (oldI >= 0) {
                    copyExtent(backend.prevExtent, newPrevExtent, oldI, i);
                    copyClip(backend.prevClip, newPrevClip, oldI, i);
                }
            }
            backend.prevExtent = newPrevExtent;
            backend.prevClip = newPrevClip;
        } else {
            for (int i = 0; i < n; i++) {
                DisplayItem cur = items.get(i), old = backend.prevItems.get(i);
                // 内容が項目の同一性に現れない種類（画像・入力欄・キャンバス等）は、abort せず毎フレーム「変わった」として扱う（下の itemChanged）
                if (!ItemDiff.sameKeyAndType(old, cur) || groupItem(cur)) {
                    backend.fullWhy[!ItemDiff.sameKeyAndType(old, cur) ? 7 : ItemDiff.hasFxLayer(cur) ? 9 : 10]++;
                    resetCurExtents(n);
                    return false;
                }
                if (ItemDiff.changed(old, cur, seen) && mayAnimate(cur)) {
                    // 差し替わった項目は状態が消えているので、目標との突き合わせから確かめる
                    byte act = backend.driver.activityAt(cur, nowMs);
                    if (act != PaintSceneDriver.ACT_IDLE) {
                        backend.frameHadActive = true;
                        backend.activity[i] = (byte) Math.max(backend.activity[i], act);
                        if (act == PaintSceneDriver.ACT_OPACITY) {
                            backend.frameHadGeometryActive = true;
                            backend.fullWhy[6]++;
                            resetCurExtents(n);
                            return false;
                        }
                    }
                }
            }
            backend.damage.reset(backend.raster.width(), backend.raster.height());
        }

        ensureArrays(n);

        // 変わった項目と、姿勢が変わる「動いた節」の子孫
        for (int i = 0; i < n; i++) {
            DisplayItem cur = items.get(i);
            int p = backend.driver.parentIndex(i);
            boolean parentMoved = p >= 0 && backend.movedBuf[p];
            boolean itemChanged;
            if (!sameSize) {
                int oldI = oldIndexOf[i];
                itemChanged = (oldI < 0) || ItemDiff.changed(backend.prevItems.get(oldI), cur, seen);
            } else {
                DisplayItem old = backend.prevItems.get(i);
                // 塗りだけが動く遷移の途中の項目と、前のフレームで動いていた項目（遷移が終わって最終色へ変わる）は塗り直す
                itemChanged = ItemDiff.changed(old, cur, seen) || volatileItem(cur) || backend.activity[i] != 0 || backend.prevActivity[i] != 0;
            }
            boolean nodeChanged = itemChanged && cur.type() == ElementType.NODE;
            backend.movedBuf[i] = parentMoved || nodeChanged;
            backend.dirtyBuf[i] = itemChanged || parentMoved;
        }

        // スクロール Blit 再利用の判定
        boolean didScrollBlit = false;
        int scrollDx = 0, scrollDy = 0;
        int scrollContentIdx = -1;
        if (sameSize) {
            for (int i = 0; i < n; i++) {
                DisplayItem cur = items.get(i), old = backend.prevItems.get(i);
                if (ItemDiff.changed(old, cur, seen) && isScrollContent(cur)) {
                    if (ItemDiff.translationChanged(old, cur)) {
                        if (scrollContentIdx == -1) {
                            scrollContentIdx = i;
                        } else {
                            scrollContentIdx = -2;
                            break;
                        }
                    }
                }
            }
            if (scrollContentIdx >= 0) {
                DisplayItem sNode = items.get(scrollContentIdx);
                double[] prevP = backend.prevScrollPoses.get(sNode.key());
                Resolved r = backend.driver.resolveAt(scrollContentIdx);
                if (prevP != null && r != null && r.pose() != null) {
                    Matrix2D pose = r.pose();
                    if (Math.abs(pose.b()) < 1e-5 && Math.abs(pose.c()) < 1e-5) {
                        double ddx = pose.e() - prevP[0];
                        double ddy = pose.f() - prevP[1];
                        int sdx = (int) Math.round(ddx);
                        int sdy = (int) Math.round(ddy);
                        // 純粋な整数ピクセル移動のときのみ scrollBlit を実行（サブピクセル位相差による画素ズレを完全防止）
                        boolean isExactIntegerScroll = Math.abs(ddx - sdx) < 1e-3 && Math.abs(ddy - sdy) < 1e-3;
                        if (isExactIntegerScroll && (sdx != 0 || sdy != 0)) {
                            boolean otherOk = true;
                            for (int i = 0; i < n; i++) {
                                if (i == scrollContentIdx) continue;
                                if (backend.dirtyBuf[i]) {
                                    int anc = backend.driver.parentIndex(i);
                                    while (anc >= 0 && anc != scrollContentIdx) anc = backend.driver.parentIndex(anc);
                                    if (anc != scrollContentIdx) {
                                        DisplayItem it = items.get(i);
                                        int part = DisplayItem.partOrdinalOf(it.key());
                                        boolean isScrollUi = part == DisplayItem.PART_SCROLLBAR_V
                                                || part == DisplayItem.PART_SCROLLBAR_H
                                                || part == DisplayItem.PART_SCROLLBAR_TRACK_V
                                                || part == DisplayItem.PART_SCROLLBAR_TRACK_H
                                                || part == DisplayItem.PART_SCROLLBAR_NODE_V
                                                || part == DisplayItem.PART_SCROLLBAR_NODE_H;
                                        if (!isScrollUi) {
                                            otherOk = false;
                                            break;
                                        }
                                    }
                                }
                            }
                            if (otherOk) {
                                int[] clipBounds = null;
                                for (int i = 0; i < n; i++) {
                                    int anc = backend.driver.parentIndex(i);
                                    while (anc >= 0 && anc != scrollContentIdx) anc = backend.driver.parentIndex(anc);
                                    if (anc == scrollContentIdx) {
                                        Clip c = backend.buildClip(i, items.get(i), backend.driver.animAt(i));
                                        if (c != null && !c.empty()) {
                                            clipBounds = new int[]{c.x0(), c.y0(), c.x1(), c.y1()};
                                            break;
                                        }
                                    }
                                }
                                if (clipBounds != null && clipBounds[2] > clipBounds[0] && clipBounds[3] > clipBounds[1]) {
                                    int cx0 = clipBounds[0], cy0 = clipBounds[1], cx1 = clipBounds[2], cy1 = clipBounds[3];
                                    backend.raster.scrollBlit(cx0, cy0, cx1, cy1, sdx, sdy, backend.clearColor);
                                    didScrollBlit = true;
                                    scrollDx = sdx;
                                    scrollDy = sdy;
                                    if (sdy < 0) {
                                        backend.damage.add(cx0, cy1 + sdy, cx1, cy1);
                                    } else if (sdy > 0) {
                                        backend.damage.add(cx0, cy0, cx1, cy0 + sdy);
                                    }
                                    if (sdx < 0) {
                                        backend.damage.add(cx1 + sdx, cy0, cx1, cy1);
                                    } else if (sdx > 0) {
                                        backend.damage.add(cx0, cy0, cx0 + sdx, cy1);
                                    }
                                    backend.damage.add(Math.max(cx0, cx1 - 20), cy0, cx1, cy1);
                                }
                            }
                        }
                    }
                }
            }
        }

        backend.syncClipGeneration();
        java.util.Arrays.fill(backend.skipBuf, 0, n, true);

        if (didScrollBlit) {
            // スクロール Blit 実行時
            for (int i = 0; i < n; i++) {
                if (!backend.dirtyBuf[i]) continue;
                int anc = backend.driver.parentIndex(i);
                while (anc >= 0 && anc != scrollContentIdx) anc = backend.driver.parentIndex(anc);
                if (anc != scrollContentIdx) {
                    DisplayItem cur = items.get(i);
                    if (cur.type() == ElementType.NODE) continue;
                    Resolved r = backend.driver.resolveAt(i);
                    int[] cb = planClip(i, cur, r, true);
                    estimateBounds(cur, r.pose(), cb, backend.estimateScratch);
                    backend.damage.add(backend.estimateScratch[0], backend.estimateScratch[1], backend.estimateScratch[2], backend.estimateScratch[3]);
                    addExtent(backend.prevExtent, i, backend.damage);
                }
            }
            backend.damage.seal();
            int dx0 = backend.damage.x0(), dy0 = backend.damage.y0(), dx1 = backend.damage.x1(), dy1 = backend.damage.y1();
            if (SoftwareRenderBackend.damageDebug()) {
                System.out.println("FRAME=" + (backend.partialFrames + backend.fullFrames) + " dx0=" + dx0 + " dy0=" + dy0 + " dx1=" + dx1 + " dy1=" + dy1 + " sdy=" + scrollDy);
            }

            boolean[] subtreeDirty = new boolean[n];
            for (int i = 0; i < n; i++) {
                int anc = backend.driver.parentIndex(i);
                while (anc >= 0 && anc != scrollContentIdx) anc = backend.driver.parentIndex(anc);
                boolean isDescendant = anc == scrollContentIdx;

                if (isDescendant) {
                    DisplayItem cur = items.get(i);
                    if (cur.type() == ElementType.NODE) continue;
                    boolean hasOldArea = hasArea(backend.prevExtent, i);
                    if (hasOldArea) {
                        int b = i * 4;
                        backend.prevExtent[b] += scrollDx;
                        backend.prevExtent[b + 1] += scrollDy;
                        backend.prevExtent[b + 2] += scrollDx;
                        backend.prevExtent[b + 3] += scrollDy;
                    }

                    Resolved r = backend.driver.resolveAt(i);
                    int[] cb = planClip(i, cur, r, true);
                    estimateBounds(cur, r.pose(), cb, backend.estimateScratch);
                    boolean hasNewArea = backend.estimateScratch[2] > backend.estimateScratch[0] && backend.estimateScratch[3] > backend.estimateScratch[1];

                    boolean needRepaint;
                    if (!hasOldArea) {
                        needRepaint = hasNewArea;
                    } else {
                        needRepaint = extentIntersects(backend.prevExtent, i, dx0, dy0, dx1, dy1)
                                || (hasNewArea && backend.damage.intersects(backend.estimateScratch[0], backend.estimateScratch[1], backend.estimateScratch[2], backend.estimateScratch[3]));
                    }
                    if (needRepaint) {
                        backend.dirtyBuf[i] = true;
                        int p = backend.driver.parentIndex(i);
                        while (p >= 0 && p != scrollContentIdx) {
                            subtreeDirty[p] = true;
                            p = backend.driver.parentIndex(p);
                        }
                    }
                }
            }

            for (int i = 0; i < n; i++) {
                int anc = backend.driver.parentIndex(i);
                while (anc >= 0 && anc != scrollContentIdx) anc = backend.driver.parentIndex(anc);
                boolean isDescendant = anc == scrollContentIdx;

                if (isDescendant) {
                    DisplayItem cur = items.get(i);
                    if (cur.type() == ElementType.NODE) {
                        backend.skipBuf[i] = true;
                        continue;
                    }
                    int p = backend.driver.parentIndex(i);
                    boolean parentTreeDirty = false;
                    while (p >= 0 && p != scrollContentIdx) {
                        if (subtreeDirty[p]) {
                            parentTreeDirty = true;
                            break;
                        }
                        p = backend.driver.parentIndex(p);
                    }
                    if (backend.dirtyBuf[i] || parentTreeDirty) {
                        backend.skipBuf[i] = false;
                        backend.dirtyBuf[i] = true;
                        clearExtent(backend.curExtent, i);
                        copyClip(backend.prevClip, backend.curClip, i);
                    } else {
                        backend.skipBuf[i] = true;
                        backend.dirtyBuf[i] = false;
                        copyExtent(backend.prevExtent, backend.curExtent, i);
                        copyClip(backend.prevClip, backend.curClip, i);
                    }
                } else {
                    if (backend.dirtyBuf[i]) {
                        backend.skipBuf[i] = false;
                        clearExtent(backend.curExtent, i);
                        copyClip(backend.prevClip, backend.curClip, i);
                    } else {
                        backend.skipBuf[i] = !extentIntersects(backend.prevExtent, i, dx0, dy0, dx1, dy1);
                        if (backend.skipBuf[i]) {
                            copyExtent(backend.prevExtent, backend.curExtent, i);
                            copyClip(backend.prevClip, backend.curClip, i);
                        } else {
                            clearExtent(backend.curExtent, i);
                            copyClip(backend.prevClip, backend.curClip, i);
                        }
                    }
                }
            }
        } else {
            // 通常の差分描画（要素追加・削除・スタイル変更・テキスト変更・小数スクロール）
            for (int i = 0; i < n; i++) {
                if (!backend.dirtyBuf[i]) continue;
                DisplayItem cur = items.get(i);
                if (cur.type() == ElementType.NODE) continue;
                int oldIdx = (oldIndexOf != null) ? oldIndexOf[i] : i;
                boolean changed = oldIdx < 0 || ItemDiff.changed(backend.prevItems.get(oldIdx), cur, seen) || backend.movedBuf[i]
                        || (oldIndexOf == null && (backend.activity[i] != 0 || backend.prevActivity[i] != 0));
                Resolved r = backend.driver.resolveAt(i);
                int[] cb = planClip(i, cur, r, changed);
                estimateBounds(cur, r.pose(), cb, backend.estimateScratch);
                boolean oldHas = hasArea(backend.prevExtent, i);
                boolean newHas = backend.estimateScratch[2] > backend.estimateScratch[0] && backend.estimateScratch[3] > backend.estimateScratch[1];
                backend.skipBuf[i] = !oldHas && !newHas;
                backend.damage.add(backend.estimateScratch[0], backend.estimateScratch[1], backend.estimateScratch[2], backend.estimateScratch[3]);
                addExtent(backend.prevExtent, i, backend.damage);
            }
            backend.damage.seal();
            int dx0 = backend.damage.x0(), dy0 = backend.damage.y0(), dx1 = backend.damage.x1(), dy1 = backend.damage.y1();
            for (int i = 0; i < n; i++) {
                if (!backend.dirtyBuf[i]) backend.skipBuf[i] = !extentIntersects(backend.prevExtent, i, dx0, dy0, dx1, dy1);
                if (backend.skipBuf[i] || !backend.dirtyBuf[i]) {
                    copyExtent(backend.prevExtent, backend.curExtent, i);
                    copyClip(backend.prevClip, backend.curClip, i);
                } else {
                    clearExtent(backend.curExtent, i);
                    copyClip(backend.prevClip, backend.curClip, i);
                }
            }
        }
        return true;
    }

    void commitFrame(List<DisplayItem> items, boolean partial) {
        if (partial) backend.partialFrames++;
        else backend.fullFrames++;
        backend.prevItems = items;
        backend.prevValid = true;
        backend.prevHadActive = backend.frameHadActive;
        backend.prevHadGeometryActive = backend.frameHadGeometryActive;
        byte[] ta = backend.prevActivity;
        backend.prevActivity = backend.activity;
        backend.activity = ta;
        backend.prevClearColor = backend.clearColor;
        backend.prevCullOffscreen = com.nishiyu.enderframelib.core.kernel.Shortcut.SOFT_CULL_OFFSCREEN.on();
        int[] t = backend.prevExtent;
        backend.prevExtent = backend.curExtent;
        backend.curExtent = t;
        t = backend.prevClip;
        backend.prevClip = backend.curClip;
        backend.curClip = t;

        backend.prevScrollPoses.clear();
        for (int i = 0; i < items.size(); i++) {
            DisplayItem it = items.get(i);
            if (isScrollContent(it)) {
                Resolved r = backend.driver.resolveAt(i);
                if (r != null && r.pose() != null) {
                    backend.prevScrollPoses.put(it.key(), new double[]{r.pose().e(), r.pose().f()});
                }
            }
        }
    }

    void ensureArrays(int n) {
        int need = n * 4;
        if (backend.prevExtent.length != need) {
            backend.prevExtent = new int[need];
            backend.prevClip = new int[need];
            backend.prevValid = false; // 添字の意味が変わる
        }
        if (backend.curExtent.length != need) {
            backend.curExtent = new int[need];
            backend.curClip = new int[need];
        }
        if (backend.skipBuf.length != n) {
            backend.skipBuf = new boolean[n];
            backend.dirtyBuf = new boolean[n];
            backend.movedBuf = new boolean[n];
        }
    }

    void resetCurExtents(int n) {
        ensureArrays(n);
        java.util.Arrays.fill(backend.curExtent, 0);
        java.util.Arrays.fill(backend.curClip, 0);
    }

    /** 項目の新しい範囲を、前のフレームのクリップ（変わった項目は新しいクリップ）に収めて見積もる。 */
    int[] planClip(int i, DisplayItem el, Resolved r, boolean changed) {
        int b = i * 4;
        if (!changed && backend.prevClip[b + 2] > backend.prevClip[b] && backend.prevClip[b + 3] > backend.prevClip[b + 1]) {
            backend.planClipScratch[0] = backend.prevClip[b];
            backend.planClipScratch[1] = backend.prevClip[b + 1];
            backend.planClipScratch[2] = backend.prevClip[b + 2];
            backend.planClipScratch[3] = backend.prevClip[b + 3];
            return backend.planClipScratch;
        }
        Clip c = backend.buildClip(i, el, backend.driver.animAt(i));
        if (c.empty()) {
            backend.planClipScratch[0] = backend.planClipScratch[1] = backend.planClipScratch[2] = backend.planClipScratch[3] = 0;
        } else {
            backend.planClipScratch[0] = c.x0();
            backend.planClipScratch[1] = c.y0();
            backend.planClipScratch[2] = c.x1();
            backend.planClipScratch[3] = c.y1();
        }
        return backend.planClipScratch;
    }

    /**
     * 項目が新しく塗りうる範囲（デバイス画素）の見積もり。箱を姿勢で写し、文字・影・枠線のはみ出し分の余白を足して
     * クリップと画面に収める。実際の塗りがこれを超えたら {@link SoftwareRenderBackend#damageAbort} で全面描画へ切り替える。
     */
    void estimateBounds(DisplayItem el, Matrix2D pose, int[] clip, int[] out) {
        if (clip != null && (clip[2] <= clip[0] || clip[3] <= clip[1])) {
            out[0] = out[1] = out[2] = out[3] = 0;
            return;
        }
        float pad = 2f;
        RenderHints hints = el.hints();
        if (hints.shadowBlur() > 0f) pad = Math.max(pad, BoxDecorationPainter.SHADOW_REACH * hints.shadowBlur() + 1f);
        String text = el.text();
        if (text != null && !text.isEmpty()) {
            RenderMeta meta = el.meta();
            float font = meta != null && meta.fontHeight() > 0f ? meta.fontHeight() : el.height();
            pad = Math.max(pad, font + 1f);
        }
        double[] b = SoftwareRenderBackend.deviceBounds(pose, el.x() - pad, el.y() - pad, el.x() + el.width() + pad, el.y() + el.height() + pad, backend.boundsScratch);
        int x0 = (int) Math.floor(b[0]), y0 = (int) Math.floor(b[1]);
        int x1 = (int) Math.ceil(b[2]), y1 = (int) Math.ceil(b[3]);
        if (clip != null) {
            x0 = Math.max(x0, clip[0]);
            y0 = Math.max(y0, clip[1]);
            x1 = Math.min(x1, clip[2]);
            y1 = Math.min(y1, clip[3]);
        }
        x0 = Math.max(0, x0);
        y0 = Math.max(0, y0);
        x1 = Math.min(backend.raster.width(), x1);
        y1 = Math.min(backend.raster.height(), y1);
        if (x1 <= x0 || y1 <= y0) {
            out[0] = out[1] = out[2] = out[3] = 0;
            return;
        }
        out[0] = x0;
        out[1] = y0;
        out[2] = x1;
        out[3] = y1;
    }

    static boolean stableList(List<DisplayItem> list) {
        return PaintSceneDriver.isImmutable(list);
    }

    static boolean volatileItem(DisplayItem el) {
        return switch (el.type()) {
            case IMAGE, VIDEO, CANVAS, SCENE, INPUT, TEXTAREA, SELECT, PROGRESS, RANGE -> true;
            default -> false;
        };
    }

    static boolean groupItem(DisplayItem el) {
        return el.type() == ElementType.NODE && (el.opacity() < 0.999f || ItemDiff.hasFxLayer(el));
    }

    static boolean mayAnimate(DisplayItem el) {
        return el.transitionDuration() > 0 || (el.animDef() != null && !AnimationSpec.NONE.equals(el.animDef()));
    }

    static void addExtent(int[] ext, int i, DamageRegion d) {
        int b = i * 4;
        if (ext[b + 2] > ext[b] && ext[b + 3] > ext[b + 1]) d.add(ext[b], ext[b + 1], ext[b + 2], ext[b + 3]);
    }

    static boolean hasArea(int[] ext, int i) {
        int b = i * 4;
        return ext[b + 2] > ext[b] && ext[b + 3] > ext[b + 1];
    }

    static boolean extentIntersects(int[] ext, int i, int x0, int y0, int x1, int y1) {
        int b = i * 4;
        return ext[b + 2] > ext[b] && ext[b + 3] > ext[b + 1]
                && ext[b + 2] > x0 && ext[b] < x1 && ext[b + 3] > y0 && ext[b + 1] < y1;
    }

    static void copyExtent(int[] from, int[] to, int i) {
        copyExtent(from, to, i, i);
    }

    static void copyExtent(int[] from, int[] to, int srcIdx, int dstIdx) {
        int sb = srcIdx * 4, db = dstIdx * 4;
        to[db] = from[sb];
        to[db + 1] = from[sb + 1];
        to[db + 2] = from[sb + 2];
        to[db + 3] = from[sb + 3];
    }

    static void clearExtent(int[] ext, int i) {
        int b = i * 4;
        ext[b] = ext[b + 1] = ext[b + 2] = ext[b + 3] = 0;
    }

    static void copyClip(int[] from, int[] to, int i) {
        copyClip(from, to, i, i);
    }

    static void copyClip(int[] from, int[] to, int srcIdx, int dstIdx) {
        int sb = srcIdx * 4, db = dstIdx * 4;
        to[db] = from[sb];
        to[db + 1] = from[sb + 1];
        to[db + 2] = from[sb + 2];
        to[db + 3] = from[sb + 3];
    }
}
