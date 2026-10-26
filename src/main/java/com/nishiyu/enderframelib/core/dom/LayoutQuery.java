package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.api.dom.DOMRect;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import org.jetbrains.annotations.Nullable;

/**
 * 文書のレイアウト結果を DOM（CSSOM View）から読むための窓口。レイアウトの型（{@code LayoutBox}）を
 * {@code core/dom} に持ち込まないよう、値だけの {@link BoxMetrics} で返す。実装はレイアウトを持つ側
 * （セッションなど）が文書に差し込む。差し込まれていない文書では CSSOM View は全て 0 を返す。
 */
public interface LayoutQuery {

    /**
     * 1 つの要素の箱の寸法。座標はビューポート左上が原点で、祖先のスクロール量を含む。
     *
     * @param offsetParent 最も近い位置指定の祖先（無ければ null）
     * @param scrolls      {@code overflow} でスクロールコンテナになっている
     * @param clips        中身をクリップする（{@code overflow} が visible 以外）
     * @param collapsedTable {@code border-collapse: collapse} の表。外周の枠線を client に数えず、スクロール範囲は箱の大きさで切らない
     */
    record BoxMetrics(float x, float y, float w, float h,
                      float bL, float bT, float bR, float bB,
                      float pL, float pT, float pR, float pB,
                      float mL, float mT, float mR, float mB,
                      float scrollWidth, float scrollHeight,
                      boolean borderBoxSizing, boolean scrolls, boolean clips, boolean collapsedTable,
                      @Nullable HtmlNode offsetParent) {

        public float clientWidth() {
            return collapsedTable ? w : Math.max(0, w - bL - bR);
        }

        public float clientHeight() {
            return collapsedTable ? h : Math.max(0, h - bT - bB);
        }

        /** padding box 基準の全体の幅（クライアント幅を下回らない）。 */
        public float fullScrollWidth() {
            if (collapsedTable) return scrollWidth > 0 ? scrollWidth : w;
            return Math.max(clientWidth(), (scrollWidth > 0 ? scrollWidth : w) - bL - bR);
        }

        public float fullScrollHeight() {
            if (collapsedTable) return scrollHeight > 0 ? scrollHeight : h;
            return Math.max(clientHeight(), (scrollHeight > 0 ? scrollHeight : h) - bT - bB);
        }

        public float maxScrollX() {
            return Math.max(0, fullScrollWidth() - clientWidth());
        }

        public float maxScrollY() {
            return Math.max(0, fullScrollHeight() - clientHeight());
        }
    }

    /** レイアウトされていない（{@code display: none}・未接続・まだ描かれていない）なら null。 */
    @Nullable
    BoxMetrics metrics(HtmlNode node);

    float scrollLeft(HtmlNode node);

    float scrollTop(HtmlNode node);

    /** スクロール位置を設定する（範囲に丸める）。位置の決め直しと描き直しを求める。 */
    void scrollTo(HtmlNode node, float x, float y);

    void scrollIntoView(HtmlNode node);

    /** {@code node} の矩形を、クリップする祖先と {@code root}（null ならビューポート）で切り取った矩形。 */
    DOMRect visibleRect(HtmlNode node, @Nullable HtmlNode root);

    /** メディアクエリを、この文書のメディア環境（ビューポート・{@code prefers-*}）で評価する。 */
    boolean matchMedia(String query);

    int viewportWidth();

    int viewportHeight();
}
