package com.nishiyu.enderframelib.network;

import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.binding.ClientPubSubManager;
import com.nishiyu.enderframelib.client.binding.DeclarativeBindingManager;
import com.nishiyu.enderframelib.client.renderer.WebHudRenderer;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.network.c2s.SubscribeC2SPacket;
import com.nishiyu.enderframelib.network.c2s.WebActionC2SPacket;
import com.nishiyu.enderframelib.network.s2c.PubSubUpdateS2CPacket;
import com.nishiyu.enderframelib.network.s2c.WebSyncS2CPacket;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.List;

public class EnderFrameNetwork {

    /**
     * MOD初期化時に呼び出して内部コールバックを結線する
     */
    public static void initCommonCallbacks() {
        ClientPubSubManager.subscriptionSender = (channel) ->
                PacketDistributor.sendToServer(new SubscribeC2SPacket(channel));

        ClientWebManager.applyOverridesCallback = ClientScreenInteractionManager::applyLocalOverrides;

        ClientWebManager.sessionClearedCallback = WebHudRenderer::clearSession;

        DeclarativeBindingManager.hudCallback = new DeclarativeBindingManager.IHudRegistrationCallback() {
            @Override
            public void registerHudTemplate(String sessionId, String containerId, String origDisplay, HtmlNode templateRoot, List<String> requireNbt) {
                WebHudRenderer.enableHudTemplate(sessionId, containerId, origDisplay, templateRoot, requireNbt);
            }

            @Override
            public void registerTrackerTemplate(String sessionId, HtmlNode templateNode, double radius, double yOffset, String targetType, List<String> requireNbt) {
                WebHudRenderer.setTrackerTemplate(sessionId, templateNode, radius, yOffset, targetType, requireNbt);
            }
        };
    }

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