package com.nishiyu.enderframelib.core.paint;

/** 選んだ画像の候補（{@link ImageSources}）。{@code density} は画像の画素数を割って CSS px の固有サイズにする値。 */
public record ImageChoice(String url, double density) {}
