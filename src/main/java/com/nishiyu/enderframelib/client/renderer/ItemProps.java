package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import com.nishiyu.enderframelib.core.paint.model.ShaderGradient;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import static com.nishiyu.enderframelib.client.renderer.BoxAnimation.applyTransitionEvents;
import static com.nishiyu.enderframelib.client.renderer.PaintSupport.putFontEvents;
import com.nishiyu.enderframelib.client.renderer.BoxAnimation.TransitionSpec;

/**
 * {@link DisplayListBuilder} が 1 つの要素の部品群に共通して付ける情報。DOM 由来の属性（{@code onclick}・{@code data-*}・書体）と、
 * 型付きの描画の付帯情報（{@link RenderHints}）を分けて持つ。部品を出すときに前者は {@code DisplayItem.events}、後者は
 * {@code RenderMeta.hints} へ入る。
 */
final class ItemProps {

        private final Map<String, String> attrs;
        private RenderHints hints;

        ItemProps() {
            this(new HashMap<>(), RenderHints.NONE);
        }

        private ItemProps(Map<String, String> attrs, RenderHints hints) {
            this.attrs = attrs;
            this.hints = hints;
        }

        static ItemProps parent(String parentId) {
            return new ItemProps().parentId(parentId);
        }

        ItemProps copy() {
            return new ItemProps(new HashMap<>(attrs), hints);
        }

        // --- DOM 由来の属性 ---

        void put(String key, String value) {
            attrs.put(key, value);
        }

        String get(String key) {
            return attrs.get(key);
        }

        boolean containsKey(String key) {
            return attrs.containsKey(key);
        }

        Map<String, String> attrs() {
            return attrs;
        }

        // --- 型付きの描画の付帯情報 ---

        RenderHints hints() {
            return hints;
        }

        ItemProps parentId(String id) {
            hints = hints.withParentId(id);
            return this;
        }

        ItemProps title(String title) {
            hints = hints.withTitle(title);
            return this;
        }

        ItemProps scroll(float contentWidth, float contentHeight) {
            hints = hints.withScroll(contentWidth, contentHeight);
            return this;
        }

        ItemProps shadowBlur(float blur) {
            hints = hints.withShadowBlur(blur);
            return this;
        }

        ItemProps shaderGradient(ShaderGradient gradient) {
            hints = hints.withShaderGradient(gradient);
            return this;
        }

        ItemProps pointerEventsNone() {
            hints = hints.withPointerEventsNone(true);
            return this;
        }

        ItemProps transition(int delayMs, String easing, String property) {
            hints = hints.withTransition(delayMs, easing, property);
            return this;
        }

    static final String[] PASSTHROUGH_DATA_ATTRS = {
            "data-sound", "data-sound-pitch", "draggable", "data-count",
            "data-ghost-slot", "data-pan-zoom", "for", "tabindex"
    };

    static ItemProps forParts(LayoutBox box, String id, TransitionSpec transSpec) {
        ItemProps partsEvents = new ItemProps();
        partsEvents.parentId(id);
        applyTransitionEvents(partsEvents, transSpec);
        if (box.style.containsKey(CssProperty.FONT_FAMILY)) {
            partsEvents.put("font-family", box.style.get(CssProperty.FONT_FAMILY));
        }
        putFontEvents(box, partsEvents);
        if (box.node.attrs.containsKey("title")) partsEvents.title(box.node.attrs.get("title"));
        else if (box.node.attrs.containsKey("data-tooltip")) partsEvents.title(box.node.attrs.get("data-tooltip"));

        for (String attr : PASSTHROUGH_DATA_ATTRS) {
            if (box.node.attrs.containsKey(attr)) {
                partsEvents.put(attr, box.node.attrs.get(attr));
            }
        }

        Overflow overflowX = box.style.overflowX();
        Overflow overflowY = box.style.overflowY();
        boolean isScrollable = overflowX == Overflow.SCROLL || overflowX == Overflow.AUTO
                || overflowY == Overflow.SCROLL || overflowY == Overflow.AUTO
                || "root".equals(box.node.tag) || "html".equals(box.node.tag) || "body".equals(box.node.tag);
        if (isScrollable) {
            partsEvents.scroll(box.scrollWidth, box.scrollHeight);
        }

        for (Map.Entry<String, String> entry : box.node.attrs.entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).startsWith("on")) {
                partsEvents.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
            }
        }
        for (Map.Entry<String, String> entry : box.style.asMap().entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).startsWith("on")) {
                partsEvents.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
            }
        }

        String tag = box.node.tag;
        if (("button".equals(tag) || "select".equals(tag) || "summary".equals(tag)) && !partsEvents.containsKey("onclick")) {
            partsEvents.put("onclick", id);
        }
        if ("label".equals(tag) && box.node.attrs.containsKey("for") && !partsEvents.containsKey("onclick")) {
            partsEvents.put("onclick", "label_for:" + box.node.attrs.get("for"));
        }
        return partsEvents;
    }

    static ItemProps forPassiveBorder(String id, TransitionSpec transSpec) {
        ItemProps passive = new ItemProps();
        passive.parentId(id);
        applyTransitionEvents(passive, transSpec);
        return passive;
    }
}
