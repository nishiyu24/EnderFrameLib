package com.nishiyu.enderframelib.core.text;

import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontStyle;
import com.nishiyu.enderframelib.core.text.TextShaper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.RenderPipeline;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

/**
 * 折り返し・空白処理・省略が 1 箇所に揃っていることの番人。
 *
 * 何が壊れていたか:
 * - 折り返しが測定用と配置用に別々にあり、片方には 4px の余裕が入っていた。
 * - {@code white-space} は継承リストに載っていたが誰も読まず、
 *   {@code LayoutBox} が無条件に空白を畳んでいたので {@code <pre>} が効かなかった。
 * - 単語分割の規則が「空白と和文の句読点」だけだったため、
 *   句読点を含まない日本語の長文がまったく折り返されなかった。
 */
public class TextShaperTest {

    private static final FontFace FACE = FontFace.FALLBACK;
    private static final FontStyle FONT = new FontStyle(16.0f, false, false, 0.0f);

    private static TextStyle style(WhiteSpace ws) {
        return style(ws, WordBreak.NORMAL, false, false);
    }

    private static TextStyle style(WhiteSpace ws, WordBreak wb,
                                              boolean breakLongWords, boolean ellipsis) {
        return new TextStyle(FONT, ws, wb, breakLongWords, ellipsis, 20.0f);
    }

    // ==========================================
    // white-space
    // ==========================================

    @Test
    public void testNormalCollapsesRunsOfWhitespaceAndNewlines() {
        assertEquals("a b c", TextShaper.normalize("a  \n  b\t\tc", WhiteSpace.NORMAL));
    }

    @Test
    public void testPreKeepsNewlinesAndRunsOfSpaces() {
        assertEquals("a  b\nc", TextShaper.normalize("a  b\nc", WhiteSpace.PRE));
    }

    @Test
    public void testPreLineCollapsesSpacesButKeepsNewlines() {
        assertEquals("a b\nc", TextShaper.normalize("a   b\nc", WhiteSpace.PRE_LINE));
    }

    @Test
    public void testPreSplitsOnNewlinesAndNeverWraps() {
        List<ShapedLine> lines =
                TextShaper.shape("aaaa\nbb\ncc", FACE, style(WhiteSpace.PRE), 10);
        assertEquals(3, lines.size(), "改行が行分割として残っていない");
        assertEquals("aaaa", lines.getFirst().text());
        // 幅 10px でも pre は折り返さないので、1 行目は制限を超える
        assertTrue(lines.getFirst().width() > 10, "pre が折り返されている");
    }

    @Test
    public void testNowrapKeepsEverythingOnOneLine() {
        List<ShapedLine> lines =
                TextShaper.shape("one two three four", FACE, style(WhiteSpace.NOWRAP), 20);
        assertEquals(1, lines.size());
    }

    @Test
    public void testForcedBreakEmitsAnEmptyLineThatStillHasHeight() {
        List<ShapedLine> lines =
                TextShaper.shape("a\n\nb", FACE, style(WhiteSpace.PRE), 0);
        assertEquals(3, lines.size());
        assertEquals("", lines.get(1).text());
        assertEquals(20.0f, lines.get(1).height(), 0.001f);
    }

    // ==========================================
    // 折り返し
    // ==========================================

    @Test
    public void testWrapsAtTheAvailableWidthWithoutSlop() {
        float oneWord = FACE.advance("aaaa", FONT);
        List<ShapedLine> lines =
                TextShaper.shape("aaaa aaaa", FACE, style(WhiteSpace.NORMAL), oneWord);

        // 以前は 4px の余裕があったため、ちょうど 1 語分の幅でも 2 語目が同じ行に残っていた
        assertEquals(2, lines.size(), "1 語分の幅で折り返されていない: " + lines);
        assertEquals("aaaa", lines.getFirst().text());
        assertEquals("aaaa", lines.getLast().text());
    }

    @Test
    public void testTrailingSpaceIsNotCountedInTheLineWidth() {
        List<ShapedLine> lines =
                TextShaper.shape("aa bb", FACE, style(WhiteSpace.NORMAL), FACE.advance("aa bb", FONT));
        assertEquals(1, lines.size());
        // 行末に空白がぶら下がっても幅には入らない
        assertFalse(lines.getFirst().text().endsWith(" "));
    }

    @Test
    public void testJapaneseWrapsBetweenIdeographsWithoutPunctuation() {
        String text = "日本語の文章は句読点が無くても折り返せる";
        List<ShapedLine> lines =
                TextShaper.shape(text, FACE, style(WhiteSpace.NORMAL), 60);
        assertTrue(lines.size() > 1, "表意文字の間で折り返されていない: " + lines);
    }

    @Test
    public void testClosingPunctuationNeverStartsALine() {
        // 「、」が行頭に来てはいけない（行頭禁則）
        List<String> segments = TextShaper.segments("あい、うえ", WordBreak.NORMAL);
        for (String segment : segments) {
            assertFalse(segment.startsWith("、"), "句読点が行頭に来る分割になっている: " + segments);
        }
    }

