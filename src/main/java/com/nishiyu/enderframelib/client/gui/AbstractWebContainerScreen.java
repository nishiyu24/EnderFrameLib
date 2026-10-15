package com.nishiyu.enderframelib.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.binding.WebStateStore;
import com.nishiyu.enderframelib.client.renderer.WebCanvasContext;
import com.nishiyu.enderframelib.client.renderer.WebRenderCore;
import com.nishiyu.enderframelib.client.renderer.WebSceneContext;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.model.UIElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Consumer;

/**
 * WebStateStore、ラムダ式イベントルーター、<textarea> マルチライン編集、<canvas>、<mc-scene> 3D操作、
 * <select> ドロップダウン、ドラッグ＆ドロップ、バニラ Slot スケーリング完全同期、
 * 1行サーバーRPC送信、JEI/EMI連携ヘルパー、F12 DevTools、F5 ホットリロードを標準内蔵したコンテナ画面基底クラス。
 * UI操作・オーバーレイ描画ロジックは AbstractWebScreen.WebUiScreenSupport と共通化されている。
 */
public abstract class AbstractWebContainerScreen<T extends AbstractContainerMenu> extends AbstractContainerScreen<T> {

    protected final String sessionId;
    public final WebStateStore state;
    protected final AbstractWebScreen.WebUiScreenSupport uiSupport;

    protected int virtualWidth;
    protected int virtualHeight;
    protected boolean fullscreenResponsive;
    protected float viewportMarginRatio = 0.94f;
    protected float guiScaleFactor = 1.0f;
    protected float offsetX = 0.0f;
    protected float offsetY = 0.0f;
    protected EditBox hiddenEditBox;
    protected String hoveredId = "";
    private boolean inScaledSuperRender = false;

    // DevTools (F12 インスペクタ & F5 ホットリロード)
    protected boolean devToolsEnabled = false;
    protected ResourceLocation htmlResourceLocation = null;

