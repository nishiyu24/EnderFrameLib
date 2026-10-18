package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssParser.CssTokenizer;
import com.nishiyu.enderframelib.core.css.LengthResolver;
import com.nishiyu.enderframelib.core.layout.LayoutBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 箱の装飾（枠線・輪郭線の線種と背景のレイヤー）を矩形の並びに分ける純関数群。
 *
 * 描画側（{@code WebRenderCore}）が知っているのは塗りの矩形・角丸・グラデーション・画像だけなので、
 * 線種の破線や点、背景のタイルはここで矩形に直してから {@link UIRenderer} が要素にする。
 * Minecraft に依存しないので単体テストで形を確かめられる。
 */
public final class BoxDecorations {

    private BoxDecorations() {}

    public enum Side { TOP, RIGHT, BOTTOM, LEFT }

    /** 線の 1 片。{@code shade} は色の濃淡（-1 = 暗く、0 = そのまま。inset / groove などの立体表現）。 */
    public record Segment(float x, float y, float w, float h, int shade, boolean round) {}

    // ==========================================
    // 線種（border-style / outline-style）
    // ==========================================

    /**
     * 1 辺の線を線種に従って片に分ける。
     *
     * {@code (x, y, w, h)} は辺の帯（上辺なら箱の幅 × 線の太さ）。{@code startInset} / {@code endInset} は
     * 帯の両端に接する辺の太さで、{@code double} の内側の線を角で隣の辺の内側の線に揃えるのに使う。
     * 破線と点線は両端が線で終わるように間隔を伸び縮みさせる（ブラウザと同じ考え方。間隔の正確な値は UA 依存）。
     */
    public static List<Segment> sideSegments(String lineStyle, Side side, float x, float y, float w, float h,
                                             float startInset, float endInset) {
        boolean horizontal = side == Side.TOP || side == Side.BOTTOM;
        float thickness = horizontal ? h : w;
        float length = horizontal ? w : h;
        List<Segment> out = new ArrayList<>();
        if (thickness <= 0 || length <= 0) return out;
        String style = lineStyle == null ? "solid" : lineStyle.trim().toLowerCase(Locale.ROOT);

        switch (style) {
            case "none", "hidden" -> {}
            case "double" -> {
                if (thickness < 3) {
                    out.add(new Segment(x, y, w, h, 0, false));
                    break;
                }
                float stroke = thickness / 3f;
                // 外側の線は帯いっぱい、内側の線は隣の辺の内側の線と角で接するまで縮める
                float innerStart = startInset * 2f / 3f;
                float innerEnd = endInset * 2f / 3f;
                switch (side) {
                    case TOP -> {
                        out.add(new Segment(x, y, w, stroke, 0, false));
                        out.add(new Segment(x + innerStart, y + h - stroke, w - innerStart - innerEnd, stroke, 0, false));
                    }
                    case BOTTOM -> {
                        out.add(new Segment(x, y + h - stroke, w, stroke, 0, false));
                        out.add(new Segment(x + innerStart, y, w - innerStart - innerEnd, stroke, 0, false));
                    }
                    case LEFT -> {
                        out.add(new Segment(x, y, stroke, h, 0, false));
                        out.add(new Segment(x + w - stroke, y + innerStart, stroke, h - innerStart - innerEnd, 0, false));
                    }
                    case RIGHT -> {
                        out.add(new Segment(x + w - stroke, y, stroke, h, 0, false));
                        out.add(new Segment(x, y + innerStart, stroke, h - innerStart - innerEnd, 0, false));
                    }
                }
            }
            case "dashed" -> dashes(out, horizontal, x, y, w, h, thickness * 3f, thickness * 3f, false);
            case "dotted" -> dashes(out, horizontal, x, y, w, h, thickness, thickness, thickness >= 2);
            case "inset", "outset" -> {
                boolean darkSide = (side == Side.TOP || side == Side.LEFT) == "inset".equals(style);
                out.add(new Segment(x, y, w, h, darkSide ? -1 : 0, false));
            }
            case "groove", "ridge" -> {
                // 外側の半分と内側の半分で濃淡を入れ替える（groove は彫り込み、ridge は盛り上がり）
                boolean outerDark = (side == Side.TOP || side == Side.LEFT) == "groove".equals(style);
                float half = thickness / 2f;
                switch (side) {
                    case TOP -> {
                        out.add(new Segment(x, y, w, half, outerDark ? -1 : 0, false));
                        out.add(new Segment(x, y + half, w, h - half, outerDark ? 0 : -1, false));
                    }
                    case BOTTOM -> {
                        out.add(new Segment(x, y + h - half, w, half, outerDark ? -1 : 0, false));
                        out.add(new Segment(x, y, w, h - half, outerDark ? 0 : -1, false));
                    }
                    case LEFT -> {
                        out.add(new Segment(x, y, half, h, outerDark ? -1 : 0, false));
                        out.add(new Segment(x + half, y, w - half, h, outerDark ? 0 : -1, false));
                    }
                    case RIGHT -> {
                        out.add(new Segment(x + w - half, y, half, h, outerDark ? -1 : 0, false));
                        out.add(new Segment(x, y, w - half, h, outerDark ? 0 : -1, false));
                    }
                }
            }
            default -> out.add(new Segment(x, y, w, h, 0, false));
        }
        return out;
    }

