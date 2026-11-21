package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.kernel.CompactList;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;
import com.nishiyu.enderframelib.core.css.CssPropertyIndex;
import com.nishiyu.enderframelib.core.text.ParagraphSegments;
import com.nishiyu.enderframelib.core.css.StyleFacts;
import com.nishiyu.enderframelib.core.layout.model.*;

import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.model.CssKeywords;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutEngineState;
import com.nishiyu.enderframelib.core.text.model.FontStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.PositionTryRule;
import java.util.Map;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

/**
 * レイアウト結果（ジオメトリ）の保持。
 *
 * 寸法を決めるアルゴリズムは {@link BoxMeasurer} とレイアウトエンジンにあり、
 * このクラスは「どこに・どれだけの大きさで置かれたか」を持つだけ。
 * フィールドが public なのは、レンダラがこの結果を読み取る値オブジェクトだからで、
 * 書き換えてよいのはレイアウト中のエンジンだけ。
 */
public class LayoutBox {

    public final HtmlNode node;
    public ComputedStyle style;
    /** {@code display} を outer / inner に分けたもの。{@link #updateStyle} が算出スタイルから決める。 */
    Display display;
    public LayoutBox parent = null;
    /** ルートの箱だけが使う: この木をレイアウトしたときのデバイスピクセル比（描画が同じ値で候補を選ぶ）。 */
    float devicePixelRatio = 1f;
    /**
     * ボックスツリーの子。レイアウトと描画はこちらを読む。
     *
     * パーサが作るツリーでは {@link BoxTreeBuilder} が {@link #elementChildren} から組み立てるので、
     * 匿名ボックスが挟まり、{@code display: contents} の要素は抜けて子が繰り上がる。
     */
    public final List<LayoutBox> children = new CompactList<>();
    /**
     * 要素ツリー（DOM と 1:1）の子。スタイルの継承と再スタイルはこちらを辿る。
     *
     * 何が壊れていたか: 以前は DOM と箱が 1:1 で、この 2 つが同じリストだった。そのため
     * 匿名ブロック・匿名表ボックス・{@code display: contents} が作れなかった（軸 6）。
     */
    public final List<LayoutBox> elementChildren = new CompactList<>();
    /** DOM に無い箱（匿名ブロック・匿名表ボックス・{@code ::marker}）。{@link #node} は持ち主に紐づけた合成ノード。 */
    public final boolean isAnonymous;

    /**
     * 縦書きの根（{@code writing-mode: vertical-*} を持ち、親は横書き）。内容は {@link #isVerticalWrapper} の箱 1 つに包まれ、
     * {@link VerticalWritingEngine} が論理（横書き）の軸で組んだ内容を回して置く。{@code BoxTreeBuilder} が組み立てのたびに立てる。
     */
    boolean isVerticalRoot;

    /** 縦書きの中に置かれた {@code writing-mode: horizontal-tb} の根（縦中横・直交ネスト）。内容は {@link #isVerticalWrapper} に包まれ、{@link HorizontalReturnEngine} が逆回転で水平へ戻して置く。 */
    boolean isHorizontalReturnRoot;

    /** {@link #isHorizontalReturnRoot} を包んでいた親の書字方向（{@link com.nishiyu.enderframelib.core.css.WritingModes} の定数）。 */
    byte orthogonalParentFlow;

    /** 縦書き／直交戻しの根の内容を包む、回転を持つ生成箱（DOM に無い）。 */
    boolean isVerticalWrapper;

    /**
     * 縦書きラッパー（{@code _vw}）の内側でレイアウト・描画される箱の書字方向（0 なら通常の水平座標、非 0 なら
     * {@link #axisStyle()} が物理プロパティをローカル軸へ写像したスタイルを返す）。
     */
    byte verticalAxisFlow;

    /** 物理寸法・余白・枠線・角丸・overflow をローカル座標軸で読むスタイル（横書き・ラッパー自身では {@link #style}）。 */
    public ComputedStyle axisStyle() {
        int flow = verticalAxisFlow & 0xFF;
        return flow == 0 || style == null ? style : style.derivedVerticalAxisStyle(flow);
    }
    /**
     * {@code list-style-position: outside} の {@code ::marker}。通常フローに入らず、
     * リスト項目の最初の行の左に置かれる。
     */
    boolean isOutsideMarker = false;
    /** 子の中に {@code isOutsideMarker} を持つ箱があるか（{@link BlockLayoutEngine#placeOutsideMarkers} の短絡用）。 */
    boolean hasOutsideMarker = false;
    /** 子の中に {@code isAbsolute} を持つ箱があるか（{@link #layout} の絶対配置走査の短絡用）。 */
    boolean hasAbsoluteChild = false;
    /** 子の親リンクの再確認が必要か（レイアウト時の {@code adopt} ループの短絡用）。 */
    boolean needsAdoption = true;
    /** 部分木に匿名ボックス・::marker を含むか（{@link BoxTreeBuilder} の再利用収集の枝刈り用）。 */
    boolean subtreeHasGenerated = false;
    /** 自分または子孫に {@code content-visibility: auto} を持つ箱があるか。 */
    boolean subtreeHasContentAuto = false;
    /** {@code content-visibility: auto} により現在画面外として子の測定・配置・描画をスキップ中か。 */
    public boolean contentAutoSkipped = false;
    /** {@code content-visibility: auto} のスキップ中に DOM の子の構築を遅延している（未具現）か。 */
    public boolean childrenDeferred = false;
    /** 構築時に子の具現化を遅延したボックスか（画面外へ戻ったときに再解放できる）。 */
    public boolean originallyDeferred = false;
    /** フォーカス／focus-within 等で画面外でも強制的に関連（スキップ不可）か。 */
    public boolean cvForceRelevant = false;
    /** {@code contain-intrinsic-size: auto <length>} 用の最後に記憶された内寸（px）。未記憶は NaN。 */
    public float rememberedInnerW = Float.NaN, rememberedInnerH = Float.NaN;
    /** 親が {@code VirtualFlowState} のときの子配列インデックス（O(1) 参照用、通常は -1）。 */
    int virtualChildIndex = -1;
    /** {@link #elementChildren} に疑似要素（{@code ::before}/{@code ::after}）の子を含むか。 */
    public boolean hasPseudoElementChild = false;
    /** 前回の {@link #buildLayoutTree} 等で集めたノード索引表（ルート箱が保持し、次の構築へ渡す）。 */
    private ReusableTable reusableTable;
    /** {@code restyleLayoutTree} が子孫のスタイルを更新したが、まだ {@code markLayoutChanges} を終えていない根か。 */
    public boolean hasUnmarkedChanges = false;
    /** {@code restyleLayoutTree} が構造変化（{@code BoxTreeBuilder} 再構築必須）を検出した根か。 */
    public boolean hasStructuralPending = false;
    /**
     * fieldset の描画される legend（HTML §15.3.13 の rendered legend）。通常フローに入らず、
     * fieldset の上の枠線に重ねて置かれる。{@link BoxTreeBuilder} が決める。
     */
    boolean isRenderedLegend = false;
    /**
     * この箱がブロック整形コンテキスト（BFC）を作るか。測定のたびに {@link BoxMeasurer} が決める。
     * BFC を作る箱は子のマージンを自分の外へ貫通させない。
     */
    boolean establishesBfc = false;
    /**
     * 「子孫（同じ BFC の中）に float があるか」の結果のメモ（{@link BlockLayoutEngine#containsFloats}）。
     * {@link #floatsMemoEpoch} が {@link #shapeEpoch()} と一致するときだけ有効。
     */
    int floatsMemoEpoch = -1;
    boolean floatsMemo;

    /**
     * 箱の木の「形」（子の並び・親・{@code display}・{@code position}・{@code float}・BFC・rendered legend）の世代。
     * これらのどれかが変わるたびに進める。float の有無のような部分木の性質のメモは、世代が同じ間だけ使う。
     * 全体で 1 つの世代なので、どこかが変われば全てのメモが無効になる（保守的）。文字の差し替えのような形を変えない更新では進まない。
     */
    private static final java.util.concurrent.atomic.AtomicInteger SHAPE_EPOCH = new java.util.concurrent.atomic.AtomicInteger();

    static void bumpShapeEpoch() {
        SHAPE_EPOCH.incrementAndGet();
    }

    /**
     * 「スクロールするだけで中身が動く」かの判定（固定・粘着・絶対配置と包含ブロック・非表示）に効く変化の世代。
     * 子の増減と、これらに効くスタイルの変わり目（position・非表示・transform/filter・containment）で進める。見た目だけの変更（ホバーの影など）では進まない。
     */
    private static final java.util.concurrent.atomic.AtomicLong STRUCT_EPOCH = new java.util.concurrent.atomic.AtomicLong();

    public static long structEpoch() {
        return STRUCT_EPOCH.get();
    }

    /** 絶対配置の子孫の包含ブロックを作る性質（transform / filter / containment）が変わった世代。絶対配置の子孫が居ない判定には響かない。 */
    private static final java.util.concurrent.atomic.AtomicLong CONTAINER_EPOCH = new java.util.concurrent.atomic.AtomicLong();

    public static long containerEpoch() {
        return CONTAINER_EPOCH.get();
    }

    private int containerTraits() {
        if (style == null) return -1;
        return (hasTransformOrFilter() ? 1 : 0)
                | ((style.containment() & (ComputedStyle.CONTAIN_LAYOUT | ComputedStyle.CONTAIN_PAINT)) != 0 ? 2 : 0);
    }

    /** {@link #STRUCT_EPOCH} が見る、この箱の性質。 */
    private int structTraits() {
        if (style == null) return -1;
        return (isFixed ? 1 : 0) | (isAbsolute ? 2 : 0) | (isRelative ? 4 : 0) | (isSticky ? 8 : 0)
                | (display != null && display.isNone() ? 16 : 0);
    }

    /**
     * 指定された箱から親へと祖先を辿り、float のメモ（containsFloats）を局所的に無効化する。
     * 木の他の無関係な部分木のメモを巻き添え無効化せずに済む。
     */
    public static void invalidateFloats(LayoutBox box) {
        for (LayoutBox cur = box; cur != null; cur = cur.parent) {
            cur.floatsMemoEpoch = -1;
        }
    }

    static int shapeEpoch() {
        return SHAPE_EPOCH.get();
    }

    /** 親リンクを張る。変わるときだけ世代を進める（毎回同じ値を書き直す経路で無効化しないため）。 */
    void adopt(LayoutBox child) {
        if (child.parent != this) {
            child.parent = this;
            bumpShapeEpoch();
        }
        if (child.isAbsolute) this.hasAbsoluteChild = true;
        if (child.isOutsideMarker) this.hasOutsideMarker = true;
        if (child.subtreeHasContentAuto) markContentAutoSubtree();
    }

