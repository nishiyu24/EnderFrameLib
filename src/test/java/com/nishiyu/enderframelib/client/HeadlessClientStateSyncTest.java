package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.client.template.ClientTemplateManager;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.network.OpenUiS2CPacket;
import com.nishiyu.enderframelib.network.StateDeltaS2CPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * フェーズ H2: クライアント側 Headless State 同期テスト。
 * OpenUiS2CPacket による DOM 展開＋初期値投入と、StateDeltaS2CPacket による差分高速バインディングを検証。
 */
public class HeadlessClientStateSyncTest {

    private static final String TEMPLATE_ID = "mymod:crusher";
    private static final String INSTANCE_ID = "block:100,64,200";

    @BeforeEach
    void setUp() {
        ClientTemplateManager.clearCache();
        ClientWebManager.clearSession(INSTANCE_ID);

        // テスト用機械 UI テンプレートを登録
        String html = """
                <html>
                <head>
                    <style>
                        .running { color: green; }
                        .idle { color: gray; }
                    </style>
                </head>
                <body>
                    <div id="machine">
                        <span id="machine-status" data-bind="status">{{status}}</span>
                        <div id="progress-val">{{progress}}</div>
                        <div id="energy-val">Energy: {{energy}}</div>
                        <div id="conditional" data-if="running">Machine is Active</div>
                    </div>
                </body>
                </html>
                """;
        ClientTemplateManager.registerTemplate(TEMPLATE_ID, html);
    }

    @AfterEach
    void tearDown() {
        ClientWebManager.clearSession(INSTANCE_ID);
        ClientTemplateManager.clearCache();
    }

    @Test
    @DisplayName("OpenUiS2CPacket 受信時にテンプレートから DOM が展開され初期 State がバインドされること")
    void testOpenUiInitialStateBinding() {
        CompoundTag initialState = new CompoundTag();
        initialState.putString("status", "Running");
        initialState.putDouble("progress", 0.35);
        initialState.putInt("energy", 48000);
        initialState.putBoolean("running", true);

        // パケット受信ハンドラを実行
        EnderFrameClientInit.handleOpenUiPacket(TEMPLATE_ID, INSTANCE_ID, initialState);

        SessionContext session = ClientWebManager.getSession(INSTANCE_ID);
        assertNotNull(session, "セッションが作成されていること");
        assertTrue(session.isHeadlessState(), "HeadlessState フラグが true であること");
        assertFalse(session.isServerManaged(), "Headless State では DOM はクライアントローカルであること");

        HtmlNode root = session.rootNode();
        assertNotNull(root, "DOM ツリーが展開されていること");

        // 初期 State のバインディングを検証
        HtmlNode statusNode = root.getElementById("machine-status");
        assertNotNull(statusNode);
        assertEquals("Running", statusNode.getTextContent());

        HtmlNode progressNode = root.getElementById("progress-val");
        assertNotNull(progressNode);
        assertEquals("0.35", progressNode.getTextContent());

        HtmlNode energyNode = root.getElementById("energy-val");
        assertNotNull(energyNode);
        assertEquals("Energy: 48000", energyNode.getTextContent());

        HtmlNode conditional = root.getElementById("conditional");
        assertNotNull(conditional);
        assertFalse(conditional.attrs.containsKey("hidden"), "running=true のため hidden 属性が付かないこと");
    }

