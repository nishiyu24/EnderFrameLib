package com.nishiyu.enderframelib.client.gui;

import com.nishiyu.enderframelib.core.paint.model.*;

import com.nishiyu.enderframelib.api.event.UIEvents;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.client.SessionInput;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;

public class InvisibleInputScreen extends Screen {
    private static final double MAX_INTERACTION_DISTANCE_BLOCKS = 8.0;
    private static final double MAX_INTERACTION_DIST_SQR = MAX_INTERACTION_DISTANCE_BLOCKS * MAX_INTERACTION_DISTANCE_BLOCKS;

    private final String sessionId;
    private final DisplayItem targetElement;
    private final BlockPos blockPos;
    private WebTextInputHost inputHost;
    private boolean submitted = false;

    public InvisibleInputScreen(String sessionId, DisplayItem targetElement, BlockPos blockPos) {
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
        SessionContext session = ClientWebManager.getSession(sessionId);
        HtmlNode vNode = session != null ? session.findNode(baseId) : null;
        if (vNode != null && vNode.attrs.containsKey("value")) {
            initialVal = vNode.attrs.get("value");
        }

        this.inputHost = new WebTextInputHost(sessionId, this.font, newText ->
                input().updateInputLocally(targetElement.id(), newText)
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
        // 背景を暗くせず、3Dスクリーン上のカーソル描画(DisplayItemDrawer)に任せる
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
        SessionInput input = input();
        UIEvents.Keyboard keyDown = dispatchKeyEvent("keydown", keyCode, modifiers);
        if (keyDown != null && keyDown.isDefaultPrevented()) {
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            commitAndClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            // textarea の場合は Enter で画面を閉じず、改行を挿入する
            if (targetElement.type() == ElementType.TEXTAREA) {
                String data = "\n";
                if (!input.beginTextEdit(targetElement.id(), data, "insertLineBreak")) return true;
                if (this.inputHost != null) {
                    this.inputHost.editBox().insertText(data);
                    input.commitTextEdit(targetElement.id(), this.inputHost.getValue(), data, "insertLineBreak");
                }
                return true;
            } else {
                commitAndClose();
                return true;
            }
        }

        String inputType = SessionInput.editingInputType(keyCode, modifiers, targetElement.type() == ElementType.TEXTAREA);
        if (inputType != null && !input.beginTextEdit(targetElement.id(), null, inputType)) return true;
        if (this.inputHost != null && this.inputHost.keyPressed(keyCode, scanCode, modifiers)) {
            if (inputType != null) {
                input.commitTextEdit(targetElement.id(), this.inputHost.getValue(), null, inputType);
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        UIEvents.Keyboard keyUp = dispatchKeyEvent("keyup", keyCode, modifiers);
        if (keyUp != null && keyUp.isDefaultPrevented()) {
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        SessionInput input = input();
        String data = String.valueOf(codePoint);
        if (!input.beginTextEdit(targetElement.id(), data, "insertText")) return true;
        if (this.inputHost != null && this.inputHost.charTyped(codePoint, modifiers)) {
            input.commitTextEdit(targetElement.id(), this.inputHost.getValue(), data, "insertText");
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    private SessionInput input() {
        return ClientWebManager.getOrCreateSession(sessionId).input();
    }

    private UIEvents.Keyboard dispatchKeyEvent(String type, int keyCode, int modifiers) {
        SessionContext session = ClientWebManager.getSession(sessionId);
        String baseId = ClientWebManager.getBaseNodeId(targetElement.id());
        HtmlNode target = session != null ? session.findNode(baseId) : null;
        if (target == null && session != null) target = session.rootNode();
        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        boolean alt = (modifiers & GLFW.GLFW_MOD_ALT) != 0;
        boolean meta = (modifiers & GLFW.GLFW_MOD_SUPER) != 0;
        return input().keyEvent(type, target, keyCode, ctrl, shift, alt, meta);
    }

    private void commitAndClose() {
        if (!submitted && this.inputHost != null) {
            submitted = true;
            input().submitInput(targetElement.id(), this.inputHost.getValue());
        }
        this.onClose();
    }

    @Override
    public void onClose() {
        if (!submitted && this.inputHost != null) {
            submitted = true;
            input().submitInput(targetElement.id(), this.inputHost.getValue());
        }
        if (this.inputHost != null) {
            this.inputHost.deactivate();
        }
        SessionContext session = ClientWebManager.getSession(sessionId);
        if (session != null) session.setActiveInputId("");
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
