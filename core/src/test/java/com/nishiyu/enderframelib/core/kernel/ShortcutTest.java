package com.nishiyu.enderframelib.core.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 切り替えの表（ENGINE_REARCHITECTURE_PLAN S1 ③）。 */
class ShortcutTest {

    @Test
    void initialValuesComeFromEngineFlags() {
        assertEquals(EngineFlags.SOFT_CULL_OFFSCREEN, Shortcut.SOFT_CULL_OFFSCREEN.initial());
    }

    @Test
    void withAppliesOnlyInsideAndRestoresEvenOnException() {
        boolean cutoff = Shortcut.SOFT_CULL_OFFSCREEN.on(), reuse = Shortcut.CLIP_SHARE.on();
        String seen = Shortcut.with(Map.of(Shortcut.SOFT_CULL_OFFSCREEN, !cutoff, Shortcut.CLIP_SHARE, !reuse), () -> Shortcut.SOFT_CULL_OFFSCREEN.on() + "/" + Shortcut.CLIP_SHARE.on());
        assertEquals(!cutoff + "/" + !reuse, seen);
        assertEquals(cutoff, Shortcut.SOFT_CULL_OFFSCREEN.on());
        assertEquals(reuse, Shortcut.CLIP_SHARE.on());

        assertThrows(IllegalStateException.class, () -> Shortcut.with(Map.of(Shortcut.SOFT_CULL_OFFSCREEN, !cutoff), (Runnable) () -> {
            throw new IllegalStateException();
        }));
        assertEquals(cutoff, Shortcut.SOFT_CULL_OFFSCREEN.on(), "例外でも戻る");
    }

    @Test
    void setChangesOneSwitchAndResetAllRestoresTheInitialValues() {
        boolean other = Shortcut.GL_RETAINED_SCROLL.on();
        Shortcut.SOFT_TILED.set(false);
        assertFalse(Shortcut.SOFT_TILED.on());
        assertEquals(other, Shortcut.GL_RETAINED_SCROLL.on(), "ほかの切り替えは変わらない");
        Shortcut.resetAll();
        assertEquals(Shortcut.SOFT_TILED.initial(), Shortcut.SOFT_TILED.on());
    }

    /** 状態は 1 つの配列だけで、定数ごとの可変の欄を持たない（切り替えの読み口が 1 つ）。 */
    @Test
    void stateIsASingleArray() {
        int mutable = 0;
        for (Field f : Shortcut.class.getDeclaredFields()) {
            if (f.isEnumConstant() || f.isSynthetic()) continue;
            if (!Modifier.isFinal(f.getModifiers())) {
                mutable++;
                assertTrue(Modifier.isStatic(f.getModifiers()) && Modifier.isPrivate(f.getModifiers()) && f.getType() == boolean[].class, f.toString());
            }
        }
        assertEquals(1, mutable);
    }
}
