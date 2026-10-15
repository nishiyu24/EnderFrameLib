package com.nishiyu.enderframelib.network;

import com.nishiyu.enderframelib.network.c2s.SubscribeC2SPacket;
import com.nishiyu.enderframelib.network.c2s.WebActionC2SPacket;
import com.nishiyu.enderframelib.network.s2c.PubSubUpdateS2CPacket;
import com.nishiyu.enderframelib.network.s2c.WebSyncS2CPacket;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class EnderFrameNetwork {

    @SubscribeEvent
    public static void registerPayloads(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("enderframelib").versioned("1.0.0");

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
                WebActionC2SPacket::handle
        );
        registrar.playToServer(
                SubscribeC2SPacket.TYPE,
                SubscribeC2SPacket.STREAM_CODEC,
                SubscribeC2SPacket::handle
        );
    }
}