package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新経路の {@code ::first-line}（M2c ④）: 書体の指定は 1 行目の語の幅と折り返しに効き、1 行目を閉じたら 2 行目以降は節の本来の書体で
 * 測る。規則の付け外しは増分のレイアウトへ届き（全再計算と一致）、色だけの {@code ::first-line} はレイアウトの入力を変えない。
 */
class FirstLineFlowTest {
    private static final String DOC = "<html><style>body{margin:0} p{margin:0;width:110px;font:12px/16px monospace}"
            + " .big::first-line{font-size:28px} .red::first-line{color:red}</style><body><p id=t>WWWW tail</p></body></html>";

    private static List<String> lines(SpikeView v) {
        return SpikeView.dump(v.frame()).stream().filter(l -> l.trim().startsWith("|")).map(String::trim).toList();
    }

    @Test
    void firstLineFontSizeWrapsTheFirstLineAndTheRestUsesTheNormalFont() {
        SpikeView v = new SpikeView(DOC, 640, 480);
        List<String> plain = lines(v);
        assertEquals(1, plain.size(), "規則なしでは 1 行に収まる: " + plain);
        HtmlElement p = (HtmlElement) v.byId("t");
        p.setAttribute("class", "big");
        List<String> big = lines(v);
        assertEquals(2, big.size(), "1 行目の 28px で折り返す: " + big);
        assertTrue(big.get(0).contains("[WWWW@") && big.get(1).contains("[tail@"), big.toString());
        float tailW = Float.parseFloat(big.get(1).split(" ")[2].split("x")[0]);
        List<String> ref = lines(new SpikeView(DOC.replace("WWWW tail", "tail"), 640, 480)); // 12px の "tail"
        float plainTail = Float.parseFloat(ref.get(0).split(" ")[2].split("x")[0]);
        assertEquals(plainTail, tailW, 0.01f, "2 行目の語は 12px で測る（1 行目の書体で測った幅のままにしない）");
        assertEquals(SpikeView.dump(v.scratch()), SpikeView.dump(v.frame()), "付けた増分が全再計算と一致");
        BoxNode layout = v.boxRoot.layout;
        p.setAttribute("class", "big red");
        v.frame();
        assertSame(layout, v.boxRoot.layout, "色だけの ::first-line はレイアウトの入力を変えない");
        p.setAttribute("class", "");
        assertEquals(plain, lines(v), "外した増分は元の 1 行");
        assertEquals(SpikeView.dump(v.scratch()), SpikeView.dump(v.frame()));
    }
}
