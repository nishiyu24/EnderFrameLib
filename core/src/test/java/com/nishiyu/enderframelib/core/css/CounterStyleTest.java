package com.nishiyu.enderframelib.core.css;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code @counter-style} の読み取りと、値からマーカー表現への変換（CSS Counter Styles 3）。
 *
 * <p>期待値は Edge 156 ヘッドレスの実測（大文字小文字を区別する作者名、既定の
 * {@code suffix: ". "}、{@code range} の外の {@code fallback}、{@code pad} の幅に負号を含むこと）。
 */
public class CounterStyleTest {

    private static final int VIEWPORT_W = 800;

    @Test
    public void testBuiltInStyles() {
        CounterStyles styles = CounterStyles.builtins();

        assertEquals("• ", styles.marker("disc", 1));
        assertEquals("◦ ", styles.marker("circle", 1));
        assertEquals("▪ ", styles.marker("square", 1));
        assertEquals("1. ", styles.marker("decimal", 1));
        assertEquals("01. ", styles.marker("decimal-leading-zero", 1));
        assertEquals("-2. ", styles.marker("decimal-leading-zero", -2));
        assertEquals("a. ", styles.marker("lower-alpha", 1));
        assertEquals("z. ", styles.marker("lower-alpha", 26));
        assertEquals("aa. ", styles.marker("lower-alpha", 27));
        assertEquals("α. ", styles.marker("lower-greek", 1));
        assertEquals("iv. ", styles.marker("lower-roman", 4));
        assertEquals("MCMXCIV. ", styles.marker("upper-roman", 1994));
        // 表せない値は decimal に落ちる
        assertEquals("0. ", styles.marker("lower-alpha", 0));
        assertEquals("0. ", styles.marker("lower-roman", 0));
        assertEquals("4000. ", styles.marker("upper-roman", 4000));
        // 名前が無い・知らない名前も decimal
        assertEquals("5. ", styles.marker(null, 5));
        assertEquals("5. ", styles.marker("unknown", 5));
        // 定義済みの名前は ASCII 大文字小文字を区別しない
        assertEquals("1. ", styles.marker("DECIMAL", 1));
        assertEquals("• ", styles.marker("Disc", 1));
        assertNull(styles.marker("none", 5));
        assertNull(styles.marker("NONE", 5));
        assertNull(styles.marker("", 5));
        // list-style-type の文字列はマーカーだけの特別扱い
        assertEquals("→ ", styles.marker("\"→ \"", 5));
        assertEquals("→ ", styles.marker("'→ '", 5));
    }

