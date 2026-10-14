package com.nishiyu.enderframelib.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.renderer.WebRenderCore;
import com.nishiyu.enderframelib.core.dom.DomUtils;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.model.UIElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Objects;

/**
 * AbstractContainerMenu を持つ画面を HTML/CSS で構築するための汎用基底クラス。
 * フルスクリーン・レスポンシブ表示と、マイクラ標準スロットと1:1で重なるコンテナ表示の両方をサポートする。
 */
public abstract class AbstractWebContainerScreen<T extends AbstractContainerMenu> extends AbstractContainerScreen<T> {

    protected final String sessionId;
    protected int virtualWidth;
    protected int virtualHeight;
    protected boolean fullscreenResponsive;
    protected float viewportMarginRatio = 0.94f;
    protected float guiScaleFactor = 1.0f;
    protected float offsetX = 0.0f;
    protected float offsetY = 0.0f;
    protected EditBox hiddenEditBox;
    protected String hoveredId = "";

    /**
     * フルスクリーン・レスポンシブモード用コンストラクタ（IDE画面など）
     */
    public AbstractWebContainerScreen(T menu, Inventory playerInventory, Component title, String sessionId) {
        this(menu, playerInventory, title, sessionId, 0, 0);
    }

    /**
     * 固定サイズ（スロット付きインベントリ等）または指定仮想解像度用コンストラクタ。
     * virtualWidth / virtualHeight に 0 以下を渡すとフルスクリーン・レスポンシブモードになる。
     */
    public AbstractWebContainerScreen(T menu, Inventory playerInventory, Component title, String sessionId, int virtualWidth, int virtualHeight) {
        super(menu, playerInventory, title);
        this.sessionId = sessionId;
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

    @Override
    protected void init() {
        super.init();
        this.titleLabelX = 10000;
        this.inventoryLabelY = 10000;

        this.hiddenEditBox = new EditBox(this.font, -2000, -2000, 400, 20, Component.empty());
        this.hiddenEditBox.setMaxLength(2048);
        this.hiddenEditBox.setResponder(newText -> {
            if (!WebRenderCore.activeInputId.isEmpty()) {
                String baseId = ClientWebManager.getBaseNodeId(WebRenderCore.activeInputId);
                ClientWebManager.updateElementTextLocally(sessionId, WebRenderCore.activeInputId, newText);
                ClientWebManager.updateNodeAttributeLocally(sessionId, baseId, "value", newText, false);
                onWebInputChanged(baseId, newText);
            }
        });
        this.addWidget(this.hiddenEditBox);

        updateViewportTransform();
        String initialHtml = buildInitialHtml();
        if (initialHtml != null && !initialHtml.isEmpty()) {
            ClientWebManager.loadLocalHtml(this.sessionId, initialHtml, this.virtualWidth, this.virtualHeight);
        }
    }

    /**
     * 初期ロードするHTML文字列を返す（AbstractWebScreen.loadHtmlResource 等を利用可能）
     */
    protected abstract String buildInitialHtml();

    /**
     * HTML要素の onclick アクションが発火した際に呼ばれるハンドラ
     */
    protected void onWebAction(String action, UIElement clickedElement, int mouseButton) {}

    /**
     * <input> のテキストが変更された際にリアルタイムで呼ばれるハンドラ
     */
    protected void onWebInputChanged(String elementId, String newText) {}

    /**
     * <input> で Enter キーが押された際に呼ばれるハンドラ
     */
    protected void onWebInputSubmitted(String elementId, String text) {}

    protected void updateViewportTransform() {
        if (this.fullscreenResponsive) {
            int targetH = 480;
            float aspect = (this.height > 0) ? ((float) this.width / (float) this.height) : (16.0f / 9.0f);
            int targetW = Math.max(320, Math.round(targetH * aspect));

            if (this.virtualWidth != targetW || this.virtualHeight != targetH) {
                this.virtualWidth = targetW;
                this.virtualHeight = targetH;
                if (ClientWebManager.hasDocument(sessionId)) {
                    ClientWebManager.recomputeLayout(sessionId, this.virtualWidth, this.virtualHeight);
                }
            }

            float scaleX = (this.width * viewportMarginRatio) / virtualWidth;
            float scaleY = (this.height * viewportMarginRatio) / virtualHeight;
            this.guiScaleFactor = Math.min(scaleX, scaleY);
            this.offsetX = Math.round((this.width - virtualWidth * guiScaleFactor) / 2.0f);
            this.offsetY = Math.round((this.height - virtualHeight * guiScaleFactor) / 2.0f);
            this.leftPos = (int) this.offsetX;
            this.topPos = (int) this.offsetY;
        } else {
            // スロット付きコンテナ画面など、固定サイズ1:1モード
            this.leftPos = (this.width - this.imageWidth) / 2;
            this.topPos = (this.height - this.imageHeight) / 2;
            this.guiScaleFactor = (float) this.imageWidth / (float) this.virtualWidth;
            this.offsetX = this.leftPos;
            this.offsetY = this.topPos;
        }
    }

    protected double toWebX(double mouseX) {
        return (mouseX - offsetX) / guiScaleFactor;
    }

    protected double toWebY(double mouseY) {
        return (mouseY - offsetY) / guiScaleFactor;
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        ClientWebManager.processRenderQueue();
        updateViewportTransform();

        double webX = toWebX(mouseX);
        double webY = toWebY(mouseY);

        ClientWebManager.UIHitResult hit = ClientWebManager.getHitElement(sessionId, webX, webY);
        String newHover = (hit != null && hit.element() != null) ? ClientWebManager.getBaseNodeId(hit.element().id()) : "";
        if (!Objects.equals(hoveredId, newHover)) {
            if (!hoveredId.isEmpty()) ClientWebManager.updateNodeAttributeLocally(sessionId, hoveredId, "hover", "", true);
            if (!newHover.isEmpty()) ClientWebManager.updateNodeAttributeLocally(sessionId, newHover, "hover", "true", false);
            hoveredId = newHover;
        }

        List<UIElement> elements = ClientWebManager.getElements(sessionId);
        if (elements != null && !elements.isEmpty()) {
            PoseStack poseStack = guiGraphics.pose();
            poseStack.pushPose();
            poseStack.translate(offsetX, offsetY, 0);
            poseStack.scale(guiScaleFactor, guiScaleFactor, 1.0f);

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
            poseStack.popPose();
        }
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {}

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double webX = toWebX(mouseX);
        double webY = toWebY(mouseY);

        ClientWebManager.UIHitResult hit = ClientWebManager.getHitElement(sessionId, webX, webY);
        UIElement clicked = hit != null ? hit.element() : null;

        if (clicked != null && "input".equals(clicked.type()) && button == 0) {
            focusWebInput(clicked.id(), clicked.text() != null ? clicked.text() : "");
            return true;
        } else {
            unfocusWebInput();
        }

        if (clicked != null) {
            WebRenderCore.activePressedId = clicked.id();
            String action = clicked.events() != null ? clicked.events().get("onclick") : null;
            if (action != null) {
                onWebAction(action, clicked, button);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        WebRenderCore.activePressedId = "";
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (ClientWebManager.handleScrollLocally(sessionId, toWebX(mouseX), toWebY(mouseY), scrollX, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!WebRenderCore.activeInputId.isEmpty()) {
            String baseId = ClientWebManager.getBaseNodeId(WebRenderCore.activeInputId);
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                onWebInputSubmitted(baseId, this.hiddenEditBox.getValue());
                return true;
            }
            if (this.hiddenEditBox.keyPressed(keyCode, scanCode, modifiers)) return true;
            if (Objects.requireNonNull(this.minecraft).options.keyInventory.matches(keyCode, scanCode)) return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (!WebRenderCore.activeInputId.isEmpty()) {
            if (this.hiddenEditBox.charTyped(codePoint, modifiers)) return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    public void focusWebInput(String inputElementId, String currentText) {
        String renderInputId = inputElementId.endsWith("_input") ? inputElementId : inputElementId + "_input";
        WebRenderCore.activeInputId = renderInputId;
        this.hiddenEditBox.setValue(currentText != null ? currentText : "");
        this.setFocused(this.hiddenEditBox);
        this.hiddenEditBox.setFocused(true);
    }

    public void unfocusWebInput() {
        if (!WebRenderCore.activeInputId.isEmpty()) {
            WebRenderCore.activeInputId = "";
            if (this.hiddenEditBox != null) this.hiddenEditBox.setFocused(false);
        }
    }

    // ==========================================
    // DOM 操作ショートカットメソッド群
    // ==========================================
    public void setElementText(String id, String text) {
        HtmlNode root = ClientWebManager.getVirtualRoot(sessionId);
        if (root == null) return;
        HtmlNode target = DomUtils.findNodeById(root, id);
        if (target != null) {
            target.setTextContent(text);
            ClientWebManager.requestRender(sessionId);
        }
    }

    public void setInputValue(String id, String value) {
        String safeVal = value != null ? value : "";
        ClientWebManager.updateNodeAttributeLocally(sessionId, id, "value", safeVal, false);
        ClientWebManager.updateElementTextLocally(sessionId, id + "_input", safeVal);
    }

    public String getInputValue(String id) {
        HtmlNode node = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), id);
        return node != null ? node.attrs.getOrDefault("value", "") : "";
    }

    public void setInnerHtml(String id, String html) {
        ClientWebManager.updateNodeInnerHtmlLocally(sessionId, id, html);
    }

    public void setStyle(String id, String prop, String val) {
        ClientWebManager.updateNodeStyleLocally(sessionId, id, prop, val);
    }

    public void setAttribute(String id, String attr, String val) {
        ClientWebManager.updateNodeAttributeLocally(sessionId, id, attr, val, false);
    }

    public void addClass(String id, String cls) {
        HtmlNode root = ClientWebManager.getVirtualRoot(sessionId);
        if (root == null) return;
        HtmlNode target = DomUtils.findNodeById(root, id);
        if (target != null && !target.classes.contains(cls)) {
            target.addClass(cls);
            ClientWebManager.requestRender(sessionId);
        }
    }

    public void removeClass(String id, String cls) {
        HtmlNode root = ClientWebManager.getVirtualRoot(sessionId);
        if (root == null) return;
        HtmlNode target = DomUtils.findNodeById(root, id);
        if (target != null && target.classes.contains(cls)) {
            target.removeClass(cls);
            ClientWebManager.requestRender(sessionId);
        }
    }

    public void toggleClass(String id, String cls, boolean enable) {
        if (enable) addClass(id, cls);
        else removeClass(id, cls);
    }

    @Override
    public void onClose() {
        unfocusWebInput();
        WebRenderCore.activePressedId = "";
        ClientWebManager.clearSession(this.sessionId);
        super.onClose();
    }
}