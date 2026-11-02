package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.api.event.Event;
import com.nishiyu.enderframelib.client.gui.WebTextInputHost;
import com.nishiyu.enderframelib.client.renderer.Canvas2DContext;
import com.nishiyu.enderframelib.client.renderer.WebFontManager;
import com.nishiyu.enderframelib.client.renderer.WebHudRenderer;
import com.nishiyu.enderframelib.client.renderer.WebSceneContext;
import com.nishiyu.enderframelib.common.UiView;
import com.nishiyu.enderframelib.core.dom.Bindings;
import com.nishiyu.enderframelib.core.dom.EventForwardDeclaration;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.Ownership;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import com.nishiyu.enderframelib.network.DomMutationRecord;
import com.nishiyu.enderframelib.network.HtmlNodeSerializer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1つのWebセッション（GUI画面、コンテナ画面、ブロック面、HUD、アイテム等）に関するクライアント状態。
 *
 * <p>DOM・スタイル・レイアウト・スクロール・フォーカス・無効化といった Minecraft に依存しない部分は
 * コアの {@link UiView} が持つ。ここには、Minecraft 側にしかないもの（描画キューへの通知、フォント、
 * NBT でのサーバー同期、入力処理、HUD、キャンバス、タイマー、リアクティブ状態）だけを足す。</p>
 */
public class SessionContext extends UiView {

    private final SessionInput input = new SessionInput(this);
    private final ReactiveStateStore stateStore;
    private final Map<String, Canvas2DContext> canvases = new ConcurrentHashMap<>();
    private final Map<String, WebSceneContext> scenes = new ConcurrentHashMap<>();
    private WebTextInputHost activeTextInputHost = null;

    private WebHudRenderer.HudTemplateConfig hudConfig = null;
    private Map<String, String> lastHudData = null;
    private WebHudRenderer.TrackerEntry trackerEntry = null;

    public SessionContext(String sessionId) {
        super(sessionId);
        this.stateStore = new ReactiveStateStore(sessionId);
    }

    // ------------------------------------------------------------------
    // コアのフック → Minecraft 側の実装
    // ------------------------------------------------------------------

    @Override
    protected void invalidated() {
        ClientWebManager.markDirty(sessionId());
    }

    @Override
    protected void beforeElements() {
        HtmlNode root = rootNode();
        if (root != null) {
            ClientLocalEnvProvider.tickSessionLocalVariables(sessionId(), root, root);
        }
        if (ClientWebManager.hasPendingRenders()) {
            ClientWebManager.processRenderQueue();
        }
    }

    @Override
    protected void applyOverrides() {
        ClientWebManager.applyOverrides(sessionId());
    }

    @Override
    protected void tickLocalVariables(HtmlNode root) {
        ClientLocalEnvProvider.tickSessionLocalVariables(sessionId(), root, root);
    }

    @Override
    protected FontResolver fontResolver() {
        return WebFontManager.McFontResolver.INSTANCE;
    }

    @Override
    protected void runInlineAction(HtmlNode node, Event event, String action) {
        input.runInlineAction(node, event, action);
    }

    @Override
    protected void forwardDeclared(HtmlNode node, Event event, EventForwardDeclaration declaration) {
        input.forwardDeclared(node, event, declaration);
    }

    @Override
    public void initializeBindings(HtmlNode target) {
        if (target == null) return;
        final String sessionId = sessionId();
        Bindings.scanDeclarativeBindings(target, new Bindings.DeclarativeBindingHandler() {
            @Override
            public void bindData(String targetId, String channel, String path, String targetAttr) {
                ClientPubSubManager.registerDataBinding(sessionId, targetId, channel, path, targetAttr);
                ClientPubSubManager.requestSubscribe(sessionId, channel);
            }

            @Override
            public void bindVirtualList(String targetId, String channel, HtmlNode templateNode, int itemW, int itemH) {
                ClientPubSubManager.registerVirtualList(sessionId, targetId, channel, templateNode, itemW, itemH);
                ClientPubSubManager.requestSubscribe(sessionId, channel);
            }

            @Override
            public void bindHud(String targetId, String origDisplay, HtmlNode templateRoot, List<String> requireNbt) {
                WebHudRenderer.enableHudTemplate(sessionId, targetId, origDisplay, templateRoot, requireNbt);
            }

            @Override
            public void bindTracker(HtmlNode templateNode, double radius, double yOffset, String targetType, List<String> requireNbt) {
                WebHudRenderer.setTrackerTemplate(sessionId, templateNode, radius, yOffset, targetType, requireNbt);
            }
        });
    }

    @Override
    protected void onClose() {
        boolean hadHudState = hudConfig != null || trackerEntry != null;

        canvases.clear();
        scenes.clear();
        activeTextInputHost = null;

        hudConfig = null;
        lastHudData = null;
        trackerEntry = null;

        stateStore.clearBindings();

        if (hadHudState) {
            WebHudRenderer.onSessionStateCleared();
        }
    }

    // ------------------------------------------------------------------
    // Minecraft 側だけが持つ状態
    // ------------------------------------------------------------------

