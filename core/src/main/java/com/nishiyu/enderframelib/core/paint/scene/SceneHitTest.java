package com.nishiyu.enderframelib.core.paint.scene;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.model.ImageFitMode;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static com.nishiyu.enderframelib.core.paint.scene.SceneHitGeometry.*;

/**
 * 不変の {@link DisplayScene} と {@link DynamicSceneState} に対する直接の当たり判定（M3b-3。計画書 §3.4）。
 *
 * <p>CSS 2.2 付録 E の描画順の完全な逆順（手前から奥）で木を走査し、{@link SceneChildren#monotonicY} の二分探索による
 * {@code O(log(N/32))} チャンクカリングと {@link SceneChildren#unculledChunks} による動的境界（sticky・変形・
 * アニメーション・アンカー追従・Top Layer）の回収を行う。旧 {@code HitTestIndex} を置き換える。</p>
 */
public final class SceneHitTest {
    private SceneHitTest() { }

    /**
     * アニメーション・ユーザー平行移動など、{@link SceneLayer#transform} を上書きする外部姿勢の差し込み口。
     * 上書きがあれば {@code out6 = {tx, ty, sx, sy, rotateDeg, skewDeg}} を書いて {@code true} を返す。
     */
    @FunctionalInterface
    public interface TransformOverride {
        boolean resolve(SceneLayer layer, float[] out6);
    }

    /** {@code <img data-hit-alpha>} の画素不透明度を判定する差し込み口。 */
    @FunctionalInterface
    public interface AlphaHitTest {
        boolean isOpaque(SceneLayer layer, String src, int threshold, double localX, double localY,
                         float boxW, float boxH, ImageFitMode fit, String objectPosition, float fontPx);
    }

    /**
     * 当たり判定の入力コンテキスト（スレッドローカルや静的状態を持たない）。ビューが持ち回す前提で、作業用の値
     * （{@link HitScratch}）の予備を 1 つ持つ。並行に使われたときは予備を取れなかった側が新しく作る。
     */
    public static final class Context {
        public final DynamicSceneState dynamic;
        public final TransformOverride transformOverride;
        public final AlphaHitTest alphaHitTest;
        public final Predicate<SceneLayer> accept;
        public final boolean allowCull;
        private final AtomicReference<HitScratch> spare = new AtomicReference<>();

        private Context(DynamicSceneState dynamic, TransformOverride transformOverride, AlphaHitTest alphaHitTest,
                        Predicate<SceneLayer> accept, boolean allowCull) {
            this.dynamic = dynamic;
            this.transformOverride = transformOverride;
            this.alphaHitTest = alphaHitTest;
            this.accept = accept;
            this.allowCull = allowCull;
        }

        public DynamicSceneState dynamic() { return dynamic; }
        public TransformOverride transformOverride() { return transformOverride; }
        public AlphaHitTest alphaHitTest() { return alphaHitTest; }
        public Predicate<SceneLayer> accept() { return accept; }
        public boolean allowCull() { return allowCull; }

        HitScratch acquire(int[] visited) {
            HitScratch s = spare.getAndSet(null);
            if (s == null) s = new HitScratch(this);
            s.visited = visited;
            return s;
        }

        void release(HitScratch s) {
            s.reset();
            spare.set(s);
        }

        public static Context of(DynamicSceneState dynamic) {
            return new Context(dynamic, null, null, null, true);
        }

        public static Context of(DynamicSceneState dynamic, TransformOverride transformOverride,
                                 AlphaHitTest alphaHitTest, Predicate<SceneLayer> accept, boolean allowCull) {
            return new Context(dynamic, transformOverride, alphaHitTest, accept, allowCull);
        }
    }

    /** 最前面の対話・遮蔽要素を探す。無ければ {@code null}。 */
    public static SceneHit hitTest(DisplayScene scene, Context ctx, double pixelX, double pixelY) {
        return hitTest(scene, ctx, pixelX, pixelY, null);
    }

    /** {@code visitedChunksOut}（長さ 1 の配列、null 可）へ、チャンク走査の訪問回数を記録する。 */
    public static SceneHit hitTest(DisplayScene scene, Context ctx, double pixelX, double pixelY, int[] visitedChunksOut) {
        if (scene == null || !Double.isFinite(pixelX) || !Double.isFinite(pixelY)) return null;
        DynamicSceneState dyn = SceneQuery.normalize(scene, ctx != null ? ctx.dynamic : null);
        Context effective = ctx != null && ctx.dynamic == dyn ? ctx
                : ctx != null ? new Context(dyn, ctx.transformOverride, ctx.alphaHitTest, ctx.accept, ctx.allowCull)
                : Context.of(dyn);
        HitScratch s = effective.acquire(visitedChunksOut);
        try {
            return hitRoot(scene.root, pixelX, pixelY, s);
        } finally {
            effective.release(s);
        }
    }

