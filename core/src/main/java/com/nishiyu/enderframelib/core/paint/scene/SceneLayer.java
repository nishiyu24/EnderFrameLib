package com.nishiyu.enderframelib.core.paint.scene;

import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.box.Display;
import com.nishiyu.enderframelib.core.box.DomValues;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.fragment.AnchorScroll;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutFragment;
import com.nishiyu.enderframelib.core.layout.fragment.TableBorders;
import com.nishiyu.enderframelib.core.paint.model.ClipShape;

/**
 * 不変の階層的描画シーンの節（Engine v3 M3b-2。計画書 §3.4）。
 *
 * <p><b>親相対座標</b>: {@link #x}, {@link #y} は親のチャンク（または親の原点の箱）を基準としたスクロール 0・unstuck の相対位置。
 * 前の兄弟が伸びて動いただけの部分木は、同じ {@link SceneLayer} インスタンス（{@code ==}）のまま使い回される。</p>
 *
 * <p><b>記憶の鍵</b>: {@link #frag} と {@link #box}（最新の箱）の参照同一性 {@code (frag ==, box ==)} だけ。描く文脈は持たない。</p>
 */
public final class SceneLayer {
    public static final SceneLayer[] NO_LAYERS = new SceneLayer[0];
    public static final SceneRun[] NO_RUNS = new SceneRun[0];

    // 伝播する描画層ビットマスク（propagatedLayers）
    public static final int LAYER_BLOCK = 1;
    public static final int LAYER_FLOAT = 1 << 1;
    public static final int LAYER_INLINE = 1 << 2;
    public static final int LAYER_NEG = 1 << 3;
    public static final int LAYER_POS0 = 1 << 4;
    public static final int LAYER_POSZ = 1 << 5;
    public static final int LAYER_AFTER = 1 << 6;
    public static final int LAYER_HOIST = 1 << 7;

    // 素性ビットフラグ（flags）
    public static final int FLAG_ANONYMOUS = 1;
    public static final int FLAG_VERTICAL_WRAPPER = 1 << 1;
    public static final int FLAG_RENDERED_LEGEND = 1 << 2;
    public static final int FLAG_TEXT = 1 << 3;
    public static final int FLAG_INTERACTIVE = 1 << 4;
    public static final int FLAG_SCROLL_CONTAINER = 1 << 5;
    public static final int FLAG_CLIPS_X = 1 << 6;
    public static final int FLAG_CLIPS_Y = 1 << 7;
    public static final int FLAG_TOP_LAYER = 1 << 8;
    public static final int FLAG_CONTENT_VISIBILITY_AUTO = 1 << 9;
    public static final int FLAG_ANCHORED_BELOW = 1 << 10;
    public static final int FLAG_BLEND_DESCENDANT = 1 << 11;
    public static final int FLAG_PROPAGATES_BLEND = 1 << 12;
    public static final int FLAG_WANTS_SCROLLBARS = 1 << 13;
    public static final int FLAG_IS_ORIGIN = 1 << 14;
    public static final int FLAG_ABSOLUTE = 1 << 15;
    public static final int FLAG_FIXED = 1 << 16;
    public static final int FLAG_Z_AUTO = 1 << 17;
    public static final int FLAG_DISPLAY_NONE = 1 << 18;
    public static final int FLAG_HAS_SELF = 1 << 19;
    public static final int FLAG_DYNAMIC_BOUNDS = 1 << 20;
    public static final int FLAG_COLLAPSED_TABLE = 1 << 21;

    // ---- 計画書 §3.4 データモデル ----
    /** 文書内の DOM 要素の数値 ID（生成節・疑似要素は 0）。 */
    public final int nodeId;
    /** 親（チャンク内はチャンク相対、それ以外は親の原点の箱基準）からの unstuck・スクロール 0 の相対位置と寸法。 */
    public final float x, y, w, h;
    /** スクロールコンテナの内側クリップ幅・高さ（スクロールしない要素は {@link Float#NaN}）。 */
    public final float scrollClipW, scrollClipH;
    public final Transform2D transform;
    public final ClipShape clip;
    public final LayerEffects effects;
    public final StickyConstraint sticky;
    /** スクロールコンテナ（および根）だけ: カリング範囲外でも回収すべき子孫の sticky 節。 */
    public final SceneLayer[] stickyDescendants;
    /** この層自身の描画命令（M3b-4a）。{@link SceneEmitter} が層を作った直後に 1 度だけ付ける（シーンを渡す前）。 */
    private DrawOps ops;
    public final SceneChildren children;

