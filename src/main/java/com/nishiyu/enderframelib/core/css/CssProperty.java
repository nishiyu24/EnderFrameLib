package com.nishiyu.enderframelib.core.css;
import java.util.Locale;

/**
 * CSS プロパティ名の定数。
 *
 * 以前は同じプロパティ名の文字列リテラルがレイアウト・レンダラ・パーサに散っていて
 * （"display" 25 箇所、"height" 29 箇所…）、綴り間違いが黙って無視される no-op になっていた。
 * 算出スタイルへのアクセスはすべてこの定数を経由する。
 */
public final class CssProperty {

    public static final String ALIGN_CONTENT = "align-content";
    public static final String ALIGN_ITEMS = "align-items";
    public static final String ALIGN_SELF = "align-self";
    public static final String ANIMATION = "animation";
    public static final String ANIMATION_DELAY = "animation-delay";
    public static final String ANIMATION_DIRECTION = "animation-direction";
    public static final String ANIMATION_DURATION = "animation-duration";
    public static final String ANIMATION_FILL_MODE = "animation-fill-mode";
    public static final String ANIMATION_ITERATION_COUNT = "animation-iteration-count";
    public static final String ANIMATION_NAME = "animation-name";
    public static final String ANIMATION_TIMING_FUNCTION = "animation-timing-function";
    public static final String ASPECT_RATIO = "aspect-ratio";
    public static final String BACKGROUND = "background";
    public static final String BACKGROUND_COLOR = "background-color";
    public static final String BORDER = "border";
    public static final String BORDER_BOTTOM = "border-bottom";
    public static final String BORDER_BOTTOM_COLOR = "border-bottom-color";
    public static final String BORDER_BOTTOM_LEFT_RADIUS = "border-bottom-left-radius";
    public static final String BORDER_BOTTOM_RIGHT_RADIUS = "border-bottom-right-radius";
    public static final String BORDER_BOTTOM_WIDTH = "border-bottom-width";
    public static final String BORDER_COLLAPSE = "border-collapse";
    public static final String BORDER_COLOR = "border-color";
    public static final String BORDER_LEFT = "border-left";
    public static final String BORDER_LEFT_COLOR = "border-left-color";
    public static final String BORDER_LEFT_WIDTH = "border-left-width";
    public static final String BORDER_RADIUS = "border-radius";
    public static final String BORDER_RIGHT = "border-right";
    public static final String BORDER_RIGHT_COLOR = "border-right-color";
    public static final String BORDER_RIGHT_WIDTH = "border-right-width";
    public static final String BORDER_SPACING = "border-spacing";
    public static final String BORDER_TOP = "border-top";
    public static final String BORDER_TOP_COLOR = "border-top-color";
    public static final String BORDER_TOP_LEFT_RADIUS = "border-top-left-radius";
    public static final String BORDER_TOP_RIGHT_RADIUS = "border-top-right-radius";
    public static final String BORDER_TOP_WIDTH = "border-top-width";
    public static final String BORDER_WIDTH = "border-width";
    public static final String BOTTOM = "bottom";
    public static final String BOX_SHADOW = "box-shadow";
    public static final String BOX_SIZING = "box-sizing";
    public static final String CAPTION_SIDE = "caption-side";
    public static final String CLEAR = "clear";
    public static final String COLOR = "color";
    public static final String COLUMN_GAP = "column-gap";
    public static final String CONTAINER_NAME = "container-name";
    public static final String CONTAINER_TYPE = "container-type";
    public static final String CONTENT = "content";
    public static final String COUNTER_INCREMENT = "counter-increment";
    public static final String COUNTER_RESET = "counter-reset";
    public static final String COUNTER_SET = "counter-set";
    public static final String CURSOR = "cursor";
    public static final String DIRECTION = "direction";
    public static final String DISPLAY = "display";
    public static final String FLEX = "flex";
    public static final String FLEX_BASIS = "flex-basis";
    public static final String FLEX_DIRECTION = "flex-direction";
    public static final String FLEX_GROW = "flex-grow";
    public static final String FLEX_SHRINK = "flex-shrink";
    public static final String FLEX_WRAP = "flex-wrap";
    public static final String FLOAT = "float";
    public static final String FONT_FAMILY = "font-family";
    public static final String FONT_SIZE = "font-size";
    public static final String FONT_STYLE = "font-style";
    public static final String FONT_WEIGHT = "font-weight";
    public static final String GAP = "gap";
    public static final String GRID_AREA = "grid-area";
    public static final String GRID_AUTO_COLUMNS = "grid-auto-columns";
    public static final String GRID_AUTO_FLOW = "grid-auto-flow";
    public static final String GRID_AUTO_ROWS = "grid-auto-rows";
    public static final String GRID_COLUMN = "grid-column";
    public static final String GRID_COLUMN_END = "grid-column-end";
    public static final String GRID_COLUMN_GAP = "grid-column-gap";
    public static final String GRID_COLUMN_START = "grid-column-start";
    public static final String GRID_GAP = "grid-gap";
    public static final String GRID_ROW = "grid-row";
    public static final String GRID_ROW_END = "grid-row-end";
    public static final String GRID_ROW_GAP = "grid-row-gap";
    public static final String GRID_ROW_START = "grid-row-start";
    public static final String GRID_TEMPLATE_AREAS = "grid-template-areas";
    public static final String GRID_TEMPLATE_COLUMNS = "grid-template-columns";
    public static final String GRID_TEMPLATE_ROWS = "grid-template-rows";
    public static final String HEIGHT = "height";
    public static final String JUSTIFY_CONTENT = "justify-content";
    public static final String JUSTIFY_ITEMS = "justify-items";
    public static final String JUSTIFY_SELF = "justify-self";
    public static final String LEFT = "left";
    public static final String LETTER_SPACING = "letter-spacing";
    public static final String LINE_HEIGHT = "line-height";
    public static final String LIST_STYLE_POSITION = "list-style-position";
    public static final String LIST_STYLE_TYPE = "list-style-type";
    public static final String MARGIN = "margin";
    public static final String MARGIN_BOTTOM = "margin-bottom";
    public static final String MARGIN_LEFT = "margin-left";
    public static final String MARGIN_RIGHT = "margin-right";
    public static final String MARGIN_TOP = "margin-top";
    public static final String MAX_HEIGHT = "max-height";
    public static final String MAX_WIDTH = "max-width";
    public static final String MIN_HEIGHT = "min-height";
    public static final String MIN_WIDTH = "min-width";
    public static final String OBJECT_FIT = "object-fit";
    public static final String OPACITY = "opacity";
    public static final String ORDER = "order";
    public static final String OVERFLOW = "overflow";
    public static final String OVERFLOW_WRAP = "overflow-wrap";
    public static final String OVERFLOW_X = "overflow-x";
    public static final String OVERFLOW_Y = "overflow-y";
    public static final String PADDING = "padding";
    public static final String PADDING_BOTTOM = "padding-bottom";
    public static final String PADDING_LEFT = "padding-left";
    public static final String PADDING_RIGHT = "padding-right";
    public static final String PADDING_TOP = "padding-top";
    public static final String POINTER_EVENTS = "pointer-events";
    public static final String POSITION = "position";
    public static final String RIGHT = "right";
    public static final String ROW_GAP = "row-gap";
    public static final String SCROLLBAR_WIDTH = "scrollbar-width";
    public static final String SRC = "src";
    public static final String TABLE_LAYOUT = "table-layout";
    public static final String TEXT_ALIGN = "text-align";
    public static final String TEXT_DECORATION = "text-decoration";
    public static final String TEXT_INDENT = "text-indent";
    public static final String TEXT_OVERFLOW = "text-overflow";
    public static final String TEXT_SHADOW = "text-shadow";
    public static final String TEXT_TRANSFORM = "text-transform";
    public static final String TOP = "top";
    public static final String TRANSFORM = "transform";
    public static final String TRANSFORM_ORIGIN = "transform-origin";
    public static final String TRANSITION = "transition";
    public static final String TRANSITION_DELAY = "transition-delay";
    public static final String TRANSITION_DURATION = "transition-duration";
    public static final String TRANSITION_PROPERTY = "transition-property";
    public static final String TRANSITION_TIMING_FUNCTION = "transition-timing-function";
    public static final String VERTICAL_ALIGN = "vertical-align";
    public static final String VISIBILITY = "visibility";
    public static final String WHITE_SPACE = "white-space";
    public static final String WIDTH = "width";
    public static final String WORD_BREAK = "word-break";
    public static final String Z_INDEX = "z-index";

