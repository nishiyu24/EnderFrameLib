// 生成物（scripts/GenTypedCss.java が PropertyRegistry から作る）。手で直さない。
package com.nishiyu.enderframelib.api.ui.style;

import org.jetbrains.annotations.ApiStatus;

/**
 * キーワードだけを値に取るプロパティの値（{@code PropertyRegistry} のキーワード列挙と、生成器の表から作る）。
 * {@code import static ...style.Keyword.*} して {@code Display.FLEX} のように使う。
 */
@ApiStatus.AvailableSince("1.0")
public final class Keyword {

    private Keyword() {}

    public enum None implements CssValue {
        NONE("none");
        private final String css;

        None(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum Position implements CssValue {
        STATIC("static"), RELATIVE("relative"), ABSOLUTE("absolute"), FIXED("fixed"), STICKY("sticky");
        private final String css;

        Position(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum FloatSide implements CssValue {
        NONE("none"), LEFT("left"), RIGHT("right"), INLINE_START("inline-start"), INLINE_END("inline-end");
        private final String css;

        FloatSide(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum Clear implements CssValue {
        NONE("none"), LEFT("left"), RIGHT("right"), BOTH("both"), INLINE_START("inline-start"),
        INLINE_END("inline-end");
        private final String css;

        Clear(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum Visibility implements CssValue {
        VISIBLE("visible"), HIDDEN("hidden"), COLLAPSE("collapse");
        private final String css;

        Visibility(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum BoxSizing implements CssValue {
        CONTENT_BOX("content-box"), BORDER_BOX("border-box");
        private final String css;

        BoxSizing(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum BorderStyle implements CssValue {
        NONE("none"), HIDDEN("hidden"), DOTTED("dotted"), DASHED("dashed"), SOLID("solid"), DOUBLE("double"),
        GROOVE("groove"), RIDGE("ridge"), INSET("inset"), OUTSET("outset"), AUTO("auto");
        private final String css;

        BorderStyle(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum Overflow implements CssValue {
        VISIBLE("visible"), HIDDEN("hidden"), CLIP("clip"), SCROLL("scroll"), AUTO("auto");
        private final String css;

        Overflow(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum ScrollbarWidth implements CssValue {
        AUTO("auto"), THIN("thin"), NONE("none");
        private final String css;

        ScrollbarWidth(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum FlexDirection implements CssValue {
        ROW("row"), ROW_REVERSE("row-reverse"), COLUMN("column"), COLUMN_REVERSE("column-reverse");
        private final String css;

        FlexDirection(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum FlexWrap implements CssValue {
        NOWRAP("nowrap"), WRAP("wrap"), WRAP_REVERSE("wrap-reverse"), BALANCE("balance");
        private final String css;

        FlexWrap(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum Alignment implements CssValue {
        NORMAL("normal"), AUTO("auto"), STRETCH("stretch"), CENTER("center"), START("start"), END("end"),
        FLEX_START("flex-start"), FLEX_END("flex-end"), SELF_START("self-start"), SELF_END("self-end"), LEFT("left"),
        RIGHT("right"), BASELINE("baseline"), FIRST_BASELINE("first-baseline"), LAST_BASELINE("last-baseline"),
        SPACE_BETWEEN("space-between"), SPACE_AROUND("space-around"), SPACE_EVENLY("space-evenly"), LEGACY("legacy");
        private final String css;

        Alignment(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum TableLayout implements CssValue {
        AUTO("auto"), FIXED("fixed");
        private final String css;

        TableLayout(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum BorderCollapse implements CssValue {
        SEPARATE("separate"), COLLAPSE("collapse");
        private final String css;

        BorderCollapse(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum CaptionSide implements CssValue {
        TOP("top"), BOTTOM("bottom");
        private final String css;

        CaptionSide(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum ListStylePosition implements CssValue {
        OUTSIDE("outside"), INSIDE("inside");
        private final String css;

        ListStylePosition(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum FontStyle implements CssValue {
        NORMAL("normal"), ITALIC("italic"), OBLIQUE("oblique");
        private final String css;

        FontStyle(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum TextAlign implements CssValue {
        START("start"), END("end"), LEFT("left"), RIGHT("right"), CENTER("center"), JUSTIFY("justify"),
        MATCH_PARENT("match-parent");
        private final String css;

        TextAlign(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum TextTransform implements CssValue {
        NONE("none"), CAPITALIZE("capitalize"), UPPERCASE("uppercase"), LOWERCASE("lowercase");
        private final String css;

        TextTransform(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum OverflowWrap implements CssValue {
        NORMAL("normal"), ANYWHERE("anywhere"), BREAK_WORD("break-word");
        private final String css;

        OverflowWrap(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum TextOverflow implements CssValue {
        CLIP("clip"), ELLIPSIS("ellipsis");
        private final String css;

        TextOverflow(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum ContentVisibility implements CssValue {
        VISIBLE("visible"), AUTO("auto"), HIDDEN("hidden");
        private final String css;

        ContentVisibility(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum ContainerType implements CssValue {
        NORMAL("normal"), SIZE("size"), INLINE_SIZE("inline-size");
        private final String css;

        ContainerType(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum Isolation implements CssValue {
        AUTO("auto"), ISOLATE("isolate");
        private final String css;

        Isolation(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum PointerEvents implements CssValue {
        AUTO("auto"), NONE("none");
        private final String css;

        PointerEvents(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum Display implements CssValue {
        NONE("none"), BLOCK("block"), INLINE("inline"), INLINE_BLOCK("inline-block"), FLEX("flex"),
        INLINE_FLEX("inline-flex"), GRID("grid"), INLINE_GRID("inline-grid"), FLOW_ROOT("flow-root"),
        CONTENTS("contents"), LIST_ITEM("list-item"), TABLE("table"), INLINE_TABLE("inline-table"),
        TABLE_ROW("table-row"), TABLE_CELL("table-cell"), TABLE_ROW_GROUP("table-row-group"),
        TABLE_HEADER_GROUP("table-header-group"), TABLE_FOOTER_GROUP("table-footer-group"),
        TABLE_COLUMN("table-column"), TABLE_COLUMN_GROUP("table-column-group"), TABLE_CAPTION("table-caption");
        private final String css;

        Display(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum ColumnFill implements CssValue {
        AUTO("auto"), BALANCE("balance");
        private final String css;

        ColumnFill(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum ColumnSpan implements CssValue {
        NONE("none"), ALL("all");
        private final String css;

        ColumnSpan(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum GridAutoFlow implements CssValue {
        ROW("row"), COLUMN("column"), DENSE("dense"), ROW_DENSE("row dense"), COLUMN_DENSE("column dense");
        private final String css;

        GridAutoFlow(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum EmptyCells implements CssValue {
        SHOW("show"), HIDE("hide");
        private final String css;

        EmptyCells(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum FontVariant implements CssValue {
        NORMAL("normal"), SMALL_CAPS("small-caps");
        private final String css;

        FontVariant(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum FontKerning implements CssValue {
        AUTO("auto"), NORMAL("normal"), NONE("none");
        private final String css;

        FontKerning(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum TextAlignLast implements CssValue {
        AUTO("auto"), START("start"), END("end"), LEFT("left"), RIGHT("right"), CENTER("center"), JUSTIFY("justify");
        private final String css;

        TextAlignLast(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum TextJustify implements CssValue {
        AUTO("auto"), NONE("none"), INTER_WORD("inter-word"), INTER_CHARACTER("inter-character");
        private final String css;

        TextJustify(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum TextWrapStyle implements CssValue {
        AUTO("auto"), BALANCE("balance"), PRETTY("pretty"), STABLE("stable");
        private final String css;

        TextWrapStyle(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum TextWrap implements CssValue {
        WRAP("wrap"), NOWRAP("nowrap"), BALANCE("balance"), PRETTY("pretty"), STABLE("stable");
        private final String css;

        TextWrap(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum WhiteSpace implements CssValue {
        NORMAL("normal"), NOWRAP("nowrap"), PRE("pre"), PRE_WRAP("pre-wrap"), PRE_LINE("pre-line"),
        BREAK_SPACES("break-spaces");
        private final String css;

        WhiteSpace(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum WordBreak implements CssValue {
        NORMAL("normal"), BREAK_ALL("break-all"), KEEP_ALL("keep-all"), BREAK_WORD("break-word");
        private final String css;

        WordBreak(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum Direction implements CssValue {
        LTR("ltr"), RTL("rtl");
        private final String css;

        Direction(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum Appearance implements CssValue {
        NONE("none"), AUTO("auto");
        private final String css;

        Appearance(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum PositionTryOrder implements CssValue {
        NORMAL("normal"), MOST_WIDTH("most-width"), MOST_HEIGHT("most-height"), MOST_BLOCK_SIZE("most-block-size"),
        MOST_INLINE_SIZE("most-inline-size");
        private final String css;

        PositionTryOrder(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum ObjectFit implements CssValue {
        FILL("fill"), CONTAIN("contain"), COVER("cover"), NONE("none"), SCALE_DOWN("scale-down");
        private final String css;

        ObjectFit(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum WritingMode implements CssValue {
        HORIZONTAL_TB("horizontal-tb"), VERTICAL_RL("vertical-rl"), VERTICAL_LR("vertical-lr");
        private final String css;

        WritingMode(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum TextOrientation implements CssValue {
        MIXED("mixed"), UPRIGHT("upright"), SIDEWAYS("sideways");
        private final String css;

        TextOrientation(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum BreakInside implements CssValue {
        AUTO("auto"), AVOID("avoid"), AVOID_PAGE("avoid-page"), AVOID_COLUMN("avoid-column");
        private final String css;

        BreakInside(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum MixBlendMode implements CssValue {
        NORMAL("normal"), MULTIPLY("multiply"), SCREEN("screen"), OVERLAY("overlay"), DARKEN("darken"),
        LIGHTEN("lighten"), COLOR_DODGE("color-dodge"), COLOR_BURN("color-burn"), HARD_LIGHT("hard-light"),
        SOFT_LIGHT("soft-light"), DIFFERENCE("difference"), EXCLUSION("exclusion"), HUE("hue"),
        SATURATION("saturation"), COLOR("color"), LUMINOSITY("luminosity"), PLUS_LIGHTER("plus-lighter");
        private final String css;

        MixBlendMode(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum UserSelect implements CssValue {
        AUTO("auto"), NONE("none"), TEXT("text"), ALL("all"), CONTAIN("contain");
        private final String css;

        UserSelect(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum Cursor implements CssValue {
        AUTO("auto"), DEFAULT("default"), NONE("none"), POINTER("pointer"), TEXT("text"), MOVE("move"), WAIT("wait"),
        PROGRESS("progress"), HELP("help"), CROSSHAIR("crosshair"), NOT_ALLOWED("not-allowed"), GRAB("grab"),
        GRABBING("grabbing"), COL_RESIZE("col-resize"), ROW_RESIZE("row-resize"), EW_RESIZE("ew-resize"),
        NS_RESIZE("ns-resize"), NWSE_RESIZE("nwse-resize"), NESW_RESIZE("nesw-resize"), CONTEXT_MENU("context-menu"),
        CELL("cell"), COPY("copy"), ALIAS("alias"), NO_DROP("no-drop"), ZOOM_IN("zoom-in"), ZOOM_OUT("zoom-out"),
        VERTICAL_TEXT("vertical-text"), ALL_SCROLL("all-scroll");
        private final String css;

        Cursor(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum FontWeight implements CssValue {
        NORMAL("normal"), BOLD("bold"), BOLDER("bolder"), LIGHTER("lighter");
        private final String css;

        FontWeight(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum ListStyleType implements CssValue {
        NONE("none"), DISC("disc"), CIRCLE("circle"), SQUARE("square"), DECIMAL("decimal"),
        DECIMAL_LEADING_ZERO("decimal-leading-zero"), LOWER_ROMAN("lower-roman"), UPPER_ROMAN("upper-roman"),
        LOWER_ALPHA("lower-alpha"), UPPER_ALPHA("upper-alpha"), LOWER_LATIN("lower-latin"), UPPER_LATIN("upper-latin"),
        LOWER_GREEK("lower-greek");
        private final String css;

        ListStyleType(String css) { this.css = css; }

        @Override public String css() { return css; }
    }

    public enum FontFamily implements CssValue {
        SERIF("serif"), SANS_SERIF("sans-serif"), MONOSPACE("monospace"), CURSIVE("cursive"), FANTASY("fantasy"),
        SYSTEM_UI("system-ui");
        private final String css;

        FontFamily(String css) { this.css = css; }

        @Override public String css() { return css; }
    }
}
