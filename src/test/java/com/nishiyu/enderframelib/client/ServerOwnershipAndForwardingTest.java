package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.api.dom.Document;
import com.nishiyu.enderframelib.api.event.Event;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.dom.Ownership;
import com.nishiyu.enderframelib.core.event.CoreEvent;
import com.nishiyu.enderframelib.core.event.CoreEvent.DomEvent;
import com.nishiyu.enderframelib.network.DomMutationRecord;
import com.nishiyu.enderframelib.network.DomMutationRecord.HtmlNodeSerializer;
import com.nishiyu.enderframelib.network.EnderFrameNetwork.WebActionC2SPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R3: 所有権（サーバー所有 / クライアント所有）と、イベントのサーバー転送の宣言を、
 * セッション・ネットワークの境界（同期パケット・変異パケット・送出パケット）を通して確かめる。
 */
public class ServerOwnershipAndForwardingTest {

    private static final String SESSION = "ownership-and-forwarding";
    private static final int WIDTH = 400;
    private static final int HEIGHT = 300;

    private final List<WebActionC2SPacket> sentPackets = new ArrayList<>();
    private final List<String> localActions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ClientWebManager.clearSession(SESSION);
        ClientScheduler.clearAll();
        ClientScreenInteractionManager.setClientEventCallback(null);
        ClientScreenInteractionManager.setServerActionSender(sentPackets::add);
    }

    @AfterEach
    void tearDown() {
        ClientScreenInteractionManager.setServerActionSender(null);
        ClientScreenInteractionManager.setClientEventCallback(null);
        ClientWebManager.clearSession(SESSION);
        ClientScheduler.clearAll();
    }

    /** サーバーが {@code dom_sync} で送る形に直したタグ。 */
    private static CompoundTag syncPacketFor(String html) {
        HtmlNode serverRoot = new UiDocumentCompiler().parseDocument(html, "", WIDTH, HEIGHT).root;
        CompoundTag tag = new CompoundTag();
        tag.put("DomTree", HtmlNodeSerializer.serialize(serverRoot));
        tag.putString("CssString", "");
        tag.putInt("RootW", WIDTH);
        tag.putInt("RootH", HEIGHT);
        return tag;
    }

    private static CompoundTag mutationPacket(DomMutationRecord... records) {
        ListTag list = new ListTag();
        for (DomMutationRecord record : records) list.add(record.toTag());
        CompoundTag tag = new CompoundTag();
        tag.put("Mutations", list);
        return tag;
    }

    private void syncServerDocument(String bodyHtml) {
        ClientWebManager.handleSyncPacket(SESSION, syncPacketFor("<html><body>" + bodyHtml + "</body></html>"));
    }

    private void loadClientDocument(String bodyHtml) {
        ClientWebManager.loadLocalHtml(SESSION, "<html><body>" + bodyHtml + "</body></html>", WIDTH, HEIGHT);
    }

    private HtmlNode node(String id) {
        HtmlNode root = ClientWebManager.getRootNode(SESSION);
        assertNotNull(root, "セッションに文書があること");
        HtmlNode found = root.getNodeById(id);
        assertNotNull(found, "要素 #" + id + " が文書にあること");
        return found;
    }

    private static String field(WebActionC2SPacket packet, String key) {
        return packet.payload().getString(key);
    }

    // --- 所有権 ---

    @Test
    @DisplayName("サーバーと同期した文書の既定はサーバー所有、クライアントだけで開いた文書の既定はクライアント所有")
    void documentDefaultOwnershipFollowsWhoDrivesTheSession() {
        loadClientDocument("<p id=\"mine\">x</p>");
        assertEquals(Ownership.CLIENT, node("mine").ownership());

        ClientWebManager.clearSession(SESSION);
        syncServerDocument("<p id=\"theirs\">x</p>");
        assertTrue(ClientWebManager.isServerManagedSession(SESSION));
        assertEquals(Ownership.SERVER, node("theirs").ownership());
    }

    @Test
    @DisplayName("サーバー所有の部分木へのクライアントの書き込みは、次のサーバー変異で上書きされる")
    void nextServerMutationOverwritesClientWriteInServerOwnedSubtree() {
        syncServerDocument("<p id=\"score\">0</p>");

        node("score").setTextContent("client says 99");
        assertEquals("client says 99", node("score").getTextContent(), "書き込み自体はできる");

        ClientWebManager.handlePatchPacket(SESSION, mutationPacket(DomMutationRecord.text("score", "7")));

        assertEquals("7", node("score").getTextContent(), "サーバーの変異が正になる");
    }

    @Test
    @DisplayName("クライアント所有の部分木には、サーバー変異が届いても適用されない")
    void serverMutationsAreNotAppliedInsideClientOwnedSubtree() {
        syncServerDocument("<p id=\"score\">0</p>"
                + "<div id=\"overlay\" data-owner=\"client\"><span id=\"fps\">60</span></div>");
        node("fps").setTextContent("144");

        ClientWebManager.handlePatchPacket(SESSION, mutationPacket(
                DomMutationRecord.text("fps", "from server"),
                DomMutationRecord.attribute("overlay", "title", "from server", false),
                DomMutationRecord.text("score", "1")));

        assertEquals("144", node("fps").getTextContent());
        assertFalse(node("overlay").hasAttribute("title"));
        assertEquals("1", node("score").getTextContent(), "同じパケットの、サーバー所有の変異は適用される");
    }

    @Test
    @DisplayName("再同期はサーバー所有の部分木を作り直し、クライアント所有の島は同じ id の位置へ移して残す")
    void resyncRebuildsServerTreeAndKeepsClientOwnedIslands() {
        syncServerDocument("<p id=\"title\">old</p>"
                + "<div id=\"slot\" data-owner=\"client\"><span id=\"note\">draft</span></div>");
        HtmlNode documentHandle = ClientWebManager.getRootNode(SESSION);
        node("note").setTextContent("my edited note");

        syncServerDocument("<p id=\"title\">new</p><section id=\"wrapper\">"
                + "<div id=\"slot\" data-owner=\"client\"><span id=\"note\">server draft</span></div></section>");

        assertSame(documentHandle, ClientWebManager.getRootNode(SESSION), "文書のハンドルは変わらない");
        assertEquals("new", node("title").getTextContent());
        assertEquals("my edited note", node("note").getTextContent(), "島の中身はクライアントの書き込みのまま");
        assertEquals("wrapper", node("slot").parent.getId(), "島はサーバーが置いた位置へ移る");
        assertTrue(documentHandle.contains(node("slot")), "島は生きている文書に接続し直される");
    }

    // --- 宣言による転送 ---

    @Test
    @DisplayName("data-forward のクリックは、子孫から届いても宣言した要素の id・アクション名・指定した値で送られる")
    void declaredClickIsForwardedWithChosenPayload() {
        loadClientDocument("<button id=\"buy\" data-forward=\"click:target\" data-forward-action=\"buy:sword\">"
                + "<span id=\"label\">Buy</span></button>");

        node("label").dispatchEvent(DomEvent.click(3, 4, 0));

        assertEquals(1, sentPackets.size());
        WebActionC2SPacket packet = sentPackets.get(0);
        assertEquals(SESSION, packet.sessionId());
        assertEquals("ui_event", packet.action());
        assertEquals("buy", field(packet, "ElementId"));
        assertEquals("onclick", field(packet, "EventName"));
        assertEquals("buy:sword", field(packet, "ActionName"));
        assertEquals("label", field(packet, "Value"), "TARGET はイベントの対象要素の id");
    }

    @Test
    @DisplayName("クライアントのリスナーが preventDefault した宣言のイベントは、サーバーへ送られない")
    void preventDefaultKeepsDeclaredEventOffTheNetwork() {
        loadClientDocument("<button id=\"buy\" data-forward=\"click\" data-forward-action=\"buy\">Buy</button>");
        node("buy").addEventListener("click", Event::preventDefault);

        node("buy").dispatchEvent(DomEvent.click(1, 1, 0));

        assertTrue(sentPackets.isEmpty());
    }

    @Test
    @DisplayName("宣言していない種別のイベントは、同じ要素でも送られない")
    void undeclaredEventTypesAreNotForwarded() {
        loadClientDocument("<button id=\"buy\" data-forward=\"dblclick\" data-forward-action=\"buy\">Buy</button>");

        node("buy").dispatchEvent(DomEvent.click(1, 1, 0));

        assertTrue(sentPackets.isEmpty());
    }

    @Test
    @DisplayName("サーバー所有の入力コントロールは、宣言なしでも input / change の値を書き戻しとして送る")
    void serverOwnedControlsForwardTheirValueImplicitly() {
        syncServerDocument("<input id=\"name\" type=\"text\">"
                + "<div data-owner=\"client\"><input id=\"search\" type=\"text\"></div>");

        node("name").attrs.put("value", "Alex");
        node("name").dispatchEvent(new CoreEvent.CoreUIEvent("input", true, false, 0));
        node("search").attrs.put("value", "diamond");
        node("search").dispatchEvent(new CoreEvent.CoreUIEvent("input", true, false, 0));

        assertEquals(1, sentPackets.size(), "クライアント所有の入力は送らない");
        WebActionC2SPacket packet = sentPackets.get(0);
        assertEquals("name", field(packet, "ElementId"));
        assertEquals("oninput", field(packet, "EventName"));
        assertEquals("", field(packet, "ActionName"));
        assertEquals("Alex", field(packet, "Value"));
    }

    @Test
    @DisplayName("onclick=\"server:…\" は、ローカルのルータが処理しなければ宣言と同じ経路でサーバーへ送られる")
    void serverPrefixedInlineActionIsSugarForDeclaration() {
        loadClientDocument("<button id=\"go\" onclick=\"server:teleport:spawn\">Go</button>");

        node("go").dispatchEvent(DomEvent.click(1, 1, 0));

        assertEquals(1, sentPackets.size());
        assertEquals("server:teleport:spawn", field(sentPackets.get(0), "ActionName"));
        assertEquals("go", field(sentPackets.get(0), "ElementId"));
    }

    @Test
    @DisplayName("ローカルのルータが処理した server: アクションを、ここで重ねて送らない")
    void locallyRoutedServerActionIsNotSentTwice() {
        ClientScreenInteractionManager.setClientEventCallback((session, elementId, eventName, action, value) -> {
            localActions.add(action);
            return true;
        });
        loadClientDocument("<button id=\"go\" onclick=\"server:teleport\">Go</button>");

        node("go").dispatchEvent(DomEvent.click(1, 1, 0));

        assertEquals(List.of("server:teleport"), localActions);
        assertTrue(sentPackets.isEmpty());
    }

    @Test
    @DisplayName("server: の付かない未処理のアクション名は、サーバーへ送られない")
    void unhandledLocalActionsStayOnTheClient() {
        loadClientDocument("<button id=\"b\" onclick=\"open_inventory\">Go</button>");

        node("b").dispatchEvent(DomEvent.click(1, 1, 0));

        assertTrue(sentPackets.isEmpty());
    }

    // --- クライアントだけの文書 ---

    @Test
    @DisplayName("クライアントだけで開いた HUD を毎フレーム更新しても、パケットは 1 つも出ない")
    void clientOnlyHudUpdatedEveryFrameSendsNoPackets() {
        loadClientDocument("<div id=\"hud\"><span id=\"fps\">0</span><div id=\"bar\" style=\"width:10px;height:4px\"></div></div>");
        SessionContext session = ClientWebManager.getOrCreateSession(SESSION);
        session.setServerManaged(false);
        Document document = ClientWebManager.getDomDocument(SESSION);
        assertNotNull(document);

        int[] frame = {0};
        Runnable[] nextFrame = new Runnable[1];
        nextFrame[0] = () -> ClientScheduler.requestAnimationFrame(SESSION, timestamp -> {
            frame[0]++;
            document.getElementById("fps").setTextContent(String.valueOf(frame[0]));
            document.getElementById("bar").setAttribute("style", "width:" + (10 + frame[0]) + "px;height:4px");
            document.getElementById("hud").setAttribute("data-frame", String.valueOf(frame[0]));
            ClientWebManager.requestRender(SESSION);
            ClientScreenInteractionManager.ensureSynced(SESSION);
            nextFrame[0].run();
        });
        nextFrame[0].run();

        for (int i = 0; i < 240; i++) {
            ClientScheduler.onRenderFrame(i * 16.0);
            ClientScheduler.onClientTick();
        }

        assertEquals(240, frame[0], "全フレームで更新が走ったこと");
        assertEquals("240", document.getElementById("fps").getTextContent());
        assertTrue(sentPackets.isEmpty(), "クライアント駆動の文書の更新はネットワークに出ない: " + sentPackets);
    }
}