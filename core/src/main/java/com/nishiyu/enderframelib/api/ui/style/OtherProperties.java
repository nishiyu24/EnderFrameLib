// 生成物（scripts/GenTypedCss.java が PropertyRegistry から作る）。手で直さない。
package com.nishiyu.enderframelib.api.ui.style;

import com.nishiyu.enderframelib.api.ui.State;
import org.jetbrains.annotations.ApiStatus;

import static com.nishiyu.enderframelib.api.ui.style.Keyword.*;

/**
 * プロパティ関数の群（{@code PropertyRegistry} から生成）。{@link Css} が全群を継承するので {@code import static ...style.Css.*} で使う。
 *
 * <p>まだ型の無いもの（{@link Raw} を受ける）: contain, contain-intrinsic-inline-size, contain-intrinsic-block-size, contain-intrinsic-size, container-name, anchor-name, position-anchor, position-area, position-try-fallbacks, position-try, container, content, counter-reset, counter-increment, counter-set, src, transition-property, transition-timing-function, transition, animation-name, animation-timing-function, animation-iteration-count, animation-direction, animation-fill-mode, animation。</p>
 */
@ApiStatus.AvailableSince("1.0")
public abstract class OtherProperties extends TextProperties {

    OtherProperties() {}

    private static Declaration d(String property, CssValue value) { return Declaration.of(property, value); }

    private static Declaration d(String property, State<? extends CssValue> value) { return Declaration.of(property, value); }

    // appearance
    public static Declaration appearance(Appearance v) { return d("appearance", v); }
    // contain
    public static Declaration contain(Raw v) { return d("contain", v); }
    // content-visibility
    public static Declaration contentVisibility(ContentVisibility v) { return d("content-visibility", v); }
    // contain-intrinsic-inline-size（ショートハンド）
    public static Declaration containIntrinsicInlineSize(Raw v) { return d("contain-intrinsic-inline-size", v); }
    // contain-intrinsic-block-size（ショートハンド）
    public static Declaration containIntrinsicBlockSize(Raw v) { return d("contain-intrinsic-block-size", v); }
    // contain-intrinsic-size（ショートハンド）
    public static Declaration containIntrinsicSize(Raw v) { return d("contain-intrinsic-size", v); }
    // container-type
    public static Declaration containerType(ContainerType v) { return d("container-type", v); }
    // container-name
    public static Declaration containerName(Raw v) { return d("container-name", v); }
    // anchor-name
    public static Declaration anchorName(Raw v) { return d("anchor-name", v); }
    // position-anchor
    public static Declaration positionAnchor(Raw v) { return d("position-anchor", v); }
    // position-area
    public static Declaration positionArea(Raw v) { return d("position-area", v); }
    // position-try-fallbacks
    public static Declaration positionTryFallbacks(Raw v) { return d("position-try-fallbacks", v); }
    // position-try-order
    public static Declaration positionTryOrder(PositionTryOrder v) { return d("position-try-order", v); }
    // position-try（ショートハンド）
    public static Declaration positionTry(Raw v) { return d("position-try", v); }
    // container（ショートハンド）
    public static Declaration container(Raw v) { return d("container", v); }
    // content
    public static Declaration content(Raw v) { return d("content", v); }
    // counter-reset
    public static Declaration counterReset(Raw v) { return d("counter-reset", v); }
    // counter-increment
    public static Declaration counterIncrement(Raw v) { return d("counter-increment", v); }
    // counter-set
    public static Declaration counterSet(Raw v) { return d("counter-set", v); }
    // src
    public static Declaration src(Raw v) { return d("src", v); }
    // break-inside
    public static Declaration breakInside(BreakInside v) { return d("break-inside", v); }
    // cursor
    public static Declaration cursor(Cursor v) { return d("cursor", v); }
    // pointer-events
    public static Declaration pointerEvents(PointerEvents v) { return d("pointer-events", v); }
    // user-select
    public static Declaration userSelect(UserSelect v) { return d("user-select", v); }
    // transition-property
    public static Declaration transitionProperty(Raw v) { return d("transition-property", v); }
    // transition-duration
    public static Declaration transitionDuration(Value<Time> v) { return d("transition-duration", v); }
    // transition-delay
    public static Declaration transitionDelay(Value<Time> v) { return d("transition-delay", v); }
    // transition-timing-function
    public static Declaration transitionTimingFunction(Raw v) { return d("transition-timing-function", v); }
    // transition（ショートハンド）
    public static Declaration transition(Raw v) { return d("transition", v); }
    // animation-name
    public static Declaration animationName(Raw v) { return d("animation-name", v); }
    // animation-duration
    public static Declaration animationDuration(Value<Time> v) { return d("animation-duration", v); }
    // animation-delay
    public static Declaration animationDelay(Value<Time> v) { return d("animation-delay", v); }
    // animation-timing-function
    public static Declaration animationTimingFunction(Raw v) { return d("animation-timing-function", v); }
    // animation-iteration-count
    public static Declaration animationIterationCount(Raw v) { return d("animation-iteration-count", v); }
    // animation-direction
    public static Declaration animationDirection(Raw v) { return d("animation-direction", v); }
    // animation-fill-mode
    public static Declaration animationFillMode(Raw v) { return d("animation-fill-mode", v); }
    // animation（ショートハンド）
    public static Declaration animation(Raw v) { return d("animation", v); }
}
