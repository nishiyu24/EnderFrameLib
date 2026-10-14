package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.core.model.UIElement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WebAnimator {

    private static final Map<String, AnimState> STATES = new ConcurrentHashMap<>();

    public static boolean isElementAnimating(String sessionId, String elementId, long now) {
        String key = sessionId + "_" + elementId;
        AnimState state = STATES.get(key);
        if (state == null) return false;
        if (state.durationMs > 0 && now < state.startTime + state.delayMs + state.durationMs) return true;
        if (state.currentAnimDef != null) {
            if (state.currentAnimDef.infinite) return true;
            return now < state.animStartTime + state.currentAnimDef.durationMs;
        }
        return false;
    }

    public static boolean isAnimating(String sessionId, long now) {
        String prefix = sessionId + "_";
        for (Map.Entry<String, AnimState> entry : STATES.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                AnimState state = entry.getValue();
                if (state.durationMs > 0 && now < state.startTime + state.delayMs + state.durationMs) return true;
                if (state.currentAnimDef != null) {
                    if (state.currentAnimDef.infinite) return true;
                    if (now < state.animStartTime + state.currentAnimDef.durationMs) return true;
                }
            }
        }
        return false;
    }

    private static AnimationDef parseAnimDef(String defStr) {
        if (defStr == null || defStr.isEmpty()) return null;
        try {
            String mainAnim = defStr.split("@@")[0];
            if (mainAnim.isEmpty()) return null;

            String[] parts = mainAnim.split("_");
            if (parts.length < 3) return null;
            AnimationDef def = new AnimationDef();
            def.durationMs = Math.max(1, Integer.parseInt(parts[0]));
            def.infinite = "1".equals(parts[1]);

            String[] frameStrs = parts[2].split("\\|");
            for (String fs : frameStrs) {
                if (fs.isEmpty()) continue;
                String[] kv = fs.split(":");
                if (kv.length < 2) continue;
                Keyframe kf = new Keyframe();
                kf.pct = Float.parseFloat(kv[0]);
                String[] vals = kv[1].split(",");
                if (vals.length >= 6) {
                    kf.tx = Float.parseFloat(vals[0]); kf.ty = Float.parseFloat(vals[1]);
                    kf.sx = Float.parseFloat(vals[2]); kf.sy = Float.parseFloat(vals[3]);
                    kf.rot = Float.parseFloat(vals[4]); kf.op = Float.parseFloat(vals[5]);
                    def.frames.add(kf);
                }
            }
            def.frames.sort(Comparator.comparingDouble(f -> f.pct));
            return def.frames.isEmpty() ? null : def;
        } catch (Exception e) { return null; }
    }

    public static AnimatedValues getValues(String sessionId, UIElement el, boolean isHovered) {
        return getValues(sessionId, el, isHovered, false, false);
    }

    public static AnimatedValues getValues(String sessionId, UIElement el, boolean isHovered, boolean isActive, boolean isFocused) {
        String key = sessionId + "_" + el.id();
        AnimState state = STATES.computeIfAbsent(key, k -> new AnimState(el));

        if (state.wasHovered != isHovered || state.wasActive != isActive || state.wasFocused != isFocused ||
                state.baseTargetOpacity != el.opacity() ||
                state.baseTx != el.translateX() || state.baseTy != el.translateY() ||
                state.baseSx != el.scaleX() || state.baseSy != el.scaleY() || state.baseRot != el.rotate() ||
                state.baseColor != el.color() || state.baseBgColor != el.bgColor() ||
                (el.animDef() != null && !el.animDef().equals(state.currentAnimDefStr))) {

            state.baseTargetOpacity = el.opacity(); state.baseTx = el.translateX(); state.baseTy = el.translateY();
            state.baseSx = el.scaleX(); state.baseSy = el.scaleY(); state.baseRot = el.rotate();
            state.baseColor = el.color(); state.baseBgColor = el.bgColor();
            state.updateTarget(el, isHovered, isActive, isFocused);
        }
        state.updateStyleCache(el);
        return state.getInterpolatedValues(System.currentTimeMillis());
    }

    public static AnimatedValues getCurrentValues(String sessionId, UIElement el) {
        String key = sessionId + "_" + el.id();
        AnimState state = STATES.get(key);
        if (state != null) return state.getInterpolatedValues(System.currentTimeMillis());
        AnimatedValues def = new AnimatedValues();
        def.set(el.opacity(), el.translateX(), el.translateY(), el.scaleX(), el.scaleY(), el.rotate(), el.color(), el.bgColor());
        return def;
    }

    public static void cleanup(String sessionId, List<UIElement> currentElements) {
        String prefix = sessionId + "_";
        STATES.keySet().removeIf(key -> {
            if (!key.startsWith(prefix)) return false;
            String id = key.substring(prefix.length());
            for (UIElement el : currentElements) {
                if (el.id().equals(id)) return false;
            }
            return true;
        });
    }

    static class Keyframe { float pct, tx, ty, sx, sy, rot, op; }
    static class AnimationDef { int durationMs; boolean infinite; List<Keyframe> frames = new ArrayList<>(); }

    static class PseudoTarget {
        float op, tx, ty, sx, sy, rot;
        int color, bgColor;
    }

    public static class AnimState {
        private final AnimatedValues cachedValues = new AnimatedValues();
        public float startOpacity, targetOpacity, baseTargetOpacity, startTx, targetTx, baseTx, startTy, targetTy, baseTy;
        public float startSx, targetSx, baseSx, startSy, targetSy, baseSy, startRot, targetRot, baseRot;
        public int startColor, targetColor, baseColor, startBgColor, targetBgColor, baseBgColor;
        public long startTime; public int durationMs; public int delayMs; public String easing;
        public String currentAnimDefStr = ""; public AnimationDef currentAnimDef = null; public long animStartTime = 0;

        public boolean wasHovered = false, wasActive = false, wasFocused = false;
        private PseudoTarget hoverTarget = null, activeTarget = null, focusTarget = null;

        public String cachedText = null; public Map<String, String> cachedEvents = null;
        public float[] clipData = null; public float[] radiusData = null;
        public float rangeVal = 50, rangeMin = 0, rangeMax = 100, circleThickness = 0;
        public int[] circleColors = null; public String textAlign = "left";

        public AnimState(UIElement el) {
            this.targetOpacity = el.opacity(); this.startOpacity = el.opacity(); this.baseTargetOpacity = el.opacity();
            this.targetTx = el.translateX(); this.startTx = el.translateX(); this.baseTx = el.translateX();
            this.targetTy = el.translateY(); this.startTy = el.translateY(); this.baseTy = el.translateY();
            this.targetSx = el.scaleX(); this.startSx = el.scaleX(); this.baseSx = el.scaleX();
            this.targetSy = el.scaleY(); this.startSy = el.scaleY(); this.baseSy = el.scaleY();
            this.targetRot = el.rotate(); this.startRot = el.rotate(); this.baseRot = el.rotate();
            this.targetColor = el.color(); this.startColor = el.color(); this.baseColor = el.color();
            this.targetBgColor = el.bgColor(); this.startBgColor = el.bgColor(); this.baseBgColor = el.bgColor();

            this.durationMs = 0; this.delayMs = 0; this.easing = "ease-out"; this.startTime = System.currentTimeMillis();
            this.currentAnimDefStr = el.animDef(); this.currentAnimDef = parseAnimDef(el.animDef());
            this.animStartTime = this.startTime; parsePseudoDefs(el.animDef()); updateStyleCache(el);
        }

        private static PseudoTarget extractMarker(String def, String marker) {
            if (def == null || !def.contains(marker)) return null;
            try {
                int idx = def.indexOf(marker) + marker.length();
                int nextIdx = def.indexOf("@@", idx);
                String sub = nextIdx == -1 ? def.substring(idx) : def.substring(idx, nextIdx);
                String[] vals = sub.split(":");
                if (vals.length >= 8) {
                    PseudoTarget pt = new PseudoTarget();
                    pt.op = Float.parseFloat(vals[0]); pt.tx = Float.parseFloat(vals[1]);
                    pt.ty = Float.parseFloat(vals[2]); pt.sx = Float.parseFloat(vals[3]);
                    pt.sy = Float.parseFloat(vals[4]); pt.rot = Float.parseFloat(vals[5]);
                    pt.color = Integer.parseInt(vals[6]); pt.bgColor = Integer.parseInt(vals[7]);
                    return pt;
                }
            } catch (Exception ignored) {}
            return null;
        }

        private void parsePseudoDefs(String def) {
            this.hoverTarget = extractMarker(def, "@@HOVER@@");
            this.activeTarget = extractMarker(def, "@@ACTIVE@@");
            this.focusTarget = extractMarker(def, "@@FOCUS@@");
        }

        public void updateStyleCache(UIElement el) {
            boolean textChanged = cachedText == null || !cachedText.equals(el.text());
            boolean eventsChanged = cachedEvents == null || !cachedEvents.equals(el.events());
            if (textChanged || eventsChanged) {
                cachedText = el.text(); cachedEvents = el.events();
                clipData = null;
                if (el.events() != null && el.events().containsKey("mc-clip")) {
                    String[] pts = el.events().get("mc-clip").split(",");
                    clipData = new float[8];
                    if (pts.length >= 4) { clipData[0] = Float.parseFloat(pts[0]); clipData[1] = Float.parseFloat(pts[1]); clipData[2] = Float.parseFloat(pts[2]); clipData[3] = Float.parseFloat(pts[3]); }
                    if (pts.length >= 8) { clipData[4] = Float.parseFloat(pts[4]); clipData[5] = Float.parseFloat(pts[5]); clipData[6] = Float.parseFloat(pts[6]); clipData[7] = Float.parseFloat(pts[7]); }
                    else if (pts.length >= 5) { clipData[4] = clipData[5] = clipData[6] = clipData[7] = Float.parseFloat(pts[4]); }
                }
                radiusData = new float[]{el.radius(), el.radius(), el.radius(), el.radius()};
                if (el.events() != null && el.events().containsKey("mc-radius")) {
                    String[] pts = el.events().get("mc-radius").split(",");
                    if (pts.length >= 4) { radiusData[0] = Float.parseFloat(pts[0]); radiusData[1] = Float.parseFloat(pts[1]); radiusData[2] = Float.parseFloat(pts[2]); radiusData[3] = Float.parseFloat(pts[3]); }
                }
                textAlign = el.events() != null ? el.events().getOrDefault("text-align", "left") : "left";
                if (textChanged && el.text() != null) {
                    if ("range".equals(el.type()) || "progress".equals(el.type())) {
                        String[] parts = el.text().split(":");
                        rangeVal = 50; rangeMin = 0; rangeMax = 100;
                        if (parts.length >= 3) {
                            try { rangeVal = Float.parseFloat(parts[0]); rangeMin = Float.parseFloat(parts[1]); rangeMax = Float.parseFloat(parts[2]); } catch (Exception ignored) {}
                        }
                    } else if ("circle_border".equals(el.type())) {
                        String[] parts = el.text().split(":");
                        circleThickness = 0; circleColors = null;
                        if (parts.length == 2) {
                            try {
                                circleThickness = Float.parseFloat(parts[0]);
                                String[] cols = parts[1].split(",");
                                if (cols.length == 4) {
                                    circleColors = new int[]{Integer.parseInt(cols[0]), Integer.parseInt(cols[1]), Integer.parseInt(cols[2]), Integer.parseInt(cols[3])};
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        }

        public void updateTarget(UIElement el, boolean isHovered, boolean isActive, boolean isFocused) {
            long now = System.currentTimeMillis();
            AnimatedValues current = getInterpolatedValues(now);

            this.startOpacity = current.opacity(); this.startTx = current.tx(); this.startTy = current.ty();
            this.startSx = current.sx(); this.startSy = current.sy(); this.startRot = current.rot();
            this.startColor = current.color(); this.startBgColor = current.bgColor();

            if (el.animDef() != null && !el.animDef().equals(this.currentAnimDefStr)) {
                this.currentAnimDefStr = el.animDef(); this.currentAnimDef = parseAnimDef(el.animDef());
                parsePseudoDefs(el.animDef()); this.animStartTime = now;
            }

            PseudoTarget chosen = null;
            if (isActive && this.activeTarget != null) chosen = this.activeTarget;
            else if (isFocused && this.focusTarget != null) chosen = this.focusTarget;
            else if (isHovered && this.hoverTarget != null) chosen = this.hoverTarget;

            if (chosen != null) {
                this.targetOpacity = chosen.op; this.targetTx = chosen.tx; this.targetTy = chosen.ty;
                this.targetSx = chosen.sx; this.targetSy = chosen.sy; this.targetRot = chosen.rot;
                this.targetColor = chosen.color; this.targetBgColor = chosen.bgColor;
            } else {
                this.targetOpacity = el.opacity(); this.targetTx = el.translateX(); this.targetTy = el.translateY();
                this.targetSx = el.scaleX(); this.targetSy = el.scaleY(); this.targetRot = el.rotate();
                this.targetColor = el.color(); this.targetBgColor = el.bgColor();
            }

            this.durationMs = el.transitionDuration();
            if (this.durationMs == 0 && (this.hoverTarget != null || this.activeTarget != null || this.focusTarget != null)) {
                this.durationMs = 150;
            }
            this.delayMs = 0; this.easing = "ease-out";
            if (el.events() != null) {
                if (el.events().containsKey("mc-delay")) { try { this.delayMs = Integer.parseInt(el.events().get("mc-delay")); } catch (Exception ignored) {} }
                if (el.events().containsKey("mc-easing")) { this.easing = el.events().get("mc-easing"); }
            }
            this.startTime = now;
            this.wasHovered = isHovered;
            this.wasActive = isActive;
            this.wasFocused = isFocused;
        }

        public AnimatedValues getInterpolatedValues(long now) {
            float op, tx, ty, sx, sy, rot; int col, bgCol;
            if (durationMs <= 0 || now >= startTime + delayMs + durationMs) {
                op = targetOpacity; tx = targetTx; ty = targetTy; sx = targetSx; sy = targetSy; rot = targetRot; col = targetColor; bgCol = targetBgColor;
            } else if (now < startTime + delayMs) {
                op = startOpacity; tx = startTx; ty = startTy; sx = startSx; sy = startSy; rot = startRot; col = startColor; bgCol = startBgColor;
            } else {
                float t = (float) (now - (startTime + delayMs)) / durationMs;
                if ("ease-in".equals(easing)) t = t * t * t;
                else if ("ease-in-out".equals(easing)) t = t < 0.5f ? 4 * t * t * t : 1.0f - (float) Math.pow(-2 * t + 2, 3) / 2.0f;
                else if (!"linear".equals(easing)) t = 1.0f - (float) Math.pow(1.0f - t, 3);

                op = lerp(startOpacity, targetOpacity, t); tx = lerp(startTx, targetTx, t); ty = lerp(startTy, targetTy, t);
                sx = lerp(startSx, targetSx, t); sy = lerp(startSy, targetSy, t); rot = lerp(startRot, targetRot, t);
                col = lerpColor(startColor, targetColor, t); bgCol = lerpColor(startBgColor, targetBgColor, t);
            }
            if (currentAnimDef != null && !currentAnimDef.frames.isEmpty()) {
                long elapsed = now - animStartTime;
                float progress = currentAnimDef.infinite ? (float) (elapsed % currentAnimDef.durationMs) / currentAnimDef.durationMs : Math.min(1.0f, (float) elapsed / currentAnimDef.durationMs);
                float pct = progress * 100f;
                Keyframe f0 = currentAnimDef.frames.getFirst(), f1 = currentAnimDef.frames.getLast();
                for (int i = 0; i < currentAnimDef.frames.size() - 1; i++) {
                    if (pct >= currentAnimDef.frames.get(i).pct && pct <= currentAnimDef.frames.get(i + 1).pct) {
                        f0 = currentAnimDef.frames.get(i); f1 = currentAnimDef.frames.get(i + 1); break;
                    }
                }
                float localT = f1.pct > f0.pct ? (pct - f0.pct) / (f1.pct - f0.pct) : 0f;
                op = lerp(f0.op, f1.op, localT); tx = lerp(f0.tx, f1.tx, localT); ty = lerp(f0.ty, f1.ty, localT);
                sx = lerp(f0.sx, f1.sx, localT); sy = lerp(f0.sy, f1.sy, localT); rot = lerp(f0.rot, f1.rot, localT);
            }
            cachedValues.set(op, tx, ty, sx, sy, rot, col, bgCol);
            cachedValues.clipData = this.clipData; cachedValues.radiusData = this.radiusData;
            cachedValues.rangeVal = this.rangeVal; cachedValues.rangeMin = this.rangeMin; cachedValues.rangeMax = this.rangeMax;
            cachedValues.circleThickness = this.circleThickness; cachedValues.circleColors = this.circleColors; cachedValues.textAlign = this.textAlign;
            return cachedValues;
        }

        private float lerp(float a, float b, float t) { return a + (b - a) * t; }
        private int lerpColor(int c1, int c2, float t) {
            float a1 = ((c1 >> 24) & 0xFF) / 255f, r1 = ((c1 >> 16) & 0xFF) / 255f, g1 = ((c1 >> 8) & 0xFF) / 255f, b1 = (c1 & 0xFF) / 255f;
            float a2 = ((c2 >> 24) & 0xFF) / 255f, r2 = ((c2 >> 16) & 0xFF) / 255f, g2 = ((c2 >> 8) & 0xFF) / 255f, b2 = (c2 & 0xFF) / 255f;
            int a = (int) (lerp(a1, a2, t) * 255) & 0xFF, r = (int) (lerp(r1, r2, t) * 255) & 0xFF, g = (int) (lerp(g1, g2, t) * 255) & 0xFF, b = (int) (lerp(b1, b2, t) * 255) & 0xFF;
            return (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    public static class AnimatedValues {
        public float[] clipData; public float[] radiusData;
        public float rangeVal, rangeMin, rangeMax, circleThickness;
        public int[] circleColors; public String textAlign;
        private float opacity, tx, ty, sx, sy, rot; private int color, bgColor;
        public void set(float op, float tx, float ty, float sx, float sy, float rot, int col, int bgCol) {
            this.opacity = op; this.tx = tx; this.ty = ty; this.sx = sx; this.sy = sy; this.rot = rot; this.color = col; this.bgColor = bgCol;
        }
        public float opacity() { return opacity; } public float tx() { return tx; } public float ty() { return ty; }
        public float sx() { return sx; } public float sy() { return sy; } public float rot() { return rot; }
        public int color() { return color; } public int bgColor() { return bgColor; }
    }
}