package com.nishiyu.enderframelib.common;

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
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.TextSelection;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.OffsetItemList;
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
        RESTYLE,
        RELAYOUT
    }

    /** TEXT_PATCH の対象にする Text 節の最大数（これを超えたら全体の再レイアウトの方が安い）。 */
    private static final int TEXT_PATCH_MAX_NODES = 256;
    /** 次の flush で局所パッチする Text 節（同一性で比べる）。{@code synchronized (pendingTextNodes)} で触る。 */
    private final Set<HtmlNode> pendingTextNodes = Collections.newSetFromMap(new IdentityHashMap<>());
    /** TEXT_PATCH を使うか。false なら文字の変更も全体の再レイアウトにする（切り分け・比較用）。 */
    public static volatile boolean textPatchEnabled = true;

    /** 次の flush で「描画だけ」の属性パッチをする節（同一性で比べる）。{@code synchronized (pendingAttrNodes)} で触る。 */
    private final Set<HtmlNode> pendingAttrNodes = Collections.newSetFromMap(new IdentityHashMap<>());
    /** 切り分け用: false なら描画だけの属性（progress / meter の value など）も全体の再レイアウトにする。 */
    public static volatile boolean attrPatchEnabled = true;
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
    private Document document;
    private LayoutBox layoutRoot;
    private boolean serverManaged = false;
    private boolean headlessState = false;
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
    /** スクロール以外の理由（レイアウトの問い合わせ・JS のスクロール指定など）でも位置の更新が要求された。 */
    private volatile boolean nonScrollRepositionPending = false;
    /**
     * 中身を 1 つの節にまとめたスクロールコンテナ（直近に作った表示リストの）。スクロールだけの更新は、表示リストを作り直さずに
     * この節の移動量を更新して済ませる（{@link #tryScrollTranslate}）。
     */
    private List<ScrollContentInfo> scrollBase = List.of();
    /** {@link #scrollBase} の表示リストを作ったときの、全ての節のスクロール位置（0 でないもの）。 */
    private Map<HtmlNode, float[]> scrollAtBuild = Map.of();

    /** 診断: 位置の更新を、節の移動で済ませた回数・スクロールだけなのに作り直した回数・スクロール以外で作り直した回数。 */
    public static final java.util.concurrent.atomic.LongAdder STAT_SCROLL_TRANSLATED = new java.util.concurrent.atomic.LongAdder(),
            STAT_SCROLL_FALLBACK = new java.util.concurrent.atomic.LongAdder(), STAT_REPOSITION_OTHER = new java.util.concurrent.atomic.LongAdder();
    /** 節の移動で済ませる範囲（px）。表示リストは画面の外 256px まで作ってあるので、それより内に収める。 */
    private static final float SCROLL_TRANSLATE_LIMIT = 192f;
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
    private long lastLayoutCostNanos = 0L;
    private long lastLayoutFinishedNanos = 0L;

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
     * （画面の外の箱は項目を作らなくてよい）。既定は {@link UiDocumentCompiler#clientBoxRenderer}。
     */
    protected List<DisplayItem> paintBoxes(LayoutBox root, int width, int height, boolean scrollOnly) {
        var renderer = UiDocumentCompiler.clientBoxRenderer;
        if (renderer == null) return null;
        var keyframes = document.sheet.keyframes;
        if (renderer != UiDocumentCompiler.DEFAULT_BOX_RENDERER) return renderer.render(root, width, height, keyframes, scrollState);
        // 既定の描画は、ビルダーをこのビューで持ち回す: 前回と入力が同じ箱は、項目を作り直さず前回のものを使う
        var builder = paintBuilder;
        if (builder == null || paintBuilderW != width || paintBuilderH != height
                || paintBuilderKeyframes != keyframes || paintBuilderScroll != scrollState) {
            builder = paintBuilder = new com.nishiyu.enderframelib.core.paint.DisplayListBuilder(width, height, keyframes, scrollState);
            paintBuilderW = width;
            paintBuilderH = height;
            paintBuilderKeyframes = keyframes;
            paintBuilderScroll = scrollState;
        }
        builder.setUseChangeStamps(useChangeStamps);
        try {
            return builder.render(root);
        } finally {
            builder.setUseChangeStamps(false);
        }
    }

    /** 次の {@link #paintBoxes} だけ、箱の変更印で署名の比較を省く（変わった箱が分かっている文字の局所パッチ）。 */
    private boolean useChangeStamps;

    /** 今の {@link #paintBoxes} が変更印を使ってよいか（文字の局所パッチの描画のとき）。 */
    protected final boolean useChangeStamps() {
        return useChangeStamps;
    }
    /** 切り分け用: false なら文字の局所パッチでも変更印を使わない。 */
    public static volatile boolean changeStampsEnabled = !Boolean.getBoolean("enderframe.paint.nostamps");

    private com.nishiyu.enderframelib.core.paint.DisplayListBuilder paintBuilder;
    private int paintBuilderW, paintBuilderH;
    private Object paintBuilderKeyframes, paintBuilderScroll;

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
            // 動く節は 1〜数個。全項目を複製せず、差し替えだけを疎な表で持つ（作るのは O(動く節の数)）
            Map<String, Integer> byId = baseIndexById(base);
            java.util.TreeMap<Integer, DisplayItem> moved = new java.util.TreeMap<>();
            for (Map.Entry<String, float[]> e : paintOffsets.entrySet()) {
                Integer idx = byId.get(e.getKey());
                if (idx == null) continue;
                DisplayItem el = base.get(idx);
                if (el.type() != ElementType.NODE) continue;
                float[] off = e.getValue();
                moved.put(idx, el.withTranslation(el.translateX() + off[0], el.translateY() + off[1]));
            }
            if (!moved.isEmpty()) {
                int[] positions = new int[moved.size()];
                DisplayItem[] repl = new DisplayItem[moved.size()];
                int k = 0;
                for (Map.Entry<Integer, DisplayItem> e : moved.entrySet()) {
                    positions[k] = e.getKey();
                    repl[k++] = e.getValue();
                }
                shown = new OffsetItemList(base, positions, repl);
            }
        }
        this.renderCache = shown;
        // ヒットテストの索引は引かれたときに、同じ基底の上の差し替えだけが変わったなら作り直さず patch する（hitTestIndex()）
    }

    /**
     * 描画の上だけで動きうる節の id: いま移動量を持つ節に加えて、スクロールコンテナの中身・つまみの節
     * （基底を作った時点で分かっている。まだ動いていなくても、次のスクロールで動くので最初から動きうる節として扱う）。
     */
    private java.util.Set<String> dynamicNodeIds() {
        java.util.Set<String> ids = new java.util.HashSet<>(paintOffsets.keySet());
        for (ScrollContentInfo info : scrollBase) {
            ids.add(info.contentId());
            if (info.thumbVId() != null) ids.add(info.thumbVId());
            if (info.thumbHId() != null) ids.add(info.thumbHId());
        }
        return ids;
    }

    /** 基底リストの id → 位置。基底が変わるまで使い回す（スクロールのたびには作らない）。 */
    private Map<String, Integer> baseIndexById(List<DisplayItem> base) {
        Map<String, Integer> m = baseIndexCache;
        if (m != null && baseIndexSource == base) return m;
        m = new java.util.HashMap<>(base.size() * 2);
        for (int i = 0; i < base.size(); i++) m.put(base.get(i).id(), i);
        baseIndexCache = m;
        baseIndexSource = base;
        return m;
    }

    private Map<String, Integer> baseIndexCache;
    private List<DisplayItem> baseIndexSource;
    /** {@link #hitTestIndex} を作ったときの基底リスト。 */
    private volatile List<DisplayItem> hitTestIndexBase;

    /** 画像の透過ヒットテスト（{@code <img data-hit-alpha>}）が不透明度を引く先。ホストが画像の読み方を差し込む。 */
    private volatile com.nishiyu.enderframelib.core.paint.AlphaMaskProvider alphaMaskProvider;

    public void alphaMaskProvider(com.nishiyu.enderframelib.core.paint.AlphaMaskProvider provider) {
        this.alphaMaskProvider = provider;
        HitTestIndex idx = this.hitTestIndex;
        if (idx != null) idx.alphaMaskProvider(provider);
    }

    /** 点が対話要素の上にあるか（ゲーム側が、クリックを UI に渡すか自分で処理するかを決めるのに使う）。 */
    public boolean isPointerOverUi(double pixelX, double pixelY) {
        return hitTestIndex().isPointerOverUi(sessionId, pixelX, pixelY);
    }

    public HitTestIndex hitTestIndex() {
        applyPendingPaintOffsets();
        List<DisplayItem> current = this.renderCache;
        HitTestIndex idx = this.hitTestIndex;
        if (idx != null && hitTestIndexSource == current) return idx;
        // 同じ基底の上で、描画の上だけ動く節の移動量が変わっただけなら、索引は作り直さず該当の節だけを差し替える
        java.util.Set<String> dynamic = dynamicNodeIds();
        if (idx != null && hitTestIndexBase == baseRenderCache && idx.dynamicIds().containsAll(dynamic)) {
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
        this.hitTestIndexSource = current;
        this.hitTestIndexBase = baseRenderCache;
        this.hitTestIndex = idx;
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
            } else if (attributeName != null && attrPatchEnabled && isPaintOnlyAttribute(node, attributeName)) {
                synchronized (pendingAttrNodes) {
                    pendingAttrNodes.add(node);
                }
                requestRestyle();
            } else if (attributeName == null && textPatchEnabled && node.nodeType == NodeType.TEXT) {
                requestTextPatch(node);
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
        return layoutRoot;
    }

    public void setLayoutRoot(LayoutBox layoutRoot) {
        this.layoutRoot = layoutRoot;
        nodeBoxIndex = null; // 箱の木を作り直したかもしれない。索引は次に要るときに作り直す
        publishLayoutToDom();
    }

    /**
     * DOM の節ごとの箱（匿名でない）の索引。文字の局所パッチが変わった節の箱を木全体から探さずに引く。
     * 箱の木を作る経路（{@link #setLayoutRoot}）で捨てる。文字の局所パッチと、箱の木の形を変えない再レイアウトは捨てない。
     * 表の中の箱は値が null（局所パッチの対象にしない印）。
     */
    private Map<HtmlNode, LayoutBox> nodeBoxIndex;

    private Map<HtmlNode, LayoutBox> nodeBoxIndex() {
        Map<HtmlNode, LayoutBox> index = nodeBoxIndex;
        if (index == null) {
            index = new IdentityHashMap<>();
            indexBoxes(layoutRoot, index, false);
            nodeBoxIndex = index;
        }
        return index;
    }

    private static void indexBoxes(LayoutBox box, Map<HtmlNode, LayoutBox> out, boolean inTable) {
        boolean table = inTable || LayoutBox.isTableBox(box);
        for (LayoutBox child : box.children) {
            if (child.node != null && !child.isAnonymous) out.put(child.node, table ? null : child);
            indexBoxes(child, out, table);
        }
    }

    /** CSSOM View が読む箱の木を差し替え、ResizeObserver / IntersectionObserver へ知らせる。 */
    private void publishLayoutToDom() {
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

    /** 描画要素のキャッシュ。必要なら先に環境変数と描画キューを更新する。 */
    public List<DisplayItem> elements() {
        beforeElements();
        applyPendingPaintOffsets();
        return renderCache;
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
        if (interactionState.setActiveNode(resolveInteractiveNode(id))) {
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
        repaintFromLayout(false);
    }

    private void repaintFromLayout(boolean scrollOnly) {
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
    private void publishBuilt(List<DisplayItem> elements) {
        for (ScrollContentInfo info : scrollBase) {
            clearPaintOffset(info.contentId());
            if (info.thumbVId() != null) clearPaintOffset(info.thumbVId());
            if (info.thumbHId() != null) clearPaintOffset(info.thumbHId());
        }
        setRenderCache(elements);
        scrollBase = DisplayListBuilder.scrollContentNodes ? builtScrollContents() : List.of();
        scrollAtBuild = scrollState.snapshot();
    }

    /** 直近に作った表示リストの、中身を 1 つの節にまとめたスクロールコンテナ。既定の描画（{@link DisplayListBuilder}）が持つ。 */
    protected List<ScrollContentInfo> builtScrollContents() {
        var builder = paintBuilder;
        return builder != null && UiDocumentCompiler.clientBoxRenderer == UiDocumentCompiler.DEFAULT_BOX_RENDERER
                ? builder.scrollContents() : List.of();
    }

    /**
     * スクロール量だけが変わったので、表示リストは作らずに、スクロールコンテナの中身の節とつまみの節を動かす。
     * 動かせるのは、全てのコンテナの移動が範囲内のとき。動かせなければ false（作り直す）。
     */
    private boolean tryScrollTranslate() {
        if (!DisplayListBuilder.scrollContentNodes || scrollBase.isEmpty()) return false;
        // 節にまとめていないコンテナ（sticky などを含む）のスクロール量が変わっていたら、その中身は作り直さないと動かない
        java.util.Set<HtmlNode> handled = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (ScrollContentInfo info : scrollBase) handled.add(info.node());
        Map<HtmlNode, float[]> now = scrollState.snapshot();
        for (Map.Entry<HtmlNode, float[]> e : now.entrySet()) {
            float[] was = scrollAtBuild.get(e.getKey());
            boolean changed = was == null || was[0] != e.getValue()[0] || was[1] != e.getValue()[1];
            if (changed && !handled.contains(e.getKey())) return false;
        }
        for (Map.Entry<HtmlNode, float[]> e : scrollAtBuild.entrySet()) {
            if (!now.containsKey(e.getKey()) && !handled.contains(e.getKey())) return false; // 0 に戻った
        }
        for (ScrollContentInfo info : scrollBase) {
            float dx = scrollState.getScrollX(info.node()) - info.sx0();
            float dy = scrollState.getScrollY(info.node()) - info.sy0();
            if (Math.abs(dx) > SCROLL_TRANSLATE_LIMIT || Math.abs(dy) > SCROLL_TRANSLATE_LIMIT) return false;
        }
        for (ScrollContentInfo info : scrollBase) {
            float dx = scrollState.getScrollX(info.node()) - info.sx0();
            float dy = scrollState.getScrollY(info.node()) - info.sy0();
            setPaintOffset(info.contentId(), -dx, -dy);
            if (info.thumbVId() != null) setPaintOffset(info.thumbVId(), 0f, info.ky() * dy);
            if (info.thumbHId() != null) setPaintOffset(info.thumbHId(), info.kx() * dx, 0f);
        }
        return true;
    }

    /** 寸法はそのままに位置だけを決め直し、描画要素リストを作り直す（measure と箱の木の作り直しを省く）。 */
    private void repositionFromLayout(boolean scrollOnly) {
        if (layoutRoot == null || document == null) return;
        RenderPipeline.repositionLayout(layoutRoot, rootW, rootH, scrollState, fontResolver());
        // スクロール量だけが変わったなら、表示リストは作り直さない（レイアウトは上で最新にしてある）
        if (scrollOnly && tryScrollTranslate()) {
            STAT_SCROLL_TRANSLATED.increment();
        } else {
            (scrollOnly ? STAT_SCROLL_FALLBACK : STAT_REPOSITION_OTHER).increment();
            repaintFromLayout(true);
        }
        if (document.root instanceof HtmlDocument live) live.notifyLayoutObservers();
    }

    /** 汚れた部分木を再スタイルし、結果に応じて必要な最小の段（全レイアウト / 位置だけ / 描き直しだけ）を走らせる。 */
    private void restyleAndUpdate(boolean repositionRequested, boolean scrollOnly, List<HtmlNode> attrNodes) {
        nodeBoxIndex = null; // 再スタイルは箱を作り替えることがある（疑似要素・生成された箱）。索引は保守側に捨てる
        UiDocumentCompiler parser = new UiDocumentCompiler();
        RenderPipeline.RestyleResult res = parser.restyleLayoutTree(
                layoutRoot, document,
                rootW, rootH,
                env, interactionState);
        if (res.layoutRequired()) {
            if (res.structural() || !tryRelayoutInPlace()) recomputeLayout(rootW, rootH);
        } else if (repositionRequested || res.repositionRequired()) {
            // 再スタイルで見た目が変わった・位置の指定が変わったときは、スクロールだけの更新ではない
            repositionFromLayout(scrollOnly && !res.styleChanged() && !res.repositionRequired());
        } else if (res.styleChanged()) {
            repaintFromLayout();
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
        collectTextBoxes(layoutRoot, wanted, found, false);
        boolean stamps = changeStampsEnabled && !found.containsValue(null);
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

    /** 描画キューから呼ばれる、このセッション 1 回分の無効化処理。 */
    public void flushInvalidation() {
        if (document == null || document.root == null) return;

        // 時間で動くスタイル（opacity / transform / 色以外のトランジションと @keyframes）を進める。
        // 動いているうちは毎フレーム再スタイルを要求する（終わった要素は進めた時点で再スタイルの対象になる）
        if (interactionState.advanceAnimations(nowMs())) requestRestyle();

        boolean resize = resizeDue();
        InvalidationLevel level = consumeInvalidationLevel();
        if (level != InvalidationLevel.TEXT_PATCH) {
            // 文字の変更と再スタイルが同じフレームに重なったら、再スタイルの経路は文字の箱を差し替えないので全体にする
            boolean hadPendingText;
            synchronized (pendingTextNodes) {
                hadPendingText = !pendingTextNodes.isEmpty();
                pendingTextNodes.clear();
            }
            if (hadPendingText && level != InvalidationLevel.RELAYOUT) level = InvalidationLevel.RELAYOUT;
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
        if (resize) {
            recomputeLayout(pendingResizeW, pendingResizeH);
            return;
        }

        switch (level) {
            case RELAYOUT -> recomputeLayout(rootW, rootH);
            case TEXT_PATCH -> {
                if (tryTextPatch()) {
                    TEXT_PATCH_HITS.incrementAndGet();
                } else {
                    TEXT_PATCH_FALLBACKS.incrementAndGet();
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
    private boolean tryTextPatch() {
        List<HtmlNode> changed;
        synchronized (pendingTextNodes) {
            changed = new ArrayList<>(pendingTextNodes);
            pendingTextNodes.clear();
        }
        Document doc = document;
        if (changed.isEmpty() || layoutRoot == null || doc == null || doc.root == null || doc.sheet == null) return false;
        if (doc.sheet.declaresContainers() || doc.sheet.usesContainerQueries()) return false;
        if (!interactionState.styleDirtyOnlyIn(changed)) return false;

        Set<HtmlNode> wanted = Collections.newSetFromMap(new IdentityHashMap<>());
        for (HtmlNode n : changed) {
            if (n.text == null || n.text.isBlank() || !n.isConnected() || n.parent == null) return false;
            if (!isPatchableParent(n.parent)) return false;
            wanted.add(n);
        }
        Map<HtmlNode, LayoutBox> found = new IdentityHashMap<>();
        Map<HtmlNode, LayoutBox> index = nodeBoxIndex();
        for (HtmlNode n : wanted) {
            LayoutBox b = index.get(n);
            if (b != null || index.containsKey(n)) found.put(n, b);
        }
        if (found.size() != wanted.size() || found.containsValue(null)) return false;
        for (LayoutBox box : found.values()) {
            // 前回の文字が空白だけだった箱は、前回の木に今の形で入っていたか分からない
            if (box.displayText == null || box.displayText.isBlank()) return false;
        }

        long startedAt = System.nanoTime();
        for (LayoutBox box : found.values()) box.refreshStyle(box.style);
        applyOverrides();
        RenderPipeline.relayoutInPlace(layoutRoot, rootW, rootH, layoutRoot.devicePixelRatio, scrollState, fontResolver(),
                found.values());
        for (HtmlNode n : changed) interactionState.clearStyleDirty(n);
        publishLayoutToDom(); // 箱の木は作り替えていないので、節の箱の索引は捨てない
        long layoutDone = System.nanoTime();
        useChangeStamps = changeStampsEnabled && RenderPipeline.lastRelayoutWasLocal;
        List<DisplayItem> elements;
        try {
            elements = paintBoxes(layoutRoot, rootW, rootH, false);
        } finally {
            useChangeStamps = false;
        }
        if (elements == null) elements = new ArrayList<>();
        long paintDone = System.nanoTime();
        publishBuilt(elements);
        lastBreakdown = "text-patch nodes=" + found.size() + " layout=" + ms(startedAt, layoutDone)
                + "ms paint=" + ms(layoutDone, paintDone) + "ms publish=" + ms(paintDone, System.nanoTime()) + "ms";
        applyOverrides();
        lastLayoutFinishedNanos = System.nanoTime();
        lastLayoutCostNanos = lastLayoutFinishedNanos - startedAt;
        return true;
    }

    /** 再スタイルで寸法の入力だけが変わったとき、箱の木を作り直さずに測り直した回数（調べる・テスト用）。 */
    public static final java.util.concurrent.atomic.AtomicLong INPLACE_RELAYOUT_HITS = new java.util.concurrent.atomic.AtomicLong();

    /**
     * 再スタイル済みの箱の木を、作り直さずに測り直して配置し直す（寸法の入力だけが変わったとき）。成功したら true。
     * 条件に合わないとき（false）は何も変えないので、呼び出し側が全体の再レイアウトに退く。
     * 箱の木の形を変える変化（表示の種類・生成内容など）は、呼び出し側が {@link RenderPipeline.RestyleResult#structural()} で除いてある。
     */
    private boolean tryRelayoutInPlace() {
        Document doc = document;
        if (!RenderPipeline.inPlaceRestyleRelayout || layoutRoot == null || doc == null || doc.root == null
                || doc.sheet == null) {
            return false;
        }
        if (doc.sheet.declaresContainers() || doc.sheet.usesContainerQueries()) return false;
        long startedAt = System.nanoTime();
        applyOverrides();
        List<LayoutBox> changedBoxes = new ArrayList<>(RenderPipeline.layoutChangedBoxes);
        RenderPipeline.relayoutInPlace(layoutRoot, rootW, rootH, layoutRoot.devicePixelRatio, scrollState, fontResolver(),
                changedBoxes);
        setLayoutRoot(layoutRoot);
        long layoutDone = System.nanoTime();
        // 再スタイルは変わった箱に updateStyle で印を付け、レイアウトは触った箱に印を付ける（検証モードが付け漏れを検出する）
        useChangeStamps = changeStampsEnabled;
        List<DisplayItem> elements;
        try {
            elements = paintBoxes(layoutRoot, rootW, rootH, false);
        } finally {
            useChangeStamps = false;
        }
        if (elements == null) elements = new ArrayList<>();
        long paintDone = System.nanoTime();
        publishBuilt(elements);
        lastBreakdown = "inplace-relayout layout=" + ms(startedAt, layoutDone)
                + "ms paint=" + ms(layoutDone, paintDone) + "ms publish=" + ms(paintDone, System.nanoTime()) + "ms";
        applyOverrides();
        lastLayoutFinishedNanos = System.nanoTime();
        lastLayoutCostNanos = lastLayoutFinishedNanos - startedAt;
        INPLACE_RELAYOUT_HITS.incrementAndGet();
        return true;
    }

    /** 診断表示用: ナノ秒の差をミリ秒（小数 3 桁）で。 */
    private static String ms(long fromNanos, long toNanos) {
        return String.format(Locale.ROOT, "%.3f", (toNanos - fromNanos) / 1_000_000.0);
    }

    /** 文字の箱の親が、局所パッチしてよい要素か（文字が箱の寸法以外の意味を持つ要素を除く）。 */
    private static boolean isPatchableParent(HtmlNode parent) {
        if (parent.nodeType != NodeType.ELEMENT) return false;
        String tag = parent.tag == null ? "" : parent.tag.toLowerCase(Locale.ROOT);
        return switch (tag) {
            case "textarea", "select", "option", "optgroup", "style", "script", "title", "noscript", "svg", "template",
                 "table", "thead", "tbody", "tfoot", "tr", "td", "th", "caption", "colgroup", "col", "button", "label",
                 "legend", "summary", "details", "pre" -> false;
            default -> true;
        };
    }

    /** {@code wanted} の節が持つ文字の箱（匿名でない）を集める。表の中に入ったら何も集めない（退く）。 */
    private static void collectTextBoxes(LayoutBox box, Set<HtmlNode> wanted, Map<HtmlNode, LayoutBox> out, boolean inTable) {
        boolean table = inTable || LayoutBox.isTableBox(box);
        for (LayoutBox child : box.children) {
            if (child.node != null && !child.isAnonymous && wanted.contains(child.node)) {
                if (table) {
                    out.put(child.node, null); // 大きさを合わせて失敗させる印（null は下で弾く）
                    continue;
                }
                out.put(child.node, child);
            }
            collectTextBoxes(child, wanted, out, table);
        }
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
            boolean visible = focused != null && interaction.get(focused) != null && interaction.get(focused).isFocusVisible;
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

    private String lastBreakdown = "";

    /** 直近の全レイアウトの内訳（調べる用）。 */
    public String lastBreakdown() {
        return lastBreakdown;
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
            long paintAlloc0 = RenderPipeline.trackAllocations ? RenderPipeline.threadAllocatedBytes() : 0;
            List<DisplayItem> elements = paintBoxes(rootBox, newRootW, newRootH, false);
            lastBreakdown = RenderPipeline.lastBuildStats
                    + (RenderPipeline.trackAllocations ? " paintAllocKB=" + (RenderPipeline.threadAllocatedBytes() - paintAlloc0) / 1024 : "") + " layout=" + (layoutDone - startedAt) / 1_000_000 + "ms paint=" + (System.nanoTime() - layoutDone) / 1_000_000 + "ms";
            if (elements == null) elements = new ArrayList<>();
            publishBuilt(elements);
            applyOverrides();
            lastLayoutFinishedNanos = System.nanoTime();
            lastLayoutCostNanos = lastLayoutFinishedNanos - startedAt;
        }
    }

    /** 描画 ID または節の ID が指す表示要素。無ければ null。 */
    public DisplayItem elementById(String id) {
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
        for (UIHitResult hit : hitTestIndex().scrollContainersAt(sessionId, pixelX, pixelY)) {
            DisplayItem el = hit.element();
            float maxW = el.hints().scrollWidth(), maxH = el.hints().scrollHeight();

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
        return hitTestIndex().hitTest(sessionId, pixelX, pixelY);
    }

    public UIHitResult localCoords(String elementId, double pixelX, double pixelY) {
        DisplayItem el = elementById(elementId);
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