    private CssProperty() {}

    /**
     * キーワードだけを値に取るプロパティの値の型。
     *
     * 何が壊れていたか: 以前は読み手がそれぞれ {@code "border-box".equals(style.get(BOX_SIZING))} のように
     * 文字列で比べていて、綴りの間違いは黙って偽になり、初期値も読み手ごとに別々に書かれていた。
     * いまは {@link PropertyRegistry} がプロパティごとに値の型（enum）と初期値を 1 回だけ宣言し、
     * {@link ComputedStyle#keyword(String, Class)} が算出値を型で返す。知らない値は初期値になる。
     *
     * enum の定数名は CSS のキーワードを大文字にして {@code -} を {@code _} にしたもの（{@code flex-start} は
     * {@code FLEX_START}）。
     */
    public static final class CssKeywords {

        private CssKeywords() {}

        /** CSS のキーワード 1 つ。 */
        public interface Keyword {
            /** CSS の書き方（小文字、{@code -} 区切り）。 */
            default String cssName() {
                return ((Enum<?>) this).name().toLowerCase(Locale.ROOT).replace('_', '-');
            }
        }

        /** 値の文字列を {@code type} の定数にする。前後の空白と大文字小文字は無視する。知らない値は null。 */
        public static <E extends Enum<E> & Keyword> E parse(Class<E> type, String value) {
            if (value == null) return null;
            String text = value.trim().toLowerCase(Locale.ROOT);
            // 揃えの safe / unsafe は位置の扱いの違いで、レイアウトは区別しない（safe center は center）
            if (text.startsWith("safe ")) text = text.substring(5).trim();
            else if (text.startsWith("unsafe ")) text = text.substring(7).trim();
            String name = text.toUpperCase(Locale.ROOT).replaceAll("[-\\s]+", "_");
            for (E constant : type.getEnumConstants()) {
                if (constant.name().equals(name)) return constant;
            }
            return null;
        }

