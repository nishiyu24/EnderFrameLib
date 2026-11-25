package com.nishiyu.enderframelib.core.layout.fragment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * M2b-1: 同じ BFC の float と兄弟の箱（{@link FloatAvoid}・{@link Floats}）。独立した整形コンテキストを作らないブロックは float に
 * 重なって置かれ中の行が float を避け（CSS 2.2 §9.5）、子の中の float は後ろの兄弟にも掛かり、独立した整形コンテキストの根は
 * float の横に縮むか入らなければ下がる。期待値は固定の大きさの箱と行の高さから手で求めた数（行の高さ 20、文字は 1 行に収まる短さ）。
 */
class FloatIntrusionTest {
    private static final float EPS = 0.02f;
    private static final String HEAD = "<html><head><style>body{margin:0;font-size:16px;line-height:20px} p{margin:0}"
            + " .f{float:left;width:100px;height:50px} .r{float:right;width:60px;height:30px}</style></head><body>";

    private static LayoutFragment root(String body) {
        return new SpikeView(HEAD + body + "</body></html>", 800, 600).frame();
    }

    private static LayoutFragment frag(LayoutFragment r, String id) {
        LayoutFragment f = InlineTextTest.frag(r, id);
        assertNotNull(f, id);
        return f;
    }

    @Test
    void linesOfAFollowingSiblingAvoidTheFloatWhileTheBoxOverlapsIt() {
        LayoutFragment r = root("<div style='width:300px'><div class=f></div><p id=p>a<br>b<br>c<br>d</p></div>");
        LayoutFragment p = frag(r, "p");
        assertEquals(300, p.width, EPS); // 箱は float に重なる
        assertEquals(100, p.lines[0].x(), EPS); // 0〜20, 20〜40, 40〜60 の行は float（高さ 50）を避ける
        assertEquals(100, p.lines[2].x(), EPS);
        assertEquals(0, p.lines[3].x(), EPS); // 60〜 は float の下
        assertEquals(80, p.height, EPS); // float は p の高さに入らない
    }

    @Test
    void nestedBlocksTranslateTheExclusionsAndRightFloatsNarrowTheLine() {
        LayoutFragment r = root("<div style='width:300px'><div class=f></div><div class=r></div>"
                + "<div style='margin-left:20px;padding-left:5px'><p id=p>a<br>b</p></div></div>");
        LayoutFragment p = frag(r, "p");
        assertEquals(100 - 25, p.lines[0].x(), EPS); // p の内容の左端は 25: float の右端 100 は p の中で 75
        assertEquals(100 - 25, p.lines[1].x(), EPS);
    }

    @Test
    void marginCollapsedTopIsUsedForTheExclusions() {
        // 最初の p は 0〜20、float は 20〜70。次の div の中の p の上の余白 30 は div を突き抜けて相殺され、div と p の上端は 50
        // （div の上の余白 0 で見積もった 20 と違う: 正しい上端で整形し直す）。1 行目（50〜70）だけ float を避ける
        LayoutFragment r = root("<div style='width:300px'><p>first</p><div class=f></div><div><p id=p style='margin-top:30px'>a<br>b</p></div></div>");
        LayoutFragment p = frag(r, "p");
        assertEquals(100, p.lines[0].x(), EPS);
        assertEquals(0, p.lines[1].x(), EPS);
    }

    @Test
    void floatInsideAChildAffectsTheFollowingSiblings() {
        LayoutFragment r = root("<div style='width:300px'><p><span class=f></span>x</p><p id=q>a<br>b<br>c</p></div>");
        LayoutFragment q = frag(r, "q");
        // 1 つ目の p は高さ 20（float は外へはみ出す）。q は 20〜: 20〜40・40〜60（float の下端 50 に掛かる）を避ける
        assertEquals(100, q.lines[0].x(), EPS);
        assertEquals(100, q.lines[1].x(), EPS);
        assertEquals(0, q.lines[2].x(), EPS);
    }

    @Test
    void formattingRootsShrinkBesideFloatsOrDropBelowThem() {
        LayoutFragment r = root("<div style='width:300px'><div class=f></div><div id=b style='overflow:hidden;height:10px'></div>"
                + "<div id=c style='overflow:hidden;width:250px;height:10px'></div></div>");
        float[] b = SpikeView.find(r, "b");
        assertEquals(100, b[0], EPS); // auto の幅は float の右から
        assertEquals(200, b[2], EPS);
        assertEquals(0, b[1], EPS);
        float[] c = SpikeView.find(r, "c");
        assertEquals(0, c[0], EPS); // 幅 250 は残り 200 に入らない: float の下端 50 まで下がる
        assertEquals(50, c[1], EPS);
    }

    @Test
    void formattingRootContainsDescendantFloats() {
        LayoutFragment r = root("<div id=d style='overflow:hidden;width:300px'><p><span class=f></span>x</p></div>");
        assertEquals(50, frag(r, "d").height, EPS);
    }

    @Test
    void lineThatDoesNotFitBesideFloatsDropsBelowThem() {
        // 残り幅 50 に最初の語（16 文字の w）が入らない: 行は float の下端 40 まで下がり、左端から始まる（CSS 2.2 §9.5.1）
        LayoutFragment r = root("<div style='width:300px'><div style='float:left;width:250px;height:40px'></div><p id=p>wwwwwwwwwwwwwwww</p></div>");
        LayoutFragment p = frag(r, "p");
        assertEquals(40, p.lines[0].y(), EPS);
        assertEquals(0, p.lines[0].x(), EPS);
        assertEquals(60, p.height, EPS);
    }
}
