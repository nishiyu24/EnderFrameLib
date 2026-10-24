package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.gui.WebTextInputHost;
import com.nishiyu.enderframelib.client.renderer.CssAnimator;
import com.nishiyu.enderframelib.client.renderer.Canvas2DContext;
import com.nishiyu.enderframelib.client.renderer.WebFontManager;
import com.nishiyu.enderframelib.client.renderer.WebHudRenderer;
import com.nishiyu.enderframelib.client.renderer.WebSceneContext;
import com.nishiyu.enderframelib.common.EnderFrameBuiltInConfig;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.api.event.Event;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.dom.parser.DomUtils;
import com.nishiyu.enderframelib.core.dom.EventForwardDeclaration;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.Bindings;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.Ownership;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.TextSelection;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.RenderKeys;
import com.nishiyu.enderframelib.network.HtmlNodeSerializer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.RenderPipeline;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontStyle;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.Viewport;

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
     *   <li>{@link #REPOSITION}: 寸法はそのままに位置だけ決め直す（スクロール・{@code top} / {@code left} の変化。measure を省略）</li>
     *   <li>{@link #RESTYLE}: 状態変化した部分木のみ再スタイルし、レイアウト影響があれば {@link #RELAYOUT}、
     *       位置だけなら {@link #REPOSITION}、無ければ {@link #REPAINT} に分岐</li>
     *   <li>{@link #RELAYOUT}: スタイル解決・measure・layout・描画要素生成の全パスを実行</li>
     * </ul>
     */
    public enum InvalidationLevel {
        NONE,
        REPAINT,
        REPOSITION,
        RESTYLE,
        RELAYOUT
    }

    /**
     * 連続したリサイズ中は、直前のレイアウトにかかった時間のこの倍数だけ次のレイアウトを待つ。
     * 1 フレームの大半をレイアウトに取られないよう、待つ間は古いレイアウトのまま描く（大きさが落ち着けば必ず追いつく）。
     */
    private static final long RESIZE_THROTTLE_FACTOR = 2;

    private final String sessionId;
    private volatile List<DisplayItem> renderCache = Collections.emptyList();
    /** レイアウトから作った描画要素（描画上の平行移動 {@link #paintOffsets} を掛ける前）。 */
    private volatile List<DisplayItem> baseRenderCache = Collections.emptyList();
    private final Map<String, float[]> paintOffsets = new ConcurrentHashMap<>();
    private volatile boolean paintOffsetsDirty = false;
    private Document document;
    private LayoutBox layoutRoot;
    private boolean serverManaged = false;
    private boolean headlessState = false;
    private final ReactiveStateStore stateStore;
    private boolean requested = false;
    private long lastSyncRequestTime = 0L;
    private int domHash = 0;
    private int rootW = 854;
    private int rootH = 480;
    /** 1 CSS px あたりのデバイスピクセル（{@code @media (resolution)} が見る値）。 */
    private float devicePixelRatio = 1.0f;
    private final Map<String, String> env = new ConcurrentHashMap<>();
    private final ScrollState scrollState = new ScrollState();
    private final InteractionState interactionState = new InteractionState();
    private volatile InvalidationLevel invalidationLevel = InvalidationLevel.NONE;
    /** 位置の決め直しが要求されている（同じフレームの再スタイルがレイアウト不要でも、スクロールは反映する）。 */
    private volatile boolean repositionPending = false;
    /** まだ反映していないビューポートの大きさ。無ければ -1。 */
    /**
     * GUI など、クライアントが画面の大きさを自分で決めているセッションか。そうでない（ブロック・アイテム・ホログラムの
     * ように世界に貼る）セッションは、サーバーが読み込んだときの解像度を画面の大きさとして使う。
     */
    private boolean explicitSize;

    public void markExplicitSize() {
        explicitSize = true;
    }

    private volatile int pendingResizeW = -1;
    private volatile int pendingResizeH = -1;
    /** 直前の全レイアウト（描画要素の生成まで）にかかった時間と、それを終えた時刻（ナノ秒）。 */
    private long lastLayoutCostNanos = 0L;
    private long lastLayoutFinishedNanos = 0L;

    private String activeInputId = "";
    private final TextSelection textSelection = new TextSelection();
    private volatile HitTestIndex hitTestIndex;
    /** {@link #hitTestIndex} を作った描画要素リスト（同じ物を指す間だけ使い回す）。 */
    private volatile List<DisplayItem> hitTestIndexSource;

    // --- セッションに属する入力・描画・バインディング状態（S1） ---
    private final InputSessionState inputState = new InputSessionState();
    private final SessionInput input = new SessionInput(this);
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
        this.stateStore = new ReactiveStateStore(sessionId);
    }

    public String sessionId() {
        return sessionId;
    }

    public List<DisplayItem> renderCache() {
        applyPendingPaintOffsets();
        return renderCache;
    }

    public void setRenderCache(List<DisplayItem> renderCache) {
        this.baseRenderCache = (renderCache != null && !renderCache.isEmpty())
                ? List.copyOf(renderCache)
                : Collections.emptyList();
        publishRenderCache();
    }

    /**
     * 要素を描画の上だけで平行移動する（レイアウトも DOM も変えない）。
     *
     * <p>要素本体の描画要素（id が一致する NODE）の translate に足すので、子孫・クリップ・ヒットテストも
     * 親の連鎖を通して一緒に動く。ウィンドウのドラッグのように毎フレーム位置が変わる操作で、
     * 1 フレームごとの再レイアウトを避けるために使う。再レイアウトで描画要素が作り直されても掛け続け、
     * {@link #clearPaintOffset(String)} で外す。反映は次に描画要素を読んだとき（1 フレームに 1 回）。</p>
     */
    public void setPaintOffset(String elementId, float dx, float dy) {
        if (elementId == null || elementId.isEmpty()) return;
        float[] prev = paintOffsets.put(elementId, new float[]{dx, dy});
        if (prev == null || prev[0] != dx || prev[1] != dy) paintOffsetsDirty = true;
    }

    public void clearPaintOffset(String elementId) {
        if (elementId != null && paintOffsets.remove(elementId) != null) paintOffsetsDirty = true;
    }

    private void applyPendingPaintOffsets() {
        if (paintOffsetsDirty) publishRenderCache();
    }

    private void publishRenderCache() {
        paintOffsetsDirty = false;
        List<DisplayItem> base = this.baseRenderCache;
        List<DisplayItem> shown = base;
        if (!paintOffsets.isEmpty() && !base.isEmpty()) {
            List<DisplayItem> moved = new ArrayList<>(base);
            for (int i = 0; i < moved.size(); i++) {
                DisplayItem el = moved.get(i);
                float[] off = paintOffsets.get(el.id());
                if (off != null && el.type() == ElementType.NODE) {
                    moved.set(i, el.withTranslation(el.translateX() + off[0], el.translateY() + off[1]));
                }
            }
            shown = List.copyOf(moved);
        }
        this.renderCache = shown;
        // 索引は引かれたときに作る。ドラッグ中は描画要素が毎フレーム変わるが、ヒットテストは入力が来たときしか要らない
        this.hitTestIndex = null;
    }

    public HitTestIndex hitTestIndex() {
        applyPendingPaintOffsets();
        List<DisplayItem> current = this.renderCache;
        HitTestIndex idx = this.hitTestIndex;
        if (idx == null || hitTestIndexSource != current) {
            idx = new HitTestIndex(current);
            this.hitTestIndexSource = current;
            this.hitTestIndex = idx;
        }
        return idx;
    }

    public Document document() {
        return document;
    }

    public void setDocument(Document document) {
        this.document = document;
        bindLiveDocument();
    }

    /** 文書のルート。文書が無ければ null。 */
    public HtmlNode rootNode() {
        return document != null ? document.root : null;
    }

    /** 公開面の {@link com.nishiyu.enderframelib.api.dom.Document}。文書が無い・実装でないときは null。 */
    public com.nishiyu.enderframelib.api.dom.Document domDocument() {
        return document != null && document.root instanceof com.nishiyu.enderframelib.api.dom.Document doc
                ? doc
                : null;
    }

    /** 文書の外へ出ていくイベントの送り先と、所有者の既定をこのセッションに結ぶ。 */
    private void bindLiveDocument() {
        if (document == null || !(document.root instanceof HtmlDocument liveDocument)) return;
        liveDocument.setDefaultOwnership(serverManaged ? Ownership.SERVER : Ownership.CLIENT);
        liveDocument.setInteractionState(interactionState);
        // DOM の操作（クライアントのスクリプト・サーバーの変異・バインディング）は、文書が汚れの印を付けて知らせてくる。
        // どの経路から変えても同じ無効化になるよう、属性なら対話状態（checked / open など）もここで合わせる。
        // 見た目の属性（style / class / hidden）だけなら再スタイルから、文字・子の並び・その他の属性なら再レイアウトから始める
        liveDocument.setDomChangeListener((node, attributeName) -> {
            if (attributeName != null) {
                syncAttributeToInteractionState(node, attributeName, node.attrs.containsKey(attributeName));
            }
            if (attributeName != null && isPresentationalAttribute(attributeName)) {
                requestRestyle();
            } else {
                requestRender();
            }
        });
        // :target の規則が掛かる要素は、ターゲットが変わると見た目が変わる（子孫・兄弟の規則にも及ぶので全体を解き直す）
        liveDocument.setTargetChangeListener((before, after) -> {
            interactionState().markAllDirty();
            requestRestyle();
        });
        liveDocument.setInlineActionSink(new HtmlDocument.InlineActionSink() {
            @Override
            public void run(HtmlNode node, Event event, String action) {
                input.runInlineAction(node, event, action);
            }

            @Override
            public void forward(HtmlNode node, Event event, EventForwardDeclaration declaration) {
                input.forwardDeclared(node, event, declaration);
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

    public float devicePixelRatio() {
        return devicePixelRatio;
    }

    /**
     * デバイスピクセル比を変える。{@code @media (resolution)} の結果が変わったときだけレイアウトし直す
     * （大きさの変更と同時なら、続く {@link #recomputeLayout} がまとめて評価する）。
     */
    public void setDevicePixelRatio(float dppx) {
        if (dppx <= 0 || Math.abs(dppx - devicePixelRatio) < 0.001f) return;
        this.devicePixelRatio = dppx;
        Document doc = document;
        if (doc != null && doc.sheet != null && doc.root != null
                && doc.sheet.updateMediaEnvironment(doc.sheet.mediaEnvironment().withResolution(dppx))) {
            interactionState.markAllDirty();
            // リサイズ中は毎フレーム呼ばれ得るので、作り直しは描画キューで 1 回にまとめる
            requestRender();
        }
    }

    private void applyResolution(StyleSheet sheet) {
        if (sheet != null) sheet.updateMediaEnvironment(sheet.mediaEnvironment().withResolution(devicePixelRatio));
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

    /** このセッションの入力処理（S4: ホストごとの座標変換の先にある唯一の入口）。 */
    public SessionInput input() {
        return input;
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

    // ------------------------------------------------------------------
    // 文書・入力・描画の操作（S3: セッション ID の代わりにこのオブジェクトへ）
    // ------------------------------------------------------------------

    /** 描画要素のキャッシュ。必要なら先に環境変数と描画キューを更新する。 */
    public List<DisplayItem> elements() {
        HtmlNode root = rootNode();
        if (root != null) {
            ClientLocalEnvProvider.tickSessionLocalVariables(sessionId, root, root);
        }
        if (ClientWebManager.hasPendingRenders()) {
            ClientWebManager.processRenderQueue();
        }
        applyPendingPaintOffsets();
        return renderCache;
    }

    /** 状態変化に伴う部分木の再スタイル（必要に応じて再レイアウトまたは再描画へ分岐）を要求する。 */
    public void requestRestyle() {
        markInvalidation(InvalidationLevel.RESTYLE);
        ClientWebManager.markDirty(sessionId);
    }

    /** スクロールなど、寸法を変えずに位置だけが変わる更新を要求する（measure を省いて layout だけやり直す）。 */
    public void requestReposition() {
        repositionPending = true;
        markInvalidation(InvalidationLevel.REPOSITION);
        ClientWebManager.markDirty(sessionId);
    }

    /**
     * ビューポートの大きさの変更を予約する。同じフレームに何度呼ばれても反映は描画キューで 1 回にまとめ、
     * 連続したリサイズ中はレイアウトの重さに応じて間引く（{@link #recomputeLayout} は即座に作り直す）。
     */
    public void requestResize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        explicitSize = true;
        if (width == rootW && height == rootH && pendingResizeW < 0) return;
        pendingResizeW = width;
        pendingResizeH = height;
        ClientWebManager.markDirty(sessionId);
    }

    /** DOM 変更やビューポート変更に伴う再レイアウトを要求する。 */
    public void requestRender() {
        markInvalidation(InvalidationLevel.RELAYOUT);
        ClientWebManager.markDirty(sessionId);
    }

    /** 同期要求を送る間隔を空ける。送るべきなら true。 */
    public boolean shouldRequestSync() {
        long now = System.currentTimeMillis();
        long lastReq = lastSyncRequestTime;
        if (now - lastReq > 2000) {
            lastSyncRequestTime = now;
            return true;
        }
        return false;
    }

    /**
     * 描画 ID・ネットワーク ID が指す節。ID を節へ解決する境界はここ（と {@link HtmlNode#getNodeById}）だけで、
     * 状態や座標はこの先ずっとノードをキーにして持つ。文書が無い・該当が無いときは null。
     */
    public HtmlNode findNode(String nodeId) {
        HtmlNode root = rootNode();
        return root != null ? root.getNodeById(nodeId) : null;
    }

    /**
     * 対話状態（hover / active / focus）の持ち主になる節。擬似要素（{@code x_before}）とその中の文字は
     * DOM に無いので、状態を持つ要素（{@code x}）へ読み替える。
     */
    public HtmlNode findInteractiveNode(String baseId) {
        return resolveInteractiveNode(baseId);
    }

    private HtmlNode resolveInteractiveNode(String baseId) {
        if (baseId == null || baseId.isEmpty()) return null;
        HtmlNode node = findNode(baseId);
        return node != null ? node : findNode(generatedContentOwnerId(baseId));
    }

    private static String generatedContentOwnerId(String id) {
        String base = id.endsWith("_txt") ? id.substring(0, id.length() - "_txt".length()) : id;
        for (String suffix : new String[]{"_before", "_after", "_marker"}) {
            if (base.length() > suffix.length() && base.endsWith(suffix)) {
                return base.substring(0, base.length() - suffix.length());
            }
        }
        return base;
    }

    /** 入力欄をアクティブにし、その節へフォーカスを移す（旧 setActiveInputId の完全な挙動）。 */
    public void activateInput(String id) {
        setActiveInputId(id);
        String baseId = ClientWebManager.getBaseNodeId(id);
        if (!baseId.isEmpty() && interactionState.setFocusedNode(resolveInteractiveNode(baseId), false)) {
            requestRestyle();
        }
    }

    public boolean isActiveInput(String renderId) {
        return !activeInputId.isEmpty() && activeInputId.equals(renderId);
    }

    public boolean isFocusedBase(String baseId) {
        if (baseId == null || baseId.isEmpty()) return false;
        if (baseId.equals(focusedId())) {
            return true;
        }
        String active = activeInputId;
        return !active.isEmpty() && ClientWebManager.getBaseNodeId(active).equals(baseId);
    }

    public boolean isPressedBase(String baseId) {
        String pressed = activePressedId();
        return !pressed.isEmpty() && ClientWebManager.getBaseNodeId(pressed).equals(baseId);
    }

    public void setActivePressedId(String id) {
        if (interactionState.setActiveNode(resolveInteractiveNode(ClientWebManager.getBaseNodeId(id)))) {
            requestRestyle();
        }
    }

    /** ホバー中（:hover）の要素の基底ノード ID を更新し、変化があれば再スタイルを要求する。 */
    public void setHoveredElement(String baseId) {
        if (interactionState.setHoveredNode(resolveInteractiveNode(baseId))) {
            requestRestyle();
        }
    }

    /** フォーカス中（:focus / :focus-visible / :focus-within）の要素を更新し、変化があれば再スタイルを要求する。 */
    public void setFocusedElement(String baseId, boolean focusVisible) {
        HtmlNode targetNode = resolveInteractiveNode(baseId);
        boolean changed;
        if (rootNode() instanceof HtmlDocument doc) {
            // 文書の activeElement は同じ InteractionState から読むので、焦点イベントを伴う更新は文書に任せる
            changed = doc.setFocusedElement(targetNode instanceof HtmlElement el ? el : null, focusVisible);
        } else {
            changed = interactionState.setFocusedNode(targetNode, focusVisible);
        }
        if (changed) {
            requestRestyle();
        }
    }

    /** レイアウト結果から描画要素リストだけを作り直す。 */
    private void repaintFromLayout() {
        if (layoutRoot == null || document == null) return;
        if (UiDocumentCompiler.clientBoxRenderer == null) return;
        List<DisplayItem> elements = UiDocumentCompiler.clientBoxRenderer.render(
                layoutRoot,
                rootW,
                rootH,
                document.sheet.keyframes,
                scrollState);
        setRenderCache(elements);
        ClientWebManager.applyOverrides(sessionId);
    }

    /** 寸法はそのままに位置だけを決め直し、描画要素リストを作り直す（measure と箱の木の作り直しを省く）。 */
    private void repositionFromLayout() {
        if (layoutRoot == null || document == null) return;
        RenderPipeline.repositionLayout(layoutRoot, rootW, rootH, scrollState, WebFontManager.McFontResolver.INSTANCE);
        repaintFromLayout();
    }

    /** 汚れた部分木を再スタイルし、結果に応じて必要な最小の段（全レイアウト / 位置だけ / 描き直しだけ）を走らせる。 */
    private void restyleAndUpdate(boolean repositionRequested) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        RenderPipeline.RestyleResult res = parser.restyleLayoutTree(
                layoutRoot, document,
                rootW, rootH,
                env, interactionState);
        if (res.layoutRequired()) {
            recomputeLayout(rootW, rootH);
        } else if (repositionRequested || res.repositionRequired()) {
            repositionFromLayout();
        } else if (res.styleChanged()) {
            repaintFromLayout();
        }
    }

    /**
     * 予約されたリサイズを今のフレームで反映するか。連続したリサイズ中は、直前のレイアウトにかかった時間の
     * {@link #RESIZE_THROTTLE_FACTOR} 倍が過ぎるまで待つ（React のスケジューラが重い更新を後回しにするのと同じ考え方）。
     * 待つときは次のフレームでまた見るよう描画キューに残す。
     */
    private boolean resizeDue() {
        if (pendingResizeW < 0) return false;
        if (pendingResizeW == rootW && pendingResizeH == rootH) {
            pendingResizeW = -1;
            pendingResizeH = -1;
            return false;
        }
        long sinceLastLayout = System.nanoTime() - lastLayoutFinishedNanos;
        if (layoutRoot != null && sinceLastLayout < lastLayoutCostNanos * RESIZE_THROTTLE_FACTOR) {
            ClientWebManager.markDirty(sessionId);
            return false;
        }
        return true;
    }

    /** 描画キューから呼ばれる、このセッション 1 回分の無効化処理。 */
    void flushInvalidation() {
        if (document == null || document.root == null) return;

        boolean resize = resizeDue();
        InvalidationLevel level = consumeInvalidationLevel();
        boolean repositionRequested = repositionPending;
        repositionPending = false;
        if (layoutRoot == null) {
            level = InvalidationLevel.RELAYOUT;
            interactionState.markAllDirty();
        }
        if (resize) {
            recomputeLayout(pendingResizeW, pendingResizeH);
            return;
        }

        switch (level) {
            case RELAYOUT -> recomputeLayout(rootW, rootH);
            case RESTYLE -> restyleAndUpdate(repositionRequested);
            case REPOSITION -> {
                if (interactionState.hasDirtyNodes()) {
                    restyleAndUpdate(true);
                } else {
                    repositionFromLayout();
                }
            }
            case REPAINT -> repaintFromLayout();
            case NONE -> {
                if (interactionState.hasDirtyNodes()) restyleAndUpdate(repositionRequested);
            }
        }
    }

    /** サーバーから届いた dom_sync をこのセッションへ適用する。 */
    public void applySync(CompoundTag tag) {
        if (tag == null || tag.getBoolean("IsEmpty")) return;
        if (tag.contains("DomTree")) {
            setServerManaged(true);
            CompoundTag domTag = tag.getCompound("DomTree");
            String cssString = tag.getString("CssString");

            boolean serverSize = !explicitSize && tag.getInt("RootW") > 0 && tag.getInt("RootH") > 0;
            int currentHash = Objects.hash(domTag.hashCode(), cssString.hashCode(),
                    serverSize ? tag.getInt("RootW") : 0, serverSize ? tag.getInt("RootH") : 0);
            if (domHash == currentHash && document != null) {
                setRequested(false);
                return;
            }
            setDomHash(currentHash);

            // 世界に貼る画面は、サーバーが読み込んだときの解像度がそのまま画面の大きさ（メニューモードと同じレイアウト）
            int w = serverSize ? tag.getInt("RootW") : (rootW > 0 ? rootW : (tag.contains("RootW") ? tag.getInt("RootW") : 854));
            int h = serverSize ? tag.getInt("RootH") : (rootH > 0 ? rootH : (tag.contains("RootH") ? tag.getInt("RootH") : 480));

            HtmlNode rootNode = HtmlNodeSerializer.deserialize(domTag, null);
            installDocument(rootNode, cssString, w, h, true);
        }
        setRequested(false);
    }

    /** サーバーから届いた dom_mutations をこのセッションへ適用する。 */
    public void applyMutations(CompoundTag data) {
        if (data == null) return;
        if (document == null || document.root == null) return;
        HtmlNode root = document.root;

        List<com.nishiyu.enderframelib.network.DomMutationRecord> mutations = new ArrayList<>();
        if (data.contains("Mutations", Tag.TAG_LIST)) {
            ListTag list = data.getList("Mutations", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                mutations.add(com.nishiyu.enderframelib.network.DomMutationRecord.fromTag(list.getCompound(i)));
            }
        } else if (data.contains("Patches", Tag.TAG_LIST)) {
            ListTag list = data.getList("Patches", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                mutations.add(com.nishiyu.enderframelib.network.DomMutationRecord.fromTag(list.getCompound(i)));
            }
        }

        boolean anyChanged = false;
        for (com.nishiyu.enderframelib.network.DomMutationRecord mut : mutations) {
            // クライアント所有の部分木はサーバー変異の宛先にならない。サーバー所有なら、クライアントの書き込みを上書きして正になる。
            HtmlNode mutationTarget = root.getNodeById(mut.getTargetId());
            if (mutationTarget != null && mutationTarget.ownership() == Ownership.CLIENT) continue;

            String targetId = ClientWebManager.getBaseNodeId(mut.getTargetId());
            boolean applied = mut.applyTo(root, interactionState::markDirty);
            if (applied) {
                anyChanged = true;
                HtmlNode target = root.getNodeById(targetId);
                if (mut.getType() == com.nishiyu.enderframelib.network.DomMutationRecord.Type.ATTRIBUTE) {
                    if ("value".equalsIgnoreCase(mut.getName())) {
                        input.syncServerValueOverride(targetId,
                                "remove".equalsIgnoreCase(mut.getMode()) ? "" : mut.getValue());
                    }
                    syncAttributeToInteractionState(target, mut.getName(), !"remove".equalsIgnoreCase(mut.getMode()));
                } else if (mut.getType() == com.nishiyu.enderframelib.network.DomMutationRecord.Type.INNER_HTML && target != null) {
                    initializeBindings(target);
                }
            }
        }

        if (anyChanged) {
            ClientLocalEnvProvider.tickSessionLocalVariables(sessionId, root, root);
            requestRender();
        }
    }

    /** 書き換えても再スタイルだけで済む属性か（カスケードの入力にしかならない）。 */
    private static boolean isPresentationalAttribute(String name) {
        return "style".equalsIgnoreCase(name) || "class".equalsIgnoreCase(name) || "hidden".equalsIgnoreCase(name);
    }

    private void syncAttributeToInteractionState(HtmlNode node, String attrName, boolean present) {
        if (node == null || attrName == null) return;
        String lower = attrName.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "checked" -> interactionState.setChecked(node, present);
            case "disabled" -> interactionState.setDisabled(node, present);
            case "indeterminate" -> interactionState.setIndeterminate(node, present);
            case "open" -> interactionState.setOpen(node, present);
        }
    }

    /** ローカルの HTML を読み込んで、このセッションの文書にする。 */
    public void loadLocalHtml(String html, int width, int height) {
        explicitSize = true;
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document parsedDoc = parser.parseDocument(html, "", width, height);
        installDocument(parsedDoc.root, parsedDoc.authorCss, width, height, false);
    }

    /**
     * Headless Server State: パース済みテンプレートから生成された {@link Document} をセッションにインストールする。
     */
    public void loadTemplateDocument(Document parsedDoc) {
        loadTemplateDocument(parsedDoc, rootW, rootH);
    }

    /**
     * Headless Server State: パース済みテンプレートから生成された {@link Document} を指定サイズでセッションにインストールする。
     */
    public void loadTemplateDocument(Document parsedDoc, int width, int height) {
        if (parsedDoc == null) return;
        setHeadlessState(true);
        int targetW = width > 0 ? width : (rootW > 0 ? rootW : 854);
        int targetH = height > 0 ? height : (rootH > 0 ? rootH : 480);
        installDocument(parsedDoc.root, parsedDoc.authorCss, targetW, targetH, false);
        if (document != null && document.root instanceof HtmlDocument liveDoc) {
            liveDoc.setDefaultOwnership(Ownership.CLIENT);
        }
    }

    public ReactiveStateStore stateStore() {
        return stateStore;
    }

    public boolean isHeadlessState() {
        return headlessState;
    }

    public void setHeadlessState(boolean headlessState) {
        this.headlessState = headlessState;
    }

    private void installDocument(HtmlNode rootNode, String authorCss, int width, int height, boolean fromServer) {
        boolean existingServerDoc = fromServer && document != null;

        // サーバーからの dom_sync で既存セッションがある場合は close() を呼ばず、
        // CssAnimator の補間状態や Canvas2DContext のピクセルバッファを維持したまま差分更新する。
        if (!existingServerDoc) {
            close();
        }

        int oldRootW = rootW;
        int oldRootH = rootH;
        setRootW(width);
        setRootH(height);
        if (fromServer) setServerManaged(true);

        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = document;
        if (doc == null || !existingServerDoc) {
            StyleSheet sheet = parser.buildStyleSheet(authorCss, width, height);
            applyResolution(sheet);
            doc = new Document(rootNode, sheet, authorCss);
            setDocument(doc);
        } else {
            if (!Objects.equals(doc.authorCss, authorCss) || doc.sheet == null) {
                doc.sheet = parser.buildStyleSheet(authorCss, width, height);
                applyResolution(doc.sheet);
                doc.authorCss = authorCss;
            } else if (oldRootW != width || oldRootH != height) {
                // ビューポートだけが変わったときは CSS を読み直さず、@media の条件だけ評価し直す
                doc.sheet.updateMediaEnvironment(doc.sheet.mediaEnvironment().withSize(width, height));
            }
            adoptServerTree(doc.root, rootNode);
        }

        initializeBindings(doc.root);
        ClientLocalEnvProvider.tickSessionLocalVariables(sessionId, doc.root, doc.root);
        ClientWebManager.applyOverrides(sessionId);

        interactionState.markAllDirty();
        // 再同期で同じ文書を差し替えたときは、使い回せる箱（クライアント所有の島など）を前回の木から拾う
        LayoutBox rootBox = parser.layoutDocument(
                doc, width, height, env, scrollState, WebFontManager.McFontResolver.INSTANCE, interactionState,
                existingServerDoc ? layoutRoot : null);
        setLayoutRoot(rootBox);
        List<DisplayItem> elements = UiDocumentCompiler.clientBoxRenderer != null
                ? UiDocumentCompiler.clientBoxRenderer.render(rootBox, width, height, doc.sheet.keyframes, scrollState)
                : new ArrayList<>();
        setRenderCache(elements);

        ClientWebManager.applyOverrides(sessionId);
    }

    /**
     * 再同期で届いた全木を、生きている文書の中身にする。文書のハンドル（{@code doc.root}）は変えない。
     * クライアント所有（{@code data-owner="client"}）の部分木は捨てず、新しい木の同じ id の位置へ付け替える。
     * 新しい木に同じ id の要素が無ければ、その持ち主がサーバー側で消えたのでクライアント所有の部分木も一緒に消える。
     */
    private static void adoptServerTree(HtmlNode liveRoot, HtmlNode freshRoot) {
        if (liveRoot == freshRoot) return;

        Map<String, HtmlNode> clientIslands = new LinkedHashMap<>();
        collectClientOwnedIslands(liveRoot, clientIslands);

        for (HtmlNode stale : new ArrayList<>(liveRoot.children)) {
            liveRoot.removeChild(stale);
        }
        for (HtmlNode fresh : new ArrayList<>(freshRoot.children)) {
            liveRoot.appendChild(fresh);
        }

        for (Map.Entry<String, HtmlNode> island : clientIslands.entrySet()) {
            HtmlNode placeholder = liveRoot.getNodeById(island.getKey());
            if (placeholder != null && placeholder.parent != null) {
                placeholder.parent.replaceChild(island.getValue(), placeholder);
            }
        }
    }

    /** 最上位のクライアント所有の部分木（id を持つもの）を集める。その内側は島ごと運ぶので降りない。 */
    private static void collectClientOwnedIslands(HtmlNode node, Map<String, HtmlNode> islands) {
        for (HtmlNode child : node.children) {
            boolean declaredClientOwned = Ownership.parse(child.attrs.get(Ownership.ATTRIBUTE)) == Ownership.CLIENT;
            if (declaredClientOwned && !child.getId().isEmpty()) {
                islands.put(child.getId(), child);
            } else {
                collectClientOwnedIslands(child, islands);
            }
        }
    }

    /** ビューポートを変更してレイアウト・描画要素を作り直す。 */
    public void recomputeLayout(int newRootW, int newRootH) {
        int oldRootW = rootW;
        int oldRootH = rootH;
        setRootW(newRootW);
        setRootH(newRootH);
        if (pendingResizeW == newRootW && pendingResizeH == newRootH) {
            pendingResizeW = -1;
            pendingResizeH = -1;
        }

        ClientWebManager.applyOverrides(sessionId);

        Document doc = document;
        if (doc != null && doc.root != null) {
            long startedAt = System.nanoTime();
            UiDocumentCompiler parser = new UiDocumentCompiler();

            // @media はシートを読み直さず、新しいビューポートで条件だけ評価し直す
            if (doc.sheet == null) {
                doc.sheet = parser.buildStyleSheet(doc.authorCss, newRootW, newRootH);
                applyResolution(doc.sheet);
                interactionState.markAllDirty();
            } else if (oldRootW != newRootW || oldRootH != newRootH) {
                boolean mediaChanged = doc.sheet.updateMediaEnvironment(
                        doc.sheet.mediaEnvironment().withSize(newRootW, newRootH));
                if (mediaChanged) interactionState.markAllDirty();
            }

            // 前回の箱の木から差分レイアウトする（変わっていない部分木は measure を飛ばす）
            LayoutBox rootBox = parser.layoutDocument(
                    doc, newRootW, newRootH, env, scrollState, WebFontManager.McFontResolver.INSTANCE, interactionState,
                    layoutRoot);
            setLayoutRoot(rootBox);
            List<DisplayItem> elements = UiDocumentCompiler.clientBoxRenderer != null
                    ? UiDocumentCompiler.clientBoxRenderer.render(rootBox, newRootW, newRootH, doc.sheet.keyframes, scrollState)
                    : new ArrayList<>();
            setRenderCache(elements);
            ClientWebManager.applyOverrides(sessionId);
            lastLayoutFinishedNanos = System.nanoTime();
            lastLayoutCostNanos = lastLayoutFinishedNanos - startedAt;
        }
    }

    /** 描画 ID が指す表示要素。無ければ null。 */
    public DisplayItem elementById(String id) {
        if (id == null) return null;
        String targetBase = ClientWebManager.getBaseNodeId(id);
        DisplayItem fallback = null;
        List<DisplayItem> elements = renderCache();
        for (DisplayItem el : elements) {
            if (el.id().equals(id) && el.type() != ElementType.NODE) {
                return el;
            }
            if (targetBase.equals(ClientWebManager.getBaseNodeId(el.id()))) {
                if (el.type() == ElementType.NODE || el.id().endsWith("_ph") || el.id().contains("_shadow") || el.id().contains("_b_") || el.id().contains("_o_") || el.id().contains("_bgl")) {
                    if (fallback == null) fallback = el;
                    continue;
                }
                return el;
            }
        }
        return fallback;
    }

    public void updateNodeAttributeLocally(String elementId, String attrName, String attrValue, boolean remove) {
        String baseId = ClientWebManager.getBaseNodeId(elementId);
        HtmlNode target = findNode(baseId);
        if (target == null) return;

        DomUtils.updateAttribute(target, attrName, attrValue, remove);
        if ("value".equalsIgnoreCase(attrName)) {
            updateElementTextLocally(baseId, remove || attrValue == null ? "" : attrValue);
        }
        syncAttributeToInteractionState(target, attrName, !remove);
        interactionState.markDirty(target);
        requestRender();
    }

    public void updateNodeInnerHtmlLocally(String elementId, String html) {
        HtmlNode target = findNode(ClientWebManager.getBaseNodeId(elementId));
        if (target == null) return;

        target.setInnerHTML(html);
        initializeBindings(target);
        interactionState.markDirty(target);
        requestRender();
    }

    public void updateNodeStyleLocally(String elementId, String prop, String val) {
        HtmlNode target = findNode(ClientWebManager.getBaseNodeId(elementId));
        if (target == null) return;

        target.setStyleProperty(prop, val);
        interactionState.markDirty(target);
        // インライン style だけの変化なので再スタイルから始める（left / top だけなら位置の決め直しで済む）
        requestRestyle();
    }

    public boolean handleScrollLocally(double pixelX, double pixelY, double scrollX, double scrollY) {
        List<DisplayItem> elements = elements();
        float sens = EnderFrameBuiltInConfig.scrollSensitivity;

        for (int i = elements.size() - 1; i >= 0; i--) {
            DisplayItem el = elements.get(i);
            if (el.events() != null && el.events().containsKey(RenderKeys.MC_SCROLLABLE)) {
                CssAnimator.AnimatedValues anim = CssAnimator.getCurrentValues(sessionId, el);
                double[] localPt = inverseTransform(pixelX, pixelY, el, anim);
                double localX = localPt[0] - el.x();
                double localY = localPt[1] - el.y();

                if (localX >= 0 && localX <= el.width() && localY >= 0 && localY <= el.height()) {
                    String[] maxStrs = el.events().get(RenderKeys.MC_SCROLLABLE).split(",");
                    float maxW = Float.parseFloat(maxStrs[0]), maxH = Float.parseFloat(maxStrs[1]);

                    ScrollState state = scrollState;
                    // 何が壊れていたか: ここだけが描画 ID (foo_bg など) で読み書きしていた。
                    // 読み手 (LayoutBox / DisplayListBuilder) はベースノード ID を使うため、
                    // スクロール可能な部分要素が _bg だったコンテナはホイールが無反応だった。
                    HtmlNode scrollNode = findNode(el.baseId());
                    if (scrollNode == null) continue;
                    float curX = state.getScrollX(scrollNode);
                    float curY = state.getScrollY(scrollNode);

                    float limitX = Math.max(0, maxW - el.width()), limitY = Math.max(0, maxH - el.height());
                    if (limitX > 0 || limitY > 0) {
                        float nextX = Mth.clamp(curX - (float) scrollX * sens, 0, limitX);
                        float nextY = Mth.clamp(curY - (float) scrollY * sens, 0, limitY);

                        if (Math.abs(nextX - curX) > 0.1f || Math.abs(nextY - curY) > 0.1f) {
                            state.setScroll(scrollNode, (int) nextX, (int) nextY);
                            requestReposition();
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    public boolean handleScrollbarClick(DisplayItem clickedEl, double pixelX, double pixelY) {
        if (clickedEl == null || clickedEl.id() == null) return false;
        String id = clickedEl.id();
        boolean isVTrack = id.endsWith("_sb_vt");
        boolean isHTrack = id.endsWith("_sb_ht");
        boolean isVThumb = id.endsWith("_sb_v");
        boolean isHThumb = id.endsWith("_sb_h");
        if (!isVTrack && !isHTrack && !isVThumb && !isHThumb) return false;

        if (layoutRoot == null) return false;

        HtmlNode scrollNode = findNode(ClientWebManager.getBaseNodeId(id));
        LayoutBox box = scrollNode != null ? layoutRoot.findByNode(scrollNode) : null;
        if (box == null) return false;

        ScrollState state = scrollState;

        if (isVTrack) {
            float trackY = box.y + box.bT + 2;
            float trackH = box.h - box.bT - box.bB - 4;
            float thumbH = Math.max(12, trackH * (box.h / (float) Math.max(1, box.scrollHeight)));
            float availH = Math.max(1, trackH - thumbH);
            float ratio = Math.clamp((float) (pixelY - trackY - thumbH / 2.0) / (float) availH, 0f, 1f);
            float maxScrollY = Math.max(0, box.scrollHeight - box.h);
            float curX = state.getScrollX(scrollNode);
            state.setScroll(scrollNode, (int) curX, (int) (ratio * maxScrollY));
            requestReposition();
            return true;
        } else if (isHTrack) {
            float trackX = box.x + box.bL + 2;
            float trackW = box.w - box.bL - box.bR - 4;
            float thumbW = Math.max(12, trackW * (box.w / (float) Math.max(1, box.scrollWidth)));
            float availW = Math.max(1, trackW - thumbW);
            float ratio = Math.clamp((float) (pixelX - trackX - thumbW / 2.0) / (float) availW, 0f, 1f);
            float maxScrollX = Math.max(0, box.scrollWidth - box.w);
            float curY = state.getScrollY(scrollNode);
            state.setScroll(scrollNode, (int) (ratio * maxScrollX), (int) curY);
            requestReposition();
            return true;
        }
        return false;
    }

    public boolean handleScrollbarDrag(String draggingId, double pixelX, double pixelY) {
        if (draggingId == null || draggingId.isEmpty()) return false;
        boolean isVThumb = draggingId.endsWith("_sb_v") || draggingId.endsWith("_sb_vt");
        boolean isHThumb = draggingId.endsWith("_sb_h") || draggingId.endsWith("_sb_ht");
        if (!isVThumb && !isHThumb) return false;

        if (layoutRoot == null) return false;

        HtmlNode scrollNode = findNode(ClientWebManager.getBaseNodeId(draggingId));
        LayoutBox box = scrollNode != null ? layoutRoot.findByNode(scrollNode) : null;
        if (box == null) return false;

        ScrollState state = scrollState;

        if (isVThumb) {
            float trackY = box.y + box.bT + 2;
            float trackH = box.h - box.bT - box.bB - 4;
            float thumbH = Math.max(12, trackH * (box.h / (float) Math.max(1, box.scrollHeight)));
            float availH = Math.max(1, trackH - thumbH);
            float ratio = Math.clamp((float) (pixelY - trackY - thumbH / 2.0) / (float) availH, 0f, 1f);
            float maxScrollY = Math.max(0, box.scrollHeight - box.h);
            float curX = state.getScrollX(scrollNode);
            state.setScroll(scrollNode, (int) curX, (int) (ratio * maxScrollY));
            requestReposition();
            return true;
        } else if (isHThumb) {
            float trackX = box.x + box.bL + 2;
            float trackW = box.w - box.bL - box.bR - 4;
            float thumbW = Math.max(12, trackW * (box.w / (float) Math.max(1, box.scrollWidth)));
            float availW = Math.max(1, trackW - thumbW);
            float ratio = Math.clamp((float) (pixelX - trackX - thumbW / 2.0) / (float) availW, 0f, 1f);
            float maxScrollX = Math.max(0, box.scrollWidth - box.w);
            float curY = state.getScrollY(scrollNode);
            state.setScroll(scrollNode, (int) (ratio * maxScrollX), (int) curY);
            requestReposition();
            return true;
        }
        return false;
    }

    private static double[] inverseTransform(double px, double py, DisplayItem el, CssAnimator.AnimatedValues anim) {
        RenderMeta meta = el.meta();
        float originX = meta != null ? meta.originX() : 0.5f;
        float originY = meta != null ? meta.originY() : 0.5f;

        float cx = el.x() + el.width() * originX;
        float cy = el.y() + el.height() * originY;

        double dx = px - cx - anim.tx();
        double dy = py - cy - anim.ty();
        if (anim.rot() != 0.0f) {
            double rad = -Math.toRadians(anim.rot());
            double cos = Math.cos(rad);
            double sin = Math.sin(rad);
            double nx = dx * cos - dy * sin;
            double ny = dx * sin + dy * cos;
            dx = nx;
            dy = ny;
        }
        if (anim.sx() != 0 && anim.sx() != 1.0f) dx /= anim.sx();
        if (anim.sy() != 0 && anim.sy() != 1.0f) dy /= anim.sy();

        return new double[]{dx + cx, dy + cy};
    }

    public UIHitResult hitTest(double pixelX, double pixelY) {
        return hitTestIndex().hitTest(sessionId, pixelX, pixelY);
    }

    public UIHitResult localCoords(String elementId, double pixelX, double pixelY) {
        DisplayItem el = elementById(elementId);
        if (el == null) return null;
        return hitTestIndex().getLocalCoords(sessionId, el, pixelX, pixelY);
    }

    public void updateElementTextLocally(String elementId, String newText) {
        String safeText = newText != null ? newText : "";
        String baseId = ClientWebManager.getBaseNodeId(elementId);

        HtmlNode target = findNode(baseId);
        boolean isRange = target != null && "range".equalsIgnoreCase(target.attrs.getOrDefault("type", ""));
        boolean isSelect = target != null && "select".equalsIgnoreCase(target.tag);

        String domValue = safeText;
        if (isRange && safeText.contains(":")) {
            domValue = safeText.split(":")[0];
        }

        if (target != null && !Objects.equals(target.attrs.get("value"), domValue)) {
            DomUtils.updateAttribute(target, "value", domValue, false);
        }
        // 書き戻し先は平行移動を掛ける前の一覧（掛けた後を戻すと二重に掛かる）
        List<DisplayItem> elements = baseRenderCache;
        if (elements != null && !elements.isEmpty()) {
            String phId = baseId + "_ph";
            String inputRenderId = baseId + "_input";
            String textareaRenderId = baseId + "_textarea";
            String selectRenderId = baseId + "_select";
            String placeholderOrig = target != null ? target.attrs.getOrDefault("placeholder", "") : "";

            List<DisplayItem> updated = null;
            for (int i = 0; i < elements.size(); i++) {
                DisplayItem el = elements.get(i);
                if (el.id().equals(elementId) || el.id().equals(inputRenderId) || el.id().equals(textareaRenderId) || el.id().equals(selectRenderId)) {
                    String renderText = safeText;
                    if ((el.type() == ElementType.RANGE || el.type() == ElementType.PROGRESS) && !safeText.contains(":")) {
                        String min = target != null ? target.attrs.getOrDefault("min", "0") : "0";
                        String max = target != null ? target.attrs.getOrDefault("max", "100") : "100";
                        renderText = safeText + ":" + min + ":" + max;
                    } else if (el.type() == ElementType.SELECT || isSelect) {
                        renderText = DomUtils.resolveSelectDisplayLabel(target, domValue) + " \u25be";
                    }
                    if (!Objects.equals(el.text(), renderText)) {
                        if (updated == null) updated = new ArrayList<>(elements);
                        updated.set(i, el.withText(renderText));
                    }
                } else if (el.id().equals(phId)) {
                    String nextPh = domValue.isEmpty() ? placeholderOrig : "";
                    if (!Objects.equals(el.text(), nextPh)) {
                        if (updated == null) updated = new ArrayList<>(elements);
                        updated.set(i, el.withText(nextPh));
                    }
                }
            }
            if (updated != null) {
                setRenderCache(updated);
            }
        }
    }

    /** このセッションの入力欄キャレット状態（描画・編集の境界で使う文字列 ID）を消す。 */
    public void clearTextEditState(String renderId) {
        inputScrollOffsets.remove(renderId);
        textareaCursorIndex.remove(renderId);
        textareaScrollRow.remove(renderId);
    }

    public record UIHitResult(DisplayItem element, double localX, double localY) {}

    public void initializeBindings(HtmlNode target) {
        if (target == null) return;
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
                com.nishiyu.enderframelib.client.renderer.WebHudRenderer.enableHudTemplate(sessionId, targetId, origDisplay, templateRoot, requireNbt);
            }

            @Override
            public void bindTracker(HtmlNode templateNode, double radius, double yOffset, String targetType, List<String> requireNbt) {
                com.nishiyu.enderframelib.client.renderer.WebHudRenderer.setTrackerTemplate(sessionId, templateNode, radius, yOffset, targetType, requireNbt);
            }
        });
    }

    public void close() {
        boolean hadHudState = hudConfig != null || trackerEntry != null;

        renderCache = Collections.emptyList();
        baseRenderCache = Collections.emptyList();
        paintOffsets.clear();
        paintOffsetsDirty = false;
        hitTestIndex = null;
        if (document != null && document.root instanceof HtmlDocument liveDocument) {
            liveDocument.setDomChangeListener(null);
        }
        document = null;
        layoutRoot = null;
        env.clear();
        interactionState.clear();
        invalidationLevel = InvalidationLevel.NONE;
        repositionPending = false;
        pendingResizeW = -1;
        pendingResizeH = -1;
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
        stateStore.clearBindings();

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
        /** 押下時にヒットした描画要素。click のアクション名を GUI へ返すときに使う。 */
        DisplayItem pressedElement = null;
        String lastSubmittedId = "";
        String lastSubmittedText = "";
        long lastSubmitTime = 0;
        final Map<String, String> localInputCache = new ConcurrentHashMap<>();
        final Map<String, String> lastCommittedValues = new ConcurrentHashMap<>();

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
            pressedElement = null;
            lastSubmittedId = "";
            lastSubmittedText = "";
            lastSubmitTime = 0;
            localInputCache.clear();
            lastCommittedValues.clear();
            lastClickNode = null;
            lastClickTime = 0;
        }
    }
}
