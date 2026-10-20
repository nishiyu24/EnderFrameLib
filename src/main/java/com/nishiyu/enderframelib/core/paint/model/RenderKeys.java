package com.nishiyu.enderframelib.core.paint.model;

import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;

/**
 * DisplayItem の events マップで使用される内部プロトコルキー定数。
 */
public final class RenderKeys {
    private RenderKeys() {}

    public static final String MC_PARENT = "mc-parent";
    public static final String MC_CLIP = "mc-clip";
    public static final String MC_RADIUS = "mc-radius";
    public static final String MC_GRADIENT = "mc-gradient";
    public static final String MC_FONT_H = "mc-font-h";
    public static final String MC_TITLE = "mc-title";
    public static final String MC_ORIGIN = "mc-origin";
    public static final String MC_DELAY = "mc-delay";
    public static final String MC_EASING = "mc-easing";
    public static final String MC_TRANSITION_PROP = "mc-transition-prop";
    public static final String MC_SCROLLABLE = "mc-scrollable";
    public static final String MC_SCROLL_CURRENT = "mc-scroll-current";
    /** 算出値の {@code pointer-events}（{@code none} のときだけ載せる）。ヒットテストはこの部品を透過する。 */
    public static final String POINTER_EVENTS = "pointer-events";
}
