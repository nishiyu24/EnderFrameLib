package com.nishiyu.enderframelib.core.model;

import java.util.Map;

/**
 * レンダリングパイプラインで使用されるUI要素モデル。
 * 文字列パースのオーバーヘッドを無くすための構造化メタデータ(RenderMeta)を保持しつつ、
 * 既存の19引数コンストラクタとも完全な後方互換性を維持する。
 */
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
        String animDef,
        RenderMeta meta
) {
    /**
     * 既存クラス（WebAnimator等）との後方互換用19引数コンストラクタ
     */
    public UIElement(
            String id, String type, int x, int y, int width, int height,
            String text, int color, int bgColor, Map<String, String> events,
            int radius, float opacity, float translateX, float translateY,
            float scaleX, float scaleY, float rotate, int transitionDuration, String animDef
    ) {
        this(id, type, x, y, width, height, text, color, bgColor, events,
                radius, opacity, translateX, translateY, scaleX, scaleY, rotate,
                transitionDuration, animDef, RenderMeta.fromEvents(events, radius, height));
    }

    public record GradientSpec(String direction, int startColor, int endColor) {}

    public record CircleBorderSpec(float thickness, int colorTop, int colorRight, int colorBottom, int colorLeft) {}

    public record RenderMeta(
            float[] clipRect,
            int[] borderRadii,
            GradientSpec gradient,
            CircleBorderSpec circleBorder,
            float fontHeight,
            float originX,
            float originY,
            int itemCount
    ) {
        public static final RenderMeta EMPTY = new RenderMeta(null, null, null, null, 16.0f, 0.5f, 0.5f, 1);

        public static RenderMeta fromEvents(Map<String, String> events, int fallbackRadius, int boxH) {
            if (events == null || events.isEmpty()) {
                int[] r = fallbackRadius > 0 ? new int[]{fallbackRadius, fallbackRadius, fallbackRadius, fallbackRadius} : null;
                return new RenderMeta(null, r, null, null, Math.max(10.0f, boxH), 0.5f, 0.5f, 1);
            }

            float[] clip = null;
            if (events.containsKey("mc-clip")) {
                String[] parts = events.get("mc-clip").split(",");
                if (parts.length >= 8) {
                    try {
                        clip = new float[8];
                        for (int i = 0; i < 8; i++) clip[i] = Float.parseFloat(parts[i]);
                    } catch (Exception ignored) { clip = null; }
                }
            }

            int[] radii = null;
            if (events.containsKey("mc-radius")) {
                String[] parts = events.get("mc-radius").split(",");
                if (parts.length >= 4) {
                    try {
                        radii = new int[]{
                                Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                                Integer.parseInt(parts[2]), Integer.parseInt(parts[3])
                        };
                    } catch (Exception ignored) {}
                }
            } else if (fallbackRadius > 0) {
                radii = new int[]{fallbackRadius, fallbackRadius, fallbackRadius, fallbackRadius};
            }

            GradientSpec grad = null;
            if (events.containsKey("mc-gradient")) {
                String[] gParts = events.get("mc-gradient").split(":");
                if (gParts.length >= 3) {
                    try {
                        grad = new GradientSpec(gParts[0], Integer.parseInt(gParts[1]), Integer.parseInt(gParts[2]));
                    } catch (Exception ignored) {}
                }
            }

            float fontH = Math.max(10.0f, boxH);
            if (events.containsKey("mc-font-h")) {
                try { fontH = Float.parseFloat(events.get("mc-font-h")); } catch (Exception ignored) {}
            }

            float ox = 0.5f, oy = 0.5f;
            if (events.containsKey("mc-origin")) {
                String[] parts = events.get("mc-origin").split(",");
                if (parts.length >= 2) {
                    try { ox = Float.parseFloat(parts[0]); oy = Float.parseFloat(parts[1]); } catch (Exception ignored) {}
                }
            }

            int count = 1;
            if (events.containsKey("data-count")) {
                try { count = Integer.parseInt(events.get("data-count").trim()); } catch (Exception ignored) {}
            }

            return new RenderMeta(clip, radii, grad, null, fontH, ox, oy, count);
        }
    }
}