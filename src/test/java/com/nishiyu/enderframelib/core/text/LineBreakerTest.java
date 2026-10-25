package com.nishiyu.enderframelib.core.text;

import com.nishiyu.enderframelib.core.text.model.WordBreak;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UAX #14（Line Breaking Algorithm）の折り返し候補。期待値は規則（LB 番号）と Chrome（ICU）の挙動に合わせてある。
 *
 * 何が壊れていたか: 候補が空白・ハイフン・和文だけだったので、{@code /} の後ろや {@code —} の前後など
 * Chrome が折り返す位置で折り返せず、長い URL やパスがはみ出していた。
 */
public class LineBreakerTest {

    private static List<String> seg(String text) {
        return TextShaper.segments(text, WordBreak.NORMAL);
    }

    @Test
    void breaksAfterSpacesAndKeepsThemWithThePreviousWord() {
        assertEquals(List.of("hello ", "world"), seg("hello world"));
        assertEquals(List.of("a  ", "b"), seg("a  b"));
    }

    @Test
    void breaksAfterHyphenButNotBeforeNumbers() {
        assertEquals(List.of("two-", "viewers"), seg("two-viewers"));
        // LB25: HY × NU（マイナス・ハイフンで始まる数）
        assertEquals(List.of("a-1"), seg("a-1"));
    }

    @Test
    void breaksAfterSlashInPaths() {
        // LB13: × SY の後は LB31 で折り返せる。URL やパスはスラッシュの後で折り返す
        assertEquals(List.of("usr/", "local/", "bin"), seg("usr/local/bin"));
        // LB25: SY × NU（日付や分数）
        assertEquals(List.of("1/2"), seg("1/2"));
        assertEquals(List.of("2026/10/26"), seg("2026/10/26"));
    }

    @Test
    void doesNotBreakBeforeClosingPunctuationOrInsideNumbers() {
        assertEquals(List.of("Hello, ", "world!"), seg("Hello, world!"));
        assertEquals(List.of("3.14159"), seg("3.14159"));
        assertEquals(List.of("1,000,000"), seg("1,000,000"));
        // LB13: × EX / × IS
        assertEquals(List.of("what?!"), seg("what?!"));
    }

    @Test
    void currencyAndPercentStickToNumbers() {
        assertEquals(List.of("$100"), seg("$100"));
        assertEquals(List.of("50%"), seg("50%"));
        assertEquals(List.of("(50%)"), seg("(50%)"));
        assertEquals(List.of("up ", "to ", "$100 ", "off"), seg("up to $100 off"));
    }

    @Test
    void parenthesesAndQuotes() {
        // LB14: OP SP* ×
        assertEquals(List.of("( x)"), seg("( x)"));
        // LB30: AL × OP、CP × AL
        assertEquals(List.of("foo(bar)"), seg("foo(bar)"));
        // LB19: 引用符の前後では折り返さない。ただし空白の後ろは LB18 が先に折り返す
        assertEquals(List.of("say ", "\"hi\" ", "now"), seg("say \"hi\" now"));
        assertEquals(List.of("a\"b\"c"), seg("a\"b\"c"));
    }

    @Test
    void emDashBreaksOnBothSidesButNotBetweenTwo() {
        assertEquals(List.of("wait", "—", "what"), seg("wait—what"));
        // LB17: B2 SP* × B2（2 つ続いた間は折り返さない）
        assertEquals(List.of("wait", "——", "what"), seg("wait——what"));
    }

    @Test
    void noBreakSpaceAndWordJoinerGlue() {
        assertEquals(List.of("10 km"), seg("10 km"));
        assertEquals(List.of("a⁠b"), seg("a⁠b"));
        // ZWSP の後ろは折り返せる（LB8）
        assertEquals(List.of("ab​", "cd"), seg("ab​cd"));
    }

    @Test
    void combiningMarksStayWithTheirBase() {
        String text = "é́a";
        assertEquals(List.of(text), seg(text));
        // 絵文字の結合（ZWJ）の間では折り返さない
        String family = "👨‍👩‍👧";
        assertEquals(List.of(family), seg(family));
    }

    @Test
    void regionalIndicatorsBreakBetweenPairsOnly() {
        String flags = "🇯🇵🇺🇸"; // 🇯🇵🇺🇸
        assertEquals(List.of("🇯🇵", "🇺🇸"), seg(flags));
    }

    @Test
    void japaneseKinsoku() {
        // 行頭禁則: 句読点・閉じ括弧・小書きの仮名・長音は行頭に来ない
        for (String s : seg("あいう、えお。かき」くけ")) {
            assertFalse(s.startsWith("、") || s.startsWith("。") || s.startsWith("」"), s);
        }
        for (String s : seg("きゃっちゃー")) {
            assertFalse(s.startsWith("ゃ") || s.startsWith("っ") || s.startsWith("ー"), s);
        }
        // 行末禁則: 開き括弧は行末に来ない
        List<String> open = seg("あ「い」う");
        for (String s : open) assertFalse(s.endsWith("「"), open.toString());
        // 漢字・かなの間はどこでも折り返せる
        assertEquals(List.of("日", "本", "語"), seg("日本語"));
    }

    @Test
    void hangulBreaksBetweenSyllablesLikeIdeographs() {
        assertEquals(List.of("한", "국", "어"), seg("한국어"));
        assertEquals(List.of("한국어"), TextShaper.segments("한국어", WordBreak.KEEP_ALL));
    }

    @Test
    void keepAllKeepsCjkTogetherButStillBreaksAtSpaces() {
        assertEquals(List.of("日本語"), TextShaper.segments("日本語", WordBreak.KEEP_ALL));
        assertEquals(List.of("日本語 ", "text"), TextShaper.segments("日本語 text", WordBreak.KEEP_ALL));
        assertEquals(List.of("a-", "b"), TextShaper.segments("a-b", WordBreak.KEEP_ALL));
    }

    @Test
    void breakAllBreaksBetweenAnyLettersAndDigits() {
        assertEquals(List.of("a", "b", "c", "1", "2"), TextShaper.segments("abc12", WordBreak.BREAK_ALL));
        // 閉じ括弧や句読点の前、結合文字の前は break-all でも折り返さない
        assertEquals(List.of("a", "b)"), TextShaper.segments("ab)", WordBreak.BREAK_ALL));
        assertEquals(List.of("é", "a"), TextShaper.segments("éa", WordBreak.BREAK_ALL));
    }

    @Test
    void surrogatePairsAreNeverSplit() {
        String text = "𠮷𠮷"; // 𠮷𠮷
        for (String s : TextShaper.segments(text, WordBreak.NORMAL)) {
            assertTrue(s.codePointCount(0, s.length()) >= 1);
            assertFalse(Character.isLowSurrogate(s.charAt(0)), "代用対の後半から始まる断片がある");
        }
        assertEquals(2, seg(text).size());
    }

    @Test
    void segmentsAlwaysConcatenateBackToTheInput() {
        String[] samples = {
                "The quick (brown) fox—jumps/over \"the\" lazy dog, 3.14 or 1,000 at $5/50%.",
                "日本語とEnglishの混在、「括弧」と（全角）。",
                "path/to/file-name_v1.2.3.tar.gz https://example.com/a/b?c=d&e=f#g",
        };
        for (String sample : samples) {
            for (WordBreak wb : WordBreak.values()) {
                assertEquals(sample, String.join("", TextShaper.segments(sample, wb)), wb + ": " + sample);
            }
        }
    }
}
