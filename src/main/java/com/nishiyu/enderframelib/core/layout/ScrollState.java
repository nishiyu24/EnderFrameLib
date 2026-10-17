package com.nishiyu.enderframelib.core.layout;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ScrollState {
    private final Map<String, int[]> scrollOffsets = new ConcurrentHashMap<>();

    public int getScrollX(String nodeId) {
        return scrollOffsets.containsKey(nodeId) ? scrollOffsets.get(nodeId)[0] : 0;
    }
    public int getScrollY(String nodeId) {
        return scrollOffsets.containsKey(nodeId) ? scrollOffsets.get(nodeId)[1] : 0;
    }
    public void setScroll(String nodeId, int x, int y) {
        scrollOffsets.put(nodeId, new int[]{x, y});
    }
}