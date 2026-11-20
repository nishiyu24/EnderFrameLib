package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.api.dom.CSSStyleDeclaration;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.model.StateLookup;
import com.nishiyu.enderframelib.core.dom.node.DocumentState;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.LayoutQuery;

/**
 * {@link DocumentState} のうち、疑似クラス状態（{@link StateLookup}）と算出スタイルから導ける部分の既定の実装。
 * {@link InteractionState} が実装する（ノード側の口 {@code DocumentState} は算出スタイルの型を知らない）。
 */
public interface StyledDocumentState extends DocumentState, StateLookup {
    /** ノードの算出スタイル（未算出なら null）。 */
    ComputedStyle computedStyleOf(HtmlNode node);

    @Override
    default boolean isModal(HtmlNode node) {
        return stateOf(node).modal();
    }

    @Override
    default boolean isPopoverOpen(HtmlNode node) {
        return stateOf(node).popoverOpen();
    }

    @Override
    default CSSStyleDeclaration computedStyle(HtmlNode node, LayoutQuery.BoxMetrics box) {
        return new ComputedStyleDeclaration(node != null ? computedStyleOf(node) : null, box);
    }
}
