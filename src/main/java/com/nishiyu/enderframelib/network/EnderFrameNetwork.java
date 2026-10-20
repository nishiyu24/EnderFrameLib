package com.nishiyu.enderframelib.network;

import com.nishiyu.enderframelib.EnderFrameLib;
import com.nishiyu.enderframelib.server.ServerPayloadHandler;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.jetbrains.annotations.ApiStatus;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public class EnderFrameNetwork {

    private EnderFrameNetwork() {}

    /**
     * プロトコルバージョン。
     * ハードコードした "1.0.0" のままだと、パケットの形を変えても
     * 新旧クライアントが同じバージョンを名乗って接続できてしまう。
     */
    private static String protocolVersion() {
        return ModList.get().getModContainerById(EnderFrameLib.MODID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("0.0.0");
    }

    public static void registerPayloads(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(EnderFrameLib.MODID).versioned(protocolVersion());

        registrar.playToClient(
                WebSyncS2CPacket.TYPE,
                WebSyncS2CPacket.STREAM_CODEC,
                WebSyncS2CPacket::handle
        );
        registrar.playToClient(
                PubSubUpdateS2CPacket.TYPE,
                PubSubUpdateS2CPacket.STREAM_CODEC,
                PubSubUpdateS2CPacket::handle
        );

        registrar.playToServer(
                WebActionC2SPacket.TYPE,
                WebActionC2SPacket.STREAM_CODEC,
                ServerPayloadHandler::handleWebAction
        );
        registrar.playToServer(
                SubscribeC2SPacket.TYPE,
                SubscribeC2SPacket.STREAM_CODEC,
                ServerPayloadHandler::handleSubscribe
        );
    }

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
    }

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
}
