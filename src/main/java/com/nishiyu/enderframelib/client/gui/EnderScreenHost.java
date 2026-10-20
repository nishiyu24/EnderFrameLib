package com.nishiyu.enderframelib.client.gui;

import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.client.renderer.Canvas2DContext;
import com.nishiyu.enderframelib.client.renderer.WebSceneContext;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import org.jetbrains.annotations.ApiStatus;

/**
 * {@link AbstractEnderScreen} と {@link AbstractEnderContainerScreen} が共有する公開操作（STRUCTURE_PLAN S5）。
 *
 * <p>Minecraft 側の {@link Screen} と {@code AbstractContainerScreen} は継承関係に無いため、
 * 共通部分はこのインターフェースのデフォルトメソッドに置き、両方の基底クラスが実装する。
 * 下流 Mod から見たメソッドのシグネチャと動作は変えない。</p>
 */
@ApiStatus.AvailableSince("1.1")
public interface EnderScreenHost {

    /** この画面が使う Web セッション ID。 */
    String getWebSessionId();

    /** 入力・ビューポート・オーバーレイの実装。 */
    EnderScreenDelegate getUiDelegate();

    /**
     * テキスト入力のフォーカスを移す先の {@link Screen}。
     * 基底クラスは自身を返す。GUI 以外のホストが実装する場合は {@code null} でもよい。
     */
    Screen getWebScreen();

    default void sendServerAction(String actionName) {
        sendServerAction(actionName, "");
    }

    default void sendServerAction(String actionName, Object value) {
        getUiDelegate().sendServerAction(actionName, value);
    }

    default Rect2i toScreenRect(int webX, int webY, int webW, int webH) {
        return getUiDelegate().toScreenRect(webX, webY, webW, webH);
    }

    default Rect2i getWebViewportBounds() {
        return getUiDelegate().getViewportBounds();
    }

    default Canvas2DContext getCanvas(String canvasId) {
        return Canvas2DContext.getOrCreate(getWebSessionId(), canvasId);
    }

    default WebSceneContext getScene(String sceneId) {
        return WebSceneContext.getOrCreate(getWebSessionId(), sceneId);
    }

    default void setTextareaValue(String id, String value) {
        getUiDelegate().setTextareaValue(id, value);
    }

    default String getTextareaValue(String id) {
        return getUiDelegate().getTextareaValue(id);
    }

    default void focusWebInput(String inputElementId, String currentText) {
        getUiDelegate().focusWebInput(getWebScreen(), inputElementId, currentText);
    }

    default void unfocusWebInput() {
        getUiDelegate().unfocusWebInput();
    }

    default void setElementText(String id, String text) {
        getUiDelegate().setElementText(id, text);
    }

    default void setInputValue(String id, String value) {
        getUiDelegate().setInputValue(id, value);
    }

    default String getInputValue(String id) {
        return getUiDelegate().getInputValue(id);
    }

    default void setInnerHtml(String id, String html) {
        SessionContext session = ClientWebManager.getSession(getWebSessionId());
        if (session != null) session.updateNodeInnerHtmlLocally(id, html);
    }

    default void setStyle(String id, String prop, String val) {
        SessionContext session = ClientWebManager.getSession(getWebSessionId());
        if (session != null) session.updateNodeStyleLocally(id, prop, val);
    }

    default void setAttribute(String id, String attr, String val) {
        SessionContext session = ClientWebManager.getSession(getWebSessionId());
        if (session != null) session.updateNodeAttributeLocally(id, attr, val, false);
    }

    default void addClass(String id, String cls) {
        getUiDelegate().addClass(id, cls);
    }

    default void removeClass(String id, String cls) {
        getUiDelegate().removeClass(id, cls);
    }

    default void toggleClass(String id, String cls, boolean enable) {
        getUiDelegate().toggleClass(id, cls, enable);
    }
}