    /** カリングを無効化した基準の線形走査（単体テストでカリングとの全点一致を検証する用）。 */
    public static SceneHit hitTestLinear(DisplayScene scene, Context ctx, double pixelX, double pixelY) {
        if (scene == null || !Double.isFinite(pixelX) || !Double.isFinite(pixelY)) return null;
        DynamicSceneState dyn = SceneQuery.normalize(scene, ctx != null ? ctx.dynamic : null);
        Context linear = ctx != null
                ? (!ctx.allowCull && ctx.dynamic == dyn ? ctx : new Context(dyn, ctx.transformOverride, ctx.alphaHitTest, ctx.accept, false))
                : new Context(dyn, null, null, null, false);
        HitScratch s = linear.acquire(null);
        try {
            return hitRoot(scene.root, pixelX, pixelY, s);
        } finally {
            linear.release(s);
        }
    }

    /** 点が対話・遮蔽要素の上にあるか。 */
    public static boolean isPointerOverUi(DisplayScene scene, Context ctx, double pixelX, double pixelY) {
        return hitTest(scene, ctx, pixelX, pixelY) != null;
    }

    /** 点の下にあるスクロール可能なコンテナを手前から順に返す（ホイール配送用）。 */
    public static List<SceneHit> scrollContainersAt(DisplayScene scene, Context ctx, double pixelX, double pixelY) {
        if (scene == null || !Double.isFinite(pixelX) || !Double.isFinite(pixelY)) return List.of();
        DynamicSceneState dyn = SceneQuery.normalize(scene, ctx != null ? ctx.dynamic : null);
        Context effective = ctx != null && ctx.dynamic == dyn ? ctx
                : ctx != null ? new Context(dyn, ctx.transformOverride, ctx.alphaHitTest, ctx.accept, ctx.allowCull)
                : Context.of(dyn);
        HitScratch s = effective.acquire(null);
        try {
            collectScrollRoot(scene.root, pixelX, pixelY, s);
            return s.scrollResult();
        } finally {
            effective.release(s);
        }
    }

    /** 画面座標 {@code (pixelX, pixelY)} を要素 {@code target} の局所座標に変換する。見つからなければ {@code null}。 */
    public static SceneHit localCoords(DisplayScene scene, Context ctx, HtmlNode target, double pixelX, double pixelY) {
        if (scene == null || target == null) return null;
        DynamicSceneState dyn = SceneQuery.normalize(scene, ctx != null ? ctx.dynamic : null);
        Context effective = ctx != null && ctx.dynamic == dyn ? ctx
                : ctx != null ? new Context(dyn, ctx.transformOverride, ctx.alphaHitTest, ctx.accept, ctx.allowCull)
                : Context.of(dyn);
        HitScratch s = effective.acquire(null);
        try {
            return findLocalCoords(scene.root, 0f, 0f, 0f, 0f, 0f, 0f, dyn.viewportW, dyn.viewportH, pixelX, pixelY, target, s);
        } finally {
            effective.release(s);
        }
    }

    // ------------------------------------------------------------------
    // 逆スタッキング順走査（SceneDisplayListAdapter の完全な双対）
    // ------------------------------------------------------------------

