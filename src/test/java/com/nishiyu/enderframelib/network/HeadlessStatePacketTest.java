package com.nishiyu.enderframelib.network;

import com.nishiyu.enderframelib.network.StateNbtConverter.KeyTable;
import com.nishiyu.enderframelib.network.StateNbtConverter.ReceivedKeyTables;
import com.nishiyu.enderframelib.server.EnderFrameServerState;
import com.nishiyu.enderframelib.server.state.TestServerPlayerHelper;
import com.nishiyu.enderframelib.server.state.UIStateImpl;
import com.nishiyu.enderframelib.server.state.UIStateManager;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * フェーズ H1: Headless Server State 新ネットワークパケットのシリアライズ・デシリアライズおよびハンドリング検証テスト。
 *
 * <p>差分パケットはインスタンスとキーを番号で送るので、往復は「開く → 差分」の順にデコードして
 * 受信側の辞書（{@link ReceivedKeyTables}）で名前に戻ることまでを見る。</p>
 */
public class HeadlessStatePacketTest {

    @BeforeEach
    void setUp() {
        ReceivedKeyTables.clear();
        EnderFrameServerState.clearAll();
    }

    @AfterEach
    void tearDown() {
        OpenUiS2CPacket.clientHandler = null;
        StateDeltaS2CPacket.clientHandler = null;
        StateDeltaS2CPacket.clientCloseHandler = null;
        ReceivedKeyTables.clear();
        EnderFrameServerState.clearAll();
    }

