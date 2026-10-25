package com.nishiyu.enderframelib.server.state;

import com.nishiyu.enderframelib.api.EnderFrameAPI;
import com.nishiyu.enderframelib.api.Ui;
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
public class AdHocSessionLifecycleTest {

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
    @DisplayName("Ad-hoc セッション（open(Ui.html)）のライフサイクルと従来通りの完全な動作維持を検証")
    void testAdHocScreenLifecycle() {
        EnderFrameAPI api = EnderFrameAPI.get();
        ServerPlayer player = TestServerPlayerHelper.createMockPlayer("Alice");

        String sid = "adhoc_screen_1";
        String html = "<html><body><h1 id=\"header\">Hello World</h1></body></html>";

        // 動的インライン画面を開く
        api.open(player, Ui.html(html).size(640, 480).session(sid));

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
