package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.tree.PaintBox;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CustomProperties;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import com.nishiyu.enderframelib.core.paint.model.ShaderGradient;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import static com.nishiyu.enderframelib.core.paint.BoxAnimation.applyTransitionEvents;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.putFontEvents;
import com.nishiyu.enderframelib.core.paint.BoxAnimation.TransitionSpec;

/**
 * {@code DisplayListBuilder} が 1 つの要素の部品群に共通して付ける情報。DOM 由来の属性（{@code onclick}・{@code data-*}・書体）と、
 * 型付きの描画の付帯情報（{@link RenderHints}）を分けて持つ。部品を出すときに前者は {@code DisplayItem.events}、後者は
 * {@code RenderMeta.hints} へ入る。
 */
final class ItemProps {

        private Map<String, String> baseAttrs;
        private Map<String, String> ownAttrs;
        private String parentId;
        private long parentKey;
        private String title;
        private boolean scrollable;
        private float scrollWidth;
        private float scrollHeight;
        private float shadowBlur;
        private ShadowShape shadowShape;
        private ShaderGradient shaderGradient;
        private boolean shaderGradientLocal;
        private boolean pointerEventsNone;
        private int transitionDelayMs;
        private String transitionEasing;
        private String transitionProperty;
        private float skew;
        private boolean relativeOrigin;
        private String fontFamily;
        private RenderHints cachedHints = RenderHints.NONE;
        private RenderHints reuseCandidate;
        /**
         * 前に出した項目の属性（無ければ null）。{@link #put} が前と同じ値を入れる限り、表を作らず「前と同じ」とだけ覚え
         * （{@link #pendingKeys}）、全部一致したら {@link #attrs()} が前の表をそのまま返す（hover のたびに属性の表を作り直さない。B9）。
         */
        private Map<String, String> candidateAttrs;
        private final String[] pendingKeys = new String[4];
        private int pendingCount;

        ItemProps() {
        }

        private ItemProps(Map<String, String> baseAttrs, Map<String, String> attrs, ItemProps src) {
            this.baseAttrs = baseAttrs;
            this.ownAttrs = attrs;
            this.parentId = src.parentId;
            this.parentKey = src.parentKey;
            this.title = src.title;
            this.scrollable = src.scrollable;
            this.scrollWidth = src.scrollWidth;
            this.scrollHeight = src.scrollHeight;
            this.shadowBlur = src.shadowBlur;
            this.shadowShape = src.shadowShape;
            this.shaderGradient = src.shaderGradient;
            this.shaderGradientLocal = src.shaderGradientLocal;
            this.pointerEventsNone = src.pointerEventsNone;
            this.transitionDelayMs = src.transitionDelayMs;
            this.transitionEasing = src.transitionEasing;
            this.transitionProperty = src.transitionProperty;
            this.skew = src.skew;
            this.relativeOrigin = src.relativeOrigin;
            this.fontFamily = src.fontFamily;
            this.cachedHints = src.cachedHints;
            this.reuseCandidate = src.reuseCandidate;
        }

        ItemProps reset() {
            this.baseAttrs = null;
            this.ownAttrs = null;
            this.parentId = null;
            this.parentKey = 0L;
            this.title = null;
            this.scrollable = false;
            this.scrollWidth = 0f;
            this.scrollHeight = 0f;
            this.shadowBlur = 0f;
            this.shadowShape = null;
            this.shaderGradient = null;
            this.shaderGradientLocal = false;
            this.pointerEventsNone = false;
            this.transitionDelayMs = 0;
            this.transitionEasing = null;
            this.transitionProperty = null;
            this.skew = 0f;
            this.relativeOrigin = false;
            this.fontFamily = null;
            this.cachedHints = RenderHints.NONE;
            this.reuseCandidate = null;
            this.candidateAttrs = null;
            this.pendingCount = 0;
            return this;
        }

        static ItemProps parent(String parentId) {
            return new ItemProps().parentId(parentId);
        }

