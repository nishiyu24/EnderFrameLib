package com.nishiyu.enderframelib;

import com.nishiyu.enderframelib.api.config.EnderConfigManager;
import com.nishiyu.enderframelib.client.EnderFrameClientInit;
import com.nishiyu.enderframelib.common.EnderFrameBuiltInConfig;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.network.EnderFrameNetwork;
import com.nishiyu.enderframelib.server.EnderFrameCommands;
import com.nishiyu.enderframelib.server.EnderFrameDataPackManager;
import com.nishiyu.enderframelib.server.EnderFrameServerState;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

@Mod(EnderFrameLib.MODID)
public class EnderFrameLib {
    public static final String MODID = "enderframelib";
    public static final Logger LOGGER = LoggerFactory.getLogger(EnderFrameLib.class);

    public EnderFrameLib(IEventBus modEventBus) {
        // 0. 設定画面のテンプレート解決を注入（api/config から common への依存を持たせないため）
        EnderConfigManager.resourceResolver = path -> {
            Function<String, String> resolver = UiDocumentCompiler.resourceTextResolver;
            return resolver != null ? resolver.apply(path) : "";
        };

        // 1. 組み込み設定クラスの登録（HTML5標準フォームによるMod設定画面自動生成）
        EnderConfigManager.register(MODID, EnderFrameBuiltInConfig.class);

        // 2. ネットワークペイロード登録
        modEventBus.addListener(EnderFrameNetwork::registerPayloads);

        // 3. サーバー側の static 状態の初期化（以前は各クラスの static イニシャライザ任せだった）
        EnderFrameServerState.init();

        // 4. サーバー＆データパックイベント登録
        NeoForge.EVENT_BUS.addListener(this::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogin);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogout);
        NeoForge.EVENT_BUS.addListener(this::onPlayerChangedDimension);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onChunkSent);
        NeoForge.EVENT_BUS.addListener(this::onChunkUnwatched);
        // 統合サーバーでワールドを読み直すと static 状態が丸ごと残っていた（I-6）
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);

        // 5. クライアント環境のみクライアントコールバックとシェーダーを初期化 (Dedicated Serverでクラッシュしない)
        if (FMLEnvironment.dist.isClient()) {
            EnderFrameClientInit.init(modEventBus);
        }

        // 6. 開発用の surface_test ブロック／アイテムは開発環境（runClient / runServer / runGameTestServer）でのみ登録する。
        //    配布ビルドには登録されない（EnderFrameCommands は未登録を許容する）。
        if (!FMLEnvironment.production) {
            if (FMLEnvironment.dist.isClient()) {
                com.nishiyu.enderframelib.gametest.WebScreenGameTest.SurfaceDevHarnessClient.init(modEventBus);
            }
            com.nishiyu.enderframelib.gametest.WebScreenGameTest.SurfaceDevHarness.init(modEventBus);
        }

        LOGGER.info("EnderFrameLib Initialized.");
    }

    private void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new EnderFrameDataPackManager());
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        EnderFrameCommands.register(event.getDispatcher());
    }

    private void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            EnderFrameServerState.onPlayerLogin(sp);
        }
    }

    private void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            EnderFrameServerState.onPlayerLogout(sp);
        }
    }

    private void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            EnderFrameServerState.onPlayerChangedDimension(sp);
        }
    }

    /** テンプレートで動く世界内の面は、チャンクを追跡しているプレイヤーに見せる。 */
    private void onChunkSent(ChunkWatchEvent.Sent event) {
        EnderFrameServerState.onChunkSent(event.getPlayer(), event.getChunk());
    }

    private void onChunkUnwatched(ChunkWatchEvent.UnWatch event) {
        EnderFrameServerState.onChunkUnwatched(event.getPlayer(), event.getLevel(), event.getPos());
    }

    private void onServerTick(ServerTickEvent.Post event) {
        EnderFrameServerState.onServerTick();
    }

    private void onServerStopping(ServerStoppingEvent event) {
        EnderFrameServerState.clearAll();
    }

    /** 停止処理の途中で再登録された状態が残らないよう、停止完了後にもう一度捨てる。 */
    private void onServerStopped(ServerStoppedEvent event) {
        EnderFrameServerState.clearAll();
    }
}
