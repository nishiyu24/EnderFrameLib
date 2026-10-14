package com.nishiyu.enderframelib.network.c2s;

import com.nishiyu.enderframelib.server.WebSessionManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public record WebActionC2SPacket(String sessionId, String action, CompoundTag payload) implements CustomPacketPayload {
    public static final Type<WebActionC2SPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("enderframelib", "web_action"));

    public static final StreamCodec<FriendlyByteBuf, WebActionC2SPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> packet.write(buf),
            WebActionC2SPacket::new
    );

    private static final Map<String, Long> RATE_LIMIT_MAP = new ConcurrentHashMap<>();

    public WebActionC2SPacket(FriendlyByteBuf buf) {
        this(buf.readUtf(), buf.readUtf(), buf.readNbt());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(sessionId);
        buf.writeUtf(action);
        buf.writeNbt(payload != null ? payload : new CompoundTag());
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static boolean checkRateLimit(ServerPlayer player, String actionKey, long cooldownMs) {
        long now = System.currentTimeMillis();
        String key = player.getUUID() + ":" + actionKey;
        Long last = RATE_LIMIT_MAP.get(key);
        if (last != null && (now - last) < cooldownMs) {
            return false;
        }
        RATE_LIMIT_MAP.put(key, now);
        return true;
    }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                CompoundTag tag = payload != null ? payload : new CompoundTag();
                switch (action) {
                    case "request_sync" -> {
                        if (checkRateLimit(serverPlayer, "sync_" + sessionId, 500L)) {
                            WebSessionManager.forceSyncToPlayer(serverPlayer, sessionId);
                        }
                    }
                    case "fetch" -> {
                        if (checkRateLimit(serverPlayer, "fetch_" + sessionId, 40L)) {
                            WebSessionManager.handleFetchRequest(
                                    serverPlayer,
                                    sessionId,
                                    tag.getString("RequestId"),
                                    tag.getString("Endpoint"),
                                    tag.getString("JsonPayload")
                            );
                        }
                    }
                    case "ui_event" -> {
                        String elId = tag.getString("ElementId");
                        if (checkRateLimit(serverPlayer, "ev_" + sessionId + "_" + elId, 30L)) {
                            WebSessionManager.handleClientEvent(
                                    serverPlayer,
                                    sessionId,
                                    elId,
                                    tag.getString("EventName"),
                                    tag.getString("ActionName"),
                                    tag.getString("Value")
                            );
                        }
                    }
                }
            }
        });
    }
}