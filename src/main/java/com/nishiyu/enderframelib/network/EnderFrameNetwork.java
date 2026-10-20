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

    }
