package com.nishiyu.enderframelib.core.paint.tree;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.text.model.ShapedLine;

import java.util.List;

/**
 * 箱 1 つを描画項目にするときに読む箱（M2c ②。計画書 §3.4.2-4）。箱の飾り・部品・文字を描く道具（{@code BoxItemPainter}・
 * {@code BoxDecorationPainter}・{@code ControlPainter} ほか）はこの口だけを読む。実装は描画用の木の見え方 {@link PaintView} だけ
 * （M2c-3 で旧経路の {@code LayoutBox} を消し、実装と同じこのパッケージへ移した。{@code core.paint} からの片方向の参照になる）。
 * M3b で {@code DisplayScene} に吸収して消す。
 *
 * <p>座標は全て絶対（文書の左上が原点の px）。{@link #runs} の位置は {@link #originParent} の左上が原点。</p>
 */
public abstract class PaintBox {
    /** 元の DOM の節（匿名の箱・生成内容では持ち主に紐づけた節）。 */
    public final HtmlNode node;
    /** DOM に無い箱（匿名ブロック・匿名表ボックス・{@code ::marker}）。{@link #node} は持ち主に紐づけた合成ノード。 */
    public final boolean isAnonymous;

    /** {@link #displayTraits} のビット: 表のセル・{@code display: none}・インライン水準の原子・flex / grid の容器。 */
    public static final int TABLE_CELL = 1, DISPLAY_NONE = 2, ATOMIC_INLINE = 4, FLEX_OR_GRID = 8;

    protected PaintBox(HtmlNode node, boolean anonymous) {
        this.node = node;
        this.isAnonymous = anonymous;
    }

    public final HtmlNode node() {
        return node;
    }

    public final boolean anonymous() {
        return isAnonymous;
    }

    /** 算出スタイル。 */
    public abstract ComputedStyle style();

    /**
     * 描画が読む DOM の値（属性・textarea の中身・状態の規則が掛かるか）の写し（M3b-1・決定 F。箱の木が作る。DOM に無い箱では空）。
     * 描画はこれだけを読み、生きた DOM の属性・文字を読まない（{@code ArchitectureHealthTest#paintDoesNotReadDomValues}）。
     */
    public abstract com.nishiyu.enderframelib.core.box.DomValues values();

    /** 物理の寸法・余白・枠線・角丸・overflow を箱の局所の軸で読むスタイル（縦書きの中の箱。それ以外は {@link #style}）。 */
    public abstract ComputedStyle axisStyle();

    /** 描画の木の親（根なら null）。 */
    public abstract PaintBox paintParent();

    /** 描画の木の子。 */
    public abstract List<? extends PaintBox> paintChildren();

    /** 行に置かれたこの箱の断片（文字の語・インライン要素の行ごとの箱）。 */
    public abstract List<? extends PaintRun> runs();

    /** {@link #runs} の座標の原点の箱（無ければ null で、原点は 0）。 */
    public abstract PaintBox originParent();

    public abstract float x();
    public abstract float y();
    public abstract float w();
    public abstract float h();
    public abstract float bT();
    public abstract float bR();
    public abstract float bB();
    public abstract float bL();
    public abstract float pT();
    public abstract float pR();
    public abstract float pB();
    public abstract float pL();

    /** 余白（下は {@code <legend>} が {@code <fieldset>} の枠線を切り欠く範囲にも使う。CSSOM・インスペクタが読む）。 */
    public abstract float mT();
    public abstract float mR();
    public abstract float mB();
    public abstract float mL();

    /** スクロールコンテナか（{@code overflow} が visible / clip 以外）。 */
    public abstract boolean isScrollContainer();

    /** 枠線を重ねる（{@code border-collapse: collapse}）表か（CSSOM の client の寸法に外周の枠線を数えない）。 */
    public abstract boolean isCollapsedTable();

    /** スクロールで溢れた範囲の幅・高さ（border-box の左上から）。 */
    public abstract float scrollWidth();
    public abstract float scrollHeight();

    /** 行の高さ（px）。 */
    public abstract float lineHeightPx();

    /** 位置指定が {@code absolute} / {@code fixed} か。 */
    public abstract boolean isAbsolute();

    /** 直交フローの根の中身を包む、回転を持つ生成箱か。 */
    public abstract boolean isVerticalWrapper();

    /** 状態で値が変わる規則（{@code :hover} など）を受けるか（描画項目に印を付ける）。 */
    public abstract boolean interactiveRules();

    /** 枠線を重ねる表の、表が決めた使う枠線の幅 {上, 右, 下, 左}（無ければ null）。 */
    public abstract float[] collapsedBorders();

    /** 段組の列の間の線の矩形（無ければ null）。 */
    public abstract float[][] columnRules();

    /** {@code ::first-line} の背景を塗る矩形（無ければ null）。 */
    public abstract float[] firstLineBgRect();

    /** この箱の {@code ::first-line} の算出スタイル（無ければ null）。 */
    public abstract ComputedStyle firstLine();

    /** {@code ::placeholder} の算出スタイル（無ければ null）。 */
    public abstract ComputedStyle placeholder();

    /** 描画される {@code <legend>}（{@code <fieldset>} の枠線を切り欠く。無ければ null）。 */
    public abstract PaintBox renderedLegend();

    /** 部品の中の文字の行（{@code textarea} など。無ければ null）。 */
    public abstract List<ShapedLine> shapedLines();

    /** 表示の種類のビット（{@link #TABLE_CELL} ほか）。 */
    public abstract int displayTraits();

    /** 親が flex / grid の容器か（項目は位置指定が無くても {@code z-index} が効く）。 */
    public final boolean inFlexOrGrid() {
        PaintBox p = paintParent();
        return p != null && (p.displayTraits() & FLEX_OR_GRID) != 0;
    }

    /** インライン水準の原子（{@code inline-block} など、中が流れでないインラインの箱）か。 */
    public final boolean atomicInline() {
        return (displayTraits() & ATOMIC_INLINE) != 0;
    }

    /** 表のセルか。 */
    public final boolean tableCell() {
        return (displayTraits() & TABLE_CELL) != 0;
    }

    /** {@code display: none} か。 */
    public final boolean displayNone() {
        return (displayTraits() & DISPLAY_NONE) != 0;
    }
}
