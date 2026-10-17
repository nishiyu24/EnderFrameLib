package com.nishiyu.enderframelib.api;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Flat static facade over {@link EnderFrameAPI} for KubeJS and other script
 * runtimes that cannot comfortably hold an interface instance.
 *
 * <p>Every method here is a thin delegation; {@link EnderFrameAPI} is the
 * reference for what the operations do.</p>
 */
@ApiStatus.AvailableSince("1.0")
public class EnderFrameScriptingBridge {

    private static final Map<String, IWebSessionProvider> GLOBAL_PROVIDERS = new ConcurrentHashMap<>();

    private EnderFrameScriptingBridge() {}

    /** Opens a screen for one player from an HTML string. The session is owned by that player. */
    public static void openHtmlScreen(ServerPlayer player, String sessionId, String html, int width, int height) {
        EnderFrameAPI.get().openScreen(player, sessionId, html, width, height);
    }

    /** Opens a datapack UI ({@code "namespace:path"}) for one player. */
    public static void openDatapackScreen(ServerPlayer player, String uiId, int width, int height) {
        EnderFrameAPI.get().openDatapackScreen(player, uiId, width, height);
    }

    /** Installs a persistent HUD overlay for one player (no helmet required). */
    public static void setCustomHud(ServerPlayer player, String sessionId, String html) {
        EnderFrameAPI.get().setHud(player, sessionId, html);
    }

    /** Removes the HUD overlay installed with {@link #setCustomHud}. */
    public static void clearCustomHud(ServerPlayer player) {
        EnderFrameAPI.get().clearHud(player);
    }

    /** Publishes data onto a pub/sub channel (a script map can be passed straight through). */
    public static void publish(String channel, Map<String, Object> data) {
        EnderFrameAPI.get().publish(channel, data);
    }

    /**
     * Registers a listener for {@code onclick="server:…"} events of every session
     * whose id starts with {@code sessionIdPrefix}.
     *
     * <p>The provider installed here only resolves sessions that already exist:
     * a client request for an unknown id no longer creates one.</p>
     */
    public static void onGlobalServerAction(String sessionIdPrefix, BiConsumer<ServerPlayer, CompoundTag> handler) {
        if (sessionIdPrefix == null || handler == null) return;
        EnderFrameAPI api = EnderFrameAPI.get();

        // リロード等で重複して登録された場合は古いプロバイダを解除してメモリリークを防ぐ
        IWebSessionProvider existing = GLOBAL_PROVIDERS.get(sessionIdPrefix);
        if (existing != null) {
            api.unregisterProvider(existing);
        }

        IWebSessionProvider provider = new IWebSessionProvider() {
            @Override
            public boolean matchesSession(String sessionId) {
                return sessionId != null && sessionId.startsWith(sessionIdPrefix);
            }

            @Override
            public WebSessionHandle getSessionForClient(String sessionId) {
                // クライアントの要求でセッションを作らない。スクリプトが先に作ったものだけを返す
                return EnderFrameAPI.get().findSession(sessionId);
            }

            @Override
            public void onClientEvent(ServerPlayer player, String sessionId, String elementId, String eventName, String actionName, String payload) {
                CompoundTag tag = new CompoundTag();
                tag.putString("SessionId", sessionId != null ? sessionId : "");
                tag.putString("ElementId", elementId != null ? elementId : "");
                tag.putString("EventName", eventName != null ? eventName : "");
                tag.putString("ActionName", actionName != null ? actionName : "");
                tag.putString("Value", payload != null ? payload : "");
                handler.accept(player, tag);
            }
        };

        GLOBAL_PROVIDERS.put(sessionIdPrefix, provider);
        api.registerProvider(provider);
    }

    /** Unregisters a listener installed with {@link #onGlobalServerAction}. */
    public static void removeGlobalServerAction(String sessionIdPrefix) {
        IWebSessionProvider provider = GLOBAL_PROVIDERS.remove(sessionIdPrefix);
        if (provider != null && EnderFrameAPI.isAvailable()) {
            EnderFrameAPI.get().unregisterProvider(provider);
        }
    }

    /** Drops every script-registered provider. Called when the server stops. */
    @ApiStatus.Internal
    public static void clearAll() {
        GLOBAL_PROVIDERS.clear();
    }
}
