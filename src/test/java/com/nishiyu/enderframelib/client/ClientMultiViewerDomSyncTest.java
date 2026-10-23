package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.template.ClientTemplateManager;
import com.nishiyu.enderframelib.client.template.ParsedTemplate;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * フェーズ H2: クライアント視点でのマルチビューワー同時閲覧テスト。
 *
 * <p>Headless State の複数閲覧者（ARCHITECTURE.md §2）：
 * プレイヤー Alice が開いている機械にプレイヤー Bob が後から同時に合流しても、
 * Alice の DOM インスタンスおよびローカル状態が一切白紙化・リセットされず維持され、
 * その後の状態更新（StateDelta）が両プレイヤーの画面に即時・同時に同期されることを検証。</p>
 */
public class ClientMultiViewerDomSyncTest {

    private static final String TEMPLATE_ID = "mymod:crusher";
    private static final String INSTANCE_ID = "block:50,64,-120";

    @BeforeEach
    void setUp() {
        ClientTemplateManager.clearCache();
        UIStateManager.removeState(INSTANCE_ID);

        String html = """
                <html>
                <body>
                    <div id="machine">
                        <span id="progress-val">{{progress}}</span>
                        <span id="energy-val">{{energy}}</span>
                        <input id="note-input" value="custom-note" />
                    </div>
                </body>
                </html>
                """;
        ClientTemplateManager.registerTemplate(TEMPLATE_ID, html);
    }

    @AfterEach
    void tearDown() {
        ClientWebManager.clearSession(INSTANCE_ID + "_alice");
        ClientWebManager.clearSession(INSTANCE_ID + "_bob");
        UIStateManager.removeState(INSTANCE_ID);
        ClientTemplateManager.clearCache();
    }

    @Test
    @DisplayName("2人目のプレイヤー合流時に1人目のDOMツリーとローカル状態が維持され、双方に差分同期されること")
    void testMultiViewerSimultaneousViewingWithoutReset() {
        // --- 1. サーバー側 State の初期化 ---
        UIStateImpl serverState = (UIStateImpl) UIStateManager.createState(TEMPLATE_ID, INSTANCE_ID);
        serverState.set("progress", 0.3);
        serverState.set("energy", 10000);

        // クライアント側のパケット受信キューをシミュレート
        List<CustomPacketPayload> alicePackets = new ArrayList<>();
        List<CustomPacketPayload> bobPackets = new ArrayList<>();

        ServerPlayer alice = TestServerPlayerHelper.createMockPlayer("Alice", UUID.randomUUID());
        ServerPlayer bob = TestServerPlayerHelper.createMockPlayer("Bob", UUID.randomUUID());

        serverState.setPacketSender((player, packet) -> {
            if (player == alice) alicePackets.add(packet);
            if (player == bob) bobPackets.add(packet);
        });

        // --- 2. Alice が機械を開く ---
        serverState.addViewer(alice);
        assertEquals(1, alicePackets.size());
        assertTrue(alicePackets.get(0) instanceof OpenUiS2CPacket);

        // Alice のクライアントコンテキストに画面を展開
        String aliceSessionId = INSTANCE_ID + "_alice";
        SessionContext aliceSession = ClientWebManager.getOrCreateSession(aliceSessionId);
        OpenUiS2CPacket aliceOpenPkt = (OpenUiS2CPacket) alicePackets.get(0);

        ParsedTemplate template = ClientTemplateManager.getTemplate(aliceOpenPkt.templateId());
        assertNotNull(template);
        aliceSession.loadTemplateDocument(template.instantiate(854, 480));
        EnderFrameClientInit.applyCompoundTagToStore(aliceSession.stateStore(), aliceOpenPkt.initialState());
        aliceSession.stateStore().syncToDom();

        HtmlNode aliceRoot = aliceSession.rootNode();
        assertNotNull(aliceRoot);
        assertEquals("0.3", aliceRoot.getElementById("progress-val").getTextContent());

        // Alice がローカルで編集した状態（ノートの変更）をシミュレート
        HtmlNode aliceInput = aliceRoot.getElementById("note-input");
        assertNotNull(aliceInput);
        aliceInput.setAttribute("value", "Alice-Private-Note");
        assertEquals("Alice-Private-Note", aliceInput.getAttribute("value"));

        // Alice の DOM インスタンスの参照を保持
        HtmlDocument aliceOriginalDoc = (HtmlDocument) aliceRoot;

        // --- 3. Bob が同時に同じ機械を開く ---
        serverState.addViewer(bob);
        assertEquals(1, bobPackets.size());
        assertTrue(bobPackets.get(0) instanceof OpenUiS2CPacket);

        // ★ 検証: Alice 宛てには新しいパケット（dom_sync 等）は一切飛んでいない！
        assertEquals(1, alicePackets.size(), "Alice の画面には合流時の再送パケットが飛ばないこと");

        // Bob のクライアントコンテキストに画面を展開
        String bobSessionId = INSTANCE_ID + "_bob";
        SessionContext bobSession = ClientWebManager.getOrCreateSession(bobSessionId);
        OpenUiS2CPacket bobOpenPkt = (OpenUiS2CPacket) bobPackets.get(0);
        bobSession.loadTemplateDocument(template.instantiate(854, 480));
        EnderFrameClientInit.applyCompoundTagToStore(bobSession.stateStore(), bobOpenPkt.initialState());
        bobSession.stateStore().syncToDom();

        HtmlNode bobRoot = bobSession.rootNode();
        assertNotNull(bobRoot);
        assertEquals("0.3", bobRoot.getElementById("progress-val").getTextContent());

        // ★ 検証: Alice の DOM インスタンスとローカル入力状態が完全に維持されていること！
        assertSame(aliceOriginalDoc, aliceSession.rootNode(), "Alice の DOM 木は再生成されず同一オブジェクトであること");
        assertEquals("Alice-Private-Note", aliceInput.getAttribute("value"), "Alice の入力値が白紙化されていないこと");

        // --- 4. 機械が tick 稼働（進捗 0.3 -> 0.7） ---
        serverState.set("progress", 0.7);
        serverState.set("energy", 9500);
        serverState.flush();

        // Alice と Bob の双方に StateDeltaS2CPacket が1通ずつ届く
        assertEquals(2, alicePackets.size());
        assertEquals(2, bobPackets.size());

        StateDeltaS2CPacket aliceDelta = (StateDeltaS2CPacket) alicePackets.get(1);
        StateDeltaS2CPacket bobDelta = (StateDeltaS2CPacket) bobPackets.get(1);

        // 双方のクライアントで差分を適用
        EnderFrameClientInit.applyCompoundTagToStore(aliceSession.stateStore(), aliceDelta.deltas());
        aliceSession.stateStore().syncToDom();

        EnderFrameClientInit.applyCompoundTagToStore(bobSession.stateStore(), bobDelta.deltas());
        bobSession.stateStore().syncToDom();

        // ★ 検証: 両方のプレイヤーの DOM 上で進捗が 0.7 に同時に更新されていること
        assertEquals("0.7", aliceRoot.getElementById("progress-val").getTextContent());
        assertEquals("0.7", bobRoot.getElementById("progress-val").getTextContent());

        // Alice のローカル入力も破壊されていないこと
        assertEquals("Alice-Private-Note", aliceInput.getAttribute("value"));

        aliceSession.close();
        bobSession.close();
        serverState.close();
    }
}
