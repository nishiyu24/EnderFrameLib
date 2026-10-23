package com.nishiyu.enderframelib.network;

import com.nishiyu.enderframelib.network.StateNbtConverter.KeyTable;
import com.nishiyu.enderframelib.network.StateNbtConverter.ReceivedKeyTables;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * Headless Server State: クライアントで UI 画面を初回展開するための S2C パケット。
 *
 * <p>DOM 構造そのものは送信せず、テンプレート識別子（{@code templateId}）と
 * インスタンス識別子（{@code instanceId}）、および初期状態データ（{@code initialState}）のみを転送します。</p>
 *
 * <p>以後の {@link StateDeltaS2CPacket} はインスタンス ID とキー名を文字列で送らず番号で送るため、
 * ここでインスタンスの番号（{@code wireId}）とキー辞書（{@code keys}）の全体を一度だけ送ります。
 * 受信側はデコード時に {@link ReceivedKeyTables} へ登録します。</p>
 *
 * <p>形式: {@code Utf templateId, Utf instanceId, VarInt wireId, VarInt 辞書の件数, Utf キー名..., 番号付きの値}</p>
 */
public record OpenUiS2CPacket(
        String templateId,
        String instanceId,
        int wireId,
        KeyTable keys,
        CompoundTag initialState
) implements CustomPacketPayload {

    public static final Type<OpenUiS2CPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("enderframelib", "open_ui")
    );

    @FunctionalInterface
    public interface IClientOpenUiHandler {
        void onOpenUi(String templateId, String instanceId, CompoundTag initialState);
    }

    /**
     * クライアント起動時（EnderFrameClientInit）に注入されるハンドラ。
     */
    public static volatile IClientOpenUiHandler clientHandler = null;

    public static final StreamCodec<FriendlyByteBuf, OpenUiS2CPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> packet.write(buf),
            OpenUiS2CPacket::read
    );

    public static OpenUiS2CPacket read(FriendlyByteBuf buf) {
        String templateId = buf.readUtf(256);
        String instanceId = buf.readUtf(256);
        int wireId = buf.readVarInt();
        KeyTable keys = KeyTable.of(StateNbtConverter.readNames(buf));
        CompoundTag initialState = new CompoundTag();
        StateNbtConverter.readEntries(buf, keys, initialState);
        ReceivedKeyTables.open(wireId, instanceId, keys);
        return new OpenUiS2CPacket(templateId, instanceId, wireId, keys, initialState);
    }

    public void write(FriendlyByteBuf buf) {
        KeyTable table = keys != null ? keys : KeyTable.EMPTY;
        buf.writeUtf(templateId != null ? templateId : "", 256);
        buf.writeUtf(instanceId != null ? instanceId : "", 256);
        buf.writeVarInt(wireId);
        StateNbtConverter.writeNames(buf, table.names());
        StateNbtConverter.writeEntries(buf, table, initialState != null ? initialState : new CompoundTag());
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (clientHandler != null) {
                clientHandler.onOpenUi(templateId, instanceId, initialState != null ? initialState : new CompoundTag());
            }
        });
    }
}