    // バニラ Slot.x / Slot.y リフレクションキャッシュ
    private static Field slotXField;
    private static Field slotYField;
    static {
        try {
            for (Field f : Slot.class.getDeclaredFields()) {
                if (f.getType() == int.class) {
                    if (f.getName().equals("x") || f.getName().equals("xPos")) {
                        f.setAccessible(true);
                        slotXField = f;
                    } else if (f.getName().equals("y") || f.getName().equals("yPos")) {
                        f.setAccessible(true);
                        slotYField = f;
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public AbstractWebContainerScreen(T menu, Inventory playerInventory, Component title) {
        this(menu, playerInventory, title, "local_container_" + UUID.randomUUID(), 0, 0);
    }

    public AbstractWebContainerScreen(T menu, Inventory playerInventory, Component title, String sessionId) {
        this(menu, playerInventory, title, sessionId, 0, 0);
    }

    public AbstractWebContainerScreen(T menu, Inventory playerInventory, Component title, String sessionId, int virtualWidth, int virtualHeight) {
        super(menu, playerInventory, title);
        this.sessionId = sessionId;
        this.state = new WebStateStore(sessionId);
        this.uiSupport = new AbstractWebScreen.WebUiScreenSupport(sessionId, state);
        if (virtualWidth <= 0 || virtualHeight <= 0) {
            this.fullscreenResponsive = true;
            this.virtualWidth = 854;
            this.virtualHeight = 480;
            this.imageWidth = 854;
            this.imageHeight = 480;
        } else {
            this.fullscreenResponsive = false;
            this.virtualWidth = virtualWidth;
            this.virtualHeight = virtualHeight;
            this.imageWidth = virtualWidth;
            this.imageHeight = virtualHeight;
        }
    }

    protected void setHtmlResource(ResourceLocation location) {
        this.htmlResourceLocation = location;
    }

    @Override
    protected void init() {
        super.init();
        this.titleLabelX = 10000;
        this.inventoryLabelY = 10000;

        this.hiddenEditBox = this.uiSupport.createHiddenEditBox(this.font, this::onWebInputChanged);
        this.addWidget(this.hiddenEditBox);

        reloadWebUI(false);
    }

    public void reloadWebUI() {
        reloadWebUI(true);
    }

    protected void reloadWebUI(boolean forceReload) {
        this.uiSupport.clearHandlers();
        this.state.clearBindings();
        registerWebActions();
        updateViewportTransform();

        ClientWebManager.initOrBindSession(
                this.sessionId,
                buildInitialHtml(),
                this.htmlResourceLocation,
                this.virtualWidth,
                this.virtualHeight,
                forceReload
        );
        this.state.forceSync();
        ClientWebManager.processRenderQueue();
        syncVanillaSlotsToDom();
    }

    protected abstract String buildInitialHtml();

    protected void registerWebActions() {}

    protected void onAction(String actionName, Runnable handler) {
        this.uiSupport.onAction(actionName, handler);
    }

    protected void onActionPrefix(String prefix, Consumer<String> payloadHandler) {
        this.uiSupport.onActionPrefix(prefix, payloadHandler);
    }

    public void sendServerAction(String actionName) {
        sendServerAction(actionName, "");
    }

    public void sendServerAction(String actionName, Object value) {
        this.uiSupport.sendServerAction(actionName, value);
    }

    /**
     * JEI / EMI の Exclusion Area（アイテム一覧除外領域）登録にそのまま渡せる画面矩形
     */
    public Rect2i getWebViewportBounds() {
        return new Rect2i(
                Math.round(this.offsetX),
                Math.round(this.offsetY),
                Math.round(this.virtualWidth * this.guiScaleFactor),
                Math.round(this.virtualHeight * this.guiScaleFactor)
        );
    }

    /**
     * マウスホバー中の Web要素が item:... や fluid:... の場合、JEI/EMIのレシピ表示用に ItemStack / FluidStack を返す
     */
    public Object getHoveredWebIngredient(double mouseX, double mouseY) {
        ClientWebManager.UIHitResult hit = ClientWebManager.getHitElement(sessionId, toWebX(mouseX), toWebY(mouseY));
        if (hit == null || hit.element() == null || hit.element().text() == null) return null;
        String src = hit.element().text();
        if (src.startsWith("item:")) {
            try {
                String rawItem = src.substring(5).split("\\*")[0].trim();
                Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(rawItem));
                if (item != Items.AIR) return new ItemStack(item);
            } catch (Exception ignored) {}
        } else if (src.startsWith("fluid:")) {
            try {
                var fluid = BuiltInRegistries.FLUID.get(ResourceLocation.parse(src.substring(6).trim()));
                if (fluid != net.minecraft.world.level.material.Fluids.EMPTY) {
                    return new FluidStack(fluid, 1000);
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    public WebCanvasContext getCanvas(String canvasId) {
        return WebCanvasContext.getOrCreate(this.sessionId, canvasId);
    }

    public WebSceneContext getScene(String sceneId) {
        return WebSceneContext.getOrCreate(this.sessionId, sceneId);
    }

    protected void onWebAction(String action, UIElement clickedElement, int mouseButton) {}
    protected void onWebInputChanged(String elementId, String newText) {}
    protected void onWebInputSubmitted(String elementId, String text) {}
    protected void onWebDrop(String draggedId, String dropTargetId, String dropAction) {}

    protected void updateViewportTransform() {
        if (this.fullscreenResponsive) {
            float aspect = (this.height > 0) ? ((float) this.width / (float) this.height) : (16.0f / 9.0f);
            int targetW = Math.max(854, Math.round(480 * aspect));
            int targetH = Math.max(480, Math.round(targetW / aspect));

            if (this.virtualWidth != targetW || this.virtualHeight != targetH) {
                this.virtualWidth = targetW;
                this.virtualHeight = targetH;
                if (ClientWebManager.hasDocument(sessionId)) {
                    ClientWebManager.recomputeLayout(sessionId, this.virtualWidth, this.virtualHeight);
                }
            }
        }

        float scaleX = (this.width * viewportMarginRatio) / virtualWidth;
        float scaleY = (this.height * viewportMarginRatio) / virtualHeight;
        this.guiScaleFactor = Math.min(scaleX, scaleY);
        this.offsetX = Math.round((this.width - virtualWidth * guiScaleFactor) / 2.0f);
        this.offsetY = Math.round((this.height - virtualHeight * guiScaleFactor) / 2.0f);
    }

    /**
     * HTML内の <div data-slot="0"> または <slot index="0"> にバニラ Slot を同期する。
     */
    protected void syncVanillaSlotsToDom() {
        if (this.menu == null || this.menu.slots.isEmpty()) return;
        HtmlNode vRoot = ClientWebManager.getVirtualRoot(sessionId);
        if (vRoot == null) return;

        Map<Integer, HtmlNode> slotNodes = new HashMap<>();
        collectSlotNodesRecursive(vRoot, slotNodes);
        if (slotNodes.isEmpty()) return;

        Map<String, UIElement> nodeElements = new HashMap<>();
        for (UIElement el : ClientWebManager.getElements(sessionId)) {
            if ("node".equals(el.type())) {
                nodeElements.put(el.id(), el);
            }
        }

        for (Map.Entry<Integer, HtmlNode> entry : slotNodes.entrySet()) {
            int slotIdx = entry.getKey();
            if (slotIdx < 0 || slotIdx >= this.menu.slots.size()) continue;

            Slot slot = this.menu.slots.get(slotIdx);
            HtmlNode node = entry.getValue();
            UIElement anchor = nodeElements.get(node.id);

            if (anchor != null && anchor.width() > 0 && anchor.height() > 0 && isNodeVisibleInDom(node)) {
                int targetX = Math.round(anchor.x() + (anchor.width() - 16.0f) / 2.0f);
                int targetY = Math.round(anchor.y() + (anchor.height() - 16.0f) / 2.0f);
                setSlotPosition(slot, targetX, targetY);
            } else {
                setSlotPosition(slot, -10000, -10000);
            }
        }
    }

    private void collectSlotNodesRecursive(HtmlNode node, Map<Integer, HtmlNode> out) {
        if (node == null) return;
        String slotAttr = node.attrs.get("data-slot");
        if (slotAttr == null && "slot".equalsIgnoreCase(node.tag)) {
            slotAttr = node.attrs.getOrDefault("index", node.attrs.get("id"));
        }
        if (slotAttr != null) {
            try {
                int idx = Integer.parseInt(slotAttr.trim());
                out.put(idx, node);
            } catch (NumberFormatException ignored) {}
        }
        for (HtmlNode child : node.children) {
            collectSlotNodesRecursive(child, out);
        }
    }

    private boolean isNodeVisibleInDom(HtmlNode node) {
        HtmlNode cur = node;
        while (cur != null) {
            String style = cur.attrs.getOrDefault("style", "");
            if (style.replaceAll("\\s+", "").toLowerCase().contains("display:none")) return false;
            if (cur.computedStyle != null && "none".equalsIgnoreCase(cur.computedStyle.get("display"))) return false;
            cur = cur.parent;
        }
        return true;
    }

    private static void setSlotPosition(Slot slot, int x, int y) {
        if (slot == null) return;
        try {
            if (slotXField != null) slotXField.setInt(slot, x);
            if (slotYField != null) slotYField.setInt(slot, y);
        } catch (Exception ignored) {}
    }

    protected double toWebX(double mouseX) {
        return (mouseX - offsetX) / guiScaleFactor;
    }

    protected double toWebY(double mouseY) {
        return (mouseY - offsetY) / guiScaleFactor;
    }

    protected double toSlotSpaceX(double mouseX) {
        return this.leftPos + toWebX(mouseX);
    }

    protected double toSlotSpaceY(double mouseY) {
        return this.topPos + toWebY(mouseY);
    }

    @Override
    public void renderBackground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (!inScaledSuperRender) {
            super.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        double webX = mouseX - this.leftPos;
        double webY = mouseY - this.topPos;

        ClientWebManager.UIHitResult hit = ClientWebManager.getHitElement(sessionId, webX, webY);
        hoveredId = (hit != null && hit.element() != null) ? ClientWebManager.getBaseNodeId(hit.element().id()) : "";

        List<UIElement> elements = ClientWebManager.getElements(sessionId);
        if (elements != null && !elements.isEmpty()) {
            PoseStack poseStack = guiGraphics.pose();
            poseStack.pushPose();
            poseStack.translate(this.leftPos, this.topPos, 0);

            var bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
            int logicW = (int) Math.ceil(virtualWidth / WebRenderCore.RESOLUTION);
            int logicH = (int) Math.ceil(virtualHeight / WebRenderCore.RESOLUTION);

            WebRenderCore.renderElements(
                    elements, logicW, logicH,
                    poseStack, bufferSource,
                    0xF000F0, OverlayTexture.NO_OVERLAY,
                    true, true, true,
                    0.05f, 0.02f,
                    hoveredId,
                    0x00000000, false,
                    Minecraft.getInstance().level,
                    false, true, sessionId,
                    null, null, 0
            );
            bufferSource.endBatch();

            this.uiSupport.renderOverlays(guiGraphics, this.font, elements, (int) webX, (int) webY, virtualWidth, virtualHeight, devToolsEnabled);

            poseStack.popPose();
        }
    }

    protected void renderDevToolsInspector(GuiGraphics guiGraphics, List<UIElement> elements, int webX, int webY) {
        this.uiSupport.renderDevToolsInspector(guiGraphics, this.font, elements, webX, webY, virtualWidth, virtualHeight);
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {}

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.state.syncToDom();
        ClientWebManager.processRenderQueue();
        updateViewportTransform();
        syncVanillaSlotsToDom();

        // 1. 背景の暗転はフルスクリーン等倍で描画
        super.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        // 2. Web UI とバニラ Slot・ドラッグ中アイテムを全て同一の guiScaleFactor 空間で描画
        int slotMouseX = (int) Math.round(toSlotSpaceX(mouseX));
        int slotMouseY = (int) Math.round(toSlotSpaceY(mouseY));

        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(this.leftPos, this.topPos, 0);
        pose.scale(this.guiScaleFactor, this.guiScaleFactor, 1.0f);
        pose.translate(-this.leftPos, -this.topPos, 0);

        this.inScaledSuperRender = true;
        try {
            super.render(guiGraphics, slotMouseX, slotMouseY, partialTick);
        } finally {
            this.inScaledSuperRender = false;
            pose.popPose();
        }

        // 3. ツールチップは実際のスクリーンマウス位置に描画
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double webX = toWebX(mouseX);
        double webY = toWebY(mouseY);

        boolean handled = this.uiSupport.handleMouseClicked(
                this, this.font, webX, webY, button,
                this::onWebInputChanged,
                this::onWebAction
        );
        if (handled) {
            return true;
        }
        return super.mouseClicked(toSlotSpaceX(mouseX), toSlotSpaceY(mouseY), button);
    }

    protected boolean dispatchRoutedAction(String action) {
        return this.uiSupport.dispatchRoutedAction(action);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        double webX = toWebX(mouseX);
        double webY = toWebY(mouseY);
        if (this.uiSupport.handleMouseDragged(webX, webY, dragX, dragY, guiScaleFactor, this::onWebInputChanged)) {
            return true;
        }
        return super.mouseDragged(toSlotSpaceX(mouseX), toSlotSpaceY(mouseY), button, dragX / guiScaleFactor, dragY / guiScaleFactor);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        double webX = toWebX(mouseX);
        double webY = toWebY(mouseY);
        this.uiSupport.handleMouseReleased(webX, webY, this::onWebDrop);
        return super.mouseReleased(toSlotSpaceX(mouseX), toSlotSpaceY(mouseY), button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double webX = toWebX(mouseX);
        double webY = toWebY(mouseY);
        if (this.uiSupport.handleMouseScrolled(webX, webY, scrollX, scrollY)) {
            return true;
        }
        return super.mouseScrolled(toSlotSpaceX(mouseX), toSlotSpaceY(mouseY), scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_F12) {
            this.devToolsEnabled = !this.devToolsEnabled;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_F5) {
            reloadWebUI(true);
            return true;
        }
        if (this.uiSupport.handleKeyPressed(keyCode, scanCode, modifiers, true, this::onWebInputChanged, this::onWebInputSubmitted)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.uiSupport.handleCharTyped(codePoint, modifiers, this::onWebInputChanged)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    public void setTextareaValue(String id, String value) { this.uiSupport.setTextareaValue(id, value); }
    public String getTextareaValue(String id) { return this.uiSupport.getTextareaValue(id); }
    public void focusWebInput(String inputElementId, String currentText) { this.uiSupport.focusWebInput(this, inputElementId, currentText); }
    public void unfocusWebInput() { this.uiSupport.unfocusWebInput(); }
    public void setElementText(String id, String text) { this.uiSupport.setElementText(id, text); }
    public void setInputValue(String id, String value) { this.uiSupport.setInputValue(id, value); }
    public String getInputValue(String id) { return this.uiSupport.getInputValue(id); }
    public void setInnerHtml(String id, String html) { ClientWebManager.updateNodeInnerHtmlLocally(sessionId, id, html); }
    public void setStyle(String id, String prop, String val) { ClientWebManager.updateNodeStyleLocally(sessionId, id, prop, val); }
    public void setAttribute(String id, String attr, String val) { ClientWebManager.updateNodeAttributeLocally(sessionId, id, attr, val, false); }
    public void addClass(String id, String cls) { this.uiSupport.addClass(id, cls); }
    public void removeClass(String id, String cls) { this.uiSupport.removeClass(id, cls); }
    public void toggleClass(String id, String cls, boolean enable) { this.uiSupport.toggleClass(id, cls, enable); }

    @Override
    public void onClose() {
        this.uiSupport.onClose();
        if (!ClientWebManager.isServerManagedSession(this.sessionId)) {
            WebCanvasContext.clearSession(this.sessionId);
            WebSceneContext.clearSession(this.sessionId);
            ClientWebManager.clearSession(this.sessionId);
        }
        super.onClose();
    }
}