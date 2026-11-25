package com.nishiyu.enderframelib.core.layout.fragment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code position: fixed} の包含ブロックはビューポート（新経路では文書の根。スクロール 0 のときに一致）で、位置指定の祖先（relative・
 * absolute）は受け取らない（CSS 2.2 §10.1）。位置指定の祖先が absolute の子孫だけを置き、fixed は根まで渡すこと。
 */
class FixedPositionTest {

    @Test
    void fixedInsidePositionedAncestorsUsesTheViewport() {
        for (String display : new String[]{"block", "flex", "grid", "table"}) {
            SpikeView v = new SpikeView("<html><body style='margin:0'><div id=sc style='position:relative;display:" + display
                    + ";border:3px solid #888;padding:2px;margin:20px'><div id=ab style='position:absolute;left:5px;top:6px;width:10px;height:10px'></div>"
                    + "<div id=fx style='position:fixed;left:7px;top:8px;width:10px;height:10px'></div>"
                    + "<div style='position:absolute;left:30px;top:0'><div id=fx2 style='position:fixed;right:9px;bottom:4px;width:10px;height:10px'></div></div>"
                    + "</div></body></html>", 640, 480);
            LayoutFragment root = v.frame();
            float[] fx = SpikeView.find(root, "fx"), fx2 = SpikeView.find(root, "fx2"), ab = SpikeView.find(root, "ab");
            assertNotNull(fx, display);
            assertEquals(7f, fx[0], 0.01f, display + " fixed の x はビューポート基準");
            assertEquals(8f, fx[1], 0.01f, display + " fixed の y はビューポート基準");
            assertEquals(640f - 9f - 10f, fx2[0], 0.01f, display + " 入れ子の absolute の中の fixed もビューポート基準");
            assertEquals(480f - 4f - 10f, fx2[1], 0.01f, display);
            // absolute は位置指定の祖先（パディングの箱）が受け取る: 余白 20 + 枠線 3 + left / top
            assertEquals(20f + 3f + 5f, ab[0], 0.01f, display + " absolute は位置指定の祖先が包含ブロック");
            assertEquals(20f + 3f + 6f, ab[1], 0.01f, display);
            assertEquals(SpikeView.dump(v.scratch()), SpikeView.dump(root), display);
        }
    }

    /**
     * {@code transform}・{@code filter}・layout / paint containment の箱は、{@code position} に依らず absolute と fixed の両方の包含ブロック
     * （CSS Transforms 1 §2）。{@code transform} は描画だけの群にあるので、付け外しが増分のレイアウトへ届くこと（{@code Style.sameLayout}）。
     */
    @Test
    void transformFilterAndContainmentContainAbsoluteAndFixed() {
        for (String prop : new String[]{"transform:translateX(0)", "filter:blur(0)", "contain:paint", "contain:layout"}) {
            SpikeView v = new SpikeView("<html><body style='margin:0'><div id=t style='width:200px;height:50px;margin:20px'>"
                    + "<div id=ab style='position:absolute;left:5px;top:6px;width:5px;height:5px'></div>"
                    + "<div id=fx style='position:fixed;left:7px;top:8px;width:5px;height:5px'></div></div></body></html>", 320, 240);
            LayoutFragment before = v.frame();
            assertEquals(5f, SpikeView.find(before, "ab")[0], 0.01f, prop + " 付ける前は初期包含ブロック");
            ((com.nishiyu.enderframelib.core.dom.node.HtmlElement) v.byId("t")).setAttribute("style", "width:200px;height:50px;margin:20px;" + prop);
            LayoutFragment after = v.frame();
            assertEquals(20f + 5f, SpikeView.find(after, "ab")[0], 0.01f, prop + " absolute はこの箱が包含ブロック");
            assertEquals(20f + 6f, SpikeView.find(after, "ab")[1], 0.01f, prop);
            assertEquals(20f + 7f, SpikeView.find(after, "fx")[0], 0.01f, prop + " fixed もこの箱が包含ブロック");
            assertEquals(20f + 8f, SpikeView.find(after, "fx")[1], 0.01f, prop);
            assertEquals(SpikeView.dump(v.scratch()), SpikeView.dump(after), prop + " 増分と最初から作った結果が一致");
        }
    }

    /**
     * 両方のオフセットと大きさが決まった流れの外の箱は、auto の余白で余りを分けて中央へ寄る（CSS 2.2 §10.3.7・§10.6.4。UA の
     * {@code dialog} の中央寄せ）。高さが内在サイズの語（{@code fit-content}）なら上下のオフセットの間へ伸ばさず内容の高さにする。
     */
    @Test
    void autoMarginsCenterAnOutOfFlowBoxBetweenItsInsets() {
        SpikeView v = new SpikeView("<html><body style='margin:0'><div style='position:relative;width:300px;height:200px'>"
                + "<div id=a style='position:absolute;inset:0;margin:auto;width:100px;height:40px'></div>"
                + "<div id=b style='position:absolute;left:0;right:0;top:0;bottom:0;margin:auto;width:fit-content;height:fit-content'><div style='width:60px;height:30px'></div></div>"
                + "<div id=c style='position:absolute;left:0;right:0;top:0;bottom:0;margin:auto auto auto 10px;width:100px;height:40px'></div>"
                + "<div id=d style='position:absolute;left:0;right:0;top:0;bottom:0'></div></div></body></html>", 640, 480);
        LayoutFragment root = v.frame();
        float[] a = SpikeView.find(root, "a"), b = SpikeView.find(root, "b"), c = SpikeView.find(root, "c"), d = SpikeView.find(root, "d");
        assertEquals(100f, a[0], 0.01f, "左右の auto の余白で中央");
        assertEquals(80f, a[1], 0.01f, "上下の auto の余白で中央");
        assertEquals(120f, b[0], 0.01f, "fit-content の幅でも中央");
        assertEquals(85f, b[1], 0.01f, "fit-content の高さは内容の高さで、中央");
        assertEquals(30f, b[3], 0.01f);
        assertEquals(10f, c[0], 0.01f, "左だけ指定なら右の auto が余りを取る");
        assertEquals(80f, c[1], 0.01f);
        assertEquals(300f, d[2], 0.01f, "大きさが auto ならオフセットの間いっぱい");
        assertEquals(200f, d[3], 0.01f);
        assertEquals(SpikeView.dump(v.scratch()), SpikeView.dump(root));
    }
}
