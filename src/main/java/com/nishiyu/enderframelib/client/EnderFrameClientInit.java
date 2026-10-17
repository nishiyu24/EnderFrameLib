package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.EnderFrameLib;
import com.nishiyu.enderframelib.api.config.EnderConfigManager;
import com.nishiyu.enderframelib.client.binding.ClientPubSubManager;
import com.nishiyu.enderframelib.client.binding.DeclarativeBindingManager;
import com.nishiyu.enderframelib.client.gui.AbstractWebScreen;
import com.nishiyu.enderframelib.client.gui.EnderConfigScreen;
import com.nishiyu.enderframelib.client.renderer.UIRenderer;
import com.nishiyu.enderframelib.client.renderer.WebFontManager;
import com.nishiyu.enderframelib.client.renderer.WebHudRenderer;
import com.nishiyu.enderframelib.client.renderer.WebRenderCore;
import com.nishiyu.enderframelib.common.EnderFrameBuiltInConfig;
import com.nishiyu.enderframelib.common.ServerImageRegistry;
import com.nishiyu.enderframelib.common.UIParser;
import com.nishiyu.enderframelib.core.css.CssParser;
import com.nishiyu.enderframelib.core.dom.DomUtils;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.network.c2s.SubscribeC2SPacket;
import com.nishiyu.enderframelib.network.s2c.PubSubUpdateS2CPacket;
import com.nishiyu.enderframelib.network.s2c.WebSyncS2CPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * クライアント環境でのみロード・実行される初期化クラス
 */
public class EnderFrameClientInit {

    public static void init(IEventBus modEventBus) {
        // 0. ビルトイン設定のランタイム反映フック登録と、Mod一覧「Config」ボタンへの設定画面登録
        EnderFrameBuiltInConfig.runtimeApplier = () -> {
            WebRenderCore.useSdfShader = EnderFrameBuiltInConfig.useSdfShader;
            ClientScreenInteractionManager.enableDefaultUiSounds = EnderFrameBuiltInConfig.enableDefaultUiSounds;
        };
        EnderFrameBuiltInConfig.applyToRuntime();

        EnderConfigManager.RegisteredConfig builtInCfg = EnderConfigManager.getConfig(EnderFrameLib.MODID);
        if (builtInCfg != null) {
            builtInCfg.addChangeListener(cfg -> EnderFrameBuiltInConfig.applyToRuntime());
        }
        EnderConfigScreen.registerForMod(EnderFrameLib.MODID);

        // 1. UIParser / CssParser / DomUtils のクライアント専用コールバック注入
        UIParser.clientBoxRenderer = (rootBox, rootW, rootH, keyframes, scrollState) -> {
            UIRenderer renderer = new UIRenderer(rootW, rootH, keyframes, scrollState);
            return renderer.render(rootBox);
        };

        CssParser.fontFaceCallback = WebFontManager::extractAndRegisterFontFaces;

        UIParser.clientResourceFallback = path -> {
            ResourceLocation loc = ResourceLocation.tryParse(path);
            if (loc != null) {
                String loaded = AbstractWebScreen.loadHtmlResource(loc);
                if (loaded != null && !loaded.contains("Failed to load HTML resource:")) {
                    return loaded;
                }
            }
            return "";
        };

        DomUtils.i18nResolver = ClientLocalEnvProvider::translateKey;

        // --- 追加: core側の依存切り離し用フックの注入 ---
        DomUtils.innerHtmlParser = UIParser.INNER_HTML_PARSER;
        UIParser.intrinsicSizeResolver = ServerImageRegistry.RESOLVER_ADAPTER;

        // 2. 画像サイズ確定時のクライアント自動再レイアウト登録
        ServerImageRegistry.addReflowListener(ClientWebManager::recomputeAllLayouts);

        // 3. PubSub・ローカルオーバーライド・HUDテンプレート結線（マルチリスナー登録）
        ClientPubSubManager.subscriptionSender = channel ->
                PacketDistributor.sendToServer(new SubscribeC2SPacket(channel));

        ClientWebManager.applyOverridesCallback = ClientScreenInteractionManager::applyLocalOverrides;
        ClientWebManager.addSessionClearedListener(WebHudRenderer::clearSession);

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

        // 4. S2C パケットハンドラのクライアント側結線
        PubSubUpdateS2CPacket.clientHandler = (channel, isFull, diffData) -> {
            if ("ar_sync".equals(channel)) {
                WebHudRenderer.handleBinaryTargetDataSync(diffData);
            } else {
                ClientPubSubManager.onReceiveUpdate(channel, isFull, diffData);
            }
        };

        WebSyncS2CPacket.clientHandler = EnderFrameClientInit::handleWebSyncPacket;
    }

    private static void handleWebSyncPacket(String sessionId, String action, CompoundTag data) {
        switch (action) {
            case "dom_sync" -> ClientWebManager.handleSyncPacket(sessionId, data);
            case "dom_patch" -> ClientWebManager.handlePatchPacket(sessionId, data);
            case "open_screen" -> {
                String title = data.getString("Title");
                int w = data.getInt("Width");
                int h = data.getInt("Height");
                String inlineHtml = data.getString("Html");
                String resLocStr = data.getString("ResourceLocation");
                ResourceLocation resLoc = !resLocStr.isEmpty() ? ResourceLocation.tryParse(resLocStr) : null;

                Minecraft.getInstance().execute(() -> {
                    AbstractWebScreen screen = new AbstractWebScreen(Component.literal(title.isEmpty() ? "EnderFrame UI" : title), sessionId, w, h) {
                        @Override
                        protected String buildInitialHtml() {
                            return !inlineHtml.isEmpty() ? inlineHtml : null;
                        }
                    };
                    if (resLoc != null) {
                        screen.setHtmlResource(resLoc);
                    }
                    Minecraft.getInstance().setScreen(screen);
                });
            }
            case "set_hud" -> {
                String inlineHtml = data.getString("Html");
                String resLocStr = data.getString("ResourceLocation");
                ResourceLocation resLoc = !resLocStr.isEmpty() ? ResourceLocation.tryParse(resLocStr) : null;
                Minecraft.getInstance().execute(() -> {
                    ClientWebManager.initOrBindSession(sessionId, inlineHtml, resLoc, 1280, 720, false);
                    WebHudRenderer.customActiveHudSessionId = sessionId;
                });
            }
            case "clear_hud" -> Minecraft.getInstance().execute(() -> {
                if (!WebHudRenderer.customActiveHudSessionId.isEmpty()) {
                    ClientWebManager.clearSession(WebHudRenderer.customActiveHudSessionId);
                    WebHudRenderer.customActiveHudSessionId = "";
                }
            });
        }
    }
}