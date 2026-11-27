package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.ClipShape;
import com.nishiyu.enderframelib.core.paint.model.FilterOp;
import com.nishiyu.enderframelib.core.paint.model.RenderHints;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;
import com.nishiyu.enderframelib.core.paint.scene.DisplayScene;
import com.nishiyu.enderframelib.core.paint.scene.DrawOps;
import com.nishiyu.enderframelib.core.paint.scene.DynamicSceneState;
import com.nishiyu.enderframelib.core.paint.scene.LayerEffects;
import com.nishiyu.enderframelib.core.paint.scene.PlacedLayer;
import com.nishiyu.enderframelib.core.paint.scene.SceneChildren;
import com.nishiyu.enderframelib.core.paint.scene.SceneChunk;
import com.nishiyu.enderframelib.core.paint.scene.SceneLayer;
import com.nishiyu.enderframelib.core.paint.scene.Transform2D;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 部分の描き直しの範囲（M3b-4b-3b。計画書 §3.4「M3b-4b の設計」）: 前のフレームと今のフレームの {@link DisplayScene} と動的な値
 * （スクロール量・ビューポート）から、絵が変わりうる範囲（CSS px のビューポート座標の矩形の和）を求める。表示項目の列を比べない。
 *
 * <p><b>求め方</b>: 2 つのシーンを根から対にしてたどり、{@link com.nishiyu.enderframelib.core.paint.scene.SceneEmitter} が使い回した層
 * （{@code ==}）で同じ位置のものは下りない。作り直した層は、位置・大きさ・変形・効果・切り抜きが同じなら自分の描画命令だけを比べて
 * 子へ下り（子は同一性・レイアウトの箱・節の順で対にする）、そうでなければ前後の部分木の範囲を丸ごと足す。消えた子・増えた子・
 * 描く順の入れ替わった子も部分木の範囲を足す。スクロール量の変わったコンテナは枠の範囲を丸ごと足して下りない（中身は枠で切られる）。</p>
 *
 * <p>範囲は描画命令の矩形に、影のぼかし・文字の大きさの余白を足し、祖先の変形（回転は外接円）とフィルターの広がりで写した保守的な
 * 見積もり。全面になるのは: 初回・ビューポートの変化・文書のスクロール・アンカーの追従・{@code backdrop-filter}・キャンバスの背景の変化・
 * せん断・入れ子の深すぎる部分木。補間（アニメーション）と外の事情で変わる項目は走査器が足す（{@link SceneWalker#addDynamicDamage}）。</p>
 *
 * <p>覚えるのは前のフレームのシーンとスクロール量だけ（記憶の鍵ではなく、比べる相手）。スレッドセーフではない。</p>
 */
public final class SceneDamage {
    /** 描画命令の矩形に足す余白の最小（丸め・アンチエイリアスの縁。CSS px）。 */
    static final float PAD = 2f;

    /** 描き直す範囲。全面（{@link #full}）か、CSS px の矩形の和。 */
    public static final class Rects {
        private float[] r = new float[32];
        private int n;
        private boolean full;
        private String why;

        public void clear() { n = 0; full = false; why = null; }
        /** 全面にする（{@code why} は診断用の理由）。 */
        public void setFull(String why) { if (!full) this.why = why; full = true; n = 0; }
        public boolean full() { return full; }
        /** 全面にした理由（診断用。全面でなければ null）。 */
        public String fullReason() { return why; }
        public int size() { return n; }
        public boolean isEmpty() { return !full && n == 0; }
        public float x0(int i) { return r[i * 4]; }
        public float y0(int i) { return r[i * 4 + 1]; }
        public float x1(int i) { return r[i * 4 + 2]; }
        public float y1(int i) { return r[i * 4 + 3]; }

        /** 矩形を足す（空・非有限は捨てる。非有限は全面にする）。 */
        public void add(float x0, float y0, float x1, float y1) {
            if (full) return;
            if (!Float.isFinite(x0) || !Float.isFinite(y0) || !Float.isFinite(x1) || !Float.isFinite(y1)) { setFull("non-finite"); return; }
            if (x1 <= x0 || y1 <= y0) return;
            if (n * 4 == r.length) r = Arrays.copyOf(r, r.length * 2);
            int b = n++ * 4;
            r[b] = x0; r[b + 1] = y0; r[b + 2] = x1; r[b + 3] = y1;
        }

        /** 点（画素の中心 {@code (px, py)}）が範囲に入るか。 */
        public boolean covers(float px, float py) {
            if (full) return true;
            for (int i = 0, b = 0; i < n; i++, b += 4) {
                if (px >= r[b] && px < r[b + 2] && py >= r[b + 1] && py < r[b + 3]) return true;
            }
            return false;
        }

        /** 矩形と交わるか。 */
        public boolean intersects(float x0, float y0, float x1, float y1) {
            if (full) return true;
            for (int i = 0, b = 0; i < n; i++, b += 4) {
                if (x1 > r[b] && x0 < r[b + 2] && y1 > r[b + 1] && y0 < r[b + 3]) return true;
            }
            return false;
        }

        /** 外接矩形 {@code {x0, y0, x1, y1}}（空なら null）。 */
        public float[] bounds() {
            if (n == 0) return null;
            float x0 = Float.POSITIVE_INFINITY, y0 = x0, x1 = Float.NEGATIVE_INFINITY, y1 = x1;
            for (int i = 0, b = 0; i < n; i++, b += 4) {
                x0 = Math.min(x0, r[b]); y0 = Math.min(y0, r[b + 1]); x1 = Math.max(x1, r[b + 2]); y1 = Math.max(y1, r[b + 3]);
            }
            return new float[]{x0, y0, x1, y1};
        }
    }

    /** 変形とフィルターの広がりの鎖（外側が先）。範囲は内側から外へ写す。 */
    private static final class Chain {
        float[] e = new float[8 * 9];
        int n;

        void pushTransform(PlacedLayer box, Transform2D t) {
            ensure();
            int b = n++ * 9;
            e[b] = 0f;
            e[b + 1] = box.x + box.layer.w * t.originX();
            e[b + 2] = box.y + box.layer.h * t.originY();
            e[b + 3] = t.tx(); e[b + 4] = t.ty(); e[b + 5] = t.sx(); e[b + 6] = t.sy();
            e[b + 7] = t.rotate(); e[b + 8] = t.skew();
        }

        void pushOutset(float outset) {
            ensure();
            int b = n++ * 9;
            e[b] = 1f;
            e[b + 1] = outset;
        }

        private void ensure() { if ((n + 1) * 9 > e.length) e = Arrays.copyOf(e, e.length * 2); }
    }

    private DisplayScene prevScene;
    private DynamicSceneState prevDyn;
    private Map<HtmlNode, float[]> prevScroll = Map.of();
    private final Rects out = new Rects();
    private final IdentityHashMap<HtmlNode, Boolean> scrolled = new IdentityHashMap<>();
    /** スクロール量の変わったコンテナと、その祖先の層（前後のシーンの両方。使い回した層でも下りる道）。 */
    private final IdentityHashMap<SceneLayer, Boolean> onPath = new IdentityHashMap<>();
    private final Chain chain = new Chain();
    /** 層の命令を読むカーソル（使い回す）。 */
    private final DrawOps.Cursor cursor = new DrawOps.Cursor();
    /** たどっている間の前（{@code a} の側）と今（{@code b} の側）の動的な値。 */
    private DynamicSceneState dynA, dynB;
    private int depth;
    /** 診断用: 下りた対の数（前のフレームの使い回しで飛ばした部分木は数えない）。 */
    private long visitedPairs;
    /** 診断用: 前と同じ位置の同じチャンクで、中へ下りずに飛ばした数。 */
    private long skippedChunks;

    /** 前のフレームを忘れる（次は全面）。 */
    public void reset() {
        prevScene = null;
        prevDyn = null;
        prevScroll = Map.of();
    }

    public long visitedPairs() { return visitedPairs; }

    public long skippedChunks() { return skippedChunks; }

    /**
     * 前に渡したフレームから、{@code scene} と {@code dyn} のフレームまでに描き直す範囲を返し、今のフレームを覚える。返す値は次の呼び出しまで有効。
     */
    public Rects next(DisplayScene scene, DynamicSceneState dyn) {
        Objects.requireNonNull(scene, "scene");
        DynamicSceneState cur = dyn != null ? dyn : DynamicSceneState.of(null, scene.root.w, scene.root.h);
        Map<HtmlNode, float[]> curScroll = cur.scroll.snapshot();
        out.clear();
        try {
            compute(scene, cur, curScroll);
        } finally {
            scrolled.clear();
            onPath.clear();
            chain.n = 0;
            depth = 0;
            prevScene = scene;
            prevScroll = curScroll;
            prevDyn = DynamicSceneState.of(copyOf(curScroll), cur.viewportW, cur.viewportH);
        }
        return out;
    }

    private void compute(DisplayScene scene, DynamicSceneState cur, Map<HtmlNode, float[]> curScroll) {
        if (prevScene == null || prevDyn.viewportW != cur.viewportW || prevDyn.viewportH != cur.viewportH) { out.setFull("first-or-viewport"); return; }
        if (!collectScrolled(scene, curScroll)) { out.setFull("document-scroll-or-anchor"); return; }
        if (prevScene == scene && scrolled.isEmpty()) return;
        if (scene.hasBackdropFilter() || prevScene.hasBackdropFilter()) { out.setFull("backdrop-filter"); return; }
        if (!scrolled.isEmpty()) {
            markPaths(prevScene.root);
            markPaths(scene.root);
        }
        SceneLayer prevCanvas = isRoot(prevScene.root) ? PaintSupport.canvasBackgroundSource(prevScene.root) : null;
        SceneLayer curCanvas = isRoot(scene.root) ? PaintSupport.canvasBackgroundSource(scene.root) : null;
        if ((prevCanvas == null) != (curCanvas == null)
                || (prevCanvas != null && prevCanvas.style.typed().visual != curCanvas.style.typed().visual)) { out.setFull("canvas-background"); return; }
        this.dynA = prevDyn;
        this.dynB = cur;
        try {
            diff(PlacedLayer.root(prevScene.root), PlacedLayer.root(scene.root));
        } finally {
            this.dynA = null;
            this.dynB = null;
        }
    }

    private static boolean isRoot(SceneLayer l) {
        return l.node != null && "root".equals(l.node.tag);
    }

    /**
     * スクロール量の変わった節を集める。文書のスクロール（根・html・body）と、アンカーが追従する文書のスクロールは全面（false）。
     * スクロールコンテナでない節の値は描画に効かないので数えない。
     */
    private boolean collectScrolled(DisplayScene scene, Map<HtmlNode, float[]> curScroll) {
        for (int pass = 0; pass < 2; pass++) {
            Map<HtmlNode, float[]> from = pass == 0 ? curScroll : prevScroll, other = pass == 0 ? prevScroll : curScroll;
            for (Map.Entry<HtmlNode, float[]> e : from.entrySet()) {
                HtmlNode node = e.getKey();
                if (scrolled.containsKey(node) || Arrays.equals(e.getValue(), other.get(node))) continue;
                SceneLayer now = scene.layerByNodeId(node.nodeId()), was = prevScene.layerByNodeId(node.nodeId());
                boolean container = (now != null && now.isScrollContainer()) || (was != null && was.isScrollContainer());
                if (!container) continue;
                String tag = node.tag;
                if ("root".equals(tag) || "html".equals(tag) || "body".equals(tag)) return false;
                scrolled.put(node, Boolean.TRUE);
            }
        }
        return scrolled.isEmpty() || !(scene.hasAnchoredDescendants() || prevScene.hasAnchoredDescendants());
    }

    /** スクロール量の変わったコンテナまでの道の層に印を付ける（深さ優先。コンテナを含む部分木の根の印を返す）。 */
    private boolean markPaths(SceneLayer l) {
        boolean hit = l.isScrollContainer() && l.node != null && scrolled.containsKey(l.node);
        for (var c : l.children.chunks) {
            for (SceneLayer k : c.layers) hit |= markPaths(k);
        }
        if (hit) onPath.put(l, Boolean.TRUE);
        return hit;
    }

    private static ScrollState copyOf(Map<HtmlNode, float[]> snapshot) {
        ScrollState s = new ScrollState();
        for (Map.Entry<HtmlNode, float[]> e : snapshot.entrySet()) s.setScroll(e.getKey(), e.getValue()[0], e.getValue()[1]);
        return s;
    }

    // ---- 対にしてたどる ----

    /** 対にした箱 {@code a}（前）・{@code b}（今）の違いを足す。鎖は {@code b} の祖先のもの（同じ枠の対だけが下りるので前も同じ）。 */
    private void diff(PlacedLayer a, PlacedLayer b) {
        if (out.full) return;
        visitedPairs++;
        SceneLayer la = a.layer, lb = b.layer;
        // 使い回した層が同じ位置: 部分木ごと同じ（スクロール量の変わったコンテナを含む部分木だけは、そこまで下りる）
        if (la == lb && a.x == b.x && a.y == b.y && !onPath.containsKey(lb)) return;
        if (!sameFrame(a, b)) {
            subtree(a, dynA);
            subtree(b, dynB);
            return;
        }
        int mark = chain.n;
        try {
            push(b);
            boolean scrolledHere = lb.isScrollContainer() && lb.node != null && scrolled.containsKey(lb.node);
            if (scrolledHere) {
                if ((lb.propagatedLayers & SceneLayer.LAYER_HOIST) != 0) { out.setFull("hoisted-in-scroller"); return; }
                if (!(la.clipsX() && la.clipsY() && lb.clipsX() && lb.clipsY())) { // 片方の軸だけ切る: 中身が枠の外へはみ出す
                    subtree(a, dynA);
                    subtree(b, dynB);
                    return;
                }
                own(a);
                own(b);
                box(b); // 中身は枠で切られる: 枠を丸ごと描き直す
                return;
            }
            if (la != lb && ownDiffers(la, lb)) {
                own(a);
                own(b);
            }
            if (la.children != lb.children || onPath.containsKey(lb)) children(a, b);
        } finally {
            chain.n = mark;
        }
    }

    /**
     * 子を対にする: 同じ層 → 同じレイアウトの箱 → 同じ節。対にならない子・描く順の入れ替わった子は部分木を足す。
     * 前と同じ位置の同じチャンク（{@code ==}。{@code SceneEmitter} が使い回したもの）は、中の層が全部自分と同じ位置で対になり違いが
     * 無いので下りない（スクロール量の変わったコンテナへの道の層を含むチャンクは下りる）。親が同じ位置（{@link #sameFrame}）で、
     * 原点の箱のスクロールが変わっていない（変わったコンテナは {@link #diff} が中へ下りない）ので、チャンクの中の子の位置も前と同じ。
     * 子を対にする表は、違うチャンクがあるときだけ作る。
     */
    private void children(PlacedLayer a, PlacedLayer b) {
        SceneChildren ac = a.layer.children, bc = b.layer.children;
        int an = ac.size;
        int[] aBase = new int[ac.chunks.length + 1];
        for (int k = 0; k < ac.chunks.length; k++) aBase[k + 1] = aBase[k] + ac.chunks[k].layers.length;
        boolean[] used = new boolean[an];
        IdentityHashMap<Object, Integer> byLayer = null, byBox = null, byNode = null;
        int lastMatched = -1;
        for (int k = 0; k < bc.chunks.length && !out.full; k++) {
            SceneChunk ck = bc.chunks[k];
            if (k < ac.chunks.length && ac.chunks[k] == ck && ac.chunkX[k] == bc.chunkX[k] && ac.chunkY[k] == bc.chunkY[k]
                    && aBase[k] > lastMatched && unusedRange(used, aBase[k], aBase[k + 1]) && !onPathIn(ck)) {
                for (int i = aBase[k]; i < aBase[k + 1]; i++) used[i] = true;
                if (aBase[k + 1] > aBase[k]) lastMatched = aBase[k + 1] - 1;
                skippedChunks++;
                continue;
            }
            if (byLayer == null) {
                byLayer = new IdentityHashMap<>(an * 2);
                byBox = new IdentityHashMap<>(an * 2);
                byNode = new IdentityHashMap<>(an * 2);
                int i = 0;
                for (SceneChunk ak : ac.chunks) {
                    for (SceneLayer l : ak.layers) {
                        byLayer.putIfAbsent(l, i);
                        if (l.frag != null && l.frag.box != null) byBox.putIfAbsent(l.frag.box, i);
                        if (l.node != null && !l.anonymous()) byNode.putIfAbsent(l.node, i);
                        i++;
                    }
                }
            }
            for (SceneLayer l : ck.layers) {
                if (out.full) return;
                PlacedLayer nb = b.child(l, bc.chunkX[k], bc.chunkY[k], dynB);
                int i = take(byLayer, l, used);
                if (i < 0 && l.frag != null && l.frag.box != null) i = take(byBox, l.frag.box, used);
                if (i < 0 && l.node != null && !l.anonymous()) i = take(byNode, l.node, used);
                if (i < 0) {
                    subtree(nb, dynB);
                    continue;
                }
                used[i] = true;
                PlacedLayer na = childAt(a, aBase, i);
                if (i < lastMatched) { // 描く順が入れ替わった（重なりの順が変わりうる）
                    subtree(na, dynA);
                    subtree(nb, dynB);
                    continue;
                }
                lastMatched = i;
                diff(na, nb);
            }
        }
        for (int i = 0; i < an && !out.full; i++) if (!used[i]) subtree(childAt(a, aBase, i), dynA);
    }

    private static boolean unusedRange(boolean[] used, int from, int to) {
        for (int i = from; i < to; i++) if (used[i]) return false;
        return true;
    }

    private boolean onPathIn(SceneChunk c) {
        if (onPath.isEmpty()) return false;
        for (SceneLayer l : c.layers) if (onPath.containsKey(l)) return true;
        return false;
    }

    /** 前の側の {@code i} 番目の子の置き場所。 */
    private PlacedLayer childAt(PlacedLayer a, int[] aBase, int i) {
        SceneChildren ac = a.layer.children;
        int k = 0;
        while (aBase[k + 1] <= i) k++;
        return a.child(ac.chunks[k].layers[i - aBase[k]], ac.chunkX[k], ac.chunkY[k], dynA);
    }

    private static int take(IdentityHashMap<Object, Integer> index, Object key, boolean[] used) {
        Integer i = index.get(key);
        return i != null && !used[i] ? i : -1;
    }

    /**
     * 子孫の写し方（位置・変形・効果・切り抜き・重なり）が同じか。大きさ・枠・余白は、それで子孫を切る・写す箱（はみ出しを切る・
     * スクロールする・変形・効果・切り抜きを持つ）でだけ比べる（それ以外の箱の大きさの違いは自分の描画命令に現れる）。
     */
    private static boolean sameFrame(PlacedLayer a, PlacedLayer b) {
        SceneLayer la = a.layer, lb = b.layer;
        if (a.x != b.x || a.y != b.y) return false; // 動いた（祖先の移動・sticky）
        if (la == lb) return true;
        boolean sizeMatters = framesChildren(la) || framesChildren(lb);
        if (sizeMatters && (la.w != lb.w || la.h != lb.h
                || la.bT != lb.bT || la.bR != lb.bR || la.bB != lb.bB || la.bL != lb.bL
                || la.pT != lb.pT || la.pR != lb.pR || la.pB != lb.pB || la.pL != lb.pL
                || la.scrollWidth != lb.scrollWidth || la.scrollHeight != lb.scrollHeight)) return false;
        return la.flags == lb.flags && la.stackingKind == lb.stackingKind && la.zIndex == lb.zIndex
                && Objects.equals(la.transform, lb.transform) && sameEffects(la.effects, lb.effects) && sameClip(la.clip, lb.clip)
                && Objects.equals(la.sticky, lb.sticky) && la.follow == lb.follow;
    }

    /** 大きさ・枠・余白で子孫を切る・写す箱か。 */
    private static boolean framesChildren(SceneLayer l) {
        return l.clipsX() || l.clipsY() || l.isScrollContainer() || l.transform != null || l.effects != null || l.clip != null
                || l.sticky != null || l.isCollapsedTable();
    }

    private static boolean sameEffects(LayerEffects x, LayerEffects y) {
        if (x == y) return true;
        if (x == null || y == null) return false;
        return x.opacity() == y.opacity() && x.animatesOpacity() == y.animatesOpacity() && x.blendMode() == y.blendMode()
                && x.isolate() == y.isolate() && x.masked() == y.masked() && x.luminanceMask() == y.luminanceMask()
                && sameFilters(x.filters(), y.filters()) && sameFilters(x.backdropFilters(), y.backdropFilters())
                && sameClip(x.backdropShape(), y.backdropShape());
    }

    private static boolean sameFilters(List<FilterOp> x, List<FilterOp> y) {
        if (x.size() != y.size()) return false;
        for (int i = 0; i < x.size(); i++) {
            FilterOp p = x.get(i), q = y.get(i);
            if (p.kind() != q.kind() || p.amount() != q.amount() || p.dx() != q.dx() || p.dy() != q.dy() || p.color() != q.color()
                    || !Arrays.equals(p.matrix(), q.matrix())) return false;
        }
        return true;
    }

    private static boolean sameClip(ClipShape x, ClipShape y) {
        if (x == y) return true;
        return x != null && y != null && x.kind() == y.kind() && x.evenOdd() == y.evenOdd() && Arrays.equals(x.data(), y.data());
    }

    /**
     * 作り直した層の自分の描画が変わりうるか。描画命令（層の局所の座標・中身）で比べる。命令を持たない（毎フレーム作る）層は、
     * 描画が読む入力（スタイル・属性の写し・文字の断片）で比べる。キャンバスへ譲った背景（html / body）は {@link #compute} が先に比べる。
     */
    private boolean ownDiffers(SceneLayer la, SceneLayer lb) {
        if (!la.ops().sameAs(lb.ops())) return true;
        if (la.ops().isEmpty() && la.hasSelf()) {
            return la.style.typed() != lb.style.typed() || la.values() != lb.values(); // 断片は上の命令の列と一緒に比べた
        }
        return la.style.typed() != lb.style.typed() && (la.wantsScrollbars() || la.backdrop() != null || hasMask(la));
    }

    private static boolean hasMask(SceneLayer l) {
        return l.effects != null && l.effects.masked();
    }

    // ---- 範囲を足す ----

    /**
     * 箱の自分の描画の範囲: 描画命令と文字の断片の矩形。毎フレーム作る描画（命令を持たない層・スクロールバー・マスク・{@code ::backdrop}）は
     * 枠（border-box）。
     */
    private void own(PlacedLayer box) {
        if (out.full) return;
        SceneLayer l = box.layer;
        if (l.displayNone()) return;
        if (l.isTopLayer() && l.backdrop() != null) { out.setFull("backdrop-pseudo"); return; } // ::backdrop はビューポート全体を覆う
        DrawOps ops = l.ops();
        if ((ops.isEmpty() && l.hasSelf()) || l.wantsScrollbars() || l.backdrop() != null || hasMask(l) || l.verticalWrapper()) box(box);
        PlacedLayer op = box.originParent();
        float ox = op != null ? op.x : 0f, oy = op != null ? op.y : 0f;
        DrawOps.Cursor it = cursor.reset(ops);
        while (it.next()) {
            boolean fromOrigin = (it.flags() & DrawOps.BASE_ORIGIN_PARENT) != 0;
            float bx = fromOrigin ? ox : box.x, by = fromOrigin ? oy : box.y;
            float pad = padOf(it.meta(), it.text(), it.h());
            add(bx + it.x() - pad, by + it.y() - pad, bx + it.x() + it.w() + pad, by + it.y() + it.h() + pad);
        }
        for (int i = 0, n = ops.runCount(); i < n; i++) {
            float rx = ops.runX(i), ry = ops.runY(i), rw = ops.runW(i), rh = ops.runH(i);
            float pad = Math.max(PAD, rh + 1f);
            add(ox + rx - pad, oy + ry - pad, ox + rx + rw + pad, oy + ry + rh + pad);
        }
    }

    /** 箱の枠（border-box。スクロールバー・マスク・{@code ::backdrop} は枠の中）。 */
    private void box(PlacedLayer box) {
        add(box.x - PAD, box.y - PAD, box.x + box.layer.w + PAD, box.y + box.layer.h + PAD);
    }

    /** 描画命令の余白（影のぼかし・文字の大きさ。旧 {@code DamagePlanner#estimateBounds} から引き継いだ規則）。 */
    static float padOf(RenderMeta meta, String text, float h) {
        float pad = PAD;
        RenderHints hints = meta != null ? meta.hints() : null;
        if (hints != null && hints.shadowBlur() > 0f) pad = Math.max(pad, BoxDecorationPainter.SHADOW_REACH * hints.shadowBlur() + 1f);
        if (text != null && !text.isEmpty()) {
            float font = meta != null && meta.fontHeight() > 0f ? meta.fontHeight() : h;
            pad = Math.max(pad, font + 1f);
        }
        return pad;
    }

    /** 部分木の範囲（自分・子孫。両方向を切る箱は子孫を枠で切るので下りない）。 */
    private void subtree(PlacedLayer box, DynamicSceneState dyn) {
        if (out.full || box.layer.displayNone()) return;
        if (++depth > 256) { out.setFull("depth"); return; }
        int mark = chain.n;
        try {
            push(box);
            own(box);
            SceneLayer l = box.layer;
            boolean clipsAll = l.clipsX() && l.clipsY() && (l.propagatedLayers & SceneLayer.LAYER_HOIST) == 0;
            if (clipsAll || l.children.size == 0) return;
            SceneChildren sc = l.children;
            for (int k = 0; k < sc.chunks.length; k++) {
                for (SceneLayer c : sc.chunks[k].layers) {
                    subtree(box.child(c, sc.chunkX[k], sc.chunkY[k], dyn), dyn);
                    if (out.full) return;
                }
            }
        } finally {
            chain.n = mark;
            depth--;
        }
    }

    /** 箱の変形とフィルターの広がりを鎖に積む（自分の描画と子孫に掛かる）。 */
    private void push(PlacedLayer box) {
        SceneLayer l = box.layer;
        if (l.transform != null) chain.pushTransform(box, l.transform);
        if (l.effects != null && !l.effects.filters().isEmpty()) {
            float outset = 0f;
            for (FilterOp f : l.effects.filters()) {
                if (f.kind() == FilterOp.Kind.BLUR) outset += 3f * f.amount() + PAD;
                else if (f.kind() == FilterOp.Kind.DROP_SHADOW) outset += Math.max(Math.abs(f.dx()), Math.abs(f.dy())) + 3f * f.amount() + PAD;
            }
            if (outset > 0f) chain.pushOutset(outset);
        }
    }

    /** 箱の座標の矩形を、鎖（内側から外へ）で写してから足す。 */
    private void add(float x0, float y0, float x1, float y1) {
        float[] e = chain.e;
        for (int k = chain.n - 1; k >= 0; k--) {
            int b = k * 9;
            if (e[b] == 1f) {
                float o = e[b + 1];
                x0 -= o; y0 -= o; x1 += o; y1 += o;
                continue;
            }
            float ox = e[b + 1], oy = e[b + 2], tx = e[b + 3], ty = e[b + 4], sx = e[b + 5], sy = e[b + 6], rot = e[b + 7], skew = e[b + 8];
            if (rot == 0f && skew == 0f) {
                float ax = ox + tx + sx * (x0 - ox), bx = ox + tx + sx * (x1 - ox);
                float ay = oy + ty + sy * (y0 - oy), by = oy + ty + sy * (y1 - oy);
                x0 = Math.min(ax, bx); x1 = Math.max(ax, bx); y0 = Math.min(ay, by); y1 = Math.max(ay, by);
            } else {
                // 回転・せん断: 原点のまわりの外接円（向きの取り違えに依らない保守的な範囲）
                float r = 0f;
                for (int c = 0; c < 4; c++) {
                    float dx = sx * ((c & 1) == 0 ? x0 - ox : x1 - ox), dy = sy * ((c & 2) == 0 ? y0 - oy : y1 - oy);
                    r = Math.max(r, (float) Math.hypot(dx, dy));
                }
                r *= 1f + (float) Math.abs(Math.tan(Math.toRadians(skew)));
                x0 = ox + tx - r; x1 = ox + tx + r; y0 = oy + ty - r; y1 = oy + ty + r;
            }
        }
        out.add(x0, y0, x1, y1);
    }
}
