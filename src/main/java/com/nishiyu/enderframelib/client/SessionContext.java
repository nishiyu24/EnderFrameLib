package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.gui.InvisibleInputScreen.WebTextInputHost;
import com.nishiyu.enderframelib.client.renderer.CssAnimator;
import com.nishiyu.enderframelib.client.renderer.Canvas2DContext;
import com.nishiyu.enderframelib.client.renderer.WebHudRenderer;
import com.nishiyu.enderframelib.client.renderer.WebSceneContext;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.api.event.Event;
import com.nishiyu.enderframelib.core.dom.EventForwardDeclaration;
import com.nishiyu.enderframelib.core.dom.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.Ownership;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext.ScrollState;
import com.nishiyu.enderframelib.core.layout.TextSelection;
import com.nishiyu.enderframelib.core.layout.DisplayItem;
import com.nishiyu.enderframelib.core.layout.DisplayItem.ClipRect;
import com.nishiyu.enderframelib.core.layout.DisplayItem.ElementType;
import com.nishiyu.enderframelib.core.layout.DisplayItem.RenderKeys;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 1つのWebセッション（GUI画面、コンテナ画面、ブロック面、HUD、アイテム等）に関する
 * 全クライアント状態（DOMツリー、スタイル、レンダー要素キャッシュ、環境変数、スクロール、フォーカス等）を
 * 1オブジェクトに集約管理するコンテキスト。
 */
public class SessionContext implements AutoCloseable {

    /**
     * 画面更新の無効化段階（F3-2）。
     * <ul>
     *   <li>{@link #NONE}: 再計算不要</li>
     *   <li>{@link #REPAINT}: 既存の {@link LayoutBox} から描画要素リストのみ再生成（measure/layout を省略）</li>
     *   <li>{@link #RESTYLE}: 状態変化した部分木のみ再スタイルし、レイアウト影響があれば {@link #RELAYOUT}、無ければ {@link #REPAINT} に分岐</li>
     *   <li>{@link #RELAYOUT}: スタイル解決・measure・layout・描画要素生成の全パスを実行</li>
     * </ul>
     */
    public enum InvalidationLevel {
        NONE,
        REPAINT,
        RESTYLE,
        RELAYOUT
    }

    private final String sessionId;
    private volatile List<DisplayItem> renderCache = Collections.emptyList();
    private UiDocumentCompiler.Document document;
    private LayoutBox layoutRoot;
    private boolean serverManaged = false;
    private boolean requested = false;
    private long lastSyncRequestTime = 0L;
    private int domHash = 0;
    private int rootW = 854;
    private int rootH = 480;
    private final Map<String, String> env = new ConcurrentHashMap<>();
    private final ScrollState scrollState = new ScrollState();
    private final InteractionState interactionState = new InteractionState();
    private volatile InvalidationLevel invalidationLevel = InvalidationLevel.NONE;

    private String activeInputId = "";
    private final TextSelection textSelection = new TextSelection();
    private volatile HitTestIndex hitTestIndex;

    // --- セッションに属する入力・描画・バインディング状態（S1） ---
    private final InputSessionState inputState = new InputSessionState();
    private final Map<String, CssAnimator.AnimState> cssAnimations = new ConcurrentHashMap<>();
    private final Map<String, Canvas2DContext> canvases = new ConcurrentHashMap<>();
    private final Map<String, WebSceneContext> scenes = new ConcurrentHashMap<>();
    private final Map<String, Integer> inputScrollOffsets = new ConcurrentHashMap<>();
    private final Map<String, Integer> textareaCursorIndex = new ConcurrentHashMap<>();
    private final Map<String, Integer> textareaScrollRow = new ConcurrentHashMap<>();
    private WebTextInputHost activeTextInputHost = null;

    private long envLastUpdate = 0L;
    private Set<String> envUsedKeys = null;
    private Map<String, String> envApplied = null;

    private final Set<String> subscriptions = ConcurrentHashMap.newKeySet();
    private final List<ClientPubSubManager.DataBinding> dataBindings = new CopyOnWriteArrayList<>();
    private final List<ClientPubSubManager.VirtualListBinding> virtualListBindings = new CopyOnWriteArrayList<>();

