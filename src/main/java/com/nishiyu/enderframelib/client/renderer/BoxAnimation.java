package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.core.css.ColorParser;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.model.Keyframes;
import com.nishiyu.enderframelib.core.css.model.KeyframeBlock;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.CssSyntax;
import com.nishiyu.enderframelib.core.paint.model.EasingFunction;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.css.AnimationParser;
import com.nishiyu.enderframelib.core.paint.model.AnimationTiming;
import com.nishiyu.enderframelib.core.paint.model.AnimationSpec;
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
    private final int rootW;
    private final int rootH;
    private final Map<String, Keyframes> keyframes;

    BoxAnimation(int rootW, int rootH, Map<String, Keyframes> keyframes) {
        this.rootW = rootW;
        this.rootH = rootH;
        this.keyframes = keyframes;
    }

    static final Set<String> EASING_KEYWORDS = Set.of(
            "ease", "linear", "ease-in", "ease-out", "ease-in-out"
    );

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
        if (t == null || t.isEmpty()) return new float[]{defTx, defTy, defSx, defSy, defRot};
        return Transforms.toTrs(Transforms.parse(t, new Transforms.Context(boxW, boxH, 0f, 0f, 16f, 16f)));
    }

    /** 箱の算出値の {@code translate} / {@code rotate} / {@code scale} / {@code transform} を宣言順に合成して {@code {tx, ty, sx, sy, rot}} にする。 */
    float[] transformOf(LayoutBox box) {
        Transforms.Context ctx = new Transforms.Context(box.w, box.h, rootW, rootH, box.style.fontSizePx(), box.style.rootFontSizePx());
        return Transforms.toTrs(Transforms.compose(box.style.get("translate"), box.style.get("rotate"), box.style.get("scale"),
                box.style.get(CssProperty.TRANSFORM), ctx));
    }

    /**
     * CSS の {@code animation} ショートハンド・各ロングハンドと {@code @keyframes} を
     * {@link AnimationSpec} に解決する。
     */
    AnimationSpec parseAnimationDef(ComputedStyle style, float baseW, float baseH, int baseColor, int baseBgColor) {
        AnimationTiming timing = AnimationParser.parse(style, keyframes::containsKey);
        if (timing == null) return AnimationSpec.NONE;
        String name = timing.name();

        List<AnimationSpec.Keyframe> frames = new ArrayList<>();
        int animatedChannels = 0;

        for (KeyframeBlock block : keyframes.get(name).blocks()) {
            float pct = block.percent();
            Map<String, String> decls = block.declarations();
            int definedChannels = 0;

            float[] tr = new float[]{0f, 0f, 1f, 1f, 0f};
            if (decls.containsKey(CssProperty.TRANSFORM)) {
                tr = parseTransformProps(decls.get(CssProperty.TRANSFORM), baseW, baseH, 0f, 0f, 1f, 1f, 0f);
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
            frames.add(new AnimationSpec.Keyframe(pct, tr[0], tr[1], tr[2], tr[3], tr[4], op, col, bgCol, definedChannels));
        }

        if (frames.isEmpty() || animatedChannels == 0) return AnimationSpec.NONE;
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
        String inherited = text.style.get(CssProperty.COLOR);
        for (LayoutBox a = text.parent; a != null && a.style != null; a = a.parent) {
            if (!Objects.equals(inherited, a.style.get(CssProperty.COLOR))) break;
            if (a.style.get(CssProperty.ANIMATION) != null || a.style.get(CssProperty.ANIMATION_NAME) != null) {
                int color = a.style.color(CssProperty.COLOR, 0xFF000000);
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
                && !Objects.equals(box.style.get(CssProperty.COLOR), box.parent.style.get(CssProperty.COLOR));
    }

    record TransitionSpec(int durationMs, int delayMs, String easing, String property) {}
}
