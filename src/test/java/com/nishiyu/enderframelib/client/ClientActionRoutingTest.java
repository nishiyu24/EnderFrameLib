package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.gui.AbstractEnderScreen;
import com.nishiyu.enderframelib.client.gui.EnderScreenDelegate;
import com.nishiyu.enderframelib.client.template.ClientTemplateManager;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.event.CoreEvent;
import com.nishiyu.enderframelib.network.OpenUiS2CPacket;
import com.nishiyu.enderframelib.network.UiActionC2SPacket;
import com.nishiyu.enderframelib.network.WebActionC2SPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * フェーズ H2: クライアント側アクションルーティングテスト。
 * Headless State セッションでの `server:action` や `data-forward` が UiActionC2SPacket に正しくルーティングされ、
 * かつ従来の Dual DOM セッションでは WebActionC2SPacket が維持される後方互換性を検証。
 */
public class ClientActionRoutingTest {

    private static final String TEMPLATE_ID = "mymod:crusher_ctrl";
    private static final String INSTANCE_ID = "block:10,20,30";

    private final AtomicReference<UiActionC2SPacket> lastUiAction = new AtomicReference<>();
    private final AtomicReference<WebActionC2SPacket> lastWebAction = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        lastUiAction.set(null);
        lastWebAction.set(null);

        ClientScreenInteractionManager.setUiActionSender(lastUiAction::set);
        ClientScreenInteractionManager.setServerActionSender(lastWebAction::set);

        ClientTemplateManager.clearCache();
        ClientWebManager.clearSession(INSTANCE_ID);

