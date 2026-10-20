package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.ClientWebManager.DeclarativeBindingManager;
import com.nishiyu.enderframelib.EnderFrameLib;
import com.nishiyu.enderframelib.api.EnderFrameClientAPI;
import com.nishiyu.enderframelib.api.config.EnderConfigManager;
import com.nishiyu.enderframelib.client.gui.AbstractEnderScreen;
import com.nishiyu.enderframelib.client.gui.EnderConfigScreen;
import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.client.renderer.WebFontManager;
import com.nishiyu.enderframelib.client.renderer.WebHudRenderer;
import com.nishiyu.enderframelib.client.renderer.DisplayItemDrawer;
import com.nishiyu.enderframelib.client.renderer.WebSdfShader;
import com.nishiyu.enderframelib.common.EnderFrameBuiltInConfig;
import com.nishiyu.enderframelib.client.renderer.WebMediaManager.ClientImageRegistry;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.css.CssParser;
import com.nishiyu.enderframelib.core.dom.DomUtils;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.dom.TemplateEngine;
import com.nishiyu.enderframelib.network.EnderFrameNetwork.SubscribeC2SPacket;
import com.nishiyu.enderframelib.network.EnderFrameNetwork.PubSubUpdateS2CPacket;
import com.nishiyu.enderframelib.network.EnderFrameNetwork.WebSyncS2CPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import com.nishiyu.enderframelib.api.dom.Document;
import java.util.UUID;
import java.util.function.Consumer;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

public class EnderFrameClientInit {

    public static void init(IEventBus modEventBus) {
        modEventBus.addListener((RegisterClientReloadListenersEvent event) -> {
            event.registerReloadListener((ResourceManagerReloadListener) resourceManager -> {
                WebFontManager.clearCache();
                WebSdfShader.reload();
            });
        });
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
            if (event.getLevel() != null && event.getLevel().isClientSide()) {
                DisplayItemDrawer.clearEntityPreviewCache();
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            DisplayItemDrawer.clearEntityPreviewCache();
            ClientScheduler.clearAll();
        });

        EnderFrameClientAPI.setInstance(EnderFrameClientAPIImpl.INSTANCE);

        EnderFrameBuiltInConfig.runtimeApplier = () -> {
            DisplayItemDrawer.useSdfShader = EnderFrameBuiltInConfig.useSdfShader;
            ClientScreenInteractionManager.setEnableDefaultUiSounds(EnderFrameBuiltInConfig.enableDefaultUiSounds);
        };
        EnderFrameBuiltInConfig.applyToRuntime();

        EnderConfigManager.RegisteredConfig builtInCfg = EnderConfigManager.getConfig(EnderFrameLib.MODID);
        if (builtInCfg != null) {
            builtInCfg.addChangeListener(cfg -> EnderFrameBuiltInConfig.applyToRuntime());
        }
        EnderConfigScreen.registerForMod(EnderFrameLib.MODID);

        UiDocumentCompiler.clientBoxRenderer = (rootBox, rootW, rootH, keyframes, scrollState) -> {
            DisplayListBuilder renderer = new DisplayListBuilder(rootW, rootH, keyframes, scrollState);
            return renderer.render(rootBox);
        };

        CssParser.fontFaceCallback = WebFontManager::extractAndRegisterFontFaces;

        UiDocumentCompiler.clientResourceFallback = path -> {
            ResourceLocation loc = ResourceLocation.tryParse(path);
            if (loc == null) {
                EnderFrameLib.LOGGER.warn("Not a valid resource location, ignoring include: {}", path);
                return "";
            }
            return AbstractEnderScreen.findHtmlResource(loc).orElse("");
        };

        // --- 修正箇所: DomUtils から TemplateEngine に変更 ---
        TemplateEngine.i18nResolver = ClientLocalEnvProvider::translateKey;

        DomUtils.innerHtmlParser = UiDocumentCompiler.INNER_HTML_PARSER;
        UiDocumentCompiler.intrinsicSizeResolver = ClientImageRegistry.RESOLVER_ADAPTER;

        ClientImageRegistry.addReflowListener(ClientWebManager::recomputeAllLayouts);

        ClientPubSubManager.subscriptionSender = channel ->
                PacketDistributor.sendToServer(new SubscribeC2SPacket(channel));

        ClientWebManager.applyOverridesCallback = ClientScreenInteractionManager::applyLocalOverrides;

