package com.nishiyu.enderframelib.core.text;

/**
 * 幅に依存しない段落の下ごしらえ: 折り返し候補の断片・段落内の開始位置・断片ごとの幅と、境目の詰め。
 * 幅が変わる（ウィンドウのリサイズ・flex の再測定）たびに UAX #14 の判定と語ごとの測定をやり直さず、
 * 行の分割はこの数値だけで行う（{@link TextShaper#segmented}）。配列は共有されるので書き換えない。
 *
 * <p>{@code widths} は断片 {@code i} ごとに {@link #STRIDE} (=5) 要素ずつ
 * {@code [fullW, trimmedW, joinW, strippedTrailingW, strippedW]} を密詰めして配列オブジェクト数を削減する。</p>
 *
 * @param segs             折り返し候補の断片（末尾の空白は直前の断片に付く）
 * @param offsets          段落内での断片の開始位置
 * @param widths           断片ごとの 5 種の幅を {@link #STRIDE} 単位で連結した配列
 * @param unwrappedAdvance 1 行にそのまま並べたときの送り幅
 */
public record ParagraphSegments(String[] segs, int[] offsets, float[] widths, float unwrappedAdvance) {

    public static final int OFF_FULL = 0;
    public static final int OFF_TRIMMED = 1;
    public static final int OFF_JOIN = 2;
    public static final int OFF_STRIPPED_TRAILING = 3;
    public static final int OFF_STRIPPED = 4;
    public static final int STRIDE = 5;

    public ParagraphSegments(String[] segs, int[] offsets, float[] widths) {
        this(segs, offsets, widths, computeUnwrappedAdvance(widths, segs.length));
    }

    public float fullW(int i) {
        return widths[i * STRIDE + OFF_FULL];
    }

    public float trimmedW(int i) {
        return widths[i * STRIDE + OFF_TRIMMED];
    }

    public float joinW(int i) {
        return widths[i * STRIDE + OFF_JOIN];
    }

    public float strippedTrailingW(int i) {
        return widths[i * STRIDE + OFF_STRIPPED_TRAILING];
    }

    public float strippedW(int i) {
        return widths[i * STRIDE + OFF_STRIPPED];
    }

    public float maxStrippedW() {
        float max = 0f;
        for (int p = OFF_STRIPPED; p < widths.length; p += STRIDE) {
            if (widths[p] > max) max = widths[p];
        }
        return max;
    }

    private static float computeUnwrappedAdvance(float[] widths, int count) {
        float total = 0;
        for (int s = 0, p = 0; s < count; s++, p += STRIDE) {
            total += widths[p + OFF_FULL] + Math.max(0f, widths[p + OFF_JOIN]);
        }
        return total;
    }
}
