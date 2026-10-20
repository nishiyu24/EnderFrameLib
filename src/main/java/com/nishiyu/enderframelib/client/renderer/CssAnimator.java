package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.core.paint.model.EasingFunction;
import com.nishiyu.enderframelib.core.paint.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.RenderKeys;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;

/**
 * {@link DisplayItem} の前値→次値の汎用トランジション補間および {@code @keyframes} アニメーションを評価する。
 *
 * <p>以前は {@code PseudoTarget} を介して {@code :hover}/{@code :active}/{@code :focus} の 4 プロパティだけを
 * 特例補間していたが、状態がスタイル解決の入力になったことで、再スタイル後の {@link DisplayItem} の目標値変化を
 * そのまま検知して補間する一般化された仕組みとなった。
 */
public class CssAnimator {

    /**
     * DisplayListBuilder が解決した {@link AnimationSpec} を、補間しやすい進行度順のキーフレーム列に正規化する。
     */
    private static AnimationDef normalize(AnimationSpec spec) {
        if (spec == null || spec.isEmpty()) return null;
        AnimationDef def = new AnimationDef(spec);
        def.frames.addAll(spec.frames());
        def.frames.sort(Comparator.comparingDouble(AnimationSpec.Keyframe::pct));
        return def;
    }

    public static AnimatedValues getValues(String sessionId, DisplayItem el) {
        return getValuesAt(sessionId, el, System.currentTimeMillis());
    }

    public static AnimatedValues getValues(String sessionId, DisplayItem el, boolean isHovered) {
        return getValuesAt(sessionId, el, System.currentTimeMillis());
    }

    public static AnimatedValues getValues(String sessionId, DisplayItem el, boolean isHovered, boolean isActive, boolean isFocused) {
        return getValuesAt(sessionId, el, System.currentTimeMillis());
    }

    /**
     * テストやフレーム同期から時刻を明示して補間値を取得する。
     */
    public static AnimatedValues getValuesAt(String sessionId, DisplayItem el, long now) {
        String safeSessionId = (sessionId != null && !sessionId.isEmpty()) ? sessionId : "unknown";
        Map<String, AnimState> sessionMap = ClientWebManager.getOrCreateSession(safeSessionId).cssAnimations();
        AnimState state = sessionMap.computeIfAbsent(el.id(), k -> new AnimState(el, now));

        if (state.hasTargetChanged(el)) {
            state.updateTarget(el, now);
        }
        state.updateStyleCache(el);
        return state.getInterpolatedValues(now);
    }

    public static AnimatedValues getCurrentValues(String sessionId, DisplayItem el) {
        String safeSessionId = (sessionId != null && !sessionId.isEmpty()) ? sessionId : "unknown";
        SessionContext session = ClientWebManager.getSession(safeSessionId);
        Map<String, AnimState> sessionMap = session != null ? session.cssAnimations() : null;
        AnimState state = sessionMap != null ? sessionMap.get(el.id()) : null;
        if (state != null) {
            return state.getInterpolatedValues(System.currentTimeMillis());
        }
        AnimatedValues def = new AnimatedValues();
        def.set(el.opacity(), el.translateX(), el.translateY(), el.scaleX(), el.scaleY(), el.rotate(), el.color(), el.bgColor());
        return def;
    }

    public static void cleanup(String sessionId, List<DisplayItem> currentElements) {
        if (sessionId == null) return;
        SessionContext session = ClientWebManager.getSession(sessionId);
        if (session == null || session.cssAnimations().isEmpty()) return;
        Map<String, AnimState> sessionMap = session.cssAnimations();

        Set<String> activeIds = new HashSet<>(currentElements.size());
        for (DisplayItem el : currentElements) {
            activeIds.add(el.id());
        }
        sessionMap.keySet().retainAll(activeIds);
    }

