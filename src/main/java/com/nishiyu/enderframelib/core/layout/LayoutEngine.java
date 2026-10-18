package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.layout.LayoutBox.ScrollExtent;
import com.nishiyu.enderframelib.core.layout.LayoutContext.FormattingContext;

/**
 * 整形コンテキストのエンジン（block / flex / grid）の基底と、{@code display} からの選択。
 *
 * 3 つのレイアウトエンジンに共通する処理もここに置く。
 * 以前は「絶対配置子の確定」「justify-content の余白配分」
 * 「display:none のフィルタ」「太字判定」が各エンジンにコピーされていた
 * （合計で約 400 行の重複）。共有できるものはここに集約する。
 */
public abstract class LayoutEngine {

    /** 基底クラスの初期化中にサブクラスを生成しないよう、インスタンスは別ホルダーに置く。 */
    private static final class Engines {
        static final LayoutEngine BLOCK = new BlockLayoutEngine();
        static final LayoutEngine FLEX = new FlexLayoutEngine();
        static final LayoutEngine GRID = new GridLayoutEngine();
    }

    /**
     * {@code display} から整形コンテキストのエンジンを選ぶ。
     *
     * LIMITATION(制約): DOM と箱が 1:1 で、ボックスツリーを作らない。そのため
     * 匿名ブロック（ブロック内のインライン混在）・匿名表ボックス・{@code display: contents}・
     * {@code flow-root} の BFC が無く、{@code table} / {@code table-*} / {@code list-item} は
     * ブロックとして組まれる（表レイアウトが無い）。W3（ボックスツリー）と W4（表）で直す。
     * FIDELITY.md の「W3 — ボックスツリー」「W4 — 整形コンテキスト」と README の
     * "Not supported" に対応。
     */
    public static LayoutEngine getEngine(String display) {
        if (display == null) return Engines.BLOCK;
        return switch (display.trim().toLowerCase()) {
            case "flex", "inline-flex" -> Engines.FLEX;
            case "grid", "inline-grid" -> Engines.GRID;
            default -> Engines.BLOCK; // block, inline, inline-block, table 等はすべてブロックレイアウトで処理
        };
    }

    public abstract void measure(LayoutBox box, float tentativeInnerW, float parentH, FormattingContext fmtCtx, LayoutContext ctx);

    public abstract void resolveFlexibleHeights(LayoutBox box, float parentH, FormattingContext fmtCtx, LayoutContext ctx);

    public abstract void arrangeChildren(LayoutBox box, float innerX, float innerY, LayoutContext ctx);


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
        FormattingContext absCtx = new FormattingContext("block", "stretch", false);
        float rootW = ctx.viewport().rootW();
        float rootH = ctx.viewport().rootH();

        for (LayoutBox child : box.children) {
            if (child.isAbsolute && !isDisplayNone(child.style)) {
                LayoutBox cb = child.findContainingBlock();
                float cbW, cbH;
                if (child.isFixed || cb == null) {
                    cbW = rootW;
                    cbH = rootH;
                } else {
                    cbW = Math.max(0, cb.w - cb.horizontalDecoration());
                    cbH = Math.max(0, cb.h - cb.verticalDecoration());
                }
                child.measure(cbW, cbH, absCtx, ctx);
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
    protected MainAxisSpacing distributeMainAxis(String justify, float start, float availableMain,
                                                 float itemsTotal, float baseGap, int itemCount) {
        float totalWithGap = itemsTotal + baseGap * Math.max(0, itemCount - 1);
        float container = Math.max(totalWithGap, availableMain);
        float gap = baseGap;

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
    protected record MainAxisSpacing(float start, float gap) {}

    /**
     * レイアウトエンジンがボックスに持たせる、エンジン固有の状態。
     *
     * 以前は {@code LayoutBox.engineState} が {@code Object} で、スクロール範囲を求めるために
     * {@code LayoutBox} が {@code instanceof GridLayoutEngine.GridState} でエンジン内部型を
     * 覗き込んでいた（core/layout の中で循環していた）。
     * 範囲への寄与をこのインターフェースの責務にすることでその参照を断つ。
     */
    public interface LayoutEngineState {

        /**
         * 通常フローの子以外に、このエンジンが配置した要素の範囲を足し込む。
         * 何も追加しないエンジンは実装しなくてよい（既定は何もしない）。
         */
        default void contributeScrollExtent(ScrollExtent extent, float scrollX, float scrollY) {
        }
    }
}
