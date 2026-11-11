package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.css.model.*;
import com.nishiyu.enderframelib.core.css.CssPropertyIndex;
import com.nishiyu.enderframelib.core.css.ColorParser;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.model.Keyframes;
import com.nishiyu.enderframelib.core.css.model.KeyframeBlock;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.CssSyntax;
import com.nishiyu.enderframelib.core.css.model.EasingFunction;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.css.AnimationParser;
import com.nishiyu.enderframelib.core.css.model.AnimationTiming;
import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;

/** 箱の {@code transform}・{@code transition}・{@code animation} を、描画項目が運ぶ値（{@link AnimationSpec} など）に解く。 */
final class BoxAnimation {
    private int rootW;
    private int rootH;
    private final Map<String, Keyframes> keyframes;

    void setViewport(int rootW, int rootH) {
        this.rootW = rootW;
        this.rootH = rootH;
    }

    BoxAnimation(int rootW, int rootH, Map<String, Keyframes> keyframes) {
        this.rootW = rootW;
        this.rootH = rootH;
        this.keyframes = keyframes;
    }

    static final Set<String> EASING_KEYWORDS = Set.of(
            "ease", "linear", "ease-in", "ease-out", "ease-in-out"
    );

    /**
     * {@code transform-origin} の 1 語を、箱の寸法（{@code size}）に対する割合にする。キーワード・割合・長さ（{@code 0} / {@code px} / {@code em} /
     * {@code rem}）を読む。以前は長さを読めず、{@code transform-origin: 0 0} が中心（50%）になっていた。
     */
    static float parseOriginProp(String p, float size, float fontPx, float rootFontPx) {
        float length = Float.NaN;
        if ("0".equals(p)) length = 0f;
        else if (p.endsWith("rem")) length = originNumber(p, 3) * rootFontPx;
        else if (p.endsWith("em")) length = originNumber(p, 2) * fontPx;
        else if (p.endsWith("px")) length = originNumber(p, 2);
        if (!Float.isNaN(length)) return size > 0 ? length / size : 0f;
        return parseOriginProp(p);
    }

