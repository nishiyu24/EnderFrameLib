package com.nishiyu.enderframelib.server.state;

import com.nishiyu.enderframelib.api.state.UIState;
import com.nishiyu.enderframelib.network.OpenUiS2CPacket;
import com.nishiyu.enderframelib.network.StateDeltaS2CPacket;
import com.nishiyu.enderframelib.server.EnderFrameServerState;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * フェーズ H1（REFACTORING_PLAN.md の H0〜H4）:
 * マルチビューワー同時閲覧テスト (MultiViewerStateSyncTest)。
 *
 * <p>プレイヤー 1 (Alice) が機械 UI を開いている最中に、プレイヤー 2 (Bob) が同じ機械を開いて合流しても、
 * Alice への画面破壊（全木初期化パケット dom_sync の再送）が一切起きず、
 * 稼働中の差分（StateDeltaS2CPacket）だけが両名に正確に配信されることを検証する。</p>
 */
public class MultiViewerStateSyncTest {

    private final Map<ServerPlayer, List<CustomPacketPayload>> sentPackets = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        EnderFrameServerState.clearAll();
        sentPackets.clear();
    }

    @AfterEach
    void tearDown() {
        EnderFrameServerState.clearAll();
        sentPackets.clear();
    }

    private List<CustomPacketPayload> getPackets(ServerPlayer player) {
        return sentPackets.computeIfAbsent(player, k -> Collections.synchronizedList(new ArrayList<>()));
    }

    @Test
    @DisplayName("2人目のプレイヤー合流時に1人目の画面破壊（初期化パケット再送）が起きず、両名に差分が届くことの検証")
    void testMultiViewerSimultaneousSync() {
        String templateId = "mymod:crusher";
        String instanceId = "block:100,64,200";

        UIStateImpl state = (UIStateImpl) UIStateManager.createState(templateId, instanceId);
        state.setPacketSender((player, packet) -> {
            getPackets(player).add(packet);
        });

        ServerPlayer alice = TestServerPlayerHelper.createMockPlayer("Alice");
        ServerPlayer bob = TestServerPlayerHelper.createMockPlayer("Bob");

        // 1. 初期状態設定
        state.set("progress", 0.0);
        state.set("energy", 10000);
        state.set("status", "idle");

        // 2. Alice が機械を開く
        state.addViewer(alice);

        // Alice に OpenUiS2CPacket が 1 件届いていること
        List<CustomPacketPayload> alicePackets = getPackets(alice);
        assertEquals(1, alicePackets.size());
        assertInstanceOf(OpenUiS2CPacket.class, alicePackets.get(0));
        OpenUiS2CPacket aliceOpen = (OpenUiS2CPacket) alicePackets.get(0);
        assertEquals(templateId, aliceOpen.templateId());
        assertEquals(instanceId, aliceOpen.instanceId());
        assertEquals(0.0, aliceOpen.initialState().getDouble("progress"));

        // 3. 機械の稼働: progress が 0.3 に進む -> flush
        state.set("progress", 0.3);
        state.flush();

        // Alice に StateDeltaS2CPacket が届く
        assertEquals(2, alicePackets.size());
        assertInstanceOf(StateDeltaS2CPacket.class, alicePackets.get(1));
        StateDeltaS2CPacket delta1 = (StateDeltaS2CPacket) alicePackets.get(1);
        assertEquals(0.3, delta1.deltas().getDouble("progress"), 0.0001);

        // 4. Bob が後から同じ機械を開いて合流
        state.addViewer(bob);

        // Bob に最新状態 (progress: 0.3) の OpenUiS2CPacket が届く
        List<CustomPacketPayload> bobPackets = getPackets(bob);
        assertEquals(1, bobPackets.size());
        assertInstanceOf(OpenUiS2CPacket.class, bobPackets.get(0));
        OpenUiS2CPacket bobOpen = (OpenUiS2CPacket) bobPackets.get(0);
        assertEquals(0.3, bobOpen.initialState().getDouble("progress"), 0.0001);
        assertEquals(10000, bobOpen.initialState().getInt("energy"));

        // ★重要★: Alice には Bob の合流によって何もパケットが送られていないこと！（Alice のパケット数は 2 のまま）
        assertEquals(2, alicePackets.size(), "Bob が合流しても Alice の画面破壊・リセットパケットは一切送られないこと");

        // 5. 機械がさらに稼働: progress が 0.4 に進み、energy が 9800 に減少 -> flush
        state.set("progress", 0.4);
        state.set("energy", 9800);
        state.flush();

        // Alice と Bob の両方に全く同一の差分パケットが届く
        assertEquals(3, alicePackets.size());
        assertEquals(2, bobPackets.size());

        StateDeltaS2CPacket aliceDelta2 = (StateDeltaS2CPacket) alicePackets.get(2);
        StateDeltaS2CPacket bobDelta2 = (StateDeltaS2CPacket) bobPackets.get(1);

        assertEquals(0.4, aliceDelta2.deltas().getDouble("progress"), 0.0001);
        assertEquals(9800, aliceDelta2.deltas().getInt("energy"));
        assertEquals(0.4, bobDelta2.deltas().getDouble("progress"), 0.0001);
        assertEquals(9800, bobDelta2.deltas().getInt("energy"));

        // 6. Alice が機械を閉じる（離脱）
        state.removeViewer(alice);
        assertEquals(1, state.viewers().size());
        assertTrue(state.viewers().contains(bob));

        // 7. さらに稼働: progress が 0.5 に進む -> flush
        state.set("progress", 0.5);
        state.flush();

        // Bob だけに届き、離脱した Alice には届かない
        assertEquals(3, alicePackets.size(), "離脱した Alice には新しい差分パケットが届かないこと");
        assertEquals(3, bobPackets.size());
        StateDeltaS2CPacket bobDelta3 = (StateDeltaS2CPacket) bobPackets.get(2);
        assertEquals(0.5, bobDelta3.deltas().getDouble("progress"), 0.0001);
    }
}