    private static SceneHit hitRoot(SceneLayer root, double px, double py, HitScratch s) {
        toLayerSpace(root, 0f, 0f, px, py, s);
        double cpx = s.ptX, cpy = s.ptY;
        float cox = SceneQuery.nextOriginX(root, 0f, 0f, s.dyn);
        float coy = SceneQuery.nextOriginY(root, 0f, 0f, s.dyn);
        float pw = s.dyn.viewportW, ph = s.dyn.viewportH;
        int kl = root.children.propagatedLayers;
        SceneHit h;
        if ((kl & SceneLayer.LAYER_AFTER) != 0 && (h = hitAfterPass(root, 0f, 0f, cox, coy, 0f, 0f, pw, ph, cpx, cpy, s)) != null) return h;
        if ((kl & SceneLayer.LAYER_HOIST) != 0 && (h = hitZPass(root, 0f, 0f, cox, coy, 0f, 0f, pw, ph, cpx, cpy, SceneLayer.LAYER_HOIST, s)) != null) return h;
        if ((kl & SceneLayer.LAYER_POSZ) != 0 && (h = hitZPass(root, 0f, 0f, cox, coy, 0f, 0f, pw, ph, cpx, cpy, SceneLayer.LAYER_POSZ, s)) != null) return h;
        if ((kl & SceneLayer.LAYER_POS0) != 0 && (h = hitPos0Pass(root, 0f, 0f, cox, coy, 0f, 0f, pw, ph, cpx, cpy, s)) != null) return h;
        if ((kl & SceneLayer.LAYER_INLINE) != 0 && (h = hitInlinesPass(root, 0f, 0f, cox, coy, 0f, 0f, pw, ph, cpx, cpy, s)) != null) return h;
        if ((kl & SceneLayer.LAYER_FLOAT) != 0 && (h = hitFloatsPass(root, 0f, 0f, cox, coy, 0f, 0f, pw, ph, cpx, cpy, s)) != null) return h;
        if ((kl & SceneLayer.LAYER_BLOCK) != 0 && (h = hitBlocksPass(root, 0f, 0f, cox, coy, 0f, 0f, pw, ph, cpx, cpy, s)) != null) return h;
        if ((kl & SceneLayer.LAYER_NEG) != 0) return hitZPass(root, 0f, 0f, cox, coy, 0f, 0f, pw, ph, cpx, cpy, SceneLayer.LAYER_NEG, s);
        return null;
    }

    private static SceneHit hitStacking(SceneLayer l, float lx, float ly, float originX, float originY,
                                        float portX, float portY, float portW, float portH,
                                        double px, double py, HitScratch s) {
        toLayerSpace(l, lx, ly, px, py, s);
        double cpx = s.ptX, cpy = s.ptY;
        SceneHit h;
        if (!isValueControl(l)) {
            if (l.wantsScrollbars() && (h = hitScrollbars(l, lx, ly, cpx, cpy, s)) != null) return h;
            if (withinChildClip(l, lx, ly, cpx, cpy)) {
                int kl = l.children.propagatedLayers;
                float cox = SceneQuery.nextOriginX(l, lx, originX, s.dyn);
                float coy = SceneQuery.nextOriginY(l, ly, originY, s.dyn);
                float npx = l.isScrollContainer() ? lx + l.bL : portX;
                float npy = l.isScrollContainer() ? ly + l.bT : portY;
                float npw = l.isScrollContainer() ? Math.max(0f, l.w - l.bL - l.bR) : portW;
                float nph = l.isScrollContainer() ? Math.max(0f, l.h - l.bT - l.bB) : portH;
                if ((kl & SceneLayer.LAYER_AFTER) != 0 && (h = hitAfterPass(l, lx, ly, cox, coy, npx, npy, npw, nph, cpx, cpy, s)) != null) return h;
                if ((kl & SceneLayer.LAYER_POSZ) != 0 && (h = hitZPass(l, lx, ly, cox, coy, npx, npy, npw, nph, cpx, cpy, SceneLayer.LAYER_POSZ, s)) != null) return h;
                if ((kl & SceneLayer.LAYER_POS0) != 0 && (h = hitPos0Pass(l, lx, ly, cox, coy, npx, npy, npw, nph, cpx, cpy, s)) != null) return h;
                if ((kl & SceneLayer.LAYER_INLINE) != 0 && (h = hitInlinesPass(l, lx, ly, cox, coy, npx, npy, npw, nph, cpx, cpy, s)) != null) return h;
                if ((kl & SceneLayer.LAYER_FLOAT) != 0 && (h = hitFloatsPass(l, lx, ly, cox, coy, npx, npy, npw, nph, cpx, cpy, s)) != null) return h;
                if ((kl & SceneLayer.LAYER_BLOCK) != 0 && (h = hitBlocksPass(l, lx, ly, cox, coy, npx, npy, npw, nph, cpx, cpy, s)) != null) return h;
                if ((kl & SceneLayer.LAYER_NEG) != 0 && (h = hitZPass(l, lx, ly, cox, coy, npx, npy, npw, nph, cpx, cpy, SceneLayer.LAYER_NEG, s)) != null) return h;
            }
        }
        return hitSelf(l, lx, ly, originX, originY, cpx, cpy, s);
    }

