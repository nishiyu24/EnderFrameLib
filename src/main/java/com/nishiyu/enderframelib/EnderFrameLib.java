package com.nishiyu.enderframelib;

import com.nishiyu.enderframelib.client.EnderFrameClientInit;
import com.nishiyu.enderframelib.network.EnderFrameNetwork;
import com.nishiyu.enderframelib.server.EnderFrameCommands;
import com.nishiyu.enderframelib.server.EnderFrameDataPackManager;
import com.nishiyu.enderframelib.server.ServerPubSubManager;
import com.nishiyu.enderframelib.server.ServerTargetSyncHandler;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(EnderFrameLib.MODID)
public class EnderFrameLib {
    public static final String MODID = "enderframelib";
    public static final Logger LOGGER = LoggerFactory.getLogger(EnderFrameLib.class);

    public EnderFrameLib(IEventBus modEventBus) {
        // 1. ネットワークペイロード登録
        modEventBus.addListener(EnderFrameNetwork::registerPayloads);

        // 2. サーバー＆データパックイベント登録
        NeoForge.EVENT_BUS.addListener(this::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogout);

        // 3. クライアント環境のみクライアントコールバックとシェーダーを初期化 (Dedicated Serverでクラッシュしない)
        if (FMLEnvironment.dist.isClient()) {
            EnderFrameClientInit.init(modEventBus);
        }

        LOGGER.info("EnderFrameLib Initialized.");
    }

    private void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new EnderFrameDataPackManager());
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        EnderFrameCommands.register(event.getDispatcher());
    }

    private void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            ServerPubSubManager.removePlayerAll(sp);
            ServerTargetSyncHandler.removePlayer(sp.getUUID());
        }
    }
}