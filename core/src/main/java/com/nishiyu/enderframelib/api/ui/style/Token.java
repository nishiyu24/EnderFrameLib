package com.nishiyu.enderframelib.api.ui.style;

import com.nishiyu.enderframelib.api.dom.Element;
import com.nishiyu.enderframelib.api.ui.Prop;
import com.nishiyu.enderframelib.api.ui.State;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * デザイントークン（中身は CSS のカスタムプロパティ {@code --name}）。値として使うと {@code var(--name)} になり、
 * 値は {@link Theme} が祖先に配るか、{@link #bind} で要素に直接与える。
 *
 * <pre>{@code
 * static final Token<Color> SURFACE = Token.of("surface");
 * static final StyleClass CARD = StyleClass.of("card", Css.background(SURFACE));
 * }</pre>
 *
 * @param <T> トークンが取る値の型（{@link Color}・{@link Length} など）
 */
@ApiStatus.AvailableSince("1.0")
public final class Token<T extends CssValue> implements Value<T> {

    private static final Pattern NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9-]*");

    private final String name;

    private Token(String name) {
        this.name = name;
    }

    /** @param name カスタムプロパティ名（{@code --} を除く。英字で始まり英数字とハイフン） */
    public static <T extends CssValue> Token<T> of(String name) {
        if (name == null || !NAME.matcher(name).matches()) throw new IllegalArgumentException("トークン名が不正: " + name);
        return new Token<>(name);
    }

    /** {@code --name}。 */
    public String property() {
        return "--" + name;
    }

    /** このトークンに値を与える宣言（{@link Theme#of} に渡す）。 */
    public Declaration is(T value) {
        return Declaration.of(property(), Objects.requireNonNull(value, "value"));
    }

    /** 要素のインラインスタイルでこのトークンの値を状態に結ぶ（子孫へ継承される）。 */
    public Prop bind(State<? extends T> value) {
        Objects.requireNonNull(value, "value");
        return (Element el) -> {
            el.getStyle().setProperty(property(), value.get().css());
            value.onChange(v -> el.getStyle().setProperty(property(), v.css()));
        };
    }

    @Override
    public String css() {
        return "var(" + property() + ")";
    }

    @Override
    public String toString() {
        return css();
    }
}
