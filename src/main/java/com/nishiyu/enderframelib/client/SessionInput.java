package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.gui.WebTextInputHost;
import com.nishiyu.enderframelib.common.UiInput;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.network.UiActionC2SPacket;
import com.nishiyu.enderframelib.network.WebActionC2SPacket;
import net.minecraft.nbt.CompoundTag;

/**
 * 1 セッション分の入力処理の Minecraft 側。ヒットテスト・pointer/mouse/click の合成・キーとテキスト編集・
 * 既定動作はコアの {@link UiInput} が行い、ここには効果音・GUI のアクションルータ・グローバルハンドラ・
 * パケット送信・隠し入力欄の文字列だけを足す。
 *
 * <p>{@code Host} / {@code ClickSink} / {@code PressResult} は {@link UiInput} の型で、
 * {@code SessionInput.Host} の綴りのまま使える。</p>
 */
public final class SessionInput extends UiInput {

    private final SessionContext session;

    SessionInput(SessionContext session) {
        super(session);
        this.session = session;
    }

    @Override
    protected void playElementSound(DisplayItem clicked) {
        ClientScreenInteractionManager.playElementSound(clicked);
    }

    @Override
    protected boolean mutateDomOnHover() {
        return ClientScreenInteractionManager.isMutateDomOnHover();
    }

    @Override
    protected String activeInputText() {
        return WebTextInputHost.getActiveText(session.sessionId());
    }

    /**
     * GUI 画面のルータなど、このセッション専用のアクション受け皿を登録する。
     * 画面を閉じるときは {@link #clearLocalEventCallback} で自分のものだけを外す。
     */
    public void setLocalEventCallback(ClientScreenInteractionManager.IClientEventCallback callback) {
        ClientScreenInteractionManager.setSessionEventCallback(session.sessionId(), callback);
    }

    public void clearLocalEventCallback(ClientScreenInteractionManager.IClientEventCallback callback) {
        ClientScreenInteractionManager.clearSessionEventCallback(session.sessionId(), callback);
    }

    @Override
    protected void triggerLocalEvent(String elementId, String eventName, String actionName, String value) {
        if (actionName == null || actionName.isEmpty()) return;
        boolean handledLocally = false;
        ClientScreenInteractionManager.IClientEventCallback local = ClientScreenInteractionManager.sessionEventCallback(session.sessionId());
        if (local != null) {
            handledLocally = local.onEvent(session.sessionId(), elementId, eventName, actionName, value);
        }
        if (!handledLocally) {
            handledLocally = ClientScreenInteractionManager.dispatchGlobalEvent(session.sessionId(), elementId, eventName, actionName, value);
        }
        if (!handledLocally && (actionName.startsWith("server:") || session.isHeadlessState())) {
            sendUiEvent(elementId, eventName, actionName, value);
        }
    }

    @Override
    protected void sendUiEvent(String elementId, String eventName, String actionName, String value) {
        CompoundTag tag = new CompoundTag();
        tag.putString("ElementId", elementId != null ? elementId : "");
        tag.putString("EventName", eventName != null ? eventName : "");
        tag.putString("ActionName", actionName != null ? actionName : "");
        tag.putString("Value", value != null ? value : "");

        if (session.isHeadlessState()) {
            String cleanAction = actionName != null && actionName.startsWith("server:")
                    ? actionName.substring(7)
                    : (actionName != null ? actionName : "");
            ClientScreenInteractionManager.sendToServer(new UiActionC2SPacket(session.sessionId(), cleanAction, tag));
        } else {
            ClientScreenInteractionManager.sendToServer(new WebActionC2SPacket(session.sessionId(), "ui_event", tag));
        }
    }
}
