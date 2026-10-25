package com.nishiyu.enderframelib.server.state;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@code toCompoundTag} で保存した状態を {@code load} で戻せること（ブロック画面の永続化の土台）。 */
public class UIStateLoadTest {

    @Test
    @DisplayName("toCompoundTag → load で値が戻り、変更として記録される")
    void roundTrip() {
        UIStateImpl saved = new UIStateImpl("m:t", "block:1,2,3");
        saved.set("status", "running").set("count", 7).set("on", true).set("names", List.of("a", "b"));
        CompoundTag tag = saved.toCompoundTag();

        UIStateImpl restored = new UIStateImpl("m:t", "block:1,2,3");
        restored.load(tag);

        assertEquals("running", restored.getString("status"));
        assertEquals(7, restored.getInt("count", -1));
        assertTrue(restored.getBoolean("on", false));
        assertEquals(List.of("a", "b"), restored.get("names"));
        assertTrue(restored.hasChanges());
    }
}