    public ReactiveStateStore stateStore() {
        return stateStore;
    }

    /** このセッションの入力処理（S4: ホストごとの座標変換の先にある唯一の入口）。 */
    public SessionInput input() {
        return input;
    }

    public Map<String, Canvas2DContext> canvases() {
        return canvases;
    }

    public Map<String, WebSceneContext> scenes() {
        return scenes;
    }

    /** このセッションで現在アクティブな隠し入力欄。無ければ null。 */
    public WebTextInputHost activeTextInputHost() {
        return activeTextInputHost;
    }

    public void setActiveTextInputHost(WebTextInputHost host) {
        this.activeTextInputHost = host;
    }

    public WebHudRenderer.HudTemplateConfig hudConfig() {
        return hudConfig;
    }

    public void setHudConfig(WebHudRenderer.HudTemplateConfig hudConfig) {
        this.hudConfig = hudConfig;
    }

    public Map<String, String> lastHudData() {
        return lastHudData;
    }

    public void setLastHudData(Map<String, String> lastHudData) {
        this.lastHudData = lastHudData;
    }

    public WebHudRenderer.TrackerEntry trackerEntry() {
        return trackerEntry;
    }

    public void setTrackerEntry(WebHudRenderer.TrackerEntry trackerEntry) {
        this.trackerEntry = trackerEntry;
    }

    // ------------------------------------------------------------------
    // サーバー同期（NBT）
    // ------------------------------------------------------------------

    /** サーバーから届いた dom_sync をこのセッションへ適用する。 */
    public void applySync(CompoundTag tag) {
        if (tag == null || tag.getBoolean("IsEmpty")) return;
        if (tag.contains("DomTree")) {
            setServerManaged(true);
            CompoundTag domTag = tag.getCompound("DomTree");
            String cssString = tag.getString("CssString");

            boolean serverSize = !isExplicitSize() && tag.getInt("RootW") > 0 && tag.getInt("RootH") > 0;
            int currentHash = Objects.hash(domTag.hashCode(), cssString.hashCode(),
                    serverSize ? tag.getInt("RootW") : 0, serverSize ? tag.getInt("RootH") : 0);
            if (domHash() == currentHash && document() != null) {
                setRequested(false);
                return;
            }
            setDomHash(currentHash);

            // 世界に貼る画面は、サーバーが読み込んだときの解像度がそのまま画面の大きさ（メニューモードと同じレイアウト）
            int w = serverSize ? tag.getInt("RootW") : (rootW() > 0 ? rootW() : (tag.contains("RootW") ? tag.getInt("RootW") : 854));
            int h = serverSize ? tag.getInt("RootH") : (rootH() > 0 ? rootH() : (tag.contains("RootH") ? tag.getInt("RootH") : 480));

            HtmlNode rootNode = HtmlNodeSerializer.deserialize(domTag, null);
            installDocument(rootNode, cssString, w, h, true);
        }
        setRequested(false);
    }

    /** サーバーから届いた dom_mutations をこのセッションへ適用する。 */
    public void applyMutations(CompoundTag data) {
        if (data == null) return;
        if (document() == null || document().root == null) return;
        HtmlNode root = document().root;

        List<DomMutationRecord> mutations = new ArrayList<>();
        if (data.contains("Mutations", Tag.TAG_LIST)) {
            ListTag list = data.getList("Mutations", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                mutations.add(DomMutationRecord.fromTag(list.getCompound(i)));
            }
        } else if (data.contains("Patches", Tag.TAG_LIST)) {
            ListTag list = data.getList("Patches", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                mutations.add(DomMutationRecord.fromTag(list.getCompound(i)));
            }
        }

        boolean anyChanged = false;
        for (DomMutationRecord mut : mutations) {
            // クライアント所有の部分木はサーバー変異の宛先にならない。サーバー所有なら、クライアントの書き込みを上書きして正になる。
            HtmlNode mutationTarget = root.getNodeById(mut.getTargetId());
            if (mutationTarget != null && mutationTarget.ownership() == Ownership.CLIENT) continue;

            String targetId = mut.getTargetId();
            boolean applied = mut.applyTo(root, interactionState()::markDirty);
            if (applied) {
                anyChanged = true;
                HtmlNode target = root.getNodeById(targetId);
                if (mut.getType() == DomMutationRecord.Type.ATTRIBUTE) {
                    if ("value".equalsIgnoreCase(mut.getName())) {
                        input.syncServerValueOverride(targetId,
                                "remove".equalsIgnoreCase(mut.getMode()) ? "" : mut.getValue());
                    }
                    syncAttributeToInteractionState(target, mut.getName(), !"remove".equalsIgnoreCase(mut.getMode()));
                } else if (mut.getType() == DomMutationRecord.Type.INNER_HTML && target != null) {
                    initializeBindings(target);
                }
            }
        }

        if (anyChanged) {
            ClientLocalEnvProvider.tickSessionLocalVariables(sessionId(), root, root);
            requestRender();
        }
    }
}