    private WebHudRenderer.HudTemplateConfig hudConfig = null;
    private Map<String, String> lastHudData = null;
    private WebHudRenderer.TrackerEntry trackerEntry = null;

    private final Map<Integer, ClientScheduler.RafCallback> rafCallbacks = new ConcurrentHashMap<>();
    private final Map<Integer, ClientScheduler.TimerTask> timers = new ConcurrentHashMap<>();

    public SessionContext(String sessionId) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
    }

    public String sessionId() {
        return sessionId;
    }

    public List<DisplayItem> renderCache() {
        return renderCache;
    }

    public void setRenderCache(List<DisplayItem> renderCache) {
        this.renderCache = (renderCache != null && !renderCache.isEmpty())
                ? List.copyOf(renderCache)
                : Collections.emptyList();
        this.hitTestIndex = new HitTestIndex(this.renderCache);
    }

    public HitTestIndex hitTestIndex() {
        HitTestIndex idx = this.hitTestIndex;
        if (idx == null) {
            idx = new HitTestIndex(this.renderCache);
            this.hitTestIndex = idx;
        }
        return idx;
    }

    public UiDocumentCompiler.Document document() {
        return document;
    }

    public void setDocument(UiDocumentCompiler.Document document) {
        this.document = document;
        bindLiveDocument();
    }

    /** 文書の外へ出ていくイベントの送り先と、所有者の既定をこのセッションに結ぶ。 */
    private void bindLiveDocument() {
        if (document == null || !(document.root instanceof HtmlDocument liveDocument)) return;
        liveDocument.setDefaultOwnership(serverManaged ? Ownership.SERVER : Ownership.CLIENT);
        liveDocument.setInteractionState(interactionState);
        // :target の規則が掛かる要素は、ターゲットが変わると見た目が変わる（子孫・兄弟の規則にも及ぶので全体を解き直す）
        liveDocument.setTargetChangeListener((before, after) -> {
            interactionState().markAllDirty();
            ClientWebManager.requestRestyle(sessionId);
        });
        liveDocument.setInlineActionSink(new HtmlDocument.InlineActionSink() {
            @Override
            public void run(HtmlNode node, Event event, String action) {
                ClientScreenInteractionManager.runInlineAction(sessionId, node, event, action);
            }

            @Override
            public void forward(HtmlNode node, Event event, EventForwardDeclaration declaration) {
                ClientScreenInteractionManager.forwardDeclared(sessionId, node, event, declaration);
            }
        });
    }

    public LayoutBox layoutRoot() {
        return layoutRoot;
    }

    public void setLayoutRoot(LayoutBox layoutRoot) {
        this.layoutRoot = layoutRoot;
    }

    public InteractionState interactionState() {
        return interactionState;
    }

    public InvalidationLevel invalidationLevel() {
        return invalidationLevel;
    }

    public synchronized void markInvalidation(InvalidationLevel level) {
        if (level != null && level.ordinal() > this.invalidationLevel.ordinal()) {
            this.invalidationLevel = level;
        }
    }

    public synchronized InvalidationLevel consumeInvalidationLevel() {
        InvalidationLevel current = this.invalidationLevel;
        this.invalidationLevel = InvalidationLevel.NONE;
        return current;
    }

    public boolean isServerManaged() {
        return serverManaged;
    }

    /** サーバーと同期するセッションの文書は、既定でサーバー所有（{@link Ownership#SERVER}）になる。 */
    public void setServerManaged(boolean serverManaged) {
        this.serverManaged = serverManaged;
        bindLiveDocument();
    }

    public boolean isRequested() {
        return requested;
    }

    public void setRequested(boolean requested) {
        this.requested = requested;
    }

    public long lastSyncRequestTime() {
        return lastSyncRequestTime;
    }

    public void setLastSyncRequestTime(long lastSyncRequestTime) {
        this.lastSyncRequestTime = lastSyncRequestTime;
    }

    public int domHash() {
        return domHash;
    }

    public void setDomHash(int domHash) {
        this.domHash = domHash;
    }

    public int rootW() {
        return rootW;
    }

    public void setRootW(int rootW) {
        this.rootW = rootW;
    }

    public int rootH() {
        return rootH;
    }

    public void setRootH(int rootH) {
        this.rootH = rootH;
    }

    public Map<String, String> env() {
        return env;
    }

    public void setEnv(Map<String, String> newEnv) {
        this.env.clear();
        if (newEnv != null) {
            this.env.putAll(newEnv);
        }
    }

    public ScrollState scrollState() {
        return scrollState;
    }

    public String activeInputId() {
        return activeInputId;
    }

    public void setActiveInputId(String activeInputId) {
        this.activeInputId = activeInputId != null ? activeInputId : "";
    }

    /** 押下中の節の ID。押下中の節は {@link InteractionState} が唯一の持ち主。 */
    public String activePressedId() {
        HtmlNode active = interactionState.activeNode();
        return active != null ? active.getId() : "";
    }

    /** フォーカス中の節の ID。フォーカス中の節は {@link InteractionState} が唯一の持ち主。 */
    public String focusedId() {
        HtmlNode focused = interactionState.focusedNode();
        return focused != null ? focused.getId() : "";
    }

    public TextSelection textSelection() {
        return textSelection;
    }

    public InputSessionState inputState() {
        return inputState;
    }

    public Map<String, CssAnimator.AnimState> cssAnimations() {
        return cssAnimations;
    }

    public Map<String, Canvas2DContext> canvases() {
        return canvases;
    }

    public Map<String, WebSceneContext> scenes() {
        return scenes;
    }

    public Map<String, Integer> inputScrollOffsets() {
        return inputScrollOffsets;
    }

    public Map<String, Integer> textareaCursorIndex() {
        return textareaCursorIndex;
    }

    public Map<String, Integer> textareaScrollRow() {
        return textareaScrollRow;
    }

    /** このセッションで現在アクティブな隠し入力欄。無ければ null。 */
    public WebTextInputHost activeTextInputHost() {
        return activeTextInputHost;
    }

    public void setActiveTextInputHost(WebTextInputHost host) {
        this.activeTextInputHost = host;
    }

    public long envLastUpdate() {
        return envLastUpdate;
    }

    public void setEnvLastUpdate(long envLastUpdate) {
        this.envLastUpdate = envLastUpdate;
    }

    public Set<String> envUsedKeys() {
        return envUsedKeys;
    }

    public void setEnvUsedKeys(Set<String> envUsedKeys) {
        this.envUsedKeys = envUsedKeys;
    }

    public Map<String, String> envApplied() {
        return envApplied;
    }

    public void setEnvApplied(Map<String, String> envApplied) {
        this.envApplied = envApplied;
    }

    public Set<String> subscriptions() {
        return subscriptions;
    }

    public List<ClientPubSubManager.DataBinding> dataBindings() {
        return dataBindings;
    }

    public List<ClientPubSubManager.VirtualListBinding> virtualListBindings() {
        return virtualListBindings;
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

    public Map<Integer, ClientScheduler.RafCallback> rafCallbacks() {
        return rafCallbacks;
    }

    public Map<Integer, ClientScheduler.TimerTask> timers() {
        return timers;
    }

    /** このセッションの入力欄キャレット状態（描画・編集の境界で使う文字列 ID）を消す。 */
    public void clearTextEditState(String renderId) {
        inputScrollOffsets.remove(renderId);
        textareaCursorIndex.remove(renderId);
        textareaScrollRow.remove(renderId);
    }

    @Override
    public void close() {
        boolean hadHudState = hudConfig != null || trackerEntry != null;

        renderCache = Collections.emptyList();
        hitTestIndex = null;
        document = null;
        layoutRoot = null;
        env.clear();
        interactionState.clear();
        invalidationLevel = InvalidationLevel.NONE;
        activeInputId = "";
        textSelection.clear();

        inputState.clear();
        cssAnimations.clear();
        canvases.clear();
        scenes.clear();
        inputScrollOffsets.clear();
        textareaCursorIndex.clear();
        textareaScrollRow.clear();
        activeTextInputHost = null;

        envLastUpdate = 0L;
        envUsedKeys = null;
        envApplied = null;

        subscriptions.clear();
        dataBindings.clear();
        virtualListBindings.clear();

        hudConfig = null;
        lastHudData = null;
        trackerEntry = null;

        rafCallbacks.clear();
        timers.clear();

        if (hadHudState) {
            WebHudRenderer.onSessionStateCleared();
        }
    }

    /**
     * 1 セッション分の入力状態。ホバー・押下・フォーカスのノードは {@link InteractionState} が持つので、
     * ここには描画・編集の境界でしか使わない文字列 ID と、ジェスチャの途中経過だけを置く。
     */
    static final class InputSessionState {
        String draggingId = "";
        float lastRatio = -1.0f;
        String lastRangeText = "";
        double lastSentX = -1, lastSentY = -1;
        long lastEventTime = 0;
        double hoverX = -1, hoverY = -1;
        int pressedButton = 0;
        String lastSubmittedId = "";
        String lastSubmittedText = "";
        long lastSubmitTime = 0;
        final Map<String, String> localInputCache = new ConcurrentHashMap<>();

        /** ダブルクリック判定（旧 ClientDomEventDispatcher.ClickState）。 */
        HtmlNode lastClickNode = null;
        long lastClickTime = 0;

        void clear() {
            draggingId = "";
            lastRatio = -1.0f;
            lastRangeText = "";
            lastSentX = -1;
            lastSentY = -1;
            lastEventTime = 0;
            hoverX = -1;
            hoverY = -1;
            pressedButton = 0;
            lastSubmittedId = "";
            lastSubmittedText = "";
            lastSubmitTime = 0;
            localInputCache.clear();
            lastClickNode = null;
            lastClickTime = 0;
        }
    }

    /**
     * レイアウト更新ごとに1回生成されるヒットテスト用インデックス。
     * 対話可能要素の事前抽出、Z順序の保持、親チェーンの事前解決、clipRect判定を行い、
     * 毎フレームの全要素スキャン・HashMapアロケーションを排除する。
     */
    public static class HitTestIndex {

        public static class Entry {
            public final DisplayItem element;
            public final ClipRect clipRect;
            public final List<DisplayItem> transformChain; // ルートから自身までの順序

            public Entry(DisplayItem element, ClipRect clipRect, List<DisplayItem> transformChain) {
                this.element = element;
                this.clipRect = clipRect;
                this.transformChain = transformChain;
            }
        }

        private final List<Entry> interactiveEntries; // z順序の逆順（上位優先）
        private final Map<String, DisplayItem> elementMap;

        public HitTestIndex(List<DisplayItem> elements) {
            if (elements == null || elements.isEmpty()) {
                this.interactiveEntries = Collections.emptyList();
                this.elementMap = Collections.emptyMap();
                return;
            }

            this.elementMap = new HashMap<>(elements.size());
            for (DisplayItem el : elements) {
                elementMap.put(el.id(), el);
            }

            List<Entry> entries = new ArrayList<>();
            // elements は描画順（下層から上層）なので、逆順で探索できるように保持
            for (int i = elements.size() - 1; i >= 0; i--) {
                DisplayItem el = elements.get(i);
                if (isIgnoredElement(el)) continue;
                if (!isInteractiveOrOpaque(el, elementMap)) continue;

                List<DisplayItem> chain = buildTransformChain(el, elementMap);
                ClipRect clip = el.meta() != null ? el.meta().clipRect() : null;
                entries.add(new Entry(el, clip, chain));
            }
            this.interactiveEntries = entries;
        }

        /** 描画側が {@code pointer-events: none} の箱の部品に載せた印（{@link RenderKeys#POINTER_EVENTS}）。 */
        private static boolean isPointerEventsNone(DisplayItem el) {
            return el.events() != null && "none".equalsIgnoreCase(el.events().get(RenderKeys.POINTER_EVENTS));
        }

        /** 装飾だけの部品（影・枠線・輪郭線・背景のレイヤー・プレースホルダ）。クリックを受けず、下の要素を隠さない。 */
        private static final String[] DECORATION_PARTS = {"_shadow", "_ph", "_b_", "_o_", "_bgl"};

        /**
         * 部品の id は持ち主の id + 接尾辞なので、持ち主の id に "_b_" などが含まれても誤判定しないよう接尾辞だけを見る。
         *
         * 何が壊れていたか: 以前は "_shadow" で終わる id だけを除いていたので、番号付きの影（"_shadow_1" など）が
         * ヒットテストに残り、影の上のカーソルが存在しない id でホバーとクリックを起こしていた。
         */
        private static boolean isIgnoredElement(DisplayItem el) {
            if (el == null) return true;
            if (el.type() == ElementType.NODE) return true;
            if (isPointerEventsNone(el)) return true;

            String id = el.id();
            String part = id.startsWith(el.baseId()) ? id.substring(el.baseId().length()) : id;
            for (String prefix : DECORATION_PARTS) {
                if (part.startsWith(prefix)) return true;
            }
            return false;
        }

        private static boolean isInteractiveOrOpaque(DisplayItem el, Map<String, DisplayItem> elementMap) {
            if (isPointerEventsNone(el)) return false;

            ElementType t = el.type();
            boolean isInput = t == ElementType.INPUT || t == ElementType.TEXTAREA || t == ElementType.SELECT
                    || t == ElementType.RANGE || t == ElementType.TOGGLE || t == ElementType.CHECKBOX
                    || t == ElementType.RADIO || t == ElementType.BUTTON || t == ElementType.A || t == ElementType.SCENE;
            if (isInput) return true;

            // スクロールバー
            if (el.id().contains("_sb_")) return true;

            // テキストノード
            if (t == ElementType.TEXT) return true;

            // イベントや対話属性がある
            if (el.events() != null) {
                for (String key : el.events().keySet()) {
                    if (key.startsWith("on") || RenderKeys.MC_TITLE.equals(key) || "draggable".equals(key)) {
                        return true;
                    }
                }
            }

            // 置換要素（画像・キャンバス）
            if (t == ElementType.IMAGE || t == ElementType.CANVAS) {
                return true;
            }

            // 不透明な背景色（Alpha > 0）を持つ要素（遮蔽物として下のボタン貫通を防止）
            if ((el.bgColor() & 0xFF000000) != 0) {
                return true;
            }

            DisplayItem parentAnchor = elementMap.get(el.events() != null ? el.events().get(RenderKeys.MC_PARENT) : null);
            return parentAnchor != null && (parentAnchor.hasHover() || parentAnchor.hasPseudo());
        }

        private static List<DisplayItem> buildTransformChain(DisplayItem el, Map<String, DisplayItem> map) {
            List<DisplayItem> chain = new ArrayList<>(4);
            DisplayItem curr = el;
            while (curr != null) {
                chain.add(curr);
                String parentId = curr.events() != null ? curr.events().get(RenderKeys.MC_PARENT) : null;
                curr = parentId != null ? map.get(parentId) : null;
            }
            Collections.reverse(chain);
            return chain;
        }

        public ClientWebManager.UIHitResult hitTest(String sessionId, double pixelX, double pixelY) {
            for (Entry entry : interactiveEntries) {
                DisplayItem el = entry.element;

                // クリップ判定 (D-9)
                // entry.clipRect は仮想座標での可視矩形。pixelX, pixelY がクリップ範囲外なら判定除外
                if (entry.clipRect != null && !entry.clipRect.contains(pixelX, pixelY)) {
                    continue;
                }

                double px = pixelX;
                double py = pixelY;
                for (DisplayItem ancestor : entry.transformChain) {
                    CssAnimator.AnimatedValues anim = CssAnimator.getCurrentValues(sessionId, ancestor);
                    double[] p = ClientWebManager.inverseTransform(px, py, ancestor, anim);
                    px = p[0];
                    py = p[1];
                }

                double localX = px - el.x();
                double localY = py - el.y();

                if (localX >= 0 && localX <= el.width() && localY >= 0 && localY <= el.height()) {
                    return new ClientWebManager.UIHitResult(el, localX, localY);
                }
            }
            return null;
        }

        public ClientWebManager.UIHitResult getLocalCoords(String sessionId, DisplayItem el, double pixelX, double pixelY) {
            List<DisplayItem> chain = buildTransformChain(el, elementMap);
            double px = pixelX;
            double py = pixelY;
            for (DisplayItem ancestor : chain) {
                CssAnimator.AnimatedValues anim = CssAnimator.getCurrentValues(sessionId, ancestor);
                double[] p = ClientWebManager.inverseTransform(px, py, ancestor, anim);
                px = p[0];
                py = p[1];
            }
            return new ClientWebManager.UIHitResult(el, px - el.x(), py - el.y());
        }

        public DisplayItem getElementById(String id) {
            return elementMap.get(id);
        }

        public Map<String, DisplayItem> elementMap() {
            return elementMap;
        }
    }
}