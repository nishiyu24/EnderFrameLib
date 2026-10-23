package com.nishiyu.enderframelib.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * Headless Server State: クライアント UI からサーバーの UIState へアクション（ボタンクリック、フォーム操作等）を通知する C2S パケット。
 */
@ApiStatus.Internal
public record UiActionC2SPacket(
        String instanceId,
        String actionName,
        CompoundTag payload
) implements CustomPacketPayload {

    public static final Type<UiActionC2SPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("enderframelib", "ui_action")
    );

    /**
     * 予約アクション名: クライアントで Headless State 画面が閉じられたことを通知し、閲覧者から外す。
     */
    public static final String CLOSE_ACTION = "__close";

    /**
     * 画面クローズ通知パケットを生成します。
     */
    public static UiActionC2SPacket close(String instanceId) {
        return new UiActionC2SPacket(instanceId, CLOSE_ACTION, new CompoundTag());
    }

    public static final StreamCodec<FriendlyByteBuf, UiActionC2SPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> packet.write(buf),
            UiActionC2SPacket::new
    );

    public UiActionC2SPacket(FriendlyByteBuf buf) {
        this(buf.readUtf(256), buf.readUtf(64), buf.readNbt());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(instanceId != null ? instanceId : "", 256);
        buf.writeUtf(actionName != null ? actionName : "", 64);
        buf.writeNbt(payload != null ? payload : new CompoundTag());
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
