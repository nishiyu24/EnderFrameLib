package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.LayerSpec;

import java.util.Arrays;
import java.util.List;

/**
 * 一時アダプタ（{@link SceneDisplayListAdapter}）が描画順に組み立てた項目の受け手（M3b-4b-2）。項目と一緒に、シーンの層をたどって決まる
 * 親の錨の位置を受け取り、列の階層（{@link FrameStructure}。M3b-4c-2b-3b-1）を作る。一時アダプタとともに M3b-4c で消える。
 */
interface ItemSink {
    /** 列の中の位置がまだ決まっていないかもしれない錨（箱の錨・スクロールの中身の節）。 */
    interface AnchorRef {
        /** 錨の項目の位置（まだ足していなければ -1）。 */
        int anchorPos();
    }

    /**
     * 項目を描画順の末尾に足す。{@code parent} は親の錨の位置（無ければ -1）。親の錨が後から足される（祖先の錨より先に描く
     * {@code ::backdrop}・重なりの順で前に出る子）ときは {@code parent} を -1 にして {@code later} を渡すと、組み終えたときに位置を引く。
     */
    void add(DisplayItem item, int parent, AnchorRef later);

    /** これまでに受け取った項目の数（次に足す項目の位置）。 */
    int size();

    /**
     * 位置 {@code anchor} の節の錨（層を作る箱の錨）に、子孫を描き終えて決まった層の指定 {@code spec}（範囲・マスクの開始を含む）を載せる。
     */
    void setLayer(int anchor, LayerSpec spec);

    /** 位置 {@code pos} の項目を、中身を 1 つの節にまとめたスクロールコンテナの中身の節にする（{@code token} は中身の同一性の印）。 */
    void markContent(int pos, Object token);

    /** 表示項目の列に溜め、階層を書く受け手（組むたびに {@link #begin} して使い回す）。 */
    final class ToList implements ItemSink {
        private List<DisplayItem> out;
        private final FrameStructure.Builder structure = new FrameStructure.Builder();
        private int[] pendingPos = new int[8];
        private AnchorRef[] pendingRef = new AnchorRef[8];
        private int pending;

        /** 列 {@code out} へ組み始める（階層の組み立て器を空に戻す）。 */
        ToList begin(List<DisplayItem> out) {
            this.out = out;
            structure.reset();
            pending = 0;
            return this;
        }

        @Override
        public void add(DisplayItem item, int parent, AnchorRef later) {
            if (parent < 0 && later != null) {
                if (pending == pendingPos.length) {
                    pendingPos = Arrays.copyOf(pendingPos, pending * 2);
                    pendingRef = Arrays.copyOf(pendingRef, pending * 2);
                }
                pendingPos[pending] = out.size();
                pendingRef[pending++] = later;
            }
            structure.add(parent);
            out.add(item);
        }

        @Override public int size() { return out.size(); }

        @Override public void setLayer(int anchor, LayerSpec spec) { out.set(anchor, BoxItemPainter.withLayer(out.get(anchor), spec)); }

        @Override public void markContent(int pos, Object token) { structure.markContent(pos, token); }

        /** 組み終える: 後から足された親の錨の位置を引き、列への参照を手放す。 */
        void finish() {
            for (int k = 0; k < pending; k++) {
                int p = pendingRef[k].anchorPos();
                if (p >= 0) structure.setParent(pendingPos[k], p);
                pendingRef[k] = null;
            }
            pending = 0;
            out = null;
        }

        /** 直近に組み終えた列の階層（呼んだときに作る。列を組むだけで階層を読まない組み手〔ビューの一時の列〕は作らない）。 */
        FrameStructure structure() {
            return structure.build();
        }
    }
}
