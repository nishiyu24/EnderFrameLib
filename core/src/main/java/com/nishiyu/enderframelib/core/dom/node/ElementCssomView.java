package com.nishiyu.enderframelib.core.dom.node;

import com.nishiyu.enderframelib.api.dom.DOMRect;
import com.nishiyu.enderframelib.api.dom.Element;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * {@link Element} のうち CSSOM View（§6〜7: 箱の位置・大きさ・スクロール）の部分。値はつながっている文書のレイアウトの問い合わせ
 * （{@link LayoutQuery}）から読み、文書に繋がっていない・まだ並べていない要素は 0 か空を返す。M5-3a で {@link HtmlElement} から出した
 * （{@code HtmlElement} だけが継ぐ）。
 */
abstract class ElementCssomView extends HtmlNode implements Element {

    ElementCssomView(String tag) {
        super(NodeType.ELEMENT, tag);
    }


    @Nullable
    private LayoutQuery layoutQuery() {
        return getOwnerDocument() instanceof HtmlDocument doc ? doc.layoutQuery() : null;
    }

    @Nullable
    private LayoutQuery.BoxMetrics boxMetrics() {
        LayoutQuery query = layoutQuery();
        return query != null ? query.metrics(this) : null;
    }

    @Override
    public DOMRect getBoundingClientRect() {
        LayoutQuery.BoxMetrics m = boxMetrics();
        return m == null ? DOMRect.EMPTY : new DOMRect(m.x(), m.y(), m.w(), m.h());
    }

    @Override
    public List<DOMRect> getClientRects() {
        LayoutQuery.BoxMetrics m = boxMetrics();
        return m == null ? List.of() : List.of(new DOMRect(m.x(), m.y(), m.w(), m.h()));
    }

    @Override
    public int getOffsetWidth() {
        LayoutQuery.BoxMetrics m = boxMetrics();
        return m == null ? 0 : Math.round(m.w());
    }

    @Override
    public int getOffsetHeight() {
        LayoutQuery.BoxMetrics m = boxMetrics();
        return m == null ? 0 : Math.round(m.h());
    }

    @Override
    public int getOffsetLeft() {
        return offsetFromParent(true);
    }

    @Override
    public int getOffsetTop() {
        return offsetFromParent(false);
    }

    private int offsetFromParent(boolean horizontal) {
        LayoutQuery query = layoutQuery();
        LayoutQuery.BoxMetrics m = query != null ? query.metrics(this) : null;
        if (m == null) return 0;
        LayoutQuery.BoxMetrics parent = m.offsetParent() != null ? query.metrics(m.offsetParent()) : null;
        if (parent == null) return Math.round(horizontal ? m.x() : m.y());
        // offsetParent の padding edge（border の内側）から測る
        return Math.round(horizontal ? m.x() - parent.x() - parent.bL() : m.y() - parent.y() - parent.bT());
    }

    @Nullable
    @Override
    public Element getOffsetParent() {
        LayoutQuery.BoxMetrics m = boxMetrics();
        return m != null && m.offsetParent() instanceof Element e ? e : null;
    }

    @Override
    public int getClientWidth() {
        LayoutQuery.BoxMetrics m = boxMetrics();
        return m == null ? 0 : Math.round(m.clientWidth());
    }

    @Override
    public int getClientHeight() {
        LayoutQuery.BoxMetrics m = boxMetrics();
        return m == null ? 0 : Math.round(m.clientHeight());
    }

    @Override
    public int getClientLeft() {
        LayoutQuery.BoxMetrics m = boxMetrics();
        return m == null ? 0 : Math.round(m.bL());
    }

    @Override
    public int getClientTop() {
        LayoutQuery.BoxMetrics m = boxMetrics();
        return m == null ? 0 : Math.round(m.bT());
    }

    @Override
    public int getScrollWidth() {
        LayoutQuery.BoxMetrics m = boxMetrics();
        return m == null ? 0 : Math.round(m.fullScrollWidth());
    }

    @Override
    public int getScrollHeight() {
        LayoutQuery.BoxMetrics m = boxMetrics();
        return m == null ? 0 : Math.round(m.fullScrollHeight());
    }

    @Override
    public float getScrollLeft() {
        LayoutQuery query = layoutQuery();
        return query == null ? 0 : query.scrollLeft(this);
    }

    @Override
    public float getScrollTop() {
        LayoutQuery query = layoutQuery();
        return query == null ? 0 : query.scrollTop(this);
    }

    @Override
    public void setScrollLeft(float x) {
        LayoutQuery query = layoutQuery();
        if (query != null) query.scrollTo(this, x, query.scrollTop(this));
    }

    @Override
    public void setScrollTop(float y) {
        LayoutQuery query = layoutQuery();
        if (query != null) query.scrollTo(this, query.scrollLeft(this), y);
    }

    @Override
    public void scrollTo(float x, float y) {
        LayoutQuery query = layoutQuery();
        if (query != null) query.scrollTo(this, x, y);
    }

    @Override
    public void scrollIntoView() {
        LayoutQuery query = layoutQuery();
        if (query != null) query.scrollIntoView(this);
    }

    @Nullable
    @Override
    public DOMRect getContentRect() {
        LayoutQuery.BoxMetrics m = boxMetrics();
        if (m == null) return null;
        float w = Math.max(0, m.w() - m.bL() - m.bR() - m.pL() - m.pR());
        float h = Math.max(0, m.h() - m.bT() - m.bB() - m.pT() - m.pB());
        return new DOMRect(m.pL(), m.pT(), w, h);
    }

    @Override
    public DOMRect getVisibleClientRect(@Nullable Element root) {
        LayoutQuery query = layoutQuery();
        if (query == null) return DOMRect.EMPTY;
        return query.visibleRect(this, root instanceof HtmlNode node ? node : null);
    }
}