        ItemProps copy() {
            materializePending();
            return new ItemProps(baseAttrs, ownAttrs == null || ownAttrs.isEmpty() ? null : new HashMap<>(ownAttrs), this);
        }

        /** {@code src} と同じ属性・ヒントを借り受ける作業用複製（呼び出し元で {@code put} しないこと）。 */
        void copyFrom(ItemProps src) {
            src.materializePending();
            this.candidateAttrs = null;
            this.pendingCount = 0;
            this.baseAttrs = src.baseAttrs;
            this.ownAttrs = (src.ownAttrs == null || src.ownAttrs.isEmpty()) ? null : src.ownAttrs;
            this.parentId = src.parentId;
            this.parentKey = src.parentKey;
            this.title = src.title;
            this.scrollable = src.scrollable;
            this.scrollWidth = src.scrollWidth;
            this.scrollHeight = src.scrollHeight;
            this.shadowBlur = src.shadowBlur;
            this.shadowShape = src.shadowShape;
            this.shaderGradient = src.shaderGradient;
            this.shaderGradientLocal = src.shaderGradientLocal;
            this.pointerEventsNone = src.pointerEventsNone;
            this.transitionDelayMs = src.transitionDelayMs;
            this.transitionEasing = src.transitionEasing;
            this.transitionProperty = src.transitionProperty;
            this.skew = src.skew;
            this.relativeOrigin = src.relativeOrigin;
            this.fontFamily = src.fontFamily;
            this.cachedHints = src.cachedHints;
            this.reuseCandidate = src.reuseCandidate;
        }

        void reuseCandidate(RenderHints candidate) {
            this.reuseCandidate = candidate;
        }

        // --- DOM 由来の属性 ---

        /** 前の項目の属性を使い回す候補にする（この後の {@link #put} が前と同じなら表を作らない）。 */
        void reuseAttrs(Map<String, String> candidate) {
            this.candidateAttrs = candidate != null && !candidate.isEmpty() ? candidate : null;
        }

        /** 「前と同じ」とだけ覚えた {@code put} を、実際の表へ書く（表を持たねばならなくなったとき）。 */
        private void materializePending() {
            if (pendingCount == 0) return;
            int n = pendingCount;
            pendingCount = 0;
            if (ownAttrs == null) ownAttrs = (baseAttrs != null && !baseAttrs.isEmpty()) ? new HashMap<>(baseAttrs) : new HashMap<>(8);
            for (int i = 0; i < n; i++) ownAttrs.put(pendingKeys[i], candidateAttrs.get(pendingKeys[i]));
        }

        private Map<String, String> ensureAttrs() {
            materializePending();
            if (ownAttrs == null) {
                ownAttrs = (baseAttrs != null && !baseAttrs.isEmpty()) ? new HashMap<>(baseAttrs) : new HashMap<>(4);
            }
            return ownAttrs;
        }

        void putBaseAttrs(Map<String, String> immutableMap) {
            if (immutableMap == null || immutableMap.isEmpty()) return;
            if (ownAttrs == null && baseAttrs == null && pendingCount == 0) {
                baseAttrs = immutableMap;
            } else {
                ensureAttrs().putAll(immutableMap);
            }
        }

        void put(String key, String value) {
            if (ownAttrs == null && candidateAttrs != null && pendingCount < pendingKeys.length
                    && value != null && value.equals(candidateAttrs.get(key)) && !pendingHas(key)) {
                pendingKeys[pendingCount++] = key;
                return;
            }
            ensureAttrs().put(key, value);
        }

        private boolean pendingHas(String key) {
            for (int i = 0; i < pendingCount; i++) if (pendingKeys[i].equals(key)) return true;
            return false;
        }

        String get(String key) {
            if (pendingCount > 0 && ownAttrs == null && pendingHas(key)) return candidateAttrs.get(key);
            if (ownAttrs != null) return ownAttrs.get(key);
            return baseAttrs != null ? baseAttrs.get(key) : null;
        }

