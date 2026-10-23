package com.nishiyu.enderframelib.server.state;

import com.nishiyu.enderframelib.api.state.UIState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * フェーズ H1: UIState のコア機能単体テスト。
 */
public class UIStateTest {

    @BeforeEach
    void setUp() {
        UIStateManager.clearAll();
    }

    @AfterEach
    void tearDown() {
        UIStateManager.clearAll();
    }

    @Test
    @DisplayName("UIState の生成、プロパティ、基本的な set/get の検証")
    void testBasicSetAndGet() {
        UIState state = UIStateManager.createState("mymod:crusher", "block:100,64,200");
        assertEquals("mymod:crusher", state.templateId());
        assertEquals("block:100,64,200", state.instanceId());

        assertNull(state.get("progress"));
        state.set("progress", 0.5);
        assertEquals(0.5, state.get("progress"));
        assertEquals(0.5, state.getDouble("progress", 0.0));
        assertTrue(state.hasChanges());
    }

    @Test
    @DisplayName("各種プリミティブ型ゲッターとフォールバックの検証")
    void testTypedGettersAndFallbacks() {
        UIState state = UIStateManager.createState("test:template", "test:inst1");

        state.set("intKey", 42);
        state.set("longKey", 10000000000L);
        state.set("doubleKey", 3.14159);
        state.set("boolKey", true);
        state.set("stringKey", "hello");
        state.set("stringNum", "123");

        assertEquals(42, state.getInt("intKey", 0));
        assertEquals(10000000000L, state.getLong("longKey", 0L));
        assertEquals(3.14159, state.getDouble("doubleKey", 0.0), 0.0001);
        assertTrue(state.getBoolean("boolKey", false));
        assertEquals("hello", state.getString("stringKey"));

        // 文字列から数値への安全パース
        assertEquals(123, state.getInt("stringNum", 0));

        // 存在しないキーのフォールバック
        assertEquals(999, state.getInt("missing", 999));
        assertEquals(999L, state.getLong("missing", 999L));
        assertEquals(99.9, state.getDouble("missing", 99.9), 0.01);
        assertFalse(state.getBoolean("missing", false));
        assertTrue(state.getBoolean("missing", true));
        assertNull(state.getString("missing"));
    }

    @Test
    @DisplayName("値が同一の場合は dirty（変更フラグ）が立たないことの検証（不要なパケット配信防止）")
    void testNoDirtyWhenValueUnchanged() {
        UIState state = UIStateManager.createState("test:template", "test:inst2");

        state.set("energy", 1000);
        assertTrue(state.hasChanges());

        // flush で dirty フラグをリセット
        state.flush();
        assertFalse(state.hasChanges(), "flush 後は hasChanges が false であること");

        // 全く同一の値を再度 set
        state.set("energy", 1000);
        assertFalse(state.hasChanges(), "同一の値を再設定した場合は dirty にならないこと");

        // 異なる値を set すると dirty になる
        state.set("energy", 1001);
        assertTrue(state.hasChanges(), "異なる値を設定した場合は dirty になること");
    }

    @Test
    @DisplayName("null を set した場合のキー削除と削除追跡の検証")
    void testNullRemovesKey() {
        UIStateImpl state = (UIStateImpl) UIStateManager.createState("test:template", "test:inst3");

        state.set("temp", "val");
        state.flush();
        assertFalse(state.hasChanges());

        // null を設定 -> キー削除
        state.set("temp", null);
        assertNull(state.get("temp"));
        assertTrue(state.hasChanges());

        // toCompoundTag にも残っていないこと
        CompoundTag tag = state.toCompoundTag();
        assertFalse(tag.contains("temp"));
    }

    @Test
    @DisplayName("snapshot と toCompoundTag の不変性と整合性")
    void testSnapshotAndToCompoundTag() {
        UIState state = UIStateManager.createState("test:template", "test:inst4");

        state.set("name", "Crusher");
        state.set("tier", 2);

        Map<String, Object> snap = state.snapshot();
        assertEquals("Crusher", snap.get("name"));
        assertEquals(2, snap.get("tier"));

        // snapshot の変更が内部に影響しない（unmodifiable）
        assertThrows(UnsupportedOperationException.class, () -> snap.put("name", "Hacked"));

        CompoundTag nbt = state.toCompoundTag();
        assertEquals("Crusher", nbt.getString("name"));
        assertEquals(2, nbt.getInt("tier"));
    }

    @Test
    @DisplayName("クライアントアクションの登録とディスパッチ検証")
    void testActionRegistrationAndDispatch() {
        UIState state = UIStateManager.createState("test:template", "test:inst5");

        AtomicBoolean toggleCalled = new AtomicBoolean(false);
        AtomicReference<String> modeValue = new AtomicReference<>();

        state.onAction("toggle_power", (player, payload) -> {
            toggleCalled.set(true);
        });

        state.onAction("set_mode", (player, payload) -> {
            modeValue.set(payload.getString("mode"));
        });

        ServerPlayer dummyPlayer = TestServerPlayerHelper.createMockPlayer("Alice");

        // toggle_power をディスパッチ
        state.dispatchAction("toggle_power", dummyPlayer, new CompoundTag());
        assertTrue(toggleCalled.get(), "toggle_power ハンドラが実行されること");

        // set_mode をディスパッチ
        CompoundTag payload = new CompoundTag();
        payload.putString("mode", "overclock");
        state.dispatchAction("set_mode", dummyPlayer, payload);
        assertEquals("overclock", modeValue.get(), "set_mode ハンドラに payload が伝達されること");
    }

    @Test
    @DisplayName("close による登録解除と状態リセット")
    void testCloseCleansUp() {
        UIState state = UIStateManager.createState("test:template", "test:inst6");
        state.set("val", 123);
        assertEquals(1, UIStateManager.stateCount());

        state.close();
        assertEquals(0, UIStateManager.stateCount());
        assertNull(UIStateManager.findState("test:inst6"));
        assertNull(state.get("val"));
    }
}
