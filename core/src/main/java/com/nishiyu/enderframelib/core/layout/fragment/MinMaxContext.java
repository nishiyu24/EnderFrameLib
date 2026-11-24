package com.nishiyu.enderframelib.core.layout.fragment;

/**
 * 内在サイズの問い合わせの文脈（Engine v3 M2a）。M2a の範囲（横書き・Block/Inline/Flex）では内在サイズが箱と
 * そのスタイルだけで決まるので、文脈は {@link #NONE} 1 つ（利用可能幅に依らない）。縦書き・{@code aspect-ratio}・
 * % の解決基準が要る型は M2b でここへ欄を足す。親の縮小幅・flex の柔軟長の決定では変わらない。
 */
public final class MinMaxContext {
    public static final MinMaxContext NONE = new MinMaxContext();

    private MinMaxContext() { }
}
