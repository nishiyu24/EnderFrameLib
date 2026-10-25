package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.template.ClientTemplateManager;
import com.nishiyu.enderframelib.network.OpenUiS2CPacket;
import com.nishiyu.enderframelib.network.StateDeltaS2CPacket;
import com.nishiyu.enderframelib.network.StateNbtConverter;
import com.nishiyu.enderframelib.server.EnderFrameServerState;
import com.nishiyu.enderframelib.server.state.TestServerPlayerHelper;
import com.nishiyu.enderframelib.server.state.UIStateImpl;
import com.nishiyu.enderframelib.server.state.UIStateManager;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * テンプレートで動く世界内の面: サーバーは閲覧者を「面の閲覧者」として足し、クライアントは画面を開かずに
 * 指定の解像度でセッションだけを作る。解除すると、そのプレイヤーのセッションが捨てられる。
 */
public class SurfaceStateTest {

    private static final String TEMPLATE_ID = "test:surface_panel";
    private static final String INSTANCE_ID = "block:1,2,3";

    private final List<CustomPacketPayload> sent = new ArrayList<>();

    @BeforeEach
    void setUp() {
        EnderFrameServerState.clearAll();
        ClientTemplateManager.registerTemplate(TEMPLATE_ID,
                "<html><body><span id=\"v\">{{value}}</span></body></html>");
    }

    @AfterEach
    void tearDown() {
        ClientWebManager.clearSession(INSTANCE_ID);
        ClientTemplateManager.clearCache();
        EnderFrameServerState.clearAll();
        sent.clear();
    }

    private UIStateImpl newState() {
        UIStateImpl state = (UIStateImpl) UIStateManager.createState(TEMPLATE_ID, INSTANCE_ID);
        state.setPacketSender((player, packet) -> sent.add(packet));
        return state;
    }

    @Test
    @DisplayName("面の閲覧者には、大きさ付きの OpenUi が届き、画面用のものは大きさ 0 のままであること")
    void surfaceViewerGetsSizedOpenPacket() {
        UIStateImpl state = newState();
        state.set("value", "hello");
        ServerPlayer alice = TestServerPlayerHelper.createMockPlayer("Alice");
        ServerPlayer bob = TestServerPlayerHelper.createMockPlayer("Bob");

        state.addSurfaceViewer(alice, 256, 128);
        state.addViewer(bob);

        OpenUiS2CPacket forSurface = (OpenUiS2CPacket) sent.get(0);
        OpenUiS2CPacket forScreen = (OpenUiS2CPacket) sent.get(1);
        assertTrue(forSurface.isSurface());
        assertEquals(256, forSurface.surfaceWidth());
        assertEquals(128, forSurface.surfaceHeight());
        assertFalse(forScreen.isSurface());
        assertTrue(state.isViewer(alice), "面の閲覧者もアクションを送れる閲覧者であること");
    }

    @Test
    @DisplayName("OpenUi の大きさは往復しても保たれ、画面用は 1 バイトしか増えないこと")
    void surfaceSizeRoundTrips() {
        OpenUiS2CPacket surface = new OpenUiS2CPacket(TEMPLATE_ID, INSTANCE_ID, 1,
                StateNbtConverter.KeyTable.EMPTY, new CompoundTag(), 320, 240);
        OpenUiS2CPacket screen = new OpenUiS2CPacket(TEMPLATE_ID, INSTANCE_ID, 1,
                StateNbtConverter.KeyTable.EMPTY, new CompoundTag());

        OpenUiS2CPacket decoded = roundTrip(surface);
        assertEquals(320, decoded.surfaceWidth());
        assertEquals(240, decoded.surfaceHeight());
        OpenUiS2CPacket decodedScreen = roundTrip(screen);
        assertFalse(decodedScreen.isSurface());
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

    @Test
    @DisplayName("面の閲覧者を外すと closed が届き、画面用の閲覧者を外しても何も送られないこと")
    void removingSurfaceViewerClosesItsSession() {
        UIStateImpl state = newState();
        ServerPlayer alice = TestServerPlayerHelper.createMockPlayer("Alice");
        state.addSurfaceViewer(alice, 128, 128);
        sent.clear();

        state.removeSurfaceViewer(alice);

        assertEquals(1, sent.size());
        assertInstanceOf(StateDeltaS2CPacket.class, sent.get(0));
        assertTrue(((StateDeltaS2CPacket) sent.get(0)).closed());
        assertFalse(state.isViewer(alice));

        sent.clear();
        state.removeSurfaceViewer(alice);
        assertTrue(sent.isEmpty(), "閲覧者でなければ何も送らないこと");
    }

    @Test
    @DisplayName("クライアントは面の OpenUi で画面を開かず、指定の解像度でテンプレートを展開すること")
    void clientBuildsSurfaceSessionWithoutOpeningScreen() {
        CompoundTag initial = new CompoundTag();
        initial.putString("value", "shown");

        EnderFrameClientInit.handleOpenUiPacket(TEMPLATE_ID, INSTANCE_ID, initial, 640, 360);

        SessionContext session = ClientWebManager.getSession(INSTANCE_ID);
        assertNotNull(session);
        assertEquals(640, session.rootW());
        assertEquals(360, session.rootH());
        assertTrue(session.isHeadlessState());
        assertEquals("shown", session.rootNode().getElementById("v").getTextContent());
    }

    @Test
    @DisplayName("同じ状態のまま面を開き直しても（大きさだけ変わっても）、新しい文書に値が当たること")
    void reopeningWithSameStateRebindsTheNewDocument() {
        CompoundTag initial = new CompoundTag();
        initial.putString("value", "shown");
        EnderFrameClientInit.handleOpenUiPacket(TEMPLATE_ID, INSTANCE_ID, initial, 640, 360);
        EnderFrameClientInit.handleOpenUiPacket(TEMPLATE_ID, INSTANCE_ID, initial, 320, 180);

        SessionContext session = ClientWebManager.getSession(INSTANCE_ID);
        assertEquals(320, session.rootW());
        assertEquals("shown", session.rootNode().getElementById("v").getTextContent());
    }
}
