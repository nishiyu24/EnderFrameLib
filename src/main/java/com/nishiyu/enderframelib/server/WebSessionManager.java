package com.nishiyu.enderframelib.server;

import com.nishiyu.enderframelib.api.IWebSessionProvider;
import com.nishiyu.enderframelib.core.dom.HtmlNodeSerializer;
import com.nishiyu.enderframelib.network.s2c.WebSyncS2CPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

public class WebSessionManager {
    private static final Set<IWebSessionProvider> PROVIDERS = new CopyOnWriteArraySet<>();
    private static final Map<String, WebSession> STANDALONE_SESSIONS = new ConcurrentHashMap<>();

    public static void registerProvider(IWebSessionProvider provider) {
        PROVIDERS.add(provider);
    }

    public static void unregisterProvider(IWebSessionProvider provider) {
        PROVIDERS.remove(provider);
    }

    public static WebSession getOrCreateStandaloneSession(String sessionId, int width, int height) {
        return STANDALONE_SESSIONS.computeIfAbsent(sessionId, id -> {
            WebSession s = new WebSession(id, WebSessionManager::broadcastDom);
            s.updateSize(width, height);
            return s;
        });
    }

    public static void removeStandaloneSession(String sessionId) {
        WebSession session = STANDALONE_SESSIONS.remove(sessionId);
        if (session != null) session.clear();
    }

    public static CompoundTag packDom(WebSession session) {
        CompoundTag tag = new CompoundTag();
        if (session != null && session.getDocument() != null && session.getDocument().root != null) {
            tag.put("DomTree", HtmlNodeSerializer.serialize(session.getDocument().root));
            tag.putString("CssString", session.getDocument().authorCss != null ? session.getDocument().authorCss : "");
            tag.putString("ClientScript", session.getDocument().clientScript != null ? session.getDocument().clientScript : "");
            tag.putInt("RootW", session.getWidth());
            tag.putInt("RootH", session.getHeight());
        } else {
            tag.putBoolean("IsEmpty", true);
        }
        return tag;
    }

    public static void broadcastDom(WebSession session) {
        CompoundTag tag = packDom(session);
        PacketDistributor.sendToAllPlayers(new WebSyncS2CPacket(session.getSessionId(), "dom_sync", tag));
    }

    public static void broadcastPatch(String sessionId, CompoundTag patchPayload) {
        PacketDistributor.sendToAllPlayers(new WebSyncS2CPacket(sessionId, "dom_patch", patchPayload));
    }

    public static void forceSyncToPlayer(ServerPlayer player, String sessionId) {
        WebSession standalone = STANDALONE_SESSIONS.get(sessionId);
        if (standalone != null) {
            PacketDistributor.sendToPlayer(player, new WebSyncS2CPacket(sessionId, "dom_sync", packDom(standalone)));
            return;
        }
        for (IWebSessionProvider provider : PROVIDERS) {
            if (provider.matchesSession(sessionId)) {
                WebSession session = provider.getSessionForClient(sessionId);
                if (session != null) {
                    PacketDistributor.sendToPlayer(player, new WebSyncS2CPacket(sessionId, "dom_sync", packDom(session)));
                    return;
                }
            }
        }
    }

    public static void handleFetchRequest(ServerPlayer player, String sessionId, String requestId, String endpoint, String jsonPayload) {
        for (IWebSessionProvider provider : PROVIDERS) {
            if (provider.matchesSession(sessionId)) {
                provider.onFetchRequest(player, sessionId, requestId, endpoint, jsonPayload);
                return;
            }
        }
    }

    public static void handleClientEvent(ServerPlayer player, String sessionId, String elementId, String eventName, String actionName, String value) {
        for (IWebSessionProvider provider : PROVIDERS) {
            if (provider.matchesSession(sessionId)) {
                provider.onClientEvent(player, sessionId, elementId, eventName, actionName, value);
                return;
            }
        }
    }
}