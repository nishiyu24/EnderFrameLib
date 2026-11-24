// 生成物（scripts/GenTypedCss.java が PropertyRegistry から作る）。手で直さない。
package com.nishiyu.enderframelib.api.ui.style;

import com.nishiyu.enderframelib.api.ui.State;
import org.jetbrains.annotations.ApiStatus;

import static com.nishiyu.enderframelib.api.ui.style.Keyword.*;

/**
 * プロパティ関数の群（{@code PropertyRegistry} から生成）。{@link Css} が全群を継承するので {@code import static ...style.Css.*} で使う。
 *
 * <p>まだ型の無いもの（{@link Raw} を受ける）: list-style-image, list-style, font, text-decoration, text-shadow。</p>
 */
@ApiStatus.AvailableSince("1.0")
public abstract class TextProperties extends FlexGridProperties {

    TextProperties() {}

    private static Declaration d(String property, CssValue value) { return Declaration.of(property, value); }

    private static Declaration d(String property, State<? extends CssValue> value) { return Declaration.of(property, value); }

    // list-style-type
    public static Declaration listStyleType(ListStyleType v) { return d("list-style-type", v); }
    // list-style-position
    public static Declaration listStylePosition(ListStylePosition v) { return d("list-style-position", v); }
    // list-style-image
    public static Declaration listStyleImage(Raw v) { return d("list-style-image", v); }
    // list-style（ショートハンド）
    public static Declaration listStyle(Raw v) { return d("list-style", v); }
    // font-family
    public static Declaration fontFamily(FontFamily v) { return d("font-family", v); }
    public static Declaration fontFamily(Raw v) { return d("font-family", v); }
    // font-size
    public static Declaration fontSize(Value<Length> v) { return d("font-size", v); }
    public static Declaration fontSize(State<? extends Value<Length>> v) { return d("font-size", v); }
    // font-weight
    public static Declaration fontWeight(FontWeight v) { return d("font-weight", v); }
    public static Declaration fontWeight(int v) { return d("font-weight", CssValues.weight(v)); }
    // font-style
    public static Declaration fontStyle(FontStyle v) { return d("font-style", v); }
    // font-variant
    public static Declaration fontVariant(FontVariant v) { return d("font-variant", v); }
    // font-kerning
    public static Declaration fontKerning(FontKerning v) { return d("font-kerning", v); }
    // line-height
    public static Declaration lineHeight(double v) { return d("line-height", CssValues.num(v)); }
    public static Declaration lineHeight(Value<Length> v) { return d("line-height", v); }
    // font（ショートハンド）
    public static Declaration font(Raw v) { return d("font", v); }
    // letter-spacing
    public static Declaration letterSpacing(Value<Length> v) { return d("letter-spacing", v); }
    public static Declaration letterSpacing(State<? extends Value<Length>> v) { return d("letter-spacing", v); }
    // word-spacing
    public static Declaration wordSpacing(Value<Length> v) { return d("word-spacing", v); }
    public static Declaration wordSpacing(State<? extends Value<Length>> v) { return d("word-spacing", v); }
    // text-indent
    public static Declaration textIndent(Value<Length> v) { return d("text-indent", v); }
    public static Declaration textIndent(State<? extends Value<Length>> v) { return d("text-indent", v); }
    // text-align
    public static Declaration textAlign(TextAlign v) { return d("text-align", v); }
    // text-align-last
    public static Declaration textAlignLast(TextAlignLast v) { return d("text-align-last", v); }
    // text-justify
    public static Declaration textJustify(TextJustify v) { return d("text-justify", v); }
    // tab-size
    public static Declaration tabSize(int v) { return d("tab-size", CssValues.integer(v)); }
    public static Declaration tabSize(Value<Length> v) { return d("tab-size", v); }
    // text-wrap
    public static Declaration textWrap(TextWrap v) { return d("text-wrap", v); }
    // text-wrap-style
    public static Declaration textWrapStyle(TextWrapStyle v) { return d("text-wrap-style", v); }
    // text-transform
    public static Declaration textTransform(TextTransform v) { return d("text-transform", v); }
    // white-space
    public static Declaration whiteSpace(WhiteSpace v) { return d("white-space", v); }
    // word-break
    public static Declaration wordBreak(WordBreak v) { return d("word-break", v); }
    // overflow-wrap
    public static Declaration overflowWrap(OverflowWrap v) { return d("overflow-wrap", v); }
    // direction
    public static Declaration direction(Direction v) { return d("direction", v); }
    // vertical-align
    public static Declaration verticalAlign(Value<Length> v) { return d("vertical-align", v); }
    public static Declaration verticalAlign(State<? extends Value<Length>> v) { return d("vertical-align", v); }
    // text-overflow
    public static Declaration textOverflow(TextOverflow v) { return d("text-overflow", v); }
    // color
    public static Declaration color(Value<Color> v) { return d("color", v); }
    public static Declaration color(State<? extends Value<Color>> v) { return d("color", v); }
    // text-decoration
    public static Declaration textDecoration(Raw v) { return d("text-decoration", v); }
    // text-shadow
    public static Declaration textShadow(Raw v) { return d("text-shadow", v); }
    // writing-mode
    public static Declaration writingMode(WritingMode v) { return d("writing-mode", v); }
    // text-orientation
    public static Declaration textOrientation(TextOrientation v) { return d("text-orientation", v); }
}