        String html = """
                <html>
                <body>
                    <div id="controls">
                        <button id="power-btn" onclick="server:toggle_power">Power</button>
                        <button id="forward-btn" data-forward="click" data-forward-action="switch_mode">Mode</button>
                        <input id="name-input" type="text" oninput="server:rename">
                    </div>
                </body>
                </html>
                """;
        ClientTemplateManager.registerTemplate(TEMPLATE_ID, html);
    }

    @AfterEach
    void tearDown() {
        ClientScreenInteractionManager.setUiActionSender(null);
        ClientScreenInteractionManager.setServerActionSender(null);
        ClientWebManager.clearSession(INSTANCE_ID);
        ClientTemplateManager.clearCache();
    }

    @Test
    @DisplayName("Headless State 上で server:action が UiActionC2SPacket にルーティングされること")
    void testHeadlessStateButtonActionRouting() {
        // 画面をオープン
        EnderFrameClientInit.handleOpenUiPacket(TEMPLATE_ID, INSTANCE_ID, new CompoundTag());

        SessionContext session = ClientWebManager.getSession(INSTANCE_ID);
        assertNotNull(session);
        assertTrue(session.isHeadlessState());

        HtmlNode powerBtn = session.rootNode().getElementById("power-btn");
        assertNotNull(powerBtn);

        // クリックイベントを発火
        powerBtn.dispatchEvent(CoreEvent.click(0, 0, 0));

        UiActionC2SPacket packet = lastUiAction.get();
        assertNotNull(packet, "UiActionC2SPacket が送出されていること");
        assertEquals(INSTANCE_ID, packet.instanceId());
        assertEquals("toggle_power", packet.actionName(), "server: 接頭辞が除去されて cleanAction になっていること");
        assertNotNull(packet.payload());
        assertEquals("power-btn", packet.payload().getString("ElementId"));

        // 従来の WebActionC2SPacket は送出されていないこと
        assertNull(lastWebAction.get());
    }

    /**
     * 画面を開いているときは画面のルータが {@code server:} アクションを受け取る。そこでイベントの文脈を捨てて
     * {@code rpc} と空の値で送っていたため、実機（runClient + 専用サーバー）でサーバーが入力値を受け取れなかった。
     */
    @Test
    @DisplayName("画面を開いた状態でも server:action に要素 ID・イベント名・入力値が付くこと")
    void testScreenRouterKeepsEventContext() {
        EnderFrameClientInit.handleOpenUiPacket(TEMPLATE_ID, INSTANCE_ID, new CompoundTag());
        SessionContext session = ClientWebManager.getSession(INSTANCE_ID);
        assertNotNull(session);
        AbstractEnderScreen screen = new AbstractEnderScreen(Component.literal("t"), INSTANCE_ID, 800, 600) {
            @Override
            protected String buildInitialHtml() {
                return null;
            }
        };
        try {
            HtmlNode input = session.rootNode().getElementById("name-input");
            input.attrs.put("value", "Crusher #2");
            input.dispatchEvent(CoreEvent.input("Crusher #2"));

            UiActionC2SPacket typed = lastUiAction.get();
            assertNotNull(typed);
            assertEquals("rename", typed.actionName());
            assertEquals("name-input", typed.payload().getString("ElementId"));
            assertEquals("oninput", typed.payload().getString("EventName"));
            assertEquals("Crusher #2", typed.payload().getString("Value"));

            session.rootNode().getElementById("power-btn").dispatchEvent(CoreEvent.click(0, 0, 0));
            UiActionC2SPacket clicked = lastUiAction.get();
            assertEquals("toggle_power", clicked.actionName());
            assertEquals("power-btn", clicked.payload().getString("ElementId"));
            assertEquals("onclick", clicked.payload().getString("EventName"));
        } finally {
            screen.getUiDelegate().onClose();
        }
    }

    @Test
    @DisplayName("Headless State 上で data-forward が UiActionC2SPacket にルーティングされること")
    void testHeadlessStateDataForwardActionRouting() {
        EnderFrameClientInit.handleOpenUiPacket(TEMPLATE_ID, INSTANCE_ID, new CompoundTag());
        SessionContext session = ClientWebManager.getSession(INSTANCE_ID);
        assertNotNull(session);

        HtmlNode forwardBtn = session.rootNode().getElementById("forward-btn");
        assertNotNull(forwardBtn);

        forwardBtn.dispatchEvent(CoreEvent.click(0, 0, 0));

        UiActionC2SPacket packet = lastUiAction.get();
        assertNotNull(packet, "UiActionC2SPacket が送出されていること");
        assertEquals(INSTANCE_ID, packet.instanceId());
        assertEquals("switch_mode", packet.actionName());
    }

    @Test
    @DisplayName("Headless State 画面が表示から外れるとクローズ通知（CLOSE_ACTION）がサーバーへ送出されること")
    void testHeadlessScreenRemovedSendsCloseAction() {
        EnderFrameClientInit.handleOpenUiPacket(TEMPLATE_ID, INSTANCE_ID, new CompoundTag());
        SessionContext session = ClientWebManager.getSession(INSTANCE_ID);
        assertNotNull(session);

        // ESC 以外（別画面への差し替え等）でも呼ばれる Screen#removed() を経由する
        AbstractEnderScreen screen = new AbstractEnderScreen(Component.literal("t"), INSTANCE_ID, 800, 600) {
            @Override
            protected String buildInitialHtml() {
                return null;
            }
        };
        screen.removed();

        UiActionC2SPacket packet = lastUiAction.get();
        assertNotNull(packet, "クローズ通知が送出されていること");
        assertEquals(INSTANCE_ID, packet.instanceId());
        assertEquals(UiActionC2SPacket.CLOSE_ACTION, packet.actionName());
    }

    @Test
    @DisplayName("非 HeadlessState の通常セッションを閉じてもクローズ通知は送出されないこと")
    void testDualDomScreenCloseSendsNothing() {
        String legacySessionId = "legacy_session_close";
        ClientWebManager.clearSession(legacySessionId);
        SessionContext session = ClientWebManager.getOrCreateSession(legacySessionId);
        session.loadLocalHtml("<div>legacy</div>", 800, 600);

        new EnderScreenDelegate(legacySessionId, session.stateStore()).onScreenRemoved();

        assertNull(lastUiAction.get());
        ClientWebManager.clearSession(legacySessionId);
    }

    @Test
    @DisplayName("非 HeadlessState の通常セッションでは従来の WebActionC2SPacket が送出されること（後方互換性）")
    void testDualDomBackwardCompatibility() {
        String legacySessionId = "legacy_session_1";
        ClientWebManager.clearSession(legacySessionId);

        SessionContext session = ClientWebManager.getOrCreateSession(legacySessionId);
        assertFalse(session.isHeadlessState(), "通常のセッションは headlessState=false");

        String legacyHtml = "<div><button id='legacy-btn' onclick='server:do_something'>Legacy</button></div>";
        session.loadLocalHtml(legacyHtml, 800, 600);

        HtmlNode btn = session.rootNode().getElementById("legacy-btn");
        assertNotNull(btn);

        btn.dispatchEvent(CoreEvent.click(0, 0, 0));

        WebActionC2SPacket packet = lastWebAction.get();
        assertNotNull(packet, "従来の WebActionC2SPacket が送出されていること");
        assertEquals(legacySessionId, packet.sessionId());
        assertEquals("ui_event", packet.action());
        assertEquals("server:do_something", packet.payload().getString("ActionName"));

        // 新しい UiActionC2SPacket は送出されていないこと
        assertNull(lastUiAction.get());

        ClientWebManager.clearSession(legacySessionId);
    }

    /**
     * HUD 用と GUI 画面用の {@link EnderScreenDelegate} が同時に存在するとき、コールバックが static の 1 本なので
     * 後から作った方が先の方を上書きし、先の画面のアクションが届かなくなる（監査 2026-10-25）。
     * Phase 1 / T8 でコールバックをセッション単位にするまで無効にしておく。直したら {@code @Disabled} を外す。
     */
    @Test
    @org.junit.jupiter.api.Disabled("Phase 1 / T8: イベントコールバックのセッション単位化まで失敗する（監査で確認済みの不具合）")
    @DisplayName("複数画面の同時稼働: 後から開いた画面が先の画面のイベント配送を上書きしないこと")
    void testConcurrentDelegatesKeepOwnCallbacks() {
        String hudId = "concurrent_hud";
        String guiId = "concurrent_gui";
        String html = "<div><button id='go' onclick='do_it'>Go</button></div>";
        ClientWebManager.clearSession(hudId);
        ClientWebManager.clearSession(guiId);
        SessionContext hud = ClientWebManager.getOrCreateSession(hudId);
        SessionContext gui = ClientWebManager.getOrCreateSession(guiId);
        hud.loadLocalHtml(html, 800, 600);
        gui.loadLocalHtml(html, 800, 600);

        java.util.concurrent.atomic.AtomicInteger hudHits = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger guiHits = new java.util.concurrent.atomic.AtomicInteger();
        EnderScreenDelegate hudDelegate = new EnderScreenDelegate(hudId, hud.stateStore());
        hudDelegate.onAction("do_it", hudHits::incrementAndGet);
        EnderScreenDelegate guiDelegate = new EnderScreenDelegate(guiId, gui.stateStore());
        guiDelegate.onAction("do_it", guiHits::incrementAndGet);
        try {
            hud.rootNode().getElementById("go").dispatchEvent(CoreEvent.click(0, 0, 0));
            assertEquals(1, hudHits.get(), "後から GUI 画面を作っても HUD のアクションが届くこと");
            assertEquals(0, guiHits.get());

            gui.rootNode().getElementById("go").dispatchEvent(CoreEvent.click(0, 0, 0));
            assertEquals(1, guiHits.get());

            guiDelegate.onClose();
            hud.rootNode().getElementById("go").dispatchEvent(CoreEvent.click(0, 0, 0));
            assertEquals(2, hudHits.get(), "GUI 画面を閉じても HUD のイベント配送が壊れないこと");
        } finally {
            hudDelegate.onClose();
            guiDelegate.onClose();
            ClientWebManager.clearSession(hudId);
            ClientWebManager.clearSession(guiId);
        }
    }
}