    private static float parseFloatSafe(String s, float fallback) {
        if (s == null || s.isEmpty()) return fallback;
        try {
            return Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseIntSafe(String s, int fallback) {
        if (s == null || s.isEmpty()) return fallback;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * {@code transition-property} の指定文字列をアニメーション対象チャネルのビットマスクに変換する。
     */
    static int parseTransitionPropertyMask(String propSpec) {
        if (propSpec == null || propSpec.isBlank()) {
            return AnimationSpec.CH_OPACITY | AnimationSpec.CH_TRANSFORM | AnimationSpec.CH_COLOR | AnimationSpec.CH_BG_COLOR;
        }
        int mask = 0;
        for (String token : propSpec.toLowerCase(Locale.ROOT).split("[,\\s]+")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            switch (t) {
                case "none" -> { return 0; }
                case "all" -> {
                    return AnimationSpec.CH_OPACITY | AnimationSpec.CH_TRANSFORM | AnimationSpec.CH_COLOR | AnimationSpec.CH_BG_COLOR;
                }
                case "opacity" -> mask |= AnimationSpec.CH_OPACITY;
                case "transform", "translate", "scale", "rotate" -> mask |= AnimationSpec.CH_TRANSFORM;
                case "color" -> mask |= AnimationSpec.CH_COLOR;
                case "background", "background-color", "border", "border-color",
                     "border-top-color", "border-right-color", "border-bottom-color", "border-left-color" ->
                        mask |= AnimationSpec.CH_BG_COLOR;
                default -> {}
            }
        }
        return mask;
    }

    /** 進行度順に並べ替えたキーフレーム列。{@link AnimationSpec} から作る。 */
    static class AnimationDef {
        final int durationMs;
        final int delayMs;
        final float iterationCount;
        final AnimationSpec.Direction direction;
        final AnimationSpec.FillMode fillMode;
        final String easing;
        final int animatedChannels;
        final List<AnimationSpec.Keyframe> frames = new ArrayList<>();

        AnimationDef(AnimationSpec spec) {
            this.durationMs = spec.durationMs();
            this.delayMs = spec.delayMs();
            this.iterationCount = spec.iterationCount();
            this.direction = spec.direction();
            this.fillMode = spec.fillMode();
            this.easing = spec.easing();
            this.animatedChannels = spec.animatedChannels();
        }

        boolean infinite() {
            return Float.isInfinite(iterationCount);
        }

        double totalActiveDurationMs() {
            return infinite() ? Double.POSITIVE_INFINITY : (double) durationMs * iterationCount;
        }
    }

    public static class AnimState {
        private final AnimatedValues cachedValues = new AnimatedValues();
        public float startOpacity, targetOpacity, baseTargetOpacity;
        public float startTx, targetTx, baseTx;
        public float startTy, targetTy, baseTy;
        public float startSx, targetSx, baseSx;
        public float startSy, targetSy, baseSy;
        public float startRot, targetRot, baseRot;
        public int startColor, targetColor, baseColor;
        public int startBgColor, targetBgColor, baseBgColor;

        public long startTime;
        public int durationMs;
        public int delayMs;
        public String easing;
        public int transitionMask = AnimationSpec.CH_OPACITY | AnimationSpec.CH_TRANSFORM | AnimationSpec.CH_COLOR | AnimationSpec.CH_BG_COLOR;
        public AnimationSpec currentAnimSpec = AnimationSpec.NONE;
        public AnimationDef currentAnimDef = null;
        public long animStartTime = 0;

        public String cachedText = null;
        public Map<String, String> cachedEvents = null;
        public float[] clipData = null;
        public float[] radiusData = null;
        public float rangeVal = 50, rangeMin = 0, rangeMax = 100, circleThickness = 0;
        public int[] circleColors = null;

        public AnimState(DisplayItem el) {
            this(el, System.currentTimeMillis());
        }

        public AnimState(DisplayItem el, long now) {
            syncBaseTargets(el);
            this.targetOpacity = this.startOpacity = this.baseTargetOpacity;
            this.targetTx = this.startTx = this.baseTx;
            this.targetTy = this.startTy = this.baseTy;
            this.targetSx = this.startSx = this.baseSx;
            this.targetSy = this.startSy = this.baseSy;
            this.targetRot = this.startRot = this.baseRot;
            this.targetColor = this.startColor = this.baseColor;
            this.targetBgColor = this.startBgColor = this.baseBgColor;

            this.durationMs = 0;
            this.delayMs = 0;
            this.easing = EasingFunction.DEFAULT_EASING;
            this.startTime = now;
            this.currentAnimSpec = el.animDef() != null ? el.animDef() : AnimationSpec.NONE;
            this.currentAnimDef = normalize(this.currentAnimSpec);
            this.animStartTime = now;
            updateStyleCache(el);
        }

        public boolean isActiveAt(long now) {
            if (durationMs > 0 && now < startTime + delayMs + durationMs) return true;
            if (currentAnimDef != null) {
                if (currentAnimDef.infinite()) return true;
                double endMs = (double) animStartTime + currentAnimDef.delayMs + currentAnimDef.totalActiveDurationMs();
                return now < endMs;
            }
            return false;
        }

        public boolean hasTargetChanged(DisplayItem el) {
            return Float.compare(baseTargetOpacity, el.opacity()) != 0
                    || Float.compare(baseTx, el.translateX()) != 0
                    || Float.compare(baseTy, el.translateY()) != 0
                    || Float.compare(baseSx, el.scaleX()) != 0
                    || Float.compare(baseSy, el.scaleY()) != 0
                    || Float.compare(baseRot, el.rotate()) != 0
                    || baseColor != el.color()
                    || baseBgColor != el.bgColor()
                    || !Objects.equals(el.animDef(), currentAnimSpec);
        }

        public void syncBaseTargets(DisplayItem el) {
            this.baseTargetOpacity = el.opacity();
            this.baseTx = el.translateX();
            this.baseTy = el.translateY();
            this.baseSx = el.scaleX();
            this.baseSy = el.scaleY();
            this.baseRot = el.rotate();
            this.baseColor = el.color();
            this.baseBgColor = el.bgColor();
        }

        public void updateStyleCache(DisplayItem el) {
            boolean textChanged = !Objects.equals(cachedText, el.text());
            boolean eventsChanged = !Objects.equals(cachedEvents, el.events());
            if (!textChanged && !eventsChanged) return;

            cachedText = el.text();
            cachedEvents = el.events();
            RenderMeta meta = el.meta();

            if (meta != null && meta.clipRect() != null) {
                clipData = meta.clipRect().toArray();
            } else {
                clipData = null;
            }

            if (meta != null && meta.borderRadii() != null) {
                radiusData = meta.borderRadii().toFloatArray();
            } else {
                radiusData = new float[]{el.radius(), el.radius(), el.radius(), el.radius()};
            }

            if (textChanged && el.text() != null) {
                if (el.type() == ElementType.RANGE || el.type() == ElementType.PROGRESS) {
                    String[] parts = el.text().split(":");
                    rangeVal = 50f; rangeMin = 0f; rangeMax = 100f;
                    if (parts.length >= 3) {
                        rangeVal = parseFloatSafe(parts[0], 50f);
                        rangeMin = parseFloatSafe(parts[1], 0f);
                        rangeMax = parseFloatSafe(parts[2], 100f);
                    }
                } else if (el.type() == ElementType.CIRCLE_BORDER) {
                    CircleBorderSpec cb = meta != null ? meta.circleBorder() : null;
                    if (cb != null) {
                        circleThickness = cb.thickness();
                        circleColors = new int[]{cb.colorTop(), cb.colorRight(), cb.colorBottom(), cb.colorLeft()};
                    } else {
                        circleThickness = 0f;
                        circleColors = null;
                    }
                }
            }
        }

        public void updateTarget(DisplayItem el, long now) {
            AnimatedValues current = getInterpolatedValues(now);

            if (!Objects.equals(el.animDef(), this.currentAnimSpec)) {
                this.currentAnimSpec = el.animDef() != null ? el.animDef() : AnimationSpec.NONE;
                this.currentAnimDef = normalize(this.currentAnimSpec);
                this.animStartTime = now;
            }

            syncBaseTargets(el);
            this.targetOpacity = el.opacity();
            this.targetTx = el.translateX();
            this.targetTy = el.translateY();
            this.targetSx = el.scaleX();
            this.targetSy = el.scaleY();
            this.targetRot = el.rotate();
            this.targetColor = el.color();
            this.targetBgColor = el.bgColor();

            this.durationMs = el.transitionDuration();
            this.delayMs = 0;
            this.easing = EasingFunction.DEFAULT_EASING;
            String propSpec = "all";
            if (el.events() != null) {
                if (el.events().containsKey(RenderKeys.MC_DELAY)) {
                    this.delayMs = parseIntSafe(el.events().get(RenderKeys.MC_DELAY), 0);
                }
                if (el.events().containsKey(RenderKeys.MC_EASING)) {
                    this.easing = el.events().get(RenderKeys.MC_EASING);
                }
                if (el.events().containsKey(RenderKeys.MC_TRANSITION_PROP)) {
                    propSpec = el.events().get(RenderKeys.MC_TRANSITION_PROP);
                }
            }
            this.transitionMask = parseTransitionPropertyMask(propSpec);

            // transition-property に含まれるチャネルは現在値から補間し、含まれないチャネルは即座に目標値へスナップする
            if ((this.transitionMask & AnimationSpec.CH_OPACITY) != 0) {
                this.startOpacity = current.opacity();
            } else {
                this.startOpacity = this.targetOpacity;
            }

            if ((this.transitionMask & AnimationSpec.CH_TRANSFORM) != 0) {
                this.startTx = current.tx();
                this.startTy = current.ty();
                this.startSx = current.sx();
                this.startSy = current.sy();
                this.startRot = current.rot();
            } else {
                this.startTx = this.targetTx;
                this.startTy = this.targetTy;
                this.startSx = this.targetSx;
                this.startSy = this.targetSy;
                this.startRot = this.targetRot;
            }

            if ((this.transitionMask & AnimationSpec.CH_COLOR) != 0) {
                this.startColor = current.color();
            } else {
                this.startColor = this.targetColor;
            }

            if ((this.transitionMask & AnimationSpec.CH_BG_COLOR) != 0) {
                this.startBgColor = current.bgColor();
            } else {
                this.startBgColor = this.targetBgColor;
            }

            if (this.transitionMask == 0) {
                this.durationMs = 0;
            }
            this.startTime = now;
        }

        public AnimatedValues getInterpolatedValues(long now) {
            float op, tx, ty, sx, sy, rot;
            int col, bgCol;

            if (durationMs <= 0 || transitionMask == 0 || now >= startTime + delayMs + durationMs) {
                op = targetOpacity; tx = targetTx; ty = targetTy;
                sx = targetSx; sy = targetSy; rot = targetRot;
                col = targetColor; bgCol = targetBgColor;
            } else if (now < startTime + delayMs) {
                op = startOpacity; tx = startTx; ty = startTy;
                sx = startSx; sy = startSy; rot = startRot;
                col = startColor; bgCol = startBgColor;
            } else {
                float rawT = (float) (now - (startTime + delayMs)) / durationMs;
                float t = EasingFunction.apply(rawT, easing);

                op = lerp(startOpacity, targetOpacity, t);
                tx = lerp(startTx, targetTx, t);
                ty = lerp(startTy, targetTy, t);
                sx = lerp(startSx, targetSx, t);
                sy = lerp(startSy, targetSy, t);
                rot = lerp(startRot, targetRot, t);
                col = lerpColor(startColor, targetColor, t);
                bgCol = lerpColor(startBgColor, targetBgColor, t);
            }

            if (currentAnimDef != null && !currentAnimDef.frames.isEmpty() && currentAnimDef.animatedChannels != 0) {
                float animPct = computeKeyframePct(currentAnimDef, now - animStartTime);
                if (!Float.isNaN(animPct)) {
                    AnimationSpec.Keyframe f0 = currentAnimDef.frames.getFirst();
                    AnimationSpec.Keyframe f1 = currentAnimDef.frames.getLast();
                    for (int i = 0; i < currentAnimDef.frames.size() - 1; i++) {
                        AnimationSpec.Keyframe cur = currentAnimDef.frames.get(i);
                        AnimationSpec.Keyframe next = currentAnimDef.frames.get(i + 1);
                        if (animPct >= cur.pct() && animPct <= next.pct()) {
                            f0 = cur;
                            f1 = next;
                            break;
                        }
                    }
                    float span = f1.pct() - f0.pct();
                    float rawLocalT = span > 0f ? Math.clamp((animPct - f0.pct()) / span, 0f, 1f) : 0f;
                    float localT = EasingFunction.apply(rawLocalT, currentAnimDef.easing);

                    // @keyframes で実際に宣言されたチャネルだけを上書きし、他のチャネルのトランジションを潰さない
                    if ((currentAnimDef.animatedChannels & AnimationSpec.CH_OPACITY) != 0) {
                        op = lerp(f0.op(), f1.op(), localT);
                    }
                    if ((currentAnimDef.animatedChannels & AnimationSpec.CH_TRANSFORM) != 0) {
                        tx = lerp(f0.tx(), f1.tx(), localT);
                        ty = lerp(f0.ty(), f1.ty(), localT);
                        sx = lerp(f0.sx(), f1.sx(), localT);
                        sy = lerp(f0.sy(), f1.sy(), localT);
                        rot = lerp(f0.rot(), f1.rot(), localT);
                    }
                    if ((currentAnimDef.animatedChannels & AnimationSpec.CH_COLOR) != 0) {
                        col = lerpColor(f0.color(), f1.color(), localT);
                    }
                    if ((currentAnimDef.animatedChannels & AnimationSpec.CH_BG_COLOR) != 0) {
                        bgCol = lerpColor(f0.bgColor(), f1.bgColor(), localT);
                    }
                }
            }

            cachedValues.set(op, tx, ty, sx, sy, rot, col, bgCol);
            cachedValues.clipData = this.clipData;
            cachedValues.radiusData = this.radiusData;
            cachedValues.rangeVal = this.rangeVal;
            cachedValues.rangeMin = this.rangeMin;
            cachedValues.rangeMax = this.rangeMax;
            cachedValues.circleThickness = this.circleThickness;
            cachedValues.circleColors = this.circleColors;
            return cachedValues;
        }

        /**
         * {@code animation-delay}, {@code animation-iteration-count}, {@code animation-direction},
         * {@code animation-fill-mode} を考慮して現在のキーフレーム進行度（0〜100）を返す。
         * アニメーションの適用外（delay 中で backwards 無し、または終了後で forwards 無し）の場合は {@code Float.NaN}。
         */
        private static float computeKeyframePct(AnimationDef def, long elapsedFromStartMs) {
            long activeElapsed = elapsedFromStartMs - def.delayMs;
            int dur = Math.max(1, def.durationMs);

            if (activeElapsed < 0) {
                if (def.fillMode == AnimationSpec.FillMode.BACKWARDS || def.fillMode == AnimationSpec.FillMode.BOTH) {
                    boolean reverseFirst = (def.direction == AnimationSpec.Direction.REVERSE
                            || def.direction == AnimationSpec.Direction.ALTERNATE_REVERSE);
                    return reverseFirst ? 100f : 0f;
                }
                return Float.NaN;
            }

            double totalActive = def.totalActiveDurationMs();
            if (!def.infinite() && activeElapsed >= totalActive) {
                if (def.fillMode == AnimationSpec.FillMode.FORWARDS || def.fillMode == AnimationSpec.FillMode.BOTH) {
                    float ic = def.iterationCount;
                    long finalIterIndex;
                    float finalProgress;
                    if (Math.floor(ic) == ic && ic >= 1.0f) {
                        finalIterIndex = (long) ic - 1L;
                        finalProgress = 1.0f;
                    } else {
                        finalIterIndex = (long) Math.floor(ic);
                        finalProgress = ic - finalIterIndex;
                    }
                    boolean reverse = isIterationReversed(def.direction, finalIterIndex);
                    float dirProgress = reverse ? (1.0f - finalProgress) : finalProgress;
                    return dirProgress * 100f;
                }
                return Float.NaN;
            }

            long iterIndex = activeElapsed / dur;
            float iterProgress = (float) (activeElapsed % dur) / dur;
            boolean reverse = isIterationReversed(def.direction, iterIndex);
            float dirProgress = reverse ? (1.0f - iterProgress) : iterProgress;
            return dirProgress * 100f;
        }

        private static boolean isIterationReversed(AnimationSpec.Direction dir, long iterIndex) {
            return switch (dir) {
                case NORMAL -> false;
                case REVERSE -> true;
                case ALTERNATE -> (iterIndex % 2L) == 1L;
                case ALTERNATE_REVERSE -> (iterIndex % 2L) == 0L;
            };
        }

        private static float lerp(float a, float b, float t) {
            return a + (b - a) * t;
        }

        private static int lerpColor(int c1, int c2, float t) {
            if (c1 == c2) return c1;
            float a1 = ((c1 >>> 24) & 0xFF) / 255f, r1 = ((c1 >>> 16) & 0xFF) / 255f, g1 = ((c1 >>> 8) & 0xFF) / 255f, b1 = (c1 & 0xFF) / 255f;
            float a2 = ((c2 >>> 24) & 0xFF) / 255f, r2 = ((c2 >>> 16) & 0xFF) / 255f, g2 = ((c2 >>> 8) & 0xFF) / 255f, b2 = (c2 & 0xFF) / 255f;
            int a = Math.round(lerp(a1, a2, t) * 255f) & 0xFF;
            int r = Math.round(lerp(r1, r2, t) * 255f) & 0xFF;
            int g = Math.round(lerp(g1, g2, t) * 255f) & 0xFF;
            int b = Math.round(lerp(b1, b2, t) * 255f) & 0xFF;
            return (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    public static class AnimatedValues {
        public float[] clipData;
        public float[] radiusData;
        public float rangeVal, rangeMin, rangeMax, circleThickness;
        public int[] circleColors;
        private float opacity, tx, ty, sx, sy, rot;
        private int color, bgColor;

        public void set(float op, float tx, float ty, float sx, float sy, float rot, int col, int bgCol) {
            this.opacity = op;
            this.tx = tx;
            this.ty = ty;
            this.sx = sx;
            this.sy = sy;
            this.rot = rot;
            this.color = col;
            this.bgColor = bgCol;
        }

        public float opacity() { return opacity; }
        public float tx() { return tx; }
        public float ty() { return ty; }
        public float sx() { return sx; }
        public float sy() { return sy; }
        public float rot() { return rot; }
        public int color() { return color; }
        public int bgColor() { return bgColor; }
    }
}