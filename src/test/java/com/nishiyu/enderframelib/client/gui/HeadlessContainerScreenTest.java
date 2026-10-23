package com.nishiyu.enderframelib.client.gui;

import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.EnderFrameClientInit;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.client.template.ClientTemplateManager;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.event.CoreEvent;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.network.StateDeltaS2CPacket;
import com.nishiyu.enderframelib.network.UiActionC2SPacket;
import net.minecraft.SharedConstants;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * フェーズ H3: AbstractEnderContainerScreen の Headless State 連携テスト。
 * テンプレート ID と instanceId を指定した初期化、Vanilla スロット同期、
 * UIState/ReactiveStateStore バインディング、アクションルーティングを検証。
 */
public class HeadlessContainerScreenTest {

    private static final String TEMPLATE_ID = "mymod:crusher_screen";
    private static final String INSTANCE_ID = "block:50,64,80";

    private static final Unsafe UNSAFE;

    static {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
        } catch (Throwable ignored) {
        }

        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to obtain Unsafe", e);
        }
    }

    private final AtomicReference<UiActionC2SPacket> lastUiAction = new AtomicReference<>();

    private DummyMenu menu;
    private Inventory inventory;
    private Container container;
    private Slot inputSlot;
    private Slot outputSlot;

    @BeforeEach
    void setUp() {
        lastUiAction.set(null);
        ClientScreenInteractionManager.setUiActionSender(lastUiAction::set);
        UiDocumentCompiler.clientBoxRenderer = (rootBox, rootW, rootH, keyframes, scrollState) -> {
            DisplayListBuilder renderer = new DisplayListBuilder(rootW, rootH, keyframes, scrollState);
            return renderer.render(rootBox);
        };

        ClientTemplateManager.clearCache();
        ClientWebManager.clearSession(INSTANCE_ID);

        // テスト用機械コンテナテンプレート
        // スロット 0: left=60px, top=100px, width=18px, height=18px
        // スロット 1: left=120px, top=100px, width=18px, height=18px
        String html = """
                <html>
                <head>
                    <style>
                        body {
                            margin: 0;
                            padding: 0;
                        }
                        .slot-box {
                            position: absolute;
                            width: 18px;
                            height: 18px;
                        }
                    </style>
                </head>
                <body>
                    <div id="container-root" style="position: relative; width: 800px; height: 600px;">
                        <span id="machine-status" data-bind="status">{{status}}</span>
                        <span id="progress-text">{{progress}}</span>
                        <div id="slot-input" class="slot-box" data-slot="0" style="left: 60px; top: 100px;"></div>
                        <div id="slot-output" class="slot-box" data-slot="1" style="left: 120px; top: 100px;"></div>
                        <button id="action-btn" onclick="server:start_process">Start</button>
                    </div>
                </body>
                </html>
                """;
        ClientTemplateManager.registerTemplate(TEMPLATE_ID, html);

        // DummyMenu と Slot の準備
        menu = new DummyMenu(1);
        container = new SimpleContainer(2);
        inputSlot = menu.addTestSlot(container, 0, 0, 0);
        outputSlot = menu.addTestSlot(container, 1, 0, 0);

        try {
            inventory = (Inventory) UNSAFE.allocateInstance(Inventory.class);
            Field itemsField = Inventory.class.getDeclaredField("items");
            itemsField.setAccessible(true);
            itemsField.set(inventory, NonNullList.withSize(36, ItemStack.EMPTY));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    void tearDown() {
        ClientScreenInteractionManager.setUiActionSender(null);
        ClientWebManager.clearSession(INSTANCE_ID);
        ClientTemplateManager.clearCache();
    }

    private static class DummyMenu extends AbstractContainerMenu {
        public DummyMenu(int containerId) {
            super(null, containerId);
        }

        @Override
        public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(@NotNull Player player) {
            return true;
        }

        public Slot addTestSlot(Container container, int slot, int x, int y) {
            Slot s = new Slot(container, slot, x, y);
            this.addSlot(s);
            return s;
        }
    }

    private static class TestMachineContainerScreen extends AbstractEnderContainerScreen<DummyMenu> {
        public TestMachineContainerScreen(DummyMenu menu, Inventory inventory, Component title, String templateId, String instanceId) {
            super(menu, inventory, title, templateId, instanceId, 800, 600);
        }

        // buildInitialHtml() の実装は不要（デフォルト実装が提供される）
    }

    @Test
    @DisplayName("テンプレート ID と instanceId を指定して初期化し、DOM が展開され HeadlessState フラグが立つこと")
    void testTemplateScreenInitialization() {
        TestMachineContainerScreen screen = new TestMachineContainerScreen(
                menu, inventory, Component.literal("Test Machine"), TEMPLATE_ID, INSTANCE_ID);

        assertEquals(TEMPLATE_ID, screen.getTemplateId());
        assertEquals(INSTANCE_ID, screen.getWebSessionId());

        // UI をロード
        screen.reloadWebUI();

        SessionContext session = ClientWebManager.getSession(INSTANCE_ID);
        assertNotNull(session, "セッションが作成されていること");
        assertTrue(session.isHeadlessState(), "HeadlessState であること");
        assertFalse(session.isServerManaged(), "DOM はクライアントローカルであること");

        HtmlNode root = session.rootNode();
        assertNotNull(root, "DOM ツリーが展開されていること");
        assertNotNull(root.getElementById("slot-input"));
        assertNotNull(root.getElementById("slot-output"));
    }

    @Test
    @DisplayName("Vanilla スロット位置が DOM の要素位置に自動同期されること (syncVanillaSlotsToDom)")
    void testVanillaSlotSynchronization() {
        TestMachineContainerScreen screen = new TestMachineContainerScreen(
                menu, inventory, Component.literal("Test Machine"), TEMPLATE_ID, INSTANCE_ID);

        // 初期状態ではスロット位置は (0, 0)
        assertEquals(0, inputSlot.x);
        assertEquals(0, inputSlot.y);
        assertEquals(0, outputSlot.x);
        assertEquals(0, outputSlot.y);

        // UI をロード（内部で syncVanillaSlotsToDom() が実行される）
        screen.reloadWebUI();

        // 期待スロット位置:
        // inputSlot (slot 0): left=60, top=100, width=18, height=18
        // slot 中心寄せ: Math.round(60 + (18 - 16)/2) = 61
        //                Math.round(100 + (18 - 16)/2) = 101
        assertEquals(61, inputSlot.x, "Slot 0 の X 座標が DOM 要素の位置に同期していること");
        assertEquals(101, inputSlot.y, "Slot 0 の Y 座標が DOM 要素の位置に同期していること");

        // outputSlot (slot 1): left=120, top=100
        // slot 中心寄せ: Math.round(120 + 1) = 121, Math.round(100 + 1) = 101
        assertEquals(121, outputSlot.x, "Slot 1 の X 座標が DOM 要素の位置に同期していること");
        assertEquals(101, outputSlot.y, "Slot 1 の Y 座標が DOM 要素の位置に同期していること");
    }

    @Test
    @DisplayName("screen.state とパケット受信による UIState バインディングが DOM へ自動反映されること")
    void testStateBindingSynchronization() {
        TestMachineContainerScreen screen = new TestMachineContainerScreen(
                menu, inventory, Component.literal("Test Machine"), TEMPLATE_ID, INSTANCE_ID);
        screen.reloadWebUI();

        SessionContext session = ClientWebManager.getSession(INSTANCE_ID);
        assertNotNull(session);

        // 1. screen.state.set による更新
        screen.state.set("status", "Heating");
        screen.state.set("progress", "25%");
        screen.state.syncToDom();

        HtmlNode statusNode = session.rootNode().getElementById("machine-status");
        HtmlNode progressNode = session.rootNode().getElementById("progress-text");

        assertEquals("Heating", statusNode.getTextContent());
        assertEquals("25%", progressNode.getTextContent());

        // 2. サーバーから StateDeltaS2CPacket を受信した際の連動
        CompoundTag delta = new CompoundTag();
        delta.putString("status", "Crushing");
        delta.putString("progress", "80%");

        EnderFrameClientInit.handleStateDeltaPacket(INSTANCE_ID, delta);

        assertEquals("Crushing", statusNode.getTextContent());
        assertEquals("80%", progressNode.getTextContent());
        assertEquals("Crushing", screen.state.get("status"), "screen.state にも値が同期されていること");
    }

    @Test
    @DisplayName("Headless Container 画面内のボタンクリックで UiActionC2SPacket が送信されること")
    void testActionRoutingFromContainerScreen() {
        TestMachineContainerScreen screen = new TestMachineContainerScreen(
                menu, inventory, Component.literal("Test Machine"), TEMPLATE_ID, INSTANCE_ID);
        screen.reloadWebUI();

        SessionContext session = ClientWebManager.getSession(INSTANCE_ID);
        assertNotNull(session);

        HtmlNode btn = session.rootNode().getElementById("action-btn");
        assertNotNull(btn);

        // クリックイベントを発火
        btn.dispatchEvent(CoreEvent.click(0, 0, 0));

        UiActionC2SPacket action = lastUiAction.get();
        assertNotNull(action, "UiActionC2SPacket が送出されていること");
        assertEquals(INSTANCE_ID, action.instanceId());
        assertEquals("start_process", action.actionName());
    }

    @Test
    @DisplayName("サーバー側からのコンテナクローズ等で Screen#removed() が呼ばれるとクローズ通知が送信されること")
    void testContainerScreenRemovedSendsCloseAction() {
        TestMachineContainerScreen screen = new TestMachineContainerScreen(
                menu, inventory, Component.literal("Test Machine"), TEMPLATE_ID, INSTANCE_ID);
        screen.reloadWebUI();

        try {
            screen.removed();
        } catch (NullPointerException ignored) {
            // AbstractContainerScreen#removed は Minecraft インスタンスを参照する。
            // ヘッドレス環境では未初期化だが、クローズ通知はその前に送出済み
        }

        UiActionC2SPacket action = lastUiAction.get();
        assertNotNull(action, "クローズ通知が送出されていること");
        assertEquals(INSTANCE_ID, action.instanceId());
        assertEquals(UiActionC2SPacket.CLOSE_ACTION, action.actionName());
    }
}
