package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.LayerSpec;

import java.util.List;

/**
 * 一時アダプタ（{@link SceneDisplayListAdapter}）が描画順に組み立てた項目の受け手（M3b-4b-2）。今は表示項目の列（{@link #toList}）だけ
 * （走査器は M3b-4b-4a からビューの列を入力にする）。一時アダプタとともに M3b-4c で消える。
 */
interface ItemSink {
    /** 項目を描画順の末尾に足す。 */
    void add(DisplayItem item);

    /** これまでに受け取った項目の数（次に足す項目の位置）。 */
    int size();

    /**
     * 位置 {@code anchor} の節の錨（層を作る箱の錨）に、子孫を描き終えて決まった層の指定 {@code spec}（範囲・マスクの開始を含む）を載せる。
     */
    void setLayer(int anchor, LayerSpec spec);

    /** 表示項目の列に溜める受け手。 */
    static ItemSink toList(List<DisplayItem> out) {
        return new ItemSink() {
            @Override public void add(DisplayItem item) { out.add(item); }
            @Override public int size() { return out.size(); }
            @Override public void setLayer(int anchor, LayerSpec spec) { out.set(anchor, BoxItemPainter.withLayer(out.get(anchor), spec)); }
        };
    }
}
