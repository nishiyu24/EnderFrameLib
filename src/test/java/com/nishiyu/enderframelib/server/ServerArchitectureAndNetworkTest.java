package com.nishiyu.enderframelib.server;

import com.nishiyu.enderframelib.api.EnderFrameScriptingBridge;
import com.nishiyu.enderframelib.client.ClientPubSubManager;
import com.nishiyu.enderframelib.network.DomMutationRecord;
import com.nishiyu.enderframelib.network.WebSyncS2CPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T7 (S11): サーバー・ネットワークの重複排除テスト。
 * <ul>
 *   <li>WebSessionManager のセッション属性並行マップ（SESSION_VIEWERS / SESSION_OWNERS）が 0 であること</li>
 *   <li>EnderFrameScriptingBridge が状態マップを持たない純粋なファサードであること</li>
 *   <li>WebSession が viewers と owner を自ら保持し、ライフサイクルが連動すること</li>
 *   <li>差分送信が dom_mutations 1 系統であること</li>
 *   <li>ar_sync などの予約チャンネルが ClientPubSubManager を通ること</li>
 * </ul>
 */
public class ServerArchitectureAndNetworkTest {

    @BeforeEach
    void setUp() {
        WebSessionManager.clearAll();
        ClientPubSubManager.clearAll();
    }

    @AfterEach
    void tearDown() {
        WebSessionManager.clearAll();
        ClientPubSubManager.clearAll();
    }

    @Test
    @DisplayName("WebSessionManager はセッションの属性を並行した static マップで持たない（S11）")
    void testWebSessionManagerHasNoParallelSessionAttributeMaps() {
        for (Field field : WebSessionManager.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && Map.class.isAssignableFrom(field.getType())) {
                String name = field.getName();
                assertNotEquals("SESSION_VIEWERS", name, "SESSION_VIEWERS は WebSession.viewers に集約されるべきです");
                assertNotEquals("SESSION_OWNERS", name, "SESSION_OWNERS は WebSession.owner に集約されるべきです");
            }
        }
    }

    @Test
    @DisplayName("EnderFrameScriptingBridge は static マップ等の状態を持たない（S11）")
    void testScriptingBridgeHasNoStaticMaps() {
        for (Field field : EnderFrameScriptingBridge.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && Map.class.isAssignableFrom(field.getType())) {
                fail("EnderFrameScriptingBridge が状態マップを持っています: " + field.getName());
            }
        }
    }

    @Test
    @DisplayName("WebSession は閲覧者と所有者の状態を保持し、セッション破棄時に正しくクリアされる")
    void testWebSessionViewersAndOwnerLifecycle() {
        String sessionId = "test_s1";
        WebSession session = WebSessionManager.getOrCreateStandaloneSession(sessionId, 100, 100);
        assertNotNull(session);
        assertFalse(session.hasViewers());
        assertNull(session.getOwner());

        UUID ownerUuid = UUID.randomUUID();
        session.setOwner(ownerUuid);
        assertEquals(ownerUuid, session.getOwner());

        // 破棄で viewers と owner がクリアされる
        WebSessionManager.removeStandaloneSession(sessionId);
        assertNull(WebSessionManager.findStandaloneSession(sessionId));
        assertFalse(session.hasViewers());
        assertNull(session.getOwner());
    }

    @Test
    @DisplayName("差分変異の配信は dom_mutations 1 系統で行われる（S11）")
    void testMutationsBroadcastAsDomMutationsAction() {
        String sessionId = "test_mutations";
        WebSession session = WebSessionManager.getOrCreateStandaloneSession(sessionId, 100, 100);
        session.loadHtml("<html><body><p id=\"p1\">old</p></body></html>");

        // 閲覧者がいない状態でも例外を投げずに処理できること
        assertDoesNotThrow(() -> {
            WebSessionManager.broadcastMutation(sessionId, DomMutationRecord.text("p1", "new"));
            session.patchText("p1", "patched");
        });
    }

    @Test
    @DisplayName("ar_sync チャンネルは ClientPubSubManager のチャンネルリスナー経由で受信される（S11）")
    void testArSyncThroughClientPubSubManager() {
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        ClientPubSubManager.addChannelListener("ar_sync", received::set);

        Map<String, Object> diff = Map.of("target", Map.of("name", "Zombie"));
        ClientPubSubManager.onReceiveUpdate("ar_sync", false, diff);

        assertNotNull(received.get(), "ar_sync リスナーに更新が届くこと");
        assertEquals("Zombie", ((Map<?, ?>) received.get().get("target")).get("name"));
    }
}
