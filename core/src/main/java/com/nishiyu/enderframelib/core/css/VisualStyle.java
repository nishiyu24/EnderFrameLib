package com.nishiyu.enderframelib.core.css;

/**
 * 生成物（{@code scripts/GenStyleGroups.java}。手で直さない）: 継承されないプロパティの型付き不変構造体。
 * 値は構築時に 1 回だけ解いた型付きのフィールドで、文字列を読み直さない。{@link #equals} は全フィールドの値等価。
 */
public final class VisualStyle {
    private static final int ID_borderTopColor = ComputedStyle.idOf("border-top-color");
    private static final int ID_borderRightColor = ComputedStyle.idOf("border-right-color");
    private static final int ID_borderBottomColor = ComputedStyle.idOf("border-bottom-color");
    private static final int ID_borderLeftColor = ComputedStyle.idOf("border-left-color");
    private static final int ID_borderTopLeftRadius = ComputedStyle.idOf("border-top-left-radius");
    private static final int ID_borderTopRightRadius = ComputedStyle.idOf("border-top-right-radius");
    private static final int ID_borderBottomRightRadius = ComputedStyle.idOf("border-bottom-right-radius");
    private static final int ID_borderBottomLeftRadius = ComputedStyle.idOf("border-bottom-left-radius");
    private static final int ID_outlineColor = ComputedStyle.idOf("outline-color");
    private static final int ID_outlineStyle = ComputedStyle.idOf("outline-style");
    private static final int ID_outlineWidth = ComputedStyle.idOf("outline-width");
    private static final int ID_outlineOffset = ComputedStyle.idOf("outline-offset");
    private static final int ID_objectPosition = ComputedStyle.idOf("object-position");
    private static final int ID_backgroundColor = ComputedStyle.idOf("background-color");
    private static final int ID_backgroundImage = ComputedStyle.idOf("background-image");
    private static final int ID_backgroundPosition = ComputedStyle.idOf("background-position");
    private static final int ID_backgroundSize = ComputedStyle.idOf("background-size");
    private static final int ID_backgroundRepeat = ComputedStyle.idOf("background-repeat");
    private static final int ID_backgroundClip = ComputedStyle.idOf("background-clip");
    private static final int ID_backgroundOrigin = ComputedStyle.idOf("background-origin");
    private static final int ID_backgroundAttachment = ComputedStyle.idOf("background-attachment");
    private static final int ID_boxShadow = ComputedStyle.idOf("box-shadow");
    private static final int ID_borderImageSource = ComputedStyle.idOf("border-image-source");
    private static final int ID_borderImageSlice = ComputedStyle.idOf("border-image-slice");
    private static final int ID_borderImageWidth = ComputedStyle.idOf("border-image-width");
    private static final int ID_borderImageOutset = ComputedStyle.idOf("border-image-outset");
    private static final int ID_borderImageRepeat = ComputedStyle.idOf("border-image-repeat");
    private static final int ID_opacity = ComputedStyle.idOf("opacity");
    private static final int ID_transform = ComputedStyle.idOf("transform");
    private static final int ID_transformOrigin = ComputedStyle.idOf("transform-origin");
    private static final int ID_rotate = ComputedStyle.idOf("rotate");
    private static final int ID_translate = ComputedStyle.idOf("translate");
    private static final int ID_scale = ComputedStyle.idOf("scale");
    private static final int ID_filter = ComputedStyle.idOf("filter");
    private static final int ID_mixBlendMode = ComputedStyle.idOf("mix-blend-mode");
    private static final int ID_isolation = ComputedStyle.idOf("isolation");
    private static final int ID_clipPath = ComputedStyle.idOf("clip-path");
    private static final int ID_backdropFilter = ComputedStyle.idOf("backdrop-filter");
    private static final int ID_maskImage = ComputedStyle.idOf("mask-image");
    private static final int ID_maskPosition = ComputedStyle.idOf("mask-position");
    private static final int ID_maskSize = ComputedStyle.idOf("mask-size");
    private static final int ID_maskRepeat = ComputedStyle.idOf("mask-repeat");
    private static final int ID_maskOrigin = ComputedStyle.idOf("mask-origin");
    private static final int ID_maskClip = ComputedStyle.idOf("mask-clip");
    private static final int ID_maskMode = ComputedStyle.idOf("mask-mode");
    private static final int ID_maskComposite = ComputedStyle.idOf("mask-composite");

    /** 宣言が 1 つも無いときの共有インスタンス（初期値だけ）。ID_* の初期化より後に置く。 */
    public static final VisualStyle DEFAULT = new VisualStyle(null);

