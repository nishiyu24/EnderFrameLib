package com.nishiyu.enderframelib.api;

import org.jetbrains.annotations.ApiStatus;

/** {@link WebViewSurface#keyDown} / {@link WebViewSurface#keyUp} の修飾キーのビット（GLFW の {@code GLFW_MOD_*} と同じ値）。 */
@ApiStatus.AvailableSince("1.0")
public final class KeyMods {
    private KeyMods() {}

    public static final int SHIFT = 1;
    public static final int CONTROL = 2;
    public static final int ALT = 4;
    public static final int SUPER = 8;
}
