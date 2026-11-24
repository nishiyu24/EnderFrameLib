package com.nishiyu.enderframelib.api.ui.style;

import com.nishiyu.enderframelib.api.ui.Prop;
import com.nishiyu.enderframelib.api.ui.State;
import com.nishiyu.enderframelib.api.ui.style.Keyword.None;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;

/**
 * 型付きのスタイルを書く入口。{@code import static ...style.Css.*} すると、値（{@link #px}・{@link #hex} など）、
 * 条件つきの宣言（{@link #when}・{@link #hover}）、インライン（{@link #inline}）と、継承した全プロパティ関数
 * （{@link BoxProperties} ほかの群。{@code PropertyRegistry} から生成）が使える。
 *
 * <pre>{@code
 * StyleClass.of("card", padding(px(20)), borderRadius(px(14)), background(SURFACE),
 *         border(px(1), BorderStyle.SOLID, BORDER), display(Display.FLEX), flexDirection(FlexDirection.COLUMN));
 * }</pre>
 */
@ApiStatus.AvailableSince("1.0")
public final class Css extends OtherProperties {

    private Css() {}

    /** {@code none}（{@code border(NONE)}・{@code flex(NONE)}・{@code maxWidth(NONE)}）。 */
    public static final None NONE = None.NONE;
    public static final Length ZERO = Length.ZERO;
    public static final Length AUTO = Length.AUTO;

    // ---- 値

    public static Length px(double v) { return Length.px(v); }
    public static Length pct(double v) { return Length.pct(v); }
    public static Length em(double v) { return Length.em(v); }
    public static Length rem(double v) { return Length.rem(v); }
    public static Length vw(double v) { return Length.vw(v); }
    public static Length vh(double v) { return Length.vh(v); }
    public static Time ms(double v) { return Time.ms(v); }
    public static Time sec(double v) { return Time.sec(v); }
    public static Color hex(int rgb) { return Color.hex(rgb); }

    /** まだ型の無い値を CSS の書き方のまま渡す逃げ道（{@link Raw}）。型のあるプロパティには渡せない。 */
    public static Raw raw(String css) { return new Raw(css); }

    // ---- 条件つきの宣言（StyleClass.of へ渡す）

    /** 状態 {@code modifier} が付いている間だけ効く宣言（{@link StyleClass#is} で付ける）。 */
    public static Variant when(Modifier modifier, Declaration... declarations) {
        return new Variant(Objects.requireNonNull(modifier, "modifier"), null, declarations);
    }

    /** ポインタが乗っている間（{@code :hover}）。 */
    public static Variant hover(Declaration... declarations) {
        return new Variant(null, "hover", declarations);
    }

    /** 押している間（{@code :active}）。 */
    public static Variant pressed(Declaration... declarations) {
        return new Variant(null, "active", declarations);
    }

    /** キーボードでフォーカスしている間（{@code :focus-visible}）。 */
    public static Variant focusVisible(Declaration... declarations) {
        return new Variant(null, "focus-visible", declarations);
    }

    // ---- 要素ごとのインライン

    /**
     * 要素のインラインスタイル。状態に結んだ宣言（{@code width(percent.map(Length::pct))} など）は値が変わるたびに書き換える。
     * 繰り返し使う見た目は {@link StyleClass} に、本当に要素ごとに違う値だけをここに書く。
     */
    public static Prop inline(Declaration... declarations) {
        return el -> {
            for (Declaration d : declarations) {
                el.getStyle().setProperty(d.property(), d.currentCss());
                State<? extends CssValue> s = d.dynamicValue();
                if (s != null) s.onChange(v -> el.getStyle().setProperty(d.property(), v.css()));
            }
        };
    }
}
