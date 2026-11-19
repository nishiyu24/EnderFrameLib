package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.Scene3DDomSync;
import com.nishiyu.enderframelib.core.scene3d.Raycaster3D;
import com.nishiyu.enderframelib.core.scene3d.Scene3DTags;
import com.nishiyu.enderframelib.core.scene3d.Scene3DWorld;

import com.nishiyu.enderframelib.core.paint.CssAnimator;
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
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.Ownership;
import com.nishiyu.enderframelib.core.css.CounterStyles;
import com.nishiyu.enderframelib.core.layout.BoxTreeBuilder;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
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
import com.nishiyu.enderframelib.common.RenderPipeline;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontStyle;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
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
    /** LocalPatches（切り出した協力クラス）。 */
    private final LocalPatches localPatches = new LocalPatches(this);

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
        /**
         * 既存の Text 節の文字だけが変わった（{@link HtmlNode#updateTextContent} / {@code Text.data} など）。箱の木を
         * 作り直さず、その文字の箱だけ作り直して測り直す。条件に合わなければ {@link #RELAYOUT} と同じ処理になる。
         */
        TEXT_PATCH,
        /**
         * 要素の子が増減した（{@code appendChild} / {@code removeChild} など）。箱の木を作り直さず、その親の子の並びだけを直す。
         * 条件に合わなければ {@link #RELAYOUT} と同じ処理になる。
         */
        CHILD_PATCH,
        RESTYLE,
        RELAYOUT
    }

    /** TEXT_PATCH の対象にする Text 節の最大数（これを超えたら全体の再レイアウトの方が安い）。 */
    private static final int TEXT_PATCH_MAX_NODES = 256;
    /** 次の flush で局所パッチする Text 節（同一性で比べる）。{@code synchronized (pendingTextNodes)} で触る。 */
    final Set<HtmlNode> pendingTextNodes = Collections.newSetFromMap(new IdentityHashMap<>());

    /** 次の flush で「描画だけ」の属性パッチをする節（同一性で比べる）。{@code synchronized (pendingAttrNodes)} で触る。 */
    private final Set<HtmlNode> pendingAttrNodes = Collections.newSetFromMap(new IdentityHashMap<>());
    public static final java.util.concurrent.atomic.AtomicLong ATTR_PATCH_HITS = new java.util.concurrent.atomic.AtomicLong();
    /** 局所パッチが成功した回数 / 全体の再レイアウトへ退いた回数（調べる用）。 */
    public static final java.util.concurrent.atomic.AtomicLong TEXT_PATCH_HITS = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong TEXT_PATCH_FALLBACKS = new java.util.concurrent.atomic.AtomicLong();

    /**
     * 連続したリサイズ中は、直前のレイアウトにかかった時間のこの倍数だけ次のレイアウトを待つ。
     * 1 フレームの大半をレイアウトに取られないよう、待つ間は古いレイアウトのまま描く（大きさが落ち着けば必ず追いつく）。
     */
    private static final long RESIZE_THROTTLE_FACTOR = 2;

    private volatile String sessionId;
    private volatile List<DisplayItem> renderCache = Collections.emptyList();
    /** レイアウトから作った描画要素（描画上の平行移動 {@link #paintOffsets} を掛ける前）。 */
    private volatile List<DisplayItem> baseRenderCache = Collections.emptyList();
    private final Map<String, float[]> paintOffsets = new ConcurrentHashMap<>();
    private volatile boolean paintOffsetsDirty = false;
    Document document;
    private final Map<Long, Scene3DWorld> scene3dWorlds = new ConcurrentHashMap<>();
    /** ワールドごとのレイキャスト（BVH を持つので使い回す）。 */
    private final Map<Long, Raycaster3D> scene3dRaycasters = new ConcurrentHashMap<>();
    /** {@code <scene-3d>} の子要素（{@code <mesh-3d>} など）の属性を 3D ワールドへ写す（再スタイル・再レイアウトを伴うフレームで走る）。 */
    private Scene3DDomSync scene3dSync = new Scene3DDomSync(this::getOrCreateScene3dWorld);
    LayoutBox layoutRoot;
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
            STAT_SCROLL_FALLBACK = new java.util.concurrent.atomic.LongAdder(), STAT_REPOSITION_OTHER = new java.util.concurrent.atomic.LongAdder();
    /** 節の移動で済ませる範囲（px）。表示リストは画面の外 256px まで作ってあるので、それより内に収める。 */
    /** 診断: 箱の位置を決め直したうえで節の移動で済ませた回数（箱は今のスクロール位置、表示リストは作ったときの位置になる）。 */
    public static final java.util.concurrent.atomic.LongAdder STAT_TRANSLATE_AFTER_FRESH = new java.util.concurrent.atomic.LongAdder();
    private static final float SCROLL_TRANSLATE_LIMIT = DisplayListBuilder.CULL_MARGIN - 64f;
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

    private String activeInputId = "";
    /** {@link #activeInputId} の持ち主の節の ID。描画 ID の綴りから逆算せず、入力欄を開いた時点の項目から取る。 */
    private String activeInputBaseId = "";
    private final TextSelection textSelection = new TextSelection();
    private volatile HitTestIndex hitTestIndex;
    /** {@link #hitTestIndex} を作った描画要素リスト（同じ物を指す間だけ使い回す）。 */
    private volatile List<DisplayItem> hitTestIndexSource;

    // --- セッションに属する入力・描画・バインディング状態（S1） ---
    private final InputSessionState inputState = new InputSessionState();
    private final Map<String, CssAnimator.AnimState> cssAnimations = new ConcurrentHashMap<>();
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
        return System.currentTimeMillis();
    }

    /**
     * 箱の木から表示リストを作る。{@code scrollOnly} は位置だけが変わった作り直し
     * （画面の外の箱は項目を作らなくてよい）。既定は {@link com.nishiyu.enderframelib.core.EngineContext#boxRenderer}。
     */
    protected List<DisplayItem> paintBoxes(LayoutBox root, int width, int height, boolean scrollOnly) {
        var renderer = com.nishiyu.enderframelib.core.EngineContext.current().host().boxRenderer();
        if (renderer == null) return null;
        var keyframes = document.sheet.keyframes;
        if (renderer != com.nishiyu.enderframelib.core.paint.ILayoutBoxRenderer.DEFAULT) return renderer.render(root, width, height, keyframes, scrollState);
        // 既定の描画は、ビルダーをこのビューで持ち回す: 前回と入力が同じ箱は、項目を作り直さず前回のものを使う
        var builder = paintBuilder;
        if (builder != null && (paintBuilderW != width || paintBuilderH != height)
                && paintBuilderKeyframes == keyframes && paintBuilderScroll == scrollState) {
            // リサイズ: ビルダーを作り直さず、箱ごとの記憶を残す（ビューポートに依らない箱は項目を使い回せる）
            builder.setViewport(width, height);
            paintBuilderW = width;
            paintBuilderH = height;
        }
        if (builder == null || paintBuilderKeyframes != keyframes || paintBuilderScroll != scrollState) {
            builder = paintBuilder = new com.nishiyu.enderframelib.core.paint.DisplayListBuilder(width, height, keyframes, scrollState);
            paintBuilderW = width;
            paintBuilderH = height;
            paintBuilderKeyframes = keyframes;
            paintBuilderScroll = scrollState;
        } else if (paintBuilderW != width || paintBuilderH != height) {
            builder.setViewport(width, height);
            paintBuilderW = width;
            paintBuilderH = height;
        }
        builder.setUseChangeStamps(useChangeStamps);
        builder.setExactTranslation(exactTranslation);
        try {
            com.nishiyu.enderframelib.core.paint.ImmutableItemList prev =
                    this.baseRenderCache instanceof com.nishiyu.enderframelib.core.paint.ImmutableItemList il ? il : null;
            return builder.renderImmutable(root, scrollOnly, prev);
        } finally {
            builder.setUseChangeStamps(false);
            builder.setExactTranslation(false);
        }
    }

    /** 直近に表示リストを構築したビルダー。スライスパッチ判定やスクロール節情報参照に使用。 */
    public final com.nishiyu.enderframelib.core.paint.DisplayListBuilder paintBuilder() {
        return paintBuilder;
    }

    /** 次の {@link #paintBoxes} だけ、箱の変更印で署名の比較を省く（変わった箱が分かっている文字の局所パッチ）。 */
    boolean useChangeStamps;
    /** 次の {@link #paintBoxes} だけ、整数ぶん動いた箱の項目を厳密に平行移動して使い回す（子の増減の局所パッチ）。 */
    boolean exactTranslation;

    protected final boolean exactTranslation() {
        return exactTranslation;
    }

    /** 今の {@link #paintBoxes} が変更印を使ってよいか（文字の局所パッチの描画のとき）。 */
    protected final boolean useChangeStamps() {
        return useChangeStamps;
    }

    com.nishiyu.enderframelib.core.paint.DisplayListBuilder paintBuilder;
    private int paintBuilderW, paintBuilderH;
    private Object paintBuilderKeyframes, paintBuilderScroll;

    public List<DisplayItem> renderCache() {
        applyPendingPaintOffsets();
        return renderCache;
    }

    public void setRenderCache(List<DisplayItem> renderCache) {
        if (renderCache == null || renderCache.isEmpty()) {
            this.baseRenderCache = Collections.emptyList();
        } else if (renderCache instanceof ImmutableItemList) {
            this.baseRenderCache = renderCache;
        } else {
            ImmutableItemList prev = this.baseRenderCache instanceof ImmutableItemList il ? il : null;
            this.baseRenderCache = ImmutableItemList.from(prev, renderCache);
        }
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
        float[] cur = paintOffsets.get(elementId);
        if (cur != null) {
            if (cur[0] != dx || cur[1] != dy) {
                cur[0] = dx;
                cur[1] = dy;
                paintOffsetsDirty = true;
            }
            return;
        }
        paintOffsets.put(elementId, new float[]{dx, dy});
        paintKeysVersion.incrementAndGet();
        paintOffsetsDirty = true;
    }

    public void clearPaintOffset(String elementId) {
        if (elementId != null && paintOffsets.remove(elementId) != null) { paintKeysVersion.incrementAndGet(); paintOffsetsDirty = true; }
    }

    private void applyPendingPaintOffsets() {
        if (paintOffsetsDirty) publishRenderCache();
    }

    /** いま移動量（{@link #paintOffsets}）を書いてある項目（id → 項目）。外れたときに 0 へ戻す。 */
    private final Map<String, DisplayItem> offsetApplied = new java.util.HashMap<>();

    private void publishRenderCache() {
        paintOffsetsDirty = false;
        List<DisplayItem> base = this.baseRenderCache;
        if (base.isEmpty() || (paintOffsets.isEmpty() && offsetApplied.isEmpty())) {
            this.renderCache = base;
            return;
        }
        // 描画の上だけの移動量は、項目を作り直さず項目のセルへ書く（B9 の M4）。動く節は 1〜数個で、書き換えた位置だけを
        // 新しいスナップショットに載せる（消費者は通し番号とブロックで変化を知る）。レイアウトで項目が作り直されても、
        // 発行のたびに載せ直す（新しい項目には移動量が無い）
        ImmutableItemList list = base instanceof ImmutableItemList il ? il : ImmutableItemList.from(null, base);
        Map<String, Integer> byId = baseIndexById(list);
        int count = 0;
        // 外れた項目は 0 へ戻す（まだリストにある項目だけが位置を持つ）
        for (java.util.Iterator<Map.Entry<String, DisplayItem>> it = offsetApplied.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, DisplayItem> e = it.next();
            if (paintOffsets.containsKey(e.getKey())) continue;
            it.remove();
            if (e.getValue().setPaintOffset(0f, 0f) && byId.containsKey(e.getKey())) count++;
        }
        for (Map.Entry<String, float[]> e : paintOffsets.entrySet()) {
            Integer idxBox = byId.get(e.getKey());
            if (idxBox == null) continue;
            DisplayItem el = list.get(idxBox);
            if (el.type() != ElementType.NODE) continue;
            float[] off = e.getValue();
            offsetApplied.put(e.getKey(), el);
            if (el.setPaintOffset(off[0], off[1])) count++;
        }
        if (count > 0) {
            list = list.touched();
            this.baseRenderCache = list;
        } else if (list != base) {
            this.baseRenderCache = list;
        }
        this.renderCache = list;
        // ヒットテストの索引は引かれたときに、同じ基底の上の差し替えだけが変わったなら作り直さず patch する（hitTestIndex()）
    }

    /**
     * 描画の上だけで動きうる節の id: いま移動量を持つ節に加えて、スクロールコンテナの中身・つまみの節
     * （基底を作った時点で分かっている。まだ動いていなくても、次のスクロールで動くので最初から動きうる節として扱う）。
     */
    private java.util.Set<String> dynamicNodeIds() {
        // 移動量を持つ節の集合（値でなく鍵）と scrollBase が変わらない限り、同じ不変の集合を返す（スクロールのたびに作らない。
        // HitTestIndex が Set.copyOf で持つので、同一インスタンスなら包含の検査も省ける）
        int version = paintKeysVersion.get();
        java.util.Set<String> cached = dynamicIdsCache;
        List<ScrollContentInfo> base = scrollBase;
        if (cached != null && dynamicIdsVersion == version && dynamicIdsBase == base) return cached;
        java.util.Set<String> ids = new java.util.HashSet<>(paintOffsets.keySet());
        for (ScrollContentInfo info : base) {
            ids.add(info.contentId());
            if (info.thumbVId() != null) ids.add(info.thumbVId());
            if (info.thumbHId() != null) ids.add(info.thumbHId());
            for (var st : info.stickies()) ids.add(st.nodeId());
        }
        cached = java.util.Set.copyOf(ids);
        dynamicIdsCache = cached;
        dynamicIdsVersion = version;
        dynamicIdsBase = base;
        return cached;
    }

    /** {@link #paintOffsets} の鍵が増減するたびに増える（値の更新では増えない）。 */
    private final java.util.concurrent.atomic.AtomicInteger paintKeysVersion = new java.util.concurrent.atomic.AtomicInteger();
    private java.util.Set<String> dynamicIdsCache;
    private int dynamicIdsVersion;
    private List<ScrollContentInfo> dynamicIdsBase;

    /** 基底リストの id → 位置。基底が変わるまで使い回す（スクロールのたびには作らない）。 */
    private Map<String, Integer> baseIndexById(List<DisplayItem> base) {
        Map<String, Integer> m = baseIndexCache;
        Object token = ItemDiff.contentTokenOf(base); // 移動量の書き込みだけで別のスナップショットになっても、位置は同じ
        if (m != null && baseIndexSource == token) return m;
        m = new java.util.HashMap<>(base.size() * 2);
        for (int i = 0; i < base.size(); i++) m.put(base.get(i).id(), i);
        baseIndexCache = m;
        baseIndexSource = token;
        return m;
    }

    private Map<String, Integer> baseIndexCache;
    private Object baseIndexSource;
    /** {@link #hitTestIndex} を作ったときの基底リスト。 */
    private volatile Object hitTestIndexBase;

    /** 画像の透過ヒットテスト（{@code <img data-hit-alpha>}）が不透明度を引く先。ホストが画像の読み方を差し込む。 */
    private volatile com.nishiyu.enderframelib.core.paint.AlphaMaskProvider alphaMaskProvider;

    public void alphaMaskProvider(com.nishiyu.enderframelib.core.paint.AlphaMaskProvider provider) {
        this.alphaMaskProvider = provider;
        HitTestIndex idx = this.hitTestIndex;
        if (idx != null) idx.alphaMaskProvider(provider);
    }

    private final java.util.function.Predicate<DisplayItem> nonInertHitFilter = el -> !isItemInert(el);

    private boolean hasInertOrModal() {
        return interactionState.hasActiveModal()
                || (rootNode() instanceof HtmlDocument doc && doc.hasInertAttributes());
    }

    private boolean isItemInert(DisplayItem el) {
        if (el == null) return false;
        String baseId = el.baseId();
        if (baseId == null || baseId.isEmpty()) return false;
        HtmlNode node = resolveInteractiveNode(baseId);
        return node != null && interactionState.isInert(node);
    }

    /** 点が対話要素の上にあるか（ゲーム側が、クリックを UI に渡すか自分で処理するかを決めるのに使う）。 */
    public boolean isPointerOverUi(double pixelX, double pixelY) {
        if (!hasInertOrModal()) return hitTestIndex().isPointerOverUi(sessionId, pixelX, pixelY);
        return hitTestIndex().isPointerOverUi(sessionId, pixelX, pixelY, nonInertHitFilter);
    }

    public HitTestIndex hitTestIndex() {
        applyPendingPaintOffsets();
        List<DisplayItem> current = this.renderCache;
        HitTestIndex idx = this.hitTestIndex;
        if (idx != null && hitTestIndexSource == current) return idx;
        // 同じ基底の上で、描画の上だけ動く節の移動量が変わっただけなら、索引は作り直さず該当の節だけを差し替える
        java.util.Set<String> dynamic = dynamicNodeIds();
        if (idx != null && hitTestIndexBase == ItemDiff.contentTokenOf(baseRenderCache) && (idx.dynamicIds() == dynamic || idx.dynamicIds().containsAll(dynamic))) {
            Map<String, Integer> byId = baseIndexById(baseRenderCache);
            List<DisplayItem> now = new ArrayList<>(idx.dynamicIds().size());
            for (String id : idx.dynamicIds()) {
                Integer at = byId.get(id);
                if (at != null) now.add(current.get(at));
            }
            if (idx.patch(now)) {
                this.hitTestIndexSource = current;
                return idx;
            }
        }
        idx = new HitTestIndex(current, dynamic);
        idx.alphaMaskProvider(alphaMaskProvider);
        idx.animationClock(interactionState::animationTimeMs); // 描いたフレームと同じ時刻の姿勢で当てる（T2）
        this.hitTestIndexSource = current;
        this.hitTestIndexBase = ItemDiff.contentTokenOf(baseRenderCache);
        return this.hitTestIndex = idx;
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
        if (boxLayoutQuery == null) {
            boxLayoutQuery = new BoxLayoutQuery(scrollState, this::requestReposition);
            boxLayoutQuery.freshener(this::ensureLayoutFresh);
        }
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
            } else if (attributeName != null && com.nishiyu.enderframelib.core.kernel.Shortcut.ATTR_PATCH.on() && isPaintOnlyAttribute(node, attributeName)) {
                synchronized (pendingAttrNodes) {
                    pendingAttrNodes.add(node);
                }
                requestRestyle();
            } else if (attributeName == null && com.nishiyu.enderframelib.core.kernel.Shortcut.TEXT_PATCH.on() && node.nodeType == NodeType.TEXT) {
                requestTextPatch(node);
            } else if (attributeName == null && com.nishiyu.enderframelib.core.kernel.Shortcut.CHILD_PATCH.on() && node.nodeType == NodeType.ELEMENT) {
                localPatches.requestChildPatch(node);
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

    public LayoutBox layoutRoot() {
        ensureLayoutFresh();
        return layoutRoot;
    }

    /**
     * スクロールだけの更新で、箱の位置の決め直しを後回しにしているか。表示リストはスクロール量の差し替えだけで動くので、
     * 箱の位置を読む側（描き直し・CSSOM・スクロールバーの操作）が現れるまで決め直さなくてよい。
     */
    boolean layoutStale;

    /** 後回しにした位置の決め直しがあれば済ませる。箱の位置を読む前に呼ぶ。 */
    void ensureLayoutFresh() {
        if (!layoutStale) return;
        layoutStale = false;
        if (layoutRoot != null) {
            RenderPipeline.repositionLayout(layoutRoot, document, env, interactionState,
                    rootW, rootH, scrollState, fontResolver());
        }
    }

    public void setLayoutRoot(LayoutBox layoutRoot) {
        this.layoutRoot = layoutRoot;
        layoutStale = false; // 渡された木は、位置が決まったもの（同じ木の再設定でも、後回しにした分は呼び出し側が済ませてから渡す）
        nodeBoxIndex = null; // 箱の木を作り直したかもしれない。索引は次に要るときに作り直す
        publishLayoutToDom();
    }

    /**
     * DOM の節ごとの箱（匿名でない）の索引。文字の局所パッチが変わった節の箱を木全体から探さずに引く。
     * 箱の木を作る経路（{@link #setLayoutRoot}）で捨てる。文字の局所パッチと、箱の木の形を変えない再レイアウトは捨てない。
     * 表の中の箱は値が null（局所パッチの対象にしない印）。
     */
    Map<HtmlNode, LayoutBox> nodeBoxIndex;

    Map<HtmlNode, LayoutBox> nodeBoxIndex() {
        Map<HtmlNode, LayoutBox> index = nodeBoxIndex;
        if (index == null) {
            index = new IdentityHashMap<>();
            indexBoxes(layoutRoot, index, false);
            nodeBoxIndex = index;
        }
        return index;
    }

    static void indexBoxes(LayoutBox box, Map<HtmlNode, LayoutBox> out, boolean inTable) {
        boolean table = inTable || LayoutBox.isTableBox(box);
        for (LayoutBox child : box.children) {
            if (child.node != null && !child.isAnonymous) out.put(child.node, table ? null : child);
            indexBoxes(child, out, table);
        }
    }

    /** CSSOM View が読む箱の木を差し替え、ResizeObserver / IntersectionObserver へ知らせる。 */
    void publishLayoutToDom() {
        if (boxLayoutQuery == null || document == null || layoutRoot == null) return;
        boxLayoutQuery.update(layoutRoot, document.sheet, rootW, rootH);
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

    public Map<String, CssAnimator.AnimState> cssAnimations() {
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
        applyPendingPaintOffsets();
        return absolute(renderCache);
    }

    /**
     * 描画要素のキャッシュを、親相対のまま返す（{@link #elements()} の前処理つき）。親相対を読める描画経路（GPU バックエンド）用で、
     * 絶対座標へ直す費用を払わない。読めない消費者は {@link #elements()} を使う。
     */
    public List<DisplayItem> relativeElements() {
        beforeElements();
        applyPendingPaintOffsets();
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
        applyPendingPaintOffsets();
        return DisplayItem.resolveAbsolute(itemsById(), item);
    }

    /** 描画 ID が {@code id} の NODE 項目（絶対位置）。無ければ null。 */
    public DisplayItem nodeItemAbsolute(String id) {
        applyPendingPaintOffsets();
        DisplayItem it = itemsById().get(id);
        return it != null && it.type() == ElementType.NODE ? DisplayItem.resolveAbsolute(itemsById(), it) : null;
    }

    /** {@link #elements()} と同じ絶対座標のリストを、前処理なしで返す。 */
    public List<DisplayItem> absoluteRenderCache() {
        applyPendingPaintOffsets();
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
        invalidated();
    }

    /** 既存の Text 節の文字が変わったことを知らせる（局所パッチの候補。条件に合わなければ flush で全体の再レイアウトにする）。 */
    private void requestTextPatch(HtmlNode textNode) {
        boolean overflow;
        synchronized (pendingTextNodes) {
            pendingTextNodes.add(textNode);
            overflow = pendingTextNodes.size() > TEXT_PATCH_MAX_NODES;
        }
        if (overflow) {
            requestRender();
            return;
        }
        markInvalidation(InvalidationLevel.TEXT_PATCH);
        invalidated();
    }

    /** DOM 変更やビューポート変更に伴う再レイアウトを要求する。 */
    public void requestRender() {
        markInvalidation(InvalidationLevel.RELAYOUT);
        invalidated();
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

    /** レイアウト結果から描画要素リストだけを作り直す。 */
    private void repaintFromLayout() {
        repaintFromLayout(false);
    }

    private void repaintFromLayout(boolean scrollOnly) {
        ensureLayoutFresh();
        if (layoutRoot == null || document == null) return;
        List<DisplayItem> elements = paintBoxes(layoutRoot, rootW, rootH, scrollOnly);
        if (elements == null) return;
        publishBuilt(elements);
        applyOverrides();
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
            clearPaintOffset(info.contentId());
            if (info.thumbVId() != null) clearPaintOffset(info.thumbVId());
            if (info.thumbHId() != null) clearPaintOffset(info.thumbHId());
            for (int k = 0, sn = info.stickies().size(); k < sn; k++) clearPaintOffset(info.stickies().get(k).nodeId());
        }
        setRenderCache(elements);
        setScrollBase(com.nishiyu.enderframelib.core.kernel.Shortcut.SCROLL_CONTENT_NODES.on() ? builtScrollContents() : List.of());
        scrollAtBuild = scrollState.snapshot();
        scrollWindowAt = scrollAtBuild;
    }

    /** 直近に作った表示リストの、中身を 1 つの節にまとめたスクロールコンテナ。既定の描画（{@link DisplayListBuilder}）が持つ。 */
    protected List<ScrollContentInfo> builtScrollContents() {
        var builder = paintBuilder;
        return builder != null && com.nishiyu.enderframelib.core.EngineContext.current().host().boxRenderer() == com.nishiyu.enderframelib.core.paint.ILayoutBoxRenderer.DEFAULT
                ? builder.scrollContents() : List.of();
    }

    /**
     * スクロール量だけが変わったので、表示リストは作らずに、スクロールコンテナの中身の節とつまみの節を動かす。
     * 動かせるのは、全てのコンテナの移動が範囲内のとき。動かせなければ false（作り直す）。
     */
    private boolean tryScrollTranslate() {
        if (!com.nishiyu.enderframelib.core.kernel.Shortcut.SCROLL_CONTENT_NODES.on() || scrollBase.isEmpty()) return false;
        // 節にまとめていないコンテナ（sticky などを含む）のスクロール量が変わっていたら、その中身は作り直さないと動かない
        if (scrollState.hasUncheckedChange(scrollAtBuild, handledScrollNodes)) return false;
        if (!withinWindow()) return false;
        for (ScrollContentInfo info : scrollBase) {
            float dx = scrollState.getScrollX(info.node()) - info.sx0();
            float dy = scrollState.getScrollY(info.node()) - info.sy0();
            setPaintOffset(info.contentId(), -dx, -dy);
            // つまみは作り直したときと同じ整数の位置へ（小数のまま動かすと角の縁の画素が作り直しと違う。A6b ⑤）
            if (info.thumbVId() != null) setPaintOffset(info.thumbVId(), 0f, Math.round(info.fy0() + info.ky() * dy));
            if (info.thumbHId() != null) setPaintOffset(info.thumbHId(), Math.round(info.fx0() + info.kx() * dx), 0f);
            // 中身の中の sticky の箱: 中身と一緒に動いた分に、貼り付きのずれの増減を足す（作り直したときの式と同じ。B12）
            for (int k = 0, sn = info.stickies().size(); k < sn; k++) {
                var st = info.stickies().get(k);
                setPaintOffset(st.nodeId(), 0f, st.extraAt(dy));
            }
        }
        return true;
    }


    /** 寸法はそのままに位置だけを決め直し、描画要素リストを作り直す（measure と箱の木の作り直しを省く）。 */
    private void repositionFromLayout(boolean scrollOnly) {
        if (layoutRoot == null || document == null) return;
        // スクロール量だけの更新で、観測者がいなければ、箱の位置の決め直しは後回しにする（箱の位置を読む側が現れたときに済ませる）
        boolean observed = document.root instanceof HtmlDocument live && live.hasLayoutObservers();
        if (scrollOnly && com.nishiyu.enderframelib.core.kernel.Shortcut.DEFER_SCROLL_LAYOUT.on() && !observed && tryScrollTranslate()) {
            layoutStale = true;
            STAT_SCROLL_TRANSLATED.increment();
            return;
        }
        if (scrollOnly && com.nishiyu.enderframelib.core.kernel.Shortcut.DEFER_SCROLL_LAYOUT.on() && !observed && !layoutRoot.subtreeHasContentAuto && rebuildWindowAtBoxFrame()) return;
        layoutStale = false; // 後回しにした決め直しも、次の 1 回で済む（ensureLayoutFresh を呼ぶと同じ決め直しが 2 回走る）
        RenderPipeline.repositionLayout(layoutRoot, document, env, interactionState,
                rootW, rootH, scrollState, fontResolver());
        // スクロール量だけが変わったなら、表示リストは作り直さない（レイアウトは上で最新にしてある）
        if (scrollOnly && tryScrollTranslate()) {
            STAT_SCROLL_TRANSLATED.increment();
            STAT_TRANSLATE_AFTER_FRESH.increment();
        } else {
            (scrollOnly ? STAT_SCROLL_FALLBACK : STAT_REPOSITION_OTHER).increment();
            repaintFromLayout(true);
        }
        if (document.root instanceof HtmlDocument live) live.notifyLayoutObservers();
    }

    /**
     * スクロールと同じ更新で見た目だけが変わった（スクロールで :hover の対象が替わった）とき、箱の位置は決め直さずに、
     * 変わった箱のスライスだけを差し替え、スクロール量は節の移動で済ませる。できなければ false（何も変えていない）。
     */
    private boolean patchThenTranslate() {
        if (!com.nishiyu.enderframelib.core.kernel.Shortcut.DEFER_SCROLL_LAYOUT.on() || paintBuilder == null || !com.nishiyu.enderframelib.core.kernel.Shortcut.SCROLL_CONTENT_NODES.on() || scrollBase.isEmpty()) return false;
        if (document == null || (document.root instanceof HtmlDocument live && live.hasLayoutObservers())) return false;
        if (!canTranslateAll()) return false;
        List<LayoutBox> changed = RenderPipeline.styleChangedBoxes();
        if (changed.isEmpty()) return false;
        List<DisplayItem> patched = paintBuilder.patchBoxes(changed);
        if (patched == null) return false;
        RESTYLE_REPAINT_PATCH.incrementAndGet();
        setRenderCache(patched);
        applyOverrides();
        if (!tryScrollTranslate()) { // canTranslateAll で確かめてあるので通常は来ない。来たら位置を決め直して作り直す
            repositionFromLayout(false);
            return true;
        }
        layoutStale = true;
        STAT_SCROLL_TRANSLATED.increment();
        return true;
    }

    /** {@link #tryScrollTranslate} が成り立つか（表示リストを変えずに確かめる）。 */
    private boolean canTranslateAll() {
        if (scrollState.hasUncheckedChange(scrollAtBuild, handledScrollNodes)) return false;
        return withinWindow();
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

    /** 診断: 箱の位置を決め直さずに、窓だけを動かして表示リストを作り直した回数。 */
    public static final java.util.concurrent.atomic.LongAdder STAT_WINDOW_REBUILD = new java.util.concurrent.atomic.LongAdder();

    /**
     * 窓の余白を超えてスクロールしたとき、箱の位置（前に決めたスクロール位置のまま）は決め直さずに、窓だけを今の位置へ動かして
     * 表示リストを作り直す。箱も表示リストの座標もスクロール前の位置のままで、動いた分は節の移動で足す。
     * 箱の位置を決め直す分（全箱の配置）が要らない。できなければ false（何も変えていない）。
     */
    private boolean rebuildWindowAtBoxFrame() {
        if (!layoutStale || paintBuilder == null || !com.nishiyu.enderframelib.core.kernel.Shortcut.SCROLL_CONTENT_NODES.on() || scrollBase.isEmpty()) return false;
        if (layoutRoot == null || document == null) return false;
        if (scrollState.hasUncheckedChange(scrollAtBuild, handledScrollNodes)) return false;
        Map<HtmlNode, float[]> now = scrollState.snapshot();
        Map<HtmlNode, float[]> shift = new java.util.IdentityHashMap<>();
        for (ScrollContentInfo info : scrollBase) {
            float[] was = scrollAtBuild.get(info.node());
            float wx = was != null ? was[0] : 0f, wy = was != null ? was[1] : 0f;
            shift.put(info.node(), new float[]{scrollState.getScrollX(info.node()) - wx, scrollState.getScrollY(info.node()) - wy});
        }
        // 箱の位置を決めたときのスクロール位置へ一時的に戻して作る（つまみの位置・コンテナの基準が、箱と同じ座標になる）
        java.util.Set<HtmlNode> keys = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        keys.addAll(now.keySet());
        keys.addAll(scrollAtBuild.keySet());
        for (HtmlNode k : keys) {
            float[] f = scrollAtBuild.get(k);
            scrollState.setScroll(k, f != null ? f[0] : 0f, f != null ? f[1] : 0f);
        }
        List<DisplayItem> elements;
        try {
            paintBuilder.setWindowShift(shift);
            elements = paintBoxes(layoutRoot, rootW, rootH, true);
        } finally {
            paintBuilder.setWindowShift(null);
            for (HtmlNode k : keys) {
                float[] n = now.get(k);
                scrollState.setScroll(k, n != null ? n[0] : 0f, n != null ? n[1] : 0f);
            }
        }
        if (elements == null) return false;
        List<ScrollContentInfo> built = com.nishiyu.enderframelib.core.kernel.Shortcut.SCROLL_CONTENT_NODES.on() ? builtScrollContents() : List.of();
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (ScrollContentInfo info : built) {
            keep.add(info.contentId());
            if (info.thumbVId() != null) keep.add(info.thumbVId());
            if (info.thumbHId() != null) keep.add(info.thumbHId());
            for (var st : info.stickies()) keep.add(st.nodeId());
        }
        for (ScrollContentInfo info : scrollBase) { // 窓から外れたコンテナの移動量は、残さない
            if (!keep.contains(info.contentId())) clearPaintOffset(info.contentId());
            if (info.thumbVId() != null && !keep.contains(info.thumbVId())) clearPaintOffset(info.thumbVId());
            if (info.thumbHId() != null && !keep.contains(info.thumbHId())) clearPaintOffset(info.thumbHId());
            for (var st : info.stickies()) if (!keep.contains(st.nodeId())) clearPaintOffset(st.nodeId());
        }
        setRenderCache(elements);
        setScrollBase(built);
        scrollWindowAt = now;
        applyOverrides();
        if (!tryScrollTranslate()) { // 作った窓の中なので通常は来ない。来たら位置を決め直して作り直す
            layoutStale = false;
            RenderPipeline.repositionLayout(layoutRoot, document, env, interactionState,
                    rootW, rootH, scrollState, fontResolver());
            repaintFromLayout(true);
            return true;
        }
        STAT_WINDOW_REBUILD.increment();
        return true;
    }

    /** 汚れた部分木を再スタイルし、結果に応じて必要な最小の段（全レイアウト / 位置だけ / 描き直しだけ）を走らせる。 */
    private void restyleAndUpdate(boolean repositionRequested, boolean scrollOnly, List<HtmlNode> attrNodes) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        RenderPipeline.RestyleResult res = parser.restyleLayoutTree(
                layoutRoot, document,
                rootW, rootH,
                env, interactionState);
        // 箱を作り替えるのは構造が変わる再スタイルだけ（疑似要素・生成された箱）。それ以外は箱を使い回して refreshStyle するだけなので、索引は残す
        if (res.structural()) nodeBoxIndex = null;
        if (res.layoutRequired()) {
            ensureLayoutFresh();
            if (res.structural()) {
                RESTYLE_STRUCTURAL.incrementAndGet();
                recomputeLayout(rootW, rootH);
            } else if (!localPatches.tryRelayoutInPlace()) {
                RESTYLE_RELAYOUT_FULL.incrementAndGet();
                recomputeLayout(rootW, rootH);
            } else {
                RESTYLE_RELAYOUT_INPLACE.incrementAndGet();
            }
        } else if (repositionRequested || res.repositionRequired()) {
            // 再スタイルで見た目が変わった・位置の指定が変わったときは、スクロールだけの更新ではない
            RESTYLE_REPOSITION.incrementAndGet();
            if (scrollOnly && res.styleChanged() && !res.repositionRequired() && patchThenTranslate()) return;
            repositionFromLayout(scrollOnly && !res.styleChanged() && !res.repositionRequired());
        } else if (res.styleChanged()) {
            List<LayoutBox> changedBoxes = RenderPipeline.styleChangedBoxes();
            // 空白だけのテキストの箱は描くものが無い（ブロックの間なら箱の木にも入らない）。継承の変更で混ざっても部分の描き直しを妨げない
            if (!changedBoxes.isEmpty()) {
                boolean anyBlank = false;
                for (int i = 0, n = changedBoxes.size(); i < n && !anyBlank; i++) anyBlank = LocalPatches.isBlankText(changedBoxes.get(i).node);
                if (anyBlank) {
                    List<LayoutBox> kept = new ArrayList<>(changedBoxes.size());
                    for (LayoutBox b : changedBoxes) if (!LocalPatches.isBlankText(b.node)) kept.add(b);
                    changedBoxes = kept;
                }
            }
            List<DisplayItem> patched = null;
            if (paintBuilder == null) {
                SLICE_NO_BUILDER.incrementAndGet();
            } else if (!changedBoxes.isEmpty()) {
                patched = paintBuilder.patchBoxes(changedBoxes);
            }
            if (patched != null) {
                RESTYLE_REPAINT_PATCH.incrementAndGet();
                if (layoutStale) setRenderCache(patched); // 位置を後回しにしている間は、表示リストのスクロールの基準を動かさない
                else publishBuilt(patched);
                applyOverrides();
            } else {
                RESTYLE_REPAINT_FULL.incrementAndGet();
                repaintFromLayout();
            }
        } else if (!attrNodes.isEmpty()) {
            repaintAttrPatched(attrNodes);
        }
    }

    /**
     * 描画だけに効く属性が変わった節の箱に変更印を付け、印の付いていない箱の署名の比較を省いて表示リストを作り直す。
     * レイアウトは走らない（箱の位置・大きさは属性に依らない）ので、印の付く箱は変わった節の箱だけ。
     * 箱の木は触らないため、変更印で省いてよい条件（前回の作り直し以降、レイアウトが箱に触れていない）はここでも成り立つ。
     */
    private void repaintAttrPatched(List<HtmlNode> attrNodes) {
        if (layoutRoot == null || document == null) return;
        Set<HtmlNode> wanted = Collections.newSetFromMap(new IdentityHashMap<>());
        wanted.addAll(attrNodes);
        Map<HtmlNode, LayoutBox> found = new IdentityHashMap<>();
        LocalPatches.collectTextBoxes(layoutRoot, wanted, found, false);
        boolean stamps = com.nishiyu.enderframelib.core.kernel.Shortcut.CHANGE_STAMPS.on() && !found.containsValue(null);
        for (LayoutBox box : found.values()) if (box != null) box.touchPaint();
        useChangeStamps = stamps;
        try {
            repaintFromLayout();
        } finally {
            useChangeStamps = false;
        }
        if (stamps) ATTR_PATCH_HITS.incrementAndGet();
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
            invalidated();
            return false;
        }
        return true;
    }

    /** 何も無効化されていなかったために早期に返したフレームの数（診断・テスト用）。 */
    static final java.util.concurrent.atomic.AtomicLong IDLE_FRAMES = new java.util.concurrent.atomic.AtomicLong();

    /**
     * 静止フレームか。リサイズ・無効化・保留中の文字/属性・位置の決め直し・汚れた節・後回しの配置のどれも無く、
     * 箱の木もある（初回の構築ではない）。このとき以降の処理はすべて何もしないので、省いても結果は変わらない。
     */
    private boolean isIdleFrame(boolean resize, InvalidationLevel level) {
        if (resize || level != InvalidationLevel.NONE || layoutRoot == null || layoutStale
                || repositionPending || nonScrollRepositionPending || interactionState.hasDirtyNodes()) return false;
        synchronized (pendingTextNodes) {
            if (!pendingTextNodes.isEmpty()) return false;
        }
        synchronized (pendingAttrNodes) {
            return pendingAttrNodes.isEmpty();
        }
    }

    /** 描画キューから呼ばれる、このセッション 1 回分の無効化処理。 */
    public void flushInvalidation() {
        if (document == null || document.root == null) return;

        // 時間で動くスタイル（opacity / transform / 色以外のトランジションと @keyframes）を進める。
        // 動いているうちは毎フレーム再スタイルを要求する（終わった要素は進めた時点で再スタイルの対象になる）
        if (interactionState.advanceAnimations(nowMs())) requestRestyle();

        boolean resize = resizeDue();
        InvalidationLevel level = consumeInvalidationLevel();
        if (isIdleFrame(resize, level)) {
            IDLE_FRAMES.incrementAndGet();
            return;
        }
        scene3dSync.sync(document.root); // DOM（属性・束縛）が変わったかもしれないフレームだけ、3D の記述を世界へ写す
        if (level != InvalidationLevel.TEXT_PATCH) {
            // 文字の変更と再スタイルが同じフレームに重なったら、再スタイルの経路は文字の箱を差し替えないので全体にする
            boolean hadPendingText;
            synchronized (pendingTextNodes) {
                hadPendingText = !pendingTextNodes.isEmpty();
                // 再スタイルだけと重なったときは、文字を先に局所パッチで反映してから再スタイルを進める（下の localPatches.combinedTextPatch）。残しておく
                if (hadPendingText && (level == InvalidationLevel.RESTYLE || level == InvalidationLevel.NONE) && com.nishiyu.enderframelib.core.kernel.Shortcut.COMBINED_TEXT_PATCH.on()) {
                    localPatches.combinedTextPatch = true;
                } else {
                    pendingTextNodes.clear();
                }
            }
            if (hadPendingText && !localPatches.combinedTextPatch && level != InvalidationLevel.RELAYOUT) level = InvalidationLevel.RELAYOUT;
        }
        if (level != InvalidationLevel.CHILD_PATCH) {
            // 子の増減と再スタイルが同じフレームに重なったら、再スタイルの経路は箱を増減しないので全体にする
            boolean hadPendingChildren;
            synchronized (localPatches.pendingChildParents) {
                hadPendingChildren = !localPatches.pendingChildParents.isEmpty();
                localPatches.pendingChildParents.clear();
            }
            if (hadPendingChildren && level != InvalidationLevel.RELAYOUT) level = InvalidationLevel.RELAYOUT;
        }
        List<HtmlNode> attrNodes;
        synchronized (pendingAttrNodes) {
            attrNodes = pendingAttrNodes.isEmpty() ? List.of() : new ArrayList<>(pendingAttrNodes);
            pendingAttrNodes.clear();
        }
        boolean repositionRequested = repositionPending;
        repositionPending = false;
        boolean scrollOnly = !nonScrollRepositionPending;
        nonScrollRepositionPending = false;
        if (layoutRoot == null) {
            level = InvalidationLevel.RELAYOUT;
            interactionState.markAllDirty();
        }
        // 後回しにした位置の決め直しは、スクロールだけの更新以外の経路に入る前に済ませる
        // 汚れた節（:hover など）の再スタイルは、位置が古いままでも進められる（箱は表示リストを作ったときの位置のままで、表示リストと同じ座標）。
        // 見た目だけの変化は箱のスライスの差し替えで済み、位置が要る経路に落ちるときだけ restyleAndUpdate が決め直す
        // 文字だけの更新（毎秒のタイマーなど）は、位置が古いままでも箱の座標で済ませられる（tryTextPatch が古い位置の枠で直す）。ここで決め直すと全箱の配置が走る
        boolean staleTextPatch = layoutStale && com.nishiyu.enderframelib.core.kernel.Shortcut.STALE_TEXT_PATCH.on() && (level == InvalidationLevel.TEXT_PATCH || localPatches.combinedTextPatch) && !resize
                && attrNodes.isEmpty() && !repositionRequested
                && !(document.root instanceof HtmlDocument liveDoc && liveDoc.hasLayoutObservers());
        if (layoutStale && !staleTextPatch && (resize || level != InvalidationLevel.REPOSITION && level != InvalidationLevel.NONE && level != InvalidationLevel.RESTYLE
                || !scrollOnly && level == InvalidationLevel.REPOSITION || !attrNodes.isEmpty())) {
            ensureLayoutFresh();
        }
        if (localPatches.combinedTextPatch && !resize) {
            localPatches.combinedTextPatch = false;
            if (!staleTextPatch) ensureLayoutFresh();
            if (localPatches.tryTextPatch(true)) {
                TEXT_PATCH_HITS.incrementAndGet();
                COMBINED_TEXT_PATCH_HITS.incrementAndGet();
            } else {
                TEXT_PATCH_FALLBACKS.incrementAndGet();
                level = InvalidationLevel.RELAYOUT;
            }
        }
        localPatches.combinedTextPatch = false;
        if (resize) {
            exactTranslation = true;
            try {
                recomputeLayout(pendingResizeW, pendingResizeH);
            } finally {
                exactTranslation = false;
            }
            return;
        }

        switch (level) {
            case RELAYOUT -> recomputeLayout(rootW, rootH);
            case TEXT_PATCH -> {
                if (localPatches.tryTextPatch()) {
                    TEXT_PATCH_HITS.incrementAndGet();
                } else {
                    TEXT_PATCH_FALLBACKS.incrementAndGet();
                    recomputeLayout(rootW, rootH);
                }
            }
            case CHILD_PATCH -> {
                if (localPatches.tryChildPatch(attrNodes, repositionRequested)) {
                    CHILD_PATCH_HITS.incrementAndGet();
                } else {
                    CHILD_PATCH_FALLBACKS.incrementAndGet();
                    recomputeLayout(rootW, rootH);
                }
            }
            case RESTYLE -> restyleAndUpdate(repositionRequested, scrollOnly, attrNodes);
            case REPOSITION -> {
                if (interactionState.hasDirtyNodes()) {
                    restyleAndUpdate(true, scrollOnly, attrNodes);
                } else {
                    repositionFromLayout(scrollOnly);
                }
            }
            case REPAINT -> repaintFromLayout();
            case NONE -> {
                if (interactionState.hasDirtyNodes()) restyleAndUpdate(repositionRequested, scrollOnly, attrNodes);
            }
        }
    }

    /**
     * 文字の変更だけを、箱の木を作り直さずに反映する。成功したら true。
     * 条件に合わないとき（false）は何も変えないので、呼び出し側が全体の再レイアウトに退く。
     *
     * <p>条件: 文字以外の汚れが無い・コンテナクエリが無い・変わった文字の箱がすべて見つかる・文字の前後とも空白だけではない
     * （空白だけの Text は箱の木に入るかどうかが変わる）・表や入力部品、{@code <style>} の中ではない。</p>
     */
    /** 診断: 位置の決め直しを後回しにしたまま、文字の局所パッチで済ませた回数。 */
    public static final java.util.concurrent.atomic.AtomicLong STALE_TEXT_PATCH_HITS = new java.util.concurrent.atomic.AtomicLong();
    /** 診断: 重なった文字の変更を局所パッチで済ませた回数。 */
    public static final java.util.concurrent.atomic.AtomicLong COMBINED_TEXT_PATCH_HITS = new java.util.concurrent.atomic.AtomicLong();



    // ==========================================
    // 子の増減の局所パッチ
    // ==========================================

    public static final java.util.concurrent.atomic.AtomicLong CHILD_PATCH_HITS = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong CHILD_PATCH_FALLBACKS = new java.util.concurrent.atomic.AtomicLong();
    /** 再スタイル経路（{@code restyleAndUpdate}）の行き先の内訳（計測用）。{@code RESTYLE_REPAINT_FULL} は表示リストを全体再構築した回数（スライスパッチ未使用）。 */
    /** スライスパッチを試みる箇所で、表示リストのビルダーが無かった／子の計画が複数だった回数（計測用）。 */
    public static final java.util.concurrent.atomic.AtomicLong SLICE_NO_BUILDER = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong SLICE_MULTI_PLAN = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong RESTYLE_STRUCTURAL = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong RESTYLE_RELAYOUT_FULL = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong RESTYLE_RELAYOUT_INPLACE = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong RESTYLE_REPOSITION = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong RESTYLE_REPAINT_PATCH = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong RESTYLE_REPAINT_FULL = new java.util.concurrent.atomic.AtomicLong();

    /**
     * 子の増減だけを、箱の木を作り直さずに反映する。成功したら true。条件に合わないとき（false）は何も変えないので、
     * 呼び出し側が全体の再レイアウトに退く。
     *
     * <p>条件: 文字・属性・その他の汚れが無い・コンテナクエリとカウンタが無い・親の箱がブロックの並びで（匿名ボックス・疑似要素・
     * マーカーが挟まらない）、増える子がブロックレベルの要素で、再スタイルが要る節が親と増えた子の部分木だけ
     * （兄弟の規則に当たる節が無い）。</p>
     */
    /** 診断用: 直近の局所パッチを断った理由（成功したら "ok"）。 */
    public static volatile String lastChildPatchRejection = "";

    /** 再スタイルで寸法の入力だけが変わったとき、箱の木を作り直さずに測り直した回数（調べる・テスト用）。 */
    public static final java.util.concurrent.atomic.AtomicLong INPLACE_RELAYOUT_HITS = new java.util.concurrent.atomic.AtomicLong();

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
        String tag = node.tag.toLowerCase(Locale.ROOT);
        if (!tag.equals("progress") && !tag.equals("meter")) return false;
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
        UIHitResult r = hitTest(pixelX, pixelY);
        if (r == null || r.element() == null || r.element().events() == null) return null;
        java.util.Map<String, String> ev = r.element().events();
        if (!Scene3DTags.KIND_3D.equals(ev.get(Scene3DTags.KIND_ATTR))) return null;
        String id = ev.get(Scene3DTags.WORLD_ATTR);
        if (id == null) return null;
        long worldId;
        try {
            worldId = Long.parseLong(id);
        } catch (NumberFormatException e) {
            return null;
        }
        Scene3DWorld world = scene3dWorlds.get(worldId);
        if (world == null || r.element().width() <= 0 || r.element().height() <= 0) return null;
        Raycaster3D.Hit hit = new Raycaster3D.Hit();
        Raycaster3D rc = scene3dRaycasters.computeIfAbsent(worldId, k -> new Raycaster3D());
        synchronized (rc) {
            if (!rc.pickScreen(world, (float) r.localX(), (float) r.localY(), r.element().width(), r.element().height(), hit)) return null;
        }
        return new Scene3DPick(worldId, r.element().id(), hit);
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
                doc.syncSheet();
                applyResolution(doc.sheet);
                doc.authorCss = authorCss;
            } else if (oldRootW != width || oldRootH != height) {
                // ビューポートだけが変わったときは CSS を読み直さず、@media の条件だけ評価し直す
                doc.sheet.updateMediaEnvironment(doc.sheet.mediaEnvironment().withSize(width, height));
            }
            NodeStateSnapshot carried = NodeStateSnapshot.capture(doc.root, scrollState, interactionState);
            adoptServerTree(doc.root, rootNode);
            carried.restore(doc.root, scrollState, interactionState);
        }

        initializeBindings(doc.root);
        tickLocalVariables(doc.root);
        applyOverrides();
        scene3dSync.sync(doc.root); // 初回の記述を世界へ（以後は再スタイル・再レイアウトを伴うフレームで差分だけ）

        interactionState.markAllDirty();
        // 再同期で同じ文書を差し替えたときは、使い回せる箱（クライアント所有の島など）を前回の木から拾う
        LayoutBox rootBox = parser.layoutDocument(
                doc, width, height, env, scrollState, fontResolver(), interactionState,
                existingServerDoc ? layoutRoot : null);
        setLayoutRoot(rootBox);
        List<DisplayItem> elements = paintBoxes(rootBox, width, height, false);
        if (elements == null) elements = new ArrayList<>();
        publishBuilt(elements);

        applyOverrides();
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

    /**
     * 再同期で木を差し替えても戻す、節に紐づく状態（スクロール位置・フォーカス・ホバー）。
     * 状態は節そのものをキーに持つので、作り直された節へは id をたよりに移す。id の無い節は対象外。
     */
    record NodeStateSnapshot(Map<String, float[]> scrolls, String focusedId, boolean focusVisible, String hoveredId) {

        static NodeStateSnapshot capture(HtmlNode root, ScrollState scrollState, InteractionState interaction) {
            Map<String, float[]> scrolls = new LinkedHashMap<>();
            collectScrolls(root, scrollState, scrolls);
            HtmlNode focused = interaction.focusedNode();
            boolean visible = focused != null && interaction.get(focused) != null && interaction.get(focused).isFocusVisible();
            HtmlNode hovered = interaction.hoveredNode();
            return new NodeStateSnapshot(scrolls, focused != null ? focused.key() : "", visible,
                    hovered != null ? hovered.key() : "");
        }

        private static void collectScrolls(HtmlNode node, ScrollState scrollState, Map<String, float[]> out) {
            float x = scrollState.getScrollX(node);
            float y = scrollState.getScrollY(node);
            if ((x != 0 || y != 0) && !node.key().isEmpty()) out.put(node.key(), new float[]{x, y});
            for (HtmlNode child : node.children) collectScrolls(child, scrollState, out);
        }

        void restore(HtmlNode root, ScrollState scrollState, InteractionState interaction) {
            for (Map.Entry<String, float[]> entry : scrolls.entrySet()) {
                HtmlNode node = root.getNodeById(entry.getKey());
                if (node != null) scrollState.setScroll(node, entry.getValue()[0], entry.getValue()[1]);
            }
            if (!focusedId.isEmpty()) {
                HtmlNode node = root.getNodeById(focusedId);
                if (node != null) interaction.setFocusedNode(node, focusVisible);
            }
            if (!hoveredId.isEmpty()) {
                HtmlNode node = root.getNodeById(hoveredId);
                if (node != null) interaction.setHoveredNode(node);
            }
        }
    }

    /** 最上位のクライアント所有の部分木（id を持つもの）を集める。その内側は島ごと運ぶので降りない。 */
    private static void collectClientOwnedIslands(HtmlNode node, Map<String, HtmlNode> islands) {
        for (HtmlNode child : node.children) {
            boolean declaredClientOwned = Ownership.parse(child.attrs.get(Ownership.ATTRIBUTE)) == Ownership.CLIENT;
            if (declaredClientOwned && !child.key().isEmpty()) {
                islands.put(child.key(), child);
            } else {
                collectClientOwnedIslands(child, islands);
            }
        }
    }

    /** 直近の更新の内訳（診断表示用）。毎フレームの文字列の組み立て（{@code String.format}）を避けるため、読まれたときに作る（B12）。 */
    java.util.function.Supplier<String> lastBreakdown = () -> "";

    /** 診断・テスト用: 直近の更新の段ごとの内訳。 */
    public String lastBreakdownForTest() {
        return lastBreakdown.get();
    }

    /** 直近の全レイアウトの内訳（調べる用）。 */
    public String lastBreakdown() {
        return lastBreakdown.get();
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

        applyOverrides();

        Document doc = document;
        if (doc != null && doc.root != null) {
            long startedAt = System.nanoTime();
            UiDocumentCompiler parser = new UiDocumentCompiler();

            // @media はシートを読み直さず、新しいビューポートで条件だけ評価し直す
            if (doc.sheet == null) {
                doc.sheet = parser.buildStyleSheet(doc.authorCss, newRootW, newRootH);
                doc.syncSheet();
                applyResolution(doc.sheet);
                interactionState.markAllDirty();
            } else if (oldRootW != newRootW || oldRootH != newRootH) {
                boolean mediaChanged = doc.sheet.updateMediaEnvironment(
                        doc.sheet.mediaEnvironment().withSize(newRootW, newRootH));
                if (mediaChanged) interactionState.markAllDirty();
            }

            // 前回の箱の木から差分レイアウトする（変わっていない部分木は measure を飛ばす）
            LayoutBox rootBox = parser.layoutDocument(
                    doc, newRootW, newRootH, env, scrollState, fontResolver(), interactionState,
                    layoutRoot);
            setLayoutRoot(rootBox);
            long layoutDone = System.nanoTime();
            long paintAlloc0 = com.nishiyu.enderframelib.core.EngineContext.current().flags().trackAllocations() ? RenderPipeline.threadAllocatedBytes() : 0;
            List<DisplayItem> elements = paintBoxes(rootBox, newRootW, newRootH, false);
            String breakdown = RenderPipeline.lastBuildStats()
                    + (com.nishiyu.enderframelib.core.EngineContext.current().flags().trackAllocations() ? " paintAllocKB=" + (RenderPipeline.threadAllocatedBytes() - paintAlloc0) / 1024 : "") + " layout=" + (layoutDone - startedAt) / 1_000_000 + "ms paint=" + (System.nanoTime() - layoutDone) / 1_000_000 + "ms";
            lastBreakdown = () -> breakdown;
            if (elements == null) elements = new ArrayList<>();
            publishBuilt(elements);
            applyOverrides();
            lastLayoutFinishedNanos = System.nanoTime();
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

    public boolean handleScrollLocally(double pixelX, double pixelY, double scrollX, double scrollY) {
        float sens = EnderFrameBuiltInConfig.scrollSensitivity;

        // 手前のスクロールする箱から。祖先の変換（回転・拡縮・平行移動）の逆行列とクリップを通した点で当たりを取る
        List<UIHitResult> hits = hitTestIndex().scrollContainersAt(sessionId, pixelX, pixelY);
        for (int i = 0, hn = hits.size(); i < hn; i++) {
            DisplayItem el = hits.get(i).element();
            float maxW = el.hints().scrollWidth(), maxH = el.hints().scrollHeight();

            ScrollState state = scrollState;
            // 何が壊れていたか: ここだけが描画 ID (foo_bg など) で読み書きしていた。
            // 読み手 (LayoutBox / DisplayListBuilder) はベースノード ID を使うため、
            // スクロール可能な部分要素が _bg だったコンテナはホイールが無反応だった。
            HtmlNode scrollNode = findNode(el.baseId());
            if (scrollNode == null || interactionState.isInert(scrollNode)) continue;
            float curX = state.getScrollX(scrollNode);
            float curY = state.getScrollY(scrollNode);

            float limitX = Math.max(0, maxW - el.width()), limitY = Math.max(0, maxH - el.height());
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

    public boolean handleScrollbarClick(DisplayItem clickedEl, double pixelX, double pixelY) {
        if (clickedEl == null || clickedEl.id() == null) return false;
        boolean isVTrack = clickedEl.isPart(SB_V_TRACK);
        boolean isHTrack = clickedEl.isPart(SB_H_TRACK);
        boolean isVThumb = clickedEl.isPart(SB_V_THUMB);
        boolean isHThumb = clickedEl.isPart(SB_H_THUMB);
        if (!isVTrack && !isHTrack && !isVThumb && !isHThumb) return false;

        ensureLayoutFresh();
        if (layoutRoot == null) return false;

        HtmlNode scrollNode = findNode(clickedEl.baseId());
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
            state.setScroll(scrollNode, curX, ratio * maxScrollY);
            requestScrollReposition();
            return true;
        } else if (isHTrack) {
            float trackX = box.x + box.bL + 2;
            float trackW = box.w - box.bL - box.bR - 4;
            float thumbW = Math.max(12, trackW * (box.w / (float) Math.max(1, box.scrollWidth)));
            float availW = Math.max(1, trackW - thumbW);
            float ratio = Math.clamp((float) (pixelX - trackX - thumbW / 2.0) / (float) availW, 0f, 1f);
            float maxScrollX = Math.max(0, box.scrollWidth - box.w);
            float curY = state.getScrollY(scrollNode);
            state.setScroll(scrollNode, ratio * maxScrollX, curY);
            requestScrollReposition();
            return true;
        }
        return false;
    }

    /** スクロールバーの部品の描画 ID 接尾辞（持ち主の節の ID に続く部分）。 */
    public static final String SB_V_TRACK = "_sb_vt";
    public static final String SB_H_TRACK = "_sb_ht";
    public static final String SB_V_THUMB = "_sb_v";
    public static final String SB_H_THUMB = "_sb_h";

    /** @param nodeId スクロールする節の ID  @param part つかんでいる部品の接尾辞（{@code SB_*}） */
    public boolean handleScrollbarDrag(String nodeId, String part, double pixelX, double pixelY) {
        if (nodeId == null || nodeId.isEmpty() || part == null) return false;
        boolean isVThumb = part.equals(SB_V_THUMB) || part.equals(SB_V_TRACK);
        boolean isHThumb = part.equals(SB_H_THUMB) || part.equals(SB_H_TRACK);
        if (!isVThumb && !isHThumb) return false;

        ensureLayoutFresh();
        if (layoutRoot == null) return false;

        HtmlNode scrollNode = findNode(nodeId);
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
            state.setScroll(scrollNode, curX, ratio * maxScrollY);
            requestScrollReposition();
            return true;
        } else if (isHThumb) {
            float trackX = box.x + box.bL + 2;
            float trackW = box.w - box.bL - box.bR - 4;
            float thumbW = Math.max(12, trackW * (box.w / (float) Math.max(1, box.scrollWidth)));
            float availW = Math.max(1, trackW - thumbW);
            float ratio = Math.clamp((float) (pixelX - trackX - thumbW / 2.0) / (float) availW, 0f, 1f);
            float maxScrollX = Math.max(0, box.scrollWidth - box.w);
            float curY = state.getScrollY(scrollNode);
            state.setScroll(scrollNode, ratio * maxScrollX, curY);
            requestScrollReposition();
            return true;
        }
        return false;
    }

    public UIHitResult hitTest(double pixelX, double pixelY) {
        if (!hasInertOrModal()) return hitTestIndex().hitTest(sessionId, pixelX, pixelY);
        return hitTestIndex().hitTest(sessionId, pixelX, pixelY, nonInertHitFilter);
    }

    /** {@link #hitTest} の結果の項目を絶対座標にしたもの（絶対座標を前提にする MOD 側の消費者用）。 */
    public UIHitResult hitTestAbsolute(double pixelX, double pixelY) {
        UIHitResult r = hitTest(pixelX, pixelY);
        if (r == null || r.element() == null) return r;
        return new UIHitResult(DisplayItem.resolveAbsolute(itemsById(), r.element()), r.localX(), r.localY());
    }

    public UIHitResult localCoords(String elementId, double pixelX, double pixelY) {
        DisplayItem el = elementByIdRaw(elementId);
        if (el == null) return null;
        return hitTestIndex().getLocalCoords(sessionId, el, pixelX, pixelY);
    }

    public void updateElementTextLocally(String elementId, String newText) {
        String safeText = newText != null ? newText : "";
        String baseId = elementId;

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


    @Override
    public void close() {
        renderCache = Collections.emptyList();
        baseRenderCache = Collections.emptyList();
        paintOffsets.clear();
        paintKeysVersion.incrementAndGet();
        paintOffsetsDirty = false;
        hitTestIndex = null;
        if (document != null && document.root instanceof HtmlDocument liveDocument) {
            liveDocument.setDomChangeListener(null);
        }
        document = null;
        layoutRoot = null;
        nodeBoxIndex = null;
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
        /** 押下時にヒットした描画要素。click のアクション名を GUI へ返すときに使う。 */
        public DisplayItem pressedElement = null;
        public String lastSubmittedId = "";
        public String lastSubmittedText = "";
        public long lastSubmitTime = 0;
        public final Map<String, String> localInputCache = new ConcurrentHashMap<>();
        public final Map<String, String> lastCommittedValues = new ConcurrentHashMap<>();

        /** ダブルクリック判定（旧 ClientDomEventDispatcher.ClickState）。 */
        public HtmlNode lastClickNode = null;
        public long lastClickTime = 0;

        public void clear() {
            draggingId = "";
            draggingScrollbarPart = "";
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
