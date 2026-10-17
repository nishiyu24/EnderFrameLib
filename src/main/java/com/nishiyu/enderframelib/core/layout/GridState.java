package com.nishiyu.enderframelib.core.layout;

import java.util.ArrayList;
import java.util.List;

public class GridState {
    public float[] colWidths;
    public float[] rowHeights;
    public List<GridItem> items = new ArrayList<>();
    public int colsCount = 1, rowsCount = 1;
}