package com.nishiyu.enderframelib.core.paint;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.LayerSpec;
import com.nishiyu.enderframelib.core.paint.model.PaintOffsets;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;
import com.nishiyu.enderframelib.core.raster.Matrix2D;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 全バックエンド共通の入り口: {@link DisplayItem} の列を、描画 API に依存しない形で走査する「シーン解決器」。
 *
 * <p>ここが受け持つのは次だけで、画素の配列（{@code Pixmap}）も GL も持たない:
 * <ul>
 *   <li>{@link CssAnimator} の補間（時刻 {@code nowMs} での値）</li>
 *   <li>親子で引き継ぐ変形（移動・回転・せん断・拡縮）と不透明度の積</li>
 *   <li>不透明度・フィルター・マスク・{@code mix-blend-mode} などを持つ祖先「グループ」の鎖と、その層の開閉順</li>
 *   <li>項目の id → 位置の索引、動いていない項目の姿勢の使い回し</li>
 * </ul>
 *
 * <p>走査は {@link Painter} へ「層をこの鎖に合わせる」「この項目を描く」の 2 つの呼び出しで渡す。ソフトウェア描画・GL・
 * Vulkan・独自エンジンのどれでも、{@code Painter} を実装するだけでアニメーションや CSS 座標変換のロジックを書かずに済む。
 *
 * <p>同じインスタンスで {@link #run} を繰り返すと、補間の状態を保ったまま次のフレームを解決する。スレッドセーフではない。
 */
public final class PaintSceneDriver {


    /** 項目の姿勢（親から引き継いだ変形）と、祖先の不透明度の積。{@code anim} は親の実体を共有する項目では親のもの（項目の値は {@link #animAt}）。 */
    public record Resolved(Matrix2D pose, float opacity, float residual, List<Group> groups, AnimatedValues anim) {}

    /** 不透明度を持つ祖先（グループ）。層にまとめて描き、{@code opacity} を掛けて重ねる。 */
    public record Group(String id, float opacity, int anchor, LayerSpec spec, double[] map, String maskOf, Matrix2D pose) {}


    private static final int BLOCK_SHIFT = 6;
    static final int BLOCK = 1 << BLOCK_SHIFT;

    private final Int2ObjectOpenHashMap<RangeBounds> rangeBounds = new Int2ObjectOpenHashMap<>();
    /** 項目の状態（{@link CssAnimator.AnimState}）が「姿勢は中立」と確かめ済みの印。項目が差し替わる・索引を作り直すと外れる。 */
    private boolean[] stateVerified = new boolean[0];
    /** このフレームの、走査から外すブロックのある RECORD の節 → その範囲の索引（{@link RangeBounds#keep} が走査するブロック）。 */
    private final Int2ObjectOpenHashMap<RangeBounds> windows = new Int2ObjectOpenHashMap<>();
    /** 診断・テスト用: 走査から外した項目の数（累計）。 */
    private long rangeCulledItems;

    /** 診断用: 全ドライバの合計（計測ハーネスが読む）。 */
    public static final java.util.concurrent.atomic.AtomicLong RANGE_CULLED_TOTAL = new java.util.concurrent.atomic.AtomicLong();
    /** 診断用: 記録（RECORD）の範囲を計画した回数と、窓を出せず・条件を満たさず外せなかった回数。 */
    public static final java.util.concurrent.atomic.AtomicLong RANGE_PLANNED = new java.util.concurrent.atomic.AtomicLong(), RANGE_INELIGIBLE = new java.util.concurrent.atomic.AtomicLong();

    public long rangeCulledItems() { return rangeCulledItems; }
    private long posesBuilt;
    /** 作った姿勢（{@code Resolved}）の数の累計（使い回し・親との共有は数えない。テストが確保の代わりに見る）。 */
    public long posesBuilt() { return posesBuilt; }

    private void invalidateRangeBounds() {
        for (RangeBounds rb : rangeBounds.values()) rb.valid = false;
    }

    /** {@link #isNeutral} が、項目の状態（動いている・目標が中立でない）を理由に false を返した（項目の中身のせいではない）。 */
    private boolean stateBlocked;

    private boolean isNeutral(DisplayItem el, int i) {
        if (el.translateX() != 0f || el.translateY() != 0f || el.scaleX() != 1f || el.scaleY() != 1f || el.rotate() != 0f
                || el.opacity() != 1f || el.hints().skew() != 0f) return false;
        if (i < itemOffX.length && (itemOffX[i] != 0f || itemOffY[i] != 0f)) return false;
        if (el.animDef() != null && !com.nishiyu.enderframelib.core.css.model.AnimationSpec.NONE.equals(el.animDef())) return false;
        if (el.type() == ElementType.NODE && ItemDiff.hasFx(el.hints().layer())) return false;
        if (el.type() == ElementType.SCENE) return false;
        if (i < stateVerified.length && !stateVerified[i]) {
            CssAnimator.AnimState st = states[i] != null ? states[i] : CssAnimator.peekState(sessionId, el.id());
            if (st != null && !st.isNeutralGeometryAt(now)) {
                stateBlocked = true; // 時間で変わる理由: 判定を覚えず、次のフレームでもう一度確かめる
                return false;
            }
            stateVerified[i] = true;
        }
        return true;
    }

    /** 節 {@code c} の範囲の縦の範囲の索引。条件を満たさなければ null（範囲を飛ばせない）。 */
    private RangeBounds boundsFor(List<DisplayItem> items, int c) {
        RangeBounds rb = rangeBounds.get(c);
        if (rb == null) {
            rb = new RangeBounds();
            rangeBounds.put(c, rb);
        }
        if (rb.valid) return rb.eligible ? rb : null;
        rb.valid = true;
        rb.eligible = false;
        int from = retain.from[c], to = retain.to[c], n = to - from;
        if (n <= 0) return null;
        for (int k : retain.nodes) { // 入れ子のスクロール節は姿勢が中立でない（スクロール量を持つ）ので対象外
            if (k != c && retain.from[k] >= from && retain.from[k] < to) return null;
        }
        if (rb.bottom.length != n) {
            rb.bottom = new double[n]; rb.top = new double[n]; rb.height = new double[n];
        }
        rb.keyScale = Double.NaN;
        for (int j = 0; j < n; j++) {
            int idx = from + j;
            DisplayItem el = items.get(idx);
            if (!isNeutral(el, idx)) {
                if (stateBlocked) { rb.valid = false; stateBlocked = false; }
                return null;
            }
            boolean node = el.type() == ElementType.NODE;
            double relY = 0;
            if (!node) {
                int p = parentIdx[idx];
                int hops = 0;
                while (p != c) {
                    if (p < from || p >= to || ++hops > n) {
                        if (stateBlocked) { rb.valid = false; stateBlocked = false; }
                        return null;
                    }
                    DisplayItem anc = items.get(p);
                    if (anc.type() == ElementType.NODE && anc.hints().relativeOrigin()) {
                        relY += anc.y();
                    }
                    p = parentIdx[p];
                }
            }
            double absY = relY + el.y();
            rb.bottom[j] = node ? Double.NEGATIVE_INFINITY : absY + (double) el.height();
            rb.top[j] = node ? Double.POSITIVE_INFINITY : absY;
            rb.height[j] = node ? 0 : el.height();
        }
        rb.eligible = true;
        return rb;
    }

    /** RECORD の節 {@code c} について、走査から外せる先頭・末尾の区間を求めて {@link #windows} に入れる（外せなければ入れない）。 */
    private void planWindow(List<DisplayItem> items, int c, Painter painter) {
        if (!com.nishiyu.enderframelib.core.kernel.Shortcut.CULL_RECORDED_RANGES.on()) return;
        RANGE_PLANNED.incrementAndGet();
        RangeBounds rb = boundsFor(items, c);
        if (rb == null) { RANGE_INELIGIBLE.incrementAndGet(); return; }
        Matrix2D m = resolveAt(c).pose();
        if (!m.isFinite() || m.b() != 0 || m.c() != 0 || !(m.d() > 0)) return;
        double[] w = painter.cullWindow(items.get(c), c);
        if (w == null || w.length < 3) return;
        rb.ensureKeys(m.d());
        final double eps = 2; // 丸め・判定の端の余裕（デバイス画素）。外す側を狭める向き
        // 項目の下端（デバイス）= d*下端 + f。下へ外れる条件: d*下端 + f + pad + 高さ < y0。上は d*上端 + f − pad − 高さ > y1
        double thrTop = w[0] - eps - w[2] - m.f();
        double thrBot = w[1] + eps + w[2] - m.f();
        int n = retain.to[c] - retain.from[c];
        int skipped = 0;
        for (int b = 0; b < rb.keep.length; b++) {
            // ブロックの全項目が上へ外れている（鍵の最大が閾値未満）か、全項目が下へ外れている（鍵の最小が閾値超）
            boolean out = rb.blockMax[b] < thrTop || rb.blockMin[b] > thrBot;
            rb.keep[b] = !out;
            if (out) skipped += Math.min(n, (b + 1) * BLOCK) - b * BLOCK;
        }
        if (skipped == 0) return;
        windows.put(c, rb);
        rangeCulledItems += skipped;
        RANGE_CULLED_TOTAL.addAndGet(skipped);
    }


    /** スクロール節（{@code _scrollc}）の id の接尾辞。 */
    public static final String SCROLL_CONTENT_SUFFIX = "_scrollc";

    private static final Long2ObjectOpenHashMap<DisplayItem> EMPTY_BY_KEY = new Long2ObjectOpenHashMap<>(0);

    private final String sessionId;

    private Long2IntOpenHashMap indexOf = new Long2IntOpenHashMap();
    private final List<Integer> fxAnchors = new ArrayList<>();
    private final List<Integer> activeAnchors = new ArrayList<>();
    /** {@link #chainFor} が前へ進める錨の位置。 */
    private int anchorNext;
    private int[] parentIdx = new int[0];
    /** スクロール節ごとの中身の範囲の表（{@link RetainRanges}）。 */
    private final RetainRanges retain = new RetainRanges();
    /** {@link #run} の作業表（使い回し）。 */
    @SuppressWarnings("unchecked")
    private List<Group>[] chainBuf = new List[0];
    private int[] membersBuf = new int[0];
    private RetainPlan[] planBuf = new RetainPlan[0];
    /** 今のフレームで {@link #curRes} が有効な項目の印（フレーム番号と一致するときだけ有効。フレームごとの O(N) の消去を避ける）。 */
    private int[] curStamp = new int[0];
    private int frameStamp;
    private Resolved[] curRes = new Resolved[0], prevRes = new Resolved[0], prevParentRes = new Resolved[0];
    private boolean[] prevStatic = new boolean[0], prevIdle = new boolean[0], visiting = new boolean[0];
    private CssAnimator.AnimState[] states = new CssAnimator.AnimState[0];
    /** 外部 {@link PaintOffsets} から解決した、項目添字ごとの描画上の平行移動。 */
    private float[] itemOffX = new float[0], itemOffY = new float[0];
    private int[] activeOffIdx = new int[8];
    private int activeOffCount;
    private int[] scratchOffIdx = new int[8];
    private float[] scratchOffX = new float[8], scratchOffY = new float[8];
    private PaintOffsets seenOffsets;
    private int seenOffsetsVersion = -1;
    private float seenSnapDpr;
    private int[] diffBuf = new int[64];
    /** 差のある位置の数がこれを超える（項目数に対する割合）なら、差分の更新より作り直しの方が安い。 */
    private static final int PATCH_MAX_DIFF_DIVISOR = 4;
    /** 索引（{@link #indexedById}・{@link #indexOf}・{@link #fxAnchors}）を作った時のリスト。 */
    private List<DisplayItem> indexedItems;
    /** {@link #prepare} で索引とフレーム番号を整え、まだ {@link #run} が消費していない。 */
    private boolean prepared;
    private Long2ObjectOpenHashMap<DisplayItem> indexedById = new Long2ObjectOpenHashMap<>();
    private long generation;

    // 1 フレームの間だけ使う状態
    private long now;
    private Matrix2D frame = Matrix2D.IDENTITY;
    private Long2ObjectOpenHashMap<DisplayItem> byId = EMPTY_BY_KEY;

    public PaintSceneDriver(String sessionId) {
        this.sessionId = sessionId;
    }

    /** 項目の索引を作り直すたび（リストが変わったとき）に増える。項目の位置で引く外部のキャッシュの無効化に使う。 */
    public long generation() { return generation; }

    /** 項目 {@code i} の親（{@link ElementType#NODE} の項目）の位置。無ければ -1。 */
    public int parentIndex(int i) { return i >= 0 && i < parentIdx.length ? parentIdx[i] : -1; }

    /** 項目 {@code el} に、時刻 {@code nowMs} でまだ動いている補間・キーフレームがあるか。静止判定用。 */
    public static boolean itemActiveAt(String sessionId, DisplayItem el, long nowMs) { return CssAnimator.stateFor(sessionId, el, nowMs).isActiveAt(nowMs); }
    /** 走査中の、フレーム全体の変換（CSS px → デバイス画素）。 */
    public Matrix2D frame() { return frame; }
    /** 走査中の、ノードハンドルと部品番号から項目を引く。 */
    public DisplayItem item(int nodeHandle, int partOrdinal) { return byId.get(DisplayItem.keyOf(nodeHandle, partOrdinal)); }
    /** ノードハンドルと部品番号の項目の位置。無ければ -1。 */
    public int indexOf(int nodeHandle, int partOrdinal) { return indexOf.getOrDefault(DisplayItem.keyOf(nodeHandle, partOrdinal), -1); }
    /** 走査中の、id から項目を引く。 */
    public DisplayItem itemById(String id) { return byId.get(DisplayItem.keyOf(id)); }
    /** 走査中の、鍵から項目を引く（文字列のハッシュを使わない）。 */
    public DisplayItem itemByKey(long key) { return byId.get(key); }
    /** id の項目の位置。無ければ -1。 */
    public int indexOf(String id) { return indexOf.getOrDefault(DisplayItem.keyOf(id), -1); }
    /** 鍵の項目の位置。無ければ -1。 */
    public int indexOfKey(long key) { return indexOf.getOrDefault(key, -1); }

    /** {@code items} の索引を（前回と同じなら差分で）整え、このフレームの時刻と変換を設定する。直後に {@link #run} を呼ぶと索引更新を省ける。 */
    public void prepare(List<DisplayItem> items, long nowMs, Matrix2D frame) { prepare(items, PaintOffsets.EMPTY, nowMs, frame); }

    /** 描画上の平行移動 {@code offsets} 付きで索引とこのフレームの準備を行う。 */
    public void prepare(List<DisplayItem> items, PaintOffsets offsets, long nowMs, Matrix2D frame) {
        if (items == null || items.isEmpty()) return;
        this.now = nowMs;
        this.frame = frame;
        CssAnimator.cleanup(sessionId, items);
        if ((items != indexedItems || !isImmutable(items)) && !reconcileIndex(items)) rebuildIndex(items);
        syncOffsets(items, offsets != null ? offsets : PaintOffsets.EMPTY);
        this.byId = indexedById;
        if (++frameStamp == 0) { Arrays.fill(curStamp, 0); frameStamp = 1; }
        prepared = true;
    }

    /** {@code items} を時刻 {@code nowMs} の見た目で先頭から走査し、{@code painter} へ渡す。 */
    public void run(List<DisplayItem> items, long nowMs, Matrix2D frame, Painter painter) { run(items, PaintOffsets.EMPTY, nowMs, frame, painter); }

    /** 描画上の平行移動 {@code offsets} 付きで {@code items} を走査する。 */
    public void run(List<DisplayItem> items, PaintOffsets offsets, long nowMs, Matrix2D frame, Painter painter) {
        if (items == null || items.isEmpty()) return;
        if (!prepared) prepare(items, offsets, nowMs, frame);
        prepared = false;
        this.byId = indexedById;
        try {
            // 項目ごとの祖先のグループ（親子の鎖と、錨の範囲の両方から。外側から内側の順）
            anchorNext = 0;
            activeAnchors.clear();
            // 作業表は使い回す（フレームごとの O(N) の確保をしない）
            int total = items.size();
            if (chainBuf.length < total) { chainBuf = new List[Math.max(total, chainBuf.length * 2)]; membersBuf = new int[chainBuf.length]; }
            else { Arrays.fill(chainBuf, 0, total, null); Arrays.fill(membersBuf, 0, total, 0); }
            List<Group>[] chains = chainBuf;
            // グループに属する描く部品の数。錨の位置（項目の添字）で数える（文字列の表を引かない）
            int[] members = membersBuf;
            Map<String, Integer> extraMembers = null; // 錨がリストに無いグループだけ
            // スクロール層: 保持できる範囲を先に決める（SKIP の範囲は、グループの鎖も姿勢の計算も要らない）
            windows.clear();
            RetainPlan[] plan = planRetained(items, painter);
            int skipTo = 0;
            RangeBounds win = null; // 記録する範囲で、走査から外すブロックを持つもの
            int winFrom = 0, winTo = 0;
            for (int i = 0; i < items.size(); i++) {
                if (i < skipTo) continue;
                int rn = retain.nodeAt.length > i ? retain.nodeAt[i] : -1;
                if (rn >= 0 && plan != null && plan[rn] == RetainPlan.SKIP) {
                    skipTo = retain.to[rn];
                    continue;
                }
                if (rn >= 0) {
                    win = windows.isEmpty() ? null : windows.get(rn);
                    if (win != null) { winFrom = retain.from[rn]; winTo = retain.to[rn]; } else winTo = 0;
                }
                if (win != null && i < winTo) {
                    int blk = (i - winFrom) >> BLOCK_SHIFT;
                    if (!win.keep[blk]) { i = Math.min(winTo, winFrom + ((blk + 1) << BLOCK_SHIFT)) - 1; continue; }
                }
                DisplayItem el = items.get(i);
                if (el.type() != ElementType.NODE && painter.skipItem(i)) { // 描画側が前のフレームの画素をそのまま残す項目: 姿勢も鎖も要らない
                    chains[i] = null;
                    continue;
                }
                List<Group> chain = List.of();
                if (el.type() != ElementType.NODE) {
                    Resolved r = resolveAt(i);
                    if (r.opacity() > 0.005f) {
                        if (!r.groups().isEmpty() || !fxAnchors.isEmpty()) {
                            chain = chainFor(items, i, r);
                            for (int k = 0, cn = chain.size(); k < cn; k++) {
                                Group g = chain.get(k);
                                if (g.maskOf() != null) continue;
                                if (g.anchor() >= 0) members[g.anchor()]++;
                                else {
                                    if (extraMembers == null) extraMembers = new HashMap<>(4);
                                    extraMembers.merge(g.id(), 1, Integer::sum);
                                }
                            }
                        }
                    } else {
                        chain = null;
                    }
                }
                chains[i] = chain;
            }
            win = null;
            winFrom = winTo = 0;
            int[] open = new int[4]; // 記録中の保持層の節の添字（入れ子の順）
            int openN = 0;
            for (int i = 0; i < items.size(); i++) {
                int rn = retain.nodeAt.length > i ? retain.nodeAt[i] : -1;
                if (rn >= 0) {
                    RetainPlan r = plan != null ? plan[rn] : null;
                    if (r == RetainPlan.SKIP) {
                        DisplayItem node = items.get(rn);
                        painter.syncLayers(List.of());
                        painter.drawRetained(node, rn, retain.from[rn], retain.to[rn]);
                        i = retain.to[rn] - 1;
                        continue;
                    }
                    if (r == RetainPlan.RECORD) {
                        DisplayItem node = items.get(rn);
                        painter.syncLayers(List.of());
                        painter.beginRetained(node, rn, retain.from[rn], retain.to[rn]);
                        if (openN == open.length) open = java.util.Arrays.copyOf(open, openN * 2);
                        open[openN++] = rn;
                    }
                    win = windows.isEmpty() ? null : windows.get(rn);
                    if (win != null) { winFrom = retain.from[rn]; winTo = retain.to[rn]; } else winTo = 0;
                }
                boolean culledRun = false;
                if (win != null && i < winTo) {
                    int blk = (i - winFrom) >> BLOCK_SHIFT;
                    if (!win.keep[blk]) { i = Math.min(winTo, winFrom + ((blk + 1) << BLOCK_SHIFT)) - 1; culledRun = true; }
                }
                DisplayItem el = culledRun || painter.skipItem(i) ? null : items.get(i);
                if (el != null && el.type() != ElementType.NODE) {
                    List<Group> chain = chains[i];
                    if (chain != null) {
                        if (chain.isEmpty()) { // 不透明度のグループの外: 層を開かず、そのまま描く
                            painter.syncLayers(List.of());
                            painter.drawItem(el, i, 1f);
                        } else {
                            List<Group> layered = layeredScratch; // 描画側は呼び出しの間だけ読む（Painter#syncLayers）
                            layered.clear();
                            float flat = 1f;
                            for (int gi = 0, gn = chain.size(); gi < gn; gi++) {
                                Group g = chain.get(gi);
                                // フィルター・マスクのあるグループと、描く部品が 2 つ以上のグループだけ層にする
                                // （部品が 1 つなら、部品ごとに不透明度を掛けても同じ。GPU の経路も保てる）
                                if (g.spec() != null || g.maskOf() != null || (g.anchor() >= 0 ? members[g.anchor()] : (extraMembers != null ? extraMembers.getOrDefault(g.id(), 0) : 0)) > 1) layered.add(g);
                                else flat *= g.opacity();
                            }
                            painter.syncLayers(layered);
                            painter.drawItem(el, i, flat);
                        }
                    }
                }
                // 記録中の保持層の範囲の終わり: 層を保存して重ねる。保持できなければ、範囲をふつうに描き直す
                while (openN > 0 && retain.to[open[openN - 1]] == i + 1) {
                    int node = open[--openN];
                    painter.syncLayers(List.of());
                    if (!painter.endRetained(items.get(node), node)) {
                        plan[node] = RetainPlan.NONE;
                        windows.remove(node);
                        i = retain.from[node] - 1;
                        openN = 0;
                        win = null; // 描き直しは通常の走査（外した項目は鎖が無いので描かれない）
                        break;
                    }
                }
            }
            painter.syncLayers(List.of());
        } finally {
            this.byId = EMPTY_BY_KEY;
        }
    }

    /**
     * 保持できるスクロール層の扱いを、描画側へ問い合わせて決める。節の祖先にグループ（不透明度・フィルター）がある・
     * フィルターの錨の範囲に入っているときは問い合わせない（描画側の保持はグループの外でだけ成り立つ）。
     * 外側の範囲の中にある入れ子の節は問い合わせない（外側が NONE のときだけ、内側を問い合わせる）。
     * 保持しない（NONE）場合でも、描画側が cullWindow を持っていれば画面外ブロックを走査から外す。
     */
    private RetainPlan[] planRetained(List<DisplayItem> items, Painter painter) {
        if (retain.nodes.length == 0) return null;
        RetainPlan[] plan = null;
        int coveredUntil = 0;
        for (int c : retain.nodes) {
            int from = retain.from[c], to = retain.to[c];
            if (from < coveredUntil) continue;
            if (insideFxAnchor(items, c)) continue;
            Resolved r = resolveAt(c);
            if (!r.groups().isEmpty() || r.opacity() < 0.999f) continue;
            RetainPlan decision = com.nishiyu.enderframelib.core.kernel.Shortcut.RETAIN_SCROLL_LAYERS.on() ? painter.planRetained(items.get(c), c, from, to) : RetainPlan.NONE;
            if (decision == RetainPlan.SKIP) {
                if (plan == null) {
                    if (planBuf.length < items.size()) planBuf = new RetainPlan[Math.max(items.size(), planBuf.length * 2)];
                    else Arrays.fill(planBuf, 0, items.size(), null);
                    plan = planBuf;
                }
                plan[c] = decision;
                coveredUntil = to;
            } else if (decision == RetainPlan.RECORD) {
                if (plan == null) {
                    if (planBuf.length < items.size()) planBuf = new RetainPlan[Math.max(items.size(), planBuf.length * 2)];
                    else Arrays.fill(planBuf, 0, items.size(), null);
                    plan = planBuf;
                }
                plan[c] = decision;
                planWindow(items, c, painter);
                coveredUntil = to;
            } else {
                // 保持層を作らない通常描画（CPUバックエンド等）でも、画面外ブロックを走査から外す
                planWindow(items, c, painter);
            }
        }
        return plan;
    }

    private boolean insideFxAnchor(List<DisplayItem> items, int index) {
        for (int k = 0; k < fxAnchors.size(); k++) {
            int a = fxAnchors.get(k);
            if (a < index && index < a + items.get(a).hints().layer().span()) return true;
        }
        return false;
    }

    /** 走査中の、いまの表示リスト（不変リストなら、これ自身が中身の同一性の印になる）。 */
    public List<DisplayItem> items() { return indexedItems; }

    /** スクロールの中身の節の移動量を丸める先のデバイス画素比（0 以下なら丸めない）。GPU のスクロール層の保持が使う。 */
    private float scrollSnapDpr;

    /**
     * スクロールの中身の節（{@link #SCROLL_CONTENT_SUFFIX}）の見える移動量を、デバイス画素の整数倍へ丸める（{@code dpr} が 0 以下なら丸めない）。
     * 保持した層をずらして使うとき、小数の位置だと層の境で 1 画素ずれるため。項目や {@link PaintOffsets} は書き換えず {@link #build} の姿勢だけに適用する。
     */
    public void setScrollSnapDpr(float dpr) { this.scrollSnapDpr = dpr; }

    private static boolean isScrollContent(DisplayItem it) {
        return DisplayItem.partOrdinalOf(it.key()) == DisplayItem.PART_SCROLL_CONTENT
                || (it.id() != null && it.id().endsWith(SCROLL_CONTENT_SUFFIX));
    }

    private void invalidatePoseAt(int idx) {
        prevRes[idx] = null;
        prevStatic[idx] = false;
        stateVerified[idx] = false;
    }

    private void syncOffsets(List<DisplayItem> items, PaintOffsets eff) {
        int ver = eff.version();
        boolean snapChanged = Float.compare(scrollSnapDpr, seenSnapDpr) != 0;
        if (eff == seenOffsets && ver == seenOffsetsVersion && !snapChanged) return;
        seenOffsets = eff;
        seenOffsetsVersion = ver;
        seenSnapDpr = scrollSnapDpr;
        if (snapChanged) {
            for (int i = 0, n = items.size(); i < n; i++) if (isScrollContent(items.get(i))) invalidatePoseAt(i);
        }
        int newCount = 0;
        if (!eff.isEmpty()) {
            if (eff.isImmutable()) {
                newCount = collectOffsets(eff, items);
            } else {
                synchronized (eff) {
                    newCount = collectOffsets(eff, items);
                }
            }
        }
        boolean anyChanged = snapChanged;
        for (int a = 0; a < activeOffCount; a++) {
            int idx = activeOffIdx[a];
            boolean kept = false;
            for (int b = 0; b < newCount; b++) { if (scratchOffIdx[b] == idx) { kept = true; break; } }
            if (!kept && idx < itemOffX.length && (itemOffX[idx] != 0f || itemOffY[idx] != 0f)) {
                itemOffX[idx] = 0f; itemOffY[idx] = 0f; invalidatePoseAt(idx); anyChanged = true;
            }
        }
        int outCount = 0;
        if (activeOffIdx.length < newCount) activeOffIdx = new int[Math.max(newCount, activeOffIdx.length * 2)];
        for (int b = 0; b < newCount; b++) {
            int idx = scratchOffIdx[b];
            float nx = scratchOffX[b], ny = scratchOffY[b];
            if (Float.compare(itemOffX[idx], nx) != 0 || Float.compare(itemOffY[idx], ny) != 0) {
                itemOffX[idx] = nx; itemOffY[idx] = ny; invalidatePoseAt(idx); anyChanged = true;
            }
            if (nx != 0f || ny != 0f) activeOffIdx[outCount++] = idx;
        }
        activeOffCount = outCount;
        if (anyChanged) invalidateRangeBounds();
    }

    private int collectOffsets(PaintOffsets eff, List<DisplayItem> items) {
        int esz = eff.size();
        if (scratchOffIdx.length < esz) { scratchOffIdx = new int[esz]; scratchOffX = new float[esz]; scratchOffY = new float[esz]; }
        int count = 0;
        for (int k = 0; k < esz; k++) {
            int idx = indexOf.getOrDefault(eff.keyAt(k), -1);
            if (idx >= 0 && idx < items.size()) {
                DisplayItem it = items.get(idx);
                if (it.type() == ElementType.NODE && eff.idAt(k).equals(it.id())) {
                    scratchOffIdx[count] = idx; scratchOffX[count] = eff.dxAt(k); scratchOffY[count] = eff.dyAt(k); count++;
                }
            }
        }
        return count;
    }

    private void ensureCapacity(int minCap) {
        if (curRes.length >= minCap) return;
        int newCap = Math.max(minCap, curRes.length == 0 ? 16 : curRes.length * 3 / 2);
        curRes = Arrays.copyOf(curRes, newCap);
        curStamp = Arrays.copyOf(curStamp, newCap);
        prevRes = Arrays.copyOf(prevRes, newCap);
        prevParentRes = Arrays.copyOf(prevParentRes, newCap);
        prevStatic = Arrays.copyOf(prevStatic, newCap);
        prevIdle = Arrays.copyOf(prevIdle, newCap);
        visiting = Arrays.copyOf(visiting, newCap);
        states = Arrays.copyOf(states, newCap);
        itemOffX = Arrays.copyOf(itemOffX, newCap);
        itemOffY = Arrays.copyOf(itemOffY, newCap);
        stateVerified = Arrays.copyOf(stateVerified, newCap);
        parentIdx = Arrays.copyOf(parentIdx, newCap);
    }

    /**
     * 前回と同じ大きさで、差のある位置の構造
     * （鍵・親・種類・層）が前回と同じなら、差のある位置だけを更新して true。変わっていない項目の姿勢・補間状態は使い回す。
     * 1 つの要素の変化（文字・ホバー・色）や、全要素に及ぶ継承スタイル変更（inherited）でも、全項目の索引・姿勢・補間状態の表を作り直さずに済む。
     * 構造が違う（項目の増減・鍵や親の変更・層の出入り）ときは false で、呼び出し側が作り直すかシフトする。
     */
    private boolean reconcileIndex(List<DisplayItem> items) {
        List<DisplayItem> old = indexedItems;
        if (old == null || old == items || old.size() != items.size()) return false;
        // 可変のリストは、索引を作ってから中身を書き換えられうる（前回のリストを基準にした差が当てにならない）ので、毎回作り直す。
        // 表示リストは不変のリスト（List.copyOf 系）で届く
        if (!isImmutable(old) || !isImmutable(items)) return false;
        int n = items.size();

        // 第 1 パス: 差分がある位置の構造が一致しているか全件確認（ImmutableItemList 同士は同一ブロックを飛ばす）
        int count = 0;
        if (diffBuf.length < 256) diffBuf = new int[256];
        if (old instanceof ImmutableItemList oi && items instanceof ImmutableItemList ni) {
            int chunks = oi.chunkCount();
            for (int c = 0; c < chunks; c++) {
                DisplayItem[] ob = oi.chunkAt(c);
                DisplayItem[] nb = ni.chunkAt(c);
                if (ob == nb) continue;
                int base = c << ImmutableItemList.CHUNK_SHIFT;
                for (int j = 0, len = ob.length; j < len; j++) {
                    DisplayItem was = ob[j], is = nb[j];
                    if (ItemDiff.unchanged(was, is)) continue;
                    if (!(was.key() == is.key() && ItemDiff.sameStructure(was, is))) return false;
                    if (count < diffBuf.length) diffBuf[count] = base + j;
                    count++;
                }
            }
        } else {
            for (int i = 0; i < n; i++) {
                DisplayItem was = old.get(i), is = items.get(i);
                if (ItemDiff.unchanged(was, is)) continue;
                if (!(was.key() == is.key() && ItemDiff.sameStructure(was, is))) return false;
                if (count < diffBuf.length) diffBuf[count] = i;
                count++;
            }
        }
        if (count == 0) {
            indexedItems = items;
            return true;
        }

        // 第 2 パス: 構造が完全に一致していることが保証されたため、変更位置を更新
        if (count <= diffBuf.length && count <= n / PATCH_MAX_DIFF_DIVISOR) {
            for (int k = 0; k < count; k++) {
                int i = diffBuf[k];
                updateDiffSlot(i, old.get(i), items.get(i));
            }
        } else {
            // 差分が多数（inherited 等）の場合: diffBuf の再確保を避け直接走査で更新
            for (int i = 0; i < n; i++) {
                DisplayItem prevItem = old.get(i);
                DisplayItem is = items.get(i);
                if (ItemDiff.unchanged(prevItem, is)) continue;
                updateDiffSlot(i, prevItem, is);
            }
        }
        invalidateRangeBounds();
        indexedItems = items;
        return true;
    }

    private int resolveParentIndex(DisplayItem item) {
        long pKey = item.hints().parentKey();
        int pidx = pKey != 0L ? indexOf.getOrDefault(pKey, -1) : -1;
        if (pidx < 0) {
            String pid = item.hints().parentId();
            if (pid != null) pidx = indexOf.getOrDefault(DisplayItem.keyOf(pid), -1);
        }
        return pidx;
    }

    /** 作り直しの間だけ前回の索引（鍵 → 位置）を持つ。{@link #indexOf} と入れ替えて使い、毎回の確保を避ける。 */
    private Long2IntOpenHashMap prevIndexOf = new Long2IntOpenHashMap();
    /** 作り直しで、前回の位置から今回の位置へ運ぶ記憶の作業表（作業のたびに {@link #prevRes} などと入れ替える）。 */
    private Resolved[] nextRes = new Resolved[0], nextParentRes = new Resolved[0];
    private boolean[] nextStatic = new boolean[0], nextIdle = new boolean[0], nextVerified = new boolean[0];
    private CssAnimator.AnimState[] nextStates = new CssAnimator.AnimState[0];

    /**
     * 索引（鍵 → 位置・親の添字・節の範囲）を表示リストから作り直す。索引の更新はこの 1 本で、差分が小さいときは {@link #reconcileIndex} が
     * 同じ結果をより安く作る。<b>位置が変わった項目の姿勢・補間状態は、前回の位置から鍵で引いて今回の位置へ運ぶ</b>（構造と中身が前回と同じなら）。
     * ホバーで項目が出入りして間がずれるときも、変わらない項目の姿勢を作り直さない。前回の索引の表は入れ替えて使い回す。
     */
    private void rebuildIndex(List<DisplayItem> items) {
        int n = items.size();
        List<DisplayItem> was = indexedItems;
        // 同じリストを書き換えたもの・可変のリストは、前回の中身が当てにならない（運ぶ記憶を持たない）
        if (was != null && (was == items || !isImmutable(was))) was = null;
        int wasN = was != null ? Math.min(was.size(), prevRes.length) : 0;
        // 前回の索引は退避し、今回の索引は入れ替えた空の表へ作る
        Long2IntOpenHashMap fresh = prevIndexOf;
        prevIndexOf = indexOf;
        indexOf = fresh;
        indexOf.clear();
        indexedById.clear();
        indexedById.ensureCapacity(n * 2);
        fxAnchors.clear();
        for (int i = 0; i < n; i++) {
            DisplayItem it = items.get(i);
            long key = it.key();
            indexedById.put(key, it);
            indexOf.put(key, i);
            if (it.id() != null && !it.id().isEmpty()) {
                long strKey = DisplayItem.keyOf(it.id());
                if (strKey != key) { indexedById.putIfAbsent(strKey, it); indexOf.putIfAbsent(strKey, i); }
            }
            if (it.type() == ElementType.NODE && ItemDiff.hasFx(it.hints().layer())) fxAnchors.add(i);
        }

        ensureCapacity(n);
        ensureNextCapacity(curRes.length);
        for (int i = 0; i < n; i++) {
            DisplayItem it = items.get(i);
            int o = was != null ? prevIndexOf.getOrDefault(it.key(), -1) : -1;
            nextRes[i] = null; nextParentRes[i] = null; nextStatic[i] = false; nextIdle[i] = false; nextStates[i] = null; nextVerified[i] = false;
            if (o < 0 || o >= wasN || itemOffX[o] != 0f || itemOffY[o] != 0f) continue; // 描画上の平行移動を持つ項目の姿勢は運ばない（平行移動の同期が差を見る基準が 0 になるため）
            DisplayItem prevItem = was.get(o);
            if (prevItem == it || ItemDiff.unchanged(prevItem, it)) {
                if (prevItem == it || ItemDiff.sameStructure(prevItem, it)) {
                    nextRes[i] = prevRes[o]; nextParentRes[i] = prevParentRes[o]; nextStatic[i] = prevStatic[o];
                    nextIdle[i] = prevIdle[o]; nextStates[i] = states[o]; nextVerified[i] = stateVerified[o];
                }
            } else if (ItemDiff.sameStructure(prevItem, it) && prevRes[o] != null && prevStatic[o] && ItemDiff.sameGeometryWithoutTransition(prevItem, it)) {
                nextRes[i] = prevRes[o]; nextParentRes[i] = prevParentRes[o]; nextStatic[i] = true;
            }
        }
        Resolved[] tr = prevRes; prevRes = nextRes; nextRes = tr;
        tr = prevParentRes; prevParentRes = nextParentRes; nextParentRes = tr;
        boolean[] tb = prevStatic; prevStatic = nextStatic; nextStatic = tb;
        tb = prevIdle; prevIdle = nextIdle; nextIdle = tb;
        tb = stateVerified; stateVerified = nextVerified; nextVerified = tb;
        CssAnimator.AnimState[] ts = states; states = nextStates; nextStates = ts;
        Arrays.fill(nextRes, null); Arrays.fill(nextParentRes, null); Arrays.fill(nextStates, null);
        prevIndexOf.clear();
        indexedItems = items;

        for (int i = 0; i < n; i++) {
            parentIdx[i] = resolveParentIndex(items.get(i));
        }
        retain.compute(items, parentIdx);
        Arrays.fill(curRes, 0, n, null);
        Arrays.fill(curStamp, 0, n, 0);
        Arrays.fill(visiting, 0, n, false);
        Arrays.fill(itemOffX, 0, n, 0f);
        Arrays.fill(itemOffY, 0, n, 0f);
        activeOffCount = 0;
        seenOffsets = null;
        seenOffsetsVersion = -1;
        seenSnapDpr = Float.NaN;
        invalidateRangeBounds();
        generation++;
    }

    private void ensureNextCapacity(int cap) {
        if (nextRes.length >= cap) return;
        nextRes = new Resolved[cap]; nextParentRes = new Resolved[cap];
        nextStatic = new boolean[cap]; nextIdle = new boolean[cap]; nextVerified = new boolean[cap];
        nextStates = new CssAnimator.AnimState[cap];
    }

    private void updateDiffSlot(int i, DisplayItem prevItem, DisplayItem curItem) {
        long key = curItem.key();
        indexedById.put(key, curItem);
        if (curItem.id() != null && !curItem.id().isEmpty()) {
            long strKey = DisplayItem.keyOf(curItem.id());
            if (strKey != key) indexedById.put(strKey, curItem);
        }
        if (prevRes[i] != null && prevStatic[i] && ItemDiff.sameGeometryWithoutTransition(prevItem, curItem)) {
            states[i] = null;
            prevIdle[i] = false;
        } else {
            states[i] = null;
            prevRes[i] = null;
            prevStatic[i] = false;
            stateVerified[i] = false;
        }
    }

    /** 中身が変わらないと言えるリストか（{@code List.of} / {@code List.copyOf} / {@code Stream.toList} / {@link ImmutableItemList}）。 */
    public static boolean isImmutable(List<?> list) {
        return list instanceof ImmutableItemList || list.getClass().getName().startsWith("java.util.ImmutableCollections$");
    }

    private Group groupFor(DisplayItem node, Resolved nr) {
        LayerSpec spec = ItemDiff.hasFx(node.hints().layer()) ? node.hints().layer() : null;
        Matrix2D m = nr.pose();
        return new Group(node.id(), Math.max(0f, nr.anim().opacity()), indexOf.getOrDefault(node.key(), -1), spec,
                new double[]{m.a(), m.b(), m.c(), m.d()}, null, m);
    }

    /** 項目 {@code i} を含むグループの鎖。親子の鎖に、錨の範囲に入るフィルター・マスクの錨を足して並べ、マスクの項目にはマスクの層を挟む。 */
    private List<Group> chainFor(List<DisplayItem> items, int i, Resolved r) {
        // 項目は先頭から順に来るので、錨の一覧を前へ進めながら「今の項目を範囲に含む錨」だけを持つ（全錨を毎回なめない）
        while (anchorNext < fxAnchors.size() && fxAnchors.get(anchorNext) <= i) activeAnchors.add(fxAnchors.get(anchorNext++));
        for (int k = activeAnchors.size() - 1; k >= 0; k--) {
            int a = activeAnchors.get(k);
            if (i >= a + items.get(a).hints().layer().span()) activeAnchors.remove(k);
        }
        List<Group> base = r.groups();
        if (activeAnchors.isEmpty()) {
            boolean plain = true;
            for (int k = 0; k < base.size() && plain; k++) plain = base.get(k).spec() == null;
            if (plain) return base; // フィルター・マスクの層が無ければ、親子の鎖がそのまま答え
        }
        List<Group> chain = new ArrayList<>(base);
        // 添字で回す（毎フレーム、錨の数・鎖の長さぶんの反復子と箱詰めを作らない。B12）
        for (int ai = 0, an = activeAnchors.size(); ai < an; ai++) {
            int a = activeAnchors.get(ai);
            DisplayItem node = items.get(a);
            boolean present = false;
            for (int gi = 0, gn = chain.size(); gi < gn; gi++) present |= chain.get(gi).id().equals(node.id());
            if (!present) chain.add(groupFor(node, resolveAt(a)));
        }
        chain.sort((x, y) -> Integer.compare(x.anchor(), y.anchor())); // 捕まえない式は 1 つの実体で済む
        List<Group> out = new ArrayList<>(chain.size() + 1);
        for (int gi = 0, gn = chain.size(); gi < gn; gi++) {
            Group g = chain.get(gi);
            out.add(g);
            LayerSpec spec = g.spec();
            if (spec != null && spec.masked() && g.anchor() >= 0 && i >= g.anchor() + spec.maskFrom() && i < g.anchor() + spec.span()) {
                out.add(new Group(g.id() + "#mask", 1f, g.anchor(), null, g.map(), g.id(), g.pose()));
            }
        }
        return out;
    }

    /**
     * 直近に描いた {@code items} のうち、時刻 {@code nowMs} でまだ補間やキーフレームが動いている項目があるか。
     * false なら、項目リストが変わらない限り、次の描画も同じ絵になる（再描画を飛ばせる）。
     */
    public boolean anyActiveAt(List<DisplayItem> items, long nowMs) { return anyActiveAt(sessionId, items, nowMs); }
    /** セッション {@code sessionId} の状態で、{@code items} に時刻 {@code nowMs} でまだ動いているアニメーションがあるか。 */
    public static boolean anyActiveAt(String sessionId, List<DisplayItem> items, long nowMs) {
        for (DisplayItem el : items) if (CssAnimator.stateFor(sessionId, el, nowMs).isActiveAt(nowMs)) return true;
        return false;
    }
    /** 直近に解決した項目の状態だけを走査し、時刻 {@code nowMs} で動いているものがあるか。 */
    public boolean anyCachedActiveAt(long nowMs) {
        int n = indexedItems != null ? indexedItems.size() : states.length;
        for (int i = 0; i < n; i++) {
            CssAnimator.AnimState st = states[i];
            if (st != null && st.isActiveAt(nowMs)) return true;
        }
        return false;
    }
    /** 項目の動きの種類（{@link #fillActivity}・{@link #activityAt}）。 */
    public static final byte ACT_IDLE = 0, ACT_PAINT_ONLY = 1, ACT_GEOMETRY = 2, ACT_OPACITY = 3;
    private static byte activityOf(CssAnimator.AnimState st, long nowMs) { return (st == null || !st.isActiveAt(nowMs)) ? ACT_IDLE : (byte) st.motionKindAt(nowMs); }
    /** 時刻 {@code nowMs} の各項目の動きを {@code out[0..n)} に入れ、最大値を返す。 */
    public byte fillActivity(byte[] out, int n, long nowMs) {
        byte max = ACT_IDLE;
        int lim = Math.min(n, states.length);
        for (int i = 0; i < lim; i++) {
            byte a = activityOf(states[i], nowMs);
            out[i] = a;
            if (a > max) max = a;
        }
        for (int i = lim; i < n; i++) out[i] = ACT_IDLE;
        return max;
    }
    /** 項目 {@code el} の時刻 {@code nowMs} の動き。 */
    public byte activityAt(DisplayItem el, long nowMs) { return activityOf(CssAnimator.stateFor(sessionId, el, nowMs), nowMs); }
    /** 項目 {@code i} がいまのフレームで止まっているか。 */
    public boolean idleAt(int i) { return prevIdle[i]; }
    /** 項目の直近の解決済み姿勢（呼び出しは {@link #resolveAt} の前後に限る）。 */
    public Resolved peekPrevRes(int i) { return i >= 0 && i < prevRes.length ? prevRes[i] : null; }

    /** 項目 {@code i} の補間値（色など）。姿勢を親と共有する項目でも、その項目自身のもの。 */
    public AnimatedValues animAt(int i) {
        Resolved r = resolveAt(i);
        int p = parentIdx[i];
        return p >= 0 && p != i && curStamp[p] == frameStamp && curRes[p] == r ? states[i].getInterpolatedValues(now) : r.anim();
    }

    /** 項目 {@code i} の姿勢。同じフレームの間は 1 回だけ計算し、動いていない項目は前フレームの結果をそのまま使う。 */
    public Resolved resolveAt(int i) {
        Resolved r;
        if (curStamp[i] == frameStamp && (r = curRes[i]) != null) return r;
        DisplayItem el = indexedItems.get(i);
        Resolved parent = null;
        int p = parentIdx[i];
        if (p >= 0 && p != i) {
            if (curStamp[p] == frameStamp && (parent = curRes[p]) != null) {
                // 親が同フレームで解決済みなら再帰を省く
            } else if (!visiting[p]) {
                visiting[i] = true; // 親子が輪になっていても止まる
                parent = resolveAt(p);
                visiting[i] = false;
            }
        }
        CssAnimator.AnimState st = states[i];
        if (st == null) st = states[i] = CssAnimator.stateFor(sessionId, el, now);
        boolean idle = !st.isActiveAt(now);
        // 姿勢と不透明度が動いていない（止まっている・色だけが補間される）なら、前回の姿勢をそのまま使う
        boolean geometryStatic = idle || st.motionKindAt(now) <= 1;
        Resolved prev = prevRes[i];
        AnimatedValues animVals = null;
        if (com.nishiyu.enderframelib.core.kernel.Shortcut.REUSE_IDLE_POSES.on() && prev != null && geometryStatic && prevStatic[i] && prevParentRes[i] == parent
                && ((idle && prevIdle[i]) || prev.anim() == (animVals = st.getInterpolatedValues(now)))) {
            r = prev;
            AnimatedValues av2 = com.nishiyu.enderframelib.core.kernel.Shortcut.VERIFY_MEMO.on() ? (animVals != null ? animVals : st.getInterpolatedValues(now)) : null;
            if (av2 != null) { long b = posesBuilt; PoseVerifier.check(el.id(), prev, parent != null && el.type() != ElementType.NODE && PoseVerifier.identityGeometry(av2) ? parent : build(el, i, parent, av2)); posesBuilt = b; }
        } else {
            AnimatedValues av = animVals != null ? animVals : st.getInterpolatedValues(now);
            r = com.nishiyu.enderframelib.core.kernel.Shortcut.REUSE_IDLE_POSES.on() && parent != null && el.type() != ElementType.NODE && PoseVerifier.identityGeometry(av) ? parent : build(el, i, parent, av);
            prevRes[i] = r;
            prevParentRes[i] = parent;
            prevStatic[i] = geometryStatic; // 「前回も姿勢が動いていなかった」
        }
        prevIdle[i] = idle; // 「前回の補間値は確定済み」
        curRes[i] = r;
        curStamp[i] = frameStamp;
        return r;
    }

    private final double[] poseScratch = new double[6];
    /** {@link #run} が項目ごとに層にするグループを集める作業用の一覧。 */
    private final List<Group> layeredScratch = new ArrayList<>();

    /** {@code m = m · o}（{@link Matrix2D#multiply} と同じ式）。 */
    private static void mulInto(double[] m, double oa, double ob, double oc, double od, double oe, double of) {
        double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5];
        m[0] = a * oa + c * ob;
        m[1] = b * oa + d * ob;
        m[2] = a * oc + c * od;
        m[3] = b * oc + d * od;
        m[4] = a * oe + c * of + e;
        m[5] = b * oe + d * of + f;
    }

    private Resolved build(DisplayItem el, int index, Resolved parent, AnimatedValues anim) {
        posesBuilt++;
        Matrix2D base = parent != null ? parent.pose() : frame;
        float parentOpacity = parent != null ? parent.opacity() : 1f;
        float parentResidual = parent != null ? parent.residual() : 1f;
        List<Group> parentGroups = parent != null ? parent.groups() : List.of();
        Matrix2D pose;
        boolean relOrigin = el.type() == ElementType.NODE && el.hints().relativeOrigin();
        float oxOff = index >= 0 && index < itemOffX.length ? itemOffX[index] : 0f;
        float oyOff = index >= 0 && index < itemOffY.length ? itemOffY[index] : 0f;
        float atx = anim.tx() + oxOff, aty = anim.ty() + oyOff;
        if (scrollSnapDpr > 0f && el.type() == ElementType.NODE && isScrollContent(el)) {
            atx = Math.round(atx * scrollSnapDpr) / scrollSnapDpr;
            aty = Math.round(aty * scrollSnapDpr) / scrollSnapDpr;
        }
        if (anim.rot() == 0f && anim.skew() == 0f && anim.sx() == 1f && anim.sy() == 1f) {
            float tx = atx + (relOrigin ? el.x() : 0f);
            float ty = aty + (relOrigin ? el.y() : 0f);
            if (tx == 0f && ty == 0f) {
                pose = base;
            } else {
                pose = new Matrix2D(base.a(), base.b(), base.c(), base.d(),
                        base.e() + base.a() * tx + base.c() * ty,
                        base.f() + base.b() * tx + base.d() * ty);
            }
        } else {
            RenderMeta meta = el.meta();
            float ox = meta != null ? meta.originX() : 0.5f, oy = meta != null ? meta.originY() : 0.5f;
            double lx = (relOrigin ? 0.0 : el.x()) + el.width() * ox;
            double ly = (relOrigin ? 0.0 : el.y()) + el.height() * oy;
            double px = relOrigin ? el.x() : 0.0;
            double py = relOrigin ? el.y() : 0.0;
            // base · T(原点 + 移動) · R · skewX · S · T(-原点)。途中の行列を作らずに、Matrix2D#multiply と同じ式・同じ順で掛ける
            // （結果はビット単位で同じ。動く項目ごとに毎フレーム 4〜6 個の行列を作っていた。B12）
            double[] m = poseScratch;
            m[0] = base.a(); m[1] = base.b(); m[2] = base.c(); m[3] = base.d(); m[4] = base.e(); m[5] = base.f();
            mulInto(m, 1, 0, 0, 1, px + lx + atx, py + ly + aty);
            if (anim.rot() != 0f) {
                double rad = Math.toRadians(anim.rot()), cos = Math.cos(rad), sin = Math.sin(rad);
                mulInto(m, cos, sin, -sin, cos, 0, 0);
            }
            if (anim.skew() != 0f) mulInto(m, 1, 0, Math.tan(Math.toRadians(anim.skew())), 1, 0, 0); // skewX（回転の後・拡縮の前）
            if (anim.sx() != 1f || anim.sy() != 1f) mulInto(m, anim.sx(), 0, 0, anim.sy(), 0, 0);
            mulInto(m, 1, 0, 0, 1, -lx, -ly);
            pose = new Matrix2D(m[0], m[1], m[2], m[3], m[4], m[5]);
        }
        boolean fx = el.type() == ElementType.NODE && ItemDiff.hasFx(el.hints().layer());
        boolean group = el.type() == ElementType.NODE && (anim.opacity() < 0.999f || fx);
        List<Group> groups = parentGroups;
        if (group) {
            groups = new ArrayList<>(parentGroups.size() + 1);
            groups.addAll(parentGroups);
            groups.add(new Group(el.id(), Math.max(0f, anim.opacity()), index, fx ? el.hints().layer() : null,
                    new double[]{pose.a(), pose.b(), pose.c(), pose.d()}, null, pose));
        }
        return new Resolved(pose, parentOpacity * anim.opacity(), parentResidual * (group ? 1f : anim.opacity()), groups, anim);
    }
}
