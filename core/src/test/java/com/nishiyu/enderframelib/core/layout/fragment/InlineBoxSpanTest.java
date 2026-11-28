package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontStyle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2b-1: インライン要素の行ごとの箱（{@link LineFragment.Span}。背景・枠線を描く断片。旧 {@code LineBuilder} の開いた箱の移植）。
 * 横は開始端（左の margin・border・padding）と終了端の間、縦は自分の書体の内容領域に上下の padding と border を足した範囲
 * （CSS 2.2 §10.6.1）。行をまたぐ箱は最初の行だけが開始端・最後の行だけが終了端を持ち（{@code box-decoration-break: slice}）、
 * 中身を置く前に折り返した箱は開始端ごと次の行へ移る。直後に閉じる箱の終了端は最後の語と一緒に行に入る。
 * 期待値は同じ書体（ヘッドレスの {@link FontFace#FALLBACK}）の {@code advance} と ascent / descent から手で求める。
 */
class InlineBoxSpanTest {
    private static final float EPS = 0.02f;
    private static final String HEAD = "<html><head><style>body{margin:0;font-size:16px;line-height:20px} div{margin:0}</style></head><body>";

    private static LayoutFragment root(String body) {
        return new SpikeView(HEAD + body + "</body></html>", 800, 600).frame();
    }

    private static FontStyle fs(LayoutFragment root, String id) {
        LayoutFragment f = InlineTextTest.frag(root, id);
        assertNotNull(f, id);
        return f.box.style.facts.fontStyle;
    }

    private static float adv(String s, FontStyle fs) {
        return FontFace.FALLBACK.advance(s, fs);
    }

    @Test
    void decoratedSpanCoversItsEdgesAndContentArea() {
        LayoutFragment r = root("<div id=d>aa <span id=s style='padding:0 5px;border:2px solid'>bb</span> cc</div>");
        FontStyle fs = fs(r, "d");
        List<float[]> sp = SpikeView.spans(r, "s");
        assertEquals(1, sp.size());
        float[] s = sp.get(0);
        // 箱の左端は前の空白の後（空白は箱の外の文字に属する）
        assertEquals(adv("aa ", fs), s[0], EPS);
        assertEquals(2 + 5 + adv("bb", fs) + 5 + 2, s[2], EPS);
        float a = FontFace.FALLBACK.ascent(fs), d = FontFace.FALLBACK.descent(fs);
        float base = InlineFlow.leadingAbove(20, a, d) + a; // 行の高さ 20 の中のベースライン
        assertEquals(base - a - 2, s[1], EPS);
        assertEquals(2 + a + d + 2, s[3], EPS);
        assertEquals(1, s[4]);
        assertEquals(1, s[5]);
    }

    @Test
    void emptyDecoratedSpanTakesSpaceAndLeadingSpacesCollapseThroughIt() {
        // WPT line-edge-white-space-collapse-002: 行頭の空白は空の箱をまたいで畳まれ、K は枠線の直後
        LayoutFragment r = root("<div id=d><span>   <span id=e style='border-left:30px solid'>  </span>  K</span></div>");
        List<float[]> sp = SpikeView.spans(r, "e");
        assertEquals(1, sp.size());
        assertEquals(0, sp.get(0)[0], EPS);
        assertEquals(30, sp.get(0)[2], EPS);
        LineFragment l = InlineTextTest.frag(r, "d").lines[0];
        assertEquals("K", l.pieces()[0].string());
        assertEquals(30, l.pieces()[0].x(), EPS);
    }

    @Test
    void spacesCollapseAcrossTheOpeningEdge() {
        // "are <strong> green": 2 つの空白は要素の境目をまたいで 1 つに畳まれ、残る空白は箱の外（前）に置く
        LayoutFragment r = root("<div id=d>are <strong id=s style='border-left:3px solid'> green</strong></div>");
        FontStyle fs = fs(r, "d");
        float[] s = SpikeView.spans(r, "s").get(0);
        assertEquals(adv("are ", fs), s[0], EPS);
        LineFragment l = InlineTextTest.frag(r, "d").lines[0];
        assertEquals(adv("are ", fs) + 3, l.pieces()[1].x(), EPS);
    }

    @Test
    void emptyDecoratedSpanAloneMakesALine() {
        LayoutFragment r = root("<div id=d><span style='padding:0 4px;border:1px solid'></span></div><div id=z><span></span></div>");
        assertEquals(20, InlineTextTest.frag(r, "d").height, EPS);
        assertEquals(0, InlineTextTest.frag(r, "z").height, EPS); // 余白も枠線も無い空の箱は行を作らない
    }

    @Test
    void spanAcrossLinesSlicesItsEdges() {
        LayoutFragment probe = root("<div id=d>x</div>");
        FontStyle fs = fs(probe, "d");
        float w = adv("aaa bbb", fs) - 1; // "aaa bbb" は収まらず、"aaa" は収まる
        LayoutFragment r = root("<div style='width:" + w + "px'><span id=s style='border:2px solid'>aaa bbb</span></div>");
        List<float[]> sp = SpikeView.spans(r, "s");
        assertEquals(2, sp.size());
        assertEquals(0, sp.get(0)[0], EPS);
        assertEquals(2 + adv("aaa", fs), sp.get(0)[2], EPS); // 1 行目: 開始端だけ（後ろの空白は数えない）
        assertEquals(1, sp.get(0)[4]);
        assertEquals(0, sp.get(0)[5]);
        assertEquals(0, sp.get(1)[0], EPS);
        assertEquals(adv("bbb", fs) + 2, sp.get(1)[2], EPS); // 2 行目: 終了端だけ
        assertEquals(0, sp.get(1)[4]);
        assertEquals(1, sp.get(1)[5]);
        assertEquals(20, sp.get(1)[1] - sp.get(0)[1], EPS);
    }

    @Test
    void boxWithoutContentMovesToTheNextLineWithItsStartEdge() {
        LayoutFragment probe = root("<div id=d>x</div>");
        FontStyle fs = fs(probe, "d");
        float w = adv("aaa ", fs) + 15 + adv("bbbb", fs) - 1;
        LayoutFragment r = root("<div id=d style='width:" + w + "px'>aaa <span id=m style='margin-left:10px;border-left:5px solid'>bbbb</span></div>");
        List<float[]> sp = SpikeView.spans(r, "m");
        assertEquals(1, sp.size()); // 1 行目には出ない
        assertEquals(10, sp.get(0)[0], EPS);
        assertEquals(5 + adv("bbbb", fs), sp.get(0)[2], EPS);
        assertEquals(1, sp.get(0)[4]);
        LineFragment first = InlineTextTest.frag(r, "d").lines[0];
        assertEquals(adv("aaa", fs), first.width(), EPS); // 移した箱の余白と後ろの空白は 1 行目の幅に入らない
    }

    @Test
    void closingEdgeStaysWithTheLastWord() {
        LayoutFragment probe = root("<div id=d>x</div>");
        FontStyle fs = fs(probe, "d");
        float w = adv("aaa bb", fs) + 10; // "bb" は収まるが、直後に閉じる 20px の枠線とは収まらない
        LayoutFragment r = root("<div id=d style='width:" + w + "px'>aaa <span id=c style='border-right:20px solid'>bb</span></div>");
        LayoutFragment d = InlineTextTest.frag(r, "d");
        assertEquals(2, d.lines.length);
        assertEquals("bb", d.lines[1].pieces()[0].string());
    }

    @Test
    void justifiedLineShiftsSpanEdges() {
        LayoutFragment probe = root("<div id=d>x</div>");
        FontStyle fs = fs(probe, "d");
        float w = adv("aa bb cc", fs) + 40;
        LayoutFragment r = root("<div style='width:" + w + "px;text-align:justify'>aa <span id=s style='border:1px solid'>bb</span> cc dddddddddddddddddd</div>");
        float[] s = SpikeView.spans(r, "s").get(0);
        // 余り 40 − 2（箱の左右の枠線）を機会 2 つ（aa と bb の間、bb と cc の間）で等分して各 19px。箱は最初の機会の後ろなので 19 ずれ、幅は変わらない
        assertEquals(adv("aa ", fs) + 19, s[0], EPS);
        assertEquals(1 + adv("bb", fs) + 1, s[2], EPS);
        assertTrue(s[4] == 1 && s[5] == 1);
    }
}
