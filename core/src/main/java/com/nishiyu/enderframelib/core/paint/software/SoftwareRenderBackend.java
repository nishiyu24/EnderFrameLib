package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.text.font.GlyphBitmap;

import com.nishiyu.enderframelib.core.paint.AnimatedValues;

import com.nishiyu.enderframelib.core.paint.ImagePlacement;

import com.nishiyu.enderframelib.core.paint.BorderDashPattern;

import com.nishiyu.enderframelib.core.paint.model.ShadowShape;
import com.nishiyu.enderframelib.core.paint.model.TextureRef;

import com.nishiyu.enderframelib.core.paint.model.ClipChainPart;

import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.paint.BorderPath;
import com.nishiyu.enderframelib.core.paint.gpu.GpuClip;
import com.nishiyu.enderframelib.core.paint.gpu.GpuScrollLayer;
import com.nishiyu.enderframelib.core.paint.gpu.GpuSink;
import com.nishiyu.enderframelib.core.paint.BoxDecorationPainter;
import com.nishiyu.enderframelib.core.paint.CssAnimator;
import com.nishiyu.enderframelib.core.paint.ObjectFit;
import com.nishiyu.enderframelib.core.paint.PaintSceneDriver;
import com.nishiyu.enderframelib.core.paint.RetainPlan;
import com.nishiyu.enderframelib.core.paint.PaintSceneDriver.Group;
import com.nishiyu.enderframelib.core.paint.PaintSceneDriver.Resolved;
import com.nishiyu.enderframelib.core.paint.RenderBackend;
import com.nishiyu.enderframelib.core.paint.ShaderGradientEmulator;
import com.nishiyu.enderframelib.core.paint.model.CircleBorderSpec;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.Corners;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.RenderHints;
import com.nishiyu.enderframelib.core.paint.BlendMode;
import com.nishiyu.enderframelib.core.paint.model.LayerSpec;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;
import com.nishiyu.enderframelib.core.paint.model.RoundedBorderSpec;
import com.nishiyu.enderframelib.core.paint.model.ShaderGradient;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.BidiText;
import com.nishiyu.enderframelib.core.text.font.LoadedFace;
import com.nishiyu.enderframelib.core.text.font.OutlineFontFace;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import com.nishiyu.enderframelib.core.text.model.FontStyle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ディスプレイリスト（{@link DisplayItem} の列）を {@link Pixmap} に描く、依存ゼロのソフトウェア描画バックエンド。
 * OpenGL のコンテキストも Minecraft も要らず、JUnit やサーバー、CI のピクセル比較、Swing / PNG 出力にそのまま使える。
 *
 * <p>GL 版（{@code DisplayItemDrawer}）と同じ規則で次を描く:
 * <ul>
 *   <li>背景・角丸（楕円の角を含む）・グラデーション（{@link ShaderGradientEmulator}）・ぼかした影（外側・内側）</li>
 *   <li>角丸の枠線（辺ごとの色と太さ・破線・点線）と円の枠線</li>
 *   <li>文字（FreeType の被覆率。字送り・カーニング・太字・斜体・下線・打ち消し線・影）と画像（{@code object-fit}）</li>
 *   <li>進捗バー・レンジ・チェックボックス・ラジオ・トグル</li>
 *   <li>親子で引き継ぐ変形（移動・回転・拡縮）と不透明度、祖先のクリップの連鎖（角丸を含む）</li>
 *   <li>{@code @keyframes} と {@code transition} による {@link CssAnimator} の補間（時刻 {@code nowMs} で決まる）</li>
 * </ul>
 *
 * <p>グループ単位の {@code opacity} は、不透明度が 1 未満の要素（錨の {@code NODE}）の子孫を別の層に描いてから、
 * まとめて不透明度を掛けて重ねる（重なる部品が透けて見えない。入れ子も扱う）。
 *
 * <p>{@code filter}（色の行列・{@code opacity()}・{@code blur()}・{@code drop-shadow()}）と {@code mask-image}（アルファ・輝度）は、
 * {@link LayerSpec} を持つ錨（{@code NODE}）の範囲を層に描いてから、フィルター → マスク → 不透明度の順に掛けて重ねる。
 *
 * <p>{@code clip-path} は層をフィルターの後・マスクの前に図形で切り、{@code backdrop-filter} は層を重ねる前に親の絵を加工して箱の形に貼る。
 * <p>描かないもの: {@code <canvas>}・動画・3D のシーン。
 *
 * <p>同じインスタンスで {@link #render} を繰り返すと、補間の状態を保ったまま次のフレームを描く（トランジションが進む）。
 * スレッドセーフではない。
 */
public final class SoftwareRenderBackend implements RenderBackend {

    private static final AtomicInteger SESSIONS = new AtomicInteger();
    /** 文字の字形キャッシュの上限。 */
    private static final int GLYPH_CACHE_LIMIT = 4096;

    /**
     * 最終の描画先。<b>CPU が実際に画素を塗るまで確保しない</b>（GPU 版が {@link #renderOnto} で使うとき、図形が全部 GPU に
     * 受け取られれば画面サイズの CPU 画素は要らない）。大きさは {@link #rootW} × {@link #rootH}。
     */
    private Pixmap rootPixmap;
    private final int rootW, rootH;
    /** いま描いている先（グループの層の中なら、その層）。根を指すときは {@link #rootPixmap}（未確保なら null）。 */
    private Pixmap target;
    private final java.util.ArrayList<Layer> layers = new java.util.ArrayList<>();
    private final java.util.ArrayDeque<Pixmap> layerPool = new java.util.ArrayDeque<>();
    private GpuSink rootSink;
    private final float dpr;
    private final String sessionId;
    private final PaintResources resources;
    private int clearColor = 0;

    private final Map<GlyphKey, GlyphBitmap> glyphs = new HashMap<>();
    /** 縁取り用の SDF の字形（{@link #glyphs} と同じ鍵）。 */
    private final Map<GlyphKey, GlyphBitmap> sdfGlyphs = new HashMap<>();

    // 1 フレームの間だけ使う状態
    private GpuSink sink;
    /** {@link #paintRange} の作業用（描画は 1 スレッドで、使い終えてから次を呼ぶ）。 */
    private final double[] boundsScratch = new double[4];
    private int dirtyX0 = Integer.MAX_VALUE, dirtyY0 = Integer.MAX_VALUE, dirtyX1 = Integer.MIN_VALUE, dirtyY1 = Integer.MIN_VALUE;
    private Matrix2D frame = Matrix2D.IDENTITY;

    /** CSS px の大きさ {@code cssW × cssH}・等倍の描画先を作る。 */
    public SoftwareRenderBackend(int cssW, int cssH) {
        this(cssW, cssH, 1f);
    }

    /** CSS px の大きさ {@code cssW × cssH}、1 CSS px が {@code devicePixelRatio} 画素の描画先を作る。 */
    public SoftwareRenderBackend(int cssW, int cssH, float devicePixelRatio) {
        this(cssW, cssH, devicePixelRatio, "software-" + SESSIONS.incrementAndGet());
    }

    /**
     * セッション名を指定して作る。GPU 版が、保険の CPU 描画器にも自分と同じセッションを使わせる（アニメーションの
     * 状態は {@link CssAnimator} がセッションごとに持つので、描く側と問う側で名前が揃っている必要がある）。
     */
    public SoftwareRenderBackend(int cssW, int cssH, float devicePixelRatio, String sessionId) {
        this(cssW, cssH, devicePixelRatio, sessionId, new PaintResources());
    }

    /** 文字・画像の供給元を共有して作る（GPU 版が自分の持ち物を保険の CPU 描画器にも使わせる）。 */
    public SoftwareRenderBackend(int cssW, int cssH, float devicePixelRatio, String sessionId, PaintResources resources) {
        this.resources = java.util.Objects.requireNonNull(resources, "resources");
        this.sessionId = java.util.Objects.requireNonNull(sessionId, "sessionId");
        this.driver = new PaintSceneDriver(sessionId);
        if (!(devicePixelRatio > 0f)) throw new IllegalArgumentException("devicePixelRatio must be > 0");
        this.dpr = devicePixelRatio;
        this.rootW = Math.max(1, Math.round(cssW * devicePixelRatio));
        this.rootH = Math.max(1, Math.round(cssH * devicePixelRatio));
    }

    /** 根の描画先。初めて必要になったときに確保する。 */
    private Pixmap root() {
        Pixmap r = rootPixmap;
        if (r == null) rootPixmap = r = new Pixmap(rootW, rootH);
        return r;
    }

    /** 根の画素を確保済みか（診断・テスト用）。 */
    public boolean isRootAllocated() {
        return rootPixmap != null;
    }

    /** いまの描画先。根を指していて未確保なら、ここで確保する（画素を書く直前に呼ぶ）。 */
    private Pixmap target() {
        Pixmap t = target;
        return t != null ? t : (target = root());
    }

    private int targetWidth() {
        return target != null ? target.width() : rootW;
    }

    private int targetHeight() {
        return target != null ? target.height() : rootH;
    }

    public SoftwareRenderBackend fonts(FontResolver resolver) {
        resources.fonts(resolver);
        return this;
    }

    public SoftwareRenderBackend images(ImageProvider provider) {
        resources.images(provider);
        return this;
    }

    /** フレームの最初に塗る色（非乗算の ARGB。既定は透明）。 */
    public SoftwareRenderBackend clearColor(int argb) {
        this.clearColor = Pixmap.premultiply(argb);
        return this;
    }

    /** 描画先（事前乗算の ARGB）。 */
    @Override
    public Pixmap pixmap() {
        return root();
    }

    @Override
    public float devicePixelRatio() {
        return dpr;
    }

    // ------------------------------------------------------------------ フレーム

    @Override
    public String sessionId() {
        return sessionId;
    }

    /**
     * 描画先を {@link #clearColor} で塗り直し、{@code items} を時刻 {@code nowMs}（ミリ秒）の見た目で描く。
     */
    @Override
    public void render(List<DisplayItem> items, long nowMs) {
        java.util.Arrays.fill(root().pixels(), clearColor);
        drawFrame(items, nowMs, null);
    }

    /**
     * 描画先を塗り直さずに {@code items} を重ねる。{@code sink} があれば、単色の角丸矩形と角丸の枠線はそちらへ渡す
     * （受け取られなかったものだけ CPU が描く）。GPU 版が文字・画像などの CPU 描画を重ねるのに使う。
     */
    public void renderOnto(List<DisplayItem> items, long nowMs, GpuSink sink) {
        drawFrame(items, nowMs, sink);
    }

    /** CPU が描いた範囲 {@code {x0, y0, x1, y1}}（x1・y1 は含まない）。何も描いていなければ {@code null}。 */
    public int[] dirtyBounds() {
        return dirtyX1 <= dirtyX0 || dirtyY1 <= dirtyY0 ? null : new int[]{dirtyX0, dirtyY0, dirtyX1, dirtyY1};
    }

    /** 描いた範囲を透明に戻し、{@link #dirtyBounds} を空にする。 */
    public void clearDirty() {
        int[] b = dirtyBounds();
        if (b != null) {
            int[] px = root().pixels();
            for (int y = b[1]; y < b[3]; y++) java.util.Arrays.fill(px, y * rootW + b[0], y * rootW + b[2], 0);
        }
        dirtyX0 = dirtyY0 = Integer.MAX_VALUE;
        dirtyX1 = dirtyY1 = Integer.MIN_VALUE;
    }

    private void drawFrame(List<DisplayItem> items, long nowMs, GpuSink gpu) {
        if (items == null || items.isEmpty()) return;
        this.sink = gpu;
        this.rootSink = gpu;
        this.target = rootPixmap;
        this.frame = Matrix2D.scale(dpr, dpr);
        this.frameSize = items.size();
        this.retainInfo.clear();
        this.recordOwner = null;
        try {
            // 走査（アニメーション補間・親子の変形・グループの鎖）は PaintSceneDriver が受け持ち、ここは画素を塗るだけ
            driver.run(items, nowMs, frame, painter);
            flushPending(); // 開いたままの層の中身も、層を捨てる前に書き切る
        } finally {
            pending.clear(); // 例外のときは捨てる（描画先は不定）
            while (!layers.isEmpty()) {
                Layer l = layers.remove(layers.size() - 1);
                if (l.pixmap != null) layerPool.addFirst(l.pixmap);
                if (l.maskPixmap != null) layerPool.addFirst(l.maskPixmap);
            }
            this.target = rootPixmap;
            this.rootSink = null;
            this.sink = null;
        }
    }

    private final PaintSceneDriver driver;

    /** 直近に描いた {@code items} に、時刻 {@code nowMs} でまだ動いているアニメーションがあるか。 */
    public boolean hasActiveAnimation(List<DisplayItem> items, long nowMs) {
        return driver.anyActiveAt(items, nowMs);
    }
    private final PaintSceneDriver.Painter painter = new PaintSceneDriver.Painter() {
        @Override
        public void syncLayers(List<Group> want) {
            SoftwareRenderBackend.this.syncLayers(want);
        }

        @Override
        public void drawItem(DisplayItem el, int index, float flat) {
            SoftwareRenderBackend.this.drawItem(el, index, flat);
        }

        @Override
        public RetainPlan planRetained(DisplayItem node, int nodeIndex, int from, int to) {
            return SoftwareRenderBackend.this.planRetained(node, nodeIndex, from, to);
        }

        @Override
        public void drawRetained(DisplayItem node, int nodeIndex, int from, int to) {
            GpuScrollLayer l = retainInfo.get(nodeIndex);
            if (l != null && sink != null) sink.drawScrollLayer(l);
        }

        @Override
        public void beginRetained(DisplayItem node, int nodeIndex, int from, int to) {
            GpuScrollLayer l = retainInfo.get(nodeIndex);
            if (l == null || sink == null) return;
            recordOwner = ownerKeyOf(node);
            sink.beginScrollLayer(l);
        }

        @Override
        public boolean endRetained(DisplayItem node, int nodeIndex) {
            GpuScrollLayer l = retainInfo.get(nodeIndex);
            recordOwner = null;
            return l == null || sink == null || sink.endScrollLayer(l);
        }
    };

    // ------------------------------------------------------------------ スクロール層の保持

    /** 節の添字 → 保持する層の情報（このフレームで問い合わせたもの）。 */
    private final java.util.HashMap<Integer, GpuScrollLayer> retainInfo = new java.util.HashMap<>();
    /** 保持する層へ記録している間の、コンテナ（スクロール節の持ち主）の鍵。クリップの部品のうち、これが持ち主のものは外す。 */
    private String recordOwner;
    /** 中身が保持に向くかの判定の覚え（基底の表示リストと節が同じなら同じ答え）。 */
    private Object eligibleToken;
    private int eligibleNode = -1;
    private boolean eligibleVerdict;

    private static String ownerKeyOf(DisplayItem node) {
        String id = node.id();
        return id.endsWith(PaintSceneDriver.SCROLL_CONTENT_SUFFIX) ? id.substring(0, id.length() - PaintSceneDriver.SCROLL_CONTENT_SUFFIX.length()) : id;
    }

    private RetainPlan planRetained(DisplayItem node, int nodeIndex, int from, int to) {
        GpuSink s = sink;
        if (s == null || recordOwner != null) return RetainPlan.NONE;
        Object token = driver.contentToken();
        if (!(eligibleToken == token && eligibleNode == nodeIndex)) {
            eligibleVerdict = rangeIsRetainable(driver.items(), from, to);
            eligibleToken = token;
            eligibleNode = nodeIndex;
        }
        if (!eligibleVerdict) return RetainPlan.NONE;
        GpuScrollLayer info = scrollLayerInfo(node, nodeIndex, from, to, token);
        if (info == null) return RetainPlan.NONE;
        RetainPlan r = s.planScrollLayer(info);
        if (r != RetainPlan.NONE) retainInfo.put(nodeIndex, info);
        return r;
    }

    /**
     * 範囲の項目が、保持した層に向くか: 時間や外部の状態で見た目が変わるもの（画像の読み込み・動画・キャンバス・入力欄のカーソル・
     * 動くアニメーション）を含まないこと。どれも、表示リストが変わらないまま見た目だけが変わりうるので、層が古くなる。
     */
    private static boolean rangeIsRetainable(List<DisplayItem> items, int from, int to) {
        for (int i = from; i < to; i++) {
            DisplayItem it = items.get(i);
            switch (it.type()) {
                case IMAGE, VIDEO, CANVAS, SCENE, INPUT, TEXTAREA, SELECT, PROGRESS -> {
                    return false;
                }
                default -> { }
            }
            if (it.transitionDuration() > 0 || (it.animDef() != null && !it.animDef().isEmpty())) return false;
        }
        return true;
    }

    /** 層の情報。コンテナの切り取りは、中身の項目の切り取りの鎖のうち、持ち主がコンテナのもの。 */
    private GpuScrollLayer scrollLayerInfo(DisplayItem node, int nodeIndex, int from, int to, Object token) {
        String owner = ownerKeyOf(node);
        List<DisplayItem> items = driver.items();
        List<ClipPart> parts = new ArrayList<>(1);
        for (int i = from; i < to && parts.isEmpty(); i++) {
            RenderMeta meta = items.get(i).meta();
            ClipRect cr = meta != null ? meta.clipRect() : null;
            if (cr == null) continue;
            if (!cr.chain().isEmpty()) {
                for (ClipChainPart p : cr.chain()) {
                    if (owner.equals(p.ownerId())) addClipPart(parts, new ArrayList<>(1), new ArrayList<>(1), p.rect(), p.ownerId());
                }
            } else if (owner.equals(cr.ownerId())) {
                addClipPart(parts, new ArrayList<>(1), new ArrayList<>(1), cr.toArray(), cr.ownerId());
            }
        }
        if (parts.isEmpty()) return null;
        Clip c = finishClip(parts);
        GpuClip[] gc = gpuClips(c);
        if (gc == null || c.x1() <= c.x0() || c.y1() <= c.y0()) return null;
        return new GpuScrollLayer(node.id(), token, driver.resolveAt(nodeIndex).pose(), gc, new int[]{c.x0(), c.y0(), c.x1(), c.y1()});
    }

    /** 開いている層。{@code parentDirty} は層を開く前の（親の）描いた範囲。GPU の層は {@code pixmap} が null。 */
    private static final class Layer {
        final Group group;
        final Pixmap pixmap;
        final int[] parentDirty;
        Pixmap maskPixmap;
        int[] maskRegion;
        boolean maskOnGpu;

        Layer(Group group, Pixmap pixmap, int[] parentDirty) {
            this.group = group;
            this.pixmap = pixmap;
            this.parentDirty = parentDirty;
        }

        boolean isMask() {
            return group.maskOf() != null;
        }
    }

    /** 今のフレームの項目数。 */
    private int frameSize;
    /** 項目ごとのクリップ。項目の索引が作り直された（{@link PaintSceneDriver#generation}）ら捨てる。 */
    private ClipEntry[] clipCache = new ClipEntry[0];
    private long clipGeneration = -1;
    private boolean sawUnresolvedOwner;

    // ------------------------------------------------------------------ グループの層

    /** 開いている層を、項目の祖先のグループ {@code want} に合わせる（共通の手前までを残し、残りを閉じて重ね、足りない分を開く）。 */
    private void syncLayers(List<Group> want) {
        int common = 0;
        while (common < layers.size() && common < want.size() && layers.get(common).group.id().equals(want.get(common).id())) common++;
        while (layers.size() > common) closeLayer();
        for (int i = common; i < want.size(); i++) openLayer(want.get(i));
    }

    private void openLayer(Group g) {
        flushPending(); // 描画先が変わる前に、溜めた分を今の描画先へ
        Layer top = layers.isEmpty() ? null : layers.get(layers.size() - 1);
        if (g.maskOf() != null) {
            // マスクの層。親（中身の層）が GPU の層なら GPU で、そうでなければ CPU で描く
            if (top != null && top.pixmap == null && sink != null && sink.beginMask()) {
                layers.add(new Layer(g, null, null));
                return;
            }
        } else if (sink != null) {
            LayerSpec spec = g.spec();
            GpuSink.GroupExtras extras = spec != null && (spec.clip() != null || spec.hasBackdrop())
                    ? new GpuSink.GroupExtras(g.pose(), spec.clip(), spec.hasBackdrop() ? spec.backdrop() : List.of(),
                    spec.hasBackdrop() ? spec.backdropShape() : null) : null;
            if (sink.beginGroup(g.opacity(), spec != null ? spec.filters() : List.of(), g.map(),
                    spec != null && spec.masked(), spec != null && spec.maskLuminance(),
                    spec != null ? spec.blend() : BlendMode.NORMAL, extras)) {
                layers.add(new Layer(g, null, null)); // GPU の層（画素は持たない）
                return;
            }
        }
        Pixmap pm = layerPool.pollFirst();
        if (pm == null) pm = new Pixmap(rootW, rootH);
        layers.add(new Layer(g, pm, new int[]{dirtyX0, dirtyY0, dirtyX1, dirtyY1}));
        dirtyX0 = dirtyY0 = Integer.MAX_VALUE;
        dirtyX1 = dirtyY1 = Integer.MIN_VALUE;
        target = pm;
        sink = null; // CPU の層の中は CPU が描く
    }

    /** 一番内側の層を閉じる。中身の層は、フィルター → マスク → 不透明度を掛けて親へ重ねる。マスクの層は親の層に預ける。 */
    private void closeLayer() {
        flushPending(); // 層を読んで合成する前に、層へ溜めた分を書き切る
        Layer l = layers.remove(layers.size() - 1);
        if (l.pixmap == null) {
            if (l.isMask()) sink.endMask();
            else sink.endGroup();
            return;
        }
        Layer owner = l.isMask() ? layers.get(layers.size() - 1) : null;
        Pixmap parent = root();
        for (int i = layers.size() - 1; i >= 0; i--) {
            if (layers.get(i).pixmap != null) {
                parent = layers.get(i).pixmap;
                break;
            }
        }
        int[] region = {dirtyX0, dirtyY0, dirtyX1, dirtyY1};
        boolean any = region[2] > region[0] && region[3] > region[1];
        int[] pd = l.parentDirty;
        dirtyX0 = pd[0];
        dirtyY0 = pd[1];
        dirtyX1 = pd[2];
        dirtyY1 = pd[3];
        boolean cpuLayer = false;
        for (Layer o : layers) cpuLayer |= o.pixmap != null;
        target = parent;
        if (!cpuLayer) sink = rootSink;
        if (l.isMask()) {
            // マスクは合成せず、中身の層に預ける（描かれていなければ、マスクなしと同じ）
            if (any) {
                owner.maskPixmap = l.pixmap;
                owner.maskRegion = region;
            } else {
                layerPool.addFirst(l.pixmap);
            }
            return;
        }
        LayerSpec spec = l.group.spec();
        int w = rootW, h = rootH;
        // GPU の層が足りず CPU の層に落ちたとき、背景（混合・backdrop-filter が読む絵）は GPU 側にある。
        // 描画先を読み戻して CPU で混ぜ、結果を GPU の描画先へ書き戻す。
        boolean viaGpu = false;
        if (rootSink != null && !cpuLayer && spec != null && parent == rootPixmap
                && (spec.blend() != BlendMode.NORMAL || spec.hasBackdrop())) {
            // blend だけなら層の描いた範囲しか背景を読まない。backdrop-filter はぼかしが範囲外を読むので全面
            Pixmap snap = !spec.hasBackdrop() && any ? rootSink.snapshotTarget(region) : rootSink.snapshotTarget();
            if (snap != null) {
                parent = snap;
                viaGpu = true;
            }
        }
        int[] gpuArea = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        if (viaGpu) {
            // 読み戻しで親の溜めた分が GPU へ流れた。以降の dirty は親の描いた範囲ではなく書き戻しの範囲だけを追う
            dirtyX0 = dirtyY0 = Integer.MAX_VALUE;
            dirtyX1 = dirtyY1 = Integer.MIN_VALUE;
        }
        if (any && spec != null && !spec.filters().isEmpty()) {
            region = LayerFx.filter(l.pixmap, region, spec.filters(), l.group.map());
        }
        if (any && spec != null && spec.clip() != null) {
            LayerFx.clip(l.pixmap, region, spec.clip(), l.group.pose());
        }
        if (spec != null && spec.hasBackdrop()) {
            Pixmap scratch = layerPool.pollFirst();
            if (scratch == null) scratch = new Pixmap(rootW, rootH);
            int[] area = LayerFx.backdrop(parent, scratch, spec.backdrop(), l.group.map(), l.group.pose(), spec.backdropShape(),
                    spec.clip(), l.group.opacity());
            layerPool.addFirst(scratch);
            if (area != null) grow(viaGpu ? gpuArea : null, area);
        }
        if (any && l.maskPixmap != null) {
            LayerFx.mask(l.pixmap, region, l.maskPixmap, l.maskRegion, spec.maskLuminance());
        }
        if (l.maskPixmap != null) {
            int[] mr = l.maskRegion;
            int[] mp = l.maskPixmap.pixels();
            for (int y = mr[1]; y < mr[3]; y++) java.util.Arrays.fill(mp, y * w + mr[0], y * w + mr[2], 0);
            layerPool.addFirst(l.maskPixmap);
        }
        int[] src = l.pixmap.pixels(), dst = parent.pixels();
        BlendMode blend = spec != null ? spec.blend() : BlendMode.NORMAL;
        if (any) {
            final float groupOpacity = l.group.opacity();
            final int cx0 = region[0], cx1 = region[2];
            Rows.forEach(region[1], region[3], (long) (cx1 - cx0) * (region[3] - region[1]), (ya, yb) -> {
                for (int y = ya; y < yb; y++) {
                    for (int x = cx0; x < cx1; x++) {
                        int c = src[y * w + x];
                        if (c == 0) continue;
                        if (groupOpacity < 1f) c = SoftShapes.scalePremul(c, groupOpacity);
                        dst[y * w + x] = blend == BlendMode.NORMAL ? SoftShapes.over(c, dst[y * w + x])
                                : blendPixel(blend, c, dst[y * w + x]);
                        src[y * w + x] = 0;
                    }
                }
            });
            grow(viaGpu ? gpuArea : null, region);
        }
        if (viaGpu && gpuArea[2] > gpuArea[0] && gpuArea[3] > gpuArea[1]) rootSink.overwriteTarget(parent, gpuArea);
        layerPool.addFirst(l.pixmap);
    }

    /** {@code into} （null なら CPU の dirty 範囲）を {@code r} の分だけ広げる。 */
    private void grow(int[] into, int[] r) {
        if (into != null) {
            into[0] = Math.min(into[0], r[0]);
            into[1] = Math.min(into[1], r[1]);
            into[2] = Math.max(into[2], r[2]);
            into[3] = Math.max(into[3], r[3]);
            return;
        }
        dirtyX0 = Math.min(dirtyX0, r[0]);
        dirtyY0 = Math.min(dirtyY0, r[1]);
        dirtyX1 = Math.max(dirtyX1, r[2]);
        dirtyY1 = Math.max(dirtyY1, r[3]);
    }

    /** {@code mix-blend-mode} の合成。{@code src}・{@code dst} は事前乗算の ARGB で、結果も事前乗算。 */
    private static int blendPixel(BlendMode mode, int src, int dst) {
        float as = (src >>> 24) / 255f, ab = (dst >>> 24) / 255f;
        if (as <= 0f) return dst;
        float[] cs = unpremul(src, as), cb = ab > 0f ? unpremul(dst, ab) : new float[3];
        float[] o = BlendMode.composite(mode, cb, ab, cs, as);
        int a = Math.round(Math.min(1f, o[3]) * 255f);
        int r = Math.round(Math.min(1f, Math.max(0f, o[0])) * 255f);
        int g = Math.round(Math.min(1f, Math.max(0f, o[1])) * 255f);
        int b = Math.round(Math.min(1f, Math.max(0f, o[2])) * 255f);
        return (a << 24) | (Math.min(r, a) << 16) | (Math.min(g, a) << 8) | Math.min(b, a);
    }

    private static float[] unpremul(int pm, float a) {
        return new float[]{((pm >> 16) & 0xFF) / 255f / a, ((pm >> 8) & 0xFF) / 255f / a, (pm & 0xFF) / 255f / a};
    }

    // ------------------------------------------------------------------ 項目

    private void drawItem(DisplayItem el, int index, float flat) {
        if (clipGeneration != driver.generation()) {
            if (clipCache.length != frameSize) clipCache = java.util.Arrays.copyOf(clipCache, frameSize);
            clipGeneration = driver.generation();
        }
        Resolved res = driver.resolveAt(index);
        if (res.opacity() <= 0.005f) return;
        float opacity = res.residual() * flat;
        if (opacity <= 0.005f) return;
        AnimatedValues anim = res.anim();
        Clip clip = buildClip(index, el, anim);
        if (clip.empty()) return;

        float[][] radii = radii(el, anim);
        float[] rx = radii[0], ry = radii[1];
        ElementType type = el.type();

        if (type == ElementType.CIRCLE_BORDER) {
            if (drawCircleBorder(el, anim, res, clip, opacity)) return;
        }
        if (type == ElementType.ROUNDED_BORDER) {
            RoundedBorderSpec spec = el.meta() != null ? el.meta().roundedBorder() : null;
            if (spec != null) drawRoundedBorder(el, spec, rx, ry, res, clip, opacity);
            return;
        }
        if (type != ElementType.RANGE) drawBackground(el, anim, rx, ry, res, clip, opacity);

        switch (type) {
            case SCENE -> { }
            case TEXTAREA -> drawTextarea(el, anim, res, clip, opacity);
            case RANGE -> drawRange(el, anim, res, clip, opacity);
            case PROGRESS -> drawProgress(el, anim, rx, ry, res, clip, opacity);
            case TOGGLE, CHECKBOX, RADIO -> drawToggle(el, anim, res, clip, opacity);
            case IMAGE, VIDEO, CANVAS -> drawImage(el, rx, ry, res, clip, opacity);
            default -> {
                if ((el.text() != null && !el.text().isEmpty()) || type == ElementType.INPUT || type == ElementType.SELECT) {
                    drawText(el, anim, res, clip, opacity);
                }
            }
        }
    }

    /** 角ごとの横・縦の半径（左上・右上・右下・左下の順）。要素の箱に収まるよう切り詰める。 */
    private static float[][] radii(DisplayItem el, AnimatedValues anim) {
        float[] rx = new float[4], ry = new float[4];
        RenderMeta meta = el.meta();
        float w = el.width(), h = el.height();
        if (meta != null && meta.borderRadii() != null && meta.borderRadii().isElliptical()) {
            Corners c = meta.borderRadii();
            float[] h4 = c.toFloatArray(), v4 = c.toFloatArrayV();
            for (int i = 0; i < 4; i++) {
                rx[i] = Math.min(h4[i], w / 2f);
                ry[i] = Math.min(v4[i], h / 2f);
                if (rx[i] <= 0f || ry[i] <= 0f) rx[i] = ry[i] = 0f;
            }
            return new float[][]{rx, ry};
        }
        float[] circ = null;
        if (meta != null && meta.borderRadii() != null) circ = meta.borderRadii().toFloatArray();
        else if (anim.radiusData != null && anim.radiusData.length >= 4) circ = anim.radiusData;
        if (circ != null) {
            float limit = Math.min(w, h) / 2f;
            for (int i = 0; i < 4; i++) rx[i] = ry[i] = Math.max(0f, Math.min(circ[i], limit));
        }
        return new float[][]{rx, ry};
    }

    // ------------------------------------------------------------------ クリップ

    /** クリップの 1 つ。持ち主の姿勢の逆変換で画素を持ち主の空間へ戻し、角丸矩形の SDF で被覆率を出す。 */
    private record ClipPart(Matrix2D inv, float scale, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                            int bx0, int by0, int bx1, int by1, float ix0, float iy0, float ix1, float iy1,
                            double dix0, double diy0, double dix1, double diy1) {
        float coverage(double dx, double dy) {
            float lx = (float) inv.applyX(dx, dy), ly = (float) inv.applyY(dx, dy);
            if (lx >= ix0 && lx <= ix1 && ly >= iy0 && ly <= iy1) return 1f; // 角と縁から離れた内側は距離を測らない
            return SoftShapes.coverage(SoftShapes.sdRoundRect(lx, ly, x0, y0, x1, y1, rx, ry), scale);
        }
    }

    private record Clip(List<ClipPart> parts, int x0, int y0, int x1, int y1,
                        double ix0, double iy0, double ix1, double iy1) {
        boolean empty() {
            return x1 <= x0 || y1 <= y0;
        }

        float coverage(double dx, double dy) {
            if (dx >= ix0 && dx <= ix1 && dy >= iy0 && dy <= iy1) return 1f; // 全部の内側（軸平行のときだけ非空）
            float c = 1f;
            for (int i = 0; i < parts.size(); i++) {
                c *= parts.get(i).coverage(dx, dy);
                if (c <= 0f) return 0f;
            }
            return c;
        }
    }

    /** 項目のクリップの結果と、それを作ったときの前提（描画先の大きさ・持ち主の姿勢）。前提が同じ間は作り直さない。 */
    private static final class ClipEntry {
        Clip clip;
        int[] owner;
        Resolved[] ownerRes;
        int w, h;
        /** 作ったときの世代と、クリップの元（{@link ClipRect} の同一インスタンス。動くクリップは null）。 */
        long generation;
        Object source;
        /** 持ち主の id があるのに見つからなかった（後から現れると結果が変わるので世代をまたいで使い回さない）。 */
        boolean unresolvedOwner;
    }

    private Clip buildClip(int index, DisplayItem el, AnimatedValues anim) {
        ClipEntry ce = clipCache[index];
        RenderMeta m0 = el.meta();
        Object source = m0 != null ? m0.clipRect() : null;
        // 項目の並びが変わった世代をまたぐときは、元の ClipRect が同一インスタンスのものだけ使い回す（添字が同じでも別の項目かもしれない）
        boolean recording = recordOwner != null; // 保持する層へ記録中は、コンテナの切り取りを外すので使い回しの覚えを使わない
        if (!recording && ce != null && ce.w == targetWidth() && ce.h == targetHeight()
                && (ce.generation == clipGeneration || (source != null && ce.source == source && !ce.unresolvedOwner))) {
            boolean same = true;
            for (int k = 0; k < ce.owner.length && same; k++) {
                same = (ce.owner[k] < 0 ? null : driver.resolveAt(ce.owner[k])) == ce.ownerRes[k];
            }
            if (same) return ce.clip;
        }
        sawUnresolvedOwner = false;
        List<ClipPart> parts = new ArrayList<>(2);
        List<Integer> owners = new ArrayList<>(2);
        List<Resolved> ownerRes = new ArrayList<>(2);
        RenderMeta meta = el.meta();
        ClipRect cr = meta != null ? meta.clipRect() : null;
        if (cr != null && !cr.chain().isEmpty()) {
            for (ClipChainPart p : cr.chain()) {
                if (recording && recordOwner.equals(p.ownerId())) continue;
                addClipPart(parts, owners, ownerRes, p.rect(), p.ownerId());
            }
        } else if (cr != null) {
            if (!(recording && recordOwner.equals(cr.ownerId()))) addClipPart(parts, owners, ownerRes, cr.toArray(), cr.ownerId());
        } else if (anim.clipData != null) {
            addClipPart(parts, owners, ownerRes, anim.clipData, null);
        }
        ce = new ClipEntry();
        ce.clip = finishClip(parts);
        ce.owner = new int[owners.size()];
        for (int k = 0; k < ce.owner.length; k++) ce.owner[k] = owners.get(k);
        ce.generation = clipGeneration;
        ce.source = source;
        ce.unresolvedOwner = sawUnresolvedOwner;
        ce.ownerRes = ownerRes.toArray(new Resolved[0]);
        ce.w = targetWidth();
        ce.h = targetHeight();
        if (!recording) clipCache[index] = ce;
        return ce.clip;
    }

    private Clip finishClip(List<ClipPart> parts) {
        int x0 = 0, y0 = 0, x1 = targetWidth(), y1 = targetHeight();
        double ix0 = Double.NEGATIVE_INFINITY, iy0 = Double.NEGATIVE_INFINITY, ix1 = Double.POSITIVE_INFINITY, iy1 = Double.POSITIVE_INFINITY;
        for (ClipPart p : parts) {
            x0 = Math.max(x0, p.bx0());
            y0 = Math.max(y0, p.by0());
            x1 = Math.min(x1, p.bx1());
            y1 = Math.min(y1, p.by1());
            ix0 = Math.max(ix0, p.dix0());
            iy0 = Math.max(iy0, p.diy0());
            ix1 = Math.min(ix1, p.dix1());
            iy1 = Math.min(iy1, p.diy1());
        }
        return new Clip(parts, x0, y0, x1, y1, ix0, iy0, ix1, iy1);
    }

    private void addClipPart(List<ClipPart> parts, List<Integer> owners, List<Resolved> ownerRes, float[] rect, String ownerId) {
        if (rect == null || rect.length < 4) return;
        Matrix2D pose = frame;
        int ownerIndex = -1;
        Resolved owner = null;
        if (ownerId != null) {
            int ix = driver.indexOf(ownerId);
            if (ix >= 0) {
                ownerIndex = ix;
                owner = driver.resolveAt(ix);
                pose = owner.pose();
            } else {
                sawUnresolvedOwner = true;
            }
        }
        owners.add(ownerIndex);
        ownerRes.add(owner);
        Matrix2D inv = pose.invert();
        if (inv == null) return;
        float[] rx = new float[4], ry = new float[4];
        for (int i = 0; i < 4; i++) {
            rx[i] = rect.length > 4 + i ? rect[4 + i] : 0f;
            ry[i] = rect.length >= 12 ? rect[8 + i] : rx[i];
        }
        double[] b = deviceBounds(pose, rect[0], rect[1], rect[2], rect[3], new double[4]);
        float scale = (float) pose.meanScale();
        float reach = 0.5f / Math.max(scale, 0.01f) + 1e-3f;
        float mrx = maxOf(rx), mry = maxOf(ry);
        // 端末空間での「被覆率が 1 と分かっている内側」。軸平行の姿勢のときだけ（そうでなければ空）。
        double[] dev = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        if (pose.b() == 0.0 && pose.c() == 0.0) {
            double ax = pose.applyX(rect[0] + mrx + reach, 0), bx = pose.applyX(rect[2] - mrx - reach, 0);
            double ay = pose.applyY(0, rect[1] + mry + reach), by = pose.applyY(0, rect[3] - mry - reach);
            dev = new double[]{Math.min(ax, bx) + 1e-3, Math.min(ay, by) + 1e-3, Math.max(ax, bx) - 1e-3, Math.max(ay, by) - 1e-3};
        }
        parts.add(new ClipPart(inv, scale, rect[0], rect[1], rect[2], rect[3], rx, ry,
                (int) Math.floor(b[0]), (int) Math.floor(b[1]), (int) Math.ceil(b[2]), (int) Math.ceil(b[3]),
                rect[0] + mrx + reach, rect[1] + mry + reach, rect[2] - mrx - reach, rect[3] - mry - reach,
                dev[0], dev[1], dev[2], dev[3]));
    }

    /** 四隅を写した外接矩形 {@code {minX, minY, maxX, maxY}} を {@code out} に書く（割り当てなし）。 */
    private static double[] deviceBounds(Matrix2D m, double x0, double y0, double x1, double y1, double[] out) {
        double ax = m.applyX(x0, y0), ay = m.applyY(x0, y0);
        double bx = m.applyX(x1, y0), by = m.applyY(x1, y0);
        double cx = m.applyX(x1, y1), cy = m.applyY(x1, y1);
        double dx = m.applyX(x0, y1), dy = m.applyY(x0, y1);
        out[0] = Math.min(Math.min(ax, bx), Math.min(cx, dx));
        out[1] = Math.min(Math.min(ay, by), Math.min(cy, dy));
        out[2] = Math.max(Math.max(ax, bx), Math.max(cx, dx));
        out[3] = Math.max(Math.max(ay, by), Math.max(cy, dy));
        return out;
    }

    // ------------------------------------------------------------------ 画素の塗り

    /** 局所座標 {@code (lx, ly)} の事前乗算の色。0 は何も塗らない。 */
    @FunctionalInterface
    private interface Shader {
        int shade(float lx, float ly);
    }

    /**
     * 局所の矩形 {@code (bx0,by0)-(bx1,by1)} を姿勢 {@code pose} で写した範囲の画素ごとに {@code shader} を呼び、
     * クリップと不透明度を掛けて重ねる。画素の中心を逆変換した点で評価する。
     */
    private void paint(Matrix2D pose, float bx0, float by0, float bx1, float by1, Clip clip, float opacity, Shader shader) {
        paint(pose, bx0, by0, bx1, by1, clip, opacity, shader, null);
    }

    /**
     * 距離を測らずに済む領域の記述（局所座標）。呼び出し側は、各領域の内側で {@code shader} が「その色」（色 0 は何も塗らない）を
     * 返すことを保証する。矩形の領域と、円の角を持つ角丸矩形の内側（行ごとに左右の端を解析的に求める）を持てる。
     */
    private static final class Fast {
        /** 近道の領域は無いが、シェーダーが純粋なので溜めてタイルで実行してよい、という印。 */
        static final Fast PURE = new Fast();
        static final int MAX = 6;
        final float[] rect = new float[4 * MAX];
        final int[] color = new int[MAX];
        int n;
        /** 角丸矩形の内側（被覆率 1）の領域。最大 {@link #MAX_ROUND} 個。 */
        static final int MAX_ROUND = 2;
        final float[] rb = new float[5 * MAX_ROUND];
        final float[][] rr = new float[MAX_ROUND][];
        final int[] roundColor = new int[MAX_ROUND];
        int nRound;

        void rect(float x0, float y0, float x1, float y1, int argbPremul) {
            if (n == MAX || !(x1 > x0) || !(y1 > y0)) return;
            rect[4 * n] = x0; rect[4 * n + 1] = y0; rect[4 * n + 2] = x1; rect[4 * n + 3] = y1;
            color[n++] = argbPremul;
        }

        /** 円の角（{@code rx[i] == ry[i]} かつ半辺以下）の角丸矩形なら、行ごとの内側を使う。そうでなければ矩形 1 つで代用する。 */
        void roundRect(float x0, float y0, float x1, float y1, float[] rx, float[] ry, float reach, int argbPremul) {
            float hx = (x1 - x0) * 0.5f, hy = (y1 - y0) * 0.5f;
            boolean circular = true;
            for (int i = 0; i < 4; i++) {
                if (rx[i] != ry[i] || rx[i] > hx || ry[i] > hy) { circular = false; break; }
            }
            if (!circular) {
                float mrx = maxOf(rx), mry = maxOf(ry);
                rect(x0 + mrx + reach, y0 + mry + reach, x1 - mrx - reach, y1 - mry - reach, argbPremul);
                return;
            }
            if (nRound == MAX_ROUND) return;
            int k = nRound++;
            rb[5 * k] = x0; rb[5 * k + 1] = y0; rb[5 * k + 2] = x1; rb[5 * k + 3] = y1; rb[5 * k + 4] = reach + 2e-3f;
            rr[k] = rx; roundColor[k] = argbPremul;
        }

        /** 局所の高さ {@code ly} での内側の区間 {@code [out0, out1]}。無ければ false。 */
        boolean roundSpan(int k, double ly, double[] out) {
            double rx0 = rb[5 * k], ry0 = rb[5 * k + 1], rx1 = rb[5 * k + 2], ry1 = rb[5 * k + 3], e = rb[5 * k + 4];
            float[] roundR = rr[k];
            if (ly < ry0 + e || ly > ry1 - e) return false;
            double xl = rx0 + e, xr = rx1 - e;
            boolean top = ly < (ry0 + ry1) * 0.5;
            double rl = roundR[top ? 0 : 3], rr = roundR[top ? 1 : 2];
            if (rl > e && (top ? ly < ry0 + rl : ly > ry1 - rl)) {
                double dyc = Math.abs(ly - (top ? ry0 + rl : ry1 - rl)), rd = rl - e;
                double q = rd * rd - dyc * dyc;
                if (q < 0) return false;
                xl = Math.max(xl, rx0 + rl - Math.sqrt(q));
            }
            if (rr > e && (top ? ly < ry0 + rr : ly > ry1 - rr)) {
                double dyc = Math.abs(ly - (top ? ry0 + rr : ry1 - rr)), rd = rr - e;
                double q = rd * rd - dyc * dyc;
                if (q < 0) return false;
                xr = Math.min(xr, rx1 - rr + Math.sqrt(q));
            }
            if (xl > xr) return false;
            out[0] = xl; out[1] = xr;
            return true;
        }
    }

    // 記録時（1 スレッド）だけ使う作業表
    private final int[] fastDx0 = new int[Fast.MAX], fastDx1 = new int[Fast.MAX], fastDy0 = new int[Fast.MAX], fastDy1 = new int[Fast.MAX];
    private final int[] fastCol = new int[Fast.MAX];
    private final int[] roundCol = new int[Fast.MAX_ROUND];

    // ------------------------------------------------------------------ 画素を書く命令とタイル実行

    /** 実行時の作業表（区間の表）。タイルを処理するスレッドごとに 1 つ。 */
    private static final class Scratch {
        final int[] a = new int[Fast.MAX + Fast.MAX_ROUND], b = new int[Fast.MAX + Fast.MAX_ROUND], c = new int[Fast.MAX + Fast.MAX_ROUND];
        final double[] out = new double[2];
    }

    /**
     * 画素を書く命令。記録（姿勢・クリップ・範囲の決定。描画先の範囲の更新もここ）は呼び出しスレッドで順に行い、画素への書き込みは
     * {@link #flushPending} が 64×64 のタイルごとに（並列に）、命令の順で実行する。命令同士は同じ画素を触るときだけ順序が効く。
     * 命令は不変の値と純粋な関数だけを持つ（記録後に書き換わる状態を読まない）。
     */
    private abstract static class Op {
        int[] px;
        int w;
        /** 書く範囲（描画先の画素。x1・y1 は含まない）。 */
        int x0, y0, x1, y1;

        /** 範囲 {@code [ax0,ax1) × [ay0,ay1)}（この命令の範囲に収まる）の画素を書く。 */
        abstract void run(int ax0, int ay0, int ax1, int ay1, Scratch s);
    }

    /** タイル単位の遅延実行を使うか（{@code -Denderframe.soft.notiled=true} で無効＝命令ごとにその場で全範囲を実行）。 */
    static volatile boolean tiled = !Boolean.getBoolean("enderframe.soft.notiled");
    static final int TILE = 64;

    private final java.util.ArrayList<Op> pending = new java.util.ArrayList<>();
    private final Scratch scratch = new Scratch();
    // 実行し終えた命令の置き場（次のフレームの命令に作り直さず使う。記録中の命令はどこにも載っていない）
    private final java.util.ArrayDeque<PaintOp> freePaint = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<GradientOp> freeGradient = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<GlyphOp> freeGlyph = new java.util.ArrayDeque<>();

    private PaintOp acquirePaint() {
        PaintOp o = freePaint.pollLast();
        return o != null ? o : new PaintOp();
    }

    private GradientOp acquireGradient() {
        GradientOp o = freeGradient.pollLast();
        return o != null ? o : new GradientOp();
    }

    private GlyphOp acquireGlyph() {
        GlyphOp o = freeGlyph.pollLast();
        return o != null ? o : new GlyphOp();
    }

    /** 実行し終えた命令を、掴んでいる参照（描画先・クリップなど）を手放して置き場へ戻す。 */
    private void release(Op op) {
        op.px = null;
        if (op instanceof PaintOp p) {
            p.clip = null; p.shader = null; p.fast = null; p.pose = null; p.inv = null;
            freePaint.addLast(p);
        } else if (op instanceof GradientOp g) {
            g.clip = null; g.inv = null; g.g = null; g.rx = null; g.ry = null;
            freeGradient.addLast(g);
        } else if (op instanceof GlyphOp g) {
            g.g = null; g.lut = null; g.clip = null;
            freeGlyph.addLast(g);
        }
    }

    /** {@code defer} の命令は溜めてタイルで実行。そうでなければ、溜めた分を先に実行してからその場で全範囲を実行（順序を守る）。 */
    private void submit(Op op, boolean defer) {
        if (defer && tiled) {
            if (!pending.isEmpty() && pending.get(0).px != op.px) flushPending();
            pending.add(op);
            return;
        }
        flushPending();
        op.run(op.x0, op.y0, op.x1, op.y1, scratch);
        release(op);
    }

    /** 溜めた命令を実行して空にする。描画先を読む・切り替える処理、フレームの終わりの前に必ず呼ぶ。 */
    private void flushPending() {
        int n = pending.size();
        if (n == 0) return;
        Op first = pending.get(0);
        int w = first.w, h = first.px.length / w;
        long area = 0;
        for (int i = 0; i < n; i++) {
            Op o = pending.get(i);
            area += (long) (o.x1 - o.x0) * (o.y1 - o.y0);
        }
        if (n == 1 || Runtime.getRuntime().availableProcessors() < 2 || area < Rows.PARALLEL_MIN_PIXELS || !Rows.PARALLEL) {
            for (int i = 0; i < n; i++) {
                Op o = pending.get(i);
                o.run(o.x0, o.y0, o.x1, o.y1, scratch);
            }
            releaseAll();
            return;
        }
        int tilesX = (w + TILE - 1) / TILE, tilesY = (h + TILE - 1) / TILE;
        int[] start = new int[tilesX * tilesY + 1];
        for (int i = 0; i < n; i++) {
            Op o = pending.get(i);
            for (int ty = o.y0 / TILE; ty <= (o.y1 - 1) / TILE; ty++)
                for (int tx = o.x0 / TILE; tx <= (o.x1 - 1) / TILE; tx++) start[ty * tilesX + tx + 1]++;
        }
        for (int t = 0; t < tilesX * tilesY; t++) start[t + 1] += start[t];
        int[] fill = java.util.Arrays.copyOf(start, tilesX * tilesY);
        int[] list = new int[start[tilesX * tilesY]];
        for (int i = 0; i < n; i++) { // 命令の順に入れるので、タイルの中でも命令の順
            Op o = pending.get(i);
            for (int ty = o.y0 / TILE; ty <= (o.y1 - 1) / TILE; ty++)
                for (int tx = o.x0 / TILE; tx <= (o.x1 - 1) / TILE; tx++) list[fill[ty * tilesX + tx]++] = i;
        }
        final java.util.ArrayList<Op> ops = pending;
        java.util.stream.IntStream.range(0, tilesX * tilesY).parallel().forEach(t -> {
            if (start[t] == start[t + 1]) return;
            Scratch s = new Scratch();
            int tx0 = (t % tilesX) * TILE, ty0 = (t / tilesX) * TILE;
            int tx1 = Math.min(w, tx0 + TILE), ty1 = Math.min(h, ty0 + TILE);
            for (int k = start[t]; k < start[t + 1]; k++) {
                Op o = ops.get(list[k]);
                int ax0 = Math.max(o.x0, tx0), ay0 = Math.max(o.y0, ty0), ax1 = Math.min(o.x1, tx1), ay1 = Math.min(o.y1, ty1);
                if (ax1 > ax0 && ay1 > ay0) o.run(ax0, ay0, ax1, ay1, s);
            }
        });
        releaseAll();
    }

    private void releaseAll() {
        for (int i = 0, n = pending.size(); i < n; i++) release(pending.get(i));
        pending.clear();
    }

    /** 形の塗り（シェーダー＋近道の領域）の命令。 */
    private static final class PaintOp extends Op {
        Matrix2D pose, inv;
        Clip clip;
        boolean clipped;
        float opacity;
        Shader shader;
        Fast fast;
        int nr, nRound;
        final int[] dx0 = new int[Fast.MAX], dx1 = new int[Fast.MAX], dy0 = new int[Fast.MAX], dy1 = new int[Fast.MAX], col = new int[Fast.MAX];
        final int[] roundColor = new int[Fast.MAX_ROUND];

        @Override
        void run(int ax0, int ay0, int ax1, int ay1, Scratch s) {
            boolean any = nr > 0 || nRound > 0;
            for (int y = ay0; y < ay1; y++) {
                double dy = y + 0.5;
                if (!any) {
                    paintPixels(px, w, y, dy, ax0, ax1, inv, clip, clipped, opacity, shader);
                    continue;
                }
                // この行の「塗りだけ／飛ばす」区間 [a, b) と色 c（0 は飛ばす）
                int ns = 0;
                for (int k = 0; k < nr; k++) {
                    if (y < dy0[k] || y >= dy1[k]) continue;
                    s.a[ns] = dx0[k]; s.b[ns] = dx1[k]; s.c[ns] = col[k]; ns++;
                }
                for (int k = 0; k < nRound; k++) {
                    if (roundRow(pose, inv, clip, clipped && roundColor[k] != 0, dy, ax0, ax1, fast, k, s.a, s.b, ns, s.out)) {
                        s.c[ns] = roundColor[k]; ns++;
                    }
                }
                for (int i = 1; i < ns; i++) { // 開始位置の昇順（区間は多くて数個）
                    int a = s.a[i], b = s.b[i], c = s.c[i], j = i - 1;
                    while (j >= 0 && s.a[j] > a) { s.a[j + 1] = s.a[j]; s.b[j + 1] = s.b[j]; s.c[j + 1] = s.c[j]; j--; }
                    s.a[j + 1] = a; s.b[j + 1] = b; s.c[j + 1] = c;
                }
                int cur = ax0;
                int base = y * w;
                for (int i = 0; i < ns; i++) {
                    int a = Math.max(s.a[i], cur), b = Math.min(s.b[i], ax1);
                    if (b <= a) continue;
                    if (a > cur) paintPixels(px, w, y, dy, cur, a, inv, clip, clipped, opacity, shader);
                    int c = s.c[i];
                    if (c != 0) {
                        if ((c >>> 24) == 255) java.util.Arrays.fill(px, base + a, base + b, c);
                        else for (int x = a; x < b; x++) px[base + x] = SoftShapes.over(c, px[base + x]);
                    }
                    cur = b;
                }
                if (cur < ax1) paintPixels(px, w, y, dy, cur, ax1, inv, clip, clipped, opacity, shader);
            }
        }
    }

    /**
     * 同上。{@code fast} の領域は、軸平行の姿勢のとき距離の計算なしに行ごとの塗り（または省略）で済ませる（結果は画素ごとの評価と同じ）。
     * 縁・角・クリップの被覆率が 1 と言えない画素だけが従来の経路を通る。{@code fastSolid=false} で無効（照合用）。
     * {@code fast} がある（呼び出し側がシェーダーを純粋と保証する）塗りは、溜めてタイルで実行する。
     */
    private void paint(Matrix2D pose, float bx0, float by0, float bx1, float by1, Clip clip, float opacity, Shader shader,
                       Fast fast) {
        if (opacity <= 0f || !(bx1 > bx0) || !(by1 > by0)) return;
        Matrix2D inv = pose.invert();
        if (inv == null || !inv.isFinite()) return;
        int[] r = paintRange(pose, bx0, by0, bx1, by1, clip);
        if (r == null) return;
        PaintOp op = acquirePaint();
        op.nr = 0;
        op.nRound = 0;
        op.px = target().pixels();
        op.w = target.width();
        op.x0 = r[0]; op.y0 = r[1]; op.x1 = r[2]; op.y1 = r[3];
        op.pose = pose; op.inv = inv; op.clip = clip; op.opacity = opacity; op.shader = shader; op.fast = fast;
        op.clipped = !clip.parts().isEmpty();
        boolean axis = fastSolid && fast != null && pose.b() == 0.0 && pose.c() == 0.0;
        int nr = 0;
        if (axis) {
            for (int k = 0; k < fast.n; k++) {
                int col = fast.color[k] == 0 ? 0 : (opacity < 1f ? SoftShapes.scalePremul(fast.color[k], opacity) : fast.color[k]);
                if (!deviceRange(pose, clip, op.clipped, fast.rect[4 * k], fast.rect[4 * k + 1], fast.rect[4 * k + 2], fast.rect[4 * k + 3],
                        col != 0, r[0], r[1], r[2], r[3], nr)) continue;
                fastCol[nr++] = col;
            }
            for (int k = 0; k < fast.nRound; k++) {
                roundCol[k] = fast.roundColor[k] == 0 ? 0
                        : (opacity < 1f ? SoftShapes.scalePremul(fast.roundColor[k], opacity) : fast.roundColor[k]);
            }
            op.nRound = fast.nRound;
            System.arraycopy(roundCol, 0, op.roundColor, 0, fast.nRound);
        }
        op.nr = nr;
        System.arraycopy(fastDx0, 0, op.dx0, 0, nr); System.arraycopy(fastDx1, 0, op.dx1, 0, nr);
        System.arraycopy(fastDy0, 0, op.dy0, 0, nr); System.arraycopy(fastDy1, 0, op.dy1, 0, nr);
        System.arraycopy(fastCol, 0, op.col, 0, nr);
        submit(op, fast != null);
    }

    /** 局所の矩形の内側の画素の範囲を作業表の {@code slot} に書く。空なら false。色つきの領域はクリップの被覆率 1 の内側に限る。 */
    private boolean deviceRange(Matrix2D pose, Clip clip, boolean clipped, float lx0, float ly0, float lx1, float ly1, boolean painting,
                                int x0, int y0, int x1, int y1, int slot) {
        if (!(lx1 > lx0) || !(ly1 > ly0)) return false;
        double ax = pose.applyX(lx0, 0), bx = pose.applyX(lx1, 0);
        double ay = pose.applyY(0, ly0), by = pose.applyY(0, ly1);
        double lo = Math.min(ax, bx) + 1e-3, hi = Math.max(ax, bx) - 1e-3;
        double lo2 = Math.min(ay, by) + 1e-3, hi2 = Math.max(ay, by) - 1e-3;
        if (clipped && painting) { // クリップの被覆率が 1 と分かっている内側（クリップ自身の判定と同じ比較）
            lo = Math.max(lo, clip.ix0()); hi = Math.min(hi, clip.ix1());
            lo2 = Math.max(lo2, clip.iy0()); hi2 = Math.min(hi2, clip.iy1());
        }
        if (hi < lo || hi2 < lo2) return false;
        int a = Math.max(x0, (int) Math.ceil(lo - 0.5)), b = Math.min(x1, (int) Math.floor(hi - 0.5) + 1);
        int c = Math.max(y0, (int) Math.ceil(lo2 - 0.5)), d = Math.min(y1, (int) Math.floor(hi2 - 0.5) + 1);
        if (b <= a || d <= c) return false;
        fastDx0[slot] = a; fastDx1[slot] = b; fastDy0[slot] = c; fastDy1[slot] = d;
        return true;
    }

    /** 1 行ぶんの角丸矩形の内側の画素区間を {@code a[ns], b[ns]} に書く。無ければ false。 */
    private static boolean roundRow(Matrix2D pose, Matrix2D inv, Clip clip, boolean clipped, double dy, int x0, int x1, Fast fast, int k,
                                    int[] a, int[] b, int ns, double[] out) {
        double ly = inv.applyY(x0 + 0.5, dy);
        if (!fast.roundSpan(k, ly, out)) return false;
        double p = pose.applyX(out[0], 0), q = pose.applyX(out[1], 0);
        double lo = Math.min(p, q) + 1e-3, hi = Math.max(p, q) - 1e-3;
        if (clipped) {
            if (dy < clip.iy0() || dy > clip.iy1()) return false;
            lo = Math.max(lo, clip.ix0()); hi = Math.min(hi, clip.ix1());
        }
        if (hi < lo) return false;
        int s = Math.max(x0, (int) Math.ceil(lo - 0.5)), e = Math.min(x1, (int) Math.floor(hi - 0.5) + 1);
        if (e <= s) return false;
        a[ns] = s; b[ns] = e;
        return true;
    }

    /** 照合・切り分け用: false なら {@link #paint} の行ごとの塗りを使わず、全画素を評価する。 */
    static volatile boolean fastSolid = !Boolean.getBoolean("enderframe.soft.nofastsolid");

    private static void paintPixels(int[] px, int w, int y, double dy, int xa, int xb, Matrix2D inv, Clip clip, boolean clipped,
                                    float opacity, Shader shader) {
        for (int x = xa; x < xb; x++) {
            double dx = x + 0.5;
            int c = shader.shade((float) inv.applyX(dx, dy), (float) inv.applyY(dx, dy));
            if (c == 0) continue;
            float k = opacity;
            if (clipped) {
                k *= clip.coverage(dx, dy);
                if (k <= 0f) continue;
            }
            if (k < 1f) c = SoftShapes.scalePremul(c, k);
            int i = y * w + x;
            px[i] = SoftShapes.over(c, px[i]);
        }
    }

    /** 局所の矩形を姿勢で写してクリップと交わらせた画素の範囲 {@code {x0, y0, x1, y1}}（空なら null）。汚れた範囲も広げる。 */
    private int[] paintRange(Matrix2D pose, float bx0, float by0, float bx1, float by1, Clip clip) {
        double[] b = deviceBounds(pose, bx0, by0, bx1, by1, boundsScratch);
        int x0 = Math.max(clip.x0(), (int) Math.floor(b[0])), y0 = Math.max(clip.y0(), (int) Math.floor(b[1]));
        int x1 = Math.min(clip.x1(), (int) Math.ceil(b[2])), y1 = Math.min(clip.y1(), (int) Math.ceil(b[3]));
        if (x1 <= x0 || y1 <= y0) return null;
        dirtyX0 = Math.min(dirtyX0, x0);
        dirtyY0 = Math.min(dirtyY0, y0);
        dirtyX1 = Math.max(dirtyX1, x1);
        dirtyY1 = Math.max(dirtyY1, y1);
        return new int[]{x0, y0, x1, y1};
    }

    /** 角丸矩形を単色で塗る。 */
    private void fillRoundRect(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                               int argb, Clip clip, float opacity) {
        if ((argb >>> 24) == 0) return;
        if (sink != null) {
            GpuClip[] gc = gpuClips(clip);
            if (gc != null && sink.fillRoundRect(pose, x0, y0, x1, y1, rx, ry, argb, gc, opacity)) return;
        }
        int pm = Pixmap.premultiply(argb);
        float scale = (float) pose.meanScale();
        float m = 1f / Math.max(scale, 0.01f);
        // 縁の 0.5 画素ぶんと角の丸みを除いた内側は被覆率がちょうど 1。そこは距離を測らず塗る
        Fast fast = new Fast();
        fast.roundRect(x0, y0, x1, y1, rx, ry, 0.5f / Math.max(scale, 0.01f) + 1e-3f, pm);
        paint(pose, x0 - m, y0 - m, x1 + m, y1 + m, clip, opacity, (lx, ly) -> {
            float cov = SoftShapes.coverage(SoftShapes.sdRoundRect(lx, ly, x0, y0, x1, y1, rx, ry), scale);
            return cov <= 0f ? 0 : SoftShapes.scalePremul(pm, cov);
        }, fast);
    }

    /** GPU へ渡せるクリップの列。多すぎれば {@code null}（CPU が塗る）。 */
    private static GpuClip[] gpuClips(Clip clip) {
        List<ClipPart> parts = clip.parts();
        if (parts.size() > GpuSink.MAX_CLIPS) return null;
        GpuClip[] out = new GpuClip[parts.size()];
        for (int i = 0; i < out.length; i++) {
            ClipPart p = parts.get(i);
            out[i] = new GpuClip(p.inv(), p.scale(), p.x0(), p.y0(), p.x1(), p.y1(), p.rx(), p.ry());
        }
        return out;
    }

    private static final float[] ZERO4 = new float[4];

    private void fillRect(Matrix2D pose, float x0, float y0, float x1, float y1, int argb, Clip clip, float opacity) {
        fillRoundRect(pose, x0, y0, x1, y1, ZERO4, ZERO4, argb, clip, opacity);
    }

    private static float maxOf(float[] v) {
        float m = 0f;
        for (float f : v) m = Math.max(m, f);
        return m;
    }

    private static float[] uniform(float r) {
        return new float[]{r, r, r, r};
    }

    // ------------------------------------------------------------------ 背景・影・グラデーション

    private void drawBackground(DisplayItem el, AnimatedValues anim, float[] rx, float[] ry,
                                Resolved res, Clip clip, float opacity) {
        RenderHints hints = el.hints();
        float x0 = el.x(), y0 = el.y(), x1 = el.x() + el.width(), y1 = el.y() + el.height();
        if (hints.shadowBlur() > 0f) {
            drawShadow(el, anim, hints, rx, ry, res, clip, opacity);
        } else if (hints.shaderGradient() != null) {
            drawGradient(hints.shaderGradient(), x0, y0, x1, y1, rx, ry, res, clip, opacity);
        } else {
            fillRoundRect(res.pose(), x0, y0, x1, y1, rx, ry, anim.bgColor(), clip, opacity);
        }
    }

    private void drawGradient(ShaderGradient g, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                              Resolved res, Clip clip, float opacity) {
        if (sink != null) {
            GpuClip[] gc = gpuClips(clip);
            if (gc != null && sink.fillGradient(res.pose(), x0, y0, x1, y1, rx, ry, g, gc, opacity)) return;
        }
        float scale = (float) res.pose().meanScale();
        float m = 1f / Math.max(scale, 0.01f);
        // 角の丸みと縁の半画素から十分に内側なら被覆率は 1（距離の計算を省ける）。
        float reach = 0.5f / Math.max(scale, 0.01f) + 1e-3f;
        float ix0 = x0 + maxOf(rx) + reach, ix1 = x1 - maxOf(rx) - reach;
        float iy0 = y0 + maxOf(ry) + reach, iy1 = y1 - maxOf(ry) - reach;
        Matrix2D pose = res.pose();
        if (opacity <= 0f) return;
        Matrix2D inv = pose.invert();
        if (inv == null || !inv.isFinite()) return;
        int[] r = paintRange(pose, x0 - m, y0 - m, x1 + m, y1 + m, clip);
        if (r == null) return;
        int[] px = target().pixels();
        int w = target.width();
        boolean clipped = !clip.parts().isEmpty();
        final int rx0 = r[0], rx1 = r[2], ry0 = r[1];
        // 軸平行の姿勢では局所の x は列だけ、y は行だけで決まる（逆変換を画素ごとにしない）。被覆率 1 の内側の列の範囲 [spLo, spHi) と、
        // 内側の行（行ごとに判定）を作り、その中は被覆率・クリップの計算を省く。範囲の外と姿勢が斜めのときは従来の画素ごとの経路。
        GradientOp op = acquireGradient();
        op.hasCol = false;
        op.spLo = op.spHi = 0;
        op.px = px; op.w = w;
        op.x0 = r[0]; op.y0 = r[1]; op.x1 = r[2]; op.y1 = r[3];
        op.inv = inv; op.clip = clip; op.clipped = clipped; op.opacity = opacity; op.g = g;
        op.sx0 = x0; op.sy0 = y0; op.sx1 = x1; op.sy1 = y1; op.rx = rx; op.ry = ry; op.scale = scale;
        op.fIx0 = ix0; op.fIx1 = ix1; op.fIy0 = iy0; op.fIy1 = iy1;
        op.axis = fastSolid && pose.b() == 0.0 && pose.c() == 0.0;
        int lo = 0, hi = 0;
        if (op.axis) {
            float[] lxs = op.lxs != null && op.lxs.length >= rx1 - rx0 ? op.lxs : new float[rx1 - rx0];
            float[] lys = op.lys != null && op.lys.length >= r[3] - ry0 ? op.lys : new float[r[3] - ry0];
            for (int x = rx0; x < rx1; x++) lxs[x - rx0] = (float) inv.applyX(x + 0.5, ry0 + 0.5);
            for (int y = ry0; y < r[3]; y++) lys[y - ry0] = (float) inv.applyY(rx0 + 0.5, y + 0.5);
            int n = 0;
            lo = Integer.MAX_VALUE; hi = Integer.MIN_VALUE;
            for (int x = rx0; x < rx1; x++) {
                float lx = lxs[x - rx0];
                double dx = x + 0.5;
                if (lx >= ix0 && lx <= ix1 && (!clipped || (dx >= clip.ix0() && dx <= clip.ix1()))) { lo = Math.min(lo, x); hi = Math.max(hi, x + 1); n++; }
            }
            if (n == 0 || hi - lo != n) { lo = hi = 0; } // 空、または連続でない（想定外）: 範囲の近道を使わない
            op.lxs = lxs; op.lys = lys;
            // 色が列の中で一定の線形グラデーションは、色を列ごとに 1 回だけ求める
            if (hi > lo && ShaderGradientEmulator.prepare(g).constantAlongColumns()) {
                ShaderGradientEmulator.Prepared sh0 = ShaderGradientEmulator.prepare(g);
                if (op.colFinal == null || op.colFinal.length < hi - lo) op.colFinal = new int[hi - lo];
                op.hasCol = true;
                for (int x = lo; x < hi; x++) op.colFinal[x - lo] = spanColor(sh0.colorOrZero(lxs[x - rx0], lys[0]), opacity);
            }
        }
        op.spLo = lo; op.spHi = hi;
        submit(op, true); // 行は互いに独立（書き込む画素も別）。タイルごとに並列で塗る
    }

    /** グラデーションで形を塗る命令。 */
    private static final class GradientOp extends Op {
        Matrix2D inv;
        Clip clip;
        boolean clipped, axis;
        float opacity, sx0, sy0, sx1, sy1, scale, fIx0, fIx1, fIy0, fIy1;
        ShaderGradient g;
        float[] rx, ry, lxs, lys;
        int spLo, spHi;
        int[] colFinal;
        boolean hasCol;

        @Override
        void run(int ax0, int ay0, int ax1, int ay1, Scratch s) {
            ShaderGradientEmulator.Prepared shader = ShaderGradientEmulator.prepare(g); // 直前の色を覚えるのでスレッドごと
            boolean rowConst = axis && shader.constantAlongRows();
            int a = Math.max(ax0, Math.min(ax1, spLo)), b = Math.max(ax0, Math.min(ax1, spHi)); // 内側の列の範囲をタイルに収める
            for (int y = ay0; y < ay1; y++) {
                double dy = y + 0.5;
                if (axis && spHi > spLo) {
                    float lyRow = lys[y - y0];
                    if (lyRow >= fIy0 && lyRow <= fIy1 && (!clipped || (dy >= clip.iy0() && dy <= clip.iy1()))) {
                        int base = y * w;
                        paintGradientPixels(px, w, y, dy, ax0, a, inv, clip, clipped, opacity, shader, sx0, sy0, sx1, sy1, rx, ry, scale, fIx0, fIx1, fIy0, fIy1);
                        if (hasCol) {
                            for (int x = a; x < b; x++) {
                                int c = colFinal[x - spLo];
                                if (c != 0) px[base + x] = SoftShapes.over(c, px[base + x]);
                            }
                        } else if (rowConst) {
                            if (b > a) {
                                int c = spanColor(shader.colorOrZero(lxs[a - x0], lyRow), opacity);
                                if (c != 0) {
                                    if ((c >>> 24) == 255) java.util.Arrays.fill(px, base + a, base + b, c);
                                    else for (int x = a; x < b; x++) px[base + x] = SoftShapes.over(c, px[base + x]);
                                }
                            }
                        } else {
                            for (int x = a; x < b; x++) {
                                int c = spanColor(shader.colorOrZero(lxs[x - x0], lyRow), opacity);
                                if (c != 0) px[base + x] = SoftShapes.over(c, px[base + x]);
                            }
                        }
                        paintGradientPixels(px, w, y, dy, b, ax1, inv, clip, clipped, opacity, shader, sx0, sy0, sx1, sy1, rx, ry, scale, fIx0, fIx1, fIy0, fIy1);
                        continue;
                    }
                }
                paintGradientPixels(px, w, y, dy, ax0, ax1, inv, clip, clipped, opacity, shader, sx0, sy0, sx1, sy1, rx, ry, scale, fIx0, fIx1, fIy0, fIy1);
            }
        }
    }

    /** 被覆率 1・クリップ 1 の画素の最終の色（事前乗算・不透明度込み）。0 は塗らない。画素ごとの経路と同じ式。 */
    private static int spanColor(int straight, float opacity) {
        if ((straight >>> 24) == 0) return 0;
        int c = SoftShapes.scalePremul(Pixmap.premultiply(straight), 1f);
        if (c == 0) return 0;
        return opacity < 1f ? SoftShapes.scalePremul(c, opacity) : c;
    }

    private static void paintGradientPixels(int[] px, int w, int y, double dy, int xa, int xb, Matrix2D inv, Clip clip, boolean clipped,
                                            float opacity, ShaderGradientEmulator.Prepared shader,
                                            float x0, float y0, float x1, float y1, float[] rx, float[] ry, float scale,
                                            float ix0, float ix1, float iy0, float iy1) {
        for (int x = xa; x < xb; x++) {
            double dx = x + 0.5;
            float lx = (float) inv.applyX(dx, dy), ly = (float) inv.applyY(dx, dy);
            float cov = lx >= ix0 && lx <= ix1 && ly >= iy0 && ly <= iy1 ? 1f
                    : SoftShapes.coverage(SoftShapes.sdRoundRect(lx, ly, x0, y0, x1, y1, rx, ry), scale);
            if (cov <= 0f) continue;
            int c = shader.colorOrZero(lx, ly);
            if ((c >>> 24) == 0) continue;
            c = SoftShapes.scalePremul(Pixmap.premultiply(c), cov);
            if (c == 0) continue;
            float k = opacity;
            if (clipped) {
                k *= clip.coverage(dx, dy);
                if (k <= 0f) continue;
            }
            if (k < 1f) c = SoftShapes.scalePremul(c, k);
            int i = y * w + x;
            px[i] = SoftShapes.over(c, px[i]);
        }
    }

    /**
     * ぼかした影。要素の矩形は影が消える所までの広さで、外側の影はそこから {@link BoxDecorationPainter#SHADOW_REACH} ×
     * ぼかし半径だけ内側が影の形、箱そのものは影を抜く穴。内側の影は穴の外を塗る。濃さはガウスぼかし（標準偏差 = 半径 / 2）。
     */
    private void drawShadow(DisplayItem el, AnimatedValues anim, RenderHints hints, float[] rx, float[] ry,
                            Resolved res, Clip clip, float opacity) {
        int color = anim.bgColor();
        if ((color >>> 24) == 0) return;
        int pm = Pixmap.premultiply(color);
        float blur = hints.shadowBlur();
        float sigma = blur / 2f;
        float scale = (float) res.pose().meanScale();
        float qx0 = el.x(), qy0 = el.y(), qx1 = el.x() + el.width(), qy1 = el.y() + el.height();
        ShadowShape shape = hints.shadowShape();

        if (shape != null && shape.inset()) {
            float[] hrx = {shape.rTL(), shape.rTR(), shape.rBR(), shape.rBL()};
            float[] hry = {shape.rTLv(), shape.rTRv(), shape.rBRv(), shape.rBLv()};
            if (sink != null) {
                GpuClip[] gc = gpuClips(clip);
                if (gc != null && sink.insetShadow(res.pose(), qx0, qy0, qx1, qy1, rx, ry, sigma,
                        new float[]{shape.x0(), shape.y0(), shape.x1(), shape.y1()}, hrx, hry, color, gc, opacity)) return;
            }
            paint(res.pose(), qx0 - 1, qy0 - 1, qx1 + 1, qy1 + 1, clip, opacity, (lx, ly) -> {
                float inRect = SoftShapes.coverage(SoftShapes.sdRoundRect(lx, ly, qx0, qy0, qx1, qy1, rx, ry), scale);
                if (inRect <= 0f) return 0;
                float sd = SoftShapes.sdRoundRect(lx, ly, shape.x0(), shape.y0(), shape.x1(), shape.y1(), hrx, hry);
                float a = SoftShapes.blurredEdge(-sd, sigma);
                return a <= 0f ? 0 : SoftShapes.scalePremul(pm, a * inRect);
            });
            return;
        }

        float reach = BoxDecorationPainter.SHADOW_REACH * blur;
        float sx0 = qx0 + reach, sy0 = qy0 + reach, sx1 = qx1 - reach, sy1 = qy1 - reach;
        if (sx1 <= sx0 || sy1 <= sy0) return;
        float limit = Math.min(sx1 - sx0, sy1 - sy0) / 2f;
        float[] srx = new float[4], sry = new float[4];
        for (int i = 0; i < 4; i++) {
            srx[i] = Math.min(rx[i], limit);
            sry[i] = Math.min(ry[i], limit);
        }
        float[] hrx = null, hry = null;
        if (shape != null) {
            hrx = new float[]{shape.rTL(), shape.rTR(), shape.rBR(), shape.rBL()};
            hry = new float[]{shape.rTLv(), shape.rTRv(), shape.rBRv(), shape.rBLv()};
        }
        final float[] holeRx = hrx, holeRy = hry;
        if (sink != null) {
            GpuClip[] gc = gpuClips(clip);
            float[] hole = shape != null ? new float[]{shape.x0(), shape.y0(), shape.x1(), shape.y1()} : null;
            if (gc != null && sink.outerShadow(res.pose(), qx0, qy0, qx1, qy1, sx0, sy0, sx1, sy1, srx, sry, sigma,
                    hole, holeRx, holeRy, color, gc, opacity)) return;
        }
        paint(res.pose(), qx0, qy0, qx1, qy1, clip, opacity, (lx, ly) -> {
            float a = SoftShapes.blurredEdge(SoftShapes.sdRoundRect(lx, ly, sx0, sy0, sx1, sy1, srx, sry), sigma);
            if (a <= 0f) return 0;
            if (shape != null) {
                float hole = SoftShapes.coverage(SoftShapes.sdRoundRect(lx, ly, shape.x0(), shape.y0(), shape.x1(), shape.y1(), holeRx, holeRy), scale);
                a *= 1f - hole;
            }
            return a <= 0f ? 0 : SoftShapes.scalePremul(pm, a);
        });
    }

    // ------------------------------------------------------------------ 枠線

    private void drawRoundedBorder(DisplayItem el, RoundedBorderSpec spec, float[] rx, float[] ry,
                                   Resolved res, Clip clip, float opacity) {
        float x0 = el.x(), y0 = el.y(), x1 = el.x() + el.width(), y1 = el.y() + el.height();
        float wt = spec.widthTop(), wr = spec.widthRight(), wb = spec.widthBottom(), wl = spec.widthLeft();
        float ix0 = x0 + wl, iy0 = y0 + wt, ix1 = x1 - wr, iy1 = y1 - wb;
        boolean innerEmpty = ix1 <= ix0 || iy1 <= iy0;
        float[] irx = new float[4], iry = new float[4];
        float[] sideH = {wl, wr, wr, wl}, sideV = {wt, wt, wb, wb};
        for (int i = 0; i < 4; i++) {
            irx[i] = Math.max(0f, rx[i] - sideH[i]);
            iry[i] = Math.max(0f, ry[i] - sideV[i]);
            if (irx[i] <= 0f || iry[i] <= 0f) irx[i] = iry[i] = 0f;
        }
        int[] colors = new int[4];
        for (int i = 0; i < 4; i++) colors[i] = Pixmap.premultiply(spec.color(i));
        float[] widths = {wt, wr, wb, wl};

        // 破線・点線は 4 辺が同じ太さのときだけ（GL 版と同じ）
        BorderDashPattern pattern = null;
        float bx = 0, by = 0, cxm = (x0 + x1) / 2f, cym = (y0 + y1) / 2f;
        float[] pcx = new float[4], pcy = new float[4];
        if (spec.dashKind() != BorderPath.SOLID && wt > 0f && wt == wr && wr == wb && wb == wl) {
            bx = Math.max(0.001f, el.width() / 2f - wt / 2f);
            by = Math.max(0.001f, el.height() / 2f - wt / 2f);
            for (int i = 0; i < 4; i++) {
                pcx[i] = Math.min(Math.max(rx[i] - wt / 2f, 0f), bx);
                pcy[i] = Math.min(Math.max(ry[i] - wt / 2f, 0f), by);
                if (pcx[i] <= 0f || pcy[i] <= 0f) pcx[i] = pcy[i] = 0f;
            }
            pattern = BorderPath.pattern(spec.dashKind(), wt, BorderPath.length(bx, by, pcx, pcy));
        }
        final BorderDashPattern dash = pattern;
        if (sink != null && (dash != null || spec.dashKind() == BorderPath.SOLID)) {
            GpuClip[] gc = gpuClips(clip);
            if (gc != null) {
                int[] straight = {spec.color(0), spec.color(1), spec.color(2), spec.color(3)};
                if (dash == null) {
                    if (sink.strokeRoundBorder(res.pose(), x0, y0, x1, y1, rx, ry, ix0, iy0, ix1, iy1, irx, iry, innerEmpty,
                            straight, widths, gc, opacity)) return;
                } else if (sink.strokeDashedBorder(res.pose(), x0, y0, x1, y1, rx, ry, ix0, iy0, ix1, iy1, irx, iry, innerEmpty,
                        straight, widths, bx, by, cxm, cym, pcx, pcy, dash.period(), dash.dash(), dash.round(), wt,
                        gc, opacity)) return;
            }
        }
        final float fbx = bx, fby = by;
        final float thickness = wt;

        float scale = (float) res.pose().meanScale();
        float m = 1f / Math.max(scale, 0.01f);
        // 距離を測らずに済む領域。穴（内側の角丸矩形から角の最大半径と縁の 0.5 画素を除いた内側）は内側の被覆率がちょうど 1 で何も塗らない。
        // 4 辺の直線部分は、外側の被覆率が 1・内側が 0 で、辺の色の判定も一意（その辺の比が 1 未満、ほかの辺は 1 以上）な帯
        Fast fast = new Fast();
        if (!innerEmpty) {
            float reach = 0.5f / Math.max(scale, 0.01f) + 1e-3f;
            float mx = maxOf(irx), my = maxOf(iry);
            fast.roundRect(ix0, iy0, ix1, iy1, irx, iry, reach, 0); // 穴（円の角なら行ごとに、そうでなければ矩形で）
            boolean circles = true; // 円の角だけ（楕円の近似距離では「枠の外ならいくら以上」を保証しない）
            float hx = (x1 - x0) * 0.5f, hy = (y1 - y0) * 0.5f, ihx = (ix1 - ix0) * 0.5f, ihy = (iy1 - iy0) * 0.5f;
            for (int i = 0; i < 4; i++) {
                if (rx[i] != ry[i] || rx[i] > hx || ry[i] > hy || irx[i] != iry[i] || irx[i] > ihx || iry[i] > ihy) circles = false;
            }
            if (dash == null && circles) {
                float e = reach + 2e-3f;
                fast.rect(x0 + Math.max(rx[0], wl) + e, y0 + e, x1 - Math.max(rx[1], wr) - e, iy0 - e, colors[0]);
                fast.rect(ix1 + e, y0 + Math.max(ry[1], wt) + e, x1 - e, y1 - Math.max(ry[2], wb) - e, colors[1]);
                fast.rect(x0 + Math.max(rx[3], wl) + e, iy1 + e, x1 - Math.max(rx[2], wr) - e, y1 - e, colors[2]);
                fast.rect(x0 + e, y0 + Math.max(ry[0], wt) + e, ix0 - e, y1 - Math.max(ry[3], wb) - e, colors[3]);
            }
        }
        paint(res.pose(), x0 - m, y0 - m, x1 + m, y1 + m, clip, opacity, (lx, ly) -> {
            float outer = SoftShapes.coverage(SoftShapes.sdRoundRect(lx, ly, x0, y0, x1, y1, rx, ry), scale);
            if (outer <= 0f) return 0;
            float inner = innerEmpty ? 0f
                    : SoftShapes.coverage(SoftShapes.sdRoundRect(lx, ly, ix0, iy0, ix1, iy1, irx, iry), scale);
            float cov = outer * (1f - inner);
            if (cov <= 0f) return 0;
            // 辺の色: 外側の角と内側の角を結ぶ線で辺を分ける（辺からの距離 / 太さが小さい辺）
            int side = -1;
            float best = Float.MAX_VALUE;
            if (widths[0] > 0f) { float v = (ly - y0) / widths[0]; if (v < best) { best = v; side = 0; } }
            if (widths[1] > 0f) { float v = (x1 - lx) / widths[1]; if (v < best) { best = v; side = 1; } }
            if (widths[2] > 0f) { float v = (y1 - ly) / widths[2]; if (v < best) { best = v; side = 2; } }
            if (widths[3] > 0f) { float v = (lx - x0) / widths[3]; if (v < best) { best = v; side = 3; } }
            if (side < 0) return 0;
            int c = colors[side];
            if (dash != null) {
                float s = BorderPath.param(lx - cxm, ly - cym, fbx, fby, pcx, pcy);
                float phase = s - dash.period() * (float) Math.floor(s / dash.period());
                float along = Math.abs(phase - dash.period() / 2f);
                float on;
                if (dash.round()) {
                    float perp = Math.abs(SoftShapes.sdRoundRect(lx, ly, x0, y0, x1, y1, rx, ry) + thickness / 2f);
                    on = Math.clamp(dash.dash() / 2f - (float) Math.hypot(along, perp) + 0.5f, 0f, 1f);
                } else {
                    on = Math.clamp(dash.dash() / 2f - along + 0.5f, 0f, 1f);
                }
                cov *= on;
                if (cov <= 0f) return 0;
            }
            return SoftShapes.scalePremul(c, cov);
        }, fast);
    }

    /** 円の枠線（円の外半径 = 短い辺の半分、4 方向で色が違ってよい）。描いたら true。 */
    private boolean drawCircleBorder(DisplayItem el, AnimatedValues anim, Resolved res, Clip clip, float opacity) {
        CircleBorderSpec cb = el.meta() != null ? el.meta().circleBorder() : null;
        if (cb == null && anim.circleColors == null) return false;
        int[] colors = new int[4];
        float thick;
        if (cb != null) {
            colors[0] = cb.colorTop();
            colors[1] = cb.colorRight();
            colors[2] = cb.colorBottom();
            colors[3] = cb.colorLeft();
            thick = cb.thickness();
        } else {
            System.arraycopy(anim.circleColors, 0, colors, 0, 4);
            thick = anim.circleThickness;
        }
        int[] straightColors = colors.clone();
        for (int i = 0; i < 4; i++) colors[i] = Pixmap.premultiply(colors[i]);
        float cx = el.x() + el.width() / 2f, cy = el.y() + el.height() / 2f;
        float outerR = Math.min(el.width(), el.height()) / 2f;
        float innerR = outerR - thick;
        if (sink != null) {
            GpuClip[] gc = gpuClips(clip);
            if (gc != null && sink.strokeCircleBorder(res.pose(), cx, cy, outerR, innerR, el.x() - 1, el.y() - 1,
                    el.x() + el.width() + 1, el.y() + el.height() + 1, straightColors, gc, opacity)) return true;
        }
        float scale = (float) res.pose().meanScale();
        paint(res.pose(), el.x() - 1, el.y() - 1, el.x() + el.width() + 1, el.y() + el.height() + 1, clip, opacity, (lx, ly) -> {
            float dx = lx - cx, dy = ly - cy;
            float dist = (float) Math.hypot(dx, dy);
            float cov = SoftShapes.coverage(dist - outerR, scale) * (innerR > 0f ? 1f - SoftShapes.coverage(dist - innerR, scale) : 1f);
            if (cov <= 0f) return 0;
            int side = Math.abs(dy) >= Math.abs(dx) ? (dy < 0 ? 0 : 2) : (dx > 0 ? 1 : 3);
            return SoftShapes.scalePremul(colors[side], cov);
        });
        return true;
    }

    // ------------------------------------------------------------------ 文字

    /**
     * 字形キャッシュの鍵。引くときは作業用の 1 個を書き換えて使い回し（字ごとの割り当てを避ける）、登録するときだけ複製する。
     * 登録済みの鍵は書き換えない。
     */
    private static final class GlyphKey {
        LoadedFace face;
        int codePoint;
        float pixelSize;
        boolean bold, italic, light;
        int subpixel;

        GlyphKey set(LoadedFace face, int codePoint, float pixelSize, boolean bold, boolean italic, int subpixel, boolean light) {
            this.face = face;
            this.codePoint = codePoint;
            this.pixelSize = pixelSize;
            this.bold = bold;
            this.italic = italic;
            this.subpixel = subpixel;
            this.light = light;
            return this;
        }

        GlyphKey copy() {
            return new GlyphKey().set(face, codePoint, pixelSize, bold, italic, subpixel, light);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof GlyphKey k)) return false;
            return face.equals(k.face) && codePoint == k.codePoint && Float.compare(pixelSize, k.pixelSize) == 0
                    && bold == k.bold && italic == k.italic && subpixel == k.subpixel && light == k.light;
        }

        @Override
        public int hashCode() {
            int h = face.hashCode();
            h = 31 * h + codePoint;
            h = 31 * h + Float.floatToIntBits(pixelSize);
            h = 31 * h + (bold ? 1 : 0) + (italic ? 2 : 0) + (light ? 4 : 0);
            return 31 * h + subpixel;
        }
    }

    private final GlyphKey probeKey = new GlyphKey();

    private OutlineFontFace faceFor(DisplayItem el) {
        String family = el.events() != null ? el.events().get("font-family") : null;
        FontFace f = resources.fonts().resolve(family);
        if (f instanceof OutlineFontFace o) return o;
        f = SystemFonts.system().resolve(family);
        return f instanceof OutlineFontFace o ? o : null;
    }

    private static FontStyle styleFor(DisplayItem el, float fontPx) {
        Map<String, String> ev = el.events() != null ? el.events() : Map.of();
        int weight = LayoutMetrics.numericWeight(ev.get("font-weight"));
        String style = ev.getOrDefault("font-style", "normal").trim();
        float letterSpacing = 0f;
        try {
            letterSpacing = Float.parseFloat(ev.getOrDefault("letter-spacing", "0"));
        } catch (NumberFormatException ignored) {
        }
        boolean kerning = !"none".equalsIgnoreCase(ev.getOrDefault("font-kerning", "auto").trim());
        return new FontStyle(fontPx, weight >= 600, !style.equalsIgnoreCase("normal"), letterSpacing, weight, kerning);
    }

    private boolean skipPlaceholder(DisplayItem el) {
        if (!el.isPart("_ph")) return false;
        String nodeId = el.id().substring(0, el.id().length() - 3);
        DisplayItem input = driver.itemById(nodeId + "_input");
        return input != null && input.text() != null && !input.text().isEmpty();
    }

    private void drawText(DisplayItem el, AnimatedValues anim, Resolved res, Clip clip, float opacity) {
        if (skipPlaceholder(el)) return;
        String text = el.text() == null ? "" : el.text().replace((char) 0xA0, ' ');
        boolean field = el.type() == ElementType.INPUT || el.type() == ElementType.SELECT;
        if (!field && text.isEmpty()) return;
        OutlineFontFace face = faceFor(el);
        RenderMeta meta = el.meta();
        if (face == null) return;
        float fontPx = meta != null && meta.fontHeight() > 0f ? meta.fontHeight() : Math.max(1f, el.height());
        FontStyle style = styleFor(el, fontPx);
        float ascent = face.ascent(style), descent = face.descent(style);
        boolean centers = field || el.isPart("_ph") || meta == null || meta.baseline() <= 0f;
        float baseline = el.y() + (centers ? (el.height() + ascent - descent) / 2f : meta.baseline());
        float textX = el.x() + (field ? 4f : 0f);

        int alpha = (int) (((anim.color() >>> 24) & 0xFF));
        Stroke stroke = textStroke(el);
        if (alpha < 4 && stroke == null) return;
        int argb = (alpha << 24) | (anim.color() & 0x00FFFFFF);

        String shown = text;
        if (field || el.isPart("_ph")) {
            float available = Math.max(0f, el.width() - 8f);
            shown = text.substring(0, face.fitChars(text, style, available));
        }
        boolean shadow = el.events() != null && "true".equals(el.events().get("text-shadow"));
        drawRun(shown, face, style, textX, baseline, argb, shadow, res, clip, opacity, alpha >= 4, stroke);
        if (alpha < 4) return; // 塗りが透明（縁取りだけの文字）なら、下線などは付けない

        String deco = el.events() != null ? el.events().getOrDefault("text-decoration", "none") : "none";
        if (!field && !el.isPart("_ph") && (deco.contains("underline") || deco.contains("line-through"))) {
            LoadedFace primary = face.primaryFace(style);
            float size = style.fontSizePx();
            if (deco.contains("underline")) {
                float top = baseline + primary.underlineOffset(size);
                fillRect(res.pose(), textX, top, textX + el.width(), top + primary.underlineThickness(size), argb, clip, opacity);
            }
            if (deco.contains("line-through")) {
                float thick = primary.strikeoutThickness(size);
                float top = baseline - primary.strikeoutPosition(size) - thick / 2f;
                fillRect(res.pose(), textX, top, textX + el.width(), top + thick, argb, clip, opacity);
            }
        }
    }

    private void drawTextarea(DisplayItem el, AnimatedValues anim, Resolved res, Clip clip, float opacity) {
        String full = el.text() == null ? "" : el.text();
        if (full.isEmpty()) return;
        OutlineFontFace face = faceFor(el);
        if (face == null) return;
        RenderMeta meta = el.meta() != null ? el.meta() : RenderMeta.EMPTY;
        FontStyle style = styleFor(el, meta.fontHeight() > 0f ? meta.fontHeight() : 16f);
        float lineHeight = meta.resolvedLineHeight();
        float ascent = face.ascent(style), descent = face.descent(style);
        int alpha = (anim.color() >>> 24) & 0xFF;
        if (alpha < 4) return;
        // 箱の外へは出さない
        List<ClipPart> parts = new ArrayList<>(clip.parts());
        addClipPart(parts, new ArrayList<>(1), new ArrayList<>(1), new float[]{el.x(), el.y(), el.x() + el.width(), el.y() + el.height(), 0, 0, 0, 0}, null);
        Clip boxed = finishClip(parts);
        String[] lines = full.replace("\r\n", "\n").split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            float top = el.y() + 4f + i * lineHeight;
            if (top > el.y() + el.height()) break;
            if (lines[i].isEmpty()) continue;
            float baseline = top + (lineHeight + ascent - descent) / 2f;
            drawRun(lines[i], face, style, el.x() + 4f, baseline, anim.color(), false, res, boxed, opacity);
        }
    }

    private static boolean isLight(int argb) {
        float luma = (0.2126f * ((argb >>> 16) & 0xFF) + 0.7152f * ((argb >>> 8) & 0xFF) + 0.0722f * (argb & 0xFF)) / 255f;
        return luma >= 0.4f;
    }

    /** 1 行の文字を描く。影があれば 1/9 em ずらして 1/4 の明るさで先に描く（GL 版と同じ）。 */
    private void drawRun(String text, OutlineFontFace face, FontStyle style, float x, float baseline, int argb, boolean shadow,
                         Resolved res, Clip clip, float opacity) {
        drawRun(text, face, style, x, baseline, argb, shadow, res, clip, opacity, true, null);
    }

    /**
     * @param fill   塗りを描くか（縁取りだけの文字では false）
     * @param stroke 縁取り（幅は CSS px、色は非乗算 ARGB）。null なら縁取りなし。塗りの上に重ねる
     */
    private void drawRun(String text, OutlineFontFace face, FontStyle style, float x, float baseline, int argb, boolean shadow,
                         Resolved res, Clip clip, float opacity, boolean fill, Stroke stroke) {
        if (text == null || text.isEmpty()) return;
        text = BidiText.toVisual(text, null);
        float[] positions = face.positions(text, style);
        if (!fill) {
            drawGlyphStrokes(text, positions, face, style, x, baseline, stroke.argb(), stroke.width(), res.pose(), clip, opacity);
            return;
        }
        if (shadow) {
            float off = style.fontSizePx() / 9f;
            int shadowColor = (argb & 0xFF000000) | ((argb & 0xFCFCFC) >> 2);
            drawGlyphs(text, positions, face, style, x + off, baseline + off, shadowColor, res.pose(), clip, opacity);
        }
        drawGlyphs(text, positions, face, style, x, baseline, argb, res.pose(), clip, opacity);
        if (stroke != null) {
            drawGlyphStrokes(text, positions, face, style, x, baseline, stroke.argb(), stroke.width(), res.pose(), clip, opacity);
        }
    }

    /** {@code text-stroke} イベント（{@code "<幅> <色の 16 進>"}）。無い・幅が 0 以下なら null。 */
    private record Stroke(float width, int argb) {}

    private static Stroke textStroke(DisplayItem el) {
        String v = el.events() != null ? el.events().get("text-stroke") : null;
        if (v == null) return null;
        int sp = v.indexOf(' ');
        if (sp < 0) return null;
        try {
            float w = Float.parseFloat(v.substring(0, sp));
            int color = (int) Long.parseLong(v.substring(sp + 1), 16);
            return w > 0f ? new Stroke(w, color) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 文字の縁取り。輪郭からの符号付き距離（SDF の字形）が {@code |d| <= 幅/2} の帯を、1 画素の傾斜でなめらかに塗る
     * （{@code -webkit-text-stroke} と同じ、輪郭の上に中心を置く）。幅は SDF の広がり（{@link LoadedFace#SDF_SPREAD}）で頭打ち。
     */
    private void drawGlyphStrokes(String text, float[] positions, OutlineFontFace face, FontStyle style, float x, float baseline,
                                  int argb, float widthCss, Matrix2D pose, Clip clip, float opacity) {
        boolean aligned = Math.abs(pose.b()) < 1e-9 && Math.abs(pose.c()) < 1e-9 && pose.a() > 0 && Math.abs(pose.a() - pose.d()) < 1e-6;
        float pixelSize = quantize((float) (style.fontSizePx() * pose.meanScale()));
        if (pixelSize < 0.5f) return;
        float half = Math.min(widthCss * (float) pose.meanScale() / 2f, LoadedFace.SDF_SPREAD - 1.5f);
        if (half <= 0.01f) return;
        int pm = Pixmap.premultiply(argb);
        if ((argb >>> 24) < 4) return;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int n = Character.charCount(cp);
            if (cp <= 0x20 || cp == 0xA0 || Character.isWhitespace(cp)) {
                i += n;
                continue;
            }
            OutlineFontFace.GlyphFace gf = face.glyphFace(cp, style);
            float gx = x + positions[i];
            if (aligned) {
                double devX = pose.applyX(gx, baseline), devY = pose.applyY(gx, baseline);
                double whole = Math.floor(devX);
                int sub = (int) Math.round((devX - whole) * LoadedFace.SUBPIXEL_STEPS);
                if (sub == LoadedFace.SUBPIXEL_STEPS) {
                    whole += 1;
                    sub = 0;
                }
                GlyphBitmap sdf = sdfGlyph(gf, cp, pixelSize, sub);
                if (!sdf.isEmpty() && !strokeOnGpu(sdf, (float) whole, Math.round(devY), half, argb, clip, opacity)) {
                    blitStrokeAligned(sdf, (float) whole, Math.round(devY), half, pm, clip, opacity);
                }
            } else {
                GlyphBitmap sdf = sdfGlyph(gf, cp, pixelSize, 0);
                if (!sdf.isEmpty()) blitStrokeTransformed(sdf, gx, baseline, pixelSize / style.fontSizePx(), pose, half, pm, clip, opacity);
            }
            i += n;
        }
    }

    private boolean strokeOnGpu(GlyphBitmap sdf, float originX, float originY, float half, int argb, Clip clip, float opacity) {
        if (sink == null) return false;
        if (opacity <= 0f) return true;
        GpuClip[] gc = gpuClips(clip);
        if (gc == null) return false;
        return sink.drawGlyphStroke(sdf, Math.round(originX + sdf.left()), Math.round(originY - sdf.top()), half, argb, gc, opacity);
    }

    private GlyphBitmap sdfGlyph(OutlineFontFace.GlyphFace gf, int cp, float pixelSize, int sub) {
        GlyphKey key = probeKey.set(gf.face(), cp, pixelSize, gf.syntheticBold(), gf.syntheticItalic(), sub, false);
        GlyphBitmap g = sdfGlyphs.get(key);
        if (g == null) {
            g = gf.face().rasterizeSdf(cp, pixelSize, gf.syntheticBold(), gf.syntheticItalic(), sub);
            if (sdfGlyphs.size() >= GLYPH_CACHE_LIMIT) sdfGlyphs.clear();
            sdfGlyphs.put(key.copy(), g);
        }
        return g;
    }

    /** SDF の値（128 = 輪郭、内側が大きい、{@code SDF_SPREAD} 画素で 128 段階）→ 縁取りの被覆率。 */
    private static float strokeCoverage(float sdfValue, float half) {
        float d = (sdfValue - 128f) * (LoadedFace.SDF_SPREAD / 128f); // 画素（内側が正）
        float c = half - Math.abs(d) + 0.5f;
        return c <= 0f ? 0f : Math.min(1f, c);
    }

    private void blitStrokeAligned(GlyphBitmap g, float originX, float originY, float half, int pm, Clip clip, float opacity) {
        float bx = originX + g.left(), by = originY - g.top();
        paint(Matrix2D.IDENTITY, bx, by, bx + g.width(), by + g.height(), clip, opacity, (lx, ly) -> {
            int col = (int) Math.floor(lx - bx), row = (int) Math.floor(ly - by);
            if (col < 0 || row < 0 || col >= g.width() || row >= g.height()) return 0;
            float cov = strokeCoverage(g.coverageAt(col, row), half);
            return cov <= 0f ? 0 : SoftShapes.scalePremul(pm, cov);
        }, Fast.PURE);
    }

    private void blitStrokeTransformed(GlyphBitmap g, float ox, float oy, float s, Matrix2D pose, float half, int pm, Clip clip, float opacity) {
        float lx0 = ox + g.left() / s, ly0 = oy - g.top() / s;
        float lx1 = ox + (g.left() + g.width()) / s, ly1 = oy + (g.height() - g.top()) / s;
        paint(pose, lx0 - 1, ly0 - 1, lx1 + 1, ly1 + 1, clip, opacity, (lx, ly) -> {
            float col = (lx - ox) * s - g.left() - 0.5f, row = (ly - oy) * s + g.top() - 0.5f;
            float cov = strokeCoverage(sampleRaw(g, col, row), half);
            return cov <= 0f ? 0 : SoftShapes.scalePremul(pm, cov);
        }, Fast.PURE);
    }

    /** 範囲の外は SDF の「遠い外側」（0）として、双一次補間した生の値（0〜255）。 */
    private static float sampleRaw(GlyphBitmap g, float col, float row) {
        int c0 = (int) Math.floor(col), r0 = (int) Math.floor(row);
        float fx = col - c0, fy = row - r0;
        float v00 = cov(g, c0, r0), v10 = cov(g, c0 + 1, r0), v01 = cov(g, c0, r0 + 1), v11 = cov(g, c0 + 1, r0 + 1);
        return (v00 * (1 - fx) + v10 * fx) * (1 - fy) + (v01 * (1 - fx) + v11 * fx) * fy;
    }

    private void drawGlyphs(String text, float[] positions, OutlineFontFace face, FontStyle style, float x, float baseline,
                            int argb, Matrix2D pose, Clip clip, float opacity) {
        // 回転・せん断・縦横で違う拡縮が無ければ、字をデバイス画素の格子に揃えて置く（サブピクセル位置は 1/4 画素）
        boolean aligned = Math.abs(pose.b()) < 1e-9 && Math.abs(pose.c()) < 1e-9 && pose.a() > 0 && Math.abs(pose.a() - pose.d()) < 1e-6;
        float pixelSize = quantize((float) (style.fontSizePx() * pose.meanScale()));
        if (pixelSize < 0.5f) return;
        boolean light = isLight(argb);
        int pm = Pixmap.premultiply(argb);
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int n = Character.charCount(cp);
            if (cp <= 0x20 || cp == 0xA0 || Character.isWhitespace(cp)) {
                i += n;
                continue;
            }
            OutlineFontFace.GlyphFace gf = face.glyphFace(cp, style);
            float gx = x + positions[i];
            if (aligned) {
                double devX = pose.applyX(gx, baseline), devY = pose.applyY(gx, baseline);
                double whole = Math.floor(devX);
                int sub = (int) Math.round((devX - whole) * LoadedFace.SUBPIXEL_STEPS);
                if (sub == LoadedFace.SUBPIXEL_STEPS) {
                    whole += 1;
                    sub = 0;
                }
                float originY = Math.round(devY);
                GlyphBitmap bmp = glyph(gf, cp, pixelSize, sub, light);
                if (!bmp.isEmpty() && !glyphOnGpu(bmp, (float) whole, originY, argb, clip, opacity)) {
                    blitAligned(bmp, (float) whole, originY, pm, clip, opacity);
                }
            } else {
                GlyphBitmap bmp = glyph(gf, cp, pixelSize, 0, light);
                if (!bmp.isEmpty() && !glyphTransformedOnGpu(bmp, gx, baseline, pixelSize / style.fontSizePx(), pose, argb, clip, opacity)) {
                    blitTransformed(bmp, gx, baseline, pixelSize / style.fontSizePx(), pose, pm, clip, opacity);
                }
            }
            i += n;
        }
    }

    private static float quantize(float px) {
        return Math.round(px * 16f) / 16f;
    }

    private GlyphBitmap glyph(OutlineFontFace.GlyphFace gf, int cp, float pixelSize, int sub, boolean light) {
        GlyphKey key = probeKey.set(gf.face(), cp, pixelSize, gf.syntheticBold(), gf.syntheticItalic(), sub, light);
        GlyphBitmap g = glyphs.get(key);
        if (g == null) {
            g = gf.face().rasterize(cp, pixelSize, gf.syntheticBold(), gf.syntheticItalic(), sub);
            if (!g.isEmpty()) g = g.withTextContrast(light);
            if (glyphs.size() >= GLYPH_CACHE_LIMIT) glyphs.clear();
            glyphs.put(key.copy(), g);
        }
        return g;
    }

    private boolean glyphOnGpu(GlyphBitmap g, float originX, float originY, int argb, Clip clip, float opacity) {
        if (sink == null) return false;
        if (opacity <= 0f) return true;
        GpuClip[] gc = gpuClips(clip);
        if (gc == null) return false;
        return sink.drawGlyph(g, Math.round(originX + g.left()), Math.round(originY - g.top()), argb, gc, opacity);
    }

    private boolean glyphTransformedOnGpu(GlyphBitmap g, float ox, float oy, float s, Matrix2D pose, int argb, Clip clip, float opacity) {
        if (sink == null) return false;
        if (opacity <= 0f) return true;
        GpuClip[] gc = gpuClips(clip);
        if (gc == null) return false;
        float lx0 = ox + g.left() / s, ly0 = oy - g.top() / s;
        float lx1 = ox + (g.left() + g.width()) / s, ly1 = oy + (g.height() - g.top()) / s;
        return sink.drawGlyphTransformed(pose, g, ox, oy, s, lx0 - 1, ly0 - 1, lx1 + 1, ly1 + 1, argb, gc, opacity);
    }

    /** デバイス画素の格子にそのまま置く（原点 {@code (originX, originY)}）。 */
    private void blitAligned(GlyphBitmap g, float originX, float originY, int pm, Clip clip, float opacity) {
        float bx = originX + g.left(), by = originY - g.top();
        if (fastSolid && bx == Math.rint(bx) && by == Math.rint(by) && Math.abs(bx) < 1e6f && Math.abs(by) < 1e6f) {
            blitGridAligned(g, (int) bx, (int) by, pm, clip, opacity);
            return;
        }
        paint(Matrix2D.IDENTITY, bx, by, bx + g.width(), by + g.height(), clip, opacity, (lx, ly) -> {
            int col = (int) Math.floor(lx - bx), row = (int) Math.floor(ly - by);
            if (col < 0 || row < 0 || col >= g.width() || row >= g.height()) return 0;
            int cov = g.coverageAt(col, row);
            return cov == 0 ? 0 : SoftShapes.scalePremul(pm, cov / 255f);
        }, Fast.PURE);
    }

    /** 被覆率 0..255 → 色（{@code covLutColor} の事前乗算を {@code cov/255} 倍したもの）。同じ色の字が続く間は再利用（命令が参照するので上書きしない）。 */
    private int[] covLut;
    private int covLutColor;

    /** 字形を画素の格子に貼る命令。 */
    private static final class GlyphOp extends Op {
        GlyphBitmap g;
        int bx, by;
        int[] lut;
        Clip clip;
        boolean clipped;
        float opacity;

        @Override
        void run(int ax0, int ay0, int ax1, int ay1, Scratch s) {
            for (int y = ay0; y < ay1; y++) {
                double dy = y + 0.5;
                int row = y - by, base = y * w;
                for (int x = ax0; x < ax1; x++) {
                    int cov = g.coverageAt(x - bx, row);
                    if (cov == 0) continue;
                    int c = lut[cov];
                    if (c == 0) continue;
                    float k = opacity;
                    if (clipped) {
                        k *= clip.coverage(x + 0.5, dy);
                        if (k <= 0f) continue;
                    }
                    if (k < 1f) c = SoftShapes.scalePremul(c, k);
                    px[base + x] = SoftShapes.over(c, px[base + x]);
                }
            }
        }
    }

    /**
     * 字形の左上が画素の格子に載るとき（原点・左・上が整数）の貼り付け。恒等の姿勢の {@code paint} と同じ画素を同じ式で重ねるが、
     * 画素ごとの逆変換・シェーダー呼び出しをせず、被覆率→色は 256 項の表で引く。
     */
    private void blitGridAligned(GlyphBitmap g, int bx, int by, int pm, Clip clip, float opacity) {
        if (opacity <= 0f || g.width() <= 0 || g.height() <= 0) return;
        int[] r = paintRange(Matrix2D.IDENTITY, bx, by, bx + g.width(), by + g.height(), clip);
        if (r == null) return;
        if (covLut == null || covLutColor != pm) {
            int[] lut = new int[256];
            for (int i = 1; i < 256; i++) lut[i] = SoftShapes.scalePremul(pm, i / 255f);
            covLut = lut;
            covLutColor = pm;
        }
        GlyphOp op = acquireGlyph();
        op.px = target().pixels();
        op.w = target.width();
        op.x0 = r[0]; op.y0 = r[1]; op.x1 = r[2]; op.y1 = r[3];
        op.g = g; op.bx = bx; op.by = by; op.lut = covLut; op.clip = clip; op.clipped = !clip.parts().isEmpty(); op.opacity = opacity;
        submit(op, true);
    }

    /** 回転などの姿勢の下で、字形を双一次補間で貼る。{@code s} は 1 レイアウト単位あたりの字形の画素数。 */
    private void blitTransformed(GlyphBitmap g, float ox, float oy, float s, Matrix2D pose, int pm, Clip clip, float opacity) {
        float lx0 = ox + g.left() / s, ly0 = oy - g.top() / s;
        float lx1 = ox + (g.left() + g.width()) / s, ly1 = oy + (g.height() - g.top()) / s;
        paint(pose, lx0 - 1, ly0 - 1, lx1 + 1, ly1 + 1, clip, opacity, (lx, ly) -> {
            float col = (lx - ox) * s - g.left() - 0.5f, row = (ly - oy) * s + g.top() - 0.5f;
            float cov = sampleCoverage(g, col, row);
            return cov <= 0f ? 0 : SoftShapes.scalePremul(pm, cov);
        }, Fast.PURE);
    }

    private static float sampleCoverage(GlyphBitmap g, float col, float row) {
        int c0 = (int) Math.floor(col), r0 = (int) Math.floor(row);
        float fx = col - c0, fy = row - r0;
        float v00 = cov(g, c0, r0), v10 = cov(g, c0 + 1, r0), v01 = cov(g, c0, r0 + 1), v11 = cov(g, c0 + 1, r0 + 1);
        return ((v00 * (1 - fx) + v10 * fx) * (1 - fy) + (v01 * (1 - fx) + v11 * fx) * fy) / 255f;
    }

    private static float cov(GlyphBitmap g, int c, int r) {
        return c < 0 || r < 0 || c >= g.width() || r >= g.height() ? 0f : g.coverageAt(c, r);
    }

    // ------------------------------------------------------------------ 画像

    private void drawImage(DisplayItem el, float[] rx, float[] ry, Resolved res, Clip clip, float opacity) {
        String src = el.text();
        if (src == null || src.isEmpty()) return;
        float x0 = el.x(), y0 = el.y(), w = el.width(), h = el.height();
        float scale = (float) res.pose().meanScale();
        TextureRef texture = sink != null ? TextureRef.parse(src) : null;
        if (texture != null && drawExternalTexture(el, texture, rx, ry, res, clip, opacity, scale)) return;
        ImageProvider.Image img = resources.images().load(el, (int) Math.ceil(w * scale), (int) Math.ceil(h * scale));
        if (img == null || img.pixmap().width() <= 0 || img.pixmap().height() <= 0) return;
        Pixmap pm = img.pixmap();
        float iw = img.cssW() > 0 ? img.cssW() : pm.width(), ih = img.cssH() > 0 ? img.cssH() : pm.height();
        String position = el.events() != null ? el.events().get("object-position") : null;
        float fontPx = el.meta() != null && el.meta().fontHeight() > 0 ? el.meta().fontHeight() : 16f;
        ImagePlacement pl = ObjectFit.place(el.fitMode(), position, iw, ih, w, h, fontPx);
        if (!(pl.w() > 0) || !(pl.h() > 0)) return;
        float px0 = x0 + pl.x(), py0 = y0 + pl.y();
        float sx = pm.width() / pl.w(), sy = pm.height() / pl.h();
        // 縮小するときは対象の画素の中を複数点で標本化して、折り返しの粗さを抑える
        int taps = (int) Math.min(4, Math.max(1, Math.ceil(Math.max(sx, sy) / Math.max(scale, 0.01f))));
        float m = 1f / Math.max(scale, 0.01f);
        if (sink != null) {
            GpuClip[] gc = gpuClips(clip);
            if (gc != null && sink.drawImage(res.pose(), pm, Math.max(x0 - m, px0), Math.max(y0 - m, py0),
                    Math.min(x0 + w + m, px0 + pl.w()), Math.min(y0 + h + m, py0 + pl.h()), px0, py0, sx, sy,
                    x0, y0, x0 + w, y0 + h, rx, ry, taps, sx / scale, sy / scale, gc, opacity)) return;
        }
        paint(res.pose(), Math.max(x0 - m, px0), Math.max(y0 - m, py0), Math.min(x0 + w + m, px0 + pl.w()), Math.min(y0 + h + m, py0 + pl.h()),
                clip, opacity, (lx, ly) -> {
                    float box = SoftShapes.coverage(SoftShapes.sdRoundRect(lx, ly, x0, y0, x0 + w, y0 + h, rx, ry), scale);
                    if (box <= 0f) return 0;
                    float u = (lx - px0) * sx, v = (ly - py0) * sy;
                    if (u < -0.5f || v < -0.5f || u > pm.width() + 0.5f || v > pm.height() + 0.5f) return 0;
                    int c = taps == 1 ? bilinear(pm, u - 0.5f, v - 0.5f) : boxSample(pm, u, v, taps, sx / scale, sy / scale);
                    return c == 0 ? 0 : SoftShapes.scalePremul(c, box);
                });
    }

    /**
     * 外部テクスチャを GPU の受け口へ渡す。貼る範囲（{@code uv}）と上下反転を、{@code drawTexture} の写像
     * （テクスチャ全体の左上・画素を基準にした {@code px0/py0/sx/sy}）に織り込む。受け口が断る（CPU 版など）ときは false。
     */
    private boolean drawExternalTexture(DisplayItem el, TextureRef t, float[] rx, float[] ry, Resolved res, Clip clip, float opacity,
                                        float scale) {
        float x0 = el.x(), y0 = el.y(), w = el.width(), h = el.height();
        float sw = t.regionWidth(), sh = t.regionHeight();
        String position = el.events() != null ? el.events().get("object-position") : null;
        float fontPx = el.meta() != null && el.meta().fontHeight() > 0 ? el.meta().fontHeight() : 16f;
        ImagePlacement pl = ObjectFit.place(el.fitMode(), position, sw, sh, w, h, fontPx);
        if (!(pl.w() > 0) || !(pl.h() > 0)) return true; // 描くものが無い（画像の項目としては描いたことにする）
        float px0 = x0 + pl.x(), py0 = y0 + pl.y();
        float kx = sw / pl.w(), ky = sh / pl.h();           // 局所の 1 単位あたりのテクスチャの画素数
        float regionX = t.u0() * t.width(), regionY = t.v0() * t.height();
        float effPx0 = px0 - regionX / kx;                  // 範囲の左上を、貼る位置の左上に合わせる
        float effPy0, sy;
        if (t.flipY()) { // 下から上へ並んだテクスチャ: 見た目の上端は、テクスチャの上端（行 height）から下へ数える
            sy = -ky;
            effPy0 = py0 + (t.height() - regionY) / ky;
        } else {
            sy = ky;
            effPy0 = py0 - regionY / ky;
        }
        float m = 1f / Math.max(scale, 0.01f);
        GpuClip[] gc = gpuClips(clip);
        return gc != null && sink.drawTexture(res.pose(), t.handle(), t.width(), t.height(),
                Math.max(x0 - m, px0), Math.max(y0 - m, py0), Math.min(x0 + w + m, px0 + pl.w()), Math.min(y0 + h + m, py0 + pl.h()),
                effPx0, effPy0, kx, sy, x0, y0, x0 + w, y0 + h, rx, ry, gc, opacity);
    }

    private static int boxSample(Pixmap pm, float u, float v, int taps, float stepX, float stepY) {
        float a = 0, r = 0, g = 0, b = 0;
        for (int j = 0; j < taps; j++) {
            for (int i = 0; i < taps; i++) {
                float su = u + ((i + 0.5f) / taps - 0.5f) * stepX, sv = v + ((j + 0.5f) / taps - 0.5f) * stepY;
                int c = bilinear(pm, su - 0.5f, sv - 0.5f);
                a += c >>> 24;
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
            }
        }
        float n = taps * taps;
        return (Math.round(a / n) << 24) | (Math.round(r / n) << 16) | (Math.round(g / n) << 8) | Math.round(b / n);
    }

    /** 事前乗算の画素の双一次補間（範囲の外は端の画素）。 */
    private static int bilinear(Pixmap pm, float u, float v) {
        int w = pm.width(), h = pm.height();
        int x0 = (int) Math.floor(u), y0 = (int) Math.floor(v);
        float fx = u - x0, fy = v - y0;
        int xa = Math.clamp(x0, 0, w - 1), xb = Math.clamp(x0 + 1, 0, w - 1);
        int ya = Math.clamp(y0, 0, h - 1), yb = Math.clamp(y0 + 1, 0, h - 1);
        int c00 = pm.get(xa, ya), c10 = pm.get(xb, ya), c01 = pm.get(xa, yb), c11 = pm.get(xb, yb);
        int out = 0;
        for (int shift = 24; shift >= 0; shift -= 8) {
            float top = ((c00 >>> shift) & 0xFF) * (1 - fx) + ((c10 >>> shift) & 0xFF) * fx;
            float bot = ((c01 >>> shift) & 0xFF) * (1 - fx) + ((c11 >>> shift) & 0xFF) * fx;
            out |= Math.clamp(Math.round(top * (1 - fy) + bot * fy), 0, 255) << shift;
        }
        return out;
    }

    // ------------------------------------------------------------------ 操作部品

    private void drawProgress(DisplayItem el, AnimatedValues anim, float[] rx, float[] ry, Resolved res, Clip clip, float opacity) {
        float min = anim.rangeMin, max = anim.rangeMax, val = anim.rangeVal;
        float[] parsed = parseRange(el.text());
        if (parsed != null) {
            val = parsed[0];
            min = parsed[1];
            max = parsed[2];
        }
        float ratio = max > min ? Math.clamp((val - min) / (max - min), 0f, 1f) : 0f;
        float fillW = el.width() * ratio;
        if (fillW <= 0.5f) return;
        int color = anim.color() != 0 ? anim.color() : 0xFF28A745;
        float[] frx = {rx[0], ratio > 0.98f ? rx[1] : 0f, ratio > 0.98f ? rx[2] : 0f, rx[3]};
        float[] fry = {ry[0], ratio > 0.98f ? ry[1] : 0f, ratio > 0.98f ? ry[2] : 0f, ry[3]};
        fillRoundRect(res.pose(), el.x(), el.y(), el.x() + fillW, el.y() + el.height(), frx, fry, color, clip, opacity);
    }

    private static float[] parseRange(String text) {
        if (text == null || !text.contains(":")) return null;
        String[] sp = text.split(":");
        if (sp.length < 3) return null;
        try {
            return new float[]{Float.parseFloat(sp[0]), Float.parseFloat(sp[1]), Float.parseFloat(sp[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final float RANGE_TRACK_HEIGHT = 8f, RANGE_THUMB_SIZE = 16f;

    /** Chromium のスライダーと同じ形: 溝は高さ 8px、つまみは直径 16px で中心が両端から 8px の間を動く。 */
    private void drawRange(DisplayItem el, AnimatedValues anim, Resolved res, Clip clip, float opacity) {
        float val = anim.rangeVal, min = anim.rangeMin, max = anim.rangeMax;
        float[] parsed = parseRange(el.text());
        if (parsed != null) {
            val = parsed[0];
            min = parsed[1];
            max = parsed[2];
        }
        float ratio = max > min ? Math.clamp((val - min) / (max - min), 0f, 1f) : 0f;
        float cy = el.y() + el.height() / 2f;
        float trackTop = cy - RANGE_TRACK_HEIGHT / 2, trackBottom = cy + RANGE_TRACK_HEIGHT / 2;
        float trackRad = RANGE_TRACK_HEIGHT / 2, thumbRad = RANGE_THUMB_SIZE / 2;
        float left = el.x(), right = el.x() + el.width();
        float tcx = left + thumbRad + ratio * Math.max(0f, el.width() - RANGE_THUMB_SIZE);
        Matrix2D pose = res.pose();
        int fill = anim.bgColor();
        if ((fill >>> 24) != 0) {
            RenderMeta meta = el.meta();
            int edge = meta != null && meta.roundedBorder() != null ? meta.roundedBorder().colorTop() : 0;
            int accent = anim.color() | 0xFF000000;
            fillRoundRect(pose, left, trackTop, right, trackBottom, uniform(trackRad), uniform(trackRad), over(edge, fill), clip, opacity);
            fillRoundRect(pose, left + 1, trackTop + 1, right - 1, trackBottom - 1, uniform(trackRad - 1), uniform(trackRad - 1), fill, clip, opacity);
            if (tcx > left) {
                float r = Math.min(trackRad, (tcx - left) / 2);
                fillRoundRect(pose, left, trackTop, tcx, trackBottom, new float[]{r, 0, 0, r}, new float[]{r, 0, 0, r}, over(edge, accent), clip, opacity);
                if (tcx > left + 1) {
                    float ri = Math.max(0f, Math.min(trackRad - 1, (tcx - left - 1) / 2));
                    fillRoundRect(pose, left + 1, trackTop + 1, tcx, trackBottom - 1, new float[]{ri, 0, 0, ri}, new float[]{ri, 0, 0, ri}, accent, clip, opacity);
                }
            }
        }
        fillRoundRect(pose, tcx - thumbRad, cy - thumbRad, tcx + thumbRad, cy + thumbRad, uniform(thumbRad), uniform(thumbRad), anim.color(), clip, opacity);
    }

    /** 半透明の {@code top} を不透明な {@code bottom} に重ねた色（sRGB のまま合成）。 */
    private static int over(int top, int bottom) {
        float a = ((top >>> 24) & 0xFF) / 255f;
        int out = bottom & 0xFF000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            float t = (top >>> shift) & 0xFF, b = (bottom >>> shift) & 0xFF;
            out |= Math.round(t * a + b * (1 - a)) << shift;
        }
        return out;
    }

    private void drawToggle(DisplayItem el, AnimatedValues anim, Resolved res, Clip clip, float opacity) {
        boolean mixed = el.type() == ElementType.CHECKBOX && "mixed".equals(el.text());
        if (!"true".equals(el.text()) && !mixed) return;
        int color = anim.color();
        if ((color >>> 24) == 0) return;
        float x = el.x(), y = el.y(), w = el.width(), h = el.height();
        Matrix2D pose = res.pose();
        if (el.type() == ElementType.RADIO) {
            float pad = w * 0.2f;
            float dot = (w - 2 * pad) / 2f;
            fillRoundRect(pose, x + pad, y + h / 2f - dot, x + w - pad, y + h / 2f + dot, uniform(dot), uniform(dot), color, clip, opacity);
        } else if (el.type() == ElementType.CHECKBOX) {
            if (mixed) {
                float radius = Math.min(2f, h * 0.1f);
                fillRoundRect(pose, x + w * 0.2f, y + h * 0.4f, x + w * 0.8f, y + h * 0.6f, uniform(radius), uniform(radius), color, clip, opacity);
            } else {
                // Chromium の NativeThemeBase::PaintCheckbox: (0.2w, 0.5h) → 右下へ (0.2w, 0.2h) → (0.8w, 0.2h)、太さ 0.16h
                float stroke = h * 0.16f;
                float x0 = x + w * 0.2f, y0 = y + h * 0.5f, x1 = x0 + w * 0.2f, y1 = y0 + h * 0.2f, x2 = x + w * 0.8f, y2 = y + h * 0.2f;
                drawSegment(pose, x0, y0, x1, y1, stroke, color, clip, opacity);
                drawSegment(pose, x1, y1, x2, y2, stroke, color, clip, opacity);
            }
        } else {
            float pad = w * 0.25f;
            fillRect(pose, x + pad, y + pad, x + w - pad, y + h - pad, color, clip, opacity);
        }
    }

    /** 太さ {@code width} の線分（両端は丸）。 */
    private void drawSegment(Matrix2D pose, float x0, float y0, float x1, float y1, float width, int argb, Clip clip, float opacity) {
        int pm = Pixmap.premultiply(argb);
        float scale = (float) pose.meanScale();
        float half = width / 2f, m = half + 1f / Math.max(scale, 0.01f);
        float dx = x1 - x0, dy = y1 - y0, len2 = dx * dx + dy * dy;
        paint(pose, Math.min(x0, x1) - m, Math.min(y0, y1) - m, Math.max(x0, x1) + m, Math.max(y0, y1) + m, clip, opacity, (lx, ly) -> {
            float t = len2 <= 0f ? 0f : Math.clamp(((lx - x0) * dx + (ly - y0) * dy) / len2, 0f, 1f);
            float dist = (float) Math.hypot(lx - (x0 + dx * t), ly - (y0 + dy * t));
            float cov = SoftShapes.coverage(dist - half, scale);
            return cov <= 0f ? 0 : SoftShapes.scalePremul(pm, cov);
        });
    }
}
