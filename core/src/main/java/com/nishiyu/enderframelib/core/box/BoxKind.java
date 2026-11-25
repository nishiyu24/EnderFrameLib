package com.nishiyu.enderframelib.core.box;

import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;

/**
 * 箱の種類（Engine v3 M2a）。整形エンジンの振り分けに要る粒度だけを持つ。
 *
 * <p>表はまだ区別しない（{@link #BLOCK} に寄せる。M2b-4 で種類を足す）。</p>
 */
public enum BoxKind {
    /** 箱を作らない（{@code display: none}・コメント）。子は辿らない。 */
    NONE,
    /** 文字の節。{@link BoxNode#text} に正規化済みの文字列を持つ。 */
    TEXT,
    /** ブロック水準の箱（{@code block}・{@code list-item}・{@code flow-root} ほか未対応の表示型）。 */
    BLOCK,
    /** インライン水準の箱（{@code inline}）。中身は行の中に平らに並べる。 */
    INLINE,
    /** フレックスコンテナ（{@code flex}）。 */
    FLEX,
    /** インライン水準の原子で、中はブロックの流れ（{@code inline-block}）。行の中に 1 つの塊として置く。 */
    INLINE_BLOCK,
    /** インライン水準の原子で、中は flex（{@code inline-flex}）。 */
    INLINE_FLEX,
    /** グリッドコンテナ（{@code grid}。M2b-3）。 */
    GRID,
    /** インライン水準の原子で、中はグリッド（{@code inline-grid}）。 */
    INLINE_GRID,
    /**
     * 箱を作らず、子（と疑似要素）を親の子として並べる（{@code display: contents}。M2b-1）。子のスタイルの継承元ではある。
     * レイアウトは {@link BoxChildren#toList()} で中身へ展開して読む。
     */
    CONTENTS;

    /** 節と算出スタイルから種類を決める。 */
    public static BoxKind of(HtmlNode node, Style style) {
        if (node.nodeType == NodeType.TEXT) return TEXT;
        if (node.nodeType == NodeType.ROOT || node.nodeType == NodeType.DOCUMENT_FRAGMENT) return BLOCK;
        if (node.nodeType != NodeType.ELEMENT) return NONE;
        String d = style.box.display;
        BoxKind k = d == null ? INLINE : switch (d.trim().toLowerCase(java.util.Locale.ROOT)) { // display の初期値は inline
            case "none" -> NONE;
            case "flex" -> FLEX;
            case "inline-flex" -> INLINE_FLEX;
            case "inline-block" -> INLINE_BLOCK;
            case "grid" -> GRID;
            case "inline-grid" -> INLINE_GRID;
            case "inline" -> INLINE;
            case "contents" -> CONTENTS;
            default -> BLOCK;
        };
        // 置換要素と部品（img・input・button ほか）はインライン水準なら原子（行の中に 1 つの塊として置く）
        if (k == INLINE && (com.nishiyu.enderframelib.core.replaced.ReplacedElements.isReplacedTag(node.tag)
                || com.nishiyu.enderframelib.core.dom.node.ElementTraits.isEmbeddedMedia(node.tag))) k = INLINE_BLOCK;
        return k == NONE ? NONE : blockified(k, style);
    }

    /** float・位置指定（absolute / fixed）の箱はブロック水準になる（CSS Display §2.7 blockification）。 */
    private static BoxKind blockified(BoxKind k, Style style) {
        var p = style.box.position;
        boolean out = style.facts.floating
                || p == com.nishiyu.enderframelib.core.css.model.Position.ABSOLUTE || p == com.nishiyu.enderframelib.core.css.model.Position.FIXED;
        if (!out) return k;
        return switch (k) {
            case INLINE, INLINE_BLOCK -> BLOCK;
            case INLINE_FLEX -> FLEX;
            case INLINE_GRID -> GRID;
            default -> k;
        };
    }

    /** ブロックの流れに並ぶ箱か（{@link #BLOCK} / {@link #FLEX} / {@link #GRID}）。 */
    public boolean blockLevel() {
        return this == BLOCK || this == FLEX || this == GRID;
    }

    /** 行の中に 1 つの塊として置くインライン水準の原子か（{@link #INLINE_BLOCK} / {@link #INLINE_FLEX} / {@link #INLINE_GRID}）。 */
    public boolean atomicInline() {
        return this == INLINE_BLOCK || this == INLINE_FLEX || this == INLINE_GRID;
    }

    /** 中身が flex か（{@link #FLEX} / {@link #INLINE_FLEX}）。 */
    public boolean flexContainer() {
        return this == FLEX || this == INLINE_FLEX;
    }

    /** 中身がグリッドか（{@link #GRID} / {@link #INLINE_GRID}）。 */
    public boolean gridContainer() {
        return this == GRID || this == INLINE_GRID;
    }
}
