package com.nishiyu.enderframelib.client.gui;

import com.nishiyu.enderframelib.EnderFrameLib;
import com.nishiyu.enderframelib.core.model.UIElement.ElementType;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.binding.WebStateStore; // ★これが必須です
import com.nishiyu.enderframelib.client.compat.WebRecipeViewerBridge;
import com.nishiyu.enderframelib.client.renderer.WebCanvasContext;
import com.nishiyu.enderframelib.client.renderer.WebPaintPass;
import com.nishiyu.enderframelib.client.renderer.WebRenderCore;
import com.nishiyu.enderframelib.client.renderer.WebSceneContext;
import com.nishiyu.enderframelib.core.dom.DomUtils;
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
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.function.Consumer;

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

    protected boolean isLocalHtmlSession = true;
    protected boolean devToolsEnabled = false;
    protected ResourceLocation htmlResourceLocation = null;

    private static final java.lang.reflect.Field SLOT_X_FIELD;
    private static final java.lang.reflect.Field SLOT_Y_FIELD;

    static {
        java.lang.reflect.Field xField = null;
        java.lang.reflect.Field yField = null;
        try {
            for (java.lang.reflect.Field f : Slot.class.getDeclaredFields()) {
                if (f.getType() == int.class) {
                    if ("x".equals(f.getName()) || "xPos".equals(f.getName())) {
                        f.setAccessible(true);
                        xField = f;
                    } else if ("y".equals(f.getName()) || "yPos".equals(f.getName())) {
                        f.setAccessible(true);
                        yField = f;
                    }
                }
            }
        } catch (Throwable t) {
            com.mojang.logging.LogUtils.getLogger().warn("Failed to initialize Slot position reflection fields in AbstractWebContainerScreen", t);
        }
        SLOT_X_FIELD = xField;
        SLOT_Y_FIELD = yField;
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

    public String getWebSessionId() {
        return this.sessionId;
    }

    protected void setHtmlResource(ResourceLocation location) {
        this.htmlResourceLocation = location;
    }

    @Override
    protected void init() {
        super.init();
        this.titleLabelX = 10000;
        this.inventoryLabelY = 10000;

        this.hiddenEditBox = this.uiSupport.createHiddenEditBox(this.font, this::notifyInputChanged);
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

        this.isLocalHtmlSession = ClientWebManager.initOrBindSession(
                this.sessionId,
                buildInitialHtml(),
                this.htmlResourceLocation,
                this.virtualWidth,
                this.virtualHeight,
                forceReload
        );
        this.state.forceSync();
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

    public Rect2i toScreenRect(int webX, int webY, int webW, int webH) {
        return this.uiSupport.toScreenRect(webX, webY, webW, webH);
    }

    public Rect2i getWebViewportBounds() {
        return this.uiSupport.getViewportBounds();
    }

    public List<Rect2i> getWebExclusionZones() {
        return WebRecipeViewerBridge.getExclusionZones(this);
    }

    public List<WebRecipeViewerBridge.GhostDropTarget> getGhostTargets() {
        return WebRecipeViewerBridge.getGhostTargets(this);
    }

    public void acceptGhostIngredientDrop(String elementId, Object ingredient) {
        String idStr = "";
        if (ingredient instanceof ItemStack stack && !stack.isEmpty()) {
            idStr = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            setAttribute(elementId, "data-ghost-item", idStr);
            setAttribute(elementId, "data-item", idStr);
        } else if (ingredient instanceof FluidStack fluidStack && !fluidStack.isEmpty()) {
            idStr = BuiltInRegistries.FLUID.getKey(fluidStack.getFluid()).toString();
            setAttribute(elementId, "data-fluid", idStr);
        }
        if (!idStr.isEmpty()) {
            HtmlNode node = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), elementId);
            if (node != null && node.attrs.containsKey("data-model")) {
                this.state.set(node.attrs.get("data-model").trim(), idStr);
            }
            sendServerAction("ghost_drop:" + elementId, idStr);
            onWebGhostDrop(elementId, ingredient, idStr);
        }
    }

    public Object getHoveredWebIngredient(double mouseX, double mouseY) {
        return WebRecipeViewerBridge.resolveIngredientAt(sessionId, toWebX(mouseX), toWebY(mouseY));
    }

    public WebCanvasContext getCanvas(String canvasId) {
        return WebCanvasContext.getOrCreate(this.sessionId, canvasId);
    }

    public WebSceneContext getScene(String sceneId) {
        return WebSceneContext.getOrCreate(this.sessionId, sceneId);
    }

    protected void onInputChanged(String elementId, String newText) {}
    protected void onWebAction(String action, UIElement clickedElement, int mouseButton) {}
    protected void onWebInputChanged(String elementId, String newText) {}
    protected void notifyInputChanged(String elementId, String newText) {
        onInputChanged(elementId, newText);
        onWebInputChanged(elementId, newText);
    }
    protected void onWebInputSubmitted(String elementId, String text) {}
    protected void onWebDrop(String draggedId, String dropTargetId, String dropAction) {}
    protected void onWebGhostDrop(String elementId, Object ingredient, String resourceId) {}

    protected void updateViewportTransform() {
        this.uiSupport.updateViewport(this.width, this.height, this.virtualWidth, this.virtualHeight, this.fullscreenResponsive, this.viewportMarginRatio);
        this.virtualWidth = this.uiSupport.getVirtualWidth();
        this.virtualHeight = this.uiSupport.getVirtualHeight();
        this.guiScaleFactor = this.uiSupport.getGuiScaleFactor();
        this.offsetX = this.uiSupport.getOffsetX();
        this.offsetY = this.uiSupport.getOffsetY();
    }

    protected void syncVanillaSlotsToDom() {
        if (this.menu == null || this.menu.slots.isEmpty()) return;
        HtmlNode vRoot = ClientWebManager.getVirtualRoot(sessionId);
        if (vRoot == null) return;

        Map<Integer, HtmlNode> slotNodes = new HashMap<>();
        collectSlotNodesRecursive(vRoot, slotNodes);
        if (slotNodes.isEmpty()) return;

        Map<String, UIElement> nodeElements = new HashMap<>();
        for (UIElement el : ClientWebManager.getElements(sessionId)) {
            if (el.type() == ElementType.NODE) {
                nodeElements.put(el.id(), el);
            }
        }

        for (Map.Entry<Integer, HtmlNode> entry : slotNodes.entrySet()) {
            int slotIdx = entry.getKey();
            if (slotIdx < 0 || slotIdx >= this.menu.slots.size()) continue;

            Slot slot = this.menu.slots.get(slotIdx);
            HtmlNode node = entry.getValue();
            UIElement anchor = nodeElements.get(node.getId());

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
            if (cur.attrs.containsKey("hidden")) return false;
            if (cur.parent != null && "details".equalsIgnoreCase(cur.parent.tag) && !cur.parent.attrs.containsKey("open") && !"summary".equalsIgnoreCase(cur.tag)) {
                return false;
            }
            String style = cur.attrs.getOrDefault("style", "");
            if (style.replaceAll("\\s+", "").toLowerCase().contains("display:none")) return false;
            cur = cur.parent;
        }
        return true;
    }

    private static void setSlotPosition(Slot slot, int x, int y) {
        if (slot == null) return;
        try {
            if (SLOT_X_FIELD != null) SLOT_X_FIELD.setInt(slot, x);
            if (SLOT_Y_FIELD != null) SLOT_Y_FIELD.setInt(slot, y);
        } catch (Throwable t) {
            // 失敗すると全スロットが黙って誤った位置に描かれるため warn で出す。
            EnderFrameLib.LOGGER.warn("Failed to set Slot position to ({}, {}); "
                    + "vanilla slots will render at their original positions", x, y, t);
        }
    }

    protected double toWebX(double mouseX) {
        return this.uiSupport.toWebX(mouseX);
    }

    protected double toWebY(double mouseY) {
        return this.uiSupport.toWebY(mouseY);
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
        double webX = toWebX(mouseX);
        double webY = toWebY(mouseY);

        hoveredId = ClientScreenInteractionManager.handleHover(sessionId, webX, webY);

        List<UIElement> elements = ClientWebManager.getElements(sessionId);
        if (elements != null && !elements.isEmpty()) {
            PoseStack poseStack = guiGraphics.pose();
            poseStack.pushPose();
            poseStack.translate(this.leftPos, this.topPos, 0);

            var bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
            int logicW = (int) Math.ceil(virtualWidth / WebRenderCore.RESOLUTION);
            int logicH = (int) Math.ceil(virtualHeight / WebRenderCore.RESOLUTION);

            WebPaintPass.forGui(
                    elements, logicW, logicH,
                    poseStack, bufferSource,
                    sessionId, hoveredId,
                    Minecraft.getInstance().level,
                    false
            ).render();
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
        if (!isLocalHtmlSession) {
            ClientScreenInteractionManager.ensureSynced(sessionId);
        }
        updateViewportTransform();
        syncVanillaSlotsToDom();

        super.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

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

        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.uiSupport.handleMouseClicked(this, this.font, toWebX(mouseX), toWebY(mouseY), button, this::notifyInputChanged, this::onWebAction)) {
            return true;
        }
        return super.mouseClicked(toSlotSpaceX(mouseX), toSlotSpaceY(mouseY), button);
    }

    protected boolean dispatchRoutedAction(String action) {
        return this.uiSupport.dispatchRoutedAction(action);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.uiSupport.handleMouseDragged(toWebX(mouseX), toWebY(mouseY), dragX, dragY, guiScaleFactor, this::notifyInputChanged)) {
            return true;
        }
        return super.mouseDragged(toSlotSpaceX(mouseX), toSlotSpaceY(mouseY), button, dragX / guiScaleFactor, dragY / guiScaleFactor);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.uiSupport.handleMouseReleased(toWebX(mouseX), toWebY(mouseY), this::onWebDrop);
        return super.mouseReleased(toSlotSpaceX(mouseX), toSlotSpaceY(mouseY), button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.uiSupport.handleMouseScrolled(toWebX(mouseX), toWebY(mouseY), scrollX, scrollY)) {
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
        if (this.uiSupport.handleKeyPressed(this, this.font, keyCode, scanCode, modifiers, true, this::notifyInputChanged, this::onWebInputSubmitted, this::onWebAction)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.uiSupport.handleCharTyped(codePoint, modifiers, this::notifyInputChanged)) {
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
        if (isLocalHtmlSession && !ClientWebManager.isServerManagedSession(this.sessionId)) {
            WebCanvasContext.clearSession(this.sessionId);
            WebSceneContext.clearSession(this.sessionId);
            ClientWebManager.clearSession(this.sessionId);
        }
        super.onClose();
    }
}