    private static float originNumber(String p, int unitLength) {
        try {
            return Float.parseFloat(p.substring(0, p.length() - unitLength));
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    static float parseOriginProp(String p) {
        return switch (p) {
            case "left", "top" -> 0.0f;
            case "right", "bottom" -> 1.0f;
            case "center" -> 0.5f;
            default -> {
                if (p.endsWith("%")) {
                    try {
                        yield Float.parseFloat(p.substring(0, p.length() - 1)) / 100f;
                    } catch (NumberFormatException ignored) {}
                }
                yield 0.5f;
            }
        };
    }

    /** {@code transform} の値を {@code {tx, ty, sx, sy, rot}} にする（既定値は無指定のチャネル用）。 */
    static float[] parseTransformProps(String t, float boxW, float boxH, float defTx, float defTy, float defSx, float defSy, float defRot) {
        if (t == null || t.isEmpty()) return new float[]{defTx, defTy, defSx, defSy, defRot, 0f};
        return Transforms.toTrs(Transforms.parse(t, new Transforms.Context(boxW, boxH, 0f, 0f, 16f, 16f)));
    }

    /** キーフレームの宣言（{@code transform} と個別の {@code translate} / {@code rotate} / {@code scale}）を {@code {tx, ty, sx, sy, rot, skew}} にする。 */
    static float[] parseKeyframeTransform(Map<String, String> decls, float boxW, float boxH) {
        Transforms.Context ctx = new Transforms.Context(boxW, boxH, 0f, 0f, 16f, 16f);
        return Transforms.toTrs(Transforms.compose(decls.get("translate"), decls.get("rotate"), decls.get("scale"), decls.get(CssProperty.TRANSFORM), ctx));
    }

    /** 箱の算出値の {@code translate} / {@code rotate} / {@code scale} / {@code transform} を宣言順に合成して {@code {tx, ty, sx, sy, rot}} にする。 */
    float[] transformOf(LayoutBox box) {
        Transforms.Context ctx = new Transforms.Context(box.w, box.h, rootW, rootH, box.style.fontSizePx(), box.style.rootFontSizePx());
        return Transforms.toTrs(Transforms.compose(box.style.get(CssPropertyIndex.TRANSLATE), box.style.get(CssPropertyIndex.ROTATE), box.style.get(CssPropertyIndex.SCALE),
                box.style.get(CssPropertyIndex.TRANSFORM), ctx));
    }

    /**
     * CSS の {@code animation} ショートハンド・各ロングハンドと {@code @keyframes} を
     * {@link AnimationSpec} に解決する。
     */
    AnimationSpec parseAnimationDef(ComputedStyle style, float baseW, float baseH, int baseColor, int baseBgColor) {
        // 結果はスタイルと（@keyframes・基準の大きさと色）だけで決まるので、スタイルの置き場に覚える。
        // 描画のたびに全箱の animation を字句解析し直していた（表示リスト作りの 3 割）
        if (NO_MEMO) return resolveAnimationDef(style, baseW, baseH, baseColor, baseBgColor);
        Object memoKey = new AnimKey(keyframes, keyframes.size(), baseW, baseH, baseColor, baseBgColor);
        return style.paintMemo(memoKey, k -> resolveAnimationDef(style, baseW, baseH, baseColor, baseBgColor));
    }

    /** 切り分け用: {@code -Denderframe.paint.nomemo=true} でメモを使わない。 */
    private static final boolean NO_MEMO = Boolean.getBoolean("enderframe.paint.nomemo");

    /** {@link #parseAnimationDef} のメモのキー。{@code @keyframes} の表は同一性で比べる（中身の比較は重い）。 */
    private static final class AnimKey {
        private final Object keyframes;
        private final int keyframeCount;
        private final float w, h;
        private final int color, bg;

        AnimKey(Object keyframes, int keyframeCount, float w, float h, int color, int bg) {
            this.keyframes = keyframes;
            this.keyframeCount = keyframeCount;
            this.w = w;
            this.h = h;
            this.color = color;
            this.bg = bg;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof AnimKey k && k.keyframes == keyframes && k.keyframeCount == keyframeCount
                    && Float.compare(k.w, w) == 0 && Float.compare(k.h, h) == 0 && k.color == color && k.bg == bg;
        }

        @Override
        public int hashCode() {
            int hash = System.identityHashCode(keyframes);
            hash = 31 * hash + keyframeCount;
            hash = 31 * hash + Float.floatToIntBits(w);
            hash = 31 * hash + Float.floatToIntBits(h);
            hash = 31 * hash + color;
            return 31 * hash + bg;
        }
    }

    private AnimationSpec resolveAnimationDef(ComputedStyle style, float baseW, float baseH, int baseColor, int baseBgColor) {
        AnimationTiming timing = AnimationParser.parse(style, keyframes::containsKey);
        if (timing == null) return AnimationSpec.NONE;
        String name = timing.name();

        List<AnimationSpec.Keyframe> frames = new ArrayList<>();
        int animatedChannels = 0;

        for (KeyframeBlock block : keyframes.get(name).blocks()) {
            float pct = block.percent();
            Map<String, String> decls = block.declarations();
            int definedChannels = 0;

            float[] tr = new float[]{0f, 0f, 1f, 1f, 0f, 0f};
            if (decls.containsKey(CssProperty.TRANSFORM) || decls.containsKey("translate") || decls.containsKey("rotate") || decls.containsKey("scale")) {
                tr = parseKeyframeTransform(decls, baseW, baseH);
                definedChannels |= AnimationSpec.CH_TRANSFORM;
            }

            float op = 1f;
            if (decls.containsKey(CssProperty.OPACITY)) {
                try {
                    op = Float.parseFloat(decls.get(CssProperty.OPACITY).trim());
                    definedChannels |= AnimationSpec.CH_OPACITY;
                } catch (NumberFormatException ignored) {}
            }

            int col = baseColor;
            if (decls.containsKey(CssProperty.COLOR)) {
                // transparent（0x00000000）もキーフレームの色として有効
                if (ColorParser.isColor(decls.get(CssProperty.COLOR))) {
                    col = ColorParser.parseColor(decls.get(CssProperty.COLOR));
                    definedChannels |= AnimationSpec.CH_COLOR;
                }
            }

            int bgCol = baseBgColor;
            String bgDecl = decls.getOrDefault(CssProperty.BACKGROUND_COLOR, decls.get(CssProperty.BACKGROUND));
            if (bgDecl != null) {
                bgCol = ColorParser.parseColor(bgDecl);
                definedChannels |= AnimationSpec.CH_BG_COLOR;
            }

            animatedChannels |= definedChannels;
            frames.add(new AnimationSpec.Keyframe(pct, tr[0], tr[1], tr[2], tr[3], tr[4], tr[5], op, col, bgCol, definedChannels));
        }

        if (frames.isEmpty() || animatedChannels == 0) return AnimationSpec.NONE;
        // 0%（from）か 100%（to）が書かれていなければ、要素自身の元の値がその端（CSS Animations 1 §4.2）。
        // {@code @keyframes spin { to { transform: rotate(360deg) } }} は、元の姿勢から 360° まで回る
        boolean hasStart = false, hasEnd = false;
        for (AnimationSpec.Keyframe f : frames) {
            hasStart |= f.pct() <= 0f;
            hasEnd |= f.pct() >= 100f;
        }
        if (!hasStart || !hasEnd) {
            Transforms.Context ctx = new Transforms.Context(baseW, baseH, rootW, rootH, style.fontSizePx(), style.rootFontSizePx());
            float[] base = java.util.Arrays.copyOf(Transforms.toTrs(Transforms.compose(style.get(CssPropertyIndex.TRANSLATE), style.get(CssPropertyIndex.ROTATE),
                    style.get(CssPropertyIndex.SCALE), style.get(CssPropertyIndex.TRANSFORM), ctx)), 6);
            if (base[2] == 0f && base[3] == 0f) { base[2] = 1f; base[3] = 1f; }
            float baseOpacity = style.number(CssPropertyIndex.OPACITY, 1f);
            if (!hasStart) {
                frames.add(new AnimationSpec.Keyframe(0f, base[0], base[1], base[2], base[3], base[4], base[5], baseOpacity,
                        baseColor, baseBgColor, animatedChannels));
            }
            if (!hasEnd) {
                frames.add(new AnimationSpec.Keyframe(100f, base[0], base[1], base[2], base[3], base[4], base[5], baseOpacity,
                        baseColor, baseBgColor, animatedChannels));
            }
        }
        return new AnimationSpec(timing.durationMs(), timing.delayMs(), timing.iterationCount(), timing.direction(),
                timing.fillMode(), timing.easing(), animatedChannels, frames);
    }

    /**
     * CSS の {@code transition} ショートハンドとロングハンド（{@code transition-property},
     * {@code transition-duration}, {@code transition-delay}, {@code transition-timing-function}）を解決する。
     * CSS 既定のイージングは {@code ease}（{@code cubic-bezier(0.25, 0.1, 0.25, 1.0)}）。
     */
    static TransitionSpec parseTransitionSpec(Map<String, String> style) {
        int durationMs = 0;
        int delayMs = 0;
        String easing = EasingFunction.DEFAULT_EASING;
        String property = "all";

        String tr = style.get(CssProperty.TRANSITION);
        if (tr != null && !tr.isBlank()) {
            if ("none".equalsIgnoreCase(tr.trim())) {
                property = "none";
            } else {
                easing = EasingFunction.extractFromShorthand(tr);
                List<String> entries = CssSyntax.parseCommaList(tr);
                List<String> propNames = new ArrayList<>(entries.size());

                for (int idx = 0; idx < entries.size(); idx++) {
                    String entry = entries.get(idx).trim();
                    if (entry.isEmpty()) continue;
                    int entryTimeCount = 0;
                    // 関数（cubic-bezier() / steps()）は 1 つの値なので、イージングとして読み飛ばす
                    for (String word : CssSyntax.words(entry)) {
                        String lower = word.toLowerCase(Locale.ROOT);
                        if (lower.endsWith(")")) continue;
                        Integer parsedMs = AnimationParser.parseTimeMs(lower);
                        if (parsedMs != null) {
                            int ms = parsedMs;
                            if (idx == 0) {
                                if (entryTimeCount == 0) durationMs = ms;
                                else if (entryTimeCount == 1) delayMs = ms;
                            } else if (entryTimeCount == 0 && durationMs == 0) {
                                durationMs = ms;
                            }
                            entryTimeCount++;
                        } else if (!EASING_KEYWORDS.contains(lower)) {
                            propNames.add(lower);
                        }
                    }
                }
                if (!propNames.isEmpty()) {
                    property = String.join(",", propNames);
                }
            }
        }

        String tp = style.get(CssProperty.TRANSITION_PROPERTY);
        if (tp != null && !tp.isBlank()) {
            property = tp.trim().toLowerCase(Locale.ROOT);
        }

        String tdur = style.get(CssProperty.TRANSITION_DURATION);
        if (tdur != null) {
            Integer ms = AnimationParser.firstTimeMs(tdur);
            if (ms != null) durationMs = ms;
        }

        String td = style.get(CssProperty.TRANSITION_DELAY);
        if (td != null) {
            Integer ms = AnimationParser.firstTimeMs(td);
            if (ms != null) delayMs = ms;
        }

        String ttf = style.get(CssProperty.TRANSITION_TIMING_FUNCTION);
        if (ttf != null && !ttf.isBlank()) {
            easing = ttf.trim();
        }
        return new TransitionSpec(durationMs, delayMs, easing, property);
    }

    static void applyTransitionEvents(ItemProps events, TransitionSpec transSpec) {
        events.transition(transSpec.delayMs() > 0 ? transSpec.delayMs() : 0,
                EasingFunction.DEFAULT_EASING.equals(transSpec.easing()) ? null : transSpec.easing(),
                "all".equals(transSpec.property()) ? null : transSpec.property());
    }

    /**
     * 文字の色のアニメーション。{@code animation} は継承されないが、祖先の {@code color} を補間すると
     * 継承した文字の色も一緒に変わる（CSS Animations 1 §3: アニメーションした値も算出値として継承される）。
     * 途中の要素が自分の色を持っていれば、そこから先には届かない。
     *
     * 何が壊れていたか: 文字の DisplayItem には文字ノード自身のアニメーション（常に none）しか載せていなかったので、
     * {@code <span style="animation: kfTextFlash ...">} の文字色が変わらなかった。
     */
    AnimationSpec textColorAnimation(LayoutBox text, AnimationSpec own) {
        AnimationSpec colorAnim = own.withChannels(AnimationSpec.CH_COLOR);
        if (!colorAnim.isEmpty()) return colorAnim;
        String inherited = text.style.get(CssPropertyIndex.COLOR);
        for (LayoutBox a = text.parent; a != null && a.style != null; a = a.parent) {
            if (!Objects.equals(inherited, a.style.get(CssPropertyIndex.COLOR))) break;
            if (a.style.get(CssPropertyIndex.ANIMATION) != null || a.style.get(CssPropertyIndex.ANIMATION_NAME) != null) {
                int color = a.style.color(CssPropertyIndex.COLOR, 0xFF000000);
                AnimationSpec anim = parseAnimationDef(a.style, a.w, a.h, color, 0).withChannels(AnimationSpec.CH_COLOR);
                if (!anim.isEmpty()) return anim;
            }
            // 祖先の要素が自分の色を宣言していれば、その上の祖先の色は継承されない
            if (declaresOwnColor(a)) break;
        }
        return AnimationSpec.NONE;
    }

    /** 親と違う色を持っている（= 自分で color を宣言した）か。 */
    static boolean declaresOwnColor(LayoutBox box) {
        return box.parent != null && box.parent.style != null
                && !Objects.equals(box.style.get(CssPropertyIndex.COLOR), box.parent.style.get(CssPropertyIndex.COLOR));
    }

    record TransitionSpec(int durationMs, int delayMs, String easing, String property) {}
}
