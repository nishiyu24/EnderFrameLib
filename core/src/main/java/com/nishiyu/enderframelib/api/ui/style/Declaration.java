package com.nishiyu.enderframelib.api.ui.style;

import com.nishiyu.enderframelib.api.dom.StyleRule;
import com.nishiyu.enderframelib.api.ui.State;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * 1 つのスタイル宣言（プロパティと型付きの値）。{@link Css} のプロパティ関数が作る。
 *
 * <p>値が {@link State} の宣言（動的な宣言）は要素ごとのインラインスタイルにしか使えない（{@link Css#inline}）。
 * {@link StyleClass} に渡すと例外になる。</p>
 */
@ApiStatus.AvailableSince("1.0")
public final class Declaration {

    private final String property;
    private final @Nullable CssValue value;
    private final @Nullable State<? extends CssValue> dynamic;

    private Declaration(String property, @Nullable CssValue value, @Nullable State<? extends CssValue> dynamic) {
        this.property = property;
        this.value = value;
        this.dynamic = dynamic;
    }

    static Declaration of(String property, CssValue value) {
        return new Declaration(property, Objects.requireNonNull(value, property), null);
    }

    static Declaration of(String property, State<? extends CssValue> value) {
        return new Declaration(property, null, Objects.requireNonNull(value, property));
    }

    /** kebab-case のプロパティ名（カスタムプロパティは {@code --name}）。 */
    public String property() {
        return property;
    }

    /** 値が状態に結ばれている（インラインでしか使えない）。 */
    public boolean isDynamic() {
        return dynamic != null;
    }

    @Nullable
    State<? extends CssValue> dynamicValue() {
        return dynamic;
    }

    /** 今の値を直列化したもの。 */
    String currentCss() {
        return value != null ? value.css() : dynamic.get().css();
    }

    StyleRule.Declaration toRule(String owner) {
        if (dynamic != null) {
            throw new IllegalArgumentException("'" + owner + "' の " + property
                    + " は状態に結ばれている。状態で変わる値は Css.inline(...) か Token.bind(...) で要素に与える");
        }
        return new StyleRule.Declaration(property, value.css());
    }

    @Override
    public String toString() {
        return property + ": " + (value != null ? value.css() : "<" + dynamic.get().css() + ">");
    }
}
