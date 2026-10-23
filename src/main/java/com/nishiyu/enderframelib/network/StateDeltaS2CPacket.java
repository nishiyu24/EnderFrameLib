package com.nishiyu.enderframelib.network;

import com.nishiyu.enderframelib.network.StateNbtConverter.KeyTable;
import com.nishiyu.enderframelib.network.StateNbtConverter.ReceivedKeyTables;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Consumer;

/**
 * Headless Server State: 変更された状態（Key-Value）のみを通知する高頻度・最小フットプリント S2C パケット。
 *
 * <p>DOM 変異レコードやタグ全体ではなく、直前の tick から変化したキーとプリミティブ値のみを転送します。
 * インスタンスは {@link OpenUiS2CPacket} で渡した番号（{@code wireId}）、キーは辞書の番号で送り、
 * 前回の差分以降に辞書へ増えたキー（番号 {@code newKeysFrom} 以降）だけ名前を添えます。</p>
 *
 * <p>形式: {@code VarInt wireId, byte flags, [VarInt from, VarInt 件数, Utf キー名...], 番号付きの値,
 * [VarInt 件数, VarInt 削除した番号...]}。flags は {@link #FLAG_NEW_KEYS} / {@link #FLAG_REMOVED} /
 * {@link #FLAG_CLOSED}。</p>
 *
 * <p>{@code instanceId} と {@code deltas}（キー名の {@link CompoundTag}。削除は {@code __removed} の一覧）は
 * 送信側では元の値、受信側ではデコード時に辞書で名前へ戻した値です。</p>
 */
public record StateDeltaS2CPacket(
        int wireId,
        String instanceId,
        KeyTable keys,
        int newKeysFrom,
        CompoundTag deltas,
        boolean closed
) implements CustomPacketPayload {

    static final int FLAG_NEW_KEYS = 1;
    static final int FLAG_REMOVED = 1 << 1;
    /** サーバーで状態が破棄された（ブロックの破壊など）。クライアントは画面を閉じる。 */
    static final int FLAG_CLOSED = 1 << 2;

    public static final Type<StateDeltaS2CPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("enderframelib", "state_delta")
    );

    @FunctionalInterface
    public interface IClientDeltaHandler {
        void onDelta(String instanceId, CompoundTag deltas);
    }

    /**
     * クライアント起動時（EnderFrameClientInit）に注入されるハンドラ。
     */
    public static volatile IClientDeltaHandler clientHandler = null;

    /**
     * サーバーで状態が破棄されたときに、そのインスタンス ID を受け取るハンドラ（EnderFrameClientInit が注入）。
     */
    public static volatile Consumer<String> clientCloseHandler = null;

    public static final StreamCodec<FriendlyByteBuf, StateDeltaS2CPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> packet.write(buf),
            StateDeltaS2CPacket::read
    );

    /** 状態の破棄を閲覧者へ知らせるパケット。 */
    public static StateDeltaS2CPacket closed(int wireId, String instanceId) {
        return new StateDeltaS2CPacket(wireId, instanceId, KeyTable.EMPTY, 0, new CompoundTag(), true);
    }

    public static StateDeltaS2CPacket read(FriendlyByteBuf buf) {
        int wireId = buf.readVarInt();
        int flags = buf.readUnsignedByte();

        ReceivedKeyTables.Entry entry;
        if ((flags & FLAG_NEW_KEYS) != 0) {
            int from = buf.readVarInt();
            List<String> names = StateNbtConverter.readNames(buf);
            entry = ReceivedKeyTables.define(wireId, from, names);
        } else {
            entry = ReceivedKeyTables.get(wireId);
        }
        // 開く前の差分（取りこぼし）は中身を読み捨てる。インスタンス ID が空なのでハンドラは何もしない
        KeyTable keys = entry != null ? entry.keys() : KeyTable.EMPTY;
        String instanceId = entry != null ? entry.instanceId() : "";

        CompoundTag deltas = new CompoundTag();
        StateNbtConverter.readEntries(buf, keys, deltas);

        if ((flags & FLAG_REMOVED) != 0) {
            int count = StateNbtConverter.readCount(buf);
            ListTag removed = new ListTag();
            for (int i = 0; i < count; i++) {
                String name = keys.nameAt(buf.readVarInt());
                if (name != null) removed.add(StringTag.valueOf(name));
            }
            deltas.put(StateNbtConverter.REMOVED_KEY, removed);
        }

        boolean closed = (flags & FLAG_CLOSED) != 0;
        if (closed) ReceivedKeyTables.drop(wireId);
        return new StateDeltaS2CPacket(wireId, instanceId, keys, keys.size(), deltas, closed);
    }

    public void write(FriendlyByteBuf buf) {
        KeyTable table = keys != null ? keys : KeyTable.EMPTY;
        CompoundTag values = deltas != null ? deltas : new CompoundTag();
        List<String> newKeys = newKeysFrom < table.size()
                ? table.names().subList(Math.max(0, newKeysFrom), table.size())
                : List.of();
        ListTag removed = values.getList(StateNbtConverter.REMOVED_KEY, Tag.TAG_STRING);

        int flags = 0;
        if (!newKeys.isEmpty()) flags |= FLAG_NEW_KEYS;
        if (!removed.isEmpty()) flags |= FLAG_REMOVED;
        if (closed) flags |= FLAG_CLOSED;

        buf.writeVarInt(wireId);
        buf.writeByte(flags);
        if (!newKeys.isEmpty()) {
            buf.writeVarInt(table.size() - newKeys.size());
            StateNbtConverter.writeNames(buf, newKeys);
        }
        StateNbtConverter.writeEntries(buf, table, values);
        if (!removed.isEmpty()) {
            buf.writeVarInt(removed.size());
            for (int i = 0; i < removed.size(); i++) {
                buf.writeVarInt(StateNbtConverter.requireIndex(table, removed.getString(i)));
            }
        }
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (instanceId == null || instanceId.isEmpty()) return;
            if (closed) {
                if (clientCloseHandler != null) clientCloseHandler.accept(instanceId);
            } else if (clientHandler != null) {
                clientHandler.onDelta(instanceId, deltas != null ? deltas : new CompoundTag());
            }
        });
    }
}
