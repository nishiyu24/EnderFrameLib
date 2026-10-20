package com.nishiyu.enderframelib.core.paint.model;

import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;

import java.util.Locale;

/**
 * DisplayItem の型分類。
 */
public enum ElementType {
    NODE,
    RECT,
    TEXT,
    IMAGE,
    VIDEO,
    CANVAS,
    SCENE,
    TEXTAREA,
    SELECT,
    INPUT,
    RANGE,
    PROGRESS,
    TOGGLE,
    CHECKBOX,
    RADIO,
    CIRCLE_BORDER,
    /** 角丸の枠線（枠の領域だけを塗る。{@link RenderMeta#roundedBorder()}）。 */
    ROUNDED_BORDER,
    BUTTON,
    A;

    public boolean isInput() {
        return this == INPUT || this == TEXTAREA || this == SELECT
                || this == RANGE || this == TOGGLE || this == CHECKBOX || this == RADIO
                || this == BUTTON || this == A || this == SCENE;
    }

    public static ElementType fromString(String str) {
        if (str == null) return NODE;
        if (str.startsWith("img")) return IMAGE;
        return switch (str.toLowerCase(Locale.ROOT)) {
            case "node" -> NODE;
            case "rect" -> RECT;
            case "text" -> TEXT;
            case "image", "img" -> IMAGE;
            case "video" -> VIDEO;
            case "canvas" -> CANVAS;
            case "scene" -> SCENE;
            case "textarea" -> TEXTAREA;
            case "select" -> SELECT;
            case "input" -> INPUT;
            case "range" -> RANGE;
            case "progress", "meter" -> PROGRESS;
            case "toggle" -> TOGGLE;
            case "checkbox" -> CHECKBOX;
            case "radio" -> RADIO;
            case "circle_border" -> CIRCLE_BORDER;
            case "rounded_border" -> ROUNDED_BORDER;
            case "button" -> BUTTON;
            case "a" -> A;
            default -> NODE;
        };
    }
}
