package com.nishiyu.enderframelib.server;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * クライアント指定の NBT パスが許可リストを通り抜けないことの番人（I-1）。
 * ここが緩むと、HUD の購読要求だけで他プレイヤーのインベントリが読める状態に戻る。
 */
public class NbtPathFilterTest {

    @Test
    public void normalizeStripsNbtPrefix() {
        assertEquals("Health", NbtPathFilter.normalize("nbt.Health"));
        assertEquals("Health", NbtPathFilter.normalize("  Health  "));
        assertEquals("Attributes.Base", NbtPathFilter.normalize("nbt.Attributes.Base"));
    }

    @Test
    public void normalizeRejectsMalformedPaths() {
        assertNull(NbtPathFilter.normalize(null));
        assertNull(NbtPathFilter.normalize(""));
        assertNull(NbtPathFilter.normalize("nbt."));
        assertNull(NbtPathFilter.normalize(".Health"));
        assertNull(NbtPathFilter.normalize("Health."));
        assertNull(NbtPathFilter.normalize("Health..Max"));
        assertNull(NbtPathFilter.normalize("Health/../Inventory"));
        assertNull(NbtPathFilter.normalize("Inventory[0]"));
        assertNull(NbtPathFilter.normalize("a.b.c.d.e.f.g"), "深さの上限を超えたパスは拒否する");
    }

    @Test
    public void sanitizeDropsBadPathsKeepsOrderAndCaps() {
        List<String> result = NbtPathFilter.sanitize(List.of("nbt.Health", "bad path", "Air", "Health"));
        assertEquals(List.of("Health", "Air"), result, "重複は畳み、不正なパスは落とす");

        assertEquals(List.of(), NbtPathFilter.sanitize(null));
        assertEquals(List.of(), NbtPathFilter.sanitize(List.of()));

        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < NbtPathFilter.MAX_PATHS * 2; i++) many.add("Key" + i);
        assertEquals(NbtPathFilter.MAX_PATHS, NbtPathFilter.sanitize(many).size());
    }

    @Test
    public void onlyAllowListedRootsAreReadable() {
        assertTrue(NbtPathFilter.isRootAllowed("Health", false));
        assertTrue(NbtPathFilter.isRootAllowed("CustomName", false));

        // 持ち物・エンダーチェスト・能力値は読み出せてはいけない
        assertFalse(NbtPathFilter.isRootAllowed("Inventory", false));
        assertFalse(NbtPathFilter.isRootAllowed("Items", false));
        assertFalse(NbtPathFilter.isRootAllowed("EnderItems", false));
        assertFalse(NbtPathFilter.isRootAllowed("abilities", false));
        assertFalse(NbtPathFilter.isRootAllowed("Inventory.Slot", false));
        assertFalse(NbtPathFilter.isRootAllowed("", false));
        assertFalse(NbtPathFilter.isRootAllowed(null, false));
    }

    @Test
    public void playerTargetsUseNarrowerAllowList() {
        // 他プレイヤーを対象にした場合、一般エンティティで許している BlockEntity 系キーも読めない
        assertTrue(NbtPathFilter.isRootAllowed("CookTime", false));
        assertFalse(NbtPathFilter.isRootAllowed("CookTime", true));

        assertTrue(NbtPathFilter.isRootAllowed("Health", true));
        assertFalse(NbtPathFilter.isRootAllowed("Inventory", true));
        assertFalse(NbtPathFilter.isRootAllowed("recipeBook", true));
    }

    @Test
    public void allowRootValidatesCharacterSet() {
        assertFalse(NbtPathFilter.allowRoot("my key"));
        assertFalse(NbtPathFilter.allowRoot("../etc"));
        assertFalse(NbtPathFilter.allowRoot(null));

        String custom = "EnderFrameTestRoot";
        assertTrue(NbtPathFilter.allowRoot(custom));
        assertTrue(NbtPathFilter.isRootAllowed(custom, false));
        assertFalse(NbtPathFilter.isRootAllowed(custom, true), "プレイヤー側の許可リストには追加しない");
    }

    @Test
    public void sanitizeIgnoresNullElements() {
        assertEquals(List.of("Health"), NbtPathFilter.sanitize(java.util.Arrays.asList(null, "Health")));
        assertEquals(List.of(), NbtPathFilter.sanitize(Collections.singletonList(null)));
    }
}
