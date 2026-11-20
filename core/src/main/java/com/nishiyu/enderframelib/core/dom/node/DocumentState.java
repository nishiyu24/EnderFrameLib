package com.nishiyu.enderframelib.core.dom.node;

import com.nishiyu.enderframelib.api.dom.CSSStyleDeclaration;

/**
 * 文書が持つ対話状態（フォーカス・不活性・Top Layer・無効化の通知）への、ノード側の狭い口。実装は上の層の
 * {@code core.dom.InteractionState}（算出スタイルを持つ）。ノードの型が算出スタイルやセレクタの型を知らずに済むよう（S5・S8:
 * {@code dom.node} を最下層にする）、{@link HtmlDocument} と {@link HtmlElement} が実際に呼ぶものだけを並べる。
 */
public interface DocumentState {
    /** フォーカスのあるノード（無ければ null）。 */
    HtmlNode focusedNode();

    /** フォーカスを移す。移れたら true。 */
    boolean setFocusedNode(HtmlNode target, boolean focusVisible);

    /** 不活性（{@code inert} の配下、またはモーダルの外）か。 */
    boolean isInert(HtmlNode node);

    /** 不活性になったノードのポインタ状態（hover・active）を捨てる。 */
    void clearInertPointerStates();

    /** モーダル（Top Layer）を切り替える。変わったら true。 */
    boolean setModal(HtmlNode node, boolean modal);

    /** ポップオーバーの表示を切り替える。変わったら true。 */
    boolean setPopoverOpen(HtmlNode node, boolean open);

    /** {@code :modal}。 */
    boolean isModal(HtmlNode node);

    /** {@code :popover-open}。 */
    boolean isPopoverOpen(HtmlNode node);

    /** 木の構造が変わった。 */
    void notifyDomStructureChanged();

    /** ノードを汚れたとして印を付ける。 */
    void markDirty(HtmlNode node);

    /** 属性が変わった（再スタイルの無効化）。 */
    void invalidateAttribute(HtmlNode node, String name, String oldValue, String newValue);

    /** 子の並びが変わった（再スタイルの無効化）。 */
    void invalidateChildList(HtmlNode parent, int changedAt);

    /** {@code getComputedStyle} の結果（レイアウトの寸法 {@code box} は無くてよい）。 */
    CSSStyleDeclaration computedStyle(HtmlNode node, LayoutQuery.BoxMetrics box);
}
