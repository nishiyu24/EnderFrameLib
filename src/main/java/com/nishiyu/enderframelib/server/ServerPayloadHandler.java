package com.nishiyu.enderframelib.server;

import com.nishiyu.enderframelib.network.SubscribeC2SPacket;
import com.nishiyu.enderframelib.network.WebActionC2SPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.ApiStatus;

public class ServerPayloadHandler {

    private static final long SUBSCRIBE_COOLDOWN_MS = 200L;
    private static final long AR_SUBSCRIBE_COOLDOWN_MS = 100L;

    private static final long SYNC_COOLDOWN_MS = 500L;
    private static final long FETCH_COOLDOWN_MS = 40L;
    private static final long EVENT_COOLDOWN_MS = 30L;

    public static void handleSubscribe(SubscribeC2SPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) return;

            if (packet.channel() != null && !packet.channel().isEmpty()) {
                if (!PlayerRateLimiter.allow(serverPlayer, "sub_" + packet.channel(), SUBSCRIBE_COOLDOWN_MS)) return;
                if (packet.isUnsubscribe()) {
                    ServerPubSubManager.unsubscribe(serverPlayer, packet.channel());
                } else {
                    ServerPubSubManager.subscribe(serverPlayer, packet.channel());
                }
            } else {
                // キーに購読内容のハッシュを混ぜる。同じ要求の連打だけを弾き、
                // 内容が変わった要求（＝落とすと HUD が止まる）は通す
                String arKey = Integer.toHexString(java.util.Objects.hash(
                        packet.needsHud(), packet.needsTracker(), packet.radius(),
                        packet.targetType(), packet.hudNbtPaths(), packet.trackerNbtPaths()));

                if (!PlayerRateLimiter.allow(serverPlayer, "ar_sub_" + arKey, AR_SUBSCRIBE_COOLDOWN_MS)) return;
                ServerPubSubManager.updateArSubscription(
                        serverPlayer, packet.needsHud(), packet.needsTracker(),
                        packet.radius(), packet.targetType(), packet.hudNbtPaths(), packet.trackerNbtPaths()
                );
            }
        });
    }

    public static void handleWebAction(WebActionC2SPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) return;
            CompoundTag tag = packet.payload() != null ? packet.payload() : new CompoundTag();
            switch (packet.action()) {
                case "request_sync" -> {
                    if (PlayerRateLimiter.allow(serverPlayer, "sync_" + packet.sessionId(), SYNC_COOLDOWN_MS)) {
                        WebSessionManager.forceSyncToPlayer(serverPlayer, packet.sessionId());
                    }
                }
                case "fetch" -> {
                    if (PlayerRateLimiter.allow(serverPlayer, "fetch_" + packet.sessionId(), FETCH_COOLDOWN_MS)) {
                        WebSessionManager.handleFetchRequest(
                                serverPlayer,
                                packet.sessionId(),
                                tag.getString("RequestId"),
                                tag.getString("Endpoint"),
                                tag.getString("JsonPayload")
                        );
                    }
                }
                case "ui_event" -> {
                    // レート制限のキーに ElementId を混ぜてはいけない。
                    // クライアントが要素 ID を変えるだけで制限を完全に迂回できた（I-7）
                    if (PlayerRateLimiter.allow(serverPlayer, "ev_" + packet.sessionId(), EVENT_COOLDOWN_MS)) {
                        WebSessionManager.handleClientEvent(
                                serverPlayer,
                                packet.sessionId(),
                                tag.getString("ElementId"),
                                tag.getString("EventName"),
                                tag.getString("ActionName"),
                                tag.getString("Value")
                        );
                    }
                }
                default -> { /* 未知のアクションは黙って捨てる */ }
            }
        });
    }

    /**
     * プレイヤー単位のクールダウン。C2S パケットの共通の入口。
     *
     * <p>キーはパケット側が決めるが、<b>クライアントが自由に変えられる値をキーに混ぜてはいけない</b>。
     * 以前 {@code WebActionC2SPacket} は {@code ElementId} をキーに入れていたため、
     * 要素 ID を変えるだけで制限を完全に迂回できた（I-7）。</p>
     */
    @ApiStatus.Internal
    public static final class PlayerRateLimiter {

        /** 履歴の保持時間。これより古いエントリは次のアクセスで捨てる。 */
        private static final long HISTORY_TTL_MS = 10_000L;
        /** 1 プレイヤーが保持できるキーの数の上限（無制限マップ化の防止）。 */
        private static final int MAX_KEYS_PER_PLAYER = 64;

        // プレイヤーUUID単位に階層化し、ログアウト時の安全な解放を実現
        private static final Map<UUID, Map<String, Long>> RATE_LIMITS = new ConcurrentHashMap<>();

        private PlayerRateLimiter() {}

        /**
         * {@code key} の操作を許可するかどうか。許可した場合は時刻を記録する。
         *
         * @return クールダウンが明けていれば true
         */
        public static boolean allow(ServerPlayer player, String key, long cooldownMs) {
            if (player == null || key == null) return false;
            long now = System.currentTimeMillis();
            Map<String, Long> limits = RATE_LIMITS.computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>());

            // メモリリーク防止のため、古い履歴をパージ
            limits.entrySet().removeIf(e -> (now - e.getValue()) > HISTORY_TTL_MS);
            if (limits.size() >= MAX_KEYS_PER_PLAYER && !limits.containsKey(key)) {
                return false;
            }

            Long last = limits.get(key);
            if (last != null && (now - last) < cooldownMs) {
                return false;
            }
            limits.put(key, now);
            return true;
        }

        public static void clearPlayer(UUID uuid) {
            if (uuid != null) {
                RATE_LIMITS.remove(uuid);
            }
        }

        /** サーバー停止時に全ての static 状態を捨てる（I-6）。 */
        static void clearAll() {
            RATE_LIMITS.clear();
        }

        static int trackedPlayerCount() {
            return RATE_LIMITS.size();
        }
    }
}
