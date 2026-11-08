package com.nishiyu.enderframelib.core.layout.model;

/**
 * 子の測定に親から降りてくる整形コンテキスト。親の種類は列挙、交差軸の揃えが stretch かは boolean で持つ
 * （子の測定が読むのは「stretch か」だけ。文字列の比較・生成をしない）。
 */
public record FormattingContext(Display parentDisplay, boolean stretch, boolean isShrink) {

    /** 子を置く親の整形の種類。flex は主軸の向きまで区別する。 */
    public enum Display {
        BLOCK, GRID, LEGEND, ROW, ROW_REVERSE, COLUMN, COLUMN_REVERSE
    }

    /** 通常のブロックフロー（幅いっぱいに広がる）。 */
    public static final FormattingContext BLOCK = new FormattingContext(Display.BLOCK, true, false);

    public boolean isFlexRow() { return parentDisplay == Display.ROW || parentDisplay == Display.ROW_REVERSE; }
    public boolean isFlexColumn() { return parentDisplay == Display.COLUMN || parentDisplay == Display.COLUMN_REVERSE; }
    public boolean isBlock() { return parentDisplay == Display.BLOCK; }
    public boolean isGrid() { return parentDisplay == Display.GRID; }
}
