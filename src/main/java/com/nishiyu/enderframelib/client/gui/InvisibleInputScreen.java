package com.nishiyu.enderframelib.client.gui;

import com.nishiyu.enderframelib.client.Hologram;
import com.nishiyu.enderframelib.client.SurfaceHit;
import com.nishiyu.enderframelib.client.renderer.HologramRenderer;
import com.nishiyu.enderframelib.core.paint.model.*;

import com.nishiyu.enderframelib.api.event.UIEvents;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.SurfaceHitResolver;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.client.SessionInput;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import com.nishiyu.enderframelib.api.WebScreenBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
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
    /** 入力欄のある面のブロック（master）。ブロックの無いホログラムでは {@code null}。 */
    private final BlockPos blockPos;
    private WebTextInputHost inputHost;
    private boolean submitted = false;
    /** 入力欄の外を押した。ボタンのクリックなどが解放まで届くよう、閉じるのは離したとき。 */
    private boolean closeOnRelease = false;

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
        // 世界内のUIを操作している場合、プレイヤーが画面から離れすぎたら自動で閉じる
        if (this.minecraft != null && this.minecraft.player != null && this.minecraft.level != null) {
            if (distanceSqrToScreen() > MAX_INTERACTION_DIST_SQR) {
                commitAndClose();
            }
        }
    }

    /** 目から画面全体（master から右へ幅、上へ高さ分のブロック）の箱までの距離の 2 乗。大きな画面でも、端の近くに立てば届く。 */
    private double distanceSqrToScreen() {
        Vec3 eye = this.minecraft.player.getEyePosition();
        if (blockPos == null) {
            Hologram hologram = HologramRenderer.find(sessionId);
            // ホログラムが外された／置き換えられたら、離れたのと同じに扱って閉じる
            return hologram != null ? eye.distanceToSqr(hologram.center()) : Double.MAX_VALUE;
        }
        if (!(this.minecraft.level.getBlockEntity(blockPos) instanceof WebScreenBlockEntity screen) || screen.getFacing() == null
                || screen.getFacing().getAxis().isVertical()) {
            return eye.distanceToSqr(Vec3.atCenterOf(blockPos));
        }
        Direction right = screen.getFacing().getCounterClockWise();
        BlockPos far = blockPos.relative(right, screen.getScreenWidth() - 1).above(screen.getScreenHeight() - 1);
        double dx = axisGap(eye.x, Math.min(blockPos.getX(), far.getX()), Math.max(blockPos.getX(), far.getX()) + 1);
        double dy = axisGap(eye.y, blockPos.getY(), far.getY() + 1);
        double dz = axisGap(eye.z, Math.min(blockPos.getZ(), far.getZ()), Math.max(blockPos.getZ(), far.getZ()) + 1);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double axisGap(double v, double min, double max) {
        return Math.max(Math.max(min - v, v - max), 0.0);
    }

    // ------------------------------------------------------------------
    // マウス。カーソルが自由になっているので、カーソル位置から画面上の点を求めてセッションへ転送する
    // ------------------------------------------------------------------

    private SurfaceHit cursorHit(double mouseX, double mouseY) {
        if (this.minecraft == null) return null;
        return blockPos != null ? SurfaceHitResolver.fromCursor(this.minecraft, blockPos, mouseX, mouseY)
                : HologramRenderer.pickAtCursor(this.minecraft, sessionId, mouseX, mouseY);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        SurfaceHit hit = cursorHit(mouseX, mouseY);
        if (hit != null) {
            ClientScreenInteractionManager.handleHover(sessionId, hit.pixelX(), hit.pixelY());
        } else {
            ClientScreenInteractionManager.handleHover(sessionId, -1, -1);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT && button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return false;
        SurfaceHit hit = cursorHit(mouseX, mouseY);
        closeOnRelease = true;
        if (hit == null) return true;

        DisplayItem clicked = ClientScreenInteractionManager.handleClick(sessionId, hit.pixelX(), hit.pixelY(), button);
        if (clicked == null) return true;

        boolean isInput = clicked.type() == ElementType.INPUT || clicked.type() == ElementType.TEXTAREA;
        if (isInput && clicked.id().equals(targetElement.id())) {
            closeOnRelease = false;
        } else if (isInput) {
            switchTo(clicked);
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        SurfaceHit hit = cursorHit(mouseX, mouseY);
        if (hit != null) ClientScreenInteractionManager.handleDrag(sessionId, hit.pixelX(), hit.pixelY());
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT && button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return false;
        SurfaceHit hit = cursorHit(mouseX, mouseY);
        if (hit != null) {
            ClientScreenInteractionManager.handleRelease(sessionId, hit.pixelX(), hit.pixelY(), button, true);
        } else {
            ClientScreenInteractionManager.handleRelease(sessionId);
        }
        if (closeOnRelease) {
            closeOnRelease = false;
            ClientScreenInteractionManager.swallowNextMouseEvent();
            commitAndClose();
        }
        return true;
    }

    /** 別の入力欄がクリックされたとき。いまの値を確定して、そちらの入力画面へ移る。 */
    private void switchTo(DisplayItem next) {
        if (!submitted && this.inputHost != null) {
            submitted = true;
            input().submitInput(targetElement.id(), this.inputHost.getValue());
            this.inputHost.deactivate();
        }
        ClientWebManager.getOrCreateSession(sessionId).activateInput(next.id());
        ClientScreenInteractionManager.openInputScreen(sessionId, next, blockPos);
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
