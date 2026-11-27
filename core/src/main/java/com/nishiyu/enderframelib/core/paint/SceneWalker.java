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
import com.nishiyu.enderframelib.core.paint.scene.SceneAnchorFollow;
import com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
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
 * 一致することをテスト（{@code SceneWalkerShadowTest}）で確かめる。M3b-4b-3b で、保持層を中身の節に結び（{@link ScrollContentRanges}。中身の
 * 同一性の印はコンテナの層の子の並び）、描き直す範囲に補間・外の事情で変わる項目を足す（{@link #addDynamicDamage}。シーンの違いは
 * {@link SceneDamage}）。画面外のブロックの間引きは 4c、全バックエンドの切り替えは 4b-4。スレッドセーフではない。</p>
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
    /** 保持層にできるスクロールコンテナの中身の範囲（M3b-4b-3b。1 フレームの間だけ）。 */
    private final ScrollContentRanges ranges = new ScrollContentRanges();
    private AnimationStates anims;
    private long now;
    private Matrix2D frame = Matrix2D.IDENTITY;
    private long posesBuilt;
    /** 前の {@link #run} の時刻（補間がその時刻から今までに動いたかを見る。まだ無ければ -1）。 */
    private long lastNow = -1L;

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
     *
     * <p>スクロール量は項目に焼き込まない（M3b-4b-3）: 中身を 1 つの節にまとめるスクロールコンテナはスクロール 0 で組み、今のスクロール量
     * （{@code dynamic}）を中身の節・つまみ・中の sticky の平行移動として足す。項目はそのコンテナのスクロールに依らない。
     * {@code userOffsets} は利用者の描画上の平行移動（{@code UiView#setPaintOffset}）だけ。</p>
     */
    public void run(SceneDisplayListAdapter adapter, DisplayScene scene, DynamicSceneState dynamic, PaintOffsets userOffsets,
                    long nowMs, Matrix2D frame, Painter painter) {
        run(adapter, scene, dynamic, userOffsets, nowMs, frame, painter, null);
    }

    /**
     * {@link #run(SceneDisplayListAdapter, DisplayScene, DynamicSceneState, PaintOffsets, long, Matrix2D, Painter)} に、描き直す範囲
     * {@code damage}（{@link SceneDamage} がシーンの違いから求めたもの。null なら求めない）へ、補間と外の事情で変わる項目の範囲を
     * 走査の前に足す（{@link #addDynamicDamage}）。
     */
    public void run(SceneDisplayListAdapter adapter, DisplayScene scene, DynamicSceneState dynamic, PaintOffsets userOffsets,
                    long nowMs, Matrix2D frame, Painter painter, SceneDamage.Rects damage) {
        long prevNow = lastNow;
        lastNow = nowMs;
        count = 0;
        fxCount = 0;
        try {
            DynamicSceneState real = dynamic != null ? dynamic : DynamicSceneState.of(null, scene.root.w, scene.root.h);
            List<ScrollContentInfo> contents = emitUnscrolled(adapter, scene, real);
            if (count == 0) return;
            this.now = nowMs;
            this.frame = frame;
            this.anims = CssAnimator.states(sessionId, true);
            index(userOffsets != null ? userOffsets : PaintOffsets.EMPTY, contents, real);
            if (damage != null) addDynamicDamage(damage, prevNow);
            ranges.compute(items, count, parentIdx, indexOf, contents, scene);
            walk(painter);
        } finally {
            Arrays.fill(items, 0, count, null);
            Arrays.fill(res, 0, count, null);
            Arrays.fill(states, 0, count, null);
            Arrays.fill(chains, 0, count, null);
            ranges.clear();
            indexOf.clear();
            activeAnchors.clear();
            layeredScratch.clear();
            count = 0;
        }
    }

    /**
     * 今スクロールしているコンテナ（根・html・body・アンカーが追従するものを除く）をスクロール 0 で組む。組んだ結果、中身を 1 つの節に
     * まとめられなかった（固定配置・外へ出る絶対配置・content-visibility の子孫を持つ）コンテナは、今のスクロール量で組み直す。
     */
    private List<ScrollContentInfo> emitUnscrolled(SceneDisplayListAdapter adapter, DisplayScene scene, DynamicSceneState real) {
        Map<HtmlNode, float[]> scrolled = real.scroll.snapshot();
        Set<HtmlNode> zero = Collections.newSetFromMap(new IdentityHashMap<>());
        if (!scrolled.isEmpty()) {
            Set<HtmlNode> followed = SceneAnchorFollow.followedScrollers(scene);
            for (HtmlNode n : scrolled.keySet()) {
                String t = n.tag;
                if (!"root".equals(t) && !"html".equals(t) && !"body".equals(t) && !followed.contains(n)) zero.add(n);
            }
        }
        for (int attempt = 0; ; attempt++) {
            count = 0;
            fxCount = 0;
            adapter.emit(scene, real.unscrolled(zero), this);
            List<ScrollContentInfo> contents = adapter.scrollContents();
            if (zero.isEmpty()) return contents;
            Set<HtmlNode> asOneNode = Collections.newSetFromMap(new IdentityHashMap<>());
            for (ScrollContentInfo c : contents) asOneNode.add(c.node());
            if (asOneNode.containsAll(zero)) return contents;
            if (attempt >= 2) zero.clear();
            else zero.retainAll(asOneNode);
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

    private void index(PaintOffsets offsets, List<ScrollContentInfo> contents, DynamicSceneState real) {
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
        for (int c = 0, cn = contents.size(); c < cn; c++) scrollOffsets(contents.get(c), real);
        if (offsets.isEmpty()) return;
        if (offsets.isImmutable()) collectOffsets(offsets);
        else synchronized (offsets) { collectOffsets(offsets); }
    }

    /**
     * スクロールコンテナ {@code c} の中身の節・つまみ・中の sticky の平行移動（{@code UiView} がスクロールだけの変化で表示項目の列を動かす式と同じ。
     * 組んだときのスクロール量 {@code sx0}・{@code sy0} は、スクロール 0 で組んだコンテナでは 0）。
     */
    private void scrollOffsets(ScrollContentInfo c, DynamicSceneState real) {
        float dx = real.scroll.getScrollX(c.node()) - c.sx0(), dy = real.scroll.getScrollY(c.node()) - c.sy0();
        if (dx == 0f && dy == 0f) return;
        setOffset(c.contentId(), -dx, -dy);
        // つまみは組んだときと同じ整数の位置へ（小数のまま動かすと角の縁の画素が組み直しと違う）
        if (c.thumbVId() != null) setOffset(c.thumbVId(), 0f, Math.round(c.fy0() + c.ky() * dy));
        if (c.thumbHId() != null) setOffset(c.thumbHId(), Math.round(c.fx0() + c.kx() * dx), 0f);
        for (int k = 0, sn = c.stickies().size(); k < sn; k++) setOffset(c.stickies().get(k).nodeId(), 0f, c.stickies().get(k).extraAt(dy));
    }

    private void setOffset(String id, float dx, float dy) {
        int idx = indexOf.get(DisplayItem.keyOf(id));
        if (idx >= 0 && items[idx].type() == ElementType.NODE && id.equals(items[idx].id())) {
            offX[idx] = dx;
            offY[idx] = dy;
        }
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

    // ---- 描き直す範囲（M3b-4b-3b） ----

    /**
     * シーンの違いに現れない変化の範囲を {@code damage} へ足す: 前のフレームの時刻 {@code prevNow} か今の時刻に補間が動いている項目と、
     * 外の事情・部品の状態で絵が変わる項目（画像・動画・キャンバス・3D・入力欄・ゲージ・スライダー）。補間が姿勢（変形・不透明度）を
     * 動かすときは、子孫の位置と層が変わるので全面にする。範囲は項目の箱に影・文字の余白を足し、姿勢で写した CSS px の外接矩形。
     */
    void addDynamicDamage(SceneDamage.Rects damage, long prevNow) {
        if (damage.full()) return;
        for (int i = 0; i < count; i++) {
            DisplayItem el = items[i];
            CssAnimator.AnimState st = states[i];
            if (st == null) st = states[i] = CssAnimator.stateFor(anims, el, now);
            boolean activeNow = st.isActiveAt(now), activeBefore = prevNow >= 0L && st.isActiveAt(prevNow);
            if (activeNow || activeBefore) {
                int kind = Math.max(activeNow ? st.motionKindAt(now) : 0, activeBefore ? st.motionKindAt(prevNow) : 0);
                if (kind >= PaintSceneDriver.ACT_GEOMETRY) { damage.setFull("animation-geometry"); return; }
            } else if (!el.type().isVolatileForDamage()) {
                continue;
            }
            if (el.type() == ElementType.NODE) continue; // 節は描かない（色の補間は部品の項目が持つ）
            addItemBounds(damage, el, resolveAt(i).pose());
            if (damage.full()) return;
        }
    }

    /** 項目の箱（影・文字の余白つき）を姿勢で写し、フレームの変換（CSS px → デバイス画素）を戻した CSS px の外接矩形を足す。 */
    private void addItemBounds(SceneDamage.Rects damage, DisplayItem el, Matrix2D pose) {
        float pad = SceneDamage.padOf(el.meta(), el.text(), el.height());
        double x0 = el.x() - pad, y0 = el.y() - pad, x1 = el.x() + el.width() + pad, y1 = el.y() + el.height() + pad;
        double minX = Double.POSITIVE_INFINITY, minY = minX, maxX = Double.NEGATIVE_INFINITY, maxY = maxX;
        for (int c = 0; c < 4; c++) {
            double px = (c & 1) == 0 ? x0 : x1, py = (c & 2) == 0 ? y0 : y1;
            double dx = pose.a() * px + pose.c() * py + pose.e(), dy = pose.b() * px + pose.d() * py + pose.f();
            minX = Math.min(minX, dx); maxX = Math.max(maxX, dx); minY = Math.min(minY, dy); maxY = Math.max(maxY, dy);
        }
        double sx = frame.a() != 0.0 ? frame.a() : 1.0, sy = frame.d() != 0.0 ? frame.d() : 1.0;
        damage.add((float) ((minX - frame.e()) / sx), (float) ((minY - frame.f()) / sy),
                (float) ((maxX - frame.e()) / sx), (float) ((maxY - frame.f()) / sy));
    }

    // ---- 保持層（M3b-4b-3b） ----

    /**
     * 保持層の扱いを描画側に問う（{@link PaintSceneDriver} と同じ規則）: 中身の節の祖先にグループ（不透明度・フィルター）がある・フィルターの錨の
     * 範囲に入っているときは問わない。外側が保持する範囲の中の入れ子は問わない。
     */
    private void planRetained(Painter painter) {
        int coveredUntil = 0;
        for (int k = 0; k < ranges.count; k++) {
            int node = ranges.node[k];
            ranges.plan[k] = RetainPlan.NONE;
            if (ranges.from[k] < coveredUntil || insideFxAnchor(node)) continue;
            Resolved r = resolveAt(node);
            if (!r.groups().isEmpty() || r.opacity() < 0.999f) continue;
            RetainPlan d = painter.planRetained(items[node], node, ranges.from[k], ranges.to[k], ranges.token[k]);
            ranges.plan[k] = d != null ? d : RetainPlan.NONE;
            if (ranges.plan[k] != RetainPlan.NONE) coveredUntil = ranges.to[k];
        }
    }

    private boolean insideFxAnchor(int index) {
        for (int k = 0; k < fxCount; k++) {
            int a = fxAnchors[k];
            if (a < index && index < a + items[a].hints().layer().span()) return true;
        }
        return false;
    }

    // ---- 走査 ----

    private void walk(Painter painter) {
        int n = count;
        anchorNext = 0;
        activeAnchors.clear();
        planRetained(painter);
        Map<String, Integer> extraMembers = null; // 錨が列に無いグループだけ
        for (int i = 0; i < n; i++) {
            int skip = ranges.count > 0 ? ranges.at(i, RetainPlan.SKIP) : -1;
            if (skip >= 0) { // 保持済みの層を重ねる範囲: 鎖も姿勢も要らない
                Arrays.fill(chains, i, ranges.to[skip], null);
                i = ranges.to[skip] - 1;
                continue;
            }
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
        int[] open = ranges.count > 0 ? new int[ranges.count] : null; // 記録中の保持層の範囲の番号（入れ子の順）
        int openN = 0;
        for (int i = 0; i < n; i++) {
            if (ranges.count > 0) {
                int skip = ranges.at(i, RetainPlan.SKIP);
                if (skip >= 0) {
                    painter.syncLayers(List.of());
                    painter.drawRetained(items[ranges.node[skip]], ranges.node[skip], ranges.from[skip], ranges.to[skip]);
                    i = ranges.to[skip] - 1;
                    continue;
                }
                int rec = ranges.at(i, RetainPlan.RECORD);
                if (rec >= 0) {
                    painter.syncLayers(List.of());
                    painter.beginRetained(items[ranges.node[rec]], ranges.node[rec], ranges.from[rec], ranges.to[rec]);
                    open[openN++] = rec;
                }
            }
            List<Group> chain = chains[i];
            if (chain != null) drawChained(painter, i, chain, extraMembers);
            // 記録中の保持層の範囲の終わり: 層を保存して重ねる。保持できなければ、範囲をふつうに描き直す
            while (openN > 0 && ranges.to[open[openN - 1]] == i + 1) {
                int k = open[--openN];
                painter.syncLayers(List.of());
                if (!painter.endRetained(items[ranges.node[k]], ranges.node[k])) {
                    ranges.plan[k] = RetainPlan.NONE;
                    i = ranges.from[k] - 1;
                    openN = 0;
                    break;
                }
            }
        }
        painter.syncLayers(List.of());
    }

    private void drawChained(Painter painter, int i, List<Group> chain, Map<String, Integer> extraMembers) {
        DisplayItem el = items[i];
        if (chain.isEmpty()) {
            painter.syncLayers(List.of());
            painter.drawItem(el, i, 1f);
            return;
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
