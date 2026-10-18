package com.nishiyu.enderframelib.server;

import com.nishiyu.enderframelib.EnderFrameLib;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * サーバー → クライアントの送信口。サーバー側の送信はすべてここを通す。
 *
 * <p>{@code PacketDistributor.sendToPlayer} は、相手の接続がそのチャンネルを
 * ネゴシエートしていないと {@link UnsupportedOperationException} を投げる。
 * これは Mod を入れていないクライアント（Vanilla 接続）、接続途中のプレイヤー、
 * ゲームテストの擬似プレイヤーで実際に起きる。</p>
 *
 * <p>送れない相手が 1 人いただけで例外が呼び出し元まで抜けるため、
 * 以前は全員への配信が途中で止まり、さらにサーバー停止時の後片付け
 * （{@code ServerStoppingEvent} → {@code clearAll}）が例外で中断して
 * サーバーがクラッシュしていた。送信は「できる相手にだけ送る」操作であって、
 * 失敗が呼び出し元の処理を壊してよい操作ではない。</p>
 */
@ApiStatus.Internal
final class ServerPacketSender {

    private ServerPacketSender() {}

    /**
     * 送れる相手にだけ送る。送れない理由（切断済み・チャンネル未ネゴシエート）は
     * 黙って捨てるのではなく debug ログに残す。
     *
     * @return 実際に送ったら true
     */
    static boolean send(@Nullable ServerPlayer player, @Nullable CustomPacketPayload payload) {
        if (player == null || payload == null) return false;
        if (player.hasDisconnected()) return false;

        ServerGamePacketListenerImpl connection = player.connection;
        if (connection == null) return false;
        if (!connection.hasChannel(payload)) {
            EnderFrameLib.LOGGER.debug("ペイロード {} を送れません（チャンネル未対応）: player={}",
                    payload.type().id(), player.getGameProfile().getName());
            return false;
        }

        try {
            PacketDistributor.sendToPlayer(player, payload);
            return true;
        } catch (RuntimeException e) {
            // ネゴシエーション後に接続が落ちた等。1 人の失敗で配信全体を止めない
            EnderFrameLib.LOGGER.debug("ペイロード {} の送信に失敗しました: player={}",
                    payload.type().id(), player.getGameProfile().getName(), e);
            return false;
        }
    }
}
