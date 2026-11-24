// 生成物（scripts/GenTypedCss.java が PropertyRegistry から作る）。手で直さない。
package com.nishiyu.enderframelib.api.ui.style;

import com.nishiyu.enderframelib.api.ui.State;
import org.jetbrains.annotations.ApiStatus;

import static com.nishiyu.enderframelib.api.ui.style.Keyword.*;

/**
 * プロパティ関数の群（{@code PropertyRegistry} から生成）。{@link Css} が全群を継承するので {@code import static ...style.Css.*} で使う。
 *
 * <p>まだ型の無いもの（{@link Raw} を受ける）: columns, grid-template-columns, grid-template-rows, grid-template-areas, grid-auto-columns, grid-auto-rows, grid-template, grid, grid-column-start, grid-column-end, grid-row-start, grid-row-end, grid-column, grid-row, grid-area。</p>
 */
@ApiStatus.AvailableSince("1.0")
public abstract class FlexGridProperties extends VisualProperties {

    FlexGridProperties() {}

    private static Declaration d(String property, CssValue value) { return Declaration.of(property, value); }

    private static Declaration d(String property, State<? extends CssValue> value) { return Declaration.of(property, value); }

    // flex-direction
    public static Declaration flexDirection(FlexDirection v) { return d("flex-direction", v); }
    // flex-wrap
    public static Declaration flexWrap(FlexWrap v) { return d("flex-wrap", v); }
    // flex-flow（ショートハンド）
    public static Declaration flexFlow(FlexDirection direction, FlexWrap wrap) { return d("flex-flow", CssValues.join(direction, wrap)); }
    // flex-grow
    public static Declaration flexGrow(double v) { return d("flex-grow", CssValues.num(v)); }
    // flex-shrink
    public static Declaration flexShrink(double v) { return d("flex-shrink", CssValues.num(v)); }
    // flex-basis
    public static Declaration flexBasis(Value<Length> v) { return d("flex-basis", v); }
    public static Declaration flexBasis(State<? extends Value<Length>> v) { return d("flex-basis", v); }
    // flex（ショートハンド）
    public static Declaration flex(double grow) { return d("flex", CssValues.num(grow)); }
    public static Declaration flex(double grow, double shrink, Value<Length> basis) { return d("flex", CssValues.join(CssValues.num(grow), CssValues.num(shrink), basis)); }
    public static Declaration flex(None v) { return d("flex", v); }
    // order
    public static Declaration order(int v) { return d("order", CssValues.integer(v)); }
    // justify-content
    public static Declaration justifyContent(Alignment v) { return d("justify-content", v); }
    // align-content
    public static Declaration alignContent(Alignment v) { return d("align-content", v); }
    // justify-items
    public static Declaration justifyItems(Alignment v) { return d("justify-items", v); }
    // align-items
    public static Declaration alignItems(Alignment v) { return d("align-items", v); }
    // justify-self
    public static Declaration justifySelf(Alignment v) { return d("justify-self", v); }
    // align-self
    public static Declaration alignSelf(Alignment v) { return d("align-self", v); }
    // place-content（ショートハンド）
    public static Declaration placeContent(Alignment both) { return d("place-content", both); }
    public static Declaration placeContent(Alignment align, Alignment justify) { return d("place-content", CssValues.join(align, justify)); }
    // place-items（ショートハンド）
    public static Declaration placeItems(Alignment both) { return d("place-items", both); }
    public static Declaration placeItems(Alignment align, Alignment justify) { return d("place-items", CssValues.join(align, justify)); }
    // place-self（ショートハンド）
    public static Declaration placeSelf(Alignment both) { return d("place-self", both); }
    public static Declaration placeSelf(Alignment align, Alignment justify) { return d("place-self", CssValues.join(align, justify)); }
    // row-gap
    public static Declaration rowGap(Value<Length> v) { return d("row-gap", v); }
    public static Declaration rowGap(State<? extends Value<Length>> v) { return d("row-gap", v); }
    // column-gap
    public static Declaration columnGap(Value<Length> v) { return d("column-gap", v); }
    public static Declaration columnGap(State<? extends Value<Length>> v) { return d("column-gap", v); }
    // gap（ショートハンド）
    public static Declaration gap(Value<Length> both) { return d("gap", both); }
    public static Declaration gap(Value<Length> row, Value<Length> column) { return d("gap", CssValues.join(row, column)); }
    // grid-gap（ショートハンド）
    public static Declaration gridGap(Value<Length> both) { return d("grid-gap", both); }
    public static Declaration gridGap(Value<Length> row, Value<Length> column) { return d("grid-gap", CssValues.join(row, column)); }
    // grid-row-gap（ショートハンド）
    public static Declaration gridRowGap(Value<Length> v) { return d("grid-row-gap", v); }
    // grid-column-gap（ショートハンド）
    public static Declaration gridColumnGap(Value<Length> v) { return d("grid-column-gap", v); }
    // column-count
    public static Declaration columnCount(int v) { return d("column-count", CssValues.integer(v)); }
    // column-width
    public static Declaration columnWidth(Value<Length> v) { return d("column-width", v); }
    public static Declaration columnWidth(State<? extends Value<Length>> v) { return d("column-width", v); }
    // columns（ショートハンド）
    public static Declaration columns(Raw v) { return d("columns", v); }
    // column-fill
    public static Declaration columnFill(ColumnFill v) { return d("column-fill", v); }
    // column-span
    public static Declaration columnSpan(ColumnSpan v) { return d("column-span", v); }
    // column-rule-color
    public static Declaration columnRuleColor(Value<Color> v) { return d("column-rule-color", v); }
    public static Declaration columnRuleColor(State<? extends Value<Color>> v) { return d("column-rule-color", v); }
    // column-rule-style
    public static Declaration columnRuleStyle(BorderStyle v) { return d("column-rule-style", v); }
    // column-rule-width
    public static Declaration columnRuleWidth(Value<Length> v) { return d("column-rule-width", v); }
    public static Declaration columnRuleWidth(State<? extends Value<Length>> v) { return d("column-rule-width", v); }
    // column-rule（ショートハンド）
    public static Declaration columnRule(Value<Length> width, BorderStyle style, Value<Color> color) { return d("column-rule", CssValues.join(width, style, color)); }
    public static Declaration columnRule(None v) { return d("column-rule", v); }
    // grid-template-columns
    public static Declaration gridTemplateColumns(Raw v) { return d("grid-template-columns", v); }
    // grid-template-rows
    public static Declaration gridTemplateRows(Raw v) { return d("grid-template-rows", v); }
    // grid-template-areas
    public static Declaration gridTemplateAreas(Raw v) { return d("grid-template-areas", v); }
    // grid-auto-columns
    public static Declaration gridAutoColumns(Raw v) { return d("grid-auto-columns", v); }
    // grid-auto-rows
    public static Declaration gridAutoRows(Raw v) { return d("grid-auto-rows", v); }
    // grid-auto-flow
    public static Declaration gridAutoFlow(GridAutoFlow v) { return d("grid-auto-flow", v); }
    // grid-template（ショートハンド）
    public static Declaration gridTemplate(Raw v) { return d("grid-template", v); }
    // grid（ショートハンド）
    public static Declaration grid(Raw v) { return d("grid", v); }
    // grid-column-start
    public static Declaration gridColumnStart(Raw v) { return d("grid-column-start", v); }
    // grid-column-end
    public static Declaration gridColumnEnd(Raw v) { return d("grid-column-end", v); }
    // grid-row-start
    public static Declaration gridRowStart(Raw v) { return d("grid-row-start", v); }
    // grid-row-end
    public static Declaration gridRowEnd(Raw v) { return d("grid-row-end", v); }
    // grid-column
    public static Declaration gridColumn(Raw v) { return d("grid-column", v); }
    // grid-row
    public static Declaration gridRow(Raw v) { return d("grid-row", v); }
    // grid-area
    public static Declaration gridArea(Raw v) { return d("grid-area", v); }
}
