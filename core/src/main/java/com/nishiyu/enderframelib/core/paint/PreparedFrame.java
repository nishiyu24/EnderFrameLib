package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.PaintOffsets;
import com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo;

import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;

/**
 * 描く前に準備したフレーム（M3b-4c-2a。{@link RenderBackend#prepare} が返し、{@link RenderBackend#render(PreparedFrame)} が受け取る）。
 * 前に描いたフレームから描き直す範囲と、描き直しが要るかを持つ。描き直しが要らなければ描かずに前の画素を使ってよい（前の画素を持ち続ける描画先）。
 * M3b-4c-2b-2b から、シーンから組んだ表示項目の列（{@link FrameComposer}）もここに持つ（ビューは列を持たない）。
 *
 * <p>描画器は最後に準備した値だけを信じる: 別の描画器の値・前に準備した値・2 度目の {@code render} は全面を描く（比べる相手は準備で 1 度だけ進める）。
 * 以前の {@code FrameDamage} の plan → take（別々の入力を実体の同一性で結ぶ）の代わりに、準備した値そのものを渡す。</p>
 */
public final class PreparedFrame {
    private final RenderFrame frame;
    private final FrameComposer.Composed composed;
    private final SceneDamage.Rects damage;
    private final boolean repaint;
    private final PreparedFrame inner;

    /**
     * @param frame    準備したフレーム
     * @param composed フレームの列（シーンから組んだ列か、シーンの無いフレームの列。null なら中の準備 {@code inner} の列、それも無ければフレームの列）
     * @param damage   描き直す範囲（null なら全面）
     * @param repaint  描き直しが要るか
     * @param inner    描画器が中で使う別の描画器の準備（GPU の描画器が CPU の描画器に描かせるとき。無ければ null）
     */
    public PreparedFrame(RenderFrame frame, FrameComposer.Composed composed, SceneDamage.Rects damage, boolean repaint, PreparedFrame inner) {
        this.frame = frame;
        this.composed = composed != null ? composed : inner != null ? inner.composed
                : new FrameComposer.Composed(frame.items(), frame.offsets(), List.of(), null);
        this.damage = damage;
        this.repaint = repaint;
        this.inner = inner;
    }

    /** 範囲を求めず列も組まない描画器の準備（いつも全面を描く。シーンのフレームは描く項目が無い）。 */
    public static PreparedFrame full(RenderFrame frame) {
        return new PreparedFrame(frame, null, null, true, null);
    }

    public RenderFrame frame() {
        return frame;
    }

    /** シーンから組んだ項目の列（シーンの無いフレームはその列）。重ね描きは含まない。 */
    public List<DisplayItem> items() {
        return composed.items();
    }

    /** 描く列（{@link #items} の後ろに重ね描き。写さない）。 */
    public List<DisplayItem> drawList() {
        List<DisplayItem> a = composed.items(), b = frame.overlays();
        return b.isEmpty() ? a : new Joined(a, b);
    }

    /** 描く列のうちシーンから組んだ部分の長さ（シーンが無ければ 0。後ろは重ね描き）。 */
    public int sceneItemCount() {
        return frame.scene() != null ? composed.items().size() : 0;
    }

    /** このフレームの描画上の平行移動（利用者の分と、スクロールコンテナの中身の移動）。 */
    public PaintOffsets offsets() {
        return composed.offsets();
    }

    /**
     * ホストが描く中身（{@code HostTextures#wants}。重ね描きの分を含み、描かない節の分を除く。
     * M3b-4c-2b-3b-2b-2）。ホストは表示項目の列を読まず、これだけを見て描く前に用意する。
     */
    public List<com.nishiyu.enderframelib.core.paint.model.HostContent> hostContents() {
        return composed.hostContents();
    }

    /** 中身を 1 つの節にまとめたスクロールコンテナ（シーンが無ければ空）。 */
    public List<ScrollContentInfo> contents() {
        return composed.contents();
    }

    /**
     * 描く列の階層（M3b-4c-2b-3b-1）: シーンのフレームは列を組んだ側がシーンの層をたどって書いたもの（後ろの重ね描きは親を持たない）。
     * シーンの無いフレーム（列だけのフレーム。M3b-4c-3 で消す）は項目の鍵から作る（呼ぶたびに作る）。
     */
    public FrameStructure structure() {
        FrameStructure s = composed.structure();
        return s != null ? s : FrameStructure.fromKeys(drawList());
    }

    /** 描き直しが要るか（偽なら前に描いた画素がこのフレームの絵）。 */
    public boolean needsRepaint() {
        return repaint;
    }

    /** 描き直す範囲（null なら全面）。 */
    public SceneDamage.Rects damage() {
        return damage;
    }

    /** 中で使う別の描画器の準備（無ければ null）。 */
    public PreparedFrame inner() {
        return inner;
    }

    /** 2 つの列をつないだ読み取りだけの見え方。 */
    private static final class Joined extends AbstractList<DisplayItem> implements RandomAccess {
        private final List<DisplayItem> a, b;
        private final int na;

        Joined(List<DisplayItem> a, List<DisplayItem> b) {
            this.a = a;
            this.b = b;
            this.na = a.size();
        }

        @Override
        public DisplayItem get(int i) {
            return i < na ? a.get(i) : b.get(i - na);
        }

        @Override
        public int size() {
            return na + b.size();
        }
    }
}
