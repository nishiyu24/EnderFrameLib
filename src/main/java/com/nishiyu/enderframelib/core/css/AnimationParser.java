package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.paint.model.AnimationTiming;
import com.nishiyu.enderframelib.core.paint.model.EasingFunction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 算出スタイルの {@code animation} / {@code transition} の指定を読んで {@link AnimationTiming} にする。
 *
 * <p>以前はこの読み取りが {@code DisplayListBuilder} の中にあり、{@code opacity} / {@code transform} / 色の 4 チャネルを
 * 描画項目に載せる経路でしか使えなかった。ここへ出したので、{@link StyleAnimator} が他のプロパティのトランジションと
 * アニメーションにも同じ読み取りを使う。</p>
 */
public final class AnimationParser {

    private AnimationParser() {}

    private static final Set<String> EASING_KEYWORDS = Set.of(
            "ease", "linear", "ease-in", "ease-out", "ease-in-out", "step-start", "step-end");
    private static final Set<String> DIRECTIONS = Set.of("normal", "reverse", "alternate", "alternate-reverse");
    private static final Set<String> FILL_MODES = Set.of("forwards", "backwards", "both");

    /**
     * 算出スタイルの {@code animation} ショートハンドと各ロングハンドを読む。
     *
     * @param knownKeyframes {@code @keyframes} の名前として存在するか
     * @return 適用するアニメーション。名前が無い・{@code none}・該当する {@code @keyframes} が無いときは null
     */
    public static AnimationTiming parse(ComputedStyle style, Predicate<String> knownKeyframes) {
        String shorthand = style.get(CssProperty.ANIMATION);
        String name = "";
        int duration = 1000;
        int delay = 0;
        float iterations = 1.0f;
        String direction = "normal";
        String fill = "none";
        String easing = EasingFunction.DEFAULT_EASING;

        if (shorthand != null && !"none".equalsIgnoreCase(shorthand.trim())) {
            int times = 0;
            for (String word : words(shorthand)) {
                String lower = word.toLowerCase(Locale.ROOT);
                int ms = timeMs(lower);
                if (ms != NOT_A_TIME) {
                    if (times == 0) duration = ms;
                    else if (times == 1) delay = ms;
                    times++;
                } else if (lower.equals("infinite")) {
                    iterations = Float.POSITIVE_INFINITY;
                } else if (DIRECTIONS.contains(lower)) {
                    direction = lower;
                } else if (FILL_MODES.contains(lower)) {
                    fill = lower;
                } else if (isEasing(lower)) {
                    easing = lower;
                } else if (knownKeyframes.test(word)) {
                    name = word;
                } else {
                    try {
                        iterations = Float.parseFloat(lower);
                    } catch (NumberFormatException ignored) {
                        if (name.isEmpty()) name = word;
                    }
                }
            }
        }

        String nameProp = style.get(CssProperty.ANIMATION_NAME);
        if (nameProp != null && !nameProp.isBlank()) name = nameProp.trim();
        if (name.isEmpty() || "none".equalsIgnoreCase(name) || !knownKeyframes.test(name)) return null;

        int ms = timeMs(style.get(CssProperty.ANIMATION_DURATION));
        if (ms != NOT_A_TIME) duration = ms;
        ms = timeMs(style.get(CssProperty.ANIMATION_DELAY));
        if (ms != NOT_A_TIME) delay = ms;

        String iterProp = style.get(CssProperty.ANIMATION_ITERATION_COUNT);
        if (iterProp != null && !iterProp.isBlank()) {
            String trimmed = iterProp.trim().toLowerCase(Locale.ROOT);
            if (trimmed.equals("infinite")) {
                iterations = Float.POSITIVE_INFINITY;
            } else {
                try {
                    iterations = Float.parseFloat(trimmed);
                } catch (NumberFormatException ignored) {
                    // 読めない値は無視（ショートハンドの値を残す）
                }
            }
        }
        String dirProp = style.get(CssProperty.ANIMATION_DIRECTION);
        if (dirProp != null && !dirProp.isBlank()) direction = dirProp.trim().toLowerCase(Locale.ROOT);
        String fillProp = style.get(CssProperty.ANIMATION_FILL_MODE);
        if (fillProp != null && !fillProp.isBlank()) fill = fillProp.trim().toLowerCase(Locale.ROOT);
        String timing = style.get(CssProperty.ANIMATION_TIMING_FUNCTION);
        if (timing != null && !timing.isBlank()) easing = timing.trim();

        return AnimationTiming.of(name, duration, delay, iterations, direction, fill, easing);
    }

