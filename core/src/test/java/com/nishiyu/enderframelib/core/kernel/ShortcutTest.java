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
        assertEquals(EngineFlags.CUTOFF, Shortcut.CUTOFF.initial());
        assertEquals(EngineFlags.CHILD_PATCH, Shortcut.CHILD_PATCH.initial());
        assertEquals(EngineFlags.VERIFY_STAMPS, Shortcut.VERIFY_STAMPS.initial());
        assertTrue(Shortcut.STACKING_PATCH.initial(), "システムプロパティの無い切り替えは既定オン");
    }

    @Test
    void withAppliesOnlyInsideAndRestoresEvenOnException() {
        boolean cutoff = Shortcut.CUTOFF.on(), prune = Shortcut.PRUNE.on();
        String seen = Shortcut.with(Map.of(Shortcut.CUTOFF, !cutoff, Shortcut.PRUNE, !prune), () -> Shortcut.CUTOFF.on() + "/" + Shortcut.PRUNE.on());
        assertEquals(!cutoff + "/" + !prune, seen);
        assertEquals(cutoff, Shortcut.CUTOFF.on());
        assertEquals(prune, Shortcut.PRUNE.on());

        assertThrows(IllegalStateException.class, () -> Shortcut.with(Map.of(Shortcut.CUTOFF, !cutoff), (Runnable) () -> {
            throw new IllegalStateException();
        }));
        assertEquals(cutoff, Shortcut.CUTOFF.on(), "例外でも戻る");
    }

    @Test
    void setChangesOneSwitchAndResetAllRestoresTheInitialValues() {
        boolean other = Shortcut.FIT_REUSE.on();
        Shortcut.SOFT_TILED.set(false);
        assertFalse(Shortcut.SOFT_TILED.on());
        assertEquals(other, Shortcut.FIT_REUSE.on(), "ほかの切り替えは変わらない");
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
