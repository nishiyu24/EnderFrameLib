package com.nishiyu.enderframelib.core.layout;

public class LayoutEngineFactory {
    private static final LayoutEngine BLOCK = new BlockLayoutEngine();
    private static final LayoutEngine FLEX = new FlexLayoutEngine();
    private static final LayoutEngine GRID = new GridLayoutEngine();

    public static LayoutEngine getEngine(String display) {
        return switch (display) {
            case "flex" -> FLEX;
            case "grid" -> GRID;
            default -> BLOCK;
        };
    }
}