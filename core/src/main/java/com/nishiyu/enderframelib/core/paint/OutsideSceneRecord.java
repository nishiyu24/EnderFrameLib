package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.raster.Matrix2D;

import java.util.Arrays;

/**
 * 走査器（{@link SceneWalker}）が前の走査で描いた、シーンの違いに現れない理由で絵が変わりうる項目の記録（M3b-4.24a。計画書 §3.4）:
 * 補間の状態を持つ項目（補間が走った・キーフレームを持つ）と外の事情で描く項目（画像・動画・キャンバス・3D）の、範囲（CSS px）と補間の状態。
 * 描く前の判定（{@link SceneWalker#changesOutsideScene}）と、描き直す範囲に足す補間・外の事情の分は、列を先にたどらずにこれを読む
 * （以前は描く前に列の全部の項目の補間の状態を引いていた）。
 *
 * <p>前の走査の範囲を今のフレームに使ってよいのは、項目の範囲を変える変化が、どれもシーンの違い（層の移動・スクロール。{@link SceneDamage}）か
 * 全面（姿勢を動かす補間・利用者の平行移動・描かない節の変化）で描き直す範囲に入るから。今のフレームで新しく始まる補間は、始まりが目標の変化
 * （シーンの違い）なので、始まった時刻の絵（元の値）はシーンの違いの範囲で描かれ、次のフレームからこの記録に入る。</p>
 *
 * <p>前の走査の記録（読む）と今の走査の記録（書く）の 2 つを入れ替えて使う。覚えて描くのを省くものではなく、前のフレームの重ね描きの範囲と同じ
 * 比べる相手。スレッドセーフではない。</p>
 */
final class OutsideSceneRecord {
    /** 記録の 1 本（項目ごとに補間の状態・外の事情の印・範囲の有無と範囲）。 */
    private static final class Rec {
        CssAnimator.AnimState[] states = new CssAnimator.AnimState[16];
        boolean[] external = new boolean[16];
        boolean[] hasBox = new boolean[16];
        float[] box = new float[64];
        int n;

        void clear() {
            Arrays.fill(states, 0, n, null);
            n = 0;
        }

        void add(CssAnimator.AnimState st, boolean ext, boolean withBox, float x0, float y0, float x1, float y1) {
            if (n == states.length) {
                int cap = n * 2;
                states = Arrays.copyOf(states, cap);
                external = Arrays.copyOf(external, cap);
                hasBox = Arrays.copyOf(hasBox, cap);
                box = Arrays.copyOf(box, cap * 4);
            }
            states[n] = st;
            external[n] = ext;
            hasBox[n] = withBox;
            box[n * 4] = x0;
            box[n * 4 + 1] = y0;
            box[n * 4 + 2] = x1;
            box[n * 4 + 3] = y1;
            n++;
        }
    }

    private Rec prev = new Rec(), cur = new Rec();

    /** 今の走査の記録を始める（前の走査の記録は {@link #commit} まで読める）。 */
    void begin() { cur.clear(); }

    /** 今の走査の記録の長さ（{@link #truncate} で戻す位置）。 */
    int mark() { return cur.n; }

    /** 今の走査の記録を {@code mark} まで戻す（保持層へ描いた範囲を、ふつうに描き直すとき）。 */
    void truncate(int mark) {
        if (mark >= cur.n) return;
        Arrays.fill(cur.states, mark, cur.n, null);
        cur.n = mark;
    }

    /** 描いた項目を記録する。{@code st} は補間の状態（補間の無い外の事情の項目は null 可）。範囲は CSS px（節の錨は範囲を持たない）。 */
    void add(CssAnimator.AnimState st, boolean external, boolean hasBox, float x0, float y0, float x1, float y1) {
        cur.add(st, external, hasBox, x0, y0, x1, y1);
    }

    /** 今の走査の記録を、次のフレームが読む記録にする。 */
    void commit() {
        Rec t = prev;
        prev = cur;
        cur = t;
        cur.clear();
    }

    /** 記録を全部捨てる（描く項目が無いフレーム）。 */
    void clear() {
        prev.clear();
        cur.clear();
    }

    /** 前の走査で描いた項目に、外の事情で描くものか、時刻 {@code now} か前の時刻 {@code prevNow}（無ければ負）に補間が動いているものがあるか。 */
    boolean changes(long now, long prevNow) {
        Rec r = prev;
        for (int k = 0; k < r.n; k++) {
            if (r.external[k]) return true;
            CssAnimator.AnimState st = r.states[k];
            if (st != null && (st.isActiveAt(now) || (prevNow >= 0L && st.isActiveAt(prevNow)))) return true;
        }
        return false;
    }

    /**
     * 前の走査で描いた項目のうち、時刻 {@code prevNow} か {@code now} に補間が動いている項目と外の事情で描く項目の範囲を {@code damage} へ足す。
     * 補間が姿勢（変形・不透明度）を動かすときは、子孫の位置と層が変わるので全面にする。
     */
    void addDamage(SceneDamage.Rects damage, long now, long prevNow) {
        Rec r = prev;
        for (int k = 0; k < r.n && !damage.full(); k++) {
            CssAnimator.AnimState st = r.states[k];
            boolean activeNow = st != null && st.isActiveAt(now), activeBefore = st != null && prevNow >= 0L && st.isActiveAt(prevNow);
            if (activeNow || activeBefore) {
                int kind = Math.max(activeNow ? st.motionKindAt(now) : 0, activeBefore ? st.motionKindAt(prevNow) : 0);
                if (kind >= SceneWalker.ACT_GEOMETRY) {
                    damage.setFull("animation-geometry");
                    return;
                }
            } else if (!r.external[k]) {
                continue;
            }
            if (r.hasBox[k]) damage.add(r.box[k * 4], r.box[k * 4 + 1], r.box[k * 4 + 2], r.box[k * 4 + 3]);
        }
    }

    /**
     * 項目 {@code el} の箱（影・文字の余白つき）を姿勢 {@code pose} で写し、フレームの変換 {@code frame}（CSS px → デバイス画素）を戻した
     * CSS px の外接矩形を {@code out}（minX・minY・maxX・maxY）に入れて返す。
     */
    static float[] bounds(DisplayItem el, Matrix2D pose, Matrix2D frame, float[] out) {
        float pad = SceneDamage.padOf(el.meta(), el.text(), el.height());
        double x0 = el.x() - pad, y0 = el.y() - pad, x1 = el.x() + el.width() + pad, y1 = el.y() + el.height() + pad;
        double minX = Double.POSITIVE_INFINITY, minY = minX, maxX = Double.NEGATIVE_INFINITY, maxY = maxX;
        for (int c = 0; c < 4; c++) {
            double px = (c & 1) == 0 ? x0 : x1, py = (c & 2) == 0 ? y0 : y1;
            double dx = pose.a() * px + pose.c() * py + pose.e(), dy = pose.b() * px + pose.d() * py + pose.f();
            minX = Math.min(minX, dx); maxX = Math.max(maxX, dx); minY = Math.min(minY, dy); maxY = Math.max(maxY, dy);
        }
        double sx = frame.a() != 0.0 ? frame.a() : 1.0, sy = frame.d() != 0.0 ? frame.d() : 1.0;
        out[0] = (float) ((minX - frame.e()) / sx);
        out[1] = (float) ((minY - frame.f()) / sy);
        out[2] = (float) ((maxX - frame.e()) / sx);
        out[3] = (float) ((maxY - frame.f()) / sy);
        return out;
    }
}