    /** {@code calc()} 等の式の表。式を持たない値は共有の空表。 */
    public final CssExprs exprs;
    /** {@code border-top-color}。 */
    public final int borderTopColor;
    /** {@code border-right-color}。 */
    public final int borderRightColor;
    /** {@code border-bottom-color}。 */
    public final int borderBottomColor;
    /** {@code border-left-color}。 */
    public final int borderLeftColor;
    /** {@code border-top-left-radius}。 */
    public final long borderTopLeftRadius;
    /** {@code border-top-right-radius}。 */
    public final long borderTopRightRadius;
    /** {@code border-bottom-right-radius}。 */
    public final long borderBottomRightRadius;
    /** {@code border-bottom-left-radius}。 */
    public final long borderBottomLeftRadius;
    /** {@code outline-color}。 */
    public final int outlineColor;
    /** {@code outline-style}。 */
    public final com.nishiyu.enderframelib.core.css.model.BorderStyle outlineStyle;
    /** {@code outline-width}。 */
    public final long outlineWidth;
    /** {@code outline-offset}。 */
    public final long outlineOffset;
    /** {@code object-position}。 */
    public final String objectPosition;
    /** {@code background-color}。 */
    public final int backgroundColor;
    /** {@code background-image}。 */
    public final String backgroundImage;
    /** {@code background-position}。 */
    public final String backgroundPosition;
    /** {@code background-size}。 */
    public final String backgroundSize;
    /** {@code background-repeat}。 */
    public final String backgroundRepeat;
    /** {@code background-clip}。 */
    public final String backgroundClip;
    /** {@code background-origin}。 */
    public final String backgroundOrigin;
    /** {@code background-attachment}。 */
    public final String backgroundAttachment;
    /** {@code box-shadow}。 */
    public final String boxShadow;
    /** {@code border-image-source}。 */
    public final String borderImageSource;
    /** {@code border-image-slice}。 */
    public final String borderImageSlice;
    /** {@code border-image-width}。 */
    public final String borderImageWidth;
    /** {@code border-image-outset}。 */
    public final String borderImageOutset;
    /** {@code border-image-repeat}。 */
    public final String borderImageRepeat;
    /** {@code opacity}。 */
    public final float opacity;
    /** {@code transform}。 */
    public final String transform;
    /** {@code transform-origin}。 */
    public final String transformOrigin;
    /** {@code rotate}。 */
    public final String rotate;
    /** {@code translate}。 */
    public final String translate;
    /** {@code scale}。 */
    public final String scale;
    /** {@code filter}。 */
    public final String filter;
    /** {@code mix-blend-mode}。 */
    public final String mixBlendMode;
    /** {@code isolation}。 */
    public final com.nishiyu.enderframelib.core.css.model.CssKeywords.Isolation isolation;
    /** {@code clip-path}。 */
    public final String clipPath;
    /** {@code backdrop-filter}。 */
    public final String backdropFilter;
    /** {@code mask-image}。 */
    public final String maskImage;
    /** {@code mask-position}。 */
    public final String maskPosition;
    /** {@code mask-size}。 */
    public final String maskSize;
    /** {@code mask-repeat}。 */
    public final String maskRepeat;
    /** {@code mask-origin}。 */
    public final String maskOrigin;
    /** {@code mask-clip}。 */
    public final String maskClip;
    /** {@code mask-mode}。 */
    public final String maskMode;
    /** {@code mask-composite}。 */
    public final String maskComposite;

