package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.css.ColorParser;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssSyntax;
import com.nishiyu.enderframelib.core.css.LengthResolver;
import com.nishiyu.enderframelib.core.css.VisualStyle;
import com.nishiyu.enderframelib.core.media.ImageChoice;
import com.nishiyu.enderframelib.core.media.ImageSources;
import com.nishiyu.enderframelib.core.paint.model.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 箱の装飾（枠線・輪郭線の線種と背景のレイヤー）を矩形の並びに分ける純関数群。
 *
 * 描画側（{@code DisplayItemDrawer}）が知っているのは塗りの矩形・角丸・グラデーション・画像だけなので、
 * 線種の破線や点、背景のタイルはここで矩形に直してから {@code DisplayListBuilder} が要素にする。
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
            case "dashed" -> dashes(out, horizontal, x, y, w, h, BorderPath.dashLength(BorderPath.DASHED, thickness),
                    BorderPath.gapLength(BorderPath.DASHED, thickness), false);
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
    public record BackgroundLayer(String image, String position, String size, String repeat, String origin, String clip,
                                  double dppx) {
        public boolean isGradient() {
            return image.toLowerCase(Locale.ROOT).contains("gradient(");
        }

        /** {@code url(...)}（または {@code image-set()} が選んだ候補）の URL。画像でなければ null。 */
        public String url() {
            ImageChoice set = imageSet();
            if (set != null) return set.url();
            String v = image.trim();
            if (!v.toLowerCase(Locale.ROOT).startsWith("url(") || !v.endsWith(")")) return null;
            String inner = v.substring(4, v.length() - 1).trim();
            if (inner.length() >= 2 && (inner.startsWith("\"") || inner.startsWith("'"))) {
                inner = inner.substring(1, inner.length() - 1);
            }
            return inner;
        }

        /** 画像の画素数を割る密度（{@code image-set()} の選んだ解像度。それ以外は 1）。 */
        public double density() {
            ImageChoice set = imageSet();
            return set != null ? set.density() : 1.0;
        }

        private ImageChoice imageSet() {
            return ImageSources.isImageSet(image) ? ImageSources.selectImageSet(image, dppx) : null;
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
        return layers(style, 1.0);
    }

    /** @param dppx {@code image-set()} の候補を選ぶデバイスピクセル比 */
    public static List<BackgroundLayer> layers(ComputedStyle style, double dppx) {
        // スタイルだけで決まるので、スタイルごとに 1 回だけ解く（描画のたびに全箱の background を字句解析していた）。
        // 呼び出し側が並べ替えても壊れないよう、写しを返す
        return new ArrayList<>(layersView(style, dppx));
    }

    private static final LayerKey LAYERS_KEY_1X = new LayerKey(false, 1.0);
    private static final LayerKey MASK_LAYERS_KEY_1X = new LayerKey(true, 1.0);

    private static List<BackgroundLayer> computeLayers1x(ComputedStyle style) {
        return List.copyOf(computeLayers(style, 1.0));
    }

    private static List<BackgroundLayer> computeMaskLayers1x(ComputedStyle style) {
        return List.copyOf(computeMaskLayers(style, 1.0));
    }

    /** 読み取り専用のレイヤー一覧をそのまま返す（呼び出し側は変更しないこと）。 */
    @SuppressWarnings("unchecked")
    public static List<BackgroundLayer> layersView(ComputedStyle style, double dppx) {
        if (dppx == 1.0) return style.paintMemoSelf(LAYERS_KEY_1X, BoxDecorations::computeLayers1x);
        return (List<BackgroundLayer>) style.paintMemo(
                new LayerKey(false, dppx), k -> List.copyOf(computeLayers(style, dppx)));
    }

    /** 背景・マスクのレイヤー表のメモのキー。 */
    private record LayerKey(boolean mask, double dppx) {}

    private static List<BackgroundLayer> computeLayers(ComputedStyle style, double dppx) {
        VisualStyle v = style.typed().visual;
        List<String> images = list(v.backgroundImage, "none");
        List<String> positions = list(v.backgroundPosition, "0% 0%");
        List<String> sizes = list(v.backgroundSize, "auto");
        List<String> repeats = list(v.backgroundRepeat, "repeat");
        List<String> origins = list(v.backgroundOrigin, "padding-box");
        List<String> clips = list(v.backgroundClip, "border-box");
        List<BackgroundLayer> out = new ArrayList<>(images.size());
        for (int i = 0; i < images.size(); i++) {
            out.add(new BackgroundLayer(images.get(i), positions.get(i % positions.size()), sizes.get(i % sizes.size()),
                    repeats.get(i % repeats.size()), origins.get(i % origins.size()), clips.get(i % clips.size()), dppx));
        }
        return out;
    }

    /**
     * {@code mask-image} のレイヤーを上から順に返す（先頭が最前面）。背景のレイヤーと同じ並びで、{@code mask-origin} /
     * {@code mask-clip} の既定は {@code border-box}。{@code no-clip} は {@code border-box} として扱う。
     */
    public static List<BackgroundLayer> maskLayers(ComputedStyle style) {
        return maskLayers(style, 1.0);
    }

    public static List<BackgroundLayer> maskLayers(ComputedStyle style, double dppx) {
        return new ArrayList<>(maskLayersView(style, dppx));
    }

    /** 読み取り専用のマスクレイヤー一覧をそのまま返す（呼び出し側は変更しないこと）。 */
    @SuppressWarnings("unchecked")
    public static List<BackgroundLayer> maskLayersView(ComputedStyle style, double dppx) {
        if (dppx == 1.0) return style.paintMemoSelf(MASK_LAYERS_KEY_1X, BoxDecorations::computeMaskLayers1x);
        return (List<BackgroundLayer>) style.paintMemo(
                new LayerKey(true, dppx), k -> List.copyOf(computeMaskLayers(style, dppx)));
    }

    private static final List<BackgroundLayer> NONE_MASK_LAYERS_1X = List.of(
            new BackgroundLayer("none", "0% 0%", "auto", "repeat", "border-box", "border-box", 1.0));

    private static List<BackgroundLayer> computeMaskLayers(ComputedStyle style, double dppx) {
        VisualStyle v = style.typed().visual;
        if (dppx == 1.0 && v.maskImage == null && v.maskPosition == null && v.maskSize == null
                && v.maskRepeat == null && v.maskOrigin == null && v.maskClip == null) {
            return NONE_MASK_LAYERS_1X;
        }
        List<String> images = list(v.maskImage, "none");
        List<String> positions = list(v.maskPosition, "0% 0%");
        List<String> sizes = list(v.maskSize, "auto");
        List<String> repeats = list(v.maskRepeat, "repeat");
        List<String> origins = list(v.maskOrigin, "border-box");
        List<String> clips = list(v.maskClip, "border-box");
        List<BackgroundLayer> out = new ArrayList<>(images.size());
        for (int i = 0; i < images.size(); i++) {
            String origin = origins.get(i % origins.size()), clip = clips.get(i % clips.size());
            out.add(new BackgroundLayer(images.get(i), positions.get(i % positions.size()), sizes.get(i % sizes.size()),
                    repeats.get(i % repeats.size()), boxKeyword(origin), boxKeyword(clip), dppx));
        }
        return out;
    }

    private static String boxKeyword(String v) {
        return switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "padding-box", "content-box" -> v.trim().toLowerCase(Locale.ROOT);
            default -> "border-box";
        };
    }

    /** {@code mask-mode} のリスト（{@code alpha} / {@code luminance} / {@code match-source}）。 */
    public static List<String> maskModes(ComputedStyle style) {
        List<String> out = new ArrayList<>();
        for (String m : list(style.typed().visual.maskMode, "match-source")) out.add(m.trim().toLowerCase(Locale.ROOT));
        return out;
    }

    /** {@code background-clip: text}（{@code -webkit-} 付きも）のレイヤーがあるか。背景を文字の形で切る（箱の中身を文字のアルファでマスクする）。 */
    public static boolean clipsText(ComputedStyle style) {
        String v = style.typed().visual.backgroundClip;
        if (v == null) return false;
        for (String clip : CssSyntax.parseCommaList(v)) if ("text".equalsIgnoreCase(clip.trim())) return true;
        return false;
    }

    /** 背景色が塗られる領域を決める箱（最後のレイヤーの {@code background-clip}）。 */
    public static String colorClip(ComputedStyle style) {
        return style.paintMemoSelf(COLOR_CLIP, BoxDecorations::computeColorClip);
    }

    private static final Object COLOR_CLIP = new Object();

    private static String computeColorClip(ComputedStyle style) {
        VisualStyle v = style.typed().visual;
        List<String> clips = list(v.backgroundClip, "border-box");
        int layerCount = list(v.backgroundImage, "none").size();
        return clips.get((layerCount - 1) % clips.size());
    }

    private static List<String> list(String value, String fallback) {
        List<String> out = CssSyntax.parseCommaList(value);
        if (out.isEmpty()) out.add(fallback);
        return out;
    }

    /** {@code border-box} / {@code padding-box} / {@code content-box} の矩形。 */
    static Rect boxArea(SceneAdapterBox box, String which) {
        return boxArea(box.x(), box.y(), box.w(), box.h(), box.bL(), box.bT(), box.bR(), box.bB(),
                box.pL(), box.pT(), box.pR(), box.pB(), which);
    }

    /** {@code border-box} / {@code padding-box} / {@code content-box} の矩形。 */
    public static Rect boxArea(float x, float y, float w, float h, float bL, float bT, float bR, float bB,
                               float pL, float pT, float pR, float pB, String which) {
        if ("padding-box".equals(which) || "content-box".equals(which)) { x += bL; y += bT; w -= bL + bR; h -= bT + bB; }
        if ("content-box".equals(which)) { x += pL; y += pT; w -= pL + pR; h -= pT + pB; }
        return new Rect(x, y, Math.max(0, w), Math.max(0, h));
    }

    /** 1 レイヤーに並べるタイルの上限（巨大な箱に小さな画像を敷き詰めても要素が爆発しないように）。 */
    static final int MAX_TILES = 400;

    private static final String[] REPEAT_BOTH = {"repeat", "repeat"};
    private static final String[] REPEAT_NO = {"no-repeat", "no-repeat"};
    private static final String[] REPEAT_X_PAIR = {"repeat", "no-repeat"};
    private static final String[] REPEAT_Y_PAIR = {"no-repeat", "repeat"};
    private static final ConcurrentHashMap<String, String[]> REPEAT_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ParsedPosition> POSITION_CACHE = new ConcurrentHashMap<>();
    private static final ThreadLocal<float[]> SCRATCH_PAIR = ThreadLocal.withInitial(() -> new float[4]);

    private record ParsedPosition(String hEdge, String hOff, String vEdge, String vOff) {}

    static List<Rect> tiles(BackgroundLayer layer, SceneAdapterBox box, int[] intrinsic, float rootW, float rootH,
                                   float fontSize, float rootFontSize) {
        return tiles(layer, boxArea(box, layer.origin()), boxArea(box, layer.clip()),
                intrinsic, rootW, rootH, fontSize, rootFontSize);
    }

    public static List<Rect> tiles(BackgroundLayer layer, Rect area, Rect paint, int[] intrinsic, float rootW, float rootH,
                                   float fontSize, float rootFontSize) {
        if (area.w() <= 0 || area.h() <= 0 || paint.w() <= 0 || paint.h() <= 0) return List.of();

        float[] scratch = SCRATCH_PAIR.get();
        tileSizeInto(layer.size(), area, intrinsic, rootW, rootH, fontSize, rootFontSize, scratch, 0);
        float tileW = scratch[0], tileH = scratch[1];
        if (tileW <= 0 || tileH <= 0) return List.of();

        String[] repeat = repeatPair(layer.repeat());
        // round は敷き詰めの数が整数になるようにタイルを伸び縮みさせる（§3.4）
        if ("round".equals(repeat[0])) tileW = area.w() / Math.max(1, Math.round(area.w() / tileW));
        if ("round".equals(repeat[1])) tileH = area.h() / Math.max(1, Math.round(area.h() / tileH));

        positionInto(layer.position(), area, tileW, tileH, rootW, rootH, fontSize, rootFontSize, scratch, 2);
        float posX = scratch[2], posY = scratch[3];
        float tx0 = area.x() + posX, ty0 = area.y() + posY;
        boolean noRepeat = !"repeat".equals(repeat[0]) && !"round".equals(repeat[0]) && !"space".equals(repeat[0])
                && !"repeat".equals(repeat[1]) && !"round".equals(repeat[1]) && !"space".equals(repeat[1]);
        boolean singleCoveringRepeat = "repeat".equals(repeat[0]) && "repeat".equals(repeat[1])
                && tx0 <= paint.x() + 1e-4f && tx0 + tileW >= paint.maxX() - 1e-4f
                && ty0 <= paint.y() + 1e-4f && ty0 + tileH >= paint.maxY() - 1e-4f;
        if (noRepeat || singleCoveringRepeat) {
            if (tx0 < paint.maxX() && tx0 + tileW > paint.x() && ty0 < paint.maxY() && ty0 + tileH > paint.y()) {
                return List.of(new Rect(tx0, ty0, tileW, tileH));
            }
            return List.of();
        }
        List<Rect> out = new ArrayList<>();
        float[] xs = axisStarts(repeat[0], area.x(), area.w(), paint.x(), paint.maxX(), tx0, tileW);
        float[] ys = axisStarts(repeat[1], area.y(), area.h(), paint.y(), paint.maxY(), ty0, tileH);
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
        if ("no-repeat".equals(repeat)) return REPEAT_NO;
        if ("repeat".equals(repeat)) return REPEAT_BOTH;
        if ("repeat-x".equals(repeat)) return REPEAT_X_PAIR;
        if ("repeat-y".equals(repeat)) return REPEAT_Y_PAIR;
        String[] cached = REPEAT_CACHE.get(repeat);
        if (cached != null) return cached;
        String s = repeat.trim().toLowerCase(Locale.ROOT);
        int sp = indexOfWhitespace(s, 0);
        String p0 = sp < 0 ? s : s.substring(0, sp);
        String[] res = switch (p0) {
            case "repeat-x" -> REPEAT_X_PAIR;
            case "repeat-y" -> REPEAT_Y_PAIR;
            default -> {
                String p1 = p0;
                if (sp >= 0) {
                    int s1 = skipWhitespace(s, sp);
                    if (s1 < s.length()) {
                        int e1 = indexOfWhitespace(s, s1);
                        p1 = e1 < 0 ? s.substring(s1) : s.substring(s1, e1);
                    }
                }
                yield "repeat".equals(p0) && "repeat".equals(p1) ? REPEAT_BOTH
                        : "no-repeat".equals(p0) && "no-repeat".equals(p1) ? REPEAT_NO
                        : new String[]{p0, p1};
            }
        };
        if (REPEAT_CACHE.size() < 256) REPEAT_CACHE.putIfAbsent(repeat, res);
        return res;
    }

    private static int indexOfWhitespace(String s, int from) {
        for (int i = from, n = s.length(); i < n; i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    private static int skipWhitespace(String s, int from) {
        int i = from, n = s.length();
        while (i < n && Character.isWhitespace(s.charAt(i))) i++;
        return i;
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
    private static void tileSizeInto(String size, Rect area, int[] intrinsic, float rootW, float rootH,
                                     float fontSize, float rootFontSize, float[] out, int off) {
        boolean hasIntrinsic = intrinsic != null && intrinsic.length >= 2 && intrinsic[0] > 0 && intrinsic[1] > 0;
        float iw = hasIntrinsic ? intrinsic[0] : area.w();
        float ih = hasIntrinsic ? intrinsic[1] : area.h();
        if ("auto".equals(size) || "auto auto".equals(size)) {
            out[off] = iw;
            out[off + 1] = ih;
            return;
        }
        String v = size.trim().toLowerCase(Locale.ROOT);
        if ("auto".equals(v) || "auto auto".equals(v)) {
            out[off] = iw;
            out[off + 1] = ih;
            return;
        }
        if ("cover".equals(v) || "contain".equals(v)) {
            if (!hasIntrinsic) { out[off] = area.w(); out[off + 1] = area.h(); return; }
            float sx = area.w() / iw, sy = area.h() / ih;
            float s = "cover".equals(v) ? Math.max(sx, sy) : Math.min(sx, sy);
            out[off] = iw * s;
            out[off + 1] = ih * s;
            return;
        }
        int sp = indexOfWhitespace(v, 0);
        String ws = sp < 0 ? v : v.substring(0, sp);
        String hs = "auto";
        if (sp >= 0) {
            int s1 = skipWhitespace(v, sp);
            if (s1 < v.length()) {
                int e1 = indexOfWhitespace(v, s1);
                hs = e1 < 0 ? v.substring(s1) : v.substring(s1, e1);
            }
        }
        Float w = "auto".equals(ws) ? null : LengthResolver.parseDim(ws, area.w(), rootW, rootH, fontSize, rootFontSize);
        Float h = "auto".equals(hs) ? null : LengthResolver.parseDim(hs, area.h(), rootW, rootH, fontSize, rootFontSize);
        if (w == null && h == null) { out[off] = iw; out[off + 1] = ih; return; }
        // 片方だけ auto なら、固有の比率を保つ（比率が無ければ固有サイズか配置領域の寸法）
        if (w == null) { out[off] = hasIntrinsic ? h * iw / ih : iw; out[off + 1] = h; return; }
        if (h == null) { out[off] = w; out[off + 1] = hasIntrinsic ? w * ih / iw : ih; return; }
        out[off] = w;
        out[off + 1] = h;
    }

    /**
     * {@code background-position} を配置領域内のずれに解く。% は「領域 − タイル」に対する割合（§3.6）。
     * 1〜2 値の構文と、辺のキーワード + ずれの 3〜4 値の構文を読む。
     */
    static float[] position(String position, Rect area, float tileW, float tileH, float rootW, float rootH,
                            float fontSize, float rootFontSize) {
        float[] out = new float[2];
        positionInto(position, area, tileW, tileH, rootW, rootH, fontSize, rootFontSize, out, 0);
        return out;
    }

    private static void positionInto(String position, Rect area, float tileW, float tileH, float rootW, float rootH,
                                     float fontSize, float rootFontSize, float[] out, int off) {
        float freeW = area.w() - tileW, freeH = area.h() - tileH;
        if ("0% 0%".equals(position) || "left top".equals(position) || "0 0".equals(position)) {
            out[off] = 0f;
            out[off + 1] = 0f;
            return;
        }
        if ("50% 50%".equals(position) || "center".equals(position) || "center center".equals(position)) {
            out[off] = freeW * 0.5f;
            out[off + 1] = freeH * 0.5f;
            return;
        }
        ParsedPosition parsed = POSITION_CACHE.get(position);
        if (parsed == null) {
            parsed = parsePosition(position);
            if (POSITION_CACHE.size() < 512) POSITION_CACHE.putIfAbsent(position, parsed);
        }
        out[off] = axisOffset(parsed.hEdge, parsed.hOff, freeW, rootW, rootH, fontSize, rootFontSize);
        out[off + 1] = axisOffset(parsed.vEdge, parsed.vOff, freeH, rootW, rootH, fontSize, rootFontSize);
    }

    private static ParsedPosition parsePosition(String position) {
        String trimmed = position.trim().toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>(4);
        int pos = 0, len = trimmed.length();
        while (pos < len) {
            int s = skipWhitespace(trimmed, pos);
            if (s >= len) break;
            int e = indexOfWhitespace(trimmed, s);
            if (e < 0) e = len;
            tokens.add(trimmed.substring(s, e));
            pos = e;
        }
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
        return new ParsedPosition(hEdge, hOff, vEdge, vOff);
    }

    private static boolean isPositionKeyword(String t) {
        return "left".equals(t) || "right".equals(t) || "top".equals(t) || "bottom".equals(t) || "center".equals(t);
    }

    private static float axisOffset(String edge, String offset, float free, float rootW, float rootH,
                                    float fontSize, float rootFontSize) {
        if (edge == null) edge = "center";
        float off;
        if (offset == null || "0%".equals(offset) || "0".equals(offset) || "0px".equals(offset)) off = 0f;
        else if ("50%".equals(offset)) off = free * 0.5f;
        else if ("100%".equals(offset)) off = free;
        else off = LengthResolver.parseDim(offset, free, rootW, rootH, fontSize, rootFontSize);
        return switch (edge) {
            case "center" -> free / 2f;
            case "right", "bottom" -> free - off;
            default -> off;
        };
    }

    // ==========================================
    // 角丸の枠線（CSS Backgrounds 3 §4.3 / §5）
    // ==========================================

    /** 枠線の領域の 1 片（{@code side} はその片の色を決める辺）。 */
    public record Span(float x, float y, float w, float h, Side side) {}

    /** 角丸の行を刻む高さ（px）。描画は仮想ピクセルの上で行われ、GUI の倍率で拡大されるので 1px より細かく刻む。 */
    static final float RING_ROW_STEP = 0.5f;

    /**
     * 角丸の枠線の領域（外側の角丸矩形から内側のパディングボックスの角丸矩形を抜いたもの）を、重ならない矩形の片に分ける。
     * 辺の帯は角の外にある部分を 1 枚ずつ、角は行ごとに外側と内側の曲線の間を刻む。
     *
     * 何が壊れていたか: 以前は箱全体を枠の色で塗り、内側を背景色で塗り重ねて枠に見せていた。
     * 背景が透明なら内側が塗られず箱全体が枠の色になり、半透明なら枠の色と混ざっていた。
     *
     * {@code radii} は外側の半径（左上・右上・右下・左下）、{@code widths} は枠の太さ（上・右・下・左）。
     * 内側の角は外側の半径から隣り合う辺の太さを引いた楕円（§5.2）。
     */
    public static List<Span> roundedBorderSpans(float x, float y, float w, float h, float[] radii, float[] widths) {
        List<Span> out = new ArrayList<>();
        if (w <= 0 || h <= 0) return out;
        float[] r = scaledRadii(w, h, radii);
        float wt = widths[0], wr = widths[1], wb = widths[2], wl = widths[3];
        float rTL = r[0], rTR = r[1], rBR = r[2], rBL = r[3];

        // 角の領域は半径と隣り合う辺の太さの大きい方。帯はその外側だけを受け持つので片は重ならない
        float topL = Math.max(rTL, wl), topR = Math.max(rTR, wr), botL = Math.max(rBL, wl), botR = Math.max(rBR, wr);
        float topZone = Math.min(h, Math.max(Math.max(rTL, rTR), wt));
        float botZone = Math.min(h - topZone, Math.max(Math.max(rBL, rBR), wb));

        // 上下の角の行（行の全体を外側と内側の曲線で切る）
        for (float ry = 0; ry < topZone; ry += RING_ROW_STEP) {
            float y0 = y + ry, y1 = y + Math.min(topZone, ry + RING_ROW_STEP);
            ringRow(out, x, w, y0, y1, (y0 + y1) / 2f - y, rTL, rTR, wt, wl, wr, topL, topR, Side.TOP);
        }
        for (float ry = 0; ry < botZone; ry += RING_ROW_STEP) {
            float y1 = y + h - ry, y0 = y + h - Math.min(botZone, ry + RING_ROW_STEP);
            ringRow(out, x, w, y0, y1, y + h - (y0 + y1) / 2f, rBL, rBR, wb, wl, wr, botL, botR, Side.BOTTOM);
        }
        // 角の行の間は左右の辺の帯だけ
        float midY0 = y + topZone, midY1 = y + h - botZone;
        if (midY1 > midY0) {
            if (wl > 0) out.add(new Span(x, midY0, Math.min(wl, w), midY1 - midY0, Side.LEFT));
            if (wr > 0) out.add(new Span(x + w - Math.min(wr, w), midY0, Math.min(wr, w), midY1 - midY0, Side.RIGHT));
        }
        return out;
    }

    /**
     * 角の 1 行。{@code d} は行の中心から外側の辺（上の角なら上端）までの距離、{@code edge} は上か下の辺の太さ。
     * 行が辺の太さの中にあれば行の全体が上（下）の辺、そうでなければ左右の端の外側と内側の曲線の間だけが枠。
     */
    private static void ringRow(List<Span> out, float x, float w, float y0, float y1, float d,
                                float rL, float rR, float edge, float wl, float wr, float zoneL, float zoneR, Side edgeSide) {
        float outL = x + outerInset(rL, d), outR = x + w - outerInset(rR, d);
        if (outR <= outL) return;
        if (d < edge) {
            // 帯の部分は角の領域の外だけ。角の領域の中も同じ辺の色なので 1 枚にまとめる
            out.add(new Span(outL, y0, outR - outL, y1 - y0, edgeSide));
            return;
        }
        float inL = x + innerEdge(rL, wl, edge, d), inR = x + w - innerEdge(rR, wr, edge, d);
        inL = Math.clamp(inL, outL, Math.max(outL, x + zoneL));
        inR = Math.clamp(inR, Math.min(outR, x + w - zoneR), outR);
        if (inR <= inL) {
            // 内側が無い（太い枠で左右の内側の曲線が重なる）行は全体が枠
            out.add(new Span(outL, y0, outR - outL, y1 - y0, edgeSide));
            return;
        }
        if (inL > outL) out.add(new Span(outL, y0, inL - outL, y1 - y0, Side.LEFT));
        if (outR > inR) out.add(new Span(inR, y0, outR - inR, y1 - y0, Side.RIGHT));
    }

    /** 外側の円弧が辺から食い込む量。{@code d} は行の中心から外側の辺までの距離。 */
    private static float outerInset(float r, float d) {
        if (r <= 0 || d >= r) return 0;
        float dy = r - d;
        return r - (float) Math.sqrt(Math.max(0, r * r - dy * dy));
    }

    /** 内側の曲線（半径 r − 太さの楕円）の、外側の縦の辺からの距離。 */
    private static float innerEdge(float r, float sideWidth, float edgeWidth, float d) {
        float rx = r - sideWidth, ry = r - edgeWidth;
        if (rx <= 0 || ry <= 0 || d >= r) return sideWidth;
        float dy = r - d; // 楕円の中心からの縦の距離
        if (dy >= ry) return r; // 行は内側の楕円の上（辺の太さの中）。呼び出し側で扱い済み
        return r - rx * (float) Math.sqrt(1 - (dy / ry) * (dy / ry));
    }

    /** 隣り合う半径の和が辺の長さを超えるなら全体を同じ比で縮める（§5.5）。 */
    static float[] scaledRadii(float w, float h, float[] radii) {
        float f = 1f;
        if (radii[0] + radii[1] > w) f = Math.min(f, w / (radii[0] + radii[1]));
        if (radii[3] + radii[2] > w) f = Math.min(f, w / (radii[3] + radii[2]));
        if (radii[0] + radii[3] > h) f = Math.min(f, h / (radii[0] + radii[3]));
        if (radii[1] + radii[2] > h) f = Math.min(f, h / (radii[1] + radii[2]));
        return new float[]{radii[0] * f, radii[1] * f, radii[2] * f, radii[3] * f};
    }

    // ==========================================
    // box-shadow（CSS Backgrounds 3 §7.1）
    // ==========================================

    /** 影 1 つ。長さは解いた px、色は ARGB。 */
    public record Shadow(float offsetX, float offsetY, float blur, float spread, int color, boolean inset) {}

    /**
     * {@code box-shadow} を影のリストに解く（先頭が最前面）。{@code <offset-x> <offset-y> [<blur> [<spread>]]}・
     * 色・{@code inset} を順不同で読み、色が無ければ {@code currentcolor}。長さが 2 つ未満の影は無効で、
     * 1 つでも無効な影があれば宣言全体が無効（空のリスト）。
     *
     * 何が壊れていたか: 以前は色だけを取り出し、オフセット・ぼかし・広がり・inset を無視して
     * 右下に 1〜3px ずらした矩形を 3 枚固定で描いていた。
     */
    public static List<Shadow> parseBoxShadows(String value, int currentColor, float rootW, float rootH,
                                               float fontSize, float rootFontSize) {
        if (value == null || value.isBlank() || "none".equalsIgnoreCase(value.trim())) return List.of();
        List<Shadow> out = new ArrayList<>();
        for (String part : CssSyntax.parseCommaList(value)) {
            List<Float> lengths = new ArrayList<>(4);
            Integer color = null;
            boolean inset = false;
            for (String token : splitTopLevelWhitespace(part)) {
                String t = token.toLowerCase(Locale.ROOT);
                if ("inset".equals(t)) {
                    inset = true;
                } else if (isLength(t)) {
                    lengths.add(LengthResolver.parseDim(t, 0, rootW, rootH, fontSize, rootFontSize));
                } else if (color != null || !ColorParser.isColor(t)) {
                    return List.of(); // 色が 2 つ、または色でも長さでもない語（以前は知らない語を白にしていた）
                } else {
                    color = "currentcolor".equals(t) ? currentColor : ColorParser.parseColor(t);
                }
            }
            if (lengths.size() < 2 || lengths.size() > 4) return List.of();
            float blur = lengths.size() > 2 ? Math.max(0, lengths.get(2)) : 0;
            float spread = lengths.size() > 3 ? lengths.get(3) : 0;
            out.add(new Shadow(lengths.get(0), lengths.get(1), blur, spread, color != null ? color : currentColor, inset));
        }
        return out;
    }

    /** ぼかしを近似する層 1 枚。{@code grow} は影の形を外へ広げる量（負なら内へ）、{@code color} はその層の色。 */
    public record ShadowLayer(float grow, int color) {}

    /**
     * ぼかしを同じ色の層の重ね塗りで近似する。ぼかし半径 b の影は形の辺の前後 b の幅で透明から影の色へ変わるので、
     * 辺を −b から +b まで均等にずらした層を重ね、全部が重なる内側で影の色の不透明度になるように各層の不透明度を決める。
     *
     * LIMITATION(制約): ガウスぼかしではなく段の付いた近似。README の "Not supported" に対応。
     */
    public static List<ShadowLayer> shadowLayers(Shadow shadow) {
        int alpha = shadow.color() >>> 24;
        int rgb = shadow.color() & 0x00FFFFFF;
        if (shadow.blur() <= 0) return List.of(new ShadowLayer(0, shadow.color()));
        int n = Math.clamp(Math.round(shadow.blur() / 2f), 2, 6);
        // n 枚重ねて元の不透明度になる 1 枚の不透明度（完全に不透明だと段が出ないので上限を少し下げる）
        double target = Math.min(alpha / 255.0, 0.96);
        int layerAlpha = Math.clamp((int) Math.round((1 - Math.pow(1 - target, 1.0 / n)) * 255), 1, 255);
        List<ShadowLayer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            float grow = shadow.blur() - 2 * shadow.blur() * i / (n - 1);
            out.add(new ShadowLayer(grow, (layerAlpha << 24) | rgb));
        }
        return out;
    }

    /** 括弧の外の空白で分ける（{@code rgba(0, 0, 0, .5)} を 1 語に保つ）。 */
    static List<String> splitTopLevelWhitespace(String s) {
        List<String> out = new ArrayList<>();
        for (List<CssSyntax.ComponentValue> part : CssSyntax.splitOnWhitespace(CssSyntax.parseComponentValues(s))) {
            out.add(CssSyntax.serialize(part));
        }
        return out;
    }

    /** 長さ（寸法・割合・単位の無い 0）か数式の語か。 */
    private static boolean isLength(String t) {
        List<CssSyntax.ComponentValue> values = CssSyntax.trim(CssSyntax.parseComponentValues(t));
        if (values.size() != 1) return false;
        if (values.getFirst() instanceof CssSyntax.Function f) return LengthResolver.isMathFunction(f.name());
        CssSyntax.Token token = CssSyntax.tokenOf(values.getFirst());
        if (token == null) return false;
        return switch (token.type()) {
            case NUMBER, PERCENTAGE -> true;
            case DIMENSION -> LengthResolver.isLengthUnit(token.unit());
            default -> false;
        };
    }

    // ==========================================
    // クリップの交わり
    // ==========================================

    /**
     * 2 つのクリップ（{@code minX, minY, maxX, maxY, 左上, 右上, 右下, 左下の半径}）の交わり。{@code outer} は null 可。
     *
     * 角の半径は、その角が各クリップの角からどれだけ離れているかで残す: 角どうしが重なれば大きい方の丸み、
     * 離れればその距離だけ小さくした丸み（保守的な近似。2 つの角丸の正確な交わりは角丸矩形にならない）。
     * 交わりが空なら幅・高さ 0 に潰す。
     *
     * 何が壊れていたか: 以前は子のクリップの半径をそのまま使っていたので、角丸の親の中にある角丸の無い
     * スクロール領域で親の角が消え、交わらないときは幅・高さが負になっていた。
     */
    public static float[] intersectClip(float[] outer, float[] inner) {
        if (outer == null) return normalizeClip(inner.clone());
        float minX = Math.max(outer[0], inner[0]), minY = Math.max(outer[1], inner[1]);
        float maxX = Math.min(outer[2], inner[2]), maxY = Math.min(outer[3], inner[3]);
        maxX = Math.max(minX, maxX);
        maxY = Math.max(minY, maxY);
        float[] cornerX = {minX, maxX, maxX, minX}, cornerY = {minY, minY, maxY, maxY};
        boolean elliptical = outer.length >= 12 || inner.length >= 12;
        float[] out = new float[elliptical ? 12 : 8];
        out[0] = minX;
        out[1] = minY;
        out[2] = maxX;
        out[3] = maxY;
        for (int c = 0; c < 4; c++) {
            out[4 + c] = Math.max(cornerRadius(outer, c, cornerX[c], cornerY[c], false), cornerRadius(inner, c, cornerX[c], cornerY[c], false));
            if (elliptical) {
                out[8 + c] = Math.max(cornerRadius(outer, c, cornerX[c], cornerY[c], true), cornerRadius(inner, c, cornerX[c], cornerY[c], true));
            }
        }
        return normalizeClip(out);
    }

    /**
     * 矩形の角 {@code corner} に当たる半径。矩形の角から交わりの角までの距離（その軸）の分だけ小さくなる。
     * {@code vertical} なら縦の半径（楕円の角を持たない矩形は横と同じ）。
     */
    private static float cornerRadius(float[] clip, int corner, float x, float y, boolean vertical) {
        int index = vertical && clip.length >= 12 ? 8 + corner : 4 + corner;
        float r = clip.length > index ? clip[index] : 0;
        if (r <= 0) return 0;
        float cx = corner == 0 || corner == 3 ? clip[0] : clip[2];
        float cy = corner == 0 || corner == 1 ? clip[1] : clip[3];
        float dx = Math.abs(x - cx), dy = Math.abs(y - cy);
        if (clip.length < 12) {
            if (dx >= r || dy >= r) return 0;
            // 交わりの角が角丸矩形の内側にあれば、そこは切られていない直角の角（半径を当てると形が歪む）
            if (Math.hypot(r - dx, r - dy) <= r) return 0;
            return Math.max(0, r - Math.max(dx, dy));
        }
        // 楕円の角は軸ごとに縮める（横の半径は横の移動、縦の半径は縦の移動の分）
        float rx = clip[4 + corner], ry = clip[8 + corner];
        if (rx > 0 && ry > 0 && dx < rx && dy < ry) {
            double ux = (rx - dx) / rx, uy = (ry - dy) / ry;
            if (ux * ux + uy * uy <= 1.0) return 0;
        }
        return Math.max(0, r - (vertical ? dy : dx));
    }

    /** 逆転した矩形を空に潰し、半径を短い辺の半分までに収める。 */
    private static float[] normalizeClip(float[] c) {
        if (c[2] < c[0]) c[2] = c[0];
        if (c[3] < c[1]) c[3] = c[1];
        float halfW = Math.max(0, (c[2] - c[0]) / 2f), halfH = Math.max(0, (c[3] - c[1]) / 2f);
        if (c.length < 12) {
            float limit = Math.min(halfW, halfH);
            for (int i = 4; i < c.length; i++) c[i] = Math.clamp(c[i], 0, limit);
            return c;
        }
        // 楕円の角: 横は幅の半分、縦は高さの半分まで。どちらかが 0 の角は直角
        boolean elliptical = false;
        for (int i = 0; i < 4; i++) {
            c[4 + i] = Math.clamp(c[4 + i], 0, halfW);
            c[8 + i] = Math.clamp(c[8 + i], 0, halfH);
            if (c[4 + i] <= 0 || c[8 + i] <= 0) c[4 + i] = c[8 + i] = 0;
            if (c[4 + i] != c[8 + i]) elliptical = true;
        }
        return elliptical ? c : java.util.Arrays.copyOf(c, 8);
    }

    /**
     * スカラー引数から直接 {@link ClipRect} を交差・正規化して作る（配列の一時確保を避ける）。
     */
    public static ClipRect intersectClipRect(
            ClipRect outer,
            float inMinX, float inMinY, float inMaxX, float inMaxY,
            float rx0, float rx1, float rx2, float rx3,
            float ry0, float ry1, float ry2, float ry3,
            boolean inElliptical, String ownerId, List<ClipChainPart> chain) {
        if (outer == null) {
            return normalizeToClipRect(inMinX, inMinY, inMaxX, inMaxY,
                    rx0, rx1, rx2, rx3, ry0, ry1, ry2, ry3, inElliptical, ownerId, chain);
        }
        float minX = Math.max(outer.minX(), inMinX);
        float minY = Math.max(outer.minY(), inMinY);
        float maxX = Math.max(minX, Math.min(outer.maxX(), inMaxX));
        float maxY = Math.max(minY, Math.min(outer.maxY(), inMaxY));
        boolean outEll = outer.isElliptical();
        boolean anyEll = outEll || inElliptical;
        float outRx0 = Math.max(cornerRadiusScalar(outer, 0, minX, minY, false), cornerRadiusInline(inMinX, inMinY, inMaxX, inMaxY, rx0, ry0, inElliptical, 0, minX, minY, false));
        float outRx1 = Math.max(cornerRadiusScalar(outer, 1, maxX, minY, false), cornerRadiusInline(inMinX, inMinY, inMaxX, inMaxY, rx1, ry1, inElliptical, 1, maxX, minY, false));
        float outRx2 = Math.max(cornerRadiusScalar(outer, 2, maxX, maxY, false), cornerRadiusInline(inMinX, inMinY, inMaxX, inMaxY, rx2, ry2, inElliptical, 2, maxX, maxY, false));
        float outRx3 = Math.max(cornerRadiusScalar(outer, 3, minX, maxY, false), cornerRadiusInline(inMinX, inMinY, inMaxX, inMaxY, rx3, ry3, inElliptical, 3, minX, maxY, false));
        if (!anyEll) {
            return normalizeToClipRect(minX, minY, maxX, maxY,
                    outRx0, outRx1, outRx2, outRx3, 0f, 0f, 0f, 0f, false, ownerId, chain);
        }
        float outRy0 = Math.max(cornerRadiusScalar(outer, 0, minX, minY, true), cornerRadiusInline(inMinX, inMinY, inMaxX, inMaxY, rx0, ry0, inElliptical, 0, minX, minY, true));
        float outRy1 = Math.max(cornerRadiusScalar(outer, 1, maxX, minY, true), cornerRadiusInline(inMinX, inMinY, inMaxX, inMaxY, rx1, ry1, inElliptical, 1, maxX, minY, true));
        float outRy2 = Math.max(cornerRadiusScalar(outer, 2, maxX, maxY, true), cornerRadiusInline(inMinX, inMinY, inMaxX, inMaxY, rx2, ry2, inElliptical, 2, maxX, maxY, true));
        float outRy3 = Math.max(cornerRadiusScalar(outer, 3, minX, maxY, true), cornerRadiusInline(inMinX, inMinY, inMaxX, inMaxY, rx3, ry3, inElliptical, 3, minX, maxY, true));
        return normalizeToClipRect(minX, minY, maxX, maxY,
                outRx0, outRx1, outRx2, outRx3, outRy0, outRy1, outRy2, outRy3, true, ownerId, chain);
    }

    private static float cornerRadiusScalar(ClipRect clip, int corner, float x, float y, boolean vertical) {
        boolean ell = clip.isElliptical();
        float rx = switch (corner) {
            case 0 -> clip.radiusTopLeft();
            case 1 -> clip.radiusTopRight();
            case 2 -> clip.radiusBottomRight();
            default -> clip.radiusBottomLeft();
        };
        float ry = ell ? switch (corner) {
            case 0 -> clip.radiusTopLeftV();
            case 1 -> clip.radiusTopRightV();
            case 2 -> clip.radiusBottomRightV();
            default -> clip.radiusBottomLeftV();
        } : rx;
        float r = vertical && ell ? ry : rx;
        return cornerRadiusInline(clip.minX(), clip.minY(), clip.maxX(), clip.maxY(), rx, ry, ell, corner, x, y, vertical, r);
    }

    private static float cornerRadiusInline(float minX, float minY, float maxX, float maxY,
                                            float rx, float ry, boolean ell, int corner,
                                            float x, float y, boolean vertical) {
        float r = vertical && ell ? ry : rx;
        return cornerRadiusInline(minX, minY, maxX, maxY, rx, ry, ell, corner, x, y, vertical, r);
    }

    private static float cornerRadiusInline(float minX, float minY, float maxX, float maxY,
                                            float rx, float ry, boolean ell, int corner,
                                            float x, float y, boolean vertical, float r) {
        if (r <= 0f) return 0f;
        float cx = corner == 0 || corner == 3 ? minX : maxX;
        float cy = corner == 0 || corner == 1 ? minY : maxY;
        float dx = Math.abs(x - cx), dy = Math.abs(y - cy);
        if (!ell) {
            if (dx >= r || dy >= r) return 0f;
            if (Math.hypot(r - dx, r - dy) <= r) return 0f;
            return Math.max(0f, r - Math.max(dx, dy));
        }
        if (rx > 0f && ry > 0f && dx < rx && dy < ry) {
            double ux = (rx - dx) / rx, uy = (ry - dy) / ry;
            if (ux * ux + uy * uy <= 1.0) return 0f;
        }
        return Math.max(0f, r - (vertical ? dy : dx));
    }

    private static ClipRect normalizeToClipRect(
            float minX, float minY, float maxX, float maxY,
            float rx0, float rx1, float rx2, float rx3,
            float ry0, float ry1, float ry2, float ry3,
            boolean elliptical, String ownerId, List<ClipChainPart> chain) {
        if (maxX < minX) maxX = minX;
        if (maxY < minY) maxY = minY;
        float halfW = Math.max(0f, (maxX - minX) / 2f), halfH = Math.max(0f, (maxY - minY) / 2f);
        if (!elliptical) {
            float limit = Math.min(halfW, halfH);
            return new ClipRect(minX, minY, maxX, maxY,
                    Math.clamp(rx0, 0f, limit), Math.clamp(rx1, 0f, limit),
                    Math.clamp(rx2, 0f, limit), Math.clamp(rx3, 0f, limit),
                    ownerId, 0f, 0f, 0f, 0f, chain);
        }
        rx0 = Math.clamp(rx0, 0f, halfW); ry0 = Math.clamp(ry0, 0f, halfH);
        rx1 = Math.clamp(rx1, 0f, halfW); ry1 = Math.clamp(ry1, 0f, halfH);
        rx2 = Math.clamp(rx2, 0f, halfW); ry2 = Math.clamp(ry2, 0f, halfH);
        rx3 = Math.clamp(rx3, 0f, halfW); ry3 = Math.clamp(ry3, 0f, halfH);
        if (rx0 <= 0f || ry0 <= 0f) rx0 = ry0 = 0f;
        if (rx1 <= 0f || ry1 <= 0f) rx1 = ry1 = 0f;
        if (rx2 <= 0f || ry2 <= 0f) rx2 = ry2 = 0f;
        if (rx3 <= 0f || ry3 <= 0f) rx3 = ry3 = 0f;
        boolean stillEll = rx0 != ry0 || rx1 != ry1 || rx2 != ry2 || rx3 != ry3;
        return stillEll
                ? new ClipRect(minX, minY, maxX, maxY, rx0, rx1, rx2, rx3, ownerId, ry0, ry1, ry2, ry3, chain)
                : new ClipRect(minX, minY, maxX, maxY, rx0, rx1, rx2, rx3, ownerId, 0f, 0f, 0f, 0f, chain);
    }
}