    // ---- 入力・幾何・補助（問い合わせ・一時アダプタ・増分照合用） ----
    public final BoxNode box;
    public final LayoutFragment frag;
    public final HtmlNode node;
    public final ComputedStyle style;
    public final Display display;
    public final float bT, bR, bB, bL, pT, pR, pB, pL, mT, mR, mB, mL;
    public final float[] collapsedBorders;
    public final float scrollWidth, scrollHeight, lineHeight;
    public final ComputedStyle firstLine;
    public final float[] firstLineBgRect;
    public final AnchorScroll follow;
    public final StackingKind stackingKind;
    public final int zIndex;
    public final int flags;
    public final int propagatedLayers;
    public final int anonChain;
    /** 自身の原点基準での部分木の包含矩形（カリング用）。 */
    public final float boundsMinX, boundsMinY, boundsMaxX, boundsMaxY;

    SceneLayer(int nodeId, float x, float y, float w, float h, float scrollClipW, float scrollClipH,
               Transform2D transform, ClipShape clip, LayerEffects effects, StickyConstraint sticky,
               DrawOps ops, SceneChildren children, BoxNode box, LayoutFragment frag, HtmlNode node,
               ComputedStyle style, Display display,
               float bT, float bR, float bB, float bL, float pT, float pR, float pB, float pL,
               float mT, float mR, float mB, float mL, float[] collapsedBorders,
               float scrollWidth, float scrollHeight, float lineHeight,
               ComputedStyle firstLine, float[] firstLineBgRect, SceneRun[] runs, AnchorScroll follow,
               StackingKind stackingKind, int zIndex, int flags, int propagatedLayers) {
        this.nodeId = nodeId;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.scrollClipW = scrollClipW;
        this.scrollClipH = scrollClipH;
        this.transform = transform;
        this.clip = clip;
        this.effects = effects;
        this.sticky = sticky;
        // 行に置かれた断片は描画の中身の列の先頭に詰める（M3b-4c-1b）。断片を渡すのは新しく作る層だけ（命令はまだ無い）
        this.ops = runs != null && runs.length > 0 ? DrawOps.ofRuns(runs) : ops != null ? ops : DrawOps.EMPTY;
        this.children = children != null ? children : SceneChildren.EMPTY;
        this.box = box;
        this.frag = frag;
        this.node = node;
        this.style = style;
        this.display = display;
        this.bT = bT; this.bR = bR; this.bB = bB; this.bL = bL;
        this.pT = pT; this.pR = pR; this.pB = pB; this.pL = pL;
        this.mT = mT; this.mR = mR; this.mB = mB; this.mL = mL;
        this.collapsedBorders = collapsedBorders;
        this.scrollWidth = scrollWidth;
        this.scrollHeight = scrollHeight;
        this.lineHeight = lineHeight;
        this.firstLine = firstLine;
        this.firstLineBgRect = firstLineBgRect;
        this.follow = follow;
        this.stackingKind = stackingKind;
        this.zIndex = zIndex;
        boolean anchored = follow != null || this.children.anchoredBelow;
        boolean dynamic = follow != null || sticky != null || this.children.hasDynamicBounds;
        int resolvedFlags = (anchored ? (flags | FLAG_ANCHORED_BELOW) : flags)
                | (dynamic ? FLAG_DYNAMIC_BOUNDS : 0)
                | (box != null && TableBorders.collapses(box) ? FLAG_COLLAPSED_TABLE : 0);
        this.flags = resolvedFlags;
        this.propagatedLayers = propagatedLayers;
        int chain = 0;
        if ((flags & FLAG_ANONYMOUS) != 0) {
            chain = 1 + this.children.anonChain;
        }
        this.anonChain = chain;
        this.stickyDescendants = ((flags & FLAG_SCROLL_CONTAINER) != 0 || stackingKind == StackingKind.ROOT)
                ? this.children.collectStickies() : NO_LAYERS;

        float minX = 0f, minY = 0f, maxX = Math.max(0f, w), maxY = Math.max(0f, h);
        for (int i = 0, n = this.ops.runCount(); i < n; i++) {
            float rx = this.ops.runX(i), ry = this.ops.runY(i);
            minX = Math.min(minX, rx - x);
            minY = Math.min(minY, ry - y);
            maxX = Math.max(maxX, rx + this.ops.runW(i) - x);
            maxY = Math.max(maxY, ry + this.ops.runH(i) - y);
        }
        boolean clippedOwn = (flags & FLAG_CLIPS_X) != 0 && (flags & FLAG_CLIPS_Y) != 0 && (propagatedLayers & LAYER_HOIST) == 0;
        if (!clippedOwn && this.children.chunks.length > 0) {
            for (int k = 0; k < this.children.chunks.length; k++) {
                SceneChunk c = this.children.chunks[k];
                if (c.layers.length == 0) continue;
                float cx = this.children.chunkX[k], cy = this.children.chunkY[k];
                minX = Math.min(minX, cx + c.minX);
                minY = Math.min(minY, cy + c.minY);
                maxX = Math.max(maxX, cx + c.maxX);
                maxY = Math.max(maxY, cy + c.maxY);
            }
        }
        this.boundsMinX = minX;
        this.boundsMinY = minY;
        this.boundsMaxX = maxX;
        this.boundsMaxY = maxY;
    }

