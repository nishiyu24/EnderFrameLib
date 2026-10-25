package com.nishiyu.enderframelib.core.text.font;

/**
 * 字形を焼くピクセルサイズの量子化。{@code scale} のアニメーションなどで大きさが連続して変わっても、
 * サイズごとの置き場が際限なく増えないようにする。
 *
 * 刻みは大きさに比例して粗くする（小さい字は 1/4px、大きい字は 1px）。どの大きさでも誤差は 1% 未満で、
 * 静止した文字（CSS の大きさ × GUI の倍率が刻みに乗る普通のサイズ）は変わらない。
 */
public final class AtlasSizing {

    private AtlasSizing() {}

    /** 焼くピクセルサイズを刻みの整数倍に丸める。 */
    public static float quantize(float pixelSize) {
        float step = pixelSize <= 32f ? 0.25f : pixelSize <= 64f ? 0.5f : 1f;
        return Math.max(0.25f, Math.round(pixelSize / step) * step);
    }

    /** 置き場の鍵に使う整数（量子化した大きさ × 4）。 */
    public static int key(float quantizedPixelSize) {
        return Math.round(quantizedPixelSize * 4f);
    }
}
