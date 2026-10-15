package com.nishiyu.enderframelib.network.s2c;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
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

    public PubSubUpdateS2CPacket(FriendlyByteBuf buf) {
        this(buf.readUtf(), buf.readBoolean(), readMap(buf));
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(channel());
        buf.writeBoolean(isFull());
        writeMap(buf, diffData());
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

    private static void writeMap(FriendlyByteBuf buf, Map<String, Object> map) {
        buf.writeVarInt(map.size());
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            buf.writeUtf(entry.getKey());
            writeValue(buf, entry.getValue());
        }
    }

    private static Map<String, Object> readMap(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        Map<String, Object> map = new HashMap<>(size);
        for (int i = 0; i < size; i++) {
            map.put(buf.readUtf(), readValue(buf));
        }
        return map;
    }

    private static void writeValue(FriendlyByteBuf buf, Object value) {
        switch (value) {
            case null -> buf.writeByte(0);
            case String s -> {
                buf.writeByte(1);
                buf.writeUtf(s);
            }
            case Integer i -> {
                buf.writeByte(2);
                buf.writeVarInt(i);
            }
            case Double d -> {
                buf.writeByte(3);
                buf.writeDouble(d);
            }
            case Boolean b -> {
                buf.writeByte(4);
                buf.writeBoolean(b);
            }
            case Map<?, ?> m -> {
                buf.writeByte(5);
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) m;
                writeMap(buf, map);
            }
            default -> {
                buf.writeByte(1);
                buf.writeUtf(value.toString());
            }
        }
    }

    private static Object readValue(FriendlyByteBuf buf) {
        byte type = buf.readByte();
        return switch (type) {
            case 1 -> buf.readUtf();
            case 2 -> buf.readVarInt();
            case 3 -> buf.readDouble();
            case 4 -> buf.readBoolean();
            case 5 -> readMap(buf);
            default -> null;
        };
    }
}