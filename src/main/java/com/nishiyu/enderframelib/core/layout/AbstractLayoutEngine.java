package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssProperty;

/**
 * 3 つのレイアウトエンジンに共通する処理。
 *
 * 以前は「絶対配置子の確定」「justify-content の余白配分」
 * 「display:none のフィルタ」「太字判定」が各エンジンにコピーされていた
 * （合計で約 400 行の重複）。共有できるものはここに集約する。
 */
public abstract class AbstractLayoutEngine implements LayoutEngine {

    protected boolean isDisplayNone(ComputedStyle style) {
        return "none".equals(style.getOrDefault(CssProperty.DISPLAY, "block"));
    }

    protected boolean isBold(ComputedStyle style) {
        return LayoutMetrics.isBold(style);
    }

    /**
     * 絶対配置の子を、確定した内容領域を基準に測り直す。
     * 3 エンジンに同じ 5 行がコピーされていた部分。
     */
    protected void measureAbsoluteChildren(LayoutBox box, LayoutContext ctx) {
        int innerW = Math.max(0, box.w - box.horizontalDecoration());
        int innerH = Math.max(0, box.h - box.verticalDecoration());
        FormattingContext absCtx = new FormattingContext("block", "stretch", false);

        for (LayoutBox child : box.children) {
            if (child.isAbsolute && !isDisplayNone(child.style)) {
                child.measure(innerW, innerH, absCtx, ctx);
            }
        }
    }

    /**
     * 主軸方向の余白配分（{@code justify-content}）。
     *
     * @param start        配分前の開始座標
     * @param availableMain 利用できる主軸サイズ
     * @param itemsTotal   アイテムの合計サイズ（gap を含まない）
     * @param baseGap      スタイル指定の gap
     * @param itemCount    アイテム数
     */
    protected MainAxisSpacing distributeMainAxis(String justify, int start, int availableMain,
                                                 int itemsTotal, int baseGap, int itemCount) {
        int totalWithGap = itemsTotal + baseGap * Math.max(0, itemCount - 1);
        int container = Math.max(totalWithGap, availableMain);
        int gap = baseGap;

        switch (justify == null ? "flex-start" : justify) {
            case "space-between" -> {
                if (itemCount > 1) gap = Math.max(0, container - itemsTotal) / (itemCount - 1);
            }
            case "space-around" -> {
                if (itemCount > 0) {
                    gap = Math.max(0, container - itemsTotal) / itemCount;
                    start += gap / 2;
                }
            }
            case "space-evenly" -> {
                if (itemCount > 0) {
                    gap = Math.max(0, container - itemsTotal) / (itemCount + 1);
                    start += gap;
                }
            }
            case "center" -> start += Math.max(0, container - totalWithGap) / 2;
            case "flex-end", "end" -> start += Math.max(0, container - totalWithGap);
            default -> {
                // flex-start / start / 未知の値は先頭詰め
            }
        }
        return new MainAxisSpacing(start, gap);
    }

    /** {@link #distributeMainAxis} の結果（開始座標とアイテム間隔）。 */
    protected record MainAxisSpacing(int start, int gap) {}
}