    private static SceneHit hitPlain(SceneLayer l, float lx, float ly, float originX, float originY,
                                     float portX, float portY, float portW, float portH,
                                     double px, double py, HitScratch s) {
        toLayerSpace(l, lx, ly, px, py, s);
        double cpx = s.ptX, cpy = s.ptY;
        SceneHit h;
        if (!isValueControl(l)) {
            if (l.wantsScrollbars() && (h = hitScrollbars(l, lx, ly, cpx, cpy, s)) != null) return h;
            if (withinChildClip(l, lx, ly, cpx, cpy)) {
                int kl = l.children.propagatedLayers;
                float cox = SceneQuery.nextOriginX(l, lx, originX, s.dyn);
                float coy = SceneQuery.nextOriginY(l, ly, originY, s.dyn);
                float npx = l.isScrollContainer() ? lx + l.bL : portX;
                float npy = l.isScrollContainer() ? ly + l.bT : portY;
                float npw = l.isScrollContainer() ? Math.max(0f, l.w - l.bL - l.bR) : portW;
                float nph = l.isScrollContainer() ? Math.max(0f, l.h - l.bT - l.bB) : portH;
                if ((kl & SceneLayer.LAYER_AFTER) != 0 && (h = hitAfterPass(l, lx, ly, cox, coy, npx, npy, npw, nph, cpx, cpy, s)) != null) return h;
                if ((kl & SceneLayer.LAYER_INLINE) != 0 && (h = hitInlinesPass(l, lx, ly, cox, coy, npx, npy, npw, nph, cpx, cpy, s)) != null) return h;
                if ((kl & SceneLayer.LAYER_FLOAT) != 0 && (h = hitFloatsPass(l, lx, ly, cox, coy, npx, npy, npw, nph, cpx, cpy, s)) != null) return h;
                if ((kl & SceneLayer.LAYER_BLOCK) != 0 && (h = hitBlocksPass(l, lx, ly, cox, coy, npx, npy, npw, nph, cpx, cpy, s)) != null) return h;
            }
        }
        return hitSelf(l, lx, ly, originX, originY, cpx, cpy, s);
    }

    private static SceneHit hitZPass(SceneLayer root, float rx, float ry, float cox, float coy,
                                     float px, float py, float pw, float ph,
                                     double cpx, double cpy, int mask, HitScratch s) {
        if ((root.children.propagatedLayers & mask) == 0) return null;
        int base = s.zSize;
        collectZPass(root, rx, ry, cox, coy, px, py, pw, ph, cpx, cpy, mask, s);
        int end = s.zSize;
        if (end == base) return null;
        try {
            if (mask != SceneLayer.LAYER_HOIST) s.sortZ(base, end);
            for (int i = end - 1; i >= base; i--) {
                HitScratch.ZNode e = s.z(i);
                SceneHit h = hitStacking(e.layer, e.lx, e.ly, e.originX, e.originY, e.portX, e.portY, e.portW, e.portH, e.px, e.py, s);
                if (h != null) return h;
            }
            return null;
        } finally {
            s.zSize = base;
        }
    }

    static void collectZPass(SceneLayer container, float cx, float cy, float cox, float coy,
                             float portX, float portY, float portW, float portH,
                             double cpx, double cpy, int mask, HitScratch s) {
        SceneChildren sc = container.children;
        for (int k = 0; k < sc.chunks.length; k++) {
            SceneChunk chunk = sc.chunks[k];
            if ((chunk.propagatedLayers & mask) == 0) continue;
            float chx = sc.chunkX[k], chy = sc.chunkY[k];
            for (SceneLayer child : chunk.layers) {
                if ((child.propagatedLayers & mask) == 0) continue;
                SceneQuery.resolveChildPos(container, cx, cy, cox, coy, portX, portY, portW, portH, chx, chy, child, s.dyn, s);
                float lx = s.resX, ly = s.resY;
                if (!isVisible(child, lx, ly, s.dyn)) continue;
                boolean hoistOnly = mask == SceneLayer.LAYER_HOIST;
                if (child.stackingKind == StackingKind.STACKING) {
                    boolean topLayer = child.isTopLayer();
                    int z = child.zIndex;
                    boolean matches = topLayer ? (mask & SceneLayer.LAYER_HOIST) != 0
                            : z < 0 ? (mask & SceneLayer.LAYER_NEG) != 0
                            : z > 0 && (mask & SceneLayer.LAYER_POSZ) != 0;
                    if (matches) s.pushZ(child, lx, ly, cox, coy, portX, portY, portW, portH, cpx, cpy);
                    if ((mask & SceneLayer.LAYER_HOIST) != 0 && !isValueControl(child)
                            && (child.children.propagatedLayers & SceneLayer.LAYER_HOIST) != 0) {
                        toLayerSpace(child, lx, ly, cpx, cpy, s);
                        double lpx = s.ptX, lpy = s.ptY;
                        float ncox = SceneQuery.nextOriginX(child, lx, cox, s.dyn);
                        float ncoy = SceneQuery.nextOriginY(child, ly, coy, s.dyn);
                        float npx = child.isScrollContainer() ? lx + child.bL : portX;
                        float npy = child.isScrollContainer() ? ly + child.bT : portY;
                        float npw = child.isScrollContainer() ? Math.max(0f, child.w - child.bL - child.bR) : portW;
                        float nph = child.isScrollContainer() ? Math.max(0f, child.h - child.bT - child.bB) : portH;
                        collectZPass(child, lx, ly, ncox, ncoy, npx, npy, npw, nph, lpx, lpy, SceneLayer.LAYER_HOIST, s);
                    }
                } else if (!isValueControl(child)) {
                    toLayerSpace(child, lx, ly, cpx, cpy, s);
                    double lpx = s.ptX, lpy = s.ptY;
                    if (!hoistOnly && !withinChildClip(child, lx, ly, lpx, lpy)) continue;
                    float ncox = SceneQuery.nextOriginX(child, lx, cox, s.dyn);
                    float ncoy = SceneQuery.nextOriginY(child, ly, coy, s.dyn);
                    float npx = child.isScrollContainer() ? lx + child.bL : portX;
                    float npy = child.isScrollContainer() ? ly + child.bT : portY;
                    float npw = child.isScrollContainer() ? Math.max(0f, child.w - child.bL - child.bR) : portW;
                    float nph = child.isScrollContainer() ? Math.max(0f, child.h - child.bT - child.bB) : portH;
                    collectZPass(child, lx, ly, ncox, ncoy, npx, npy, npw, nph, lpx, lpy, mask, s);
                }
            }
        }
    }

