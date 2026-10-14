package com.nishiyu.enderframelib.core.model;

import java.util.Map;

public record UIElement(
        String id,
        String type,
        int x,
        int y,
        int width,
        int height,
        String text,
        int color,
        int bgColor,
        Map<String, String> events,
        int radius,
        float opacity,
        float translateX,
        float translateY,
        float scaleX,
        float scaleY,
        float rotate,
        int transitionDuration,
        String animDef
) {}