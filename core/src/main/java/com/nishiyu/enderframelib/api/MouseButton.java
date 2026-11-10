package com.nishiyu.enderframelib.api;

import org.jetbrains.annotations.ApiStatus;

/**
 * マウスのボタン。DOM では左が {@code button=0}（{@code click}）、中が {@code 1}、右が {@code 2}
 * （{@code contextmenu} と {@code auxclick}）として届く。
 */
@ApiStatus.AvailableSince("1.0")
public enum MouseButton {
    LEFT, RIGHT, MIDDLE;

    /** GLFW のボタン番号（0=左・1=右・2=中）。 */
    public int glfwButton() {
        return ordinal();
    }

    /** GLFW のボタン番号から。範囲外なら null。 */
    public static MouseButton fromGlfw(int button) {
        MouseButton[] all = values();
        return button >= 0 && button < all.length ? all[button] : null;
    }
}
