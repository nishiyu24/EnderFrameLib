package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.text.font.GlyphBitmap;

import com.nishiyu.enderframelib.core.paint.AnimatedValues;

import com.nishiyu.enderframelib.core.paint.ImagePlacement;

import com.nishiyu.enderframelib.core.paint.BorderDashPattern;

import com.nishiyu.enderframelib.core.paint.model.ShadowShape;

import com.nishiyu.enderframelib.core.paint.model.ClipChainPart;

import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.paint.BorderPath;
import com.nishiyu.enderframelib.core.paint.BoxDecorationPainter;
import com.nishiyu.enderframelib.core.paint.CssAnimator;
import com.nishiyu.enderframelib.core.paint.ObjectFit;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
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

    /** 最終の描画先。 */
    private final Pixmap root;
    /** いま描いている先（グループの層の中なら、その層）。 */
    private Pixmap target;
    private final java.util.ArrayList<Layer> layers = new java.util.ArrayList<>();
    private final java.util.ArrayDeque<Pixmap> layerPool = new java.util.ArrayDeque<>();
    private GpuSink rootSink;
    private final float dpr;
    private final String sessionId = "software-" + SESSIONS.incrementAndGet();
    private FontResolver fonts = SystemFonts.system();
    private ImageProvider images = ImageProvider.standard();
    private int clearColor = 0;

    private final Map<GlyphKey, GlyphBitmap> glyphs = new HashMap<>();

    // 1 フレームの間だけ使う状態
    private Map<String, DisplayItem> byId = Map.of();
    private long now;
    private GpuSink sink;
    private int dirtyX0 = Integer.MAX_VALUE, dirtyY0 = Integer.MAX_VALUE, dirtyX1 = Integer.MIN_VALUE, dirtyY1 = Integer.MIN_VALUE;
    private Matrix2D frame = Matrix2D.IDENTITY;

    /** CSS px の大きさ {@code cssW × cssH}・等倍の描画先を作る。 */
    public SoftwareRenderBackend(int cssW, int cssH) {
        this(cssW, cssH, 1f);
    }

    /** CSS px の大きさ {@code cssW × cssH}、1 CSS px が {@code devicePixelRatio} 画素の描画先を作る。 */
    public SoftwareRenderBackend(int cssW, int cssH, float devicePixelRatio) {
        if (!(devicePixelRatio > 0f)) throw new IllegalArgumentException("devicePixelRatio must be > 0");
        this.dpr = devicePixelRatio;
        this.root = new Pixmap(Math.max(1, Math.round(cssW * devicePixelRatio)), Math.max(1, Math.round(cssH * devicePixelRatio)));
        this.target = root;
    }

    public SoftwareRenderBackend fonts(FontResolver resolver) {
        this.fonts = resolver == null ? SystemFonts.system() : resolver;
        return this;
    }

    public SoftwareRenderBackend images(ImageProvider provider) {
        this.images = provider == null ? ImageProvider.standard() : provider;
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
        return root;
    }

    @Override
    public float devicePixelRatio() {
        return dpr;
    }

    /** 描画先を {@link BufferedImage}（{@code TYPE_INT_ARGB}、非乗算）に写す。Swing や ImageIO にそのまま渡せる。 */
    @Override
    public BufferedImage toBufferedImage() {
        BufferedImage img = new BufferedImage(root.width(), root.height(), BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, root.width(), root.height(), root.toStraightArgb(), 0, root.width());
        return img;
    }

    /** PNG として書き出す（親のフォルダは作る）。 */
    @Override
    public void writePng(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        ImageIO.write(toBufferedImage(), "png", file.toFile());
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
        java.util.Arrays.fill(root.pixels(), clearColor);
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
            int[] px = root.pixels();
            for (int y = b[1]; y < b[3]; y++) java.util.Arrays.fill(px, y * root.width() + b[0], y * root.width() + b[2], 0);
        }
        dirtyX0 = dirtyY0 = Integer.MAX_VALUE;
        dirtyX1 = dirtyY1 = Integer.MIN_VALUE;
    }

    private void drawFrame(List<DisplayItem> items, long nowMs, GpuSink gpu) {
        if (items == null || items.isEmpty()) return;
        this.sink = gpu;
        this.rootSink = gpu;
        this.target = root;
        this.now = nowMs;
        this.frame = Matrix2D.scale(dpr, dpr);
        CssAnimator.cleanup(sessionId, items);
        try {
            // 項目の並びが前回と同じ（不変リストの同一インスタンス）なら、id の索引と錨の一覧は作り直さない。
            // アニメーションだけが動くフレームでは、リストは変わらず姿勢だけが変わる
            if (!(items == indexedItems && isImmutable(items))) {
                Map<String, DisplayItem> map = new HashMap<>(items.size() * 2);
                for (DisplayItem it : items) map.put(it.id(), it);
                indexedById = map;
                indexOf.clear();
                fxAnchors.clear();
                for (int i = 0; i < items.size(); i++) {
                    DisplayItem it = items.get(i);
                    indexOf.put(it.id(), i);
                    if (it.type() == ElementType.NODE && hasFx(it.hints().layer())) fxAnchors.add(i);
                }
                indexedItems = items;
                int n = items.size();
                parentIdx = new int[n];
                for (int i = 0; i < n; i++) {
                    String pid = items.get(i).hints().parentId();
                    Integer pi = pid == null ? null : indexOf.get(pid);
                    parentIdx[i] = pi == null ? -1 : pi;
                }
                curRes = new Resolved[n];
                prevRes = new Resolved[n];
                prevParentRes = new Resolved[n];
                prevStatic = new boolean[n];
                prevIdle = new boolean[n];
                visiting = new boolean[n];
                states = new CssAnimator.AnimState[n];
                clipCache = new ClipEntry[n];
            }
            java.util.Arrays.fill(curRes, null);
            this.byId = indexedById;
            // 項目ごとの祖先のグループ（親子の鎖と、錨の範囲の両方から。外側から内側の順）
            anchorNext = 0;
            activeAnchors.clear();
            List<List<Group>> chains = new java.util.ArrayList<>(items.size());
            // グループに属する描く部品の数。錨の位置（項目の添字）で数える（文字列の表を引かない）
            int[] members = new int[items.size()];
            Map<String, Integer> extraMembers = new HashMap<>(); // 錨がリストに無いグループだけ
            for (int i = 0; i < items.size(); i++) {
                DisplayItem el = items.get(i);
                List<Group> chain = List.of();
                if (el.type() != ElementType.NODE) {
                    Resolved r = resolveAt(i);
                    if (r.opacity() > 0.005f) {
                        chain = chainFor(items, i, r);
                        for (Group g : chain) {
                            if (g.maskOf() != null) continue;
                            if (g.anchor() >= 0) members[g.anchor()]++;
                            else extraMembers.merge(g.id(), 1, Integer::sum);
                        }
                    } else {
                        chain = null;
                    }
                }
                chains.add(chain);
            }
            for (int i = 0; i < items.size(); i++) {
                DisplayItem el = items.get(i);
                if (el.type() == ElementType.NODE) continue;
                List<Group> chain = chains.get(i);
                if (chain == null) continue;
                if (chain.isEmpty()) { // 不透明度のグループの外: 層を開かず、そのまま描く
                    syncLayers(List.of());
                    drawItem(el, i, 1f);
                    continue;
                }
                List<Group> layered = new java.util.ArrayList<>(chain.size());
                float flat = 1f;
                for (Group g : chain) {
                    // フィルター・マスクのあるグループと、描く部品が 2 つ以上のグループだけ層にする
                    // （部品が 1 つなら、部品ごとに不透明度を掛けても同じ。GPU の経路も保てる）
                    if (g.spec() != null || g.maskOf() != null || (g.anchor() >= 0 ? members[g.anchor()] : extraMembers.getOrDefault(g.id(), 0)) > 1) layered.add(g);
                    else flat *= g.opacity();
                }
                syncLayers(layered);
                drawItem(el, i, flat);
            }
            syncLayers(List.of());
        } finally {
            while (!layers.isEmpty()) {
                Layer l = layers.remove(layers.size() - 1);
                if (l.pixmap != null) layerPool.addFirst(l.pixmap);
                if (l.maskPixmap != null) layerPool.addFirst(l.maskPixmap);
            }
            this.target = root;
            this.rootSink = null;
            this.sink = null;
            this.byId = Map.of();
        }
    }

    /** 項目の姿勢（親から引き継いだ変形）と、祖先の不透明度の積。 */
    private record Resolved(Matrix2D pose, float opacity, float residual, List<Group> groups, AnimatedValues anim) {}

    /** 不透明度を持つ祖先（グループ）。層にまとめて描き、{@code opacity} を掛けて重ねる。 */
    private record Group(String id, float opacity, int anchor, LayerSpec spec, double[] map, String maskOf, Matrix2D pose) {}

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

    private final Map<String, Integer> indexOf = new HashMap<>();
    private final List<Integer> fxAnchors = new java.util.ArrayList<>();
    /** {@link #chainFor} が前へ進める錨の位置と、いま範囲に入っている錨。 */
    private int anchorNext;
    /** 項目ごとの親の位置（無ければ -1）・今のフレームの姿勢・前フレームの姿勢と、そのときの親の姿勢・静止だったか。リストが変わるまで持ち回る。 */
    /**
     * 姿勢が動いていない項目の姿勢を、前のフレームのものから使い回すか。false なら毎フレーム作り直す
     * （疑うときの切り分け用。絵は同じでなければならない）。
     */
    public static volatile boolean reuseIdlePoses = true;
    private int[] parentIdx = new int[0];
    private Resolved[] curRes = new Resolved[0], prevRes = new Resolved[0], prevParentRes = new Resolved[0];
    private boolean[] prevStatic = new boolean[0], prevIdle = new boolean[0], visiting = new boolean[0];
    private CssAnimator.AnimState[] states = new CssAnimator.AnimState[0];
    private ClipEntry[] clipCache = new ClipEntry[0];
    private final List<Integer> activeAnchors = new java.util.ArrayList<>();
    /** 索引（{@link #indexedById}・{@link #indexOf}・{@link #fxAnchors}）を作った時のリスト。 */
    private List<DisplayItem> indexedItems;
    private Map<String, DisplayItem> indexedById = Map.of();

    /** 中身が変わらないと言えるリストか（{@code List.of} / {@code List.copyOf} / {@code Stream.toList} の結果）。 */
    private static boolean isImmutable(List<?> list) {
        return list.getClass().getName().startsWith("java.util.ImmutableCollections$");
    }

    private static boolean hasFx(LayerSpec spec) {
        return spec != null && (!spec.filters().isEmpty() || spec.masked() || spec.blend() != BlendMode.NORMAL || spec.isolate()
                || spec.clip() != null || spec.hasBackdrop());
    }

    private Group groupFor(DisplayItem node, Resolved nr) {
        LayerSpec spec = hasFx(node.hints().layer()) ? node.hints().layer() : null;
        Matrix2D m = nr.pose();
        return new Group(node.id(), Math.max(0f, nr.anim().opacity()), indexOf.getOrDefault(node.id(), -1), spec,
                new double[]{m.a(), m.b(), m.c(), m.d()}, null, m);
    }

    /** 項目 {@code i} を含むグループの鎖。親子の鎖に、錨の範囲に入るフィルター・マスクの錨を足して並べ、マスクの項目にはマスクの層を挟む。 */
    private List<Group> chainFor(List<DisplayItem> items, int i, Resolved r) {
        // 項目は先頭から順に来るので、錨の一覧を前へ進めながら「今の項目を範囲に含む錨」だけを持つ（全錨を毎回なめない）
        while (anchorNext < fxAnchors.size() && fxAnchors.get(anchorNext) <= i) activeAnchors.add(fxAnchors.get(anchorNext++));
        for (int k = activeAnchors.size() - 1; k >= 0; k--) {
            int a = activeAnchors.get(k);
            if (i >= a + items.get(a).hints().layer().span()) activeAnchors.remove(k);
        }
        List<Group> base = r.groups();
        if (activeAnchors.isEmpty()) {
            boolean plain = true;
            for (int k = 0; k < base.size() && plain; k++) plain = base.get(k).spec() == null;
            if (plain) return base; // フィルター・マスクの層が無ければ、親子の鎖がそのまま答え
        }
        List<Group> chain = new java.util.ArrayList<>(base);
        for (int a : activeAnchors) {
            DisplayItem node = items.get(a);
            boolean present = false;
            for (Group g : chain) present |= g.id().equals(node.id());
            if (!present) chain.add(groupFor(node, resolveAt(a)));
        }
        chain.sort(java.util.Comparator.comparingInt(Group::anchor));
        List<Group> out = new java.util.ArrayList<>(chain.size() + 1);
        for (Group g : chain) {
            out.add(g);
            LayerSpec spec = g.spec();
            if (spec != null && spec.masked() && g.anchor() >= 0 && i >= g.anchor() + spec.maskFrom() && i < g.anchor() + spec.span()) {
                out.add(new Group(g.id() + "#mask", 1f, g.anchor(), null, g.map(), g.id(), g.pose()));
            }
        }
        return out;
    }

    /** 項目 {@code i} の姿勢。同じフレームの間は 1 回だけ計算し、動いていない項目は前フレームの結果をそのまま使う。 */
    private Resolved resolveAt(int i) {
        Resolved r = curRes[i];
        if (r != null) return r;
        DisplayItem el = indexedItems.get(i);
        Resolved parent = null;
        int p = parentIdx[i];
        if (p >= 0 && p != i && !visiting[p]) {
            visiting[i] = true; // 親子が輪になっていても止まる
            parent = resolveAt(p);
            visiting[i] = false;
        }
        CssAnimator.AnimState st = states[i];
        if (st == null) st = states[i] = CssAnimator.stateFor(sessionId, el, now);
        boolean idle = !st.isActiveAt(now);
        // 姿勢と不透明度が動いていない（止まっている・色だけが補間される）なら、前回の姿勢をそのまま使う
        boolean geometryStatic = idle || st.geometryStaticAt(now);
        Resolved prev = prevRes[i];
        if (reuseIdlePoses && prev != null && geometryStatic && prevStatic[i] && prevParentRes[i] == parent) {
            // 自分も親も姿勢は動いていない: 行列の掛け算はしない。補間値（Resolved が持つ共有の同じ実体）は、前回も止まっていて
            // 確定済みのとき以外は更新する（色が動いている・今のフレームで終わったとき）
            if (!(idle && prevIdle[i])) st.getInterpolatedValues(now);
            r = prev;
        } else {
            r = build(el, i, parent, st.getInterpolatedValues(now));
            prevRes[i] = r;
            prevParentRes[i] = parent;
            prevStatic[i] = geometryStatic; // 「前回も姿勢が動いていなかった」
        }
        prevIdle[i] = idle; // 「前回の補間値は確定済み」
        curRes[i] = r;
        return r;
    }

    private Resolved build(DisplayItem el, int index, Resolved parent, AnimatedValues anim) {
        Matrix2D base = parent != null ? parent.pose() : frame;
        float parentOpacity = parent != null ? parent.opacity() : 1f;
        float parentResidual = parent != null ? parent.residual() : 1f;
        List<Group> parentGroups = parent != null ? parent.groups() : List.of();
        RenderMeta meta = el.meta();
        float ox = meta != null ? meta.originX() : 0.5f, oy = meta != null ? meta.originY() : 0.5f;
        double cx = el.x() + el.width() * ox, cy = el.y() + el.height() * oy;
        Matrix2D pose = base.multiply(Matrix2D.translate(cx + anim.tx(), cy + anim.ty()));
        if (anim.rot() != 0f) pose = pose.multiply(Matrix2D.rotate(Math.toRadians(anim.rot())));
        if (anim.skew() != 0f) pose = pose.multiply(new Matrix2D(1, 0, Math.tan(Math.toRadians(anim.skew())), 1, 0, 0)); // skewX（回転の後・拡縮の前）
        if (anim.sx() != 1f || anim.sy() != 1f) pose = pose.multiply(Matrix2D.scale(anim.sx(), anim.sy()));
        pose = pose.multiply(Matrix2D.translate(-cx, -cy));
        boolean fx = el.type() == ElementType.NODE && hasFx(el.hints().layer());
        boolean group = el.type() == ElementType.NODE && (anim.opacity() < 0.999f || fx);
        List<Group> groups = parentGroups;
        Resolved r = new Resolved(pose, parentOpacity * anim.opacity(), parentResidual * (group ? 1f : anim.opacity()), groups, anim);
        if (group) {
            groups = new java.util.ArrayList<>(parentGroups);
            groups.add(new Group(el.id(), Math.max(0f, anim.opacity()), index, fx ? el.hints().layer() : null,
                    new double[]{pose.a(), pose.b(), pose.c(), pose.d()}, null, pose));
            r = new Resolved(pose, r.opacity(), r.residual(), groups, anim);
        }
        return r;
    }

    // ------------------------------------------------------------------ グループの層

    /** 開いている層を、項目の祖先のグループ {@code want} に合わせる（共通の手前までを残し、残りを閉じて重ね、足りない分を開く）。 */
    private void syncLayers(List<Group> want) {
        int common = 0;
        while (common < layers.size() && common < want.size() && layers.get(common).group.id().equals(want.get(common).id())) common++;
        while (layers.size() > common) closeLayer();
        for (int i = common; i < want.size(); i++) openLayer(want.get(i));
    }

    private void openLayer(Group g) {
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
        if (pm == null) pm = new Pixmap(root.width(), root.height());
        layers.add(new Layer(g, pm, new int[]{dirtyX0, dirtyY0, dirtyX1, dirtyY1}));
        dirtyX0 = dirtyY0 = Integer.MAX_VALUE;
        dirtyX1 = dirtyY1 = Integer.MIN_VALUE;
        target = pm;
        sink = null; // CPU の層の中は CPU が描く
    }

    /** 一番内側の層を閉じる。中身の層は、フィルター → マスク → 不透明度を掛けて親へ重ねる。マスクの層は親の層に預ける。 */
    private void closeLayer() {
        Layer l = layers.remove(layers.size() - 1);
        if (l.pixmap == null) {
            if (l.isMask()) sink.endMask();
            else sink.endGroup();
            return;
        }
        Layer owner = l.isMask() ? layers.get(layers.size() - 1) : null;
        Pixmap parent = root;
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
        int w = root.width(), h = root.height();
        // GPU の層が足りず CPU の層に落ちたとき、背景（混合・backdrop-filter が読む絵）は GPU 側にある。
        // 描画先を読み戻して CPU で混ぜ、結果を GPU の描画先へ書き戻す。
        boolean viaGpu = false;
        if (rootSink != null && !cpuLayer && spec != null && parent == root
                && (spec.blend() != BlendMode.NORMAL || spec.hasBackdrop())) {
            Pixmap snap = rootSink.snapshotTarget();
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
            if (scratch == null) scratch = new Pixmap(root.width(), root.height());
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
            for (int y = region[1]; y < region[3]; y++) {
                for (int x = region[0]; x < region[2]; x++) {
                    int c = src[y * w + x];
                    if (c == 0) continue;
                    if (l.group.opacity() < 1f) c = SoftShapes.scalePremul(c, l.group.opacity());
                    dst[y * w + x] = blend == BlendMode.NORMAL ? SoftShapes.over(c, dst[y * w + x])
                            : blendPixel(blend, c, dst[y * w + x]);
                    src[y * w + x] = 0;
                }
            }
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
        Resolved res = resolveAt(index);
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
                            int bx0, int by0, int bx1, int by1) {
        float coverage(double dx, double dy) {
            float lx = (float) inv.applyX(dx, dy), ly = (float) inv.applyY(dx, dy);
            return SoftShapes.coverage(SoftShapes.sdRoundRect(lx, ly, x0, y0, x1, y1, rx, ry), scale);
        }
    }

    private record Clip(List<ClipPart> parts, int x0, int y0, int x1, int y1) {
        boolean empty() {
            return x1 <= x0 || y1 <= y0;
        }

        float coverage(double dx, double dy) {
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
    }

    private Clip buildClip(int index, DisplayItem el, AnimatedValues anim) {
        ClipEntry ce = clipCache[index];
        if (ce != null && ce.w == target.width() && ce.h == target.height()) {
            boolean same = true;
            for (int k = 0; k < ce.owner.length && same; k++) {
                same = (ce.owner[k] < 0 ? null : resolveAt(ce.owner[k])) == ce.ownerRes[k];
            }
            if (same) return ce.clip;
        }
        List<ClipPart> parts = new ArrayList<>(2);
        List<Integer> owners = new ArrayList<>(2);
        List<Resolved> ownerRes = new ArrayList<>(2);
        RenderMeta meta = el.meta();
        ClipRect cr = meta != null ? meta.clipRect() : null;
        if (cr != null && !cr.chain().isEmpty()) {
            for (ClipChainPart p : cr.chain()) addClipPart(parts, owners, ownerRes, p.rect(), p.ownerId());
        } else if (cr != null) {
            addClipPart(parts, owners, ownerRes, cr.toArray(), cr.ownerId());
        } else if (anim.clipData != null) {
            addClipPart(parts, owners, ownerRes, anim.clipData, null);
        }
        ce = new ClipEntry();
        ce.clip = finishClip(parts);
        ce.owner = owners.stream().mapToInt(Integer::intValue).toArray();
        ce.ownerRes = ownerRes.toArray(new Resolved[0]);
        ce.w = target.width();
        ce.h = target.height();
        clipCache[index] = ce;
        return ce.clip;
    }

    private Clip finishClip(List<ClipPart> parts) {
        int x0 = 0, y0 = 0, x1 = target.width(), y1 = target.height();
        for (ClipPart p : parts) {
            x0 = Math.max(x0, p.bx0());
            y0 = Math.max(y0, p.by0());
            x1 = Math.min(x1, p.bx1());
            y1 = Math.min(y1, p.by1());
        }
        return new Clip(parts, x0, y0, x1, y1);
    }

    private void addClipPart(List<ClipPart> parts, List<Integer> owners, List<Resolved> ownerRes, float[] rect, String ownerId) {
        if (rect == null || rect.length < 4) return;
        Matrix2D pose = frame;
        int ownerIndex = -1;
        Resolved owner = null;
        if (ownerId != null) {
            Integer ix = indexOf.get(ownerId);
            if (ix != null) {
                ownerIndex = ix;
                owner = resolveAt(ix);
                pose = owner.pose();
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
        double[] b = deviceBounds(pose, rect[0], rect[1], rect[2], rect[3]);
        parts.add(new ClipPart(inv, (float) pose.meanScale(), rect[0], rect[1], rect[2], rect[3], rx, ry,
                (int) Math.floor(b[0]), (int) Math.floor(b[1]), (int) Math.ceil(b[2]), (int) Math.ceil(b[3])));
    }

    private static double[] deviceBounds(Matrix2D m, double x0, double y0, double x1, double y1) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        double[][] corners = {{x0, y0}, {x1, y0}, {x1, y1}, {x0, y1}};
        for (double[] c : corners) {
            double x = m.applyX(c[0], c[1]), y = m.applyY(c[0], c[1]);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        return new double[]{minX, minY, maxX, maxY};
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
        if (opacity <= 0f || !(bx1 > bx0) || !(by1 > by0)) return;
        Matrix2D inv = pose.invert();
        if (inv == null || !inv.isFinite()) return;
        double[] b = deviceBounds(pose, bx0, by0, bx1, by1);
        int x0 = Math.max(clip.x0(), (int) Math.floor(b[0])), y0 = Math.max(clip.y0(), (int) Math.floor(b[1]));
        int x1 = Math.min(clip.x1(), (int) Math.ceil(b[2])), y1 = Math.min(clip.y1(), (int) Math.ceil(b[3]));
        if (x1 <= x0 || y1 <= y0) return;
        dirtyX0 = Math.min(dirtyX0, x0);
        dirtyY0 = Math.min(dirtyY0, y0);
        dirtyX1 = Math.max(dirtyX1, x1);
        dirtyY1 = Math.max(dirtyY1, y1);
        int[] px = target.pixels();
        int w = target.width();
        boolean clipped = !clip.parts().isEmpty();
        for (int y = y0; y < y1; y++) {
            double dy = y + 0.5;
            for (int x = x0; x < x1; x++) {
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
    }

    /** 角丸矩形を単色で塗る。 */
    private void fillRoundRect(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                               int argb, Clip clip, float opacity) {
        if ((argb >>> 24) == 0) return;
        if (sink != null) {
            GpuSink.Clip[] gc = gpuClips(clip);
            if (gc != null && sink.fillRoundRect(pose, x0, y0, x1, y1, rx, ry, argb, gc, opacity)) return;
        }
        int pm = Pixmap.premultiply(argb);
        float scale = (float) pose.meanScale();
        float m = 1f / Math.max(scale, 0.01f);
        paint(pose, x0 - m, y0 - m, x1 + m, y1 + m, clip, opacity, (lx, ly) -> {
            float cov = SoftShapes.coverage(SoftShapes.sdRoundRect(lx, ly, x0, y0, x1, y1, rx, ry), scale);
            return cov <= 0f ? 0 : SoftShapes.scalePremul(pm, cov);
        });
    }

    /** GPU へ渡せるクリップの列。多すぎれば {@code null}（CPU が塗る）。 */
    private static GpuSink.Clip[] gpuClips(Clip clip) {
        List<ClipPart> parts = clip.parts();
        if (parts.size() > GpuSink.MAX_CLIPS) return null;
        GpuSink.Clip[] out = new GpuSink.Clip[parts.size()];
        for (int i = 0; i < out.length; i++) {
            ClipPart p = parts.get(i);
            out[i] = new GpuSink.Clip(p.inv(), p.scale(), p.x0(), p.y0(), p.x1(), p.y1(), p.rx(), p.ry());
        }
        return out;
    }

    private static final float[] ZERO4 = new float[4];

    private void fillRect(Matrix2D pose, float x0, float y0, float x1, float y1, int argb, Clip clip, float opacity) {
        fillRoundRect(pose, x0, y0, x1, y1, ZERO4, ZERO4, argb, clip, opacity);
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
            GpuSink.Clip[] gc = gpuClips(clip);
            if (gc != null && sink.fillGradient(res.pose(), x0, y0, x1, y1, rx, ry, g, gc, opacity)) return;
        }
        float scale = (float) res.pose().meanScale();
        float m = 1f / Math.max(scale, 0.01f);
        paint(res.pose(), x0 - m, y0 - m, x1 + m, y1 + m, clip, opacity, (lx, ly) -> {
            float cov = SoftShapes.coverage(SoftShapes.sdRoundRect(lx, ly, x0, y0, x1, y1, rx, ry), scale);
            if (cov <= 0f) return 0;
            Integer c = ShaderGradientEmulator.colorOf(g, lx, ly);
            if (c == null || (c >>> 24) == 0) return 0;
            return SoftShapes.scalePremul(Pixmap.premultiply(c), cov);
        });
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
                GpuSink.Clip[] gc = gpuClips(clip);
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
            GpuSink.Clip[] gc = gpuClips(clip);
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
            GpuSink.Clip[] gc = gpuClips(clip);
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
            float[] d = {ly - y0, x1 - lx, y1 - ly, lx - x0};
            for (int s = 0; s < 4; s++) {
                if (widths[s] <= 0f) continue;
                float v = d[s] / widths[s];
                if (v < best) {
                    best = v;
                    side = s;
                }
            }
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
        });
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
            GpuSink.Clip[] gc = gpuClips(clip);
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

    private record GlyphKey(LoadedFace face, int codePoint, float pixelSize, boolean bold, boolean italic, int subpixel, boolean light) {}

    private OutlineFontFace faceFor(DisplayItem el) {
        String family = el.events() != null ? el.events().get("font-family") : null;
        FontFace f = fonts.resolve(family);
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
        DisplayItem input = byId.get(nodeId + "_input");
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
        if (alpha < 4) return;
        int argb = (alpha << 24) | (anim.color() & 0x00FFFFFF);

        String shown = text;
        if (field || el.isPart("_ph")) {
            float available = Math.max(0f, el.width() - 8f);
            shown = text.substring(0, face.fitChars(text, style, available));
        }
        boolean shadow = el.events() != null && "true".equals(el.events().get("text-shadow"));
        drawRun(shown, face, style, textX, baseline, argb, shadow, res, clip, opacity);

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
        if (text == null || text.isEmpty()) return;
        text = BidiText.toVisual(text, null);
        float[] positions = face.positions(text, style);
        if (shadow) {
            float off = style.fontSizePx() / 9f;
            int shadowColor = (argb & 0xFF000000) | ((argb & 0xFCFCFC) >> 2);
            drawGlyphs(text, positions, face, style, x + off, baseline + off, shadowColor, res.pose(), clip, opacity);
        }
        drawGlyphs(text, positions, face, style, x, baseline, argb, res.pose(), clip, opacity);
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
        GlyphKey key = new GlyphKey(gf.face(), cp, pixelSize, gf.syntheticBold(), gf.syntheticItalic(), sub, light);
        GlyphBitmap g = glyphs.get(key);
        if (g == null) {
            g = gf.face().rasterize(cp, pixelSize, gf.syntheticBold(), gf.syntheticItalic(), sub);
            if (!g.isEmpty()) g = g.withTextContrast(light);
            if (glyphs.size() >= GLYPH_CACHE_LIMIT) glyphs.clear();
            glyphs.put(key, g);
        }
        return g;
    }

    private boolean glyphOnGpu(GlyphBitmap g, float originX, float originY, int argb, Clip clip, float opacity) {
        if (sink == null) return false;
        if (opacity <= 0f) return true;
        GpuSink.Clip[] gc = gpuClips(clip);
        if (gc == null) return false;
        return sink.drawGlyph(g, Math.round(originX + g.left()), Math.round(originY - g.top()), argb, gc, opacity);
    }

    private boolean glyphTransformedOnGpu(GlyphBitmap g, float ox, float oy, float s, Matrix2D pose, int argb, Clip clip, float opacity) {
        if (sink == null) return false;
        if (opacity <= 0f) return true;
        GpuSink.Clip[] gc = gpuClips(clip);
        if (gc == null) return false;
        float lx0 = ox + g.left() / s, ly0 = oy - g.top() / s;
        float lx1 = ox + (g.left() + g.width()) / s, ly1 = oy + (g.height() - g.top()) / s;
        return sink.drawGlyphTransformed(pose, g, ox, oy, s, lx0 - 1, ly0 - 1, lx1 + 1, ly1 + 1, argb, gc, opacity);
    }

    /** デバイス画素の格子にそのまま置く（原点 {@code (originX, originY)}）。 */
    private void blitAligned(GlyphBitmap g, float originX, float originY, int pm, Clip clip, float opacity) {
        float bx = originX + g.left(), by = originY - g.top();
        paint(Matrix2D.IDENTITY, bx, by, bx + g.width(), by + g.height(), clip, opacity, (lx, ly) -> {
            int col = (int) Math.floor(lx - bx), row = (int) Math.floor(ly - by);
            if (col < 0 || row < 0 || col >= g.width() || row >= g.height()) return 0;
            int cov = g.coverageAt(col, row);
            return cov == 0 ? 0 : SoftShapes.scalePremul(pm, cov / 255f);
        });
    }

    /** 回転などの姿勢の下で、字形を双一次補間で貼る。{@code s} は 1 レイアウト単位あたりの字形の画素数。 */
    private void blitTransformed(GlyphBitmap g, float ox, float oy, float s, Matrix2D pose, int pm, Clip clip, float opacity) {
        float lx0 = ox + g.left() / s, ly0 = oy - g.top() / s;
        float lx1 = ox + (g.left() + g.width()) / s, ly1 = oy + (g.height() - g.top()) / s;
        paint(pose, lx0 - 1, ly0 - 1, lx1 + 1, ly1 + 1, clip, opacity, (lx, ly) -> {
            float col = (lx - ox) * s - g.left() - 0.5f, row = (ly - oy) * s + g.top() - 0.5f;
            float cov = sampleCoverage(g, col, row);
            return cov <= 0f ? 0 : SoftShapes.scalePremul(pm, cov);
        });
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
        ImageProvider.Image img = images.load(el, (int) Math.ceil(w * scale), (int) Math.ceil(h * scale));
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
            GpuSink.Clip[] gc = gpuClips(clip);
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