    /** 自分と祖先に {@link #subtreeHasContentAuto} の印を伝播する。 */
    public void markContentAutoSubtree() {
        for (LayoutBox cur = this; cur != null && !cur.subtreeHasContentAuto; cur = cur.parent) {
            cur.subtreeHasContentAuto = true;
        }
    }
    /**
     * 親が組んでいる BFC の float の領域と、その中でのこの箱の内容ボックスの原点。
     * BFC を作らないブロックは親の float を避けて行を組み、自分の float を親の領域に置く。
     * {@link BlockLayoutEngine} が子の高さを解き直す直前に入れ、子は読んだら消す
     * （前回のレイアウトの領域を持ち越さないため）。
     */
    FloatSpace floatSpace;
    float floatSpaceX, floatSpaceY;
    /**
     * 親のエンジンが決めた border box の幅・高さ（flex の伸縮後の寸法、stretch、表の列幅）。
     * NaN でなければ {@link BoxMeasurer} は指定値と内容よりこちらを使う。エンジンは測定の直前に入れ、
     * 測定が終わったら NaN に戻す（別の文脈に移った箱に古い値を残さないため）。
     */
    float overrideWidth = Float.NaN, overrideHeight = Float.NaN;
    /**
     * {@code border-collapse: collapse} の表で、隣と共有する枠線を半分ずつに分けた幅（上・右・下・左）。
     * null でなければ {@link BoxMeasurer} は {@code border-*-width} の代わりにこれを使い、描画もこの幅で描く。
     * 表のエンジンがセルと表自身に入れる。
     */
    float[] collapsedBorders;
    /**
     * 段組（multicol）が列に割り当てた、単位（子の配置・行）ごとの移動量 {x, y}。鍵は {@code BlockLayoutEngine} の
     * 配置と行の実体（同一性で比べる）。持ち主の配置のときに足す。段組でなければ null。
     */
    Map<Object, float[]> columnShifts;
    /** 段組の列の間の線の矩形 {x, y, w, h}（ビューポート座標）。無ければ null。 */
    float[][] columnRules;
    /** 内容を縦にずらす量（px）。表のセルの {@code vertical-align} を表のエンジンが入れる。 */
    float contentOffsetY = 0;
    /**
     * fieldset の legend が上の枠線よりはみ出す分（px）。内容はこの分だけ下がり、上の装飾の一部として
     * {@link #verticalDecoration()} に入る。{@link BoxMeasurer} が legend を測ったあとに入れる。
     */
    float legendSpill = 0;
    /** 直近の測定で、内容の幅が使える幅で切られた（shrink-to-fit の幅が使える幅に頭打ちされた）。ブロックの整形が決める。 */
    boolean measuredClamped;
    /** shrink-to-fit の箱が、min/max-width で切り詰める前に内容から決まった幅（装飾込み）。決まらなければ NaN。 */
    float naturalInlineSize = Float.NaN;
    /** 親の幅を埋める箱（fill 幅）で、内容が収まり切るために必要な内幅（装飾・マージン抜き）。決まらなければ NaN。 */
    float naturalFitInnerWidth = Float.NaN;

    /**
     * 指定された {@code height} / 最小・最大に関わらない内容の高さ（装飾を含む）。直近の測定で入る。
     * flex の列方向の {@code min-height: auto}（内容の最小寸法）が読む。
     */
    float contentBlockExtent = 0;
    /** 相殺する前の上下マージン（px）。親子のマージン相殺は解き直すたびにここから始める。 */
    float ownMarginTop, ownMarginBottom;

    public void addChild(LayoutBox child) {
        if (child != null) {
            child.parent = this;
            this.children.add(child);
            if (child.isOutsideMarker) this.hasOutsideMarker = true;
            if (child.isAbsolute) this.hasAbsoluteChild = true;
            if (child.isAnonymous || child.subtreeHasGenerated) this.subtreeHasGenerated = true;
            if (child.subtreeHasContentAuto) markContentAutoSubtree();
            bumpShapeEpoch();
            STRUCT_EPOCH.incrementAndGet();
            touchSubtree();
        }
    }

    public void clearChildren() {
        STRUCT_EPOCH.incrementAndGet();
        touchSubtree();
        this.children.clear();
        this.hasOutsideMarker = false;
        this.hasAbsoluteChild = false;
        this.subtreeHasGenerated = false;
        this.subtreeHasContentAuto = style != null && style.facts().contentVisibilityAuto;
        this.needsAdoption = false;
    }

    public void updateChildFlagsOnAdd(LayoutBox child) {
        if (child.isOutsideMarker) this.hasOutsideMarker = true;
        if (child.isAbsolute) this.hasAbsoluteChild = true;
        if (child.isAnonymous || child.subtreeHasGenerated) this.subtreeHasGenerated = true;
        if (child.subtreeHasContentAuto) markContentAutoSubtree();
    }

    public void recomputeChildFlags() {
        boolean outside = false;
        boolean abs = false;
        boolean cv = style != null && style.facts().contentVisibilityAuto;
        for (int i = 0, n = children.size(); i < n; i++) {
            LayoutBox c = children.get(i);
            if (c.isOutsideMarker) outside = true;
            if (c.isAbsolute) abs = true;
            if (c.subtreeHasContentAuto) cv = true;
        }
        this.hasOutsideMarker = outside;
        this.hasAbsoluteChild = abs;
        if (cv) markContentAutoSubtree();
        else this.subtreeHasContentAuto = false;
    }

    /** 要素ツリーに子を足す。ボックスツリーは {@link BoxTreeBuilder} がここから組み立てる。 */
    public void addElementChild(LayoutBox child) {
        if (child != null) {
            child.parent = this;
            if (child.node != null && child.node.isPseudoNode) this.hasPseudoElementChild = true;
            this.elementChildren.add(child);
            bumpShapeEpoch();
        }
    }

    public void linkParents() {
        for (LayoutBox child : children) {
            adopt(child);
            child.linkParents();
        }
    }

