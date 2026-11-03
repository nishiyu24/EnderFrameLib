package com.nishiyu.enderframelib.core.paint;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.ItemKeys;
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
        /** 開いている層を、項目の祖先のグループ {@code want}（外側から内側の順）に合わせる。 */
        void syncLayers(List<Group> want);

        /**
         * 項目 {@code index} を描く。{@code flat} は層にしなかったグループの不透明度の積
         * （姿勢などは {@link PaintSceneDriver#resolveAt} で引く）。
         */
        void drawItem(DisplayItem el, int index, float flat);
    }

    private static final Long2ObjectOpenHashMap<DisplayItem> EMPTY_BY_KEY = new Long2ObjectOpenHashMap<>(0);

    private final String sessionId;

    private final Long2IntOpenHashMap indexOf = new Long2IntOpenHashMap();
    private final List<Integer> fxAnchors = new ArrayList<>();
    private final List<Integer> activeAnchors = new ArrayList<>();
    /** {@link #chainFor} が前へ進める錨の位置。 */
    private int anchorNext;
    private int[] parentIdx = new int[0];
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
        return byId.get(ItemKeys.of(id));
    }

    /** 走査中の、鍵から項目を引く（文字列のハッシュを使わない）。 */
    public DisplayItem itemByKey(long key) {
        return byId.get(key);
    }

    /** id の項目の位置。無ければ -1。 */
    public int indexOf(String id) {
        return indexOf.getOrDefault(ItemKeys.of(id), -1);
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
            if (!(items == indexedItems && isImmutable(items))) rebuildIndex(items);
            Arrays.fill(curRes, null);
            this.byId = indexedById;
            // 項目ごとの祖先のグループ（親子の鎖と、錨の範囲の両方から。外側から内側の順）
            anchorNext = 0;
            activeAnchors.clear();
            List<List<Group>> chains = new ArrayList<>(items.size());
            // グループに属する描く部品の数。錨の位置（項目の添字）で数える（文字列の表を引かない）
            int[] members = new int[items.size()];
            Map<String, Integer> extraMembers = new HashMap<>(); // 錨がリストに無いグループだけ
            for (int i = 0; i < items.size(); i++) {
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
                chains.add(chain);
            }
            for (int i = 0; i < items.size(); i++) {
                DisplayItem el = items.get(i);
                if (el.type() == ElementType.NODE) continue;
                List<Group> chain = chains.get(i);
                if (chain == null) continue;
                if (chain.isEmpty()) { // 不透明度のグループの外: 層を開かず、そのまま描く
                    painter.syncLayers(List.of());
                    painter.drawItem(el, i, 1f);
                    continue;
                }
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
            painter.syncLayers(List.of());
        } finally {
            this.byId = EMPTY_BY_KEY;
        }
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
        int n = items.size();
        parentIdx = new int[n];
        for (int i = 0; i < n; i++) {
            String pid = items.get(i).hints().parentId();
            parentIdx[i] = pid == null ? -1 : indexOf.getOrDefault(ItemKeys.of(pid), -1);
        }
        curRes = new Resolved[n];
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

    /** 項目 {@code i} の姿勢。同じフレームの間は 1 回だけ計算し、動いていない項目は前フレームの結果をそのまま使う。 */
    public Resolved resolveAt(int i) {
        Resolved r = curRes[i];
        if (r != null) return r;
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
