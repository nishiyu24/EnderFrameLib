package com.nishiyu.enderframelib.api;

import com.nishiyu.enderframelib.network.s2c.WebSyncS2CPacket;
import com.nishiyu.enderframelib.server.EnderFrameDataPackManager;
import com.nishiyu.enderframelib.server.ServerPubSubManager;
import com.nishiyu.enderframelib.server.WebSession;
import com.nishiyu.enderframelib.server.WebSessionManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * KubeJS や外部MODスクリプトから Java の Screen クラス等を一切書かずに
 * EnderFrameLib の全機能を呼び出すための静的ブリッジAPI
 */
public class EnderFrameScriptingBridge {

    private static final Map<String, IWebSessionProvider> GLOBAL_PROVIDERS = new ConcurrentHashMap<>();

    /**
     * プレイヤーに指定したHTML文字列で即座にWeb画面を開く
     */
    public static void openHtmlScreen(ServerPlayer player, String sessionId, String html, int width, int height) {
        if (player == null || html == null) return;
        String sid = (sessionId == null || sessionId.isEmpty()) ? ("script_" + player.getStringUUID()) : sessionId;
        WebSession session = WebSessionManager.getOrCreateStandaloneSession(sid, width > 0 ? width : 854, height > 0 ? height : 480);
        session.loadHtml(html);

        // DOMが同期されるので、open_screenパケットにはHTMLを含めず通信量を削減
        WebSessionManager.forceSyncToPlayer(player, sid);

        CompoundTag tag = new CompoundTag();
        tag.putString("Title", sid);
        tag.putInt("Width", width);
        tag.putInt("Height", height);
        PacketDistributor.sendToPlayer(player, new WebSyncS2CPacket(sid, "open_screen", tag));
    }

    /**
     * データパック内の UI ID ("namespace:path") を指定してプレイヤーに画面を開く
     */
    public static void openDatapackScreen(ServerPlayer player, String uiId, int width, int height) {
        if (player == null || uiId == null) return;
        String html = EnderFrameDataPackManager.getHtml(uiId);
        String sid = "dp_" + uiId.replace(':', '_').replace('/', '_');
        if (html != null) {
            openHtmlScreen(player, sid, html, width, height);
        } else {
            CompoundTag tag = new CompoundTag();
            tag.putString("Title", uiId);
            tag.putString("ResourceLocation", uiId);
            tag.putInt("Width", width);
            tag.putInt("Height", height);
            PacketDistributor.sendToPlayer(player, new WebSyncS2CPacket(sid, "open_screen", tag));
        }
    }

    /**
     * プレイヤーに常時表示されるカスタムWeb HUDを設定する（ヘルメット不要）
     */
    public static void setCustomHud(ServerPlayer player, String sessionId, String html) {
        if (player == null || html == null) return;
        String sid = (sessionId == null || sessionId.isEmpty()) ? "custom_hud" : sessionId;
        WebSession session = WebSessionManager.getOrCreateStandaloneSession(sid, 1280, 720);
        session.loadHtml(html);
        WebSessionManager.forceSyncToPlayer(player, sid);

        CompoundTag tag = new CompoundTag();
        tag.putString("Html", html);
        PacketDistributor.sendToPlayer(player, new WebSyncS2CPacket(sid, "set_hud", tag));
    }

    public static void clearCustomHud(ServerPlayer player) {
        if (player == null) return;
        PacketDistributor.sendToPlayer(player, new WebSyncS2CPacket("", "clear_hud", new CompoundTag()));
    }

    /**
     * PubSubチャンネルへデータを配信する (KubeJSのオブジェクト/Mapをそのまま渡せる)
     */
    public static void publish(String channel, Map<String, Object> data) {
        if (channel == null || data == null) return;
        ServerPubSubManager.publish(channel, data);
    }

    /**
     * クライアントの onclick="server:action" イベントをスクリプト側でグローバルに受信するリスナーを登録
     */
    public static void onGlobalServerAction(String sessionIdPrefix, BiConsumer<ServerPlayer, CompoundTag> handler) {
        // リロード等で重複して登録された場合は古いプロバイダを解除してメモリリークを防ぐ
        IWebSessionProvider existing = GLOBAL_PROVIDERS.get(sessionIdPrefix);
        if (existing != null) {
            WebSessionManager.unregisterProvider(existing);
        }

        IWebSessionProvider provider = new IWebSessionProvider() {
            @Override
            public boolean matchesSession(String sessionId) {
                return sessionId != null && sessionId.startsWith(sessionIdPrefix);
            }

            @Override
            public WebSession getSessionForClient(String sessionId) {
                return WebSessionManager.getOrCreateStandaloneSession(sessionId, 854, 480);
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
        WebSessionManager.registerProvider(provider);
    }
}