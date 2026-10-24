package com.nishiyu.enderframelib.server;

import com.nishiyu.enderframelib.server.ServerPayloadHandler.PlayerRateLimiter;
import com.nishiyu.enderframelib.EnderFrameLib;
import com.nishiyu.enderframelib.api.EnderFrameAPI;
import com.nishiyu.enderframelib.api.EnderFrameScriptingBridge;
import com.nishiyu.enderframelib.server.state.UIStateManager;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;

/**
 * サーバー側の static 状態の初期化と一括破棄。
 *
 * <p>セッション・閲覧者・購読・レート制限・データパック・プロバイダ登録・UIState は全て static マップで、
 * 以前はどこからも破棄されていなかった。統合サーバーでワールドを読み直すと、
 * 前のワールドのセッションとプレイヤー参照がそのまま残っていた（I-6）。
 * 破棄の経路はここ 1 箇所にまとめる。</p>
 */
@ApiStatus.Internal
public final class EnderFrameServerState {

    /** アイドルセッションの掃除間隔（tick）。 */
    private static final int CLEANUP_INTERVAL_TICKS = 20 * 60;

    private static int tickCounter = 0;

    private EnderFrameServerState() {}

    /** Mod 構築時に呼ぶ。static イニシャライザ任せだった登録をここに集める。 */
    public static void init() {
        WebSessionManager.init();
        ServerTargetSyncHandler.init();
        EnderFrameAPI.setInstance(new EnderFrameAPIImpl());
    }

    /** サーバーの tick から呼ばれる定期処理。 */
    public static void onServerTick() {
        // Headless State の変更差分を一括配信
        UIStateManager.tickAll();

        if (++tickCounter < CLEANUP_INTERVAL_TICKS) return;
        tickCounter = 0;
        WebSessionManager.evictIdleSessions();
    }

    /** チャンクがプレイヤーに送られた。中のテンプレート面を見せる。 */
    public static void onChunkSent(ServerPlayer player, net.minecraft.world.level.chunk.LevelChunk chunk) {
        SurfaceViewers.onChunkSent(player, chunk);
    }

    /** プレイヤーがチャンクの追跡をやめた。中のテンプレート面のセッションを捨てさせる。 */
    public static void onChunkUnwatched(ServerPlayer player, net.minecraft.server.level.ServerLevel level,
                                        net.minecraft.world.level.ChunkPos pos) {
        SurfaceViewers.onChunkUnwatched(player, level, pos);
    }

    /** プレイヤーのログアウト。本人に紐づく状態を全て落とす。 */
    public static void onPlayerLogout(ServerPlayer player) {
        if (player == null) return;
        WebSessionManager.removePlayerAll(player);
        ServerPubSubManager.removePlayerAll(player);
        ServerTargetSyncHandler.removePlayer(player.getUUID());
        PlayerRateLimiter.clearPlayer(player.getUUID());
        UIStateManager.removePlayerAll(player);
    }

    /**
     * ディメンション移動。ワールド内画面のセッションは移動先では見えないので
     * 閲覧者から外し、AR 購読の対象も作り直させる。
     */
    public static void onPlayerChangedDimension(ServerPlayer player) {
        if (player == null) return;
        WebSessionManager.removePlayerAll(player);
        ServerTargetSyncHandler.removePlayer(player.getUUID());
        UIStateManager.removePlayerAll(player);
    }

    /** サーバー停止。全ての static 状態を捨てる。 */
    public static void clearAll() {
        WebSessionManager.clearAll();
        ServerPubSubManager.clearAll();
        ServerTargetSyncHandler.clearAll();
        PlayerRateLimiter.clearAll();
        EnderFrameDataPackManager.clearAll();
        EnderFrameScriptingBridge.clearAll();
        UIStateManager.clearAll();
        tickCounter = 0;
        EnderFrameLib.LOGGER.debug("EnderFrameLib のサーバー側 static 状態を破棄しました。");
    }

    /** 破棄が効いているかの確認用（ゲームテストから使う）。 */
    public static String describeState() {
        return "sessions=" + WebSessionManager.standaloneSessionCount()
                + " viewers=" + WebSessionManager.viewerMapSize()
                + " providers=" + WebSessionManager.providerCount()
                + " channels=" + ServerPubSubManager.channelCount()
                + " subscribers=" + ServerPubSubManager.subscriberMapSize()
                + " arSubscriptions=" + ServerTargetSyncHandler.subscriptionCount()
                + " rateLimited=" + PlayerRateLimiter.trackedPlayerCount()
                + " datapackUis=" + EnderFrameDataPackManager.entryCount()
                + " uiStates=" + UIStateManager.stateCount()
                + " uiViewers=" + UIStateManager.viewerCount();
    }

    /** 全ての static 状態が空かどうか。 */
    public static boolean isEmpty() {
        return WebSessionManager.standaloneSessionCount() == 0
                && WebSessionManager.viewerMapSize() == 0
                && WebSessionManager.providerCount() == 0
                && ServerPubSubManager.channelCount() == 0
                && ServerPubSubManager.subscriberMapSize() == 0
                && ServerTargetSyncHandler.subscriptionCount() == 0
                && PlayerRateLimiter.trackedPlayerCount() == 0
                && UIStateManager.stateCount() == 0
                && UIStateManager.viewerCount() == 0;
    }
}
