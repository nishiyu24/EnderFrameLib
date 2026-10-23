package com.nishiyu.enderframelib.server.state;

import com.nishiyu.enderframelib.api.EnderFrameAPI;
import com.nishiyu.enderframelib.api.WebSessionHandle;
import com.nishiyu.enderframelib.api.state.UIState;
import com.nishiyu.enderframelib.network.StateDeltaS2CPacket;
import com.nishiyu.enderframelib.server.EnderFrameServerState;
import com.nishiyu.enderframelib.server.WebSession;
import com.nishiyu.enderframelib.server.WebSessionManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * フェーズ H4: 後方互換性アダプタ（UIStateSessionAdapter / WebSessionUIStateAdapter）と
 * レガシー API 非推奨化、Ad-hoc 互換セッションの検証テスト。
 */
public class BackwardCompatibilityAdapterTest {

    @BeforeEach
    void setUp() {
        EnderFrameServerState.init();
        EnderFrameServerState.clearAll();
    }

    @AfterEach
    void tearDown() {
        EnderFrameServerState.clearAll();
    }

    @Test
    @DisplayName("レガシー DOM 変異 API（patch 系メソッド）に @Deprecated が付与されていること")
    void testDeprecatedAnnotationsOnLegacyApis() throws Exception {
        // WebSessionHandle インターフェースの検証
        Class<?> handleClass = WebSessionHandle.class;
        assertTrue(handleClass.getMethod("patchText", String.class, String.class).isAnnotationPresent(Deprecated.class));
        assertTrue(handleClass.getMethod("patchAttribute", String.class, String.class, String.class, boolean.class).isAnnotationPresent(Deprecated.class));
        assertTrue(handleClass.getMethod("patchStyle", String.class, String.class, String.class).isAnnotationPresent(Deprecated.class));
        assertTrue(handleClass.getMethod("patchClass", String.class, String.class, String.class).isAnnotationPresent(Deprecated.class));
        assertTrue(handleClass.getMethod("patchInnerHtml", String.class, String.class).isAnnotationPresent(Deprecated.class));

        // WebSession クラスの検証
        Class<?> sessionClass = WebSession.class;
        assertTrue(sessionClass.getMethod("patchElement", String.class, java.util.function.Consumer.class).isAnnotationPresent(Deprecated.class));
        assertTrue(sessionClass.getMethod("patchText", String.class, String.class).isAnnotationPresent(Deprecated.class));
        assertTrue(sessionClass.getMethod("patchAttribute", String.class, String.class, String.class, boolean.class).isAnnotationPresent(Deprecated.class));
        assertTrue(sessionClass.getMethod("patchStyle", String.class, String.class, String.class).isAnnotationPresent(Deprecated.class));
        assertTrue(sessionClass.getMethod("patchClass", String.class, String.class, String.class).isAnnotationPresent(Deprecated.class));
        assertTrue(sessionClass.getMethod("patchInnerHtml", String.class, String.class).isAnnotationPresent(Deprecated.class));
    }