    private VisualStyle(ComputedStyle cs) {
        CssExprs.Builder ex = new CssExprs.Builder();
        this.borderTopColor = StyleReader.color(cs, ID_borderTopColor);
        this.borderRightColor = StyleReader.color(cs, ID_borderRightColor);
        this.borderBottomColor = StyleReader.color(cs, ID_borderBottomColor);
        this.borderLeftColor = StyleReader.color(cs, ID_borderLeftColor);
        this.borderTopLeftRadius = StyleReader.length(cs, ID_borderTopLeftRadius, ex);
        this.borderTopRightRadius = StyleReader.length(cs, ID_borderTopRightRadius, ex);
        this.borderBottomRightRadius = StyleReader.length(cs, ID_borderBottomRightRadius, ex);
        this.borderBottomLeftRadius = StyleReader.length(cs, ID_borderBottomLeftRadius, ex);
        this.outlineColor = StyleReader.color(cs, ID_outlineColor);
        this.outlineStyle = StyleReader.keyword(cs, ID_outlineStyle, com.nishiyu.enderframelib.core.css.model.BorderStyle.class);
        this.outlineWidth = StyleReader.length(cs, ID_outlineWidth, ex);
        this.outlineOffset = StyleReader.length(cs, ID_outlineOffset, ex);
        this.objectPosition = StyleReader.text(cs, ID_objectPosition);
        this.backgroundColor = StyleReader.color(cs, ID_backgroundColor);
        this.backgroundImage = StyleReader.text(cs, ID_backgroundImage);
        this.backgroundPosition = StyleReader.text(cs, ID_backgroundPosition);
        this.backgroundSize = StyleReader.text(cs, ID_backgroundSize);
        this.backgroundRepeat = StyleReader.text(cs, ID_backgroundRepeat);
        this.backgroundClip = StyleReader.text(cs, ID_backgroundClip);
        this.backgroundOrigin = StyleReader.text(cs, ID_backgroundOrigin);
        this.backgroundAttachment = StyleReader.text(cs, ID_backgroundAttachment);
        this.boxShadow = StyleReader.text(cs, ID_boxShadow);
        this.borderImageSource = StyleReader.text(cs, ID_borderImageSource);
        this.borderImageSlice = StyleReader.text(cs, ID_borderImageSlice);
        this.borderImageWidth = StyleReader.text(cs, ID_borderImageWidth);
        this.borderImageOutset = StyleReader.text(cs, ID_borderImageOutset);
        this.borderImageRepeat = StyleReader.text(cs, ID_borderImageRepeat);
        this.opacity = StyleReader.number(cs, ID_opacity);
        this.transform = StyleReader.text(cs, ID_transform);
        this.transformOrigin = StyleReader.text(cs, ID_transformOrigin);
        this.rotate = StyleReader.text(cs, ID_rotate);
        this.translate = StyleReader.text(cs, ID_translate);
        this.scale = StyleReader.text(cs, ID_scale);
        this.filter = StyleReader.text(cs, ID_filter);
        this.mixBlendMode = StyleReader.text(cs, ID_mixBlendMode);
        this.isolation = StyleReader.keyword(cs, ID_isolation, com.nishiyu.enderframelib.core.css.model.CssKeywords.Isolation.class);
        this.clipPath = StyleReader.text(cs, ID_clipPath);
        this.backdropFilter = StyleReader.text(cs, ID_backdropFilter);
        this.maskImage = StyleReader.text(cs, ID_maskImage);
        this.maskPosition = StyleReader.text(cs, ID_maskPosition);
        this.maskSize = StyleReader.text(cs, ID_maskSize);
        this.maskRepeat = StyleReader.text(cs, ID_maskRepeat);
        this.maskOrigin = StyleReader.text(cs, ID_maskOrigin);
        this.maskClip = StyleReader.text(cs, ID_maskClip);
        this.maskMode = StyleReader.text(cs, ID_maskMode);
        this.maskComposite = StyleReader.text(cs, ID_maskComposite);
        this.exprs = ex.build();
    }

