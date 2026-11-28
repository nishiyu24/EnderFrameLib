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
 * 走査器（M3b-4.6。計画書 §3.4）: 全バックエンド（CPU・GPU・Vulkan・MOD の面）の描画の経路。描画器の準備が組んだ表示項目の列
 * （スクロールコンテナは中身を 1 つの節にまとめてスクロール 0 で組む）を描画順にたどり、描画側（{@link Painter}）へ渡す。項目の姿勢
 * （親から引き継ぐ変形）・祖先の不透明度・グループ（不透明度・フィルター・マスクの層）の鎖を解くのはここ 1 か所。項目の親と保持層の範囲は、
 * 列を組んだ側がシーンの層をたどって書いた列の階層（{@link FrameStructure}。M3b-4.18）から読み、項目の鍵・ID から引き直さない。
 * 描画側へは項目・保持層の中身を層の文脈（{@link LayerContext}。姿勢・補間値・クリップの持ち主・中身のクリップと印）で渡し、列の位置で
 * 問わせない（M3b-4.23）。
 *
 * <p><b>列は前から 1 回だけ読む</b>（M3b-4.24a。4.24b で列をシーンのたどりに替える前提）: 項目を描くときに決めることは、その項目より前と、
 * 列の階層のその位置と、前の走査の記録だけで決める。グループを層にするか（部品が 2 つ以上か）は、部品が 1 つのうちは項目を 1 つだけ待たせ、
 * 次の項目がそのグループに続くかで決める（グループの部品は描画順で続く）。補間・外の事情で変わる項目の描き直す範囲と描く前の判定は、
 * 前の走査で描いた項目の記録（{@link OutsideSceneRecord}）から求める。保持層の扱いは範囲の始まりで問い、平行移動は節の姿勢を作るときに引く。</p>
 *
 * <p>フレームをまたいで持つのは、補間の状態（節ごとの表 {@link AnimationStates}。{@link CssAnimator} がセッションごとに持つ。列が別の
 * 実体になったら列にある節の分だけ残す）・前のフレームの時刻・列の同一性（{@link #generation}）・前のフレームの重ね描きの範囲と
 * 動く項目の記録（どれも比べる相手）だけ。索引・親の位置・姿勢は毎フレーム作り直す。スクロールの移動量と利用者の移動は、
 * ビューの平行移動の表（{@link PaintOffsets}）で節に足す。スレッドセーフではない。</p>
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

    /** グループの部品の数え（錨の位置ごと）: まだ見ていない・1 つ目を待たせている・2 つ以上（層にする）・1 つで終わった。 */
    private static final byte G_NONE = 0, G_ONE = 1, G_MANY = 2, G_ENDED = 3;

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
    private byte[] groupSeen = new byte[64];
    private final List<Group> layeredScratch = new ArrayList<>();
    /** このフレームの列の階層（項目の親・保持層の範囲。1 フレームの間だけ）。 */
    private FrameStructure structure = FrameStructure.EMPTY;
    private int retainCount;
    /** 記録中の保持層の範囲の番号と、記録を始めたときの動く項目の記録の長さ（入れ子の順）。 */
    private int[] open = new int[4], openMark = new int[4];
    /** 平行移動の表の ID → 番号と移動量（1 フレームの間だけ。節の姿勢を作るときに、同じ ID の最初の節へ 1 度だけ足す）。 */
    private final Map<String, Integer> offsetOf = new HashMap<>();
    private float[] offDx = new float[8], offDy = new float[8];
    /** 部品が 1 つのグループを持つので、描くのを待たせている項目（無ければ -1）と、その鎖。 */
    private int pending = -1;
    private List<Group> pendingChain;
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
    /** 前の走査で描いた、補間・外の事情で変わりうる項目（{@link OutsideSceneRecord}）。 */
    private final OutsideSceneRecord outside = new OutsideSceneRecord();

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
     * 準備したフレーム {@code p} の描く列（シーンから組んだ列の後ろに重ね描き。M3b-4.15 から列は準備が組む）を、変換 {@code frame}
     * （CSS px → デバイス画素）の見た目で描画順にたどり、{@code painter} へ渡す（M3b-4.12）。
     *
     * @param damage 描き直す範囲（{@link SceneDamage} がシーンの違いから求めたもの）。null でなければ、補間・外の事情で変わる項目
     *               （前の走査の記録）と重ね描きの範囲を足す
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
        if (list == null || list.isEmpty()) {
            outside.clear();
            return;
        }
        try {
            load(list);
            this.now = nowMs;
            this.exclude = f.excluded();
            this.frame = frame;
            this.anims = CssAnimator.states(sessionId, true);
            // 列が変わった: 列にある節の補間状態だけを残す（後片付けは実時間でなく、構造の変化の印。M3b-4.3）
            if (newList) anims.retainNodesOf(list);
            this.structure = p.structure();
            index(offsets != null ? offsets : PaintOffsets.EMPTY);
            if (damage != null) {
                if (!damage.full()) outside.addDamage(damage, nowMs, prevNow);
                addOverlayDamage(damage, p.sceneItemCount());
                if (!painter.beginWalk(damage)) return;
            }
            retainCount = source != null && source.scene() != null ? structure.retainCount() : 0;
            outside.begin();
            walk(painter);
            outside.commit();
        } finally {
            Arrays.fill(items, 0, count, null);
            Arrays.fill(res, 0, count, null);
            Arrays.fill(states, 0, count, null);
            retainCount = 0;
            structure = FrameStructure.EMPTY;
            offsetOf.clear();
            layeredScratch.clear();
            pending = -1;
            pendingChain = null;
            count = 0;
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
        for (int i = 0; i < n; i++) items[i] = src.get(i);
        count = n;
    }

    private void grow(int cap) {
        items = Arrays.copyOf(items, cap);
        parentIdx = Arrays.copyOf(parentIdx, cap);
        res = Arrays.copyOf(res, cap);
        states = Arrays.copyOf(states, cap);
        visiting = Arrays.copyOf(visiting, cap);
        groupSeen = Arrays.copyOf(groupSeen, cap);
    }

    // ---- 1 フレームの親・平行移動（列の階層から。フレームをまたいで持たない） ----

    private void index(PaintOffsets offsets) {
        int n = count;
        for (int i = 0; i < n; i++) {
            int p = structure.parent(i);
            parentIdx[i] = p < n ? p : -1;
            visiting[i] = false;
            groupSeen[i] = G_NONE;
        }
        if (offsets.isEmpty()) return;
        if (offsets.isImmutable()) collectOffsets(offsets);
        else synchronized (offsets) { collectOffsets(offsets); }
    }

    /** 平行移動の表を ID で引ける形に写す（同じ ID が並べば先のもの）。節に足すのは姿勢を作るとき（{@link #build}）。 */
    private void collectOffsets(PaintOffsets eff) {
        int n = eff.size();
        if (offDx.length < n) {
            offDx = new float[Math.max(n, offDx.length * 2)];
            offDy = new float[offDx.length];
        }
        for (int k = 0; k < n; k++) {
            if (offsetOf.putIfAbsent(eff.idAt(k), k) != null) continue;
            offDx[k] = eff.dxAt(k);
            offDy[k] = eff.dyAt(k);
        }
    }

    // ---- 描き直す範囲（M3b-4.5） ----

    /**
     * 補間・外の事情で変わる項目と重ね描きの前後の範囲を足さずに、描き直しが要るかを答える（描く前の判定用で、列をたどらず索引・姿勢を作らない。
     * M3b-4.7・4.24a）: 重ね描きが今か前のフレームにある・前の走査で描いた項目に外の事情で変わるものがある・その補間が前の時刻か今に動いている。
     */
    public boolean changesOutsideScene(PreparedFrame p) {
        if (prevOverlay.size() > 0) return true;
        List<DisplayItem> overlays = p.frame().overlays();
        for (int i = 0, n = overlays.size(); i < n; i++) if (overlays.get(i).type() != ElementType.NODE) return true;
        return outside.changes(p.frame().nowMs(), lastNow);
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
        float[] b = OutsideSceneRecord.bounds(el, pose, frame, boundsScratch);
        damage.add(b[0], b[1], b[2], b[3]);
    }

    private final float[] boundsScratch = new float[4];

    /**
     * 描く順に来た項目 {@code i} を、補間・外の事情で変わりうるなら記録する（次のフレームの描き直す範囲と描く前の判定。{@link OutsideSceneRecord}）。
     * 補間の状態は、補間が走った・キーフレームを持つものだけ（宣言だけのトランジションは、始まるときに目標の変化としてシーンの違いに出る）。
     */
    private void record(int i, DisplayItem el) {
        boolean external = el.type().isExternallyDriven();
        // 補間が走るのは、トランジションかキーフレームを宣言した項目だけ（宣言の無い項目の状態は引かない。節の錨は姿勢を作らないなら引かない）
        if (!external && el.transitionDuration() <= 0 && (el.animDef() == null || el.animDef().isEmpty())) return;
        CssAnimator.AnimState st = stateAt(i);
        boolean animated = st.durationMs > 0 || st.currentAnimDef != null;
        if (!external && !animated) return;
        if (el.type() == ElementType.NODE) { // 節は描かない（姿勢の補間だけを見る）
            outside.add(st, false, false, 0f, 0f, 0f, 0f);
            return;
        }
        float[] b = OutsideSceneRecord.bounds(el, resolveAt(i).pose(), frame, boundsScratch);
        outside.add(animated ? st : null, external, true, b[0], b[1], b[2], b[3]);
    }

    // ---- 走査 ----

    /**
     * 列を前から 1 回たどって描く（M3b-4.24a）。保持層の範囲は始まりで描画側に問う（外側が先。外側が保持する範囲の中の入れ子は問わない）。
     * 中身の節の祖先にグループ（不透明度・フィルター）がある範囲は問わない。
     */
    private void walk(Painter painter) {
        int n = count;
        FrameStructure s = structure;
        int nextRange = 0, coveredUntil = 0, openN = 0;
        for (int i = 0; i < n; i++) {
            boolean skipped = false;
            while (nextRange < retainCount && s.retainFrom(nextRange) <= i) {
                int k = nextRange++;
                if (s.retainFrom(k) < i || s.retainFrom(k) < coveredUntil) continue;
                RetainPlan d = planRetained(painter, k);
                if (d == RetainPlan.NONE) continue;
                flush(painter);
                painter.syncLayers(List.of());
                coveredUntil = s.retainTo(k);
                if (d == RetainPlan.SKIP) { // 保持済みの層を重ねる範囲: 鎖も姿勢も要らない
                    painter.drawRetained(contentAt(k));
                    i = s.retainTo(k) - 1;
                    skipped = true;
                    break;
                }
                painter.beginRetained(contentAt(k));
                if (openN == open.length) {
                    open = Arrays.copyOf(open, openN * 2);
                    openMark = Arrays.copyOf(openMark, openN * 2);
                }
                openMark[openN] = outside.mark();
                open[openN++] = k;
            }
            if (skipped) continue;
            DisplayItem el = items[i];
            if (exclude == null || !exclude.test(el)) { // 描かない節の部品は描かない（動いても絵を変えない）
                record(i, el);
                if (el.type() != ElementType.NODE) { // 節の錨は描かない
                    Resolved r = resolveAt(i);
                    if (r.opacity() > 0.005f) {
                        List<Group> chain = r.groups().isEmpty() ? List.of() : s.isMask(i) ? maskChain(i, r.groups()) : r.groups();
                        submit(painter, i, chain);
                    }
                }
            }
            // 記録中の保持層の範囲の終わり: 層を保存して重ねる。保持できなければ、範囲をふつうに描き直す
            while (openN > 0 && s.retainTo(open[openN - 1]) == i + 1) {
                int k = open[--openN];
                flush(painter);
                painter.syncLayers(List.of());
                if (!painter.endRetained(contentAt(k))) {
                    outside.truncate(openMark[openN]);
                    Arrays.fill(groupSeen, s.retainFrom(k), s.retainTo(k), G_NONE);
                    i = s.retainFrom(k) - 1;
                    nextRange = k + 1; // 中の範囲は外側が問うたので問わない（coveredUntil の中）
                    openN = 0;
                    break;
                }
            }
        }
        flush(painter);
        painter.syncLayers(List.of());
    }

    private RetainPlan planRetained(Painter painter, int k) {
        Resolved r = resolveAt(structure.retainNode(k));
        if (!r.groups().isEmpty() || r.opacity() < 0.999f) return RetainPlan.NONE;
        RetainPlan d = painter.planRetained(contentAt(k));
        return d != null ? d : RetainPlan.NONE;
    }

    /**
     * 描く順に来た項目 {@code i}（グループの鎖 {@code chain}）を描く。鎖に部品の数がまだ 1 つのグループがあれば、層にするかが決まるまで 1 つだけ
     * 待たせる（{@link #settle}）。フィルター・マスクのあるグループはいつも層にするので数えない。
     */
    private void submit(Painter painter, int i, List<Group> chain) {
        if (pending >= 0) settle(painter, chain);
        boolean wait = false;
        for (int gi = 0, gn = chain.size(); gi < gn; gi++) {
            Group g = chain.get(gi);
            if (!counted(g) || groupSeen[g.anchor()] == G_MANY) continue;
            groupSeen[g.anchor()] = G_ONE; // 初めて（か、1 つで終わった後にまた）部品が来た
            wait = true;
        }
        if (!wait) {
            drawChained(painter, i, chain);
            return;
        }
        pending = i;
        pendingChain = chain;
    }

    /**
     * 待たせた項目のグループのうち部品が 1 つのものを、次に描く項目の鎖 {@code next}（無ければ null）にも入っているかで決めてから描く: 入っていれば
     * 部品が 2 つ以上（層にする）、入っていなければ 1 つで終わった（グループの部品は描画順で続くので、不透明度を項目に掛けても同じ絵）。
     */
    private void settle(Painter painter, List<Group> next) {
        int p = pending;
        List<Group> chain = pendingChain;
        pending = -1;
        pendingChain = null;
        for (int gi = 0, gn = chain.size(); gi < gn; gi++) {
            Group g = chain.get(gi);
            if (!counted(g) || groupSeen[g.anchor()] != G_ONE) continue;
            groupSeen[g.anchor()] = next != null && containsSame(next, g) ? G_MANY : G_ENDED;
        }
        drawChained(painter, p, chain);
    }

    /** 待たせた項目があれば、そのグループを終わったものとして描く（保持層の範囲の前後・走査の終わり）。 */
    private void flush(Painter painter) {
        if (pending >= 0) settle(painter, null);
    }

    private static boolean counted(Group g) { return g.spec() == null && g.maskOf() == null; }

    private static boolean containsSame(List<Group> chain, Group g) {
        for (int i = 0, n = chain.size(); i < n; i++) if (chain.get(i) == g) return true;
        return false;
    }

    private void drawChained(Painter painter, int i, List<Group> chain) {
        DisplayItem el = items[i];
        if (chain.isEmpty()) {
            painter.syncLayers(List.of());
            painter.drawItem(el, itemAt(i), 1f);
            return;
        }
        List<Group> layered = layeredScratch;
        layered.clear();
        float flat = 1f;
        for (int gi = 0, gn = chain.size(); gi < gn; gi++) {
            Group g = chain.get(gi);
            // フィルター・マスクのあるグループと、描く部品が 2 つ以上のグループだけ層にする
            // （部品が 1 つなら、部品ごとに不透明度を掛けても同じ。GPU の経路も保てる）
            if (!counted(g) || groupSeen[g.anchor()] == G_MANY) layered.add(g);
            else flat *= g.opacity();
        }
        painter.syncLayers(layered);
        painter.drawItem(el, itemAt(i), flat);
    }

    /**
     * マスク画像を描く項目 {@code i}（列の階層の印 {@link FrameStructure#isMask}）のグループの鎖: 祖先のグループの鎖の、マスクを持つ箱（項目の親の錨）の
     * グループの後ろにマスクの層を挟む（M3b-4.19。以前は錨からの列の範囲で引いていた）。層の中身は錨の子孫なので、ほかの項目の鎖は親子の鎖のまま。
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

    // ---- 描画側へ渡す層の文脈（M3b-4c-2b-3b-2b-4a。描画側は列の位置で問わない） ----

    /** 描く項目の文脈（使い回す。{@link Painter#drawItem} の間だけ有効）。 */
    private final class ItemView implements LayerContext.Item {
        int i;

        @Override public Resolved resolved() { return resolveAt(i); }

        @Override public AnimatedValues anim() { return animAt(i); }

        @Override
        public Resolved owner(String id) {
            int ix = ancestorById(i, id);
            return ix >= 0 ? resolveAt(ix) : null;
        }
    }

    /** 保持層の中身の文脈（使い回す。保持の呼び出しの間だけ有効）。範囲は列の階層の {@link FrameStructure#retainFrom} から。 */
    private final class ContentView implements LayerContext.Content {
        int k;

        private int node() { return structure.retainNode(k); }

        @Override public String id() { return items[node()].id(); }

        @Override
        public String ownerId() { // 中身の節の ID はコンテナの ID に接尾辞を足したもの（HtmlNode#scrollContentKey）
            String id = id();
            return id.endsWith(SCROLL_CONTENT_SUFFIX) ? id.substring(0, id.length() - SCROLL_CONTENT_SUFFIX.length()) : id;
        }

        @Override public Object token() { return structure.retainToken(k); }

        @Override public Resolved resolved() { return resolveAt(node()); }

        @Override
        public Resolved owner(String id) {
            int ix = ancestorById(node(), id);
            return ix >= 0 ? resolveAt(ix) : null;
        }

        @Override public com.nishiyu.enderframelib.core.paint.model.ClipRect clip() { return structure.contentClip(k, items, ownerId()); }

        @Override public boolean retainable() { return structure.contentRetainable(k, items); }
    }

    private final ItemView itemView = new ItemView();
    private final ContentView contentView = new ContentView();

    private LayerContext.Item itemAt(int i) {
        itemView.i = i;
        return itemView;
    }

    private LayerContext.Content contentAt(int k) {
        contentView.k = k;
        return contentView;
    }

    /**
     * 走査中の項目 {@code i} 自身か祖先（列の階層の親をたどる）のうち、ID が {@code id} の項目の位置（無ければ -1）。クリップの持ち主は
     * 項目の祖先の箱なので、列全体から引かずに親をたどる。
     */
    private int ancestorById(int i, String id) {
        if (id == null) return -1;
        for (int guard = 0; i >= 0 && i < count && guard < 1024; guard++) {
            if (id.equals(items[i].id())) return i;
            i = parentIdx[i];
        }
        return -1;
    }

    /** 項目 {@code i} の姿勢（このフレームで 1 回だけ作る。姿勢の変わらない部品は親のものを共有する）。 */
    private Resolved resolveAt(int i) {
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
    private AnimatedValues animAt(int i) {
        Resolved r = resolveAt(i);
        int p = parentIdx[i];
        return p >= 0 && p != i && res[p] == r ? states[i].getInterpolatedValues(now) : r.anim();
    }

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
        float atx = anim.tx(), aty = anim.ty();
        if (node && !offsetOf.isEmpty() && el.id() != null) { // 平行移動の表の移動量は、同じ ID の最初の節へ 1 度だけ
            Integer k = offsetOf.remove(el.id());
            if (k != null) {
                atx += offDx[k];
                aty += offDy[k];
            }
        }
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
