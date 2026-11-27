package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.kernel.Clock;
import com.nishiyu.enderframelib.core.scene3d.Raycaster3D;
import com.nishiyu.enderframelib.core.scene3d.Scene3DTags;
import com.nishiyu.enderframelib.core.scene3d.Scene3DWorld;

import com.nishiyu.enderframelib.core.paint.AnimationStates;
import com.nishiyu.enderframelib.common.EnderFrameBuiltInConfig;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.api.event.Event;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.dom.node.DomUtils;
import com.nishiyu.enderframelib.core.dom.node.EventForwardDeclaration;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.Bindings;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.Ownership;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.TextSelection;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.ElementType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.BoxLayoutQuery;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.paint.scene.DisplayScene;
import com.nishiyu.enderframelib.core.paint.scene.DynamicSceneState;
import com.nishiyu.enderframelib.core.paint.scene.SceneHit;
import com.nishiyu.enderframelib.core.paint.scene.SceneHitTest;
import com.nishiyu.enderframelib.core.paint.scene.SceneLayer;
import com.nishiyu.enderframelib.core.paint.scene.SceneLocation;
import com.nishiyu.enderframelib.core.paint.scene.SceneQuery;
import com.nishiyu.enderframelib.core.paint.scene.Transform2D;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontStyle;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

/**
 * Minecraft に依存しない 1 つの「生きた画面」。DOM ツリー・スタイル・レイアウト・描画要素のキャッシュ・
 * 環境変数・スクロール・フォーカス・無効化の段階を 1 オブジェクトに集約する（旧 {@code SessionContext} の純 Java 部分）。
 *
 * <p>ホスト固有の事情（描画キューへの通知、フォント、サーバー同期、入力処理、HUD など）は、
 * 空の既定を持つ {@code protected} のフックを継承クラスが上書きして差し込む。Minecraft 側は
 * {@code SessionContext} がその継承クラスで、スタンドアロンでは何も上書きせずにそのまま使える。</p>
 */
public class UiView implements AutoCloseable {
    /**
     * 画面更新の要求の段階（F3-2）。入口は変更台帳 1 本（M2c-3）で、段階はフレームを予約する強さだけを表す。
     * <ul>
     *   <li>{@link #NONE}: 再計算不要</li>
     *   <li>{@link #REPAINT}・{@link #RESTYLE}: 台帳に載った変化を増分で反映する</li>
     *   <li>{@link #REPOSITION}: スクロール量だけの変化（中身の節を動かして済ませられるか先に試す）</li>
     *   <li>{@link #RELAYOUT}: 前回の木と記憶を捨てて全体を作り直す（台帳に載らない全体の変化）</li>
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

    private volatile String sessionId;
    private volatile List<DisplayItem> renderCache = Collections.emptyList();
    /** 描画の上だけの平行移動（スクロール・ドラッグ）の外部表（C6）。 */
    private final PaintOffsets paintOffsets = new PaintOffsets();
    /** ユーザー操作（ドラッグ等）による描画平行移動。スクロールや sticky は {@link DynamicSceneState} が解くため分離する。 */
    private final PaintOffsets userPaintOffsets = new PaintOffsets();
    Document document;
    private final Map<Long, Scene3DWorld> scene3dWorlds = new ConcurrentHashMap<>();
    /** ワールドごとのレイキャスト（BVH を持つので使い回す）。 */
    private final Map<Long, Raycaster3D> scene3dRaycasters = new ConcurrentHashMap<>();
    /** {@code <scene-3d>} の子要素（{@code <mesh-3d>} など）の属性を 3D ワールドへ写す（再スタイル・再レイアウトを伴うフレームで走る）。 */
    private Scene3DDomSync scene3dSync = new Scene3DDomSync(this::getOrCreateScene3dWorld);
    /** このビューの 1 フレームの組み立て（箱の木 → 断片木 → 描画用の木 → 表示リスト。状態はこのビューに閉じる）。 */
    final V3Frame v3 = new V3Frame();
    private boolean serverManaged = false;
    private boolean headlessState = false;
    private boolean requested = false;
    private long lastSyncRequestTime = 0L;
    private int domHash = 0;
    int rootW = 854;
    int rootH = 480;
    /** 1 CSS px あたりのデバイスピクセル（{@code @media (resolution)} が見る値）。 */
    private float devicePixelRatio = 1.0f;
    final Map<String, String> env = new ConcurrentHashMap<>();
    final ScrollState scrollState = new ScrollState();
    final InteractionState interactionState = new InteractionState();
    private volatile InvalidationLevel invalidationLevel = InvalidationLevel.NONE;
    /** 位置の決め直しが要求されている（同じフレームの再スタイルがレイアウト不要でも、スクロールは反映する）。 */
    private volatile boolean repositionPending = false;
    /** スクロール以外の理由（レイアウトの問い合わせ・JS のスクロール指定など）でも位置の更新が要求された。 */
    private volatile boolean nonScrollRepositionPending = false;
    /**
     * 中身を 1 つの節にまとめたスクロールコンテナ（直近に作った表示リストの）。スクロールだけの更新は、表示リストを作り直さずに
     * この節の移動量を更新して済ませる（{@link #tryScrollTranslate}）。
     */
    private List<ScrollContentInfo> scrollBase = List.of();
    private final java.util.Set<HtmlNode> handledScrollNodes =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /** {@link #scrollBase} の表示リストを作ったときの、全ての節のスクロール位置（0 でないもの）。 */
    Map<HtmlNode, float[]> scrollAtBuild = Map.of();
    /** 表示リストの窓（画面と余白の範囲に作った項目）を作ったときのスクロール位置。節の移動で済ませてよい範囲はこれを基準にする（箱の位置の基準は {@link #scrollAtBuild}）。 */
    private Map<HtmlNode, float[]> scrollWindowAt = Map.of();

    /** 診断: 位置の更新を、節の移動で済ませた回数・スクロールだけなのに作り直した回数・スクロール以外で作り直した回数。 */
    public static final java.util.concurrent.atomic.LongAdder STAT_SCROLL_TRANSLATED = new java.util.concurrent.atomic.LongAdder(),
            STAT_SCROLL_FALLBACK = new java.util.concurrent.atomic.LongAdder();
    /** 節の移動で済ませる範囲（px）。 */
    private static final float SCROLL_TRANSLATE_LIMIT = com.nishiyu.enderframelib.core.kernel.EngineFlags.CULL_MARGIN - 64f;
    /** まだ反映していないビューポートの大きさ。無ければ -1。 */
    /**
     * GUI など、クライアントが画面の大きさを自分で決めているセッションか。そうでない（ブロック・アイテム・ホログラムの
     * ように世界に貼る）セッションは、サーバーが読み込んだときの解像度を画面の大きさとして使う。
     */
    private boolean explicitSize;

