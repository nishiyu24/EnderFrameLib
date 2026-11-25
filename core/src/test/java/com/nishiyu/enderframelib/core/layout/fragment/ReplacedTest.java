package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2b-1 の置換要素とフォーム部品（計画書 §3.4.1）: 新経路の {@link ReplacedFlow} が内在の大きさと比から箱の大きさを決め、
 * 大きさの入力（属性・選択肢）の変化が増分でも届くこと（{@link com.nishiyu.enderframelib.core.box.ReplacedInput}）。
 */
class ReplacedTest {
    private static final float EPS = 0.02f;
    private static final String RESET = "margin:0;padding:0;border:0;";

    private static float[] at(SpikeView v, String id) {
        float[] r = SpikeView.find(v.frame(), id);
        assertNotNull(r, id);
        return r;
    }

    private static float[] at(String body, String id) {
        return at(new SpikeView("<html><body style='margin:0'>" + body + "</body></html>", 800, 600), id);
    }

    /** canvas は属性の大きさ（既定 300×150）。幅だけ指定すると比で高さ、高さだけなら比で幅（CSS 2 §10.3.2・§10.6.2）。 */
    @Test
    void canvasIntrinsicSizeAndRatio() {
        assertEquals(300, at("<canvas id=c style='" + RESET + "'></canvas>", "c")[2], EPS);
        assertEquals(150, at("<canvas id=c style='" + RESET + "'></canvas>", "c")[3], EPS);
        float[] w = at("<canvas id=c width=200 height=100 style='" + RESET + "width:100px'></canvas>", "c");
        assertEquals(50, w[3], EPS);
        float[] h = at("<canvas id=c width=200 height=100 style='" + RESET + "height:30px'></canvas>", "c");
        assertEquals(60, h[2], EPS);
        float[] pad = at("<canvas id=c width=200 height=100 style='margin:0;border:0;padding:5px'></canvas>", "c");
        assertEquals(210, pad[2], EPS);
        assertEquals(110, pad[3], EPS);
    }

    /** svg は viewBox から、大きさの分からない img は既定の正方形（読み込み中のリモート画像は大きめ）。 */
    @Test
    void svgAndFallbackImages() {
        float[] s = at("<svg id=s viewBox='0 0 40 20' width=80 style='" + RESET + "'></svg>", "s");
        assertEquals(80, s[2], EPS);
        assertEquals(40, s[3], EPS);
        assertEquals(16, at("<img id=i style='" + RESET + "'>", "i")[2], EPS);
        assertEquals(32, at("<img id=i src='https://example.invalid/a.png' style='" + RESET + "'>", "i")[2], EPS);
    }

    /** 部品: 文字の入力欄の既定の幅 90（{@code size} で 8 px 倍）、チェック類の内在の大きさは 16（UA の指定は 13px）。 */
    @Test
    void videoAndIframeUseTheDefaultObjectSize() {
        // 既定の物体の大きさ 300 x 150（中身のフォールバックは描かない）。iframe は UA の枠線 2px
        float[] v = at("<video id=v><p>fallback text</p></video>", "v");
        assertEquals(300, v[2], EPS);
        assertEquals(150, v[3], EPS);
        float[] f = at("<iframe id=f></iframe>", "f");
        assertEquals(304, f[2], EPS);
        assertEquals(154, f[3], EPS);
        assertEquals(640, at("<video id=v width=640></video>", "v")[2], EPS); // 属性の幅
        // 幅が % の置換要素は縮められる: min-content の寄与は 0（CSS Sizing 3 §5.2.2。実文書 media_test のグリッドの列）
        assertEquals(0, at("<div id=d style='width:min-content'><video style='width:100%'></video></div>", "d")[2], EPS);
    }

    @Test
    void controlDefaultSizes() {
        assertEquals(90, at("<input id=i style='" + RESET + "'>", "i")[2], EPS);
        assertEquals(80, at("<input id=i size=10 style='" + RESET + "'>", "i")[2], EPS);
        float[] cb = at("<input id=i type=checkbox style='" + RESET + "width:auto;height:auto'>", "i"); // UA の 13px を外した内在の大きさ
        assertEquals(16, cb[2], EPS);
        assertEquals(16, cb[3], EPS);
        float shortSel = at("<select id=s style='" + RESET + "'><option>a</option></select>", "s")[2];
        float longSel = at("<select id=s style='" + RESET + "'><option>a</option><option>a much longer label</option></select>", "s")[2];
        assertTrue(longSel > shortSel + 20, shortSel + " / " + longSel);
    }