    @Test
    public void testSystems() {
        CounterStyles styles = styles("""
                @counter-style cyc { system: cyclic; symbols: "A" "B"; suffix: "|"; }
                @counter-style num { system: numeric; symbols: "0" "1" "2"; prefix: "<"; suffix: ">"; negative: "(" ")"; }
                @counter-style alpha { system: alphabetic; symbols: "a" "b"; }
                @counter-style sym { system: symbolic; symbols: "X" "Y"; }
                @counter-style add { system: additive; additive-symbols: 5 "V", 1 "I"; }
                @counter-style fix { system: fixed 5; symbols: "A" "B"; }
                @counter-style ext { system: extends alpha; suffix: "!"; }
                """);

        // cyclic は 1 から記号を繰り返し、0 と負の値は逆向きに回る
        assertEquals("A|", styles.marker("cyc", 1));
        assertEquals("B|", styles.marker("cyc", 2));
        assertEquals("A|", styles.marker("cyc", 3));
        assertEquals("B|", styles.marker("cyc", 0));
        assertEquals("A|", styles.marker("cyc", -1));
        assertEquals("A", styles.representation(1, "cyc"), "counter() は prefix / suffix を付けない");

        // numeric は桁の並び。負号は negative の記号
        assertEquals("<0>", styles.marker("num", 0));
        assertEquals("<12>", styles.marker("num", 5));
        assertEquals("<(12)>", styles.marker("num", -5));
        assertEquals("12", styles.representation(5, "num"));

        // alphabetic は a..z の後 aa..。0 以下は range の外
        assertEquals("a. ", styles.marker("alpha", 1));
        assertEquals("b. ", styles.marker("alpha", 2));
        assertEquals("aa. ", styles.marker("alpha", 3));
        assertEquals("0. ", styles.marker("alpha", 0));
        assertEquals("-1. ", styles.marker("alpha", -1));

        // symbolic は記号を 1 周したら 2 回ずつ重ねる
        assertEquals("X. ", styles.marker("sym", 1));
        assertEquals("Y. ", styles.marker("sym", 2));
        assertEquals("XX. ", styles.marker("sym", 3));
        assertEquals("YY. ", styles.marker("sym", 4));
        assertEquals("XXX. ", styles.marker("sym", 5));

        // additive は重みの大きい記号から貪欲に並べる
        assertEquals("IIII. ", styles.marker("add", 4));
        assertEquals("V. ", styles.marker("add", 5));
        assertEquals("VI. ", styles.marker("add", 6));
        assertEquals("0. ", styles.marker("add", 0));

        // fixed は fixed の第 2 引数から記号を割り当てる
        assertEquals("4. ", styles.marker("fix", 4));
        assertEquals("A. ", styles.marker("fix", 5));
        assertEquals("B. ", styles.marker("fix", 6));
        assertEquals("7. ", styles.marker("fix", 7));

        // extends は指定しなかった記述子を引き継ぎ、指定した suffix だけ差し替える
        assertEquals("a!", styles.marker("ext", 1));
        assertEquals("aa!", styles.marker("ext", 3));
        assertEquals("a", styles.representation(1, "ext"));
    }

    @Test
    public void testExtendsInheritsPrefixNegativePadRangeAndFallback() {
        CounterStyles styles = styles("""
                @counter-style base {
                  system: numeric;
                  symbols: "0" "1" "2" "3" "4" "5" "6" "7" "8" "9";
                  prefix: "[";
                  suffix: "]";
                  negative: "(" ")";
                  pad: 2 "0";
                  range: 0 10;
                  fallback: lower-alpha;
                }
                @counter-style child { system: extends base; suffix: "!"; }
                """);

        assertEquals("[01]", styles.marker("base", 1));
        assertEquals("[01!", styles.marker("child", 1));
        assertEquals("[10!", styles.marker("child", 10));
        assertEquals("01", styles.representation(1, "child"));
        // range の外は fallback の表現。prefix / suffix は元のスタイルのもの
        assertEquals("[k!", styles.marker("child", 11));
        assertEquals("[-1!", styles.marker("child", -1));
    }

    @Test
    public void testFallbackChainStopsAtDecimal() {
        CounterStyles styles = styles("""
                @counter-style a { system: cyclic; symbols: "x"; range: 1 1; fallback: b; }
                @counter-style b { system: cyclic; symbols: "y"; range: 1 1; fallback: a; }
                @counter-style missing { system: cyclic; symbols: "z"; range: 1 1; fallback: NoSuchStyle; }
                """);

        assertEquals("x. ", styles.marker("a", 1));
        assertEquals("2. ", styles.marker("a", 2), "fallback が循環したら decimal");
        assertEquals("2. ", styles.marker("missing", 2));
    }

    @Test
    public void testBuiltInNameCaseIsLowercasedBeforeAuthorLookup() {
        CounterStyles styles = styles("""
                @counter-style lower-alpha { system: cyclic; symbols: "X"; }
                """);

        // 定義済みの名前は ASCII 小文字に直してから作者の定義を探す（§2.1）
        assertEquals("X. ", styles.marker("lower-alpha", 1));
        assertEquals("X. ", styles.marker("LOWER-ALPHA", 1));
        assertEquals("X. ", styles.marker("Lower-Alpha", 1));
        assertEquals("X", styles.representation(1, "LOWER-ALPHA"));
        // 作者名はそれ以外では大文字小文字を区別する
        assertEquals("1. ", styles.marker("LOWER-ALPHA-NOT-BUILT-IN", 1));
    }

