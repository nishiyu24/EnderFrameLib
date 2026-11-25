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
