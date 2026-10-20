package com.nishiyu.enderframelib.server;

import com.nishiyu.enderframelib.server.ServerPubSubManager.ServerPacketSender;
import com.nishiyu.enderframelib.api.EnderFrameAPI;
import com.nishiyu.enderframelib.api.WebSessionProvider;
import com.nishiyu.enderframelib.api.WebSessionHandle;
import com.nishiyu.enderframelib.network.EnderFrameNetwork.WebSyncS2CPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * {@link EnderFrameAPI} の実装。
 * api パッケージが server / network / common を import しないようにするための橋。
 */
@ApiStatus.Internal
final class EnderFrameAPIImpl implements EnderFrameAPI {

    private static final int DEFAULT_SCREEN_WIDTH = 854;
    private static final int DEFAULT_SCREEN_HEIGHT = 480;
    private static final int DEFAULT_HUD_WIDTH = 1280;
    private static final int DEFAULT_HUD_HEIGHT = 720;

    @Override
    public WebSessionHandle getOrCreateSession(String sessionId, int width, int height) {
        return WebSessionManager.getOrCreateStandaloneSession(sessionId, width, height);
    }

    @Override
    @Nullable
    public WebSessionHandle findSession(String sessionId) {
        return WebSessionManager.findStandaloneSession(sessionId);
    }

    @Override
    public void closeSession(String sessionId) {
        WebSessionManager.removeStandaloneSession(sessionId);
    }

    @Override
    public void openScreen(ServerPlayer player, @Nullable String sessionId, String html, int width, int height) {
        if (player == null || html == null) return;
        int w = width > 0 ? width : DEFAULT_SCREEN_WIDTH;
        int h = height > 0 ? height : DEFAULT_SCREEN_HEIGHT;
        boolean perPlayer = isBlank(sessionId);
        String sid = perPlayer ? "script_" + player.getStringUUID() : sessionId;

        WebSession session = WebSessionManager.getOrCreateStandaloneSession(sid, w, h);
        // ID を こちらで決めた＝1 人用の画面なので所有者を立てる。
        // 呼び出し側が ID を指定した場合は複数プレイヤーで共有する意図があり得るので立てない
        if (perPlayer) WebSessionManager.setSessionOwner(sid, player);
        session.loadHtml(html);

        // DOMが同期されるので、open_screenパケットにはHTMLを含めず通信量を削減
        WebSessionManager.forceSyncToPlayer(player, sid);

        CompoundTag tag = new CompoundTag();
        tag.putString("Title", sid);
        tag.putInt("Width", width);
        tag.putInt("Height", height);
        ServerPacketSender.send(player, new WebSyncS2CPacket(sid, "open_screen", tag));
    }

    @Override
    public void openDatapackScreen(ServerPlayer player, String uiId, int width, int height) {
        if (player == null || uiId == null) return;
        String html = EnderFrameDataPackManager.getHtml(uiId);
        String sid = "dp_" + uiId.replace(':', '_').replace('/', '_');
        if (html != null) {
            openScreen(player, sid, html, width, height);
            return;
        }
        // サーバー側に HTML が無い場合はクライアントのリソースから解決させる
        CompoundTag tag = new CompoundTag();
        tag.putString("Title", uiId);
        tag.putString("ResourceLocation", uiId);
        tag.putInt("Width", width);
        tag.putInt("Height", height);
        ServerPacketSender.send(player, new WebSyncS2CPacket(sid, "open_screen", tag));
    }

    @Override
    public void setHud(ServerPlayer player, @Nullable String sessionId, String html) {
        if (player == null || html == null) return;
        // 既定の HUD は 1 人分。以前は全員が "custom_hud" を共有していたため、
        // 誰かの HUD を別のプレイヤーが同期要求で読み出せた
        boolean perPlayer = isBlank(sessionId);
        String sid = perPlayer ? "custom_hud_" + player.getStringUUID() : sessionId;
        WebSession session = WebSessionManager.getOrCreateStandaloneSession(sid, DEFAULT_HUD_WIDTH, DEFAULT_HUD_HEIGHT);
        if (perPlayer) WebSessionManager.setSessionOwner(sid, player);
        session.loadHtml(html);
        WebSessionManager.forceSyncToPlayer(player, sid);

        CompoundTag tag = new CompoundTag();
        tag.putString("Html", html);
        ServerPacketSender.send(player, new WebSyncS2CPacket(sid, "set_hud", tag));
    }

    @Override
    public void clearHud(ServerPlayer player) {
        if (player == null) return;
        ServerPacketSender.send(player, new WebSyncS2CPacket("", "clear_hud", new CompoundTag()));
    }

    @Override
    public void publish(String channel, Map<String, Object> data) {
        if (channel == null || data == null) return;
        ServerPubSubManager.publish(channel, data);
    }

    @Override
    public void addSubscriptionCheck(SubscriptionCheck check) {
        ServerPubSubManager.addAccessCheck(check);
    }

    @Override
    public void registerProvider(WebSessionProvider provider) {
        WebSessionManager.registerProvider(provider);
    }

    @Override
    public void unregisterProvider(WebSessionProvider provider) {
        WebSessionManager.unregisterProvider(provider);
    }

    private static boolean isBlank(@Nullable String sessionId) {
        return sessionId == null || sessionId.isEmpty();
    }
}
