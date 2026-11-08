package com.nishiyu.enderframelib.core.dom.node;

import java.util.Map;

/**
 * {@code style} 属性の宣言に、属性の文字列を経由せず直接書かれた値を重ねる口。
 * バインディングの数値（{@code width: {{hp}}%} など）は属性を書き換えず、アリーナと同じ添字の列に直接書かれ、
 * スタイル解決がインラインの宣言を集めた直後にここから重ねる。
 */
public interface InlineStyleOverlay {
    /** {@code inline}（style 属性の通常の宣言）に、{@code node} の直接書き込み値を足す。同名の宣言は置き換える。 */
    void overlayInline(HtmlNode node, Map<String, String> inline);
}
