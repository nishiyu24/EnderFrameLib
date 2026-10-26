package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.ImageFitMode;

import java.util.Locale;

/**
 * {@code object-fit} と {@code object-position}（CSS Images 3 §5.5・§5.6）。
 * 置換要素の中身（画像・キャンバス・SVG）を、要素の内容ボックスのどこにどの大きさで置くかを決める。
 */
public final class ObjectFit {

    private ObjectFit() {}

    /** 内容ボックスの左上を原点とした、中身の矩形。ボックスからはみ出してよい（はみ出しは切る）。 */
    public record Placement(float x, float y, float w, float h) {}

    /** 中身の具体的な大きさ（CSS px）。{@code none} は固有の大きさ、{@code scale-down} は none と contain の小さい方。 */
    public static float[] objectSize(ImageFitMode fit, float imgW, float imgH, float boxW, float boxH) {
        if (!(imgW > 0) || !(imgH > 0)) return new float[]{boxW, boxH};
        float contain = Math.min(boxW / imgW, boxH / imgH);
        float cover = Math.max(boxW / imgW, boxH / imgH);
        return switch (fit == null ? ImageFitMode.FILL : fit) {
            case CONTAIN -> new float[]{imgW * contain, imgH * contain};
            case COVER -> new float[]{imgW * cover, imgH * cover};
            case NONE -> new float[]{imgW, imgH};
            case SCALE_DOWN -> contain < 1f ? new float[]{imgW * contain, imgH * contain} : new float[]{imgW, imgH};
            case FILL -> new float[]{boxW, boxH};
        };
    }

    /**
     * 中身の置き場所。{@code position} は {@code object-position}（null は {@code 50% 50%}）。
     *
     * @param fontPx {@code em} を含む位置の基準
     */
    public static Placement place(ImageFitMode fit, String position, float imgW, float imgH, float boxW, float boxH, float fontPx) {
        float[] size = objectSize(fit, imgW, imgH, boxW, boxH);
        float[] pos = parsePosition(position, fontPx);
        // pos は {割合, 長さ}（x・y）。位置 = (余白) * 割合 + 長さ
        float x = (boxW - size[0]) * pos[0] + pos[1];
        float y = (boxH - size[1]) * pos[2] + pos[3];
        return new Placement(x, y, size[0], size[1]);
    }

    /** {@code [xFraction, xOffsetPx, yFraction, yOffsetPx]}。読めなければ中央。 */
    static float[] parsePosition(String position, float fontPx) {
        float[] out = {0.5f, 0, 0.5f, 0};
        if (position == null || position.isBlank()) return out;
        String[] tokens = position.trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (tokens.length == 0 || tokens.length > 4) return out;
        // 1 値: もう片方は center。キーワードは軸が決まっているもの（left / right / top / bottom）を優先して割り当てる
        if (tokens.length == 1) {
            String t = tokens[0];
            if (t.equals("top") || t.equals("bottom")) return withAxis(out, 1, t, null);
            return withAxis(out, 0, t, null);
        }
        if (tokens.length == 2) {
            boolean firstIsY = tokens[0].equals("top") || tokens[0].equals("bottom");
            boolean secondIsX = tokens[1].equals("left") || tokens[1].equals("right");
            if (firstIsY || secondIsX) {
                float[] r = withAxis(out, 1, tokens[0], null);
                return withAxis(r, 0, tokens[1], null);
            }
            float[] r = withAxis(out, 0, tokens[0], null);
            return withAxis(r, 1, tokens[1], null);
        }
        // 3〜4 値: キーワード + 内側への距離（left 10px top 20px など）
        float[] r = out;
        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i];
            boolean isX = t.equals("left") || t.equals("right");
            boolean isY = t.equals("top") || t.equals("bottom");
            if (!isX && !isY) continue;
            String offset = i + 1 < tokens.length && !isKeyword(tokens[i + 1]) ? tokens[i + 1] : null;
            r = withAxis(r, isX ? 0 : 1, t, offset);
        }
        return r;
    }

    private static boolean isKeyword(String t) {
        return t.equals("left") || t.equals("right") || t.equals("top") || t.equals("bottom") || t.equals("center");
    }

    private static float[] withAxis(float[] base, int axis, String value, String offset) {
        float[] r = base.clone();
        int i = axis * 2;
        switch (value) {
            case "left", "top" -> {
                r[i] = 0;
                r[i + 1] = offset == null ? 0 : px(offset, 16);
            }
            case "right", "bottom" -> {
                r[i] = 1;
                r[i + 1] = offset == null ? 0 : -px(offset, 16);
            }
            case "center" -> {
                r[i] = 0.5f;
                r[i + 1] = 0;
            }
            default -> {
                if (value.endsWith("%")) {
                    try {
                        r[i] = Float.parseFloat(value.substring(0, value.length() - 1)) / 100f;
                        r[i + 1] = 0;
                    } catch (NumberFormatException ignored) {
                        // 読めない値は中央のまま
                    }
                } else {
                    float v = px(value, 16);
                    if (!Float.isNaN(v)) {
                        r[i] = 0;
                        r[i + 1] = v;
                    }
                }
            }
        }
        return r;
    }

    private static float px(String t, float fontPx) {
        try {
            if (t.endsWith("px")) return Float.parseFloat(t.substring(0, t.length() - 2));
            if (t.endsWith("em")) return Float.parseFloat(t.substring(0, t.length() - 2)) * fontPx;
            if (t.equals("0")) return 0;
        } catch (NumberFormatException ignored) {
            // fallthrough
        }
        return Float.NaN;
    }
}