    private SceneLayer(SceneLayer src, float nx, float ny) {
        this.nodeId = src.nodeId;
        this.x = nx;
        this.y = ny;
        this.w = src.w;
        this.h = src.h;
        this.scrollClipW = src.scrollClipW;
        this.scrollClipH = src.scrollClipH;
        this.transform = src.transform;
        this.clip = src.clip;
        this.effects = src.effects;
        this.sticky = src.sticky;
        this.stickyDescendants = src.stickyDescendants;
        this.ops = src.ops;
        this.children = src.children;
        this.box = src.box;
        this.frag = src.frag;
        this.node = src.node;
        this.style = src.style;
        this.display = src.display;
        this.bT = src.bT; this.bR = src.bR; this.bB = src.bB; this.bL = src.bL;
        this.pT = src.pT; this.pR = src.pR; this.pB = src.pB; this.pL = src.pL;
        this.mT = src.mT; this.mR = src.mR; this.mB = src.mB; this.mL = src.mL;
        this.collapsedBorders = src.collapsedBorders;
        this.scrollWidth = src.scrollWidth;
        this.scrollHeight = src.scrollHeight;
        this.lineHeight = src.lineHeight;
        this.firstLine = src.firstLine;
        this.firstLineBgRect = src.firstLineBgRect;
        this.follow = src.follow;
        this.stackingKind = src.stackingKind;
        this.zIndex = src.zIndex;
        this.flags = src.flags;
        this.propagatedLayers = src.propagatedLayers;
        this.anonChain = src.anonChain;
        this.boundsMinX = src.boundsMinX;
        this.boundsMinY = src.boundsMinY;
        this.boundsMaxX = src.boundsMaxX;
        this.boundsMaxY = src.boundsMaxY;
    }

    /** 座標だけを替えた浅い複製を返す（同じ位置なら {@code this} を返す。部分木は共有）。 */
    public SceneLayer withPosition(float nx, float ny) {
        if (Float.floatToRawIntBits(x) == Float.floatToRawIntBits(nx)
                && Float.floatToRawIntBits(y) == Float.floatToRawIntBits(ny)) {
            return this;
        }
        return new SceneLayer(this, nx, ny);
    }

    /** この層自身の描画命令（M3b-4a。座標は層自身か原点の節から。自分を描かない層は空）。 */
    public DrawOps ops() {
        return ops;
    }

    /**
     * 作った直後の層に描画命令を付ける（{@link SceneEmitter} だけが、層をシーンに入れる前に 1 度だけ呼ぶ。複製を作らないため）。
     */
    SceneLayer attachOps(DrawOps newOps) {
        if (!ops.isEmpty()) throw new IllegalStateException("描画命令は 1 度だけ付ける");
        if (newOps != null && newOps.runCount() != ops.runCount()) throw new IllegalStateException("命令は層の断片を写して作る");
        ops = newOps != null ? newOps : DrawOps.EMPTY;
        return this;
    }

