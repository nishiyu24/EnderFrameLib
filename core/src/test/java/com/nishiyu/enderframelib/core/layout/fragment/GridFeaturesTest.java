package com.nishiyu.enderframelib.core.layout.fragment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * M2b-3 のグリッド（計画書 §3.4.1）: 新経路の {@link GridFlow}（配置 {@link GridPlacement}・トラック {@link GridTracks}）が CSS Grid の
 * 規則どおりに置くこと。期待値は仕様から手で求めた数（旧経路との比較は {@link LegacyParityTest#gridMatchesLegacy}）。
 * 文字を含まない固定の大きさの箱だけで組むので、書体に依らない。
 */
class GridFeaturesTest {
    private static final float EPS = 0.02f;

    private static float[] at(String html, String id) {
        float[] r = SpikeView.find(new SpikeView(html, 800, 600).frame(), id);
        assertNotNull(r, id);
        return r;
    }

    private static String doc(String body) {
        return "<html><body style='margin:0'>" + body + "</body></html>";
    }

    /** 固定長・fr・gap と自動配置（行優先）。stretch の項目は領域いっぱい。 */
    @Test
    void fixedAndFlexibleTracksWithAutoPlacement() {
        String html = doc("<div style='display:grid;width:300px;grid-template-columns:100px 1fr 2fr;grid-auto-rows:40px;gap:10px 20px'>"
                + "<div id=a></div><div id=b></div><div id=c></div><div id=d></div></div>");
        // fr の空間 300 - 100 - 40 = 160 → 1fr 53.33・2fr 106.67
        assertEquals(120, at(html, "b")[0], EPS);
        assertEquals(53.333f, at(html, "b")[2], 0.05f);
        assertEquals(193.333f, at(html, "c")[0], 0.05f);
        assertEquals(0, at(html, "d")[0], EPS);
        assertEquals(50, at(html, "d")[1], EPS);
        assertEquals(40, at(html, "d")[3], EPS);
    }

    /** 線の番号・{@code span}・負の番号・{@code grid-template-areas} の名前。 */
    @Test
    void explicitPlacementAndAreas() {
        String html = doc("<div style='display:grid;grid-template-columns:50px 60px 70px;grid-template-rows:20px 30px;"
                + "grid-template-areas:\"h h s\" \"m m s\"'>"
                + "<div id=s style='grid-area:s'></div><div id=h style='grid-area:h'></div>"
                + "<div id=x style='grid-column:-2 / span 1;grid-row:2'></div></div>");
        assertEquals(110, at(html, "s")[0], EPS);
        assertEquals(50, at(html, "s")[3], EPS);
        assertEquals(110, at(html, "h")[2], EPS);
        assertEquals(110, at(html, "x")[0], EPS);
        assertEquals(20, at(html, "x")[1], EPS);
    }

    /** {@code auto} トラックは内容の寄与で決まり、{@code justify-content: center} はトラックの塊を中央へ寄せる。 */
    @Test
    void intrinsicTracksAndContentDistribution() {
        String html = doc("<div style='display:grid;width:400px;height:100px;grid-template-columns:auto auto;justify-content:center;"
                + "align-content:end;grid-template-rows:30px'>"
                + "<div id=a style='width:50px'></div><div id=b style='width:70px'></div></div>");
        assertEquals(140, at(html, "a")[0], EPS);
        assertEquals(190, at(html, "b")[0], EPS);
        assertEquals(70, at(html, "a")[1], EPS);
    }

    /** {@code justify-self} / {@code align-self} と auto 余白（領域の中で揃える）。 */
    @Test
    void selfAlignmentAndAutoMargins() {
        String html = doc("<div style='display:grid;grid-template-columns:100px;grid-template-rows:100px'>"
                + "<div id=a style='grid-area:1/1;width:20px;height:20px;justify-self:end;align-self:center'></div>"
                + "<div id=b style='grid-area:1/1;width:20px;height:20px;margin:auto'></div></div>");
        assertEquals(80, at(html, "a")[0], EPS);
        assertEquals(40, at(html, "a")[1], EPS);
        assertEquals(40, at(html, "b")[0], EPS);
        assertEquals(40, at(html, "b")[1], EPS);
    }

    /** {@code repeat(auto-fill)} は入るだけ繰り返し、{@code auto-fit} は空のトラックを潰す。 */
    @Test
    void autoRepeat() {
        String fill = doc("<div style='display:grid;width:230px;grid-template-columns:repeat(auto-fill, 50px);gap:10px'>"
                + "<div id=a></div><div id=b></div><div id=c></div><div id=d></div><div id=e></div></div>");
        assertEquals(180, at(fill, "d")[0], EPS); // 4 本入る（4×50 + 3×10 = 230）
        assertEquals(0, at(fill, "e")[0], EPS);
        String fit = doc("<div style='display:grid;width:230px;grid-template-columns:repeat(auto-fit, 50px);justify-content:end'>"
                + "<div id=a></div></div>");
        assertEquals(180, at(fit, "a")[0], EPS); // 空の 3 本を潰して 1 本だけを右へ
    }

    /**
     * 包含ブロックがグリッドの abspos の子は、線が示す領域が包含ブロック（§10.1）。auto の線は padding の箱の端（§9）なので、
     * {@code grid-column: 2} は 2 本目の線から右の端まで。
     */
    @Test
    void absoluteChildUsesGridArea() {
        String html = doc("<div style='display:grid;position:relative;grid-template-columns:100px 200px;grid-template-rows:50px 50px'>"
                + "<div id=a style='position:absolute;grid-column:2 / 3;grid-row:2;right:10px;width:30px;height:10px'></div>"
                + "<div id=b style='position:absolute;grid-column:2;right:10px;width:30px;height:10px'></div></div>");
        assertEquals(260, at(html, "a")[0], EPS);
        assertEquals(50, at(html, "a")[1], EPS);
        assertEquals(760, at(html, "b")[0], EPS);
    }

    /** 伸ばした項目は高さが確定し、中の相対位置の % の {@code top} の基準になる（WPT css-grid/relative-grandchild）。 */
    @Test
    void stretchedItemIsDefiniteForPercentInsets() {
        String html = doc("<div style='display:grid;width:100px;height:100px'><div><div id=r style='position:relative;height:100px;top:-50%'></div>"
                + "</div></div>");
        assertEquals(-50, at(html, "r")[1], EPS);
    }

    /** 内在サイズ: max-content は列を不定の空間で解いた和、min-content は min-content の寄与で解いた和。 */
    @Test
    void intrinsicSizes() {
        String html = doc("<div style='display:inline-grid;grid-template-columns:auto 30px;column-gap:5px' id=g>"
                + "<div style='width:40px'></div><div></div></div>");
        assertEquals(75, at(html, "g")[2], EPS);
    }

    /**
     * 文書の根は初期包含ブロック（ビューポート）の高さを子の % の高さの基準に渡す（CSS 2 §10.5）。{@code html, body { height: 100% }}
     * の実文書（グリッドの容器を高さいっぱいに置く）で、幾何の一致の掃引（{@link GeometrySweepTest}）が見つけた。
     */
    @Test
    void rootPercentHeightUsesTheViewport() {
        String html = "<html style='height:100%'><body id=b style='margin:0;height:100%'><div id=g style='display:grid;height:50%'></div></body></html>";
        assertEquals(600, at(html, "b")[3], EPS);
        assertEquals(300, at(html, "g")[3], EPS);
    }
}