    /** 帯を {@code dash} の長さの片に分ける。片の数を決めてから、両端が片で終わるように間隔を均す。 */
    private static void dashes(List<Segment> out, boolean horizontal, float x, float y, float w, float h,
                               float dash, float gap, boolean round) {
        float length = horizontal ? w : h;
        if (length <= dash * 2 + gap) {
            out.add(new Segment(x, y, w, h, 0, false));
            return;
        }
        int count = Math.max(2, Math.round((length + gap) / (dash + gap)));
        float actualGap = (length - count * dash) / (count - 1);
        for (int i = 0; i < count; i++) {
            float offset = i * (dash + actualGap);
            if (horizontal) out.add(new Segment(x + offset, y, dash, h, 0, round));
            else out.add(new Segment(x, y + offset, w, dash, 0, round));
        }
    }

    /** 立体表現の暗い側の色（濃淡の正確な値は UA 依存。ここでは RGB を半分にする）。 */
    public static int shade(int argb, int shade) {
        if (shade >= 0) return argb;
        int a = argb >>> 24;
        int r = ((argb >>> 16) & 0xFF) / 2;
        int g = ((argb >>> 8) & 0xFF) / 2;
        int b = (argb & 0xFF) / 2;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ==========================================
    // 背景のレイヤー（CSS Backgrounds 3 §2）
    // ==========================================

    /** 1 枚のレイヤー。値はどれもそのレイヤーに対応するリストの要素（短いリストは繰り返して揃える）。 */
    public record BackgroundLayer(String image, String position, String size, String repeat, String origin, String clip) {
        public boolean isGradient() {
            return image.toLowerCase(Locale.ROOT).contains("gradient(");
        }

        /** {@code url(...)} の中身。画像でなければ null。 */
        public String url() {
            String v = image.trim();
            if (!v.toLowerCase(Locale.ROOT).startsWith("url(") || !v.endsWith(")")) return null;
            String inner = v.substring(4, v.length() - 1).trim();
            if (inner.length() >= 2 && (inner.startsWith("\"") || inner.startsWith("'"))) {
                inner = inner.substring(1, inner.length() - 1);
            }
            return inner;
        }
    }

    /** 矩形（タイルや描画領域）。 */
    public record Rect(float x, float y, float w, float h) {
        float maxX() { return x + w; }
        float maxY() { return y + h; }
    }

    /**
     * 画像を持つレイヤーを上から順に返す（{@code background-image} の先頭が最前面）。
     * レイヤーの数は {@code background-image} のリストの長さで決まり、他のリストは繰り返して揃える（§2.3）。
     */
    public static List<BackgroundLayer> layers(ComputedStyle style) {
        List<String> images = list(style.get("background-image"), "none");
        List<String> positions = list(style.get("background-position"), "0% 0%");
        List<String> sizes = list(style.get("background-size"), "auto");
        List<String> repeats = list(style.get("background-repeat"), "repeat");
        List<String> origins = list(style.get("background-origin"), "padding-box");
        List<String> clips = list(style.get("background-clip"), "border-box");
        List<BackgroundLayer> out = new ArrayList<>(images.size());
        for (int i = 0; i < images.size(); i++) {
            out.add(new BackgroundLayer(images.get(i), positions.get(i % positions.size()), sizes.get(i % sizes.size()),
                    repeats.get(i % repeats.size()), origins.get(i % origins.size()), clips.get(i % clips.size())));
        }
        return out;
    }

    /** 背景色が塗られる領域を決める箱（最後のレイヤーの {@code background-clip}）。 */
    public static String colorClip(ComputedStyle style) {
        List<String> clips = list(style.get("background-clip"), "border-box");
        int layerCount = list(style.get("background-image"), "none").size();
        return clips.get((layerCount - 1) % clips.size());
    }

    private static List<String> list(String value, String fallback) {
        List<String> out = new ArrayList<>();
        if (value != null) {
            for (String part : CssTokenizer.splitTopLevel(value, ',')) {
                if (!part.isBlank()) out.add(part.trim());
            }
        }
        if (out.isEmpty()) out.add(fallback);
        return out;
    }

    /** {@code border-box} / {@code padding-box} / {@code content-box} の矩形。 */
    public static Rect boxArea(LayoutBox box, String which) {
        float x = box.x, y = box.y, w = box.w, h = box.h;
        if ("padding-box".equals(which) || "content-box".equals(which)) {
            x += box.bL; y += box.bT; w -= box.bL + box.bR; h -= box.bT + box.bB;
        }
        if ("content-box".equals(which)) {
            x += box.pL; y += box.pT; w -= box.pL + box.pR; h -= box.pT + box.pB;
        }
        return new Rect(x, y, Math.max(0, w), Math.max(0, h));
    }

    /** 1 レイヤーに並べるタイルの上限（巨大な箱に小さな画像を敷き詰めても要素が爆発しないように）。 */
    static final int MAX_TILES = 400;

    /**
     * レイヤーのタイルを並べる。{@code intrinsic} は画像の固有サイズ（不明・グラデーションなら null）。
     * 返すタイルは描画領域（{@code background-clip} の箱）と重なるものだけで、描画側はさらにその箱で切る。
     */
    public static List<Rect> tiles(BackgroundLayer layer, LayoutBox box, int[] intrinsic, float rootW, float rootH,
                                   float fontSize, float rootFontSize) {
        Rect area = boxArea(box, layer.origin());
        Rect paint = boxArea(box, layer.clip());
        List<Rect> out = new ArrayList<>();
        if (area.w() <= 0 || area.h() <= 0 || paint.w() <= 0 || paint.h() <= 0) return out;

        float[] size = tileSize(layer.size(), area, intrinsic, rootW, rootH, fontSize, rootFontSize);
        float tileW = size[0], tileH = size[1];
        if (tileW <= 0 || tileH <= 0) return out;

        String[] repeat = repeatPair(layer.repeat());
        // round は敷き詰めの数が整数になるようにタイルを伸び縮みさせる（§3.4）
        if ("round".equals(repeat[0])) tileW = area.w() / Math.max(1, Math.round(area.w() / tileW));
        if ("round".equals(repeat[1])) tileH = area.h() / Math.max(1, Math.round(area.h() / tileH));

        float[] pos = position(layer.position(), area, tileW, tileH, rootW, rootH, fontSize, rootFontSize);
        float[] xs = axisStarts(repeat[0], area.x(), area.w(), paint.x(), paint.maxX(), area.x() + pos[0], tileW);
        float[] ys = axisStarts(repeat[1], area.y(), area.h(), paint.y(), paint.maxY(), area.y() + pos[1], tileH);
        for (float ty : ys) {
            for (float tx : xs) {
                if (out.size() >= MAX_TILES) return out;
                if (tx < paint.maxX() && tx + tileW > paint.x() && ty < paint.maxY() && ty + tileH > paint.y()) {
                    out.add(new Rect(tx, ty, tileW, tileH));
                }
            }
        }
        return out;
    }

    private static String[] repeatPair(String repeat) {
        String[] parts = repeat.trim().toLowerCase(Locale.ROOT).split("\\s+");
        return switch (parts[0]) {
            case "repeat-x" -> new String[]{"repeat", "no-repeat"};
            case "repeat-y" -> new String[]{"no-repeat", "repeat"};
            default -> new String[]{parts[0], parts.length > 1 ? parts[1] : parts[0]};
        };
    }

    /** 1 軸のタイルの始点。{@code repeat} は描画領域を覆うまで前後に、{@code space} は端から均等に並べる。 */
    private static float[] axisStarts(String repeat, float areaStart, float areaLen, float paintMin, float paintMax,
                                      float origin, float tile) {
        switch (repeat) {
            case "repeat", "round" -> {
                float first = origin - (float) Math.ceil((origin - paintMin) / tile) * tile;
                int count = (int) Math.ceil((paintMax - first) / tile);
                count = Math.clamp(count, 1, MAX_TILES);
                float[] out = new float[count];
                for (int i = 0; i < count; i++) out[i] = first + i * tile;
                return out;
            }
            case "space" -> {
                int count = (int) Math.floor(areaLen / tile);
                if (count < 2) return new float[]{origin};
                count = Math.min(count, MAX_TILES);
                float gap = (areaLen - count * tile) / (count - 1);
                float[] out = new float[count];
                for (int i = 0; i < count; i++) out[i] = areaStart + i * (tile + gap);
                return out;
            }
            default -> {
                return new float[]{origin};
            }
        }
    }

    /** {@code background-size}。固有サイズが無い（グラデーション・未読み込み）ときの auto は配置領域の寸法。 */
    private static float[] tileSize(String size, Rect area, int[] intrinsic, float rootW, float rootH,
                                    float fontSize, float rootFontSize) {
        String v = size.trim().toLowerCase(Locale.ROOT);
        boolean hasIntrinsic = intrinsic != null && intrinsic.length >= 2 && intrinsic[0] > 0 && intrinsic[1] > 0;
        float iw = hasIntrinsic ? intrinsic[0] : area.w();
        float ih = hasIntrinsic ? intrinsic[1] : area.h();
        if ("cover".equals(v) || "contain".equals(v)) {
            if (!hasIntrinsic) return new float[]{area.w(), area.h()};
            float sx = area.w() / iw, sy = area.h() / ih;
            float s = "cover".equals(v) ? Math.max(sx, sy) : Math.min(sx, sy);
            return new float[]{iw * s, ih * s};
        }
        String[] parts = v.split("\\s+");
        String ws = parts[0];
        String hs = parts.length > 1 ? parts[1] : "auto";
        Float w = "auto".equals(ws) ? null : LengthResolver.parseDim(ws, area.w(), rootW, rootH, fontSize, rootFontSize);
        Float h = "auto".equals(hs) ? null : LengthResolver.parseDim(hs, area.h(), rootW, rootH, fontSize, rootFontSize);
        if (w == null && h == null) return new float[]{iw, ih};
        // 片方だけ auto なら、固有の比率を保つ（比率が無ければ固有サイズか配置領域の寸法）
        if (w == null) return new float[]{hasIntrinsic ? h * iw / ih : iw, h};
        if (h == null) return new float[]{w, hasIntrinsic ? w * ih / iw : ih};
        return new float[]{w, h};
    }

    /**
     * {@code background-position} を配置領域内のずれに解く。% は「領域 − タイル」に対する割合（§3.6）。
     * 1〜2 値の構文と、辺のキーワード + ずれの 3〜4 値の構文を読む。
     */
    static float[] position(String position, Rect area, float tileW, float tileH, float rootW, float rootH,
                            float fontSize, float rootFontSize) {
        List<String> tokens = new ArrayList<>();
        for (String t : position.trim().toLowerCase(Locale.ROOT).split("\\s+")) if (!t.isEmpty()) tokens.add(t);
        float freeW = area.w() - tileW, freeH = area.h() - tileH;
        String hEdge = null, vEdge = null, hOff = null, vOff = null;

        if (tokens.size() >= 3) {
            for (int i = 0; i < tokens.size(); i++) {
                String t = tokens.get(i);
                String next = i + 1 < tokens.size() && !isPositionKeyword(tokens.get(i + 1)) ? tokens.get(i + 1) : null;
                if ("left".equals(t) || "right".equals(t)) { hEdge = t; hOff = next; }
                else if ("top".equals(t) || "bottom".equals(t)) { vEdge = t; vOff = next; }
                else if ("center".equals(t)) { if (hEdge == null) hEdge = "center"; else vEdge = "center"; }
                if (next != null) i++;
            }
        } else {
            String first = tokens.isEmpty() ? "0%" : tokens.get(0);
            String second = tokens.size() > 1 ? tokens.get(1) : "center";
            // 1 値目が縦のキーワードなら入れ替える（"top left" / "top" は縦が先）
            if ("top".equals(first) || "bottom".equals(first) || ("center".equals(first) && ("left".equals(second) || "right".equals(second)))) {
                String tmp = first; first = second; second = tmp;
            }
            if (isPositionKeyword(first)) hEdge = first; else { hEdge = "left"; hOff = first; }
            if (isPositionKeyword(second)) vEdge = second; else { vEdge = "top"; vOff = second; }
        }
        float px = axisOffset(hEdge, hOff, freeW, rootW, rootH, fontSize, rootFontSize);
        float py = axisOffset(vEdge, vOff, freeH, rootW, rootH, fontSize, rootFontSize);
        return new float[]{px, py};
    }

    private static boolean isPositionKeyword(String t) {
        return "left".equals(t) || "right".equals(t) || "top".equals(t) || "bottom".equals(t) || "center".equals(t);
    }

    private static float axisOffset(String edge, String offset, float free, float rootW, float rootH,
                                    float fontSize, float rootFontSize) {
        if (edge == null) edge = "center";
        float off = offset == null ? 0 : LengthResolver.parseDim(offset, free, rootW, rootH, fontSize, rootFontSize);
        return switch (edge) {
            case "center" -> free / 2f;
            case "right", "bottom" -> free - off;
            default -> off;
        };
    }
}