    private static OpenUiS2CPacket roundTrip(OpenUiS2CPacket packet) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            OpenUiS2CPacket.STREAM_CODEC.encode(buf, packet);
            OpenUiS2CPacket decoded = OpenUiS2CPacket.STREAM_CODEC.decode(buf);
            assertEquals(0, buf.readableBytes(), "読み残しが無いこと");
            return decoded;
        } finally {
            buf.release();
        }
    }

    private static StateDeltaS2CPacket roundTrip(StateDeltaS2CPacket packet) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            StateDeltaS2CPacket.STREAM_CODEC.encode(buf, packet);
            StateDeltaS2CPacket decoded = StateDeltaS2CPacket.STREAM_CODEC.decode(buf);
            assertEquals(0, buf.readableBytes(), "読み残しが無いこと");
            return decoded;
        } finally {
            buf.release();
        }
    }

    private static CustomPacketPayload roundTrip(CustomPacketPayload packet) {
        if (packet instanceof OpenUiS2CPacket open) return roundTrip(open);
        if (packet instanceof StateDeltaS2CPacket delta) return roundTrip(delta);
        throw new AssertionError("想定外のパケット: " + packet);
    }

    private static KeyTable table(String... names) {
        return KeyTable.of(List.of(names));
    }

    @Test
    @DisplayName("OpenUiS2CPacket のシリアライズとデシリアライズの完全性検証")
    void testOpenUiS2CPacketSerialization() {
        CompoundTag initial = new CompoundTag();
        initial.putDouble("progress", 0.75);
        initial.putInt("energy", 50000);
        initial.putString("status", "running");

        OpenUiS2CPacket decoded = roundTrip(new OpenUiS2CPacket("mymod:crusher", "block:100,64,200", 7,
                table("progress", "energy", "status"), initial));

        assertEquals("mymod:crusher", decoded.templateId());
        assertEquals("block:100,64,200", decoded.instanceId());
        assertEquals(7, decoded.wireId());
        assertEquals(0.75, decoded.initialState().getDouble("progress"), 0.0001);
        assertEquals(50000, decoded.initialState().getInt("energy"));
        assertEquals("running", decoded.initialState().getString("status"));
        assertEquals(ResourceLocation.fromNamespaceAndPath("enderframelib", "open_ui"), decoded.type().id());
        assertEquals(1, ReceivedKeyTables.size(), "デコードで受信側の辞書に登録されること");
    }

    @Test
    @DisplayName("OpenUiS2CPacket の null ペイロード安全性")
    void testOpenUiS2CPacketNullSafety() {
        OpenUiS2CPacket decoded = roundTrip(new OpenUiS2CPacket(null, null, 0, null, null));

        assertEquals("", decoded.templateId());
        assertEquals("", decoded.instanceId());
        assertNotNull(decoded.initialState());
        assertTrue(decoded.initialState().isEmpty());
    }

    @Test
    @DisplayName("StateDeltaS2CPacket は開いたときの辞書で番号から名前に戻ること")
    void testStateDeltaS2CPacketSerialization() {
        KeyTable keys = table("progress", "status");
        roundTrip(new OpenUiS2CPacket("mymod:crusher", "block:100,64,200", 3, keys, new CompoundTag()));

        CompoundTag deltas = new CompoundTag();
        deltas.putDouble("progress", 0.42);
        StateDeltaS2CPacket decoded = roundTrip(new StateDeltaS2CPacket(3, "block:100,64,200", keys, keys.size(), deltas, false));

        assertEquals("block:100,64,200", decoded.instanceId());
        assertEquals(0.42, decoded.deltas().getDouble("progress"), 0.0001);
        assertFalse(decoded.closed());
        assertEquals(ResourceLocation.fromNamespaceAndPath("enderframelib", "state_delta"), decoded.type().id());
    }

    @Test
    @DisplayName("差分で増えたキーの定義と削除（__removed）が往復すること")
    void testStateDeltaNewKeysAndRemoval() {
        KeyTable opened = table("progress");
        roundTrip(new OpenUiS2CPacket("mymod:crusher", "block:1,2,3", 4, opened, new CompoundTag()));

        KeyTable grown = opened.with("status").with("energy");
        CompoundTag deltas = new CompoundTag();
        deltas.putString("status", "running");
        ListTag removed = new ListTag();
        removed.add(StringTag.valueOf("energy"));
        removed.add(StringTag.valueOf("progress"));
        deltas.put("__removed", removed);

        StateDeltaS2CPacket decoded = roundTrip(new StateDeltaS2CPacket(4, "block:1,2,3", grown, opened.size(), deltas, false));

        assertEquals("running", decoded.deltas().getString("status"));
        ListTag decodedRemoved = decoded.deltas().getList("__removed", Tag.TAG_STRING);
        assertEquals(List.of("energy", "progress"), List.of(decodedRemoved.getString(0), decodedRemoved.getString(1)));
    }

    @Test
    @DisplayName("開いていない番号の差分は読み捨て、インスタンス ID が空になること")
    void testStateDeltaForUnknownInstanceIsDropped() {
        KeyTable keys = table("progress", "label");
        CompoundTag deltas = new CompoundTag();
        deltas.putDouble("progress", 0.5);
        deltas.putString("label", "x");

        StateDeltaS2CPacket decoded = roundTrip(new StateDeltaS2CPacket(99, "block:0,0,0", keys, keys.size(), deltas, false));

        assertEquals("", decoded.instanceId());
        assertTrue(decoded.deltas().isEmpty());
    }

    @Test
    @DisplayName("状態の破棄通知が往復し、受信側の辞書から外れること")
    void testClosedPacket() {
        roundTrip(new OpenUiS2CPacket("mymod:crusher", "block:5,5,5", 11, table("a"), new CompoundTag()));
        assertEquals(1, ReceivedKeyTables.size());

        StateDeltaS2CPacket decoded = roundTrip(StateDeltaS2CPacket.closed(11, "block:5,5,5"));

        assertTrue(decoded.closed());
        assertEquals("block:5,5,5", decoded.instanceId());
        assertEquals(0, ReceivedKeyTables.size());
    }

    @Test
    @DisplayName("StateDeltaS2CPacket の null ペイロード安全性")
    void testStateDeltaS2CPacketNullSafety() {
        StateDeltaS2CPacket decoded = roundTrip(new StateDeltaS2CPacket(0, null, null, 0, null, false));

        assertEquals("", decoded.instanceId());
        assertNotNull(decoded.deltas());
        assertTrue(decoded.deltas().isEmpty());
    }

    /**
     * 1 人目が開いた後に 2 人目が開くと、2 人目の開くパケットでだけ辞書に入ったキーが生まれる。
     * そのキーの名前が次の差分で 1 人目にも届くこと（届かないと 1 人目はその値を捨ててしまう）。
     */
    @Test
    @DisplayName("2 人目の閲覧者が開いた時に増えたキーも、1 人目の受信側で名前に戻ること")
    void testKeysAddedByLaterViewerReachEarlierViewer() {
        Map<ServerPlayer, List<CustomPacketPayload>> sent = new HashMap<>();
        UIStateImpl state = (UIStateImpl) UIStateManager.createState("mymod:crusher", "block:9,9,9");
        state.setPacketSender((player, packet) -> sent.computeIfAbsent(player, k -> new ArrayList<>()).add(packet));

        ServerPlayer alice = TestServerPlayerHelper.createMockPlayer("Alice");
        ServerPlayer bob = TestServerPlayerHelper.createMockPlayer("Bob");

        state.set("progress", 0.1);
        state.addViewer(alice);
        state.flush();
        state.set("status", "running");   // まだ flush していない新しいキー
        state.addViewer(bob);             // Bob の開くパケットで status に番号が振られる
        state.set("status", "done");
        state.set("energy", 5);
        state.flush();

        // Alice の受信側: 送信順にデコードする
        StateDeltaS2CPacket aliceLast = null;
        for (CustomPacketPayload packet : sent.get(alice)) {
            if (roundTrip(packet) instanceof StateDeltaS2CPacket delta) aliceLast = delta;
        }
        assertNotNull(aliceLast);
        assertEquals("block:9,9,9", aliceLast.instanceId());
        assertEquals("done", aliceLast.deltas().getString("status"));
        assertEquals(5, aliceLast.deltas().getInt("energy"));

        // Bob の受信側（別のクライアント）
        ReceivedKeyTables.clear();
        List<CustomPacketPayload> bobPackets = sent.get(bob);
        OpenUiS2CPacket bobOpen = (OpenUiS2CPacket) roundTrip(bobPackets.get(0));
        assertEquals("running", bobOpen.initialState().getString("status"));
        StateDeltaS2CPacket bobDelta = (StateDeltaS2CPacket) roundTrip(bobPackets.get(1));
        assertEquals("done", bobDelta.deltas().getString("status"));
        assertEquals(5, bobDelta.deltas().getInt("energy"));
    }

    @Test
    @DisplayName("状態を閉じると閲覧者に破棄通知が届くこと")
    void testCloseNotifiesViewers() {
        List<CustomPacketPayload> sent = new ArrayList<>();
        UIStateImpl state = (UIStateImpl) UIStateManager.createState("mymod:crusher", "block:7,7,7");
        state.setPacketSender((player, packet) -> sent.add(packet));
        state.addViewer(TestServerPlayerHelper.createMockPlayer("Alice"));
        sent.clear();

        state.close();

        assertEquals(1, sent.size());
        StateDeltaS2CPacket closed = assertInstanceOf(StateDeltaS2CPacket.class, sent.get(0));
        assertTrue(closed.closed());
        assertEquals("block:7,7,7", closed.instanceId());
        assertEquals(state.wireId(), closed.wireId());
    }

    @Test
    @DisplayName("UiActionC2SPacket のシリアライズとデシリアライズの完全性検証")
    void testUiActionC2SPacketSerialization() {
        CompoundTag payload = new CompoundTag();
        payload.putString("recipe", "iron_dust");
        payload.putBoolean("boost", true);

        UiActionC2SPacket original = new UiActionC2SPacket("block:100,64,200", "select_recipe", payload);

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            UiActionC2SPacket.STREAM_CODEC.encode(buf, original);
            UiActionC2SPacket decoded = UiActionC2SPacket.STREAM_CODEC.decode(buf);

            assertEquals("block:100,64,200", decoded.instanceId());
            assertEquals("select_recipe", decoded.actionName());
            assertNotNull(decoded.payload());
            assertEquals("iron_dust", decoded.payload().getString("recipe"));
            assertTrue(decoded.payload().getBoolean("boost"));
            assertEquals(ResourceLocation.fromNamespaceAndPath("enderframelib", "ui_action"), decoded.type().id());
        } finally {
            buf.release();
        }
    }

    @Test
    @DisplayName("UiActionC2SPacket の null ペイロード安全性")
    void testUiActionC2SPacketNullSafety() {
        UiActionC2SPacket packet = new UiActionC2SPacket(null, null, null);

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            UiActionC2SPacket.STREAM_CODEC.encode(buf, packet);
            UiActionC2SPacket decoded = UiActionC2SPacket.STREAM_CODEC.decode(buf);

            assertEquals("", decoded.instanceId());
            assertEquals("", decoded.actionName());
            assertNotNull(decoded.payload());
            assertTrue(decoded.payload().isEmpty());
        } finally {
            buf.release();
        }
    }
}