    @Test
    @DisplayName("UIStateSessionAdapter: UIState を WebSessionHandle として透過的に操作でき、StateDelta へ変換されること")
    void testUIStateSessionAdapterRoutingAndDelta() {
        String templateId = "mymod:crusher";
        String instanceId = "block:10,20,30";
        UIState state = UIStateManager.createState(templateId, instanceId);

        List<CustomPacketPayload> capturedPackets = new ArrayList<>();
        ((UIStateImpl) state).setPacketSender((player, packet) -> capturedPackets.add(packet));

        ServerPlayer player = TestServerPlayerHelper.createMockPlayer("Alice");
        state.addViewer(player);
        capturedPackets.clear(); // 初期オープンパケットをクリア

        // asSessionHandle によるアダプタ取得
        WebSessionHandle handle = state.asSessionHandle();
        assertNotNull(handle, "WebSessionHandle アダプタが取得できること");
        assertSame(state, handle.asUIState(), "asUIState で元の UIState が返ること");
        assertEquals(instanceId, handle.getSessionId(), "sessionId が instanceId と一致すること");
        assertNull(handle.getDocument(), "Headless State ではサーバー上 DOM は null であること");

        // 1. patchText のテスト -> state.set(elementId, text)
        handle.patchText("energyLabel", "15000 RF");
        assertEquals("15000 RF", state.get("energyLabel"));
        assertTrue(state.hasChanges());

        // 2. patchStyle のテスト -> state.set(elementId:prop, val)
        handle.patchStyle("progressBar", "width", "60%");
        assertEquals("60%", state.get("progressBar:width"));

        // 3. patchAttribute のテスト (value 属性) -> state.set(elementId, val)
        handle.patchAttribute("status", "value", "running", false);
        assertEquals("running", state.get("status"));

        // 4. patchAttribute のテスト (一般属性) -> state.set(elementId@attr, val)
        handle.patchAttribute("btn1", "disabled", "true", false);
        assertEquals("true", state.get("btn1@disabled"));

        // 5. patchAttribute の削除 -> null
        handle.patchAttribute("btn1", "disabled", "true", true);
        assertNull(state.get("btn1@disabled"));

        // 6. patchClass のテスト -> state.set(elementId:class:cls, mode)
        handle.patchClass("led", "active", "add");
        assertEquals("add", state.get("led:class:active"));

        // 7. patchInnerHtml のテスト -> state.set(elementId, fragment)
        handle.patchInnerHtml("infoPanel", "<span>OK</span>");
        assertEquals("<span>OK</span>", state.get("infoPanel"));

        // 差分配信 (flush)
        state.flush();
        assertEquals(1, capturedPackets.size(), "StateDeltaS2CPacket が 1 件配信されること");
        assertTrue(capturedPackets.get(0) instanceof StateDeltaS2CPacket);

        StateDeltaS2CPacket deltaPkt = (StateDeltaS2CPacket) capturedPackets.get(0);
        assertEquals(instanceId, deltaPkt.instanceId());
        assertEquals("15000 RF", deltaPkt.deltas().getString("energyLabel"));
        assertEquals("60%", deltaPkt.deltas().getString("progressBar:width"));
        assertEquals("running", deltaPkt.deltas().getString("status"));
        assertEquals("add", deltaPkt.deltas().getString("led:class:active"));
        assertEquals("<span>OK</span>", deltaPkt.deltas().getString("infoPanel"));

        // clear でクローズされること
        handle.clear();
        assertNull(UIStateManager.findState(instanceId), "state.close() により UIStateManager から登録解除されること");
    }

