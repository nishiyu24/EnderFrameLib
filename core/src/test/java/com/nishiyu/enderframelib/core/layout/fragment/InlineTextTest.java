package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2b-1 のインライン・文字の完成（計画書 §3.4.1）: 新経路の行組み（{@link InlineLineBuilder}）と内在サイズ（{@link InlineMeasure}）が、
 * 文字の規則（タブ・節ごとの {@code white-space}・{@code pre-wrap} / {@code break-spaces}・{@code letter-spacing}・両端揃え・
 * {@code direction}・{@code text-transform}・内在サイズの語・{@code keep-all} と {@code <wbr>}・ぶら下がる空白）を守ること。
 * 期待値は同じ書体の {@code advance} から求める（書体はヘッドレスの {@link FontFace#FALLBACK}）。
 */
class InlineTextTest {
    private static final float EPS = 0.02f;

    /** {@code id} の要素の断片（行の原子の中も探す）。 */
    static LayoutFragment frag(LayoutFragment f, String id) {
        if (f.box != null && id.equals(f.box.node.attrs.get("id"))) return f;
        if (f.lines != null) {
            for (LineFragment l : f.lines) {
                for (PlacedFragment a : l.atoms()) {
                    LayoutFragment r = frag(a.frag(), id);
                    if (r != null) return r;
                }
            }
        }
        if (f.flow != null) {
            for (FlowChunk c : f.flow.chunks) {
                for (LayoutFragment ch : c.frags) {
                    LayoutFragment r = frag(ch, id);
                    if (r != null) return r;
                }
            }
        }
        return null;
    }

    private static LayoutFragment layout(String body, String id) {
        SpikeView v = new SpikeView("<html><head><style>body{margin:0;font-size:16px}</style></head><body>" + body + "</body></html>", 800, 600);
        LayoutFragment f = frag(v.frame(), id);
        assertNotNull(f, id);
        return f;
    }

    private static float adv(String s, Style st) {
        return FontFace.FALLBACK.advance(s, st.facts.fontStyle);
    }

    private static String lineText(LineFragment l) {
        StringBuilder sb = new StringBuilder();
        for (LineFragment.Piece p : l.pieces()) sb.append(p.string());
        return sb.toString();
    }

    private static Style textStyle(LayoutFragment f) {
        return f.lines[0].pieces()[0].text().style;
    }

    @Test
    void tabsAdvanceToTabSizeStops() {
        LayoutFragment f = layout("<div id=t style=\"white-space:pre;tab-size:4\">ab\tc\t\td</div>", "t");
        Style st = textStyle(f);
        float sp = adv(" ", st), tab = 4 * sp;
        LineFragment.Piece[] p = f.lines[0].pieces();
        assertEquals(3, p.length, "タブは文字片に含めない（片はタブで切れる）");
        float c = InlineFlow.tabStop(adv("ab", st), tab, sp);
        assertEquals(c, p[1].x(), EPS, "c は ab の後の次のタブ位置");
        float d = InlineFlow.tabStop(InlineFlow.tabStop(c + adv("c", st), tab, sp), tab, sp);
        assertEquals(d, p[2].x(), EPS, "連続するタブはそれぞれ次の位置へ");
    }

    @Test
    void tabSizeAcceptsLengths() {
        LayoutFragment f = layout("<div id=t style=\"white-space:pre;tab-size:50px\">a\tb</div>", "t");
        assertEquals(50f, f.lines[0].pieces()[1].x(), EPS);
    }

    @Test
    void collapsedSpaceDecidesBreakByItsOwnWhiteSpace() {
        // nowrap の容器でも、normal の span の空白の後は折り返せる（CSS Text 3 §5: 機会はその空白の white-space が決める）
        String w = "width:60px";
        LayoutFragment wraps = layout("<div id=t style=\"" + w + ";white-space:nowrap\"><span style=\"white-space:normal\">"
                + "<span style=\"white-space:nowrap\">12345</span> </span>67890</div>", "t");
        assertEquals(2, wraps.lines.length);
        LayoutFragment stays = layout("<div id=t style=\"" + w + ";white-space:nowrap\">12345 67890</div>", "t");
        assertEquals(1, stays.lines.length, "nowrap の空白の後は折り返さない");
        // normal の容器の中の nowrap の span: 後ろの normal の空白で折り返す
        LayoutFragment after = layout("<div id=t style=\"" + w + "\"><span style=\"white-space:nowrap\">12345</span> 67890</div>", "t");
        assertEquals(2, after.lines.length);
    }

    @Test
    void elementBoundaryInsideAWordIsNotABreak() {
        LayoutFragment f = layout("<div id=t style=\"width:10px\">ab<span>cd</span>ef</div>", "t");
        assertEquals(1, f.lines.length, "h<span></span>ighway は 1 語");
    }

    @Test
    void preWrapWrapsAndHangsTrailingSpaces() {
        LayoutFragment probe = layout("<div id=t style=\"white-space:pre-wrap\">aaa bbb</div>", "t");
        float aaa = adv("aaa", textStyle(probe));
        LayoutFragment f = layout("<div id=t style=\"white-space:pre-wrap;width:" + aaa + "px\">aaa bbb</div>", "t");
        assertEquals(2, f.lines.length);
        assertEquals("aaa ", lineText(f.lines[0]), "後ろの空白は行末にぶら下がって行に残る");
        assertEquals("bbb", lineText(f.lines[1]));
        LayoutFragment pre = layout("<div id=t style=\"white-space:pre;width:" + aaa + "px\">aaa bbb</div>", "t");
        assertEquals(1, pre.lines.length, "pre は折り返さない");
    }

    @Test
    void breakSpacesKeepSpacesWithWidthAndBreakAfterEach() {
        LayoutFragment probe = layout("<div id=t style=\"white-space:pre-wrap\">123</div>", "t");
        float w = adv("123 ", textStyle(probe));
        LayoutFragment f = layout("<div id=t style=\"white-space:break-spaces;width:" + w + "px\">123    8</div>", "t");
        assertEquals(2, f.lines.length);
        assertEquals("123 ", lineText(f.lines[0]), "空白 1 つまで入る（幅を取る）");
        assertEquals("   8", lineText(f.lines[1]), "残りの空白は次の行の頭に残る");
    }

    @Test
    void letterSpacingIsNotAddedAfterTheLastCharacterOfAnElement() {
        LayoutFragment f = layout("<div id=t>a<span style=\"letter-spacing:10px\">aa</span>a</div>", "t");
        LineFragment.Piece[] p = f.lines[0].pieces();
        assertEquals(3, p.length);
        Style span = p[1].text().style;
        float aa = adv("aa", span); // 文字ごとに 10px 足した幅
        assertEquals(adv("aa", textStyle(f)) + 20f, aa, EPS, "書体の advance は文字ごとに letter-spacing を足す");
        assertEquals(aa - 10f, p[1].width(), EPS, "要素の最後の文字の後の 10px は足さない");
        assertEquals(p[1].x() + aa - 10f, p[2].x(), EPS);
    }

    @Test
    void letterSpacingDoesNotMakeShrinkToFitWrap() {
        // 内在サイズ（max-content）と行組みが同じ幅を使うので、縮小して合わせた inline-block の中で折り返さない
        LayoutFragment f = layout("<div><div id=t style=\"display:inline-block;white-space:pre-wrap;letter-spacing:10px\"><span>1. a</span><span>\n</span></div></div>", "t");
        assertEquals(1, f.lines.length);
    }

    @Test
    void justifySpreadsSoftWrappedLinesButNotTheLast() {
        String words = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu";
        LayoutFragment f = layout("<p id=t style=\"width:200px;text-align:justify\">" + words + "</p>", "t");
        assertTrue(f.lines.length >= 3);
        for (int i = 0; i < f.lines.length; i++) {
            LineFragment l = f.lines[i];
            LineFragment.Piece last = l.pieces()[l.pieces().length - 1];
            float end = l.x() + last.x() + last.width();
            if (i < f.lines.length - 1) assertEquals(200f, end, 0.5f, "折り返した行は両端まで: 行 " + i);
            else assertTrue(end < 199f, "最終行は揃えない");
        }
        LayoutFragment all = layout("<p id=t style=\"width:200px;text-align:justify;text-align-last:justify\">" + words + "</p>", "t");
        LineFragment l = all.lines[all.lines.length - 1];
        LineFragment.Piece last = l.pieces()[l.pieces().length - 1];
        assertEquals(200f, l.x() + last.x() + last.width(), 0.5f, "text-align-last: justify は最終行も揃える");
    }

    @Test
    void justifyUsesGapsBetweenIdeographsAndBeforeForcedBreaks() {
        LayoutFragment f = layout("<p id=t style=\"width:200px;text-align-last:justify\">東京<br>京城</p>", "t");
        assertEquals(2, f.lines.length);
        for (LineFragment l : f.lines) {
            LineFragment.Piece[] p = l.pieces();
            assertEquals(2, p.length, "字ごとに割る");
            assertEquals(0f, p[0].x(), EPS);
            assertEquals(200f, p[1].x() + p[1].width(), 0.5f, "強制改行の前の行も text-align-last で揃える");
        }
    }

    @Test
    void startAndEndFollowDirection() {
        LayoutFragment rtl = layout("<div id=t style=\"direction:rtl;width:300px\">abc</div>", "t");
        LineFragment l = rtl.lines[0];
        assertEquals(300f, l.x() + l.width(), 0.5f, "rtl の start は右");
        LayoutFragment end = layout("<div id=t style=\"width:300px;text-align:end\">abc</div>", "t");
        assertEquals(300f, end.lines[0].x() + end.lines[0].width(), 0.5f, "ltr の end は右");
    }

    @Test
    void textTransformIsAppliedOnTheInputSide() {
        LayoutFragment f = layout("<div id=t><span style=\"text-transform:uppercase\">abc</span> <span style=\"text-transform:capitalize\">hello world</span>"
                + " <span style=\"text-transform:lowercase\">XYZ</span>​</div>", "t");
        LineFragment.Piece[] p = f.lines[0].pieces();
        assertEquals(List.of("ABC", "Hello World", "xyz"), List.of(p[0].string(), p[1].string(), p[2].string()),
                "uppercase・capitalize・lowercase（ゼロ幅の空白は描かない文字として除く）");
        assertEquals(3, p.length);
    }

    @Test
    void textTransformChangeRebuildsTheTextIncrementally() {
        SpikeView v = new SpikeView("<html><body><div id=t>abc <b>def</b></div></body></html>", 400, 300);
        v.frame();
        ((HtmlElement) v.byId("t")).setAttribute("style", "text-transform:uppercase");
        List<String> inc = SpikeView.dump(v.frame());
        assertEquals(SpikeView.dump(v.scratch()), inc);
        LayoutFragment f = frag(v.layout.root(), "t");
        LineFragment.Piece[] p = f.lines[0].pieces();
        assertEquals("ABC", p[0].string());
        assertEquals("DEF", p[1].string());
    }

    @Test
    void intrinsicSizeKeywordsForWidth() {
        LayoutFragment max = layout("<div id=t style=\"width:max-content;border:1px solid\">中文<span>中文</span></div>", "t");
        Style st = textStyle(max);
        assertEquals(adv("中文中文", st) + 2, max.width, EPS);
        LayoutFragment min = layout("<div id=t style=\"width:min-content;border:1px solid\">中文<span>中文</span></div>", "t");
        assertEquals(adv("中", st) + 2, min.width, EPS, "要素の境目の字の間も機会（min-content は 1 字）");
        assertEquals(4, min.lines.length);
        LayoutFragment fit = layout("<div style=\"width:30px\"><div id=t style=\"width:fit-content\">aaa bbb ccc</div></div>", "t");
        float a = adv("aaa", textStyle(fit));
        assertEquals(Math.max(a, Math.min(30f, adv("aaa bbb ccc", textStyle(fit)))), fit.width, EPS, "fit-content = min(max(min, 使える幅), max)");
    }

    @Test
    void keepAllHoldsAcrossElementsAndWbrBreaks() {
        LayoutFragment one = layout("<div id=t style=\"width:min-content;word-break:keep-all\">東京へ<span>行きましょう。</span></div>", "t");
        assertEquals(1, one.lines.length, "keep-all は要素の境目の和文の間も割らない");
        LayoutFragment two = layout("<div id=t style=\"width:min-content;word-break:keep-all\">東京へ<wbr>行きましょう。</div>", "t");
        assertEquals(2, two.lines.length, "<wbr> は機会");
        assertEquals("行きましょう。", lineText(two.lines[1]));
    }

    @Test
    void trailingOtherSpaceSeparatorsHang() {
        LayoutFragment f = layout("<div id=t style=\"width:min-content;white-space:nowrap\">abc </div>", "t");
        assertEquals(adv("abc", textStyle(f)), f.width, EPS, "行末の U+1680 は内在サイズに数えない");
    }

    @Test
    void inlineBlockAfterABlockSitsOnItsLine() {
        // 混在の流れの匿名の塊の中の原子は、その行の位置（前のブロックの下）に置かれる
        SpikeView v = new SpikeView("<html><body style=\"margin:0\"><p style=\"margin:0;height:30px\">x</p>"
                + "<div id=a style=\"display:inline-block;width:40px;height:20px\"></div></body></html>", 400, 300);
        float[] a = SpikeView.find(v.frame(), "a");
        assertNotNull(a);
        assertTrue(a[1] >= 30f, "原子は p の下: y=" + a[1]);
    }

    @Test
    void collapseThroughBlockSitsAtTheParentEdgeWhenMarginsCollapse() {
        // 中身が float だけの p（高さ 0）は、余白を親と相殺するなら親の上端に来る（CSS 2 §8.3.1）
        String doc = "<html><body style=\"margin:0\"><div id=o%s><p id=p style=\"margin:16px 0\"><span id=f style=\"float:left;width:10px;height:10px\"></span></p></div></body></html>";
        LayoutFragment collapsed = new SpikeView(String.format(doc, ""), 400, 300).frame();
        float oy = SpikeView.find(collapsed, "o")[1];
        assertEquals(16f, oy, EPS, "親は相殺した余白の分だけ下がる");
        assertEquals(oy, SpikeView.find(collapsed, "p")[1], EPS, "p は親の上端");
        assertEquals(oy, SpikeView.find(collapsed, "f")[1], EPS, "float は p の内容の上端");
        LayoutFragment padded = new SpikeView(String.format(doc, " style=\"padding-top:1px\""), 400, 300).frame();
        float py = SpikeView.find(padded, "o")[1];
        assertEquals(0f, py, EPS);
        assertEquals(1f + 16f, SpikeView.find(padded, "p")[1], EPS, "相殺しない親では、p は自分の上の余白の下");
    }

    @Test
    void measureAndLayoutAgreeOnShrinkToFit() {
        // 内在サイズで決めた幅で組むと、どの文書でも max-content の幅なら 1 行に収まる
        String[] bodies = {
                "a<span style=\"letter-spacing:3px\">bc</span> d",
                "<span style=\"white-space:pre-wrap\">x  y\tz</span>",
                "東京<span>へ</span>行き",
                "<span style=\"white-space:nowrap\">12345</span> 67890<wbr>abc",
                "one <b>two</b> three",
        };
        for (String b : bodies) {
            LayoutFragment f = layout("<div><div id=t style=\"display:inline-block;tab-size:3\">" + b + "</div></div>", "t");
            assertEquals(1, f.lines.length, b);
        }
        List<String> fails = new ArrayList<>();
        for (String b : bodies) { // min-content の幅で組むと、どの塊も 1 行に収まる（塊の途中で割れない）
            LayoutFragment f = layout("<div id=t style=\"width:min-content\">" + b + "</div>", "t");
            for (LineFragment l : f.lines) {
                for (LineFragment.Piece p : l.pieces()) {
                    String s = p.string().stripTrailing();
                    if (!s.isEmpty() && adv(s, p.text().style) > f.width + 0.5f) fails.add(b + " 片 [" + s + "] が幅 " + f.width + " を超える");
                }
            }
        }
        assertEquals(List.of(), fails);
    }

    @Test
    void textWrapBalanceKeepsTheLineCountAndEvensTheLines() {
        // 貪欲なら "aa bb cc" / "dd"。balance は行数（2）を変えずに折り返しの幅を狭め、"aa bb" / "cc dd" にする（CSS Text 4 §6.1）
        LayoutFragment probe = layout("<div id=t>x</div>", "t");
        Style st = probe.box.style;
        float w = adv("aa bb cc", st) + 2;
        for (String mode : new String[]{"text-wrap:balance", "text-wrap-style:balance"}) {
            LayoutFragment f = layout("<div id=t style=\"width:" + w + "px;" + mode + "\">aa bb cc dd</div>", "t");
            assertEquals(2, f.lines.length, mode);
            assertEquals("aa bb", lineText(f.lines[0]).stripTrailing(), mode);
            assertEquals("cc dd", lineText(f.lines[1]).stripTrailing(), mode);
        }
        LayoutFragment greedy = layout("<div id=t style=\"width:" + w + "px\">aa bb cc dd</div>", "t");
        assertEquals("aa bb cc", lineText(greedy.lines[0]).stripTrailing());
        // 揃えは元の幅のまま: 右揃えの行の右端は箱の右端
        LayoutFragment right = layout("<div id=t style=\"width:" + w + "px;text-wrap:balance;text-align:right\">aa bb cc dd</div>", "t");
        assertEquals(w, right.lines[0].x() + right.lines[0].width(), 0.05f);
    }

    @Test
    void wordSpacingWidensEachWordSeparator() {
        // word-spacing は語の区切りの空白ごとに足す（CSS Text 3 §8.1）。行の片の位置と、縮小して合わせた幅（内在サイズ）の両方
        LayoutFragment f = layout("<div><div id=t style=\"display:inline-block;word-spacing:10px\">aa bb cc</div></div>", "t");
        Style st = f.box.style;
        LineFragment l = f.lines[0];
        assertEquals(3, l.pieces().length);
        assertEquals(adv("aa ", st) + 10, l.pieces()[1].x(), EPS);
        assertEquals(adv("aa bb ", st) + 20, l.pieces()[2].x(), EPS);
        assertEquals(adv("aa bb cc", st) + 20, f.width, EPS);
        // % は自分の書体の大きさ（16px）が基準: 50% = 8px
        LayoutFragment pct = layout("<div><div id=t style=\"display:inline-block;word-spacing:50%\">aa bb</div></div>", "t");
        assertEquals(adv("aa ", st) + 8, pct.lines[0].pieces()[1].x(), EPS);
    }
}
