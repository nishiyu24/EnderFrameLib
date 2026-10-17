package com.nishiyu.enderframelib.core.layout;

/**
 * スクロール範囲（内容がどこまで広がっているか）の集計。
 * エンジン固有の子要素も自分で寄与できるようにするための受け皿。
 */
public final class ScrollExtent {

    private int right;
    private int bottom;

    public ScrollExtent(int initialRight, int initialBottom) {
        this.right = initialRight;
        this.bottom = initialBottom;
    }

    /** 1 つのボックスの右下端を取り込む。 */
    public void include(LayoutBox box, int scrollX, int scrollY) {
        include(box.x + box.w + box.mR + scrollX, box.y + box.h + box.mB + scrollY);
    }

    public void include(int rightEdge, int bottomEdge) {
        if (rightEdge > right) right = rightEdge;
        if (bottomEdge > bottom) bottom = bottomEdge;
    }

    public int right() {
        return right;
    }

    public int bottom() {
        return bottom;
    }
}
