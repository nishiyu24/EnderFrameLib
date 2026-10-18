package com.nishiyu.enderframelib.core.layout;

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
public class LayoutEngineFactory {
    private static final LayoutEngine BLOCK = new BlockLayoutEngine();
    private static final LayoutEngine FLEX = new FlexLayoutEngine();
    private static final LayoutEngine GRID = new GridLayoutEngine();

    public static LayoutEngine getEngine(String display) {
        if (display == null) return BLOCK;
        return switch (display.trim().toLowerCase()) {
            case "flex", "inline-flex" -> FLEX;
            case "grid", "inline-grid" -> GRID;
            default -> BLOCK; // block, inline, inline-block, table 等はすべてブロックレイアウトで処理
        };
    }
}