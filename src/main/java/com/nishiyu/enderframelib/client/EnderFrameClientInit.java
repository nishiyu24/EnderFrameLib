package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import com.nishiyu.enderframelib.client.renderer.OutlineTextRenderer;
import com.nishiyu.enderframelib.core.text.model.*;

import com.nishiyu.enderframelib.core.paint.model.*;

import com.nishiyu.enderframelib.EnderFrameLib;
import com.nishiyu.enderframelib.api.ClientSession;
import com.nishiyu.enderframelib.api.EnderFrameClientAPI;
import com.nishiyu.enderframelib.api.Ui;
import com.nishiyu.enderframelib.api.config.EnderConfigManager;
import com.nishiyu.enderframelib.client.gui.AbstractEnderScreen;
import com.nishiyu.enderframelib.client.gui.EnderConfigScreen;
import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.client.renderer.WebFontManager;
import com.nishiyu.enderframelib.client.renderer.WebHudRenderer;
import com.nishiyu.enderframelib.client.renderer.DisplayItemDrawer;
import com.nishiyu.enderframelib.client.renderer.WebSdfShader;
import com.nishiyu.enderframelib.common.EnderFrameBuiltInConfig;
import com.nishiyu.enderframelib.client.renderer.WebMediaManager;
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
import com.nishiyu.enderframelib.client.template.ClientTemplateManager;
import com.nishiyu.enderframelib.network.OpenUiS2CPacket;
import com.nishiyu.enderframelib.network.StateDeltaS2CPacket;
import com.nishiyu.enderframelib.network.WebSyncS2CPacket;
import com.nishiyu.enderframelib.network.StateNbtConverter;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
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
                OutlineTextRenderer.clear();
                WebSdfShader.reload();
                ClientTemplateManager.clearCache();
            });
        });
        // システムの書体の一覧は最初の画面を開くときに要る。読み込み（数十〜百数十ミリ秒）を起動中に裏で済ませておく
        Thread fontScan = new Thread(() -> {
            try {
                SystemFonts.system().resolve(null);
            } catch (Throwable t) {
                EnderFrameLib.LOGGER.warn("Could not scan system fonts: {}", t.toString());
            }
        }, "EnderFrame-FontScan");
        fontScan.setDaemon(true);
        fontScan.start();
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
            if (event.getLevel() != null && event.getLevel().isClientSide()) {
                DisplayItemDrawer.clearEntityPreviewCache();
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            DisplayItemDrawer.clearEntityPreviewCache();
            ClientScheduler.clearAll();
            StateNbtConverter.clearReceivedKeyTables();
        });

        EnderFrameClientAPI.setInstance(EnderFrameClientAPIImpl.INSTANCE);

        EnderFrameBuiltInConfig.runtimeApplier = () -> {
            DisplayItemDrawer.useSdfShader = EnderFrameBuiltInConfig.useSdfShader;
            ClientScreenInteractionManager.setEnableDefaultUiSounds(EnderFrameBuiltInConfig.enableDefaultUiSounds);
            WebMediaManager.allowExternalMedia = EnderFrameBuiltInConfig.allowExternalMedia;
            WebMediaManager.allowYtDlp = EnderFrameBuiltInConfig.allowYtDlp;
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
        Bindings.i18nFormatter = ClientLocalEnvProvider::translateKey;
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
        OpenUiS2CPacket.clientHandler = EnderFrameClientInit::handleOpenUiPacket;
        StateDeltaS2CPacket.clientHandler = EnderFrameClientInit::handleStateDeltaPacket;
        StateDeltaS2CPacket.clientCloseHandler = EnderFrameClientInit::handleStateClosed;
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
            case "open_desktop" -> Minecraft.getInstance().execute(() -> {
                String initialWindow = data.getString("InitialWindow");
                com.nishiyu.enderframelib.client.gui.desktop.LinuxDesktopScreen screen =
                        new com.nishiyu.enderframelib.client.gui.desktop.LinuxDesktopScreen();
                if (!initialWindow.isEmpty()) {
                    screen.openAndFocusWindow(initialWindow);
                }
                Minecraft.getInstance().setScreen(screen);
            });
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

    public static void handleOpenUiPacket(String templateId, String instanceId, CompoundTag initialState) {
        handleOpenUiPacket(templateId, instanceId, initialState, 0, 0);
    }

    /**
     * @param surfaceWidth  0 より大きければ世界内の面への展開。画面は開かず、セッションをこの解像度（px）で作る
     * @param surfaceHeight 面の高さ（px）
     */
    public static void handleOpenUiPacket(String templateId, String instanceId, CompoundTag initialState,
                                          int surfaceWidth, int surfaceHeight) {
        if (templateId == null || instanceId == null) return;
        com.nishiyu.enderframelib.client.template.ParsedTemplate template = ClientTemplateManager.getTemplate(templateId);
        if (template == null) {
            EnderFrameLib.LOGGER.error("UI template not found for templateId: {}", templateId);
            return;
        }

        SessionContext session = ClientWebManager.getOrCreateSession(instanceId);
        session.setServerManaged(false);
        session.setHeadlessState(true);
        boolean surface = surfaceWidth > 0 && surfaceHeight > 0;
        if (surface) {
            session.setRootW(surfaceWidth);
            session.setRootH(surfaceHeight);
        }

        com.nishiyu.enderframelib.common.Document doc = template.instantiate(session.rootW(), session.rootH());
        session.loadTemplateDocument(doc);

        ReactiveStateStore store = session.stateStore();
        // 同じインスタンスを開き直すと、ストアは前の値を覚えたまま。値が同じでも、新しい文書に当て直す
        store.markAllDirty();
        if (initialState != null && !initialState.isEmpty()) applyCompoundTagToStore(store, initialState);
        store.syncToDom();
        if (surface) return;

        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                mc.execute(() -> {
                    if (mc.screen instanceof com.nishiyu.enderframelib.client.gui.EnderScreenHost host
                            && instanceId.equals(host.getWebSessionId())) {
                        return;
                    }
                    AbstractEnderScreen newScreen = new AbstractEnderScreen(
                            Component.literal("EnderFrame UI"), instanceId, session.rootW(), session.rootH()) {
                        @Override
                        protected String buildInitialHtml() {
                            return null;
                        }
                    };
                    mc.setScreen(newScreen);
                });
            }
        } catch (Throwable ignored) {
            // ヘッドレス環境・テスト環境での Minecraft 未初期化に対応
        }
    }

    public static void handleStateDeltaPacket(String instanceId, CompoundTag deltas) {
        if (instanceId == null || deltas == null) return;
        SessionContext session = ClientWebManager.getSession(instanceId);
        if (session == null) return;

        ReactiveStateStore store = session.stateStore();
        applyCompoundTagToStore(store, deltas);
        store.syncToDom();
    }

    /**
     * サーバーで状態が破棄された（ブロックの破壊など）。その画面を開いていれば閉じ、セッションを捨てる。
     */
    public static void handleStateClosed(String instanceId) {
        if (instanceId == null || instanceId.isEmpty()) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.screen instanceof com.nishiyu.enderframelib.client.gui.EnderScreenHost host
                    && instanceId.equals(host.getWebSessionId())) {
                mc.setScreen(null);
            }
        } catch (Throwable ignored) {
            // ヘッドレス環境・テスト環境での Minecraft 未初期化に対応
        }
        ClientWebManager.clearSession(instanceId);
    }

    public static void applyCompoundTagToStore(ReactiveStateStore store, CompoundTag tag) {
        if (tag == null || store == null) return;

        if (tag.contains("__removed")) {
            ListTag removedList = tag.getList("__removed", Tag.TAG_STRING);
            for (int i = 0; i < removedList.size(); i++) {
                store.set(removedList.getString(i), "");
            }
        }

        for (String key : tag.getAllKeys()) {
            if ("__removed".equals(key)) continue;
            Tag rawTag = tag.get(key);
            Object obj = StateNbtConverter.fromTag(rawTag);
            if (obj instanceof List<?> rawList) {
                List<Map<String, String>> items = new ArrayList<>();
                for (Object item : rawList) {
                    if (item instanceof Map<?, ?> m) {
                        Map<String, String> strMap = new HashMap<>();
                        for (Map.Entry<?, ?> e : m.entrySet()) {
                            if (e.getKey() != null) {
                                strMap.put(String.valueOf(e.getKey()), e.getValue() != null ? String.valueOf(e.getValue()) : "");
                            }
                        }
                        items.add(strMap);
                    } else if (item != null) {
                        Map<String, String> strMap = new HashMap<>();
                        strMap.put("_value", String.valueOf(item));
                        items.add(strMap);
                    }
                }
                store.setList(key, items);
            } else {
                store.set(key, obj);
            }
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
        public ClientSession open(Ui.Html ui) {
            String id = sanitizeSessionId(ui.sessionId(), "client_screen");
            int targetW = ui.width() > 0 ? ui.width() : 854;
            int targetH = ui.height() > 0 ? ui.height() : 480;
            String html = ui.html();

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
            return new Handle(id);
        }

        @Override
        public ClientSession hud(Ui.Html ui) {
            String id = sanitizeSessionId(ui.sessionId(), "client_hud");
            SessionContext session = ClientWebManager.getOrCreateSession(id);
            session.loadLocalHtml(ui.html(), ui.width() > 0 ? ui.width() : 1280, ui.height() > 0 ? ui.height() : 720);
            session.setServerManaged(false);
            WebHudRenderer.customActiveHudSessionId = id;
            return new Handle(id);
        }

        @Nullable
        @Override
        public ClientSession find(String sessionId) {
            if (sessionId == null || sessionId.isEmpty() || ClientWebManager.getSession(sessionId) == null) return null;
            return new Handle(sessionId);
        }

        /** セッション ID に結びついた操作の束。DOM は呼ぶたびにセッションから引く（再読み込みで差し替わるため）。 */
        private record Handle(String id) implements ClientSession {

            @Override
            public Document document() {
                SessionContext session = ClientWebManager.getSession(id);
                if (session == null) throw new IllegalStateException("client session is closed: " + id);
                return session.domDocument();
            }

            @Override
            public int requestAnimationFrame(Consumer<Double> callback) {
                return ClientScheduler.requestAnimationFrame(id, callback);
            }

            @Override
            public int setTimeout(Runnable task, long delayMs) {
                return ClientScheduler.setTimeout(id, task, delayMs);
            }

            @Override
            public int setInterval(Runnable task, long intervalMs) {
                return ClientScheduler.setInterval(id, task, intervalMs);
            }

            @Override
            public boolean cancel(int handle) {
                return ClientScheduler.cancelAnimationFrame(handle)
                        || ClientScheduler.clearTimeout(handle)
                        || ClientScheduler.clearInterval(handle);
            }

            @Override
            public void close() {
                ClientWebManager.clearSession(id);
            }
        }
    }
}
