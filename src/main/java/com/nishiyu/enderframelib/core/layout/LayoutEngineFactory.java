package com.nishiyu.enderframelib.core.layout;

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