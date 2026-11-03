package com.nishiyu.enderframelib.common;

import java.util.function.IntFunction;

/**
 * 入力キーと修飾キーの、ウィンドウ API に依存しない定数。
 *
 * <p>値は GLFW / USB HID 風の事実上の標準（英字・数字は ASCII の大文字、特殊キーは 256 以降、修飾は 1 / 2 / 4 / 8 のビット）に
 * 意図して揃えてある。GLFW を使うウィンドウ（OpenGL でも Vulkan でも）は受け取ったコードをそのまま渡せ、
 * それ以外の環境（Swing・SDL・ゲームエンジン）はこの表へ変換して {@code StandaloneView.keyDown} に渡す。
 * コア（L0）は GLFW を import しない。</p>
 */
public final class Keys {
    private Keys() {}

    public static final int SPACE = 32;
    public static final int A = 65, C = 67, V = 86, X = 88;
    public static final int ESCAPE = 256, ENTER = 257, TAB = 258, BACKSPACE = 259, DELETE = 261;
    public static final int RIGHT = 262, LEFT = 263, DOWN = 264, UP = 265;
    public static final int PAGE_UP = 266, PAGE_DOWN = 267, HOME = 268, END = 269;
    public static final int KP_ENTER = 335;

    public static final int MOD_SHIFT = 1, MOD_CONTROL = 2, MOD_ALT = 4, MOD_SUPER = 8;

    /**
     * 印字キーの表示名を引く口（{@code KeyboardEvent.key} の既定値用）。キーボード配列に依るのでウィンドウ側が教える
     * （GLFW なら {@code glfwGetKeyName}）。未設定なら null を返す実装として扱う。
     */
    private static volatile IntFunction<String> nameProvider = code -> null;

    public static void nameProvider(IntFunction<String> provider) {
        nameProvider = provider != null ? provider : code -> null;
    }

    static String nameOf(int code) {
        return nameProvider.apply(code);
    }
}
