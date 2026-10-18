package com.nishiyu.enderframelib.network;

import com.nishiyu.enderframelib.server.PlayerRateLimiter;
import com.nishiyu.enderframelib.server.WebSessionManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public record WebActionC2SPacket(String sessionId, String action, CompoundTag payload) implements CustomPacketPayload {
    public static final Type<WebActionC2SPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("enderframelib", "web_action"));

    public static final StreamCodec<FriendlyByteBuf, WebActionC2SPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> packet.write(buf),
            WebActionC2SPacket::new
    );

    private static final long SYNC_COOLDOWN_MS = 500L;
    private static final long FETCH_COOLDOWN_MS = 40L;
    private static final long EVENT_COOLDOWN_MS = 30L;

    public WebActionC2SPacket(FriendlyByteBuf buf) {
        this(buf.readUtf(256), buf.readUtf(64), buf.readNbt());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(sessionId, 256);
        buf.writeUtf(action, 64);
        buf.writeNbt(payload != null ? payload : new CompoundTag());
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) return;
            CompoundTag tag = payload != null ? payload : new CompoundTag();
            switch (action) {
                case "request_sync" -> {
                    if (PlayerRateLimiter.allow(serverPlayer, "sync_" + sessionId, SYNC_COOLDOWN_MS)) {
                        WebSessionManager.forceSyncToPlayer(serverPlayer, sessionId);
                    }
                }
                case "fetch" -> {
                    if (PlayerRateLimiter.allow(serverPlayer, "fetch_" + sessionId, FETCH_COOLDOWN_MS)) {
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
                    // レート制限のキーに ElementId を混ぜてはいけない。
                    // クライアントが要素 ID を変えるだけで制限を完全に迂回できた（I-7）
                    if (PlayerRateLimiter.allow(serverPlayer, "ev_" + sessionId, EVENT_COOLDOWN_MS)) {
                        WebSessionManager.handleClientEvent(
                                serverPlayer,
                                sessionId,
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
}
