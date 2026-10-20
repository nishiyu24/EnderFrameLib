package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.core.text.model.*;

import com.nishiyu.enderframelib.core.paint.model.*;

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
import com.nishiyu.enderframelib.core.dom.parser.DomUtils;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.Bindings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.nishiyu.enderframelib.network.SubscribeC2SPacket;
import com.nishiyu.enderframelib.network.PubSubUpdateS2CPacket;
import com.nishiyu.enderframelib.network.WebSyncS2CPacket;
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

        Bindings.i18nResolver = ClientLocalEnvProvider::translateKey;
        Bindings.jsonListParser = jsonStr -> {
            try {
                com.google.gson.JsonElement parsedJson = com.google.gson.JsonParser.parseString(jsonStr);
                List<Map<String, String>> result = new ArrayList<>();
                if (parsedJson.isJsonObject()) {
                    for (Map.Entry<String, com.google.gson.JsonElement> entry : parsedJson.getAsJsonObject().entrySet()) {
                        if (!entry.getValue().isJsonPrimitive()) continue;
                        Map<String, String> item = new HashMap<>();
                        item.put("_key", entry.getKey());
                        item.put("_value", entry.getValue().getAsString());
                        result.add(item);
                    }
                } else if (parsedJson.isJsonArray()) {
                    int idx = 0;
                    for (com.google.gson.JsonElement elem : parsedJson.getAsJsonArray()) {
                        Map<String, String> item = new HashMap<>();
                        item.put("_key", String.valueOf(idx++));
                        if (elem.isJsonPrimitive()) {
                            item.put("_value", elem.getAsString());
                        } else if (elem.isJsonObject()) {
                            for (Map.Entry<String, com.google.gson.JsonElement> objEntry : elem.getAsJsonObject().entrySet()) {
                                if (objEntry.getValue().isJsonPrimitive()) {
                                    item.put(objEntry.getKey(), objEntry.getValue().getAsString());
                                }
                            }
                            item.put("_value", elem.toString());
                        } else {
                            continue;
                        }
                        result.add(item);
                    }
                }
                return result;
            } catch (Exception e) {
                return Collections.emptyList();
            }
        };

        DomUtils.innerHtmlParser = UiDocumentCompiler.INNER_HTML_PARSER;
        UiDocumentCompiler.intrinsicSizeResolver = ClientImageRegistry.RESOLVER_ADAPTER;

        ClientImageRegistry.addReflowListener(ClientWebManager::recomputeAllLayouts);

        ClientPubSubManager.subscriptionSender = channel ->
                PacketDistributor.sendToServer(new SubscribeC2SPacket(channel));

        ClientWebManager.setApplyOverridesCallback(ClientScreenInteractionManager::applyLocalOverrides);

        ClientPubSubManager.addChannelListener("ar_sync", WebHudRenderer::handleBinaryTargetDataSync);
        PubSubUpdateS2CPacket.clientHandler = ClientPubSubManager::onReceiveUpdate;

        WebSyncS2CPacket.clientHandler = EnderFrameClientInit::handleWebSyncPacket;
    }

    private static void handleWebSyncPacket(String sessionId, String action, CompoundTag data) {
        switch (action) {
            case "dom_sync" -> {
                if (data == null) break;
                if (data.getBoolean("IsEmpty")) {
                    ClientWebManager.clearSession(sessionId);
                } else if (data.contains("DomTree")) {
                    ClientWebManager.getOrCreateSession(sessionId).applySync(data);
                } else {
                    SessionContext session = ClientWebManager.getSession(sessionId);
                    if (session != null) session.setRequested(false);
                }
            }
            case "dom_mutations" -> {
                SessionContext session = ClientWebManager.getSession(sessionId);
                if (session != null) {
                    session.applyMutations(data);
                }
            }
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

            SessionContext session = ClientWebManager.getOrCreateSession(id);
            session.loadLocalHtml(html, targetW, targetH);
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

            return session.domDocument();
        }

        @Override
        public Document setHud(@Nullable String sessionId, String html) {
            String id = sanitizeSessionId(sessionId, "client_hud");
            SessionContext session = ClientWebManager.getOrCreateSession(id);
            session.loadLocalHtml(html, 1280, 720);
            session.setServerManaged(false);
            WebHudRenderer.customActiveHudSessionId = id;
            return session.domDocument();
        }

        @Nullable
        @Override
        public Document getDocument(String sessionId) {
            if (sessionId == null || sessionId.isEmpty()) return null;
            SessionContext session = ClientWebManager.getSession(sessionId);
            return session != null ? session.domDocument() : null;
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
