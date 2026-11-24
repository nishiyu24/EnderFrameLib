package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxNode;

/**
 * グリッドの項目 1 つの作業域（Engine v3 M2b-3）。1 回の整形の間だけ使う局所の値で、断片には残さない。
 * 配置（行・列の始点とまたぐ数。0 始まり、未定は -1）と、トラックの寸法を決める寄与（余白を含む）を持つ。
 */
final class GridItem {
    final BoxNode box;
    final BoxGeometry g;
    final LayoutCache.Slots slots;
    int colStart = -1, colSpan = 1, rowStart = -1, rowSpan = 1;
    /** 列・行の min-content / max-content の寄与（余白を含む）。 */
    float colMin, colMax, rowMin, rowMax;
    LayoutFragment frag;
    /** 内容ボックス基準の border-box の左上。 */
    float x, y;

    GridItem(BoxNode box, BoxGeometry g, LayoutCache.Slots slots) {
        this.box = box;
        this.g = g;
        this.slots = slots;
    }

    int start(boolean columns) {
        return columns ? colStart : rowStart;
    }

    int span(boolean columns) {
        return columns ? colSpan : rowSpan;
    }

    float minContribution(boolean columns) {
        return columns ? colMin : rowMin;
    }

    float maxContribution(boolean columns) {
        return columns ? colMax : rowMax;
    }

    @Override
    public String toString() {
        return "GridItem[" + box + " r" + rowStart + "+" + rowSpan + " c" + colStart + "+" + colSpan + "]";
    }
}
