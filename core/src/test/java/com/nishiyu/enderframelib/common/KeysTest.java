package com.nishiyu.enderframelib.common;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link Keys} の値が GLFW と一致し続けること（GLFW を使うウィンドウがコードをそのまま渡せる前提）。 */
class KeysTest {
    @Test
    void valuesMatchGlfw() {
        assertEquals(GLFW.GLFW_KEY_SPACE, Keys.SPACE);
        assertEquals(GLFW.GLFW_KEY_A, Keys.A);
        assertEquals(GLFW.GLFW_KEY_C, Keys.C);
        assertEquals(GLFW.GLFW_KEY_V, Keys.V);
        assertEquals(GLFW.GLFW_KEY_X, Keys.X);
        assertEquals(GLFW.GLFW_KEY_ESCAPE, Keys.ESCAPE);
        assertEquals(GLFW.GLFW_KEY_ENTER, Keys.ENTER);
        assertEquals(GLFW.GLFW_KEY_TAB, Keys.TAB);
        assertEquals(GLFW.GLFW_KEY_BACKSPACE, Keys.BACKSPACE);
        assertEquals(GLFW.GLFW_KEY_DELETE, Keys.DELETE);
        assertEquals(GLFW.GLFW_KEY_RIGHT, Keys.RIGHT);
        assertEquals(GLFW.GLFW_KEY_LEFT, Keys.LEFT);
        assertEquals(GLFW.GLFW_KEY_DOWN, Keys.DOWN);
        assertEquals(GLFW.GLFW_KEY_UP, Keys.UP);
        assertEquals(GLFW.GLFW_KEY_PAGE_UP, Keys.PAGE_UP);
        assertEquals(GLFW.GLFW_KEY_PAGE_DOWN, Keys.PAGE_DOWN);
        assertEquals(GLFW.GLFW_KEY_HOME, Keys.HOME);
        assertEquals(GLFW.GLFW_KEY_END, Keys.END);
        assertEquals(GLFW.GLFW_KEY_KP_ENTER, Keys.KP_ENTER);
        assertEquals(GLFW.GLFW_MOD_SHIFT, Keys.MOD_SHIFT);
        assertEquals(GLFW.GLFW_MOD_CONTROL, Keys.MOD_CONTROL);
        assertEquals(GLFW.GLFW_MOD_ALT, Keys.MOD_ALT);
        assertEquals(GLFW.GLFW_MOD_SUPER, Keys.MOD_SUPER);
    }

    @Test
    void printableKeyNameComesFromTheInstalledProvider() {
        try {
            assertEquals("Unidentified", UiInput.glfwKeyToDomKey(Keys.A)); // 既定の提供元は null を返す
            Keys.nameProvider(code -> code == Keys.A ? "a" : null);
            assertEquals("a", UiInput.glfwKeyToDomKey(Keys.A));
        } finally {
            Keys.nameProvider(null);
        }
    }
}
