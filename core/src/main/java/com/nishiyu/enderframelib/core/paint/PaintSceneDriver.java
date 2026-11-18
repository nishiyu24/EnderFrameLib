package com.nishiyu.enderframelib.core.paint;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.LayerSpec;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;
import com.nishiyu.enderframelib.core.raster.Matrix2D;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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

    /**
     * 姿勢が動いていない項目の姿勢を、前のフレームのものから使い回すか。false なら毎フレーム作り直す
     * （疑うときの切り分け用。絵は同じでなければならない）。
     */
    public static volatile boolean reuseIdlePoses = true;

    /** 項目の姿勢（親から引き継いだ変形）と、祖先の不透明度の積。 */
    public record Resolved(Matrix2D pose, float opacity, float residual, List<Group> groups, AnimatedValues anim) {}

    /** 不透明度を持つ祖先（グループ）。層にまとめて描き、{@code opacity} を掛けて重ねる。 */
    public record Group(String id, float opacity, int anchor, LayerSpec spec, double[] map, String maskOf, Matrix2D pose) {}

    /** 走査の結果を受け取る描画側。 */
    public interface Painter {
        /**
         * このフレームで項目 {@code index} を描かないと描画側が決めているなら true。ドライバは姿勢・クリップの計算と
         * {@link #drawItem} の呼び出しを省く（画素は描画側の責任で前のフレームのまま残す）。既定は false。
         */
        default boolean skipItem(int index) {
            return false;
        }

        /** 開いている層を、項目の祖先のグループ {@code want}（外側から内側の順）に合わせる。 */
        /** {@code want} は呼び出しの間だけ有効（走査が次の項目で使い回す。持ち続けるなら写す）。 */
        void syncLayers(List<Group> want);

        /**
         * 項目 {@code index} を描く。{@code flat} は層にしなかったグループの不透明度の積
         * （姿勢などは {@link PaintSceneDriver#resolveAt} で引く）。
         */
        void drawItem(DisplayItem el, int index, float flat);

        /**
         * スクロールコンテナの中身（節 {@code node} の子孫の項目 {@code [from, to)}。表示リスト上で連続している）を、保持した層として扱うかを決める。
         * 既定は {@link RetainPlan#NONE}（ふつうに 1 項目ずつ描く）。層を保持できる描画側（GPU）だけが上書きする。
         *
         * <p>呼ぶのは、節の祖先にグループ（不透明度・フィルター）が無いときだけ。{@link RetainPlan#SKIP} を返したら、描画側は
         * 保持済みの層を {@link #drawRetained} で重ねるので、ドライバは範囲の項目を走査しない。{@link RetainPlan#RECORD} なら、
         * 範囲の先頭で {@link #beginRetained}、末尾の後で {@link #endRetained} を呼ぶ（その間の項目は層へ描く）。</p>
         */
        default RetainPlan planRetained(DisplayItem node, int nodeIndex, int from, int to) {
            return RetainPlan.NONE;
        }

        /** {@link RetainPlan#SKIP}: 保持済みの層を今の位置で重ねる。 */
        default void drawRetained(DisplayItem node, int nodeIndex, int from, int to) {}

        /** {@link RetainPlan#RECORD}: 範囲の項目を、保持する層へ描き始める。 */
        default void beginRetained(DisplayItem node, int nodeIndex, int from, int to) {}

        /**
         * 前フレームと項目構成が同一で、変更された位置が {@code indices[0..count)} に限られているとき、
         * 変更分だけを高速に再描画できたら true。できない（または非対応の）ときは false を返して通常走査へフォールバックする。
         */
        default boolean tryPatchItems(List<DisplayItem> items, int[] indices, int count) {
            return false;
        }

        /**
         * {@link RetainPlan#RECORD}: 範囲を描き終えた。層を保存して重ねたら true。保持できなかった（描き方が層に向かない）なら、層を捨てて false を返す。
         * false のとき、ドライバは同じ範囲をふつうに描き直す。
         */
        default boolean endRetained(DisplayItem node, int nodeIndex) {
            return true;
        }

        /**
         * {@link RetainPlan#RECORD} の範囲の項目のうち、描かれないと分かっている縦の区間（デバイス画素）。{@code {y0, y1, pad}} を返す:
         * 項目の箱の下端 + {@code pad} + 項目の高さが {@code y0} より上、または上端 − {@code pad} − 項目の高さが {@code y1} より下なら、
         * その項目は何も描かない（描画側の画面外判定と同じ式）。ドライバはこの区間に確実に入る先頭・末尾の連続区間だけを走査から外す。
         * 既定は null（外さない）。
         */
        default double[] cullWindow(DisplayItem node, int nodeIndex) {
            return null;
        }
    }

    /**
     * 記録する保持層の範囲のうち、描かれないと確実に言える先頭・末尾の区間を走査から外すか。false なら全項目を走査する
     * （疑うときの切り分け用。絵は同じでなければならない）。
     */
    public static volatile boolean cullRecordedRanges = !Boolean.getBoolean("enderframe.paint.nocullrange");

    /**
     * スクロール範囲の項目の縦の範囲の索引。範囲の全項目が「変形なし・不透明度 1・グループなし」（姿勢がスクロール節と同じ）のときだけ作る。
     * {@code prefBottom[j]} は先頭から j 番目までの下端（CSS px）の最大、{@code sufTop[j]} は j 番目から末尾までの上端の最小。どちらも単調なので、
     * 画面外の先頭・末尾の区間は二分探索で正確に求まる（項目が縦に整列している仮定は要らない）。
     */
    private static final class RangeBounds {
        boolean valid;
        boolean eligible;
        /** 項目ごとの下端・上端・高さ（CSS px）。節（NODE）は下端 −∞・上端 +∞（何も描かないので判定に影響しない）。 */
        double[] bottom = new double[0], top = new double[0], height = new double[0];
        /**
         * 倍率 {@code keyScale} でのブロック（{@link #BLOCK} 項目ずつ）の鍵: ブロック内の {@code scale*下端 + 高さ} の最大（全項目が上へ外れているかの判定）、
         * {@code scale*上端 − 高さ} の最小（全項目が下へ外れているかの判定）。表示リストは描画順（背景 → 文字）で並び、縦位置は整列していないので、
         * 範囲全体の二分探索ではなくブロック単位で判定する。
         */
        double[] blockMax = new double[0], blockMin = new double[0];
        double keyScale = Double.NaN;
        /** このフレームで項目を走査してよいブロック（{@link #planWindow} が決める）。 */
        boolean[] keep = new boolean[0];

        void ensureKeys(double scale) {
            if (keyScale == scale) return;
            int n = bottom.length, nb = (n + BLOCK - 1) / BLOCK;
            if (blockMax.length != nb) { blockMax = new double[nb]; blockMin = new double[nb]; keep = new boolean[nb]; }
            for (int b = 0; b < nb; b++) {
                double mx = Double.NEGATIVE_INFINITY, mn = Double.POSITIVE_INFINITY;
                for (int j = b * BLOCK, e = Math.min(n, j + BLOCK); j < e; j++) {
                    if (bottom[j] != Double.NEGATIVE_INFINITY) mx = Math.max(mx, scale * bottom[j] + height[j]);
                    if (top[j] != Double.POSITIVE_INFINITY) mn = Math.min(mn, scale * top[j] - height[j]);
                }
                blockMax[b] = mx;
                blockMin[b] = mn;
            }
            keyScale = scale;
        }
    }

    private static final int BLOCK_SHIFT = 6;
    private static final int BLOCK = 1 << BLOCK_SHIFT;

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

    private void invalidateRangeBounds() {
        for (RangeBounds rb : rangeBounds.values()) rb.valid = false;
    }

    /** {@link #isNeutral} が、項目の状態（動いている・目標が中立でない）を理由に false を返した（項目の中身のせいではない）。 */
    private boolean stateBlocked;

    private boolean isNeutral(DisplayItem el, int i) {
        if (el.translateX() != 0f || el.translateY() != 0f || el.scaleX() != 1f || el.scaleY() != 1f || el.rotate() != 0f
                || el.opacity() != 1f || el.hints().skew() != 0f) return false;
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
        int from = retainFrom[c], to = retainTo[c], n = to - from;
        if (n <= 0) return null;
        for (int k : retainNodes) { // 入れ子のスクロール節は姿勢が中立でない（スクロール量を持つ）ので対象外
            if (k != c && retainFrom[k] >= from && retainFrom[k] < to) return null;
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
        if (!cullRecordedRanges) return;
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
        int n = retainTo[c] - retainFrom[c];
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

    /**
     * スクロール層の保持を描画側へ問い合わせるか。false なら問い合わせず、毎フレーム全部走査する（疑うときの切り分け用。絵は同じでなければならない）。
     */
    public static volatile boolean retainScrollLayers = true;

    /** スクロール節（{@code _scrollc}）の id の接尾辞。 */
    public static final String SCROLL_CONTENT_SUFFIX = "_scrollc";

    private static final Long2ObjectOpenHashMap<DisplayItem> EMPTY_BY_KEY = new Long2ObjectOpenHashMap<>(0);

    private final String sessionId;

    private final Long2IntOpenHashMap indexOf = new Long2IntOpenHashMap();
    private final List<Integer> fxAnchors = new ArrayList<>();
    private final List<Integer> activeAnchors = new ArrayList<>();
    /** {@link #chainFor} が前へ進める錨の位置。 */
    private int anchorNext;
    private int[] parentIdx = new int[0];
    /** スクロール節の添字 → 中身の範囲 {@code [from, to)}（表示リスト上で連続しているものだけ。無ければ from = -1）。 */
    private int[] retainFrom = new int[0], retainTo = new int[0];
    /** {@link #computeRetainRanges} の作業表（使い回す）。 */
    private int[] retainMin = new int[0], retainMax = new int[0], retainCount = new int[0];
    private boolean[] retainCandidate = new boolean[0];
    /** 範囲の先頭の添字 → その範囲のスクロール節の添字（無ければ -1）。 */
    private int[] retainNodeAt = new int[0];
    /** スクロール節の添字（外側の節が先）。無ければ空。 */
    private int[] retainNodes = new int[0];
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
    /**
     * 項目ごとの版。{@link #patchIndexByDiff} が差し替えた位置だけ増える（索引を作り直すと 0 に戻る）。
     * 項目の添字で引く外部のキャッシュ（クリップ）が、世代が同じまま差し替わった項目の古い結果を返さないための印。
     */
    private int[] itemVersion = new int[0];
    /** 切り分け用: false なら、一般のリストの変化は常に索引の作り直しにする（従来の動き）。 */
    public static volatile boolean patchDiffIndex = !Boolean.getBoolean("enderframe.paint.nodiffpatch");
    /** 切り分け用: false なら、サイズの異なるリストの局所挿入・削除に対する増分索引シフト（patchIndexByShift）を使わず作り直す。 */
    public static volatile boolean patchShiftIndex = !Boolean.getBoolean("enderframe.paint.noshiftpatch");
    /** 差のある位置の数がこれを超える（項目数に対する割合）なら、差分の更新より作り直しの方が安い。 */
    private static final int PATCH_MAX_DIFF_DIVISOR = 4;
    private static final int MAX_DIFF_PATCH = 32;
    private int[] diffBuf = new int[64];
    private final int[] lastDiffIndices = new int[MAX_DIFF_PATCH];
    private final int[] lastActiveIndices = new int[MAX_DIFF_PATCH];
    private final int[] patchWorkIndices = new int[MAX_DIFF_PATCH];
    private int lastDiffCount, lastActiveCount;
    private boolean lastDiffEligible;
    private boolean lastRunCompleted;
    private boolean lastRunActiveOverflow;
    private int lastRunListSize;
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

    /** 項目 {@code i} の版（{@link #itemVersion}）。 */
    public int itemVersion(int i) {
        return i < itemVersion.length ? itemVersion[i] : 0;
    }

    /** 項目 {@code i} の親（{@link ElementType#NODE} の項目）の位置。無ければ -1。 */
    public int parentIndex(int i) {
        return i >= 0 && i < parentIdx.length ? parentIdx[i] : -1;
    }

    /**
     * 項目 {@code el} に、時刻 {@code nowMs} でまだ動いている補間・キーフレームがあるか。
     * 描画側が「変化がないフレームを飛ばせるか」を、項目ごとに確かめるために使う。
     */
    public static boolean itemActiveAt(String sessionId, DisplayItem el, long nowMs) {
        return CssAnimator.stateFor(sessionId, el, nowMs).isActiveAt(nowMs);
    }

    /** 走査中の、フレーム全体の変換（CSS px → デバイス画素）。 */
    public Matrix2D frame() { return frame; }

    /** 走査中の、ノードハンドルと部品番号から項目を引く。 */
    public DisplayItem item(int nodeHandle, int partOrdinal) {
        return byId.get(DisplayItem.keyOf(nodeHandle, partOrdinal));
    }

    /** ノードハンドルと部品番号の項目の位置。無ければ -1。 */
    public int indexOf(int nodeHandle, int partOrdinal) {
        return indexOf.getOrDefault(DisplayItem.keyOf(nodeHandle, partOrdinal), -1);
    }

    /** 走査中の、id から項目を引く。 */
    public DisplayItem itemById(String id) {
        return byId.get(DisplayItem.keyOf(id));
    }

    /** 走査中の、鍵から項目を引く（文字列のハッシュを使わない）。 */
    public DisplayItem itemByKey(long key) { return byId.get(key); }

    /** id の項目の位置。無ければ -1。 */
    public int indexOf(String id) {
        return indexOf.getOrDefault(DisplayItem.keyOf(id), -1);
    }

    /** 鍵の項目の位置。無ければ -1。 */
    public int indexOfKey(long key) { return indexOf.getOrDefault(key, -1); }

    /**
     * {@code items} の索引を（前回と同じなら差分で）整え、このフレームの時刻と変換を設定する。
     * 描画側が先に姿勢（{@link #resolveAt}）や親子（{@link #parentIndex}）を読んで、描く範囲を決めたいときに使う。
     * 直後に {@link #run} を呼ぶと、索引の作り直しとフレーム番号の更新を繰り返さない。
     */
    public void prepare(List<DisplayItem> items, long nowMs, Matrix2D frame) {
        if (items == null || items.isEmpty()) return;
        this.now = nowMs;
        this.frame = frame;
        CssAnimator.cleanup(sessionId, items);
        // 項目の並びが前回と同じ（不変リストの同一インスタンス）なら、id の索引と錨の一覧は作り直さない。
        // アニメーションだけが動くフレームでは、リストは変わらず姿勢だけが変わる
        if (items == indexedItems && isImmutable(items)) {
            lastDiffCount = 0;
            lastDiffEligible = lastRunCompleted && !lastRunActiveOverflow && items.size() == lastRunListSize;
        } else {
            lastDiffEligible = false;
            if (!patchIndexByDiff(items)
                    && !patchIndexByShift(items)
                    && !patchIndexByMove(items)) {
                rebuildIndex(items);
            }
        }
        snapScrollContent(items);
        // 次のフレームは、ここまでに書かれたもの（このフレームの発行・スナップ）を「見た」ものとして、後の書き込みだけを変化とみなす
        indexedEpoch = com.nishiyu.enderframelib.core.paint.model.PaintCell.currentEpoch();
        this.byId = indexedById;
        if (++frameStamp == 0) { Arrays.fill(curStamp, 0); frameStamp = 1; }
        prepared = true;
    }

    /**
     * {@code items} を時刻 {@code nowMs} の見た目で先頭から走査し、{@code painter} へ渡す。
     *
     * @param frame フレーム全体の変換（普通は {@code dpr} 倍の拡縮）
     */
    public void run(List<DisplayItem> items, long nowMs, Matrix2D frame, Painter painter) {
        if (items == null || items.isEmpty()) return;
        if (!prepared) prepare(items, nowMs, frame);
        prepared = false;
        this.byId = indexedById;
        boolean prevCompleted = lastRunCompleted;
        lastRunCompleted = false;
        try {
            if (prevCompleted && lastDiffEligible && !lastRunActiveOverflow && items.size() == lastRunListSize) {
                int wc = mergePatchIndices();
                if (wc >= 0 && painter.tryPatchItems(items, patchWorkIndices, wc)) {
                    int nextActive = 0;
                    for (int k = 0; k < wc; k++) {
                        int idx = patchWorkIndices[k];
                        if (!prevIdle[idx] || ancestorActive(idx)) lastActiveIndices[nextActive++] = idx;
                    }
                    lastActiveCount = nextActive;
                    lastRunActiveOverflow = false;
                    lastRunCompleted = true;
                    return;
                }
            }
            int activeCount = 0;
            boolean activeOverflow = false;
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
                int rn = retainNodeAt.length > i ? retainNodeAt[i] : -1;
                if (rn >= 0 && plan != null && plan[rn] == RetainPlan.SKIP) {
                    skipTo = retainTo[rn];
                    continue;
                }
                if (rn >= 0) {
                    win = windows.isEmpty() ? null : windows.get(rn);
                    if (win != null) { winFrom = retainFrom[rn]; winTo = retainTo[rn]; } else winTo = 0;
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
                    if (!prevIdle[i] || ancestorActive(i)) {
                        if (activeCount < MAX_DIFF_PATCH) lastActiveIndices[activeCount++] = i;
                        else activeOverflow = true;
                    }
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
                int rn = retainNodeAt.length > i ? retainNodeAt[i] : -1;
                if (rn >= 0) {
                    RetainPlan r = plan != null ? plan[rn] : null;
                    if (r == RetainPlan.SKIP) {
                        DisplayItem node = items.get(rn);
                        painter.syncLayers(List.of());
                        painter.drawRetained(node, rn, retainFrom[rn], retainTo[rn]);
                        i = retainTo[rn] - 1;
                        continue;
                    }
                    if (r == RetainPlan.RECORD) {
                        DisplayItem node = items.get(rn);
                        painter.syncLayers(List.of());
                        painter.beginRetained(node, rn, retainFrom[rn], retainTo[rn]);
                        if (openN == open.length) open = java.util.Arrays.copyOf(open, openN * 2);
                        open[openN++] = rn;
                    }
                    win = windows.isEmpty() ? null : windows.get(rn);
                    if (win != null) { winFrom = retainFrom[rn]; winTo = retainTo[rn]; } else winTo = 0;
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
                while (openN > 0 && retainTo[open[openN - 1]] == i + 1) {
                    int node = open[--openN];
                    painter.syncLayers(List.of());
                    if (!painter.endRetained(items.get(node), node)) {
                        plan[node] = RetainPlan.NONE;
                        windows.remove(node);
                        i = retainFrom[node] - 1;
                        openN = 0;
                        win = null; // 描き直しは通常の走査（外した項目は鎖が無いので描かれない）
                        break;
                    }
                }
            }
            painter.syncLayers(List.of());
            lastActiveCount = activeCount;
            lastRunActiveOverflow = activeOverflow;
            lastRunListSize = total;
            lastRunCompleted = true;
        } finally {
            this.byId = EMPTY_BY_KEY;
        }
    }

    /** 祖先の節がこのフレームで動いているか（動く節の下の部品も差分パッチの「動いている項目」にする。漏れると GPU が前の姿勢の図形を使い続けた。T2b）。 */
    private boolean ancestorActive(int i) {
        for (int p = parentIdx[i], g = 0; p >= 0 && p != i && g < 64; p = parentIdx[p] == p ? -1 : parentIdx[p], g++) if (curStamp[p] == frameStamp && !prevIdle[p]) return true;
        return false;
    }

    /** 前フレームのアニメーション中項目と今フレームの差分項目を重複なく {@link #patchWorkIndices} へまとめる（上限超過時は -1）。 */
    private int mergePatchIndices() {
        int wc = 0;
        for (int k = 0; k < lastActiveCount; k++) {
            patchWorkIndices[wc++] = lastActiveIndices[k];
        }
        for (int k = 0; k < lastDiffCount; k++) {
            int idx = lastDiffIndices[k];
            boolean dup = false;
            for (int j = 0; j < wc; j++) {
                if (patchWorkIndices[j] == idx) { dup = true; break; }
            }
            if (!dup) {
                if (wc >= MAX_DIFF_PATCH) return -1;
                patchWorkIndices[wc++] = idx;
            }
        }
        return wc;
    }

    /**
     * 保持できるスクロール層の扱いを、描画側へ問い合わせて決める。節の祖先にグループ（不透明度・フィルター）がある・
     * フィルターの錨の範囲に入っているときは問い合わせない（描画側の保持はグループの外でだけ成り立つ）。
     * 外側の範囲の中にある入れ子の節は問い合わせない（外側が NONE のときだけ、内側を問い合わせる）。
     * 保持しない（NONE）場合でも、描画側が cullWindow を持っていれば画面外ブロックを走査から外す。
     */
    private RetainPlan[] planRetained(List<DisplayItem> items, Painter painter) {
        if (retainNodes.length == 0) return null;
        RetainPlan[] plan = null;
        int coveredUntil = 0;
        for (int c : retainNodes) {
            int from = retainFrom[c], to = retainTo[c];
            if (from < coveredUntil) continue;
            if (insideFxAnchor(items, c)) continue;
            Resolved r = resolveAt(c);
            if (!r.groups().isEmpty() || r.opacity() < 0.999f) continue;
            RetainPlan decision = retainScrollLayers ? painter.planRetained(items.get(c), c, from, to) : RetainPlan.NONE;
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

    /** スクロール節ごとの中身の範囲（子孫の添字の最小〜最大。個数と一致して連続しているものだけ）を求める。 */
    private void computeRetainRanges(List<DisplayItem> items) {
        int n = items.size();
        IntArrayListLite nodes = new IntArrayListLite();
        for (int i = 0; i < n; i++) {
            DisplayItem it = items.get(i);
            if (it.type() == ElementType.NODE && it.id().endsWith(SCROLL_CONTENT_SUFFIX)) nodes.add(i);
        }
        // 表は使い回す（索引を整えるたびに項目数ぶんの配列を 6 本作っていた。並びの入れ替えの差分は hover の毎フレーム走る。B12）。
        // 大きさは項目数以上で、[0, n) だけを使う（読む側は添字 i < n だけを引く）
        if (retainFrom.length < n) {
            int cap = Math.max(n, retainFrom.length * 3 / 2);
            retainFrom = new int[cap];
            retainTo = new int[cap];
            retainNodeAt = new int[cap];
            retainMin = new int[cap];
            retainMax = new int[cap];
            retainCount = new int[cap];
            retainCandidate = new boolean[cap];
        }
        java.util.Arrays.fill(retainFrom, -1);
        java.util.Arrays.fill(retainTo, 0);
        java.util.Arrays.fill(retainNodeAt, -1);
        if (nodes.size == 0) {
            if (retainNodes.length != 0) retainNodes = new int[0];
            return;
        }
        boolean[] candidate = retainCandidate;
        java.util.Arrays.fill(candidate, 0, n, false);
        for (int k = 0; k < nodes.size; k++) candidate[nodes.a[k]] = true;
        int[] min = retainMin, max = retainMax, count = retainCount;
        java.util.Arrays.fill(min, 0, n, Integer.MAX_VALUE);
        java.util.Arrays.fill(max, 0, n, -1);
        java.util.Arrays.fill(count, 0, n, 0);
        for (int j = 0; j < n; j++) {
            int p = parentIdx[j];
            for (int guard = 0; p >= 0 && guard < 128; guard++) {
                if (candidate[p]) {
                    if (j < min[p]) min[p] = j;
                    if (j > max[p]) max[p] = j;
                    count[p]++;
                }
                p = parentIdx[p];
            }
        }
        IntArrayListLite valid = new IntArrayListLite();
        for (int k = 0; k < nodes.size; k++) {
            int c = nodes.a[k];
            if (count[c] == 0 || max[c] - min[c] + 1 != count[c]) continue; // 連続していない（範囲を飛ばせない）
            retainFrom[c] = min[c];
            retainTo[c] = max[c] + 1;
            valid.add(c);
        }
        // 範囲の先頭 → 節。同じ先頭を持つ入れ子は外側（範囲が大きい方）を採る
        for (int k = 0; k < valid.size; k++) {
            int c = valid.a[k];
            int at = retainFrom[c];
            int other = retainNodeAt[at];
            if (other < 0 || retainTo[c] > retainTo[other]) retainNodeAt[at] = c;
        }
        // 問い合わせは外側の節が先（範囲の先頭の昇順、同じなら範囲の大きい方）
        // 節は数個なので挿入整列（箱詰めの配列と比較の式を作らない）
        int[] order = retainNodes.length == valid.size ? retainNodes : new int[valid.size];
        for (int k = 0; k < valid.size; k++) {
            int c = valid.a[k], j = k - 1;
            while (j >= 0 && (retainFrom[order[j]] > retainFrom[c]
                    || (retainFrom[order[j]] == retainFrom[c] && retainTo[order[j]] < retainTo[c]))) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = c;
        }
        retainNodes = order;
    }

    /** 小さな int の可変配列（fastutil を引かずに済ませる）。 */
    private static final class IntArrayListLite {
        int[] a = new int[8];
        int size;
        void add(int v) {
            if (size == a.length) a = java.util.Arrays.copyOf(a, size * 2);
            a[size++] = v;
        }
    }

    /** 走査中の、いまの表示リスト。 */
    public List<DisplayItem> items() { return indexedItems; }

    /**
     * 中身の同一性の印。同じ印の間は、描画の上だけの移動量（{@link ImmutableItemList#touched}）を除いて表示リストの中身は同じ。
     * スクロール層の保持が、前に描いた層をそのまま使えるかの判定に使う。
     */
    public Object contentToken() { return indexedBase; }

    /** スクロールの中身の節の移動量を丸める先のデバイス画素比（0 以下なら丸めない）。GPU のスクロール層の保持が使う。 */
    private float scrollSnapDpr;
    private Object snapPositionsToken;
    private int snapPositionsSize = -1;
    private int[] snapPositions = new int[0];

    /**
     * スクロールの中身の節（{@link #SCROLL_CONTENT_SUFFIX}）の見える移動量を、デバイス画素の整数倍へ丸める（{@code dpr} が 0 以下なら丸めない）。
     * 保持した層をずらして使うとき、小数の位置だと層の境で 1 画素ずれるため。丸めは項目のセルへ書く（以前は描画のたびに丸めた項目を作っていた。B9）。
     */
    public void setScrollSnapDpr(float dpr) {
        this.scrollSnapDpr = dpr;
    }

    private void snapScrollContent(List<DisplayItem> items) {
        if (scrollSnapDpr <= 0f) return;
        Object token = ItemDiff.contentTokenOf(items);
        if (snapPositionsToken != token || snapPositionsSize != items.size()) {
            int n = 0;
            int[] buf = snapPositions.length >= 8 ? snapPositions : new int[8];
            for (int i = 0, sz = items.size(); i < sz; i++) {
                DisplayItem it = items.get(i);
                if (it.type() != ElementType.NODE || it.id() == null || !it.id().endsWith(SCROLL_CONTENT_SUFFIX)) continue;
                if (n == buf.length) buf = Arrays.copyOf(buf, n * 2);
                buf[n++] = i;
            }
            snapPositions = Arrays.copyOf(buf, n);
            snapPositionsToken = token;
            snapPositionsSize = items.size();
        }
        float dpr = scrollSnapDpr;
        for (int p : snapPositions) {
            DisplayItem it = items.get(p);
            float rx = Math.round(it.translateX() * dpr) / dpr, ry = Math.round(it.translateY() * dpr) / dpr;
            if (rx == it.translateX() && ry == it.translateY()) continue;
            it.setPaintOffset(rx - it.baseTranslateX(), ry - it.baseTranslateY());
        }
    }

    /** 索引を作ったときの表示リストの中身の印（{@link ItemDiff#contentTokenOf}）。移動量だけが変わったリストは同じ印。 */
    private Object indexedBase;
    /** 索引を最後に更新した時点の塗り・移動量の書き込みの通し番号（これより後に書かれた項目が「変わった」ものになる）。 */
    private int indexedEpoch;

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
        itemVersion = Arrays.copyOf(itemVersion, newCap);
        stateVerified = Arrays.copyOf(stateVerified, newCap);
        parentIdx = Arrays.copyOf(parentIdx, newCap);
    }

    /**
     * 前回と同じ大きさで、差のある位置の構造
     * （鍵・親・種類・層）が前回と同じなら、差のある位置だけを更新して true。変わっていない項目の姿勢・補間状態は使い回す。
     * 1 つの要素の変化（文字・ホバー・色）や、全要素に及ぶ継承スタイル変更（inherited）でも、全項目の索引・姿勢・補間状態の表を作り直さずに済む。
     * 構造が違う（項目の増減・鍵や親の変更・層の出入り）ときは false で、呼び出し側が作り直すかシフトする。
     */
    private boolean patchIndexByDiff(List<DisplayItem> items) {
        List<DisplayItem> old = indexedItems;
        if (!patchDiffIndex || old == null || old == items || old.size() != items.size()) return false;
        // 可変のリストは、索引を作ってから中身を書き換えられうる（前回のリストを基準にした差が当てにならない）ので、毎回作り直す。
        // 表示リストは不変のリスト（List.copyOf 系）で届く
        if (!isImmutable(old) || !isImmutable(items)) return false;
        final int seen = indexedEpoch;
        int n = items.size();

        // 第 1 パス: 差分がある位置の構造が一致しているか全件確認（ImmutableItemList 同士は同一ブロックを飛ばす）
        int count = 0;
        if (diffBuf.length < 256) diffBuf = new int[256];
        if (old instanceof ImmutableItemList oi && items instanceof ImmutableItemList ni) {
            int chunks = oi.chunkCount();
            for (int c = 0; c < chunks; c++) {
                DisplayItem[] ob = oi.chunkAt(c);
                DisplayItem[] nb = ni.chunkAt(c);
                // 同じブロックでも、前に見たあとに項目のセルが書かれていれば（時計の印）項目ごとに調べる
                if (ob == nb && ni.clock().chunkEpoch(c) <= seen) continue;
                int base = c << ImmutableItemList.CHUNK_SHIFT;
                for (int j = 0, len = ob.length; j < len; j++) {
                    DisplayItem was = ob[j], is = nb[j];
                    if (ItemDiff.unchanged(was, is, seen)) continue;
                    if (!(was.key() == is.key() && ItemDiff.sameStructure(was, is))) return false;
                    if (count < diffBuf.length) diffBuf[count] = base + j;
                    count++;
                }
            }
        } else {
            for (int i = 0; i < n; i++) {
                DisplayItem was = old.get(i), is = items.get(i);
                if (ItemDiff.unchanged(was, is, seen)) continue;
                if (!(was.key() == is.key() && ItemDiff.sameStructure(was, is))) return false;
                if (count < diffBuf.length) diffBuf[count] = i;
                count++;
            }
        }
        if (count == 0) {
            indexedItems = items;
            lastDiffCount = 0;
            lastDiffEligible = lastRunCompleted && !lastRunActiveOverflow && n == lastRunListSize;
            return true;
        }

        // 第 2 パス: 構造が完全に一致していることが保証されたため、変更位置を更新
        if (count <= diffBuf.length && count <= n / PATCH_MAX_DIFF_DIVISOR) {
            boolean eligible = lastRunCompleted && !lastRunActiveOverflow && n == lastRunListSize && count <= MAX_DIFF_PATCH;
            for (int k = 0; k < count; k++) {
                int i = diffBuf[k];
                DisplayItem prevItem = old.get(i);
                DisplayItem curItem = items.get(i);
                updateDiffSlot(i, prevItem, curItem);
                if (eligible) {
                    if (curItem.type() == ElementType.NODE) {
                        Object prevClip = prevItem.meta() != null ? prevItem.meta().clipRect() : null;
                        Object curClip = curItem.meta() != null ? curItem.meta().clipRect() : null;
                        if (!ItemDiff.sameGeometryWithoutTransition(prevItem, curItem, indexedEpoch)
                                || ItemDiff.hasFx(prevItem.hints().layer()) || ItemDiff.hasFx(curItem.hints().layer())
                                || !java.util.Objects.equals(prevClip, curClip)) {
                            eligible = false;
                        }
                    }
                    if (eligible) lastDiffIndices[k] = i;
                }
            }
            lastDiffCount = eligible ? count : 0;
            lastDiffEligible = eligible;
        } else {
            lastDiffEligible = false;
            // 差分が多数（inherited 等）の場合: diffBuf の再確保を避け直接走査で更新
            for (int i = 0; i < n; i++) {
                DisplayItem prevItem = old.get(i);
                DisplayItem is = items.get(i);
                if (ItemDiff.unchanged(prevItem, is, seen)) continue;
                updateDiffSlot(i, prevItem, is);
            }
        }
        invalidateRangeBounds();
        indexedItems = items;
        // 中身が変わった印（スクロール層の保持が、前に描いた層をそのまま使えるかの判定に使う）は、作り直しと同じく進める
        indexedBase = ItemDiff.contentTokenOf(items);
        return true;
    }

    private void updateDiffSlot(int i, DisplayItem prevItem, DisplayItem curItem) {
        long key = curItem.key();
        indexedById.put(key, curItem);
        if (curItem.id() != null && !curItem.id().isEmpty()) {
            long strKey = DisplayItem.keyOf(curItem.id());
            if (strKey != key) indexedById.put(strKey, curItem);
        }
        if (prevRes[i] != null && prevStatic[i] && ItemDiff.sameGeometryWithoutTransition(prevItem, curItem, indexedEpoch)) {
            states[i] = null;
            prevIdle[i] = false;
            Object prevClip = prevItem.meta() != null ? prevItem.meta().clipRect() : null;
            Object curClip = curItem.meta() != null ? curItem.meta().clipRect() : null;
            if (!java.util.Objects.equals(prevClip, curClip)) itemVersion[i]++;
        } else {
            states[i] = null;
            prevRes[i] = null;
            prevStatic[i] = false;
            itemVersion[i]++;
            stateVerified[i] = false;
        }
    }

    /**
     * 表示リストのサイズが異なる場合（子の挿入・削除 = CHILD_PATCH）において、
     * 変化が単一の局所領域（head と tail の間）に集約されている場合、
     * 既存の配列を System.arraycopy でシフトし、未変更項目の姿勢・アニメーション状態・キー索引を引き継ぐ。
     */
    private boolean patchIndexByShift(List<DisplayItem> items) {
        List<DisplayItem> old = indexedItems;
        // 同じ長さでも、途中の並びが入れ替わった（スタッキングコンテキストになった箱の項目が層 8 へ移った。B12）ときは、
        // 差分（patchIndexByDiff）は鍵の食い違いで成り立たない。前後の一致する部分を残して、途中だけを入れ替えとして扱う
        if (!patchShiftIndex || old == null || old == items) return false;
        if (!isImmutable(old) || !isImmutable(items)) return false;
        final int seen = indexedEpoch;

        int oldN = old.size();
        int newN = items.size();

        int head = 0;
        while (head < oldN && head < newN && ItemDiff.unchanged(old.get(head), items.get(head), seen)) {
            head++;
        }

        int tail = 0;
        while (tail < (oldN - head) && tail < (newN - head)
                && ItemDiff.unchanged(old.get(oldN - 1 - tail), items.get(newN - 1 - tail), seen)) {
            tail++;
        }

        int delCount = oldN - head - tail;
        int insCount = newN - head - tail;
        int delta = newN - oldN;

        // 変化領域が広すぎる（一致部分が半分未満）場合は全再構築に委譲
        if (head + tail < Math.min(oldN, newN) / 2) return false;

        int oldTailStart = oldN - tail;
        int newTailStart = newN - tail;

        // スクロール節 (_scrollc) が変更領域に含まれる場合は安全のため再構築
        for (int i = head; i < oldTailStart; i++) {
            DisplayItem it = old.get(i);
            if (it.type() == ElementType.NODE && it.id() != null && it.id().endsWith(SCROLL_CONTENT_SUFFIX)) return false;
        }
        for (int i = head; i < newTailStart; i++) {
            DisplayItem it = items.get(i);
            if (it.type() == ElementType.NODE && it.id() != null && it.id().endsWith(SCROLL_CONTENT_SUFFIX)) return false;
        }

        ensureCapacity(newN);

        // 1. 削除された項目の索引除去
        for (int i = head; i < oldTailStart; i++) {
            DisplayItem was = old.get(i);
            long key = was.key();
            indexedById.remove(key);
            indexOf.remove(key);
            if (was.id() != null && !was.id().isEmpty()) {
                long strKey = DisplayItem.keyOf(was.id());
                if (strKey != key) {
                    indexedById.remove(strKey);
                    indexOf.remove(strKey);
                }
            }
        }

        // 2. 末尾区間 [oldTailStart, oldN) の配列シフト
        if (tail > 0 && delta != 0) {
            System.arraycopy(states, oldTailStart, states, newTailStart, tail);
            System.arraycopy(prevRes, oldTailStart, prevRes, newTailStart, tail);
            System.arraycopy(prevParentRes, oldTailStart, prevParentRes, newTailStart, tail);
            System.arraycopy(prevStatic, oldTailStart, prevStatic, newTailStart, tail);
            System.arraycopy(prevIdle, oldTailStart, prevIdle, newTailStart, tail);
            System.arraycopy(visiting, oldTailStart, visiting, newTailStart, tail);
            System.arraycopy(itemVersion, oldTailStart, itemVersion, newTailStart, tail);
            System.arraycopy(stateVerified, oldTailStart, stateVerified, newTailStart, tail);
            System.arraycopy(parentIdx, oldTailStart, parentIdx, newTailStart, tail);
            System.arraycopy(curStamp, oldTailStart, curStamp, newTailStart, tail);
            System.arraycopy(curRes, oldTailStart, curRes, newTailStart, tail);
        }

        // 3. 挿入区間 [head, newTailStart) の初期化
        if (insCount > 0) {
            Arrays.fill(states, head, newTailStart, null);
            Arrays.fill(prevRes, head, newTailStart, null);
            Arrays.fill(prevParentRes, head, newTailStart, null);
            Arrays.fill(curRes, head, newTailStart, null);
            Arrays.fill(curStamp, head, newTailStart, 0);
            Arrays.fill(itemVersion, head, newTailStart, 0);
            Arrays.fill(prevStatic, head, newTailStart, false);
            Arrays.fill(prevIdle, head, newTailStart, false);
            Arrays.fill(visiting, head, newTailStart, false);
            Arrays.fill(stateVerified, head, newTailStart, false);
            Arrays.fill(parentIdx, head, newTailStart, -1);
        }

        // 4. 縮小時に不要になった末尾スロットのクリア
        if (newN < oldN) {
            Arrays.fill(states, newN, oldN, null);
            Arrays.fill(prevRes, newN, oldN, null);
            Arrays.fill(prevParentRes, newN, oldN, null);
            Arrays.fill(curRes, newN, oldN, null);
            Arrays.fill(curStamp, newN, oldN, 0);
            Arrays.fill(itemVersion, newN, oldN, 0);
            Arrays.fill(prevStatic, newN, oldN, false);
            Arrays.fill(prevIdle, newN, oldN, false);
            Arrays.fill(visiting, newN, oldN, false);
            Arrays.fill(stateVerified, newN, oldN, false);
            Arrays.fill(parentIdx, newN, oldN, -1);
        }

        // 5. 末尾区間の項目の indexOf 更新
        if (delta != 0) {
            for (int i = newTailStart; i < newN; i++) {
                DisplayItem it = items.get(i);
                long key = it.key();
                indexOf.put(key, i);
                if (it.id() != null && !it.id().isEmpty()) {
                    long strKey = DisplayItem.keyOf(it.id());
                    if (strKey != key) indexOf.put(strKey, i);
                }
            }
        }

        // 6. 挿入項目の indexedById, indexOf への登録
        for (int i = head; i < newTailStart; i++) {
            DisplayItem it = items.get(i);
            long key = it.key();
            indexedById.put(key, it);
            indexOf.put(key, i);
            if (it.id() != null && !it.id().isEmpty()) {
                long strKey = DisplayItem.keyOf(it.id());
                if (strKey != key) {
                    indexedById.put(strKey, it);
                    indexOf.put(strKey, i);
                }
            }
        }

        // 7. parentIdx の調整（changed 領域内にあった親も新しい索引で引き直す）
        for (int i = 0; i < head; i++) {
            int p = parentIdx[i];
            if (p >= oldTailStart) {
                parentIdx[i] = p + delta;
            } else if (p >= head) {
                parentIdx[i] = resolveParentIndex(items.get(i));
            }
        }
        for (int i = newTailStart; i < newN; i++) {
            int p = parentIdx[i];
            if (p >= oldTailStart) {
                parentIdx[i] = p + delta;
            } else if (p >= head) {
                parentIdx[i] = resolveParentIndex(items.get(i));
            }
        }
        for (int i = head; i < newTailStart; i++) {
            parentIdx[i] = resolveParentIndex(items.get(i));
        }

        // 8. fxAnchors の更新
        boolean hasNewFx = false;
        for (int i = head; i < newTailStart; i++) {
            DisplayItem it = items.get(i);
            if (it.type() == ElementType.NODE && ItemDiff.hasFx(it.hints().layer())) {
                hasNewFx = true;
                break;
            }
        }
        if (delta != 0 || hasNewFx || delCount > 0) { // 消えた区間の錨も外す（長さが同じでも、途中の錨が無くなりうる）
            List<Integer> newFx = new ArrayList<>(fxAnchors.size() + (hasNewFx ? 4 : 0));
            for (int a : fxAnchors) {
                if (a < head) {
                    newFx.add(a);
                } else if (a >= oldTailStart) {
                    newFx.add(a + delta);
                }
            }
            if (hasNewFx) {
                for (int i = head; i < newTailStart; i++) {
                    DisplayItem it = items.get(i);
                    if (it.type() == ElementType.NODE && ItemDiff.hasFx(it.hints().layer())) {
                        newFx.add(i);
                    }
                }
                newFx.sort(Integer::compareTo);
            }
            fxAnchors.clear();
            fxAnchors.addAll(newFx);
        }

        // 9. スクロール節の範囲更新
        computeRetainRanges(items);
        invalidateRangeBounds();

        indexedItems = items;
        indexedBase = ItemDiff.contentTokenOf(items);
        generation++;
        return true;
    }

    // 並びの入れ替えの差分（patchIndexByMove）の作業表。フレームをまたいで使い回す（毎回の確保を避ける）
    private int[] moveSrc = new int[0], moveNewPos = new int[0], moveParent = new int[0], moveVersion = new int[0], moveStamp = new int[0];
    private CssAnimator.AnimState[] moveStates = new CssAnimator.AnimState[0];
    private Resolved[] movePrevRes = new Resolved[0], movePrevParentRes = new Resolved[0], moveCurRes = new Resolved[0];
    private boolean[] movePrevStatic = new boolean[0], movePrevIdle = new boolean[0], moveVerified = new boolean[0];
    /** 診断: 並びの入れ替えの差分で索引を整えた回数。 */
    public static final java.util.concurrent.atomic.LongAdder STAT_MOVE_PATCHES = new java.util.concurrent.atomic.LongAdder();

    /**
     * 項目の実体はほぼ同じで、位置だけが入れ替わった（B12: スタッキングコンテキストになった箱の項目が別の層へ移り、離れた 2 か所が
     * 変わった）ときの索引の更新。各位置の項目が前回のどこにいた同じ実体（中身も変わっていない）かを鍵で引き、その位置の状態
     * （補間の状態・前回の姿勢・版）を写す。前回にいなかった・中身が変わった項目だけ状態を空にする（作り直しと同じ扱い）。
     * 新しい項目が多い（4 分の 1 超）ときは false で、呼び出し側が作り直す。
     */
    private boolean patchIndexByMove(List<DisplayItem> items) {
        List<DisplayItem> old = indexedItems;
        if (!patchShiftIndex || old == null || old == items) return false;
        if (!isImmutable(old) || !isImmutable(items)) return false;
        final int seen = indexedEpoch;
        int oldN = old.size(), n = items.size();
        if (moveSrc.length < n) moveSrc = new int[Math.max(n, moveSrc.length * 3 / 2)];
        if (moveNewPos.length < oldN) moveNewPos = new int[Math.max(oldN, moveNewPos.length * 3 / 2)];
        Arrays.fill(moveNewPos, 0, oldN, -1);
        int fresh = 0, limit = n / 4;
        for (int i = 0; i < n; i++) {
            DisplayItem is = items.get(i);
            int src = -1;
            if (i < oldN && ItemDiff.unchanged(old.get(i), is, seen)) {
                src = i;
            } else {
                int j = indexOf.getOrDefault(is.key(), -1);
                if (j >= 0 && j < oldN && j != i && moveNewPos[j] < 0 && ItemDiff.unchanged(old.get(j), is, seen)) src = j;
            }
            if (src >= 0 && moveNewPos[src] >= 0) src = -1; // 同じ実体が 2 度出る（念のため）
            moveSrc[i] = src;
            if (src >= 0) moveNewPos[src] = i;
            else if (++fresh > limit) return false;
        }
        // スクロール節（_scrollc）が動いた・増えた・消えたなら、スクロール層の範囲ごと作り直す（patchIndexByShift と同じ用心）
        for (int i = 0; i < n; i++) {
            if (moveSrc[i] == i) continue;
            DisplayItem it = items.get(i);
            if (it.type() == ElementType.NODE && it.id() != null && it.id().endsWith(SCROLL_CONTENT_SUFFIX)) return false;
        }
        for (int j = 0; j < oldN; j++) {
            if (moveNewPos[j] == j) continue;
            DisplayItem it = old.get(j);
            if (it.type() == ElementType.NODE && it.id() != null && it.id().endsWith(SCROLL_CONTENT_SUFFIX)) return false;
        }
        int m = Math.max(n, oldN);
        ensureCapacity(m);
        if (moveStates.length < oldN) {
            int cap = Math.max(oldN, moveStates.length * 3 / 2);
            moveStates = new CssAnimator.AnimState[cap];
            movePrevRes = new Resolved[cap];
            movePrevParentRes = new Resolved[cap];
            moveCurRes = new Resolved[cap];
            movePrevStatic = new boolean[cap];
            movePrevIdle = new boolean[cap];
            moveVerified = new boolean[cap];
            moveParent = new int[cap];
            moveVersion = new int[cap];
            moveStamp = new int[cap];
        }
        System.arraycopy(states, 0, moveStates, 0, oldN);
        System.arraycopy(prevRes, 0, movePrevRes, 0, oldN);
        System.arraycopy(prevParentRes, 0, movePrevParentRes, 0, oldN);
        System.arraycopy(curRes, 0, moveCurRes, 0, oldN);
        System.arraycopy(prevStatic, 0, movePrevStatic, 0, oldN);
        System.arraycopy(prevIdle, 0, movePrevIdle, 0, oldN);
        System.arraycopy(stateVerified, 0, moveVerified, 0, oldN);
        System.arraycopy(parentIdx, 0, moveParent, 0, oldN);
        System.arraycopy(itemVersion, 0, moveVersion, 0, oldN);
        System.arraycopy(curStamp, 0, moveStamp, 0, oldN);

        // 索引: 消えた項目の鍵を外してから、位置の変わった項目・新しい項目の鍵を入れる
        for (int j = 0; j < oldN; j++) {
            if (moveNewPos[j] >= 0) continue;
            DisplayItem was = old.get(j);
            long key = was.key();
            if (indexOf.getOrDefault(key, -1) == j) {
                indexOf.remove(key);
                indexedById.remove(key);
            }
            if (was.id() != null && !was.id().isEmpty()) {
                long strKey = DisplayItem.keyOf(was.id());
                if (strKey != key && indexOf.getOrDefault(strKey, -1) == j) {
                    indexOf.remove(strKey);
                    indexedById.remove(strKey);
                }
            }
        }
        for (int i = 0; i < n; i++) {
            int src = moveSrc[i];
            if (src == i) continue;
            DisplayItem it = items.get(i);
            long key = it.key();
            indexedById.put(key, it);
            indexOf.put(key, i);
            if (it.id() != null && !it.id().isEmpty()) {
                long strKey = DisplayItem.keyOf(it.id());
                if (strKey != key) {
                    indexedById.put(strKey, it);
                    indexOf.put(strKey, i);
                }
            }
        }
        // 状態を新しい位置へ写す（前回にいなかった・中身が変わった項目は空）
        for (int i = 0; i < n; i++) {
            int src = moveSrc[i];
            if (src >= 0) {
                states[i] = moveStates[src];
                prevRes[i] = movePrevRes[src];
                prevParentRes[i] = movePrevParentRes[src];
                curRes[i] = moveCurRes[src];
                curStamp[i] = moveStamp[src];
                prevStatic[i] = movePrevStatic[src];
                prevIdle[i] = movePrevIdle[src];
                stateVerified[i] = moveVerified[src];
                itemVersion[i] = src == i ? moveVersion[src] : moveVersion[src] + 1; // 位置の変わった項目の覚え（クリップなど）は使わせない
            } else {
                states[i] = null;
                prevRes[i] = null;
                prevParentRes[i] = null;
                curRes[i] = null;
                curStamp[i] = 0;
                prevStatic[i] = false;
                prevIdle[i] = false;
                stateVerified[i] = false;
                itemVersion[i] = 0;
            }
            visiting[i] = false;
        }
        if (n < oldN) {
            Arrays.fill(states, n, oldN, null);
            Arrays.fill(prevRes, n, oldN, null);
            Arrays.fill(prevParentRes, n, oldN, null);
            Arrays.fill(curRes, n, oldN, null);
            Arrays.fill(curStamp, n, oldN, 0);
            Arrays.fill(itemVersion, n, oldN, 0);
            Arrays.fill(prevStatic, n, oldN, false);
            Arrays.fill(prevIdle, n, oldN, false);
            Arrays.fill(visiting, n, oldN, false);
            Arrays.fill(stateVerified, n, oldN, false);
            Arrays.fill(parentIdx, n, oldN, -1);
        }
        // 親の位置: 親も同じ実体で残っていれば、その新しい位置。そうでなければ鍵で引き直す
        for (int i = 0; i < n; i++) {
            int src = moveSrc[i];
            int p = src >= 0 ? moveParent[src] : -1;
            parentIdx[i] = p >= 0 && p < oldN && moveNewPos[p] >= 0 ? moveNewPos[p] : resolveParentIndex(items.get(i));
        }
        fxAnchors.clear();
        for (int i = 0; i < n; i++) {
            DisplayItem it = items.get(i);
            if (it.type() == ElementType.NODE && ItemDiff.hasFx(it.hints().layer())) fxAnchors.add(i);
        }
        computeRetainRanges(items);
        invalidateRangeBounds();
        indexedItems = items;
        indexedBase = ItemDiff.contentTokenOf(items);
        generation++;
        STAT_MOVE_PATCHES.increment();
        return true;
    }

    private int resolveParentIndex(DisplayItem item) {
        int pidx = -1;
        long pKey = item.hints().parentKey();
        if (pKey != 0L) {
            pidx = indexOf.getOrDefault(pKey, -1);
        }
        if (pidx < 0) {
            String pid = item.hints().parentId();
            if (pid != null) {
                pidx = indexOf.getOrDefault(DisplayItem.keyOf(pid), -1);
            }
        }
        return pidx;
    }

    private void rebuildIndex(List<DisplayItem> items) {
        int n = items.size();
        Long2ObjectOpenHashMap<DisplayItem> map = new Long2ObjectOpenHashMap<>(n * 2);
        for (DisplayItem it : items) {
            map.put(it.key(), it);
            if (it.id() != null && !it.id().isEmpty()) {
                long strKey = DisplayItem.keyOf(it.id());
                if (strKey != it.key()) map.putIfAbsent(strKey, it);
            }
        }
        indexedById = map;
        indexOf.clear();
        fxAnchors.clear();
        for (int i = 0; i < n; i++) {
            DisplayItem it = items.get(i);
            long key = it.key();
            indexOf.put(key, i);
            if (it.id() != null && !it.id().isEmpty()) {
                long strKey = DisplayItem.keyOf(it.id());
                if (strKey != key) indexOf.putIfAbsent(strKey, i);
            }
            if (it.type() == ElementType.NODE && ItemDiff.hasFx(it.hints().layer())) fxAnchors.add(i);
        }
        indexedItems = items;
        indexedBase = ItemDiff.contentTokenOf(items);

        ensureCapacity(n);
        for (int i = 0; i < n; i++) {
            parentIdx[i] = resolveParentIndex(items.get(i));
        }
        computeRetainRanges(items);
        Arrays.fill(curRes, 0, n, null);
        Arrays.fill(curStamp, 0, n, 0);
        Arrays.fill(prevRes, 0, n, null);
        Arrays.fill(prevParentRes, 0, n, null);
        Arrays.fill(prevStatic, 0, n, false);
        Arrays.fill(prevIdle, 0, n, false);
        Arrays.fill(visiting, 0, n, false);
        Arrays.fill(states, 0, n, null);
        Arrays.fill(itemVersion, 0, n, 0);
        Arrays.fill(stateVerified, 0, n, false);
        rangeBounds.clear();
        generation++;
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
    public boolean anyActiveAt(List<DisplayItem> items, long nowMs) {
        return anyActiveAt(sessionId, items, nowMs);
    }

    /**
     * セッション {@code sessionId} の状態で、{@code items} に時刻 {@code nowMs} でまだ動いているアニメーションがあるか。
     * 描画器を持たない呼び出し側（GPU 版）が、CPU 版を介さずに問える。
     */
    public static boolean anyActiveAt(String sessionId, List<DisplayItem> items, long nowMs) {
        for (DisplayItem el : items) {
            if (CssAnimator.stateFor(sessionId, el, nowMs).isActiveAt(nowMs)) return true;
        }
        return false;
    }

    /**
     * 直近に {@link #resolveAt} で解決した項目の状態だけを走査し、時刻 {@code nowMs} でまだ動いているものがあるか。
     * 状態の表を読むだけで id の表を引かない（毎フレームの差分描画の可否判定に使う）。差し替わった項目の状態は
     * 索引の更新で消えているので、その項目は呼び出し側が {@link #itemActiveAt} で別に確かめる。
     */
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

    private static byte activityOf(CssAnimator.AnimState st, long nowMs) {
        if (st == null || !st.isActiveAt(nowMs)) return ACT_IDLE;
        return (byte) st.motionKindAt(nowMs);
    }

    /**
     * 直近に {@link #resolveAt} で解決した項目の状態から、時刻 {@code nowMs} の各項目の動きを {@code out[0..n)} に入れ、
     * 最大値を返す（{@link #anyCachedActiveAt} の項目別版）。{@link #ACT_PAINT_ONLY} は色などの塗りだけが補間される項目で、
     * 姿勢と不透明度は動かない（{@link #ACT_GEOMETRY} は変形が動く、{@link #ACT_OPACITY} は不透明度が動く）。差し替わった項目の状態は索引の更新で消えているので、その項目は {@link #activityAt} で確かめる。
     */
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

    /** 項目 {@code el} の、時刻 {@code nowMs} の動き。状態が無ければ（目標との突き合わせも含めて）作って確かめる。 */
    public byte activityAt(DisplayItem el, long nowMs) {
        return activityOf(CssAnimator.stateFor(sessionId, el, nowMs), nowMs);
    }

    /** 項目 {@code i} の姿勢。同じフレームの間は 1 回だけ計算し、動いていない項目は前フレームの結果をそのまま使う。 */
    /** 項目 {@code i} が、いまのフレームで止まっている（補間値が確定済み）か。{@link #resolveAt} をそのフレームで呼んだあとに読む。 */
    public boolean idleAt(int i) { return prevIdle[i]; }

    /** 項目の直近の解決済み姿勢（呼び出しは {@link #resolveAt} の前後に限る）。 */
    public Resolved peekPrevRes(int i) {
        return i >= 0 && i < prevRes.length ? prevRes[i] : null;
    }

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
        if (reuseIdlePoses && prev != null && geometryStatic && prevStatic[i] && prevParentRes[i] == parent
                && ((idle && prevIdle[i]) || prev.anim() == (animVals = st.getInterpolatedValues(now)))) {
            r = prev;
        } else {
            r = build(el, i, parent, animVals != null ? animVals : st.getInterpolatedValues(now));
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
        Matrix2D base = parent != null ? parent.pose() : frame;
        float parentOpacity = parent != null ? parent.opacity() : 1f;
        float parentResidual = parent != null ? parent.residual() : 1f;
        List<Group> parentGroups = parent != null ? parent.groups() : List.of();
        Matrix2D pose;
        boolean relOrigin = el.type() == ElementType.NODE && el.hints().relativeOrigin();
        if (anim.rot() == 0f && anim.skew() == 0f && anim.sx() == 1f && anim.sy() == 1f) {
            float tx = anim.tx() + (relOrigin ? el.x() : 0f);
            float ty = anim.ty() + (relOrigin ? el.y() : 0f);
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
            mulInto(m, 1, 0, 0, 1, px + lx + anim.tx(), py + ly + anim.ty());
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
