package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.ImageFitMode;
import java.util.Locale;

/** 内容ボックスの左上を原点とした、中身の矩形。ボックスからはみ出してよい（はみ出しは切る）。 */
public record ImagePlacement(float x, float y, float w, float h) {}
