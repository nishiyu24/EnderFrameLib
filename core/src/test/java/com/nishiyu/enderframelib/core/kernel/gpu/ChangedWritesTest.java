package com.nishiyu.enderframelib.core.kernel.gpu;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** {@link PersistentVramBuffer#changedWrites()}: 値が変わった書き込みだけを数える（同じ値の書き込みは数えず、範囲も広げない）。 */
class ChangedWritesTest {
    @Test
    void onlyRealChangesAreCounted() {
        PersistentVramBuffer b = new PersistentVramBuffer(64, 8, null);
        int s = b.allocate();
        assertEquals(0, b.changedWrites());
        b.putInt(s, 0, 5);
        b.putFloat(s, 4, 1.5f);
        b.putHalf(s, 8, 2f);
        b.putShort(s, 12, 7);
        assertEquals(4, b.changedWrites());
        b.flush();
        b.putInt(s, 0, 5);
        b.putFloat(s, 4, 1.5f);
        b.putHalf(s, 8, 2f);
        b.putShort(s, 12, 7);
        assertEquals(4, b.changedWrites(), "同じ値の書き込みは数えない");
        assertFalse(b.isDirty(), "同じ値の書き込みは汚れにしない");
        b.putInt(s, 0, 6);
        assertEquals(5, b.changedWrites());
        assertTrue(b.isDirty());
    }

    @Test
    void writingBackAndForthInOneFrameCountsEveryChange() {
        // 以前のクリップ欄の書き方（NONE → 個数）は、毎フレーム 2 回の「変更」になっていた
        PersistentVramBuffer b = new PersistentVramBuffer(64, 8, null);
        int s = b.allocate();
        b.putInt(s, 8, 2);
        long base = b.changedWrites();
        for (int i = 0; i < 5; i++) { b.putInt(s, 8, -1); b.putInt(s, 8, 2); }
        assertEquals(base + 10, b.changedWrites());
    }
}