        DeclarativeBindingManager.setHudCallback(new DeclarativeBindingManager.IHudRegistrationCallback() {
            @Override
            public void registerHudTemplate(String sessionId, String containerId, String origDisplay, HtmlNode templateRoot, List<String> requireNbt) {
                WebHudRenderer.enableHudTemplate(sessionId, containerId, origDisplay, templateRoot, requireNbt);
            }

            @Override
            public void registerTrackerTemplate(String sessionId, HtmlNode templateNode, double radius, double yOffset, String targetType, List<String> requireNbt) {
                WebHudRenderer.setTrackerTemplate(sessionId, templateNode, radius, yOffset, targetType, requireNbt);
            }
        });

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
            case "dom_patch", "dom_mutations" -> ClientWebManager.handlePatchPacket(sessionId, data);
            case "open_screen" -> {
                String title = data.getString("Title");
                int w = data.getInt("Width");
                int h = data.getInt("Height");
                String inlineHtml = data.getString("Html");
                String resLocStr = data.getString("ResourceLocation");
                ResourceLocation resLoc = !resLocStr.isEmpty() ? ResourceLocation.tryParse(resLocStr) : null;

                Minecraft.getInstance().execute(() -> {
                    AbstractEnderScreen screen = new AbstractEnderScreen(Component.literal(title.isEmpty() ? "EnderFrame UI" : title), sessionId, w, h) {
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

    @ApiStatus.Internal
    public static class EnderFrameClientAPIImpl implements EnderFrameClientAPI {

        public static final EnderFrameClientAPIImpl INSTANCE = new EnderFrameClientAPIImpl();

        private EnderFrameClientAPIImpl() {}

        private String sanitizeSessionId(@Nullable String sessionId, String prefix) {
            if (sessionId != null && !sessionId.isEmpty()) {
                return sessionId;
            }
            return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
        }

        @Override
        public Document openScreen(@Nullable String sessionId, String html, int width, int height) {
            String id = sanitizeSessionId(sessionId, "client_screen");
            int targetW = width > 0 ? width : 854;
            int targetH = height > 0 ? height : 480;

            ClientWebManager.loadLocalHtml(id, html, targetW, targetH);
            SessionContext session = ClientWebManager.getOrCreateSession(id);
            session.setServerManaged(false);

            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                mc.execute(() -> {
                    AbstractEnderScreen screen = new AbstractEnderScreen(Component.literal("EnderFrame Client Screen"), id, targetW, targetH) {
                        @Override
                        protected String buildInitialHtml() {
                            return html;
                        }
                    };
                    mc.setScreen(screen);
                });
            }

            return ClientWebManager.getDomDocument(id);
        }

        @Override
        public Document setHud(@Nullable String sessionId, String html) {
            String id = sanitizeSessionId(sessionId, "client_hud");
            ClientWebManager.loadLocalHtml(id, html, 1280, 720);
            SessionContext session = ClientWebManager.getOrCreateSession(id);
            session.setServerManaged(false);
            WebHudRenderer.customActiveHudSessionId = id;
            return ClientWebManager.getDomDocument(id);
        }

        @Nullable
        @Override
        public Document getDocument(String sessionId) {
            if (sessionId == null || sessionId.isEmpty()) return null;
            return ClientWebManager.getDomDocument(sessionId);
        }

        @Override
        public void closeSession(String sessionId) {
            if (sessionId == null || sessionId.isEmpty()) return;
            ClientWebManager.clearSession(sessionId);
        }

        @Override
        public int requestAnimationFrame(String sessionId, Consumer<Double> callback) {
            return ClientScheduler.requestAnimationFrame(sessionId, callback);
        }

        @Override
        public boolean cancelAnimationFrame(int id) {
            return ClientScheduler.cancelAnimationFrame(id);
        }

        @Override
        public int setTimeout(String sessionId, Runnable task, long delayMs) {
            return ClientScheduler.setTimeout(sessionId, task, delayMs);
        }

        @Override
        public boolean clearTimeout(int id) {
            return ClientScheduler.clearTimeout(id);
        }

        @Override
        public int setInterval(String sessionId, Runnable task, long intervalMs) {
            return ClientScheduler.setInterval(sessionId, task, intervalMs);
        }

        @Override
        public boolean clearInterval(int id) {
            return ClientScheduler.clearInterval(id);
        }
    }
}
