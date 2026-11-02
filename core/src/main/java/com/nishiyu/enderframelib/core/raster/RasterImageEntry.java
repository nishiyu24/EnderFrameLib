package com.nishiyu.enderframelib.core.raster;

import java.util.LinkedHashMap;
import java.util.Map;

/** 画素と版。版が変わったらテクスチャを上げ直す。 */
public record RasterImageEntry(Pixmap pixmap, long version) {}
