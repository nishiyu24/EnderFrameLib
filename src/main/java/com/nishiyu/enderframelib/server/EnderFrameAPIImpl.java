package com.nishiyu.enderframelib.server;

import com.nishiyu.enderframelib.server.ServerPubSubManager.ServerPacketSender;
import com.nishiyu.enderframelib.api.EnderFrameAPI;
import com.nishiyu.enderframelib.api.Ui;
import com.nishiyu.enderframelib.api.WebSessionProvider;
import com.nishiyu.enderframelib.api.WebSessionHandle;
import com.nishiyu.enderframelib.api.state.UIState;
import com.nishiyu.enderframelib.network.WebSyncS2CPacket;
import com.nishiyu.enderframelib.server.state.UIStateManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

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
    public void open(@NotNull ServerPlayer player, @NotNull Ui ui) {
        if (player == null || ui == null) return;
        switch (ui) {
            case Ui.Template t -> openTemplate(player, t);
            case Ui.Datapack d -> openDatapack(player, d);
            case Ui.Html h -> openHtml(player, h);
        }
    }

    @Override
    public @NotNull UIState stateOf(@NotNull String templateId, @NotNull String instanceId) {
        return UIStateManager.getOrCreateState(templateId, instanceId);
    }

    @Override
    public void showSurface(@NotNull ServerPlayer player, @NotNull Ui.Template ui, int width, int height) {
        if (player == null || ui == null) return;
        resolveState(ui).addSurfaceViewer(player, width, height);
    }

    @Override
    public void hideSurface(@NotNull ServerPlayer player, @NotNull Ui.Template ui) {
        if (player == null || ui == null) return;
        resolveState(ui).removeSurfaceViewer(player);
    }

    private static void openTemplate(ServerPlayer player, Ui.Template t) {
        resolveState(t).addViewer(player);
    }

    private static UIState resolveState(Ui.Template t) {
        UIState state = t.state();
        String instanceId = t.instanceId();
        if (t.blockEntity() != null) {
            instanceId = Ui.Template.blockInstanceId(t.blockEntity().getBlockPos());
            if (state == null) state = UIStateManager.getOrCreateBlockEntityState(t.templateId(), t.blockEntity());
        }
        if (instanceId == null) {
            throw new IllegalArgumentException("a template UI needs an instance id or a block entity: " + t.templateId());
        }
        if (state == null) state = UIStateManager.getOrCreateState(t.templateId(), instanceId);
        return state;
    }

    private static void openDatapack(ServerPlayer player, Ui.Datapack d) {
        String html = EnderFrameDataPackManager.getHtml(d.uiId());
        String sid = !isBlank(d.sessionId()) ? d.sessionId() : "dp_" + d.uiId().replace(':', '_').replace('/', '_');
        if (html != null) {
            openHtml(player, new Ui.Html(html, d.width(), d.height(), sid));
            return;
        }
        // サーバー側に HTML が無い場合はクライアントのリソースから解決させる
        CompoundTag tag = new CompoundTag();
        tag.putString("Title", d.uiId());
        tag.putString("ResourceLocation", d.uiId());
        tag.putInt("Width", d.width());
        tag.putInt("Height", d.height());
        ServerPacketSender.send(player, new WebSyncS2CPacket(sid, "open_screen", tag));
    }

    private static void openHtml(ServerPlayer player, Ui.Html ui) {
        int w = ui.width() > 0 ? ui.width() : DEFAULT_SCREEN_WIDTH;
        int h = ui.height() > 0 ? ui.height() : DEFAULT_SCREEN_HEIGHT;
        boolean perPlayer = isBlank(ui.sessionId());
        String sid = perPlayer ? "script_" + player.getStringUUID() : ui.sessionId();

        WebSession session = WebSessionManager.getOrCreateStandaloneSession(sid, w, h);
        // ID を こちらで決めた＝1 人用の画面なので所有者を立てる。
        // 呼び出し側が ID を指定した場合は複数プレイヤーで共有する意図があり得るので立てない
        if (perPlayer) WebSessionManager.setSessionOwner(sid, player);
        session.loadHtml(ui.html());

        // DOMが同期されるので、open_screenパケットにはHTMLを含めず通信量を削減
        WebSessionManager.forceSyncToPlayer(player, sid);

        CompoundTag tag = new CompoundTag();
        tag.putString("Title", sid);
        tag.putInt("Width", ui.width());
        tag.putInt("Height", ui.height());
        ServerPacketSender.send(player, new WebSyncS2CPacket(sid, "open_screen", tag));
    }

    @Override
    public void hud(@NotNull ServerPlayer player, @NotNull Ui.Html ui) {
        if (player == null || ui == null) return;
        // 既定の HUD は 1 人分。以前は全員が "custom_hud" を共有していたため、
        // 誰かの HUD を別のプレイヤーが同期要求で読み出せた
        boolean perPlayer = isBlank(ui.sessionId());
        String sid = perPlayer ? "custom_hud_" + player.getStringUUID() : ui.sessionId();
        int w = ui.width() > 0 ? ui.width() : DEFAULT_HUD_WIDTH;
        int h = ui.height() > 0 ? ui.height() : DEFAULT_HUD_HEIGHT;
        WebSession session = WebSessionManager.getOrCreateStandaloneSession(sid, w, h);
        if (perPlayer) WebSessionManager.setSessionOwner(sid, player);
        session.loadHtml(ui.html());
        WebSessionManager.forceSyncToPlayer(player, sid);

        CompoundTag tag = new CompoundTag();
        tag.putString("Html", ui.html());
        ServerPacketSender.send(player, new WebSyncS2CPacket(sid, "set_hud", tag));
    }

    @Override
    public void clearHud(@NotNull ServerPlayer player) {
        if (player == null) return;
        ServerPacketSender.send(player, new WebSyncS2CPacket("", "clear_hud", new CompoundTag()));
    }

    @Override
    public @NotNull UIState stateOf(@NotNull BlockEntity be, @NotNull String templateId) {
        Objects.requireNonNull(be, "be cannot be null");
        Objects.requireNonNull(templateId, "templateId cannot be null");
        return UIStateManager.getOrCreateBlockEntityState(templateId, be);
    }

    @Override
    public void discardSurface(@NotNull BlockEntity be) {
        Objects.requireNonNull(be, "be cannot be null");
        UIState state = UIStateManager.findState(UIStateManager.blockInstanceId(be.getBlockPos()));
        if (state != null) state.close();
    }

    @Override
    public void refreshSurface(@NotNull BlockEntity be) {
        Objects.requireNonNull(be, "be cannot be null");
        SurfaceViewers.show(be);
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

    @Override
    public void registerPrefixProvider(String prefix, WebSessionProvider provider) {
        WebSessionManager.registerPrefixProvider(prefix, provider);
    }

    @Override
    public void unregisterPrefixProvider(String prefix) {
        WebSessionManager.unregisterPrefixProvider(prefix);
    }

    private static boolean isBlank(@Nullable String sessionId) {
        return sessionId == null || sessionId.isEmpty();
    }
}