        boolean containsKey(String key) {
            if (pendingCount > 0 && ownAttrs == null && pendingHas(key)) return true;
            return (ownAttrs != null && ownAttrs.containsKey(key))
                    || (ownAttrs == null && baseAttrs != null && baseAttrs.containsKey(key));
        }

        Map<String, String> attrs() {
            if (pendingCount > 0 && ownAttrs == null) {
                // 入れたものが全部前の表の項目で、基底の表（書体など）の残りも前の表と同じで、数も合えば、前の表そのもの（作らない）
                int baseOnly = 0;
                boolean same = true;
                if (baseAttrs != null) {
                    for (Map.Entry<String, String> e : baseAttrs.entrySet()) {
                        if (pendingHas(e.getKey())) continue;
                        baseOnly++;
                        if (!e.getValue().equals(candidateAttrs.get(e.getKey()))) { same = false; break; }
                    }
                }
                if (same && baseOnly + pendingCount == candidateAttrs.size()) return candidateAttrs;
                materializePending();
            }
            if (ownAttrs != null && !ownAttrs.isEmpty()) {
                if (candidateAttrs != null && ownAttrs.equals(candidateAttrs)) return candidateAttrs;
                return ownAttrs;
            }
            if (baseAttrs != null && !baseAttrs.isEmpty()) return baseAttrs;
            return Map.of();
        }

        // --- 型付きの描画の付帯情報 ---

        RenderHints hints() {
            return hintsReuse(reuseCandidate);
        }

        RenderHints hintsReuse(RenderHints candidate) {
            RenderHints h = cachedHints;
            if (h != null) return h;
            if (parentId == null && parentKey == 0L && title == null && !scrollable && scrollWidth == 0f
                    && scrollHeight == 0f && shadowBlur == 0f && shadowShape == null && shaderGradient == null
                    && !pointerEventsNone && transitionDelayMs == 0 && transitionEasing == null
                    && transitionProperty == null && skew == 0f && !relativeOrigin && fontFamily == null) {
                cachedHints = RenderHints.NONE;
                return RenderHints.NONE;
            }
            if (candidate != null && candidate.layer() == null
                    && (parentKey != 0L
                        ? candidate.parentKey() == parentKey
                        : (parentId == null || parentId.isEmpty() ? candidate.parentKey() == 0L : true))
                    && candidate.scrollable() == scrollable
                    && Float.compare(candidate.scrollWidth(), scrollWidth) == 0
                    && Float.compare(candidate.scrollHeight(), scrollHeight) == 0
                    && Float.compare(candidate.shadowBlur(), shadowBlur) == 0
                    && candidate.pointerEventsNone() == pointerEventsNone
                    && candidate.transitionDelayMs() == transitionDelayMs
                    && Float.compare(candidate.skew(), skew) == 0
                    && candidate.relativeOrigin() == relativeOrigin
                    && java.util.Objects.equals(candidate.parentId(), parentId)
                    && java.util.Objects.equals(candidate.title(), title)
                    && java.util.Objects.equals(candidate.shadowShape(), shadowShape)
                    && java.util.Objects.equals(candidate.shaderGradient(), shaderGradient)
                    && java.util.Objects.equals(candidate.transitionEasing(), transitionEasing)
                    && java.util.Objects.equals(candidate.transitionProperty(), transitionProperty)
                    && java.util.Objects.equals(candidate.fontFamily(), fontFamily)) {
                cachedHints = candidate;
                return candidate;
            }
            h = new RenderHints(parentId, parentKey, title, scrollable, scrollWidth, scrollHeight,
                    shadowBlur, shadowShape, shaderGradient, pointerEventsNone, transitionDelayMs,
                    transitionEasing, transitionProperty, null, skew, relativeOrigin, fontFamily);
            cachedHints = h;
            return h;
        }

        ItemProps fontFamily(String family) {
            if (!java.util.Objects.equals(this.fontFamily, family)) {
                this.fontFamily = family;
                this.cachedHints = null;
            }
            return this;
        }

