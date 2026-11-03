package com.nishiyu.enderframelib.core.paint.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ItemKeysTest {

    @Test
    void sameIdSameKeyDifferentIdDifferentKey() {
        long a = ItemKeys.of("k_a"), b = ItemKeys.of("k_b");
        assertNotEquals(0L, a);
        assertNotEquals(a, b);
        assertEquals(a, ItemKeys.of(new String("k_a")));
        assertEquals(0L, ItemKeys.of(null));
    }

    @Test
    void displayItemKeyFollowsIdAndSurvivesCopies() {
        DisplayItem it = new DisplayItem("n1_bg", "n1", ElementType.NODE, 0, 0, 10, 10, null, 0, 0, null, 0, 1f,
                0f, 0f, 1f, 1f, 0f, 0, null, null, null);
        assertEquals(ItemKeys.of("n1_bg"), it.key());
        assertEquals(it.key(), it.withText("x").key());
        assertEquals(it.key(), it.withTranslation(3f, 4f).key());
    }
}