    @Test
    public void testReservedNamesAndInvalidRulesAreIgnored() {
        String css = """
                @counter-style decimal { system: cyclic; symbols: "X"; }
                @counter-style Decimal { system: cyclic; symbols: "X"; }
                @counter-style none { system: cyclic; symbols: "X"; }
                @counter-style disc { system: cyclic; symbols: "X"; }
                @counter-style Foo { system: cyclic; symbols: "X"; }
                @counter-style badNoSymbols { system: numeric; }
                @counter-style badExtendsSymbols { system: extends Foo; symbols: "Y"; }
                @counter-style badWeights { system: additive; additive-symbols: 1 "I", 2 "II"; }
                @counter-style badImage { system: cyclic; symbols: url(x); }
                @counter-style badFixed { system: fixed 1.5; symbols: "X"; }
                @counter-style badSymbolKeyword { system: cyclic; symbols: initial; }
                @counter-style badPrefixKeyword { system: cyclic; symbols: "P"; prefix: inherit; }
                @counter-style badExtendsKeyword { system: extends inherit; suffix: "!"; }
                @counter-style badExtendsNone { system: extends none; suffix: "!"; }
                @counter-style good { system: fixed 3; symbols: "a" "b"; }
                """;
        StyleSheet sheet = CssParser.parse(css, VIEWPORT_W);
        CounterStyles styles = new CounterStyles(sheet.counterStyles);

        assertEquals(Set.of("Foo", "badFixed", "badPrefixKeyword", "good"), sheet.counterStyles.keySet());

        assertEquals("X. ", styles.marker("Foo", 1));
        assertEquals("1. ", styles.marker("decimal", 1), "予約名の定義は無効");
        assertEquals("1. ", styles.marker("DECIMAL", 1));
        assertEquals("1. ", styles.marker("foo", 1), "作者名は大文字小文字を区別する");
        assertEquals("1. ", styles.marker("badNoSymbols", 1));
        assertEquals("1. ", styles.marker("badExtendsSymbols", 1));
        assertEquals("1. ", styles.marker("badWeights", 1));
        assertEquals("1. ", styles.marker("badImage", 1));
        // CSS-wide キーワードは <custom-ident> ではないので、記号としては読まない
        assertEquals("1. ", styles.marker("badSymbolKeyword", 1));
        assertEquals("P. ", styles.marker("badPrefixKeyword", 1));
        assertEquals("1. ", styles.marker("badExtendsKeyword", 1));
        assertEquals("1. ", styles.marker("badExtendsNone", 1));
        // 読めない system は宣言だけが無効になり、初期値の symbolic で成立する
        assertEquals("X. ", styles.marker("badFixed", 1));
        assertEquals("a. ", styles.marker("good", 3));
        assertEquals("b. ", styles.marker("good", 4));
        assertEquals("5. ", styles.marker("good", 5));
    }

    @Test
    public void testDescriptorNamesAreCaseInsensitiveAndLastValidWins() {
        CounterStyles styles = styles("""
                @counter-style upper {
                  SYSTEM: CYCLIC;
                  SYMBOLS: "A";
                  SYMBOLS: "B";
                  SUFFIX: "!";
                }
                @counter-style broken { system: numeric; symbols: "0" "1"; symbols: url(x); }
                @counter-style resetRange { system: cyclic; symbols: "A"; range: 1 2; range: auto; }
                """);

        assertEquals("B!", styles.marker("upper", 1));
        assertEquals("1. ", styles.marker("broken", 1), "読めない後続の宣言は無視する");
        assertEquals("A. ", styles.marker("resetRange", 3), "range: auto は初期値に戻す");
    }

    private static CounterStyles styles(String css) {
        return new CounterStyles(CssParser.parse(css, VIEWPORT_W).counterStyles);
    }
}