        ItemProps parentId(String id) {
            if (!java.util.Objects.equals(this.parentId, id)) {
                this.parentId = id;
                this.parentKey = 0L;
                this.cachedHints = null;
            } else if (this.parentKey == 0L && id != null && !id.isEmpty()) {
                this.cachedHints = null;
            }
            return this;
        }

        ItemProps parentId(String id, long key) {
            if (!java.util.Objects.equals(this.parentId, id) || this.parentKey != key) {
                this.parentId = id;
                this.parentKey = key;
                this.cachedHints = null;
            }
            return this;
        }

        ItemProps title(String title) {
            if (!java.util.Objects.equals(this.title, title)) {
                this.title = title;
                this.cachedHints = null;
            }
            return this;
        }

        ItemProps scroll(float contentWidth, float contentHeight) {
            if (!scrollable || scrollWidth != contentWidth || scrollHeight != contentHeight) {
                this.scrollable = true;
                this.scrollWidth = contentWidth;
                this.scrollHeight = contentHeight;
                this.cachedHints = null;
            }
            return this;
        }

        ItemProps shadowBlur(float blur) {
            if (this.shadowBlur != blur) {
                this.shadowBlur = blur;
                this.cachedHints = null;
            }
            return this;
        }

        ItemProps shadowShape(ShadowShape shape) {
            if (!java.util.Objects.equals(this.shadowShape, shape)) {
                this.shadowShape = shape;
                this.cachedHints = null;
            }
            return this;
        }

        ItemProps shaderGradient(ShaderGradient gradient) {
            return shaderGradient(gradient, false);
        }

        ItemProps shaderGradient(ShaderGradient gradient, boolean local) {
            if (!java.util.Objects.equals(this.shaderGradient, gradient)) {
                this.shaderGradient = gradient;
                this.cachedHints = null;
            }
            this.shaderGradientLocal = local;
            return this;
        }

        boolean isShaderGradientLocal() {
            return shaderGradientLocal;
        }

        ItemProps pointerEventsNone() {
            if (!this.pointerEventsNone) {
                this.pointerEventsNone = true;
                this.cachedHints = null;
            }
            return this;
        }

        ItemProps relativeOrigin() {
            if (!this.relativeOrigin) {
                this.relativeOrigin = true;
                this.cachedHints = null;
            }
            return this;
        }

        ItemProps skew(float degrees) {
            if (this.skew != degrees) {
                this.skew = degrees;
                this.cachedHints = null;
            }
            return this;
        }

        ItemProps transition(int delayMs, String easing, String property) {
            if (this.transitionDelayMs != delayMs
                    || !java.util.Objects.equals(this.transitionEasing, easing)
                    || !java.util.Objects.equals(this.transitionProperty, property)) {
                this.transitionDelayMs = delayMs;
                this.transitionEasing = easing;
                this.transitionProperty = property;
                this.cachedHints = null;
            }
            return this;
        }

    static final String[] PASSTHROUGH_DATA_ATTRS = {
            "data-sound", "data-sound-pitch", "draggable", "data-count",
            "data-ghost-slot", "data-pan-zoom", "for", "tabindex"
    };

    private static final Object STYLE_HANDLERS = new Object();

    private static final String[] NO_HANDLERS = new String[0];

    /** スタイルの {@code on*} の名前（小文字）と値を {@code [名前, 値, 名前, 値, …]} で。 */
    private static String[] styleHandlersOf(PaintBox box) {
        CustomProperties custom = box.style().typed().custom;
        if (custom == null) return NO_HANDLERS;
        java.util.List<String> out = null;
        for (Map.Entry<String, String> entry : custom.asMap().entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if (key.startsWith("on")) {
                if (out == null) out = new java.util.ArrayList<>();
                out.add(key);
                out.add(entry.getValue());
            }
        }
        return out == null ? NO_HANDLERS : out.toArray(new String[0]);
    }

    static ItemProps forParts(PaintBox box, String id, TransitionSpec transSpec) {
        return populateForParts(new ItemProps(), box, id, transSpec);
    }