    /** 算出スタイルから、この群の値を取り出す。 */
    public static VisualStyle of(ComputedStyle cs) {
        return new VisualStyle(cs);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof VisualStyle o)) return false;
        return exprs.equals(o.exprs)
                && borderTopColor == o.borderTopColor
                && borderRightColor == o.borderRightColor
                && borderBottomColor == o.borderBottomColor
                && borderLeftColor == o.borderLeftColor
                && borderTopLeftRadius == o.borderTopLeftRadius
                && borderTopRightRadius == o.borderTopRightRadius
                && borderBottomRightRadius == o.borderBottomRightRadius
                && borderBottomLeftRadius == o.borderBottomLeftRadius
                && outlineColor == o.outlineColor
                && outlineStyle == o.outlineStyle
                && outlineWidth == o.outlineWidth
                && outlineOffset == o.outlineOffset
                && java.util.Objects.equals(objectPosition, o.objectPosition)
                && backgroundColor == o.backgroundColor
                && java.util.Objects.equals(backgroundImage, o.backgroundImage)
                && java.util.Objects.equals(backgroundPosition, o.backgroundPosition)
                && java.util.Objects.equals(backgroundSize, o.backgroundSize)
                && java.util.Objects.equals(backgroundRepeat, o.backgroundRepeat)
                && java.util.Objects.equals(backgroundClip, o.backgroundClip)
                && java.util.Objects.equals(backgroundOrigin, o.backgroundOrigin)
                && java.util.Objects.equals(backgroundAttachment, o.backgroundAttachment)
                && java.util.Objects.equals(boxShadow, o.boxShadow)
                && java.util.Objects.equals(borderImageSource, o.borderImageSource)
                && java.util.Objects.equals(borderImageSlice, o.borderImageSlice)
                && java.util.Objects.equals(borderImageWidth, o.borderImageWidth)
                && java.util.Objects.equals(borderImageOutset, o.borderImageOutset)
                && java.util.Objects.equals(borderImageRepeat, o.borderImageRepeat)
                && Float.floatToIntBits(opacity) == Float.floatToIntBits(o.opacity)
                && java.util.Objects.equals(transform, o.transform)
                && java.util.Objects.equals(transformOrigin, o.transformOrigin)
                && java.util.Objects.equals(rotate, o.rotate)
                && java.util.Objects.equals(translate, o.translate)
                && java.util.Objects.equals(scale, o.scale)
                && java.util.Objects.equals(filter, o.filter)
                && java.util.Objects.equals(mixBlendMode, o.mixBlendMode)
                && isolation == o.isolation
                && java.util.Objects.equals(clipPath, o.clipPath)
                && java.util.Objects.equals(backdropFilter, o.backdropFilter)
                && java.util.Objects.equals(maskImage, o.maskImage)
                && java.util.Objects.equals(maskPosition, o.maskPosition)
                && java.util.Objects.equals(maskSize, o.maskSize)
                && java.util.Objects.equals(maskRepeat, o.maskRepeat)
                && java.util.Objects.equals(maskOrigin, o.maskOrigin)
                && java.util.Objects.equals(maskClip, o.maskClip)
                && java.util.Objects.equals(maskMode, o.maskMode)
                && java.util.Objects.equals(maskComposite, o.maskComposite);
    }

    @Override
    public int hashCode() {
        int h = 46;
        h = h * 31 + borderTopColor;
        h = h * 31 + borderRightColor;
        h = h * 31 + borderBottomColor;
        h = h * 31 + borderLeftColor;
        h = h * 31 + Long.hashCode(borderTopLeftRadius);
        h = h * 31 + Long.hashCode(borderTopRightRadius);
        h = h * 31 + Long.hashCode(borderBottomRightRadius);
        h = h * 31 + Long.hashCode(borderBottomLeftRadius);
        h = h * 31 + outlineColor;
        h = h * 31 + System.identityHashCode(outlineStyle);
        h = h * 31 + Long.hashCode(outlineWidth);
        h = h * 31 + Long.hashCode(outlineOffset);
        h = h * 31 + java.util.Objects.hashCode(objectPosition);
        h = h * 31 + backgroundColor;
        h = h * 31 + java.util.Objects.hashCode(backgroundImage);
        h = h * 31 + java.util.Objects.hashCode(backgroundPosition);
        h = h * 31 + java.util.Objects.hashCode(backgroundSize);
        h = h * 31 + java.util.Objects.hashCode(backgroundRepeat);
        h = h * 31 + java.util.Objects.hashCode(backgroundClip);
        h = h * 31 + java.util.Objects.hashCode(backgroundOrigin);
        h = h * 31 + java.util.Objects.hashCode(backgroundAttachment);
        h = h * 31 + java.util.Objects.hashCode(boxShadow);
        h = h * 31 + java.util.Objects.hashCode(borderImageSource);
        h = h * 31 + java.util.Objects.hashCode(borderImageSlice);
        h = h * 31 + java.util.Objects.hashCode(borderImageWidth);
        h = h * 31 + java.util.Objects.hashCode(borderImageOutset);
        h = h * 31 + java.util.Objects.hashCode(borderImageRepeat);
        h = h * 31 + Float.floatToIntBits(opacity);
        h = h * 31 + java.util.Objects.hashCode(transform);
        h = h * 31 + java.util.Objects.hashCode(transformOrigin);
        h = h * 31 + java.util.Objects.hashCode(rotate);
        h = h * 31 + java.util.Objects.hashCode(translate);
        h = h * 31 + java.util.Objects.hashCode(scale);
        h = h * 31 + java.util.Objects.hashCode(filter);
        h = h * 31 + java.util.Objects.hashCode(mixBlendMode);
        h = h * 31 + System.identityHashCode(isolation);
        h = h * 31 + java.util.Objects.hashCode(clipPath);
        h = h * 31 + java.util.Objects.hashCode(backdropFilter);
        h = h * 31 + java.util.Objects.hashCode(maskImage);
        h = h * 31 + java.util.Objects.hashCode(maskPosition);
        h = h * 31 + java.util.Objects.hashCode(maskSize);
        h = h * 31 + java.util.Objects.hashCode(maskRepeat);
        h = h * 31 + java.util.Objects.hashCode(maskOrigin);
        h = h * 31 + java.util.Objects.hashCode(maskClip);
        h = h * 31 + java.util.Objects.hashCode(maskMode);
        h = h * 31 + java.util.Objects.hashCode(maskComposite);
        return h;
    }
}
