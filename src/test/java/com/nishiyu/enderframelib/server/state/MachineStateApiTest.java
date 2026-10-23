package com.nishiyu.enderframelib.server.state;

import com.nishiyu.enderframelib.api.EnderFrameAPI;
import com.nishiyu.enderframelib.api.state.UIState;
import com.nishiyu.enderframelib.network.OpenUiS2CPacket;
import com.nishiyu.enderframelib.network.StateDeltaS2CPacket;
import com.nishiyu.enderframelib.server.EnderFrameServerState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
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
 * フェーズ H3: 機械向け高水準 API のテスト。
 * EnderFrameAPI.createBlockEntityState / openUi / openBlockEntityUi の動作を検証。
 */
public class MachineStateApiTest {

    private final Map<ServerPlayer, List<CustomPacketPayload>> sentPackets = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        EnderFrameServerState.init();
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
    @DisplayName("createBlockEntityState: BlockEntity の座標から block:x,y,z 形式の instanceId で UIState が取得・作成されること")
    void testCreateBlockEntityState() {
        EnderFrameAPI api = EnderFrameAPI.get();
        BlockPos posA = new BlockPos(100, 64, 200);
        BlockEntity beA = TestBlockEntityHelper.createMockBlockEntity(posA);

        String templateId = "mymod:crusher";
        UIState stateA = api.createBlockEntityState(beA, templateId);

        assertNotNull(stateA, "UIState が生成されること");
        assertEquals(templateId, stateA.templateId(), "templateId が一致すること");
        assertEquals("block:100,64,200", stateA.instanceId(), "instanceId が block:x,y,z 形式であること");

        // 状態を設定
        stateA.set("energy", 10000);
        assertEquals(10000, stateA.getInt("energy", 0));

        // 同一 BlockEntity で再度取得した場合、既存の UIState インスタンスおよび値が保持されること
        UIState stateA2 = api.createBlockEntityState(beA, templateId);
        assertSame(stateA, stateA2, "同一 BlockEntity の場合は同じ UIState が返ること");
        assertEquals(10000, stateA2.getInt("energy", 0), "設定済みの状態値が維持されていること");

        // 異なる座標の BlockEntity の場合は別インスタンスとなり干渉しないこと
        BlockPos posB = new BlockPos(105, 64, 200);
        BlockEntity beB = TestBlockEntityHelper.createMockBlockEntity(posB);
        UIState stateB = api.createBlockEntityState(beB, templateId);

        assertNotSame(stateA, stateB, "別座標の BlockEntity は別インスタンスとなること");
        assertEquals("block:105,64,200", stateB.instanceId());
        assertNull(stateB.get("energy"), "別インスタンスの状態値は独立していること");
    }

    @Test
    @DisplayName("createBlockEntityState: 引数が null の場合に例外を送出すること")
    void testCreateBlockEntityStateNullChecks() {
        EnderFrameAPI api = EnderFrameAPI.get();
        BlockEntity be = TestBlockEntityHelper.createMockBlockEntity(new BlockPos(0, 0, 0));

        assertThrows(RuntimeException.class, () -> api.createBlockEntityState(null, "mymod:crusher"));
        assertThrows(RuntimeException.class, () -> api.createBlockEntityState(be, null));
    }

    @Test
    @DisplayName("openUi: プレイヤーが viewer に追加され初期状態パケットが送信されること")
    void testOpenUi() {
        EnderFrameAPI api = EnderFrameAPI.get();
        String templateId = "mymod:generator";
        String instanceId = "block:10,20,30";

        UIState state = UIStateManager.createState(templateId, instanceId);
        ((UIStateImpl) state).setPacketSender((player, packet) -> getPackets(player).add(packet));
        state.set("power", 500);

        ServerPlayer player = TestServerPlayerHelper.createMockPlayer("Alice");

        api.openUi(player, templateId, instanceId, state);

        assertTrue(state.viewers().contains(player), "プレイヤーが viewer に追加されていること");

        List<CustomPacketPayload> packets = getPackets(player);
        assertEquals(1, packets.size(), "OpenUiS2CPacket が 1 件送信されていること");
        assertTrue(packets.get(0) instanceof OpenUiS2CPacket);

        OpenUiS2CPacket openPkt = (OpenUiS2CPacket) packets.get(0);
        assertEquals(templateId, openPkt.templateId());
        assertEquals(instanceId, openPkt.instanceId());
        assertEquals(500, openPkt.initialState().getInt("power"));
    }

    @Test
    @DisplayName("openBlockEntityUi: BlockEntity 経由で openUi が実行されパケット配信と差分同期が行われること")
    void testOpenBlockEntityUiAndFlush() {
        EnderFrameAPI api = EnderFrameAPI.get();
        BlockPos pos = new BlockPos(12, 64, 48);
        BlockEntity be = TestBlockEntityHelper.createMockBlockEntity(pos);
        String templateId = "mymod:smelter";

        UIState state = api.createBlockEntityState(be, templateId);
        ((UIStateImpl) state).setPacketSender((player, packet) -> getPackets(player).add(packet));
        state.set("temperature", 200);

        ServerPlayer player = TestServerPlayerHelper.createMockPlayer("Bob");

        // openBlockEntityUi の呼び出し
        api.openBlockEntityUi(player, be, templateId, state);

        assertTrue(state.viewers().contains(player));
        assertEquals(1, getPackets(player).size());

        OpenUiS2CPacket openPkt = (OpenUiS2CPacket) getPackets(player).get(0);
        assertEquals("block:12,64,48", openPkt.instanceId());
        assertEquals(200, openPkt.initialState().getInt("temperature"));

        // 稼働中の状態更新と flush
        state.set("temperature", 350);
        state.set("progress", 0.45);
        state.flush();

        assertEquals(2, getPackets(player).size());
        assertTrue(getPackets(player).get(1) instanceof StateDeltaS2CPacket);

        StateDeltaS2CPacket deltaPkt = (StateDeltaS2CPacket) getPackets(player).get(1);
        assertEquals("block:12,64,48", deltaPkt.instanceId());
        assertEquals(350, deltaPkt.deltas().getInt("temperature"));
        assertEquals(0.45, deltaPkt.deltas().getDouble("progress"), 0.0001);
    }
}
