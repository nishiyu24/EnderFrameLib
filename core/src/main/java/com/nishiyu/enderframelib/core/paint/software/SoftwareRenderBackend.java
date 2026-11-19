package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.scene3d.Scene3DWorld;
import java.util.function.LongFunction;

import com.nishiyu.enderframelib.core.paint.AnimatedValues;

import com.nishiyu.enderframelib.core.paint.model.ClipChainPart;

import com.nishiyu.enderframelib.core.paint.gpu.GpuClip;
import com.nishiyu.enderframelib.core.paint.gpu.GpuScrollLayer;
import com.nishiyu.enderframelib.core.paint.gpu.GpuSink;
import com.nishiyu.enderframelib.core.paint.CssAnimator;
import com.nishiyu.enderframelib.core.paint.ItemDiff;
import com.nishiyu.enderframelib.core.paint.PaintSceneDriver;
import com.nishiyu.enderframelib.core.paint.RetainPlan;
import com.nishiyu.enderframelib.core.paint.PaintSceneDriver.Group;
import com.nishiyu.enderframelib.core.paint.PaintSceneDriver.Resolved;
import com.nishiyu.enderframelib.core.paint.RenderBackend;
import com.nishiyu.enderframelib.core.paint.ShaderGradientEmulator;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.Corners;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.LayerSpec;
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
    /** DamagePlanner（切り出した協力クラス）。 */
    private final DamagePlanner damagePlanner = new DamagePlanner(this);
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
    final PaintResources resources;
    int clearColor = 0;

    final GlyphCache glyphCache = new GlyphCache();

    // 1 フレームの間だけ使う状態
    /** {@link #paintRange} の作業用（描画は 1 スレッドで、使い終えてから次を呼ぶ）。 */
    final double[] boundsScratch = new double[4];
    private Matrix2D frame = Matrix2D.IDENTITY;

    // ------------------------------------------------------------------ 差分描画（Tile Dirty Bitset）

    /**
     * 変更のあった項目の範囲だけを塗り直すか（{@code -Denderframe.soft.nodamage=true} で無効）。
     * 無効にすると毎フレーム全面をクリアして描く（疑うときの切り分け用。絵は同じでなければならない）。
     */
    static volatile boolean damageEnabled = !Boolean.getBoolean("enderframe.soft.nodamage");
    /** 診断: 損傷範囲の決定の理由を出力する（フレームごとに読む。{@code -Denderframe.soft.damagedebug=true}）。 */
    static boolean damageDebug() {
        return Boolean.getBoolean("enderframe.soft.damagedebug");
    }

    /** このフレームで塗り直す範囲（タイル境界まで広げた外接矩形）。 */
    final DamageRegion damage = new DamageRegion();
    /** 前のフレームの {@link DamagePlanner#planDamage} を始めた時点の塗り・移動量の書き込みの通し番号。 */
    int prevEpoch;
    /** 前のフレームの表示リスト（項目の同一性の比較と、旧範囲の持ち主）。 */
    List<DisplayItem> prevItems;
    boolean prevValid;
    /** 前のフレームの描画がアニメーションの途中だった（次のフレームは全面を描き直す）。 */
    boolean prevHadActive;
    /** 前のフレームで、不透明度が動く項目があった（あれば次は全面。色・変形の遷移は項目単位で塗り直す）。 */
    boolean prevHadGeometryActive;
    /** 項目ごとの動き（{@link PaintSceneDriver#ACT_IDLE} など）。今のフレームと前のフレーム。 */
    byte[] activity = new byte[0], prevActivity = new byte[0];
    int prevClearColor;
    boolean prevCullOffscreen = true;
    /** 直近に描いたスクロール節の姿勢平行移動成分 (key -> [tx, ty])。 */
    final java.util.HashMap<Long, double[]> prevScrollPoses = new java.util.HashMap<>();

    /** 項目ごとの、実際に塗った範囲（{@code x0,y0,x1,y1}。空なら x1 <= x0）。前のフレームと今のフレームで入れ替える。 */
    int[] prevExtent = new int[0], curExtent = new int[0];
    /** 項目ごとの、描いたときのクリップの外接（{@code x0,y0,x1,y1}）。新しい範囲の見積もりをクリップに収めるのに使う。 */
    int[] prevClip = new int[0], curClip = new int[0];
    /** このフレームで描かない（前のフレームの画素をそのまま残す）項目。 */
    boolean[] skipBuf = new boolean[0];
    /** このフレームで描き直す項目（項目自身が変わったか、動いた節の子孫）。 */
    boolean[] dirtyBuf = new boolean[0];
    /** 動いた節の子孫（姿勢が親から変わる）か。 */
    boolean[] movedBuf = new boolean[0];
    /** このフレームの差分描画が有効（{@link Painter#skipItem} を効かせる）。 */
    private boolean partialActive;
    /** 変更項目の塗りがダメージ範囲の外へ出た（全面描画へ切り替える）。 */
    private boolean damageAbort;
    /** このフレームの描画がアニメーションの途中か（次のフレームの判定に使う）。 */
    boolean frameHadActive;
    boolean frameHadGeometryActive;
    /** 実際に塗った範囲を記録するか（{@link #renderOnto} では要らない）。 */
    private boolean recordExtents;
    /** いま描いている項目の添字（{@link #paintRange} が範囲を記録する）。 */
    private int currentItem = -1;
    /** いま描いている項目が描き直しの対象か（塗りがダメージの外へ出たら全面へ切り替える）。 */
    private boolean currentItemDirty;
    /** 見積もりの作業用。 */
    final int[] estimateScratch = new int[4];
    final int[] planClipScratch = new int[4];

    /** 診断用: 差分描画したフレーム・全面描画したフレーム・見積もり外れで全面へ切り替えたフレーム・省いた項目の累計。 */
    long partialFrames, fullFrames, abortedFrames, skippedItems;
    /** 差分描画を諦めた理由の件数（1=前フレームが動作中 2=無効 3=初回 4=背景/カリング変化 5=不安定な項目列 6=動作中の項目 7=項目の増減・種類変化 8=volatile 9=グループ）。 */
    final long[] fullWhy = new long[11];

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
        this.dprFrame = Matrix2D.scale(devicePixelRatio, devicePixelRatio);
        this.raster = new CpuRaster(Math.max(1, Math.round(cssW * devicePixelRatio)), Math.max(1, Math.round(cssH * devicePixelRatio)));
    }

    /** 根の画素を確保済みか（診断・テスト用）。 */
    public boolean isRootAllocated() {
        return raster.isRootAllocated();
    }

    public SoftwareRenderBackend fonts(FontResolver resolver) {
        resources.fonts(resolver);
        return this;
    }

    public SoftwareRenderBackend images(ImageProvider provider) {
        resources.images(provider);
        return this;
    }

    public SoftwareRenderBackend scene3dWorlds(LongFunction<Scene3DWorld> provider) {
        resources.scene3dWorlds(provider);
        return this;
    }

    public Scene3DWorld scene3dWorld(long worldId) {
        return resources.scene3dWorld(worldId);
    }

    /** フレームの最初に塗る色（非乗算の ARGB。既定は透明）。 */
    public SoftwareRenderBackend clearColor(int argb) {
        this.clearColor = Pixmap.premultiply(argb);
        return this;
    }

    /** 描画先（事前乗算の ARGB）。 */
    @Override
    public Pixmap pixmap() { return raster.root(); }

    @Override
    public float devicePixelRatio() { return dpr; }

    // ------------------------------------------------------------------ フレーム

    @Override
    public String sessionId() {
        return sessionId;
    }

    /**
     * 描画先を {@link #clearColor} で塗り直し、{@code items} を時刻 {@code nowMs}（ミリ秒）の見た目で描く。
     *
     * <p>前のフレームと同じ表示リスト（変更のあった項目だけが差し替わったもの）なら、変更項目の旧範囲と新範囲が掛かる
     * 64×64 タイルだけをクリアし、その外接矩形にクリップして描き直す。描き直す必要のない項目は
     * {@link Painter#skipItem} で走査から外す。見積もりが外れた（変更項目がダメージの外へ塗った）ときは
     * 全面を描き直すので、絵は常に全面描画と同じになる。</p>
     */
    @Override
    public void render(List<DisplayItem> items, long nowMs) {
        if (items == null || items.isEmpty()) {
            raster.clearAll(clearColor);
            prevValid = false;
            prevItems = null;
            return;
        }
        route.begin(null);
        driver.setScrollSnapDpr(0f);
        raster.beginFrame();
        beginFrame(items);
        boolean partial = false;
        recordExtents = true;
        try {
            driver.prepare(items, nowMs, frame);
            partial = damagePlanner.planDamage(items, nowMs);
            if (partial) raster.clearTiles(damage, clearColor);
            else raster.clearAll(clearColor);
            partialActive = partial;
            damageAbort = false;
            driver.run(items, nowMs, frame, painter);
            queue.flush();
            if (partial && damageAbort) { // 見積もりより外へ塗った: 全面でやり直す（絵は同じ）
                abortedFrames++;
                partial = false;
                partialActive = false;
                damageAbort = false;
                raster.clearAll(clearColor);
                damagePlanner.resetCurExtents(items.size());
                driver.run(items, nowMs, frame, painter);
                queue.flush();
            }
        } finally {
            queue.discard(); // 例外のときは捨てる（描画先は不定）
            raster.endFrame();
            route.end();
            partialActive = false;
            recordExtents = false;
            currentItem = -1;
        }
        // 描く前の見積もり（planDamage の冒頭）は、項目が差し替わって補間の状態が新しくなる前の古い状態を見ている（項目の増減のフレームなど）。
        // 描いて状態が更新された今の値で取り直す。そうしないと、遷移中の項目が「止まっている」と記録され、次のフレームで遷移の
        // 続き・終わり（最終色）を塗り直さず、途中の色が残る（A6b ③④）
        byte m = driver.fillActivity(activity, items.size(), nowMs);
        frameHadActive = m != PaintSceneDriver.ACT_IDLE;
        frameHadGeometryActive = m == PaintSceneDriver.ACT_OPACITY;
        damagePlanner.commitFrame(items, partial);
    }

    /**
     * 描画先を塗り直さずに {@code items} を重ねる。{@code sink} があれば、単色の角丸矩形と角丸の枠線はそちらへ渡す
     * （受け取られなかったものだけ CPU が描く）。GPU 版が文字・画像などの CPU 描画を重ねるのに使う。
     */
    public void renderOnto(List<DisplayItem> items, long nowMs, GpuSink sink) {
        // 全面を塗っていない・別の描画先かもしれないので、差分描画の前提を捨てる
        prevValid = false;
        prevItems = null;
        cpuOpsOrdered = cpuOps - 1; // フレームの頭は必ず順序づけを確かめる
        drawFrame(items, nowMs, sink);
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
        this.retainInfo.clear();
        this.recordOwner = null;
        if (!frameClips.isEmpty()) frameClips.clear();
        if (!frameParts.isEmpty()) frameParts.clear();
    }

    private void drawFrame(List<DisplayItem> items, long nowMs, GpuSink gpu) {
        if (items == null || items.isEmpty()) return;
        partialActive = false;
        recordExtents = false;
        route.begin(gpu);
        driver.setScrollSnapDpr(gpu != null && gpu.snapsScrollOffsets() ? dpr : 0f);
        raster.beginFrame();
        beginFrame(items);
        try {
            // 走査（アニメーション補間・親子の変形・グループの鎖）は PaintSceneDriver が受け持ち、ここは画素を塗るだけ
            driver.run(items, nowMs, frame, painter);
            queue.flush(); // 開いたままの層の中身も、層を捨てる前に書き切る
        } finally {
            queue.discard(); // 例外のときは捨てる（描画先は不定）
            raster.endFrame();
            route.end();
            currentItem = -1;
        }
    }

    final PaintSceneDriver driver;

    /** 直近に描いた {@code items} に、時刻 {@code nowMs} でまだ動いているアニメーションがあるか。 */
    public boolean hasActiveAnimation(List<DisplayItem> items, long nowMs) {
        return driver.anyActiveAt(items, nowMs);
    }
    private final Painter painter = new Painter() {
        @Override
        public boolean skipItem(int index) {
            if (!partialActive || index < 0 || index >= skipBuf.length || !skipBuf[index]) return false;
            skippedItems++;
            return true;
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
        public RetainPlan planRetained(DisplayItem node, int nodeIndex, int from, int to) {
            return SoftwareRenderBackend.this.planRetained(node, nodeIndex, from, to);
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
        public double[] cullWindow(DisplayItem node, int nodeIndex) {
            // 記録中も通常描画でも、項目のクリップは描画先 [0, 高さ] に収まる。その外へ CULL_PAD + 項目の高さ以上離れた項目は塗られない（outsideBox と同じ式）
            if (!cullOffscreen) return null;
            scratchCullWindow[0] = 0;
            scratchCullWindow[1] = raster.targetHeight();
            scratchCullWindow[2] = CULL_PAD;
            return scratchCullWindow;
        }

        @Override
        public boolean endRetained(DisplayItem node, int nodeIndex) {
            recordOwner = null;
            return GpuLayerAttempt.endScroll(route.current(), retainInfo.get(nodeIndex));
        }

        @Override
        public boolean tryPatchItems(List<DisplayItem> items, int[] indices, int count) {
            return SoftwareRenderBackend.this.tryPatchItems(items, indices, count);
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
    private final double[] scratchCullWindow = new double[3];

    private static String ownerKeyOf(DisplayItem node) {
        String id = node.id();
        int suffixLen = PaintSceneDriver.SCROLL_CONTENT_SUFFIX.length();
        if (id.endsWith(PaintSceneDriver.SCROLL_CONTENT_SUFFIX)) {
            String pid = node.hints().parentId();
            if (pid != null && pid.length() == id.length() - suffixLen) return pid;
            return id.substring(0, id.length() - suffixLen);
        }
        return id;
    }

    private String cachedOwnerKeyOf(DisplayItem node, int nodeIndex) {
        Object token = driver.contentToken();
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

    private RetainPlan planRetained(DisplayItem node, int nodeIndex, int from, int to) {
        if (route.current() == null || recordOwner != null) return RetainPlan.NONE;
        Object token = driver.contentToken();
        if (eligibleToken != token) {
            eligibleToken = token;
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
            verdict = rangeIsRetainable(driver.items(), from, to) ? (byte) 2 : (byte) 1;
            retainEligible[nodeIndex] = verdict;
        }
        if (verdict != 2) return RetainPlan.NONE;

        String owner = retainOwner[nodeIndex];
        if (owner == null) {
            owner = ownerKeyOf(node);
            retainOwner[nodeIndex] = owner;
        }
        List<DisplayItem> items = driver.items();
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
                        int ix = driver.indexOf(p.ownerId());
                        Matrix2D pose = ix >= 0 ? driver.resolveAt(ix).pose() : frame;
                        double[] b = deviceBounds(pose, p.rectAt(0), p.rectAt(1), p.rectAt(2), p.rectAt(3), scratchBounds4);
                        x0 = Math.max(x0, (int) Math.floor(b[0]));
                        y0 = Math.max(y0, (int) Math.floor(b[1]));
                        x1 = Math.min(x1, (int) Math.ceil(b[2]));
                        y1 = Math.min(y1, (int) Math.ceil(b[3]));
                        partCount++;
                    }
                }
            } else if (owner.equals(cr.ownerId())) {
                int ix = driver.indexOf(cr.ownerId());
                Matrix2D pose = ix >= 0 ? driver.resolveAt(ix).pose() : frame;
                double[] b = deviceBounds(pose, cr.minX(), cr.minY(), cr.maxX(), cr.maxY(), scratchBounds4);
                x0 = Math.max(x0, (int) Math.floor(b[0]));
                y0 = Math.max(y0, (int) Math.floor(b[1]));
                x1 = Math.min(x1, (int) Math.ceil(b[2]));
                y1 = Math.min(y1, (int) Math.ceil(b[3]));
                partCount++;
            }
        }
        if (partCount == 0 || partCount > com.nishiyu.enderframelib.core.paint.gpu.GpuSink.MAX_CLIPS || x1 <= x0 || y1 <= y0) {
            return RetainPlan.NONE;
        }
        Matrix2D nodePose = driver.resolveAt(nodeIndex).pose();
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
        String owner = cachedOwnerKeyOf(node, nodeIndex);
        List<DisplayItem> items = driver.items();
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
        return new GpuScrollLayer(node.id(), token, driver.resolveAt(nodeIndex).pose(), gc, new int[]{c.x0(), c.y0(), c.x1(), c.y1()});
    }

    /** 今のフレームの項目数。 */
    private int frameSize;
    /** 項目ごとのクリップ。項目の索引が作り直された（{@link PaintSceneDriver#generation}）ら捨てる。 */
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
    static volatile boolean CLIP_SHARE = true;
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

    /** 照合・切り分け用: false なら、クリップの外にある項目も塗りの経路へ渡す（結果の画素は同じはず）。 */
    static volatile boolean cullOffscreen = !Boolean.getBoolean("enderframe.soft.nocull");
    /** 項目の塗りを、クリップの範囲に掛からないために省いた数（累計。診断・テスト用）。 */
    private long culledItems;

    long culledItems() { return culledItems; }

    /** ドライバ段で画面外ブロックとして走査から外した数（累計。診断・テスト用）。 */
    public long rangeCulledItems() { return driver.rangeCulledItems(); }

    /** 診断用: 差分描画したフレーム数・全面描画したフレーム数・見積もり外れで全面へ切り替えた回数・省いた項目の延べ数。 */
    long[] fullReasons() { return fullWhy.clone(); }
    long partialFrames() { return partialFrames; }

    long fullFrames() { return fullFrames; }

    long damageAborts() { return abortedFrames; }

    long skippedItems() { return skippedItems; }

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
        currentItem = recordExtents && index >= 0 && curExtent.length >= (index + 1) * 4 ? index : -1;
        currentItemDirty = partialActive && index >= 0 && index < dirtyBuf.length && dirtyBuf[index];
        Resolved res = driver.resolveAt(index);
        if (res.opacity() <= 0.005f) return;
        float opacity = res.residual() * flat;
        if (opacity <= 0.005f) return;
        AnimatedValues anim = driver.animAt(index);
        boolean canFastStatic = index >= 0 && !recordExtents && !partialActive && recordOwner == null && driver.idleAt(index);
        GpuSink spanSink = null;
        if (canFastStatic && index < spanEl.length && ItemDiff.unchanged(spanEl[index], el, spanEpoch[index]) && spanRes[index] == res) {
            if (spanCulled[index]) {
                if (cullOffscreen && fastClipUnchanged(index, spanClip[index])) {
                    culledItems++;
                    return;
                }
            } else if (glReplay && replayableType(el.type()) && spanFlat[index] == flat
                    && fastClipUnchanged(index, spanClip[index])) {
                spanSink = route.current();
                if (spanSink != null && spanSink.spanReplay(index, cpuOps != cpuOpsOrdered)) {
                    cpuOpsOrdered = cpuOps;
                    return;
                }
            }
        }
        // 保持層への記録中の判定は層の実体の範囲だけで決まりクリップを要さない。記録中の buildClip は使い回しの覚えが無く
        // 毎回作り直す（確保が出る）ので、画面外項目はその前に捨てる
        if (cullOffscreen && recordOwner != null && outsideClip(el, res.pose(), null)) {
            culledItems++;
            return;
        }
        Clip clip = buildClip(index, el, anim);
        if (currentItem >= 0) {
            int b = currentItem * 4;
            curClip[b] = clip.x0();
            curClip[b + 1] = clip.y0();
            curClip[b + 2] = clip.x1();
            curClip[b + 3] = clip.y1();
        }
        if (clip.empty()) return;
        if (cullOffscreen && outsideClip(el, res.pose(), clip)) {
            if (canFastStatic) {
                ensureSpanKeys(index);
                setSpanItem(index, el);
                spanRes[index] = res;
                spanClip[index] = clip;
                spanCulled[index] = true;
            }
            culledItems++;
            return;
        }

        // 項目単位の再生（GL）: 止まっている項目が、前フレームと同じ入力（項目・解決結果・クリップ・不透明度）なら、GPU 側の出力を
        // 計算せずに前進させる。前提は GpuSink.spanReplay。CPU 描画がそのフレームでまだ無いことも条件（描画順の整合のため）
        if (glReplay && index >= 0 && replayableType(el.type()) && !recordExtents && !partialActive && recordOwner == null) {
            boolean idle = driver.idleAt(index);
            if (spanSink == null) spanSink = route.current();
            if (spanSink != null) {
                ensureSpanKeys(index);
                if (idle && !spanCulled[index] && ItemDiff.unchanged(spanEl[index], el, spanEpoch[index]) && spanRes[index] == res && spanClip[index] == clip && spanFlat[index] == flat) {
                    if (spanSink.spanReplay(index, cpuOps != cpuOpsOrdered)) {
                        cpuOpsOrdered = cpuOps;
                        return;
                    }
                }
                if (!spanSink.spanBegin(index)) spanSink = null;
            }
        } else if (index >= 0 && index < spanEl.length) {
            spanEl[index] = null;
            spanCulled[index] = false;
        }
        long cpuBefore = cpuOps;
        drawItemBody(el, anim, res, clip, opacity);
        if (spanSink != null) {
            boolean ok = cpuOps == cpuBefore;
            spanSink.spanEnd(index, ok);
            if (ok) {
                setSpanItem(index, el);
                spanRes[index] = res;
                spanClip[index] = clip;
                spanFlat[index] = flat;
                spanCulled[index] = false;
            } else {
                spanEl[index] = null;
                spanCulled[index] = false;
            }
        }
    }

    private boolean tryPatchItems(List<DisplayItem> items, int[] indices, int count) {
        if (!PaintSceneDriver.reuseIdlePoses || !glReplay) return false;
        if (recordExtents || partialActive || recordOwner != null) return false;
        if (!route.beginDiffPatch()) return false;
        syncClipGeneration();
        boolean okAll = true;
        try {
            for (int k = 0; k < count; k++) {
                int index = indices[k];
                DisplayItem el = items.get(index);
                if (el.type() == ElementType.NODE) {
                    Resolved before = driver.peekPrevRes(index);
                    Resolved res = driver.resolveAt(index);
                    if (before == null || before != res || !res.groups().isEmpty()) {
                        okAll = false;
                        break;
                    }
                    continue;
                }
                Resolved res = driver.resolveAt(index);
                if (!res.groups().isEmpty()) {
                    okAll = false;
                    break;
                }
                if (!replayableType(el.type())) {
                    okAll = false;
                    break;
                }
                float flat = 1f;
                if (index < spanEl.length && spanEl[index] != null && !spanCulled[index] && spanFlat[index] != 1f) {
                    okAll = false;
                    break;
                }
                boolean visible = res.opacity() > 0.005f && res.residual() > 0.005f;
                AnimatedValues anim = driver.animAt(index);
                Clip clip = visible ? buildClip(index, el, anim) : null;
                boolean culled = !visible || clip.empty() || (cullOffscreen && outsideClip(el, res.pose(), clip));
                if (culled) {
                    if (route.canPatchSpan(index)) {
                        if (!route.patchSpanBegin(index) || !route.patchSpanEnd(index, true)) {
                            okAll = false;
                            break;
                        }
                        ensureSpanKeys(index);
                        setSpanItem(index, el);
                        spanRes[index] = res;
                        spanClip[index] = clip;
                        spanFlat[index] = flat;
                        spanCulled[index] = false;
                    } else if (index < spanEl.length && spanEl[index] != null && !spanCulled[index]) {
                        okAll = false;
                        break;
                    } else if (clip != null) {
                        ensureSpanKeys(index);
                        setSpanItem(index, el);
                        spanRes[index] = res;
                        spanClip[index] = clip;
                        spanFlat[index] = flat;
                        spanCulled[index] = true;
                    }
                    continue;
                }
                if (!route.patchSpanBegin(index)) {
                    okAll = false;
                    break;
                }
                long cpuBefore = cpuOps;
                drawItemBody(el, anim, res, clip, res.residual());
                if (cpuOps != cpuBefore || !route.patchSpanEnd(index, true)) {
                    okAll = false;
                    break;
                }
                ensureSpanKeys(index);
                setSpanItem(index, el);
                spanRes[index] = res;
                spanClip[index] = clip;
                spanFlat[index] = flat;
                spanCulled[index] = false;
            }
        } catch (RuntimeException ex) {
            route.abortDiffPatch();
            throw ex;
        }
        if (!okAll) {
            route.abortDiffPatch();
            return false;
        }
        route.endDiffPatch();
        cpuOpsOrdered = cpuOps;
        return true;
    }

    /** CPU の図形描画を行った回数（項目単位の再生が、項目の描画に CPU 描画が混じったかを知るため）。 */
    long cpuOps;
    /** 再生の直前に、CPU の層を重ねる順序づけを済ませた時点の {@link #cpuOps}（それ以降に CPU 描画があれば、再生の前に順序づけが要る）。 */
    private long cpuOpsOrdered;
    /** 項目単位の再生を使うか（切り分け用: {@code -Denderframe.paint.noreplay=true} で無効）。 */
    static volatile boolean glReplay = !Boolean.getBoolean("enderframe.paint.noreplay");
    private DisplayItem[] spanEl = new DisplayItem[0];
    /** {@link #spanEl} の項目を覚えたときの {@link DisplayItem#paintEpoch()}（塗りがセルへ書かれたら同じ項目でも再生しない）。 */
    private int[] spanEpoch = new int[0];

    private void setSpanItem(int index, DisplayItem el) {
        spanEl[index] = el;
        spanEpoch[index] = el.paintEpoch();
    }
    private Resolved[] spanRes = new Resolved[0];
    private Clip[] spanClip = new Clip[0];
    private float[] spanFlat = new float[0];
    private boolean[] spanCulled = new boolean[0];

    private boolean fastClipUnchanged(int index, Clip cachedClip) {
        if (cachedClip == null || index >= clipCache.length) return false;
        ClipEntry ce = clipCache[index];
        if (ce == null || ce.clip != cachedClip || ce.w != raster.targetWidth() || ce.h != raster.targetHeight()
                || ce.generation != clipGeneration || ce.version != driver.itemVersion(index)) {
            return false;
        }
        for (int k = 0; k < ce.owner.length; k++) {
            int ow = ce.owner[k];
            if ((ow < 0 ? null : driver.resolveAt(ow)) != ce.ownerRes[k]) return false;
        }
        return true;
    }

    private void ensureSpanKeys(int index) {
        if (index < spanEl.length) return;
        int n = Math.max(index + 1, spanEl.length * 2 + 64);
        spanEl = java.util.Arrays.copyOf(spanEl, n);
        spanEpoch = java.util.Arrays.copyOf(spanEpoch, n);
        spanRes = java.util.Arrays.copyOf(spanRes, n);
        spanClip = java.util.Arrays.copyOf(spanClip, n);
        spanFlat = java.util.Arrays.copyOf(spanFlat, n);
        spanCulled = java.util.Arrays.copyOf(spanCulled, n);
    }

    /** 再生してよい項目の種類（出力が GPU の図形・字形だけで、画像・入力・時間に依らないもの）。 */
    private static boolean replayableType(ElementType t) {
        return t == ElementType.RECT || t == ElementType.TEXT || t == ElementType.ROUNDED_BORDER || t == ElementType.BUTTON;
    }

    private final float[] scratchRx = new float[4], scratchRy = new float[4];

    private void drawItemBody(DisplayItem el, AnimatedValues anim, Resolved res, Clip clip, float opacity) {
        radii(el, anim, scratchRx, scratchRy);
        float[] rx = scratchRx, ry = scratchRy;
        ElementType type = el.type();

        if (type == ElementType.CIRCLE_BORDER) {
            if (itemRaster.drawCircleBorder(el, anim, res, clip, opacity)) return;
        }
        if (type == ElementType.ROUNDED_BORDER) {
            RoundedBorderSpec spec = el.meta() != null ? el.meta().roundedBorder() : null;
            if (spec != null) itemRaster.drawRoundedBorder(el, spec, rx, ry, res, clip, opacity);
            return;
        }
        if (type != ElementType.RANGE) itemRaster.drawBackground(el, anim, rx, ry, res, clip, opacity);

        switch (type) {
            case SCENE -> itemRaster.drawScene3d(el, rx, ry, res, clip, opacity);
            case TEXTAREA -> textRaster.drawTextarea(el, anim, res, clip, opacity);
            case RANGE -> itemRaster.drawRange(el, anim, res, clip, opacity);
            case PROGRESS -> itemRaster.drawProgress(el, anim, rx, ry, res, clip, opacity);
            case TOGGLE, CHECKBOX, RADIO -> itemRaster.drawToggle(el, anim, res, clip, opacity);
            case IMAGE, VIDEO, CANVAS -> itemRaster.drawImage(el, rx, ry, res, clip, opacity);
            default -> {
                if ((el.text() != null && !el.text().isEmpty()) || type == ElementType.INPUT || type == ElementType.SELECT) {
                    textRaster.drawText(el, anim, res, clip, opacity);
                }
            }
        }
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

    /** 項目のクリップの結果と、それを作ったときの前提（描画先の大きさ・持ち主の姿勢）。前提が同じ間は作り直さない。 */
    private static final class ClipEntry {
        Clip clip;
        int[] owner;
        Resolved[] ownerRes;
        int w, h;
        /** 作ったときの世代と、クリップの元（{@link ClipRect} の同一インスタンス。動くクリップは null）。 */
        long generation;
        /** 作ったときの項目の版（{@link PaintSceneDriver#itemVersion}）。世代が同じでも、差し替わった項目の結果は使わない。 */
        int version;
        Object source;
        /** 持ち主の id があるのに見つからなかった（後から現れると結果が変わるので世代をまたいで使い回さない）。 */
        boolean unresolvedOwner;
    }


    void syncClipGeneration() {
        if (clipGeneration != driver.generation()) {
            if (clipCache.length != frameSize) clipCache = java.util.Arrays.copyOf(clipCache, frameSize);
            clipGeneration = driver.generation();
        }
    }

    Clip buildClip(int index, DisplayItem el, AnimatedValues anim) {
        ClipEntry ce = clipCache[index];
        RenderMeta m0 = el.meta();
        Object source = m0 != null ? m0.clipRect() : null;
        // 項目の並びが変わった世代をまたぐときは、元の ClipRect が同一インスタンスのものだけ使い回す（添字が同じでも別の項目かもしれない）
        boolean recording = recordOwner != null; // 保持する層へ記録中は、コンテナの切り取りを外すので使い回しの覚えを使わない
        if (!recording && ce != null && ce.w == raster.targetWidth() && ce.h == raster.targetHeight()
                && ((ce.generation == clipGeneration && ce.version == driver.itemVersion(index))
                        || (source != null && ce.source == source && !ce.unresolvedOwner))) {
            boolean same = true;
            for (int k = 0; k < ce.owner.length && same; k++) {
                same = (ce.owner[k] < 0 ? null : driver.resolveAt(ce.owner[k])) == ce.ownerRes[k];
            }
            if (same) return ce.clip;
        }
        int tw = raster.targetWidth(), th = raster.targetHeight();
        boolean share = CLIP_SHARE && !recording && source != null;
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
                ce.version = driver.itemVersion(index);
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
        ce.version = driver.itemVersion(index);
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
            if ((owner[k] < 0 ? null : driver.resolveAt(owner[k])) != ownerRes[k]) return false;
        }
        return true;
    }

    /**
     * 同じ空間（同じ逆行列・同じ倍率）で、別の部品の領域をすっかり含む直角のクリップは、重ねても何も変わらないので除く。
     * 入れ子の箱が同じ範囲で切るとき（親が直角・子が丸角など）に、GPU が受け取れる部品数（{@link com.nishiyu.enderframelib.core.paint.gpu.GpuSink#MAX_CLIPS}）を超えて、
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
        if (!CLIP_SHARE || recordOwner != null) {
            addClipPart(parts, p.rectView(), p.ownerId(), p.radiiXView(), p.radiiYView());
            return;
        }
        int tw = raster.targetWidth(), th = raster.targetHeight();
        int ix = p.ownerId() == null ? -1 : driver.indexOf(p.ownerId());
        Resolved owner = ix >= 0 ? driver.resolveAt(ix) : null;
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
            int ix = driver.indexOf(ownerId);
            if (ix >= 0) {
                ownerIndex = ix;
                owner = driver.resolveAt(ix);
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
        cpuOps++;
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


    /** 照合・切り分け用: false なら {@link #paint} の行ごとの塗りを使わず、全画素を評価する。 */
    static volatile boolean fastSolid = !Boolean.getBoolean("enderframe.soft.nofastsolid");


    /**
     * 局所の矩形を姿勢で写してクリップと交わらせた画素の範囲 {@code {x0, y0, x1, y1}}（空なら null）。汚れた範囲も広げる。
     * 差分描画のフレームでは、実際に塗る範囲をダメージの外接矩形に収める（外の画素は前のフレームのまま残す）。
     * 描き直す項目の自然な範囲がダメージの外へ出たら、見積もりが外れた印を付ける（呼び出し側が全面でやり直す）。
     */
    int[] paintRange(Matrix2D pose, float bx0, float by0, float bx1, float by1, Clip clip) {
        double[] b = deviceBounds(pose, bx0, by0, bx1, by1, boundsScratch);
        int x0 = Math.max(clip.x0(), (int) Math.floor(b[0])), y0 = Math.max(clip.y0(), (int) Math.floor(b[1]));
        int x1 = Math.min(clip.x1(), (int) Math.ceil(b[2])), y1 = Math.min(clip.y1(), (int) Math.ceil(b[3]));
        if (x1 <= x0 || y1 <= y0) return null;
        if (partialActive) {
            if (currentItemDirty && !damage.covers(x0, y0, x1, y1)) damageAbort = true;
            x0 = Math.max(x0, damage.x0());
            y0 = Math.max(y0, damage.y0());
            x1 = Math.min(x1, damage.x1());
            y1 = Math.min(y1, damage.y1());
            if (x1 <= x0 || y1 <= y0) return null;
        }
        raster.dirty().add(x0, y0, x1, y1);
        if (recordExtents && currentItem >= 0) {
            int e = currentItem * 4;
            if (curExtent[e + 2] <= curExtent[e] || curExtent[e + 3] <= curExtent[e + 1]) {
                curExtent[e] = x0;
                curExtent[e + 1] = y0;
                curExtent[e + 2] = x1;
                curExtent[e + 3] = y1;
            } else {
                if (x0 < curExtent[e]) curExtent[e] = x0;
                if (y0 < curExtent[e + 1]) curExtent[e + 1] = y0;
                if (x1 > curExtent[e + 2]) curExtent[e + 2] = x1;
                if (y1 > curExtent[e + 3]) curExtent[e + 3] = y1;
            }
        }
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

    /** 変わらない文字項目の下ごしらえを使い回す（切り分け用: {@code -Denderframe.paint.notextsetup=true} で無効）。 */
    static volatile boolean textSetupReuse = !Boolean.getBoolean("enderframe.paint.notextsetup");

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
