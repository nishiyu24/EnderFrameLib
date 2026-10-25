package com.nishiyu.enderframelib.client.renderer;

/** 他パッケージのテストから {@link DisplayListBuilder} の transform 解釈を使うための窓口。 */
public final class TransformParseTestBridge {
    private TransformParseTestBridge() {}

    /** {@code {translateX, translateY}}。 */
    public static float[] translate(String transform, float boxW, float boxH) {
        float[] tr = BoxAnimation.parseTransformProps(transform, boxW, boxH, 0f, 0f, 1f, 1f, 0f);
        return new float[]{tr[0], tr[1]};
    }
}
