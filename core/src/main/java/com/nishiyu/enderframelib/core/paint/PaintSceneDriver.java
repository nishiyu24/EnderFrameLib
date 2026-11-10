package com.nishiyu.enderframelib.core.paint;

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

    /**
     * 同じ基底の上で差し替えだけが変わった表示リスト（{@link OffsetItemList}。スクロール・ドラッグ）を、索引を作り直さず
     * 差し替わった項目だけ更新して走査するか。false なら毎回作り直す（疑うときの切り分け用。絵は同じでなければならない）。
     */
    public static volatile boolean patchOffsetLists = true;

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

    private final HashMap<Integer, RangeBounds> rangeBounds = new HashMap<>();
    /** 項目の状態（{@link CssAnimator.AnimState}）が「姿勢は中立」と確かめ済みの印。項目が差し替わる・索引を作り直すと外れる。 */
    private boolean[] stateVerified = new boolean[0];
    /** このフレームの、走査から外すブロックのある RECORD の節 → その範囲の索引（{@link RangeBounds#keep} が走査するブロック）。 */
    private final HashMap<Integer, RangeBounds> windows = new HashMap<>();
    /** 診断・テスト用: 走査から外した項目の数（累計）。 */
    private long rangeCulledItems;

    /** 診断用: 全ドライバの合計（計測ハーネスが読む）。 */
    public static final java.util.concurrent.atomic.AtomicLong RANGE_CULLED_TOTAL = new java.util.concurrent.atomic.AtomicLong();
    /** 診断用: 記録（RECORD）の範囲を計画した回数と、窓を出せず・条件を満たさず外せなかった回数。 */
    public static final java.util.concurrent.atomic.AtomicLong RANGE_PLANNED = new java.util.concurrent.atomic.AtomicLong(), RANGE_INELIGIBLE = new java.util.concurrent.atomic.AtomicLong();

    public long rangeCulledItems() {
        return rangeCulledItems;
    }

    private void invalidateRangeBounds() {
        for (RangeBounds rb : rangeBounds.values()) rb.valid = false;
    }

    /** {@link #isNeutral} が、項目の状態（動いている・目標が中立でない）を理由に false を返した（項目の中身のせいではない）。 */
    private boolean stateBlocked;

    private boolean isNeutral(DisplayItem el, int i) {
        if (el.translateX() != 0f || el.translateY() != 0f || el.scaleX() != 1f || el.scaleY() != 1f || el.rotate() != 0f
                || el.opacity() != 1f || el.hints().skew() != 0f) return false;
        if (el.animDef() != null && !com.nishiyu.enderframelib.core.css.model.AnimationSpec.NONE.equals(el.animDef())) return false;
        if (el.type() == ElementType.NODE && hasFx(el.hints().layer())) return false;
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
        RangeBounds rb = rangeBounds.computeIfAbsent(c, k -> new RangeBounds());
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
            DisplayItem el = items.get(from + j);
            if (!isNeutral(el, from + j)) {
                if (stateBlocked) { rb.valid = false; stateBlocked = false; }
                return null;
            }
            boolean node = el.type() == ElementType.NODE;
            rb.bottom[j] = node ? Double.NEGATIVE_INFINITY : el.y() + (double) el.height();
            rb.top[j] = node ? Double.POSITIVE_INFINITY : el.y();
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
    /** 差のある位置の数がこれを超える（項目数に対する割合）なら、差分の更新より作り直しの方が安い。 */
    private static final int PATCH_MAX_DIFF_DIVISOR = 4;
    private int[] diffBuf = new int[64];
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
    public long generation() {
        return generation;
    }

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

    /** 項目が、層（フィルター・マスク・混合・クリップ・背景フィルター）を要する錨か。 */
    public static boolean hasFxLayer(DisplayItem el) {
        return el.type() == ElementType.NODE && hasFx(el.hints().layer());
    }

    /** 走査中の、フレーム全体の変換（CSS px → デバイス画素）。 */
    public Matrix2D frame() {
        return frame;
    }

    /** 走査中の、id から項目を引く。 */
    public DisplayItem itemById(String id) {
        return byId.get(DisplayItem.keyOf(id));
    }

    /** 走査中の、鍵から項目を引く（文字列のハッシュを使わない）。 */
    public DisplayItem itemByKey(long key) {
        return byId.get(key);
    }

    /** id の項目の位置。無ければ -1。 */
    public int indexOf(String id) {
        return indexOf.getOrDefault(DisplayItem.keyOf(id), -1);
    }

    /** 鍵の項目の位置。無ければ -1。 */
    public int indexOfKey(long key) {
        return indexOf.getOrDefault(key, -1);
    }

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
        if (!(items == indexedItems && isImmutable(items)) && !patchIndex(items) && !patchIndexByDiff(items)) rebuildIndex(items);
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
        try {
            this.byId = indexedById;
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
            Map<String, Integer> extraMembers = new HashMap<>(); // 錨がリストに無いグループだけ
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
                    if (r.opacity() > 0.005f) {
                        chain = chainFor(items, i, r);
                        for (Group g : chain) {
                            if (g.maskOf() != null) continue;
                            if (g.anchor() >= 0) members[g.anchor()]++;
                            else extraMembers.merge(g.id(), 1, Integer::sum);
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
                            List<Group> layered = new ArrayList<>(chain.size());
                            float flat = 1f;
                            for (Group g : chain) {
                                // フィルター・マスクのあるグループと、描く部品が 2 つ以上のグループだけ層にする
                                // （部品が 1 つなら、部品ごとに不透明度を掛けても同じ。GPU の経路も保てる）
                                if (g.spec() != null || g.maskOf() != null || (g.anchor() >= 0 ? members[g.anchor()] : extraMembers.getOrDefault(g.id(), 0)) > 1) layered.add(g);
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
        retainFrom = new int[n];
        retainTo = new int[n];
        retainNodeAt = new int[n];
        java.util.Arrays.fill(retainFrom, -1);
        java.util.Arrays.fill(retainNodeAt, -1);
        if (nodes.size == 0) {
            retainNodes = new int[0];
            return;
        }
        boolean[] candidate = new boolean[n];
        for (int k = 0; k < nodes.size; k++) candidate[nodes.a[k]] = true;
        int[] min = new int[n], max = new int[n], count = new int[n];
        java.util.Arrays.fill(min, Integer.MAX_VALUE);
        java.util.Arrays.fill(max, -1);
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
        Integer[] order = new Integer[valid.size];
        for (int k = 0; k < valid.size; k++) order[k] = valid.a[k];
        java.util.Arrays.sort(order, (x, y) -> retainFrom[x] != retainFrom[y] ? Integer.compare(retainFrom[x], retainFrom[y]) : Integer.compare(retainTo[y], retainTo[x]));
        retainNodes = new int[order.length];
        for (int k = 0; k < order.length; k++) retainNodes[k] = order[k];
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
    public List<DisplayItem> items() {
        return indexedItems;
    }

    /**
     * 中身の同一性の印。同じ印の間は、差し替え（{@link OffsetItemList}）を除いて表示リストの中身は同じ。
     * スクロール層の保持が、前に描いた層をそのまま使えるかの判定に使う。
     */
    public Object contentToken() {
        return indexedBase;
    }

    /** 索引を作ったときの基底の表示リスト（{@link OffsetItemList} の差し替えだけが変わるなら、索引は作り直さない）。 */
    private List<DisplayItem> indexedBase;

    /**
     * {@code items} が、前回の索引と同じ基底の上の {@link OffsetItemList} なら、差し替わった項目だけを更新して true。
     * 位置・親子・グループの錨は基底のままなので、id の引き当てと、その項目の補間状態・姿勢の使い回しだけを捨てる
     * （子孫の姿勢は、親の {@code Resolved} が新しくなるので自動で作り直される）。世代は進めない:
     * クリップの使い回しは持ち主の {@code Resolved} の同一性で検証されるため、古い結果は返らない。
     */
    private boolean patchIndex(List<DisplayItem> items) {
        OffsetItemList now = items instanceof OffsetItemList o ? o : null;
        boolean sameBase = now != null ? now.base() == indexedBase : items == indexedBase;
        if (!patchOffsetLists || !sameBase || indexedItems == null || indexedItems.size() != items.size()) return false;
        List<DisplayItem> old = indexedItems;
        boolean changed = false;
        // 差し替わりうる位置: 今回の差し替えと、前回の差し替え（外れたものは基底へ戻る）
        for (int pass = 0; pass < 2; pass++) {
            OffsetItemList src = pass == 0 ? now : (old instanceof OffsetItemList o ? o : null);
            if (src == null) continue;
            for (int k = 0, n = src.overrideCount(); k < n; k++) {
                int i = src.positionAt(k);
                DisplayItem was = old.get(i), is = items.get(i);
                if (was == is) continue;
                indexedById.put(is.key(), is);
                states[i] = null;
                prevRes[i] = null;
                prevStatic[i] = false;
                stateVerified[i] = false;
                changed = true;
            }
        }
        if (changed) invalidateRangeBounds();
        indexedItems = items;
        return true;
    }

    /**
     * 一般のリスト（{@link OffsetItemList} でない）が、前回と同じ大きさで、差のある位置が少なく、差のある位置の構造
     * （鍵・親・種類・層）が前回と同じなら、差のある位置だけを更新して true。変わっていない項目の姿勢・補間状態は使い回す。
     * 1 つの要素の変化（文字・ホバー・色）で、全項目の索引・姿勢・補間状態の表を作り直さずに済む。
     * 構造が違う（項目の増減・鍵や親の変更・層の出入り）ときは false で、呼び出し側が作り直す。
     */
    private boolean patchIndexByDiff(List<DisplayItem> items) {
        List<DisplayItem> old = indexedItems;
        if (!patchDiffIndex || old == null || old == items || old.size() != items.size()) return false;
        // 可変のリストは、索引を作ってから中身を書き換えられうる（前回のリストを基準にした差が当てにならない）ので、毎回作り直す。
        // 表示リストは不変のリスト（List.copyOf 系）で届く
        if (!isImmutable(old) || !isImmutable(items)) return false;
        int n = items.size();
        int limit = Math.max(8, n / PATCH_MAX_DIFF_DIVISOR);
        int count = 0;
        for (int i = 0; i < n; i++) {
            DisplayItem was = old.get(i), is = items.get(i);
            if (was == is) continue;
            if (count >= limit || was.key() != is.key() || !sameStructure(was, is)) return false;
            if (count == diffBuf.length) diffBuf = Arrays.copyOf(diffBuf, count * 2);
            diffBuf[count++] = i;
        }
        for (int k = 0; k < count; k++) {
            int i = diffBuf[k];
            indexedById.put(items.get(i).key(), items.get(i));
            states[i] = null;
            prevRes[i] = null;
            prevStatic[i] = false;
            itemVersion[i]++;
            stateVerified[i] = false;
        }
        if (count > 0) invalidateRangeBounds();
        indexedItems = items;
        // 中身が変わった印（スクロール層の保持が、前に描いた層をそのまま使えるかの判定に使う）は、作り直しと同じく進める
        indexedBase = items instanceof OffsetItemList o ? o.base() : items;
        return true;
    }

    /** 索引・親子・グループの錨・スクロール範囲が、項目から読むもの（種類・親・層）が同じか。 */
    private static boolean sameStructure(DisplayItem a, DisplayItem b) {
        if (a.type() != b.type()) return false;
        String pa = a.hints().parentId(), pb = b.hints().parentId();
        if (pa != pb && (pa == null || !pa.equals(pb))) return false;
        if (a.type() == ElementType.NODE) {
            LayerSpec la = a.hints().layer(), lb = b.hints().layer();
            boolean fa = hasFx(la), fb = hasFx(lb);
            if (fa != fb) return false;
            if (fa && la.span() != lb.span()) return false;
        }
        return true;
    }

    private void rebuildIndex(List<DisplayItem> items) {
        Long2ObjectOpenHashMap<DisplayItem> map = new Long2ObjectOpenHashMap<>(items.size() * 2);
        for (DisplayItem it : items) map.put(it.key(), it);
        indexedById = map;
        indexOf.clear();
        fxAnchors.clear();
        for (int i = 0; i < items.size(); i++) {
            DisplayItem it = items.get(i);
            indexOf.put(it.key(), i);
            if (it.type() == ElementType.NODE && hasFx(it.hints().layer())) fxAnchors.add(i);
        }
        indexedItems = items;
        indexedBase = items instanceof OffsetItemList o ? o.base() : items;
        int n = items.size();
        parentIdx = new int[n];
        for (int i = 0; i < n; i++) {
            String pid = items.get(i).hints().parentId();
            parentIdx[i] = pid == null ? -1 : indexOf.getOrDefault(DisplayItem.keyOf(pid), -1);
        }
        computeRetainRanges(items);
        curRes = new Resolved[n];
        curStamp = new int[n];
        prevRes = new Resolved[n];
        prevParentRes = new Resolved[n];
        prevStatic = new boolean[n];
        prevIdle = new boolean[n];
        visiting = new boolean[n];
        states = new CssAnimator.AnimState[n];
        itemVersion = new int[n];
        stateVerified = new boolean[n];
        rangeBounds.clear();
        generation++;
    }

    /** 中身が変わらないと言えるリストか（{@code List.of} / {@code List.copyOf} / {@code Stream.toList} の結果）。 */
    private static boolean isImmutable(List<?> list) {
        return list.getClass().getName().startsWith("java.util.ImmutableCollections$");
    }

    private static boolean hasFx(LayerSpec spec) {
        return spec != null && (!spec.filters().isEmpty() || spec.masked() || spec.blend() != BlendMode.NORMAL || spec.isolate()
                || spec.clip() != null || spec.hasBackdrop());
    }

    private Group groupFor(DisplayItem node, Resolved nr) {
        LayerSpec spec = hasFx(node.hints().layer()) ? node.hints().layer() : null;
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
        for (int a : activeAnchors) {
            DisplayItem node = items.get(a);
            boolean present = false;
            for (Group g : chain) present |= g.id().equals(node.id());
            if (!present) chain.add(groupFor(node, resolveAt(a)));
        }
        chain.sort(Comparator.comparingInt(Group::anchor));
        List<Group> out = new ArrayList<>(chain.size() + 1);
        for (Group g : chain) {
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
        for (int i = 0; i < states.length; i++) {
            CssAnimator.AnimState st = states[i];
            if (st != null && st.isActiveAt(nowMs)) return true;
        }
        return false;
    }

    /** 項目 {@code i} の姿勢。同じフレームの間は 1 回だけ計算し、動いていない項目は前フレームの結果をそのまま使う。 */
    public Resolved resolveAt(int i) {
        Resolved r;
        if (curStamp[i] == frameStamp && (r = curRes[i]) != null) return r;
        DisplayItem el = indexedItems.get(i);
        Resolved parent = null;
        int p = parentIdx[i];
        if (p >= 0 && p != i && !visiting[p]) {
            visiting[i] = true; // 親子が輪になっていても止まる
            parent = resolveAt(p);
            visiting[i] = false;
        }
        CssAnimator.AnimState st = states[i];
        if (st == null) st = states[i] = CssAnimator.stateFor(sessionId, el, now);
        boolean idle = !st.isActiveAt(now);
        // 姿勢と不透明度が動いていない（止まっている・色だけが補間される）なら、前回の姿勢をそのまま使う
        boolean geometryStatic = idle || st.geometryStaticAt(now);
        Resolved prev = prevRes[i];
        if (reuseIdlePoses && prev != null && geometryStatic && prevStatic[i] && prevParentRes[i] == parent) {
            // 自分も親も姿勢は動いていない: 行列の掛け算はしない。補間値（Resolved が持つ共有の同じ実体）は、前回も止まっていて
            // 確定済みのとき以外は更新する（色が動いている・今のフレームで終わったとき）
            if (!(idle && prevIdle[i])) st.getInterpolatedValues(now);
            r = prev;
        } else {
            r = build(el, i, parent, st.getInterpolatedValues(now));
            prevRes[i] = r;
            prevParentRes[i] = parent;
            prevStatic[i] = geometryStatic; // 「前回も姿勢が動いていなかった」
        }
        prevIdle[i] = idle; // 「前回の補間値は確定済み」
        curRes[i] = r;
        curStamp[i] = frameStamp;
        return r;
    }

    private Resolved build(DisplayItem el, int index, Resolved parent, AnimatedValues anim) {
        Matrix2D base = parent != null ? parent.pose() : frame;
        float parentOpacity = parent != null ? parent.opacity() : 1f;
        float parentResidual = parent != null ? parent.residual() : 1f;
        List<Group> parentGroups = parent != null ? parent.groups() : List.of();
        RenderMeta meta = el.meta();
        float ox = meta != null ? meta.originX() : 0.5f, oy = meta != null ? meta.originY() : 0.5f;
        double cx = el.x() + el.width() * ox, cy = el.y() + el.height() * oy;
        Matrix2D pose = base.multiply(Matrix2D.translate(cx + anim.tx(), cy + anim.ty()));
        if (anim.rot() != 0f) pose = pose.multiply(Matrix2D.rotate(Math.toRadians(anim.rot())));
        if (anim.skew() != 0f) pose = pose.multiply(new Matrix2D(1, 0, Math.tan(Math.toRadians(anim.skew())), 1, 0, 0)); // skewX（回転の後・拡縮の前）
        if (anim.sx() != 1f || anim.sy() != 1f) pose = pose.multiply(Matrix2D.scale(anim.sx(), anim.sy()));
        pose = pose.multiply(Matrix2D.translate(-cx, -cy));
        boolean fx = el.type() == ElementType.NODE && hasFx(el.hints().layer());
        boolean group = el.type() == ElementType.NODE && (anim.opacity() < 0.999f || fx);
        List<Group> groups = parentGroups;
        Resolved r = new Resolved(pose, parentOpacity * anim.opacity(), parentResidual * (group ? 1f : anim.opacity()), groups, anim);
        if (group) {
            groups = new ArrayList<>(parentGroups);
            groups.add(new Group(el.id(), Math.max(0f, anim.opacity()), index, fx ? el.hints().layer() : null,
                    new double[]{pose.a(), pose.b(), pose.c(), pose.d()}, null, pose));
            r = new Resolved(pose, r.opacity(), r.residual(), groups, anim);
        }
        return r;
    }
}
