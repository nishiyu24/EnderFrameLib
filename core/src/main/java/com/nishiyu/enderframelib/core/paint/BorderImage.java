package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.css.ComputedStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code border-image}（9-slice）の幾何。スタイルと画像の画素数、枠線ボックスと枠線の太さから、9 つの
 * 「画像の切り出し（画素）→ 描く矩形（CSS px）」の組を決める。描画 API に依存せず（L0）、結果は
 * 画像の項目 9 つ以内に落ちるので、どのバックエンド（CPU・GL・Vulkan 等）でも同じ見た目になる。
 *
 * <p>対応: {@code border-image-source}（{@code url()}）・{@code slice}（数値・%・{@code fill}）・{@code width}
 * （数値＝枠線の太さの倍数・長さ・%・{@code auto}）・{@code stretch}。{@code outset} と {@code repeat / round / space} は
 * 未対応（{@code stretch} として描く）。
 */
public final class BorderImage {

    /** 切り出し {@code (sx, sy, sw, sh)}（画像の画素）を、{@code (dx, dy, dw, dh)}（CSS px）へ貼る 1 区画。 */
    public record Cell(int sx, int sy, int sw, int sh, float dx, float dy, float dw, float dh) {}

    private BorderImage() {}

    /** {@code border-image-source} が {@code url()} ならその URL。無い・{@code none}・画像でなければ null。 */
    public static String sourceUrl(ComputedStyle style) {
        String v = style.get("border-image-source");
        if (v == null) return null;
        return new BoxDecorations.BackgroundLayer(v, "", "", "", "", "", 1.0).url();
    }

    /**
     * @param imgW   画像の幅（画素）
     * @param boxX   枠線ボックスの左上と大きさ（CSS px）
     * @param border 上・右・下・左の枠線の太さ（CSS px）
     * @return 区画（0〜9 個）。貼る先が空の区画は含めない
     */
    public static List<Cell> cells(ComputedStyle style, int imgW, int imgH, float boxX, float boxY, float boxW, float boxH,
                                   float[] border) {
        List<Cell> out = new ArrayList<>(9);
        if (imgW <= 0 || imgH <= 0 || !(boxW > 0) || !(boxH > 0)) return out;

        // slice: 上・右・下・左（画像の画素。% は縦横それぞれの画像の大きさに対して）
        boolean fill = false;
        List<String> sliceTokens = new ArrayList<>();
        for (String t : split(style.getOrDefault("border-image-slice", "100%"))) {
            if ("fill".equalsIgnoreCase(t)) fill = true;
            else sliceTokens.add(t);
        }
        float[] s = expand4(sliceTokens, new float[]{imgH, imgW, imgH, imgW}, 1f, 0f, new float[]{imgH, imgW, imgH, imgW});
        for (int i = 0; i < 4; i++) s[i] = Math.max(0, Math.min(s[i], (i % 2 == 0) ? imgH : imgW));
        int st = Math.round(s[0]), sr = Math.round(s[1]), sb = Math.round(s[2]), sl = Math.round(s[3]);
        // 向かい合う辺の合計が画像を超えるときは、両方とも画像いっぱいに収める（仕様どおり、中央が空になる）
        if (sl + sr > imgW) { sl = Math.min(sl, imgW); sr = imgW - sl; }
        if (st + sb > imgH) { st = Math.min(st, imgH); sb = imgH - st; }

        // width: 上・右・下・左（CSS px）。数値は枠線の太さの倍数、auto は切り出しの大きさ
        float[] sliceCss = {st, sr, sb, sl};
        float fontPx = style.fontSizePx();
        float[] bw = new float[4];
        List<String> widthTokens = split(style.getOrDefault("border-image-width", "1"));
        for (int i = 0; i < 4; i++) {
            String tok = widthTokens.isEmpty() ? "1" : widthTokens.get(sideIndex(widthTokens.size(), i));
            bw[i] = widthOf(tok, border[i], sliceCss[i], (i % 2 == 0) ? boxH : boxW, fontPx);
        }
        // 向かい合う辺の合計が箱を超えるときは、4 辺を同じ比で縮める（CSS Backgrounds 3 §6.2）
        float f = 1f;
        if (bw[1] + bw[3] > boxW && bw[1] + bw[3] > 0) f = Math.min(f, boxW / (bw[1] + bw[3]));
        if (bw[0] + bw[2] > boxH && bw[0] + bw[2] > 0) f = Math.min(f, boxH / (bw[0] + bw[2]));
        for (int i = 0; i < 4; i++) bw[i] *= f;
        float wt = bw[0], wr = bw[1], wb = bw[2], wl = bw[3];

        float x1 = boxX + wl, x2 = boxX + boxW - wr, y1 = boxY + wt, y2 = boxY + boxH - wb;
        int cx = imgW - sl - sr, cy = imgH - st - sb;
        add(out, 0, 0, sl, st, boxX, boxY, wl, wt);                         // 左上
        add(out, sl, 0, cx, st, x1, boxY, x2 - x1, wt);                     // 上
        add(out, imgW - sr, 0, sr, st, x2, boxY, wr, wt);                   // 右上
        add(out, 0, st, sl, cy, boxX, y1, wl, y2 - y1);                     // 左
        if (fill) add(out, sl, st, cx, cy, x1, y1, x2 - x1, y2 - y1);       // 中央（fill のときだけ）
        add(out, imgW - sr, st, sr, cy, x2, y1, wr, y2 - y1);               // 右
        add(out, 0, imgH - sb, sl, sb, boxX, y2, wl, wb);                   // 左下
        add(out, sl, imgH - sb, cx, sb, x1, y2, x2 - x1, wb);               // 下
        add(out, imgW - sr, imgH - sb, sr, sb, x2, y2, wr, wb);             // 右下
        return out;
    }