    /** 親のスクロールコンテナ（または根）へ汲み上げる sticky 要素の列。 */
    SceneLayer[] bubbledStickies() {
        if (stackingKind == StackingKind.ROOT) return NO_LAYERS;
        if (isScrollContainer()) return sticky != null ? new SceneLayer[]{this} : NO_LAYERS;
        SceneLayer[] kids = children.collectStickies();
        if (sticky == null) return kids;
        if (kids.length == 0) return new SceneLayer[]{this};
        SceneLayer[] out = new SceneLayer[kids.length + 1];
        out[0] = this;
        System.arraycopy(kids, 0, out, 1, kids.length);
        return out;
    }

    public boolean anonymous() { return (flags & FLAG_ANONYMOUS) != 0; }
    public boolean verticalWrapper() { return (flags & FLAG_VERTICAL_WRAPPER) != 0; }
    public boolean renderedLegend() { return (flags & FLAG_RENDERED_LEGEND) != 0; }
    public boolean isText() { return (flags & FLAG_TEXT) != 0; }
    public boolean interactive() { return (flags & FLAG_INTERACTIVE) != 0; }
    public boolean isScrollContainer() { return (flags & FLAG_SCROLL_CONTAINER) != 0; }
    public boolean clipsX() { return (flags & FLAG_CLIPS_X) != 0; }
    public boolean clipsY() { return (flags & FLAG_CLIPS_Y) != 0; }
    public boolean isTopLayer() { return (flags & FLAG_TOP_LAYER) != 0; }
    public boolean contentVisibilityAuto() { return (flags & FLAG_CONTENT_VISIBILITY_AUTO) != 0; }
    public boolean anchoredBelow() { return (flags & FLAG_ANCHORED_BELOW) != 0; }
    public boolean hasBlendDescendant() { return (flags & FLAG_BLEND_DESCENDANT) != 0; }
    public boolean propagatesBlend() { return (flags & FLAG_PROPAGATES_BLEND) != 0; }
    public boolean wantsScrollbars() { return (flags & FLAG_WANTS_SCROLLBARS) != 0; }
    public boolean isOrigin() { return (flags & FLAG_IS_ORIGIN) != 0; }
    public boolean isAbsolute() { return (flags & FLAG_ABSOLUTE) != 0; }
    public boolean isFixed() { return (flags & FLAG_FIXED) != 0; }
    public boolean isZAuto() { return (flags & FLAG_Z_AUTO) != 0; }
    public boolean displayNone() { return (flags & FLAG_DISPLAY_NONE) != 0; }
    public boolean hasSelf() { return (flags & FLAG_HAS_SELF) != 0; }
    public boolean hasDynamicBounds() { return (flags & FLAG_DYNAMIC_BOUNDS) != 0; }
    public boolean isCollapsedTable() { return (flags & FLAG_COLLAPSED_TABLE) != 0; }

    public DomValues values() {
        return box != null ? box.values : DomValues.NONE;
    }

    public Style backdrop() {
        return box != null ? box.backdrop : null;
    }

    /** {@code empty-cells: hide} により背景・枠線・当たり判定を隠す空の表セルか。 */
    public boolean isEmptyCellHidden() {
        if (display == null || display.inner() != Display.Inner.TABLE_CELL || collapsedBorders != null) return false;
        String ec = style != null ? style.typed().inheritedPaint.emptyCells : null;
        if (ec == null || !"hide".equalsIgnoreCase(ec.trim())) return false;
        return hasNoCellContent(this);
    }

    private static boolean hasNoCellContent(SceneLayer layer) {
        DrawOps runs = layer.ops();
        for (int i = 0, n = runs.runCount(); i < n; i++) {
            if (!runs.runIsText(i) || !runs.runText(i).isBlank()) return false;
        }
        for (SceneChunk c : layer.children.chunks) {
            for (SceneLayer child : c.layers) {
                if (child.displayNone()) continue;
                if (child.node != null && child.node.tag != null && !child.node.tag.isEmpty() && !child.node.tag.startsWith("#")) {
                    return false;
                }
                if (!hasNoCellContent(child)) return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "SceneLayer[" + (node != null ? node.key() : "?") + " @" + x + "," + y + " " + w + "x" + h + "]";
    }
}
