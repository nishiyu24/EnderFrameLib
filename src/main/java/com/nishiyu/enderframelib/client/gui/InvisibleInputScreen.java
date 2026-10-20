package com.nishiyu.enderframelib.client.gui;

import com.nishiyu.enderframelib.core.layout.DisplayItem.ElementType;
import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.layout.DisplayItem;
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
        HtmlNode vNode = ClientWebManager.findNode(sessionId, baseId);
        if (vNode != null && vNode.attrs.containsKey("value")) {
            initialVal = vNode.attrs.get("value");
        }

        this.inputHost = new WebTextInputHost(sessionId, this.font, newText ->
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

    /**
     * 画面外に配置される隠し EditBox の統合管理ホスト。
     * GUI画面（2D）および InvisibleInputScreen（3D世界ブロック操作）の双方で
     * テキスト入力・IME入力・キャレット管理を共通化する。
     */
    public static class WebTextInputHost {

        public static final int HIDDEN_X = -2000;
        public static final int HIDDEN_Y = -2000;
        public static final int HIDDEN_W = 400;
        public static final int HIDDEN_H = 20;
        public static final int MAX_TEXT_LENGTH = 8192;

        private final String sessionId;
        private final EditBox editBox;
        private final Consumer<String> responder;
        private boolean suppressResponder = false;

        public WebTextInputHost(String sessionId, Font font, Consumer<String> responder) {
            this.sessionId = sessionId != null ? sessionId : "";
            this.responder = responder;
            this.editBox = new EditBox(font, HIDDEN_X, HIDDEN_Y, HIDDEN_W, HIDDEN_H, Component.empty());
            this.editBox.setMaxLength(MAX_TEXT_LENGTH);
            this.editBox.setResponder(text -> {
                if (!suppressResponder && this.responder != null) {
                    this.responder.accept(text);
                }
            });
        }

        /** いずれかのセッションでアクティブな入力ホスト。後方互換のための全体走査。 */
        public static WebTextInputHost getActiveHost() {
            for (SessionContext session : ClientWebManager.sessions()) {
                WebTextInputHost host = session.activeTextInputHost();
                if (host != null) return host;
            }
            return null;
        }

        public static WebTextInputHost getActiveHost(String sessionId) {
            SessionContext session = ClientWebManager.getSession(sessionId);
            return session != null ? session.activeTextInputHost() : null;
        }

        public static EditBox getActiveEditBox() {
            WebTextInputHost host = getActiveHost();
            return host != null ? host.editBox() : null;
        }

        public static EditBox getActiveEditBox(String sessionId) {
            WebTextInputHost host = getActiveHost(sessionId);
            return host != null ? host.editBox() : null;
        }

        public static String getActiveText() {
            WebTextInputHost host = getActiveHost();
            return host != null ? host.getValue() : null;
        }

        public static String getActiveText(String sessionId) {
            WebTextInputHost host = getActiveHost(sessionId);
            return host != null ? host.getValue() : null;
        }

        public static int getActiveCursorPosition() {
            WebTextInputHost host = getActiveHost();
            return host != null ? host.getCursorPosition() : -1;
        }

        public static int getActiveCursorPosition(String sessionId) {
            WebTextInputHost host = getActiveHost(sessionId);
            return host != null ? host.getCursorPosition() : -1;
        }

        public static void clearActiveHost(WebTextInputHost host) {
            for (SessionContext session : ClientWebManager.sessions()) {
                if (session.activeTextInputHost() == host) {
                    session.setActiveTextInputHost(null);
                }
            }
        }

        public void activate() {
            ClientWebManager.getOrCreateSession(this.sessionId).setActiveTextInputHost(this);
            this.editBox.setFocused(true);
        }

        public void deactivate() {
            this.editBox.setFocused(false);
            SessionContext session = ClientWebManager.getSession(this.sessionId);
            if (session != null && session.activeTextInputHost() == this) {
                session.setActiveTextInputHost(null);
            }
        }

        public EditBox editBox() {
            return editBox;
        }

        public String getValue() {
            return editBox.getValue();
        }

        public void setValue(String value) {
            this.suppressResponder = true;
            try {
                this.editBox.setValue(value != null ? value : "");
                this.editBox.moveCursorToEnd(false);
            } finally {
                this.suppressResponder = false;
            }
        }

        public int getCursorPosition() {
            return editBox.getCursorPosition();
        }

        public boolean isFocused() {
            return editBox.isFocused();
        }

        public void setFocused(boolean focused) {
            if (focused) {
                activate();
            } else {
                deactivate();
            }
        }

        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            return editBox.keyPressed(keyCode, scanCode, modifiers);
        }

        public boolean charTyped(char codePoint, int modifiers) {
            return editBox.charTyped(codePoint, modifiers);
        }
    }
}
