package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.CssPropertyIndex;
import com.nishiyu.enderframelib.core.css.StyleFacts;
import com.nishiyu.enderframelib.core.layout.model.*;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.model.CssKeywords;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import com.nishiyu.enderframelib.core.text.model.FontStyle;
import com.nishiyu.enderframelib.core.text.model.TextStyle;
import com.nishiyu.enderframelib.core.text.model.WhiteSpace;
import com.nishiyu.enderframelib.core.text.model.WordBreak;

import java.util.function.Consumer;

/**
 * レイアウト 1 回分の外部依存。
 *
 * @param reuseMeasurements 前回の measure の結果を、入力と中身が変わっていない箱で使い回してよいか
 *                          （差分レイアウト。{@link LayoutBox#measure(ConstraintSpace, LayoutContext)} を参照）
 * @param layoutRun         このレイアウト（measure の全パスと layout）の通し番号
 * @param firstPass         measure の 1 回目のパスか。2 回目以降は、同じレイアウトの中で記録した結果を使い回さない
 *                          （表の枠線の分担のように、1 回目の結果を読んで 2 回目が変わる箱があるため）
 * @param autoVisibility    {@code content-visibility: auto} の可視範囲・遅延子の具現化セッション（不要なら {@code null}）
 */
public record LayoutContext(
        Viewport viewport,
        IntrinsicSizeResolver intrinsicResolver,
        ScrollState scrollState,
        FontResolver fonts,
        boolean reuseMeasurements,
        long layoutRun,
        boolean firstPass,
        AutoVisibilitySession autoVisibility
) {

    /**
     * 並列ワーカー向けに {@link AutoVisibilitySession} を外した {@link LayoutContext} を返す。
     * {@code content-visibility: auto} を持たない独立部分木でのみ使う。
     */
    public LayoutContext withoutAutoVisibility() {
        return autoVisibility == null ? this
                : new LayoutContext(viewport, intrinsicResolver, scrollState, fonts,
                        reuseMeasurements, layoutRun, firstPass, null);
    }

    /**
     * {@code content-visibility: auto} の可視判定と遅延サブツリー具現化を 1 回のレイアウト中で共有する状態。
     */
    public static final class AutoVisibilitySession {
        /** 可視判定の近傍マージン（UI スクロールの 1 ステップ翻訳幅 576px を包含する 640px）。 */
        public static final float CONTENT_VISIBILITY_MARGIN = 640f;

        private float clipLeft;
        private float clipTop;
        private float clipRight;
        private float clipBottom;
        private final Consumer<LayoutBox> materializer;
        private boolean nonVirtualVisibilityChanged;
        private boolean anyContentAuto;

        public AutoVisibilitySession(float viewportW, float viewportH, Consumer<LayoutBox> materializer) {
            this.clipLeft = 0f;
            this.clipTop = 0f;
            this.clipRight = Math.max(0f, viewportW);
            this.clipBottom = Math.max(0f, viewportH);
            this.materializer = materializer;
        }

        public float clipLeft() { return clipLeft; }
        public float clipTop() { return clipTop; }
        public float clipRight() { return clipRight; }
        public float clipBottom() { return clipBottom; }

        public void setClip(float left, float top, float right, float bottom) {
            this.clipLeft = left;
            this.clipTop = top;
            this.clipRight = right;
            this.clipBottom = bottom;
        }

        public boolean isNearViewport(float x, float y, float w, float h) {
            float m = CONTENT_VISIBILITY_MARGIN;
            float r = x + Math.max(0f, w);
            float b = y + Math.max(0f, h);
            return r >= clipLeft - m && x <= clipRight + m
                    && b >= clipTop - m && y <= clipBottom + m;
        }

        public boolean isVerticalBandNearViewport(float top, float bottom) {
            float m = CONTENT_VISIBILITY_MARGIN;
            return bottom >= clipTop - m && top <= clipBottom + m;
        }

        public void materialize(LayoutBox box) {
            if (materializer != null && box.childrenDeferred) {
                materializer.accept(box);
            }
        }

        public void noteNonVirtualVisibilityChanged() {
            nonVirtualVisibilityChanged = true;
        }

        public boolean consumeNonVirtualVisibilityChanged() {
            boolean was = nonVirtualVisibilityChanged;
            nonVirtualVisibilityChanged = false;
            return was;
        }

        public void noteContentAuto() {
            anyContentAuto = true;
        }

        public boolean hasContentAuto() {
            return anyContentAuto;
        }
    }

    /** measure の結果を使い回さない（何もかも測り直す）文脈。 */
    public LayoutContext(Viewport viewport, IntrinsicSizeResolver intrinsicResolver,
                         ScrollState scrollState, FontResolver fonts) {
        this(viewport, intrinsicResolver, scrollState, fonts, false, 0L, true, null);
    }

    /** 後方互換の 7 引数コンストラクタ（{@code autoVisibility = null}）。 */
    public LayoutContext(Viewport viewport, IntrinsicSizeResolver intrinsicResolver,
                         ScrollState scrollState, FontResolver fonts,
                         boolean reuseMeasurements, long layoutRun, boolean firstPass) {
        this(viewport, intrinsicResolver, scrollState, fonts, reuseMeasurements, layoutRun, firstPass, null);
    }

    public float visibilityClipTop() {
        return autoVisibility != null ? autoVisibility.clipTop() : 0f;
    }

    public float visibilityClipBottom() {
        return autoVisibility != null ? autoVisibility.clipBottom() : (viewport != null ? viewport.rootH() : 0f);
    }

    public void materializeAutoBox(LayoutBox box) {
        if (autoVisibility != null) autoVisibility.materialize(box);
    }

    // テキストの測定ごとに引くプロパティは、名前ではなく添字で引く
    private static final int ID_FONT_FAMILY = CssPropertyIndex.FONT_FAMILY;
    private static final int ID_FONT_STYLE = CssPropertyIndex.FONT_STYLE;
    private static final int ID_LETTER_SPACING = CssPropertyIndex.LETTER_SPACING;
    private static final int ID_FONT_KERNING = CssPropertyIndex.FONT_KERNING;
    private static final int ID_OVERFLOW_WRAP = CssPropertyIndex.OVERFLOW_WRAP;
    private static final int ID_WHITE_SPACE = CssPropertyIndex.WHITE_SPACE;
    private static final int ID_WORD_BREAK = CssPropertyIndex.WORD_BREAK;
    private static final int ID_TEXT_OVERFLOW = CssPropertyIndex.TEXT_OVERFLOW;

    /** 算出スタイルの {@code font-family} に対応する書体。 */
    public FontFace faceFor(ComputedStyle style) {
        // 解決器が世代を持つなら、算出スタイルに覚えさせる（全行の測り直しで、文字列の鍵で表を引く費用が効いていた。B11）
        long gen = fonts.resolveGeneration();
        if (gen < 0) return fonts.resolve(style.typed().inherited.fontFamily);
        FontFace face = style.peekFace(fonts, gen);
        if (face != null) return face;
        face = fonts.resolve(style.typed().inherited.fontFamily);
        style.cacheFace(fonts, gen, face);
        return face;
    }

    /** 算出スタイルから測定に必要な指定だけを取り出す。 */
    public FontStyle fontStyleFor(ComputedStyle style) {
        return style.facts().fontStyle;
    }

    /** 宣言が {@code none}（前後の空白・大小文字は無視）か。宣言が無ければ偽（既定は auto）。 */
    private static boolean isNone(String value) {
        return value != null && "none".equalsIgnoreCase(value.trim());
    }

    /** 整形（折り返し・空白・省略）に必要なスタイルをまとめる。 */
    public TextStyle textStyleFor(ComputedStyle style) {
        FontFace face = faceFor(style);
        TextStyle cached = style.peekTextStyle(face);
        if (cached != null) return cached;
        StyleFacts facts = style.facts();
        FontStyle font = facts.fontStyle;
        TextStyle built = new TextStyle(font, facts.whiteSpace, facts.wordBreak, facts.overflowWrapBreaks, facts.ellipsis,
                LayoutMetrics.lineHeightPx(style, face, font));
        style.cacheTextStyle(face, built);
        return built;
    }
}