    private static final int PASS_BLOCK = 0, PASS_FLOAT = 1, PASS_INLINE = 2, PASS_POS0 = 3, PASS_AFTER = 4;

    private static int maskForPass(int passKind) {
        return switch (passKind) {
            case PASS_BLOCK -> SceneLayer.LAYER_BLOCK;
            case PASS_FLOAT -> SceneLayer.LAYER_FLOAT;
            case PASS_INLINE -> SceneLayer.LAYER_INLINE;
            case PASS_POS0 -> SceneLayer.LAYER_POS0;
            default -> SceneLayer.LAYER_AFTER;
        };
    }

    private static SceneHit visitChildrenReverse(SceneLayer container, float cx, float cy, float cox, float coy,
                                                 float portX, float portY, float portW, float portH,
                                                 double cpx, double cpy, int passKind, HitScratch s) {
        SceneChildren sc = container.children;
        int n = sc.chunks.length;
        int mask = maskForPass(passKind);
        if (n == 0 || (sc.propagatedLayers & mask) == 0) return null;
        boolean cull = s.ctx.allowCull && container.isOrigin();
        double relX = cpx - cox, relY = cpy - coy;
        if (!cull || !sc.monotonicY) {
            for (int k = n - 1; k >= 0; k--) {
                SceneChunk c = sc.chunks[k];
                if (c.layers.length == 0 || (c.propagatedLayers & mask) == 0) continue;
                float chx = sc.chunkX[k], chy = sc.chunkY[k];
                if (cull && !c.hasDynamicBounds && !chunkContains(c, chx, chy, relX, relY)) continue;
                SceneHit h = visitChunkLayersReverse(c, chx, chy, relX, relY, mask, cull, passKind,
                        container, cx, cy, cox, coy, portX, portY, portW, portH, cpx, cpy, s);
                if (h != null) return h;
            }
            return null;
        }
        int lo = sc.firstVisibleChunk((float) relY);
        int hi = sc.lastVisibleChunk((float) relY);
        int[] unc = sc.unculledChunks;
        int ui = unc.length - 1;
        int vi = hi;
        while (vi >= lo || ui >= 0) {
            int k;
            if (ui < 0) k = vi--;
            else if (vi < lo) k = unc[ui--];
            else {
                int u = unc[ui];
                if (u > vi) { k = u; ui--; }
                else if (u == vi) { k = vi--; ui--; }
                else k = vi--;
            }
            SceneChunk c = sc.chunks[k];
            if (c.layers.length == 0 || (c.propagatedLayers & mask) == 0) continue;
            float chx = sc.chunkX[k], chy = sc.chunkY[k];
            if (!c.hasDynamicBounds && (relX < chx + c.minX || relX > chx + c.maxX)) continue;
            SceneHit h = visitChunkLayersReverse(c, chx, chy, relX, relY, mask, true, passKind,
                    container, cx, cy, cox, coy, portX, portY, portW, portH, cpx, cpy, s);
            if (h != null) return h;
        }
        return null;
    }

