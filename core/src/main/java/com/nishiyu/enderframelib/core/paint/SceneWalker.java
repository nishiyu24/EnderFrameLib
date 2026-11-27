package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.PaintSceneDriver.Group;
import com.nishiyu.enderframelib.core.paint.PaintSceneDriver.Resolved;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.LayerSpec;
import com.nishiyu.enderframelib.core.paint.model.PaintOffsets;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;
import com.nishiyu.enderframelib.core.paint.scene.DisplayScene;
import com.nishiyu.enderframelib.core.paint.scene.DynamicSceneState;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 走査器（M3b-4b。計画書 §3.4「M3b-4b の設計」）: {@link DisplayScene} を描画順（付録 E）にたどり、層の描画命令を組み立てた項目を
 * 描画側（{@link Painter}）へ渡す。項目の姿勢（親から引き継ぐ変形）・祖先の不透明度・グループ（不透明度・フィルター・マスクの層）の鎖を
 * 解くのはここ 1 か所。
 *
 * <p>{@link PaintSceneDriver} との違い: 表示項目の列をフレームをまたいで持たない（索引の作り直し・差分の照合・項目の位置で引く記憶が無い）。
 * 層の範囲（フィルター・マスクの錨が覆う項目）は、項目に書いた範囲を数え直すのでなく、一時アダプタが層を描き終えた知らせ
 * （{@link ItemSink#setLayer}）から取る。補間の状態は節ごとの表（{@link AnimationStates}）から引く。項目の並びと位置の番号は
 * 一時アダプタの表示項目の列と同じなので、描画側の呼び出し（{@link Painter}）は変えずに済む。</p>
 *
 * <p>M3b-4b-2 では影で動かし、{@code PaintSceneDriver} と描画側への呼び出し（描く項目・順・グループの鎖・不透明度・姿勢）が実文書の全フレームで
 * 一致することをテスト（{@code SceneWalkerShadowTest}）で確かめる。保持層・画面外のブロックの間引きは 4b-3、全バックエンドの切り替えは 4b-4。
 * スレッドセーフではない。</p>
 */
public final class SceneWalker implements ItemSink {
    private final String sessionId;
    private final PoseMath poseMath = new PoseMath();
    private float scrollSnapDpr;

    // ---- 1 フレームの間だけ使う（終わったら項目の参照を捨てる） ----
    private DisplayItem[] items = new DisplayItem[64];
    private int count;
    private int[] parentIdx = new int[64];
    private Resolved[] res = new Resolved[64];
    private CssAnimator.AnimState[] states = new CssAnimator.AnimState[64];
    private boolean[] visiting = new boolean[64];
    private float[] offX = new float[64], offY = new float[64];
    @SuppressWarnings("unchecked")
    private List<Group>[] chains = new List[64];
    private int[] members = new int[64];
    /** フィルター・マスクの錨の位置（{@link #setLayer} の知らせの順＝内側が先。走査の前に並べ替える）。 */
    private int[] fxAnchors = new int[8];
    private int fxCount;
    private final Long2IntOpenHashMap indexOf = new Long2IntOpenHashMap();
    private final List<Integer> activeAnchors = new ArrayList<>();
    private final List<Group> layeredScratch = new ArrayList<>();
    private int anchorNext;
    private AnimationStates anims;
    private long now;
    private Matrix2D frame = Matrix2D.IDENTITY;
    private long posesBuilt;

    public SceneWalker(String sessionId) {
        this.sessionId = java.util.Objects.requireNonNull(sessionId, "sessionId");
        indexOf.defaultReturnValue(-1);
    }

    /** スクロールの中身の節の移動量を丸める先のデバイス画素比（{@link PaintSceneDriver#setScrollSnapDpr} と同じ）。 */
    public void setScrollSnapDpr(float dpr) { this.scrollSnapDpr = dpr; }

    /** 作った姿勢の数の累計（親との共有は数えない）。 */
    public long posesBuilt() { return posesBuilt; }

    /**
     * {@code scene} を時刻 {@code nowMs}・変換 {@code frame}（CSS px → デバイス画素）の見た目で描画順にたどり、{@code painter} へ渡す。
     * {@code offsets} はビューの描画上の平行移動（スクロールの中身の節・つまみ・sticky・利用者の移動。M3b-4b-4 で動的な値から直接足す）。
     */
    public void run(SceneDisplayListAdapter adapter, DisplayScene scene, DynamicSceneState dynamic, PaintOffsets offsets,
                    long nowMs, Matrix2D frame, Painter painter) {
        count = 0;
        fxCount = 0;
        try {
            adapter.emit(scene, dynamic, this);
            if (count == 0) return;
            this.now = nowMs;
            this.frame = frame;
            this.anims = CssAnimator.states(sessionId, true);
            index(offsets != null ? offsets : PaintOffsets.EMPTY);
            walk(painter);
        } finally {
            Arrays.fill(items, 0, count, null);
            Arrays.fill(res, 0, count, null);
            Arrays.fill(states, 0, count, null);
            Arrays.fill(chains, 0, count, null);
            indexOf.clear();
            activeAnchors.clear();
            layeredScratch.clear();
            count = 0;
        }
    }

    // ---- ItemSink: 一時アダプタが描画順に渡す項目 ----

    @Override
    public void add(DisplayItem item) {
        if (count == items.length) grow(count * 2);
        items[count++] = item;
    }

    @Override
    public int size() { return count; }

    @Override
    public void setLayer(int anchor, LayerSpec spec) {
        items[anchor] = BoxItemPainter.withLayer(items[anchor], spec);
        if (ItemDiff.hasFx(spec)) {
            if (fxCount == fxAnchors.length) fxAnchors = Arrays.copyOf(fxAnchors, fxCount * 2);
            fxAnchors[fxCount++] = anchor;
        }
    }

    private void grow(int cap) {
        items = Arrays.copyOf(items, cap);
        parentIdx = Arrays.copyOf(parentIdx, cap);
        res = Arrays.copyOf(res, cap);
        states = Arrays.copyOf(states, cap);
        visiting = Arrays.copyOf(visiting, cap);
        offX = Arrays.copyOf(offX, cap);
        offY = Arrays.copyOf(offY, cap);
        chains = Arrays.copyOf(chains, cap);
        members = Arrays.copyOf(members, cap);
    }

    // ---- 1 フレームの索引（鍵 → 位置・親・平行移動。フレームをまたいで持たない） ----

    private void index(PaintOffsets offsets) {
        int n = count;
        for (int i = 0; i < n; i++) {
            DisplayItem it = items[i];
            indexOf.put(it.key(), i);
            if (it.id() != null && !it.id().isEmpty()) {
                long strKey = DisplayItem.keyOf(it.id());
                if (strKey != it.key()) indexOf.putIfAbsent(strKey, i);
            }
        }
        for (int i = 0; i < n; i++) {
            DisplayItem it = items[i];
            long pKey = it.hints().parentKey();
            int p = pKey != 0L ? indexOf.get(pKey) : -1;
            if (p < 0) {
                String pid = it.hints().parentId();
                if (pid != null) p = indexOf.get(DisplayItem.keyOf(pid));
            }
            parentIdx[i] = p;
            offX[i] = 0f;
            offY[i] = 0f;
            visiting[i] = false;
            members[i] = 0;
        }
        Arrays.sort(fxAnchors, 0, fxCount);
        if (offsets.isEmpty()) return;
        if (offsets.isImmutable()) collectOffsets(offsets);
        else synchronized (offsets) { collectOffsets(offsets); }
    }

    private void collectOffsets(PaintOffsets eff) {
        for (int k = 0, esz = eff.size(); k < esz; k++) {
            int idx = indexOf.get(eff.keyAt(k));
            if (idx < 0) continue;
            DisplayItem it = items[idx];
            if (it.type() == ElementType.NODE && eff.idAt(k).equals(it.id())) {
                offX[idx] = eff.dxAt(k);
                offY[idx] = eff.dyAt(k);
            }
        }
    }

    // ---- 走査 ----

    private void walk(Painter painter) {
        int n = count;
        anchorNext = 0;
        activeAnchors.clear();
        Map<String, Integer> extraMembers = null; // 錨が列に無いグループだけ
        for (int i = 0; i < n; i++) {
            DisplayItem el = items[i];
            if (el.type() == ElementType.NODE || painter.skipItem(i)) {
                chains[i] = null;
                continue;
            }
            Resolved r = resolveAt(i);
            List<Group> chain = null;
            if (r.opacity() > 0.005f) {
                chain = r.groups().isEmpty() && fxCount == 0 ? List.of() : chainFor(i, r);
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
            chains[i] = chain;
        }
        for (int i = 0; i < n; i++) {
            List<Group> chain = chains[i];
            if (chain == null) continue;
            DisplayItem el = items[i];
            if (chain.isEmpty()) {
                painter.syncLayers(List.of());
                painter.drawItem(el, i, 1f);
                continue;
            }
            List<Group> layered = layeredScratch;
            layered.clear();
            float flat = 1f;
            for (int gi = 0, gn = chain.size(); gi < gn; gi++) {
                Group g = chain.get(gi);
                // フィルター・マスクのあるグループと、描く部品が 2 つ以上のグループだけ層にする（PaintSceneDriver と同じ規則）
                int m = g.anchor() >= 0 ? members[g.anchor()] : (extraMembers != null ? extraMembers.getOrDefault(g.id(), 0) : 0);
                if (g.spec() != null || g.maskOf() != null || m > 1) layered.add(g);
                else flat *= g.opacity();
            }
            painter.syncLayers(layered);
            painter.drawItem(el, i, flat);
        }
        painter.syncLayers(List.of());
    }

    /** 項目 {@code i} を含むグループの鎖（親子の鎖に、範囲に入るフィルター・マスクの錨を足し、マスクの項目にはマスクの層を挟む）。 */
    private List<Group> chainFor(int i, Resolved r) {
        while (anchorNext < fxCount && fxAnchors[anchorNext] <= i) activeAnchors.add(fxAnchors[anchorNext++]);
        for (int k = activeAnchors.size() - 1; k >= 0; k--) {
            int a = activeAnchors.get(k);
            if (i >= a + items[a].hints().layer().span()) activeAnchors.remove(k);
        }
        List<Group> base = r.groups();
        if (activeAnchors.isEmpty()) {
            boolean plain = true;
            for (int k = 0; k < base.size() && plain; k++) plain = base.get(k).spec() == null;
            if (plain) return base;
        }
        List<Group> chain = new ArrayList<>(base);
        for (int ai = 0, an = activeAnchors.size(); ai < an; ai++) {
            int a = activeAnchors.get(ai);
            DisplayItem node = items[a];
            boolean present = false;
            for (int gi = 0, gn = chain.size(); gi < gn; gi++) present |= chain.get(gi).id().equals(node.id());
            if (!present) chain.add(groupFor(node, a, resolveAt(a)));
        }
        chain.sort((x, y) -> Integer.compare(x.anchor(), y.anchor()));
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

    private static Group groupFor(DisplayItem node, int index, Resolved nr) {
        LayerSpec spec = ItemDiff.hasFx(node.hints().layer()) ? node.hints().layer() : null;
        Matrix2D m = nr.pose();
        return new Group(node.id(), Math.max(0f, nr.anim().opacity()), index, spec, new double[]{m.a(), m.b(), m.c(), m.d()}, null, m);
    }

    // ---- 描画側が drawItem の間に引く値 ----

    /** 項目 {@code i} の姿勢（このフレームで 1 回だけ作る。姿勢の変わらない部品は親のものを共有する）。 */
    public Resolved resolveAt(int i) {
        Resolved r = res[i];
        if (r != null) return r;
        DisplayItem el = items[i];
        Resolved parent = null;
        int p = parentIdx[i];
        if (p >= 0 && p != i) {
            if ((parent = res[p]) == null && !visiting[p]) {
                visiting[i] = true; // 親子が輪になっていても止まる
                parent = resolveAt(p);
                visiting[i] = false;
            }
        }
        CssAnimator.AnimState st = states[i];
        if (st == null) st = states[i] = CssAnimator.stateFor(anims, el, now);
        AnimatedValues av = st.getInterpolatedValues(now);
        r = parent != null && el.type() != ElementType.NODE && isIdentityGeometry(av) ? parent : build(el, i, parent, av);
        res[i] = r;
        return r;
    }

    /** 項目 {@code i} の補間値（色など）。姿勢を親と共有する項目でも、その項目自身のもの。 */
    public AnimatedValues animAt(int i) {
        Resolved r = resolveAt(i);
        int p = parentIdx[i];
        return p >= 0 && p != i && res[p] == r ? states[i].getInterpolatedValues(now) : r.anim();
    }

    /** 項目 {@code i} の親（節の錨）の位置。無ければ -1。 */
    public int parentIndex(int i) { return i >= 0 && i < count ? parentIdx[i] : -1; }

    /** 走査中の、フレーム全体の変換（CSS px → デバイス画素）。 */
    public Matrix2D frame() { return frame; }

    private static boolean isIdentityGeometry(AnimatedValues a) {
        return a.opacity() == 1f && a.tx() == 0f && a.ty() == 0f && a.sx() == 1f && a.sy() == 1f && a.rot() == 0f && a.skew() == 0f;
    }

    private static boolean isScrollContent(DisplayItem it) {
        return DisplayItem.partOrdinalOf(it.key()) == DisplayItem.PART_SCROLL_CONTENT
                || (it.id() != null && it.id().endsWith(PaintSceneDriver.SCROLL_CONTENT_SUFFIX));
    }

    /** 姿勢を作る（{@link PaintSceneDriver} の姿勢と同じ式・同じ順。前のフレームの姿勢は使い回さない）。 */
    private Resolved build(DisplayItem el, int index, Resolved parent, AnimatedValues anim) {
        posesBuilt++;
        Matrix2D base = parent != null ? parent.pose() : frame;
        float parentOpacity = parent != null ? parent.opacity() : 1f;
        float parentResidual = parent != null ? parent.residual() : 1f;
        List<Group> parentGroups = parent != null ? parent.groups() : List.of();
        boolean node = el.type() == ElementType.NODE;
        boolean relOrigin = node && el.hints().relativeOrigin();
        float atx = anim.tx() + offX[index], aty = anim.ty() + offY[index];
        if (scrollSnapDpr > 0f && node && isScrollContent(el)) {
            atx = Math.round(atx * scrollSnapDpr) / scrollSnapDpr;
            aty = Math.round(aty * scrollSnapDpr) / scrollSnapDpr;
        }
        Matrix2D pose;
        if (anim.rot() == 0f && anim.skew() == 0f && anim.sx() == 1f && anim.sy() == 1f) {
            float tx = atx + (relOrigin ? el.x() : 0f);
            float ty = aty + (relOrigin ? el.y() : 0f);
            pose = tx == 0f && ty == 0f ? base : PoseMath.translated(null, base, tx, ty);
        } else {
            RenderMeta meta = el.meta();
            float ox = meta != null ? meta.originX() : 0.5f, oy = meta != null ? meta.originY() : 0.5f;
            double lx = (relOrigin ? 0.0 : el.x()) + el.width() * ox;
            double ly = (relOrigin ? 0.0 : el.y()) + el.height() * oy;
            double px = relOrigin ? el.x() : 0.0;
            double py = relOrigin ? el.y() : 0.0;
            pose = poseMath.transformed(null, base, anim, lx, ly, px + lx + atx, py + ly + aty);
        }
        boolean fx = node && ItemDiff.hasFx(el.hints().layer());
        boolean group = node && (anim.opacity() < 0.999f || fx);
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
