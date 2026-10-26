package com.nishiyu.enderframelib.core.layout;

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
import com.nishiyu.enderframelib.core.paint.*;
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
    /**
     * ボックスツリーの子。レイアウトと描画はこちらを読む。
     *
     * パーサが作るツリーでは {@link BoxTreeBuilder} が {@link #elementChildren} から組み立てるので、
     * 匿名ボックスが挟まり、{@code display: contents} の要素は抜けて子が繰り上がる。
     */
    public final List<LayoutBox> children = new ArrayList<>();
    /**
     * 要素ツリー（DOM と 1:1）の子。スタイルの継承と再スタイルはこちらを辿る。
     *
     * 何が壊れていたか: 以前は DOM と箱が 1:1 で、この 2 つが同じリストだった。そのため
     * 匿名ブロック・匿名表ボックス・{@code display: contents} が作れなかった（軸 6）。
     */
    public final List<LayoutBox> elementChildren = new ArrayList<>();
    /** DOM に無い箱（匿名ブロック・匿名表ボックス・{@code ::marker}）。{@link #node} は持ち主に紐づけた合成ノード。 */
    public final boolean isAnonymous;
    /**
     * {@code list-style-position: outside} の {@code ::marker}。通常フローに入らず、
     * リスト項目の最初の行の左に置かれる。
     */
    public boolean isOutsideMarker = false;
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
        }
    }

    /** 要素ツリーに子を足す。ボックスツリーは {@link BoxTreeBuilder} がここから組み立てる。 */
    public void addElementChild(LayoutBox child) {
        if (child != null) {
            child.parent = this;
            this.elementChildren.add(child);
        }
    }

    public void linkParents() {
        for (LayoutBox child : children) {
            child.parent = this;
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
        return isSet(style.get(CssProperty.TRANSFORM)) || isSet(style.get("filter"));
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

    public List<InlineFragment> inlineFragments = new ArrayList<>();
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
        this.style = newStyle;
        this.displaySource = node.text;
        this.display = Display.of(node, newStyle).blockified(newStyle);

        // 空白の畳み方は white-space で決まる。無条件に畳んでいたため <pre> が効かなかった。
        String text = TextShaper.normalize(node.text, WhiteSpace.of(newStyle.get(CssProperty.WHITE_SPACE)));
        this.displayText = LayoutMetrics.applyTextTransform(text, newStyle);

        String pos = newStyle.getOrDefault(CssProperty.POSITION, "static");
        this.isFixed = "fixed".equals(pos);
        this.isAbsolute = "absolute".equals(pos) || this.isFixed;
        this.isRelative = "relative".equals(pos);
        this.isSticky = "sticky".equals(pos);
        this.zIndex = (int) newStyle.number(CssProperty.Z_INDEX, 0f);
    }

    /** 算出スタイルも節の文字も前回と同じなら何もしない {@link #updateStyle}（差分レイアウトで箱を使い回すとき用）。 */
    public void refreshStyle(ComputedStyle newStyle) {
        if (newStyle == style && java.util.Objects.equals(node.text, displaySource)) return;
        updateStyle(newStyle);
    }

    public float horizontalDecoration() { return pL + pR + bL + bR; }

    /** 親の内容ボックスの幅（% の基準）。親が無ければ 0。 */
    float parentInnerWidth() { return parent == null ? 0 : Math.max(0, parent.w - parent.horizontalDecoration()); }
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
            if (!c.isAbsolute && !c.isOutsideMarker && !c.isRenderedLegend
                    && !"none".equals(c.style.getOrDefault(CssProperty.DISPLAY, "block"))) {
                list.add(c);
            }
        }
        return list;
    }

    /**
     * これ以上縮められない幅（min-content）。
     *
     * flex の shrink 解決ループから 1 要素あたり最大 10 回問われ、そのたびに
     * 部分木全体を再帰していたため、ビューポートごとに結果をメモ化する。
     */
    public float getMinContentWidth(LayoutContext ctx) {
        long key = ((long) ctx.viewport().rootW() << 32) | (ctx.viewport().rootH() & 0xFFFFFFFFL);
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
        String minWidthDecl = style.get(CssProperty.MIN_WIDTH);
        if (minWidthDecl != null && !LayoutMetrics.isIntrinsicSizeKeyword(minWidthDecl)) {
            // 枠線ボックスは padding と border より細くならない（min-width: 0 でも装飾の分は残る）
            float min = style.length(CssProperty.MIN_WIDTH, LayoutMetrics.UNBOUNDED_WIDTH, rootW, rootH);
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
            for (String segment : TextShaper.segments(this.displayText, textStyle.wordBreak())) {
                String word = segment.strip();
                if (!word.isEmpty()) maxWordW = Math.max(maxWordW, face.advance(word, font));
            }
            return maxWordW;
        }

        if (children.isEmpty()) {
            return this.w > 0 ? this.w : LayoutMetrics.FALLBACK_REPLACED_SIZE;
        }

        // 折り返さない行方向の flex コンテナは、アイテムの寄与の和と間隔（CSS Flexbox §9.9.1）。
        // 何が壊れていたか: 以前は常に最大値を返していたので、flex アイテムになった flex コンテナ
        // （検索欄のような横並び）が自動の最小寸法を下回って潰れ、中身が箱の外へはみ出していた
        boolean sumItems = display != null && display.inner() == Display.Inner.FLEX && isSingleLineRowFlex();
        float max = 0, sum = 0;
        int count = 0;
        for (LayoutBox c : getFlowChildren()) {
            float contribution = c.minContentContribution(ctx) + c.mL + c.mR;
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
        String width = style.get(CssProperty.WIDTH);
        if (width == null || width.contains("%") || LayoutMetrics.isIntrinsicSizeKeyword(width)) {
            return getMinContentWidth(ctx);
        }
        float v = style.length(CssProperty.WIDTH, 0, ctx.viewport().rootW(), ctx.viewport().rootH());
        float borderBox = style.isBorderBox() ? Math.max(v, horizontalDecoration()) : v + horizontalDecoration();
        String minWidth = style.get(CssProperty.MIN_WIDTH);
        if (minWidth != null && !minWidth.contains("%") && !LayoutMetrics.isIntrinsicSizeKeyword(minWidth)) {
            float min = style.length(CssProperty.MIN_WIDTH, 0, ctx.viewport().rootW(), ctx.viewport().rootH());
            borderBox = Math.max(borderBox, style.isBorderBox() ? min : min + horizontalDecoration());
        }
        return borderBox;
    }

    private boolean isSingleLineRowFlex() {
        String direction = style.getOrDefault(CssProperty.FLEX_DIRECTION, "row").trim();
        String wrap = style.getOrDefault(CssProperty.FLEX_WRAP, "nowrap").trim();
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
        for (LayoutBox child : children) child.parent = this;
        if (ctx.reuseMeasurements() && canReuseMeasurement(space, ctx)) {
            if (subtreeChanged && ctx.firstPass()) remeasureBoundaries(ctx);
            return;
        }
        MEASUREMENTS_COMPUTED.increment();
        BoxMeasurer.measure(this, space, ctx);
        recordMeasurement(space, ctx);
    }

    /**
     * 自分の measure を使い回したが、再レイアウト境界の中だけが変わっている（境界の寸法は変わらないので祖先は使い回せる）。
     * 境界まで降りて、中身だけを前回と同じ制約で測り直す。
     */
    private void remeasureBoundaries(LayoutContext ctx) {
        for (LayoutBox child : children) {
            if (!child.subtreeChanged || child.measuredSpace == null) continue;
            if (child.isRelayoutBoundary()) child.measure(child.measuredSpace, ctx);
            else child.remeasureBoundaries(ctx);
        }
    }

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
    private float measuredOverrideW = Float.NaN, measuredOverrideH = Float.NaN;
    private Viewport measuredViewport;
    private FontResolver measuredFonts;
    private IntrinsicSizeResolver measuredIntrinsics;
    private int measuredEpoch;
    private long measuredRun = -1;
    /** measure を終えたときの自分の寸法。親が後から書き換えた（stretch・表の行の高さ）なら使い回さない。 */
    private float measuredW, measuredH, measuredMT, measuredMR, measuredMB, measuredML;
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

    private boolean canReuseMeasurement(ConstraintSpace space, LayoutContext ctx) {
        return measurementValid
                && !layoutChanged
                && (ctx.firstPass() || measuredRun != ctx.layoutRun())
                && measuredEpoch == MEASUREMENT_EPOCH.get()
                && floatSpace == null
                && collapsedBorders == null
                && space.equals(measuredSpace)
                && sameLength(overrideWidth, measuredOverrideW)
                && sameLength(overrideHeight, measuredOverrideH)
                && ctx.viewport().equals(measuredViewport)
                && ctx.fonts() == measuredFonts
                && ctx.intrinsicResolver() == measuredIntrinsics
                && w == measuredW && h == measuredH
                && mT == measuredMT && mR == measuredMR && mB == measuredMB && mL == measuredML;
    }

    private static boolean sameLength(float a, float b) {
        return Float.isNaN(a) ? Float.isNaN(b) : a == b;
    }

    /**
     * measure を通らずに箱の中身を組み直した（{@link BoxMeasurer#resolveFlexibleHeights}）。この箱と祖先の
     * 前回の measure の結果は使わせない。
     */
    void invalidateMeasurement() {
        for (LayoutBox b = this; b != null; b = b.parent) {
            b.measurementValid = false;
            b.placementValid = false;
        }
    }

    private void recordMeasurement(ConstraintSpace space, LayoutContext ctx) {
        // 再レイアウト境界が中身だけを測り直し、外から見える寸法が前回と同じなら、祖先の測定は無効にならない
        boolean outerSizeStable = measuredStyle != null && w == measuredW && h == measuredH
                && mT == measuredMT && mR == measuredMR && mB == measuredMB && mL == measuredML;
        measuredSpace = space;
        measuredOverrideW = overrideWidth;
        measuredOverrideH = overrideHeight;
        measuredViewport = ctx.viewport();
        measuredFonts = ctx.fonts();
        measuredIntrinsics = ctx.intrinsicResolver();
        measuredEpoch = MEASUREMENT_EPOCH.get();
        measuredRun = ctx.layoutRun();
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
        measuredChildren = children.isEmpty() ? EMPTY_BOX_ARRAY : children.toArray(EMPTY_BOX_ARRAY);
        measuredFirstFlowChild = BlockLayoutEngine.isFirstFlowChild(this);
        measuredHadFloatChild = hasFloatingChild();
        layoutChanged = false;
        measurementValid = true;
        // 子孫を測り直したので、祖先が前回の measure で残した状態はもう当てはまらない。
        // いま祖先が測っている途中なら、祖先は測り終えたときに記録し直す
        invalidateAncestors(!(outerSizeStable && isRelayoutBoundary()));
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
    private int contentHash() {
        if (node == null) return 0;
        int hash = 1;
        for (java.util.Map.Entry<String, String> attr : node.attrs.entrySet()) {
            String key = attr.getKey();
            if ("style".equals(key) || "class".equals(key)) continue;
            hash = 31 * hash + key.hashCode();
            hash = 31 * hash + java.util.Objects.hashCode(attr.getValue());
        }
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
        if (keepRun < 0) {
            box.placementPure = false;
            box.lastLayoutX = Float.NaN;
        }
        box.inlineFragments.clear();
        box.x = 0;
        box.y = 0;
        box.staticX = Float.NaN;
        box.staticY = Float.NaN;
        box.scrollWidth = 0;
        box.scrollHeight = 0;
        box.descendantOverflowRight = 0;
        box.descendantOverflowBottom = 0;
        box.inlineContentBottom = Float.NEGATIVE_INFINITY;
        for (LayoutBox child : box.children) resetPlacements(child, keepRun);
    }

    /**
     * 前回の箱の木から、DOM の節ごとの箱を集める（差分レイアウトで使い回す候補）。
     * 表とその中は集めない: 表のエンジンが中の箱へ枠線の分担・縦の揃え・行の高さを直接書き込むので、作り直す方が確か。
     */
    public static java.util.Map<HtmlNode, LayoutBox> reusableBoxes(LayoutBox root) {
        java.util.Map<HtmlNode, LayoutBox> out = new java.util.IdentityHashMap<>();
        if (root != null) collectReusableBoxes(root, out);
        return out;
    }

    private static void collectReusableBoxes(LayoutBox box, java.util.Map<HtmlNode, LayoutBox> out) {
        if (box.isAnonymous || !isReusableShape(box)) return;
        out.putIfAbsent(box.node, box);
        for (LayoutBox child : box.elementChildren) collectReusableBoxes(child, out);
    }

    /** 前回も今も表と関わりの無い箱か（使い回してよい形か）。 */
    static boolean isReusableShape(LayoutBox box) {
        return !isTableBox(box) && !box.everLaidOutAsTable && box.collapsedBorders == null;
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
            if (child.style.keywordOf(CssProperty.FLOAT, CssKeywords.FloatSide.class) != CssKeywords.FloatSide.NONE) return true;
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
        // 再レイアウト境界の中の変化は、境界の寸法を変えないので祖先の測り直しには及ばない
        // （境界自身の署名が変わったときだけ祖先へ伝える）
        return box.isRelayoutBoundary() ? selfChanged : remeasure;
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
        return hasDefiniteLength(CssProperty.WIDTH) && hasDefiniteLength(CssProperty.HEIGHT);
    }

    private boolean hasDefiniteLength(String property) {
        String value = style.get(property);
        if (value == null) return false;
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        return !v.isEmpty() && !v.equals("auto") && !v.contains("%") && !LayoutMetrics.isIntrinsicSizeKeyword(v);
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
        if ("none".equals(style.getOrDefault(CssProperty.DISPLAY, "block"))) return;
        for (LayoutBox child : children) child.parent = this;
        hasFloatChild = false;
        for (LayoutBox child : children) {
            if (child.style.keywordOf(CssProperty.FLOAT, CssKeywords.FloatSide.class) != CssKeywords.FloatSide.NONE) {
                hasFloatChild = true;
                break;
            }
        }
        // 親の外寸（パーセントの基準）は、位置指定・スクロールコンテナ・float を含まない部分木（placementPure）の配置には効かない。
        // 中身が増えて親の高さが変わっても、子の配置の入力は変わらない
        boolean sameInputs = lastLayoutX == parentX && lastLayoutY == parentY
                && (placementPure || (lastLayoutOuterW == parentOuterW && lastLayoutOuterH == parentOuterH));
        if (ctx.reuseMeasurements() && sameInputs && canKeepPlacement(ctx.layoutRun())) {
            LAYOUTS_SKIPPED.increment();
            return;
        }
        // 原点か包含ブロックの寸法が変わったので、前回の配置を引き継いだ子孫の位置はどれも古い。先に捨てる
        // （自分の staticX / staticY は親が layout の前に置くので消さない）。入力が同じで、子孫の変化のために
        // 置き直すだけなら、子はそれぞれの入力を見て飛ばすか置き直すかを決める
        if (keepPlacements && placementPure && !sameInputs) {
            for (LayoutBox child : children) resetPlacements(child, -1);
        }

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
            if (!BoxMeasurer.isAutoInset(style, CssProperty.LEFT)) {
                this.x = refX + style.length(CssProperty.LEFT, refW, rootW, rootH) + mL;
            } else if (!BoxMeasurer.isAutoInset(style, CssProperty.RIGHT)) {
                this.x = refX + refW - this.w - style.length(CssProperty.RIGHT, refW, rootW, rootH) - mR;
            } else {
                this.x = (Float.isNaN(staticX) ? refX : staticX) + mL;
            }
            if (!BoxMeasurer.isAutoInset(style, CssProperty.TOP)) {
                this.y = refY + style.length(CssProperty.TOP, refH, rootW, rootH) + mT;
            } else if (!BoxMeasurer.isAutoInset(style, CssProperty.BOTTOM)) {
                this.y = refY + refH - this.h - style.length(CssProperty.BOTTOM, refH, rootW, rootH) - mB;
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
        for (LayoutBox child : children) {
            if (child.isAbsolute) {
                child.staticX = innerX;
                child.staticY = innerY;
            }
        }

        LayoutEngine engine = LayoutEngine.getEngine(display);
        engine.arrangeChildren(this, innerX, innerY, ctx);

        for (LayoutBox child : children) {
            if ("none".equals(child.style.getOrDefault(CssProperty.DISPLAY, "block"))) continue;
            if (child.isAbsolute) child.layout(this.x - scrollX, this.y - scrollY, this.w, this.h, ctx);
        }
        LayoutBox legend = renderedLegend();
        if (legend != null) placeRenderedLegend(legend, ctx);

        applyRelativePositioning(parentOuterW, parentOuterH, rootW, rootH);
        if (this.isSticky) applyStickyOffset(ctx);
        updateScrollExtent(scrollX, scrollY);
        recordPlacement(parentX, parentY, parentOuterW, parentOuterH, legend != null);
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
        return node.tag + "#" + node.getId() + " disp=" + display + " pure=" + placementPure + " lastX=" + lastLayoutX + " lastY=" + lastLayoutY + " x=" + x + " y=" + y + " valid=" + measurementValid + " runM=" + measuredRun + " anon=" + isAnonymous;
    }

    /** 部分木の配置が、この箱の外の状態に依らず親の渡す原点だけで決まる（前回の layout で確かめた）。 */
    private boolean placementPure = false;
    private float lastLayoutX = Float.NaN, lastLayoutY = Float.NaN, lastLayoutOuterW = Float.NaN, lastLayoutOuterH = Float.NaN;

    private void recordPlacement(float parentX, float parentY, float parentOuterW, float parentOuterH, boolean hasLegend) {
        lastLayoutX = parentX;
        lastLayoutY = parentY;
        lastLayoutOuterW = parentOuterW;
        lastLayoutOuterH = parentOuterH;
        placementPure = !hasLegend && subtreeIsSelfContained();
        placementValid = true;
    }

    /**
     * この箱とその子孫が、外の状態（絶対配置の包含ブロック・float・スクロール量・sticky のポート・表の分担）に
     * 位置を依存しないか。layout を通らない子（行に流れるテキストやインラインの箱）は構造だけで見る。
     */
    private boolean subtreeIsSelfContained() {
        if (isAbsolute || isFixed || isRelative || isSticky || isScrollContainer() || floatSpace != null
                || isTableBox(this) || collapsedBorders != null
                || style.keywordOf(CssProperty.FLOAT, CssKeywords.FloatSide.class) != CssKeywords.FloatSide.NONE) {
            return false;
        }
        for (LayoutBox child : children) {
            if ("none".equals(child.style.getOrDefault(CssProperty.DISPLAY, "block"))) continue;
            boolean laidOutByLayout = !Float.isNaN(child.lastLayoutX);
            if (laidOutByLayout ? !child.placementPure : !child.subtreeIsSelfContained()) return false;
        }
        return true;
    }

    /**
     * 前回の layout の配置をそのまま使えるか（原点が同じかは呼び出し側が確かめる）。この run で測り直されておらず
     * （測り直した箱と、その祖先の測定は無効になる）、親がブロックコンテナで、前回の配置が純粋だったときだけ。
     */
    private boolean canKeepPlacement(long run) {
        return keepPlacements && placementPure && placementValid && measurementValid && measuredRun != run
                && !layoutChanged && !subtreeChanged
                && parent != null && parent.display != null
                && (parent.display.inner() == Display.Inner.FLOW || parent.display.inner() == Display.Inner.FLOW_ROOT)
                && !display.isInlineLevel()
                && !floatsAffectPlacement();
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
        boolean autoLeft = "auto".equals(legend.style.get(CssProperty.MARGIN_LEFT));
        boolean autoRight = "auto".equals(legend.style.get(CssProperty.MARGIN_RIGHT));
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
        float l = "auto".equals(style.get(CssProperty.MARGIN_LEFT)) ? 0 : style.length(CssProperty.MARGIN_LEFT, 0, rootW, rootH);
        float r = "auto".equals(style.get(CssProperty.MARGIN_RIGHT)) ? 0 : style.length(CssProperty.MARGIN_RIGHT, 0, rootW, rootH);
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
        if (!BoxMeasurer.isAutoInset(style, CssProperty.TOP)) {
            float limit = portY + style.length(CssProperty.TOP, portH, rootW, rootH);
            if (y < limit) dy = Math.max(0, Math.min(limit - y, cbBottom - (y + h + mB)));
        } else if (!BoxMeasurer.isAutoInset(style, CssProperty.BOTTOM)) {
            float limit = portY + portH - style.length(CssProperty.BOTTOM, portH, rootW, rootH);
            if (y + h > limit) dy = -Math.max(0, Math.min(y + h - limit, y - mT - cbTop));
        }
        if (!BoxMeasurer.isAutoInset(style, CssProperty.LEFT)) {
            float limit = portX + style.length(CssProperty.LEFT, portW, rootW, rootH);
            if (x < limit) dx = Math.max(0, Math.min(limit - x, cbRight - (x + w + mR)));
        } else if (!BoxMeasurer.isAutoInset(style, CssProperty.RIGHT)) {
            float limit = portX + portW - style.length(CssProperty.RIGHT, portW, rootW, rootH);
            if (x + w > limit) dx = -Math.max(0, Math.min(x + w - limit, x - mL - cbLeft));
        }
        if (dx != 0 || dy != 0) applyRelativeOffset(dx, dy);
    }

    /**
     * 横方向の size containment（{@code container-type: size | inline-size}）。この箱の横の寸法は中身で決まらない:
     * shrink-to-fit の幅や min-content の幅に中身は寄与しない（CSS Containment 3 §3）。
     */
    public boolean containsInlineSize() {
        return (style.containment() & (ComputedStyle.CONTAIN_SIZE | ComputedStyle.CONTAIN_INLINE_SIZE)) != 0
                || style.keywordOf(CssProperty.CONTAINER_TYPE, CssKeywords.ContainerType.class).containsInlineSize();
    }

    /** 縦方向の size containment（{@code container-type: size}）。高さが {@code auto} でも中身の高さは寄与しない。 */
    public boolean containsBlockSize() {
        return (style.containment() & ComputedStyle.CONTAIN_SIZE) != 0
                || style.keywordOf(CssProperty.CONTAINER_TYPE, CssKeywords.ContainerType.class).containsBlockSize();
    }

    /** {@code overflow} が {@code visible} / {@code clip} 以外ならスクロールコンテナ。 */
    public boolean isScrollContainer() {
        return style.overflowX().scrolls() || style.overflowY().scrolls();
    }

    private void applyRelativePositioning(float parentOuterW, float parentOuterH, float rootW, float rootH) {
        if (!this.isRelative) return;

        float offX = 0, offY = 0;
        if (style.containsKey(CssProperty.LEFT)) offX += style.length(CssProperty.LEFT, parentOuterW, rootW, rootH);
        else if (style.containsKey(CssProperty.RIGHT)) offX -= style.length(CssProperty.RIGHT, parentOuterW, rootW, rootH);
        if (style.containsKey(CssProperty.TOP)) offY += style.length(CssProperty.TOP, parentOuterH, rootW, rootH);
        else if (style.containsKey(CssProperty.BOTTOM)) offY -= style.length(CssProperty.BOTTOM, parentOuterH, rootW, rootH);

        if (offX != 0 || offY != 0) applyRelativeOffset(offX, offY);
    }

    /**
     * スクロール範囲を更新する。
     * エンジン固有の子（グリッドアイテムなど）は {@link LayoutEngineState} が自分で足し込む。
     */
    private void updateScrollExtent(float scrollX, float scrollY) {
        ScrollExtent extent = new ScrollExtent(this.x + pL + bL, this.y + pT + bT);
        // 子孫の境界ボックスの和。クリップしない子は、その子孫のはみ出しも親のはみ出しになる（CSS Overflow 3 §2.2）
        ScrollExtent descendants = new ScrollExtent(this.x + this.w, this.y + this.h);
        // 下 padding・border・高さが無い親では、末子の margin-bottom は親を貫通して相殺する。貫通した余白は親の中身ではないので、
        // 親のスクロール範囲に入れない（ブラウザと同じ）
        boolean marginEscapes = !establishesBfc && pB == 0 && bB == 0 && !hasDefiniteHeight();
        LayoutBox lastInFlow = null;
        if (marginEscapes) {
            for (LayoutBox child : children) {
                if (!"none".equals(child.style.getOrDefault(CssProperty.DISPLAY, "block")) && !child.isAbsolute
                        && !child.display.isInlineLevel()) {
                    lastInFlow = child;
                }
            }
        }
        for (LayoutBox child : children) {
            if ("none".equals(child.style.getOrDefault(CssProperty.DISPLAY, "block")) || child.isAbsolute) continue;
            if (child.node != null && child.node.nodeType == NodeType.TEXT) {
                // 文字の塊の箱は位置を持たない（x / y は 0 のまま。置かれるのは断片）。箱の x / y / h を読むと、相対配置・sticky で
                // 祖先が動いたとき（その分だけ箱も平行移動される）スクロール範囲にそのずれが入り、sticky ではスクロールするほど
                // 範囲が伸び続ける。断片の端を取り込む
                for (InlineFragment frag : child.inlineFragments) {
                    float right = frag.x + frag.w + scrollX;
                    float bottom = Math.max(frag.y + frag.h, frag.y + frag.baseline + frag.descent) + scrollY;
                    extent.include(right, bottom);
                    descendants.include(right, bottom);
                }
                continue;
            }
            if (child == lastInFlow) {
                extent.include(child.x + child.w + child.mR + scrollX, child.y + child.h + scrollY);
            } else {
                extent.include(child, scrollX, scrollY);
            }
            descendants.include(child.x + child.w + scrollX, child.y + child.h + scrollY);
            if (!child.clipsOverflow()) {
                descendants.include(child.x + child.descendantOverflowRight + scrollX,
                        child.y + child.descendantOverflowBottom + scrollY);
            }
        }
        if (engineState != null) engineState.contributeScrollExtent(extent, scrollX, scrollY);
        if (inlineContentBottom > Float.NEGATIVE_INFINITY) {
            extent.include(this.x + pL + bL, inlineContentBottom + scrollY);
        }

        this.descendantOverflowRight = descendants.right() - this.x;
        this.descendantOverflowBottom = descendants.bottom() - this.y;
        if (isCollapsedTable()) {
            // 枠線を重ねる表のスクロール範囲は、セルの端まで（表の外周の枠線の半分は含めず、表の箱の大きさで下限も切らない）
            this.scrollWidth = extent.right() - this.x;
            this.scrollHeight = extent.bottom() - this.y;
            return;
        }
        this.scrollWidth = Math.max(Math.max(this.w, extent.right() - this.x + pR + bR), descendantOverflowRight);
        this.scrollHeight = Math.max(Math.max(this.h, extent.bottom() - this.y + pB + bB), descendantOverflowBottom);
    }

    /**
     * {@code border-collapse: collapse} の表か。この表の外周の枠線は、表の箱の中に半分だけあるが
     * {@code clientLeft} などには数えない（ブラウザと同じ。{@code clientWidth} は箱の幅のまま）。
     * {@code overflow} でクリップする表は普通の箱と同じに扱う。
     */
    public boolean isCollapsedTable() {
        return display != null && display.inner() == Display.Inner.TABLE && style != null && !clipsOverflow()
                && style.keywordOf(CssProperty.BORDER_COLLAPSE, CssKeywords.BorderCollapse.class) == CssKeywords.BorderCollapse.COLLAPSE;
    }

    private boolean hasDefiniteHeight() {
        String height = style.get(CssProperty.HEIGHT);
        if (height != null && !height.isBlank() && !"auto".equalsIgnoreCase(height.trim())) return true;
        String minHeight = style.get(CssProperty.MIN_HEIGHT);
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
                "br", "span", "a", "b", "i", "strong", "em", "img", "canvas", "svg", "input", "button", "select", "progress",
                "mc-fluid", "mc-entity", "mc-scene");

        /** 箱の display。テキストは常にインライン、宣言が無い・解釈できない値はタグから推測する。 */
        public static Display of(HtmlNode node, ComputedStyle style) {
            if (node.nodeType == NodeType.TEXT) return INLINE;
            Display parsed = parse(style.get(CssProperty.DISPLAY));
            if (parsed != null) return parsed;
            return INLINE_TAGS.contains(node.tag) ? INLINE : BLOCK;
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
            CssKeywords.Position position = style.keywordOf(CssProperty.POSITION, CssKeywords.Position.class);
            boolean outOfFlow = position == CssKeywords.Position.ABSOLUTE || position == CssKeywords.Position.FIXED
                    || style.keywordOf(CssProperty.FLOAT, CssKeywords.FloatSide.class) != CssKeywords.FloatSide.NONE;
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