    private static SceneHit visitChunkLayersReverse(SceneChunk c, float chx, float chy, double relX, double relY,
                                                    int mask, boolean cull, int passKind,
                                                    SceneLayer container, float cx, float cy, float cox, float coy,
                                                    float portX, float portY, float portW, float portH,
                                                    double cpx, double cpy, HitScratch s) {
        s.countChunk();
        SceneLayer[] ls = c.layers;
        for (int i = ls.length - 1; i >= 0; i--) {
            SceneLayer child = ls[i];
            if ((child.propagatedLayers & mask) == 0) continue;
            if (cull && !child.hasDynamicBounds() && child.isOrigin()
                    && (!child.isScrollContainer() || (child.clipsX() && child.clipsY()))) {
                double lxRel = relX - chx - child.x, lyRel = relY - chy - child.y;
                if (lxRel < child.boundsMinX || lxRel > child.boundsMaxX
                        || lyRel < child.boundsMinY || lyRel > child.boundsMaxY) continue;
            }
            SceneQuery.resolveChildPos(container, cx, cy, cox, coy, portX, portY, portW, portH, chx, chy, child, s.dyn, s);
            float lx = s.resX, ly = s.resY;
            if (!isVisible(child, lx, ly, s.dyn)) continue;
            SceneHit h = switch (passKind) {
                case PASS_BLOCK -> hitBlockChild(child, lx, ly, cox, coy, portX, portY, portW, portH, cpx, cpy, s);
                case PASS_FLOAT -> hitFloatChild(child, lx, ly, cox, coy, portX, portY, portW, portH, cpx, cpy, s);
                case PASS_INLINE -> hitInlineChild(child, lx, ly, cox, coy, portX, portY, portW, portH, cpx, cpy, s);
                case PASS_POS0 -> hitPos0Child(child, lx, ly, cox, coy, portX, portY, portW, portH, cpx, cpy, s);
                default -> hitAfterChild(child, lx, ly, cox, coy, portX, portY, portW, portH, cpx, cpy, s);
            };
            if (h != null) return h;
        }
        return null;
    }

    private static boolean chunkContains(SceneChunk c, float cx, float cy, double relX, double relY) {
        return relX >= cx + c.minX && relX <= cx + c.maxX && relY >= cy + c.minY && relY <= cy + c.maxY;
    }

    private static SceneHit hitBlocksPass(SceneLayer container, float cx, float cy, float cox, float coy,
                                          float portX, float portY, float portW, float portH,
                                          double cpx, double cpy, HitScratch s) {
        return visitChildrenReverse(container, cx, cy, cox, coy, portX, portY, portW, portH, cpx, cpy, PASS_BLOCK, s);
    }

    private static SceneHit hitBlockChild(SceneLayer child, float lx, float ly, float cox, float coy,
                                          float portX, float portY, float portW, float portH,
                                          double cpx, double cpy, HitScratch s) {
        StackingKind k = child.stackingKind;
        if (k == StackingKind.BLOCK) {
            toLayerSpace(child, lx, ly, cpx, cpy, s);
            double lpx = s.ptX, lpy = s.ptY;
            if (!isValueControl(child) && (child.children.propagatedLayers & SceneLayer.LAYER_BLOCK) != 0
                    && withinChildClip(child, lx, ly, lpx, lpy)) {
                float ncox = SceneQuery.nextOriginX(child, lx, cox, s.dyn);
                float ncoy = SceneQuery.nextOriginY(child, ly, coy, s.dyn);
                float npx = child.isScrollContainer() ? lx + child.bL : portX;
                float npy = child.isScrollContainer() ? ly + child.bT : portY;
                float npw = child.isScrollContainer() ? Math.max(0f, child.w - child.bL - child.bR) : portW;
                float nph = child.isScrollContainer() ? Math.max(0f, child.h - child.bT - child.bB) : portH;
                SceneHit h = hitBlocksPass(child, lx, ly, ncox, ncoy, npx, npy, npw, nph, lpx, lpy, s);
                if (h != null) return h;
            }
            return hitSelf(child, lx, ly, cox, coy, lpx, lpy, s);
        }
        if (k == StackingKind.INLINE && !isValueControl(child)
                && (child.children.propagatedLayers & SceneLayer.LAYER_BLOCK) != 0) {
            toLayerSpace(child, lx, ly, cpx, cpy, s);
            double lpx = s.ptX, lpy = s.ptY;
            if (withinChildClip(child, lx, ly, lpx, lpy)) {
                return hitBlocksPass(child, lx, ly, cox, coy, portX, portY, portW, portH, lpx, lpy, s);
            }
        }
        return null;
    }

