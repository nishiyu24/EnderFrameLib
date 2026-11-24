package com.nishiyu.enderframelib.core.box;

import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;

/**
 * 箱の種類（Engine v3 M2a）。整形エンジンの振り分けに要る粒度だけを持つ。
 *
 * <p>M2a の縦割りスパイクの範囲（Block・Text・Flex）に合わせ、表・グリッド・{@code inline-block} の原子化などは
 * まだ区別しない（{@link #BLOCK} / {@link #INLINE} に寄せる。M2b で種類を足す）。</p>
 */
public enum BoxKind {
    /** 箱を作らない（{@code display: none}・コメント）。子は辿らない。 */
    NONE,
    /** 文字の節。{@link BoxNode#text} に正規化済みの文字列を持つ。 */
    TEXT,
    /** ブロック水準の箱（{@code block}・{@code list-item}・{@code flow-root} ほか未対応の表示型）。 */
    BLOCK,
    /** インライン水準の箱（{@code inline}・{@code inline-block}）。中身は行の中に平らに並べる。 */
    INLINE,
    /** フレックスコンテナ（{@code flex}・{@code inline-flex}）。 */
    FLEX;

    /** 節と算出スタイルから種類を決める。 */
    public static BoxKind of(HtmlNode node, Style style) {
        if (node.nodeType == NodeType.TEXT) return TEXT;
        if (node.nodeType == NodeType.ROOT || node.nodeType == NodeType.DOCUMENT_FRAGMENT) return BLOCK;
        if (node.nodeType != NodeType.ELEMENT) return NONE;
        String d = style.box.display;
        if (d == null) return INLINE; // display の初期値は inline
        return switch (d.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "none" -> NONE;
            case "flex", "inline-flex" -> FLEX;
            case "inline", "inline-block" -> INLINE;
            default -> BLOCK;
        };
    }

    /** ブロックの流れに並ぶ箱か（{@link #BLOCK} / {@link #FLEX}）。 */
    public boolean blockLevel() {
        return this == BLOCK || this == FLEX;
    }
}
