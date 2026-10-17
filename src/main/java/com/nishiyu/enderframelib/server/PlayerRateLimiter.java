package com.nishiyu.enderframelib.server;

import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * プレイヤー単位のクールダウン。C2S パケットの共通の入口。
 *
 * <p>キーはパケット側が決めるが、<b>クライアントが自由に変えられる値をキーに混ぜてはいけない</b>。
 * 以前 {@code WebActionC2SPacket} は {@code ElementId} をキーに入れていたため、
 * 要素 ID を変えるだけで制限を完全に迂回できた（I-7）。</p>
 */
@ApiStatus.Internal
public final class PlayerRateLimiter {

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
