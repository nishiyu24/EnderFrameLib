package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.template.ClientTemplateManager;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.event.CoreEvent;
import com.nishiyu.enderframelib.network.OpenUiS2CPacket;
import com.nishiyu.enderframelib.network.StateDeltaS2CPacket;
import com.nishiyu.enderframelib.server.state.TestServerPlayerHelper;
import com.nishiyu.enderframelib.server.state.UIStateImpl;
import com.nishiyu.enderframelib.server.state.UIStateManager;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * フェーズ H2: クライアント視点での機械インスタンス分離テスト。
 *
 * <p>同一テンプレート（例: mymod:generator）を使用する異なるブロック座標の機械 A と機械 B が、
 * 状態更新、DOM 表示、およびクライアントアクションにおいて互いに完全に物理分離され、
 * 一切の干渉やクロスリークが起きないことを検証。</p>
 */
public class ClientMachineIsolationTest {

    private static final String TEMPLATE_ID = "mymod:generator";
    private static final String INSTANCE_A = "block:10,64,20";
    private static final String INSTANCE_B = "block:50,64,80";

    @BeforeEach
    void setUp() {
        ClientTemplateManager.clearCache();
        ClientWebManager.clearSession(INSTANCE_A);
        ClientWebManager.clearSession(INSTANCE_B);
        UIStateManager.removeState(INSTANCE_A);
        UIStateManager.removeState(INSTANCE_B);

        String html = """
                <html>
                <body>
                    <div id="gen">
                        <span id="fuel-level">{{fuel}}</span>
                        <button id="burn-btn" onclick="server:burn">Burn</button>
                    </div>
                </body>
                </html>
                """;
        ClientTemplateManager.registerTemplate(TEMPLATE_ID, html);
    }

    @AfterEach
    void tearDown() {
        ClientWebManager.clearSession(INSTANCE_A);
        ClientWebManager.clearSession(INSTANCE_B);
        UIStateManager.removeState(INSTANCE_A);
        UIStateManager.removeState(INSTANCE_B);
        ClientTemplateManager.clearCache();
    }

    @Test
    @DisplayName("同一テンプレートの機械 A と機械 B が互いに完全に干渉せず独立して同期されること")
    void testMachineInstancesDoNotInterfere() {
        // --- 1. サーバー側 State の作成 ---
        UIStateImpl stateA = (UIStateImpl) UIStateManager.createState(TEMPLATE_ID, INSTANCE_A);
        UIStateImpl stateB = (UIStateImpl) UIStateManager.createState(TEMPLATE_ID, INSTANCE_B);

        stateA.set("fuel", 100);
        stateB.set("fuel", 500);
        stateA.flush(); // 初期化時の dirty フラグをリセット
        stateB.flush();

        List<CustomPacketPayload> packetsA = new ArrayList<>();
        List<CustomPacketPayload> packetsB = new ArrayList<>();

        ServerPlayer playerA = TestServerPlayerHelper.createMockPlayer("PlayerA", UUID.randomUUID());
        ServerPlayer playerB = TestServerPlayerHelper.createMockPlayer("PlayerB", UUID.randomUUID());

        stateA.setPacketSender((p, pkt) -> packetsA.add(pkt));
        stateB.setPacketSender((p, pkt) -> packetsB.add(pkt));

        // --- 2. プレイヤー A が機械 A を、プレイヤー B が機械 B を開く ---
        stateA.addViewer(playerA);
        stateB.addViewer(playerB);

        OpenUiS2CPacket openA = (OpenUiS2CPacket) packetsA.get(0);
        OpenUiS2CPacket openB = (OpenUiS2CPacket) packetsB.get(0);

        EnderFrameClientInit.handleOpenUiPacket(openA.templateId(), openA.instanceId(), openA.initialState());
        EnderFrameClientInit.handleOpenUiPacket(openB.templateId(), openB.instanceId(), openB.initialState());

        SessionContext sessionA = ClientWebManager.getSession(INSTANCE_A);
        SessionContext sessionB = ClientWebManager.getSession(INSTANCE_B);

        assertNotNull(sessionA);
        assertNotNull(sessionB);
        assertNotSame(sessionA, sessionB);

        assertEquals("100", sessionA.rootNode().getElementById("fuel-level").getTextContent());
        assertEquals("500", sessionB.rootNode().getElementById("fuel-level").getTextContent());

        // --- 3. 機械 A の燃料のみが消費される (100 -> 80) ---
        stateA.set("fuel", 80);
        stateA.flush();
        stateB.flush(); // B は変更なしなのでパケットは飛ばない

        assertEquals(2, packetsA.size());
        assertEquals(1, packetsB.size(), "機械 B 側にはパケットが送出されないこと");

        StateDeltaS2CPacket deltaA = (StateDeltaS2CPacket) packetsA.get(1);
        EnderFrameClientInit.handleStateDeltaPacket(deltaA.instanceId(), deltaA.deltas());

        // ★ 検証: 機械 A のみ燃料が 80 になり、機械 B は 500 のまま維持されていること
        assertEquals("80", sessionA.rootNode().getElementById("fuel-level").getTextContent());
        assertEquals("500", sessionB.rootNode().getElementById("fuel-level").getTextContent());

        // --- 4. クライアント側アクションの分離検証 ---
        AtomicInteger actionsReceivedA = new AtomicInteger(0);
        AtomicInteger actionsReceivedB = new AtomicInteger(0);

        stateA.onAction("burn", (p, payload) -> actionsReceivedA.incrementAndGet());
        stateB.onAction("burn", (p, payload) -> actionsReceivedB.incrementAndGet());

        ClientScreenInteractionManager.setUiActionSender(pkt -> {
            UIStateManager.handleAction(pkt.instanceId(), pkt.actionName(), playerA, pkt.payload());
        });

        // 機械 A のボタンをクリック
        HtmlNode burnBtnA = sessionA.rootNode().getElementById("burn-btn");
        burnBtnA.dispatchEvent(CoreEvent.click(0, 0, 0));

        // ★ 検証: 機械 A のハンドラのみが実行され、機械 B には届いていないこと
        assertEquals(1, actionsReceivedA.get(), "機械 A のアクションハンドラが実行されたこと");
        assertEquals(0, actionsReceivedB.get(), "機械 B のアクションハンドラは実行されていないこと");

        ClientScreenInteractionManager.setUiActionSender(null);
        stateA.close();
        stateB.close();
    }
}
