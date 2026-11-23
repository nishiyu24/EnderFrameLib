package com.nishiyu.enderframelib.core.layout;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * 描画の印の確定（ENGINE_REARCHITECTURE_PLAN C7 ②・C1p）。レイアウトは、出力を書きうる地点を通るたびに箱へ「仮の印」を付ける
 * （{@link LayoutBox#touchLayout}・子の増減の {@code touchSubtree}）。印（{@link LayoutBox#touchPaint}）はすぐには進めず、
 * 描画の直前に {@link #settle} が、出力の署名・子の並びが前回の確定と違う箱だけ本当の印にする。測定・配置は結果が同じでも
 * 書きうる地点を通るので、すぐに印を進めると、変わっていない箱まで描画の記憶が外れる（予測テストの取りこぼしの約 8 割）。
 *
 * <p>また、子が {@link #DIRTY_MIN_KIDS} 以上ある親には、前回の描画以降に印が付いた子の短いリスト（{@link DirtyKids}）を
 * 記録し、表示リストの再構築で変わらない大多数の兄弟を走査せずに飛ばせるようにする（C1p）。</p>
 *
 * <p>前提: 描画が読む出力の書き込みは、必ず仮の印の後にある。署名は描画が読む出力の上位集合にしておく（足りないと古い項目が出る。
 * {@code PathPredictionTest}・{@code OwnItemsVerifier} が見張る）。</p>
 */
public final class PaintStamps {
    private PaintStamps() {}

    static final int DIRTY_LAYOUT = 1, DIRTY_STRUCTURE = 2;
    /** 変わった子のリストを保持し始める子の数（{@code FragmentPaint} / {@code Fragment} のチャンク・B 木境界と合わせる）。 */
    public static final int DIRTY_MIN_KIDS = 64;
    /** 親 1 つあたり保持する変わった子の最大数（超えたら通常の全子走査へ戻す）。 */
    public static final int DIRTY_CAP = 8;

    private static final AtomicIntegerFieldUpdater<LayoutBox> DIRTY =
            AtomicIntegerFieldUpdater.newUpdater(LayoutBox.class, "layoutDirty");
    private static final AtomicReferenceFieldUpdater<LayoutBox, LayoutBox> DIRTY_HEAD =
            AtomicReferenceFieldUpdater.newUpdater(LayoutBox.class, LayoutBox.class, "dirtyHead");

    /** 兄弟の多い親が保持する、前回描画以降に印が付いた子のエポック付き記録。 */
    static final class DirtyKids {
        final LayoutBox[] items = new LayoutBox[DIRTY_CAP];
        int count;
        long sinceEpoch;
        long lastEpoch;
        volatile long overflowEpoch;
    }

    /** レイアウトが出力を書きうる地点を通った仮の印。 */
    public static void touchLayout(LayoutBox box) {
        mark(box, DIRTY_LAYOUT);
    }

    /** 並列のレイアウトから呼ばれる。同じ箱は、確定までに 1 回だけ待ちリストへ入る。 */
    static void mark(LayoutBox box, int bit) {
        for (;;) {
            int cur = box.layoutDirty;
            if ((cur & bit) != 0) return;
            if (DIRTY.compareAndSet(box, cur, cur | bit)) {
                if (cur == 0) push(box);
                return;
            }
        }
    }

    /** 文書（根の箱）の侵入型ロックフリー単方向リストへ積む（ノード・配列確保なし）。 */
    private static void push(LayoutBox box) {
        LayoutBox root = box;
        while (root.parent != null) root = root.parent;
        for (;;) {
            LayoutBox h = root.dirtyHead;
            box.dirtyNext = h;
            if (DIRTY_HEAD.compareAndSet(root, h, box)) return;
        }
    }

    /** {@code start} と祖先の部分木の印を進め、兄弟の多い親には変わった子を書き留める。 */
    static void bubbleSubtreeStamp(LayoutBox start, long s) {
        long boundary = LayoutBox.renderBoundary;
        LayoutBox prev = null;
        for (LayoutBox a = start; a != null; a = a.parent) {
            // 既にこのフレーム内で印が付いた親でも、別の枝の子は必ず記録してから打ち切る
            if (prev != null && a.children.size() >= DIRTY_MIN_KIDS) {
                recordDirtyKid(a, prev, s, boundary);
            }
            long cur = a.subtreeStamp;
            if (a != start && cur > boundary) return;
            while (cur < s && !LayoutBox.SUBTREE_STAMP.compareAndSet(a, cur, s)) cur = a.subtreeStamp;
            prev = a;
        }
    }

    private static void recordDirtyKid(LayoutBox parent, LayoutBox child, long s, long boundary) {
        DirtyKids fast = parent.dirtyKids;
        if (fast != null && fast.overflowEpoch > boundary) return;
        synchronized (parent) {
            DirtyKids dk = parent.dirtyKids;
            if (dk == null) {
                dk = new DirtyKids();
                dk.sinceEpoch = boundary;
                parent.dirtyKids = dk;
            } else if (dk.lastEpoch <= boundary && dk.overflowEpoch <= boundary) {
                if (dk.count > 0) Arrays.fill(dk.items, 0, dk.count, null);
                dk.count = 0;
                dk.sinceEpoch = boundary;
            }
            if (dk.overflowEpoch > boundary) return;
            for (int i = 0; i < dk.count; i++) {
                if (dk.items[i] == child) {
                    if (s > dk.lastEpoch) dk.lastEpoch = s;
                    return;
                }
            }
            if (dk.count < DIRTY_CAP) {
                dk.items[dk.count++] = child;
                if (s > dk.lastEpoch) dk.lastEpoch = s;
            } else {
                Arrays.fill(dk.items, 0, dk.count, null);
                dk.count = 0;
                dk.overflowEpoch = s;
            }
        }
    }

    /**
     * 記憶の時点 {@code epoch} 以降に印が付いた {@code parent} の子を {@code out} に写し、件数を返す。
     * 溢れ・別描画器によるリセット等で完全な記録が無ければ {@code -1}（呼び出し側は全子を走査する）。
     */
    public static int copyDirtyKidsSince(LayoutBox parent, long epoch, LayoutBox[] out) {
        DirtyKids dk = parent.dirtyKids;
        if (dk == null) return -1;
        synchronized (parent) {
            if (dk.overflowEpoch > epoch || dk.sinceEpoch > epoch) return -1;
            int n = 0;
            for (int i = 0; i < dk.count; i++) {
                LayoutBox c = dk.items[i];
                if (c != null && c.subtreePaintStamp() > epoch) out[n++] = c;
            }
            return n;
        }
    }

    /** 描画の直前に、描く木の箱 {@code anyBox} から呼ぶ。仮の印の付いた箱のうち、出力が変わった箱だけ本当の印にする。 */
    public static void settle(LayoutBox anyBox) {
        LayoutBox root = anyBox;
        while (root.parent != null) root = root.parent;
        LayoutBox head = DIRTY_HEAD.getAndSet(root, null);
        for (LayoutBox b = head; b != null; ) {
            LayoutBox next = b.dirtyNext;
            b.dirtyNext = null;
            int bits = b.layoutDirty;
            b.layoutDirty = 0;
            boolean touched = false;
            if ((bits & DIRTY_LAYOUT) != 0) {
                long shape = shape(b), pos = pos(b);
                if (shape != b.layoutSig) {
                    b.layoutSig = shape;
                    b.layoutPosSig = pos;
                    b.touchPaint();
                    touched = true;
                } else if (pos != b.layoutPosSig) {
                    // 平行移動だけ（親相対の位置だけが違う。K4）: 箱自身の印は進めず、部分木の印（と祖先・変わった子の記録）だけを進める。
                    // 窓の外で空の部分木は、移動後も窓の外なら作り直さず再利用できる（FragmentPaint のインク境界の代理）
                    b.layoutPosSig = pos;
                    b.bubbleSubtreeStamp(LayoutBox.PAINT_EPOCH.incrementAndGet());
                    touched = true;
                }
            }
            if ((bits & DIRTY_STRUCTURE) != 0) { // 子の並びが前回の確定と違うときだけ、祖先の部分木の印へ伝える
                int n = b.children.size();
                LayoutBox[] was = b.settledKids;
                boolean same = was != null && b.settledKidCount == n;
                for (int i = 0; same && i < n; i++) same = was[i] == b.children.get(i);
                if (!same) {
                    if (was == null || was.length < n) {
                        was = b.settledKids = new LayoutBox[Math.max(n, Math.max(16, n + (n >> 3)))];
                    }
                    for (int i = 0; i < n; i++) was[i] = b.children.get(i);
                    if (b.settledKidCount > n) Arrays.fill(was, n, b.settledKidCount, null);
                    b.settledKidCount = n;
                    if (!touched) b.bubbleSubtreeStamp(LayoutBox.PAINT_EPOCH.incrementAndGet());
                }
            }
            b = next;
        }
    }

    /**
     * 署名が含める箱の出力の欄（{@link #signature} の読み口と 1 対 1）。統合宣言表 {@link PaintBoxInputs#ALL} から導出する。
     * {@code PaintReadSetTest} が、描画が実際に読む欄を実測で洗い出して照合し、この一覧の各欄が本当に署名を変えることも確かめる（C7 ③・C3 ④）。
     */
    public static final java.util.Set<String> SIGNATURE_FIELDS = java.util.Set.copyOf(PaintBoxInputs.signatureFields());

    /** 署名には入れず、別の経路で見張る欄と、その理由（{@link PaintBoxInputs#ALL} から導出）。 */
    public static final java.util.Map<String, String> COVERED_ELSEWHERE = java.util.Map.copyOf(PaintBoxInputs.coveredElsewhere());

    /** テスト用: 署名そのもの（形と位置）。 */
    public static long signatureOf(LayoutBox b) {
        return signature(b);
    }

    /** 署名のうち親相対の位置（K4: 位置だけが変わったとき、箱自身の印は進めない）。無名の箱は自分の項目を持たないので位置も見ない。 */
    static long pos(LayoutBox b) {
        return b.isAnonymous ? 1 : mix(mix(17, b.x), b.y);
    }

    /** 描画が読むレイアウトの出力の署名（寸法・余白・行・断片・文字・位置）。値が同じなら描画は同じになる。 */
    static long signature(LayoutBox b) {
        return b.isAnonymous ? 1 : shape(b) * 31 + pos(b);
    }

    private static long mix(long h, float v) {
        return h * 1_000_003L + Float.floatToIntBits(v);
    }

    /** 署名のうち位置（{@link #pos}）を除いた、箱の形・中身の部分。 */
    static long shape(LayoutBox b) {
        if (b.isAnonymous) return 1; // 無名の箱は自分の項目を持たない（子の変化は子の印が伝える）
        long h = 17;
        h = mix(h, b.w); h = mix(h, b.h);
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
        h = h * 31 + (b.node == null ? 0 : b.node.attrVersion()); // 属性（title・data-* など）は描画が直接読む入力。属性だけの変更は再スタイルを通らず、再レイアウトの経路で届く
        h = h * 31 + b.children.size();
        for (int i = 0, n = b.inlineFragments.size(); i < n; i++) {
            LayoutBox.InlineFragment f = b.inlineFragments.get(i);
            h = mix(h, f.x); h = mix(h, f.y); h = mix(h, f.w); h = mix(h, f.h); h = mix(h, f.baseline); h = mix(h, f.descent);
            h = h * 31 + (f.text == null ? 0 : f.text.hashCode());
            h = h * 31 + ((f.startEdge ? 1 : 0) | (f.endEdge ? 2 : 0));
            h = h * 31 + (f.parents == null ? -1 : f.parents.size());
            h = h * 31 + System.identityHashCode(f.firstLineStyle); // 1 行目の断片が持つ ::first-line の実効スタイル（描画が持ち主の指定より先に読む）
            h = h * 31 + System.identityHashCode(f.firstLineOwner);
        }
        return h;
    }
}