    /** 画面の大きさをクライアントが自分で決めているか。 */
    public boolean isExplicitSize() {
        return explicitSize;
    }

    public void markExplicitSize() {
        explicitSize = true;
    }

    private volatile int pendingResizeW = -1;
    private volatile int pendingResizeH = -1;
    /** 直前の全レイアウト（描画要素の生成まで）にかかった時間と、それを終えた時刻（ナノ秒）。 */
    long lastLayoutCostNanos = 0L;
    long lastLayoutFinishedNanos = 0L;
    /** このビューの時計（間引き・レイアウトの所要時間・アニメーション）。差し替えは {@code StandaloneView.clock}（K0 ①）。 */
    Clock clock = Clock.SYSTEM;

    private String activeInputId = "";
    /** {@link #activeInputId} の持ち主の節の ID。描画 ID の綴りから逆算せず、入力欄を開いた時点の項目から取る。 */
    private String activeInputBaseId = "";
    private final TextSelection textSelection = new TextSelection();

    // --- セッションに属する入力・描画・バインディング状態（S1） ---
    private final InputSessionState inputState = new InputSessionState();
    private final AnimationStates cssAnimations = new AnimationStates(); // 補間の状態（節ごと。M3b-4b-2。ホストが置き場に差し込む）
    private final Map<String, Integer> inputScrollOffsets = new ConcurrentHashMap<>();
    private final Map<String, Integer> textareaCursorIndex = new ConcurrentHashMap<>();
    private final Map<String, Integer> textareaScrollRow = new ConcurrentHashMap<>();

    private long envLastUpdate = 0L;
    private Set<String> envUsedKeys = null;
    private Map<String, String> envApplied = null;

    private final Set<String> subscriptions = ConcurrentHashMap.newKeySet();
    private final UiTimers timers;
    private final List<UiPubSub.DataBinding> dataBindings = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<UiPubSub.VirtualListBinding> virtualListBindings = new java.util.concurrent.CopyOnWriteArrayList<>();

