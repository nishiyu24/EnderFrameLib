package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.model.KeyframeBlock;
import com.nishiyu.enderframelib.core.css.model.Keyframes;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.model.AnimationTiming;
import com.nishiyu.enderframelib.core.paint.model.EasingFunction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 算出スタイルの値を時間で動かす（CSS Transitions / Animations）。{@link PropertyRegistry} で {@code animatable} と
 * 宣言されたプロパティのうち、描画項目の 4 チャネル（{@link #CHANNEL_PROPERTIES}）が担わないものすべてが対象。
 *
 * <p>動く値は「アニメーションの起源」としてカスケードの結果に重ねる。再スタイルが {@link #sample} の値を生の宣言に足して
 * 算出し直すので、レイアウトに効くプロパティ（{@code width}・{@code margin}・{@code font-size} など）が動けば
 * 再スタイルの比較が再レイアウトを要求し、色や角丸のような描画だけに効くものは描き直しだけで済む。
 * 無効化の種別はプロパティの宣言（{@link PropertyRegistry.Property#invalidation()}）がすでに持っている。</p>
 *
 * <p>時刻は呼び出し側が渡す。実時間に依らないので、テストはフレームごとの値を決定的に比べられる。</p>
 */
public final class StyleAnimator {

    /**
     * 描画項目の補間（{@code CssAnimator}）が担うプロパティ。ここで二重に動かさない。
     * 描画時に補間するので再スタイルが要らず、フレームごとのコストが小さい。
     */
    public static final Set<String> CHANNEL_PROPERTIES = Set.of(
            CssProperty.OPACITY, CssProperty.TRANSFORM, "translate", "rotate", "scale",
            CssProperty.COLOR, CssProperty.BACKGROUND_COLOR);

    /** 要素ごとの状態。動いているものが無くなれば次の {@link #observe} で捨てる。 */
    private static final class Entry {
        /** 直近の再スタイルで算出した目標値（アニメーションの値を重ねる前）。トランジションの「変化前」の出所。 */
        ComputedStyle target;
        final Map<String, Transition> transitions = new LinkedHashMap<>();
        Playing animation;
        /** 動き終えた直後の 1 回、最終値を反映する再スタイルを要求したか。 */
        boolean settledReported;
    }

    private record Transition(String from, String to, long startMs, int durationMs, int delayMs, String easing) {
        boolean finishedAt(long now) {
            return now >= startMs + delayMs + durationMs;
        }

        String valueAt(String property, long now) {
            if (now < startMs + delayMs) return from;
            if (finishedAt(now)) return to;
            float raw = (float) (now - startMs - delayMs) / durationMs;
            double eased = EasingFunction.apply(raw, easing);
            String mixed = ValueInterpolator.interpolate(property, from, to, eased);
            return mixed != null ? mixed : (eased < 0.5 ? from : to);
        }
    }

    private record Stop(float pct, String value) {}

    private static final class Playing {
        final AnimationTiming timing;
        final long startMs;
        final Map<String, List<Stop>> stops;
        final Keyframes source;

        Playing(AnimationTiming timing, long startMs, Map<String, List<Stop>> stops, Keyframes source) {
            this.timing = timing;
            this.startMs = startMs;
            this.stops = stops;
            this.source = source;
        }
    }

    private final Map<HtmlNode, Entry> entries = new WeakHashMap<>();

    /**
     * 要素のスタイルを再計算した結果を見て、トランジションとアニメーションを始める・取り替える・止める。
     * 再スタイルのたびに、要素の目標値（アニメーションを重ねる前の算出スタイル）で呼ぶ。
     *
     * @param before      この再スタイルの前の算出スタイル（アニメーションの値を含む）。初めて算出するなら null
     * @param parentStyle 親の算出値（継承による変化をトランジションにしないために見る）
     */
    public void observe(HtmlNode node, ComputedStyle before, ComputedStyle target, Map<String, String> parentStyle,
                        Map<String, Keyframes> keyframes, long now) {
        Entry entry = entries.get(node);
        boolean hasTransitionSpec = hasValue(target.get(CssProperty.TRANSITION)) || hasValue(target.get(CssProperty.TRANSITION_DURATION));
        boolean hasAnimationSpec = hasValue(target.get(CssProperty.ANIMATION)) || hasValue(target.get(CssProperty.ANIMATION_NAME));
        if (entry == null && !hasAnimationSpec && !(hasTransitionSpec && before != null)) return;

        ComputedStyle changeFrom = entry != null && entry.target != null ? entry.target : before;
        if (entry == null) entry = new Entry();

        if (changeFrom != null && changeFrom != target) {
            startTransitions(entry, changeFrom, target, parentStyle, now);
        }
        updateAnimation(entry, target, keyframes, now);
        entry.target = target;
        entry.settledReported = false;

        if (entry.transitions.isEmpty() && entry.animation == null) {
            entries.remove(node);
        } else {
            entries.put(node, entry);
        }
    }

    private void startTransitions(Entry entry, ComputedStyle from, ComputedStyle to, Map<String, String> parentStyle, long now) {
        List<AnimationTiming.Transition> specs = AnimationParser.parseTransitions(to);
        Set<String> candidates = new java.util.LinkedHashSet<>(to.asMap().keySet());
        candidates.addAll(from.asMap().keySet());
        for (String property : candidates) {
            if (!isGenericAnimatable(property)) continue;
            String before = valueOf(from, property);
            String after = valueOf(to, property);
            Transition running = entry.transitions.get(property);
            if (Objects.equals(before, after)) continue;
            AnimationTiming.Transition spec = AnimationTiming.transitionFor(specs, property);
            if (spec == null || !spec.runs() || before == null || after == null) {
                entry.transitions.remove(property);
                continue;
            }
            // 親から継承した値が動いているだけなら、子のトランジションにはしない（親のものが子へ流れる）
            if (PropertyRegistry.isInherited(property) && after.equals(parentStyle.get(property))) {
                entry.transitions.remove(property);
                continue;
            }
            // 動いている最中に目標が変わったときは、いまの値から始める（途中の値を飛び越えない）
            String start = running != null ? running.valueAt(property, now) : before;
            if (Objects.equals(start, after) || ValueInterpolator.interpolate(property, start, after, 0.5) == null) {
                entry.transitions.remove(property);
                continue;
            }
            entry.transitions.put(property, new Transition(start, after, now, spec.durationMs(), spec.delayMs(), spec.easing()));
        }
        // 目標を持たなくなったプロパティのトランジションは捨てる
        entry.transitions.keySet().removeIf(p -> !candidates.contains(p));
    }

    private void updateAnimation(Entry entry, ComputedStyle target, Map<String, Keyframes> keyframes, long now) {
        AnimationTiming timing = AnimationParser.parse(target, keyframes::containsKey);
        if (timing == null) {
            entry.animation = null;
            return;
        }
        Keyframes source = keyframes.get(timing.name());
        Playing current = entry.animation;
        if (current != null && current.source == source && current.timing.name().equals(timing.name())) {
            // 同じ名前のまま。長さなどの指定の変更は反映するが、開始時刻は変えない
            entry.animation = new Playing(timing, current.startMs, current.stops, source);
            return;
        }
        Map<String, List<Stop>> stops = stopsOf(source);
        entry.animation = stops.isEmpty() ? null : new Playing(timing, now, stops, source);
    }

    private static Map<String, List<Stop>> stopsOf(Keyframes source) {
        Map<String, List<Stop>> stops = new LinkedHashMap<>();
        for (KeyframeBlock block : source.blocks()) {
            for (Map.Entry<String, String> declaration : block.declarations().entrySet()) {
                String property = declaration.getKey();
                if (!isGenericAnimatable(property)) continue;
                stops.computeIfAbsent(property, k -> new ArrayList<>()).add(new Stop(block.percent(), declaration.getValue().trim()));
            }
        }
        for (List<Stop> list : stops.values()) list.sort(Comparator.comparingDouble(Stop::pct));
        return stops;
    }

    /**
     * 要素に今効いているアニメーションの値（目標値の上書き）。何も動いていなければ空。
     * アニメーションの値の上にトランジションの値が重なる（CSS Cascade 4 §6.1 の起源の順）。
     */
    public Map<String, String> sample(HtmlNode node, long now) {
        Entry entry = entries.get(node);
        if (entry == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        Playing playing = entry.animation;
        if (playing != null) {
            float pct = playing.timing.progressPct(now - playing.startMs);
            if (!Float.isNaN(pct)) {
                for (Map.Entry<String, List<Stop>> stops : playing.stops.entrySet()) {
                    String value = valueAtPct(stops.getKey(), stops.getValue(), pct, playing.timing.easing(), entry.target);
                    if (value != null) out.put(stops.getKey(), value);
                }
            }
        }
        for (Map.Entry<String, Transition> t : entry.transitions.entrySet()) {
            if (t.getValue().finishedAt(now)) continue;
            out.put(t.getKey(), t.getValue().valueAt(t.getKey(), now));
        }
        return out;
    }

    private static String valueAtPct(String property, List<Stop> stops, float pct, String easing, ComputedStyle target) {
        Stop previous = null;
        Stop next = null;
        for (Stop stop : stops) {
            if (stop.pct() <= pct) previous = stop;
            if (stop.pct() >= pct) {
                next = stop;
                break;
            }
        }
        // 0% / 100% が無ければ、要素自身の（アニメーションを重ねる前の）値がその端
        String base = target != null ? valueOf(target, property) : PropertyRegistry.initialValue(property);
        String from = previous != null ? previous.value() : base;
        String to = next != null ? next.value() : base;
        float fromPct = previous != null ? previous.pct() : 0f;
        float toPct = next != null ? next.pct() : 100f;
        if (from == null || to == null) return null;
        float span = toPct - fromPct;
        float raw = span > 0f ? Math.clamp((pct - fromPct) / span, 0f, 1f) : 0f;
        double eased = EasingFunction.apply(raw, easing);
        String mixed = ValueInterpolator.interpolate(property, from, to, eased);
        return mixed != null ? mixed : (eased < 0.5 ? from : to);
    }

    /**
     * 時刻を進める。値が変わった（動いている）要素と、動き終えて最終値に戻す要素を返す。呼び出し側はそれらを再スタイルに回す。
     */
    public List<HtmlNode> advance(long now) {
        List<HtmlNode> changed = new ArrayList<>();
        for (Map.Entry<HtmlNode, Entry> e : entries.entrySet()) {
            Entry entry = e.getValue();
            boolean running = false;
            boolean finishedNow = false;
            var iterator = entry.transitions.entrySet().iterator();
            while (iterator.hasNext()) {
                Transition t = iterator.next().getValue();
                if (t.finishedAt(now)) {
                    iterator.remove();
                    finishedNow = true;
                } else {
                    running = true;
                }
            }
            Playing playing = entry.animation;
            if (playing != null) {
                if (playing.timing.activeAt(now - playing.startMs)) {
                    running = true;
                } else if (!entry.settledReported) {
                    entry.settledReported = true;
                    finishedNow = true;
                }
            }
            if (running || finishedNow) changed.add(e.getKey());
        }
        return changed;
    }

    /** 時刻とともに値が変わるものが残っているか（次のフレームを要求し続けるか）。 */
    public boolean isRunning(long now) {
        for (Entry entry : entries.values()) {
            if (!entry.transitions.isEmpty()) return true;
            Playing playing = entry.animation;
            if (playing != null && playing.timing.activeAt(now - playing.startMs)) return true;
        }
        return false;
    }

    public void clear() {
        entries.clear();
    }

    // ------------------------------------------------------------------

    private static boolean isGenericAnimatable(String property) {
        if (CHANNEL_PROPERTIES.contains(property)) return false;
        PropertyRegistry.Property declared = PropertyRegistry.get(property);
        return declared != null && declared.animatable() && !declared.isShorthand();
    }

    /** 算出スタイルの値。宣言が無ければ初期値。 */
    private static String valueOf(ComputedStyle style, String property) {
        String value = style.get(property);
        return value != null ? value : PropertyRegistry.initialValue(property);
    }

    private static boolean hasValue(String value) {
        return value != null && !value.isBlank() && !value.trim().equalsIgnoreCase("none");
    }
}
