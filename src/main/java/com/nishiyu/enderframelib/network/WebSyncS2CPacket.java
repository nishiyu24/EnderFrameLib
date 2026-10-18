package com.nishiyu.enderframelib.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

public record WebSyncS2CPacket(String sessionId, String action, CompoundTag data) implements CustomPacketPayload {
    public static final Type<WebSyncS2CPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("enderframelib", "web_sync"));

    @FunctionalInterface
    public interface IClientSyncHandler {
        void onSync(String sessionId, String action, CompoundTag data);
    }

    /**
     * EnderFrameClientInit によってクライアント起動時に注入されるハンドラ
     */
    public static IClientSyncHandler clientHandler = null;

    public static final StreamCodec<FriendlyByteBuf, WebSyncS2CPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> packet.write(buf),
            WebSyncS2CPacket::new
    );

    public WebSyncS2CPacket(FriendlyByteBuf buf) {
        this(buf.readUtf(), buf.readUtf(), buf.readNbt());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(sessionId);
        buf.writeUtf(action);
        buf.writeNbt(data != null ? data : new CompoundTag());
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (data != null && clientHandler != null) {
                clientHandler.onSync(sessionId, action, data);
            }
        });
    }
}