    public UiView(String sessionId) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.timers = new UiTimers(sessionId);
        interactionState.bindStyleLookup(v3::styleOf); // CSSOM の算出スタイルは箱の木から
    }

    private DynamicSceneState cachedDynState;
    private SceneLayer cachedDynRoot;
    private int cachedDynW = -1, cachedDynH = -1;
    private UiHitSupport hitSupport;

    /** 直近のフレームの不変描画シーン（まだ無ければ null）。 */
    public DisplayScene scene() {
        return v3.scene();
    }

    /** 直近のフレームの動的状態（スクロール位置・ビューポート寸法。シーンが無ければ null）。 */
    public DynamicSceneState dynamicState() {
        DisplayScene s = v3.scene();
        if (s == null) return null;
        if (cachedDynState != null && cachedDynRoot == s.root && cachedDynW == rootW && cachedDynH == rootH) {
            return cachedDynState;
        }
        cachedDynRoot = s.root;
        cachedDynW = rootW;
        cachedDynH = rootH;
        cachedDynState = DynamicSceneState.of(scrollState, rootW, rootH);
        return cachedDynState;
    }

    /** シーン内の要素 {@code node} の解決済み位置・大きさ（無ければ null）。 */
    public com.nishiyu.enderframelib.core.paint.scene.SceneLocation locate(HtmlNode node) {
        return SceneQuery.locate(v3.scene(), dynamicState(), node);
    }

    /** 新経路の 1 フレーム（{@code full} なら台帳を読まずに全体を作り直す）。汚れた節の印は台帳に写してあるので片付ける。 */
    private void v3Frame(boolean full) {
        if (document == null || document.root == null) return;
        interactionState.bindStyleSheet(document.sheet); // DOM の変更を規則の範囲（兄弟・祖先）へ広げる無効化セットを引くシート
        // 全体の作り直しは DOM を全て読むので、溜まっている変更（文書を入れたときの markAllDirty など）もこのフレームで消化する。
        // 凍結しておかないと、次のフレームが同じ変更を読んで、もう一度全体を組み直す（静止フレームにならない）
        if (full) interactionState.changeLedger().freeze();
        com.nishiyu.enderframelib.core.dom.node.ChangeLedger ledger = full ? null : interactionState.changeLedger();
        List<DisplayItem> elements = v3.frame(document, ledger, rootW, rootH, devicePixelRatio, env, interactionState, scrollState, fontResolver());
        interactionState.clearAllStyleDirty();
        if (elements == null) { // シーンが前回のまま: スクロールが無ければ何もしない、あれば中身の節を動かす
            if (!v3.hasEffectiveScrollChange(scrollState, scrollAtBuild)) return;
            if (tryScrollTranslate()) { STAT_SCROLL_TRANSLATED.increment(); return; }
            elements = v3.paint();
        }
        publishBuilt(elements);
        publishLayoutToDom();
        applyOverrides();
    }

    // ------------------------------------------------------------------
    // ホストが差し込むフック（既定は何もしない）
    // ------------------------------------------------------------------

    /** 再計算が要求された（描画キューへの通知先）。 */
    protected void invalidated() {
    }

    /** 描画要素を読む前の準備（環境変数の更新や、溜まった描画要求の消化）。 */
    protected void beforeElements() {
    }

    /** レイアウトの前後で、ホストが持つ上書き（スクリプトによる属性など）を反映する。 */
    protected void applyOverrides() {
    }

    /** 環境変数を使うテンプレート変数を更新する。 */
    protected void tickLocalVariables(HtmlNode root) {
    }

    /** 文字の測定に使うフォント。 */
    protected FontResolver fontResolver() {
        return SystemFonts.system();
    }

    /** インラインのイベント属性（{@code onclick="..."}）のアクションを実行する。 */
    protected void runInlineAction(HtmlNode node, Event event, String action) {
    }

    /** 宣言的に転送するイベント（サーバーへ送るなど）を処理する。 */
    protected void forwardDeclared(HtmlNode node, Event event, EventForwardDeclaration declaration) {
    }

    /** 宣言的バインディング（{@code data-bind} など）を節の部分木から探して結ぶ。 */
    public void initializeBindings(HtmlNode target) {
    }

    /** {@link #close()} でホスト固有の状態を捨てる。 */
    protected void onClose() {
    }

    /** このビューの {@code requestAnimationFrame} / タイマーの登録簿。 */
    public UiTimers timers() {
        return timers;
    }

    public List<UiPubSub.DataBinding> dataBindings() {
        return dataBindings;
    }

    public List<UiPubSub.VirtualListBinding> virtualListBindings() {
        return virtualListBindings;
    }

    public String sessionId() {
        return sessionId;
    }

    /**
     * 描画側が補間の状態を引く ID に合わせる。スタンドアロンでは描画バックエンドが ID を持つので、
     * ヒットテスト（変形中の要素の逆変換）が同じ補間値を見るようにここで合わせる。
     */
    public void adoptSessionId(String id) {
        if (id != null && !id.isEmpty()) this.sessionId = id;
    }

    /** アニメーションを進める時刻（ms）。既定は現在時刻。時間をホストが握るとき（仮想時計）は上書きする。 */
    protected long nowMs() {
        return clock.millis();
    }

    /** この画面の描画上の平行移動表の不変スナップショット（フレーム境界越え用、{@link DisplayItem} の外に持つ）。 */
    public PaintOffsets paintOffsets() {
        return paintOffsets.snapshot();
    }

    public List<DisplayItem> renderCache() {
        return renderCache;
    }

    public void setRenderCache(List<DisplayItem> renderCache) {
        if (renderCache == null || renderCache.isEmpty()) {
            this.renderCache = Collections.emptyList();
        } else if (renderCache instanceof ImmutableItemList) {
            this.renderCache = renderCache;
        } else {
            ImmutableItemList prev = this.renderCache instanceof ImmutableItemList il ? il : null;
            this.renderCache = ImmutableItemList.from(prev, renderCache);
        }
    }

    /**
     * 要素を描画の上だけで平行移動する（レイアウトも表示リストも変えない）。
     * 移動量は {@link PaintOffsets} に記録し、不変の {@link DisplayItem} は作り直さない。
     */
    public void setPaintOffset(String elementId, float dx, float dy) {
        paintOffsets.set(elementId, dx, dy);
        userPaintOffsets.set(elementId, dx, dy);
    }

    public void clearPaintOffset(String elementId) {
        paintOffsets.clear(elementId);
        userPaintOffsets.clear(elementId);
    }

    /** 画像の透過ヒットテスト（{@code <img data-hit-alpha>}）が不透明度を引く先。ホストが画像の読み方を差し込む。 */
    private volatile com.nishiyu.enderframelib.core.paint.AlphaMaskProvider alphaMaskProvider;

    public void alphaMaskProvider(com.nishiyu.enderframelib.core.paint.AlphaMaskProvider provider) {
        this.alphaMaskProvider = provider;
    }

    /** 点が対話要素の上にあるか（ゲーム側が、クリックを UI に渡すか自分で処理するかを決めるのに使う）。 */
    public boolean isPointerOverUi(double pixelX, double pixelY) {
        return SceneHitTest.isPointerOverUi(scene(), hitTestContext(), pixelX, pixelY);
    }

    /** 直近のフレームの不変描画シーンと動的状態から {@link SceneHitTest.Context} を組み立てる。 */
    public SceneHitTest.Context hitTestContext() {
        if (hitSupport == null) hitSupport = new UiHitSupport(sessionId, interactionState, userPaintOffsets);
        return hitSupport.context(dynamicState(), alphaMaskProvider, document);
    }

    public Document document() { return document; }

    public void setDocument(Document document) {
        this.document = document;
        bindLiveDocument();
    }

    /** 文書のルート。文書が無ければ null。 */
    public HtmlNode rootNode() { return document != null ? document.root : null; }

    /** 公開面の {@link com.nishiyu.enderframelib.api.dom.Document}。文書が無い・実装でないときは null。 */
    public com.nishiyu.enderframelib.api.dom.Document domDocument() {
        return document != null && document.root instanceof com.nishiyu.enderframelib.api.dom.Document doc ? doc : null;
    }

    /** 文書の外へ出ていくイベントの送り先と、所有者の既定をこのセッションに結ぶ。 */
    private void bindLiveDocument() {
        if (document == null || !(document.root instanceof HtmlDocument liveDocument)) return;
        liveDocument.setDefaultOwnership(serverManaged ? Ownership.SERVER : Ownership.CLIENT);
        liveDocument.setInteractionState(interactionState);
        if (boxLayoutQuery == null) boxLayoutQuery = new BoxLayoutQuery(scrollState, this::requestReposition);
        liveDocument.setLayoutQuery(boxLayoutQuery);
        // DOM の操作（クライアントのスクリプト・サーバーの変異・バインディング）は、文書が汚れの印を付けて知らせてくる。
        // どの経路から変えても同じ無効化になるよう、属性なら対話状態（checked / open など）もここで合わせる。
        // 見た目の属性（style / class / hidden）だけなら再スタイルから、文字・子の並び・その他の属性なら再レイアウトから始める
        liveDocument.setDomChangeListener((node, attributeName) -> {
            if (attributeName != null) {
                syncAttributeToInteractionState(node, attributeName, node.attrs.containsKey(attributeName));
            }
            if (attributeName != null && isPresentationalAttribute(attributeName)) {
                requestRestyle();
            } else if (attributeName != null && isPaintOnlyAttribute(node, attributeName)) {
                requestRestyle(); // 描画だけの属性・文字の変更は、台帳から restyleAndUpdate が読む（K5b L1）
            } else if (attributeName == null && node.nodeType == NodeType.TEXT) {
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
                UiView.this.runInlineAction(node, event, action);
            }

            @Override
            public void forward(HtmlNode node, Event event, EventForwardDeclaration declaration) {
                UiView.this.forwardDeclared(node, event, declaration);
            }
        });
    }

    /** CSSOM View が読むシーンを差し替え、ResizeObserver / IntersectionObserver へ知らせる。 */
    void publishLayoutToDom() {
        DisplayScene scene = v3.scene();
        if (boxLayoutQuery == null || document == null || scene == null) return;
        boxLayoutQuery.update(scene, document.sheet, rootW, rootH);
        if (document.root instanceof HtmlDocument live) live.notifyLayoutObservers();
    }

    /** CSSOM View のレイアウト結果の読み口（文書が生きている間に作る）。 */
    private BoxLayoutQuery boxLayoutQuery;

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
        } else if (doc != null && doc.root != null) {
            // srcset / image-set() の選びと SVG の画素数が密度で変わる
            interactionState.markAllDirty();
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
        if (this.activeInputId.isEmpty()) this.activeInputBaseId = "";
    }

    /** アクティブな入力欄の節の ID。無ければ空。 */
    public String activeInputBaseId() {
        return activeInputBaseId;
    }

    /** 押下中の節の ID。押下中の節は {@link InteractionState} が唯一の持ち主。 */
    public String activePressedId() {
        HtmlNode active = interactionState.activeNode();
        return active != null ? active.key() : "";
    }

    /** フォーカス中の節の ID。フォーカス中の節は {@link InteractionState} が唯一の持ち主。 */
    public String focusedId() {
        HtmlNode focused = interactionState.focusedNode();
        return focused != null ? focused.key() : "";
    }

    public TextSelection textSelection() {
        return textSelection;
    }

    public InputSessionState inputState() {
        return inputState;
    }

    public AnimationStates cssAnimations() {
        return cssAnimations;
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

    // ------------------------------------------------------------------
    // 文書・入力・描画の操作（S3: セッション ID の代わりにこのオブジェクトへ）
    // ------------------------------------------------------------------

    /**
     * 描画要素のキャッシュ（絶対座標）。必要なら先に環境変数と描画キューを更新する。
     * 親相対の表示リストは、絶対座標を前提にする消費者（MOD 側）のために、公開のたびに 1 回だけ直して持つ。
     */
    public List<DisplayItem> elements() {
        beforeElements();
        return absolute(renderCache);
    }

    /**
     * 描画要素のキャッシュを、親相対のまま返す（{@link #elements()} の前処理つき）。親相対を読める描画経路（GPU バックエンド）用で、
     * 絶対座標へ直す費用を払わない。読めない消費者は {@link #elements()} を使う。
     */
    public List<DisplayItem> relativeElements() {
        beforeElements();
        return renderCache;
    }

    private List<DisplayItem> byIdSource;
    private Map<String, DisplayItem> byIdIndex;

    private synchronized Map<String, DisplayItem> itemsById() {
        List<DisplayItem> src = renderCache;
        if (src != byIdSource) {
            Map<String, DisplayItem> m = new java.util.HashMap<>(src.size() * 2);
            for (DisplayItem it : src) m.put(it.id(), it);
            byIdIndex = m;
            byIdSource = src;
        }
        return byIdIndex;
    }

    /** 親相対の項目 {@code item}（{@link #relativeElements()} の要素）の絶対位置を解いた項目。公開ごとの索引を使うので、全体を直さずに済む。 */
    public DisplayItem toAbsolute(DisplayItem item) {
        if (item == null) return null;
        return DisplayItem.resolveAbsolute(itemsById(), item);
    }

    /** 描画 ID が {@code id} の NODE 項目（絶対位置）。無ければ null。 */
    public DisplayItem nodeItemAbsolute(String id) {
        DisplayItem it = itemsById().get(id);
        return it != null && it.type() == ElementType.NODE ? DisplayItem.resolveAbsolute(itemsById(), it) : null;
    }

    /** {@link #elements()} と同じ絶対座標のリストを、前処理なしで返す。 */
    public List<DisplayItem> absoluteRenderCache() {
        return absolute(renderCache);
    }

    private List<DisplayItem> absoluteSource, absoluteResult;

    private synchronized List<DisplayItem> absolute(List<DisplayItem> source) {
        if (source != absoluteSource) {
            absoluteResult = com.nishiyu.enderframelib.core.paint.AbsoluteItems.of(source);
            absoluteSource = source;
        }
        return absoluteResult;
    }

    /** 状態変化に伴う部分木の再スタイル（必要に応じて再レイアウトまたは再描画へ分岐）を要求する。 */
    public void requestRestyle() {
        markInvalidation(InvalidationLevel.RESTYLE);
        invalidated();
    }

    /** スクロールなど、寸法を変えずに位置だけが変わる更新を要求する（measure を省いて layout だけやり直す）。 */
    public void requestReposition() {
        nonScrollRepositionPending = true; // スクロールだけとは限らない: 表示リストは作り直す
        requestScrollReposition();
    }

    /**
     * スクロール量だけが変わった更新を要求する。表示リストは作り直さず、スクロールコンテナの中身の節を動かして済ませる
     * （範囲を越える・ほかに変化がある・中身を節にまとめていないコンテナが動くときは、作り直す）。
     */
    public void requestScrollReposition() {
        interactionState.changeLedger().append(null, com.nishiyu.enderframelib.core.dom.node.ChangeKind.SCROLL);
        repositionPending = true;
        markInvalidation(InvalidationLevel.REPOSITION);
        invalidated();
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
        interactionState.changeLedger().append(null, com.nishiyu.enderframelib.core.dom.node.ChangeKind.ENV);
        invalidated();
    }

    /** DOM 変更やビューポート変更に伴う再レイアウトを要求する。 */
    public void requestRender() {
        markInvalidation(InvalidationLevel.RESTYLE); // DOM の変更は台帳から読む（RELAYOUT は全体の作り直し）
        invalidated();
    }

    /** 同期要求を送る間隔を空ける。送るべきなら true。 */
    public boolean shouldRequestSync() {
        long now = clock.millis();
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
    public void activateInput(String renderId, String baseId) {
        setActiveInputId(renderId);
        baseId = baseId != null ? baseId : "";
        this.activeInputBaseId = baseId;
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
        return baseId.equals(activeInputBaseId);
    }

    public boolean isPressedBase(String baseId) {
        String pressed = activePressedId();
        return !pressed.isEmpty() && pressed.equals(baseId);
    }

    public void setActivePressedId(String id) {
        HtmlNode node = resolveInteractiveNode(id);
        if (node != null && interactionState.isInert(node)) node = null;
        if (interactionState.setActiveNode(node)) {
            requestRestyle();
        }
    }

    /** ホバー中（:hover）の要素の基底ノード ID を更新し、変化があれば再スタイルを要求する。 */
    public void setHoveredElement(String baseId) {
        HtmlNode node = resolveInteractiveNode(baseId);
        if (node != null && interactionState.isInert(node)) node = null;
        if (interactionState.setHoveredNode(node)) {
            requestRestyle();
        }
    }

    /** フォーカス中（:focus / :focus-visible / :focus-within）の要素を更新し、変化があれば再スタイルを要求する。 */
    public void setFocusedElement(String baseId, boolean focusVisible) {
        HtmlNode targetNode = resolveInteractiveNode(baseId);
        if (targetNode != null && interactionState.isInert(targetNode)) return;
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

    /**
     * 作り直した表示リストを公開する。前の表示リストのスクロールの移動量は新しい表示リストには当てはまらない
     * （新しい表示リストは今のスクロール量で作ってある）ので外し、新しい表示リストのスクロールコンテナを覚え直す。
     */
    private void setScrollBase(List<ScrollContentInfo> base) {
        this.scrollBase = base;
        handledScrollNodes.clear();
        for (int i = 0, n = base.size(); i < n; i++) handledScrollNodes.add(base.get(i).node());
    }

    void publishBuilt(List<DisplayItem> elements) {
        List<ScrollContentInfo> oldScrollBase = scrollBase;
        for (int i = 0, n = oldScrollBase.size(); i < n; i++) {
            ScrollContentInfo info = oldScrollBase.get(i);
            paintOffsets.clear(info.contentId());
            if (info.thumbVId() != null) paintOffsets.clear(info.thumbVId());
            if (info.thumbHId() != null) paintOffsets.clear(info.thumbHId());
            for (int k = 0, sn = info.stickies().size(); k < sn; k++) paintOffsets.clear(info.stickies().get(k).nodeId());
        }
        setRenderCache(elements);
        setScrollBase(builtScrollContents()); // 描画は常にスクロールコンテナの中身を節にまとめる
        scrollAtBuild = scrollState.snapshot();
        scrollWindowAt = scrollAtBuild;
        tryScrollTranslate(); // 描画の記憶は使い回した中身の節を記憶したときのスクロール量で出す。今との差を節の移動で足す
    }

    /** 直近に作った表示リストの、中身を 1 つの節にまとめたスクロールコンテナ。既定の描画（{@code DisplayListBuilder}）が持つ。 */
    protected List<ScrollContentInfo> builtScrollContents() {
        return v3.scrollContents();
    }

    /**
     * スクロール量だけが変わったので、表示リストは作らずに、スクロールコンテナの中身の節とつまみの節を動かす。
     * 動かせるのは、全てのコンテナの移動が範囲内のとき。動かせなければ false（作り直す）。
     */
    private boolean tryScrollTranslate() {
        if (scrollBase.isEmpty()) return false;
        // 節にまとめていないコンテナ（sticky などを含む）のスクロール量が変わっていたら、その中身は作り直さないと動かない
        if (scrollState.hasUncheckedChange(scrollAtBuild, handledScrollNodes)) return false;
        if (!withinWindow()) return false;
        for (ScrollContentInfo info : scrollBase) {
            float dx = scrollState.getScrollX(info.node()) - info.sx0();
            float dy = scrollState.getScrollY(info.node()) - info.sy0();
            paintOffsets.setIfActive(info.contentId(), -dx, -dy);
            // つまみは作り直したときと同じ整数の位置へ（小数のまま動かすと角の縁の画素が作り直しと違う。A6b ⑤）
            if (info.thumbVId() != null) paintOffsets.setIfActive(info.thumbVId(), 0f, Math.round(info.fy0() + info.ky() * dy));
            if (info.thumbHId() != null) paintOffsets.setIfActive(info.thumbHId(), Math.round(info.fx0() + info.kx() * dx), 0f);
            // 中身の中の sticky の箱: 中身と一緒に動いた分に、貼り付きのずれの増減を足す（作り直したときの式と同じ。B12）
            for (int k = 0, sn = info.stickies().size(); k < sn; k++) {
                var st = info.stickies().get(k);
                paintOffsets.setIfActive(st.nodeId(), 0f, st.extraAt(dy));
            }
        }
        if (boxLayoutQuery != null) boxLayoutQuery.invalidateDynamic();
        return true;
    }


    /** どのスクロールコンテナも、表示リストの窓を作った位置から余白の内に居るか（居なければ、窓を作り直さないと中身が足りない）。 */
    private boolean withinWindow() {
        for (ScrollContentInfo info : scrollBase) {
            float[] w = scrollWindowAt.get(info.node());
            float wx = w != null ? w[0] : 0f, wy = w != null ? w[1] : 0f;
            if (Math.abs(scrollState.getScrollX(info.node()) - wx) > SCROLL_TRANSLATE_LIMIT
                    || Math.abs(scrollState.getScrollY(info.node()) - wy) > SCROLL_TRANSLATE_LIMIT) return false;
        }
        return true;
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
        long sinceLastLayout = clock.nanos() - lastLayoutFinishedNanos;
        if (v3.hasScene() && sinceLastLayout < lastLayoutCostNanos * RESIZE_THROTTLE_FACTOR) {
            invalidated();
            return false;
        }
        return true;
    }

    /** 何も無効化されていなかったために早期に返したフレームの数（診断・テスト用）。 */
    static final java.util.concurrent.atomic.AtomicLong IDLE_FRAMES = new java.util.concurrent.atomic.AtomicLong();

    /**
     * 静止フレームか。リサイズ・無効化・凍結した台帳の変化・位置の決め直し・汚れた節のどれも無く、箱の木もある（初回の構築では
     * ない）。このとき以降の処理はすべて何もしないので、省いても結果は変わらない。
     */
    private boolean isIdleFrame(boolean resize, InvalidationLevel level, com.nishiyu.enderframelib.core.dom.node.ChangeLedger ledger) {
        return !(resize || level != InvalidationLevel.NONE || !v3.hasScene()
                || repositionPending || nonScrollRepositionPending || interactionState.hasDirtyNodes()
                || ledger.frozenOverflow() || ledger.frozenSize() > 0);
    }

    /** 凍結した台帳がスクロール量の変化だけか（中身の節を動かして済ませられる候補）。 */
    private static boolean onlyScroll(com.nishiyu.enderframelib.core.dom.node.ChangeLedger ledger) {
        if (ledger.frozenOverflow()) return false;
        for (int i = 0, n = ledger.frozenSize(); i < n; i++) {
            if (!ledger.frozenIs(i, com.nishiyu.enderframelib.core.dom.node.ChangeKind.SCROLL)) return false;
        }
        return true;
    }

    /** 描画キューから呼ばれる、このセッション 1 回分の無効化処理。 */
    public void flushInvalidation() {
        if (document == null || document.root == null) return;
        if (document.adoptedRulesChanged()) { // Java から規則が採用された（HtmlDocument#adoptStyleRules）: シートを作り直して全体を解き直す
            document.rebuildSheet(rootW, rootH, devicePixelRatio);
            interactionState.markAllDirty();
            markInvalidation(InvalidationLevel.RELAYOUT);
        }
        // 時間で動くスタイル（opacity / transform / 色以外のトランジションと @keyframes）を進める。
        // 動いているうちは毎フレーム再スタイルを要求する（終わった要素は進めた時点で再スタイルの対象になる）
        if (interactionState.advanceAnimations(nowMs())) requestRestyle();
        var ledger = interactionState.changeLedger();
        v3.beforeFreeze(interactionState, document.sheet);
        ledger.freeze(); // 変更台帳を凍結（時間で動くスタイルの印も、この世代に入るよう進めたあとで）。変化は全て台帳から読む

        boolean resize = resizeDue();
        InvalidationLevel level = consumeInvalidationLevel();
        if (isIdleFrame(resize, level, ledger)) {
            IDLE_FRAMES.incrementAndGet();
            return;
        }
        scene3dSync.sync(document.root); // DOM（属性・束縛）が変わったかもしれないフレームだけ、3D の記述を世界へ写す
        // 入口は台帳 1 本。スクロール量だけの変化は中身の節を動かし、それ以外は増分で組み直す
        boolean scrollOnly = !nonScrollRepositionPending && level == InvalidationLevel.REPOSITION && !interactionState.hasDirtyNodes();
        repositionPending = nonScrollRepositionPending = false;
        if (level == InvalidationLevel.RELAYOUT) v3.rebuildKeepingHistory(); // 全体の作り直しの要求: 前回の木と記憶を捨てる（台帳に載らない書体の変化など）
        if (resize) recomputeLayout(pendingResizeW, pendingResizeH);
        else if (scrollOnly && onlyScroll(ledger) && tryScrollTranslate()) STAT_SCROLL_TRANSLATED.increment();
        else {
            if (scrollOnly) STAT_SCROLL_FALLBACK.increment();
            v3Frame(false);
        }
    }

    /** 診断表示用: ナノ秒の差をミリ秒（小数 3 桁）で。 */
    static String ms(long fromNanos, long toNanos) {
        return String.format(Locale.ROOT, "%.3f", (toNanos - fromNanos) / 1_000_000.0);
    }

    /**
     * 書き換えても、箱の位置・大きさに効かず、その節自身の描画にだけ効く属性か。
     * 規則の入力になる場合（属性セレクタ）は、再スタイルが見た目の変化として拾う。
     * {@code progress} / {@code meter} の {@code value} {@code min} {@code max}: 高さは固定で、描画は属性だけで決まる。
     */
    private static boolean isPaintOnlyAttribute(HtmlNode node, String name) {
        if (node.nodeType != NodeType.ELEMENT || node.tag == null) return false;
        if (!com.nishiyu.enderframelib.core.dom.node.ElementTraits.isGauge(node.tag)) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals("value") || n.equals("min") || n.equals("max");
    }

    /** 書き換えても再スタイルだけで済む属性か（カスケードの入力にしかならない）。 */
    private static boolean isPresentationalAttribute(String name) {
        return "style".equalsIgnoreCase(name) || "class".equalsIgnoreCase(name) || "hidden".equalsIgnoreCase(name);
    }

    protected void syncAttributeToInteractionState(HtmlNode node, String attrName, boolean present) {
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

    public boolean isHeadlessState() {
        return headlessState;
    }

    public void setHeadlessState(boolean headlessState) {
        this.headlessState = headlessState;
    }

    /** 指定した DOM nodeId に紐づく 3D ワールドを返す（未作成なら null）。 */
    public Scene3DWorld scene3dWorld(long nodeId) {
        return scene3dWorlds.get(nodeId);
    }

    /** 指定した DOM nodeId に紐づく 3D ワールドを取得または新規作成する。 */
    public Scene3DWorld getOrCreateScene3dWorld(long nodeId) {
        return scene3dWorlds.computeIfAbsent(nodeId, Scene3DWorld::new);
    }

    /**
     * UI の画素 {@code (pixelX, pixelY)} にある {@code <scene-3d>} を通して、3D の最も近いメッシュを選ぶ（2D のヒットテストと同じ座標・同じ
     * 前後関係: 上に重なった 2D の要素があればそちらが先に当たり、3D は選ばれない）。
     * @return 当たりが無い（3D 以外・外れ）なら null。結果の {@link Scene3DPick#hit} はこの呼び出しごとの新しいオブジェクト
     */
    public Scene3DPick pickScene3d(double pixelX, double pixelY) {
        SceneHit r = hitTest(pixelX, pixelY);
        if (r == null || r.node() == null || !Scene3DTags.isScene(r.node().tag)) return null;
        long worldId = r.node().nodeId();
        Scene3DWorld world = scene3dWorlds.get(worldId);
        SceneLayer l = r.layer();
        float iw = Math.max(0f, l.w - l.bL - l.bR);
        float ih = Math.max(0f, l.h - l.bT - l.bB);
        if (world == null || iw <= 0f || ih <= 0f) return null;
        float lx = (float) (r.localX() - l.bL);
        float ly = (float) (r.localY() - l.bT);
        if (lx < 0f || lx > iw || ly < 0f || ly > ih) return null;
        Raycaster3D.Hit hit = new Raycaster3D.Hit();
        Raycaster3D rc = scene3dRaycasters.computeIfAbsent(worldId, k -> new Raycaster3D());
        synchronized (rc) {
            if (!rc.pickScreen(world, lx, ly, Math.round(iw), Math.round(ih), hit)) return null;
        }
        return new Scene3DPick(worldId, r.baseId() + "_scene", hit);
    }

    /** {@link #pickScene3d} の結果: どの {@code <scene-3d>}（世界の nodeId・表示項目の id）の、どのメッシュに当たったか。 */
    public record Scene3DPick(long worldId, String itemId, Raycaster3D.Hit hit) {
    }

    /** 要素 ID から {@code <scene-3d>} に対応する 3D ワールドを取得または作成する（要素が無ければ null）。 */
    /** 表示中の全 3D ワールドの変更回数の合計（{@link Scene3DWorld#changeCount()}）。増えていれば 3D の見た目が変わった。 */
    public long scene3dChangeSignature() {
        if (scene3dWorlds.isEmpty()) return 0L;
        long sum = 0;
        for (Scene3DWorld w : scene3dWorlds.values()) sum += w.changeCount();
        return sum;
    }

    public Scene3DWorld scene3dWorldById(String elementId) {
        HtmlNode node = findNode(elementId);
        return node == null ? null : getOrCreateScene3dWorld(node.nodeId());
    }

    protected void installDocument(HtmlNode rootNode, String authorCss, int width, int height, boolean fromServer) {
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

        Document doc = document;
        if (doc == null || !existingServerDoc) {
            doc = new Document(rootNode, null, authorCss);
            doc.rebuildSheet(width, height, devicePixelRatio);
            setDocument(doc);
        } else {
            if (!Objects.equals(doc.authorCss, authorCss) || doc.sheet == null) {
                doc.authorCss = authorCss;
                doc.rebuildSheet(width, height, devicePixelRatio);
            } else if (oldRootW != width || oldRootH != height) {
                // ビューポートだけが変わったときは CSS を読み直さず、@media の条件だけ評価し直す
                doc.sheet.updateMediaEnvironment(doc.sheet.mediaEnvironment().withSize(width, height));
            }
            ServerTreeAdoption.NodeStateSnapshot carried = ServerTreeAdoption.NodeStateSnapshot.capture(doc.root, scrollState, interactionState);
            ServerTreeAdoption.adopt(doc.root, rootNode);
            carried.restore(doc.root, scrollState, interactionState);
        }

        initializeBindings(doc.root);
        tickLocalVariables(doc.root);
        applyOverrides();
        scene3dSync.sync(doc.root); // 初回の記述を世界へ（以後は再スタイル・再レイアウトを伴うフレームで差分だけ）

        interactionState.markAllDirty();
        v3.reset();
        v3Frame(true);
    }

    /**
     * ビューポートを変更してレイアウト・描画要素を作り直す。大きさだけの変化は増分（{@link V3Frame}）、同じ大きさでの呼び出し
     * （画像の到着など台帳の外の変化）は全体を組み直す。
     */
    public void recomputeLayout(int newRootW, int newRootH) {
        int oldRootW = rootW;
        int oldRootH = rootH;
        setRootW(newRootW);
        setRootH(newRootH);
        if (pendingResizeW == newRootW && pendingResizeH == newRootH) {
            pendingResizeW = -1;
            pendingResizeH = -1;
        }

        applyOverrides();

        Document doc = document;
        if (doc != null && doc.root != null) {
            long startedAt = clock.nanos();
            // @media はシートを読み直さず、新しいビューポートで条件だけ評価し直す（解き方の差し替えで全ての要素を解き直す）
            if (doc.sheet == null) {
                doc.rebuildSheet(newRootW, newRootH, devicePixelRatio);
                interactionState.markAllDirty();
            } else if (oldRootW != newRootW || oldRootH != newRootH) {
                doc.sheet.updateMediaEnvironment(doc.sheet.mediaEnvironment().withSize(newRootW, newRootH));
            }
            v3Frame(oldRootW == newRootW && oldRootH == newRootH);
            lastLayoutFinishedNanos = clock.nanos();
            lastLayoutCostNanos = lastLayoutFinishedNanos - startedAt;
        }
    }

    /** 描画 ID または節の ID が指す表示要素（位置は絶対座標）。無ければ null。 */
    public DisplayItem elementById(String id) {
        DisplayItem raw = elementByIdRaw(id);
        return raw == null ? null : DisplayItem.resolveAbsolute(itemsById(), raw);
    }

    /** {@link #elementById} の、親相対のままの項目（ヒットテスト索引など、相対を解く側へ渡す用）。 */
    private DisplayItem elementByIdRaw(String id) {
        if (id == null) return null;
        DisplayItem fallback = null;
        List<DisplayItem> elements = renderCache();
        for (DisplayItem el : elements) {
            if (el.id().equals(id) && el.type() != ElementType.NODE) {
                return el;
            }
            if (id.equals(el.baseId())) {
                if (el.type() == ElementType.NODE || isDecorationPart(el.partSuffix())) {
                    if (fallback == null) fallback = el;
                    continue;
                }
                return el;
            }
        }
        return fallback;
    }

    /** 背景・枠線・影・プレースホルダなど、操作の相手ではなく飾りの部品か。 */
    private static boolean isDecorationPart(String suffix) {
        return suffix.equals("_ph") || suffix.startsWith("_shadow") || suffix.startsWith("_b_")
                || suffix.startsWith("_o_") || suffix.startsWith("_bgl");
    }

    public void updateNodeAttributeLocally(String elementId, String attrName, String attrValue, boolean remove) {
        String baseId = elementId;
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
        HtmlNode target = findNode(elementId);
        if (target == null) return;

        target.setInnerHTML(html);
        initializeBindings(target);
        interactionState.markDirty(target);
        requestRender();
    }

    public void updateNodeStyleLocally(String elementId, String prop, String val) {
        HtmlNode target = findNode(elementId);
        if (target == null) return;

        target.setStyleProperty(prop, val);
        interactionState.markDirty(target);
        // インライン style だけの変化なので再スタイルから始める（left / top だけなら位置の決め直しで済む）
        requestRestyle();
    }

    /** 点の下にあるスクロール可能なコンテナを手前から順に返す。 */
    public List<SceneHit> scrollContainersAt(double pixelX, double pixelY) {
        return SceneHitTest.scrollContainersAt(scene(), hitTestContext(), pixelX, pixelY);
    }

    public boolean handleScrollLocally(double pixelX, double pixelY, double scrollX, double scrollY) {
        float sens = EnderFrameBuiltInConfig.scrollSensitivity;

        // 手前のスクロールする箱から。祖先の変換（回転・拡縮・平行移動）の逆行列とクリップを通した点で当たりを取る
        List<SceneHit> hits = scrollContainersAt(pixelX, pixelY);
        for (int i = 0, hn = hits.size(); i < hn; i++) {
            SceneHit h = hits.get(i);
            float maxW = h.layer().scrollWidth, maxH = h.layer().scrollHeight;

            ScrollState state = scrollState;
            HtmlNode scrollNode = h.node() != null ? h.node() : findNode(h.baseId());
            if (scrollNode == null || interactionState.isInert(scrollNode)) continue;
            float curX = state.getScrollX(scrollNode);
            float curY = state.getScrollY(scrollNode);

            float limitX = Math.max(0, maxW - h.width()), limitY = Math.max(0, maxH - h.height());
            if (limitX > 0 || limitY > 0) {
                float nextX = Math.clamp(curX - (float) scrollX * sens, 0, limitX);
                float nextY = Math.clamp(curY - (float) scrollY * sens, 0, limitY);

                if (Math.abs(nextX - curX) > 0.1f || Math.abs(nextY - curY) > 0.1f) {
                    state.setScroll(scrollNode, nextX, nextY);
                    requestScrollReposition();
                    return true;
                }
            }
        }
        return false;
    }

    public boolean handleScrollbarClick(SceneHit hit, double pixelX, double pixelY) {
        if (hit == null || !hit.isScrollbar()) return false;
        String part = hit.scrollbarPart();
        boolean isVTrack = SB_V_TRACK.equals(part), isHTrack = SB_H_TRACK.equals(part);
        return (isVTrack || isHTrack) && handleScrollbarDrag(hit.baseId(), part, pixelX, pixelY);
    }

    /** スクロールバーの部品の描画 ID 接尾辞（持ち主の節の ID に続く部分）。 */
    public static final String SB_V_TRACK = "_sb_vt";
    public static final String SB_H_TRACK = "_sb_ht";
    public static final String SB_V_THUMB = "_sb_v";
    public static final String SB_H_THUMB = "_sb_h";

    /** @param nodeId スクロールする節の ID  @param part つかんでいる部品の接尾辞（{@code SB_*}） */
    public boolean handleScrollbarDrag(String nodeId, String part, double pixelX, double pixelY) {
        if (nodeId == null || nodeId.isEmpty() || part == null) return false;
        boolean vertical = part.equals(SB_V_THUMB) || part.equals(SB_V_TRACK);
        if (!vertical && !part.equals(SB_H_THUMB) && !part.equals(SB_H_TRACK)) return false;
        // 軌道上の点につまみの中心が来るようにスクロールする
        HtmlNode scrollNode = findNode(nodeId);
        SceneLocation box = scrollNode == null ? null : locate(scrollNode);
        if (box == null) return false;
        if (vertical) {
            float trackY = box.y() + box.bT() + 2, trackH = box.h() - box.bT() - box.bB() - 4;
            float thumbH = Math.max(12, trackH * (box.h() / (float) Math.max(1, box.scrollHeight())));
            float ratio = Math.clamp((float) (pixelY - trackY - thumbH / 2.0) / (float) Math.max(1, trackH - thumbH), 0f, 1f);
            scrollState.setScroll(scrollNode, scrollState.getScrollX(scrollNode), ratio * Math.max(0, box.scrollHeight() - box.h()));
        } else {
            float trackX = box.x() + box.bL() + 2, trackW = box.w() - box.bL() - box.bR() - 4;
            float thumbW = Math.max(12, trackW * (box.w() / (float) Math.max(1, box.scrollWidth())));
            float ratio = Math.clamp((float) (pixelX - trackX - thumbW / 2.0) / (float) Math.max(1, trackW - thumbW), 0f, 1f);
            scrollState.setScroll(scrollNode, ratio * Math.max(0, box.scrollWidth() - box.w()), scrollState.getScrollY(scrollNode));
        }
        requestScrollReposition();
        return true;
    }

    public SceneHit hitTest(double pixelX, double pixelY) {
        return SceneHitTest.hitTest(scene(), hitTestContext(), pixelX, pixelY);
    }

    /** {@link #hitTest} の別名（シーン当たり判定は最初から絶対座標を返す）。 */
    public SceneHit hitTestAbsolute(double pixelX, double pixelY) {
        return hitTest(pixelX, pixelY);
    }

    public SceneHit localCoords(String elementId, double pixelX, double pixelY) {
        HtmlNode target = findNode(elementId);
        if (target == null) return null;
        return SceneHitTest.localCoords(scene(), hitTestContext(), target, pixelX, pixelY);
    }

    public void updateElementTextLocally(String elementId, String newText) {
        String safeText = newText != null ? newText : "";
        String baseId = elementId;

        HtmlNode target = findNode(baseId);
        boolean isRange = target != null && "range".equalsIgnoreCase(target.attrs.getOrDefault("type", ""));
        boolean isSelect = target != null && com.nishiyu.enderframelib.core.dom.node.ElementTraits.isSelect(target.tag);

        String domValue = safeText;
        if (isRange && safeText.contains(":")) {
            domValue = safeText.split(":")[0];
        }

        if (target != null && !Objects.equals(target.attrs.get("value"), domValue)) {
            DomUtils.updateAttribute(target, "value", domValue, false);
        }
        // 書き戻し先の不変リスト（平行移動は外部の PaintOffsets にある）
        List<DisplayItem> elements = renderCache;
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


    @Override
    public void close() {
        renderCache = Collections.emptyList();
        paintOffsets.clearAll();
        userPaintOffsets.clearAll();
        if (document != null && document.root instanceof HtmlDocument liveDocument) {
            liveDocument.setDomChangeListener(null);
        }
        document = null;
        v3.reset();
        env.clear();
        interactionState.clear();
        invalidationLevel = InvalidationLevel.NONE;
        repositionPending = false;
        pendingResizeW = -1;
        pendingResizeH = -1;
        activeInputId = "";
        activeInputBaseId = "";
        textSelection.clear();

        inputState.clear();
        cssAnimations.clear();
        inputScrollOffsets.clear();
        textareaCursorIndex.clear();
        textareaScrollRow.clear();

        envLastUpdate = 0L;
        envUsedKeys = null;
        envApplied = null;

        subscriptions.clear();
        dataBindings.clear();
        virtualListBindings.clear();
        timers.clear();
        scene3dWorlds.clear();
        scene3dRaycasters.clear();
        scene3dSync = new Scene3DDomSync(this::getOrCreateScene3dWorld);
        onClose();
    }

    /**
     * 1 セッション分の入力状態。ホバー・押下・フォーカスのノードは {@link com.nishiyu.enderframelib.core.dom.InteractionState} が持つので、
     * ここには描画・編集の境界でしか使わない文字列 ID と、ジェスチャの途中経過だけを置く。
     */
    public static final class InputSessionState {
        public String draggingId = "";
        /** スクロールバーの部品をつかんでいる間の接尾辞。つかんでいなければ空。 */
        public String draggingScrollbarPart = "";
        public float lastRatio = -1.0f;
        public String lastRangeText = "";
        public double lastSentX = -1, lastSentY = -1;
        public long lastEventTime = 0;
        public double hoverX = -1, hoverY = -1;
        public int pressedButton = 0;
        /** 押下時にヒットした描画層の結果。click のアクション名を GUI へ返すときに使う。 */
        public SceneHit pressedHit = null;
        public String lastSubmittedId = "";
        public String lastSubmittedText = "";
        public long lastSubmitTime = 0;
        public final Map<String, String> localInputCache = new ConcurrentHashMap<>();
        public final Map<String, String> lastCommittedValues = new ConcurrentHashMap<>();

        /** ダブルクリック判定（旧 ClientDomEventDispatcher.ClickState）。 */
        public HtmlNode lastClickNode = null;
        public long lastClickTime = 0;

        public void clear() {
            draggingId = ""; draggingScrollbarPart = "";
            lastRatio = -1.0f; lastRangeText = "";
            lastSentX = -1; lastSentY = -1; lastEventTime = 0;
            hoverX = -1; hoverY = -1;
            pressedButton = 0; pressedHit = null;
            lastSubmittedId = ""; lastSubmittedText = ""; lastSubmitTime = 0;
            localInputCache.clear(); lastCommittedValues.clear();
            lastClickNode = null; lastClickTime = 0;
        }
    }
}
