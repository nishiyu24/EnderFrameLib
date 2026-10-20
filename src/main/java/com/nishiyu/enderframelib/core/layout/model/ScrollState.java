package com.nishiyu.enderframelib.core.layout.model;

import com.nishiyu.enderframelib.core.layout.*;
import com.nishiyu.enderframelib.core.layout.model.*;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * スクロール位置。節そのものをキーにするので、同じ id を持つ別の節とは混ざらない。
 * 節が文書から外れたら位置も一緒に回収されるよう、キーは弱参照で持つ。
 */
public class ScrollState {
    private final Map<HtmlNode, int[]> offsetsByNode = Collections.synchronizedMap(new WeakHashMap<>());

    public int getScrollX(HtmlNode node) {
        int[] offset = node != null ? offsetsByNode.get(node) : null;
        return offset != null ? offset[0] : 0;
    }

    public int getScrollY(HtmlNode node) {
        int[] offset = node != null ? offsetsByNode.get(node) : null;
        return offset != null ? offset[1] : 0;
    }

    public void setScroll(HtmlNode node, int x, int y) {
        if (node == null) return;
        offsetsByNode.put(node, new int[]{x, y});
    }

    public void clear() {
        offsetsByNode.clear();
    }
}
