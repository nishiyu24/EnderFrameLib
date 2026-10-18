package com.nishiyu.enderframelib.client.gui;

import com.nishiyu.enderframelib.core.model.UIElement.ElementType;
import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.core.dom.DomUtils;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.model.UIElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

public class InvisibleInputScreen extends Screen {
    private static final double MAX_INTERACTION_DISTANCE_BLOCKS = 8.0;
    private static final double MAX_INTERACTION_DIST_SQR = MAX_INTERACTION_DISTANCE_BLOCKS * MAX_INTERACTION_DISTANCE_BLOCKS;

    private final String sessionId;
    private final UIElement targetElement;
    private final BlockPos blockPos;
    private WebTextInputHost inputHost;
    private boolean submitted = false;

    public InvisibleInputScreen(String sessionId, UIElement targetElement, BlockPos blockPos) {
        super(Component.empty());
        this.sessionId = sessionId;
        this.targetElement = targetElement;
        this.blockPos = blockPos;
    }

    @Override
    protected void init() {
        super.init();
        String baseId = ClientWebManager.getBaseNodeId(targetElement.id());
        String initialVal = targetElement.text() != null ? targetElement.text() : "";
        HtmlNode vNode = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), baseId);
        if (vNode != null && vNode.attrs.containsKey("value")) {
            initialVal = vNode.attrs.get("value");
        }

        this.inputHost = new WebTextInputHost(this.font, newText ->
                ClientScreenInteractionManager.updateInputLocally(sessionId, targetElement.id(), newText)
        );
        this.inputHost.setValue(initialVal);
        this.addWidget(this.inputHost.editBox());
        this.setInitialFocus(this.inputHost.editBox());
        this.inputHost.activate();
    }

    public EditBox getActiveEditBox() {
        return this.inputHost != null ? this.inputHost.editBox() : null;
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 背景を暗くせず、3Dスクリーン上のカーソル描画(WebRenderCore)に任せる
    }

    @Override
    public void tick() {
        super.tick();
        // ブロック上のUIを操作している場合、プレイヤーが離れすぎたら自動で閉じる
        if (this.blockPos != null && this.minecraft != null && this.minecraft.player != null) {
            if (this.minecraft.player.blockPosition().distSqr(this.blockPos) > MAX_INTERACTION_DIST_SQR) {
                commitAndClose();
            }
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            commitAndClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            // textarea の場合は Enter で画面を閉じず、改行を挿入する
            if (targetElement.type() == ElementType.TEXTAREA) {
                if (this.inputHost != null) this.inputHost.editBox().insertText("\n");
                return true;
            } else {
                commitAndClose();
                return true;
            }
        }
        return (this.inputHost != null && this.inputHost.keyPressed(keyCode, scanCode, modifiers)) || super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void commitAndClose() {
        if (!submitted && this.inputHost != null) {
            submitted = true;
            ClientScreenInteractionManager.submitInput(sessionId, targetElement.id(), this.inputHost.getValue());
        }
        this.onClose();
    }

    @Override
    public void onClose() {
        if (!submitted && this.inputHost != null) {
            submitted = true;
            ClientScreenInteractionManager.submitInput(sessionId, targetElement.id(), this.inputHost.getValue());
        }
        if (this.inputHost != null) {
            this.inputHost.deactivate();
        }
        ClientWebManager.setActiveInputId(sessionId, "");
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}