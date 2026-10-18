package com.nishiyu.enderframelib.core.layout;

/**
 * 子の測定に親から降りてくる整形コンテキスト。
 *
 * {@code parentDisplay} には、ブロックなら {@code "block"}、グリッドなら {@code "grid"}、
 * フレックスなら {@code flex-direction} の値（{@code "row"} / {@code "column"} /
 * {@code "row-reverse"} …）が入る。
 *
 * 何が壊れていたか: 以前この判定は {@code "flex-row"} / {@code "flex-column"} という
 * 前置きを探していたが、その文字列を作る箇所は 1 つも無く、両メソッドは常に false だった。
 * そのため幅指定の無いフレックスアイテムが shrink-to-fit にならず、いったん親幅いっぱいに
 * 広がってから shrink で削られる挙動になり、{@code flex: 1} の等分が効かなかった。
 */
public record FormattingContext(String parentDisplay, String alignItems, boolean isShrink) {
    public boolean isFlexRow() { return parentDisplay.startsWith("row"); }
    public boolean isFlexColumn() { return parentDisplay.startsWith("column"); }
}