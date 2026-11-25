package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.api.dom.DOMRect;
import com.nishiyu.enderframelib.core.css.AtRuleProcessor;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.dom.node.LayoutQuery;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.PaintBox;
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
    private PaintBox root;
    private StyleSheet sheet;
    private int viewportW;
    private int viewportH;
    private Map<HtmlNode, PaintBox> index;

    /**
     * @param onScrollChanged スクロール位置を変えたときに呼ぶ（位置の決め直しと描き直しを求める）
     */
    public BoxLayoutQuery(ScrollState scrollState, Runnable onScrollChanged) {
        this.scrollState = scrollState;
        this.onScrollChanged = onScrollChanged;
    }

    /** 箱を読む前に呼ぶ。位置の決め直しを後回しにしているとき、ここで済ませる。 */
    private Runnable freshener = () -> { };

    public void freshener(Runnable freshener) {
        this.freshener = freshener;
    }

    /**
     * 直近のレイアウトの結果に差し替える。{@code root} は旧経路の箱の木の根か、新経路の描画用の木の根の見え方（M2c ③。
     * {@code PaintView}。スクロールの位置を含めて作ったもの）。
     */
    public void update(PaintBox root, StyleSheet sheet, int viewportW, int viewportH) {
        this.root = root;
        this.sheet = sheet;
        this.viewportW = viewportW;
        this.viewportH = viewportH;
        this.index = null;
    }

    private PaintBox boxOf(HtmlNode node) {
        freshener.run();
        if (root == null || node == null) return null;
        if (index == null) {
            Map<HtmlNode, PaintBox> built = new IdentityHashMap<>();
            collect(root, built);
            index = built;
        }
        return index.get(node);
    }

    private static void collect(PaintBox box, Map<HtmlNode, PaintBox> out) {
        if (box.node != null && !box.node.isPseudoNode) out.putIfAbsent(box.node, box);
        for (PaintBox child : box.paintChildren()) collect(child, out);
    }

    private static boolean positioned(PaintBox box) {
        com.nishiyu.enderframelib.core.css.model.Position p = box.style() != null ? box.style().typed().box.position : null;
        return p != null && p != com.nishiyu.enderframelib.core.css.model.Position.STATIC;
    }

    /** 旧経路の仮想化した流れの、まだ置いていない子の推定位置（それ以外は null）。M2b-6 で旧経路と一緒に消す。 */
    private static float[] virtualOffset(PaintBox box) {
        return box instanceof LayoutBox lb && lb.parent != null && lb.parent.isVirtualizedFlow() && lb.contentAutoSkipped
                ? lb.parent.virtualOffsetOf(lb) : null;
    }

    @Nullable
    @Override
    public BoxMetrics metrics(HtmlNode node) {
        PaintBox box = boxOf(node);
        if (box == null || box.displayNone()) return null;
        HtmlNode offsetParent = null;
        for (HtmlNode a = node.parent; a != null; a = a.parent) {
            PaintBox ab = boxOf(a);
            if (ab != null && positioned(ab)) {
                offsetParent = a;
                break;
            }
        }
        float x = box.x();
        float y = box.y();
        float[] est = virtualOffset(box);
        if (est != null) {
            x = est[0];
            y = est[1];
        }
        return new BoxMetrics(x, y, box.w(), box.h(),
                box.bL(), box.bT(), box.bR(), box.bB(),
                box.pL(), box.pT(), box.pR(), box.pB(),
                box.mL(), box.mT(), box.mR(), box.mB(),
                box.scrollWidth(), box.scrollHeight(),
                box.style() != null && box.style().facts().borderBox, box.isScrollContainer(), clips(box), box.isCollapsedTable(), offsetParent);
    }

    private static boolean clips(PaintBox box) {
        return box.style() != null && (box.style().overflowX().clips() || box.style().overflowY().clips());
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
        PaintBox target = boxOf(node);
        if (target == null) return;
        float ex = target.x();
        float ey = target.y();
        float[] est = virtualOffset(target);
        if (est != null) {
            ex = est[0];
            ey = est[1];
        }
        float ew = target.w();
        boolean changed = false;
        for (HtmlNode a = node.parent; a != null; a = a.parent) {
            PaintBox ab = boxOf(a);
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