    @Test
    @DisplayName("WebSessionUIStateAdapter: WebSession を UIState として操作でき、DOM 変異へ反映されること")
    void testWebSessionUIStateAdapterRoutingAndDomMutation() {
        String sessionId = "adhoc_compat_test";
        WebSession session = WebSessionManager.getOrCreateStandaloneSession(sessionId, 400, 300);
        session.loadHtml("""
                <html>
                  <body>
                    <span id="title">Old Title</span>
                    <div id="bar" style="width: 20px;"></div>
                    <button id="toggleBtn" class="btn">Click</button>
                    <input id="inputField" value="initVal"/>
                  </body>
                </html>
                """);

        UIState uiState = session.asUIState();
        assertNotNull(uiState, "UIState アダプタが取得できること");
        assertSame(session, uiState.asSessionHandle(), "asSessionHandle で元の WebSession が返ること");
        assertEquals("adhoc:" + sessionId, uiState.templateId());
        assertEquals(sessionId, uiState.instanceId());

        // 1. テキスト設定 (set(key, val)) -> DOM の setTextContent
        uiState.set("title", "New Headless Title");
        assertEquals("New Headless Title", uiState.get("title"));
        assertEquals("New Headless Title", uiState.getString("title"));
        assertEquals("New Headless Title", session.getDocument().root.getElementById("title").getTextContent());

        // 2. スタイル設定 (set(id:prop, val)) -> DOM の setStyleProperty
        uiState.set("bar:width", "85%");
        assertEquals("85%", session.getDocument().root.getElementById("bar").getStyleProperty("width"));

        // 3. クラス設定 (set(id:class:cls, mode)) -> DOM の addClass
        uiState.set("toggleBtn:class:active", "add");
        assertTrue(session.getDocument().root.getElementById("toggleBtn").hasClass("active"));

        // 4. 属性設定 (set(id@attr, val)) -> DOM の updateAttribute
        uiState.set("inputField@placeholder", "Enter text...");
        assertEquals("Enter text...", session.getDocument().root.getElementById("inputField").attrs.get("placeholder"));

        // 5. 型安全ゲッターの検証
        uiState.set("count", 100);
        uiState.set("pi", 3.14159);
        uiState.set("enabled", true);
        assertEquals(100, uiState.getInt("count", 0));
        assertEquals(3.14159, uiState.getDouble("pi", 0.0), 0.0001);
        assertTrue(uiState.getBoolean("enabled", false));

        // 6. snapshot と toCompoundTag の検証
        Map<String, Object> snap = uiState.snapshot();
        assertEquals("New Headless Title", snap.get("title"));
        assertEquals(100, snap.get("count"));

        CompoundTag tag = uiState.toCompoundTag();
        assertEquals("New Headless Title", tag.getString("title"));
        assertEquals(100, tag.getInt("count"));

        // 7. close でセッションがクリアされること
        uiState.close();
        assertNull(session.getDocument(), "セッションのドキュメントが破棄されること");
    }

    @Test
    @DisplayName("Ad-hoc セッション（openScreen）のライフサイクルと従来通りの完全な動作維持を検証")
    void testAdHocScreenLifecycle() {
        EnderFrameAPI api = EnderFrameAPI.get();
        ServerPlayer player = TestServerPlayerHelper.createMockPlayer("Alice");

        String sid = "adhoc_screen_1";
        String html = "<html><body><h1 id=\"header\">Hello World</h1></body></html>";

        // 動的インライン画面を開く
        api.openScreen(player, sid, html, 640, 480);

        WebSession session = WebSessionManager.findStandaloneSession(sid);
        assertNotNull(session, "スタンドアロンセッションが生成されること");
        assertEquals(640, session.getWidth());
        assertEquals(480, session.getHeight());
        assertNotNull(session.getDocument());
        assertEquals("Hello World", session.getDocument().root.getElementById("header").getTextContent());

        // 既存の patchText による更新がそのまま機能すること
        session.patchText("header", "Welcome Alice");
        assertEquals("Welcome Alice", session.getDocument().root.getElementById("header").getTextContent());

        // セッション破棄
        api.closeSession(sid);
        assertNull(WebSessionManager.findStandaloneSession(sid), "セッションが正常に破棄されること");
    }

    @Test
    @DisplayName("WebSession 複数生成時でも static な UiDocumentCompiler が共有されメモリが健全であること")
    void testWebSessionStaticCompilerOptimization() {
        // 50 個の Ad-hoc セッションを生成して破棄
        for (int i = 0; i < 50; i++) {
            WebSession session = WebSessionManager.getOrCreateStandaloneSession("temp_" + i, 100, 100);
            session.loadHtml("<p id=\"p\">test " + i + "</p>");
            assertEquals("test " + i, session.getDocument().root.getElementById("p").getTextContent());
            session.clear();
        }
        assertNotNull(WebSessionManager.findStandaloneSession("temp_0"));
        EnderFrameServerState.clearAll();
        assertNull(WebSessionManager.findStandaloneSession("temp_0"));
    }
}
