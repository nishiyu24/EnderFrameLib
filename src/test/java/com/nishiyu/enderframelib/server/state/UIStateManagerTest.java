package com.nishiyu.enderframelib.server.state;

import com.nishiyu.enderframelib.api.state.UIState;
import com.nishiyu.enderframelib.network.StateDeltaS2CPacket;
import com.nishiyu.enderframelib.network.UiActionC2SPacket;
import com.nishiyu.enderframelib.server.EnderFrameServerState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * フェーズ H1: UIStateManager のライフサイクル管理および EnderFrameServerState 連携テスト。
 */
public class UIStateManagerTest {

    @BeforeEach
    void setUp() {
        EnderFrameServerState.clearAll();
    }

    @AfterEach
    void tearDown() {
        EnderFrameServerState.clearAll();
    }

    @Test
    @DisplayName("UIState の作成、検索、取得、削除の基本サイクル")
    void testCreateFindRemove() {
        assertEquals(0, UIStateManager.stateCount());

        UIState s1 = UIStateManager.createState("mymod:crusher", "block:1,2,3");
        assertNotNull(s1);
        assertEquals(1, UIStateManager.stateCount());
        assertSame(s1, UIStateManager.findState("block:1,2,3"));

        // getOrCreateState は既存の同一 instanceId を返す
        UIState s1Again = UIStateManager.getOrCreateState("mymod:crusher", "block:1,2,3");
        assertSame(s1, s1Again);

        // removeState
        UIState removed = UIStateManager.removeState("block:1,2,3");
        assertSame(s1, removed);
        assertEquals(0, UIStateManager.stateCount());
        assertNull(UIStateManager.findState("block:1,2,3"));
    }

    @Test
    @DisplayName("同一 instanceId での createState は旧インスタンスを close して置換する")
    void testRecreateReplacesAndClosesOld() {
        UIState oldState = UIStateManager.createState("mymod:crusher", "block:1,2,3");
        oldState.set("energy", 100);

        UIState newState = UIStateManager.createState("mymod:crusher", "block:1,2,3");
        assertNotSame(oldState, newState);
        assertEquals(1, UIStateManager.stateCount());
        assertSame(newState, UIStateManager.findState("block:1,2,3"));

        // 旧インスタンスはクローズされている
        assertNull(oldState.get("energy"));
    }

    @Test
    @DisplayName("tickAll により全アクティブ状態が一括で flush されること")
    void testTickAllFlushesAllStates() {
        UIState s1 = UIStateManager.createState("mod:t1", "inst:1");
        UIState s2 = UIStateManager.createState("mod:t2", "inst:2");

        s1.set("val", 1);
        s2.set("val", 2);

        assertTrue(s1.hasChanges());
        assertTrue(s2.hasChanges());

        UIStateManager.tickAll();

        assertFalse(s1.hasChanges(), "tickAll 後は s1 の変更が flush されていること");
        assertFalse(s2.hasChanges(), "tickAll 後は s2 の変更が flush されていること");
    }

    @Test
    @DisplayName("removePlayerAll により全アクティブ状態から対象プレイヤーが除外されること")
    void testRemovePlayerAllRemovesViewerFromAllStates() {
        UIState s1 = UIStateManager.createState("mod:t1", "inst:1");
        UIState s2 = UIStateManager.createState("mod:t2", "inst:2");

        ServerPlayer p1 = TestServerPlayerHelper.createMockPlayer("Player1");
        ServerPlayer p2 = TestServerPlayerHelper.createMockPlayer("Player2");

        s1.addViewer(p1);
        s1.addViewer(p2);
        s2.addViewer(p1);

        assertEquals(2, s1.viewers().size());
        assertEquals(1, s2.viewers().size());
        assertEquals(3, UIStateManager.viewerCount());

        // Player1 がログアウトまたはディメンション移動
        UIStateManager.removePlayerAll(p1);

        assertEquals(1, s1.viewers().size());
        assertFalse(s1.viewers().contains(p1));
        assertTrue(s1.viewers().contains(p2));

        assertEquals(0, s2.viewers().size());
        assertEquals(1, UIStateManager.viewerCount());
    }

    @Test
    @DisplayName("handleAction による該当 UIState へのアクションルーティング")
    void testHandleActionRouting() {
        UIState s1 = UIStateManager.createState("mod:t1", "inst:target");
        AtomicBoolean actionInvoked = new AtomicBoolean(false);

        s1.onAction("press_button", (player, payload) -> {
            actionInvoked.set(true);
        });

        ServerPlayer player = TestServerPlayerHelper.createMockPlayer("Alice");
        s1.addViewer(player);

        // 存在しない instanceId へのアクションは安全に無視
        assertDoesNotThrow(() -> UIStateManager.handleAction("inst:unknown", "press_button", player, new CompoundTag()));
        assertFalse(actionInvoked.get());

        // 正しい instanceId へのアクション実行
        assertTrue(UIStateManager.handleAction("inst:target", "press_button", player, new CompoundTag()));
        assertTrue(actionInvoked.get());
    }

