package com.nishiyu.enderframelib.client.gui;

import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.renderer.WebRenderCore;
import com.nishiyu.enderframelib.core.model.UIElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

public class InvisibleInputScreen extends Screen {
    private final String sessionId;
    private final UIElement targetElement;
    private final BlockPos blockPos;
    private EditBox hiddenEditBox;

    public InvisibleInputScreen(String sessionId, UIElement targetElement, BlockPos blockPos) {
        super(Component.empty());
        this.sessionId = sessionId;
        this.targetElement = targetElement;
        this.blockPos = blockPos;
    }

    @Override
    protected void init() {
        super.init();
        this.hiddenEditBox = new EditBox(this.font, -1000, -1000, 200, 20, Component.empty());
        this.hiddenEditBox.setMaxLength(1024);
        this.hiddenEditBox.setValue(targetElement.text() != null ? targetElement.text() : "");
        this.hiddenEditBox.setResponder(newText ->
                ClientScreenInteractionManager.updateInputLocally(sessionId, targetElement.id(), newText)
        );
        this.addWidget(this.hiddenEditBox);
        this.setInitialFocus(this.hiddenEditBox);
        this.hiddenEditBox.setFocused(true);
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 背景を暗くせず、3Dスクリーン上のカーソル描画(WebRenderCore)に任せる
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_ESCAPE) {
            ClientScreenInteractionManager.submitInput(sessionId, targetElement.id(), this.hiddenEditBox.getValue());
            this.onClose();
            return true;
        }
        return this.hiddenEditBox.keyPressed(keyCode, scanCode, modifiers) || super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        WebRenderCore.activeInputId = "";
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}