        public enum Position implements Keyword { STATIC, RELATIVE, ABSOLUTE, FIXED, STICKY }

        /** {@code float}。 */
        public enum FloatSide implements Keyword { NONE, LEFT, RIGHT, INLINE_START, INLINE_END }

        public enum Clear implements Keyword { NONE, LEFT, RIGHT, BOTH, INLINE_START, INLINE_END }

        public enum BoxSizing implements Keyword { CONTENT_BOX, BORDER_BOX }

        public enum Overflow implements Keyword {
            VISIBLE, HIDDEN, CLIP, SCROLL, AUTO;

            /** はみ出しを切る・スクロールさせる（スクロールコンテナまたはクリップ）か。 */
            public boolean clips() {
                return this != VISIBLE;
            }

            /** 利用者がスクロールできる（スクロールコンテナ）か。{@code hidden} はスクロールコンテナだがスクロールバーを持たない。 */
            public boolean scrolls() {
                return this == SCROLL || this == AUTO || this == HIDDEN;
            }
        }

        public enum Visibility implements Keyword { VISIBLE, HIDDEN, COLLAPSE }

        public enum TextAlign implements Keyword { START, END, LEFT, RIGHT, CENTER, JUSTIFY, MATCH_PARENT }

        public enum TextTransform implements Keyword { NONE, CAPITALIZE, UPPERCASE, LOWERCASE }

        public enum TextOverflow implements Keyword { CLIP, ELLIPSIS }

        public enum OverflowWrap implements Keyword { NORMAL, ANYWHERE, BREAK_WORD }

        public enum FontStyle implements Keyword { NORMAL, ITALIC, OBLIQUE }

        public enum FlexDirection implements Keyword {
            ROW, ROW_REVERSE, COLUMN, COLUMN_REVERSE;

            public boolean isRow() {
                return this == ROW || this == ROW_REVERSE;
            }

            public boolean isReverse() {
                return this == ROW_REVERSE || this == COLUMN_REVERSE;
            }
        }

        public enum FlexWrap implements Keyword { NOWRAP, WRAP, WRAP_REVERSE }

        /**
         * 揃え（{@code justify-content} / {@code align-content} / {@code align-items} / {@code align-self} と
         * グリッドの {@code justify-*}）。軸ごとに使えない値は読み手が既定に倒す。
         */
        public enum Alignment implements Keyword {
            NORMAL, AUTO, STRETCH, CENTER, START, END, FLEX_START, FLEX_END, SELF_START, SELF_END, LEFT, RIGHT,
            BASELINE, FIRST_BASELINE, LAST_BASELINE, SPACE_BETWEEN, SPACE_AROUND, SPACE_EVENLY, LEGACY
        }

        public enum BorderStyle implements Keyword {
            NONE, HIDDEN, DOTTED, DASHED, SOLID, DOUBLE, GROOVE, RIDGE, INSET, OUTSET, AUTO
        }

        public enum BorderCollapse implements Keyword { SEPARATE, COLLAPSE }

        public enum TableLayout implements Keyword { AUTO, FIXED }

        public enum CaptionSide implements Keyword { TOP, BOTTOM }

        public enum ListStylePosition implements Keyword { OUTSIDE, INSIDE }

        public enum PointerEvents implements Keyword { AUTO, NONE }

        public enum Isolation implements Keyword { AUTO, ISOLATE }

        public enum ScrollbarWidth implements Keyword { AUTO, THIN, NONE }

        /** {@code container-type}。{@code size} は両軸、{@code inline-size} は横軸の containment を伴う。 */
        public enum ContainerType implements Keyword {
            NORMAL, SIZE, INLINE_SIZE;

            /** 横軸の寸法が内容で決まらない（containment がある）か。 */
            public boolean containsInlineSize() {
                return this != NORMAL;
            }

            /** 縦軸の寸法が内容で決まらないか。 */
            public boolean containsBlockSize() {
                return this == SIZE;
            }
        }
    }
}
