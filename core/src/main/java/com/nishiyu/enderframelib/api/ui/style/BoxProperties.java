// 生成物（scripts/GenTypedCss.java が PropertyRegistry から作る）。手で直さない。
package com.nishiyu.enderframelib.api.ui.style;

import com.nishiyu.enderframelib.api.ui.State;
import org.jetbrains.annotations.ApiStatus;

import static com.nishiyu.enderframelib.api.ui.style.Keyword.*;

/**
 * プロパティ関数の群（{@code PropertyRegistry} から生成）。{@link Css} が全群を継承するので {@code import static ...style.Css.*} で使う。
 */
@ApiStatus.AvailableSince("1.0")
public abstract class BoxProperties {

    BoxProperties() {}

    private static Declaration d(String property, CssValue value) { return Declaration.of(property, value); }

    private static Declaration d(String property, State<? extends CssValue> value) { return Declaration.of(property, value); }

    // display
    public static Declaration display(Display v) { return d("display", v); }
    // position
    public static Declaration position(Position v) { return d("position", v); }
    // top
    public static Declaration top(Value<Length> v) { return d("top", v); }
    public static Declaration top(State<? extends Value<Length>> v) { return d("top", v); }
    // right
    public static Declaration right(Value<Length> v) { return d("right", v); }
    public static Declaration right(State<? extends Value<Length>> v) { return d("right", v); }
    // bottom
    public static Declaration bottom(Value<Length> v) { return d("bottom", v); }
    public static Declaration bottom(State<? extends Value<Length>> v) { return d("bottom", v); }
    // left
    public static Declaration left(Value<Length> v) { return d("left", v); }
    public static Declaration left(State<? extends Value<Length>> v) { return d("left", v); }
    // inset（ショートハンド）
    @SafeVarargs public static Declaration inset(Value<Length>... sides) { return d("inset", CssValues.sides(sides)); }
    // float
    public static Declaration cssFloat(FloatSide v) { return d("float", v); }
    // clear
    public static Declaration clear(Clear v) { return d("clear", v); }
    // z-index
    public static Declaration zIndex(int v) { return d("z-index", CssValues.integer(v)); }
    // width
    public static Declaration width(Value<Length> v) { return d("width", v); }
    public static Declaration width(State<? extends Value<Length>> v) { return d("width", v); }
    // height
    public static Declaration height(Value<Length> v) { return d("height", v); }
    public static Declaration height(State<? extends Value<Length>> v) { return d("height", v); }
    // min-width
    public static Declaration minWidth(Value<Length> v) { return d("min-width", v); }
    public static Declaration minWidth(State<? extends Value<Length>> v) { return d("min-width", v); }
    // min-height
    public static Declaration minHeight(Value<Length> v) { return d("min-height", v); }
    public static Declaration minHeight(State<? extends Value<Length>> v) { return d("min-height", v); }
    // max-width
    public static Declaration maxWidth(Value<Length> v) { return d("max-width", v); }
    public static Declaration maxWidth(State<? extends Value<Length>> v) { return d("max-width", v); }
    public static Declaration maxWidth(None v) { return d("max-width", v); }
    // max-height
    public static Declaration maxHeight(Value<Length> v) { return d("max-height", v); }
    public static Declaration maxHeight(State<? extends Value<Length>> v) { return d("max-height", v); }
    public static Declaration maxHeight(None v) { return d("max-height", v); }
    // box-sizing
    public static Declaration boxSizing(BoxSizing v) { return d("box-sizing", v); }
    // aspect-ratio
    public static Declaration aspectRatio(double width, double height) { return d("aspect-ratio", CssValues.ratio(width, height)); }
    // margin-top
    public static Declaration marginTop(Value<Length> v) { return d("margin-top", v); }
    public static Declaration marginTop(State<? extends Value<Length>> v) { return d("margin-top", v); }
    // margin-right
    public static Declaration marginRight(Value<Length> v) { return d("margin-right", v); }
    public static Declaration marginRight(State<? extends Value<Length>> v) { return d("margin-right", v); }
    // margin-bottom
    public static Declaration marginBottom(Value<Length> v) { return d("margin-bottom", v); }
    public static Declaration marginBottom(State<? extends Value<Length>> v) { return d("margin-bottom", v); }
    // margin-left
    public static Declaration marginLeft(Value<Length> v) { return d("margin-left", v); }
    public static Declaration marginLeft(State<? extends Value<Length>> v) { return d("margin-left", v); }
    // padding-top
    public static Declaration paddingTop(Value<Length> v) { return d("padding-top", v); }
    public static Declaration paddingTop(State<? extends Value<Length>> v) { return d("padding-top", v); }
    // padding-right
    public static Declaration paddingRight(Value<Length> v) { return d("padding-right", v); }
    public static Declaration paddingRight(State<? extends Value<Length>> v) { return d("padding-right", v); }
    // padding-bottom
    public static Declaration paddingBottom(Value<Length> v) { return d("padding-bottom", v); }
    public static Declaration paddingBottom(State<? extends Value<Length>> v) { return d("padding-bottom", v); }
    // padding-left
    public static Declaration paddingLeft(Value<Length> v) { return d("padding-left", v); }
    public static Declaration paddingLeft(State<? extends Value<Length>> v) { return d("padding-left", v); }
    // margin（ショートハンド）
    @SafeVarargs public static Declaration margin(Value<Length>... sides) { return d("margin", CssValues.sides(sides)); }
    // padding（ショートハンド）
    @SafeVarargs public static Declaration padding(Value<Length>... sides) { return d("padding", CssValues.sides(sides)); }
    // overflow-x
    public static Declaration overflowX(Overflow v) { return d("overflow-x", v); }
    // overflow-y
    public static Declaration overflowY(Overflow v) { return d("overflow-y", v); }
    // overflow（ショートハンド）
    public static Declaration overflow(Overflow both) { return d("overflow", both); }
    public static Declaration overflow(Overflow x, Overflow y) { return d("overflow", CssValues.join(x, y)); }
    // scrollbar-width
    public static Declaration scrollbarWidth(ScrollbarWidth v) { return d("scrollbar-width", v); }
    // table-layout
    public static Declaration tableLayout(TableLayout v) { return d("table-layout", v); }
    // caption-side
    public static Declaration captionSide(CaptionSide v) { return d("caption-side", v); }
    // empty-cells
    public static Declaration emptyCells(EmptyCells v) { return d("empty-cells", v); }
    // contain-intrinsic-width
    public static Declaration containIntrinsicWidth(Value<Length> v) { return d("contain-intrinsic-width", v); }
    // contain-intrinsic-height
    public static Declaration containIntrinsicHeight(Value<Length> v) { return d("contain-intrinsic-height", v); }
}