    @Test
    @DisplayName("StateDeltaS2CPacket 受信時に変更キーのみが DOM に差分バインドされること")
    void testStateDeltaUpdateBinding() {
        // 1. 初回オープン
        CompoundTag initialState = new CompoundTag();
        initialState.putString("status", "Idle");
        initialState.putDouble("progress", 0.0);
        initialState.putInt("energy", 50000);
        initialState.putBoolean("running", false);
        EnderFrameClientInit.handleOpenUiPacket(TEMPLATE_ID, INSTANCE_ID, initialState);

        SessionContext session = ClientWebManager.getSession(INSTANCE_ID);
        assertNotNull(session);
        HtmlNode progressNode = session.rootNode().getElementById("progress-val");
        assertEquals("0.0", progressNode.getTextContent());

        // 2. 稼働中の差分更新（progress と energy のみが変更）
        CompoundTag delta = new CompoundTag();
        delta.putDouble("progress", 0.65);
        delta.putInt("energy", 45000);
        delta.putString("status", "Working");
        delta.putBoolean("running", true);

        EnderFrameClientInit.handleStateDeltaPacket(INSTANCE_ID, delta);

        // DOM が更新されていることを検証
        assertEquals("0.65", progressNode.getTextContent());
        assertEquals("Energy: 45000", session.rootNode().getElementById("energy-val").getTextContent());
        assertEquals("Working", session.rootNode().getElementById("machine-status").getTextContent());

        // ReactiveStateStore の値も整合していること
        assertEquals("0.65", session.stateStore().get("progress"));
        assertEquals("45000", session.stateStore().get("energy"));
    }

    @Test
    @DisplayName("StateDeltaS2CPacket でのキー削除（__removed）が反映されること")
    void testStateDeltaKeyRemoval() {
        CompoundTag initialState = new CompoundTag();
        initialState.putString("status", "Active");
        initialState.putString("extraInfo", "Overclocked");
        EnderFrameClientInit.handleOpenUiPacket(TEMPLATE_ID, INSTANCE_ID, initialState);

        SessionContext session = ClientWebManager.getSession(INSTANCE_ID);
        assertEquals("Overclocked", session.stateStore().get("extraInfo"));

        // extraInfo を削除する差分パケット
        CompoundTag delta = new CompoundTag();
        ListTag removed = new ListTag();
        removed.add(StringTag.valueOf("extraInfo"));
        delta.put("__removed", removed);

        EnderFrameClientInit.handleStateDeltaPacket(INSTANCE_ID, delta);

        assertEquals("", session.stateStore().get("extraInfo"), "削除されたキーは空になること");
    }

    /**
     * 差分で文字が長くなった flex アイテムが、max-content の幅のまま 1 行に収まること。
     *
     * 何が壊れていたか: 行の折り返しが語ごとの幅の和で判定していたため、"running" から "two-viewers" に
     * 変わった状態表示が "two-" の後で折り返し、隣の要素がずれていた（runClient + 専用サーバーで確認）。
     */
    @Test
    @DisplayName("差分で長くなった flex アイテムの文字が折り返さないこと")
    void testGrownFlexItemTextStaysOnOneLine() {
        var prevRenderer = UiDocumentCompiler.clientBoxRenderer;
        UiDocumentCompiler.clientBoxRenderer = (rootBox, w, h, kf, ss) -> new DisplayListBuilder(w, h, kf, ss).render(rootBox);
        try {
            String templateId = "mymod:flex_status";
            ClientTemplateManager.registerTemplate(templateId, """
                    <style>.row { display: flex; gap: 12px; align-items: center; }</style>
                    <div class="row"><span>status:</span><span id="status">{{status}}</span><span id="next">RUNNING</span></div>
                    """);
            CompoundTag initialState = new CompoundTag();
            initialState.putString("status", "running");
            EnderFrameClientInit.handleOpenUiPacket(templateId, INSTANCE_ID, initialState);

            CompoundTag delta = new CompoundTag();
            delta.putString("status", "two-viewers");
            EnderFrameClientInit.handleStateDeltaPacket(INSTANCE_ID, delta);

            SessionContext session = ClientWebManager.getSession(INSTANCE_ID);
            DisplayItem status = item(session, "status");
            DisplayItem next = item(session, "next");
            assertEquals(next.height(), status.height(), 0.01, "状態表示が 2 行になった");
            assertEquals(next.y(), status.y(), 0.01, "隣の要素の縦位置がずれた");
        } finally {
            UiDocumentCompiler.clientBoxRenderer = prevRenderer;
        }
    }

    private static DisplayItem item(SessionContext session, String id) {
        return session.elements().stream().filter(i -> id.equals(i.id())).findFirst().orElseThrow();
    }
}