    /**
     * {@code transition} ショートハンドと各ロングハンドを、プロパティごとの組の列にする。
     * ロングハンドの列は {@code transition-property} の個数になるまで繰り返す（CSS Transitions §2）。
     */
    public static List<AnimationTiming.Transition> parseTransitions(ComputedStyle style) {
        List<String> properties = new ArrayList<>();
        List<Integer> durations = new ArrayList<>();
        List<Integer> delays = new ArrayList<>();
        List<String> easings = new ArrayList<>();

        String shorthand = style.get(CssProperty.TRANSITION);
        if (shorthand != null && !shorthand.isBlank()) {
            if (shorthand.trim().equalsIgnoreCase("none")) {
                properties.add("none");
                durations.add(0);
                delays.add(0);
                easings.add(EasingFunction.DEFAULT_EASING);
            } else {
                for (String entry : CssSyntax.parseCommaList(shorthand)) {
                    String property = "all";
                    int duration = 0;
                    int delay = 0;
                    String easing = EasingFunction.DEFAULT_EASING;
                    int times = 0;
                    for (String word : words(entry)) {
                        String lower = word.toLowerCase(Locale.ROOT);
                        int ms = timeMs(lower);
                        if (ms != NOT_A_TIME) {
                            if (times == 0) duration = ms;
                            else if (times == 1) delay = ms;
                            times++;
                        } else if (isEasing(lower)) {
                            easing = lower;
                        } else {
                            property = lower;
                        }
                    }
                    properties.add(property);
                    durations.add(duration);
                    delays.add(delay);
                    easings.add(easing);
                }
            }
        }

        String propertyList = style.get(CssProperty.TRANSITION_PROPERTY);
        if (propertyList != null && !propertyList.isBlank()) {
            properties.clear();
            for (String p : CssSyntax.parseCommaList(propertyList)) properties.add(p.toLowerCase(Locale.ROOT));
        }
        String durationList = style.get(CssProperty.TRANSITION_DURATION);
        if (durationList != null && !durationList.isBlank()) {
            durations.clear();
            for (String t : CssSyntax.parseCommaList(durationList)) durations.add(Math.max(0, orZero(timeMs(t))));
        }
        String delayList = style.get(CssProperty.TRANSITION_DELAY);
        if (delayList != null && !delayList.isBlank()) {
            delays.clear();
            for (String t : CssSyntax.parseCommaList(delayList)) delays.add(orZero(timeMs(t)));
        }
        String easingList = style.get(CssProperty.TRANSITION_TIMING_FUNCTION);
        if (easingList != null && !easingList.isBlank()) {
            easings.clear();
            for (String t : CssSyntax.parseCommaList(easingList)) easings.add(t.toLowerCase(Locale.ROOT));
        }

        List<AnimationTiming.Transition> out = new ArrayList<>(properties.size());
        for (int i = 0; i < properties.size(); i++) {
            out.add(new AnimationTiming.Transition(properties.get(i),
                    durations.isEmpty() ? 0 : durations.get(i % durations.size()),
                    delays.isEmpty() ? 0 : delays.get(i % delays.size()),
                    easings.isEmpty() ? EasingFunction.DEFAULT_EASING : easings.get(i % easings.size())));
        }
        return out;
    }

    private static final int NOT_A_TIME = Integer.MIN_VALUE;

    private static int orZero(int ms) {
        return ms == NOT_A_TIME ? 0 : ms;
    }

    /** {@code 0.3s} / {@code 300ms} をミリ秒に。時間でなければ {@link #NOT_A_TIME}。 */
    private static int timeMs(String text) {
        if (text == null) return NOT_A_TIME;
        String s = text.trim().toLowerCase(Locale.ROOT);
        boolean seconds = s.endsWith("s") && !s.endsWith("ms");
        if (!seconds && !s.endsWith("ms")) return NOT_A_TIME;
        String number = s.substring(0, s.length() - (seconds ? 1 : 2));
        if (number.isEmpty()) return NOT_A_TIME;
        try {
            double v = Double.parseDouble(number);
            return (int) Math.round(seconds ? v * 1000.0 : v);
        } catch (NumberFormatException e) {
            return NOT_A_TIME;
        }
    }

    private static boolean isEasing(String lower) {
        return EASING_KEYWORDS.contains(lower) || lower.startsWith("cubic-bezier(") || lower.startsWith("steps(");
    }

    /** 最上位の空白で区切った語（関数の中の空白では切らない）。 */
    private static List<String> words(String value) {
        List<String> out = new ArrayList<>();
        for (List<CssSyntax.ComponentValue> part : CssSyntax.splitOnWhitespace(CssSyntax.parseComponentValues(value))) {
            out.add(CssSyntax.serialize(part));
        }
        return out;
    }
}