    @Test
    public void testLongWordOverflowsUnlessBreakingIsAllowed() {
        String longWord = "abcdefghijklmnop";
        List<ShapedLine> kept =
                TextShaper.shape(longWord, FACE, style(WhiteSpace.NORMAL), 30);
        assertEquals(1, kept.size(), "overflow-wrap: normal は語を割らない");

        List<ShapedLine> broken = TextShaper.shape(longWord, FACE,
                style(WhiteSpace.NORMAL, WordBreak.NORMAL, true, false), 30);
        assertTrue(broken.size() > 1, "overflow-wrap: break-word で語が割れていない");
        for (ShapedLine line : broken) {
            assertTrue(line.width() <= 30, "割った行が制限を超えている: " + line);
        }
    }

    @Test
    public void testBreakAllFillsEachLineToTheEdge() {
        List<ShapedLine> lines = TextShaper.shape("aaaa bbbbbbbb", FACE,
                style(WhiteSpace.NORMAL, WordBreak.BREAK_ALL, false, false), 40);
        assertTrue(lines.size() > 1);
        for (ShapedLine line : lines) {
            assertTrue(line.width() <= 40, "break-all の行が制限を超えている: " + line);
        }
    }

    @Test
    public void testZeroWidthMeansUnconstrained() {
        List<ShapedLine> lines =
                TextShaper.shape("one two three", FACE, style(WhiteSpace.NORMAL), 0);
        assertEquals(1, lines.size());
    }

    // ==========================================
    // 行の指標
    // ==========================================

    @Test
    public void testBaselineSplitsTheLeadingEvenly() {
        ShapedLine line =
                TextShaper.shape("a", FACE, style(WhiteSpace.NORMAL), 0).getFirst();
        float halfLeading = (20.0f - (FACE.ascent(FONT) + FACE.descent(FONT))) / 2f;
        assertEquals(halfLeading + FACE.ascent(FONT), line.baseline(), 0.001f);
        assertEquals(20.0f, line.height(), 0.001f);
    }

    @Test
    public void testLineWidthIsTheSumOfItsAdvances() {
        ShapedLine line =
                TextShaper.shape("abc", FACE, style(WhiteSpace.NORMAL), 0).getFirst();
        assertEquals(FACE.advance("abc", FONT), line.width(), 0.001f);
    }

    // ==========================================
    // text-overflow
    // ==========================================

    @Test
    public void testEllipsisOnlyAppliesWhenTheLineCannotWrap() {
        String text = "abcdefghijklmnop";
        float narrow = FACE.advance("abcd", FONT);

        List<ShapedLine> clipped = TextShaper.shape(text, FACE,
                style(WhiteSpace.NOWRAP, WordBreak.NORMAL, false, true), narrow);
        assertEquals(1, clipped.size());
        assertTrue(clipped.getFirst().ellipsized(), "省略されていない: " + clipped);
        assertTrue(clipped.getFirst().text().endsWith("..."));
        assertTrue(clipped.getFirst().width() <= narrow);
    }

    @Test
    public void testEllipsizeKeepsShortTextUntouched() {
        assertEquals("ab", TextShaper.ellipsize("ab", FACE, FONT, 1000));
    }

    @Test
    public void testFitCharsStopsAtCodePointBoundaries() {
        String text = "ab😀cd"; // 絵文字はサロゲートペア
        int fit = FACE.fitChars(text, FONT, FACE.advance("ab😀", FONT));
        assertEquals("ab😀", text.substring(0, fit), "サロゲートペアの途中で切っている");
    }

    /**
     * 語の境目に詰め（カーニング）があっても、全体を 1 回で測った幅（= max-content）ちょうどの枠で折り返さない。
     *
     * 何が壊れていたか: 入るかどうかを語ごとの幅の和で見ていたため、境目の詰めの分だけ和が広くなり、
     * max-content ちょうどの flex アイテムで "two-viewers" が "two-" の後で折り返していた（実機で確認）。
     */
    @Test
    public void testKernedLineFitsItsOwnMaxContent() {
        FontFace kerned = new FontFace() {
            @Override
            public float advance(String text, FontStyle style) {
                float w = FACE.advance(text, style);
                for (int i = text.indexOf("-v"); i >= 0; i = text.indexOf("-v", i + 1)) w -= 2;
                return w;
            }

            @Override
            public float ascent(FontStyle style) {
                return FACE.ascent(style);
            }

            @Override
            public float descent(FontStyle style) {
                return FACE.descent(style);
            }

            @Override
            public float lineGap(FontStyle style) {
                return FACE.lineGap(style);
            }
        };
        float maxContent = kerned.advance("two-viewers", FONT);
        List<ShapedLine> lines = TextShaper.shape("two-viewers", kerned, style(WhiteSpace.NORMAL), maxContent);
        assertEquals(1, lines.size(), "max-content ちょうどの枠で折り返した: " + lines);
    }
}
