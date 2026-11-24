// 生成物（scripts/GenTypedCss.java が PropertyRegistry から作る）。手で直さない。
package com.nishiyu.enderframelib.api.ui.style;

import com.nishiyu.enderframelib.api.ui.State;
import org.jetbrains.annotations.ApiStatus;

import static com.nishiyu.enderframelib.api.ui.style.Keyword.*;

/**
 * プロパティ関数の群（{@code PropertyRegistry} から生成）。{@link Css} が全群を継承するので {@code import static ...style.Css.*} で使う。
 *
 * <p>まだ型の無いもの（{@link Raw} を受ける）: object-position, background-image, background-position, background-size, background-repeat, background-clip, background-origin, background-attachment, box-shadow, transform, transform-origin, rotate, translate, scale, perspective, filter, clip-path, backdrop-filter, mask-image, mask-position, mask-size, mask-repeat, mask-origin, mask-clip, mask-mode, mask-composite, mask。</p>
 */
@ApiStatus.AvailableSince("1.0")
public abstract class VisualProperties extends BorderProperties {

    VisualProperties() {}

    private static Declaration d(String property, CssValue value) { return Declaration.of(property, value); }

    private static Declaration d(String property, State<? extends CssValue> value) { return Declaration.of(property, value); }

    // visibility
    public static Declaration visibility(Visibility v) { return d("visibility", v); }
    // accent-color
    public static Declaration accentColor(Value<Color> v) { return d("accent-color", v); }
    // object-fit
    public static Declaration objectFit(ObjectFit v) { return d("object-fit", v); }
    // object-position
    public static Declaration objectPosition(Raw v) { return d("object-position", v); }
    // background-color
    public static Declaration backgroundColor(Value<Color> v) { return d("background-color", v); }
    public static Declaration backgroundColor(State<? extends Value<Color>> v) { return d("background-color", v); }
    // background-image
    public static Declaration backgroundImage(Raw v) { return d("background-image", v); }
    // background-position
    public static Declaration backgroundPosition(Raw v) { return d("background-position", v); }
    // background-size
    public static Declaration backgroundSize(Raw v) { return d("background-size", v); }
    // background-repeat
    public static Declaration backgroundRepeat(Raw v) { return d("background-repeat", v); }
    // background-clip
    public static Declaration backgroundClip(Raw v) { return d("background-clip", v); }
    // background-origin
    public static Declaration backgroundOrigin(Raw v) { return d("background-origin", v); }
    // background-attachment
    public static Declaration backgroundAttachment(Raw v) { return d("background-attachment", v); }
    // background（ショートハンド）
    public static Declaration background(Value<Color> v) { return d("background", v); }
    public static Declaration background(State<? extends Value<Color>> v) { return d("background", v); }
    public static Declaration background(Raw v) { return d("background", v); }
    // box-shadow
    public static Declaration boxShadow(Raw v) { return d("box-shadow", v); }
    // opacity
    public static Declaration opacity(double v) { return d("opacity", CssValues.num(v)); }
    // transform
    public static Declaration transform(Raw v) { return d("transform", v); }
    // transform-origin
    public static Declaration transformOrigin(Raw v) { return d("transform-origin", v); }
    // rotate
    public static Declaration rotate(Raw v) { return d("rotate", v); }
    // translate
    public static Declaration translate(Raw v) { return d("translate", v); }
    // scale
    public static Declaration scale(Raw v) { return d("scale", v); }
    // perspective
    public static Declaration perspective(Raw v) { return d("perspective", v); }
    // filter
    public static Declaration filter(Raw v) { return d("filter", v); }
    // mix-blend-mode
    public static Declaration mixBlendMode(MixBlendMode v) { return d("mix-blend-mode", v); }
    // isolation
    public static Declaration isolation(Isolation v) { return d("isolation", v); }
    // clip-path
    public static Declaration clipPath(Raw v) { return d("clip-path", v); }
    // backdrop-filter
    public static Declaration backdropFilter(Raw v) { return d("backdrop-filter", v); }
    // mask-image
    public static Declaration maskImage(Raw v) { return d("mask-image", v); }
    // mask-position
    public static Declaration maskPosition(Raw v) { return d("mask-position", v); }
    // mask-size
    public static Declaration maskSize(Raw v) { return d("mask-size", v); }
    // mask-repeat
    public static Declaration maskRepeat(Raw v) { return d("mask-repeat", v); }
    // mask-origin
    public static Declaration maskOrigin(Raw v) { return d("mask-origin", v); }
    // mask-clip
    public static Declaration maskClip(Raw v) { return d("mask-clip", v); }
    // mask-mode
    public static Declaration maskMode(Raw v) { return d("mask-mode", v); }
    // mask-composite
    public static Declaration maskComposite(Raw v) { return d("mask-composite", v); }
    // mask（ショートハンド）
    public static Declaration mask(Raw v) { return d("mask", v); }
}
