package com.nishiyu.enderframelib.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.renderer.WebRenderCore;
import com.nishiyu.enderframelib.core.model.UIElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * 2D GUIとしてHTML/CSS画面を描画・操作するための汎用Screen基底クラス
 */
public class AbstractWebScreen extends Screen {
    public static final float DEFAULT_VIRTUAL_WIDTH = 854.0f;
    public static final float DEFAULT_VIRTUAL_HEIGHT = 480.0f;

    protected final String sessionId;
    protected final int virtualWidth;
    protected final int virtualHeight;
    protected float guiScaleFactor = 1.0f;
    protected float offsetX = 0.0f;
    protected float offsetY = 0.0f;
    protected EditBox hiddenEditBox;

    public AbstractWebScreen(Component title, String sessionId) {
        this(title, sessionId, (int) DEFAULT_VIRTUAL_WIDTH, (int) DEFAULT_VIRTUAL_HEIGHT);
    }

    public AbstractWebScreen(Component title, String sessionId, int virtualWidth, int virtualHeight) {
        super(title);
        this.sessionId = sessionId;
        this.virtualWidth = virtualWidth;
        this.virtualHeight = virtualHeight;
    }

    @Override
    protected void init() {
        super.init();
        this.hiddenEditBox = new EditBox(this.font, -1000, -1000, 200, 20, Component.empty());
        this.hiddenEditBox.setMaxLength(1024);
        this.hiddenEditBox.setResponder(newText -> {
            if (!WebRenderCore.activeInputId.isEmpty()) {
                ClientScreenInteractionManager.updateInputLocally(sessionId, WebRenderCore.activeInputId, newText);
            }
        });
        this.addWidget(this.hiddenEditBox);

        ClientScreenInteractionManager.ensureSynced(sessionId);
        if (ClientWebManager.getLastRootW(sessionId) != virtualWidth || ClientWebManager.getLastRootH(sessionId) != virtualHeight) {
            ClientWebManager.recomputeLayout(sessionId, virtualWidth, virtualHeight);
        }
    }

    protected void updateViewportTransform() {
        float scaleX = (this.width * 0.9f) / virtualWidth;
        float scaleY = (this.height * 0.9f) / virtualHeight;
        this.guiScaleFactor = Math.min(scaleX, scaleY);
        this.offsetX = (this.width - virtualWidth * guiScaleFactor) / 2.0f;
        this.offsetY = (this.height - virtualHeight * guiScaleFactor) / 2.0f;
    }

    protected double toWebX(double mouseX) {
        return (mouseX - offsetX) / guiScaleFactor;
    }

    protected double toWebY(double mouseY) {
        return (mouseY - offsetY) / guiScaleFactor;
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        ClientWebManager.processRenderQueue();
        ClientScreenInteractionManager.ensureSynced(sessionId);
        updateViewportTransform();

        double webX = toWebX(mouseX);
        double webY = toWebY(mouseY);
        ClientScreenInteractionManager.handleHover(sessionId, webX, webY);

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
                    ClientScreenInteractionManager.getHoveredId(sessionId),
                    0x00000000, false,
                    Minecraft.getInstance().level,
                    true, true, sessionId,
                    null, null, 0
            );
            bufferSource.endBatch();
            poseStack.popPose();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double webX = toWebX(mouseX);
        double webY = toWebY(mouseY);
        UIElement clicked = ClientScreenInteractionManager.handleClick(sessionId, webX, webY);
        if (clicked != null && "input".equals(clicked.type())) {
            WebRenderCore.activeInputId = clicked.id();
            this.hiddenEditBox.setValue(clicked.text() != null ? clicked.text() : "");
            this.setFocused(this.hiddenEditBox);
            this.hiddenEditBox.setFocused(true);
            return true;
        } else {
            if (!WebRenderCore.activeInputId.isEmpty()) {
                ClientScreenInteractionManager.submitInput(sessionId, WebRenderCore.activeInputId, this.hiddenEditBox.getValue());
                WebRenderCore.activeInputId = "";
                this.hiddenEditBox.setFocused(false);
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (ClientScreenInteractionManager.handleDrag(sessionId, toWebX(mouseX), toWebY(mouseY))) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        ClientScreenInteractionManager.handleRelease(sessionId);
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (ClientScreenInteractionManager.handleScroll(sessionId, toWebX(mouseX), toWebY(mouseY), scrollX, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!WebRenderCore.activeInputId.isEmpty()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                ClientScreenInteractionManager.submitInput(sessionId, WebRenderCore.activeInputId, this.hiddenEditBox.getValue());
                WebRenderCore.activeInputId = "";
                this.hiddenEditBox.setFocused(false);
                return true;
            }
            if (this.hiddenEditBox.keyPressed(keyCode, scanCode, modifiers)) return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (!WebRenderCore.activeInputId.isEmpty()) {
            ClientScreenInteractionManager.submitInput(sessionId, WebRenderCore.activeInputId, this.hiddenEditBox.getValue());
            WebRenderCore.activeInputId = "";
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}