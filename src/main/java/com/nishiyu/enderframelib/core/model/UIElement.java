package com.nishiyu.enderframelib.core.model;

import java.util.Map;

/**
 * レンダリングパイプラインで使用されるUI要素モデル。
 * 構造化メタデータ（RenderMeta）および型付きプロパティを保持する不変レコード。
 */
public record UIElement(
        String id,
        String baseNodeId,
        ElementType type,
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
        AnimationSpec animDef,
        ImageFitMode fitMode,
        RenderMeta meta
) {
    public String baseId() {
        return (baseNodeId != null && !baseNodeId.isEmpty()) ? baseNodeId : id;
    }

    public boolean hasHover() {
        return meta != null && meta.interactiveState();
    }

    public boolean hasPseudo() {
        return meta != null && meta.interactiveState();
    }

    public UIElement withText(String newText) {
        return new UIElement(id, baseNodeId, type, x, y, width, height, newText, color, bgColor, events, radius, opacity, translateX, translateY, scaleX, scaleY, rotate, transitionDuration, animDef, fitMode, meta);
    }

    public record GradientSpec(String direction, int startColor, int endColor) {}

    public record CircleBorderSpec(float thickness, int colorTop, int colorRight, int colorBottom, int colorLeft) {}

    public record RenderMeta(
            ClipRect clipRect,
            Corners borderRadii,
            GradientSpec gradient,
            CircleBorderSpec circleBorder,
            float fontHeight,
            float baseline,
            float lineHeight,
            float originX,
            float originY,
            int itemCount,
            boolean interactiveState
    ) {
        public static final RenderMeta EMPTY = new RenderMeta(null, null, null, null, 16.0f, 0.5f, 0.5f, 1, false);

        /**
         * 行の情報を持たない要素（テキスト以外）のための短縮形。
         *
         * {@code baseline} はレイアウトが決めた「要素の上端からベースラインまでの距離」で、
         * テキスト要素だけが値を持つ。0 のときは描画側が書体から中央に置く。
         * {@code lineHeight} は複数行を自分で並べる要素（textarea）のための行送り。
         */
        public RenderMeta(ClipRect clipRect, Corners borderRadii, GradientSpec gradient,
                          CircleBorderSpec circleBorder, float fontHeight, float originX, float originY,
                          int itemCount, boolean interactiveState) {
            this(clipRect, borderRadii, gradient, circleBorder, fontHeight, 0.0f, 0.0f,
                    originX, originY, itemCount, interactiveState);
        }

        /**
         * 素のサイズからのフォントの倍率。
         * 描画もクリック位置の解釈もこの 1 つの式を使う
         * （別々に書くと、見えている文字とキャレットの位置が食い違う）。
         */
        public float textScale() {
            return fontHeight / com.nishiyu.enderframelib.core.text.BitmapFontFace.MC_UNITS_PER_EM;
        }

        /** 行送り（px）。レイアウトが解決していなければ {@code line-height: normal} 相当。 */
        public float resolvedLineHeight() {
            if (lineHeight > 0) return lineHeight;
            return fontHeight * (1.0f + com.nishiyu.enderframelib.core.text.BitmapFontFace.MC_LINE_GAP_RATIO);
        }

        public boolean hasClip() {
            return clipRect != null;
        }

        public boolean hasBorderRadii() {
            return borderRadii != null && borderRadii.hasRadius();
        }

        public static RenderMeta fromEvents(Map<String, String> events, int fallbackRadius, int boxH) {
            if (events == null || events.isEmpty()) {
                Corners r = fallbackRadius > 0 ? Corners.all(fallbackRadius) : null;
                return new RenderMeta(null, r, null, null, Math.max(10.0f, boxH), 0.5f, 0.5f, 1, false);
            }

            ClipRect clip = null;
            if (events.containsKey(RenderKeys.MC_CLIP)) {
                String[] parts = events.get(RenderKeys.MC_CLIP).split(",");
                if (parts.length >= 4) {
                    try {
                        float[] c = new float[8];
                        for (int i = 0; i < Math.min(parts.length, 8); i++) {
                            c[i] = Float.parseFloat(parts[i]);
                        }
                        clip = ClipRect.fromArray(c);
                    } catch (Exception ignored) {
                        clip = null;
                    }
                }
            }

            Corners radii = null;
            if (events.containsKey(RenderKeys.MC_RADIUS)) {
                String[] parts = events.get(RenderKeys.MC_RADIUS).split(",");
                if (parts.length >= 4) {
                    try {
                        radii = new Corners(
                                Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                                Integer.parseInt(parts[2]), Integer.parseInt(parts[3])
                        );
                    } catch (Exception ignored) {}
                }
            } else if (fallbackRadius > 0) {
                radii = Corners.all(fallbackRadius);
            }

            GradientSpec grad = null;
            if (events.containsKey(RenderKeys.MC_GRADIENT)) {
                String[] gParts = events.get(RenderKeys.MC_GRADIENT).split(":");
                if (gParts.length >= 3) {
                    try {
                        grad = new GradientSpec(gParts[0], Integer.parseInt(gParts[1]), Integer.parseInt(gParts[2]));
                    } catch (Exception ignored) {}
                }
            }

            float fontH = Math.max(10.0f, boxH);
            if (events.containsKey(RenderKeys.MC_FONT_H)) {
                try { fontH = Float.parseFloat(events.get(RenderKeys.MC_FONT_H)); } catch (Exception ignored) {}
            }

            float ox = 0.5f, oy = 0.5f;
            if (events.containsKey(RenderKeys.MC_ORIGIN)) {
                String[] parts = events.get(RenderKeys.MC_ORIGIN).split(",");
                if (parts.length >= 2) {
                    try { ox = Float.parseFloat(parts[0]); oy = Float.parseFloat(parts[1]); } catch (Exception ignored) {}
                }
            }

            int count = 1;
            if (events.containsKey("data-count")) {
                try { count = Integer.parseInt(events.get("data-count").trim()); } catch (Exception ignored) {}
            }

            return new RenderMeta(clip, radii, grad, null, fontH, ox, oy, count, false);
        }
    }
}