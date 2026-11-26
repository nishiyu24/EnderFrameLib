package com.nishiyu.enderframelib.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public record WebActionC2SPacket(String sessionId, String action, CompoundTag payload) implements CustomPacketPayload {
    public static final Type<WebActionC2SPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("enderframelib", "web_action"));

    public static final StreamCodec<FriendlyByteBuf, WebActionC2SPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> packet.write(buf),
            WebActionC2SPacket::new
    );

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
}
