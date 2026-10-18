package com.nishiyu.enderframelib.network;

import com.nishiyu.enderframelib.server.PlayerRateLimiter;
import com.nishiyu.enderframelib.server.ServerPubSubManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public record SubscribeC2SPacket(
        String channel,
        boolean isUnsubscribe,
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

    private static final long SUBSCRIBE_COOLDOWN_MS = 200L;
    private static final long AR_SUBSCRIBE_COOLDOWN_MS = 100L;

    /** AR 購読要求の内容を表すレート制限キー。 */
    private String arSubscriptionKey() {
        return Integer.toHexString(java.util.Objects.hash(
                needsHud(), needsTracker(), radius(), targetType(), hudNbtPaths(), trackerNbtPaths()));
    }

    public SubscribeC2SPacket(String channel) {
        this(channel, false, false, false, 0.0, "", List.of(), List.of());
    }

    public SubscribeC2SPacket(String channel, boolean isUnsubscribe) {
        this(channel, isUnsubscribe, false, false, 0.0, "", List.of(), List.of());
    }

    public SubscribeC2SPacket(FriendlyByteBuf buf) {
        this(
                buf.readUtf(256),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean(),
                sanitizeRadius(buf.readDouble()),
                buf.readUtf(64),
                readLimitedPaths(buf),
                readLimitedPaths(buf)
        );
    }

    private static double sanitizeRadius(double r) {
        if (Double.isNaN(r) || Double.isInfinite(r)) return 0.0;
        return Math.clamp(r, 0.0, 64.0);
    }

    private static List<String> readLimitedPaths(FriendlyByteBuf buf) {
        int size = Math.min(buf.readVarInt(), 16); // 最大16パスに制限（DoS対策）
        List<String> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(buf.readUtf(128)); // パス長も最大128文字に制限
        }
        return list;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(channel(), 256);
        buf.writeBoolean(isUnsubscribe());
        buf.writeBoolean(needsHud());
        buf.writeBoolean(needsTracker());
        buf.writeDouble(radius());
        buf.writeUtf(targetType(), 64);
        writeLimitedPaths(buf, hudNbtPaths());
        writeLimitedPaths(buf, trackerNbtPaths());
    }

    private static void writeLimitedPaths(FriendlyByteBuf buf, List<String> paths) {
        int size = Math.min(paths.size(), 16);
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            buf.writeUtf(paths.get(i), 128);
        }
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) return;

            if (channel() != null && !channel().isEmpty()) {
                // 購読/解除はレート制限が無く、購読の張り直しを無制限に送れた（I-7）
                if (!PlayerRateLimiter.allow(serverPlayer, "sub_" + channel(), SUBSCRIBE_COOLDOWN_MS)) return;
                if (isUnsubscribe()) {
                    ServerPubSubManager.unsubscribe(serverPlayer, channel());
                } else {
                    ServerPubSubManager.subscribe(serverPlayer, channel());
                }
            } else {
                // キーに購読内容のハッシュを混ぜる。同じ要求の連打だけを弾き、
                // 内容が変わった要求（＝落とすと HUD が止まる）は通す
                if (!PlayerRateLimiter.allow(serverPlayer, "ar_sub_" + arSubscriptionKey(), AR_SUBSCRIBE_COOLDOWN_MS)) return;
                ServerPubSubManager.updateArSubscription(
                        serverPlayer, needsHud(), needsTracker(), radius(), targetType(), hudNbtPaths(), trackerNbtPaths()
                );
            }
        });
    }
}