    /** {@code target} の節に対応する箱を、この部分木から探す。 */
    public LayoutBox findByNode(HtmlNode target) {
        if (target == null) return null;
        if (node == target) return this;
        for (LayoutBox child : children) {
            LayoutBox found = child.findByNode(target);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * 絶対配置・固定配置の包含ブロックを作る祖先。無ければ null（初期包含ブロック = ビューポート）。
     *
     * 位置指定要素は絶対配置の、transform / filter を持つ要素は固定配置を含むすべての位置指定の子孫の
     * 包含ブロックになる（CSS Transforms 1 §2 / Filter Effects 1 §3）。何が壊れていたか: 以前は
     * position だけを見ていたため、transform を持つ箱の中の絶対配置要素が外の祖先を基準に置かれていた。
     */
    public LayoutBox findContainingBlock() {
        LayoutBox cur = this.parent;
        while (cur != null) {
            // layout / paint containment を持つ箱は、絶対配置・固定配置の包含ブロックにもなる（CSS Containment 2 §3, §4）
            boolean transformLike = cur.hasTransformOrFilter()
                    || (cur.style.containment() & (ComputedStyle.CONTAIN_LAYOUT | ComputedStyle.CONTAIN_PAINT)) != 0;
            if (transformLike || (!this.isFixed && (cur.isRelative || cur.isAbsolute || cur.isSticky))) {
                return cur;
            }
            cur = cur.parent;
        }
        return null;
    }

    private boolean hasTransformOrFilter() {
        return isSet(style.get(CssPropertyIndex.TRANSFORM)) || isSet(style.get(CssPropertyIndex.FILTER));
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank() && !"none".equalsIgnoreCase(value.trim());
    }

    public record ContainingBlock(float x, float y, float w, float h) {}

    public ContainingBlock resolveContainingBlock(LayoutContext ctx) {
        LayoutBox cb = findContainingBlock();
        if (cb == null) {
            return new ContainingBlock(0, 0, ctx.viewport().rootW(), ctx.viewport().rootH());
        }
        float cbScrollX = ctx.scrollState().getScrollX(cb.node);
        float cbScrollY = ctx.scrollState().getScrollY(cb.node);
        float cbX = cb.x + cb.bL - cbScrollX;
        float cbY = cb.y + cb.bT - cbScrollY;
        float cbW = Math.max(0, cb.w - cb.bL - cb.bR);
        float cbH = Math.max(0, cb.h - cb.bT - cb.bB);
        if (cb.display != null && cb.display.inner() == Display.Inner.GRID) {
            // grid 容器が包含ブロックなら、grid-placement の線が示す領域（CSS Grid §10.1）
            float[] area = GridLayoutEngine.absoluteArea(cb, this);
            if (area != null) return new ContainingBlock(cbX + area[0], cbY + area[1], area[2], area[3]);
        }
        return new ContainingBlock(cbX, cbY, cbW, cbH);
    }

    // ==========================================
    // Anchor Positioning（CSS Anchor Positioning 1: AnchorPositioning へ委譲）
    // ==========================================

    /** ルートの箱に載せる {@code @position-try} 規則表（{@link AnchorPositioning} が読む）。 */
    public Map<String, PositionTryRule> positionTryRules = Map.of();

    /** {@code width} / {@code height} が {@code anchor-size(...)} ならアンカーの寸法（px）、そうでなければ -1。 */
    float anchorWidthPx() { return AnchorPositioning.anchorSizePx(this, CssPropertyIndex.WIDTH, null); }
    float anchorHeightPx() { return AnchorPositioning.anchorSizePx(this, CssPropertyIndex.HEIGHT, null); }

    /** この要素に対話状態（:hover / :active / :focus 等）のセレクタが関与するか。 */
    public boolean hasInteractiveRules = false;

    /**
     * {@code ::marker} の規則をカスケードして得た算出スタイル（リスト項目だけ）。規則が 1 つも掛からなければ null で、
     * マーカーはリスト項目の継承値で描く。{@link BoxTreeBuilder} がマーカーの箱を作るときに読む。
     */
    public ComputedStyle markerStyle = null;

    /** 入力欄（input / textarea）の {@code ::placeholder} の算出スタイル。規則が掛からなければ null。描画側が文字色を読む。 */
    public ComputedStyle placeholderStyle = null;
    /** {@code ::backdrop} の算出スタイル（dialog / popover だけ。規則が無ければ null）。Top Layer の箱の背面に全面で描く。 */
    public ComputedStyle backdropStyle = null;
    /** {@code ::first-line} の算出スタイル（ブロックコンテナだけ。規則が無ければ null）。 */
    public ComputedStyle firstLineStyle = null;
    /** 1 行目に {@code ::first-line} 背景があるときの描画矩形 {@code [x, y, w, h]}。無ければ null。 */
    float[] firstLineBgRect = null;

    /**
     * 疑似要素（{@code ::before} / {@code ::after} / {@code ::marker} / {@code ::placeholder}）のスタイルを解いたときの
     * ビューポート（幅と高さを 1 つにまとめた値）。差分レイアウトは、持ち主のスタイルのキャッシュが有効でこの値も同じなら
     * 疑似要素を解き直さない（疑似要素だけがビューポート単位を使っている場合に備えて持つ）。
     */
    public long pseudoStylesViewport = Long.MIN_VALUE;
    /** {@link #displayText} を作ったときの節の文字（同じなら {@link #refreshStyle} は作り直さない）。 */
    private String displaySource;

    float x, y, w, h;
    String displayText = "";

    /** margin / padding / border の 4 辺（解決済みのピクセル値）。 */
    float mT, mR, mB, mL, pT, pR, pB, pL, bT, bR, bB, bL;
    /** row-gap: 行（ブロック方向）の間隔 / column-gap: 列（インライン方向）の間隔 */
    float rowGap, columnGap;

    boolean isAbsolute = false, isFixed = false, isRelative = false, isSticky = false;
    /**
     * 絶対配置の静的位置（通常フローに置かれたはずのマージンボックスの左上、絶対座標）。
     * inset が両方 auto の軸はここに置く。親のエンジンが子を並べるときに入れる。
     */
    float staticX = Float.NaN, staticY = Float.NaN;
    /** テキストの箱の最初の行のベースライン（箱の上端から。測ったときに入る。テキストでなければ NaN）。 */
    float textBaseline = Float.NaN;
    /**
     * 静的位置が点ではなく領域のとき（grid / flex 容器の子。css-grid §9.4・css-flexbox §4.1）の領域の大きさと、
     * その中での揃え（0 = 始点、0.5 = 中央、1 = 終点）。{@link #staticX} / {@link #staticY} は領域の左上。点なら NaN。
     */
    float staticAreaW = Float.NaN, staticAreaH = Float.NaN, staticAlignX, staticAlignY;
    int zIndex = 0;

    /** 静的位置の領域の中で、マージンボックスの幅 {@code marginBoxW} の箱を揃えるための横のずれ。 */
    public float staticOffsetX(float marginBoxW) {
        return Float.isNaN(staticAreaW) ? 0 : staticAlignX * (staticAreaW - marginBoxW);
    }

    /** 静的位置の領域の中で、マージンボックスの高さ {@code marginBoxH} の箱を揃えるための縦のずれ。 */
    public float staticOffsetY(float marginBoxH) {
        return Float.isNaN(staticAreaH) ? 0 : staticAlignY * (staticAreaH - marginBoxH);
    }
    /** DisplayListBuilder が同じ検証バッチ内で祖先からの moving 判定を O(1) で使い回すための一時印。 */
    public int movingMemoEpoch = 0;
    public boolean movingMemoResult = false;
    /** RenderPipeline が再帰で親から引き継いで記録する「祖先 / 自身または祖先に moving 指定があるか」。 */
    public boolean ancestorMoving = false;
    public boolean selfOrAncestorMoving = false;
    public boolean movingStamped = false;
    /**
     * 解決済みの 1 行分の高さ（px）。
     * 自分で複数行を並べる要素（textarea）と描画側の行送りがこれを読む。
     * 以前は描画側が line-height を独自に解析していて、% / em / normal で食い違っていた。
     */
    float lineHeightPx = 0;
    /**
     * テキストノードの整形結果。幅・ベースライン・行高を各行が持つ。
     *
     * 以前は折り返した文字列（{@code List<String>}）だけを持っていたため、
     * 描画側が行の幅と行送りを自前で測り直していた。
     */
    List<ShapedLine> shapedLines = null;

    public final List<InlineFragment> inlineFragments = new CompactList<>();
    private int fragmentCursor = 0;
    /** {@link #resetPlacementsFast} で遅延消去待ちにされた断片があるか（{@link #startFragmentPass} で解除）。 */
    boolean fragmentsStale = false;

    void resetFragmentCursor() { fragmentCursor = 0; }

    /** インライン断片の再配置パスを開始する。既存の断片オブジェクトを捨てずに再利用カーソルをリセットする。 */
    public void startFragmentPass() {
        fragmentsStale = false;
        fragmentCursor = 0;
    }

    /** 今回の配置パスで使われなかった末尾の余分な断片を切り詰める。 */
    public void finishFragmentPass() {
        if (fragmentCursor < inlineFragments.size()) {
            inlineFragments.subList(fragmentCursor, inlineFragments.size()).clear();
        }
    }

    /** 既存の InlineFragment を再利用するか、必要に応じて新規生成して追加する。 */
    public InlineFragment addFragment(float x, float y, float w, float h, String text,
                                      float baseline, float descent, List<LayoutBox> parents,
                                      boolean startEdge, boolean endEdge) {
        return addFragment(x, y, w, h, text, baseline, descent, parents, startEdge, endEdge, null);
    }

    /** {@code ::first-line} の実効スタイル付きで InlineFragment を追加・再利用する。 */
    public InlineFragment addFragment(float x, float y, float w, float h, String text,
                                      float baseline, float descent, List<LayoutBox> parents,
                                      boolean startEdge, boolean endEdge, ComputedStyle firstLineStyle) {
        if (fragmentCursor < inlineFragments.size()) {
            InlineFragment f = inlineFragments.get(fragmentCursor++);
            f.set(this, x, y, w, h, text, baseline, descent, parents, startEdge, endEdge, firstLineStyle);
            return f;
        }
        int spare = spareFragments == null ? 0 : spareFragments.size();
        if (spare > 0) {
            InlineFragment f = spareFragments.remove(spare - 1);
            f.set(this, x, y, w, h, text, baseline, descent, parents, startEdge, endEdge, firstLineStyle);
            inlineFragments.add(f);
            fragmentCursor++;
            return f;
        }
        InlineFragment f = new InlineFragment(this, x, y, w, h, text);
        f.baseline = baseline;
        f.descent = descent;
        f.parents = parents;
        f.startEdge = startEdge;
        f.endEdge = endEdge;
        f.firstLineStyle = firstLineStyle;
        f.firstLineOwner = null;
        inlineFragments.add(f);
        fragmentCursor++;
        return f;
    }

    /** 置き直しで捨てた断片オブジェクトの控え（{@link #resetPlacements} が積み、{@link #addFragment} が使う）。 */
    static final int SPARE_FRAGMENT_LIMIT = 128;
    private ArrayList<InlineFragment> spareFragments;

    private TextCache textCache;

    private TextCache ensureTextCache() {
        TextCache c = textCache;
        if (c == null) textCache = c = new TextCache();
        return c;
    }

    /** 幅区間メモ付きでテキストを整形する（16px/15px 等 2 状態の往復をハッシュ無しで命中させる）。 */
    public TextShaper.ShapedInterval shapeTextInterval(String text, FontFace face, TextStyle style, float limitW) {
        if (text == null || face == null) {
            var lines = TextShaper.shape(text, face, style, limitW);
            float maxW = 0;
            for (int i = 0, n = lines.size(); i < n; i++) maxW = Math.max(maxW, lines.get(i).width());
            return new TextShaper.ShapedInterval(lines, 0, 0, true, limitW, false, false, maxW);
        }
        TextCache tc = ensureTextCache();
        TextCache.Slot p = tc.primary;
        if (p != null && p.text == text && p.face == face && p.shapedInterval != null
                && (p.shapeStyle == style || p.shapeStyle.equals(style)) && p.shapedInterval.matches(limitW)) {
            return p.shapedInterval;
        }
        TextCache.Slot s = tc.secondary;
        if (s != null && s.text == text && s.face == face && s.shapedInterval != null
                && (s.shapeStyle == style || s.shapeStyle.equals(style)) && s.shapedInterval.matches(limitW)) {
            tc.secondary = p;
            tc.primary = s;
            return s.shapedInterval;
        }
        TextShaper.ShapedInterval built = TextShaper.shapeInterval(text, face, style, limitW);
        TextCache.Slot target = tc.acquireForStyle(text, face, style.font(), style.wordBreak());
        target.shapeStyle = style;
        target.shapedInterval = built;
        return built;
    }

    public ParagraphSegments cachedSegments(String paragraph, FontFace face, FontStyle font, WordBreak wordBreak) {
        TextCache.Slot slot = ensureTextCache().acquireForStyle(paragraph, face, font, wordBreak);
        ParagraphSegments seg = slot.segments;
        if (seg == null) {
            seg = TextShaper.segmented(paragraph, face, font, wordBreak);
            slot.segments = seg;
        }
        return seg;
    }

    public float cachedStrippedAdvance(String text, FontFace face, FontStyle font) {
        TextCache.Slot slot = ensureTextCache().acquireForStyle(text, face, font, null);
        float w = slot.strippedAdvance;
        if (Float.isNaN(w)) {
            w = face.advance(text.strip(), font);
            slot.strippedAdvance = w;
        }
        return w;
    }

    public TextShaper.WrappedParagraph cachedWrappedParagraph(String paragraph, FontFace face, FontStyle font,
                                                             WordBreak wordBreak, float lineWidth) {
        TextCache.Slot slot = ensureTextCache().acquireForStyle(paragraph, face, font, wordBreak);
        TextShaper.WrappedParagraph wp = slot.wrapped;
        if (wp == null || !wp.matches(lineWidth)) {
            wp = TextShaper.wrapParagraph(paragraph, face, font, wordBreak, lineWidth);
            slot.wrapped = wp;
            slot.segments = wp.prepared();
        }
        return wp;
    }

    /** 見えている断片を捨てる（描画が古い断片を読まないように空にする）。オブジェクトは控えに回して再利用する。 */
    void discardFragments() {
        int n = inlineFragments.size();
        if (n == 0) return;
        if (spareFragments == null) spareFragments = new ArrayList<>(Math.min(n, SPARE_FRAGMENT_LIMIT));
        for (int i = n - 1; i >= 0 && spareFragments.size() < SPARE_FRAGMENT_LIMIT; i--) {
            spareFragments.add(inlineFragments.get(i));
        }
        inlineFragments.clear();
    }

    /** 診断・テスト用: 再利用待ちの断片の数。 */
    public int spareFragmentCount() {
        return spareFragments == null ? 0 : spareFragments.size();
    }

    /** エンジン固有の状態（グリッドの行列など）。 */
    LayoutEngineState engineState;

    /**
     * サブグリッド（{@code grid-template-*: subgrid}）の軸を持つ。トラックの寸法を親グリッドから受け取るため、
     * 制約が同じでも結果が変わる。測定・仮の測定の再利用を止める。{@link GridLayoutEngine} が測定のたびに立て直す。
     */
    SizingMode sizingMode = SizingMode.SHRINK_TO_FIT;

    float scrollWidth = 0;
    float scrollHeight = 0;

    /**
     * この箱が直接持つ行の文字の、内容領域の下端（座標系は {@link #y} と同じ）。行の高さが書体の高さより小さいとき、
     * 文字は行の下にはみ出し、スクロール範囲（{@link #scrollHeight}）に入る。行が無ければ負の無限大。
     */
    float inlineContentBottom = Float.NEGATIVE_INFINITY;
    /**
     * 子孫の境界ボックスの右端・下端（この箱の左上からの距離）。クリップされない子孫のはみ出しを含み、
     * マージンと内側の padding は含まない。親の {@link #scrollWidth} / {@link #scrollHeight} が読む。
     */
    float descendantOverflowRight = 0;
    float descendantOverflowBottom = 0;

    /** 測定の記憶（A7: 読み書きを MeasureMemo 1 クラスへ閉じる）。 */
    final MeasureMemo measureMemo = new MeasureMemo();
    /** 配置の記憶（A7: 未配置のテキスト・インライン要素では確保しない遅延生成）。 */
    PlacementMemo placementMemo;
    ConstraintSpace contentSpaceCache, childSpaceCache;

    PlacementMemo ensurePlacementMemo() {
        PlacementMemo pm = placementMemo;
        if (pm == null) placementMemo = pm = new PlacementMemo();
        return pm;
    }

    void invalidatePlacementValid() {
        if (placementMemo != null) placementMemo.invalidateValid();
    }

    void invalidatePlacementOnDisplayChange() {
        if (placementMemo != null) placementMemo.invalidateOnDisplayChange();
    }

    /** 窓・画面外で項目を省いた（この作り直しで NODE を出していない）箱か。子が動いていて残ると、親相対の原点に使えない。 */
    public boolean paintCulled;
    public int dlStart = -1;
    public int dlEnd = -1;
    /** この箱が直近の描画で記録された順序（DisplayListBuilder.renderedBoxes 上でのインデックス）。未記録は -1。 */
    public int dlOrder = -1;

    /** 表示リストスライス範囲をリセットする。 */
    public void clearDlSlice() {
        dlStart = -1;
        dlEnd = -1;
        dlOrder = -1;
    }

    static final java.util.concurrent.atomic.AtomicLong PAINT_EPOCH = new java.util.concurrent.atomic.AtomicLong();
    /** 最後に描画入力が書き換わりうる地点を通った時の通し番号。新しい箱は常に新しい。 */
    long paintStamp = PAINT_EPOCH.incrementAndGet();
    volatile int layoutDirty; long layoutSig; LayoutBox[] settledKids; java.util.concurrent.ConcurrentLinkedQueue<LayoutBox> ledger; // PaintStamps の箱ごとの状態（ledger は根の箱だけ）

    /** 現在の通し番号（これ以下の印は、いまの描画に反映済みとみなせる）。 */
    public static long paintEpoch() {
        return PAINT_EPOCH.get();
    }

    /** 自分と子孫の {@link #paintStamp} の最大値（{@link #touchPaint} が祖先へ伝える）。部分木の描画の記憶が「子孫が変わっていない」を O(1) で言うため。 */
    private volatile long subtreeStamp = paintStamp;
    private static final java.util.concurrent.atomic.AtomicLongFieldUpdater<LayoutBox> SUBTREE_STAMP =
            java.util.concurrent.atomic.AtomicLongFieldUpdater.newUpdater(LayoutBox.class, "subtreeStamp");

    public long subtreePaintStamp() {
        return subtreeStamp;
    }

    /**
     * これまでに描いた描画の通し番号（{@link #noteRendered}）の最大値。祖先の部分木の印がこれより新しければ、前回の描画のあとにすでに
     * 印が付いている（その祖先より上も付いている）ので、伝播を止める。1 フレームで何千もの箱が触られても、伝播はほぼ O(1) になる。
     */
    private static volatile long renderBoundary;

    /** 描画を始めた（記憶を作る）時点の通し番号を覚える。記憶の「作った時」はこの値以下になる。 */
    public static void noteRendered(long epoch) {
        if (epoch > renderBoundary) renderBoundary = epoch;
    }

    /** 描画入力が書き換わりうる地点を通った印を付ける（自分と、祖先の部分木の印）。 */
    public void touchPaint() {
        long s = PAINT_EPOCH.incrementAndGet();
        paintStamp = s;
        bubbleSubtreeStamp(s);
    }

    /** 子の増減など、自分の描画入力は変わらないが部分木の中身が変わったことを、祖先へ伝える（{@link #paintStamp} は進めない）。 */
    void touchSubtree() {
        PaintStamps.mark(this, PaintStamps.DIRTY_STRUCTURE);
    }

    void bubbleSubtreeStamp(long s) {
        long boundary = renderBoundary;
        for (LayoutBox a = this; a != null; a = a.parent) {
            long cur = a.subtreeStamp;
            if (a != this && cur > boundary) return; // 祖先は、前回の描画のあとにすでに印が付いている
            while (cur < s && !SUBTREE_STAMP.compareAndSet(a, cur, s)) cur = a.subtreeStamp; // 並列の layout から呼ばれる。大きい方を残す
        }
    }

    /**
     * 自分の印。子の描画が祖先から読むもの（動く祖先・位置・クリップ・親 id）は記憶の鍵にあり、継承されるスタイルは子の算出スタイルへ
     * 入って子自身の更新で印が付くので、祖先の印は見ない（見ると、祖先が動くたびに子孫の全部が外れる）。
     */
    public long ownPaintStamp() {
        return paintStamp;
    }

    public LayoutBox(HtmlNode node, ComputedStyle style) {
        this(node, style, false);
    }

    private LayoutBox(HtmlNode node, ComputedStyle style, boolean anonymous) {
        this.node = node;
        this.isAnonymous = anonymous;
        updateStyle(style);
    }

    /** 匿名ボックスを作る。 */
    static LayoutBox anonymous(HtmlNode node, ComputedStyle style) {
        return new LayoutBox(node, style, true);
    }

    /**
     * 算出スタイルを更新する（再スタイル時にジオメトリを保ったまま描画属性だけ差し替える用途を含む）。
     */
    public void updateStyle(ComputedStyle newStyle) {
        ComputedStyle before = this.style;
        if (before != null && newStyle != before && display != null && java.util.Objects.equals(node.text, displaySource)
                && before.fontSizePx() == newStyle.fontSizePx() && before.rootFontSizePx() == newStyle.rootFontSizePx()
                && ComputedStyle.sameDeclarations(before, newStyle)) {
            this.style = newStyle; // 宣言が同じなら配置も描画も変わらない。印は進めない（疑似要素の変化は resolveHighlightPseudos が印を進める）
            return;
        }
        int traitsBefore = structTraits(), containerBefore = containerTraits();
        touchPaint();
        invalidateMinContentCache();
        ComputedStyle prevStyle = this.style;
        Display displayBefore = this.display;
        boolean absoluteBefore = this.isAbsolute;
        String prevSource = this.displaySource;
        this.style = newStyle;
        this.displaySource = node.text;
        this.display = Display.of(node, newStyle).blockified(newStyle);

        // 文字が空、または原文・white-space・text-transform のどれも変わっていなければ再正規化・変換を省く。
        if (node.text == null || node.text.isEmpty()) {
            this.displayText = "";
        } else if (prevStyle == null
                || !java.util.Objects.equals(prevSource, node.text)
                || !java.util.Objects.equals(prevStyle.get(CssPropertyIndex.WHITE_SPACE), newStyle.get(CssPropertyIndex.WHITE_SPACE))
                || !java.util.Objects.equals(prevStyle.get(CssPropertyIndex.TEXT_TRANSFORM), newStyle.get(CssPropertyIndex.TEXT_TRANSFORM))) {
            // 空白の畳み方は white-space で決まる。無条件に畳んでいたため <pre> が効かなかった。
            WhiteSpace ws = WhiteSpace.of(newStyle.get(CssPropertyIndex.WHITE_SPACE));
            String text = TextShaper.normalize(ws.collapses() ? IntrinsicSizing.trimNewlineEdges(node.text) : node.text, ws);
            String transformed = LayoutMetrics.applyTextTransform(text, newStyle);
            // capitalize は語の頭だけを大文字にする。語が要素の境目をまたぐ（p<span></span>ass）なら、続きの頭は大文字にしない
            if (!transformed.isEmpty() && !text.isEmpty() && transformed.charAt(0) != text.charAt(0)
                    && Character.isLetterOrDigit(text.charAt(0)) && IntrinsicSizing.endsWithWordChar(node)) {
                transformed = text.charAt(0) + transformed.substring(1);
            }
            this.displayText = IntrinsicSizing.stripIgnorable(transformed);
        }

        String pos = newStyle.getOrDefault(CssPropertyIndex.POSITION, "static");
        this.isFixed = "fixed".equals(pos);
        this.isAbsolute = "absolute".equals(pos) || this.isFixed;
        this.isRelative = "relative".equals(pos);
        this.isSticky = "sticky".equals(pos);
        this.zIndex = (int) newStyle.number(CssPropertyIndex.Z_INDEX, 0f);
        if (newStyle.facts().contentVisibilityAuto) {
            markContentAutoSubtree();
        } else if (this.contentAutoSkipped) {
            this.contentAutoSkipped = false;
        }
        // 形の世代（float メモが読む）は display / position / float が変わったときだけ進める。以前は要素の箱を
        // 再スタイルするたびに進めていて、font-size だけの全行の再スタイルで全部の float メモが毎フレーム消え、
        // 2,000 行の親で子を直列に走査し直していた（B11）。文字ノードの箱は float の有無に影響しない（子を持たず、
        // isFloating は常に false）。BFC の変わり目は BoxMeasurer が自分で進める。付け漏れは verifyFloatMemo が検出する
        if (node.nodeType != com.nishiyu.enderframelib.core.dom.node.NodeType.TEXT
                && (prevStyle == null || absoluteBefore != this.isAbsolute || !display.equals(displayBefore)
                        || !java.util.Objects.equals(prevStyle.get(CssPropertyIndex.FLOAT), newStyle.get(CssPropertyIndex.FLOAT)))) {
            bumpShapeEpoch();
        }
        if (displayBefore != null && !display.equals(displayBefore)) for (LayoutBox c : children) c.invalidatePlacementOnDisplayChange(); // 子は別の整形コンテキストで置かれていた（flex の項目だった文字など。§6-109）
        if (traitsBefore != structTraits()) STRUCT_EPOCH.incrementAndGet(); if (containerBefore != containerTraits()) CONTAINER_EPOCH.incrementAndGet();
    }

    /** 算出スタイルも節の文字も前回と同じなら何もしない {@link #updateStyle}（差分レイアウトで箱を使い回すとき用）。 */
    public void refreshStyle(ComputedStyle newStyle) {
        if (newStyle == style && java.util.Objects.equals(node.text, displaySource)) return;
        updateStyle(newStyle);
    }

    public float horizontalDecoration() { return pL + pR + bL + bR; }

    /** 親の内容ボックスの幅（% の基準）。親が無ければ 0。 */
    /**
     * 内在の幅（min-content）に足す左右の余白。スタイルだけから求める（% と auto は 0: CSS Sizing 3 §5.2 の循環の扱い）。
     * 前回の測定が置いた {@link #mL} / {@link #mR}（% は前回の使える幅で解いた値・auto は中央寄せで決めた値）を読まない。
     */
    float intrinsicInlineMargins(LayoutContext ctx) {
        float rootW = ctx.viewport().rootW(), rootH = ctx.viewport().rootH();
        ComputedStyle s = axisStyle();
        return s.edgeLength(ComputedStyle.EDGE_MARGIN_L, 0, rootW, rootH) + s.edgeLength(ComputedStyle.EDGE_MARGIN_R, 0, rootW, rootH);
    }
    public float verticalDecoration() { return pT + pB + bT + bB + legendSpill; }

    /** fieldset の描画される legend（{@link #isRenderedLegend}）。無ければ null。 */
    public LayoutBox renderedLegend() {
        for (int i = 0, n = children.size(); i < n; i++) {
            LayoutBox c = children.get(i);
            if (c.isRenderedLegend) return c.display.isNone() ? null : c;
        }
        return null;
    }

    /** 通常フローに参加する子（絶対配置・display:none・外側のマーカー・fieldset の legend を除く）。 */
    public List<LayoutBox> getFlowChildren() {
        List<LayoutBox> list = new ArrayList<>();
        for (LayoutBox c : children) {
            if (c.participatesInFlow()) list.add(c);
        }
        return list;
    }

    /** 通常フローに参加するか（{@link #getFlowChildren} の判定そのもの。リストを作らずに問える）。 */
    boolean participatesInFlow() {
        return !isAbsolute && !isOutsideMarker && !isRenderedLegend && !style.facts().displayNone;
    }

    /** 通常フローの子が 1 つでもあるか。{@code !getFlowChildren().isEmpty()} と同じ答えを、確保なしで返す。 */
    public boolean hasFlowChildren() {
        for (int i = 0, n = children.size(); i < n; i++) {
            if (children.get(i).participatesInFlow()) return true;
        }
        return false;
    }

    /**
     * これ以上縮められない幅（min-content）。実装は {@link IntrinsicSizing}。
     */
    public float getMinContentWidth(LayoutContext ctx) {
        return IntrinsicSizing.getMinContentWidth(this, ctx);
    }

    /** 寸法が変わるため、測定パスの先頭でメモ化を捨てる。 */
    void invalidateMinContentCache() {
        measureMemo.invalidateMinContentCache();
    }

    public void applyRelativeOffset(float dx, float dy) {
        this.x += dx;
        this.y += dy;
        if (inlineContentBottom > Float.NEGATIVE_INFINITY) inlineContentBottom += dy;
        for (LayoutBox child : children) {
            // 祖先のずらしは子孫の原点の記録（lastLayoutX / Y）に入らないので、記録と位置の差がずれる。祖先が相対位置でなくなった
            // 次の layout で、平行移動（shiftSubtree）がこの差を保ったまま動かし、効かなくなったずらしが残る（§6-118）。記録を消して置き直させる
            if (child.placementMemo != null) child.placementMemo.clearLastLayoutX();
            child.applyRelativeOffset(dx, dy);
        }
        for (InlineFragment frag : inlineFragments) {
            frag.x += dx;
            frag.y += dy;
        }
    }

    /**
     * 寸法を決める。実装は {@link BoxMeasurer}、記憶は {@link MeasureMemo}。
     */
    public void measure(ConstraintSpace space, LayoutContext ctx) {
        measureMemo.measureWith(this, space, ctx, true);
    }

    void measureParallel(ConstraintSpace space, LayoutContext ctx) {
        measureMemo.measureParallel(this, space, ctx);
    }

    void applyFitToParent(boolean fixedWidth) {
        measureMemo.applyFitToParent(this, fixedWidth);
    }

    boolean needsParallelMeasureWork() {
        return measureMemo.needsParallelWork();
    }

    boolean needsParallelLayoutWork(float parentX, float parentY, LayoutContext ctx) {
        return PlacementMemo.needsWork(this, parentX, parentY, ctx);
    }

    float blockChildFitWidth() {
        return measureMemo.blockChildFitWidth(this);
    }

    /** 燃料切れのために前回の測定を使った回数の累計（診断・テスト用）。 */
    public static long measureFuelReused() {
        return MeasureMemo.fuelReused();
    }

    /** 仮の測定を飛ばした回数（効き具合を測るテスト・診断用）。 */
    public static long tentativeSkipped() {
        return MeasureMemo.tentativeSkipped();
    }

    boolean measureTentatively(ConstraintSpace space, LayoutContext ctx) {
        return measureMemo.measureTentatively(this, space, ctx);
    }

    boolean isTentativeStashed() {
        return measureMemo.isTentativeStashed();
    }

    void settleTentative() {
        measureMemo.settleTentative(this);
    }

    /** これまでに使い回さずに計算した measure の回数（差分レイアウトの効き具合を測るテスト・診断用）。 */
    public static long measurementsComputed() {
        return MeasureMemo.measurementsComputed();
    }

    /** 全ての箱の measure の結果を捨てさせる（次の差分レイアウトは全部測り直す）。 */
    public static void invalidateAllMeasurements() {
        MeasureMemo.invalidateAll();
    }

    boolean measuredWithoutFixedHeight() {
        return measureMemo.measuredWithoutFixedHeight();
    }

    boolean canPreserveMeasurementForEmptyFloatFlow(ConstraintSpace space, LayoutContext ctx) {
        return measureMemo.canPreserveForEmptyFloatFlow(this, space, ctx);
    }

    boolean outputsMatchRecordedMeasurement() {
        return measureMemo.outputsMatchRecorded(this);
    }

    void invalidateMeasurement() {
        MeasureMemo.invalidate(this);
    }

    /** 位置決め（layout）の前に、前回の配置の結果を初期化する。実装は {@link PlacementMemo}。 */
    public static void resetPlacements(LayoutBox box) {
        PlacementMemo.resetPlacements(box, -1);
    }

    public static void resetPlacements(LayoutBox box, long keepRun) {
        PlacementMemo.resetPlacements(box, keepRun);
    }

    public static void resetPlacementsFast(LayoutBox box, long keepRun, List<LayoutBox> staleFrags) {
        PlacementMemo.resetPlacementsFast(box, keepRun, staleFrags);
    }

    public static void finishResetPlacements(List<LayoutBox> staleFrags) {
        PlacementMemo.finishResetPlacements(staleFrags);
    }

    /**
     * 前回の箱の木から、DOM の節ごとの箱を O(1) 配列で引ける索引表。
     * 構築パス自身が次の構築用に埋めることで、再構築前の全木走査（{@code collectReusableBoxes}）と
     * {@code IdentityHashMap} 確保を省く。
     */
    public static final class ReusableTable {
        final com.nishiyu.enderframelib.core.kernel.memory.DocumentArena arena;
        private LayoutBox[] byId;
        private LayoutBox[] spareById;
        private int maxUsedId = -1;
        private java.util.IdentityHashMap<HtmlNode, LayoutBox> unindexed;

        private ReusableTable(com.nishiyu.enderframelib.core.kernel.memory.DocumentArena arena,
                              LayoutBox[] recycled, int cap) {
            this.arena = arena;
            if (arena != null) {
                this.byId = (recycled != null && recycled.length >= cap)
                        ? recycled : new LayoutBox[Math.max(64, cap)];
            } else {
                this.byId = null;
            }
        }

        public static ReusableTable createForBuild(HtmlNode rootNode, ReusableTable previous) {
            var arena = rootNode != null && rootNode.hasNodeId() ? rootNode.arena() : null;
            int cap = arena != null ? Math.max(64, arena.highWater()) : 0;
            LayoutBox[] spare = (previous != null && previous.arena == arena) ? previous.spareById : null;
            if (previous != null && previous.arena == arena) previous.spareById = null;
            return new ReusableTable(arena, spare, cap);
        }

        public boolean matchesRoot(HtmlNode rootNode) {
            var rootArena = rootNode != null && rootNode.hasNodeId() ? rootNode.arena() : null;
            return arena == rootArena;
        }

        public void putIfAbsent(HtmlNode node, LayoutBox box) {
            if (node == null || box == null) return;
            if (arena != null && node.hasNodeId() && node.arena() == arena) {
                int id = node.nodeId();
                if (id >= byId.length) {
                    byId = java.util.Arrays.copyOf(byId, Math.max(byId.length * 2, id + 1));
                }
                if (byId[id] == null) {
                    byId[id] = box;
                    if (id > maxUsedId) maxUsedId = id;
                }
            } else {
                if (unindexed == null) unindexed = new java.util.IdentityHashMap<>();
                unindexed.putIfAbsent(node, box);
            }
        }

        public LayoutBox take(HtmlNode node) {
            if (node == null) return null;
            if (arena != null && node.hasNodeId() && node.arena() == arena) {
                int id = node.nodeId();
                if (id < byId.length) {
                    LayoutBox b = byId[id];
                    if (b != null) {
                        byId[id] = null;
                        return b.node == node ? b : null;
                    }
                }
                return null;
            }
            return unindexed != null ? unindexed.remove(node) : null;
        }

        public void remove(HtmlNode node) {
            take(node);
        }

        public void releaseAfterBuild(ReusableTable previous) {
            if (previous == null || previous == this || previous.arena != this.arena) return;
            if (previous.byId != null) {
                if (previous.maxUsedId >= 0) {
                    int limit = Math.min(previous.maxUsedId + 1, previous.byId.length);
                    java.util.Arrays.fill(previous.byId, 0, limit, null);
                    previous.maxUsedId = -1;
                }
                this.spareById = previous.byId;
                previous.byId = null;
            }
            if (previous.unindexed != null) previous.unindexed.clear();
        }
    }

    public void setReusableTable(ReusableTable table) {
        this.reusableTable = table;
    }

    public static ReusableTable acquireReusableTable(LayoutBox rootBox, HtmlNode docRoot) {
        if (rootBox == null) return null;
        ReusableTable existing = rootBox.reusableTable;
        rootBox.reusableTable = null;
        if (existing != null && existing.matchesRoot(docRoot)) {
            return existing;
        }
        ReusableTable collected = ReusableTable.createForBuild(docRoot, null);
        collectReusableBoxes(rootBox, collected);
        return collected;
    }

    public void registerReusableSubtree(LayoutBox sub) {
        ReusableTable t = this.reusableTable;
        if (t != null && sub != null) collectReusableBoxes(sub, t);
    }

    public void unregisterReusableSubtree(LayoutBox sub) {
        ReusableTable t = this.reusableTable;
        if (t == null || sub == null) return;
        t.remove(sub.node);
        List<LayoutBox> kids = sub.elementChildren;
        for (int i = 0, n = kids.size(); i < n; i++) unregisterReusableSubtree(kids.get(i));
    }

    public boolean isInTableScope() {
        for (LayoutBox cur = this; cur != null; cur = cur.parent) {
            if (isTableBox(cur) || cur.measureMemo.everLaidOutAsTable()) return true;
        }
        return false;
    }

    /**
     * 差分ビルド中に {@code box} を次フレーム用の再利用テーブルへ登録し、表スコープ（子孫を再利用不可にする境界）に入るかを返す。
     */
    public static boolean recordReusableForBuild(ReusableTable out, LayoutBox box) {
        boolean table = isTableBox(box);
        if (out != null && !box.isAnonymous && !box.node.isPseudoNode
                && !isInternalTableBox(box)) {
            out.putIfAbsent(box.node, box);
        }
        return table || box.measureMemo.everLaidOutAsTable();
    }

    private static void collectReusableBoxes(LayoutBox box, ReusableTable out) {
        if (box.isAnonymous || box.node.isPseudoNode) return;
        boolean table = isTableBox(box);
        if (isInternalTableBox(box)) return;
        out.putIfAbsent(box.node, box);
        if (table || box.measureMemo.everLaidOutAsTable()) return;
        List<LayoutBox> kids = box.elementChildren;
        for (int i = 0, n = kids.size(); i < n; i++) collectReusableBoxes(kids.get(i), out);
    }

    /** 外側の table/inline-table 以外の表内部ボックス（行・セル・キャプション等）か。 */
    public static boolean isInternalTableBox(LayoutBox box) {
        return isTableBox(box) && box.display.inner() != Display.Inner.TABLE;
    }

    /**
     * 前回の箱の木から、DOM の節ごとの箱を集める（差分レイアウトで使い回す候補）。
     * 表の箱と表だった箱は集める（使い回すときに {@link #resetEngineOutputs} で表のエンジンの書き込みを捨てる）が、その中へは降りない:
     * 表のエンジンが中の箱（行・セル・キャプション）へ枠線の分担・縦の揃え・行の高さを直接書き込むので、作り直す方が確か。
     */
    public static java.util.Map<HtmlNode, LayoutBox> reusableBoxes(LayoutBox root) {
        java.util.Map<HtmlNode, LayoutBox> out = new java.util.IdentityHashMap<>();
        if (root != null) collectReusableBoxes(root, out);
        return out;
    }

    private static void collectReusableBoxes(LayoutBox box, java.util.Map<HtmlNode, LayoutBox> out) {
        if (box.isAnonymous) return;
        boolean table = isTableBox(box);
        if (table && box.display.inner() != Display.Inner.TABLE) return;
        out.putIfAbsent(box.node, box);
        if (table || box.measureMemo.everLaidOutAsTable()) return;
        for (LayoutBox child : box.elementChildren) collectReusableBoxes(child, out);
    }

    /** 前回か今、表のエンジンの書き込みを受けた（受けうる）箱か。匿名の箱や差分レイアウトで使い回すかどうかの判断に使う。 */
    public boolean touchedByTableEngine() {
        return isTableBox(this) || measureMemo.everLaidOutAsTable() || collapsedBorders != null;
    }

    /** {@code ::first-line} の所有者（インライン以外のブロックコンテナ等）になれる箱か。 */
    public boolean isFirstLineContainer() {
        Display d = display;
        if (d == null || d.isNone()) return false;
        if (d.outer() == Display.Outer.INLINE && d.inner() == Display.Inner.FLOW) return false;
        Display.Inner in = d.inner();
        return in == Display.Inner.FLOW || in == Display.Inner.FLOW_ROOT
                || in == Display.Inner.TABLE_CELL || in == Display.Inner.TABLE_CAPTION;
    }

    /** 非匿名の通常フローインライン要素（{@code display: inline}）か。 */
    public boolean isInlineFlow() {
        return display != null && display.outer() == Display.Outer.INLINE && display.inner() == Display.Inner.FLOW;
    }

    /**
     * 使い回す箱から、前回の表のエンジンの書き込みを捨てて新しく作った箱と同じ状態に戻す。
     * 枠線の分担（{@code collapsedBorders}）は BoxMeasurer が measureContent より先に読むので必ず捨てる。
     * エンジンの状態・縦の揃えは、表だったことのある箱が今は表でないときだけ捨てる:
     * 表でなかった箱の {@code engineState}（Flex / Grid の測定結果）は、使い回す measure と layout が読むので消してはいけない。
     */
    public void resetEngineOutputs() {
        collapsedBorders = null;
        if (isTableBox(this) || !measureMemo.everLaidOutAsTable()) return;
        engineState = null;
        contentOffsetY = 0;
    }

    /** 表か、表の中の箱（行・列・セル・行グループ・キャプション）か。 */
    public static boolean isTableBox(LayoutBox box) {
        return box.display.inner() == Display.Inner.TABLE || box.display.isTableInternal()
                || box.display.inner() == Display.Inner.TABLE_CAPTION;
    }

    /**
     * 箱の木を組み直した後、measure の前に呼ぶ。前回の measure から中身が変わった箱と、その祖先に印を付ける。
     *
     * @return この部分木のどこかが変わったか
     */
    public static boolean markLayoutChanges(LayoutBox root) {
        return MeasureChanges.markLayoutChanges(root);
    }

    /**
     * 変わった箱が分かっているときの {@link #markLayoutChanges(LayoutBox)} の代わり（経路だけに印を付ける）。
     * 経路だけでは決まらないときは null を返す。
     */
    public static List<LayoutBox> markLayoutChangesAlong(LayoutBox root, java.util.Collection<LayoutBox> changed) {
        return MeasureChanges.markLayoutChangesAlong(root, changed, java.util.Set.of());
    }

    /** {@code shallow} 指定付きの経路印付け（測り終えたら {@link #clearSubtreeMarks} に渡す）。 */
    public static List<LayoutBox> markLayoutChangesAlong(LayoutBox root, java.util.Collection<LayoutBox> changed,
                                                         java.util.Set<LayoutBox> shallow) {
        return MeasureChanges.markLayoutChangesAlong(root, changed, shallow);
    }

    /** {@code relayoutRootOnly} 専用: 根と直接の子だけを印付けする。 */
    public static boolean markRootRestyleChanges(LayoutBox rootBox) {
        return MeasureChanges.markRootRestyleChanges(rootBox);
    }

    /** 配置まで終えたあと、木全体の「変わった」印を外す。 */
    public static void clearAllSubtreeMarks(LayoutBox root) {
        MeasureChanges.clearAllSubtreeMarks(root);
    }

    /** {@link #markLayoutChangesAlong} が付けた印を、測り終えた箱から外す。 */
    public static void clearSubtreeMarks(List<LayoutBox> path) {
        MeasureChanges.clearSubtreeMarks(path);
    }

    /** {@link #measure(ConstraintSpace, LayoutContext)} の制約を個別に渡す形（テストと HUD の入口）。 */
    public void measure(float tentativeInnerW, float parentH, FormattingContext fmtCtx, LayoutContext ctx) {
        measure(new ConstraintSpace(tentativeInnerW, parentH, fmtCtx), ctx);
    }

    /** 高さだけ再解決する（flex grow/shrink、grid stretch の後処理用）。 */
    public void resolveFlexibleHeights(float parentH, FormattingContext fmtCtx, LayoutContext ctx) {
        BoxMeasurer.resolveFlexibleHeights(this,
                new ConstraintSpace(Math.max(0, w - horizontalDecoration()), parentH, fmtCtx), ctx);
    }

    /** 決まった寸法をもとに自分と子の位置を確定する。 */
    public void layout(float parentX, float parentY, float parentOuterW, float parentOuterH, LayoutContext ctx) {
        long probe = LayoutParallelProbe.enter(this, LayoutParallelProbe.PLACE);
        try {
            layoutImpl(parentX, parentY, parentOuterW, parentOuterH, ctx);
        } finally {
            LayoutParallelProbe.exit(LayoutParallelProbe.PLACE, probe);
        }
    }

    private void layoutImpl(float parentX, float parentY, float parentOuterW, float parentOuterH, LayoutContext ctx) {
        if (style.facts().displayNone) return;
        measureMemo.settleTentative(this);
        if (measureMemo.hasTentativeChildren()) measureMemo.settleTentativeChildren(this);
        if (needsAdoption) {
            for (int i = 0, n = children.size(); i < n; i++) adopt(children.get(i));
            needsAdoption = false;
        }
        LayoutContext.AutoVisibilitySession av = ctx.autoVisibility();
        boolean checkAutoSubtree = av != null && (subtreeHasContentAuto || style.facts().contentVisibilityAuto);
        if (PlacementMemo.tryReuseOrShift(this, parentX, parentY, parentOuterW, parentOuterH, ctx, checkAutoSubtree)) {
            return;
        }
        PlacementMemo pm = ensurePlacementMemo();
        pm.setHasFloatChild(computeHasFloatChild());
        PaintStamps.touchLayout(this);
        float rootW = ctx.viewport().rootW();
        float rootH = ctx.viewport().rootH();

        if (this.isAbsolute) {
            AnchorPositioning.placeAbsolute(this, resolveContainingBlock(ctx), rootW, rootH, ctx);
        } else {
            float refX = this.isFixed ? 0 : parentX;
            float refY = this.isFixed ? 0 : parentY;
            this.x = refX + mL;
            this.y = refY + mT;
        }

        float scrollX = ctx.scrollState().getScrollX(node);
        float scrollY = ctx.scrollState().getScrollY(node);

        boolean isCvAuto = style.facts().contentVisibilityAuto;
        if (isCvAuto && av != null) {
            av.noteContentAuto();
            boolean near = this.isFixed
                    ? (this.x + Math.max(0f, this.w) >= -LayoutContext.AutoVisibilitySession.CONTENT_VISIBILITY_MARGIN
                        && this.x <= rootW + LayoutContext.AutoVisibilitySession.CONTENT_VISIBILITY_MARGIN
                        && this.y + Math.max(0f, this.h) >= -LayoutContext.AutoVisibilitySession.CONTENT_VISIBILITY_MARGIN
                        && this.y <= rootH + LayoutContext.AutoVisibilitySession.CONTENT_VISIBILITY_MARGIN)
                    : av.isNearViewport(this.x, this.y, this.w, this.h);
            boolean relevant = cvForceRelevant || near;
            boolean shouldSkip = !relevant;
            if (shouldSkip != this.contentAutoSkipped) {
                if (shouldSkip) {
                    releaseDeferredChildren();
                    this.contentAutoSkipped = true;
                } else {
                    this.contentAutoSkipped = false;
                    if (this.childrenDeferred) av.materialize(this);
                }
                measureMemo.markVisibilityChangedForStabilize(this);
                av.noteNonVirtualVisibilityChanged();
            } else if (!shouldSkip && this.childrenDeferred) {
                av.materialize(this);
                measureMemo.markVisibilityChangedForStabilize(this);
                av.noteNonVirtualVisibilityChanged();
            }
        }

        if (this.contentAutoSkipped) {
            ScrollExtentCalc.applyRelativePositioning(this, parentOuterW, parentOuterH, rootW, rootH);
            if (this.isSticky) ScrollExtentCalc.applyStickyOffset(this, ctx);
            this.descendantOverflowRight = this.w;
            this.descendantOverflowBottom = this.h;
            this.scrollWidth = Math.max(this.w, pL + bL + pR + bR);
            this.scrollHeight = Math.max(this.h, pT + bT + pB + bB);
            pm.record(this, parentX, parentY, parentOuterW, parentOuterH, false, scrollX, scrollY);
            return;
        }

        float innerX = this.x + pL + bL - scrollX;
        float innerY = this.y + pT + bT - scrollY + contentOffsetY + legendSpill;

        float prevClipL = 0, prevClipT = 0, prevClipR = 0, prevClipB = 0;
        boolean savedClip = false;
        if (av != null && (this.isFixed || ScrollExtentCalc.clipsOverflow(this))) {
            prevClipL = av.clipLeft();
            prevClipT = av.clipTop();
            prevClipR = av.clipRight();
            prevClipB = av.clipBottom();
            savedClip = true;
            float baseL = this.isFixed ? 0f : prevClipL;
            float baseT = this.isFixed ? 0f : prevClipT;
            float baseR = this.isFixed ? rootW : prevClipR;
            float baseB = this.isFixed ? rootH : prevClipB;
            if (ScrollExtentCalc.clipsOverflow(this)) {
                float boxL = this.x + bL;
                float boxT = this.y + bT;
                float boxR = Math.max(boxL, this.x + this.w - bR);
                float boxB = Math.max(boxT, this.y + this.h - bB);
                av.setClip(Math.max(baseL, boxL), Math.max(baseT, boxT),
                        Math.min(baseR, boxR), Math.min(baseB, boxB));
            } else {
                av.setClip(baseL, baseT, baseR, baseB);
            }
        }
        LayoutBox legend;
        try {
            // 静的位置の既定は内容ボックスの原点。ブロックフローは通常フローの位置で上書きする
            if (hasAbsoluteChild) {
                for (int i = 0, n = children.size(); i < n; i++) {
                    LayoutBox child = children.get(i);
                    if (child.isAbsolute) {
                        child.staticX = innerX;
                        child.staticY = innerY;
                        AnchorPositioning.applyStaticArea(this, child, innerX, innerY);
                    }
                }
            }

            LayoutEngine engine = LayoutEngine.getEngine(this);
            engine.arrangeChildren(this, innerX, innerY, ctx);

            if (hasAbsoluteChild) {
                for (int i = 0, n = children.size(); i < n; i++) {
                    LayoutBox child = children.get(i);
                    if (child.style.facts().displayNone) continue;
                    if (child.isAbsolute) child.layout(this.x - scrollX, this.y - scrollY, this.w, this.h, ctx);
                }
            }
            legend = renderedLegend();
            if (legend != null) ScrollExtentCalc.placeRenderedLegend(this, legend, ctx);
        } finally {
            if (savedClip) av.setClip(prevClipL, prevClipT, prevClipR, prevClipB);
        }
        if (isCvAuto) recordRememberedSize();

        ScrollExtentCalc.applyRelativePositioning(this, parentOuterW, parentOuterH, rootW, rootH);
        if (this.isSticky) ScrollExtentCalc.applyStickyOffset(this, ctx);
        ScrollExtentCalc.updateScrollExtent(this, scrollX, scrollY);
        pm.record(this, parentX, parentY, parentOuterW, parentOuterH, legend != null, scrollX, scrollY);
    }

    private boolean computeHasFloatChild() {
        if (!BlockLayoutEngine.containsFloats(this)) return false;
        for (int i = 0, n = children.size(); i < n; i++) {
            if (children.get(i).style.facts().floating) return true;
        }
        return false;
    }

    /** 原点だけが違うので部分木ごと平行移動した layout の回数（診断・テスト用）。 */
    public static long layoutsShifted() {
        return PlacementMemo.layoutsShifted();
    }

    /** これまでに使い回して飛ばした layout の回数（差分レイアウトの効き具合を測るテスト・診断用）。 */
    public static long layoutsSkipped() {
        return PlacementMemo.layoutsSkipped();
    }

    /** 前回の layout の配置を使い回せる状態か（再利用が有効で、前回の部分木が外の状態に依らなかった）。 */
    public boolean holdsPlacement() {
        return PlacementMemo.holdsPlacement(this);
    }

    public String debugInfo() {
        return PlacementMemo.debugInfo(this);
    }

    /** auto を 0 とした左右のマージン（px）。 */
    float ownInlineMargins(LayoutContext ctx) {
        return IntrinsicSizing.ownInlineMargins(this, ctx);
    }

    /**
     * 直近の配置で sticky として足した縦のずれ・足す前の y・足した後の y。sticky でない箱では意味を持たない。
     */
    float stickyDy, stickyUnstuckY, stickyPlacedY;

    /** B12: この sticky の箱の、スクロールコンテナ {@code port} に対する貼り付きパラメータを返す。 */
    public float[] stickyScrollParams(LayoutBox port, float rootW, float rootH) {
        return ScrollExtentCalc.stickyScrollParams(this, port, rootW, rootH);
    }

    /**
     * 横方向の size containment（{@code container-type: size | inline-size}）。この箱の横の寸法は中身で決まらない:
     * shrink-to-fit の幅や min-content の幅に中身は寄与しない（CSS Containment 3 §3）。
     */
    public boolean containsInlineSize() {
        return style.facts().containsInlineSize;
    }

    /** 縦方向の size containment（{@code container-type: size}）。高さが {@code auto} でも中身の高さは寄与しない。 */
    public boolean containsBlockSize() {
        return style.facts().containsBlockSize;
    }

    /** スキップ中の {@code content-visibility: auto} を含む有効な横方向の size containment。 */
    public boolean effectiveContainsInlineSize() {
        return contentAutoSkipped || style.facts().containsInlineSize;
    }

    /** スキップ中の {@code content-visibility: auto} を含む有効な縦方向の size containment。 */
    public boolean effectiveContainsBlockSize() {
        return contentAutoSkipped || style.facts().containsBlockSize;
    }

    /** size containment 中の固有代用幅（{@code contain-intrinsic-width}、未指定は 0px）。 */
    public float containIntrinsicInlinePx(float rootW, float rootH) {
        float px = style.containIntrinsicWidthPx(rootW, rootH, rememberedInnerW);
        return Float.isNaN(px) ? 0f : px;
    }

    /** size containment 中の固有代用高さ（{@code contain-intrinsic-height}、未指定は 0px）。 */
    public float containIntrinsicBlockPx(float rootW, float rootH) {
        float px = style.containIntrinsicHeightPx(rootW, rootH, rememberedInnerH);
        return Float.isNaN(px) ? 0f : px;
    }

    /** {@code content-visibility: auto} が通常表示されたときの内寸を記録する（{@code auto <length>} 用）。 */
    public void recordRememberedSize() {
        rememberedInnerW = Math.max(0f, w - horizontalDecoration());
        rememberedInnerH = Math.max(0f, h - verticalDecoration());
    }

    /** 画面外へ戻った遅延構築済みボックスの子の箱を解放し、メモリ使用量を可視行数に比例させる。 */
    public void releaseDeferredChildren() {
        if (!originallyDeferred || childrenDeferred) return;
        children.clear();
        elementChildren.clear();
        childrenDeferred = true;
        engineState = null;
        inlineFragments.clear();
        hasOutsideMarker = false;
        hasAbsoluteChild = false;
        if (placementMemo != null) placementMemo.setHasFloatChild(false);
        needsAdoption = false;
        STRUCT_EPOCH.incrementAndGet();
        bumpShapeEpoch();
        touchPaint();
    }

    /** この箱が {@code FenwickVirtualList} ベースの仮想ブロックフローを使っているか。 */
    public boolean isVirtualizedFlow() {
        return engineState instanceof VirtualFlowState;
    }

    /**
     * 仮想ブロックフローで直近に配置された子範囲と追加配置インデックスを返す。
     * 非仮想ボックスなら {@code null}。
     */
    public VirtualFlowState virtualFlowState() {
        return engineState instanceof VirtualFlowState vf ? vf : null;
    }

    /**
     * 仮想ブロックフロー内で未配置（画面外）の子の推定配置座標 {@code {x, y}} を O(log N) で返す。
     * 仮想リスト外または見つからなければ {@code null}。
     */
    public float[] virtualOffsetOf(LayoutBox child) {
        if (!(engineState instanceof VirtualFlowState vf) || child == null) return null;
        return vf.estimateChildPosition(this, child);
    }

    /** {@code overflow} が {@code visible} / {@code clip} 以外ならスクロールコンテナ。 */
    public boolean isScrollContainer() {
        return style.facts().scrollContainer;
    }

    /**
     * {@code border-collapse: collapse} の表か。この表の外周の枠線は、表の箱の中に半分だけあるが
     * {@code clientLeft} などには数えない（ブラウザと同じ。{@code clientWidth} は箱の幅のまま）。
     * {@code overflow} でクリップする表は普通の箱と同じに扱う。
     */
    public boolean isCollapsedTable() {
        return display != null && display.inner() == Display.Inner.TABLE && style != null
                && !ScrollExtentCalc.clipsOverflow(this)
                && style.keywordOf(CssPropertyIndex.BORDER_COLLAPSE, CssKeywords.BorderCollapse.class) == CssKeywords.BorderCollapse.COLLAPSE;
    }

    // ==========================================
    // 読み取り専用アクセサと描画用一時境界（A7）
    // ==========================================

    public float x() { return x; }
    public float y() { return y; }
    public float w() { return w; }
    public float h() { return h; }
    public float mT() { return mT; }
    public float mR() { return mR; }
    public float mB() { return mB; }
    public float mL() { return mL; }
    public float pT() { return pT; }
    public float pR() { return pR; }
    public float pB() { return pB; }
    public float pL() { return pL; }
    public float bT() { return bT; }
    public float bR() { return bR; }
    public float bB() { return bB; }
    public float bL() { return bL; }
    public float rowGap() { return rowGap; }
    public float columnGap() { return columnGap; }
    public float scrollWidth() { return scrollWidth; }
    public float scrollHeight() { return scrollHeight; }
    public boolean isAbsolute() { return isAbsolute; }
    public boolean isFixed() { return isFixed; }
    public boolean isRelative() { return isRelative; }
    public boolean isSticky() { return isSticky; }
    public int zIndex() { return zIndex; }
    public Display display() { return display; }
    public String displayText() { return displayText; }
    public float lineHeightPx() { return lineHeightPx; }
    public List<ShapedLine> shapedLines() { return shapedLines; }
    public float[] collapsedBorders() { return collapsedBorders; }
    public float[][] columnRules() { return columnRules; }
    public float[] firstLineBgRect() { return firstLineBgRect; }
    public float stickyDy() { return stickyDy; }
    public boolean isVerticalRoot() { return isVerticalRoot; }
    public boolean isHorizontalReturnRoot() { return isHorizontalReturnRoot; }
    public boolean isVerticalWrapper() { return isVerticalWrapper; }
    public boolean isOutsideMarker() { return isOutsideMarker; }
    public boolean establishesBfc() { return establishesBfc; }
    public long paintStamp() { return paintStamp; }
    public boolean fragmentsStale() { return fragmentsStale; }
    public ConstraintSpace contentSpaceCache() { return contentSpaceCache; }
    public ConstraintSpace childSpaceCache() { return childSpaceCache; }
    public boolean subtreeHasContentAuto() { return subtreeHasContentAuto; }
    public float devicePixelRatio() { return devicePixelRatio; }

    /** 描画パイプラインからの DPI スケール更新用。 */
    public void setDevicePixelRatio(float dpr) {
        this.devicePixelRatio = dpr;
    }

    /** 描画中の部分矩形・fieldset 枠補正に限り、アクションの実行中だけ一時的に外寸・padding/border を差し替え、例外時も必ず元に戻す。 */
    public void withPaintBounds(float tempX, float tempY, float tempW, float tempH, boolean zeroEdges, Runnable action) {
        float x0 = this.x, y0 = this.y, w0 = this.w, h0 = this.h;
        float pl = this.pL, pr = this.pR, pt = this.pT, pb = this.pB;
        float bl = this.bL, br = this.bR, bt = this.bT, bb = this.bB;
        this.x = tempX;
        this.y = tempY;
        this.w = tempW;
        this.h = tempH;
        if (zeroEdges) {
            this.pL = this.pR = this.pT = this.pB = 0;
            this.bL = this.bR = this.bT = this.bB = 0;
        }
        try {
            action.run();
        } finally {
            this.x = x0;
            this.y = y0;
            this.w = w0;
            this.h = h0;
            if (zeroEdges) {
                this.pL = pl;
                this.pR = pr;
                this.pT = pt;
                this.pB = pb;
                this.bL = bl;
                this.bR = br;
                this.bT = bt;
                this.bB = bb;
            }
        }
    }

    /** インライン整形で 1 行に置かれた断片（テキスト 1 語、インラインブロック 1 つ、またはインラインボックスの 1 行分）。 */
    public static class InlineFragment {
        public LayoutBox box;
        public float x, y, w, h;
        /**
         * 断片の上端からベースラインまでの距離（px）。
         * これが無かったため描画側は「高さ 8px の箱」を仮定した行内センタリングで
         * 位置を推測していて、フォントサイズが混ざった行で文字が揃わなかった。
         */
        public float baseline;
        /** 文字の断片の descent（px）。内容領域の下端（ベースライン + descent）がスクロール範囲に入る。文字でなければ 0。 */
        public float descent;
        public String text;
        public List<LayoutBox> parents;
        /**
         * インラインボックスの断片が、箱の開始端（左の border・padding・角丸）と終了端（右）を持つか。
         * 行をまたぐ箱は最初の断片だけが開始端を、最後の断片だけが終了端を持つ（{@code box-decoration-break: slice}）。
         */
        public boolean startEdge = true, endEdge = true;
        /** 1 行目の断片に適用された {@code ::first-line} の実効算出スタイル（2 行目以降は null）。 */
        public ComputedStyle firstLineStyle;
        public LayoutBox firstLineOwner;

        /** {@code ::first-line} 適用時の実効スタイル（ペイントのみの変化を再レイアウトなしで反映する）。 */
        public ComputedStyle effectiveFirstLineStyle(LayoutBox target) {
            if (firstLineOwner == null) return firstLineStyle;
            ComputedStyle ownerFl = firstLineOwner.firstLineStyle;
            if (ownerFl == null) return null;
            if (target != null && (target.parent == firstLineOwner
                    || (target.parent != null && target.parent.isAnonymous && target.parent.parent == firstLineOwner))) {
                return ownerFl;
            }
            return target != null && target.style != null
                    ? target.style.derivedFirstLineChild(firstLineOwner.style, ownerFl)
                    : firstLineStyle;
        }

        public InlineFragment(LayoutBox box, float x, float y, float w, float h, String text) {
            this.box = box;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.text = text;
        }

        public void set(LayoutBox box, float x, float y, float w, float h, String text,
                        float baseline, float descent, List<LayoutBox> parents,
                        boolean startEdge, boolean endEdge) {
            set(box, x, y, w, h, text, baseline, descent, parents, startEdge, endEdge, null);
        }

        public void set(LayoutBox box, float x, float y, float w, float h, String text,
                        float baseline, float descent, List<LayoutBox> parents,
                        boolean startEdge, boolean endEdge, ComputedStyle firstLineStyle) {
            this.box = box;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.text = text;
            this.baseline = baseline;
            this.descent = descent;
            this.parents = parents;
            this.startEdge = startEdge;
            this.endEdge = endEdge;
            this.firstLineStyle = firstLineStyle;
            this.firstLineOwner = null;
        }

        public boolean isText() { return text != null; }
    }

    /**
     * {@code display} の値を outer（周りに対してどう振る舞うか）と inner（子をどう整形するか）に
     * 分けたもの（CSS Display 3）。{@code list-item} は別のフラグで持つ。
     *
     * 以前は各所が {@code display} の文字列を個別に比べていて、{@code flow-root} や
     * {@code table-cell} のように一覧に無い値はタグ名からの推測に落ちていた。
     */
    public record Display(Outer outer, Inner inner, boolean listItem) {

        public enum Outer { BLOCK, INLINE, NONE, CONTENTS }

        public enum Inner {
            FLOW, FLOW_ROOT, FLEX, GRID, TABLE,
            TABLE_ROW_GROUP, TABLE_ROW, TABLE_CELL, TABLE_CAPTION, TABLE_COLUMN, NONE
        }

        public static final Display BLOCK = new Display(Outer.BLOCK, Inner.FLOW, false);
        public static final Display INLINE = new Display(Outer.INLINE, Inner.FLOW, false);
        public static final Display HIDDEN = new Display(Outer.NONE, Inner.NONE, false);
        public static final Display CONTENTS = new Display(Outer.CONTENTS, Inner.NONE, false);

        /** 箱の display。テキストは常にインライン、宣言が無い・解釈できない値はタグから推測する。 */
        public static Display of(HtmlNode node, ComputedStyle style) {
            if (node.nodeType == NodeType.TEXT) return INLINE;
            Display parsed = parse(style.get(CssPropertyIndex.DISPLAY));
            if (parsed != null) return parsed;
            return com.nishiyu.enderframelib.core.dom.node.ElementTraits.isDefaultInline(node.tag) || ReplacedElements.isHost(node.tag) ? INLINE : BLOCK;
        }

        /** 1 キーワードの旧構文と、{@code block flow-root} / {@code inline flex} などの複数キーワード構文を読む。 */
        public static Display parse(String value) {
            if (value == null) return null;
            Display cached = PARSED.get(value);
            if (cached != null) return cached == UNPARSEABLE ? null : cached;
            Display parsed = parseUncached(value);
            if (PARSED.size() < 256) PARSED.putIfAbsent(value, parsed == null ? UNPARSEABLE : parsed);
            return parsed;
        }

        /** 解釈済みの {@code display} 値。再スタイルのたびに同じ文字列を読み直して {@link Display} を作らないための表（B9）。 */
        private static final java.util.concurrent.ConcurrentHashMap<String, Display> PARSED = new java.util.concurrent.ConcurrentHashMap<>();
        private static final Display UNPARSEABLE = new Display(Outer.NONE, Inner.NONE, true);

        private static Display parseUncached(String value) {
            String v = value.trim().toLowerCase(Locale.ROOT);
            Display legacy = switch (v) {
                case "none" -> HIDDEN;
                case "contents" -> CONTENTS;
                case "block" -> BLOCK;
                case "inline" -> INLINE;
                case "flow-root" -> new Display(Outer.BLOCK, Inner.FLOW_ROOT, false);
                case "inline-block" -> new Display(Outer.INLINE, Inner.FLOW_ROOT, false);
                case "flex" -> new Display(Outer.BLOCK, Inner.FLEX, false);
                case "inline-flex" -> new Display(Outer.INLINE, Inner.FLEX, false);
                case "grid" -> new Display(Outer.BLOCK, Inner.GRID, false);
                case "inline-grid" -> new Display(Outer.INLINE, Inner.GRID, false);
                case "table" -> new Display(Outer.BLOCK, Inner.TABLE, false);
                case "inline-table" -> new Display(Outer.INLINE, Inner.TABLE, false);
                case "list-item" -> new Display(Outer.BLOCK, Inner.FLOW, true);
                case "table-row-group", "table-header-group", "table-footer-group" ->
                        new Display(Outer.BLOCK, Inner.TABLE_ROW_GROUP, false);
                case "table-row" -> new Display(Outer.BLOCK, Inner.TABLE_ROW, false);
                case "table-cell" -> new Display(Outer.BLOCK, Inner.TABLE_CELL, false);
                case "table-caption" -> new Display(Outer.BLOCK, Inner.TABLE_CAPTION, false);
                case "table-column", "table-column-group" -> new Display(Outer.BLOCK, Inner.TABLE_COLUMN, false);
                default -> null;
            };
            if (legacy != null) return legacy;

            Outer outer = null;
            Inner inner = null;
            boolean listItem = false;
            for (String token : v.split("\\s+")) {
                switch (token) {
                    case "block" -> outer = Outer.BLOCK;
                    case "inline" -> outer = Outer.INLINE;
                    case "flow" -> inner = Inner.FLOW;
                    case "flow-root" -> inner = Inner.FLOW_ROOT;
                    case "flex" -> inner = Inner.FLEX;
                    case "grid" -> inner = Inner.GRID;
                    case "table" -> inner = Inner.TABLE;
                    case "list-item" -> listItem = true;
                    default -> {
                        return null;
                    }
                }
            }
            // list-item は flow / flow-root とだけ組める（CSS Display 3）
            if (listItem && inner != null && inner != Inner.FLOW && inner != Inner.FLOW_ROOT) return null;
            return new Display(outer != null ? outer : Outer.BLOCK, inner != null ? inner : Inner.FLOW, listItem);
        }

        /**
         * float と絶対配置はブロックレベルになる（CSS 2.2 §9.7 / CSS Display 3 の blockification）。
         * {@code inline-block} は {@code block flow-root} に、{@code inline-flex} は {@code flex} になる。
         *
         * 何が壊れていたか: 以前は {@code float: left} の {@code span} がインラインのまま行に並び、
         * 絶対配置の {@code span} はインラインの祖先として中身を行に平坦化されていた。
         */
        public Display blockified(ComputedStyle style) {
            if (outer != Outer.INLINE) return this;
            CssKeywords.Position position = style.keywordOf(CssPropertyIndex.POSITION, CssKeywords.Position.class);
            boolean outOfFlow = position == CssKeywords.Position.ABSOLUTE || position == CssKeywords.Position.FIXED
                    || style.facts().floating;
            return outOfFlow ? new Display(Outer.BLOCK, inner, listItem) : this;
        }

        public boolean isNone() { return outer == Outer.NONE; }

        public boolean isContents() { return outer == Outer.CONTENTS; }

        public boolean isInlineLevel() { return outer == Outer.INLINE; }

        /** ブロックとインラインの両方を子に持てる箱（ブロックコンテナ）。匿名ブロックはこの中にだけ作る。 */
        public boolean isBlockContainer() {
            // inline flow はインラインボックスで、ブロックコンテナではない
            return (inner == Inner.FLOW && outer == Outer.BLOCK) || inner == Inner.FLOW_ROOT
                    || inner == Inner.TABLE_CELL || inner == Inner.TABLE_CAPTION;
        }

        /** 表の内部の箱（行グループ・行・セル・キャプション・列）。 */
        public boolean isTableInternal() {
            return inner == Inner.TABLE_ROW_GROUP || inner == Inner.TABLE_ROW || inner == Inner.TABLE_CELL
                    || inner == Inner.TABLE_CAPTION || inner == Inner.TABLE_COLUMN;
        }
    }

    public enum SizingMode {
        DEFINITE,       // 幅がピクセルやパーセントで確定している
        SHRINK_TO_FIT   // 子要素のサイズに合わせて縮む (従来の w == -1)
    }

    /**
     * スクロール範囲（内容がどこまで広がっているか）の集計。
     * エンジン固有の子要素も自分で寄与できるようにするための受け皿。
     */
    public static final class ScrollExtent {

        private float right;
        private float bottom;

        public ScrollExtent(float initialRight, float initialBottom) {
            this.right = initialRight;
            this.bottom = initialBottom;
        }

        /** 1 つのボックスの右下端を取り込む。 */
        public void include(LayoutBox box, float scrollX, float scrollY) {
            include(box.x + box.w + box.mR + scrollX, box.y + box.h + box.mB + scrollY);
        }

        public void include(float rightEdge, float bottomEdge) {
            if (rightEdge > right) right = rightEdge;
            if (bottomEdge > bottom) bottom = bottomEdge;
        }

        public float right() {
            return right;
        }

        public float bottom() {
            return bottom;
        }
    }
}