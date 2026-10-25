package com.nishiyu.enderframelib.server;

import com.nishiyu.enderframelib.network.WebSyncS2CPacket;
import com.nishiyu.enderframelib.server.ServerPubSubManager.ServerPacketSender;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * サーバー側で管理するホログラム定義の登録簿。
 *
 * <p>コマンドクラスにあった状態をここへ移した。破棄は {@link EnderFrameServerState#clearAll} が呼ぶ。
 * コマンド側は検証とメッセージ、プレイヤーへの配信だけを受け持つ。</p>
 */
@ApiStatus.Internal
public final class HologramRegistry {

    /** サーバー側で管理するホログラム定義。 */
    public record ServerHologram(
            String id,
            ResourceLocation dimension,
            String sessionId,
            double x,
            double y,
            double z,
            float widthBlocks,
            float heightBlocks,
            float yaw,
            boolean billboard,
            double maxDistance,
            boolean interactive
    ) {
        public CompoundTag toPayload() {
            CompoundTag tag = new CompoundTag();
            tag.putString("HologramId", id);
            tag.putDouble("X", x);
            tag.putDouble("Y", y);
            tag.putDouble("Z", z);
            tag.putFloat("WidthBlocks", widthBlocks);
            tag.putFloat("HeightBlocks", heightBlocks);
            tag.putFloat("Yaw", yaw);
            tag.putBoolean("Billboard", billboard);
            tag.putDouble("MaxDistance", maxDistance);
            tag.putBoolean("Interactive", interactive);
            return tag;
        }
    }

    private static final Map<String, ServerHologram> ACTIVE = new ConcurrentHashMap<>();

    private HologramRegistry() {}

    public static void register(ServerHologram hologram) {
        ACTIVE.put(hologram.id(), hologram);
    }

    /** 取り除いたホログラム。無ければ {@code null}。 */
    public static ServerHologram remove(String id) {
        return ACTIVE.remove(id);
    }

    /** 全部取り除き、取り除いた数を返す。 */
    public static int clear() {
        int count = ACTIVE.size();
        ACTIVE.clear();
        return count;
    }

    public static Collection<ServerHologram> all() {
        return ACTIVE.values();
    }

    public static Set<String> ids() {
        return ACTIVE.keySet();
    }

    public static int count() {
        return ACTIVE.size();
    }

    /** プレイヤーのログインまたはディメンション移動時に、現在ディメンションのホログラムを同期する。 */
    public static void syncToPlayer(ServerPlayer player) {
        if (player == null) return;
        ServerPacketSender.send(player, new WebSyncS2CPacket("", "clear_holograms", new CompoundTag()));
        ResourceLocation dim = player.level().dimension().location();
        for (ServerHologram holo : ACTIVE.values()) {
            if (dim.equals(holo.dimension())) {
                if (WebSessionManager.findStandaloneSession(holo.sessionId()) != null) {
                    WebSessionManager.forceSyncToPlayer(player, holo.sessionId());
                }
                ServerPacketSender.send(player, new WebSyncS2CPacket(holo.sessionId(), "set_hologram", holo.toPayload()));
            }
        }
    }
}
