package com.nishiyu.enderframelib.core.layout.fragment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * M2b-2 の flex の完全版（計画書 §3.4.1）: 新経路の {@link FlexLines} / {@link FlexFlow} が CSS Flexbox の規則どおりに置くこと。
 * 期待値は仕様から手で求めた数（旧経路とは比べない。旧経路との比較は {@link LegacyParityTest#flexExtendedMatchesLegacy}）。
 * 文字を含まない固定の大きさの箱だけで組むので、書体に依らない。
 */
class FlexFeaturesTest {
    private static final float EPS = 0.02f;

    private static float[] at(String html, String id) {
        float[] r = SpikeView.find(new SpikeView(html, 800, 600).frame(), id);
        assertNotNull(r, id);
        return r;
    }

    private static String doc(String body) {
        return "<html><body style='margin:0'>" + body + "</body></html>";
    }

    private static final String ITEM = "width:60px;height:20px";

    /** 折り返す容器の {@code align-content: normal}（＝stretch）は余りを行へ等分し、stretch の項目は伸びた行の高さになる（§9.4 の 15）。 */
    @Test
    void alignContentNormalStretchesLines() {
        String html = doc("<div style='display:flex;flex-wrap:wrap;width:150px;height:100px'>"
                + "<div id=a style='width:60px'></div><div id=b style='" + ITEM + "'></div><div id=c style='width:60px'></div></div>");
        // 2 行（a b | c）。行の内容の高さは 20・0、余り 80 を 40 ずつ足して 60・40
        assertEquals(60, at(html, "a")[3], EPS);
        assertEquals(60, at(html, "c")[1], EPS);
        assertEquals(40, at(html, "c")[3], EPS);
    }

    /** {@code align-content: space-between} と {@code wrap-reverse}（行は下から積む）。 */
    @Test
    void alignContentDistributionAndWrapReverse() {
        String items = "<div id=a style='" + ITEM + "'></div><div id=b style='" + ITEM + "'></div><div id=c style='" + ITEM + "'></div>";
        String sb = doc("<div style='display:flex;flex-wrap:wrap;width:150px;height:100px;align-content:space-between'>" + items + "</div>");
        assertEquals(0, at(sb, "a")[1], EPS);
        assertEquals(80, at(sb, "c")[1], EPS);
        String wr = doc("<div style='display:flex;flex-wrap:wrap-reverse;width:150px;height:100px;align-content:flex-start'>" + items + "</div>");
        assertEquals(80, at(wr, "a")[1], EPS);
        assertEquals(60, at(wr, "c")[1], EPS);
    }

    /** column の折り返し: 高さで列に分け、次の列は前の列の最大の幅の右（§9.3）。 */
    @Test
    void columnWrapBreaksByHeight() {
        String html = doc("<div style='display:flex;flex-flow:column wrap;height:50px;width:300px;align-content:flex-start'>"
                + "<div id=a style='" + ITEM + "'></div><div id=b style='width:80px;height:20px'></div><div id=c style='" + ITEM + "'></div></div>");
        assertEquals(0, at(html, "b")[0], EPS);
        assertEquals(20, at(html, "b")[1], EPS);
        assertEquals(80, at(html, "c")[0], EPS);
        assertEquals(0, at(html, "c")[1], EPS);
    }

    /** {@code row-reverse} は右から詰め、{@code column-reverse} は下から積む。{@code order} は並びを変える。 */
    @Test
    void reverseAndOrder() {
        String rr = doc("<div style='display:flex;flex-direction:row-reverse;width:300px'><div id=a style='" + ITEM + "'></div>"
                + "<div id=b style='" + ITEM + "'></div></div>");
        assertEquals(240, at(rr, "a")[0], EPS);
        assertEquals(180, at(rr, "b")[0], EPS);
        String cr = doc("<div style='display:flex;flex-direction:column-reverse;height:100px'><div id=a style='" + ITEM + "'></div>"
                + "<div id=b style='" + ITEM + "'></div></div>");
        assertEquals(80, at(cr, "a")[1], EPS);
        assertEquals(60, at(cr, "b")[1], EPS);
        String or = doc("<div style='display:flex;width:300px'><div id=a style='" + ITEM + ";order:1'></div><div id=b style='" + ITEM + "'></div></div>");
        assertEquals(60, at(or, "a")[0], EPS);
        assertEquals(0, at(or, "b")[0], EPS);
    }

    /** auto 余白は {@code justify-content} より先に主軸の余りを吸い、交差軸では揃えより先に余りを吸う（§8.1）。 */
    @Test
    void autoMarginsAbsorbFreeSpace() {
        String html = doc("<div style='display:flex;width:300px;height:100px;justify-content:flex-end'>"
                + "<div id=a style='" + ITEM + ";margin:auto'></div><div id=b style='" + ITEM + ";margin-top:auto'></div></div>");
        // 主軸の余り 180 を a の左右の 2 つの auto 余白で 90 ずつ
        assertEquals(90, at(html, "a")[0], EPS);
        assertEquals(40, at(html, "a")[1], EPS);
        assertEquals(240, at(html, "b")[0], EPS);
        assertEquals(80, at(html, "b")[1], EPS);
    }

    /**
     * column の容器の {@code align-items: baseline} は交差軸（横）にベースラインが無いので safe self-start（CSS Box Alignment 3 §9.3）:
     * 項目自身の {@code direction} の始点に寄せ、{@code wrap-reverse} では裏返さない（WPT flex-align-baseline-column-vert-rl-rtl-wrap-reverse）。
     */
    @Test
    void columnBaselineFallsBackToSelfStart() {
        String html = doc("<div style='display:flex;flex-direction:column;flex-wrap:wrap-reverse;align-items:baseline;width:100px;height:100px;direction:rtl'>"
                + "<div id=a style='width:40px;height:20px'></div></div>");
        assertEquals(60, at(html, "a")[0], EPS); // rtl の始点は右
        String ltr = html.replace("direction:rtl", "direction:ltr");
        assertEquals(0, at(ltr, "a")[0], EPS); // wrap-reverse で右へ裏返さない
    }

    /** ベースライン揃え: 上の余白の大きい項目に合わせて他が下がり、容器のベースラインはその項目のもの（§8.3・§8.5）。 */
    @Test
    void baselineAlignment() {
        String html = doc("<div id=f style='display:flex;align-items:baseline'>"
                + "<div id=a style='" + ITEM + "'></div><div id=b style='" + ITEM + ";margin-top:30px'></div></div>");
        // 行を持たない項目のベースラインは border-box の下端（合成）。b の下端 50 に a の下端を揃える
        assertEquals(30, at(html, "a")[1], EPS);
        assertEquals(30, at(html, "b")[1], EPS);
        LayoutFragment f = InlineTextTest.frag(new SpikeView(html, 800, 600).frame(), "f");
        assertEquals(50, f.baseline, EPS);
    }

    /** fieldset の legend は fieldset のベースラインにならない（HTML §15.3.13。WPT fieldset-baseline-alignment）。 */
    @Test
    void fieldsetBaselineSkipsTheLegend() {
        // 行の高さ 0 の行に原子だけを置くと、行のベースラインは原子の下端（legend 30・中身 30 + 20 = 50）
        String atom = "display:inline-block;width:10px;height:";
        String html = doc("<div id=f style='display:flex;align-items:baseline'>"
                + "<fieldset id=fs style='margin:0;padding:0;border:0;line-height:0;font-size:0'>"
                + "<legend style='display:block;padding:0'><span style='" + atom + "30px'></span></legend>"
                + "<div><span style='" + atom + "20px'></span></div></fieldset><div id=b style='" + ITEM + "'></div></div>");
        // fieldset のベースラインは legend の行（30）ではなく中身の行（50）。b（行を持たず下端 20 が合成のベースライン）は 50 - 20 = 30 へ
        assertEquals(30, at(html, "b")[1], EPS);
    }

    /** abspos の子の静的位置は、内容ボックスを唯一の項目として {@code justify-content} / {@code align-items} で揃える（§4.1）。 */
    @Test
    void absoluteStaticPositionIsAligned() {
        String html = doc("<div style='display:flex;position:relative;width:200px;height:100px;justify-content:center;align-items:flex-end'>"
                + "<div id=a style='position:absolute;width:30px;height:20px'></div></div>");
        assertEquals(85, at(html, "a")[0], EPS);
        assertEquals(80, at(html, "a")[1], EPS);
    }

    /**
     * 内在サイズ（§9.9）: 縮まない項目は flex base size より小さく寄与しない（WPT intrinsic-size/row-003）・折り返す column は容器の
     * 高さで列に分けた列ごとの最大の幅の和（{@code max-width} が寄与を切る。WPT intrinsic-size/col-wrap-014）。
     */
    @Test
    void intrinsicSizes() {
        String row = doc("<div style='width:0'><div id=f style='display:flex;float:left;height:10px'><div style='flex:1 0 100px'></div></div></div>");
        assertEquals(100, at(row, "f")[2], EPS);
        String col = doc("<div id=f style='display:flex;flex-flow:column wrap;width:max-content;height:100px'>"
                + "<div style='flex:0 0 100px;min-height:0'><div style='width:75px'></div></div>"
                + "<div style='flex:0 0 100px;min-height:0;max-width:25px'><div style='width:50px'></div></div></div>");
        assertEquals(100, at(col, "f")[2], EPS);
    }

    /** {@code visibility: collapse} の項目は主軸の場所も gap も取らない（§4.4）。 */
    @Test
    void collapsedItemTakesNoSpace() {
        String html = doc("<div style='display:flex;column-gap:10px;width:300px'><div style='" + ITEM + "'></div>"
                + "<div id=x style='" + ITEM + ";visibility:collapse'></div><div id=b style='" + ITEM + "'></div></div>");
        assertEquals(70, at(html, "b")[0], EPS);
        assertFalse(SpikeView.dump(new SpikeView(html, 800, 600).frame()).toString().contains("#x"));
    }
}
