package com.nishiyu.enderframelib.api.ui.style;

import org.jetbrains.annotations.ApiStatus;

/**
 * 型 {@code T} の値として使えるもの: {@code T} そのもの（{@link Length}・{@link Color}・{@link Time}）か、
 * {@code T} を取るトークン（{@link Token}）。プロパティ関数はこれを受けるので、値とトークンを同じ引数に渡せる。
 *
 * @param <T> 値の型
 */
@ApiStatus.AvailableSince("1.0")
public interface Value<T extends CssValue> extends CssValue {
}