    /**
     * 行の中の原子の % の高さは、容器の確定した内容の高さが基準（CSS 2 §10.5。ブロックと混在する流れの匿名の行でも同じ）。容器の高さが
     * 不定なら auto。実文書 item_block_test の {@code height: 100%} の img で検出（幾何の一致の掃引）。
     */
    @Test
    void percentHeightOfAnAtomInALineUsesTheDefiniteContainerHeight() {
        assertEquals(45, at("<div style='height:90px'><canvas id=c style='" + RESET + "height:50%'></canvas></div>", "c")[3], EPS);
        assertEquals(90, at("<div style='width:200px;height:90px'><span id=s style='display:inline-block;width:100%;height:100%'></span></div>",
                "s")[3], EPS);
        assertEquals(45, at("<div style='height:90px'><div>block</div><canvas id=c style='" + RESET + "height:50%'></canvas></div>", "c")[3], EPS);
        assertEquals(150, at("<div><canvas id=c style='" + RESET + "height:50%'></canvas></div>", "c")[3], EPS); // 不定: 内在の 150
    }

    /**
     * 部品の見た目のチェックボックス・ラジオボタンのベースラインは枠線の箱の下端（下の余白を含めない。WebKit / Blink と旧経路と同じ）。
     * 同じ余白の inline-block（下の余白の端がベースライン）より下の余白の分だけ下に置く。{@code appearance: none} なら inline-block と同じ。
     */
    @Test
    void nativeCheckboxSitsOnTheBaselineWithItsBorderBox() {
        SpikeView v = new SpikeView("<html><body style='margin:0;font-size:16px'><div>x <span id=s style='display:inline-block;width:13px;"
                + "height:13px;margin:3px 3px 3px 4px'></span> <input id=c type=checkbox> <input id=r type=radio> "
                + "<input id=n type=checkbox style='appearance:none;width:13px;height:13px'></div></body></html>", 800, 600);
        float s = at(v, "s")[1];
        assertEquals(s + 3, at(v, "c")[1], EPS);
        assertEquals(s + 3, at(v, "r")[1], EPS);
        assertEquals(s, at(v, "n")[1], EPS);
    }

    /** ブロック水準の置換要素は auto 余白で中央へ。行の中では原子として並ぶ。flex の項目の内在サイズにもなる。 */
    @Test
    void layoutContexts() {
        assertEquals(250, at("<canvas id=c style='" + RESET + "display:block;margin:0 auto'></canvas>", "c")[0], EPS);
        float[] b = at("<div style='line-height:0;font-size:0'><canvas width=10 height=10 style='" + RESET + "'></canvas>"
                + "<canvas id=b width=20 height=10 style='" + RESET + "'></canvas></div>", "b");
        assertEquals(10, b[0], EPS);
        float[] f = at("<div style='display:flex;width:100px'><canvas id=c width=200 height=50 style='" + RESET + "min-width:0'></canvas>"
                + "<div style='width:50px;flex-shrink:0'></div></div>", "c");
        assertEquals(50, f[2], EPS); // 縮んで 50（基準の大きさは内在の幅 200）
    }

    /** 属性だけを変えても（スタイルは同じ）新しい大きさになる（入力の写しが箱を作り直す。前回の断片を使い回さない）。 */
    @Test
    void attributeOnlyChangeIsSeenIncrementally() {
        SpikeView v = new SpikeView("<html><body style='margin:0'><canvas id=c width=100 height=40 style='" + RESET + "'></canvas>"
                + "<select id=s style='" + RESET + "'><option id=o>a</option></select></body></html>", 800, 600);
        assertEquals(100, at(v, "c")[2], EPS);
        float sel = at(v, "s")[2];
        ((HtmlElement) v.byId("c")).setAttribute("width", "250");
        assertEquals(250, at(v, "c")[2], EPS);
        ((HtmlElement) v.byId("o")).updateTextContent("a far longer option label");
        assertTrue(at(v, "s")[2] > sel + 20);
        assertEquals(SpikeView.dump(v.layout.root()), SpikeView.dump(v.scratch()));
    }
}
