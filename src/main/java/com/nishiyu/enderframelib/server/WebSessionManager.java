package com.nishiyu.enderframelib.server;

import com.nishiyu.enderframelib.api.IWebActionReceiver;
import com.nishiyu.enderframelib.api.IWebSessionProvider;
import com.nishiyu.enderframelib.core.dom.HtmlNodeSerializer;
import com.nishiyu.enderframelib.network.s2c.WebSyncS2CPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
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
        if (player == null) return;

        // 1. プレイヤーが現在開いている ContainerMenu が IWebActionReceiver を実装していれば直接ディスパッチ
        String cleanAction = actionName != null && actionName.startsWith("server:") ? actionName.substring(7) : actionName;
        String actionParam = value != null ? value : "";
        if (cleanAction != null && cleanAction.contains(":")) {
            String[] sp = cleanAction.split(":", 2);
            cleanAction = sp[0];
            if (actionParam.isEmpty()) actionParam = sp[1];
        }

        CompoundTag rpcPayload = new CompoundTag();
        rpcPayload.putString("SessionId", sessionId != null ? sessionId : "");
        rpcPayload.putString("ElementId", elementId != null ? elementId : "");
        rpcPayload.putString("EventName", eventName != null ? eventName : "");
        rpcPayload.putString("Value", actionParam);

        if (player.containerMenu instanceof IWebActionReceiver menuReceiver && player.containerMenu.stillValid(player)) {
            menuReceiver.onWebServerAction(player, cleanAction != null ? cleanAction : "", actionParam, rpcPayload);
        }

        // 2. セッションIDが "block:x,y,z" 形式の場合、距離チェック(64ブロック以内)の上で対象 BlockEntity にディスパッチ
        if (sessionId != null && sessionId.startsWith("block:") && player.level() != null) {
            try {
                String[] coords = sessionId.substring(6).split(",");
                if (coords.length >= 3) {
                    BlockPos pos = new BlockPos(Integer.parseInt(coords[0].trim()), Integer.parseInt(coords[1].trim()), Integer.parseInt(coords[2].trim()));
                    if (player.level().isLoaded(pos) && player.blockPosition().distSqr(pos) <= 4096.0) {
                        BlockEntity be = player.level().getBlockEntity(pos);
                        if (be instanceof IWebActionReceiver beReceiver) {
                            beReceiver.onWebServerAction(player, cleanAction != null ? cleanAction : "", actionParam, rpcPayload);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // 3. 既存の IWebSessionProvider へのルーティング
        for (IWebSessionProvider provider : PROVIDERS) {
            if (provider.matchesSession(sessionId)) {
                provider.onClientEvent(player, sessionId, elementId, eventName, actionName, value);
                return;
            }
        }
    }
}