    static ItemProps populateForParts(ItemProps partsEvents, PaintBox box, String id, TransitionSpec transSpec) {
        return populateForParts(partsEvents, box, id, transSpec, null);
    }

    /** @param prevAttrs 前に出した部品の属性（無ければ null）。同じ値なら属性の表を作り直さず使い回す。 */
    static ItemProps populateForParts(ItemProps partsEvents, PaintBox box, String id, TransitionSpec transSpec,
                                      Map<String, String> prevAttrs) {
        partsEvents.reset();
        partsEvents.reuseAttrs(prevAttrs);
        long nodeKey = box.node() != null ? DisplayItem.nodeKeyOf(box.node().paintHandle()) : 0L;
        partsEvents.parentId(id, nodeKey);
        applyTransitionEvents(partsEvents, transSpec);
        String fontFamily = box.style().typed().inherited.fontFamily;
        if (fontFamily != null) partsEvents.fontFamily(fontFamily);
        putFontEvents(box, partsEvents);
        Map<String, String> nodeAttrs = box.values().attrs();
        if (!nodeAttrs.isEmpty()) {
            if (nodeAttrs.containsKey("title")) partsEvents.title(nodeAttrs.get("title"));
            else if (nodeAttrs.containsKey("data-tooltip")) partsEvents.title(nodeAttrs.get("data-tooltip"));

            for (String attr : PASSTHROUGH_DATA_ATTRS) {
                if (nodeAttrs.containsKey(attr)) {
                    partsEvents.put(attr, nodeAttrs.get(attr));
                }
            }
        }

        ComputedStyle ax = box.axisStyle();
        Overflow overflowX = ax.overflowX();
        Overflow overflowY = ax.overflowY();
        boolean isScrollable = overflowX == Overflow.SCROLL || overflowX == Overflow.AUTO
                || overflowY == Overflow.SCROLL || overflowY == Overflow.AUTO
                || "root".equals(box.node().tag) || "html".equals(box.node().tag) || "body".equals(box.node().tag);
        if (isScrollable) {
            partsEvents.scroll(box.scrollWidth(), box.scrollHeight());
        }

        if (box.values().hasEventHandlers()) {
            nodeAttrs.forEach((k, v) -> {
                if (k != null && k.regionMatches(true, 0, "on", 0, 2)) {
                    partsEvents.put(k.toLowerCase(Locale.ROOT), v);
                }
            });
        }
        // スタイルの on* は算出スタイルだけで決まる。全プロパティを走査するので、スタイルごとに 1 回だけ集める
        String[] styleHandlers = (String[]) box.style().paintMemo(STYLE_HANDLERS, k -> styleHandlersOf(box));
        for (int i = 0; i < styleHandlers.length; i += 2) partsEvents.put(styleHandlers[i], styleHandlers[i + 1]);

        String tag = box.node().tag;
        if (com.nishiyu.enderframelib.core.dom.node.ElementTraits.isClickActivated(tag) && !partsEvents.containsKey("onclick")) {
            partsEvents.put("onclick", id);
        }
        if (com.nishiyu.enderframelib.core.dom.node.ElementTraits.isLabel(tag) && box.values().attrs().containsKey("for") && !partsEvents.containsKey("onclick")) {
            partsEvents.put("onclick", "label_for:" + box.values().attrs().get("for"));
        }
        return partsEvents;
    }

    static ItemProps forPassiveBorder(String id, TransitionSpec transSpec) {
        return forPassiveBorder(id, 0L, transSpec);
    }

    static ItemProps forPassiveBorder(String id, long parentKey, TransitionSpec transSpec) {
        return populateForPassiveBorder(new ItemProps(), id, parentKey, transSpec);
    }

    static ItemProps populateForPassiveBorder(ItemProps passive, String id, long parentKey, TransitionSpec transSpec) {
        passive.reset();
        passive.parentId(id, parentKey);
        applyTransitionEvents(passive, transSpec);
        return passive;
    }
}
