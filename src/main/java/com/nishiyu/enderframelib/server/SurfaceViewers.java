package com.nishiyu.enderframelib.server;

import com.nishiyu.enderframelib.api.WebScreenBlockEntity;
import com.nishiyu.enderframelib.api.state.UIState;
import com.nishiyu.enderframelib.api.template.EnderScreenBlockEntity;
import com.nishiyu.enderframelib.server.state.UIStateManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

/**
 * テンプレートで動く世界内の面（{@link WebScreenBlockEntity#getTemplateId()} を返すブロックエンティティ）の閲覧者管理。
 * 画面を「開く」操作は無いので、そのチャンクを追跡しているプレイヤーを閲覧者にする。
 * チャンクを送られたら足し、追跡をやめたら外す。画面の形が変わったときは {@link #show} で送り直す。
 */
final class SurfaceViewers {

    private SurfaceViewers() {}

    /** 面のブロックエンティティを、そのチャンクを追跡している全プレイヤーに（送り直して）見せる。 */
    static void show(BlockEntity be) {
        if (!(be.getLevel() instanceof ServerLevel level)) return;
        if (surfaceOf(be) == null) {
            // 画面でなくなった（閉じた・master でなくなった）。見ていたプレイヤーのセッションを捨てさせる
            UIState state = UIStateManager.findState(UIStateManager.blockInstanceId(be.getBlockPos()));
            if (state != null && be instanceof WebScreenBlockEntity) {
                for (ServerPlayer player : new java.util.ArrayList<>(state.viewers())) state.removeSurfaceViewer(player);
            }
            return;
        }
        for (ServerPlayer player : level.getChunkSource().chunkMap.getPlayers(new ChunkPos(be.getBlockPos()), false)) {
            showTo(player, be);
        }
    }

    /** チャンクがプレイヤーに送られた。中の面を見せる。 */
    static void onChunkSent(ServerPlayer player, LevelChunk chunk) {
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (!be.isRemoved() && be instanceof EnderScreenBlockEntity screenBe
                    && screenBe.isMaster() && screenBe.isActive()
                    && screenBe.getTemplateId() == null && screenBe.getDatapackUiId() != null) {
                EnderFrameCommands.ensureBlockScreenSessionLoaded(screenBe);
            }
            showTo(player, be);
        }
    }

    /** プレイヤーがチャンクの追跡をやめた。中の面のセッションを捨てさせる。 */
    static void onChunkUnwatched(ServerPlayer player, ServerLevel level, ChunkPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        if (chunk == null) return;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (surfaceOf(be) == null) continue;
            UIState state = UIStateManager.findState(UIStateManager.blockInstanceId(be.getBlockPos()));
            if (state != null) state.removeSurfaceViewer(player);
        }
    }

    private static void showTo(ServerPlayer player, BlockEntity be) {
        WebScreenBlockEntity surface = surfaceOf(be);
        if (surface == null) return;
        UIState state = UIStateManager.getOrCreateBlockEntityState(surface.getTemplateId(), be);
        int ppb = surface.getPixelsPerBlock();
        state.addSurfaceViewer(player, surface.getScreenWidth() * ppb, surface.getScreenHeight() * ppb);
    }

    /** テンプレートで動く面の、画面の持ち主（master）だけ。 */
    private static @Nullable WebScreenBlockEntity surfaceOf(BlockEntity be) {
        if (be.isRemoved() || !(be instanceof WebScreenBlockEntity surface)) return null;
        return surface.isMaster() && surface.getTemplateId() != null ? surface : null;
    }
}
