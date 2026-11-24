package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.StyleFacts;
import com.nishiyu.enderframelib.core.css.model.CssKeywords.Alignment;

/**
 * flex の項目 1 つの作業域（Engine v3 M2b-2）。1 回の整形の間だけ使う局所の値で、断片には残さない。
 *
 * <p>軸は論理（主軸・交差軸）で持つ。{@code row} なら主軸が横・交差軸が縦、{@code column} なら逆。余白の {@code auto} は
 * 0 として数え、余りを配るとき（{@link FlexLines}）に解く。</p>
 */
final class FlexItem {
    final BoxNode box;
    final BoxGeometry g;
    final LayoutCache.Slots slots;
    /** 交差軸の揃え（{@code align-self}、auto なら容器の {@code align-items}。normal は stretch）。 */
    final Alignment align;
    /** 主軸・交差軸の始点・終点の余白が {@code auto} か。 */
    final boolean autoMS, autoME, autoCS, autoCE;
    /** 主軸・交差軸の始点・終点の余白（auto は解くまで 0）。 */
    float mMS, mME, mCS, mCE;
    float grow, shrink;
    /** flex base size・仮の主軸の大きさ・主軸の最小・最大（border-box）。 */
    float base, hyp, minMain, maxMain;
    /** 解いた主軸の大きさ（border-box）と、柔軟な長さの解決の作業値。 */
    float target, raw;
    boolean frozen;
    /** column の項目の交差軸の大きさ（幅。border-box）。row では使わない。 */
    float crossW;
    LayoutFragment frag;
    /** ベースラインで揃えるときの、余白の箱の交差軸の始点からベースラインまで（揃えなければ NaN）。 */
    float baseline = Float.NaN;
    /** 内容ボックス基準の border-box の左上（主軸・交差軸）。 */
    float mainPos, crossPos;

    FlexItem(BoxNode box, BoxGeometry g, LayoutCache.Slots slots, Style container, boolean row) {
        this.box = box;
        this.g = g;
        this.slots = slots;
        Style st = box.style;
        StyleFacts f = st.facts;
        Alignment a = st.flexGrid.alignSelf;
        if (a == null || a == Alignment.AUTO) a = container.flexGrid.alignItems;
        this.align = normalize(a);
        this.autoMS = row ? f.autoMarginL : f.autoMarginT;
        this.autoME = row ? f.autoMarginR : f.autoMarginB;
        this.autoCS = row ? f.autoMarginT : f.autoMarginL;
        this.autoCE = row ? f.autoMarginB : f.autoMarginR;
        this.mMS = autoMS ? 0 : row ? g.mL : g.mT;
        this.mME = autoME ? 0 : row ? g.mR : g.mB;
        this.mCS = autoCS ? 0 : row ? g.mT : g.mL;
        this.mCE = autoCE ? 0 : row ? g.mB : g.mR;
        this.grow = Math.max(0, Style.num(st.flexGrid.flexGrow, 0));
        this.shrink = Math.max(0, Style.num(st.flexGrid.flexShrink, 1));
    }

    /** {@code normal}・{@code auto} は stretch、{@code first/last baseline} は baseline。 */
    private static Alignment normalize(Alignment a) {
        if (a == null) return Alignment.STRETCH;
        return switch (a) {
            case NORMAL, AUTO, LEGACY -> Alignment.STRETCH;
            case FIRST_BASELINE, LAST_BASELINE -> Alignment.BASELINE;
            default -> a;
        };
    }

    /** 交差軸の余白のどちらかが auto か（揃えより先に余りを吸う。stretch しない）。 */
    boolean autoCross() {
        return autoCS || autoCE;
    }

    /** 主軸の余白の和（auto は 0）。 */
    float outerMain() {
        return mMS + mME;
    }

    /** 主軸の大きさ（解いた後の断片から）。 */
    float mainSize(boolean row) {
        return row ? frag.width : frag.height;
    }

    /** 交差軸の大きさ（border-box）。 */
    float crossSize(boolean row) {
        return row ? frag.height : frag.width;
    }

    /** 余白を含む交差軸の大きさ（auto の余白は 0）。 */
    float outerCross(boolean row) {
        return crossSize(row) + mCS + mCE;
    }

    @Override
    public String toString() {
        return "FlexItem[" + box + " base=" + base + " hyp=" + hyp + " target=" + target + "]";
    }
}
