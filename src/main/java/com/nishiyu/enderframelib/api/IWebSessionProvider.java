package com.nishiyu.enderframelib.api;

import com.nishiyu.enderframelib.server.WebSession;
import net.minecraft.server.level.ServerPlayer;

/**
 * サーバー側でWebSessionを管理・提供するホスト（LuaVMやBlockEntityなど）用インターフェース
 */
public interface IWebSessionProvider {
    boolean matchesSession(String sessionId);
    WebSession getSessionForClient(String sessionId);
    default void onFetchRequest(ServerPlayer player, String sessionId, String requestId, String endpoint, String jsonPayload) {}
    default void onClientEvent(ServerPlayer player, String sessionId, String elementId, String eventName, String actionName, String payload) {}
}