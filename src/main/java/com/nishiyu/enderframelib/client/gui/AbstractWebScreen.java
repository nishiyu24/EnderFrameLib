package com.nishiyu.enderframelib.client.gui;

import com.nishiyu.enderframelib.EnderFrameLib;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.binding.WebStateStore;
import com.nishiyu.enderframelib.client.renderer.WebCanvasContext;
import com.nishiyu.enderframelib.client.renderer.WebPaintPass;
import com.nishiyu.enderframelib.client.renderer.WebRenderCore;
import com.nishiyu.enderframelib.client.renderer.WebSceneContext;
import com.nishiyu.enderframelib.core.model.UIElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class AbstractWebScreen extends Screen {
    public static final float DEFAULT_VIRTUAL_WIDTH = 854.0f;
    public static final float DEFAULT_VIRTUAL_HEIGHT = 480.0f;

    protected final String sessionId;
    public final WebStateStore state;
    protected final WebUiScreenSupport uiSupport;

    protected int virtualWidth;
    protected int virtualHeight;
    protected boolean fullscreenResponsive = false;
    protected float viewportMarginRatio = 0.94f;
    protected float guiScaleFactor = 1.0f;
    protected float offsetX = 0.0f;
    protected float offsetY = 0.0f;
    protected EditBox hiddenEditBox;
    protected String hoveredId = "";
    private boolean isLocalHtmlSession = false;

    protected boolean devToolsEnabled = false;
    protected ResourceLocation htmlResourceLocation = null;

    public AbstractWebScreen(Component title) {
        this(title, "local_screen_" + UUID.randomUUID(), 0, 0);
    }

    public AbstractWebScreen(Component title, ResourceLocation htmlResource) {
        this(title, "local_screen_" + UUID.randomUUID(), 0, 0);
        this.htmlResourceLocation = htmlResource;
    }

    public AbstractWebScreen(Component title, String sessionId) {
        this(title, sessionId, (int) DEFAULT_VIRTUAL_WIDTH, (int) DEFAULT_VIRTUAL_HEIGHT);
    }

    public AbstractWebScreen(Component title, String sessionId, int virtualWidth, int virtualHeight) {
        super(title);
        this.sessionId = sessionId;
        this.state = new WebStateStore(sessionId);
        this.uiSupport = new WebUiScreenSupport(sessionId, state);
        if (virtualWidth <= 0 || virtualHeight <= 0) {
            this.fullscreenResponsive = true;
            this.virtualWidth = (int) DEFAULT_VIRTUAL_WIDTH;
            this.virtualHeight = (int) DEFAULT_VIRTUAL_HEIGHT;
        } else {
            this.virtualWidth = virtualWidth;
            this.virtualHeight = virtualHeight;
        }
    }

    public void setHtmlResource(ResourceLocation location) {
        this.htmlResourceLocation = location;
    }

    /**
     * HTML リソースを読み込む。見つからなければ空の {@link Optional} を返す。
     *
     * <p>以前はエラーを HTML 文字列で返しており、呼び出し側が
     * {@code contains("Failed to load HTML resource:")} で部分文字列照合して
     * 失敗を判定していた。正規の HTML がその文を含むだけで失敗扱いになるため、
     * 成否は型で表す。表示用のエラー HTML が必要な場合は
     * {@link #errorHtml(ResourceLocation)} を使う。
     */
    public static Optional<String> findHtmlResource(ResourceLocation loc) {
        try {
            Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(loc);
            if (res.isPresent()) {
                try (InputStream is = res.get().open()) {
                    return Optional.of(new String(is.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) {
            EnderFrameLib.LOGGER.debug("Resource manager could not read HTML resource {}: {}", loc, e.getMessage());
        }
        // リソースパックに無い場合は jar 内の assets から直接読む。
        try (InputStream is = AbstractWebScreen.class.getResourceAsStream("/assets/" + loc.getNamespace() + "/" + loc.getPath())) {
            if (is != null) {
                return Optional.of(new String(is.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            EnderFrameLib.LOGGER.debug("Classpath fallback could not read HTML resource {}: {}", loc, e.getMessage());
        }
        EnderFrameLib.LOGGER.warn("HTML resource not found: {}", loc);
        return Optional.empty();
    }

    /** 読み込みに失敗したことを画面上に示すための HTML。 */
    public static String errorHtml(ResourceLocation loc) {
        return "<html><body><div style='padding:20px;color:#f38ba8;'>Failed to load HTML resource: " + loc + "</div></body></html>";
    }

    public static String loadHtmlResource(ResourceLocation loc) {
        return findHtmlResource(loc).orElseGet(() -> errorHtml(loc));
    }

    @Override
    protected void init() {
        super.init();
        this.hiddenEditBox = this.uiSupport.createHiddenEditBox(this.font, this::notifyInputChanged);
        this.addWidget(this.hiddenEditBox);
        reloadWebUI(false);
    }

    private void notifyInputChanged(String baseId, String newText) {
        onInputChanged(baseId, newText);
        onWebInputChanged(baseId, newText);
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
        ClientWebManager.processRenderQueue();
    }

    protected String buildInitialHtml() {
        return null;
    }

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

    public WebCanvasContext getCanvas(String canvasId) {
        return WebCanvasContext.getOrCreate(this.sessionId, canvasId);
    }

    public WebSceneContext getScene(String sceneId) {
        return WebSceneContext.getOrCreate(this.sessionId, sceneId);
    }

    protected void onInputChanged(String elementId, String newText) {}
    protected void onWebAction(String action, UIElement clickedElement, int mouseButton) {}
    protected void onWebInputChanged(String elementId, String newText) {}
    protected void onWebInputSubmitted(String elementId, String text) {}
    protected void onWebDrop(String draggedId, String dropTargetId, String dropAction) {}

    protected void updateViewportTransform() {
        this.uiSupport.updateViewport(this.width, this.height, this.virtualWidth, this.virtualHeight, this.fullscreenResponsive, this.viewportMarginRatio);
        this.virtualWidth = this.uiSupport.getVirtualWidth();
        this.virtualHeight = this.uiSupport.getVirtualHeight();
        this.guiScaleFactor = this.uiSupport.getGuiScaleFactor();
        this.offsetX = this.uiSupport.getOffsetX();
        this.offsetY = this.uiSupport.getOffsetY();
    }

    protected double toWebX(double mouseX) {
        return this.uiSupport.toWebX(mouseX);
    }

    protected double toWebY(double mouseY) {
        return this.uiSupport.toWebY(mouseY);
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        this.state.syncToDom();
        ClientWebManager.processRenderQueue();
        if (!isLocalHtmlSession) {
            ClientScreenInteractionManager.ensureSynced(sessionId);
        }
        updateViewportTransform();

        double webX = toWebX(mouseX);
        double webY = toWebY(mouseY);
        hoveredId = ClientScreenInteractionManager.handleHover(sessionId, webX, webY);
        this.uiSupport.updateHoverState(webX, webY);

        List<UIElement> elements = ClientWebManager.getElements(sessionId);
        if (elements != null && !elements.isEmpty()) {
            PoseStack poseStack = guiGraphics.pose();
            poseStack.pushPose();
            poseStack.translate(offsetX, offsetY, 0);
            poseStack.scale(guiScaleFactor, guiScaleFactor, 1.0f);

            var bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
            int logicW = (int) Math.ceil(virtualWidth / WebRenderCore.RESOLUTION);
            int logicH = (int) Math.ceil(virtualHeight / WebRenderCore.RESOLUTION);

            WebPaintPass.forGui(
                    elements, logicW, logicH,
                    poseStack, bufferSource,
                    sessionId, hoveredId,
                    Minecraft.getInstance().level,
                    true
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
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.uiSupport.handleMouseClicked(this, this.font, toWebX(mouseX), toWebY(mouseY), button, this::notifyInputChanged, this::onWebAction)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    protected boolean dispatchRoutedAction(String action) {
        return this.uiSupport.dispatchRoutedAction(action);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.uiSupport.handleMouseDragged(toWebX(mouseX), toWebY(mouseY), dragX, dragY, guiScaleFactor, this::notifyInputChanged)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.uiSupport.handleMouseReleased(toWebX(mouseX), toWebY(mouseY), this::onWebDrop);
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.uiSupport.handleMouseScrolled(toWebX(mouseX), toWebY(mouseY), scrollX, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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
        if (this.uiSupport.handleKeyPressed(this, this.font, keyCode, scanCode, modifiers, false, this::notifyInputChanged, this::onWebInputSubmitted, this::onWebAction)) {
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

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // =========================================================================
    // 共通サポートクラス (AbstractWebScreen, AbstractWebContainerScreen 共有)
    // WebScreenDelegate への軽量委譲アダプター
    // =========================================================================

    public static class WebUiScreenSupport extends WebScreenDelegate {
        @FunctionalInterface
        public interface TriActionCallback extends InteractionHandler.TriActionCallback {
        }

        @FunctionalInterface
        public interface DropCallback extends DragDropController.DropCallback {
        }

        private final String sessionId;

        public WebUiScreenSupport(String sessionId, WebStateStore state) {
            super(sessionId, state);
            this.sessionId = sessionId;
        }

        public int getVirtualWidth() { return viewport().virtualWidth(); }
        public int getVirtualHeight() { return viewport().virtualHeight(); }
        public float getGuiScaleFactor() { return viewport().guiScaleFactor(); }
        public float getOffsetX() { return viewport().offsetX(); }
        public float getOffsetY() { return viewport().offsetY(); }
        public double toWebX(double mouseX) { return viewport().toWebX(mouseX); }
        public double toWebY(double mouseY) { return viewport().toWebY(mouseY); }
        public Rect2i toScreenRect(int webX, int webY, int webW, int webH) { return viewport().toScreenRect(webX, webY, webW, webH); }
        public Rect2i getViewportBounds() { return viewport().getViewportBounds(); }

        public void clearHandlers() { router().clear(); }
        public void onAction(String actionName, Runnable handler) { router().onAction(actionName, handler); }
        public void onActionPrefix(String prefix, Consumer<String> payloadHandler) { router().onActionPrefix(prefix, payloadHandler); }
        public void sendServerAction(String actionName, Object value) { router().sendServerAction(actionName, value); }
        public boolean dispatchRoutedAction(String action) { return router().dispatch(action); }

        public void renderDevToolsInspector(GuiGraphics guiGraphics, Font font, List<UIElement> elements, int webX, int webY, int virtualWidth, int virtualHeight) {
            WebDevTools.renderInspector(guiGraphics, font, elements, webX, webY, virtualWidth, virtualHeight, sessionId);
        }

        public void focusWebTextarea(Font font, UIElement taEl, double localX, double localY) {
            textareaEditor().focus(font, taEl, localX, localY);
        }
    }
}