package com.nishiyu.enderframelib.core.paint.scene;

import com.nishiyu.enderframelib.core.box.Display;
import com.nishiyu.enderframelib.core.box.DomValues;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

/**
 * {@link DisplayScene} 上で {@link DynamicSceneState}（スクロール・アンカー追従・sticky）を適用した
 * 1 要素の解決済み位置と節への参照。親参照や子リストを持たない純粋な値（M3b-3）。
 */
public record SceneLocation(
        SceneLayer layer,
        float x,
        float y,
        float originX,
        float originY,
        float stickyDy
) {
    public HtmlNode node() { return layer.node; }
    public int nodeId() { return layer.nodeId; }
    public boolean anonymous() { return layer.anonymous(); }
    public float w() { return layer.w; }
    public float h() { return layer.h; }
    public float bT() { return layer.bT; }
    public float bR() { return layer.bR; }
    public float bB() { return layer.bB; }
    public float bL() { return layer.bL; }
    public float pT() { return layer.pT; }
    public float pR() { return layer.pR; }
    public float pB() { return layer.pB; }
    public float pL() { return layer.pL; }
    public float mT() { return layer.mT; }
    public float mR() { return layer.mR; }
    public float mB() { return layer.mB; }
    public float mL() { return layer.mL; }
    public ComputedStyle style() { return layer.style; }
    public Display display() { return layer.display; }
    public float scrollWidth() { return layer.scrollWidth; }
    public float scrollHeight() { return layer.scrollHeight; }
    public float lineHeightPx() { return layer.lineHeight; }
    public boolean isScrollContainer() { return layer.isScrollContainer(); }
    public boolean isCollapsedTable() { return layer.isCollapsedTable(); }
    public boolean displayNone() { return layer.displayNone(); }
    public SceneRun[] runs() { return layer.runs; }
    public DomValues values() { return layer.values(); }
}
