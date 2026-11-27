package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.paint.emit.SoftShapes;
import com.nishiyu.enderframelib.core.paint.emit.Clip;
import com.nishiyu.enderframelib.core.paint.emit.ClipPart;
import com.nishiyu.enderframelib.core.paint.emit.GpuAttempt;
import com.nishiyu.enderframelib.core.paint.emit.GpuRoute;
import com.nishiyu.enderframelib.core.paint.emit.GpuLayerAttempt;
import com.nishiyu.enderframelib.core.paint.resource.ImageProvider;
import com.nishiyu.enderframelib.core.paint.resource.PaintResources;
import com.nishiyu.enderframelib.core.scene3d.Scene3DWorld;
import java.util.function.LongFunction;
import com.nishiyu.enderframelib.core.paint.AnimatedValues;
import com.nishiyu.enderframelib.core.paint.model.ClipChainPart;
import com.nishiyu.enderframelib.core.paint.port.GpuClip;
import com.nishiyu.enderframelib.core.paint.port.GpuScrollLayer;
import com.nishiyu.enderframelib.core.paint.port.GpuSink;
import com.nishiyu.enderframelib.core.paint.CssAnimator;
import com.nishiyu.enderframelib.core.paint.ItemDiff;
import com.nishiyu.enderframelib.core.paint.RetainPlan;
import com.nishiyu.enderframelib.core.paint.SceneDamage;
import com.nishiyu.enderframelib.core.paint.SceneSource;
import com.nishiyu.enderframelib.core.paint.SceneWalker;
import com.nishiyu.enderframelib.core.paint.SceneWalker.Group;
import com.nishiyu.enderframelib.core.paint.SceneWalker.Resolved;
import com.nishiyu.enderframelib.core.paint.RenderBackend;
import com.nishiyu.enderframelib.core.paint.ShaderGradientEmulator;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.Corners;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.LayerSpec;
import com.nishiyu.enderframelib.core.paint.model.PaintOffsets;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;
import com.nishiyu.enderframelib.core.paint.model.RoundedBorderSpec;
import com.nishiyu.enderframelib.core.paint.model.ShaderGradient;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import com.nishiyu.enderframelib.core.paint.Painter;

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
    /** TextRaster（切り出した協力クラス）。 */
    private final TextRaster textRaster = new TextRaster(this);
    /** 項目の種類ごとの塗り（{@link ItemRaster}）。 */
    private final ItemRaster itemRaster = new ItemRaster(this);
    private static final AtomicInteger SESSIONS = new AtomicInteger();

    /** 画素の置き場（根・描画先・層のスタック・汚れ範囲・層の合成）。根は CPU が実際に塗るまで確保しない。 */
    final CpuRaster raster;
    final GpuRoute route = new GpuRoute();
    final float dpr;
    private final Matrix2D dprFrame;
    private final String sessionId;
    private boolean ownsSession; // 名前を自分で発行した（借りた名前の補間状態は持ち主が捨てる）
    final PaintResources resources;
    int clearColor = 0;
    final GlyphCache glyphCache = new GlyphCache();

    // 1 フレームの間だけ使う状態
    /** {@link #paintRange} の作業用（描画は 1 スレッドで、使い終えてから次を呼ぶ）。 */
    final double[] boundsScratch = new double[4];
    private Matrix2D frame = Matrix2D.IDENTITY;

    // ------------------------------------------------------------------ 差分描画（Tile Dirty Bitset）

    /** このフレームで塗り直す範囲（タイル境界まで広げた外接矩形）。 */
    final DamageRegion damage = new DamageRegion();
    /** 描き直す範囲を前後のシーンの違いから求める（M3b-4b-4a。前のフレームのシーンとスクロール量を比べる相手として持つ）。 */
    private final SceneDamage sceneDamage = new SceneDamage();
    /** 前のフレームの塗りの色・利用者の平行移動の版（変われば全面）。 */
    private int prevClearColor;
    private int prevUserOffsets = -1;
    /** このフレームの差分描画が有効（塗りを {@link #damage} に収める）。 */
    private boolean partialActive;

    /** 診断用: 差分描画したフレーム・全面描画したフレームの累計と、差分描画で塗り直さなかった画素の累計。 */
    long partialFrames, fullFrames, untouchedPixels;

    /** CSS px の大きさ {@code cssW × cssH}・等倍の描画先を作る。 */
    public SoftwareRenderBackend(int cssW, int cssH) { this(cssW, cssH, 1f); }

    /** CSS px の大きさ {@code cssW × cssH}、1 CSS px が {@code devicePixelRatio} 画素の描画先を作る。名前は自分で発行し、閉じるときに捨てる。 */
    public SoftwareRenderBackend(int cssW, int cssH, float devicePixelRatio) {
        this(cssW, cssH, devicePixelRatio, "software-" + SESSIONS.incrementAndGet());
        this.ownsSession = true;
    }

    /**
     * セッション名を指定して作る。GPU 版が、保険の CPU 描画器にも自分と同じセッションを使わせる（アニメーションの
     * 状態は {@link CssAnimator} がセッションごとに持つので、描く側と問う側で名前が揃っている必要がある）。
     */
    public SoftwareRenderBackend(int cssW, int cssH, float devicePixelRatio, String sessionId) { this(cssW, cssH, devicePixelRatio, sessionId, new PaintResources()); }

    /** 文字・画像の供給元を共有して作る（GPU 版が自分の持ち物を保険の CPU 描画器にも使わせる）。 */
    public SoftwareRenderBackend(int cssW, int cssH, float devicePixelRatio, String sessionId, PaintResources resources) {
        this.resources = java.util.Objects.requireNonNull(resources, "resources");
        this.sessionId = java.util.Objects.requireNonNull(sessionId, "sessionId");
        this.walker = new SceneWalker(sessionId);
        if (!(devicePixelRatio > 0f)) throw new IllegalArgumentException("devicePixelRatio must be > 0");
        this.dpr = devicePixelRatio;
        this.dprFrame = Matrix2D.scale(devicePixelRatio, devicePixelRatio);
        this.raster = new CpuRaster(Math.max(1, Math.round(cssW * devicePixelRatio)), Math.max(1, Math.round(cssH * devicePixelRatio)));
    }

    /** 根の画素を確保済みか（診断・テスト用）。 */
    public boolean isRootAllocated() { return raster.isRootAllocated(); }
    public SoftwareRenderBackend fonts(FontResolver resolver) { resources.fonts(resolver); return this; }
    public SoftwareRenderBackend images(ImageProvider provider) { resources.images(provider); return this; }
    public SoftwareRenderBackend scene3dWorlds(LongFunction<Scene3DWorld> provider) { resources.scene3dWorlds(provider); return this; }
    public Scene3DWorld scene3dWorld(long worldId) { return resources.scene3dWorld(worldId); }
    /** フレームの最初に塗る色（非乗算の ARGB。既定は透明）。 */
    public SoftwareRenderBackend clearColor(int argb) { this.clearColor = Pixmap.premultiply(argb); return this; }
    @Override public Pixmap pixmap() { return raster.root(); }
    @Override public float devicePixelRatio() { return dpr; }
    @Override public String sessionId() { return sessionId; }
    /** 自分のセッションの補間状態（表示項目を握る）を捨てる。捨てないと描画器を作るたびに溜まる（CI の単一 JVM が OOM）。 */
    @Override public void close() { if (ownsSession) CssAnimator.clearSession(sessionId); }

    /** 描画先を {@link #clearColor} で塗り直し、{@code items} を時刻 {@code nowMs}（ミリ秒）の見た目で描く（シーンが無いので全面）。 */
    @Override public void render(List<DisplayItem> items, long nowMs) { render(items, PaintOffsets.EMPTY, null, nowMs); }

    @Override
    public void render(List<DisplayItem> items, PaintOffsets offsets, long nowMs) { render(items, offsets, null, nowMs); }

    /**
     * 描画先を塗り直し、{@code items} を時刻 {@code nowMs} の見た目で描く。列を組んだシーン {@code source} があれば、前のフレームとの
     * シーンの違い（{@link SceneDamage}）と、補間・外の事情で変わる項目・重ね描きの前後の範囲（走査器）だけを 64×64 タイルで塗り直し、
     * 塗りをその外接矩形に収める（M3b-4b-4a）。初回・塗りの色や利用者の平行移動が変わったフレーム・シーンの無いフレームは全面。
     */
    @Override
    public void render(List<DisplayItem> items, PaintOffsets offsets, SceneSource source, long nowMs) {
        if (items == null || items.isEmpty()) {
            raster.clearAll(clearColor);
            sceneDamage.reset();
            return;
        }
        PaintOffsets eff = offsets != null ? offsets : PaintOffsets.EMPTY;
        SceneDamage.Rects rects = null;
        if (source != null) {
            rects = sceneDamage.next(source.scene(), source.dynamic());
            if (clearColor != prevClearColor) rects.setFull("clear-color");
            if (!com.nishiyu.enderframelib.core.kernel.Shortcut.SOFT_DAMAGE.on()) rects.setFull("damage-off");
            if (source.userOffsetsVersion() != prevUserOffsets) rects.setFull("user-offsets");
            prevUserOffsets = source.userOffsetsVersion();
        } else {
            sceneDamage.reset();
            prevUserOffsets = -1;
        }
        prevClearColor = clearColor;
        route.begin(null);
        walker.setScrollSnapDpr(0f);
        raster.beginFrame();
        beginFrame(items);
        boolean done = false;
        try {
            if (rects == null) beginWalk(null);
            walker.run(items, eff, source, nowMs, frame, painter, rects);
            queue.flush();
            done = true;
        } finally {
            queue.discard(); // 例外のときは捨てる（描画先は不定）
            if (!done) sceneDamage.reset(); // 例外のとき: 次は全面
            raster.endFrame();
            route.end();
            partialActive = false;
        }
    }

    /** 走査器が描き直す範囲を決めた（描く前）: 全面なら全面を、そうでなければ範囲のタイルを塗り直す。範囲が空なら何も描かない。 */
    private boolean beginWalk(SceneDamage.Rects rects) {
        if (rects == null || rects.full()) {
            raster.clearAll(clearColor);
            partialActive = false;
            fullFrames++;
            return true;
        }
        damage.reset(raster.targetWidth(), raster.targetHeight());
        for (int k = 0, n = rects.size(); k < n; k++) { // CSS px → デバイス画素（縁のアンチエイリアスに 1 画素）
            damage.add((int) Math.floor(rects.x0(k) * dpr) - 1, (int) Math.floor(rects.y0(k) * dpr) - 1,
                    (int) Math.ceil(rects.x1(k) * dpr) + 1, (int) Math.ceil(rects.y1(k) * dpr) + 1);
        }
        damage.seal();
        partialFrames++;
        untouchedPixels += (long) raster.targetWidth() * raster.targetHeight()
                - (damage.isEmpty() ? 0L : (long) (damage.x1() - damage.x0()) * (damage.y1() - damage.y0()));
        partialActive = true;
        if (damage.isEmpty()) return false;
        raster.clearTiles(damage, clearColor);
        return true;
    }

    /** 描画先を塗り直さずに {@code items} を重ねる（平行移動・シーンなし版）。 */
    public void renderOnto(List<DisplayItem> items, long nowMs, GpuSink sink) { renderOnto(items, PaintOffsets.EMPTY, null, nowMs, sink); }

    /**
     * 描画先を塗り直さずに {@code items} を重ねる。{@code sink} があれば、単色の角丸矩形と角丸の枠線はそちらへ渡す
     * （受け取られなかったものだけ CPU が描く）。GPU 版が文字・画像などの CPU 描画を重ねるのに使う。
     */
    public void renderOnto(List<DisplayItem> items, PaintOffsets offsets, SceneSource source, long nowMs, GpuSink sink) {
        sceneDamage.reset(); // 全面を塗っていない・別の描画先かもしれないので、差分描画の前提を捨てる
        drawFrame(items, offsets != null ? offsets : PaintOffsets.EMPTY, source, nowMs, sink);
    }

    /** CPU が描いた範囲 {@code {x0, y0, x1, y1}}（x1・y1 は含まない）。何も描いていなければ {@code null}。 */
    public int[] dirtyBounds() {
        // 範囲を聞く側（GPU 版）はこの後に画素を読む。フレームの途中でも、溜めた命令を先に書き切る
        queue.flush();
        return raster.dirtyBounds();
    }

    /** 描いた範囲を透明に戻し、{@link #dirtyBounds} を空にする。 */
    public void clearDirty() {
        raster.clearDirty();
    }

    private void beginFrame(List<DisplayItem> items) {
        this.frame = dprFrame;
        this.frameSize = items.size();
        this.partialActive = false;
        this.retainInfo.clear();
        this.recordOwner = null;
        if (!frameClips.isEmpty()) frameClips.clear();
        if (!frameParts.isEmpty()) frameParts.clear();
    }

    private void drawFrame(List<DisplayItem> items, PaintOffsets offsets, SceneSource source, long nowMs, GpuSink gpu) {
        if (items == null || items.isEmpty()) return;
        route.begin(gpu);
        walker.setScrollSnapDpr(gpu != null && gpu.snapsScrollOffsets() ? dpr : 0f);
        raster.beginFrame();
        beginFrame(items);
        try {
            // 走査（アニメーション補間・親子の変形・グループの鎖）は走査器が受け持ち、ここは画素を塗るだけ
            walker.run(items, offsets, source, nowMs, frame, painter, null);
            queue.flush(); // 開いたままの層の中身も、層を捨てる前に書き切る
        } finally {
            queue.discard(); // 例外のときは捨てる（描画先は不定）
            raster.endFrame();
            route.end();
        }
    }

    final SceneWalker walker;

    /** 直近に描いた {@code items} に、時刻 {@code nowMs} でまだ動いているアニメーションがあるか。 */
    public boolean hasActiveAnimation(List<DisplayItem> items, long nowMs) {
        return SceneWalker.anyActiveAt(sessionId, items, nowMs);
    }
    private final Painter painter = new Painter() {
        @Override
        public boolean beginWalk(SceneDamage.Rects damage) {
            return SoftwareRenderBackend.this.beginWalk(damage);
        }

        @Override
        public void syncLayers(List<Group> want) {
            SoftwareRenderBackend.this.syncLayers(want);
        }

        @Override
        public void drawItem(DisplayItem el, int index, float flat) {
            SoftwareRenderBackend.this.drawItem(el, index, flat);
        }

        @Override
        public RetainPlan planRetained(DisplayItem node, int nodeIndex, int from, int to, Object token) {
            return SoftwareRenderBackend.this.planRetained(node, nodeIndex, from, to, token);
        }

        @Override
        public void drawRetained(DisplayItem node, int nodeIndex, int from, int to) {
            GpuLayerAttempt.drawScroll(route.current(), retainInfo.get(nodeIndex));
        }

        @Override
        public void beginRetained(DisplayItem node, int nodeIndex, int from, int to) {
            if (GpuLayerAttempt.beginScroll(route.current(), retainInfo.get(nodeIndex))) recordOwner = cachedOwnerKeyOf(node, nodeIndex);
        }

        @Override
        public boolean endRetained(DisplayItem node, int nodeIndex) {
            recordOwner = null;
            return GpuLayerAttempt.endScroll(route.current(), retainInfo.get(nodeIndex));
        }
    };

    // ------------------------------------------------------------------ スクロール層の保持

    /** 節の添字 → 保持する層の情報（このフレームで問い合わせたもの）。 */
    private final java.util.HashMap<Integer, GpuScrollLayer> retainInfo = new java.util.HashMap<>();
    /** 保持する層へ記録している間の、コンテナ（スクロール節の持ち主）の鍵。クリップの部品のうち、これが持ち主のものは外す。 */
    private String recordOwner;
    /** 中身が保持に向くかの判定の覚え（基底の表示リストと節が同じなら同じ答え）。0=未判定, 1=不可, 2=可。 */
    private Object eligibleToken;
    private byte[] retainEligible = new byte[0];
    private String[] retainOwner = new String[0];
    private final double[] scratchBounds4 = new double[4];

    private static String ownerKeyOf(DisplayItem node) {
        String id = node.id();
        int suffixLen = SceneWalker.SCROLL_CONTENT_SUFFIX.length();
        if (id.endsWith(SceneWalker.SCROLL_CONTENT_SUFFIX)) {
            String pid = node.hints().parentId();
            if (pid != null && pid.length() == id.length() - suffixLen) return pid;
            return id.substring(0, id.length() - suffixLen);
        }
        return id;
    }

    private String cachedOwnerKeyOf(DisplayItem node, int nodeIndex) {
        Object token = walker.items();
        if (eligibleToken != token) {
            eligibleToken = token;
            java.util.Arrays.fill(retainEligible, (byte) 0);
            java.util.Arrays.fill(retainOwner, null);
        }
        if (nodeIndex >= retainOwner.length) {
            int nextLen = Math.max(nodeIndex + 1, retainOwner.length * 2 + 16);
            retainEligible = java.util.Arrays.copyOf(retainEligible, nextLen);
            retainOwner = java.util.Arrays.copyOf(retainOwner, nextLen);
        }
        String owner = retainOwner[nodeIndex];
        if (owner == null) {
            owner = ownerKeyOf(node);
            retainOwner[nodeIndex] = owner;
        }
        return owner;
    }

    private RetainPlan planRetained(DisplayItem node, int nodeIndex, int from, int to, Object token) {
        if (route.current() == null || recordOwner != null) return RetainPlan.NONE;
        Object listToken = walker.items(); // 層に向くかの判定は列ごとに覚える。層の同一性は中身の印 token（Painter#planRetained）
        if (eligibleToken != listToken) {
            eligibleToken = listToken;
            java.util.Arrays.fill(retainEligible, (byte) 0);
            java.util.Arrays.fill(retainOwner, null);
        }
        if (nodeIndex >= retainEligible.length) {
            int nextLen = Math.max(nodeIndex + 1, retainEligible.length * 2 + 16);
            retainEligible = java.util.Arrays.copyOf(retainEligible, nextLen);
            retainOwner = java.util.Arrays.copyOf(retainOwner, nextLen);
        }
        byte verdict = retainEligible[nodeIndex];
        if (verdict == 0) {
            verdict = rangeIsRetainable(walker.items(), from, to) ? (byte) 2 : (byte) 1;
            retainEligible[nodeIndex] = verdict;
        }
        if (verdict != 2) return RetainPlan.NONE;

        String owner = retainOwner[nodeIndex];
        if (owner == null) {
            owner = ownerKeyOf(node);
            retainOwner[nodeIndex] = owner;
        }
        List<DisplayItem> items = walker.items();
        int x0 = 0, y0 = 0, x1 = raster.targetWidth(), y1 = raster.targetHeight();
        int partCount = 0;
        for (int i = from; i < to && partCount == 0; i++) {
            RenderMeta meta = items.get(i).meta();
            ClipRect cr = meta != null ? meta.clipRect() : null;
            if (cr == null) continue;
            if (!cr.chain().isEmpty()) {
                List<ClipChainPart> chain = cr.chain();
                for (int k = 0, cn = chain.size(); k < cn; k++) {
                    ClipChainPart p = chain.get(k);
                    if (owner.equals(p.ownerId()) && p.rectLength() >= 4) {
                        int ix = walker.indexOf(p.ownerId());
                        Matrix2D pose = ix >= 0 ? walker.resolveAt(ix).pose() : frame;
                        double[] b = deviceBounds(pose, p.rectAt(0), p.rectAt(1), p.rectAt(2), p.rectAt(3), scratchBounds4);
                        x0 = Math.max(x0, (int) Math.floor(b[0]));
                        y0 = Math.max(y0, (int) Math.floor(b[1]));
                        x1 = Math.min(x1, (int) Math.ceil(b[2]));
                        y1 = Math.min(y1, (int) Math.ceil(b[3]));
                        partCount++;
                    }
                }
            } else if (owner.equals(cr.ownerId())) {
                int ix = walker.indexOf(cr.ownerId());
                Matrix2D pose = ix >= 0 ? walker.resolveAt(ix).pose() : frame;
                double[] b = deviceBounds(pose, cr.minX(), cr.minY(), cr.maxX(), cr.maxY(), scratchBounds4);
                x0 = Math.max(x0, (int) Math.floor(b[0]));
                y0 = Math.max(y0, (int) Math.floor(b[1]));
                x1 = Math.min(x1, (int) Math.ceil(b[2]));
                y1 = Math.min(y1, (int) Math.ceil(b[3]));
                partCount++;
            }
        }
        if (partCount == 0 || partCount > com.nishiyu.enderframelib.core.paint.port.GpuSink.MAX_CLIPS || x1 <= x0 || y1 <= y0) {
            return RetainPlan.NONE;
        }
        Matrix2D nodePose = walker.resolveAt(nodeIndex).pose();
        RetainPlan fast = GpuLayerAttempt.planScrollBounds(route.current(), node.id(), token, nodePose, x0, y0, x1, y1);
        if (fast == RetainPlan.NONE) return RetainPlan.NONE;
        if (fast == RetainPlan.SKIP) {
            GpuScrollLayer prev = retainInfo.get(nodeIndex);
            if (prev != null && prev.token() == token && node.id() != null && node.id().equals(prev.key())) {
                int[] pb = prev.bounds();
                if (pb[0] == x0 && pb[1] == y0 && pb[2] == x1 && pb[3] == y1) {
                    Matrix2D pp = prev.pose();
                    if (pp.a() != nodePose.a() || pp.b() != nodePose.b() || pp.c() != nodePose.c()
                            || pp.d() != nodePose.d() || pp.e() != nodePose.e() || pp.f() != nodePose.f()) {
                        retainInfo.put(nodeIndex, new GpuScrollLayer(prev.key(), token, nodePose, prev.clip(), pb));
                    }
                    return RetainPlan.SKIP;
                }
            }
        }
        GpuScrollLayer info = scrollLayerInfo(node, nodeIndex, from, to, token);
        if (info == null) return RetainPlan.NONE;
        RetainPlan r = fast != null ? fast : GpuLayerAttempt.planScroll(route.current(), info);
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
            if (it.type().isExternallyDriven() || it.type().hasLiveWidgetState()) return false;
            if (it.transitionDuration() > 0 || (it.animDef() != null && !it.animDef().isEmpty())) return false;
        }
        return true;
    }

    /** 層の情報。コンテナの切り取りは、中身の項目の切り取りの鎖のうち、持ち主がコンテナのもの。 */
    private GpuScrollLayer scrollLayerInfo(DisplayItem node, int nodeIndex, int from, int to, Object token) {
        String owner = cachedOwnerKeyOf(node, nodeIndex);
        List<DisplayItem> items = walker.items();
        List<ClipPart> parts = new ArrayList<>(1);
        for (int i = from; i < to && parts.isEmpty(); i++) {
            RenderMeta meta = items.get(i).meta();
            ClipRect cr = meta != null ? meta.clipRect() : null;
            if (cr == null) continue;
            if (!cr.chain().isEmpty()) {
                for (ClipChainPart p : cr.chain()) {
                    if (owner.equals(p.ownerId())) { clipOwnerCount = 0; addClipPart(parts, p.rectView(), p.ownerId()); }
                }
            } else if (owner.equals(cr.ownerId())) {
                clipOwnerCount = 0; // 持ち主の記録は使わない
                addClipPart(parts, cr.toArray(), cr.ownerId());
            }
        }
        if (parts.isEmpty()) return null;
        Clip c = finishClip(parts);
        GpuClip[] gc = GpuAttempt.gpuClips(c);
        if (gc == null || c.x1() <= c.x0() || c.y1() <= c.y0()) return null;
        return new GpuScrollLayer(node.id(), token, walker.resolveAt(nodeIndex).pose(), gc, new int[]{c.x0(), c.y0(), c.x1(), c.y1()});
    }

    /** 今のフレームの項目数。 */
    private int frameSize;
    /** 項目ごとのクリップ。入力の列が別の実体になった（{@link SceneWalker#generation}）ら捨てる。 */
    private ClipEntry[] clipCache = new ClipEntry[0];
    private long clipGeneration = -1;
    private boolean sawUnresolvedOwner;
    /**
     * 同じフレームの中で、同じクリップの元（{@link ClipRect} の同一実体）を持つ項目どうしで組んだクリップを分け合う（B12）。
     * 動く持ち主（アニメーション中の {@code overflow: hidden} の箱など）のクリップは、項目ごとの覚え（{@link #clipCache}）が
     * 毎フレーム外れるので、以前は持ち主の下の項目の数だけ同じクリップを組み直していた。照合は項目ごとの覚えと同じ
     * （持ち主の姿勢 {@link Resolved} の同一性）。フレームの頭で空にする。テストが切り替える（起動時のスイッチは足さない）。
     */
    private final java.util.IdentityHashMap<Object, SharedClip> frameClips = new java.util.IdentityHashMap<>();
    /** 診断: 同じフレームの別の項目が組んだクリップを使い回した回数。 */
    static final java.util.concurrent.atomic.LongAdder STAT_SHARED_CLIPS = new java.util.concurrent.atomic.LongAdder();

    private record SharedClip(Clip clip, int[] owner, Resolved[] ownerRes, int w, int h) { }

    /**
     * 同じフレームの中で、連鎖の部品（{@link ClipChainPart} の同一実体。入れ子のクリップは祖先の部品を共有する）と持ち主の姿勢が同じなら、
     * 組んだ部品（逆行列・デバイスの範囲）を使い回す（B12）。フレームの頭で空にする。
     */
    private final java.util.IdentityHashMap<ClipChainPart, PartMemo> frameParts = new java.util.IdentityHashMap<>();

    private record PartMemo(int ownerIndex, Resolved owner, ClipPart part, int w, int h) { }

    // ------------------------------------------------------------------ グループの層

    /** 開いている層を、項目の祖先のグループ {@code want} に合わせる（共通の手前までを残し、残りを閉じて重ね、足りない分を開く）。 */
    private void syncLayers(List<Group> want) {
        int common = 0;
        while (common < raster.depth() && common < want.size() && raster.layerAt(common).group.id().equals(want.get(common).id())) common++;
        while (raster.depth() > common) closeLayer();
        for (int i = common; i < want.size(); i++) openLayer(want.get(i));
    }

    private void openLayer(Group g) {
        queue.flush(); // 描画先が変わる前に、溜めた分を今の描画先へ
        CpuRaster.Layer top = raster.top();
        if (g.maskOf() != null) {
            // マスクの層。親（中身の層）が GPU の層なら GPU で、そうでなければ CPU で描く
            if (top != null && top.pixmap == null && GpuLayerAttempt.beginMask(route.current())) {
                raster.pushGpuLayer(g);
                return;
            }
        } else if (GpuLayerAttempt.beginGroup(route.current(), g)) {
            raster.pushGpuLayer(g); // GPU の層（画素は持たない）
            return;
        }
        raster.pushCpuLayer(g);
        route.enterCpuLayer(); // CPU の層の中は CPU が描く
    }

    /** 一番内側の層を閉じる。GPU の層は {@link GpuLayerAttempt} 経由で閉じさせ、CPU の層の合成は {@link CpuRaster#closeCpuLayer}。 */
    private void closeLayer() {
        queue.flush(); // 層を読んで合成する前に、層へ溜めた分を書き切る
        CpuRaster.Layer l = raster.popLayer();
        if (l.pixmap == null) {
            GpuLayerAttempt.end(route.current(), l.isMask());
            return;
        }
        boolean cpuLayer = raster.hasCpuLayer();
        route.leaveCpuLayer(cpuLayer);
        raster.closeCpuLayer(l, route.root(), cpuLayer);
    }

    // ------------------------------------------------------------------ 項目

    /** 項目の塗りを、クリップの範囲に掛からないために省いた数（累計。診断・テスト用）。 */
    private long culledItems;

    long culledItems() { return culledItems; }

    /** 診断用: 差分描画したフレーム数・全面描画したフレーム数。 */
    public long partialFrames() { return partialFrames; }

    long fullFrames() { return fullFrames; }

    long untouchedPixels() { return untouchedPixels; }

    /** 文字・影の広がり（箱の外へはみ出す分）の余白（デバイス画素）。 */
    private static final float CULL_PAD = 128f;

    /**
     * 項目の箱を姿勢で写した範囲が、クリップの外接（保持する層へ記録中でない通常の描画）に全く掛からないか。掛からない項目は何も塗らない。
     * 文字はあふれて箱の外へ出うるので、横方向は見ず、縦方向だけを余白つきで見る（箱の縦のはみ出しは行の高さ程度）。
     * 影の部品の矩形は、ぼかしが消える所までの広さを既に含む。シーン（3D）は箱の外へ描きうるので省かない。
     */
    private boolean outsideClip(DisplayItem el, Matrix2D pose, Clip clip) {
        if (recordOwner != null) { // 記録中は clip を見ない（null 可）
            // 保持する層への記録中はコンテナの切り取りを外しているので、層の実体（描画先と同じ大きさ）の範囲で判定する（段階 1b）
            return outsideBox(el, pose, 0, 0, raster.targetWidth(), raster.targetHeight());
        }
        return outsideBox(el, pose, clip.x0(), clip.y0(), clip.x1(), clip.y1());
    }

    static boolean outsideBoxFor(double top, double bottom, double left, double right, double pad, boolean sideways,
                                 double x0, double y0, double x1, double y1) {
        if (bottom + pad < y0 || top - pad > y1) return true;
        if (sideways) return false;
        return right + CULL_PAD < x0 || left - CULL_PAD > x1;
    }

    private boolean outsideBox(DisplayItem el, Matrix2D pose, double cx0, double cy0, double cx1, double cy1) {
        ElementType type = el.type();
        if (type == ElementType.SCENE || type == ElementType.NODE) return false;
        if (!pose.isFinite()) return false;
        double[] b = deviceBounds(pose, el.x(), el.y(), el.x() + el.width(), el.y() + el.height(), boundsScratch);
        double pad = CULL_PAD + el.height();
        boolean overflowsSideways = (el.text() != null && !el.text().isEmpty()) || type == ElementType.INPUT
                || type == ElementType.SELECT || type == ElementType.TEXTAREA;
        return outsideBoxFor(b[1], b[3], b[0], b[2], pad, overflowsSideways, cx0, cy0, cx1, cy1);
    }

    private void drawItem(DisplayItem el, int index, float flat) {
        syncClipGeneration();
        Resolved res = walker.resolveAt(index);
        if (res.opacity() <= 0.005f) return;
        float opacity = res.residual() * flat;
        if (opacity <= 0.005f) return;
        AnimatedValues anim = walker.animAt(index);
        // 保持層への記録中の判定は層の実体の範囲だけで決まりクリップを要さない。記録中の buildClip は使い回しの覚えが無く
        // 毎回作り直す（確保が出る）ので、画面外項目はその前に捨てる
        if (com.nishiyu.enderframelib.core.kernel.Shortcut.SOFT_CULL_OFFSCREEN.on() && recordOwner != null && outsideClip(el, res.pose(), null)) {
            culledItems++;
            return;
        }
        Clip clip = buildClip(index, el, anim);
        if (clip.empty()) return;
        if (com.nishiyu.enderframelib.core.kernel.Shortcut.SOFT_CULL_OFFSCREEN.on() && outsideClip(el, res.pose(), clip)) {
            culledItems++;
            return;
        }
        drawItemBody(el, anim, res, clip, opacity);
    }

    private final float[] scratchRx = new float[4], scratchRy = new float[4];

    private void drawItemBody(DisplayItem el, AnimatedValues anim, Resolved res, Clip clip, float opacity) {
        radii(el, anim, scratchRx, scratchRy);
        float[] rx = scratchRx, ry = scratchRy;
        ElementType type = el.type();

        if (type == ElementType.CIRCLE_BORDER && itemRaster.drawCircleBorder(el, anim, res, clip, opacity)) return;
        if (type == ElementType.ROUNDED_BORDER) {
            RoundedBorderSpec spec = el.meta() != null ? el.meta().roundedBorder() : null;
            if (spec != null) itemRaster.drawRoundedBorder(el, spec, rx, ry, res, clip, opacity);
            return;
        }
        if (type != ElementType.RANGE) itemRaster.drawBackground(el, anim, rx, ry, res, clip, opacity);
        if (itemRaster.drawHostTexture(el, rx, ry, res, clip, opacity)) return; // ホストが描いた中身（HostTextures）

        // 網羅形の switch 式（default なし）。経路を足したらここがコンパイルで直し漏れを知らせる
        boolean handled = switch (type.rasterRoute()) {
            case SCENE -> { itemRaster.drawScene3d(el, rx, ry, res, clip, opacity); yield true; }
            case TEXTAREA -> { textRaster.drawTextarea(el, anim, res, clip, opacity); yield true; }
            case RANGE -> { itemRaster.drawRange(el, anim, res, clip, opacity); yield true; }
            case PROGRESS -> { itemRaster.drawProgress(el, anim, rx, ry, res, clip, opacity); yield true; }
            case TOGGLE -> { itemRaster.drawToggle(el, anim, res, clip, opacity); yield true; }
            case IMAGE -> { itemRaster.drawImage(el, rx, ry, res, clip, opacity); yield true; }
            case TEXT_ALWAYS -> { textRaster.drawText(el, anim, res, clip, opacity); yield true; }
            case TEXT_IF_ANY -> {
                boolean any = el.text() != null && !el.text().isEmpty();
                if (any) textRaster.drawText(el, anim, res, clip, opacity);
                yield any;
            }
            case BORDER_ONLY -> false;
        };
    }

    /** 角ごとの横・縦の半径（左上・右上・右下・左下の順）。要素の箱に収まるよう切り詰める。 */
    private static void radii(DisplayItem el, AnimatedValues anim, float[] rx, float[] ry) {
        RenderMeta meta = el.meta();
        float w = el.width(), h = el.height();
        Corners c = meta != null ? meta.borderRadii() : null;
        if (c != null && c.isElliptical()) {
            float hw = w / 2f, hh = h / 2f;
            setEllipticalRadius(rx, ry, 0, c.topLeft(), c.topLeftV(), hw, hh);
            setEllipticalRadius(rx, ry, 1, c.topRight(), c.topRightV(), hw, hh);
            setEllipticalRadius(rx, ry, 2, c.bottomRight(), c.bottomRightV(), hw, hh);
            setEllipticalRadius(rx, ry, 3, c.bottomLeft(), c.bottomLeftV(), hw, hh);
            return;
        }
        if (c != null) {
            float limit = Math.min(w, h) / 2f;
            rx[0] = ry[0] = Math.max(0f, Math.min(c.topLeft(), limit));
            rx[1] = ry[1] = Math.max(0f, Math.min(c.topRight(), limit));
            rx[2] = ry[2] = Math.max(0f, Math.min(c.bottomRight(), limit));
            rx[3] = ry[3] = Math.max(0f, Math.min(c.bottomLeft(), limit));
            return;
        }
        if (anim.radiusData != null && anim.radiusData.length >= 4) {
            float limit = Math.min(w, h) / 2f;
            for (int i = 0; i < 4; i++) rx[i] = ry[i] = Math.max(0f, Math.min(anim.radiusData[i], limit));
            return;
        }
        rx[0] = rx[1] = rx[2] = rx[3] = 0f;
        ry[0] = ry[1] = ry[2] = ry[3] = 0f;
    }

    private static void setEllipticalRadius(float[] rx, float[] ry, int i, float hx, float vy, float hw, float hh) {
        float x = Math.min(hx, hw), y = Math.min(vy, hh);
        if (x <= 0f || y <= 0f) { rx[i] = 0f; ry[i] = 0f; }
        else { rx[i] = x; ry[i] = y; }
    }

    // ------------------------------------------------------------------ クリップ

    /** クリップ無しの項目に使う {@link ClipEntry#source} の番兵。 */
    private static final String NO_CLIP_SOURCE = "";

    /** 項目のクリップの結果と、それを作ったときの前提（描画先の大きさ・持ち主の姿勢・作った項目と元）。前提が同じ間は作り直さない。 */
    private static final class ClipEntry {
        Clip clip;
        int[] owner;
        Resolved[] ownerRes;
        int w, h;
        /** 作ったときの世代と項目、クリップの元（{@link ClipRect} の同一インスタンス。無クリップは {@link #NO_CLIP_SOURCE}）。 */
        long generation;
        DisplayItem item;
        Object source;
        /** 持ち主の id があるのに見つからなかった（後から現れると結果が変わるので世代をまたいで使い回さない）。 */
        boolean unresolvedOwner;
    }

    void syncClipGeneration() {
        if (clipGeneration != walker.generation()) {
            if (clipCache.length != frameSize) clipCache = java.util.Arrays.copyOf(clipCache, frameSize);
            clipGeneration = walker.generation();
        }
    }

    Clip buildClip(int index, DisplayItem el, AnimatedValues anim) {
        ClipEntry ce = clipCache[index];
        RenderMeta m0 = el.meta();
        ClipRect cr0 = m0 != null ? m0.clipRect() : null;
        Object source = cr0 != null ? cr0 : (anim.clipData == null ? NO_CLIP_SOURCE : null);
        // 項目の並びが変わった世代をまたぐときは、元の ClipRect（または無クリップ番兵）が同じものだけ使い回す
        boolean recording = recordOwner != null; // 保持する層へ記録中は、コンテナの切り取りを外すので使い回しの覚えを使わない
        if (!recording && ce != null && ce.w == raster.targetWidth() && ce.h == raster.targetHeight()
                && ((ce.generation == clipGeneration && ItemDiff.unchanged(ce.item, el))
                        || (source != null && ce.source == source && !ce.unresolvedOwner))) {
            boolean same = true;
            for (int k = 0; k < ce.owner.length && same; k++) {
                same = (ce.owner[k] < 0 ? null : walker.resolveAt(ce.owner[k])) == ce.ownerRes[k];
            }
            if (same) return ce.clip;
        }
        int tw = raster.targetWidth(), th = raster.targetHeight();
        boolean share = com.nishiyu.enderframelib.core.kernel.Shortcut.CLIP_SHARE.on() && !recording && cr0 != null;
        if (share) {
            SharedClip sc = frameClips.get(source);
            if (sc != null && sc.w() == tw && sc.h() == th && ownersUnchanged(sc.owner(), sc.ownerRes())) {
                if (ce == null) {
                    ce = new ClipEntry();
                    clipCache[index] = ce;
                }
                ce.clip = sc.clip();
                ce.owner = sc.owner();
                ce.ownerRes = sc.ownerRes();
                ce.generation = clipGeneration;
                ce.item = el;
                ce.source = source;
                ce.unresolvedOwner = false;
                ce.w = tw;
                ce.h = th;
                STAT_SHARED_CLIPS.increment();
                return sc.clip();
            }
        }
        sawUnresolvedOwner = false;
        clipOwnerCount = 0;
        RenderMeta meta = el.meta();
        ClipRect cr = meta != null ? meta.clipRect() : null;
        List<ClipPart> parts = clipPartsScratch;
        parts.clear();
        if (cr != null && !cr.chain().isEmpty()) {
            List<ClipChainPart> chain = cr.chain();
            for (int ci = 0, cn = chain.size(); ci < cn; ci++) {
                ClipChainPart p = chain.get(ci);
                if (recording && recordOwner.equals(p.ownerId())) continue;
                addChainPart(parts, p);
            }
        } else if (cr != null) {
            if (!(recording && recordOwner.equals(cr.ownerId()))) addClipPart(parts, cr.toArray(), cr.ownerId());
        } else if (anim.clipData != null) {
            addClipPart(parts, anim.clipData, null);
        }
        ce = new ClipEntry();
        ce.clip = finishClip(parts);
        ce.owner = clipOwnerCount == 0 ? NO_OWNERS : java.util.Arrays.copyOf(clipOwnerScratch, clipOwnerCount);
        ce.generation = clipGeneration;
        ce.item = el;
        ce.source = source;
        ce.unresolvedOwner = sawUnresolvedOwner;
        ce.ownerRes = clipOwnerCount == 0 ? NO_OWNER_RES : java.util.Arrays.copyOf(clipOwnerResScratch, clipOwnerCount);
        ce.w = raster.targetWidth();
        ce.h = raster.targetHeight();
        if (!recording) clipCache[index] = ce;
        if (share && !ce.unresolvedOwner) {
            if (frameClips.size() >= 4096) frameClips.clear();
            frameClips.put(source, new SharedClip(ce.clip, ce.owner, ce.ownerRes, ce.w, ce.h));
        }
        return ce.clip;
    }

    /** クリップを組んだときの持ち主の姿勢（{@link Resolved} の同一実体）が今も同じか。 */
    private boolean ownersUnchanged(int[] owner, Resolved[] ownerRes) {
        for (int k = 0; k < owner.length; k++) {
            if ((owner[k] < 0 ? null : walker.resolveAt(owner[k])) != ownerRes[k]) return false;
        }
        return true;
    }

    /**
     * 同じ空間（同じ逆行列・同じ倍率）で、別の部品の領域をすっかり含む直角のクリップは、重ねても何も変わらないので除く。
     * 入れ子の箱が同じ範囲で切るとき（親が直角・子が丸角など）に、GPU が受け取れる部品数（{@link com.nishiyu.enderframelib.core.paint.port.GpuSink#MAX_CLIPS}）を超えて、
     * 図形が CPU 層へ落ちるのを防ぐ。CPU の被覆率も部品の積なので、同じ縁を二重に掛けていた分（c²）が c になる以外は変わらない。
     */
    static List<ClipPart> dropRedundantSquareParts(List<ClipPart> parts) {
        int n = parts.size();
        if (n < 2) return parts;
        boolean[] drop = null;
        for (int i = 0; i < n; i++) {
            ClipPart a = parts.get(i);
            if (!isSquare(a)) continue;
            for (int j = 0; j < n; j++) {
                if (j == i || (drop != null && drop[j])) continue;
                ClipPart b = parts.get(j);
                if (!a.inv().equals(b.inv()) || Float.compare(a.scale(), b.scale()) != 0) continue;
                boolean contained = b.x0() >= a.x0() && b.y0() >= a.y0() && b.x1() <= a.x1() && b.y1() <= a.y1();
                if (!contained) continue;
                boolean same = b.x0() == a.x0() && b.y0() == a.y0() && b.x1() == a.x1() && b.y1() == a.y1();
                // 同じ矩形どうしの直角は、前にあるほうを残す（両方を落とさない）
                if (same && isSquare(b) && j > i) continue;
                if (drop == null) drop = new boolean[n];
                drop[i] = true;
                break;
            }
        }
        if (drop == null) return parts;
        List<ClipPart> kept = new ArrayList<>(n);
        for (int i = 0; i < n; i++) if (!drop[i]) kept.add(parts.get(i));
        return kept;
    }

    private static boolean isSquare(ClipPart p) {
        for (int k = 0; k < 4; k++) if (p.rx()[k] != 0f || p.ry()[k] != 0f) return false;
        return true;
    }

    Clip finishClip(List<ClipPart> parts) {
        parts = dropRedundantSquareParts(parts);
        // 部品が 1 つ・0 個のクリップ（ほとんど）は小さい不変の一覧で持つ（可変の一覧と中の配列を作らない。部品の一覧は読むだけ）
        // 2 つ以上は不変の写しにする（呼び出し側の作業用の一覧を持ち続けない）
        parts = parts.isEmpty() ? List.of() : parts.size() == 1 ? List.of(parts.get(0)) : List.copyOf(parts);
        int x0 = 0, y0 = 0, x1 = raster.targetWidth(), y1 = raster.targetHeight();
        double ix0 = Double.NEGATIVE_INFINITY, iy0 = Double.NEGATIVE_INFINITY, ix1 = Double.POSITIVE_INFINITY, iy1 = Double.POSITIVE_INFINITY;
        for (int i = 0, n = parts.size(); i < n; i++) {
            ClipPart p = parts.get(i);
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

    /** 連鎖の部品 1 つ分（{@link #addClipPart} と同じ結果。同じフレームの同じ部品・同じ姿勢なら使い回す）。 */
    private void addChainPart(List<ClipPart> parts, ClipChainPart p) {
        if (!com.nishiyu.enderframelib.core.kernel.Shortcut.CLIP_SHARE.on() || recordOwner != null) {
            addClipPart(parts, p.rectView(), p.ownerId(), p.radiiXView(), p.radiiYView());
            return;
        }
        int tw = raster.targetWidth(), th = raster.targetHeight();
        int ix = p.ownerId() == null ? -1 : walker.indexOf(p.ownerId());
        Resolved owner = ix >= 0 ? walker.resolveAt(ix) : null;
        PartMemo memo = frameParts.get(p);
        if (memo != null && memo.ownerIndex() == ix && memo.owner() == owner && memo.w() == tw && memo.h() == th
                && (ix >= 0 || p.ownerId() == null)) {
            if (clipOwnerCount == clipOwnerScratch.length) {
                clipOwnerScratch = java.util.Arrays.copyOf(clipOwnerScratch, clipOwnerCount * 2);
                clipOwnerResScratch = java.util.Arrays.copyOf(clipOwnerResScratch, clipOwnerCount * 2);
            }
            clipOwnerScratch[clipOwnerCount] = ix;
            clipOwnerResScratch[clipOwnerCount++] = owner;
            parts.add(memo.part());
            return;
        }
        int before = parts.size();
        addClipPart(parts, p.rectView(), p.ownerId(), p.radiiXView(), p.radiiYView());
        // 持ち主の id があるのに見つからない部品は覚えない（後から現れると結果が変わる）
        if (parts.size() == before + 1 && (ix >= 0 || p.ownerId() == null)) {
            if (frameParts.size() >= 4096) frameParts.clear();
            frameParts.put(p, new PartMemo(ix, owner, parts.get(before), tw, th));
        }
    }

    // buildClip の作業表（持ち主の位置と姿勢。1 つのクリップの部品の数だけ）
    private int[] clipOwnerScratch = new int[4];
    private Resolved[] clipOwnerResScratch = new Resolved[4];
    int clipOwnerCount;
    /** buildClip が部品を集める作業用の一覧（finishClip が持ち続ける分は写す）。 */
    private final List<ClipPart> clipPartsScratch = new ArrayList<>(4);
    private static final int[] NO_OWNERS = new int[0];
    private static final Resolved[] NO_OWNER_RES = new Resolved[0];

    void addClipPart(List<ClipPart> parts, float[] rect, String ownerId) {
        addClipPart(parts, rect, ownerId, null, null);
    }

    /** {@code preRx} / {@code preRy} は、{@code rect} の角の半径を先に分けたもの（読むだけ。null なら {@code rect} から作る）。 */
    void addClipPart(List<ClipPart> parts, float[] rect, String ownerId, float[] preRx, float[] preRy) {
        if (rect == null || rect.length < 4) return;
        Matrix2D pose = frame;
        int ownerIndex = -1;
        Resolved owner = null;
        if (ownerId != null) {
            int ix = walker.indexOf(ownerId);
            if (ix >= 0) {
                ownerIndex = ix;
                owner = walker.resolveAt(ix);
                pose = owner.pose();
            } else {
                sawUnresolvedOwner = true;
            }
        }
        if (clipOwnerCount == clipOwnerScratch.length) {
            clipOwnerScratch = java.util.Arrays.copyOf(clipOwnerScratch, clipOwnerCount * 2);
            clipOwnerResScratch = java.util.Arrays.copyOf(clipOwnerResScratch, clipOwnerCount * 2);
        }
        clipOwnerScratch[clipOwnerCount] = ownerIndex;
        clipOwnerResScratch[clipOwnerCount++] = owner;
        Matrix2D inv = pose.invert();
        if (inv == null) return;
        float[] rx, ry;
        if (rect.length <= 4) {
            rx = ZERO4;
            ry = ZERO4;
        } else if (preRx != null && preRy != null) {
            rx = preRx;
            ry = preRy;
        } else {
            rx = new float[4];
            ry = new float[4];
            for (int i = 0; i < 4; i++) {
                rx[i] = rect.length > 4 + i ? rect[4 + i] : 0f;
                ry[i] = rect.length >= 12 ? rect[8 + i] : rx[i];
            }
        }
        double[] b = deviceBounds(pose, rect[0], rect[1], rect[2], rect[3], scratchBounds4);
        float scale = (float) pose.meanScale();
        float reach = 0.5f / Math.max(scale, 0.01f) + 1e-3f;
        float mrx = SoftShapes.maxOf(rx), mry = SoftShapes.maxOf(ry);
        // 端末空間での「被覆率が 1 と分かっている内側」。軸平行の姿勢のときだけ（そうでなければ空）。
        double dev0 = Double.POSITIVE_INFINITY, dev1 = Double.POSITIVE_INFINITY;
        double dev2 = Double.NEGATIVE_INFINITY, dev3 = Double.NEGATIVE_INFINITY;
        if (pose.b() == 0.0 && pose.c() == 0.0) {
            double ax = pose.applyX(rect[0] + mrx + reach, 0), bx = pose.applyX(rect[2] - mrx - reach, 0);
            double ay = pose.applyY(0, rect[1] + mry + reach), by = pose.applyY(0, rect[3] - mry - reach);
            dev0 = Math.min(ax, bx) + 1e-3;
            dev1 = Math.min(ay, by) + 1e-3;
            dev2 = Math.max(ax, bx) - 1e-3;
            dev3 = Math.max(ay, by) - 1e-3;
        }
        parts.add(new ClipPart(inv, scale, rect[0], rect[1], rect[2], rect[3], rx, ry,
                (int) Math.floor(b[0]), (int) Math.floor(b[1]), (int) Math.ceil(b[2]), (int) Math.ceil(b[3]),
                rect[0] + mrx + reach, rect[1] + mry + reach, rect[2] - mrx - reach, rect[3] - mry - reach,
                dev0, dev1, dev2, dev3));
    }

    /** 四隅を写した外接矩形 {@code {minX, minY, maxX, maxY}} を {@code out} に書く（割り当てなし）。 */
    static double[] deviceBounds(Matrix2D m, double x0, double y0, double x1, double y1, double[] out) {
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

    /**
     * 局所の矩形 {@code (bx0,by0)-(bx1,by1)} を姿勢 {@code pose} で写した範囲の画素ごとに {@code shader} を呼び、
     * クリップと不透明度を掛けて重ねる。画素の中心を逆変換した点で評価する。
     */
    void paint(Matrix2D pose, float bx0, float by0, float bx1, float by1, Clip clip, float opacity, Shader shader) {
        paint(pose, bx0, by0, bx1, by1, clip, opacity, shader, null);
    }

    // 記録時（1 スレッド）だけ使う作業表
    private final int[] fastDx0 = new int[Fast.MAX], fastDx1 = new int[Fast.MAX], fastDy0 = new int[Fast.MAX], fastDy1 = new int[Fast.MAX];
    private final int[] fastCol = new int[Fast.MAX];
    private final int[] roundCol = new int[Fast.MAX_ROUND];

    // ------------------------------------------------------------------ 画素を書く命令とタイル実行

    final OpPool pool = new OpPool();
    final TileQueue queue = new TileQueue(pool::release);

    /**
     * 同上。{@code fast} の領域は、軸平行の姿勢のとき距離の計算なしに行ごとの塗り（または省略）で済ませる（結果は画素ごとの評価と同じ）。
     * 縁・角・クリップの被覆率が 1 と言えない画素だけが従来の経路を通る。{@code fastSolid=false} で無効（照合用）。
     * {@code fast} がある（呼び出し側がシェーダーを純粋と保証する）塗りは、溜めてタイルで実行する。
     */
    void paint(Matrix2D pose, float bx0, float by0, float bx1, float by1, Clip clip, float opacity, Shader shader,
                       Fast fast) {
        if (opacity <= 0f || !(bx1 > bx0) || !(by1 > by0)) return;
        Matrix2D inv = pose.invert();
        if (inv == null || !inv.isFinite()) return;
        int[] r = paintRange(pose, bx0, by0, bx1, by1, clip);
        if (r == null) return;
        PixelOps.PaintOp op = pool.acquirePaint();
        op.nr = 0;
        op.nRound = 0;
        op.px = raster.target().pixels();
        op.w = raster.targetWidth();
        op.x0 = r[0]; op.y0 = r[1]; op.x1 = r[2]; op.y1 = r[3];
        op.pose = pose; op.inv = inv; op.clip = clip; op.opacity = opacity; op.shader = shader; op.fast = fast;
        op.clipped = !clip.parts().isEmpty();
        boolean axis = com.nishiyu.enderframelib.core.kernel.Shortcut.SOFT_FAST_SOLID.on() && fast != null && pose.b() == 0.0 && pose.c() == 0.0;
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
        queue.submit(op, fast != null);
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




    /**
     * 局所の矩形を姿勢で写してクリップと交わらせた画素の範囲 {@code {x0, y0, x1, y1}}（空なら null）。汚れた範囲も広げる。
     * 差分描画のフレームでは、実際に塗る範囲をダメージの外接矩形に収める（外の画素は前のフレームのまま残す）。
     */
    int[] paintRange(Matrix2D pose, float bx0, float by0, float bx1, float by1, Clip clip) {
        double[] b = deviceBounds(pose, bx0, by0, bx1, by1, boundsScratch);
        int x0 = Math.max(clip.x0(), (int) Math.floor(b[0])), y0 = Math.max(clip.y0(), (int) Math.floor(b[1]));
        int x1 = Math.min(clip.x1(), (int) Math.ceil(b[2])), y1 = Math.min(clip.y1(), (int) Math.ceil(b[3]));
        if (x1 <= x0 || y1 <= y0) return null;
        if (partialActive) {
            x0 = Math.max(x0, damage.x0());
            y0 = Math.max(y0, damage.y0());
            x1 = Math.min(x1, damage.x1());
            y1 = Math.min(y1, damage.y1());
            if (x1 <= x0 || y1 <= y0) return null;
        }
        raster.dirty().add(x0, y0, x1, y1);
        return new int[]{x0, y0, x1, y1};
    }

    /** 角丸矩形を単色で塗る。 */
    void fillRoundRect(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                               int argb, Clip clip, float opacity) {
        if ((argb >>> 24) == 0) return;
        if (GpuAttempt.fillRoundRect(route.current(), pose, x0, y0, x1, y1, rx, ry, argb, clip, opacity)) return;
        recordFillRoundRect(pose, x0, y0, x1, y1, rx, ry, argb, clip, opacity);
    }

    /** 単色の角丸矩形の CPU 命令を記録する（GPU に渡せなかったときだけ）。 */
    private void recordFillRoundRect(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                     int argb, Clip clip, float opacity) {
        final float[] crx = freezeRadii(rx), cry = freezeRadii(ry);
        int pm = Pixmap.premultiply(argb);
        float scale = (float) pose.meanScale();
        float m = 1f / Math.max(scale, 0.01f);
        // 縁の 0.5 画素ぶんと角の丸みを除いた内側は被覆率がちょうど 1。そこは距離を測らず塗る
        Fast fast = new Fast();
        fast.roundRect(x0, y0, x1, y1, crx, cry, 0.5f / Math.max(scale, 0.01f) + 1e-3f, pm);
        paint(pose, x0 - m, y0 - m, x1 + m, y1 + m, clip, opacity, (lx, ly) -> {
            float cov = SoftShapes.coverage(SoftShapes.sdRoundRect(lx, ly, x0, y0, x1, y1, crx, cry), scale);
            return cov <= 0f ? 0 : SoftShapes.scalePremul(pm, cov);
        }, fast);
    }

    static final float[] ZERO4 = new float[4];

    static float[] freezeRadii(float[] a) {
        if (a == ZERO4 || (a[0] == 0f && a[1] == 0f && a[2] == 0f && a[3] == 0f)) return ZERO4;
        return a.clone();
    }

    void fillRect(Matrix2D pose, float x0, float y0, float x1, float y1, int argb, Clip clip, float opacity) {
        fillRoundRect(pose, x0, y0, x1, y1, ZERO4, ZERO4, argb, clip, opacity);
    }

    private static float[] uniform(float r) {
        return new float[]{r, r, r, r};
    }

    // ------------------------------------------------------------------ 背景・影・グラデーション

    void drawGradient(ShaderGradient g, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                              Resolved res, Clip clip, float opacity) {
        if (GpuAttempt.gradient(route.current(), res.pose(), x0, y0, x1, y1, rx, ry, g, clip, opacity)) return;
        itemRaster.recordGradient(g, x0, y0, x1, y1, rx, ry, res, clip, opacity);
    }




    // ------------------------------------------------------------------ 枠線


    // ------------------------------------------------------------------ 画像

    /** 事前乗算の画素の双一次補間（範囲の外は端の画素）。 */
    static int bilinear(Pixmap pm, float u, float v) {
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

    final float[] scratchRange = new float[3];
    final float[] scratchRadA = new float[4], scratchRadB = new float[4];

    static float[] fill4(float[] dst, float v) {
        dst[0] = dst[1] = dst[2] = dst[3] = v;
        return dst;
    }

    static float[] fillLeft2(float[] dst, float r) {
        dst[0] = r; dst[1] = 0f; dst[2] = 0f; dst[3] = r;
        return dst;
    }

    static final float RANGE_TRACK_HEIGHT = 8f, RANGE_THUMB_SIZE = 16f;

    /** 半透明の {@code top} を不透明な {@code bottom} に重ねた色（sRGB のまま合成）。 */
    static int over(int top, int bottom) {
        float a = ((top >>> 24) & 0xFF) / 255f;
        int out = bottom & 0xFF000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            float t = (top >>> shift) & 0xFF, b = (bottom >>> shift) & 0xFF;
            out |= Math.round(t * a + b * (1 - a)) << shift;
        }
        return out;
    }

}
