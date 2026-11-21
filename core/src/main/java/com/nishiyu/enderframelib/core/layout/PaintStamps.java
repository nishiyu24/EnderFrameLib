package com.nishiyu.enderframelib.core.layout;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * 描画の印の確定（ENGINE_REARCHITECTURE_PLAN C7 ②）。レイアウトは、出力を書きうる地点を通るたびに箱へ「仮の印」を付ける
 * （{@link LayoutBox#touchLayout}・子の増減の {@code touchSubtree}）。印（{@link LayoutBox#touchPaint}）はすぐには進めず、
 * 描画の直前に {@link #settle} が、出力の署名・子の並びが前回の確定と違う箱だけ本当の印にする。測定・配置は結果が同じでも
 * 書きうる地点を通るので、すぐに印を進めると、変わっていない箱まで描画の記憶が外れる（予測テストの取りこぼしの約 8 割）。
 *
 * <p>前提: 描画が読む出力の書き込みは、必ず仮の印の後にある。署名は描画が読む出力の上位集合にしておく（足りないと古い項目が出る。
 * {@code PathPredictionTest}・{@code OwnItemsVerifier} が見張る）。</p>
 */
public final class PaintStamps {
    private PaintStamps() {}

    static final int DIRTY_LAYOUT = 1, DIRTY_STRUCTURE = 2;
    private static final AtomicIntegerFieldUpdater<LayoutBox> DIRTY =
            AtomicIntegerFieldUpdater.newUpdater(LayoutBox.class, "layoutDirty");

    /** レイアウトが出力を書きうる地点を通った仮の印。 */
    public static void touchLayout(LayoutBox box) {
        mark(box, DIRTY_LAYOUT);
    }

    /** 並列のレイアウトから呼ばれる。同じ箱は、確定までに 1 回だけ待ち行列へ入る。 */
    static void mark(LayoutBox box, int bit) {
        for (;;) {
            int cur = box.layoutDirty;
            if ((cur & bit) != 0) return;
            if (DIRTY.compareAndSet(box, cur, cur | bit)) {
                if (cur == 0) ledgerOf(box).add(box);
                return;
            }
        }
    }

    /** 文書（根の箱）ごとの待ち行列。ビューが別スレッドで動いても互いの確定に干渉せず、文書が消えれば行列も消える。 */
    private static ConcurrentLinkedQueue<LayoutBox> ledgerOf(LayoutBox box) {
        LayoutBox root = box;
        while (root.parent != null) root = root.parent;
        ConcurrentLinkedQueue<LayoutBox> q = root.ledger;
        if (q == null) {
            synchronized (root) {
                q = root.ledger;
                if (q == null) root.ledger = q = new ConcurrentLinkedQueue<>();
            }
        }
        return q;
    }

    /** 描画の直前に、描く木の箱 {@code anyBox} から呼ぶ。仮の印の付いた箱のうち、出力が変わった箱だけ本当の印にする。 */
    public static void settle(LayoutBox anyBox) {
        ConcurrentLinkedQueue<LayoutBox> pending = ledgerOf(anyBox);
        LayoutBox b;
        while ((b = pending.poll()) != null) {
            int bits = b.layoutDirty;
            b.layoutDirty = 0;
            boolean touched = false;
            if ((bits & DIRTY_LAYOUT) != 0) {
                long sig = signature(b);
                if (sig != b.layoutSig) {
                    b.layoutSig = sig;
                    b.touchPaint();
                    touched = true;
                }
            }
            if ((bits & DIRTY_STRUCTURE) != 0) { // 子の並びが前回の確定と違うときだけ、祖先の部分木の印へ伝える
                LayoutBox[] was = b.settledKids;
                boolean same = was != null && was.length == b.children.size();
                for (int i = 0; same && i < was.length; i++) same = was[i] == b.children.get(i);
                if (!same) {
                    b.settledKids = b.children.toArray(new LayoutBox[0]);
                    if (!touched) b.bubbleSubtreeStamp(LayoutBox.PAINT_EPOCH.incrementAndGet());
                }
            }
        }
    }

    private static long mix(long h, float v) {
        return h * 1_000_003L + Float.floatToIntBits(v);
    }

    /** 描画が読むレイアウトの出力の署名（寸法・余白・行・断片・文字）。値が同じなら描画は同じになる。 */
    static long signature(LayoutBox b) {
        if (b.isAnonymous) return 1; // 無名の箱は自分の項目を持たない（子の変化は子の印が伝える）
        long h = 17;
        h = mix(h, b.x); h = mix(h, b.y); h = mix(h, b.w); h = mix(h, b.h);
        h = mix(h, b.mT); h = mix(h, b.mR); h = mix(h, b.mB); h = mix(h, b.mL);
        h = mix(h, b.pT); h = mix(h, b.pR); h = mix(h, b.pB); h = mix(h, b.pL);
        h = mix(h, b.bT); h = mix(h, b.bR); h = mix(h, b.bB); h = mix(h, b.bL);
        h = mix(h, b.rowGap); h = mix(h, b.columnGap); h = mix(h, b.textBaseline); h = mix(h, b.lineHeightPx);
        h = mix(h, b.scrollWidth); h = mix(h, b.scrollHeight); h = mix(h, b.inlineContentBottom);
        h = mix(h, b.descendantOverflowRight); h = mix(h, b.descendantOverflowBottom);
        h = mix(h, b.contentOffsetY); h = mix(h, b.legendSpill); h = mix(h, b.contentBlockExtent);
        h = mix(h, b.ownMarginTop); h = mix(h, b.ownMarginBottom);
        h = mix(h, b.overrideWidth); h = mix(h, b.overrideHeight); h = mix(h, b.rememberedInnerW); h = mix(h, b.rememberedInnerH);
        h = h * 31 + java.util.Arrays.hashCode(b.collapsedBorders);
        h = h * 31 + java.util.Arrays.deepHashCode(b.columnRules);
        h = h * 31 + java.util.Arrays.hashCode(b.firstLineBgRect);
        h = h * 31 + (b.sizingMode == null ? 0 : b.sizingMode.ordinal());
        h = h * 31 + b.zIndex;
        h = h * 31 + b.virtualChildIndex;
        h = h * 31 + ((b.measuredClamped ? 1 : 0) | (b.contentAutoSkipped ? 2 : 0) | (b.childrenDeferred ? 4 : 0) | (b.hasAbsoluteChild ? 8 : 0)
                | (b.hasOutsideMarker ? 16 : 0) | (b.isOutsideMarker ? 32 : 0) | (b.isRenderedLegend ? 64 : 0));
        h = h * 31 + b.displayText.hashCode();
        h = h * 31 + (b.node == null || b.node.text == null ? 0 : b.node.text.hashCode());
        h = h * 31 + b.children.size();
        for (int i = 0, n = b.inlineFragments.size(); i < n; i++) {
            LayoutBox.InlineFragment f = b.inlineFragments.get(i);
            h = mix(h, f.x); h = mix(h, f.y); h = mix(h, f.w); h = mix(h, f.h); h = mix(h, f.baseline); h = mix(h, f.descent);
            h = h * 31 + (f.text == null ? 0 : f.text.hashCode());
            h = h * 31 + ((f.startEdge ? 1 : 0) | (f.endEdge ? 2 : 0));
            h = h * 31 + (f.parents == null ? -1 : f.parents.size());
        }
        return h;
    }
}