    @Test
    @DisplayName("閲覧者でないプレイヤーからのアクションは拒否されること（instanceId 推測による遠隔操作の防止）")
    void testHandleActionRejectsNonViewer() {
        UIStateImpl state = (UIStateImpl) UIStateManager.createState("mymod:crusher", "block:1,2,3");
        state.setPacketSender((p, pkt) -> {});
        AtomicInteger invoked = new AtomicInteger();
        state.onAction("toggle_power", (p, payload) -> invoked.incrementAndGet());

        ServerPlayer owner = TestServerPlayerHelper.createMockPlayer("Owner");
        ServerPlayer intruder = TestServerPlayerHelper.createMockPlayer("Intruder");
        state.addViewer(owner);

        assertFalse(UIStateManager.handleAction("block:1,2,3", "toggle_power", intruder, new CompoundTag()),
                "画面を開いていないプレイヤーの instanceId 指定アクションは拒否されること");
        assertEquals(0, invoked.get());

        // 予約アクション（close）も非閲覧者からは何も起こさない
        assertFalse(UIStateManager.handleAction("block:1,2,3", UiActionC2SPacket.CLOSE_ACTION, intruder, new CompoundTag()));
        assertTrue(state.isViewer(owner), "他人のクローズ通知で正規の閲覧者が外されないこと");

        assertTrue(UIStateManager.handleAction("block:1,2,3", "toggle_power", owner, new CompoundTag()));
        assertEquals(1, invoked.get());
    }

    @Test
    @DisplayName("クローズ通知で閲覧者から外れ、以降の差分配信とアクション受付が止まること")
    void testCloseActionRemovesViewer() {
        UIStateImpl state = (UIStateImpl) UIStateManager.createState("mymod:crusher", "block:4,5,6");
        Map<ServerPlayer, List<CustomPacketPayload>> sent = new ConcurrentHashMap<>();
        state.setPacketSender((p, pkt) -> sent.computeIfAbsent(p, k -> new ArrayList<>()).add(pkt));
        AtomicInteger invoked = new AtomicInteger();
        state.onAction("toggle_power", (p, payload) -> invoked.incrementAndGet());

        ServerPlayer alice = TestServerPlayerHelper.createMockPlayer("Alice");
        ServerPlayer bob = TestServerPlayerHelper.createMockPlayer("Bob");
        state.addViewer(alice);
        state.addViewer(bob);

        // Alice が画面を閉じる（クライアントから CLOSE_ACTION が届く）
        assertTrue(UIStateManager.handleAction("block:4,5,6", UiActionC2SPacket.CLOSE_ACTION, alice, new CompoundTag()));
        assertFalse(state.isViewer(alice));
        assertTrue(state.isViewer(bob));
        assertEquals(0, invoked.get(), "クローズ通知はユーザー定義ハンドラへディスパッチされないこと");

        // 以降の差分は Bob にだけ届く
        state.set("progress", 0.7);
        state.flush();
        assertEquals(1, sent.get(alice).size(), "閉じた Alice には OpenUi 以降のパケットが届かないこと");
        assertEquals(2, sent.get(bob).size());
        assertInstanceOf(StateDeltaS2CPacket.class, sent.get(bob).get(1));

        // 閉じた後のアクションは拒否される
        assertFalse(UIStateManager.handleAction("block:4,5,6", "toggle_power", alice, new CompoundTag()));
        assertEquals(0, invoked.get());
    }

    @Test
    @DisplayName("閲覧者判定は UUID で行い、ServerPlayer インスタンスが差し替わっても同一プレイヤーとして扱うこと")
    void testViewerMatchedByUuid() {
        UIStateImpl state = (UIStateImpl) UIStateManager.createState("mymod:crusher", "block:7,8,9");
        state.setPacketSender((p, pkt) -> {});
        UUID uuid = UUID.randomUUID();
        ServerPlayer before = TestServerPlayerHelper.createMockPlayer("Alice", uuid);
        ServerPlayer afterRespawn = TestServerPlayerHelper.createMockPlayer("Alice", uuid);
        state.addViewer(before);

        assertTrue(state.isViewer(afterRespawn));
        assertTrue(UIStateManager.handleAction("block:7,8,9", UiActionC2SPacket.CLOSE_ACTION, afterRespawn, new CompoundTag()));
        assertTrue(state.viewers().isEmpty(), "差し替え後のインスタンスからのクローズでも閲覧者から外れること");
    }

    @Test
    @DisplayName("EnderFrameServerState 連携: clearAll で破棄され isEmpty と describeState が同期すること")
    void testEnderFrameServerStateIntegration() {
        assertTrue(EnderFrameServerState.isEmpty());

        UIState s = UIStateManager.createState("mod:t1", "inst:1");
        ServerPlayer p = TestServerPlayerHelper.createMockPlayer("Alice");
        s.addViewer(p);

        assertFalse(EnderFrameServerState.isEmpty());
        String desc = EnderFrameServerState.describeState();
        assertTrue(desc.contains("uiStates=1"));
        assertTrue(desc.contains("uiViewers=1"));

        // サーバー停止時の一括破棄
        EnderFrameServerState.clearAll();

        assertTrue(EnderFrameServerState.isEmpty(), "clearAll 後は isEmpty が true に戻ること");
        assertEquals(0, UIStateManager.stateCount());
        assertEquals(0, UIStateManager.viewerCount());
    }
}
