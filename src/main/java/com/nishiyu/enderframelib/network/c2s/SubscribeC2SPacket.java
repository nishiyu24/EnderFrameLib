package com.nishiyu.enderframelib.network.c2s;

import com.nishiyu.enderframelib.server.ServerPubSubManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public record SubscribeC2SPacket(
        String channel,
        boolean needsHud,
        boolean needsTracker,
        double radius,
        String targetType,
        List<String> hudNbtPaths,
        List<String> trackerNbtPaths
) implements CustomPacketPayload {

    public static final Type<SubscribeC2SPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("enderframelib", "subscribe_pubsub"));

    public static final StreamCodec<FriendlyByteBuf, SubscribeC2SPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> packet.write(buf),
            SubscribeC2SPacket::new
    );

    public SubscribeC2SPacket(String channel) {
        this(channel, false, false, 0.0, "", List.of(), List.of());
    }

    public SubscribeC2SPacket(FriendlyByteBuf buf) {
        this(
                buf.readUtf(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readDouble(),
                buf.readUtf(),
                buf.readList(FriendlyByteBuf::readUtf),
                buf.readList(FriendlyByteBuf::readUtf)
        );
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(channel());
        buf.writeBoolean(needsHud());
        buf.writeBoolean(needsTracker());
        buf.writeDouble(radius());
        buf.writeUtf(targetType());
        buf.writeCollection(hudNbtPaths(), FriendlyByteBuf::writeUtf);
        buf.writeCollection(trackerNbtPaths(), FriendlyByteBuf::writeUtf);
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                if (channel() != null && !channel().isEmpty()) {
                    ServerPubSubManager.subscribe(serverPlayer, channel());
                } else {
                    // channel が空の場合は AR 購読状態の更新（false/false による購読解除も含む）
                    ServerPubSubManager.updateArSubscription(serverPlayer, needsHud(), needsTracker(), radius(), targetType(), hudNbtPaths(), trackerNbtPaths());
                }
            }
        });
    }
}