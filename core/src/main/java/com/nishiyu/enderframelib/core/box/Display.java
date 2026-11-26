package com.nishiyu.enderframelib.core.box;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.model.Position;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;

import java.util.Locale;

/**
 * {@code display} の値を outer（周りに対してどう振る舞うか）と inner（子をどう整形するか）に分けたもの（CSS Display 3）。
 * {@code list-item} は別のフラグで持つ。描画用の木・描画の重なりの規則・問い合わせが読む（M2c-3 で旧 {@code Display} から移した）。
 * レイアウトの側の種別は {@link BoxKind}。
 */
public record Display(Outer outer, Inner inner, boolean listItem) {

    public enum Outer { BLOCK, INLINE, NONE, CONTENTS }

    public enum Inner {
        FLOW, FLOW_ROOT, FLEX, GRID, TABLE,
        TABLE_ROW_GROUP, TABLE_ROW, TABLE_CELL, TABLE_CAPTION, TABLE_COLUMN, NONE
    }

    public static final Display BLOCK = new Display(Outer.BLOCK, Inner.FLOW, false);
    public static final Display INLINE = new Display(Outer.INLINE, Inner.FLOW, false);
    public static final Display HIDDEN = new Display(Outer.NONE, Inner.NONE, false);
    public static final Display CONTENTS = new Display(Outer.CONTENTS, Inner.NONE, false);

    /** 箱の display。テキストは常にインライン、宣言が無い・解釈できない値はタグから推測する。 */
    public static Display of(HtmlNode node, ComputedStyle style) {
        if (node.nodeType == NodeType.TEXT) return INLINE;
        Display parsed = parse(style.typed().box.display);
        if (parsed != null) return parsed;
        return com.nishiyu.enderframelib.core.dom.node.ElementTraits.isDefaultInline(node.tag)
                || com.nishiyu.enderframelib.core.replaced.ReplacedElements.isHost(node.tag) ? INLINE : BLOCK;
    }

    /** 1 キーワードの旧構文と、{@code block flow-root} / {@code inline flex} などの複数キーワード構文を読む（解釈できなければ null）。 */
    public static Display parse(String value) {
        if (value == null) return null;
        String v = value.trim().toLowerCase(Locale.ROOT);
        Display single = switch (v) {
            case "none" -> HIDDEN;
            case "contents" -> CONTENTS;
            case "block" -> BLOCK;
            case "inline" -> INLINE;
            case "flow-root" -> new Display(Outer.BLOCK, Inner.FLOW_ROOT, false);
            case "inline-block" -> new Display(Outer.INLINE, Inner.FLOW_ROOT, false);
            case "flex" -> new Display(Outer.BLOCK, Inner.FLEX, false);
            case "inline-flex" -> new Display(Outer.INLINE, Inner.FLEX, false);
            case "grid" -> new Display(Outer.BLOCK, Inner.GRID, false);
            case "inline-grid" -> new Display(Outer.INLINE, Inner.GRID, false);
            case "table" -> new Display(Outer.BLOCK, Inner.TABLE, false);
            case "inline-table" -> new Display(Outer.INLINE, Inner.TABLE, false);
            case "list-item" -> new Display(Outer.BLOCK, Inner.FLOW, true);
            case "table-row-group", "table-header-group", "table-footer-group" -> new Display(Outer.BLOCK, Inner.TABLE_ROW_GROUP, false);
            case "table-row" -> new Display(Outer.BLOCK, Inner.TABLE_ROW, false);
            case "table-cell" -> new Display(Outer.BLOCK, Inner.TABLE_CELL, false);
            case "table-caption" -> new Display(Outer.BLOCK, Inner.TABLE_CAPTION, false);
            case "table-column", "table-column-group" -> new Display(Outer.BLOCK, Inner.TABLE_COLUMN, false);
            default -> null;
        };
        if (single != null) return single;

        Outer outer = null;
        Inner inner = null;
        boolean listItem = false;
        for (String token : v.split("\\s+")) {
            switch (token) {
                case "block" -> outer = Outer.BLOCK;
                case "inline" -> outer = Outer.INLINE;
                case "flow" -> inner = Inner.FLOW;
                case "flow-root" -> inner = Inner.FLOW_ROOT;
                case "flex" -> inner = Inner.FLEX;
                case "grid" -> inner = Inner.GRID;
                case "table" -> inner = Inner.TABLE;
                case "list-item" -> listItem = true;
                default -> {
                    return null;
                }
            }
        }
        // list-item は flow / flow-root とだけ組める（CSS Display 3）
        if (listItem && inner != null && inner != Inner.FLOW && inner != Inner.FLOW_ROOT) return null;
        return new Display(outer != null ? outer : Outer.BLOCK, inner != null ? inner : Inner.FLOW, listItem);
    }

    /**
     * float と絶対配置はブロックレベルになる（CSS 2.2 §9.7 / CSS Display 3 の blockification）。
     * {@code inline-block} は {@code block flow-root} に、{@code inline-flex} は {@code flex} になる。
     */
    public Display blockified(ComputedStyle style) {
        if (outer != Outer.INLINE) return this;
        Position position = style.typed().box.position;
        boolean outOfFlow = position == Position.ABSOLUTE || position == Position.FIXED || style.facts().floating;
        return outOfFlow ? new Display(Outer.BLOCK, inner, listItem) : this;
    }

    public boolean isNone() { return outer == Outer.NONE; }

    public boolean isContents() { return outer == Outer.CONTENTS; }

    public boolean isInlineLevel() { return outer == Outer.INLINE; }

    /** ブロックとインラインの両方を子に持てる箱（ブロックコンテナ）。匿名ブロックはこの中にだけ作る。 */
    public boolean isBlockContainer() {
        // inline flow はインラインボックスで、ブロックコンテナではない
        return (inner == Inner.FLOW && outer == Outer.BLOCK) || inner == Inner.FLOW_ROOT
                || inner == Inner.TABLE_CELL || inner == Inner.TABLE_CAPTION;
    }

    /** 表の内部の箱（行グループ・行・セル・キャプション・列）。 */
    public boolean isTableInternal() {
        return inner == Inner.TABLE_ROW_GROUP || inner == Inner.TABLE_ROW || inner == Inner.TABLE_CELL
                || inner == Inner.TABLE_CAPTION || inner == Inner.TABLE_COLUMN;
    }
}
