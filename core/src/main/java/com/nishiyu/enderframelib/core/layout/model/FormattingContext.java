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

    /** 取り得る組（親の種類 × stretch × shrink）の共有の実体。子の測定のたびに作らない。 */
    private static final FormattingContext[] SHARED = new FormattingContext[Display.values().length * 4];

    static {
        for (Display d : Display.values()) {
            for (int bits = 0; bits < 4; bits++) {
                SHARED[d.ordinal() * 4 + bits] = new FormattingContext(d, (bits & 1) != 0, (bits & 2) != 0);
            }
        }
    }

    /** {@code new FormattingContext(...)} と同じ値の、共有の実体。 */
    public static FormattingContext of(Display parentDisplay, boolean stretch, boolean isShrink) {
        return SHARED[parentDisplay.ordinal() * 4 + (stretch ? 1 : 0) + (isShrink ? 2 : 0)];
    }

    public boolean isFlexRow() { return parentDisplay == Display.ROW || parentDisplay == Display.ROW_REVERSE; }
    public boolean isFlexColumn() { return parentDisplay == Display.COLUMN || parentDisplay == Display.COLUMN_REVERSE; }
    public boolean isBlock() { return parentDisplay == Display.BLOCK; }
    public boolean isGrid() { return parentDisplay == Display.GRID; }
}
