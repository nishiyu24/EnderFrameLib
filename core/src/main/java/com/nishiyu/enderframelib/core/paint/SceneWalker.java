package com.nishiyu.enderframelib.core.paint;

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
 * 走査器（M3b-4b。計画書 §3.4「M3b-4b の設計」）: 全バックエンド（CPU・GPU・Vulkan・MOD の面）の描画の経路。ビューが
 * {@link SceneDisplayListAdapter} で組んだ表示項目の列（スクロールコンテナは中身を 1 つの節にまとめてスクロール 0 で組む）を
 * 描画順にたどり、描画側（{@link Painter}）へ渡す。項目の姿勢（親から引き継ぐ変形）・祖先の不透明度・グループ（不透明度・フィルター・
 * マスクの層）の鎖を解くのはここ 1 か所。項目の親と保持層の範囲は、列を組んだ側がシーンの層をたどって書いた列の階層
 * （{@link FrameStructure}。M3b-4c-2b-3b-1）から読み、項目の鍵・ID から引き直さない。
 *
 * <p>フレームをまたいで何も持たない（M3b-4b-4a で {@code PaintSceneDriver} を置き換えた）: 索引・親の位置・姿勢は毎フレーム作り直し、
 * 前のフレームの姿勢・索引の差分・画面外のブロックの覚えを持たない。持つのは補間の状態（節ごとの表 {@link AnimationStates}。
 * {@link CssAnimator} がセッションごとに持つ。列が別の実体になったら列にある節の分だけ残す）と、前のフレームの時刻・列の同一性
 * （{@link #generation}）だけ。スクロールの移動量と利用者の
 * 移動は、ビューの平行移動の表（{@link PaintOffsets}）で節に足す。</p>
 *
 * <p>シーン（{@link SceneSource}）のフレームは、保持層にできるスクロールコンテナの中身の範囲（列の階層の {@link FrameStructure#retainCount}。
 * 中身の同一性の印はコンテナの層の子の並び）を描画側に問い、描き直す範囲（{@link SceneDamage} がシーンの違いから求めたもの）に、補間・外の事情で変わる項目を足す
 * （{@link #addDynamicDamage}）と、シーンの外から足した重ね描きの前後の範囲（{@link #addOverlayDamage}）。画面外のブロックの間引きは M3b-4c（チャンクのカリング）。スレッドセーフではない。</p>
 */
public final class SceneWalker {
    /** 項目の姿勢（親から引き継いだ変形）と、祖先の不透明度の積。{@code anim} は親の実体を共有する項目では親のもの（項目の値は {@link #animAt}）。 */
    public record Resolved(Matrix2D pose, float opacity, float residual, List<Group> groups, AnimatedValues anim) {}

    /** 不透明度を持つ祖先（グループ）。層にまとめて描き、{@code opacity} を掛けて重ねる。 */
    public record Group(String id, float opacity, int anchor, LayerSpec spec, double[] map, String maskOf, Matrix2D pose) {}

    /** 項目の動きの種類（{@link CssAnimator.AnimState#motionKindAt} と同じ値）。 */
    public static final byte ACT_IDLE = 0, ACT_PAINT_ONLY = 1, ACT_GEOMETRY = 2, ACT_OPACITY = 3;

    /** スクロール節（{@code _scrollc}）の id の接尾辞。 */
    public static final String SCROLL_CONTENT_SUFFIX = "_scrollc";

    private final String sessionId;
    private final PoseMath poseMath = new PoseMath();
    private float scrollSnapDpr;

    // ---- 1 フレームの間だけ使う（終わったら項目の参照を捨てる） ----
    private List<DisplayItem> list;
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
    private final List<Group> layeredScratch = new ArrayList<>();
    /** このフレームの列の階層（項目の親・保持層の範囲。1 フレームの間だけ）。 */
    private FrameStructure structure = FrameStructure.EMPTY;
    /** 保持層の範囲ごとの描画側の答え（{@link FrameStructure#retainCount} の数。1 フレームの間だけ）。 */
    private RetainPlan[] plan = new RetainPlan[4];
    private int retainCount;
    /** 平行移動の表の ID → 番号（1 フレームの間だけ。節の項目に移動量を足す）。 */
    private final Map<String, Integer> offsetOf = new HashMap<>();
    private AnimationStates anims;
    /** このフレームで描かない項目（{@link RenderFrame#excluded}。無ければ null）。 */
    private java.util.function.Predicate<DisplayItem> exclude;
    private long now;
    private Matrix2D frame = Matrix2D.IDENTITY;
    private long posesBuilt;

    // ---- フレームをまたぐもの（覚えではなく、比べる相手と印） ----
    /** 前の {@link #run} の時刻（補間がその時刻から今までに動いたかを見る。まだ無ければ -1）。 */
    private long lastNow = -1L;
    /** 前の {@link #run} の列（同一性だけを見る。{@link #generation} を進めるため）。 */
    private List<DisplayItem> lastList;
    private long generation;
    /** 前のフレームの重ね描きの範囲（CSS px。{@link #addOverlayDamage}）。 */
    private final SceneDamage.Rects prevOverlay = new SceneDamage.Rects();

    public SceneWalker(String sessionId) {
        this.sessionId = java.util.Objects.requireNonNull(sessionId, "sessionId");
    }

    /**
     * スクロールの中身の節（{@link #SCROLL_CONTENT_SUFFIX}）の見える移動量を、デバイス画素の整数倍へ丸める（{@code dpr} が 0 以下なら丸めない）。
     * 保持した層をずらして使うとき、小数の位置だと層の境で 1 画素ずれるため。姿勢だけに適用する。
     */
    public void setScrollSnapDpr(float dpr) { this.scrollSnapDpr = dpr; }

    /** 作った姿勢の数の累計（親との共有は数えない）。 */
    public long posesBuilt() { return posesBuilt; }

    /** 入力の列が前の {@link #run} と別の実体になるたびに進む。項目の位置で引く描画側の覚え（クリップ）の無効化に使う。 */
    public long generation() { return generation; }

    /**
     * 準備したフレーム {@code p} の描く列（シーンから組んだ列の後ろに重ね描き。M3b-4c-2b-2b から列は準備が組む）を、変換 {@code frame}
     * （CSS px → デバイス画素）の見た目で描画順にたどり、{@code painter} へ渡す（M3b-4c-2a）。
     *
     * @param damage 描き直す範囲（{@link SceneDamage} がシーンの違いから求めたもの）。null でなければ、補間・外の事情で変わる項目と重ね描きの範囲を足す
     */
    public void run(PreparedFrame p, Matrix2D frame, Painter painter, SceneDamage.Rects damage) {
        RenderFrame f = p.frame();
        List<DisplayItem> list = p.drawList();
        PaintOffsets offsets = p.offsets();
        SceneSource source = f.scene();
        long nowMs = f.nowMs();
        long prevNow = lastNow;
        lastNow = nowMs;
        boolean newList = list != lastList;
        if (newList) {
            generation++;
            lastList = list;
        }
        count = 0;
        if (list == null || list.isEmpty()) return;
        try {
            load(list);
            this.now = nowMs;
            this.exclude = f.excluded();
            this.frame = frame;
            this.anims = CssAnimator.states(sessionId, true);
            // 列が変わった: 列にある節の補間状態だけを残す（後片付けは実時間でなく、構造の変化の印。M3b-4b-2）
            if (newList) anims.retainNodesOf(list);
            this.structure = p.structure();
            index(offsets != null ? offsets : PaintOffsets.EMPTY);
            if (damage != null) {
                addDynamicDamage(damage, prevNow);
                addOverlayDamage(damage, p.sceneItemCount());
                if (!painter.beginWalk(damage)) return;
            }
            retainCount = source != null && source.scene() != null ? structure.retainCount() : 0;
            if (plan.length < retainCount) plan = new RetainPlan[Math.max(retainCount, plan.length * 2)];
            walk(painter);
        } finally {
            Arrays.fill(items, 0, count, null);
            Arrays.fill(res, 0, count, null);
            Arrays.fill(states, 0, count, null);
            Arrays.fill(chains, 0, count, null);
            Arrays.fill(plan, 0, retainCount, null);
            retainCount = 0;
            structure = FrameStructure.EMPTY;
            offsetOf.clear();
            layeredScratch.clear();
            count = 0;
            this.list = null;
            this.exclude = null;
        }
    }

    /** {@link #run} の、列だけのフレーム（シーンも描き直す範囲も無い）版。 */
    public void run(List<DisplayItem> list, long nowMs, Matrix2D frame, Painter painter) {
        run(PreparedFrame.full(RenderFrame.of(list, nowMs)), frame, painter, null);
    }

    private void load(List<DisplayItem> src) {
        int n = src.size();
        if (items.length < n) grow(Math.max(n, items.length * 2));
        this.list = src;
        for (int i = 0; i < n; i++) items[i] = src.get(i);
        count = n;
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

    // ---- 1 フレームの親・平行移動（列の階層から。フレームをまたいで持たない） ----

    private void index(PaintOffsets offsets) {
        int n = count;
        for (int i = 0; i < n; i++) {
            int p = structure.parent(i);
            parentIdx[i] = p < n ? p : -1;
            offX[i] = 0f;
            offY[i] = 0f;
            visiting[i] = false;
            members[i] = 0;
        }
        if (offsets.isEmpty()) return;
        if (offsets.isImmutable()) collectOffsets(offsets);
        else synchronized (offsets) { collectOffsets(offsets); }
    }

    /** 平行移動の表の移動量を、同じ ID の最初の節の項目に足す。 */
    private void collectOffsets(PaintOffsets eff) {
        for (int k = 0, esz = eff.size(); k < esz; k++) offsetOf.putIfAbsent(eff.idAt(k), k);
        for (int i = 0; i < count && !offsetOf.isEmpty(); i++) {
            DisplayItem it = items[i];
            if (it.type() != ElementType.NODE || it.id() == null) continue;
            Integer k = offsetOf.remove(it.id());
            if (k == null) continue;
            offX[i] = eff.dxAt(k);
            offY[i] = eff.dyAt(k);
        }
    }

    // ---- 描き直す範囲（M3b-4b-3b） ----

    /**
     * シーンの違いに現れない変化の範囲を {@code damage} へ足す: 前のフレームの時刻 {@code prevNow} か今の時刻に補間が動いている項目と、
     * 外の事情で絵が変わる項目（画像・動画・キャンバス・3D。部品の値・欄の中身は DOM の写しでシーンに入る）。補間が姿勢（変形・不透明度）を
     * 動かすときは、子孫の位置と層が変わるので全面にする。範囲は項目の箱に影・文字の余白を足し、姿勢で写した CSS px の外接矩形。
     */
    void addDynamicDamage(SceneDamage.Rects damage, long prevNow) {
        if (damage.full()) return;
        for (int i = 0; i < count; i++) {
            DisplayItem el = items[i];
            if (exclude != null && exclude.test(el)) continue; // 描かない節の部品は、動いても絵を変えない
            CssAnimator.AnimState st = stateAt(i);
            boolean activeNow = st.isActiveAt(now), activeBefore = prevNow >= 0L && st.isActiveAt(prevNow);
            if (activeNow || activeBefore) {
                int kind = Math.max(activeNow ? st.motionKindAt(now) : 0, activeBefore ? st.motionKindAt(prevNow) : 0);
                if (kind >= ACT_GEOMETRY) { damage.setFull("animation-geometry"); return; }
            } else if (!el.type().isExternallyDriven()) {
                continue;
            }
            if (el.type() == ElementType.NODE) continue; // 節は描かない（色の補間は部品の項目が持つ）
            addItemBounds(damage, el, resolveAt(i).pose());
            if (damage.full()) return;
        }
    }

    /**
     * {@link #addDynamicDamage}・{@link #addOverlayDamage} が前の {@link #run} から時刻 {@code nowMs} までに範囲を足すか（M3b-4b-4b。描く前の判定用で、
     * 索引・姿勢を作らない）: 重ね描きが今か前のフレームにある・外の事情で変わる項目がある・前の時刻か今に補間が
     * 動いている項目がある。補間は節の項目も数える（節の補間は部品の項目にも現れるので、多めに答えるだけ）。
     */
    public boolean changesOutsideScene(PreparedFrame p) {
        RenderFrame f = p.frame();
        List<DisplayItem> list = p.drawList();
        long nowMs = f.nowMs();
        int n = list.size(), from = p.sceneItemCount();
        if (prevOverlay.size() > 0) return true;
        for (int i = from; i < n; i++) if (list.get(i).type() != ElementType.NODE) return true;
        AnimationStates table = CssAnimator.states(sessionId, true);
        long prevNow = lastNow;
        java.util.function.Predicate<DisplayItem> skip = f.excluded();
        for (int i = 0; i < n; i++) {
            DisplayItem el = list.get(i);
            if (skip != null && skip.test(el)) continue;
            if (el.type().isExternallyDriven()) return true;
            CssAnimator.AnimState st = CssAnimator.stateFor(table, el, nowMs);
            if (st.isActiveAt(nowMs) || (prevNow >= 0L && st.isActiveAt(prevNow))) return true;
        }
        return false;
    }

    /** シーンの外から足した重ね描き（キャレット・選択の一覧・インスペクタ。列の位置 {@code from} から後ろ）の、前のフレームと今の範囲を足す。 */
    private void addOverlayDamage(SceneDamage.Rects damage, int from) {
        for (int i = 0, n = prevOverlay.size(); i < n; i++) {
            damage.add(prevOverlay.x0(i), prevOverlay.y0(i), prevOverlay.x1(i), prevOverlay.y1(i));
        }
        prevOverlay.clear();
        for (int i = from; i < count; i++) {
            if (items[i].type() == ElementType.NODE) continue;
            addItemBounds(prevOverlay, items[i], resolveAt(i).pose());
            addItemBounds(damage, items[i], resolveAt(i).pose());
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
     * 保持層の扱いを描画側に問う: 中身の節の祖先にグループ（不透明度・フィルター）がある・フィルターの錨の範囲に入っているときは問わない。
     * 外側が保持する範囲の中の入れ子は問わない。
     */
    private void planRetained(Painter painter) {
        int coveredUntil = 0;
        FrameStructure s = structure;
        for (int k = 0; k < retainCount; k++) {
            int node = s.retainNode(k);
            plan[k] = RetainPlan.NONE;
            if (s.retainFrom(k) < coveredUntil) continue;
            Resolved r = resolveAt(node);
            if (!r.groups().isEmpty() || r.opacity() < 0.999f) continue;
            RetainPlan d = painter.planRetained(items[node], node, s.retainFrom(k), s.retainTo(k), s.retainToken(k));
            plan[k] = d != null ? d : RetainPlan.NONE;
            if (plan[k] != RetainPlan.NONE) coveredUntil = s.retainTo(k);
        }
    }

    /** 位置 {@code i} から始まる、描画側が {@code answer} と答えた保持層の範囲の番号（無ければ -1）。 */
    private int retainAt(int i, RetainPlan answer) {
        for (int k = 0; k < retainCount; k++) if (structure.retainFrom(k) == i && plan[k] == answer) return k;
        return -1;
    }

    // ---- 走査 ----

    private void walk(Painter painter) {
        int n = count;
        planRetained(painter);
        Map<String, Integer> extraMembers = null; // 錨が列に無いグループだけ
        FrameStructure s = structure;
        for (int i = 0; i < n; i++) {
            int skip = retainCount > 0 ? retainAt(i, RetainPlan.SKIP) : -1;
            if (skip >= 0) { // 保持済みの層を重ねる範囲: 鎖も姿勢も要らない
                Arrays.fill(chains, i, s.retainTo(skip), null);
                i = s.retainTo(skip) - 1;
                continue;
            }
            DisplayItem el = items[i];
            if (el.type() == ElementType.NODE || (exclude != null && exclude.test(el))) { // 節の錨と、描かない節の部品は描かない
                chains[i] = null;
                continue;
            }
            Resolved r = resolveAt(i);
            List<Group> chain = null;
            if (r.opacity() > 0.005f) {
                chain = r.groups().isEmpty() ? List.of() : structure.isMask(i) ? maskChain(i, r.groups()) : r.groups();
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
        int[] open = retainCount > 0 ? new int[retainCount] : null; // 記録中の保持層の範囲の番号（入れ子の順）
        int openN = 0;
        for (int i = 0; i < n; i++) {
            if (retainCount > 0) {
                int skip = retainAt(i, RetainPlan.SKIP);
                if (skip >= 0) {
                    int node = s.retainNode(skip);
                    painter.syncLayers(List.of());
                    painter.drawRetained(items[node], node, s.retainFrom(skip), s.retainTo(skip));
                    i = s.retainTo(skip) - 1;
                    continue;
                }
                int rec = retainAt(i, RetainPlan.RECORD);
                if (rec >= 0) {
                    int node = s.retainNode(rec);
                    painter.syncLayers(List.of());
                    painter.beginRetained(items[node], node, s.retainFrom(rec), s.retainTo(rec));
                    open[openN++] = rec;
                }
            }
            List<Group> chain = chains[i];
            if (chain != null) drawChained(painter, i, chain, extraMembers);
            // 記録中の保持層の範囲の終わり: 層を保存して重ねる。保持できなければ、範囲をふつうに描き直す
            while (openN > 0 && s.retainTo(open[openN - 1]) == i + 1) {
                int k = open[--openN];
                int node = s.retainNode(k);
                painter.syncLayers(List.of());
                if (!painter.endRetained(items[node], node)) {
                    plan[k] = RetainPlan.NONE;
                    i = s.retainFrom(k) - 1;
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
            // フィルター・マスクのあるグループと、描く部品が 2 つ以上のグループだけ層にする
            // （部品が 1 つなら、部品ごとに不透明度を掛けても同じ。GPU の経路も保てる）
            int m = g.anchor() >= 0 ? members[g.anchor()] : (extraMembers != null ? extraMembers.getOrDefault(g.id(), 0) : 0);
            if (g.spec() != null || g.maskOf() != null || m > 1) layered.add(g);
            else flat *= g.opacity();
        }
        painter.syncLayers(layered);
        painter.drawItem(el, i, flat);
    }

    /**
     * マスク画像を描く項目 {@code i}（列の階層の印 {@link FrameStructure#isMask}）のグループの鎖: 祖先のグループの鎖の、マスクを持つ箱（項目の親の錨）の
     * グループの後ろにマスクの層を挟む（M3b-4c-2b-3b-2a。以前は錨からの列の範囲で引いていた）。層の中身は錨の子孫なので、ほかの項目の鎖は親子の鎖のまま。
     */
    private List<Group> maskChain(int i, List<Group> base) {
        int owner = parentIdx[i];
        for (int gi = 0, gn = base.size(); gi < gn; gi++) {
            Group g = base.get(gi);
            if (g.anchor() != owner || g.spec() == null || !g.spec().masked()) continue;
            List<Group> out = new ArrayList<>(gn + 1);
            out.addAll(base.subList(0, gi + 1));
            out.add(new Group(g.id() + "#mask", 1f, g.anchor(), null, g.map(), g.id(), g.pose()));
            out.addAll(base.subList(gi + 1, gn));
            return out;
        }
        return base;
    }

    // ---- 描画側が drawItem の間に引く値 ----

    /** 走査中の表示項目の列（描画側が範囲の項目を読む。走査の外では null）。 */
    public List<DisplayItem> items() { return list; }

    /**
     * 走査中の項目 {@code i} 自身か祖先（列の階層の親をたどる）のうち、ID が {@code id} の項目の位置（無ければ -1）。クリップの持ち主は
     * 項目の祖先の箱なので、列全体から引かずに親をたどる。
     */
    public int ancestorById(int i, String id) {
        if (id == null) return -1;
        for (int guard = 0; i >= 0 && i < count && guard < 1024; guard++) {
            if (id.equals(items[i].id())) return i;
            i = parentIdx[i];
        }
        return -1;
    }

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
        AnimatedValues av = stateAt(i).getInterpolatedValues(now);
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

    private CssAnimator.AnimState stateAt(int i) {
        CssAnimator.AnimState st = states[i];
        if (st == null) st = states[i] = CssAnimator.stateFor(anims, items[i], now);
        return st;
    }

    /** セッション {@code sessionId} の補間の状態で、{@code items} に時刻 {@code nowMs} でまだ動いているアニメーションがあるか。 */
    public static boolean anyActiveAt(String sessionId, List<DisplayItem> items, long nowMs) {
        AnimationStates states = CssAnimator.states(sessionId, true);
        for (DisplayItem el : items) if (CssAnimator.stateFor(states, el, nowMs).isActiveAt(nowMs)) return true;
        return false;
    }

    private static boolean hasFx(LayerSpec spec) { return spec != null && spec.hasEffects(); }

    private static boolean isIdentityGeometry(AnimatedValues a) {
        return a.opacity() == 1f && a.tx() == 0f && a.ty() == 0f && a.sx() == 1f && a.sy() == 1f && a.rot() == 0f && a.skew() == 0f;
    }

    private static boolean isScrollContent(DisplayItem it) {
        return DisplayItem.partOrdinalOf(it.key()) == DisplayItem.PART_SCROLL_CONTENT
                || (it.id() != null && it.id().endsWith(SCROLL_CONTENT_SUFFIX));
    }

    /** 姿勢を作る（前のフレームの姿勢は使い回さない）。 */
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
        boolean fx = node && hasFx(el.hints().layer());
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
