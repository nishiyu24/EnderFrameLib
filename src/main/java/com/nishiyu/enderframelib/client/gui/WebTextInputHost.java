package com.nishiyu.enderframelib.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * 画面外に配置される隠し EditBox の統合管理ホスト。
 * GUI画面（2D）および InvisibleInputScreen（3D世界ブロック操作）の双方で
 * テキスト入力・IME入力・キャレット管理を共通化する。
 */
public class WebTextInputHost {

    public static final int HIDDEN_X = -2000;
    public static final int HIDDEN_Y = -2000;
    public static final int HIDDEN_W = 400;
    public static final int HIDDEN_H = 20;
    public static final int MAX_TEXT_LENGTH = 8192;

    private static volatile WebTextInputHost activeHost = null;

    private final EditBox editBox;
    private final Consumer<String> responder;
    private boolean suppressResponder = false;

    public WebTextInputHost(Font font, Consumer<String> responder) {
        this.responder = responder;
        this.editBox = new EditBox(font, HIDDEN_X, HIDDEN_Y, HIDDEN_W, HIDDEN_H, Component.empty());
        this.editBox.setMaxLength(MAX_TEXT_LENGTH);
        this.editBox.setResponder(text -> {
            if (!suppressResponder && this.responder != null) {
                this.responder.accept(text);
            }
        });
    }

    public static WebTextInputHost getActiveHost() {
        return activeHost;
    }

    public static EditBox getActiveEditBox() {
        WebTextInputHost host = activeHost;
        return host != null ? host.editBox() : null;
    }

    public static String getActiveText() {
        WebTextInputHost host = activeHost;
        return host != null ? host.getValue() : null;
    }

    public static int getActiveCursorPosition() {
        WebTextInputHost host = activeHost;
        return host != null ? host.getCursorPosition() : -1;
    }

    public static void clearActiveHost(WebTextInputHost host) {
        if (activeHost == host) {
            activeHost = null;
        }
    }

    public void activate() {
        activeHost = this;
        this.editBox.setFocused(true);
    }

    public void deactivate() {
        this.editBox.setFocused(false);
        if (activeHost == this) {
            activeHost = null;
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
