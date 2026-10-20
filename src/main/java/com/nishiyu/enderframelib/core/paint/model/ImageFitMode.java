package com.nishiyu.enderframelib.core.paint.model;

import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;

import java.util.Locale;

/**
 * 画像およびメディア要素のフィットモード（object-fit）。
 */
public enum ImageFitMode {
    FILL,
    CONTAIN,
    COVER,
    SCALE_DOWN,
    NONE;

    public static ImageFitMode fromString(String str) {
        if (str == null) return FILL;
        return switch (str.toLowerCase(Locale.ROOT)) {
            case "contain" -> CONTAIN;
            case "cover" -> COVER;
            case "scale-down" -> SCALE_DOWN;
            case "none" -> NONE;
            default -> FILL;
        };
    }
}
