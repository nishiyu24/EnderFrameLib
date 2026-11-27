package com.nishiyu.enderframelib.core.paint.scene;

import java.util.ArrayList;
import java.util.List;

/**
 * 1 回の当たり判定・スクロールコンテナ列挙の作業用の値（M3b-3）。{@link SceneHitTest.Context} が予備を 1 つ持ち、
 * 同じ文脈（= 同じビュー）の次の問い合わせで使い回す。並行に呼ばれたときは新しく作るだけで共有しない
 * （静的状態・{@code ThreadLocal} は持たない）。
 */
final class HitScratch {
    final SceneHitTest.Context ctx;
    final DynamicSceneState dyn;
    float resX, resY;
    double ptX, ptY;
    private float[] anim6;
    int[] visited;
    /** z-index の回で集めた子。{@code [base, zSize)} を 1 回ぶんとして積み、終わったら {@code zSize = base} に戻す。 */
    private ZNode[] z = new ZNode[8];
    int zSize;
    private int zHigh;
    private SceneHit firstScroll;
    private ArrayList<SceneHit> moreScrolls;

    /** z-index の回で後から辿る子とその位置（使い回すので可変）。 */
    static final class ZNode {
        SceneLayer layer;
        float lx, ly, originX, originY, portX, portY, portW, portH;
        double px, py;
    }

    HitScratch(SceneHitTest.Context ctx) {
        this.ctx = ctx;
        this.dyn = ctx.dynamic;
    }

    /** 問い合わせを終えた後に呼ぶ。scene を握り続けないよう参照を外す。 */
    void reset() {
        visited = null;
        firstScroll = null;
        moreScrolls = null;
        for (int i = 0; i < zHigh; i++) z[i].layer = null;
        zSize = 0;
        zHigh = 0;
    }

    float[] anim6() {
        if (anim6 == null) anim6 = new float[6];
        return anim6;
    }

    void pushZ(SceneLayer layer, float lx, float ly, float originX, float originY,
               float portX, float portY, float portW, float portH, double px, double py) {
        if (zSize == z.length) z = java.util.Arrays.copyOf(z, z.length * 2);
        ZNode e = z[zSize];
        if (e == null) z[zSize] = e = new ZNode();
        e.layer = layer;
        e.lx = lx; e.ly = ly; e.originX = originX; e.originY = originY;
        e.portX = portX; e.portY = portY; e.portW = portW; e.portH = portH;
        e.px = px; e.py = py;
        if (++zSize > zHigh) zHigh = zSize;
    }

    ZNode z(int i) {
        return z[i];
    }

    /** {@code [from, to)} を z-index の昇順に安定に並べる（確保しない挿入ソート）。 */
    void sortZ(int from, int to) {
        for (int i = from + 1; i < to; i++) {
            ZNode e = z[i];
            int j = i - 1;
            while (j >= from && z[j].layer.zIndex > e.layer.zIndex) {
                z[j + 1] = z[j];
                j--;
            }
            z[j + 1] = e;
        }
    }

    void addScrollHit(SceneHit h) {
        if (firstScroll == null) {
            firstScroll = h;
            return;
        }
        if (moreScrolls == null) {
            moreScrolls = new ArrayList<>(4);
            moreScrolls.add(firstScroll);
        }
        moreScrolls.add(h);
    }

    List<SceneHit> scrollResult() {
        return moreScrolls != null ? moreScrolls : firstScroll != null ? List.of(firstScroll) : List.of();
    }

    void countChunk() {
        if (visited != null && visited.length > 0) visited[0]++;
    }
}
