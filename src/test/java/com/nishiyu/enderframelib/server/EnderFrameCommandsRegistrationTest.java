package com.nishiyu.enderframelib.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.nishiyu.enderframelib.api.template.EnderScreenBlockEntity;
import com.nishiyu.enderframelib.content.ScreenBlockEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

public class EnderFrameCommandsRegistrationTest {

    private static final Unsafe UNSAFE;
    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to obtain Unsafe", e);
        }
    }

    @BeforeEach
    void setUp() {
        EnderFrameServerState.clearAll();
    }

    @AfterEach
    void tearDown() {
        EnderFrameServerState.clearAll();
    }

    @Test
    @DisplayName("enderframe および ef コマンドが全サブコマンド構造を含めて正しく登録される")
    void testCommandRegistrationStructure() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        EnderFrameCommands.register(dispatcher);

        LiteralCommandNode<CommandSourceStack> enderframe = dispatcher.getRoot().getChild("enderframe") instanceof LiteralCommandNode<CommandSourceStack> node ? node : null;
        assertNotNull(enderframe, "enderframe コマンドがルートに登録されている必要があります");

        LiteralCommandNode<CommandSourceStack> ef = dispatcher.getRoot().getChild("ef") instanceof LiteralCommandNode<CommandSourceStack> node ? node : null;
        assertNotNull(ef, "短縮形 ef コマンドがルートに登録されている必要があります");

        // enderframe の主要カテゴリチェック
        for (String name : new String[]{"hud", "hologram", "block", "screen", "item", "session", "patch",
                "state", "publish", "dev", "devkit", "open", "open_inline", "desktop"}) {
            assertHasChild(enderframe, name);
            assertHasChild(ef, name);
        }

        CommandNode<CommandSourceStack> hud = enderframe.getChild("hud");
        assertHasChild(hud, "set");
        assertHasChild(hud, "clear");

        CommandNode<CommandSourceStack> hologram = enderframe.getChild("hologram");
        assertHasChild(hologram, "spawn");
        assertHasChild(hologram, "bind");
        assertHasChild(hologram, "remove");
        assertHasChild(hologram, "clear");
        assertHasChild(hologram, "list");

        // block は screen と同じ構造
        for (String name : new String[]{"screen", "block"}) {
            CommandNode<CommandSourceStack> node = enderframe.getChild(name);
            for (String sub : new String[]{"place", "load", "draw", "template", "patch", "info", "clear"}) {
                assertHasChild(node, sub);
            }
        }

        CommandNode<CommandSourceStack> item = enderframe.getChild("item");
        assertHasChild(item, "give");
        assertHasChild(item, "bind");

        CommandNode<CommandSourceStack> session = enderframe.getChild("session");
        assertHasChild(session, "load");
        assertHasChild(session, "clear");
        assertHasChild(session, "clear_all");
        assertHasChild(session, "list");
        assertHasChild(session, "info");
    }

    private void assertHasChild(CommandNode<CommandSourceStack> parent, String childName) {
        assertNotNull(parent.getChild(childName), "ノード '" + parent.getName() + "' に子ノード '" + childName + "' が存在する必要があります");
    }

    @Test
    @DisplayName("ServerHologramManager の状態管理とライフサイクル破棄")
    void testServerHologramManagerLifecycle() {
        ResourceLocation dim = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        ServerHologramManager.HologramEntry entry = new ServerHologramManager.HologramEntry(
                "test_holo",
                "session_holo_1",
                dim,
                10.5, 65.0, -20.5,
                2.5f, 1.8f,
                45.0f,
                true,
                true,
                32.0,
                "mymod:dashboard",
                640, 480
        );

        CompoundTag tag = entry.toPacketTag();
        assertEquals("test_holo", tag.getString("HoloId"));
        assertEquals(10.5, tag.getDouble("X"));
        assertEquals(65.0, tag.getDouble("Y"));
        assertEquals(-20.5, tag.getDouble("Z"));
        assertEquals(2.5f, tag.getFloat("WidthBlocks"));
        assertEquals(1.8f, tag.getFloat("HeightBlocks"));
        assertEquals(45.0f, tag.getFloat("Yaw"));
        assertTrue(tag.getBoolean("Billboard"));
        assertTrue(tag.getBoolean("Interactive"));
        assertEquals("mymod:dashboard", tag.getString("ResourceLocation"));
        assertEquals(640, tag.getInt("UiWidth"));
        assertEquals(480, tag.getInt("UiHeight"));

        // 手動追加＆クリアテスト
        assertNull(ServerHologramManager.get("test_holo"));
        // clearAll で状態がクリアされること
        ServerHologramManager.clearAll();
        assertTrue(ServerHologramManager.getAll().isEmpty());
        assertTrue(EnderFrameServerState.isEmpty());
    }

    @Test
    @DisplayName("ScreenBlockEntity の UiId 保存と復元")
    void testScreenBlockEntityNbtPersistence() throws Exception {
        ScreenBlockEntity entity = allocate();

        assertNull(entity.getUiId());

        ResourceLocation uiId = ResourceLocation.fromNamespaceAndPath("testmod", "screen_ui");
        entity.setUiId(uiId);
        assertEquals(uiId, entity.getUiId());

        CompoundTag tag = new CompoundTag();
        entity.saveAdditional(tag, null);
        assertEquals(uiId.toString(), tag.getString("UiId"));

        ScreenBlockEntity restored = allocate();
        restored.loadAdditional(tag, null);
        assertEquals(uiId, restored.getUiId());
    }

    /** コンストラクタを通さずに作る（Level なしで NBT だけ試す）。フィールド初期化子は走らないので向きと位置を補う。 */
    private static ScreenBlockEntity allocate() throws Exception {
        ScreenBlockEntity entity = (ScreenBlockEntity) UNSAFE.allocateInstance(ScreenBlockEntity.class);
        Field facing = EnderScreenBlockEntity.class.getDeclaredField("facing");
        facing.setAccessible(true);
        facing.set(entity, Direction.NORTH);
        Field pos = BlockEntity.class.getDeclaredField("worldPosition");
        pos.setAccessible(true);
        pos.set(entity, BlockPos.ZERO);
        return entity;
    }
}
