// 生成物（scripts/GenTypedCss.java が PropertyRegistry から作る）。手で直さない。
package com.nishiyu.enderframelib.api.ui.style;

import com.nishiyu.enderframelib.api.ui.State;
import org.jetbrains.annotations.ApiStatus;

import static com.nishiyu.enderframelib.api.ui.style.Keyword.*;

/**
 * プロパティ関数の群（{@code PropertyRegistry} から生成）。{@link Css} が全群を継承するので {@code import static ...style.Css.*} で使う。
 *
 * <p>まだ型の無いもの（{@link Raw} を受ける）: border-image-source, border-image-slice, border-image-width, border-image-outset, border-image-repeat, border-image。</p>
 */
@ApiStatus.AvailableSince("1.0")
public abstract class BorderProperties extends BoxProperties {

    BorderProperties() {}

    private static Declaration d(String property, CssValue value) { return Declaration.of(property, value); }

    private static Declaration d(String property, State<? extends CssValue> value) { return Declaration.of(property, value); }

    // border-top-width
    public static Declaration borderTopWidth(Value<Length> v) { return d("border-top-width", v); }
    public static Declaration borderTopWidth(State<? extends Value<Length>> v) { return d("border-top-width", v); }
    // border-top-style
    public static Declaration borderTopStyle(BorderStyle v) { return d("border-top-style", v); }
    // border-top-color
    public static Declaration borderTopColor(Value<Color> v) { return d("border-top-color", v); }
    public static Declaration borderTopColor(State<? extends Value<Color>> v) { return d("border-top-color", v); }
    // border-top（ショートハンド）
    public static Declaration borderTop(Value<Length> width, BorderStyle style, Value<Color> color) { return d("border-top", CssValues.join(width, style, color)); }
    public static Declaration borderTop(None v) { return d("border-top", v); }
    // border-right-width
    public static Declaration borderRightWidth(Value<Length> v) { return d("border-right-width", v); }
    public static Declaration borderRightWidth(State<? extends Value<Length>> v) { return d("border-right-width", v); }
    // border-right-style
    public static Declaration borderRightStyle(BorderStyle v) { return d("border-right-style", v); }
    // border-right-color
    public static Declaration borderRightColor(Value<Color> v) { return d("border-right-color", v); }
    public static Declaration borderRightColor(State<? extends Value<Color>> v) { return d("border-right-color", v); }
    // border-right（ショートハンド）
    public static Declaration borderRight(Value<Length> width, BorderStyle style, Value<Color> color) { return d("border-right", CssValues.join(width, style, color)); }
    public static Declaration borderRight(None v) { return d("border-right", v); }
    // border-bottom-width
    public static Declaration borderBottomWidth(Value<Length> v) { return d("border-bottom-width", v); }
    public static Declaration borderBottomWidth(State<? extends Value<Length>> v) { return d("border-bottom-width", v); }
    // border-bottom-style
    public static Declaration borderBottomStyle(BorderStyle v) { return d("border-bottom-style", v); }
    // border-bottom-color
    public static Declaration borderBottomColor(Value<Color> v) { return d("border-bottom-color", v); }
    public static Declaration borderBottomColor(State<? extends Value<Color>> v) { return d("border-bottom-color", v); }
    // border-bottom（ショートハンド）
    public static Declaration borderBottom(Value<Length> width, BorderStyle style, Value<Color> color) { return d("border-bottom", CssValues.join(width, style, color)); }
    public static Declaration borderBottom(None v) { return d("border-bottom", v); }
    // border-left-width
    public static Declaration borderLeftWidth(Value<Length> v) { return d("border-left-width", v); }
    public static Declaration borderLeftWidth(State<? extends Value<Length>> v) { return d("border-left-width", v); }
    // border-left-style
    public static Declaration borderLeftStyle(BorderStyle v) { return d("border-left-style", v); }
    // border-left-color
    public static Declaration borderLeftColor(Value<Color> v) { return d("border-left-color", v); }
    public static Declaration borderLeftColor(State<? extends Value<Color>> v) { return d("border-left-color", v); }
    // border-left（ショートハンド）
    public static Declaration borderLeft(Value<Length> width, BorderStyle style, Value<Color> color) { return d("border-left", CssValues.join(width, style, color)); }
    public static Declaration borderLeft(None v) { return d("border-left", v); }
    // border-width（ショートハンド）
    @SafeVarargs public static Declaration borderWidth(Value<Length>... sides) { return d("border-width", CssValues.sides(sides)); }
    // border-style（ショートハンド）
    public static Declaration borderStyle(BorderStyle... sides) { return d("border-style", CssValues.sides(sides)); }
    // border-color（ショートハンド）
    @SafeVarargs public static Declaration borderColor(Value<Color>... sides) { return d("border-color", CssValues.sides(sides)); }
    // border（ショートハンド）
    public static Declaration border(Value<Length> width, BorderStyle style, Value<Color> color) { return d("border", CssValues.join(width, style, color)); }
    public static Declaration border(None v) { return d("border", v); }
    // border-top-left-radius
    public static Declaration borderTopLeftRadius(Value<Length> v) { return d("border-top-left-radius", v); }
    public static Declaration borderTopLeftRadius(State<? extends Value<Length>> v) { return d("border-top-left-radius", v); }
    // border-top-right-radius
    public static Declaration borderTopRightRadius(Value<Length> v) { return d("border-top-right-radius", v); }
    public static Declaration borderTopRightRadius(State<? extends Value<Length>> v) { return d("border-top-right-radius", v); }
    // border-bottom-right-radius
    public static Declaration borderBottomRightRadius(Value<Length> v) { return d("border-bottom-right-radius", v); }
    public static Declaration borderBottomRightRadius(State<? extends Value<Length>> v) { return d("border-bottom-right-radius", v); }
    // border-bottom-left-radius
    public static Declaration borderBottomLeftRadius(Value<Length> v) { return d("border-bottom-left-radius", v); }
    public static Declaration borderBottomLeftRadius(State<? extends Value<Length>> v) { return d("border-bottom-left-radius", v); }
    // border-radius（ショートハンド）
    @SafeVarargs public static Declaration borderRadius(Value<Length>... sides) { return d("border-radius", CssValues.sides(sides)); }
    // outline-color
    public static Declaration outlineColor(Value<Color> v) { return d("outline-color", v); }
    public static Declaration outlineColor(State<? extends Value<Color>> v) { return d("outline-color", v); }
    // outline-style
    public static Declaration outlineStyle(BorderStyle v) { return d("outline-style", v); }
    // outline-width
    public static Declaration outlineWidth(Value<Length> v) { return d("outline-width", v); }
    public static Declaration outlineWidth(State<? extends Value<Length>> v) { return d("outline-width", v); }
    // outline-offset
    public static Declaration outlineOffset(Value<Length> v) { return d("outline-offset", v); }
    public static Declaration outlineOffset(State<? extends Value<Length>> v) { return d("outline-offset", v); }
    // outline（ショートハンド）
    public static Declaration outline(Value<Length> width, BorderStyle style, Value<Color> color) { return d("outline", CssValues.join(width, style, color)); }
    public static Declaration outline(None v) { return d("outline", v); }
    // border-collapse
    public static Declaration borderCollapse(BorderCollapse v) { return d("border-collapse", v); }
    // border-spacing
    public static Declaration borderSpacing(Value<Length> v) { return d("border-spacing", v); }
    public static Declaration borderSpacing(State<? extends Value<Length>> v) { return d("border-spacing", v); }
    // border-image-source
    public static Declaration borderImageSource(Raw v) { return d("border-image-source", v); }
    // border-image-slice
    public static Declaration borderImageSlice(Raw v) { return d("border-image-slice", v); }
    // border-image-width
    public static Declaration borderImageWidth(Raw v) { return d("border-image-width", v); }
    // border-image-outset
    public static Declaration borderImageOutset(Raw v) { return d("border-image-outset", v); }
    // border-image-repeat
    public static Declaration borderImageRepeat(Raw v) { return d("border-image-repeat", v); }
    // border-image（ショートハンド）
    public static Declaration borderImage(Raw v) { return d("border-image", v); }
}
