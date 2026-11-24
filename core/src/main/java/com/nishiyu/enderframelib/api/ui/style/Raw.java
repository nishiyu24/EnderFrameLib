package com.nishiyu.enderframelib.api.ui.style;

import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;

/**
 * まだ型を持たない値（{@code transform}・{@code grid-template-columns}・{@code box-shadow} など、構文の大きいプロパティ）を
 * CSS の書き方のまま渡す逃げ道。{@link Css#raw} で作る。
 *
 * <p>使った箇所は {@code raw(} で検索できる。型を足したら置き換えて減らしていく。</p>
 */
@ApiStatus.AvailableSince("1.0")
public record Raw(String css) implements CssValue {

    public Raw {
        Objects.requireNonNull(css, "css");
        if (css.isBlank() || css.indexOf(';') >= 0 || css.indexOf('{') >= 0 || css.indexOf('}') >= 0) {
            throw new IllegalArgumentException("値 1 つ分だけを渡す（; { } は不可）: " + css);
        }
    }
}
