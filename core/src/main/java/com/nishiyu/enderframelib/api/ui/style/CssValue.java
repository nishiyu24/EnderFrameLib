package com.nishiyu.enderframelib.api.ui.style;

import org.jetbrains.annotations.ApiStatus;

/**
 * 型付きスタイルの値（{@link Length}・{@link Color}・{@link Keyword} の列挙・{@link Token} など）。
 *
 * <p>書く側は文字列を組み立てず、この型の値を {@link Css} のプロパティ関数へ渡す。</p>
 */
@ApiStatus.AvailableSince("1.0")
public interface CssValue {

    /** CSS の値として直列化したもの（エンジンへ渡すときにだけ使う）。 */
    @ApiStatus.Internal
    String css();
}
