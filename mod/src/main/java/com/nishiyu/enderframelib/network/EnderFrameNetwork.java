package com.nishiyu.enderframelib.network;

import com.nishiyu.enderframelib.EnderFrameLib;
import com.nishiyu.enderframelib.server.ServerPayloadHandler;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.jetbrains.annotations.ApiStatus;

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
        registrar.playToClient(
                OpenUiS2CPacket.TYPE,
                OpenUiS2CPacket.STREAM_CODEC,
                OpenUiS2CPacket::handle
        );
        registrar.playToClient(
                StateDeltaS2CPacket.TYPE,
                StateDeltaS2CPacket.STREAM_CODEC,
                StateDeltaS2CPacket::handle
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
        registrar.playToServer(
                UiActionC2SPacket.TYPE,
                UiActionC2SPacket.STREAM_CODEC,
                ServerPayloadHandler::handleUiAction
        );
    }
}