    private static void add(List<Cell> out, int sx, int sy, int sw, int sh, float dx, float dy, float dw, float dh) {
        if (sw <= 0 || sh <= 0 || !(dw > 0.001f) || !(dh > 0.001f)) return;
        out.add(new Cell(sx, sy, sw, sh, dx, dy, dw, dh));
    }

    /** CSS の 1〜4 値の展開（上・右・下・左）の添字。 */
    private static int sideIndex(int n, int side) {
        return switch (n) {
            case 1 -> 0;
            case 2 -> side % 2;
            case 3 -> side == 3 ? 1 : side;
            default -> side;
        };
    }

    /** 数値（{@code unit} 倍）または % を、1〜4 値の展開で 4 辺に割る。{@code pctBase} は辺ごとの % の基準。 */
    private static float[] expand4(List<String> tokens, float[] numberBase, float numberScale, float fallback, float[] pctBase) {
        float[] out = new float[4];
        for (int i = 0; i < 4; i++) {
            if (tokens.isEmpty()) { out[i] = Float.isNaN(fallback) ? numberBase[i] : fallback; continue; }
            String t = tokens.get(sideIndex(tokens.size(), i)).toLowerCase(Locale.ROOT);
            try {
                if (t.endsWith("%")) out[i] = Float.parseFloat(t.substring(0, t.length() - 1)) / 100f * pctBase[i];
                else out[i] = Float.parseFloat(t) * numberScale;
            } catch (NumberFormatException e) {
                out[i] = Float.isNaN(fallback) ? numberBase[i] : fallback;
            }
        }
        return out;
    }

    private static float widthOf(String token, float borderPx, float slicePx, float percentBase, float fontPx) {
        String t = token.trim().toLowerCase(Locale.ROOT);
        try {
            if (t.equals("auto")) return slicePx;
            if (t.endsWith("%")) return Float.parseFloat(t.substring(0, t.length() - 1)) / 100f * percentBase;
            if (t.endsWith("px")) return Float.parseFloat(t.substring(0, t.length() - 2));
            if (t.endsWith("rem")) return Float.parseFloat(t.substring(0, t.length() - 3)) * 16f;
            if (t.endsWith("em")) return Float.parseFloat(t.substring(0, t.length() - 2)) * fontPx;
            return Float.parseFloat(t) * borderPx; // 無単位の数値は枠線の太さの倍数
        } catch (NumberFormatException e) {
            return borderPx;
        }
    }

    private static List<String> split(String v) {
        List<String> out = new ArrayList<>();
        if (v == null) return out;
        for (String t : v.trim().split("\s+")) if (!t.isEmpty()) out.add(t);
        return out;
    }
}
