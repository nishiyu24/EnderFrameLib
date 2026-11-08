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
    /** 索引（{@link #indexedById}・{@link #indexOf}・{@link #fxAnchors}）を作った時のリスト。 */
    private List<DisplayItem> indexedItems;
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
     * {@code items} を時刻 {@code nowMs} の見た目で先頭から走査し、{@code painter} へ渡す。
     *
     * @param frame フレーム全体の変換（普通は {@code dpr} 倍の拡縮）
     */
    public void run(List<DisplayItem> items, long nowMs, Matrix2D frame, Painter painter) {
        if (items == null || items.isEmpty()) return;
        this.now = nowMs;
        this.frame = frame;
        CssAnimator.cleanup(sessionId, items);
        try {
            // 項目の並びが前回と同じ（不変リストの同一インスタンス）なら、id の索引と錨の一覧は作り直さない。
            // アニメーションだけが動くフレームでは、リストは変わらず姿勢だけが変わる
            if (!(items == indexedItems && isImmutable(items)) && !patchIndex(items)) rebuildIndex(items);
            if (++frameStamp == 0) { Arrays.fill(curStamp, 0); frameStamp = 1; }
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
            RetainPlan[] plan = planRetained(items, painter);
            int skipTo = 0;
            for (int i = 0; i < items.size(); i++) {
                if (i < skipTo) continue;
                int rn = retainNodeAt.length > i ? retainNodeAt[i] : -1;
                if (rn >= 0 && plan != null && plan[rn] == RetainPlan.SKIP) {
                    skipTo = retainTo[rn];
                    continue;
                }
                DisplayItem el = items.get(i);
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
            int[] open = new int[4]; // 記録中の保持層の節の添字（入れ子の順）
            int openN = 0;
            for (int i = 0; i < items.size(); i++) {
                int rn = retainNodeAt.length > i ? retainNodeAt[i] : -1;
                if (rn >= 0 && plan != null && plan[rn] != null && plan[rn] != RetainPlan.NONE) {
                    DisplayItem node = items.get(rn);
                    painter.syncLayers(List.of());
                    if (plan[rn] == RetainPlan.SKIP) {
                        painter.drawRetained(node, rn, retainFrom[rn], retainTo[rn]);
                        i = retainTo[rn] - 1;
                        continue;
                    }
                    painter.beginRetained(node, rn, retainFrom[rn], retainTo[rn]);
                    if (openN == open.length) open = java.util.Arrays.copyOf(open, openN * 2);
                    open[openN++] = rn;
                }
                DisplayItem el = items.get(i);
                if (el.type() != ElementType.NODE) {
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
                        i = retainFrom[node] - 1;
                        openN = 0;
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
     */
    private RetainPlan[] planRetained(List<DisplayItem> items, Painter painter) {
        if (!retainScrollLayers || retainNodes.length == 0) return null;
        RetainPlan[] plan = null;
        int coveredUntil = 0;
        for (int c : retainNodes) {
            int from = retainFrom[c], to = retainTo[c];
            if (from < coveredUntil) continue;
            if (insideFxAnchor(items, c)) continue;
            Resolved r = resolveAt(c);
            if (!r.groups().isEmpty() || r.opacity() < 0.999f) continue;
            RetainPlan decision = painter.planRetained(items.get(c), c, from, to);
            if (decision == RetainPlan.NONE) continue;
            if (plan == null) {
                if (planBuf.length < items.size()) planBuf = new RetainPlan[Math.max(items.size(), planBuf.length * 2)];
                else Arrays.fill(planBuf, 0, items.size(), null);
                plan = planBuf;
            }
            plan[c] = decision;
            coveredUntil = to;
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
            }
        }
        indexedItems = items;
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