    private static SceneHit hitFloatsPass(SceneLayer container, float cx, float cy, float cox, float coy,
                                          float portX, float portY, float portW, float portH,
                                          double cpx, double cpy, HitScratch s) {
        return visitChildrenReverse(container, cx, cy, cox, coy, portX, portY, portW, portH, cpx, cpy, PASS_FLOAT, s);
    }

    private static SceneHit hitFloatChild(SceneLayer child, float lx, float ly, float cox, float coy,
                                          float portX, float portY, float portW, float portH,
                                          double cpx, double cpy, HitScratch s) {
        StackingKind k = child.stackingKind;
        if (k == StackingKind.FLOAT) {
            return hitPlain(child, lx, ly, cox, coy, portX, portY, portW, portH, cpx, cpy, s);
        }
        if ((k == StackingKind.BLOCK || k == StackingKind.INLINE) && !isValueControl(child)) {
            toLayerSpace(child, lx, ly, cpx, cpy, s);
            double lpx = s.ptX, lpy = s.ptY;
            if (withinChildClip(child, lx, ly, lpx, lpy)) {
                float ncox = SceneQuery.nextOriginX(child, lx, cox, s.dyn);
                float ncoy = SceneQuery.nextOriginY(child, ly, coy, s.dyn);
                float npx = child.isScrollContainer() ? lx + child.bL : portX;
                float npy = child.isScrollContainer() ? ly + child.bT : portY;
                float npw = child.isScrollContainer() ? Math.max(0f, child.w - child.bL - child.bR) : portW;
                float nph = child.isScrollContainer() ? Math.max(0f, child.h - child.bT - child.bB) : portH;
                return hitFloatsPass(child, lx, ly, ncox, ncoy, npx, npy, npw, nph, lpx, lpy, s);
            }
        }
        return null;
    }

    private static SceneHit hitInlinesPass(SceneLayer container, float cx, float cy, float cox, float coy,
                                           float portX, float portY, float portW, float portH,
                                           double cpx, double cpy, HitScratch s) {
        return visitChildrenReverse(container, cx, cy, cox, coy, portX, portY, portW, portH, cpx, cpy, PASS_INLINE, s);
    }

    private static SceneHit hitInlineChild(SceneLayer child, float lx, float ly, float cox, float coy,
                                           float portX, float portY, float portW, float portH,
                                           double cpx, double cpy, HitScratch s) {
        StackingKind k = child.stackingKind;
        if (k == StackingKind.ATOMIC) {
            return hitPlain(child, lx, ly, cox, coy, portX, portY, portW, portH, cpx, cpy, s);
        }
        if (k == StackingKind.INLINE) {
            toLayerSpace(child, lx, ly, cpx, cpy, s);
            double lpx = s.ptX, lpy = s.ptY;
            if (!isValueControl(child) && (child.children.propagatedLayers & SceneLayer.LAYER_INLINE) != 0
                    && withinChildClip(child, lx, ly, lpx, lpy)) {
                SceneHit h = hitInlinesPass(child, lx, ly, cox, coy, portX, portY, portW, portH, lpx, lpy, s);
                if (h != null) return h;
            }
            return hitSelf(child, lx, ly, cox, coy, lpx, lpy, s);
        }
        if (k == StackingKind.BLOCK && !isValueControl(child)) {
            toLayerSpace(child, lx, ly, cpx, cpy, s);
            double lpx = s.ptX, lpy = s.ptY;
            if (withinChildClip(child, lx, ly, lpx, lpy)) {
                float ncox = SceneQuery.nextOriginX(child, lx, cox, s.dyn);
                float ncoy = SceneQuery.nextOriginY(child, ly, coy, s.dyn);
                float npx = child.isScrollContainer() ? lx + child.bL : portX;
                float npy = child.isScrollContainer() ? ly + child.bT : portY;
                float npw = child.isScrollContainer() ? Math.max(0f, child.w - child.bL - child.bR) : portW;
                float nph = child.isScrollContainer() ? Math.max(0f, child.h - child.bT - child.bB) : portH;
                return hitInlinesPass(child, lx, ly, ncox, ncoy, npx, npy, npw, nph, lpx, lpy, s);
            }
        }
        return null;
    }

    private static SceneHit hitPos0Pass(SceneLayer container, float cx, float cy, float cox, float coy,
                                        float portX, float portY, float portW, float portH,
                                        double cpx, double cpy, HitScratch s) {
        return visitChildrenReverse(container, cx, cy, cox, coy, portX, portY, portW, portH, cpx, cpy, PASS_POS0, s);
    }

