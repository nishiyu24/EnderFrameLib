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
import com.nishiyu.enderframelib.core.css.CssKeywords;
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
    public Display display;
    public LayoutBox parent = null;
    /** ルートの箱だけが使う: この木をレイアウトしたときのデバイスピクセル比（描画が同じ値で候補を選ぶ）。 */
    public float devicePixelRatio = 1f;
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
     * {@code list-style-position: outside} の {@code ::marker}。通常フローに入らず、
     * リスト項目の最初の行の左に置かれる。
     */
    public boolean isOutsideMarker = false;
    /** 子の中に {@code isOutsideMarker} を持つ箱があるか（{@link BlockLayoutEngine#placeOutsideMarkers} の短絡用）。 */
    public boolean hasOutsideMarker = false;
    /** 子の中に {@code isAbsolute} を持つ箱があるか（{@link #layout} の絶対配置走査の短絡用）。 */
    public boolean hasAbsoluteChild = false;
    /** 子の親リンクの再確認が必要か（レイアウト時の {@code adopt} ループの短絡用）。 */
    public boolean needsAdoption = true;
    /** 部分木に匿名ボックス・::marker を含むか（{@link BoxTreeBuilder} の再利用収集の枝刈り用）。 */
    boolean subtreeHasGenerated = false;
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
    public boolean isRenderedLegend = false;
    /**
     * この箱がブロック整形コンテキスト（BFC）を作るか。測定のたびに {@link BoxMeasurer} が決める。
     * BFC を作る箱は子のマージンを自分の外へ貫通させない。
     */
    public boolean establishesBfc = false;
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
    private void adopt(LayoutBox child) {
        if (child.parent != this) {
            child.parent = this;
            bumpShapeEpoch();
        }
        if (child.isAbsolute) this.hasAbsoluteChild = true;
        if (child.isOutsideMarker) this.hasOutsideMarker = true;
    }
    /**
     * 親が組んでいる BFC の float の領域と、その中でのこの箱の内容ボックスの原点。
     * BFC を作らないブロックは親の float を避けて行を組み、自分の float を親の領域に置く。
     * {@link BlockLayoutEngine} が子の高さを解き直す直前に入れ、子は読んだら消す
     * （前回のレイアウトの領域を持ち越さないため）。
     */
    BlockLayoutEngine.FloatSpace floatSpace;
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
    public float[] collapsedBorders;
    /**
     * 段組（multicol）が列に割り当てた、単位（子の配置・行）ごとの移動量 {x, y}。鍵は {@code BlockLayoutEngine} の
     * 配置と行の実体（同一性で比べる）。持ち主の配置のときに足す。段組でなければ null。
     */
    public java.util.Map<Object, float[]> columnShifts;
    /** 段組の列の間の線の矩形 {x, y, w, h}（ビューポート座標）。無ければ null。 */
    public float[][] columnRules;
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
    public float ownMarginTop, ownMarginBottom;

    public void addChild(LayoutBox child) {
        if (child != null) {
            child.parent = this;
            this.children.add(child);
            if (child.isOutsideMarker) this.hasOutsideMarker = true;
            if (child.isAbsolute) this.hasAbsoluteChild = true;
            if (child.isAnonymous || child.subtreeHasGenerated) this.subtreeHasGenerated = true;
            bumpShapeEpoch();
            STRUCT_EPOCH.incrementAndGet();
        }
    }

    public void clearChildren() {
        STRUCT_EPOCH.incrementAndGet();
        this.children.clear();
        this.hasOutsideMarker = false;
        this.hasAbsoluteChild = false;
        this.subtreeHasGenerated = false;
        this.needsAdoption = false;
    }

    public void updateChildFlagsOnAdd(LayoutBox child) {
        if (child.isOutsideMarker) this.hasOutsideMarker = true;
        if (child.isAbsolute) this.hasAbsoluteChild = true;
        if (child.isAnonymous || child.subtreeHasGenerated) this.subtreeHasGenerated = true;
    }

    public void recomputeChildFlags() {
        boolean outside = false;
        boolean abs = false;
        for (int i = 0, n = children.size(); i < n; i++) {
            LayoutBox c = children.get(i);
            if (c.isOutsideMarker) outside = true;
            if (c.isAbsolute) abs = true;
        }
        this.hasOutsideMarker = outside;
        this.hasAbsoluteChild = abs;
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
        return new ContainingBlock(cbX, cbY, cbW, cbH);
    }

    /** この要素に対話状態（:hover / :active / :focus 等）のセレクタが関与するか。 */
    public boolean hasInteractiveRules = false;

    /**
     * {@code ::marker} の規則をカスケードして得た算出スタイル（リスト項目だけ）。規則が 1 つも掛からなければ null で、
     * マーカーはリスト項目の継承値で描く。{@link BoxTreeBuilder} がマーカーの箱を作るときに読む。
     */
    public ComputedStyle markerStyle = null;

    /** 入力欄（input / textarea）の {@code ::placeholder} の算出スタイル。規則が掛からなければ null。描画側が文字色を読む。 */
    public ComputedStyle placeholderStyle = null;

    /**
     * 疑似要素（{@code ::before} / {@code ::after} / {@code ::marker} / {@code ::placeholder}）のスタイルを解いたときの
     * ビューポート（幅と高さを 1 つにまとめた値）。差分レイアウトは、持ち主のスタイルのキャッシュが有効でこの値も同じなら
     * 疑似要素を解き直さない（疑似要素だけがビューポート単位を使っている場合に備えて持つ）。
     */
    public long pseudoStylesViewport = Long.MIN_VALUE;
    /** {@link #displayText} を作ったときの節の文字（同じなら {@link #refreshStyle} は作り直さない）。 */
    private String displaySource;

    public float x, y, w, h;
    public String displayText = "";

    /** margin / padding / border の 4 辺（解決済みのピクセル値）。 */
    public float mT, mR, mB, mL, pT, pR, pB, pL, bT, bR, bB, bL;
    /** row-gap: 行（ブロック方向）の間隔 / column-gap: 列（インライン方向）の間隔 */
    public float rowGap, columnGap;

    public boolean isAbsolute = false, isFixed = false, isRelative = false, isSticky = false;
    /**
     * 絶対配置の静的位置（通常フローに置かれたはずのマージンボックスの左上、絶対座標）。
     * inset が両方 auto の軸はここに置く。親のエンジンが子を並べるときに入れる。
     */
    public float staticX = Float.NaN, staticY = Float.NaN;
    public int zIndex = 0;
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
    public float lineHeightPx = 0;
    /**
     * テキストノードの整形結果。幅・ベースライン・行高を各行が持つ。
     *
     * 以前は折り返した文字列（{@code List<String>}）だけを持っていたため、
     * 描画側が行の幅と行送りを自前で測り直していた。
     */
    public List<ShapedLine> shapedLines = null;

    public List<InlineFragment> inlineFragments = new CompactList<>();
    private int fragmentCursor = 0;
    /** {@link #resetPlacementsFast} で遅延消去待ちにされた断片があるか（{@link #startFragmentPass} で解除）。 */
    public boolean fragmentsStale = false;

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
        if (fragmentCursor < inlineFragments.size()) {
            InlineFragment f = inlineFragments.get(fragmentCursor++);
            f.set(this, x, y, w, h, text, baseline, descent, parents, startEdge, endEdge);
            return f;
        }
        int spare = spareFragments == null ? 0 : spareFragments.size();
        if (spare > 0) {
            InlineFragment f = spareFragments.remove(spare - 1);
            f.set(this, x, y, w, h, text, baseline, descent, parents, startEdge, endEdge);
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
        inlineFragments.add(f);
        fragmentCursor++;
        return f;
    }

    /** 置き直しで捨てた断片オブジェクトの控え（{@link #resetPlacements} が積み、{@link #addFragment} が使う）。 */
    static final int SPARE_FRAGMENT_LIMIT = 128;
    private ArrayList<InlineFragment> spareFragments;

    private static final class TextCache {
        static final class Slot {
            String text;
            FontFace face;
            TextStyle shapeStyle;
            TextShaper.ShapedInterval shapedInterval;
            FontStyle segFont;
            WordBreak wordBreak;
            ParagraphSegments segments;
            TextShaper.WrappedParagraph wrapped;
            float strippedAdvance = Float.NaN;
        }

        Slot primary;
        Slot secondary;

        Slot acquire(String text, FontFace face) {
            if (primary == null) {
                primary = new Slot();
                primary.text = text;
                primary.face = face;
                return primary;
            }
            if (primary.text == text && primary.face == face) {
                return primary;
            }
            if (secondary != null && secondary.text == text && secondary.face == face) {
                Slot tmp = primary;
                primary = secondary;
                secondary = tmp;
                return primary;
            }
            Slot target = secondary != null ? secondary : new Slot();
            target.text = text;
            target.face = face;
            target.shapeStyle = null;
            target.shapedInterval = null;
            target.segFont = null;
            target.wordBreak = null;
            target.segments = null;
            target.wrapped = null;
            target.strippedAdvance = Float.NaN;
            secondary = primary;
            primary = target;
            return target;
        }

        Slot acquireForStyle(String text, FontFace face, FontStyle font, WordBreak wordBreak) {
            if (primary != null && primary.text == text && primary.face == face
                    && (primary.segFont == null || ((primary.segFont == font || primary.segFont.equals(font))
                            && (wordBreak == null || primary.wordBreak == null || primary.wordBreak == wordBreak)))) {
                if (primary.segFont == null) primary.segFont = font;
                if (primary.wordBreak == null && wordBreak != null) primary.wordBreak = wordBreak;
                return primary;
            }
            if (secondary != null && secondary.text == text && secondary.face == face
                    && (secondary.segFont == null || ((secondary.segFont == font || secondary.segFont.equals(font))
                            && (wordBreak == null || secondary.wordBreak == null || secondary.wordBreak == wordBreak)))) {
                Slot hit = secondary;
                secondary = primary;
                primary = hit;
                if (hit.segFont == null) hit.segFont = font;
                if (hit.wordBreak == null && wordBreak != null) hit.wordBreak = wordBreak;
                return hit;
            }
            Slot target = secondary != null ? secondary : new Slot();
            target.text = text;
            target.face = face;
            target.shapeStyle = null;
            target.shapedInterval = null;
            target.segFont = font;
            target.wordBreak = wordBreak;
            target.segments = null;
            target.wrapped = null;
            target.strippedAdvance = Float.NaN;
            secondary = primary;
            primary = target;
            return target;
        }
    }

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
    private void discardFragments() {
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
    public LayoutEngineState engineState;
    public SizingMode sizingMode = SizingMode.SHRINK_TO_FIT;

    public float scrollWidth = 0;
    public float scrollHeight = 0;

    /**
     * この箱が直接持つ行の文字の、内容領域の下端（座標系は {@link #y} と同じ）。行の高さが書体の高さより小さいとき、
     * 文字は行の下にはみ出し、スクロール範囲（{@link #scrollHeight}）に入る。行が無ければ負の無限大。
     */
    public float inlineContentBottom = Float.NEGATIVE_INFINITY;
    /**
     * 子孫の境界ボックスの右端・下端（この箱の左上からの距離）。クリップされない子孫のはみ出しを含み、
     * マージンと内側の padding は含まない。親の {@link #scrollWidth} / {@link #scrollHeight} が読む。
     */
    private float descendantOverflowRight = 0;
    private float descendantOverflowBottom = 0;

    /** min-content 幅のメモ化。flex の shrink ループから何度も問われるため。 */
    private float cachedMinContentWidth = -1;
    private long cachedMinContentKey = Long.MIN_VALUE;

    // ==========================================
    // 描画の変更印（paint stamp）＆ 表示リストスライス範囲（Segmented DisplayList）
    // ==========================================
    //
    // 箱の「描画に効く入力（位置・大きさ・スタイル・文字）」が書き換わりうる地点で、全体で単調に増える通し番号を書く。
    // 描画側（DisplayListBuilder）は、記憶を作った時点の通し番号より新しい印が自分にも祖先にも無い箱を「変わっていない」
    // とみなして、署名の比較を省ける。印は layout の再利用判定（measure を使い回す・配置を飛ばす）と同じ地点で付けるので、
    // レイアウトが「触っていない」と言う部分木は印も付かない。付け漏れは DisplayListBuilder の検証モードで検出する。

    /**
     * 直近の描画でこの箱自身が生成した DisplayItem の表示リスト上でのスライス範囲 [dlStart, dlEnd)。
     * 項目を持たない、または未描画の箱は dlStart = dlEnd = -1。
     */
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

    private static final java.util.concurrent.atomic.AtomicLong PAINT_EPOCH = new java.util.concurrent.atomic.AtomicLong();
    /** 最後に描画入力が書き換わりうる地点を通った時の通し番号。新しい箱は常に新しい。 */
    public long paintStamp = PAINT_EPOCH.incrementAndGet();
    /** 算出スタイル（と文字）が差し替わった時の通し番号。子の描画が祖先から読むのはスタイルだけなので、祖先についてはこちらを見る。 */
    private long styleStamp = paintStamp;
    /** 祖先のスタイルの印の最大値の、描画ごとのキャッシュ（{@link #pathPaintStamp}）。 */
    private long ancestorStyleStampCache;
    private int ancestorStyleStampSerial = -1;

    /** 現在の通し番号（これ以下の印は、いまの描画に反映済みとみなせる）。 */
    public static long paintEpoch() {
        return PAINT_EPOCH.get();
    }

    /** 描画入力が書き換わりうる地点を通った印を付ける。 */
    public void touchPaint() {
        paintStamp = PAINT_EPOCH.incrementAndGet();
    }

    /**
     * 自分の印と、祖先のスタイルの印の最大値。祖先の位置や大きさの変化は、子の layout が走る（子の印が付く）か、
     * クリップの比較に出るので、祖先についてはスタイルだけを見ればよい。{@code serial} が同じ間はキャッシュする。
     */
    public long pathPaintStamp(int serial) {
        return Math.max(paintStamp, parent == null ? 0L : parent.ancestorStyleStamp(serial));
    }

    private long ancestorStyleStamp(int serial) {
        if (ancestorStyleStampSerial == serial) return ancestorStyleStampCache;
        long v = styleStamp;
        if (parent != null) v = Math.max(v, parent.ancestorStyleStamp(serial));
        ancestorStyleStampCache = v;
        ancestorStyleStampSerial = serial;
        return v;
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
        // display / position / float が変わりうる。ただし文字ノードの箱は float の有無に影響しない（子を持たず、isFloating は常に false）ので、
        // 文字の差し替え（TEXT_PATCH が毎フレーム呼ぶ）では世代を進めず、float メモを残す
        if (node.nodeType != com.nishiyu.enderframelib.core.dom.node.NodeType.TEXT) bumpShapeEpoch();
        int traitsBefore = structTraits(), containerBefore = containerTraits();
        touchPaint();
        invalidateMinContentCache();
        styleStamp = paintStamp;
        ComputedStyle prevStyle = this.style;
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
            String text = TextShaper.normalize(node.text, WhiteSpace.of(newStyle.get(CssPropertyIndex.WHITE_SPACE)));
            this.displayText = LayoutMetrics.applyTextTransform(text, newStyle);
        }

        String pos = newStyle.getOrDefault(CssPropertyIndex.POSITION, "static");
        this.isFixed = "fixed".equals(pos);
        this.isAbsolute = "absolute".equals(pos) || this.isFixed;
        this.isRelative = "relative".equals(pos);
        this.isSticky = "sticky".equals(pos);
        this.zIndex = (int) newStyle.number(CssPropertyIndex.Z_INDEX, 0f);
        if (traitsBefore != structTraits()) STRUCT_EPOCH.incrementAndGet();
        if (containerBefore != containerTraits()) CONTAINER_EPOCH.incrementAndGet();
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
        return style.edgeLength(ComputedStyle.EDGE_MARGIN_L, 0, rootW, rootH) + style.edgeLength(ComputedStyle.EDGE_MARGIN_R, 0, rootW, rootH);
    }
    public float verticalDecoration() { return pT + pB + bT + bB + legendSpill; }

    /** fieldset の描画される legend（{@link #isRenderedLegend}）。無ければ null。 */
    public LayoutBox renderedLegend() {
        for (LayoutBox c : children) {
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
     * これ以上縮められない幅（min-content）。
     *
     * flex の shrink 解決ループから 1 要素あたり最大 10 回問われ、そのたびに
     * 部分木全体を再帰していたため、ビューポートごとに結果をメモ化する。
     */
    public float getMinContentWidth(LayoutContext ctx) {
        long key = ((long) ctx.viewport().rootW() << 32) | (ctx.viewport().rootH() & 0xFFFFFFFFL);
        key = key * 31 + Float.floatToIntBits(ctx.viewport().devicePixelRatio());
        if (cachedMinContentKey == key && cachedMinContentWidth >= 0) return cachedMinContentWidth;

        float result = computeMinContentWidth(ctx);
        cachedMinContentKey = key;
        cachedMinContentWidth = result;
        return result;
    }

    private float computeMinContentWidth(LayoutContext ctx) {
        if (containsInlineSize()) return horizontalDecoration();
        float rootW = ctx.viewport().rootW();
        float rootH = ctx.viewport().rootH();

        // min-content / auto などのキーワードは長さとして解けないので、内容から計算する経路に落とす
        String minWidthDecl = style.get(CssPropertyIndex.MIN_WIDTH);
        if (minWidthDecl != null && !LayoutMetrics.isIntrinsicSizeKeyword(minWidthDecl)) {
            // 枠線ボックスは padding と border より細くならない（min-width: 0 でも装飾の分は残る）
            float min = style.length(CssPropertyIndex.MIN_WIDTH, LayoutMetrics.UNBOUNDED_WIDTH, rootW, rootH);
            return style.isBorderBox() ? Math.max(min, horizontalDecoration()) : min + horizontalDecoration();
        }

        String tag = node.tag;
        if (tag.equals("select")) {
            // 閉じた select の幅は最も長い選択肢と開閉の印（描画は「ラベル ▾」）。
            // 何が壊れていたか: 以前は選択肢によらず 90px で、指定幅のある select が flex で縮みすぎていた
            return selectLabelWidth(ctx) + horizontalDecoration();
        }
        if (tag.equals("input") || tag.equals("progress")) {
            float inputW = 90;
            String type = node.attrs.getOrDefault("type", "text");
            if (tag.equals("input") && (type.equals("checkbox") || type.equals("radio"))) {
                inputW = LayoutMetrics.FALLBACK_REPLACED_SIZE;
            } else if (node.attrs.containsKey("size")) {
                try {
                    inputW = Integer.parseInt(node.attrs.get("size")) * 8;
                } catch (NumberFormatException ignored) {
                }
            }
            return inputW + horizontalDecoration();
        }

        if (node.nodeType == NodeType.TEXT) {
            // これ以上縮められない幅 = 最も長い「折り返し候補で切れない塊」の幅
            TextStyle textStyle = ctx.textStyleFor(style);
            FontFace face = ctx.faceFor(style);
            FontStyle font = textStyle.font();
            float maxWordW = 0;
            if (!textStyle.whiteSpace().wraps()) {
                // nowrap / pre は行の中で折り返さないので、行全体が 1 つの塊
                for (String line : this.displayText.split("\n", -1)) {
                    String l = textStyle.whiteSpace().collapses() ? line.strip() : line;
                    if (!l.isEmpty()) maxWordW = Math.max(maxWordW, face.advance(l, font));
                }
                return maxWordW;
            }
            // 語の幅は段落の下ごしらえ（キャッシュ）の値。前後の空白を除いた語が空なら 0 で、最大値には響かない
            return TextShaper.segmented(this.displayText, face, font, textStyle.wordBreak()).maxStrippedW();
        }

        if (children.isEmpty()) {
            // 置換要素は測った幅が固有の幅。子を持たない普通の箱の min-content は装飾だけ（今の w を読むと、親が先に
            // 測った箱と後で測る箱で値が変わり、差分レイアウトが一から組んだ結果とずれる）
            if (!ReplacedElements.isReplacedTag(node.tag) && !node.tag.equals("canvas") && !node.tag.equals("svg")
                    && !node.tag.equals("img") && !node.tag.equals("video") && !node.tag.equals("iframe")) {
                return horizontalDecoration();
            }
            float image = BoxMeasurer.replacedImageWidth(this, ctx);
            if (!Float.isNaN(image)) return image;
            return this.w > 0 ? this.w : LayoutMetrics.FALLBACK_REPLACED_SIZE;
        }

        // 折り返さない行方向の flex コンテナは、アイテムの寄与の和と間隔（CSS Flexbox §9.9.1）。
        // 何が壊れていたか: 以前は常に最大値を返していたので、flex アイテムになった flex コンテナ
        // （検索欄のような横並び）が自動の最小寸法を下回って潰れ、中身が箱の外へはみ出していた
        boolean sumItems = display != null && display.inner() == Display.Inner.FLEX && isSingleLineRowFlex();
        float max = 0, sum = 0;
        int count = 0;
        for (LayoutBox c : children) {
            if (!c.participatesInFlow()) continue;
            float contribution = c.minContentContribution(ctx) + c.intrinsicInlineMargins(ctx);
            max = Math.max(max, contribution);
            sum += contribution;
            count++;
        }
        // fieldset の legend は内容と別に置かれるが、fieldset の幅は legend より細くならない
        LayoutBox legend = renderedLegend();
        float legendW = legend == null ? 0 : legend.minContentContribution(ctx) + legend.ownInlineMargins(ctx);
        if (sumItems) return Math.max(legendW, sum + columnGap * Math.max(0, count - 1)) + horizontalDecoration();
        return Math.max(legendW, max) + horizontalDecoration();
    }

    /**
     * 親の min-content に足し込む幅（CSS Sizing 3 §5.1）。{@code width} が確定した長さならその幅、
     * そうでなければ min-content。百分率は親の幅に依存して循環するので auto と同じに扱う。
     */
    private float minContentContribution(LayoutContext ctx) {
        String width = style.get(CssPropertyIndex.WIDTH);
        if (width == null || width.contains("%") || LayoutMetrics.isIntrinsicSizeKeyword(width)) {
            return getMinContentWidth(ctx);
        }
        float v = style.length(CssPropertyIndex.WIDTH, 0, ctx.viewport().rootW(), ctx.viewport().rootH());
        float borderBox = style.isBorderBox() ? Math.max(v, horizontalDecoration()) : v + horizontalDecoration();
        String minWidth = style.get(CssPropertyIndex.MIN_WIDTH);
        if (minWidth != null && !minWidth.contains("%") && !LayoutMetrics.isIntrinsicSizeKeyword(minWidth)) {
            float min = style.length(CssPropertyIndex.MIN_WIDTH, 0, ctx.viewport().rootW(), ctx.viewport().rootH());
            borderBox = Math.max(borderBox, style.isBorderBox() ? min : min + horizontalDecoration());
        }
        return borderBox;
    }

    private boolean isSingleLineRowFlex() {
        String direction = style.getOrDefault(CssPropertyIndex.FLEX_DIRECTION, "row").trim();
        String wrap = style.getOrDefault(CssPropertyIndex.FLEX_WRAP, "nowrap").trim();
        return direction.startsWith("row") && "nowrap".equals(wrap);
    }

    /** 閉じた select に描くラベル（選択肢の文字列と「 ▾」）の最大幅。 */
    private float selectLabelWidth(LayoutContext ctx) {
        FontFace face = ctx.faceFor(style);
        FontStyle font = ctx.fontStyleFor(style);
        float max = face.advance(" ▾", font);
        for (HtmlNode option : node.children) {
            if (!"option".equals(option.tag) && !"optgroup".equals(option.tag)) continue;
            List<HtmlNode> options = "optgroup".equals(option.tag) ? option.children : List.of(option);
            for (HtmlNode o : options) {
                if (!"option".equals(o.tag)) continue;
                String label = o.attrs.containsKey("label") ? o.attrs.get("label") : o.getTextContent();
                label = label == null ? "" : label.strip().replaceAll("\\s+", " ");
                max = Math.max(max, face.advance(label + " ▾", font));
            }
        }
        return max;
    }

    /** 寸法が変わるため、測定パスの先頭でメモ化を捨てる。 */
    void invalidateMinContentCache() {
        cachedMinContentKey = Long.MIN_VALUE;
        cachedMinContentWidth = -1;
    }

    public void applyRelativeOffset(float dx, float dy) {
        this.x += dx;
        this.y += dy;
        if (inlineContentBottom > Float.NEGATIVE_INFINITY) inlineContentBottom += dy;
        for (LayoutBox child : children) child.applyRelativeOffset(dx, dy);
        for (InlineFragment frag : inlineFragments) {
            frag.x += dx;
            frag.y += dy;
        }
    }

    /**
     * 寸法を決める。実装は {@link BoxMeasurer}。
     * エンジンが子に対して再帰的に呼ぶため、入口はここに残している。
     */
    public void measure(ConstraintSpace space, LayoutContext ctx) {
        measureWith(space, ctx, true);
    }

    /**
     * @param restoreOk 親のエンジンが測定を実行している最中の呼び出し（親は子の測定のあとに stretch・auto マージン・相殺などを
     *                  改めて書く）。子の前回の測定を使い回すとき、親が書き換えた出力を自然な測定結果へ戻してよい。
     *                  親が測り直さない下降（{@link #remeasureChanged}）では false: 戻すと親の書き換えが消えるので、
     *                  出力が測定時と違う子は従来どおり測り直す。
     */
    private void measureWith(ConstraintSpace space, LayoutContext ctx, boolean restoreOk) {
        // 親が決めた寸法は制約で受け取る。測定の中身は従来どおり overrideWidth / overrideHeight を読むので、測定の間だけ写す
        // （測定の外では常に NaN。入れ子の測定は別の箱のフィールドなので干渉しない）
        overrideWidth = space.fixedInlineSize();
        overrideHeight = space.fixedBlockSize();
        boolean fixedWidth = !Float.isNaN(overrideWidth);
        try {
            measureCore(space, ctx, restoreOk);
        } finally {
            overrideWidth = Float.NaN;
            overrideHeight = Float.NaN;
        }
        noteFitToParent(measuredFitNeed, fixedWidth);
    }

    /**
     * 親の測定の最中に、親が渡した制約（上書き寸法なし）で測られた子が、使える幅に依らない（{@link #measuredFitNeed} が有限か、
     * 幅が確定している）かを親に伝える。親の fit 判定は、子の最終状態（flex が後から上書き幅で測り直した記録）ではなく、
     * 親の寸法を決めた測定のときの子の状態で行う（最終状態は親の寸法に依って決まり、使える幅への依存を隠す）。
     */
    private void noteFitToParent(float need, boolean fixedWidth) {
        LayoutBox p = parent;
        if (p == null || fixedWidth) return;
        if (need == Float.POSITIVE_INFINITY) {
            if (!(measuredWidthFree && widthIndependentReuse)) p.childFitBlocked = true;
        } else if (need > p.childFitNeedMax) {
            // 子が切られずに済む使える幅（子の使える幅は親の内容幅）。親の必要幅は、これに親の装飾を足した幅以上
            p.childFitNeedMax = need;
        }
    }

    /** 親（fill 幅ブロック）が、自分の内容が収まる幅の境目を数えるときに使うこの子の必要幅。 */
    float blockChildFitWidth() {
        return (measuredWidthFree && widthIndependentReuse) ? (w + mL + mR) : measuredFitNeed;
    }

    /** 直近の測定で、親が渡した制約で測られた子が、切られずに済むために必要とした使える幅の最大。測定の始めに 0 に戻す。 */
    private float childFitNeedMax;

    /** 直近の測定で、使える幅に依る子（折り返す・切られる・幅を埋める）があった。測定の始めに下ろし、子の測定が立てる。 */
    private boolean childFitBlocked;

    private void measureCore(ConstraintSpace space, LayoutContext ctx, boolean restoreOk) {
        restoreTentativeStash();
        if (needsAdoption) {
            for (int i = 0, n = children.size(); i < n; i++) adopt(children.get(i));
            needsAdoption = false;
        }
        if (ctx.reuseMeasurements() && canReuseMeasurement(space, ctx, restoreOk)) {
            if (restoreOk) restoreNaturalOutputs();
            if (!(subtreeChanged && ctx.firstPass())) return;
            int[] depth = DESCENT.get();
            depth[0]++;
            try {
                remeasureChanged(ctx);
            } finally {
                depth[0]--;
            }
            // 子孫の測り直しの結果が外寸に出て、自分の測定が当てはまらなくなったら、下で測り直す
            if (measurementValid) return;
        }
        if (ctx.reuseMeasurements() && (reuseDefiniteTentative(space, ctx, restoreOk)
                || fillFitReuse(space, ctx, restoreOk))) {
            return;
        }
        MEASUREMENTS_COMPUTED.increment();
        if (layoutChanged) tentatives = null; // 中身が変わった。前の仮の測定の産物は当てはまらない
        touchPaint();
        childFitBlocked = false;
        childFitNeedMax = 0;
        BoxMeasurer.measure(this, space, ctx);
        // エンジンが確定させ損ねた子（高さを測らない箱など）を、親が測り終える前に必ず確定させる
        if (hasTentativeChildren) settleTentativeChildren();
        recordMeasurement(space, ctx);
        touchPaint();
    }

    // ==========================================
    // 仮の測定の産物の再利用（flex の 2 段の測定）
    // ==========================================
    //
    // flex の項目は「仮の測定（利用可能幅）→ 確定幅での測り直し」の 2 つの制約で測られる。測定の結果を 1 つしか持たないので、
    // 次のレイアウトの最初の仮の測定が前回の確定測定を追い出し、中身が同じ項目まで毎回 2 回測り直していた。
    // 仮の測定が親に渡すのは箱自身の寸法・余白・装飾・sizingMode・内容の高さだけなので、中身が変わっていなければ
    // その産物だけを前回の値に差し替え（部分木は確定測定の状態のまま触らない）、確定の測り直しが来れば元に戻して使い回す。
    // 確定の測り直しが来なかった子は、親が settleTentative で本当に測る（部分木を仮の測定の状態で終えない）。

    /** 仮の測定が親へ渡す産物。再測定時は既存インスタンスを破壊的に更新して使い回す。 */
    private static final class BoxOutputs {
        float w, h, mT, mR, mB, mL;
        float pT, pR, pB, pL, bT, bR, bB, bL;
        float legendSpill, contentBlockExtent, ownMarginTop, ownMarginBottom;
        SizingMode sizingMode;

        static BoxOutputs capture(LayoutBox b) {
            return new BoxOutputs().captureFrom(b);
        }

        static BoxOutputs captureOrUpdate(BoxOutputs existing, LayoutBox b) {
            return existing != null ? existing.captureFrom(b) : capture(b);
        }

        BoxOutputs captureFrom(LayoutBox b) {
            this.w = b.w; this.h = b.h; this.mT = b.mT; this.mR = b.mR; this.mB = b.mB; this.mL = b.mL;
            this.pT = b.pT; this.pR = b.pR; this.pB = b.pB; this.pL = b.pL;
            this.bT = b.bT; this.bR = b.bR; this.bB = b.bB; this.bL = b.bL;
            this.legendSpill = b.legendSpill;
            this.contentBlockExtent = b.contentBlockExtent;
            this.ownMarginTop = b.ownMarginTop;
            this.ownMarginBottom = b.ownMarginBottom;
            this.sizingMode = b.sizingMode;
            return this;
        }

        void apply(LayoutBox b) {
            b.w = w; b.h = h; b.mT = mT; b.mR = mR; b.mB = mB; b.mL = mL;
            b.pT = pT; b.pR = pR; b.pB = pB; b.pL = pL; b.bT = bT; b.bR = bR; b.bB = bB; b.bL = bL;
            b.legendSpill = legendSpill; b.contentBlockExtent = contentBlockExtent;
            b.ownMarginTop = ownMarginTop; b.ownMarginBottom = ownMarginBottom; b.sizingMode = sizingMode;
        }
    }

    private enum TentativeKind { TENTATIVE, DEFINITE }

    /** 仮の測定（上書き寸法なし）・確定の測定（上書き寸法あり）の結果の産物と、その入力。 */
    private record Tentative(TentativeKind kind, ConstraintSpace space, Viewport viewport, FontResolver fonts,
                             IntrinsicSizeResolver intrinsics, int epoch, BoxOutputs outputs, boolean percentFree,
                             boolean outsideFree, boolean widthFree, float fitNeed, boolean fillFit,
                             float overrideW, float overrideH) {
        /**
         * 同じ制約か。上書き寸法は必ず一致させる。高さの % の基準が結果に効かない箱（{@link #percentFree}）は
         * それだけが違っても同じとみなし、確定幅の箱（{@link #widthFree}）や内容で寸法が決まる箱（{@link #fitNeed}）は
         * 使える幅の違いも許す。
         */
        boolean matches(ConstraintSpace s) {
            if (!sameFixedSizes(space, s)) return false;
            return space.equals(s)
                    || (percentFree && sameIgnoringPercentBlock(space, s))
                    || widthMatches(s)
                    || fitMatches(s);
        }

        boolean widthMatches(ConstraintSpace s) {
            return widthIndependentReuse && widthFree && sameBlockPercentAndParent(space, s, percentFree);
        }

        boolean fitMatches(ConstraintSpace s) {
            return fitReuseEnabled && fitNeed != Float.POSITIVE_INFINITY
                    && s.availableInlineSize() >= fitNeed && space.availableInlineSize() >= fitNeed
                    && sameBlockPercentAndParent(space, s, percentFree);
        }

        /** ビューポートが同じか。外を読まない箱（{@link #outsideFree}）や内容で寸法が決まる箱は、幅の違いを許す。 */
        boolean sameViewport(Viewport v) {
            return viewport.equals(v)
                    || (((fitReuseEnabled && fitNeed != Float.POSITIVE_INFINITY) || (viewportWidthFreeReuse && outsideFree))
                            && viewport.rootH() == v.rootH() && viewport.devicePixelRatio() == v.devicePixelRatio());
        }
    }

    private static boolean sameFixedSizes(ConstraintSpace a, ConstraintSpace b) {
        return sameLength(a.fixedInlineSize(), b.fixedInlineSize()) && sameLength(a.fixedBlockSize(), b.fixedBlockSize());
    }

    private static boolean sameBlockPercentAndParent(ConstraintSpace a, ConstraintSpace b, boolean percentFree) {
        return (percentFree || Float.floatToIntBits(a.percentageBlockSize()) == Float.floatToIntBits(b.percentageBlockSize()))
                && a.parent().equals(b.parent());
    }

    /** 高さの % の基準以外（使える幅・親の整形コンテキスト）が同じ。 */
    private static boolean sameIgnoringPercentBlock(ConstraintSpace a, ConstraintSpace b) {
        return Float.floatToIntBits(a.availableInlineSize()) == Float.floatToIntBits(b.availableInlineSize())
                && a.parent().equals(b.parent());
    }

    /**
     * この箱の測定が、親が渡す「高さの % の基準」に依らない（{@link StyleFacts#blockPercentFree} が自分と全ての子孫で真）。
     * 測ったとき（{@link #recordMeasurement}）に決める。
     */
    private boolean measuredBlockPercentFree;

    private boolean computeBlockPercentFree() {
        StyleFacts f = style.layoutFacts();
        // 横方向の割合・内在サイズのキーワードは、親の現在の幅（子の割合 padding の基準・最小内容幅のキャッシュ）を通して
        // 測定時の状態に隠れて依存する。高さの基準を無視して測り直しを省くと、その状態の更新まで省いてしまうので、含まない
        if (!blockPercentFreeEnabled || !f.blockPercentFree || f.percentGeometry
                || f.intrinsicWidthKind != StyleFacts.IW_NONE || renderedLegend() != null) return false;
        for (int i = 0, n = children.size(); i < n; i++) {
            LayoutBox c = children.get(i);
            if (c.style.facts().displayNone) continue;
            if (!c.measuredBlockPercentFree) return false;
        }
        return true;
    }

    /** 切り分け用: false なら、高さの % の基準が違う制約は常に別の入力として扱う（従来の動き）。 */
    public static volatile boolean blockPercentFreeEnabled = !Boolean.getBoolean("enderframe.layout.noblockpercentkey");

    /** 仮の測定・確定の測定の産物。仮は最大 {@link #MAX_TENTATIVES}、確定は最大 {@link #MAX_DEFINITE_TENTATIVES} を別枠で保つ。 */
    private Tentative[] tentatives;
    private static final int MAX_TENTATIVES = 4;
    private static final int MAX_DEFINITE_TENTATIVES = 2;
    private static final int MAX_TOTAL_TENTATIVES = MAX_TENTATIVES + MAX_DEFINITE_TENTATIVES;
    /** 仮の産物に差し替えている間、差し替える前（確定測定の状態）の産物を退避する領域。 */
    private BoxOutputs tentativeStash;
    private boolean tentativeStashed;
    private ConstraintSpace tentativeSpace;
    private LayoutContext tentativeCtx;
    /** 子に差し替え中の箱がある（親が測り終える前に確定させるための印）。 */
    private boolean hasTentativeChildren;

    /** 切り分け用: false なら仮の測定を常に本当に測る。 */
    /**
     * 親が寸法を決めた葉の確定測定を別枠で使い回す（{@link TentativeKind#DEFINITE}）。切り分け: {@code -Denderframe.layout.nodefiniteleaf=true}。
     */
    public static volatile boolean definiteLeafReuse = !Boolean.getBoolean("enderframe.layout.nodefiniteleaf");

    public static volatile boolean tentativeReuse = !Boolean.getBoolean("enderframe.layout.notentative");
    private static final java.util.concurrent.atomic.LongAdder TENTATIVE_SKIPPED =
            new java.util.concurrent.atomic.LongAdder();

    /** 仮の測定を飛ばした回数（効き具合を測るテスト・診断用）。 */
    public static long tentativeSkipped() {
        return TENTATIVE_SKIPPED.sum();
    }

    /**
     * {@code space} での測定を、前回の産物で済ませられるなら済ませて true。済ませた箱は、親が {@link #settleTentative} するか
     * 確定の {@link #measure} が来るまで、産物だけが仮の値になっている（部分木は前回の確定測定のまま）。
     * 前提は {@link #canReuseMeasurement} と同じで、制約と上書き寸法の比べ方だけが違う（上書きなしで測った産物と比べる）。
     */
    boolean measureTentatively(ConstraintSpace space, LayoutContext ctx) {
        restoreTentativeStash();
        Tentative[] all = tentatives;
        if (!tentativeReuse || all == null || !ctx.reuseMeasurements() || parent == null) return false;
        Tentative t = null;
        for (Tentative candidate : all) {
            if (candidate != null && candidate.kind() == TentativeKind.TENTATIVE && candidate.matches(space)) {
                t = candidate;
                break;
            }
        }
        if (t == null) return false;
        if (!Float.isNaN(overrideWidth) || !Float.isNaN(overrideHeight)) return false;
        if (!measurementValid || layoutChanged || subtreeChanged || measuredHasTable) return false;
        if (!(ctx.firstPass() || measuredRun != ctx.layoutRun() || samePassReuse)) return false;
        if (t.epoch() != MEASUREMENT_EPOCH.get() || measuredEpoch != t.epoch()
                || floatSpace != null || collapsedBorders != null
                || !t.matches(space)
                || !t.sameViewport(ctx.viewport()) || ctx.fonts() != t.fonts()
                || ctx.intrinsicResolver() != t.intrinsics()) {
            return false;
        }
        if (needsAdoption) {
            for (int i = 0, n = children.size(); i < n; i++) adopt(children.get(i));
            needsAdoption = false;
        }
        tentativeStash = BoxOutputs.captureOrUpdate(tentativeStash, this);
        tentativeStashed = true;
        tentativeSpace = space;
        tentativeCtx = ctx;
        t.outputs().apply(this);
        if (t.fillFit()) this.w = Math.max(0, space.availableInlineSize() - mL - mR);
        invalidateMinContentCache();
        parent.hasTentativeChildren = true;
        TENTATIVE_SKIPPED.increment();
        noteFitToParent(t.fitNeed(), false);
        return true;
    }

    /** 仮の測定を使う親（flex / grid）の子か。それ以外の箱は産物を記録しない（割り当てを増やさない）。 */
    private boolean parentUsesTentatives() {
        if (parent == null || parent.display == null) return false;
        Display.Inner inner = parent.display.inner();
        return inner == Display.Inner.FLEX || inner == Display.Inner.GRID;
    }

    /** 差し替えた産物を確定測定の状態に戻す。 */
    private void restoreTentativeStash() {
        if (!tentativeStashed) return;
        tentativeStashed = false;
        tentativeCtx = null;
        tentativeStash.apply(this);
        invalidateMinContentCache();
    }

    /** 仮の産物のままの箱を、本当に測って確定させる（確定の測り直しが来なかった項目）。 */
    void settleTentative() {
        if (!tentativeStashed) return;
        ConstraintSpace s = tentativeSpace;
        LayoutContext c = tentativeCtx;
        restoreTentativeStash();
        measure(s, c);
    }

    private void settleTentativeChildren() {
        hasTentativeChildren = false;
        for (int i = 0; i < children.size(); i++) children.get(i).settleTentative();
    }

    /**
     * 自分の measure を使い回したが、再レイアウト境界の中だけが変わっている（境界の寸法は変わらないので祖先は使い回せる）。
     * 境界まで降りて、中身だけを前回と同じ制約で測り直す。
     */
    private void remeasureChanged(LayoutContext ctx) {
        for (int i = 0; i < children.size(); i++) {
            LayoutBox child = children.get(i);
            if (!child.subtreeChanged || child.measuredSpace == null) continue;
            // 境界・外寸の変化を親が吸収できる箱（cutoff）・測り直しの印が付いた箱は、自分で測り直す。
            // 結果が外寸に出れば直接の親の測定が無効になり、親は呼び出し側（この関数の後）で測り直す
            if (child.layoutChanged || child.isRelayoutBoundary() || child.isCutoffEligible()) {
                child.measureWith(child.measuredSpace, ctx, false);
            } else {
                child.remeasureChanged(ctx);
                // 降りた先の結果で、この子の測定が当てはまらなくなったなら、ここで測り直す（取り残さない）
                if (!child.measurementValid) child.measureWith(child.measuredSpace, ctx, false);
            }
        }
    }

    /** 測り直しの結果を、直接の親の無効化だけで伝える下降（{@link #remeasureChanged}）の深さ。スレッドごと。 */
    private static final ThreadLocal<int[]> DESCENT = ThreadLocal.withInitial(() -> new int[1]);

    /**
     * 経路の印付けで、cutoff できない祖先に当たっても、その親に「測り直し」を前払いで付けない（結果が外寸に出たときの動的な
     * 無効化に任せる）。false なら従来どおり前払いで付ける（切り分け用）。
     */
    private static final boolean lazyThroughIneligible = !Boolean.getBoolean("enderframe.layout.nolazythrough");

    /** 切り分け用: false なら外寸が変わらない子の変化を親で吸収しない（従来の祖先まで無効にする動き）。 */
    public static volatile boolean cutoffEnabled = !Boolean.getBoolean("enderframe.layout.nocutoff");

    /**
     * 子の中身が変わって測り直しても外寸（幅・高さ・マージン）が前回と同じなら、親の測定はそのまま使える箱か。
     * 親が子から読むのは外寸だけ（配置・ベースラインは layout で組み直す）という前提が成り立つ場合に限る:
     * ブロックレベルの流れの子で、親がブロックフロー（表・段組でない）、float・絶対配置・位置指定・上書き寸法が無く、
     * 祖先のどこでも float が流れの外へ漏れない（float を避ける行・高さの解き直しが親の状態に依るため）。
     * 親が flex（行方向）のときは、アイテムの仮の主軸の寸法が中身に依らない場合だけ（{@link FlexLayoutEngine#cutoffSafeItem}）。
     */
    private boolean isCutoffEligible() {
        LayoutBox p = parent;
        if (!cutoffEnabled || p == null || display == null || p.display == null) return false;
        if (node.nodeType == com.nishiyu.enderframelib.core.dom.node.NodeType.TEXT || display.isInlineLevel() || display.isNone()
                || isAbsolute || isRelative || isSticky || isOutsideMarker || isRenderedLegend
                || isTableBox(this) || collapsedBorders != null || everLaidOutAsTable || floatSpace != null
                || !Float.isNaN(overrideWidth) || !Float.isNaN(overrideHeight)) {
            return false;
        }
        Display.Inner pi = p.display.inner();
        if (pi == Display.Inner.FLEX) {
            // flex コンテナは、仮の主軸の寸法が中身に依らないアイテムの変化なら、外寸が同じ限り吸収できる
            if (!FlexLayoutEngine.cutoffSafeItem(p, this)) return false;
        } else if (pi != Display.Inner.FLOW && pi != Display.Inner.FLOW_ROOT) {
            return false;
        }
        if (isTableBox(p) || MulticolLayout.isMulticol(p) || BlockLayoutEngine.isFloating(this)) return false;
        for (LayoutBox a = p; a != null; a = a.parent) {
            if (!a.measuredFlowFloatFree) return false;
        }
        return !BlockLayoutEngine.containsFloats(this);
    }

    /** 測ったとき、どの子も float ではなく、子孫の float が流れに漏れてもいない。{@link #isCutoffEligible} が祖先を通して見る。 */
    private boolean measuredFlowFloatFree;

    private static final java.util.concurrent.atomic.LongAdder MEASUREMENTS_COMPUTED =
            new java.util.concurrent.atomic.LongAdder();

    /** これまでに使い回さずに計算した measure の回数（差分レイアウトの効き具合を測るテスト・診断用）。 */
    public static long measurementsComputed() {
        return MEASUREMENTS_COMPUTED.sum();
    }

    // ==========================================
    // 差分レイアウト（measure の結果の再利用）
    // ==========================================
    //
    // Chromium の LayoutNG の「レイアウト結果のキャッシュ」と同じ考え方。箱の measure は、
    //   入力: 制約（ConstraintSpace）・親が決めた寸法（override*）・ビューポート・書体
    //   中身: 自分の算出スタイル・表示する文字・属性・子の並び（とその子孫）
    // だけで決まる。前回の measure から入力も中身も変わっていなければ、箱と子孫の状態は前回の measure の
    // 直後のまま使える。汚れの判定は「誰が変えたか」（サーバーの変異・クライアントの操作）ではなく、
    // 前回測ったときの署名と今の状態を比べて決めるので、どの経路で DOM が変わっても同じ結果になる。

    /** 外から測り直しを強いる世代（画像の読み込み・書体の登録など、箱の外の資源が変わったとき）。 */
    private static final java.util.concurrent.atomic.AtomicInteger MEASUREMENT_EPOCH =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 全ての箱の measure の結果を捨てさせる（次の差分レイアウトは全部測り直す）。 */
    public static void invalidateAllMeasurements() {
        MEASUREMENT_EPOCH.incrementAndGet();
    }

    /** 前回の measure の結果が今の状態に当てはまるか。子孫の measure や外からの書き換えで false になる。 */
    private boolean measurementValid = false;
    /** 前回の measure の後に、自分か子孫の中身が変わった（{@link #markLayoutChanges} が決める）。 */
    private boolean layoutChanged = true;
    private ConstraintSpace measuredSpace;
    /** エンジンへ渡す内容の制約と子へ渡す制約の再利用用（値が同じ間は確保し直さない）。 */
    public ConstraintSpace contentSpaceCache, childSpaceCache;

    /** 前回の測定で、親が高さを固定していない（自然な高さで測った）か。 */
    boolean measuredWithoutFixedHeight() {
        return measuredSpace != null && Float.isNaN(measuredSpace.fixedBlockSize());
    }
    private float measuredOverrideW = Float.NaN, measuredOverrideH = Float.NaN;
    private Viewport measuredViewport;
    private FontResolver measuredFonts;
    private IntrinsicSizeResolver measuredIntrinsics;
    private int measuredEpoch;
    private long measuredRun = -1;
    /** measure を終えたときの自分の寸法。親が後から書き換えた（stretch・表の行の高さ）なら使い回さない。 */
    private float measuredW, measuredH, measuredMT, measuredMR, measuredMB, measuredML;
    /** 測定を終えたときの自然な出力の全部（親が後から書き換える前）。 */
    private BoxOutputs measuredOutputs;
    /** measure を終えたときの中身の署名。 */
    private ComputedStyle measuredStyle;
    private ComputedStyle measuredPlaceholderStyle;
    private String measuredText;
    private int measuredContentHash;
    private boolean measuredOutsideMarker, measuredRenderedLegend;
    /**
     * 表（か表の中の箱）として測ったことがあるか。表のエンジンは中の箱へ枠線の分担・縦の揃えを直接書き込むので、
     * 一度でも表として測った箱は使い回さない。再スタイルが前回の木の算出スタイルをその場で書き換えるため、
     * 今の display だけでは前回表だったかが分からない（クライアントの class 変更がこの経路を通る）。
     */
    private boolean everLaidOutAsTable;
    private static final LayoutBox[] EMPTY_BOX_ARRAY = new LayoutBox[0];
    private LayoutBox[] measuredChildren;
    private boolean measuredFirstFlowChild;
    private boolean measuredHadFloatChild;

    /** 切り分け用: false なら 2 回目の measure パスで、1 回目に測った箱も測り直す（従来の動き）。 */
    public static volatile boolean samePassReuse = !Boolean.getBoolean("enderframe.layout.nosamerun");
    /**
     * 表を含む（自分か子孫が表の箱）か。表は measure の後に行の高さ・列幅を子へ書き戻し、2 回目のパスがそれを入力として
     * 読み直すので、同じ run の中では表を含む部分木を使い回さない（{@link #samePassReuse}）。測り終えたときに決める。
     */
    private boolean measuredHasTable;

    /**
     * 幅だけが変わった（親が渡す使える幅・ビューポートの幅）ときに、前回の測定を使い回してよいか。箱自身の横の寸法が確定した長さで
     * （{@link com.nishiyu.enderframelib.core.css.StyleFacts#horizontalFixed}）、子孫に箱の外の寸法（ビューポート／コンテナ単位・絶対配置）を
     * 読むものが無い（{@link #measuredWidthFree}）なら、測定の結果は幅に依らない。
     * 切り分け用: {@code -Denderframe.layout.nowidthreuse=true} で無効。
     */
    public static volatile boolean widthIndependentReuse = !Boolean.getBoolean("enderframe.layout.nowidthreuse");

    /** この箱の測定が、親の使える幅とビューポートの幅に依らない（前回の測定を記録した時点）。 */
    private boolean measuredWidthFree;
    /** この部分木が、箱の外の寸法（ビューポート／コンテナ単位・絶対配置）を読まない。幅に依らない測定の判定の材料。 */
    private boolean measuredOutsideFree;

    /**
     * ビューポートが前回の測定と同じとみなせるか。部分木が箱の外の寸法（ビューポート単位・絶対配置）を読まない
     * （{@link #measuredOutsideFree}）なら、ビューポートの幅は測定の結果に効かない（ウィンドウの幅変更で、制約が同じ箱を測り直さない）。
     * 高さと解像度比は、パーセントの高さ・画像の候補選びに効くので比べる。
     * 切り分け用: {@code -Denderframe.layout.noviewportwidthfree=true} で無効。
     */
    public static volatile boolean viewportWidthFreeReuse = !Boolean.getBoolean("enderframe.layout.noviewportwidthfree");

    private boolean sameViewportForMeasure(Viewport vp) {
        Viewport old = measuredViewport;
        if (old == null) return false;
        if (vp.equals(old)) return true;
        return viewportWidthFreeReuse && measuredOutsideFree && vp.rootH() == old.rootH()
                && vp.devicePixelRatio() == old.devicePixelRatio();
    }

    private boolean widthOnlyChange(ConstraintSpace space, LayoutContext ctx) {
        if (!widthIndependentReuse || !measuredWidthFree || measuredSpace == null || measuredViewport == null) return false;
        Viewport vp = ctx.viewport(), old = measuredViewport;
        return (measuredBlockPercentFree || Float.floatToIntBits(space.percentageBlockSize()) == Float.floatToIntBits(measuredSpace.percentageBlockSize()))
                && space.parent().equals(measuredSpace.parent())
                && vp.rootH() == old.rootH() && vp.devicePixelRatio() == old.devicePixelRatio();
    }

    /**
     * 内容で寸法が決まる箱（{@code width: auto} の shrink-to-fit と、折り返さない 1 行の文字）の fit 再利用。
     * 親が渡す使える幅が {@link #measuredFitNeed} 以上なら、前回の幅が何であっても測定の結果は同じ
     * （使える幅に届かず折り返す・縮められる箱だけが、使える幅に依る）。Blink の LayoutNG が fit-content の結果を
     * 使い回すのと同じ考え方で、flex の項目・インラインブロック・span のように、幅が 1px 変わっても中身が収まっている箱を
     * 測り直さない。切り分け用: {@code -Denderframe.layout.nofitreuse=true} で無効。
     */
    public static volatile boolean fitReuseEnabled = !Boolean.getBoolean("enderframe.layout.nofitreuse");

    /** この使える幅以上なら測定の結果が同じ（無限大は当てはまらない）。測り終えたときに決める。 */
    private float measuredFitNeed = Float.POSITIVE_INFINITY;
    /** fit 判定時に箱自身の幅も不変か（shrink-to-fit・上書き幅・テキスト）。false なら fill 幅（再測定を省くときは幅だけ更新）。 */
    private boolean measuredFitFixedW;
    /** fill 幅で、幅以外の出力と子の幅が {@link #measuredFitNeed} 以上で不変であり、測定を省いて幅だけ更新できるか。 */
    private boolean measuredFillFitReusable;

    private boolean fitReuse(ConstraintSpace space, LayoutContext ctx) {
        return measuredFitFixedW && matchesFitConstraint(space, ctx);
    }

    private boolean matchesFitConstraint(ConstraintSpace space, LayoutContext ctx) {
        float need = measuredFitNeed;
        if (!fitReuseEnabled || need == Float.POSITIVE_INFINITY || measuredSpace == null || measuredViewport == null) return false;
        if (!(space.availableInlineSize() >= need) || !(measuredSpace.availableInlineSize() >= need)) return false;
        Viewport vp = ctx.viewport(), old = measuredViewport;
        // 文字は整形した行の幅・高さだけが結果で、親の整形コンテキスト（shrink かどうか）や % の基準には依らない
        boolean sameContext = node.nodeType == com.nishiyu.enderframelib.core.dom.node.NodeType.TEXT
                || ((measuredBlockPercentFree || Float.floatToIntBits(space.percentageBlockSize()) == Float.floatToIntBits(measuredSpace.percentageBlockSize()))
                        && space.parent().equals(measuredSpace.parent()));
        return sameContext && vp.rootH() == old.rootH() && vp.devicePixelRatio() == old.devicePixelRatio();
    }

    /**
     * fill 幅のブロック（{@code width: auto}）で、内容が収まり切り子の幅も親の幅に依らないなら、
     * {@link BoxMeasurer#measure} を省いて自然な出力を戻した上で自分の幅だけを新しい使える幅に更新する。
     */
    private boolean fillFitReuse(ConstraintSpace space, LayoutContext ctx, boolean restoreOk) {
        if (!restoreOk || !measuredFillFitReusable || !Float.isNaN(overrideWidth) || !Float.isNaN(overrideHeight)) {
            return false;
        }
        if (!measurementValid || layoutChanged || subtreeChanged) return false;
        if (!(ctx.firstPass() || measuredRun != ctx.layoutRun() || (samePassReuse && !measuredHasTable))) return false;
        if (measuredEpoch != MEASUREMENT_EPOCH.get() || floatSpace != null || collapsedBorders != null) return false;
        if (ctx.fonts() != measuredFonts || ctx.intrinsicResolver() != measuredIntrinsics) return false;
        if (!matchesFitConstraint(space, ctx)) return false;
        restoreNaturalOutputs();
        float newW = Math.max(0, space.availableInlineSize() - mL - mR);
        if (newW != w) {
            w = newW;
            invalidateMinContentCache();
            touchPaint();
            placementValid = false;
        }
        measuredSpace = space;
        measuredViewport = ctx.viewport();
        measuredRun = ctx.layoutRun();
        measuredW = w;
        measuredOutputs = BoxOutputs.captureOrUpdate(measuredOutputs, this);
        return true;
    }

    private float computeFitNeed() {
        final float none = Float.POSITIVE_INFINITY;
        measuredFitFixedW = false;
        measuredFillFitReusable = false;
        if (!fitReuseEnabled || parent == null || !measuredOutsideFree || measuredHasTable) return none;
        if (floatSpace != null || collapsedBorders != null
                || isAbsolute || isFixed || isRenderedLegend || isOutsideMarker || display == null || display.isNone()) return none;
        StyleFacts f = style.layoutFacts();
        if (f.floating) return none;
        boolean textNode = node.nodeType == com.nishiyu.enderframelib.core.dom.node.NodeType.TEXT;
        if (!Float.isNaN(overrideWidth)) {
            // 幅は親が決めて渡した値（flex の伸縮・stretch）。中身はその幅で測るので、使える幅は余白・パディングの % と
            // auto の余白にしか効かない。文字は使える幅で整形した行を持つので当てはまらない
            if (!textNode && f.edgesAvailableFree) {
                measuredFitFixedW = true;
                return 0f;
            }
            return none;
        }
        if (!f.fitCandidate) return none;
        if (textNode) {
            // 折り返さずに 1 行に収まった文字は、使える幅がその行の幅以上なら同じ 1 行になる
            List<ShapedLine> lines = shapedLines;
            if (lines == null || lines.size() != 1 || hasActiveMaxWidth(style)) return none;
            measuredFitFixedW = true;
            return w + mL + mR;
        }
        Display.Inner inner = display.inner();
        if (inner != Display.Inner.FLOW && inner != Display.Inner.FLOW_ROOT && inner != Display.Inner.FLEX) return none;
        // 内容の幅が使える幅で切られていない（切られていれば w + 余白が使える幅ちょうどになる）ことは、
        // 呼び出し側の「前回の使える幅 >= need」の判定で確かめる。中身は文字・確定幅の箱・同じ条件の箱だけ
        if (measuredClamped || renderedLegend() != null || MulticolLayout.isMulticol(this)
                || containsInlineSize() || !measuredFlowFloatFree || childFitBlocked) {
            return none;
        }
        for (int i = 0, n = children.size(); i < n; i++) {
            LayoutBox c = children.get(i);
            if (c.style.facts().displayNone) continue;
            if (c.isOutsideMarker || c.isAbsolute || BlockLayoutEngine.isFloating(c)) return none;
        }
        if (sizingMode == SizingMode.SHRINK_TO_FIT) {
            // min/max-width で切り詰めた w ではなく、切り詰め前の内容の幅が、使える幅に届くかの境目
            if (Float.isNaN(naturalInlineSize)) return none;
            measuredFitFixedW = true;
            return Math.max(naturalInlineSize + mL + mR, childFitNeedMax + horizontalDecoration() + mL + mR);
        }
        if (sizingMode != SizingMode.DEFINITE || isFillWidthExcludedTag(node.tag) || Float.isNaN(naturalFitInnerWidth)) {
            return none;
        }
        if (hasActiveMaxWidth(style) || hasNonZeroMinWidth(style) || style.containsKey(CssPropertyIndex.ASPECT_RATIO)) {
            return none;
        }
        float innerNeed = Math.max(naturalFitInnerWidth, childFitNeedMax);
        float need = innerNeed + horizontalDecoration() + mL + mR + (innerNeed > 0 ? 0.02f : 0f);
        measuredFillFitReusable = Float.isNaN(overrideHeight) && allChildrenWidthInvariantForFill(inner);
        return need;
    }

    private boolean allChildrenWidthInvariantForFill(Display.Inner inner) {
        if (inner == Display.Inner.FLEX) return true; // FlexLayoutEngine が grow=0・wrap なし・auto 余白なしの行方向だけ naturalFitInnerWidth を入れる
        for (int i = 0, n = children.size(); i < n; i++) {
            LayoutBox c = children.get(i);
            if (c.style.facts().displayNone) continue;
            if (!(c.node.nodeType == com.nishiyu.enderframelib.core.dom.node.NodeType.TEXT
                    || (c.measuredWidthFree && widthIndependentReuse) || c.sizingMode == SizingMode.SHRINK_TO_FIT)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasActiveMaxWidth(ComputedStyle s) {
        if (!s.containsKey(CssPropertyIndex.MAX_WIDTH)) return false;
        String v = s.get(CssPropertyIndex.MAX_WIDTH);
        return v != null && !"none".equalsIgnoreCase(v.trim());
    }

    private static boolean hasNonZeroMinWidth(ComputedStyle s) {
        if (!s.containsKey(CssPropertyIndex.MIN_WIDTH)) return false;
        String v = s.get(CssPropertyIndex.MIN_WIDTH);
        if (v == null) return false;
        String t = v.trim();
        return !t.equalsIgnoreCase("auto") && !t.equals("0") && !t.equals("0px");
    }

    private static boolean isFillWidthExcludedTag(String tag) {
        return tag == null || tag.equals("img") || tag.equals("svg") || tag.equals("canvas")
                || tag.equals("input") || tag.equals("select") || tag.equals("progress")
                || ReplacedElements.isHostImageLike(tag) || ReplacedElements.isHostScene(tag);
    }

    private void recordWidthIndependence() {
        StyleFacts f = style.layoutFacts();
        // 画像の候補選び（srcset / sizes）はビューポートの幅を読む
        boolean outside = !isAbsolute && !f.usesViewportUnits && !(node != null && ("img".equals(node.tag) || "picture".equals(node.tag)));
        for (int i = 0, n = children.size(); outside && i < n; i++) {
            LayoutBox c = children.get(i);
            outside = c.style.facts().displayNone || c.measuredOutsideFree;
        }
        measuredOutsideFree = outside;
        boolean free = outside && f.horizontalFixed && !isFixed && node.nodeType != com.nishiyu.enderframelib.core.dom.node.NodeType.TEXT
                && !measuredHasTable && !isTableBox(this)
                && (!display.isInlineLevel() || BlockLayoutEngine.isAtomicInline(this));
        measuredWidthFree = free;
    }

    private boolean canReuseMeasurement(ConstraintSpace space, LayoutContext ctx, boolean restoreOk) {
        return measurementValid
                && !layoutChanged
                && (ctx.firstPass() || measuredRun != ctx.layoutRun() || (samePassReuse && !measuredHasTable))
                && measuredEpoch == MEASUREMENT_EPOCH.get()
                && floatSpace == null
                && collapsedBorders == null
                && (((space.equals(measuredSpace) || (measuredBlockPercentFree && sameIgnoringPercentBlock(space, measuredSpace)))
                        && sameViewportForMeasure(ctx.viewport())) || widthOnlyChange(space, ctx) || fitReuse(space, ctx))
                && sameLength(overrideWidth, measuredOverrideW)
                && sameLength(overrideHeight, measuredOverrideH)
                && ctx.fonts() == measuredFonts
                && ctx.intrinsicResolver() == measuredIntrinsics
                && (restoreOk || (w == measuredW && h == measuredH
                        && mT == measuredMT && mR == measuredMR && mB == measuredMB && mL == measuredML));
    }

    /** 測定を使い回すとき、親が書き換えた出力（stretch の高さ・auto/相殺の余白など）を、測定したときの自然な結果へ戻す。 */
    private void restoreNaturalOutputs() {
        BoxOutputs natural = measuredOutputs;
        if (natural == null) return;
        if (w == measuredW && h == measuredH && mT == measuredMT && mR == measuredMR && mB == measuredMB && mL == measuredML
                && contentBlockExtent == natural.contentBlockExtent) {
            return;
        }
        natural.apply(this);
        invalidateMinContentCache();
        touchPaint();
    }

    private static boolean sameLength(float a, float b) {
        return Float.isNaN(a) ? Float.isNaN(b) : a == b;
    }

    /**
     * measure を通らずに箱の中身を組み直した（{@link BoxMeasurer#resolveFlexibleHeights}）。この箱と祖先の
     * 前回の measure の結果は使わせない。
     */
    void invalidateMeasurement() {
        touchPaint();
        for (LayoutBox b = this; b != null; b = b.parent) {
            b.measurementValid = false;
            b.placementValid = false;
        }
    }

    /** 記録済みの子の並び（配列）が、今の子と同じ箱を同じ順に並べているか（同じなら配列を作り直さない）。 */
    private boolean sameChildren(LayoutBox[] recorded) {
        if (recorded == null || recorded.length != children.size()) return false;
        for (int i = 0; i < recorded.length; i++) if (recorded[i] != children.get(i)) return false;
        return true;
    }

    private void recordMeasurement(ConstraintSpace space, LayoutContext ctx) {
        // 再レイアウト境界が中身だけを測り直し、外から見える寸法が前回と同じなら、祖先の測定は無効にならない
        boolean outerSizeStable = measuredStyle != null && w == measuredW && h == measuredH
                && mT == measuredMT && mR == measuredMR && mB == measuredMB && mL == measuredML;
        measuredBlockPercentFree = computeBlockPercentFree();
        measuredSpace = space;
        measuredOverrideW = overrideWidth;
        measuredOverrideH = overrideHeight;
        measuredViewport = ctx.viewport();
        measuredFonts = ctx.fonts();
        measuredIntrinsics = ctx.intrinsicResolver();
        measuredEpoch = MEASUREMENT_EPOCH.get();
        measuredRun = ctx.layoutRun();
        measuredOutputs = BoxOutputs.captureOrUpdate(measuredOutputs, this);
        measuredW = w;
        measuredH = h;
        measuredMT = mT;
        measuredMR = mR;
        measuredMB = mB;
        measuredML = mL;
        measuredStyle = style;
        measuredPlaceholderStyle = placeholderStyle;
        measuredText = displayText;
        measuredContentHash = contentHash();
        measuredOutsideMarker = isOutsideMarker;
        measuredRenderedLegend = isRenderedLegend;
        everLaidOutAsTable |= isTableBox(this) || collapsedBorders != null;
        measuredChildren = sameChildren(measuredChildren) ? measuredChildren
                : children.isEmpty() ? EMPTY_BOX_ARRAY : children.toArray(EMPTY_BOX_ARRAY);
        measuredFirstFlowChild = BlockLayoutEngine.isFirstFlowChild(this);
        measuredHadFloatChild = hasFloatingChild();
        boolean floatFree = true;
        for (int i = 0, n = children.size(); i < n && floatFree; i++) {
            LayoutBox c = children.get(i);
            floatFree = !BlockLayoutEngine.isFloating(c) && !BlockLayoutEngine.containsFloats(c);
        }
        measuredFlowFloatFree = floatFree;
        layoutChanged = false;
        boolean hasTable = everLaidOutAsTable || isTableBox(this) || collapsedBorders != null;
        for (int i = 0; !hasTable && i < children.size(); i++) hasTable = children.get(i).measuredHasTable;
        measuredHasTable = hasTable;
        recordWidthIndependence();
        measuredFitNeed = computeFitNeed();
        if (tentativeReuse && parentUsesTentatives() && floatSpace == null && collapsedBorders == null) {
            boolean tentativePhase = Float.isNaN(overrideWidth) && Float.isNaN(overrideHeight);
            if (tentativePhase || isDefiniteCacheableLeaf()) {
                TentativeKind kind = tentativePhase ? TentativeKind.TENTATIVE : TentativeKind.DEFINITE;
                boolean fillFit = tentativePhase && measuredFillFitReusable;
                float tentFitNeed = tentativePhase
                        ? ((measuredFitFixedW || fillFit) ? measuredFitNeed : Float.POSITIVE_INFINITY)
                        : (!Float.isNaN(overrideWidth) && !style.layoutFacts().edgesAvailableFree
                                ? Float.POSITIVE_INFINITY : measuredFitNeed);
                Tentative fresh = new Tentative(kind, space, ctx.viewport(), ctx.fonts(), ctx.intrinsicResolver(),
                        measuredEpoch, BoxOutputs.capture(this), measuredBlockPercentFree, measuredOutsideFree,
                        measuredWidthFree, tentFitNeed, fillFit, overrideWidth, overrideHeight);
                recordTentative(fresh, tentativePhase ? MAX_TENTATIVES : MAX_DEFINITE_TENTATIVES);
            }
        }
        measurementValid = true;
        // 子孫を測り直したので、祖先が前回の measure で残した状態はもう当てはまらない。
        // いま祖先が測っている途中なら、祖先は測り終えたときに記録し直す
        boolean absorbed = outerSizeStable && (isRelayoutBoundary() || isCutoffEligible());
        if (DESCENT.get()[0] > 0) invalidateParentOnly(!absorbed);
        else invalidateAncestors(!(outerSizeStable && isRelayoutBoundary()));
    }

    /** 子を持たない要素だけ、BoxOutputs が測定状態のすべてなので確定測定を別枠で使い回せる。 */
    private boolean isDefiniteCacheableLeaf() {
        return definiteLeafReuse && children.isEmpty()
                && node.nodeType != com.nishiyu.enderframelib.core.dom.node.NodeType.TEXT
                && floatSpace == null && collapsedBorders == null && !measuredHasTable
                && !isTableBox(this) && renderedLegend() == null;
    }

    private void recordTentative(Tentative fresh, int maxForKind) {
        if (tentatives == null) {
            tentatives = new Tentative[MAX_TENTATIVES];
            tentatives[0] = fresh;
            return;
        }
        int firstNull = -1, countForKind = 0, oldestOfKind = -1;
        for (int i = 0; i < tentatives.length; i++) {
            Tentative old = tentatives[i];
            if (old == null) {
                if (firstNull < 0) firstNull = i;
                continue;
            }
            if (old.kind() == fresh.kind()) {
                if (old.matches(fresh.space())) {
                    tentatives[i] = fresh;
                    return;
                }
                if (oldestOfKind < 0) oldestOfKind = i;
                countForKind++;
            }
        }
        if (countForKind < maxForKind) {
            if (firstNull >= 0) {
                tentatives[firstNull] = fresh;
                return;
            }
            int nextLen = Math.min(MAX_TOTAL_TENTATIVES, tentatives.length + 2);
            tentatives = java.util.Arrays.copyOf(tentatives, nextLen);
            tentatives[tentatives.length - 2] = fresh;
            return;
        }
        // この種類の枠がいっぱい: この種類で一番古いものを落とし、他種類の後ろを詰めて末尾へ置く
        int lastNonNull = oldestOfKind;
        for (int i = oldestOfKind + 1; i < tentatives.length && tentatives[i] != null; i++) {
            tentatives[i - 1] = tentatives[i];
            lastNonNull = i;
        }
        tentatives[lastNonNull] = fresh;
    }

    /**
     * 仮の測定が {@link #measuredSpace} を上書きしたあとでも、確定測定の別枠（{@link TentativeKind#DEFINITE}）に
     * 同じ制約の記録があり部分木を持たない葉なら、再測定を省いて確定結果を戻す。
     */
    private boolean reuseDefiniteTentative(ConstraintSpace space, LayoutContext ctx, boolean restoreOk) {
        if (!tentativeReuse || tentatives == null || (Float.isNaN(overrideWidth) && Float.isNaN(overrideHeight))) {
            return false;
        }
        if (!measurementValid || layoutChanged || subtreeChanged || !isDefiniteCacheableLeaf()) return false;
        if (!(ctx.firstPass() || measuredRun != ctx.layoutRun() || samePassReuse)) return false;
        int epoch = MEASUREMENT_EPOCH.get();
        if (measuredEpoch != epoch) return false;
        Tentative match = null;
        for (Tentative t : tentatives) {
            if (t == null || t.kind() != TentativeKind.DEFINITE) continue;
            if (t.epoch() == epoch && t.fonts() == ctx.fonts() && t.intrinsics() == ctx.intrinsicResolver()
                    && sameLength(t.overrideW(), overrideWidth) && sameLength(t.overrideH(), overrideHeight)
                    && t.matches(space) && t.sameViewport(ctx.viewport())) {
                match = t;
                break;
            }
        }
        if (match == null) return false;
        BoxOutputs o = match.outputs();
        boolean outputsDiffer = w != o.w || h != o.h || mT != o.mT || mR != o.mR || mB != o.mB || mL != o.mL
                || contentBlockExtent != o.contentBlockExtent;
        if (!restoreOk && outputsDiffer) return false;
        if (restoreOk && outputsDiffer) {
            o.apply(this);
            invalidateMinContentCache();
            touchPaint();
        }
        measuredBlockPercentFree = match.percentFree();
        measuredOutsideFree = match.outsideFree();
        measuredWidthFree = match.widthFree();
        measuredSpace = space;
        measuredOverrideW = overrideWidth;
        measuredOverrideH = overrideHeight;
        measuredViewport = ctx.viewport();
        measuredFonts = match.fonts();
        measuredIntrinsics = match.intrinsics();
        measuredEpoch = epoch;
        measuredRun = ctx.layoutRun();
        // 記録（Tentative.outputs）と同じ実体を持たない: 以降の captureOrUpdate がその場で書き換えて記録を壊す
        measuredOutputs = BoxOutputs.captureOrUpdate(measuredOutputs, this);
        measuredW = w;
        measuredH = h;
        measuredMT = mT;
        measuredMR = mR;
        measuredMB = mB;
        measuredML = mL;
        measuredFitNeed = match.fitNeed();
        measuredFitFixedW = !Float.isNaN(overrideWidth) && measuredFitNeed != Float.POSITIVE_INFINITY;
        measuredFillFitReusable = false;
        measuredClamped = false;
        childFitBlocked = false;
        childFitNeedMax = 0;
        return true;
    }

    /**
     * 変化を子から親へ 1 段ずつ伝える下降（{@link #remeasureChanged}）の中で測り直したとき。配置の記録は祖先の全てで捨てるが、
     * 測定が当てはまらなくなるのは外寸が変わったときの直接の親だけ。親は下降の呼び出し元として、戻ってから自分の測定が
     * 無効になっていれば測り直し、その結果でさらに上へ伝える。
     */
    private void invalidateParentOnly(boolean measureAffected) {
        for (LayoutBox p = parent; p != null; p = p.parent) p.placementValid = false;
        if (measureAffected && parent != null) parent.measurementValid = false;
    }

    /**
     * 子孫を測り直したので、祖先が前回の measure で残した状態はもう当てはまらない。ただし再レイアウト境界から上は、
     * 境界の寸法が変わらないので測定は有効なまま（配置の記録だけは全ての祖先で捨てる）。
     */
    private void invalidateAncestors(boolean measureAffected) {
        boolean measureStillValid = measureAffected;
        for (LayoutBox p = parent; p != null; p = p.parent) {
            p.placementValid = false;
            if (measureStillValid) {
                p.measurementValid = false;
                if (p.isRelayoutBoundary()) measureStillValid = false;
            }
        }
    }

    /**
     * measure が読む、スタイルと子の箱以外の中身（属性・フォーム部品の中身）。
     * style / class はカスケードを通して算出スタイルに出るので数えない（描画だけの変化で測り直さないため）。
     */
    /** 属性（style / class を除く）のハッシュ。属性の版番号が同じ間は使い回す。 */
    private int attrHash;
    private int attrHashVersion = -1;
    private HtmlNode attrHashNode;

    private int contentHash() {
        if (node == null) return 0;
        int version = node.attrVersion();
        if (attrHashVersion != version || attrHashNode != node) {
            int hash = 1;
            for (java.util.Map.Entry<String, String> attr : node.attrs.entrySet()) {
                String key = attr.getKey();
                if ("style".equals(key) || "class".equals(key)) continue;
                hash = 31 * hash + key.hashCode();
                hash = 31 * hash + java.util.Objects.hashCode(attr.getValue());
            }
            attrHash = hash;
            attrHashVersion = version;
            attrHashNode = node;
        }
        int hash = attrHash;
        // 文字を子の箱に持たないフォーム部品は、DOM の中身から測る
        String tag = node.tag;
        if ("textarea".equals(tag) || "select".equals(tag) || "option".equals(tag)) {
            hash = 31 * hash + node.getTextContent().hashCode();
        }
        return hash;
    }

    /**
     * layout（位置決め）の前に、前回の layout が残した配置の結果を新しく作った箱と同じ状態に戻す。
     * layout がたどらない箱（行に入らなくなった箱・インラインの中のブロックなど）には前回の位置・行の断片・
     * スクロール範囲が残り、描画がそれを読んでしまう（新しく作った箱なら初期値）。measure はこれらを読まないので、
     * 差分レイアウトが使い回す measure の結果には影響しない。
     */
    public static void resetPlacements(LayoutBox box) {
        resetPlacements(box, -1);
    }

    /**
     * @param keepRun 0 以上なら、この run で測り直されていない純粋な箱は配置を残す（{@link #layout} が原点を比べて、
     *                同じなら飛ばし、違えば自分で捨ててから置き直す）。負なら全てを捨てる
     */
    public static void resetPlacements(LayoutBox box, long keepRun) {
        if (keepRun >= 0 && box.canKeepPlacement(keepRun)) return;
        // 配置を捨てた箱は、前回の layout の記録も無効にする（子を飛ばして古い 0,0 のまま残さない）
        box.touchPaint();
        if (keepRun < 0) {
            box.placementPure = false;
            box.keptPure = false;
            box.lastLayoutX = Float.NaN;
        }
        box.discardFragments();
        box.fragmentsStale = false;
        box.fragmentCursor = 0;
        box.x = 0;
        box.y = 0;
        box.staticX = Float.NaN;
        box.staticY = Float.NaN;
        box.scrollWidth = 0;
        box.scrollHeight = 0;
        box.descendantOverflowRight = 0;
        box.descendantOverflowBottom = 0;
        box.inlineContentBottom = Float.NEGATIVE_INFINITY;
        for (int i = 0, n = box.children.size(); i < n; i++) resetPlacements(box.children.get(i), keepRun);
    }

    /**
     * {@link #resetPlacements(LayoutBox, long)} の高速版。
     * 祖先から float / 相対配置の障害を O(1) で伝播し、断片の {@link #discardFragments()} は
     * 今回の layout が触れなかった箱だけへ遅延する（全箱の出し入れを省く）。
     */
    public static void resetPlacementsFast(LayoutBox box, long keepRun, List<LayoutBox> staleFrags) {
        resetPlacementsFast(box, keepRun, false, false, staleFrags);
    }

    private static void resetPlacementsFast(LayoutBox box, long keepRun,
                                            boolean ancestorOffset, boolean inBfcFloat,
                                            List<LayoutBox> staleFrags) {
        if (keepRun >= 0 && box.canKeepPlacement(keepRun, ancestorOffset || inBfcFloat)) return;
        box.touchPaint();
        if (keepRun < 0) {
            box.placementPure = false;
            box.keptPure = false;
            box.lastLayoutX = Float.NaN;
        }
        if (!box.inlineFragments.isEmpty()) {
            box.fragmentsStale = true;
            staleFrags.add(box);
        }
        box.fragmentCursor = 0;
        box.x = 0;
        box.y = 0;
        box.staticX = Float.NaN;
        box.staticY = Float.NaN;
        box.scrollWidth = 0;
        box.scrollHeight = 0;
        box.descendantOverflowRight = 0;
        box.descendantOverflowBottom = 0;
        box.inlineContentBottom = Float.NEGATIVE_INFINITY;
        List<LayoutBox> kids = box.children;
        int n = kids.size();
        if (n == 0) return;
        boolean nextOffset = ancestorOffset || box.isRelative || box.isSticky;
        boolean nextBfcFloat = box.establishesBfc ? box.hasFloatChild : (inBfcFloat || box.hasFloatChild);
        for (int i = 0; i < n; i++) {
            resetPlacementsFast(kids.get(i), keepRun, nextOffset, nextBfcFloat, staleFrags);
        }
    }

    /** {@link #resetPlacementsFast} で遅延印を付けたうち、今回の layout が再構築しなかった断片だけを捨てる。 */
    public static void finishResetPlacements(List<LayoutBox> staleFrags) {
        for (int i = 0, n = staleFrags.size(); i < n; i++) {
            LayoutBox b = staleFrags.get(i);
            if (b.fragmentsStale) {
                b.discardFragments();
                b.fragmentsStale = false;
            }
        }
        staleFrags.clear();
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
            if (isTableBox(cur) || cur.everLaidOutAsTable) return true;
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
        return table || box.everLaidOutAsTable;
    }

    private static void collectReusableBoxes(LayoutBox box, ReusableTable out) {
        if (box.isAnonymous || box.node.isPseudoNode) return;
        boolean table = isTableBox(box);
        if (isInternalTableBox(box)) return;
        out.putIfAbsent(box.node, box);
        if (table || box.everLaidOutAsTable) return;
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
        if (table || box.everLaidOutAsTable) return;
        for (LayoutBox child : box.elementChildren) collectReusableBoxes(child, out);
    }

    /** 前回か今、表のエンジンの書き込みを受けた（受けうる）箱か。匿名の箱や差分レイアウトで使い回すかどうかの判断に使う。 */
    public boolean touchedByTableEngine() {
        return isTableBox(this) || everLaidOutAsTable || collapsedBorders != null;
    }

    /**
     * 使い回す箱から、前回の表のエンジンの書き込みを捨てて新しく作った箱と同じ状態に戻す。
     * 枠線の分担（{@code collapsedBorders}）は BoxMeasurer が measureContent より先に読むので必ず捨てる。
     * エンジンの状態・縦の揃えは、表だったことのある箱が今は表でないときだけ捨てる:
     * 表でなかった箱の {@code engineState}（Flex / Grid の測定結果）は、使い回す measure と layout が読むので消してはいけない。
     */
    public void resetEngineOutputs() {
        collapsedBorders = null;
        if (isTableBox(this) || !everLaidOutAsTable) return;
        engineState = null;
        contentOffsetY = 0;
    }

    /** 表か、表の中の箱（行・列・セル・行グループ・キャプション）か。 */
    public static boolean isTableBox(LayoutBox box) {
        return box.display.inner() == Display.Inner.TABLE || box.display.isTableInternal()
                || box.display.inner() == Display.Inner.TABLE_CAPTION;
    }

    /** 描画だけに効くプロパティ（色など）以外が変わったか。片方だけ null なら変わった。 */
    private static boolean affectsLayout(ComputedStyle before, ComputedStyle after) {
        return com.nishiyu.enderframelib.core.css.StyleResolver.isLayoutAffectingChange(before, after);
    }

    /** 測ったときから、子の並び以外の中身（署名）が変わったか。一度も測っていない箱は変わったとみなす。 */
    private boolean selfSignatureChanged() {
        if (measuredStyle == null) return true;
        if (style != measuredStyle && affectsLayout(measuredStyle, style)) return true;
        if (placeholderStyle != measuredPlaceholderStyle
                && affectsLayout(measuredPlaceholderStyle, placeholderStyle)) return true;
        if (!java.util.Objects.equals(displayText, measuredText)) return true;
        if (isOutsideMarker != measuredOutsideMarker || isRenderedLegend != measuredRenderedLegend) return true;
        return contentHash() != measuredContentHash;
    }

    /** 測ったときから、子の箱の並び（増減・入れ替え）が変わったか。 */
    private boolean childListChanged() {
        if (measuredChildren == null || measuredChildren.length != children.size()) return true;
        for (int i = 0; i < measuredChildren.length; i++) {
            if (measuredChildren[i] != children.get(i)) return true;
        }
        return false;
    }

    private boolean hasFloatingChild() {
        for (LayoutBox child : children) {
            if (child.style.facts().floating) return true;
        }
        return false;
    }

    /** 測ったときと、親の最初のフローの子であるかどうかが違うか（親との相殺に効く）。 */
    private boolean firstFlowStatusChanged() {
        return BlockLayoutEngine.isFirstFlowChild(this) != measuredFirstFlowChild;
    }

    /**
     * 箱の木を組み直した後、measure の前に呼ぶ。前回の measure から中身が変わった箱と、その祖先に印を付ける。
     * 中身の変わった箱の子にも印を付ける（子の measure は親の display・揃え・兄弟の並びを読むため）。
     *
     * @return この部分木のどこかが変わったか
     */
    public static boolean markLayoutChanges(LayoutBox root) {
        return markLayoutChanges(root, false);
    }

    /**
     * 親の署名が変わったときだけ、全ての子に印を付ける。子の並びだけが変わったとき（行の追加・削除）は、
     * 兄弟の並びを読む子（親の最初のフローの子かどうか）と、float が関わるとき（行が float を避けるため）だけ。
     * 他の子の measure は、制約（幅・パーセントの基準・親の整形コンテキスト）が同じなら前回と同じ。
     */
    private static boolean markLayoutChanges(LayoutBox box, boolean forcedByParent) {
        boolean selfChanged = box.selfSignatureChanged();
        boolean listChanged = box.childListChanged();
        // 子の並びの変化を兄弟に伝えなくてよいのは、子をブロックフローで並べる親だけ。flex / grid / 表は
        // 兄弟の数と大きさで子の寸法を決める（伸縮・stretch・列幅）ので、従来どおり全ての子を測り直す
        boolean blockFlow = box.display != null && !isTableBox(box)
                && (box.display.inner() == Display.Inner.FLOW || box.display.inner() == Display.Inner.FLOW_ROOT);
        boolean forceAllChildren = selfChanged
                || (listChanged && (!blockFlow || box.measuredHadFloatChild || box.hasFloatingChild()));
        boolean childAffects = false;
        boolean subtreeChanged = false;
        for (LayoutBox child : box.children) {
            boolean force = forceAllChildren || (listChanged && child.firstFlowStatusChanged());
            childAffects |= markLayoutChanges(child, force);
            subtreeChanged |= child.subtreeChanged;
        }
        boolean remeasure = selfChanged || listChanged || forcedByParent || childAffects || !box.measurementValid;
        box.layoutChanged = remeasure;
        box.subtreeChanged = remeasure || subtreeChanged;
        if (box.subtreeChanged) box.invalidateMinContentCache();
        // 再レイアウト境界の中の変化は、境界の寸法を変えないので祖先の測り直しには及ばない
        // （境界自身の署名が変わったときだけ祖先へ伝える）
        return box.isRelayoutBoundary() ? selfChanged : remeasure;
    }

    /**
     * 変わった箱が分かっているとき（文字の局所パッチ）の {@link #markLayoutChanges(LayoutBox)} の代わり。木全体をたどらず、
     * 変わった箱から根までの経路だけに印を付ける。印の付け方は全体版と同じ（境界から上へは測り直しを伝えない）。
     *
     * <p>経路の祖先の署名・子の並びが変わっていたり、変わった箱が子を持っていたり、根へたどれなかったりして
     * 経路だけでは決まらないときは null を返す。呼び出し側は {@link #markLayoutChanges(LayoutBox)} に退く
     * （付けた印は「測り直す」側にしか倒れないので、そのまま全体版で上書きしてよい）。</p>
     *
     * @return 印を付けた経路の箱（測り終えたら {@link #clearSubtreeMarks} に渡す）。null なら全体版へ退く
     */
    public static List<LayoutBox> markLayoutChangesAlong(LayoutBox root, java.util.Collection<LayoutBox> changed) {
        return markLayoutChangesAlong(root, changed, java.util.Set.of());
    }

    /**
     * @param shallow {@code changed} のうち、子の並びだけが変わった箱（子の箱自体は前回のまま）。子を持っていても、部分木を全体版で
     *                印付けせず、葉と同じ扱い（自分の測り直しだけ）にする。新しく入った子は未測定なので測られる
     */
    public static List<LayoutBox> markLayoutChangesAlong(LayoutBox root, java.util.Collection<LayoutBox> changed,
                                                         java.util.Set<LayoutBox> shallow) {
        List<LayoutBox> path = new java.util.ArrayList<>();
        for (LayoutBox leaf : changed) {
            if (leaf == null || leaf == root) return null;
            boolean selfChanged = leaf.selfSignatureChanged();
            boolean affects;
            if (leaf.children.isEmpty() || shallow.contains(leaf)) {
                boolean remeasure = selfChanged || leaf.childListChanged() || !leaf.measurementValid;
                leaf.layoutChanged |= remeasure;
                leaf.subtreeChanged = true;
                leaf.invalidateMinContentCache();
                affects = leaf.isRelayoutBoundary() ? selfChanged : remeasure;
            } else {
                // 子を持つ箱（再スタイルで寸法の入力が変わった要素）: 自分の署名が変わったなら全ての子孫を測り直す。
                // この部分木だけは全体版と同じ印付けをする（部分木の外は経路だけ）
                affects = markLayoutChanges(leaf, false);
                leaf.subtreeChanged = true;
                leaf.invalidateMinContentCache();
            }
            path.add(leaf);
            boolean reachedRoot = false;
            // lazy: 直前の箱が、測り直した外寸が変わらなければ親が吸収できる箱（cutoff）で、結果が出るまで親に印を付けない
            boolean lazy = affects && leaf.isCutoffEligible();
            for (LayoutBox a = leaf.parent; a != null; a = a.parent) {
                if (a.selfSignatureChanged() || a.childListChanged()) return null;
                a.subtreeChanged = true;
                a.invalidateMinContentCache();
                path.add(a);
                if (lazy) {
                    // 子の結果が外寸に出れば a の測定が無効になり、a は下降（remeasureChanged）の戻りで測り直す。
                    // a の親がそれを同じように受けられる（a も cutoff）間は、印を付けずに結果を待つ。
                    // 受けられなければ、a の親を測り直しの対象にして、a はその下で測られる
                    if (a.isRelayoutBoundary()) {
                        affects = false;
                        lazy = false;
                    } else if (!a.isCutoffEligible() && !lazyThroughIneligible) {
                        affects = true;
                        lazy = false;
                    }
                } else {
                    a.layoutChanged |= affects;
                    if (a.isRelayoutBoundary()) affects = false;
                    else if (affects && a.isCutoffEligible()) lazy = true;
                }
                if (a == root) { reachedRoot = true; break; }
            }
            if (!reachedRoot) return null;
        }
        return path;
    }

    /**
     * {@code relayoutRootOnly} 専用: 根以外のスタイル・構造・属性が変わっていない前提で、
     * 根と直接の子だけを印付けし、残りのクリーンな部分木走査を省く。
     */
    public static boolean markRootRestyleChanges(LayoutBox rootBox) {
        boolean selfChanged = rootBox.selfSignatureChanged();
        boolean listChanged = rootBox.childListChanged();
        boolean blockFlow = rootBox.display != null && !isTableBox(rootBox)
                && (rootBox.display.inner() == Display.Inner.FLOW || rootBox.display.inner() == Display.Inner.FLOW_ROOT);
        boolean forceAllChildren = selfChanged
                || (listChanged && (!blockFlow || rootBox.measuredHadFloatChild || rootBox.hasFloatingChild()));
        boolean childAffects = false;
        boolean subtreeChanged = false;
        List<LayoutBox> kids = rootBox.children;
        for (int i = 0, n = kids.size(); i < n; i++) {
            LayoutBox child = kids.get(i);
            boolean force = forceAllChildren || (listChanged && child.firstFlowStatusChanged());
            if (child.isAnonymous || child.subtreeChanged || child.selfSignatureChanged() || child.childListChanged()) {
                childAffects |= markLayoutChanges(child, force);
            } else {
                boolean cRemeasure = force || !child.measurementValid;
                child.layoutChanged = cRemeasure;
                child.subtreeChanged = cRemeasure;
                if (cRemeasure) child.invalidateMinContentCache();
                childAffects |= !child.isRelayoutBoundary() && cRemeasure;
            }
            subtreeChanged |= child.subtreeChanged;
        }
        boolean remeasure = selfChanged || listChanged || childAffects || !rootBox.measurementValid;
        rootBox.layoutChanged = remeasure;
        rootBox.subtreeChanged = remeasure || subtreeChanged;
        if (rootBox.subtreeChanged) rootBox.invalidateMinContentCache();
        return remeasure;
    }

    /** 配置まで終えたあと、木全体の「変わった」印を外す（測り直した印の残っていない箱だけ。{@link #clearSubtreeMarks} の全体版）。 */
    public static void clearAllSubtreeMarks(LayoutBox root) {
        if (!root.subtreeChanged) return;
        if (!root.layoutChanged) root.subtreeChanged = false;
        List<LayoutBox> kids = root.children;
        for (int i = 0, n = kids.size(); i < n; i++) clearAllSubtreeMarks(kids.get(i));
    }

    /** {@link #markLayoutChangesAlong} が付けた印を、測り終えた箱から外す（次の全体版が「変化なし」と付けるのと同じ）。 */
    public static void clearSubtreeMarks(List<LayoutBox> path) {
        for (LayoutBox b : path) {
            if (!b.layoutChanged) b.subtreeChanged = false;
        }
    }

    /** 前回の measure の後に、自分か子孫の何かが変わった（境界を越えて伝わる。配置の使い回しの判定に使う）。 */
    private boolean subtreeChanged = true;
    /** 前回の layout の後に、子孫の再測定が起きていない（境界を越えて祖先まで切れる）。 */
    private boolean placementValid = false;

    /**
     * 自分の寸法が子孫に依らず、中身の変化が祖先の layout の入力にならない箱（再レイアウト境界）。
     * 中身の大きさで決まらない寸法（size containment・確定した幅と高さのスクロールコンテナ）を持ち、
     * 独立した整形コンテキストを作る（float や margin が外へ及ばない）ブロックレベルの箱。
     */
    boolean isRelayoutBoundary() {
        if (parent == null || display == null || display.isInlineLevel() || isTableBox(this) || !establishesBfc) return false;
        if (containsInlineSize() && containsBlockSize()) return true;
        if (!isScrollContainer()) return false;
        StyleFacts facts = style.facts();
        return facts.definiteWidth && facts.definiteHeight;
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
        if (style.facts().displayNone) return;
        settleTentative();
        if (hasTentativeChildren) settleTentativeChildren();
        if (needsAdoption) {
            for (int i = 0, n = children.size(); i < n; i++) adopt(children.get(i));
            needsAdoption = false;
        }
        // 親の外寸（パーセントの基準）は、位置指定・スクロールコンテナ・float を含まない部分木（placementPure）の配置には効かない。
        // 中身が増えて親の高さが変わっても、子の配置の入力は変わらない
        // スクロールコンテナは、自分のスクロール量も配置の入力になる（子の位置が -scroll だけずれる）。量が前回と同じなら入力は同じ
        boolean sameScroll = !isScrollContainer() || ctx.scrollState().getScrollX(node) == lastScrollX
                && ctx.scrollState().getScrollY(node) == lastScrollY;
        boolean sameMargins = lastLayoutML == mL && lastLayoutMR == mR && lastLayoutMT == mT && lastLayoutMB == mB;
        boolean sameInputs = lastLayoutX == parentX && lastLayoutY == parentY && sameScroll && sameMargins
                && (keptPure || (lastLayoutOuterW == parentOuterW && lastLayoutOuterH == parentOuterH));
        if (ctx.reuseMeasurements() && sameInputs && canKeepPlacement(ctx.layoutRun())) {
            LAYOUTS_SKIPPED.increment();
            return;
        }
        // 原点だけが違い、部分木の配置が前回と同じ（原点からの相対位置が変わらない）なら、置き直さず部分木ごと平行移動する
        // （行の挿入・削除で後続の行が動くだけのとき、各行の内側を組み直さない）
        if (!sameInputs && sameScroll && sameMargins && SHIFT_ENABLED && ctx.reuseMeasurements() && canKeepPlacement(ctx.layoutRun())
                && !Float.isNaN(lastLayoutX) && !Float.isNaN(lastLayoutY)
                && isShiftableDelta(parentX, lastLayoutX) && isShiftableDelta(parentY, lastLayoutY)
                && isShiftableSubtree(parentX != lastLayoutX, parentY != lastLayoutY)) {
            shiftSubtree(parentX - lastLayoutX, parentY - lastLayoutY);
            lastLayoutX = parentX;
            lastLayoutY = parentY;
            LAYOUTS_SHIFTED.increment();
            return;
        }
        // 原点か包含ブロックの寸法が変わったので、前回の配置を引き継いだ子孫の位置はどれも古い。先に捨てる
        // （自分の staticX / staticY は親が layout の前に置くので消さない）。入力が同じで、子孫の変化のために
        // 置き直すだけなら、子はそれぞれの入力を見て飛ばすか置き直すかを決める
        if (keepPlacements && keptPure && !sameInputs) {
            for (int i = 0, n = children.size(); i < n; i++) resetPlacements(children.get(i), -1);
        }

        if (BlockLayoutEngine.containsFloats(this)) {
            hasFloatChild = false;
            for (int i = 0, n = children.size(); i < n; i++) {
                if (children.get(i).style.facts().floating) {
                    hasFloatChild = true;
                    break;
                }
            }
        } else {
            hasFloatChild = false;
        }
        touchPaint();
        float rootW = ctx.viewport().rootW();
        float rootH = ctx.viewport().rootH();

        if (this.isAbsolute) {
            ContainingBlock cb = resolveContainingBlock(ctx);
            float refX = cb.x();
            float refY = cb.y();
            float refW = cb.w();
            float refH = cb.h();

            // 両方あれば left / top が勝つ（過剰制約のときは end 側を無視する。CSS 2.2 §10.3.7）。
            // 両方 auto の軸は静的位置に置く。以前は right / bottom が勝ち、両方 auto なら
            // 包含ブロックの原点に置いていた
            if (!BoxMeasurer.isAutoInset(style, CssPropertyIndex.LEFT)) {
                this.x = refX + style.length(CssPropertyIndex.LEFT, refW, rootW, rootH) + mL;
            } else if (!BoxMeasurer.isAutoInset(style, CssPropertyIndex.RIGHT)) {
                this.x = refX + refW - this.w - style.length(CssPropertyIndex.RIGHT, refW, rootW, rootH) - mR;
            } else {
                this.x = (Float.isNaN(staticX) ? refX : staticX) + mL;
            }
            if (!BoxMeasurer.isAutoInset(style, CssPropertyIndex.TOP)) {
                this.y = refY + style.length(CssPropertyIndex.TOP, refH, rootW, rootH) + mT;
            } else if (!BoxMeasurer.isAutoInset(style, CssPropertyIndex.BOTTOM)) {
                this.y = refY + refH - this.h - style.length(CssPropertyIndex.BOTTOM, refH, rootW, rootH) - mB;
            } else {
                this.y = (Float.isNaN(staticY) ? refY : staticY) + mT;
            }
        } else {
            float refX = this.isFixed ? 0 : parentX;
            float refY = this.isFixed ? 0 : parentY;
            this.x = refX + mL;
            this.y = refY + mT;
        }

        float scrollX = ctx.scrollState().getScrollX(node);
        float scrollY = ctx.scrollState().getScrollY(node);

        float innerX = this.x + pL + bL - scrollX;
        float innerY = this.y + pT + bT - scrollY + contentOffsetY + legendSpill;

        // 静的位置の既定は内容ボックスの原点。ブロックフローは通常フローの位置で上書きする
        if (hasAbsoluteChild) {
            for (int i = 0, n = children.size(); i < n; i++) {
                LayoutBox child = children.get(i);
                if (child.isAbsolute) {
                    child.staticX = innerX;
                    child.staticY = innerY;
                }
            }
        }

        LayoutEngine engine = LayoutEngine.getEngine(display);
        engine.arrangeChildren(this, innerX, innerY, ctx);

        if (hasAbsoluteChild) {
            for (int i = 0, n = children.size(); i < n; i++) {
                LayoutBox child = children.get(i);
                if (child.style.facts().displayNone) continue;
                if (child.isAbsolute) child.layout(this.x - scrollX, this.y - scrollY, this.w, this.h, ctx);
            }
        }
        LayoutBox legend = renderedLegend();
        if (legend != null) placeRenderedLegend(legend, ctx);

        applyRelativePositioning(parentOuterW, parentOuterH, rootW, rootH);
        if (this.isSticky) applyStickyOffset(ctx);
        updateScrollExtent(scrollX, scrollY);
        recordPlacement(parentX, parentY, parentOuterW, parentOuterH, legend != null, scrollX, scrollY);
    }

    // ==========================================
    // 差分レイアウト（layout の結果の再利用）
    // ==========================================
    //
    // measure を使い回した箱は、寸法も子の並びも前回の layout のときと同じ。配置（layout）の入力は
    // 親から渡る原点と包含ブロックの寸法だけなので、これも前回と同じなら、部分木の配置は前回の結果のまま使える。
    // 箱の位置が祖先の外の何か（絶対配置の包含ブロック・float・スクロール量・sticky のポート）に依るときは使い回せない:
    // そうした箱を部分木に含む箱は placementPure ではない。

    private static final java.util.concurrent.atomic.LongAdder LAYOUTS_SKIPPED =
            new java.util.concurrent.atomic.LongAdder();

    private static final java.util.concurrent.atomic.LongAdder LAYOUTS_SHIFTED =
            new java.util.concurrent.atomic.LongAdder();

    /** 切り分け用: false なら、原点だけが違う部分木も置き直す（平行移動で済ませない）。 */
    public static volatile boolean SHIFT_ENABLED = !Boolean.getBoolean("enderframe.layout.noshift");

    /** 原点だけが違うので部分木ごと平行移動した layout の回数（診断・テスト用）。 */
    public static long layoutsShifted() {
        return LAYOUTS_SHIFTED.sum();
    }

    /**
     * 部分木が、位置を原点からの相対だけで持つレイアウトエンジン（通常フロー・flex・grid。段組・表を持たない）か。
     * 平行移動で済ませる前に、移動してはならない状態（絶対位置を持つエンジンの状態）が無いことを確かめる。
     */
    private boolean isShiftableSubtree(boolean needGridX, boolean needGridY) {
        if ((needGridX || needGridY) && !isGridAligned(needGridX, needGridY)) return false;
        if (columnShifts != null || collapsedBorders != null || hasLegendBox()) return false;
        if (engineState != null && !(engineState instanceof BlockLayoutEngine.FlowState
                || engineState instanceof FlexLayoutEngine.FlexState
                || engineState instanceof GridLayoutEngine.GridState)) return false;
        if (display != null && display.inner() != Display.Inner.FLOW && display.inner() != Display.Inner.FLOW_ROOT
                && display.inner() != Display.Inner.FLEX && display.inner() != Display.Inner.GRID) return false;
        for (LayoutBox child : children) {
            if (child.style.facts().displayNone) continue;
            if (!child.isShiftableSubtree(needGridX, needGridY)) return false;
        }
        return true;
    }

    /** 整数ぶんだけの移動か（丸めの境目に当たらないので、置き直した結果と同じ丸めになる）。 */
    private static boolean isIntegral(float v) {
        return v == Math.rint(v);
    }

    /**
     * 移動量が、置き直しとビット単位で一致する平行移動になるか。整数ぶんなら従来どおり（丸めの境目に当たらない）。
     * 小数ぶんは、移動量と両方の原点が {@link LayoutUnit} の格子に載っているときに限る
     * （その場合に限り float の加算が厳密。部分木の側は {@link #isGridAligned} で確かめる）。
     */
    private static boolean isShiftableDelta(float parentCoord, float lastCoord) {
        float d = parentCoord - lastCoord;
        if (isIntegral(d)) return true;
        float limit = LayoutUnit.EXACT_LIMIT - LayoutUnit.SHIFT_MARGIN;
        return Math.abs(parentCoord) < limit && Math.abs(lastCoord) < limit
                && LayoutUnit.isOnGrid(parentCoord) && LayoutUnit.isOnGrid(lastCoord) && LayoutUnit.isOnGrid(d);
    }

    /** この箱が平行移動で動かす値（必要な軸の座標・静的位置・行の下端・断片・前回の配置）がすべて格子に載っているか。 */
    private boolean isGridAligned(boolean checkX, boolean checkY) {
        boolean isText = node != null && node.nodeType == NodeType.TEXT;
        if (checkX) {
            if (!isText && !LayoutUnit.isOnGrid(x)) return false;
            if (!Float.isNaN(staticX) && !LayoutUnit.isOnGrid(staticX)) return false;
            for (int i = 0, n = inlineFragments.size(); i < n; i++) {
                if (!LayoutUnit.isOnGrid(inlineFragments.get(i).x)) return false;
            }
            if (!Float.isNaN(lastLayoutX) && !LayoutUnit.isOnGrid(lastLayoutX)) return false;
        }
        if (checkY) {
            if (!isText && !LayoutUnit.isOnGrid(y)) return false;
            if (inlineContentBottom > Float.NEGATIVE_INFINITY && !LayoutUnit.isOnGrid(inlineContentBottom)) return false;
            if (!Float.isNaN(staticY) && !LayoutUnit.isOnGrid(staticY)) return false;
            for (int i = 0, n = inlineFragments.size(); i < n; i++) {
                if (!LayoutUnit.isOnGrid(inlineFragments.get(i).y)) return false;
            }
            if (!Float.isNaN(lastLayoutY) && !LayoutUnit.isOnGrid(lastLayoutY)) return false;
        }
        return true;
    }

    private boolean hasLegendBox() {
        return renderedLegend() != null;
    }

    /**
     * layout を通らずに置かれるインライン要素として {@link BlockLayoutEngine} の {@code fitToFragments} が
     * 再帰・配置できる箱か。途中で非インライン（display:flex 等）を挟むと {@code fitToFragments} が打ち切られ、
     * その箱も子孫のインライン要素も置き直しで (0, 0) のまま残る。
     */
    private boolean canRecurseFitToFragments() {
        if (node == null || node.nodeType == NodeType.TEXT || "br".equals(node.tag)) return false;
        if (isAbsolute || isFixed || style.facts().displayNone || style.facts().floating) return false;
        if (display == null || !display.isInlineLevel()) return false;
        return !BlockLayoutEngine.isAtomicInline(this);
    }

    private boolean hasNonTextFragment() {
        for (int i = 0, n = inlineFragments.size(); i < n; i++) {
            if (!inlineFragments.get(i).isText()) return true;
        }
        return false;
    }

    /** 部分木の位置（箱・断片・行の下端・前回の配置の記録）を {@code (dx, dy)} だけ動かす。描画の変更印も進める。 */
    private void shiftSubtree(float dx, float dy) {
        shiftSubtree(dx, dy, true);
    }

    private void shiftSubtree(float dx, float dy, boolean fitReachable) {
        // layout を通った箱（lastLayoutX が NaN でない）は常に動く。
        // layout を通らない箱は fitToFragments が到達し、かつ文字以外の断片を 1 つ以上持つインライン要素だけが動く。
        boolean laidOut = !Float.isNaN(lastLayoutX);
        boolean canFit = !laidOut && fitReachable && canRecurseFitToFragments();
        if (laidOut || (canFit && hasNonTextFragment())) {
            this.x += dx;
            this.y += dy;
        }
        boolean nextFitReachable = laidOut || canFit;
        if (inlineContentBottom > Float.NEGATIVE_INFINITY) inlineContentBottom += dy;
        if (!Float.isNaN(staticX)) staticX += dx;
        if (!Float.isNaN(staticY)) staticY += dy;
        for (InlineFragment frag : inlineFragments) {
            frag.x += dx;
            frag.y += dy;
        }
        touchPaint();
        for (LayoutBox child : children) {
            if (child.style.facts().displayNone) continue;
            if (!Float.isNaN(child.lastLayoutX)) {
                child.lastLayoutX += dx;
                child.lastLayoutY += dy;
            }
            child.shiftSubtree(dx, dy, nextFitReachable);
        }
    }

    /** これまでに使い回して飛ばした layout の回数（差分レイアウトの効き具合を測るテスト・診断用）。 */
    public static long layoutsSkipped() {
        return LAYOUTS_SKIPPED.sum();
    }

    /** layout の結果の再利用を使うか。false なら毎回置き直す（再利用を疑うときの切り分け用）。 */
    public static volatile boolean keepPlacements = true;

    /** 前回の layout の配置を使い回せる状態か（再利用が有効で、前回の部分木が外の状態に依らなかった）。 */
    public boolean holdsPlacement() {
        return keepPlacements && placementPure;
    }

    public String debugInfo() {
        return node.tag + "#" + node.key() + " disp=" + display + " pure=" + placementPure + " lastX=" + lastLayoutX + " lastY=" + lastLayoutY + " x=" + x + " y=" + y + " valid=" + measurementValid + " runM=" + measuredRun + " anon=" + isAnonymous;
    }

    /** 部分木の配置が、この箱の外の状態に依らず親の渡す原点だけで決まる（前回の layout で確かめた）。 */
    private boolean placementPure = false;
    /**
     * 自分の配置を使い回せる（{@link #placementPure} から、自分のスクロールコンテナであることだけを除いた条件）。祖先は
     * 子孫のスクロール量の変化を見ないので、祖先から見た純粋さ（{@link #placementPure}）はスクロールコンテナで切れるが、
     * そのコンテナ自身は、スクロール量が前回と同じなら配置を飛ばせる。
     */
    private boolean keptPure = false;
    private float lastScrollX = Float.NaN, lastScrollY = Float.NaN;
    private float lastLayoutX = Float.NaN, lastLayoutY = Float.NaN, lastLayoutOuterW = Float.NaN, lastLayoutOuterH = Float.NaN;
    private float lastLayoutML = Float.NaN, lastLayoutMR = Float.NaN, lastLayoutMT = Float.NaN, lastLayoutMB = Float.NaN;

    private void recordPlacement(float parentX, float parentY, float parentOuterW, float parentOuterH, boolean hasLegend,
                                 float scrollXAtPlacement, float scrollYAtPlacement) {
        lastLayoutX = parentX;
        lastLayoutY = parentY;
        lastLayoutOuterW = parentOuterW;
        lastLayoutOuterH = parentOuterH;
        lastLayoutML = mL;
        lastLayoutMR = mR;
        lastLayoutMT = mT;
        lastLayoutMB = mB;
        placementPure = !hasLegend && subtreeIsSelfContained(true);
        keptPure = placementPure || !hasLegend && isScrollContainer() && subtreeIsSelfContained(false);
        if (isScrollContainer()) {
            lastScrollX = scrollXAtPlacement;
            lastScrollY = scrollYAtPlacement;
        }
        placementValid = true;
    }

    /**
     * この箱とその子孫が、外の状態（絶対配置の包含ブロック・float・スクロール量・sticky のポート・表の分担）に
     * 位置を依存しないか。layout を通らない子（行に流れるテキストやインラインの箱）は構造だけで見る。
     */
    private boolean subtreeIsSelfContained(boolean selfScrollBreaks) {
        if (isAbsolute || isFixed || isRelative || isSticky || selfScrollBreaks && isScrollContainer() || floatSpace != null
                || isTableBox(this) || collapsedBorders != null
                || style.facts().floating) {
            return false;
        }
        for (LayoutBox child : children) {
            if (child.style.facts().displayNone) continue;
            boolean laidOutByLayout = !Float.isNaN(child.lastLayoutX);
            if (laidOutByLayout ? !child.placementPure : !child.subtreeIsSelfContained(true)) return false;
        }
        return true;
    }

    /**
     * 前回の layout の配置をそのまま使えるか（原点が同じかは呼び出し側が確かめる）。この run で測り直されておらず
     * （測り直した箱と、その祖先の測定は無効になる）、親がブロックコンテナで、前回の配置が純粋だったときだけ。
     */
    private boolean canKeepPlacement(long run) {
        return canKeepPlacement(run, floatsAffectPlacement());
    }

    private boolean canKeepPlacement(long run, boolean floatsOrOffset) {
        return keepPlacements && keptPure && placementValid && measurementValid && measuredRun != run
                && !layoutChanged && !subtreeChanged
                && parent != null && parent.display != null
                && (parent.display.inner() == Display.Inner.FLOW || parent.display.inner() == Display.Inner.FLOW_ROOT
                        || parent.display.inner() == Display.Inner.FLEX)
                && !display.isInlineLevel()
                && !floatsOrOffset;
    }

    /** 自分の子に float があるか。{@link #layout} の始めに決め直す。 */
    private boolean hasFloatChild = false;

    /**
     * 同じ BFC の中に float があると、後に続く箱の行は float を避けて置かれる。float の位置は layout の途中の状態で、
     * 原点や包含ブロックの寸法には現れないので、祖先（BFC の根まで）に float を子に持つ箱があれば使い回さない。
     */
    private boolean floatsAffectPlacement() {
        boolean inBfc = true;
        for (LayoutBox p = parent; p != null; p = p.parent) {
            // 相対配置・sticky の祖先は、子孫を置いた後でまとめてずらす。ずれは子孫の位置に残るので、
            // ずれが変わった（なくなった）ときに古いずれを引き継がない
            if (p.isRelative || p.isSticky) return true;
            if (inBfc && p.hasFloatChild) return true;
            if (p.establishesBfc) inBfc = false;
        }
        return false;
    }

    /**
     * fieldset の legend を上の枠線に重ねて置く（HTML §15.3.13）。
     *
     * <ul>
     *   <li>縦: border box を上の枠線の中央に置く。legend の方が高ければ fieldset の上端に揃え、
     *       枠線は描画側が legend の中央へ下げる。上マージンは数えない（Chrome の実測）</li>
     *   <li>横: 内容ボックスの左端から。{@code margin-left / margin-right: auto} で中央・右へ寄せる</li>
     * </ul>
     * スクロールしても動かない（スクロールするのは内容だけ）。
     */
    private void placeRenderedLegend(LayoutBox legend, LayoutContext ctx) {
        float contentW = Math.max(0, w - horizontalDecoration());
        boolean autoLeft = "auto".equals(legend.style.get(CssPropertyIndex.MARGIN_LEFT));
        boolean autoRight = "auto".equals(legend.style.get(CssPropertyIndex.MARGIN_RIGHT));
        float free = contentW - legend.w - (autoLeft ? 0 : legend.mL) - (autoRight ? 0 : legend.mR);
        if (autoLeft && autoRight) {
            legend.mL = Math.max(0, free / 2);
        } else if (autoLeft) {
            legend.mL = Math.max(0, free);
        }
        float top = this.y + Math.max(0, (bT - legend.h) / 2);
        legend.layout(this.x + bL + pL, top - legend.mT, contentW, Math.max(0, h - verticalDecoration()), ctx);
    }

    /** auto を 0 とした左右のマージン（px）。 */
    float ownInlineMargins(LayoutContext ctx) {
        float rootW = ctx.viewport().rootW(), rootH = ctx.viewport().rootH();
        float l = "auto".equals(style.get(CssPropertyIndex.MARGIN_LEFT)) ? 0 : style.length(CssPropertyIndex.MARGIN_LEFT, 0, rootW, rootH);
        float r = "auto".equals(style.get(CssPropertyIndex.MARGIN_RIGHT)) ? 0 : style.length(CssPropertyIndex.MARGIN_RIGHT, 0, rootW, rootH);
        return l + r;
    }

    /**
     * {@code position: sticky} のずれ。通常フローの位置に置いたあと、最も近いスクロールコンテナの
     * スクロールポート（無ければビューポート）の端から inset だけ内側に留まるようずらし、
     * 親の内容ボックスからははみ出させない（CSS Positioned Layout 3 §3.4）。
     * relative と同じく描画位置だけを動かし、後続の配置には影響しない。
     */
    private void applyStickyOffset(LayoutContext ctx) {
        float rootW = ctx.viewport().rootW();
        float rootH = ctx.viewport().rootH();
        float portX = 0, portY = 0, portW = rootW, portH = rootH;
        for (LayoutBox cur = parent; cur != null; cur = cur.parent) {
            if (cur.isScrollContainer()) {
                portX = cur.x + cur.bL;
                portY = cur.y + cur.bT;
                portW = Math.max(0, cur.w - cur.bL - cur.bR);
                portH = Math.max(0, cur.h - cur.bT - cur.bB);
                break;
            }
        }
        if (parent == null) return;
        float cbTop = parent.y + parent.bT + parent.pT;
        float cbBottom = parent.y + parent.h - parent.bB - parent.pB;
        float cbLeft = parent.x + parent.bL + parent.pL;
        float cbRight = parent.x + parent.w - parent.bR - parent.pR;

        float dx = 0, dy = 0;
        if (!BoxMeasurer.isAutoInset(style, CssPropertyIndex.TOP)) {
            float limit = portY + style.length(CssPropertyIndex.TOP, portH, rootW, rootH);
            if (y < limit) dy = Math.max(0, Math.min(limit - y, cbBottom - (y + h + mB)));
        } else if (!BoxMeasurer.isAutoInset(style, CssPropertyIndex.BOTTOM)) {
            float limit = portY + portH - style.length(CssPropertyIndex.BOTTOM, portH, rootW, rootH);
            if (y + h > limit) dy = -Math.max(0, Math.min(y + h - limit, y - mT - cbTop));
        }
        if (!BoxMeasurer.isAutoInset(style, CssPropertyIndex.LEFT)) {
            float limit = portX + style.length(CssPropertyIndex.LEFT, portW, rootW, rootH);
            if (x < limit) dx = Math.max(0, Math.min(limit - x, cbRight - (x + w + mR)));
        } else if (!BoxMeasurer.isAutoInset(style, CssPropertyIndex.RIGHT)) {
            float limit = portX + portW - style.length(CssPropertyIndex.RIGHT, portW, rootW, rootH);
            if (x + w > limit) dx = -Math.max(0, Math.min(x + w - limit, x - mL - cbLeft));
        }
        if (dx != 0 || dy != 0) applyRelativeOffset(dx, dy);
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

    /** {@code overflow} が {@code visible} / {@code clip} 以外ならスクロールコンテナ。 */
    public boolean isScrollContainer() {
        return style.facts().scrollContainer;
    }

    private void applyRelativePositioning(float parentOuterW, float parentOuterH, float rootW, float rootH) {
        if (!this.isRelative) return;

        float offX = 0, offY = 0;
        if (style.containsKey(CssPropertyIndex.LEFT)) offX += style.length(CssPropertyIndex.LEFT, parentOuterW, rootW, rootH);
        else if (style.containsKey(CssPropertyIndex.RIGHT)) offX -= style.length(CssPropertyIndex.RIGHT, parentOuterW, rootW, rootH);
        if (style.containsKey(CssPropertyIndex.TOP)) offY += style.length(CssPropertyIndex.TOP, parentOuterH, rootW, rootH);
        else if (style.containsKey(CssPropertyIndex.BOTTOM)) offY -= style.length(CssPropertyIndex.BOTTOM, parentOuterH, rootW, rootH);

        if (offX != 0 || offY != 0) applyRelativeOffset(offX, offY);
    }

    /**
     * スクロール範囲を更新する。
     * エンジン固有の子（グリッドアイテムなど）は {@link LayoutEngineState} が自分で足し込む。
     */
    private void updateScrollExtent(float scrollX, float scrollY) {
        if (children.isEmpty() && engineState == null && inlineContentBottom <= Float.NEGATIVE_INFINITY) {
            this.descendantOverflowRight = this.w;
            this.descendantOverflowBottom = this.h;
            if (isCollapsedTable()) {
                this.scrollWidth = pL + bL;
                this.scrollHeight = pT + bT;
            } else {
                this.scrollWidth = Math.max(this.w, pL + bL + pR + bR);
                this.scrollHeight = Math.max(this.h, pT + bT + pB + bB);
            }
            return;
        }

        float extRight = this.x + pL + bL;
        float extBottom = this.y + pT + bT;
        float descRight = this.x + this.w;
        float descBottom = this.y + this.h;

        boolean marginEscapes = !establishesBfc && pB == 0 && bB == 0 && !hasDefiniteHeight();
        LayoutBox lastInFlow = null;
        if (marginEscapes) {
            for (int i = children.size() - 1; i >= 0; i--) {
                LayoutBox child = children.get(i);
                if (!child.style.facts().displayNone && !child.isAbsolute
                        && !child.display.isInlineLevel()) {
                    lastInFlow = child;
                    break;
                }
            }
        }
        for (int i = 0, n = children.size(); i < n; i++) {
            LayoutBox child = children.get(i);
            if (child.style.facts().displayNone || child.isAbsolute) continue;
            if (child.node != null && child.node.nodeType == NodeType.TEXT) {
                // 文字の塊の箱は位置を持たない（x / y は 0 のまま。置かれるのは断片）。箱の x / y / h を読むと、相対配置・sticky で
                // 祖先が動いたとき（その分だけ箱も平行移動される）スクロール範囲にそのずれが入り、sticky ではスクロールするほど
                // 範囲が伸び続ける。断片の端を取り込む
                List<InlineFragment> frags = child.inlineFragments;
                for (int j = 0, m = frags.size(); j < m; j++) {
                    InlineFragment frag = frags.get(j);
                    float right = frag.x + frag.w + scrollX;
                    float bottom = Math.max(frag.y + frag.h, frag.y + frag.baseline + frag.descent) + scrollY;
                    if (right > extRight) extRight = right;
                    if (bottom > extBottom) extBottom = bottom;
                    if (right > descRight) descRight = right;
                    if (bottom > descBottom) descBottom = bottom;
                }
                continue;
            }
            if (child == lastInFlow) {
                float r = child.x + child.w + child.mR + scrollX;
                float b = child.y + child.h + scrollY;
                if (r > extRight) extRight = r;
                if (b > extBottom) extBottom = b;
            } else {
                float r = child.x + child.w + child.mR + scrollX;
                float b = child.y + child.h + child.mB + scrollY;
                if (r > extRight) extRight = r;
                if (b > extBottom) extBottom = b;
            }
            float cr = child.x + child.w + scrollX;
            float cb = child.y + child.h + scrollY;
            if (cr > descRight) descRight = cr;
            if (cb > descBottom) descBottom = cb;
            if (!child.clipsOverflow()) {
                float ovR = child.x + child.descendantOverflowRight + scrollX;
                float ovB = child.y + child.descendantOverflowBottom + scrollY;
                if (ovR > descRight) descRight = ovR;
                if (ovB > descBottom) descBottom = ovB;
            }
        }
        if (engineState != null) {
            ScrollExtent extent = new ScrollExtent(extRight, extBottom);
            engineState.contributeScrollExtent(extent, scrollX, scrollY);
            extRight = extent.right();
            extBottom = extent.bottom();
        }
        if (inlineContentBottom > Float.NEGATIVE_INFINITY) {
            float b = inlineContentBottom + scrollY;
            if (b > extBottom) extBottom = b;
        }

        this.descendantOverflowRight = descRight - this.x;
        this.descendantOverflowBottom = descBottom - this.y;
        if (isCollapsedTable()) {
            // 枠線を重ねる表のスクロール範囲は、セルの端まで（表の外周の枠線の半分は含めず、表の箱の大きさで下限も切らない）
            this.scrollWidth = extRight - this.x;
            this.scrollHeight = extBottom - this.y;
            return;
        }
        this.scrollWidth = Math.max(Math.max(this.w, extRight - this.x + pR + bR), descendantOverflowRight);
        this.scrollHeight = Math.max(Math.max(this.h, extBottom - this.y + pB + bB), descendantOverflowBottom);
    }

    /**
     * {@code border-collapse: collapse} の表か。この表の外周の枠線は、表の箱の中に半分だけあるが
     * {@code clientLeft} などには数えない（ブラウザと同じ。{@code clientWidth} は箱の幅のまま）。
     * {@code overflow} でクリップする表は普通の箱と同じに扱う。
     */
    public boolean isCollapsedTable() {
        return display != null && display.inner() == Display.Inner.TABLE && style != null && !clipsOverflow()
                && style.keywordOf(CssPropertyIndex.BORDER_COLLAPSE, CssKeywords.BorderCollapse.class) == CssKeywords.BorderCollapse.COLLAPSE;
    }

    private boolean hasDefiniteHeight() {
        String height = style.get(CssPropertyIndex.HEIGHT);
        if (height != null && !height.isBlank() && !"auto".equalsIgnoreCase(height.trim())) return true;
        String minHeight = style.get(CssPropertyIndex.MIN_HEIGHT);
        return minHeight != null && !minHeight.isBlank() && !"0".equals(minHeight.trim()) && !"0px".equals(minHeight.trim())
                && !"auto".equalsIgnoreCase(minHeight.trim());
    }

    /** はみ出しを切る・スクロールさせる（{@code overflow} が {@code visible} 以外）か。切られた中身は親のはみ出しにならない。 */
    private boolean clipsOverflow() {
        return style.overflowX().clips() || style.overflowY().clips();
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

        /** {@code display} の宣言が無いときにインラインとして扱うタグ（従来の推測と同じ一覧）。 */
        private static final Set<String> INLINE_TAGS = Set.of(
                "br", "span", "a", "b", "i", "strong", "em", "img", "canvas", "svg", "input", "button", "select", "progress");

        /** 箱の display。テキストは常にインライン、宣言が無い・解釈できない値はタグから推測する。 */
        public static Display of(HtmlNode node, ComputedStyle style) {
            if (node.nodeType == NodeType.TEXT) return INLINE;
            Display parsed = parse(style.get(CssPropertyIndex.DISPLAY));
            if (parsed != null) return parsed;
            return INLINE_TAGS.contains(node.tag) || ReplacedElements.isHost(node.tag) ? INLINE : BLOCK;
        }

        /** 1 キーワードの旧構文と、{@code block flow-root} / {@code inline flex} などの複数キーワード構文を読む。 */
        public static Display parse(String value) {
            if (value == null) return null;
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