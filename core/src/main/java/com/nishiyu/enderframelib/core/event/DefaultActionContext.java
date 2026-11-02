package com.nishiyu.enderframelib.core.event;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

/** {@link StandardDefaultActions} が既定動作の結果をホスト（Minecraft の画面・単体ビューア）へ伝える口。 */
public interface DefaultActionContext {
    /** ノードの属性が変更されたときの通知。 */
    void onAttributeChanged(HtmlNode node, String attrName, String attrValue);

    /** 別要素への合成クリックの再帰ディスパッチ。 */
    void dispatchClickTo(HtmlNode target);

    /** a[href] などのナビゲーション実行。 */
    void navigateTo(String href);

    /** フォームのアクション実行 (onsubmit, onreset 等)。 */
    void executeFormAction(HtmlNode form, String eventType);

    /** フォーカス移動。 */
    default void setFocus(HtmlNode node) {}

    /** ホイールスクロール。 */
    default void scroll(double deltaX, double deltaY) {}
}
