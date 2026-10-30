package com.nishiyu.enderframelib.server;

import com.nishiyu.enderframelib.network.WebSyncS2CPacket;
import com.nishiyu.enderframelib.server.ServerPubSubManager.ServerPacketSender;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * サーバー側でアクティブなホログラムを管理し、接続プレイヤーへの同期を行うマネージャ。
 */
@ApiStatus.Internal
public final class ServerHologramManager {

    public record HologramEntry(
            String id,
            String sessionId,
            ResourceLocation dimension,
            double x, double y, double z,
            float widthBlocks, float heightBlocks,
            float yaw, boolean billboard, boolean interactive,
            double maxDistance,
            @Nullable String resourceLocation,
            int uiWidth, int uiHeight
    ) {
        public CompoundTag toPacketTag() {
            CompoundTag tag = new CompoundTag();
            tag.putString("HoloId", id);
            tag.putDouble("X", x);
            tag.putDouble("Y", y);
            tag.putDouble("Z", z);
            tag.putFloat("WidthBlocks", widthBlocks);
            tag.putFloat("HeightBlocks", heightBlocks);
            tag.putFloat("Yaw", yaw);
            tag.putBoolean("Billboard", billboard);
            tag.putBoolean("Interactive", interactive);
            tag.putDouble("MaxDistance", maxDistance);
            if (resourceLocation != null && !resourceLocation.isEmpty()) {
                tag.putString("ResourceLocation", resourceLocation);
            }
            tag.putInt("UiWidth", uiWidth);
            tag.putInt("UiHeight", uiHeight);
            return tag;
        }
    }

    private static final Map<String, HologramEntry> HOLOGRAMS = new ConcurrentHashMap<>();

    private ServerHologramManager() {}

    public static void spawn(MinecraftServer server, HologramEntry entry) {
        HOLOGRAMS.put(entry.id(), entry);
        CompoundTag tag = entry.toPacketTag();
        WebSyncS2CPacket packet = new WebSyncS2CPacket(entry.sessionId(), "set_hologram", tag);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level().dimension().location().equals(entry.dimension())) {
                ServerPacketSender.send(player, packet);
            }
        }
    }

    public static boolean remove(MinecraftServer server, String id) {
        HologramEntry removed = HOLOGRAMS.remove(id);
        if (removed == null) return false;

        CompoundTag tag = new CompoundTag();
        tag.putString("HoloId", id);
        WebSyncS2CPacket packet = new WebSyncS2CPacket(removed.sessionId(), "clear_hologram", tag);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPacketSender.send(player, packet);
        }
        return true;
    }

    public static void clear(MinecraftServer server) {
        HOLOGRAMS.clear();
        CompoundTag tag = new CompoundTag();
        tag.putString("HoloId", "all");
        WebSyncS2CPacket packet = new WebSyncS2CPacket("", "clear_hologram", tag);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPacketSender.send(player, packet);
        }
    }

    public static void syncToPlayer(ServerPlayer player) {
        ResourceLocation dim = player.level().dimension().location();
        for (HologramEntry entry : HOLOGRAMS.values()) {
            if (entry.dimension().equals(dim)) {
                ServerPacketSender.send(player, new WebSyncS2CPacket(entry.sessionId(), "set_hologram", entry.toPacketTag()));
            }
        }
    }

    public static void clearAll() {
        HOLOGRAMS.clear();
    }

    public static Collection<HologramEntry> getAll() {
        return HOLOGRAMS.values();
    }

    public static @Nullable HologramEntry get(String id) {
        return HOLOGRAMS.get(id);
    }
}
