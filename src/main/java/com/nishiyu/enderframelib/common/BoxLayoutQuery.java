package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.api.dom.DOMRect;
import com.nishiyu.enderframelib.core.css.AtRuleProcessor;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.dom.LayoutQuery;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 直近のレイアウト結果（{@link LayoutBox} の木）から {@link LayoutQuery} を答える。レイアウトのたびに
 * {@link #update} で木を差し替える。節 → 箱の索引は最初の問い合わせで作り、更新で捨てる。
 */
public final class BoxLayoutQuery implements LayoutQuery {

    private final ScrollState scrollState;
    private final Runnable onScrollChanged;
    private LayoutBox root;
    private StyleSheet sheet;
    private int viewportW;
    private int viewportH;
    private Map<HtmlNode, LayoutBox> index;

    /**
     * @param onScrollChanged スクロール位置を変えたときに呼ぶ（位置の決め直しと描き直しを求める）
     */
    public BoxLayoutQuery(ScrollState scrollState, Runnable onScrollChanged) {
        this.scrollState = scrollState;
        this.onScrollChanged = onScrollChanged;
    }

    public void update(LayoutBox root, StyleSheet sheet, int viewportW, int viewportH) {
        this.root = root;
        this.sheet = sheet;
        this.viewportW = viewportW;
        this.viewportH = viewportH;
        this.index = null;
    }

    private LayoutBox boxOf(HtmlNode node) {
        if (root == null || node == null) return null;
        if (index == null) {
            Map<HtmlNode, LayoutBox> built = new IdentityHashMap<>();
            collect(root, built);
            index = built;
        }
        return index.get(node);
    }

    private static void collect(LayoutBox box, Map<HtmlNode, LayoutBox> out) {
        if (box.node != null && !box.node.isPseudoNode) out.putIfAbsent(box.node, box);
        for (LayoutBox child : box.children) collect(child, out);
    }

    private static boolean positioned(LayoutBox box) {
        return box.isAbsolute || box.isFixed || box.isRelative || box.isSticky;
    }

    @Nullable
    @Override
    public BoxMetrics metrics(HtmlNode node) {
        LayoutBox box = boxOf(node);
        if (box == null || (box.display != null && box.display.isNone())) return null;
        HtmlNode offsetParent = null;
        for (HtmlNode a = node.parent; a != null; a = a.parent) {
            LayoutBox ab = boxOf(a);
            if (ab != null && positioned(ab)) {
                offsetParent = a;
                break;
            }
        }
        return new BoxMetrics(box.x, box.y, box.w, box.h,
                box.bL, box.bT, box.bR, box.bB,
                box.pL, box.pT, box.pR, box.pB,
                box.mL, box.mT, box.mR, box.mB,
                box.scrollWidth, box.scrollHeight,
                box.style != null && box.style.isBorderBox(), box.isScrollContainer(), clips(box), offsetParent);
    }

    private static boolean clips(LayoutBox box) {
        return box.style != null && (box.style.overflowX().clips() || box.style.overflowY().clips());
    }

    @Override
    public float scrollLeft(HtmlNode node) {
        return scrollState.getScrollX(node);
    }

    @Override
    public float scrollTop(HtmlNode node) {
        return scrollState.getScrollY(node);
    }

    @Override
    public void scrollTo(HtmlNode node, float x, float y) {
        BoxMetrics m = metrics(node);
        if (m == null || !m.scrolls()) return;
        float nx = Math.clamp(Float.isNaN(x) ? 0 : x, 0, m.maxScrollX());
        float ny = Math.clamp(Float.isNaN(y) ? 0 : y, 0, m.maxScrollY());
        if (nx == scrollState.getScrollX(node) && ny == scrollState.getScrollY(node)) return;
        scrollState.setScroll(node, nx, ny);
        onScrollChanged.run();
    }

    @Override
    public void scrollIntoView(HtmlNode node) {
        LayoutBox target = boxOf(node);
        if (target == null) return;
        float ex = target.x;
        float ey = target.y;
        float ew = target.w;
        boolean changed = false;
        for (HtmlNode a = node.parent; a != null; a = a.parent) {
            LayoutBox ab = boxOf(a);
            BoxMetrics m = ab != null ? metrics(a) : null;
            if (m == null || !m.scrolls()) continue;
            float curX = scrollState.getScrollX(a);
            float curY = scrollState.getScrollY(a);
            float top = m.y() + m.bT();
            float left = m.x() + m.bL();
            float right = left + m.clientWidth();
            float nextY = Math.clamp(curY + (ey - top), 0, m.maxScrollY());
            float dx = 0;
            if (ex < left) dx = ex - left;
            else if (ex + ew > right) dx = Math.min(ex + ew - right, ex - left);
            float nextX = Math.clamp(curX + dx, 0, m.maxScrollX());
            if (nextX != curX || nextY != curY) {
                scrollState.setScroll(a, nextX, nextY);
                changed = true;
            }
            ex -= nextX - curX;
            ey -= nextY - curY;
        }
        if (changed) onScrollChanged.run();
    }

    @Override
    public DOMRect visibleRect(HtmlNode node, @Nullable HtmlNode scrollRoot) {
        BoxMetrics m = metrics(node);
        if (m == null) return DOMRect.EMPTY;
        DOMRect rect = new DOMRect(m.x(), m.y(), m.w(), m.h());
        boolean reachedRoot = scrollRoot == null;
        for (HtmlNode a = node.parent; a != null && !rect.isEmpty(); a = a.parent) {
            BoxMetrics am = metrics(a);
            if (am == null) continue;
            if (a == scrollRoot) {
                rect = rect.intersect(new DOMRect(am.x() + am.bL(), am.y() + am.bT(), am.clientWidth(), am.clientHeight()));
                reachedRoot = true;
                break;
            }
            if (am.clips()) {
                rect = rect.intersect(new DOMRect(am.x() + am.bL(), am.y() + am.bT(), am.clientWidth(), am.clientHeight()));
            }
        }
        if (!reachedRoot) return DOMRect.EMPTY;
        if (scrollRoot == null) rect = rect.intersect(new DOMRect(0, 0, viewportW, viewportH));
        return rect;
    }

    @Override
    public boolean matchMedia(String query) {
        return sheet != null && AtRuleProcessor.evaluateMediaQuery(query, sheet.mediaEnvironment());
    }

    @Override
    public int viewportWidth() {
        return viewportW;
    }

    @Override
    public int viewportHeight() {
        return viewportH;
    }
}
