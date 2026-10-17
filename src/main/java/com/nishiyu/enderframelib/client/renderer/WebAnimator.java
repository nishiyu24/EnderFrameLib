package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.core.model.AnimationSpec;
import com.nishiyu.enderframelib.core.model.RenderKeys;
import com.nishiyu.enderframelib.core.model.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class WebAnimator {

    private static final Map<String, Map<String, AnimState>> SESSION_STATES = new ConcurrentHashMap<>();

    public static boolean isElementAnimating(String sessionId, String elementId, long now) {
        if (sessionId == null || elementId == null) return false;
        Map<String, AnimState> states = SESSION_STATES.get(sessionId);
        if (states == null) return false;
        AnimState state = states.get(elementId);
        return state != null && state.isActiveAt(now);
    }

    public static boolean isAnimating(String sessionId, long now) {
        if (sessionId == null) return false;
        Map<String, AnimState> states = SESSION_STATES.get(sessionId);
        if (states == null || states.isEmpty()) return false;
        for (AnimState state : states.values()) {
            if (state.isActiveAt(now)) {
                return true;
            }
        }
        return false;
    }

    /**
     * UIRenderer が解決した {@link AnimationSpec} を、補間しやすい
     * 進行度順のキーフレーム列に正規化する。
     *
     * <p>以前はここで {@code "1000_1_0:0,0,1,1,0,1|..."} という文字列を
     * 毎回パースし直していた。
     */
    private static AnimationDef normalize(AnimationSpec spec) {
        if (spec == null || spec.isEmpty()) return null;
        AnimationDef def = new AnimationDef();
        def.durationMs = spec.durationMs();
        def.infinite = spec.infinite();
        def.frames.addAll(spec.frames());
        def.frames.sort(Comparator.comparingDouble(AnimationSpec.Keyframe::pct));
        return def;
    }

    public static AnimatedValues getValues(String sessionId, UIElement el, boolean isHovered) {
        return getValues(sessionId, el, isHovered, false, false);
    }

    public static AnimatedValues getValues(String sessionId, UIElement el, boolean isHovered, boolean isActive, boolean isFocused) {
        String safeSessionId = (sessionId != null && !sessionId.isEmpty()) ? sessionId : "unknown";
        Map<String, AnimState> sessionMap = SESSION_STATES.computeIfAbsent(safeSessionId, k -> new ConcurrentHashMap<>());
        AnimState state = sessionMap.computeIfAbsent(el.id(), k -> new AnimState(el));

        if (state.hasTargetChanged(el, isHovered, isActive, isFocused)) {
            state.syncBaseTargets(el);
            state.updateTarget(el, isHovered, isActive, isFocused);
        }
        state.updateStyleCache(el);
        return state.getInterpolatedValues(System.currentTimeMillis());
    }

    public static AnimatedValues getCurrentValues(String sessionId, UIElement el) {
        String safeSessionId = (sessionId != null && !sessionId.isEmpty()) ? sessionId : "unknown";
        Map<String, AnimState> sessionMap = SESSION_STATES.get(safeSessionId);
        AnimState state = sessionMap != null ? sessionMap.get(el.id()) : null;
        if (state != null) {
            return state.getInterpolatedValues(System.currentTimeMillis());
        }
        AnimatedValues def = new AnimatedValues();
        def.set(el.opacity(), el.translateX(), el.translateY(), el.scaleX(), el.scaleY(), el.rotate(), el.color(), el.bgColor());
        return def;
    }

    public static void cleanup(String sessionId, List<UIElement> currentElements) {
        if (sessionId == null) return;
        Map<String, AnimState> sessionMap = SESSION_STATES.get(sessionId);
        if (sessionMap == null || sessionMap.isEmpty()) return;

        Set<String> activeIds = new HashSet<>(currentElements.size());
        for (UIElement el : currentElements) {
            activeIds.add(el.id());
        }
        sessionMap.keySet().retainAll(activeIds);
    }

    public static void clearSession(String sessionId) {
        if (sessionId != null) {
            SESSION_STATES.remove(sessionId);
        }
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

    /** 進行度順に並べ替えたキーフレーム列。{@link AnimationSpec} から作る。 */
    static class AnimationDef {
        int durationMs;
        boolean infinite;
        final List<AnimationSpec.Keyframe> frames = new ArrayList<>();
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
        public AnimationSpec currentAnimSpec = AnimationSpec.NONE;
        public AnimationDef currentAnimDef = null;
        public long animStartTime = 0;

        public boolean wasHovered = false, wasActive = false, wasFocused = false;
        private PseudoTarget hoverTarget = null, activeTarget = null, focusTarget = null;

        public String cachedText = null;
        public Map<String, String> cachedEvents = null;
        public float[] clipData = null;
        public float[] radiusData = null;
        public float rangeVal = 50, rangeMin = 0, rangeMax = 100, circleThickness = 0;
        public int[] circleColors = null;
        public String textAlign = "left";

        public AnimState(UIElement el) {
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
            this.easing = "ease-out";
            this.startTime = System.currentTimeMillis();
            this.currentAnimSpec = el.animDef() != null ? el.animDef() : AnimationSpec.NONE;
            this.currentAnimDef = normalize(this.currentAnimSpec);
            this.animStartTime = this.startTime;
            syncPseudoTargets(el);
            updateStyleCache(el);
        }

        public boolean isActiveAt(long now) {
            if (durationMs > 0 && now < startTime + delayMs + durationMs) return true;
            if (currentAnimDef != null) {
                return currentAnimDef.infinite || now < animStartTime + currentAnimDef.durationMs;
            }
            return false;
        }

        public boolean hasTargetChanged(UIElement el, boolean isHovered, boolean isActive, boolean isFocused) {
            return wasHovered != isHovered || wasActive != isActive || wasFocused != isFocused
                    || baseTargetOpacity != el.opacity()
                    || baseTx != el.translateX() || baseTy != el.translateY()
                    || baseSx != el.scaleX() || baseSy != el.scaleY() || baseRot != el.rotate()
                    || baseColor != el.color() || baseBgColor != el.bgColor()
                    || !Objects.equals(el.animDef(), currentAnimSpec);
        }

        public void syncBaseTargets(UIElement el) {
            this.baseTargetOpacity = el.opacity();
            this.baseTx = el.translateX();
            this.baseTy = el.translateY();
            this.baseSx = el.scaleX();
            this.baseSy = el.scaleY();
            this.baseRot = el.rotate();
            this.baseColor = el.color();
            this.baseBgColor = el.bgColor();
        }

        private void syncPseudoTargets(UIElement el) {
            if (el.meta() != null && el.meta().pseudoStates() != null) {
                PseudoStates ps = el.meta().pseudoStates();
                this.hoverTarget = ps.hover();
                this.activeTarget = ps.active();
                this.focusTarget = ps.focus();
            } else {
                this.hoverTarget = null;
                this.activeTarget = null;
                this.focusTarget = null;
            }
        }

        public void updateStyleCache(UIElement el) {
            boolean textChanged = !Objects.equals(cachedText, el.text());
            boolean eventsChanged = !Objects.equals(cachedEvents, el.events());
            if (!textChanged && !eventsChanged) return;

            cachedText = el.text();
            cachedEvents = el.events();
            UIElement.RenderMeta meta = el.meta();

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

            textAlign = el.events() != null ? el.events().getOrDefault("text-align", "left") : "left";

            if (textChanged && el.text() != null) {
                if (el.type() == ElementType.RANGE) {
                    String[] parts = el.text().split(":");
                    rangeVal = 50f; rangeMin = 0f; rangeMax = 100f;
                    if (parts.length >= 3) {
                        rangeVal = parseFloatSafe(parts[0], 50f);
                        rangeMin = parseFloatSafe(parts[1], 0f);
                        rangeMax = parseFloatSafe(parts[2], 100f);
                    }
                } else if (el.type() == ElementType.CIRCLE_BORDER) {
                    UIElement.CircleBorderSpec cb = meta != null ? meta.circleBorder() : null;
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

        public void updateTarget(UIElement el, boolean isHovered, boolean isActive, boolean isFocused) {
            long now = System.currentTimeMillis();
            AnimatedValues current = getInterpolatedValues(now);

            this.startOpacity = current.opacity();
            this.startTx = current.tx();
            this.startTy = current.ty();
            this.startSx = current.sx();
            this.startSy = current.sy();
            this.startRot = current.rot();
            this.startColor = current.color();
            this.startBgColor = current.bgColor();

            if (!Objects.equals(el.animDef(), this.currentAnimSpec)) {
                this.currentAnimSpec = el.animDef() != null ? el.animDef() : AnimationSpec.NONE;
                this.currentAnimDef = normalize(this.currentAnimSpec);
                this.animStartTime = now;
            }
            syncPseudoTargets(el);

            PseudoTarget chosen = null;
            if (isActive && this.activeTarget != null) chosen = this.activeTarget;
            else if (isFocused && this.focusTarget != null) chosen = this.focusTarget;
            else if (isHovered && this.hoverTarget != null) chosen = this.hoverTarget;

            if (chosen != null) {
                this.targetOpacity = chosen.op();
                this.targetTx = chosen.tx();
                this.targetTy = chosen.ty();
                this.targetSx = chosen.sx();
                this.targetSy = chosen.sy();
                this.targetRot = chosen.rot();
                this.targetColor = chosen.color();
                this.targetBgColor = chosen.bgColor();
            } else {
                this.targetOpacity = el.opacity();
                this.targetTx = el.translateX();
                this.targetTy = el.translateY();
                this.targetSx = el.scaleX();
                this.targetSy = el.scaleY();
                this.targetRot = el.rotate();
                this.targetColor = el.color();
                this.targetBgColor = el.bgColor();
            }

            this.durationMs = el.transitionDuration();
            if (this.durationMs == 0 && (this.hoverTarget != null || this.activeTarget != null || this.focusTarget != null)) {
                this.durationMs = 150;
            }
            this.delayMs = 0;
            this.easing = "ease-out";
            if (el.events() != null) {
                if (el.events().containsKey(RenderKeys.MC_DELAY)) {
                    this.delayMs = parseIntSafe(el.events().get(RenderKeys.MC_DELAY), 0);
                }
                if (el.events().containsKey(RenderKeys.MC_EASING)) {
                    this.easing = el.events().get(RenderKeys.MC_EASING);
                }
            }
            this.startTime = now;
            this.wasHovered = isHovered;
            this.wasActive = isActive;
            this.wasFocused = isFocused;
        }

        public AnimatedValues getInterpolatedValues(long now) {
            float op, tx, ty, sx, sy, rot;
            int col, bgCol;

            if (durationMs <= 0 || now >= startTime + delayMs + durationMs) {
                op = targetOpacity; tx = targetTx; ty = targetTy;
                sx = targetSx; sy = targetSy; rot = targetRot;
                col = targetColor; bgCol = targetBgColor;
            } else if (now < startTime + delayMs) {
                op = startOpacity; tx = startTx; ty = startTy;
                sx = startSx; sy = startSy; rot = startRot;
                col = startColor; bgCol = startBgColor;
            } else {
                float t = (float) (now - (startTime + delayMs)) / durationMs;
                t = applyEasing(t, easing);

                op = lerp(startOpacity, targetOpacity, t);
                tx = lerp(startTx, targetTx, t);
                ty = lerp(startTy, targetTy, t);
                sx = lerp(startSx, targetSx, t);
                sy = lerp(startSy, targetSy, t);
                rot = lerp(startRot, targetRot, t);
                col = lerpColor(startColor, targetColor, t);
                bgCol = lerpColor(startBgColor, targetBgColor, t);
            }

            if (currentAnimDef != null && !currentAnimDef.frames.isEmpty()) {
                long elapsed = now - animStartTime;
                float progress = currentAnimDef.infinite
                        ? (float) (elapsed % currentAnimDef.durationMs) / currentAnimDef.durationMs
                        : Math.min(1.0f, (float) elapsed / currentAnimDef.durationMs);
                float pct = progress * 100f;
                AnimationSpec.Keyframe f0 = currentAnimDef.frames.getFirst();
                AnimationSpec.Keyframe f1 = currentAnimDef.frames.getLast();
                for (int i = 0; i < currentAnimDef.frames.size() - 1; i++) {
                    AnimationSpec.Keyframe cur = currentAnimDef.frames.get(i);
                    AnimationSpec.Keyframe next = currentAnimDef.frames.get(i + 1);
                    if (pct >= cur.pct() && pct <= next.pct()) {
                        f0 = cur;
                        f1 = next;
                        break;
                    }
                }
                float localT = f1.pct() > f0.pct() ? (pct - f0.pct()) / (f1.pct() - f0.pct()) : 0f;
                op = lerp(f0.op(), f1.op(), localT);
                tx = lerp(f0.tx(), f1.tx(), localT);
                ty = lerp(f0.ty(), f1.ty(), localT);
                sx = lerp(f0.sx(), f1.sx(), localT);
                sy = lerp(f0.sy(), f1.sy(), localT);
                rot = lerp(f0.rot(), f1.rot(), localT);
            }

            cachedValues.set(op, tx, ty, sx, sy, rot, col, bgCol);
            cachedValues.clipData = this.clipData;
            cachedValues.radiusData = this.radiusData;
            cachedValues.rangeVal = this.rangeVal;
            cachedValues.rangeMin = this.rangeMin;
            cachedValues.rangeMax = this.rangeMax;
            cachedValues.circleThickness = this.circleThickness;
            cachedValues.circleColors = this.circleColors;
            cachedValues.textAlign = this.textAlign;
            return cachedValues;
        }

        private static float applyEasing(float t, String easing) {
            return switch (easing) {
                case "linear" -> t;
                case "ease-in" -> t * t * t;
                case "ease-in-out" -> t < 0.5f ? 4f * t * t * t : 1.0f - (float) Math.pow(-2f * t + 2f, 3) / 2.0f;
                default -> 1.0f - (float) Math.pow(1.0f - t, 3);
            };
        }

        private static float lerp(float a, float b, float t) {
            return a + (b - a) * t;
        }

        private static int lerpColor(int c1, int c2, float t) {
            if (c1 == c2) return c1;
            float a1 = ((c1 >>> 24) & 0xFF) / 255f, r1 = ((c1 >>> 16) & 0xFF) / 255f, g1 = ((c1 >>> 8) & 0xFF) / 255f, b1 = (c1 & 0xFF) / 255f;
            float a2 = ((c2 >>> 24) & 0xFF) / 255f, r2 = ((c2 >>> 16) & 0xFF) / 255f, g2 = ((c2 >>> 8) & 0xFF) / 255f, b2 = (c2 & 0xFF) / 255f;
            int a = (int) (lerp(a1, a2, t) * 255f) & 0xFF;
            int r = (int) (lerp(r1, r2, t) * 255f) & 0xFF;
            int g = (int) (lerp(g1, g2, t) * 255f) & 0xFF;
            int b = (int) (lerp(b1, b2, t) * 255f) & 0xFF;
            return (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    public static class AnimatedValues {
        public float[] clipData;
        public float[] radiusData;
        public float rangeVal, rangeMin, rangeMax, circleThickness;
        public int[] circleColors;
        public String textAlign;
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