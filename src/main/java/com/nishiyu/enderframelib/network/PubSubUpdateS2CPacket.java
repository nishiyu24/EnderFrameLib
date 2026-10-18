package com.nishiyu.enderframelib.network;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record PubSubUpdateS2CPacket(String channel, boolean isFull, Map<String, Object> diffData) implements CustomPacketPayload {
    public static final Type<PubSubUpdateS2CPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("enderframelib", "pubsub_update"));

    @FunctionalInterface
    public interface IClientPubSubHandler {
        void onUpdate(String channel, boolean isFull, Map<String, Object> diffData);
    }

    public static IClientPubSubHandler clientHandler = null;

    public static final StreamCodec<FriendlyByteBuf, PubSubUpdateS2CPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> packet.write(buf),
            PubSubUpdateS2CPacket::new
    );

    /** 1 つのマップ / リストに許すエントリ数。 */
    private static final int MAX_ENTRIES = 1024;
    /** 入れ子の深さ。これを超えるとデコードでスタックを使い切られる。 */
    private static final int MAX_DEPTH = 8;
    private static final int MAX_CHANNEL_LENGTH = 128;
    private static final int MAX_KEY_LENGTH = 256;
    private static final int MAX_STRING_LENGTH = 32767;

    public PubSubUpdateS2CPacket(FriendlyByteBuf buf) {
        this(buf.readUtf(MAX_CHANNEL_LENGTH), buf.readBoolean(), readMap(buf, 0));
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(channel(), MAX_CHANNEL_LENGTH);
        buf.writeBoolean(isFull());
        writeMap(buf, diffData(), 0);
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (clientHandler != null) {
                clientHandler.onUpdate(channel(), isFull(), diffData());
            }
        });
    }

    /** 送信側も受信側と同じ上限を守る。守らないと自分が送った物を相手が復号できない。 */
    private static void writeMap(FriendlyByteBuf buf, Map<String, Object> map, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("PubSub payload nested deeper than " + MAX_DEPTH);
        }
        int size = Math.min(map.size(), MAX_ENTRIES);
        buf.writeVarInt(size);
        int written = 0;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (written++ >= size) break;
            buf.writeUtf(entry.getKey(), MAX_KEY_LENGTH);
            writeValue(buf, entry.getValue(), depth + 1);
        }
    }

    /**
     * 受信側のデコード。悪意あるサーバーがエントリ数と入れ子の深さで
     * クライアントを OOM / スタックオーバーフローに追い込めたため上限を入れている（I-8）。
     */
    private static Map<String, Object> readMap(FriendlyByteBuf buf, int depth) {
        if (depth > MAX_DEPTH) {
            throw new DecoderException("PubSub payload nested deeper than " + MAX_DEPTH);
        }
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_ENTRIES) {
            throw new DecoderException("PubSub map has " + size + " entries (max " + MAX_ENTRIES + ")");
        }
        Map<String, Object> map = new HashMap<>(size);
        for (int i = 0; i < size; i++) {
            map.put(buf.readUtf(MAX_KEY_LENGTH), readValue(buf, depth + 1));
        }
        return map;
    }

    private static void writeValue(FriendlyByteBuf buf, Object value, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("PubSub payload nested deeper than " + MAX_DEPTH);
        }
        switch (value) {
            case null -> buf.writeByte(0);
            case String s -> { buf.writeByte(1); buf.writeUtf(truncate(s), MAX_STRING_LENGTH); }
            case Integer i -> { buf.writeByte(2); buf.writeVarInt(i); }
            case Double d -> { buf.writeByte(3); buf.writeDouble(d); }
            case Boolean b -> { buf.writeByte(4); buf.writeBoolean(b); }
            case Map<?, ?> m -> {
                buf.writeByte(5);
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) m;
                writeMap(buf, map, depth + 1);
            }
            case Long l -> { buf.writeByte(6); buf.writeLong(l); }
            case Float f -> { buf.writeByte(7); buf.writeFloat(f); }
            case List<?> list -> {
                buf.writeByte(8);
                int size = Math.min(list.size(), MAX_ENTRIES);
                buf.writeVarInt(size);
                for (int i = 0; i < size; i++) writeValue(buf, list.get(i), depth + 1);
            }
            default -> {
                buf.writeByte(1);
                buf.writeUtf(truncate(value.toString()), MAX_STRING_LENGTH);
            }
        }
    }

    private static String truncate(String s) {
        return s.length() <= MAX_STRING_LENGTH ? s : s.substring(0, MAX_STRING_LENGTH);
    }

    private static Object readValue(FriendlyByteBuf buf, int depth) {
        if (depth > MAX_DEPTH) {
            throw new DecoderException("PubSub payload nested deeper than " + MAX_DEPTH);
        }
        byte type = buf.readByte();
        return switch (type) {
            case 1 -> buf.readUtf(MAX_STRING_LENGTH);
            case 2 -> buf.readVarInt();
            case 3 -> buf.readDouble();
            case 4 -> buf.readBoolean();
            case 5 -> readMap(buf, depth + 1);
            case 6 -> buf.readLong();
            case 7 -> buf.readFloat();
            case 8 -> {
                int size = buf.readVarInt();
                if (size < 0 || size > MAX_ENTRIES) {
                    throw new DecoderException("PubSub list has " + size + " entries (max " + MAX_ENTRIES + ")");
                }
                List<Object> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) list.add(readValue(buf, depth + 1));
                yield list;
            }
            default -> null;
        };
    }
}