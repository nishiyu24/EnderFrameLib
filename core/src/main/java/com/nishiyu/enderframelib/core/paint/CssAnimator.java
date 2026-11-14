package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.css.model.*;
import com.nishiyu.enderframelib.core.css.model.AnimationTiming;
import com.nishiyu.enderframelib.core.css.model.EasingFunction;
import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
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

    /** セッション ID ごとの補間状態の置き場。 */
    public interface StateStore {
        /** {@code create} が false で無ければ null を返してよい。 */
        Map<String, AnimState> mapFor(String sessionId, boolean create);
    }

    /** 既定: プロセス内のレジストリ。Minecraft 側はセッション自身の保持先に差し替える。 */
    private static final Map<String, Map<String, AnimState>> DEFAULT_STATES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final StateStore DEFAULT_STORE = (id, create) -> create
            ? DEFAULT_STATES.computeIfAbsent(id, k -> new java.util.concurrent.ConcurrentHashMap<>())
            : DEFAULT_STATES.get(id);

    private static volatile StateStore store = DEFAULT_STORE;

    public static void setStateStore(StateStore newStore) {
        store = newStore == null ? DEFAULT_STORE : newStore;
    }

    /** 既定の置き場の指定セッションを捨てる。 */
    public static void clearSession(String sessionId) {
        DEFAULT_STATES.remove(sessionId);
    }

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
        return stateFor(sessionId, el, now).getInterpolatedValues(now);
    }

    /**
     * 項目 {@code el} の補間状態（無ければ作り、項目が前回と変わっていれば目標を更新する）。
     * 描画側は、同じ項目リストの間はこの状態を持ち回って {@link AnimState#isActiveAt} で静止を見分け、
     * 動いていない項目の補間と姿勢の計算を飛ばす。
     */
    public static AnimState stateFor(String sessionId, DisplayItem el, long now) {
        String safeSessionId = (sessionId != null && !sessionId.isEmpty()) ? sessionId : "unknown";
        Map<String, AnimState> sessionMap = store.mapFor(safeSessionId, true);
        AnimState state = sessionMap.get(el.id());
        if (state == null) state = sessionMap.computeIfAbsent(el.id(), k -> new AnimState(el, now));

        // DisplayItem は不変。前回と同じインスタンスなら目標もテキストも変わっていないので、比較（AnimationSpec の
        // 深い equals を含む）を飛ばす。アニメーションだけが動くフレームは、ほぼ全項目がここを通る
        if (state.seenItem != el) {
            if (state.hasTargetChanged(el)) {
                state.updateTarget(el, now);
            }
            state.updateStyleCache(el);
            state.seenItem = el;
        }
        return state;
    }

    /** 状態を作らずに引く。無ければ null。 */
    public static AnimState peekState(String sessionId, String id) {
        String safeSessionId = (sessionId != null && !sessionId.isEmpty()) ? sessionId : "unknown";
        Map<String, AnimState> sessionMap = store.mapFor(safeSessionId, false);
        return sessionMap != null ? sessionMap.get(id) : null;
    }

    public static AnimatedValues getCurrentValues(String sessionId, DisplayItem el) {
        String safeSessionId = (sessionId != null && !sessionId.isEmpty()) ? sessionId : "unknown";
        Map<String, AnimState> sessionMap = store.mapFor(safeSessionId, false);
        AnimState state = sessionMap != null ? sessionMap.get(el.id()) : null;
        if (state != null) {
            return state.getInterpolatedValues(System.currentTimeMillis());
        }
        AnimatedValues def = new AnimatedValues();
        def.set(el.opacity(), el.translateX(), el.translateY(), el.scaleX(), el.scaleY(), el.rotate(), el.hints().skew(), el.color(), el.bgColor());
        return def;
    }

    /**
     * {@link #getCurrentValues} と同じ値を、{@code scratch} へ書いて返す（状態が無い項目では確保しない）。ヒットテストが
     * 候補ごとに呼ぶので、割り当てを避けるための版。状態があるときは補間の結果（新しい {@link AnimatedValues}）を返す。
     */
    public static AnimatedValues currentValuesInto(String sessionId, DisplayItem el, AnimatedValues scratch) {
        String safeSessionId = (sessionId != null && !sessionId.isEmpty()) ? sessionId : "unknown";
        Map<String, AnimState> sessionMap = store.mapFor(safeSessionId, false);
        AnimState state = sessionMap != null ? sessionMap.get(el.id()) : null;
        if (state != null) return state.getInterpolatedValues(System.currentTimeMillis());
        scratch.set(el.opacity(), el.translateX(), el.translateY(), el.scaleX(), el.scaleY(), el.rotate(), el.hints().skew(), el.color(), el.bgColor());
        return scratch;
    }

    private static final Map<String, Long> LAST_CLEANUP = new java.util.concurrent.ConcurrentHashMap<>();

    public static void cleanup(String sessionId, List<DisplayItem> currentElements) {
        if (sessionId == null) return;
        Map<String, AnimState> sessionMap = store.mapFor(sessionId, false);
        if (sessionMap == null || sessionMap.isEmpty()) return;
        // 毎フレームやると要素数分の集合を作り直すので、掃除は 1 秒に 1 回でよい（消えた要素の状態が少し残るだけ）
        long now = System.nanoTime();
        Long last = LAST_CLEANUP.get(sessionId);
        if (last != null && now - last < 1_000_000_000L) return;
        LAST_CLEANUP.put(sessionId, now);

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
        if (propSpec == null) {
            return AnimationSpec.CH_OPACITY | AnimationSpec.CH_TRANSFORM | AnimationSpec.CH_COLOR | AnimationSpec.CH_BG_COLOR;
        }
        int len = propSpec.length();
        int i = 0;
        while (i < len && (propSpec.charAt(i) == ',' || Character.isWhitespace(propSpec.charAt(i)))) i++;
        if (i == len) {
            return AnimationSpec.CH_OPACITY | AnimationSpec.CH_TRANSFORM | AnimationSpec.CH_COLOR | AnimationSpec.CH_BG_COLOR;
        }
        int mask = 0;
        while (i < len) {
            int start = i;
            while (i < len) {
                char c = propSpec.charAt(i);
                if (c == ',' || Character.isWhitespace(c)) break;
                i++;
            }
            int tokLen = i - start;
            if (tokLen == 4 && propSpec.regionMatches(true, start, "none", 0, 4)) return 0;
            if (tokLen == 3 && propSpec.regionMatches(true, start, "all", 0, 3)) {
                return AnimationSpec.CH_OPACITY | AnimationSpec.CH_TRANSFORM | AnimationSpec.CH_COLOR | AnimationSpec.CH_BG_COLOR;
            }
            mask |= transitionPropertyBit(propSpec, start, tokLen);
            while (i < len && (propSpec.charAt(i) == ',' || Character.isWhitespace(propSpec.charAt(i)))) i++;
        }
        return mask;
    }

    private static int transitionPropertyBit(String s, int start, int len) {
        return switch (len) {
            case 5 -> s.regionMatches(true, start, "color", 0, 5) ? AnimationSpec.CH_COLOR
                    : (s.regionMatches(true, start, "scale", 0, 5) ? AnimationSpec.CH_TRANSFORM : 0);
            case 6 -> (s.regionMatches(true, start, "border", 0, 6)) ? AnimationSpec.CH_BG_COLOR
                    : (s.regionMatches(true, start, "rotate", 0, 6) ? AnimationSpec.CH_TRANSFORM : 0);
            case 7 -> s.regionMatches(true, start, "opacity", 0, 7) ? AnimationSpec.CH_OPACITY : 0;
            case 9 -> (s.regionMatches(true, start, "transform", 0, 9) || s.regionMatches(true, start, "translate", 0, 9))
                    ? AnimationSpec.CH_TRANSFORM : 0;
            case 10 -> s.regionMatches(true, start, "background", 0, 10) ? AnimationSpec.CH_BG_COLOR : 0;
            case 12 -> s.regionMatches(true, start, "border-color", 0, 12) ? AnimationSpec.CH_BG_COLOR : 0;
            case 16 -> (s.regionMatches(true, start, "background-color", 0, 16)
                    || s.regionMatches(true, start, "border-top-color", 0, 16))
                    ? AnimationSpec.CH_BG_COLOR : 0;
            case 17 -> s.regionMatches(true, start, "border-left-color", 0, 17) ? AnimationSpec.CH_BG_COLOR : 0;
            case 18 -> s.regionMatches(true, start, "border-right-color", 0, 18) ? AnimationSpec.CH_BG_COLOR : 0;
            case 19 -> s.regionMatches(true, start, "border-bottom-color", 0, 19) ? AnimationSpec.CH_BG_COLOR : 0;
            default -> 0;
        };
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
        /** 最後に目標と突き合わせた項目（同じインスタンスなら再比較しない）。 */
        DisplayItem seenItem;
        public float startOpacity, targetOpacity, baseTargetOpacity;
        public float startTx, targetTx, baseTx;
        public float startTy, targetTy, baseTy;
        public float startSx, targetSx, baseSx;
        public float startSy, targetSy, baseSy;
        public float startRot, targetRot, baseRot;
        public float startSkew, targetSkew, baseSkew;
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
            this.targetSkew = this.startSkew = this.baseSkew;
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

        /**
         * 今の時刻で、姿勢（変形）と不透明度が動いていないか。色だけが補間される項目（{@code @keyframes} が色のチャンネルだけを持つなど）は
         * true で、描画側は姿勢の行列を作り直さずに済む。動いているかもしれないなら false。
         */
        public boolean geometryStaticAt(long now) {
            final int geometry = AnimationSpec.CH_OPACITY | AnimationSpec.CH_TRANSFORM;
            if (currentAnimDef != null && !currentAnimDef.frames.isEmpty() && (currentAnimDef.animatedChannels & geometry) != 0) return false;
            return !(durationMs > 0 && transitionMask != 0 && now < startTime + delayMs + durationMs && (transitionMask & geometry) != 0);
        }

        /**
         * 今の時刻の姿勢が「変形なし・不透明度 1」で、かつ動いていないと言えるか（目標が中立で、姿勢のトランジション・キーフレームが動いていない）。
         * 色だけが動いていてもよい。
         */
        public boolean isNeutralGeometryAt(long now) {
            return geometryStaticAt(now) && targetTx == 0f && targetTy == 0f && targetSx == 1f && targetSy == 1f
                    && targetRot == 0f && targetSkew == 0f && targetOpacity == 1f;
        }

        /**
         * 今の時刻に補間されている値の種類。0 = 止まっている、1 = 色などの塗りだけ、2 = 変形（姿勢）が動く、
         * 3 = 不透明度が動く（描画側が層を要する）。トランジションは許可チャンネルではなく、開始値と目標値が
         * 実際に違うチャンネルだけを動くとみなす。キーフレームは、使うチャンネルで判定する。
         */
        public int motionKindAt(long now) {
            int kind = 0;
            if (durationMs > 0 && now < startTime + delayMs + durationMs) {
                kind = 1;
                if ((transitionMask & AnimationSpec.CH_TRANSFORM) != 0
                        && (Float.compare(startTx, targetTx) != 0 || Float.compare(startTy, targetTy) != 0
                        || Float.compare(startSx, targetSx) != 0 || Float.compare(startSy, targetSy) != 0
                        || Float.compare(startRot, targetRot) != 0 || Float.compare(startSkew, targetSkew) != 0)) {
                    kind = 2;
                }
                if ((transitionMask & AnimationSpec.CH_OPACITY) != 0 && Float.compare(startOpacity, targetOpacity) != 0) return 3;
            }
            if (currentAnimDef != null && isActiveAt(now)) {
                if (currentAnimDef.frames.isEmpty()) return Math.max(kind, 1);
                int ch = currentAnimDef.animatedChannels;
                if ((ch & AnimationSpec.CH_OPACITY) != 0) return 3;
                kind = Math.max(kind, (ch & AnimationSpec.CH_TRANSFORM) != 0 ? 2 : 1);
            }
            return kind;
        }

        public boolean hasTargetChanged(DisplayItem el) {
            return Float.compare(baseTargetOpacity, el.opacity()) != 0
                    || Float.compare(baseTx, el.translateX()) != 0
                    || Float.compare(baseTy, el.translateY()) != 0
                    || Float.compare(baseSx, el.scaleX()) != 0
                    || Float.compare(baseSy, el.scaleY()) != 0
                    || Float.compare(baseRot, el.rotate()) != 0
                    || Float.compare(baseSkew, el.hints().skew()) != 0
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
            this.baseSkew = el.hints().skew();
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
            this.targetSkew = el.hints().skew();
            this.targetColor = el.color();
            this.targetBgColor = el.bgColor();

            this.durationMs = el.transitionDuration();
            this.delayMs = 0;
            this.easing = EasingFunction.DEFAULT_EASING;
            String propSpec = "all";
            RenderHints hints = el.hints();
            this.delayMs = hints.transitionDelayMs();
            if (hints.transitionEasing() != null) this.easing = hints.transitionEasing();
            if (hints.transitionProperty() != null) propSpec = hints.transitionProperty();
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
                this.startSkew = current.skew();
            } else {
                this.startTx = this.targetTx;
                this.startTy = this.targetTy;
                this.startSx = this.targetSx;
                this.startSy = this.targetSy;
                this.startRot = this.targetRot;
                this.startSkew = this.targetSkew;
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
            float op, tx, ty, sx, sy, rot, skew;
            int col, bgCol;

            if (durationMs <= 0 || transitionMask == 0 || now >= startTime + delayMs + durationMs) {
                op = targetOpacity; tx = targetTx; ty = targetTy;
                sx = targetSx; sy = targetSy; rot = targetRot; skew = targetSkew;
                col = targetColor; bgCol = targetBgColor;
            } else if (now < startTime + delayMs) {
                op = startOpacity; tx = startTx; ty = startTy;
                sx = startSx; sy = startSy; rot = startRot; skew = startSkew;
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
                skew = lerp(startSkew, targetSkew, t);
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
                        skew = lerp(f0.skew(), f1.skew(), localT);
                    }
                    if ((currentAnimDef.animatedChannels & AnimationSpec.CH_COLOR) != 0) {
                        col = lerpColor(f0.color(), f1.color(), localT);
                    }
                    if ((currentAnimDef.animatedChannels & AnimationSpec.CH_BG_COLOR) != 0) {
                        bgCol = lerpColor(f0.bgColor(), f1.bgColor(), localT);
                    }
                }
            }

            cachedValues.set(op, tx, ty, sx, sy, rot, skew, col, bgCol);
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
         * 現在のキーフレーム進行度（0〜100）。適用外（delay 中で backwards 無し、または終了後で forwards 無し）は {@code Float.NaN}。
         * 計算は {@link AnimationTiming#progressPct} が持つ（再スタイルで動かす他のプロパティと共通）。
         */
        private static float computeKeyframePct(AnimationDef def, long elapsedFromStartMs) {
            return AnimationTiming.progressPct(def.durationMs, def.delayMs, def.iterationCount, def.direction,
                    def.fillMode, elapsedFromStartMs);
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

}