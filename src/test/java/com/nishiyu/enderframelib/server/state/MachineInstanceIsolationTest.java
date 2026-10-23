package com.nishiyu.enderframelib.server.state;

import com.nishiyu.enderframelib.api.state.UIState;
import com.nishiyu.enderframelib.network.StateDeltaS2CPacket;
import com.nishiyu.enderframelib.server.EnderFrameServerState;
import net.minecraft.nbt.CompoundTag;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * フェーズ H1（REFACTORING_PLAN.md の H0〜H4）:
 * 機械インスタンス分離テスト (MachineInstanceIsolationTest)。
 *
 * <p>同一テンプレート（mymod:crusher）を使用する座標 A の機械と座標 B の機械が、
 * 状態値、閲覧者リスト、差分配信、アクションハンドラの全てにおいて
 * 物理的に完全分離され、互いに一切干渉しないことを検証する。</p>
 */
public class MachineInstanceIsolationTest {

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
    @DisplayName("同一テンプレートの複数機械インスタンスが完全に物理分離されていることの検証")
    void testMachineInstanceIsolation() {
        String templateId = "mymod:crusher";
        String posA = "block:100,64,200";
        String posB = "block:105,64,200";

        UIStateImpl machineA = (UIStateImpl) UIStateManager.createState(templateId, posA);
        UIStateImpl machineB = (UIStateImpl) UIStateManager.createState(templateId, posB);

        machineA.setPacketSender((player, packet) -> getPackets(player).add(packet));
        machineB.setPacketSender((player, packet) -> getPackets(player).add(packet));

        ServerPlayer alice = TestServerPlayerHelper.createMockPlayer("Alice");
        ServerPlayer bob = TestServerPlayerHelper.createMockPlayer("Bob");

        // Alice は機械 A、Bob は機械 B を閲覧
        machineA.addViewer(alice);
        machineB.addViewer(bob);

        assertEquals(1, getPackets(alice).size());
        assertEquals(1, getPackets(bob).size());

        // アクションハンドラの設定
        AtomicBoolean machineAActionCalled = new AtomicBoolean(false);
        AtomicBoolean machineBActionCalled = new AtomicBoolean(false);

        machineA.onAction("toggle_power", (p, payload) -> machineAActionCalled.set(true));
        machineB.onAction("toggle_power", (p, payload) -> machineBActionCalled.set(true));

        // 1. 機械 A だけの稼働更新
        machineA.set("progress", 0.7);
        machineA.flush();

        // Alice だけに届き、Bob の受信箱は変化なし
        assertEquals(2, getPackets(alice).size());
        assertEquals(1, getPackets(bob).size(), "機械 A の更新が機械 B の閲覧者 Bob に流れてはならない");

        StateDeltaS2CPacket deltaA = (StateDeltaS2CPacket) getPackets(alice).get(1);
        assertEquals(posA, deltaA.instanceId());
        assertEquals(0.7, deltaA.deltas().getDouble("progress"), 0.0001);

        // 2. 機械 B だけの稼働更新
        machineB.set("energy", 4500);
        machineB.flush();

        // Bob だけに届き、Alice の受信箱は変化なし
        assertEquals(2, getPackets(alice).size(), "機械 B の更新が機械 A の閲覧者 Alice に流れてはならない");
        assertEquals(2, getPackets(bob).size());

        StateDeltaS2CPacket deltaB = (StateDeltaS2CPacket) getPackets(bob).get(1);
        assertEquals(posB, deltaB.instanceId());
        assertEquals(4500, deltaB.deltas().getInt("energy"));

        // 3. アクションの分離検証
        UIStateManager.handleAction(posA, "toggle_power", alice, new CompoundTag());
        assertTrue(machineAActionCalled.get(), "機械 A のアクションハンドラが実行されること");
        assertFalse(machineBActionCalled.get(), "機械 B のアクションハンドラは影響を受けないこと");

        machineAActionCalled.set(false);
        UIStateManager.handleAction(posB, "toggle_power", bob, new CompoundTag());
        assertFalse(machineAActionCalled.get(), "機械 A のアクションハンドラは影響を受けないこと");
        assertTrue(machineBActionCalled.get(), "機械 B のアクションハンドラが実行されること");
    }
}
