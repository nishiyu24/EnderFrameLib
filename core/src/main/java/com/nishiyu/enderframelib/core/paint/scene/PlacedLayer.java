package com.nishiyu.enderframelib.core.paint.scene;

/**
 * シーンをたどるときの、層 1 つの今のフレームの置き場所（M3b-4c-1a）: 層・絶対位置（スクロール・sticky・アンカーの追従を足した後）・親。
 *
 * <p>層は親相対・スクロール 0 で位置を持つので、たどる側が親から子の位置を {@link #child} で求める（求め方は当たり判定の
 * {@link SceneQuery} と同じ）。描き直す範囲（{@code SceneDamage}）がシーンを直接たどるのに使う。記憶ではなく、1 回のたどりの間だけの値。</p>
 */
public final class PlacedLayer {
    public final SceneLayer layer;
    public final float x, y;
    private final PlacedLayer parent;

    private PlacedLayer(SceneLayer layer, PlacedLayer parent, float x, float y) {
        this.layer = layer;
        this.parent = parent;
        this.x = x;
        this.y = y;
    }

    /** 根の層（位置 0）。 */
    public static PlacedLayer root(SceneLayer root) {
        return new PlacedLayer(root, null, 0f, 0f);
    }

    public PlacedLayer parent() {
        return parent;
    }

    /** 座標の原点の箱（{@link SceneLayer#isOrigin} の最も近い祖先。自分は含めない）。無ければ null。 */
    public PlacedLayer originParent() {
        PlacedLayer p = parent;
        while (p != null && !p.layer.isOrigin()) p = p.parent;
        return p;
    }

    /**
     * 子の層 {@code c}（この層の子のチャンクの 1 つで、チャンクの位置が {@code (chunkX, chunkY)}）の置き場所。原点の箱がスクロールコンテナなら
     * そのスクロール量を引き、アンカーの追従と sticky のずれを足す。
     */
    public PlacedLayer child(SceneLayer c, float chunkX, float chunkY, DynamicSceneState dyn) {
        PlacedLayer o = layer.isOrigin() ? this : originParent();
        float ox = o != null ? o.x : 0f, oy = o != null ? o.y : 0f;
        if (o != null && o.layer.isScrollContainer()) {
            ox -= dyn.scrollX(o.layer);
            oy -= dyn.scrollY(o.layer);
        }
        float cx = ox + chunkX + c.x;
        float cy = oy + chunkY + c.y;
        if (c.follow == null && c.sticky == null) return new PlacedLayer(c, this, cx, cy);
        float[] d = dyn.anchorShift(c);
        if (d != null) {
            cx += d[0];
            cy += d[1];
        }
        if (c.sticky != null) {
            PlacedLayer port = this;
            while (port != null && !port.layer.isScrollContainer()) port = port.parent;
            SceneLayer pl = port != null ? port.layer : null;
            float portX = port != null ? port.x + pl.bL : 0f;
            float portY = port != null ? port.y + pl.bT : 0f;
            float portW = port != null ? Math.max(0f, pl.w - pl.bL - pl.bR) : dyn.viewportW;
            float portH = port != null ? Math.max(0f, pl.h - pl.bT - pl.bB) : dyn.viewportH;
            SceneLayer l = layer;
            float[] stick = dyn.stickyOffset(c, cx, cy, portX, portY, portW, portH,
                    x + l.bL + l.pL, y + l.bT + l.pT, x + l.w - l.bR - l.pR, y + l.h - l.bB - l.pB);
            if (stick != null) {
                cx += stick[0];
                cy += stick[1];
            }
        }
        return new PlacedLayer(c, this, cx, cy);
    }

    @Override
    public String toString() {
        return "PlacedLayer[" + layer + " @" + x + "," + y + "]";
    }
}
