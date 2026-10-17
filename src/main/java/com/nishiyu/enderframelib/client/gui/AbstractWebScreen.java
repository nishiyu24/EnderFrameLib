package com.nishiyu.enderframelib.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.binding.WebStateStore; // ★これが必須です
import com.nishiyu.enderframelib.client.renderer.WebCanvasContext;
import com.nishiyu.enderframelib.client.renderer.WebPaintPass;
import com.nishiyu.enderframelib.client.renderer.WebRenderCore;
import com.nishiyu.enderframelib.client.renderer.WebSceneContext;
import com.nishiyu.enderframelib.core.css.CssParser;
import com.nishiyu.enderframelib.core.dom.DomUtils;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.model.UIElement;
import com.nishiyu.enderframelib.network.c2s.WebActionC2SPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
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

    public static String loadHtmlResource(ResourceLocation loc) {
        try {
            Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(loc);
            if (res.isPresent()) {
                try (InputStream is = res.get().open()) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ignored) {}
        try (InputStream is = AbstractWebScreen.class.getResourceAsStream("/assets/" + loc.getNamespace() + "/" + loc.getPath())) {
            if (is != null) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}
        return "<html><body><div style='padding:20px;color:#f38ba8;'>Failed to load HTML resource: " + loc + "</div></body></html>";
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
    // 共通サポートクラス (AbstractWebScreen, AbstractWebContainerScreen, InvisibleInputScreen 共有)
    // =========================================================================

    public static class WebUiScreenSupport {
        @FunctionalInterface
        public interface TriActionCallback {
            void onAction(String action, UIElement clickedElement, int mouseButton);
        }

        @FunctionalInterface
        public interface DropCallback {
            void onDrop(String draggedId, String dropTargetId, String dropAction);
        }

        private record SelectOption(String value, String label) {}
        private record FocusCandidate(HtmlNode node, UIElement element, int tabIndex, int domOrder) {}

        private final String sessionId;
        private final WebStateStore state;
        private EditBox hiddenEditBox;
        private boolean suppressInputResponder = false;

        private int virtualWidth = 854;
        private int virtualHeight = 480;
        private float guiScaleFactor = 1.0f;
        private float offsetX = 0.0f;
        private float offsetY = 0.0f;

        private final Map<String, Runnable> exactActionHandlers = new HashMap<>();
        private final Map<String, Consumer<String>> prefixActionHandlers = new LinkedHashMap<>();

        private boolean isEditingTextarea = false;
        private String activeTextareaBaseId = "";
        private String focusedElementBaseId = "";

        private String openSelectBaseId = "";
        private int selectPopupX, selectPopupY, selectPopupW;
        private final List<SelectOption> selectOptions = new ArrayList<>();

        private String activeSceneId = "";
        private int activeSceneMouseButton = 0;

        private String draggingElementId = "";
        private String draggingLabel = "";

        public WebUiScreenSupport(String sessionId, WebStateStore state) {
            this.sessionId = sessionId;
            this.state = state;
        }

        public void updateViewport(int screenW, int screenH, int currentVirtualW, int currentVirtualH, boolean fullscreenResponsive, float marginRatio) {
            int targetW = currentVirtualW;
            int targetH = currentVirtualH;

            if (fullscreenResponsive) {
                float aspect = (screenH > 0) ? ((float) screenW / (float) screenH) : (16.0f / 9.0f);
                targetW = Math.max(854, Math.round(480 * aspect));
                targetH = Math.max(480, Math.round(targetW / aspect));

                if (currentVirtualW != targetW || currentVirtualH != targetH) {
                    if (ClientWebManager.hasDocument(sessionId)) {
                        ClientWebManager.recomputeLayout(sessionId, targetW, targetH);
                    }
                }
            }

            this.virtualWidth = targetW;
            this.virtualHeight = targetH;
            float scaleX = (screenW * marginRatio) / Math.max(1, targetW);
            float scaleY = (screenH * marginRatio) / Math.max(1, targetH);
            this.guiScaleFactor = Math.min(scaleX, scaleY);
            this.offsetX = Math.round((screenW - targetW * this.guiScaleFactor) / 2.0f);
            this.offsetY = Math.round((screenH - targetH * this.guiScaleFactor) / 2.0f);
        }

        public int getVirtualWidth() { return virtualWidth; }
        public int getVirtualHeight() { return virtualHeight; }
        public float getGuiScaleFactor() { return guiScaleFactor; }
        public float getOffsetX() { return offsetX; }
        public float getOffsetY() { return offsetY; }

        public double toWebX(double mouseX) {
            return (mouseX - offsetX) / guiScaleFactor;
        }

        public double toWebY(double mouseY) {
            return (mouseY - offsetY) / guiScaleFactor;
        }

        public Rect2i toScreenRect(int webX, int webY, int webW, int webH) {
            return new Rect2i(
                    Math.round(this.offsetX + webX * this.guiScaleFactor),
                    Math.round(this.offsetY + webY * this.guiScaleFactor),
                    Math.round(webW * this.guiScaleFactor),
                    Math.round(webH * this.guiScaleFactor)
            );
        }

        public Rect2i getViewportBounds() {
            return new Rect2i(
                    Math.round(this.offsetX),
                    Math.round(this.offsetY),
                    Math.round(this.virtualWidth * this.guiScaleFactor),
                    Math.round(this.virtualHeight * this.guiScaleFactor)
            );
        }

        public EditBox createHiddenEditBox(Font font, BiConsumer<String, String> onInputChanged) {
            this.hiddenEditBox = new EditBox(font, -2000, -2000, 400, 20, Component.empty());
            this.hiddenEditBox.setMaxLength(2048);
            this.hiddenEditBox.setResponder(newText -> {
                if (suppressInputResponder) return;
                if (!WebRenderCore.activeInputId.isEmpty() && !isEditingTextarea) {
                    String baseId = ClientWebManager.getBaseNodeId(WebRenderCore.activeInputId);
                    syncInputValueToDomNodes(baseId, newText);
                    if (this.state != null) this.state.notifyElementModelChanged(baseId, newText);
                    if (onInputChanged != null) onInputChanged.accept(baseId, newText);
                }
            });
            return this.hiddenEditBox;
        }

        public void clearHandlers() {
            exactActionHandlers.clear();
            prefixActionHandlers.clear();
        }

        public void onAction(String actionName, Runnable handler) {
            exactActionHandlers.put(actionName, handler);
        }

        public void onActionPrefix(String prefix, Consumer<String> payloadHandler) {
            prefixActionHandlers.put(prefix, payloadHandler);
        }

        public void sendServerAction(String actionName, Object value) {
            CompoundTag tag = new CompoundTag();
            tag.putString("ElementId", "");
            tag.putString("EventName", "rpc");
            tag.putString("ActionName", actionName != null ? actionName : "");
            tag.putString("Value", value != null ? String.valueOf(value) : "");
            PacketDistributor.sendToServer(new WebActionC2SPacket(this.sessionId, "ui_event", tag));
        }

        public boolean dispatchRoutedAction(String action) {
            if (action == null) return false;
            if (action.startsWith("server:")) {
                String rest = action.substring(7);
                if (rest.contains(":")) {
                    String[] sp = rest.split(":", 2);
                    sendServerAction(sp[0], sp[1]);
                } else {
                    sendServerAction(rest, "");
                }
                return true;
            }
            if (exactActionHandlers.containsKey(action)) {
                exactActionHandlers.get(action).run();
                return true;
            }
            for (Map.Entry<String, Consumer<String>> entry : prefixActionHandlers.entrySet()) {
                if (action.startsWith(entry.getKey())) {
                    entry.getValue().accept(action.substring(entry.getKey().length()));
                    return true;
                }
            }
            return false;
        }

        public void renderOverlays(GuiGraphics guiGraphics, Font font, List<UIElement> elements, int webX, int webY, int virtualWidth, int virtualHeight, boolean devToolsEnabled) {
            RenderSystem.disableDepthTest();
            try {
                if (!focusedElementBaseId.isEmpty() && elements != null) {
                    renderKeyboardFocusRing(guiGraphics, elements);
                }
                if (!openSelectBaseId.isEmpty() && !selectOptions.isEmpty()) {
                    renderSelectDropdownOverlay(guiGraphics, font, webX, webY);
                }
                if (!draggingElementId.isEmpty()) {
                    renderDragGhostOverlay(guiGraphics, font, webX, webY);
                }
                if (devToolsEnabled) {
                    renderDevToolsInspector(guiGraphics, font, elements, webX, webY, virtualWidth, virtualHeight);
                }
            } finally {
                RenderSystem.enableDepthTest();
            }
        }

        private void renderKeyboardFocusRing(GuiGraphics guiGraphics, List<UIElement> elements) {
            UIElement focusedEl = null;
            for (UIElement el : elements) {
                if ("node".equals(el.type()) && focusedElementBaseId.equals(el.id())) {
                    focusedEl = el;
                    break;
                }
            }
            if (focusedEl == null) {
                focusedEl = ClientWebManager.getElementById(sessionId, focusedElementBaseId);
            }
            if (focusedEl != null && focusedEl.width() > 0 && focusedEl.height() > 0) {
                PoseStack pose = guiGraphics.pose();
                pose.pushPose();
                pose.translate(0, 0, 280.0f);
                guiGraphics.renderOutline(
                        focusedEl.x() - 2,
                        focusedEl.y() - 2,
                        focusedEl.width() + 4,
                        focusedEl.height() + 4,
                        0xFF89B4FA
                );
                pose.popPose();
            }
        }

        private void renderSelectDropdownOverlay(GuiGraphics guiGraphics, Font font, int webX, int webY) {
            PoseStack pose = guiGraphics.pose();
            pose.pushPose();
            pose.translate(0, 0, 300.0f);
            int itemH = 18;
            int totalH = selectOptions.size() * itemH + 2;
            guiGraphics.fill(selectPopupX, selectPopupY, selectPopupX + selectPopupW, selectPopupY + totalH, 0xFF89B4FA);
            guiGraphics.fill(selectPopupX + 1, selectPopupY + 1, selectPopupX + selectPopupW - 1, selectPopupY + totalH - 1, 0xFF181825);

            String currentVal = getSelectCurrentValue(openSelectBaseId);
            for (int i = 0; i < selectOptions.size(); i++) {
                SelectOption opt = selectOptions.get(i);
                int iy = selectPopupY + 1 + i * itemH;
                boolean hov = webX >= selectPopupX && webX <= selectPopupX + selectPopupW && webY >= iy && webY < iy + itemH;
                boolean sel = opt.value().equals(currentVal);
                if (hov) guiGraphics.fill(selectPopupX + 1, iy, selectPopupX + selectPopupW - 1, iy + itemH, 0xFF45475A);
                else if (sel) guiGraphics.fill(selectPopupX + 1, iy, selectPopupX + selectPopupW - 1, iy + itemH, 0xFF313244);
                guiGraphics.drawString(font, opt.label(), selectPopupX + 6, iy + 5, sel ? 0xFF89B4FA : 0xFFCDD6F4, false);
            }
            pose.popPose();
        }

        private String getSelectCurrentValue(String baseId) {
            HtmlNode node = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), baseId);
            if (node == null) return "";
            if (node.attrs.containsKey("value") && !node.attrs.get("value").isEmpty()) {
                return node.attrs.get("value");
            }
            return !selectOptions.isEmpty() ? selectOptions.getFirst().value() : "";
        }

        private void renderDragGhostOverlay(GuiGraphics guiGraphics, Font font, int webX, int webY) {
            PoseStack pose = guiGraphics.pose();
            pose.pushPose();
            pose.translate(0, 0, 320.0f);
            int w = Math.max(60, font.width(draggingLabel) + 12);
            guiGraphics.fill(webX + 6, webY + 6, webX + 6 + w, webY + 24, 0xAA89B4FA);
            guiGraphics.drawString(font, draggingLabel, webX + 12, webY + 11, 0xFF11111B, false);
            pose.popPose();
        }

        public void renderDevToolsInspector(GuiGraphics guiGraphics, Font font, List<UIElement> elements, int webX, int webY, int virtualWidth, int virtualHeight) {
            PoseStack pose = guiGraphics.pose();
            pose.pushPose();
            pose.translate(0, 0, 350.0f);

            String banner = " [F12] EnderFrame DevTools | [F5] Hot Reload | [Tab/Arrows] Focus | Viewport: " + virtualWidth + "x" + virtualHeight + " | Mouse: (" + webX + ", " + webY + ") ";
            guiGraphics.fill(0, 0, font.width(banner) + 8, 13, 0xDD11111B);
            guiGraphics.drawString(font, banner, 4, 2, 0xFFA6E3A1, false);

            UIElement targetAnchor = null;
            for (int i = elements.size() - 1; i >= 0; i--) {
                UIElement el = elements.get(i);
                if ("node".equals(el.type()) && !"html_root".equals(el.id()) && el.width() > 0 && el.height() > 0) {
                    if (webX >= el.x() && webX <= el.x() + el.width() && webY >= el.y() && webY <= el.y() + el.height()) {
                        targetAnchor = el;
                        break;
                    }
                }
            }

            if (targetAnchor != null) {
                HtmlNode vRoot = ClientWebManager.getVirtualRoot(sessionId);
                HtmlNode domNode = vRoot != null ? DomUtils.findNodeById(vRoot, targetAnchor.id()) : null;
                int x = targetAnchor.x(), y = targetAnchor.y(), w = targetAnchor.width(), h = targetAnchor.height();

                Map<String, String> st = Map.of(); // DevToolsのマージン・パディング解析は別で再実装します

                guiGraphics.renderOutline(x, y, w, h, 0xFF89B4FA);

                List<String> infoLines = new ArrayList<>();
                String tagLabel = domNode != null ? "<" + domNode.tag + ">" : "<node>";
                String idLabel = (domNode != null && !domNode.getId().isEmpty()) ? "#" + domNode.getId() : "#" + targetAnchor.id();
                String clsLabel = (domNode != null && !domNode.getClasses().isEmpty()) ? "." + String.join(".", domNode.getClasses()) : "";
                infoLines.add("§b" + tagLabel + " §e" + idLabel + "§a" + clsLabel);
                infoLines.add("§7Rect: §f" + w + "x" + h + " §7at (" + x + ", " + y + ")");
                if (domNode != null) {
                    if (domNode.attrs.containsKey("data-model")) infoLines.add("§ddata-model: §f" + domNode.attrs.get("data-model"));
                    if (domNode.attrs.containsKey("data-bind")) infoLines.add("§ddata-bind: §f" + domNode.attrs.get("data-bind"));
                    if (domNode.attrs.containsKey("data-slot")) infoLines.add("§6data-slot: §f#" + domNode.attrs.get("data-slot"));
                    if (domNode.attrs.containsKey("onclick")) infoLines.add("§conclick: §f" + domNode.attrs.get("onclick"));
                }

                int cardW = 0;
                for (String line : infoLines) cardW = Math.max(cardW, font.width(line) + 10);
                int cardH = infoLines.size() * 11 + 6;
                int cardX = Math.clamp(x, 4, Math.max(4, virtualWidth - cardW - 4));
                int cardY = (y - cardH - 4 >= 16) ? (y - cardH - 4) : Math.min(virtualHeight - cardH - 4, y + h + 4);

                guiGraphics.fill(cardX - 1, cardY - 1, cardX + cardW + 1, cardY + cardH + 1, 0xFF89B4FA);
                guiGraphics.fill(cardX, cardY, cardX + cardW, cardY + cardH, 0xEE181825);
                for (int i = 0; i < infoLines.size(); i++) {
                    guiGraphics.drawString(font, infoLines.get(i), cardX + 5, cardY + 4 + i * 11, 0xFFFFFFFF, false);
                }
            }

            pose.popPose();
        }

        public boolean handleMouseClicked(
                Screen screen, Font font, double webX, double webY, int button,
                BiConsumer<String, String> onInputChanged,
                TriActionCallback onWebAction
        ) {
            if (!openSelectBaseId.isEmpty() && !selectOptions.isEmpty() && button == 0) {
                int itemH = 18;
                int totalH = selectOptions.size() * itemH + 2;
                if (webX >= selectPopupX && webX <= selectPopupX + selectPopupW && webY >= selectPopupY && webY <= selectPopupY + totalH) {
                    int idx = (int) ((webY - selectPopupY - 1) / itemH);
                    if (idx >= 0 && idx < selectOptions.size()) {
                        String chosenVal = selectOptions.get(idx).value();
                        ClientScreenInteractionManager.submitInput(sessionId, openSelectBaseId, chosenVal);
                        ClientWebManager.updateNodeAttributeLocally(sessionId, openSelectBaseId, "value", chosenVal, false);
                        ClientWebManager.processRenderQueue();
                        if (this.state != null) this.state.notifyElementModelChanged(openSelectBaseId, chosenVal);
                        if (onInputChanged != null) onInputChanged.accept(openSelectBaseId, chosenVal);
                    }
                    openSelectBaseId = "";
                    selectOptions.clear();
                    return true;
                }
                openSelectBaseId = "";
                selectOptions.clear();
            }

            ClientWebManager.UIHitResult hit = ClientWebManager.getHitElement(sessionId, webX, webY);
            UIElement clicked = hit != null ? hit.element() : null;

            if (clicked != null && "scene".equals(clicked.type())) {
                unfocusWebInput();
                String baseId = ClientWebManager.getBaseNodeId(clicked.id());
                if (button == 2) {
                    WebSceneContext.getOrCreate(sessionId, baseId).resetCamera();
                } else {
                    activeSceneId = baseId;
                    activeSceneMouseButton = button;
                }
                return true;
            }

            if (clicked != null && "textarea".equals(clicked.type()) && button == 0) {
                unfocusWebInput();
                String baseId = ClientWebManager.getBaseNodeId(clicked.id());
                setFocusedDomElement(baseId);
                focusWebTextarea(font, clicked, hit.localX(), hit.localY());
                return true;
            }

            if (clicked != null && "input".equals(clicked.type()) && button == 0) {
                String baseId = ClientWebManager.getBaseNodeId(clicked.id());
                setFocusedDomElement(baseId);
                String curVal = getInputValue(baseId);
                if (curVal.isEmpty() && clicked.text() != null) curVal = clicked.text();
                focusWebInput(screen, baseId, curVal);
                return true;
            } else {
                unfocusWebInput();
            }

            if (clicked != null) {
                String baseId = ClientWebManager.getBaseNodeId(clicked.id());
                setFocusedDomElement(baseId);
                WebRenderCore.activePressedId = clicked.id();

                HtmlNode domNode = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), baseId);

                HtmlNode summaryNode = findAncestorByTag(domNode, "summary");
                if (summaryNode != null && summaryNode.parent != null && "details".equalsIgnoreCase(summaryNode.parent.tag) && button == 0) {
                    ClientScreenInteractionManager.playElementSound(clicked);
                    ClientWebManager.toggleNodeAttributeLocally(sessionId, summaryNode.parent.getId(), "open");
                    ClientWebManager.processRenderQueue();
                    return true;
                }

                HtmlNode labelNode = findAncestorByTag(domNode, "label");
                if (labelNode != null && labelNode.attrs.containsKey("for") && button == 0) {
                    String targetInputId = labelNode.attrs.get("for").trim();
                    HtmlNode targetInput = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), targetInputId);
                    if (targetInput != null) {
                        ClientScreenInteractionManager.playElementSound(clicked);
                        setFocusedDomElement(targetInput.getId());
                        String tType = targetInput.attrs.getOrDefault("type", "text");
                        if ("checkbox".equalsIgnoreCase(tType) || "radio".equalsIgnoreCase(tType)) {
                            ClientWebManager.toggleNodeAttributeLocally(sessionId, targetInput.getId(), "checked");
                            ClientWebManager.processRenderQueue();
                            String checkedStr = Boolean.toString(targetInput.attrs.containsKey("checked"));
                            if (this.state != null) this.state.notifyElementModelChanged(targetInput.getId(), checkedStr);
                            if (onInputChanged != null) onInputChanged.accept(targetInput.getId(), checkedStr);
                        } else {
                            focusWebInput(screen, targetInput.getId(), getInputValue(targetInput.getId()));
                        }
                        return true;
                    }
                }

                boolean isSelectElement = "select".equals(clicked.type()) || (domNode != null && "select".equalsIgnoreCase(domNode.tag));
                if (isSelectElement && button == 0) {
                    ClientScreenInteractionManager.playElementSound(clicked);
                    openSelectDropdown(baseId, clicked);
                    return true;
                }

                if (("range".equals(clicked.type()) || "toggle".equals(clicked.type()) || "checkbox".equals(clicked.type())) && button == 0) {
                    ClientScreenInteractionManager.handleClick(sessionId, webX, webY);
                    if ("range".equals(clicked.type())) {
                        UIElement updated = ClientWebManager.getElementById(sessionId, baseId);
                        if (updated != null && updated.text() != null) {
                            String val = updated.text().split(":")[0];
                            if (this.state != null) this.state.notifyElementModelChanged(baseId, val);
                            if (onInputChanged != null) onInputChanged.accept(baseId, val);
                        }
                    } else {
                        HtmlNode node = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), baseId);
                        String checkedStr = Boolean.toString(node != null && node.attrs.containsKey("checked"));
                        if (this.state != null) this.state.notifyElementModelChanged(baseId, checkedStr);
                        if (onInputChanged != null) onInputChanged.accept(baseId, checkedStr);
                    }
                    return true;
                }

                if (button == 0 && clicked.events() != null && "true".equalsIgnoreCase(clicked.events().get("draggable"))) {
                    draggingElementId = baseId;
                    draggingLabel = (domNode != null && !domNode.getTextContent().isEmpty()) ? domNode.getTextContent().trim() : baseId;
                }

                ClientScreenInteractionManager.handleClick(sessionId, webX, webY);
                String action = clicked.events() != null ? clicked.events().get("onclick") : null;
                if ((action == null || action.equals(baseId)) && domNode != null && "button".equalsIgnoreCase(domNode.tag)) {
                    String btnType = domNode.attrs.getOrDefault("type", "").toLowerCase(Locale.ROOT);
                    HtmlNode parentForm = findAncestorByTag(domNode, "form");
                    if (parentForm != null) {
                        if ("submit".equals(btnType) && parentForm.attrs.containsKey("onsubmit")) {
                            action = parentForm.attrs.get("onsubmit");
                        } else if ("reset".equals(btnType) && parentForm.attrs.containsKey("onreset")) {
                            action = parentForm.attrs.get("onreset");
                        }
                    }
                }
                if (action != null) {
                    if (button == 0 && dispatchRoutedAction(action)) {
                        return true;
                    }
                    if (onWebAction != null) onWebAction.onAction(action, clicked, button);
                    return true;
                }
            } else {
                setFocusedDomElement("");
            }
            return false;
        }

        private static HtmlNode findAncestorByTag(HtmlNode start, String tag) {
            HtmlNode cur = start;
            while (cur != null) {
                if (tag.equalsIgnoreCase(cur.tag)) return cur;
                cur = cur.parent;
            }
            return null;
        }

        private void openSelectDropdown(String baseId, UIElement selectEl) {
            if (baseId.equals(openSelectBaseId)) {
                openSelectBaseId = "";
                selectOptions.clear();
                return;
            }
            HtmlNode selectNode = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), baseId);
            if (selectNode == null) return;
            selectOptions.clear();
            for (HtmlNode child : selectNode.children) {
                if ("option".equalsIgnoreCase(child.tag)) {
                    String val = child.attrs.getOrDefault("value", child.getTextContent().trim());
                    String label = child.getTextContent().trim();
                    selectOptions.add(new SelectOption(val, label.isEmpty() ? val : label));
                }
            }
            if (!selectOptions.isEmpty()) {
                openSelectBaseId = baseId;
                selectPopupX = selectEl.x();
                selectPopupY = selectEl.y() + selectEl.height() + 2;
                selectPopupW = Math.max(selectEl.width(), 90);
            }
        }

        public boolean handleMouseDragged(double webX, double webY, double dragX, double dragY, float guiScaleFactor, BiConsumer<String, String> onInputChanged) {
            if (!activeSceneId.isEmpty()) {
                WebSceneContext scene = WebSceneContext.getOrCreate(sessionId, activeSceneId);
                if (activeSceneMouseButton == 0) {
                    scene.addRotation((float) (dragY * 0.8), (float) (dragX * 0.8));
                } else if (activeSceneMouseButton == 1) {
                    scene.addPan((float) (dragX / guiScaleFactor), (float) (dragY / guiScaleFactor));
                }
                return true;
            }

            if (ClientScreenInteractionManager.handleDrag(sessionId, webX, webY)) {
                if (!WebRenderCore.activePressedId.isEmpty()) {
                    String baseId = ClientWebManager.getBaseNodeId(WebRenderCore.activePressedId);
                    UIElement updated = ClientWebManager.getElementById(sessionId, baseId);
                    if (updated != null && "range".equals(updated.type()) && updated.text() != null) {
                        String val = updated.text().split(":")[0];
                        if (this.state != null) this.state.notifyElementModelChanged(baseId, val);
                        if (onInputChanged != null) onInputChanged.accept(baseId, val);
                    }
                }
                return true;
            }
            return false;
        }

        public void handleMouseReleased(double webX, double webY, DropCallback onWebDrop) {
            activeSceneId = "";
            WebRenderCore.activePressedId = "";
            ClientScreenInteractionManager.handleRelease(sessionId);

            if (!draggingElementId.isEmpty()) {
                ClientWebManager.UIHitResult hit = ClientWebManager.getHitElement(sessionId, webX, webY);
                if (hit != null && hit.element() != null) {
                    String targetBaseId = ClientWebManager.getBaseNodeId(hit.element().id());
                    String dropAction = hit.element().events() != null ? hit.element().events().getOrDefault("ondrop", "") : "";
                    if (!targetBaseId.equals(draggingElementId) && onWebDrop != null) {
                        onWebDrop.onDrop(draggingElementId, targetBaseId, dropAction);
                    }
                }
                draggingElementId = "";
                draggingLabel = "";
            }
        }

        public boolean handleMouseScrolled(double webX, double webY, double scrollX, double scrollY) {
            ClientWebManager.UIHitResult hit = ClientWebManager.getHitElement(sessionId, webX, webY);
            if (hit != null && hit.element() != null) {
                if ("scene".equals(hit.element().type())) {
                    String baseId = ClientWebManager.getBaseNodeId(hit.element().id());
                    WebSceneContext.getOrCreate(sessionId, baseId).addZoom((float) (scrollY * 0.12));
                    return true;
                }
                if ("textarea".equals(hit.element().type())) {
                    String taId = hit.element().id();
                    int curScroll = WebRenderCore.textareaScrollRow.getOrDefault(taId, 0);
                    int nextScroll = Math.max(0, curScroll - (int) Math.signum(scrollY) * 3);
                    WebRenderCore.textareaScrollRow.put(taId, nextScroll);
                    return true;
                }
            }
            return ClientWebManager.handleScrollLocally(sessionId, webX, webY, scrollX, scrollY);
        }

        public boolean handleKeyPressed(
                int keyCode, int scanCode, int modifiers, boolean blockInventoryKey,
                BiConsumer<String, String> onInputChanged,
                BiConsumer<String, String> onInputSubmitted
        ) {
            return handleKeyPressed(Minecraft.getInstance().screen, Minecraft.getInstance().font, keyCode, scanCode, modifiers, blockInventoryKey, onInputChanged, onInputSubmitted, null);
        }

        public boolean handleKeyPressed(
                Screen screen, Font font,
                int keyCode, int scanCode, int modifiers, boolean blockInventoryKey,
                BiConsumer<String, String> onInputChanged,
                BiConsumer<String, String> onInputSubmitted,
                TriActionCallback onWebAction
        ) {
            if (keyCode == GLFW.GLFW_KEY_TAB && !isEditingTextarea) {
                boolean reverse = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0 || Screen.hasShiftDown();
                if (cycleSequentialFocus(screen, reverse)) {
                    return true;
                }
            }

            if (isEditingTextarea && !WebRenderCore.activeInputId.isEmpty()) {
                if (handleTextareaKeyPressed(keyCode, onInputChanged)) return true;
                if (blockInventoryKey && Minecraft.getInstance().options.keyInventory.matches(keyCode, scanCode)) return true;
            } else if (!WebRenderCore.activeInputId.isEmpty()) {
                String baseId = ClientWebManager.getBaseNodeId(WebRenderCore.activeInputId);
                if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                    unfocusWebInput();
                    return true;
                }
                if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                    String val = this.hiddenEditBox != null ? this.hiddenEditBox.getValue() : "";
                    syncInputValueToDomNodes(baseId, val);
                    ClientScreenInteractionManager.submitInput(sessionId, WebRenderCore.activeInputId, val);
                    if (onInputSubmitted != null) onInputSubmitted.accept(baseId, val);
                    return true;
                }
                if (this.hiddenEditBox != null && this.hiddenEditBox.keyPressed(keyCode, scanCode, modifiers)) return true;
                if (blockInventoryKey && Minecraft.getInstance().options.keyInventory.matches(keyCode, scanCode)) return true;
            } else {
                if (!focusedElementBaseId.isEmpty()) {
                    if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT) {
                        if (adjustFocusedRangeSlider(keyCode == GLFW.GLFW_KEY_RIGHT ? 1 : -1, onInputChanged)) {
                            return true;
                        }
                    }
                    if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_SPACE) {
                        if (activateFocusedElement(screen, font, onInputChanged, onWebAction)) {
                            return true;
                        }
                    }
                }
                if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN
                        || keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT) {
                    if (navigateSpatialFocus(screen, keyCode)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private void setFocusedDomElement(String nextBaseId) {
            String safeNext = nextBaseId != null ? nextBaseId : "";
            if (Objects.equals(this.focusedElementBaseId, safeNext)) return;

            HtmlNode vRoot = ClientWebManager.getVirtualRoot(sessionId);
            if (vRoot != null) {
                ClientWebManager.requestRender(sessionId);
            }
            this.focusedElementBaseId = safeNext;
        }

        private List<FocusCandidate> collectFocusableCandidates() {
            HtmlNode vRoot = ClientWebManager.getVirtualRoot(sessionId);
            List<UIElement> elements = ClientWebManager.getElements(sessionId);
            if (vRoot == null || elements == null || elements.isEmpty()) return Collections.emptyList();

            Map<String, UIElement> boxByNodeId = new HashMap<>();
            for (UIElement el : elements) {
                String baseId = ClientWebManager.getBaseNodeId(el.id());
                if ("node".equals(el.type())) {
                    boxByNodeId.put(el.id(), el);
                } else {
                    boxByNodeId.putIfAbsent(baseId, el);
                }
            }

            List<FocusCandidate> list = new ArrayList<>();
            int[] orderCounter = new int[]{0};
            collectFocusablesRecursive(vRoot, boxByNodeId, list, orderCounter);

            list.sort((a, b) -> {
                boolean aPos = a.tabIndex() > 0;
                boolean bPos = b.tabIndex() > 0;
                if (aPos && bPos) {
                    int cmp = Integer.compare(a.tabIndex(), b.tabIndex());
                    if (cmp != 0) return cmp;
                } else if (aPos != bPos) {
                    return aPos ? -1 : 1;
                }
                return Integer.compare(a.domOrder(), b.domOrder());
            });
            return list;
        }

        private void collectFocusablesRecursive(HtmlNode node, Map<String, UIElement> boxByNodeId, List<FocusCandidate> out, int[] orderCounter) {
            if (node == null || node.attrs.containsKey("hidden") || node.attrs.containsKey("disabled")) return;

            String styleAttr = node.attrs.getOrDefault("style", "").replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
            if (styleAttr.contains("display:none")) return;

            int tabIndex = 0;
            boolean hasExplicitTabIndex = false;
            if (node.attrs.containsKey("tabindex")) {
                try {
                    tabIndex = Integer.parseInt(node.attrs.get("tabindex").trim());
                    hasExplicitTabIndex = true;
                } catch (NumberFormatException ignored) {}
            }

            String tag = node.tag != null ? node.tag.toLowerCase(Locale.ROOT) : "";
            boolean naturallyFocusable = "button".equals(tag) || "input".equals(tag) || "select".equals(tag)
                    || "textarea".equals(tag) || "summary".equals(tag)
                    || ("a".equals(tag) && (node.attrs.containsKey("href") || node.attrs.containsKey("onclick")))
                    || node.attrs.containsKey("onclick");

            if (tabIndex >= 0 && (hasExplicitTabIndex || naturallyFocusable)) {
                UIElement el = boxByNodeId.get(node.getId());
                if (el != null && el.width() > 0 && el.height() > 0) {
                    out.add(new FocusCandidate(node, el, tabIndex, orderCounter[0]++));
                }
            }

            boolean isClosedDetails = "details".equals(tag) && !node.attrs.containsKey("open");
            for (HtmlNode child : node.children) {
                if (isClosedDetails && !"summary".equalsIgnoreCase(child.tag)) continue;
                collectFocusablesRecursive(child, boxByNodeId, out, orderCounter);
            }
        }

        private boolean cycleSequentialFocus(Screen screen, boolean reverse) {
            List<FocusCandidate> candidates = collectFocusableCandidates();
            if (candidates.isEmpty()) return false;

            int curIdx = -1;
            for (int i = 0; i < candidates.size(); i++) {
                if (candidates.get(i).node().getId().equals(focusedElementBaseId)) {
                    curIdx = i;
                    break;
                }
            }

            int nextIdx;
            if (curIdx == -1) {
                nextIdx = reverse ? candidates.size() - 1 : 0;
            } else {
                nextIdx = (curIdx + (reverse ? -1 : 1) + candidates.size()) % candidates.size();
            }

            applyFocusCandidate(screen, candidates.get(nextIdx));
            return true;
        }

        private boolean navigateSpatialFocus(Screen screen, int keyCode) {
            List<FocusCandidate> candidates = collectFocusableCandidates();
            if (candidates.isEmpty()) return false;

            FocusCandidate current = null;
            for (FocusCandidate c : candidates) {
                if (c.node().getId().equals(focusedElementBaseId)) {
                    current = c;
                    break;
                }
            }
            if (current == null) {
                applyFocusCandidate(screen, candidates.getFirst());
                return true;
            }

            double curCx = current.element().x() + current.element().width() / 2.0;
            double curCy = current.element().y() + current.element().height() / 2.0;

            FocusCandidate best = null;
            double bestScore = Double.MAX_VALUE;

            for (FocusCandidate cand : candidates) {
                if (cand == current) continue;
                double cx = cand.element().x() + cand.element().width() / 2.0;
                double cy = cand.element().y() + cand.element().height() / 2.0;
                double dx = cx - curCx;
                double dy = cy - curCy;

                boolean inDirection = switch (keyCode) {
                    case GLFW.GLFW_KEY_UP -> dy < -2;
                    case GLFW.GLFW_KEY_DOWN -> dy > 2;
                    case GLFW.GLFW_KEY_LEFT -> dx < -2;
                    case GLFW.GLFW_KEY_RIGHT -> dx > 2;
                    default -> false;
                };
                if (!inDirection) continue;

                double primaryDist = (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) ? Math.abs(dy) : Math.abs(dx);
                double orthoDist = (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) ? Math.abs(dx) : Math.abs(dy);
                double score = primaryDist + orthoDist * 2.5;
                if (score < bestScore) {
                    bestScore = score;
                    best = cand;
                }
            }

            if (best != null) {
                applyFocusCandidate(screen, best);
                return true;
            }
            return false;
        }

        private void applyFocusCandidate(Screen screen, FocusCandidate candidate) {
            unfocusWebInput();
            String baseId = candidate.node().getId();
            setFocusedDomElement(baseId);
            ClientWebManager.processRenderQueue();

            HtmlNode node = candidate.node();
            if ("input".equalsIgnoreCase(node.tag)) {
                String inputType = node.attrs.getOrDefault("type", "text").toLowerCase(Locale.ROOT);
                if (!"checkbox".equals(inputType) && !"radio".equals(inputType) && !"range".equals(inputType) && !"color".equals(inputType)) {
                    if (screen != null) {
                        focusWebInput(screen, baseId, getInputValue(baseId));
                    }
                }
            }
        }

        private boolean adjustFocusedRangeSlider(int dir, BiConsumer<String, String> onInputChanged) {
            HtmlNode node = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), focusedElementBaseId);
            if (node == null || !"input".equalsIgnoreCase(node.tag)) return false;
            if (!"range".equalsIgnoreCase(node.attrs.getOrDefault("type", ""))) return false;

            double min = 0, max = 100, step = 1, cur = 50;
            try { min = Double.parseDouble(node.attrs.getOrDefault("min", "0")); } catch (Exception ignored) {}
            try { max = Double.parseDouble(node.attrs.getOrDefault("max", "100")); } catch (Exception ignored) {}
            try { step = Double.parseDouble(node.attrs.getOrDefault("step", String.valueOf((max - min) / 20.0))); } catch (Exception ignored) {}
            try { cur = Double.parseDouble(node.attrs.getOrDefault("value", String.valueOf(min))); } catch (Exception ignored) {}
            if (step <= 0) step = 1;

            double next = Math.clamp(cur + dir * step, min, max);
            String formatted = (step == Math.floor(step) && min == Math.floor(min))
                    ? String.valueOf(Math.round(next))
                    : String.format(Locale.ROOT, "%.2f", next);

            ClientWebManager.updateNodeAttributeLocally(sessionId, focusedElementBaseId, "value", formatted, false);
            ClientWebManager.processRenderQueue();
            if (this.state != null) this.state.notifyElementModelChanged(focusedElementBaseId, formatted);
            if (onInputChanged != null) onInputChanged.accept(focusedElementBaseId, formatted);
            return true;
        }

        private boolean activateFocusedElement(
                Screen screen, Font font,
                BiConsumer<String, String> onInputChanged,
                TriActionCallback onWebAction
        ) {
            UIElement el = ClientWebManager.getElementById(sessionId, focusedElementBaseId);
            if (el == null) return false;
            double cx = el.x() + el.width() / 2.0;
            double cy = el.y() + el.height() / 2.0;
            return handleMouseClicked(screen, font, cx, cy, 0, onInputChanged, onWebAction);
        }

        public boolean handleCharTyped(char codePoint, int modifiers, BiConsumer<String, String> onInputChanged) {
            if (isEditingTextarea && !WebRenderCore.activeInputId.isEmpty()) {
                if (codePoint >= 32 && codePoint != 127) {
                    insertIntoTextarea(String.valueOf(codePoint), onInputChanged);
                    return true;
                }
            } else if (!WebRenderCore.activeInputId.isEmpty()) {
                if (this.hiddenEditBox != null && this.hiddenEditBox.charTyped(codePoint, modifiers)) return true;
            }
            return false;
        }

        public void focusWebTextarea(Font font, UIElement taEl, double localX, double localY) {
            this.isEditingTextarea = true;
            this.activeTextareaBaseId = ClientWebManager.getBaseNodeId(taEl.id());
            String renderTaId = taEl.id().endsWith("_textarea") ? taEl.id() : this.activeTextareaBaseId + "_textarea";
            WebRenderCore.activeInputId = renderTaId;
            if (this.hiddenEditBox != null) this.hiddenEditBox.setFocused(false);

            String fullText = getTextareaValue(activeTextareaBaseId);
            if (localX < 0 || localY < 0) {
                WebRenderCore.textareaCursorIndex.put(renderTaId, fullText.length());
                return;
            }

            String[] lines = fullText.split("\n", -1);
            int scrollRow = WebRenderCore.textareaScrollRow.getOrDefault(renderTaId, 0);
            int clickedRow = Math.clamp(scrollRow + (int) ((localY - 4.0) / 11.0), 0, Math.max(0, lines.length - 1));

            String syntax = taEl.events() != null ? taEl.events().getOrDefault("data-syntax", "") : "";
            boolean showLineNums = "lua".equalsIgnoreCase(syntax) || (taEl.events() != null && "true".equalsIgnoreCase(taEl.events().get("data-line-numbers")));
            float gutterW = showLineNums ? 28.0f : 4.0f;

            String lineStr = lines[clickedRow];
            int clickedCol = font.plainSubstrByWidth(lineStr, Math.max(0, (int) (localX - gutterW))).length();

            int index = 0;
            for (int i = 0; i < clickedRow; i++) index += lines[i].length() + 1;
            index += Math.min(clickedCol, lineStr.length());
            WebRenderCore.textareaCursorIndex.put(renderTaId, index);
        }

        private boolean handleTextareaKeyPressed(int keyCode, BiConsumer<String, String> onInputChanged) {
            String renderId = WebRenderCore.activeInputId;
            String text = getTextareaValue(activeTextareaBaseId);
            int cursor = Math.clamp(WebRenderCore.textareaCursorIndex.getOrDefault(renderId, text.length()), 0, text.length());

            switch (keyCode) {
                case GLFW.GLFW_KEY_ESCAPE -> {
                    unfocusWebInput();
                    return true;
                }
                case GLFW.GLFW_KEY_LEFT -> {
                    if (cursor > 0) WebRenderCore.textareaCursorIndex.put(renderId, cursor - 1);
                    return true;
                }
                case GLFW.GLFW_KEY_RIGHT -> {
                    if (cursor < text.length()) WebRenderCore.textareaCursorIndex.put(renderId, cursor + 1);
                    return true;
                }
                case GLFW.GLFW_KEY_HOME -> {
                    int lineStart = text.lastIndexOf('\n', Math.max(0, cursor - 1)) + 1;
                    WebRenderCore.textareaCursorIndex.put(renderId, lineStart);
                    return true;
                }
                case GLFW.GLFW_KEY_END -> {
                    int lineEnd = text.indexOf('\n', cursor);
                    WebRenderCore.textareaCursorIndex.put(renderId, lineEnd == -1 ? text.length() : lineEnd);
                    return true;
                }
                case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN -> {
                    String[] lines = text.split("\n", -1);
                    int row = 0, col = 0, acc = 0;
                    for (int i = 0; i < lines.length; i++) {
                        if (cursor <= acc + lines[i].length()) { row = i; col = cursor - acc; break; }
                        acc += lines[i].length() + 1;
                    }
                    int targetRow = keyCode == GLFW.GLFW_KEY_UP ? row - 1 : row + 1;
                    if (targetRow >= 0 && targetRow < lines.length) {
                        int newCursor = 0;
                        for (int i = 0; i < targetRow; i++) newCursor += lines[i].length() + 1;
                        newCursor += Math.min(col, lines[targetRow].length());
                        WebRenderCore.textareaCursorIndex.put(renderId, newCursor);
                    }
                    return true;
                }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    int lineStart = text.lastIndexOf('\n', Math.max(0, cursor - 1)) + 1;
                    String currentLine = text.substring(lineStart, cursor);
                    int indentLen = 0;
                    while (indentLen < currentLine.length() && currentLine.charAt(indentLen) == ' ') indentLen++;
                    insertIntoTextarea("\n" + " ".repeat(indentLen), onInputChanged);
                    return true;
                }
                case GLFW.GLFW_KEY_TAB -> {
                    insertIntoTextarea("  ", onInputChanged);
                    return true;
                }
                case GLFW.GLFW_KEY_BACKSPACE -> {
                    if (cursor > 0) {
                        String next = text.substring(0, cursor - 1) + text.substring(cursor);
                        WebRenderCore.textareaCursorIndex.put(renderId, cursor - 1);
                        setTextareaValue(activeTextareaBaseId, next);
                        if (this.state != null) this.state.notifyElementModelChanged(activeTextareaBaseId, next);
                        if (onInputChanged != null) onInputChanged.accept(activeTextareaBaseId, next);
                    }
                    return true;
                }
                case GLFW.GLFW_KEY_DELETE -> {
                    if (cursor < text.length()) {
                        String next = text.substring(0, cursor) + text.substring(cursor + 1);
                        setTextareaValue(activeTextareaBaseId, next);
                        if (this.state != null) this.state.notifyElementModelChanged(activeTextareaBaseId, next);
                        if (onInputChanged != null) onInputChanged.accept(activeTextareaBaseId, next);
                    }
                    return true;
                }
                case GLFW.GLFW_KEY_V -> {
                    if (Screen.hasControlDown()) {
                        String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
                        if (clip != null && !clip.isEmpty()) {
                            insertIntoTextarea(clip.replace("\r\n", "\n"), onInputChanged);
                        }
                        return true;
                    }
                }
            }
            return false;
        }

        private void insertIntoTextarea(String insertion, BiConsumer<String, String> onInputChanged) {
            String renderId = WebRenderCore.activeInputId;
            String text = getTextareaValue(activeTextareaBaseId);
            int cursor = Math.clamp(WebRenderCore.textareaCursorIndex.getOrDefault(renderId, text.length()), 0, text.length());
            String next = text.substring(0, cursor) + insertion + text.substring(cursor);
            WebRenderCore.textareaCursorIndex.put(renderId, cursor + insertion.length());
            setTextareaValue(activeTextareaBaseId, next);
            if (this.state != null) this.state.notifyElementModelChanged(activeTextareaBaseId, next);
            if (onInputChanged != null) onInputChanged.accept(activeTextareaBaseId, next);
        }

        public void setTextareaValue(String id, String value) {
            String safeVal = value != null ? value.replace("\r\n", "\n") : "";
            String baseId = ClientWebManager.getBaseNodeId(id);
            HtmlNode vNode = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), baseId);
            if (vNode != null) vNode.attrs.put("value", safeVal);
            HtmlNode tNode = DomUtils.findNodeById(ClientWebManager.getTemplateRoot(sessionId), baseId);
            if (tNode != null) tNode.attrs.put("value", safeVal);
            ClientScreenInteractionManager.updateInputLocally(sessionId, baseId + "_textarea", safeVal);
        }

        public String getTextareaValue(String id) {
            String baseId = ClientWebManager.getBaseNodeId(id);
            HtmlNode node = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), baseId);
            if (node == null) return "";
            return node.attrs.getOrDefault("value", node.getTextContent());
        }

        public void syncInputValueToDomNodes(String baseId, String safeVal) {
            String cleanVal = safeVal != null ? safeVal : "";
            HtmlNode vNode = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), baseId);
            if (vNode != null) vNode.attrs.put("value", cleanVal);
            HtmlNode tNode = DomUtils.findNodeById(ClientWebManager.getTemplateRoot(sessionId), baseId);
            if (tNode != null) tNode.attrs.put("value", cleanVal);
            ClientScreenInteractionManager.updateInputLocally(sessionId, baseId + "_input", cleanVal);
        }

        public void focusWebInput(Screen screen, String inputElementId, String currentText) {
            if (this.isEditingTextarea && !this.activeTextareaBaseId.isEmpty()) {
                unfocusWebInput();
            }
            this.isEditingTextarea = false;
            this.activeTextareaBaseId = "";
            String baseId = ClientWebManager.getBaseNodeId(inputElementId);
            setFocusedDomElement(baseId);
            String renderInputId = baseId + "_input";
            String safeVal = currentText != null ? currentText : "";

            this.suppressInputResponder = true;
            try {
                WebRenderCore.activeInputId = renderInputId;
                syncInputValueToDomNodes(baseId, safeVal);
                if (this.hiddenEditBox != null) {
                    this.hiddenEditBox.setValue(safeVal);
                    this.hiddenEditBox.moveCursorToEnd(false);
                    if (screen != null) screen.setFocused(this.hiddenEditBox);
                    this.hiddenEditBox.setFocused(true);
                }
            } finally {
                this.suppressInputResponder = false;
            }
        }

        public void unfocusWebInput() {
            if (!WebRenderCore.activeInputId.isEmpty()) {
                String activeId = WebRenderCore.activeInputId;
                String baseId = ClientWebManager.getBaseNodeId(activeId);

                if (this.isEditingTextarea || activeId.endsWith("_textarea")) {
                    String taBaseId = !this.activeTextareaBaseId.isEmpty() ? this.activeTextareaBaseId : baseId;
                    String finalTaVal = getTextareaValue(taBaseId);
                    setTextareaValue(taBaseId, finalTaVal);
                    ClientScreenInteractionManager.submitInput(sessionId, activeId, finalTaVal);
                } else {
                    String finalVal = this.hiddenEditBox != null ? this.hiddenEditBox.getValue() : getInputValue(baseId);
                    syncInputValueToDomNodes(baseId, finalVal);
                    ClientScreenInteractionManager.submitInput(sessionId, activeId, finalVal);
                }
            }
            this.isEditingTextarea = false;
            this.activeTextareaBaseId = "";
            WebRenderCore.activeInputId = "";
            if (this.hiddenEditBox != null) {
                this.hiddenEditBox.setFocused(false);
            }
        }

        public boolean isInputActive() {
            return !WebRenderCore.activeInputId.isEmpty();
        }

        public void setElementText(String id, String text) {
            HtmlNode root = ClientWebManager.getVirtualRoot(sessionId);
            if (root == null) return;
            HtmlNode target = DomUtils.findNodeById(root, id);
            if (target != null) {
                target.setTextContent(text);
                HtmlNode tNode = DomUtils.findNodeById(ClientWebManager.getTemplateRoot(sessionId), id);
                if (tNode != null) tNode.setTextContent(text);
                ClientWebManager.requestRender(sessionId);
            }
        }

        public void setInputValue(String id, String value) {
            String baseId = ClientWebManager.getBaseNodeId(id);
            String safeVal = value != null ? value : "";
            syncInputValueToDomNodes(baseId, safeVal);
            if (!WebRenderCore.activeInputId.isEmpty() && !isEditingTextarea && ClientWebManager.getBaseNodeId(WebRenderCore.activeInputId).equals(baseId)) {
                if (this.hiddenEditBox != null && !Objects.equals(this.hiddenEditBox.getValue(), safeVal)) {
                    this.suppressInputResponder = true;
                    try {
                        this.hiddenEditBox.setValue(safeVal);
                    } finally {
                        this.suppressInputResponder = false;
                    }
                }
            }
        }

        public String getInputValue(String id) {
            String baseId = ClientWebManager.getBaseNodeId(id);
            if (!WebRenderCore.activeInputId.isEmpty() && !isEditingTextarea && ClientWebManager.getBaseNodeId(WebRenderCore.activeInputId).equals(baseId)) {
                if (this.hiddenEditBox != null) return this.hiddenEditBox.getValue();
            }
            HtmlNode node = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), baseId);
            return node != null ? node.attrs.getOrDefault("value", "") : "";
        }

        public void addClass(String id, String cls) {
            HtmlNode root = ClientWebManager.getVirtualRoot(sessionId);
            if (root == null) return;
            HtmlNode target = DomUtils.findNodeById(root, id);
            if (target != null && !target.hasClass(cls)) {
                target.addClass(cls);
                ClientWebManager.requestRender(sessionId);
            }
        }

        public void removeClass(String id, String cls) {
            HtmlNode root = ClientWebManager.getVirtualRoot(sessionId);
            if (root == null) return;
            HtmlNode target = DomUtils.findNodeById(root, id);
            if (target != null && target.hasClass(cls)) {
                target.removeClass(cls);
                ClientWebManager.requestRender(sessionId);
            }
        }

        public void toggleClass(String id, String cls, boolean enable) {
            if (enable) addClass(id, cls);
            else removeClass(id, cls);
        }

        public void onClose() {
            unfocusWebInput();
            setFocusedDomElement("");
            activeSceneId = "";
            openSelectBaseId = "";
            selectOptions.clear();
            draggingElementId = "";
            draggingLabel = "";
            WebRenderCore.activePressedId = "";
        }
    }
}