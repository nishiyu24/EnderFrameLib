package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.layout.LayoutBox;
import java.util.List;

/**
 * 窓の外の部分木を O(1) で再利用するための<b>インク境界</b>（K4。設計 {@code core/DESIGN_INCREMENTAL_KERNEL.md} §3.4・§8-3）。
 *
 * <p>窓付きの組み立て（{@code cull}）では、自分の幾何が窓（画面＋{@link DisplayListBuilder#CULL_MARGIN}）の外にある箱は項目を作らない。
 * 部分木の中の項目を持つ箱が<b>全て</b>窓の外にあって断片が空になったとき、部分木が<b>平行移動しただけ</b>で、移動後も全ての箱が窓の外に
 * あるなら、断片は空のまま（作り直しても同じ）。これを部分木の子孫の幾何の外接矩形（箱の左上からの相対。= インク境界）で O(1) に判定する。</p>
 *
 * <p>境界は、窓の判定（{@link DisplayListBuilder#inWindowGeometry}）が読む矩形〔箱の矩形・インラインの断片〕だけの和集合で、保守的
 * （窓に掛かる可能性があれば代理を使わない）。平行移動で動かない子孫〔位置指定・sticky〕、自分の位置を親の外から受ける箱
 * 〔インラインの断片を持つ箱自身・無名・文字〕を含む部分木は作らない（{@code null}）。影・outline・変形は、窓の判定が箱の矩形だけを見る
 * 既存の規則と同じで、変形・アニメーションのある箱は窓で省かれない（項目を持つ）ので、空の断片には現れない。</p>
 */
final class InkBounds {
    private InkBounds() {}

    /** 窓の外の制約が要らない（部分木に項目を持つ箱が無い）ことを表す印。 */
    static final float[] UNCONSTRAINED = new float[0];
    /** 窓の外で空になった部分木で、境界をまだ作っていない印（平行移動が起きたときに初めて作る。組み立てごとの確保を避ける）。 */
    static final float[] PENDING = new float[0];
    /** 境界を作れなかった（平行移動で動かない子孫を含む等）印。代理を使わない。 */
    static final float[] NONE = new float[0];

    private static final int MIN_X = 0, MIN_Y = 1, MAX_X = 2, MAX_Y = 3;

    /**
     * {@code box} の部分木の幾何の外接矩形（箱の左上からの相対 {@code [minX, minY, maxX, maxY]}）。作れないとき {@code null}。
     * 呼び出し側は、断片が空で、かつ窓の外で省かれた箱を含み、箱の印・子の部分木の印が記憶より古い（部分木の中が記憶の時点と同じ）ときだけ呼ぶ。
     */
    static float[] compute(LayoutBox box) {
        if (box.isAnonymous || box.node == null || "#text".equals(box.node.tag) || box.isAbsolute() || box.isSticky()) return null;
        List<LayoutBox.InlineFragment> own = box.inlineFragments;
        if (own != null && !own.isEmpty()) return null;
        float ox = box.x(), oy = box.y();
        float[] r = {ox, oy, ox + box.w(), oy + box.h()};
        List<LayoutBox> ch = box.children;
        for (int i = 0, n = ch.size(); i < n; i++) if (!grow(ch.get(i), r)) return null;
        r[MIN_X] -= ox; r[MAX_X] -= ox; r[MIN_Y] -= oy; r[MAX_Y] -= oy;
        return r;
    }

    private static boolean grow(LayoutBox b, float[] r) {
        if (b.isAbsolute() || b.isSticky()) return false;
        List<LayoutBox.InlineFragment> frags = b.inlineFragments;
        if (frags != null && !frags.isEmpty()) {
            LayoutBox op = b.originParent();
            float fx = op != null ? op.x() : 0f, fy = op != null ? op.y() : 0f;
            for (int i = 0, n = frags.size(); i < n; i++) {
                LayoutBox.InlineFragment f = frags.get(i);
                include(r, f.x + fx, f.y + fy, f.x + fx + f.w, f.y + fy + f.h);
            }
        }
        include(r, b.x(), b.y(), b.x() + b.w(), b.y() + b.h());
        List<LayoutBox> ch = b.children;
        for (int i = 0, n = ch.size(); i < n; i++) if (!grow(ch.get(i), r)) return false;
        return true;
    }

    private static void include(float[] r, float x0, float y0, float x1, float y1) {
        if (x0 < r[MIN_X]) r[MIN_X] = x0;
        if (y0 < r[MIN_Y]) r[MIN_Y] = y0;
        if (x1 > r[MAX_X]) r[MAX_X] = x1;
        if (y1 > r[MAX_Y]) r[MAX_Y] = y1;
    }

    /**
     * 箱の左上が {@code (x, y)} に移ったとき、部分木の全ての箱がまだ窓の外にあるか（窓に掛かる可能性があれば {@code false}）。
     * 窓の判定は {@code x + w >= -margin && x <= rootW + margin && y + h >= -margin && y <= rootH + margin} で窓の中なので、その否定の十分条件。
     */
    static boolean stillOutside(float[] ink, float x, float y, float rootW, float rootH, float margin) {
        if (ink == UNCONSTRAINED) return true;
        if (ink == null || ink.length < 4) return false;
        return x + ink[MAX_X] < -margin || x + ink[MIN_X] > rootW + margin
                || y + ink[MAX_Y] < -margin || y + ink[MIN_Y] > rootH + margin;
    }
}