    private static SceneHit hitPos0Child(SceneLayer child, float lx, float ly, float cox, float coy,
                                         float portX, float portY, float portW, float portH,
                                         double cpx, double cpy, HitScratch s) {
        StackingKind k = child.stackingKind;
        if (k == StackingKind.STACKING) {
            if (!child.isTopLayer() && child.zIndex == 0) {
                return hitStacking(child, lx, ly, cox, coy, portX, portY, portW, portH, cpx, cpy, s);
            }
            return null;
        }
        if (k == StackingKind.POSITIONED) {
            if (!isValueControl(child) && (child.children.propagatedLayers & SceneLayer.LAYER_POS0) != 0) {
                toLayerSpace(child, lx, ly, cpx, cpy, s);
                double lpx = s.ptX, lpy = s.ptY;
                if (withinChildClip(child, lx, ly, lpx, lpy)) {
                    float ncox = SceneQuery.nextOriginX(child, lx, cox, s.dyn);
                    float ncoy = SceneQuery.nextOriginY(child, ly, coy, s.dyn);
                    float npx = child.isScrollContainer() ? lx + child.bL : portX;
                    float npy = child.isScrollContainer() ? ly + child.bT : portY;
                    float npw = child.isScrollContainer() ? Math.max(0f, child.w - child.bL - child.bR) : portW;
                    float nph = child.isScrollContainer() ? Math.max(0f, child.h - child.bT - child.bB) : portH;
                    SceneHit h = hitPos0Pass(child, lx, ly, ncox, ncoy, npx, npy, npw, nph, lpx, lpy, s);
                    if (h != null) return h;
                }
            }
            return hitPlain(child, lx, ly, cox, coy, portX, portY, portW, portH, cpx, cpy, s);
        }
        if (!isValueControl(child)) {
            toLayerSpace(child, lx, ly, cpx, cpy, s);
            double lpx = s.ptX, lpy = s.ptY;
            if (withinChildClip(child, lx, ly, lpx, lpy)) {
                float ncox = SceneQuery.nextOriginX(child, lx, cox, s.dyn);
                float ncoy = SceneQuery.nextOriginY(child, ly, coy, s.dyn);
                float npx = child.isScrollContainer() ? lx + child.bL : portX;
                float npy = child.isScrollContainer() ? ly + child.bT : portY;
                float npw = child.isScrollContainer() ? Math.max(0f, child.w - child.bL - child.bR) : portW;
                float nph = child.isScrollContainer() ? Math.max(0f, child.h - child.bT - child.bB) : portH;
                return hitPos0Pass(child, lx, ly, ncox, ncoy, npx, npy, npw, nph, lpx, lpy, s);
            }
        }
        return null;
    }

    private static SceneHit hitAfterPass(SceneLayer container, float cx, float cy, float cox, float coy,
                                         float portX, float portY, float portW, float portH,
                                         double cpx, double cpy, HitScratch s) {
        return visitChildrenReverse(container, cx, cy, cox, coy, portX, portY, portW, portH, cpx, cpy, PASS_AFTER, s);
    }

    private static SceneHit hitAfterChild(SceneLayer child, float lx, float ly, float cox, float coy,
                                          float portX, float portY, float portW, float portH,
                                          double cpx, double cpy, HitScratch s) {
        StackingKind k = child.stackingKind;
        if ((k == StackingKind.BLOCK || k == StackingKind.INLINE) && !isValueControl(child)) {
            toLayerSpace(child, lx, ly, cpx, cpy, s);
            double lpx = s.ptX, lpy = s.ptY;
            SceneHit h;
            if (child.wantsScrollbars() && (h = hitScrollbars(child, lx, ly, lpx, lpy, s)) != null) return h;
            if ((child.children.propagatedLayers & SceneLayer.LAYER_AFTER) != 0 && withinChildClip(child, lx, ly, lpx, lpy)) {
                float ncox = SceneQuery.nextOriginX(child, lx, cox, s.dyn);
                float ncoy = SceneQuery.nextOriginY(child, ly, coy, s.dyn);
                float npx = child.isScrollContainer() ? lx + child.bL : portX;
                float npy = child.isScrollContainer() ? ly + child.bT : portY;
                float npw = child.isScrollContainer() ? Math.max(0f, child.w - child.bL - child.bR) : portW;
                float nph = child.isScrollContainer() ? Math.max(0f, child.h - child.bT - child.bB) : portH;
                return hitAfterPass(child, lx, ly, ncox, ncoy, npx, npy, npw, nph, lpx, lpy, s);
            }
        }
        return null;
    }
}
