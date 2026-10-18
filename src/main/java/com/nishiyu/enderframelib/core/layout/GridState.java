package com.nishiyu.enderframelib.core.layout;

import java.util.ArrayList;
import java.util.List;

/** グリッドの行列サイズと配置済みアイテム。 */
public class GridState implements LayoutEngineState {
    public float[] colWidths;
    public float[] rowHeights;
    public List<GridItem> items = new ArrayList<>();
    public int colsCount = 1, rowsCount = 1;

    @Override
    public void contributeScrollExtent(ScrollExtent extent, float scrollX, float scrollY) {
        // グリッドアイテムは box.children とは別に保持しているため、ここで範囲に足し込む
        for (GridItem item : items) {
            if (item.box.isAbsolute) continue;
            extent.include(item.box, scrollX, scrollY);
        }
    }
}
