package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.kernel.style.KeywordMatch;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.text.model.WhiteSpace;
import com.nishiyu.enderframelib.core.text.model.WordBreak;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Milestone 2: 文字列由来フラグ（rtl / white-space / word-break / writing-mode / font-kerning）の照合を、新しい文字列を作らない形に替えても結果は同じ。 */
class KeywordMatchTest {
    private static final String[] WORDS = {"rtl", "RTL", " Rtl ", "ltr", "none", "NONE", "normal", "nowrap", "NoWrap", "pre", "PRE",
            "pre-wrap", "pre-line", "break-all", "KEEP-ALL", "keep-all", "horizontal-tb", " horizontal-tb", "Horizontal-tb",
            "vertical-rl", "", "  ", "\tpre\n", "pre ", "prex", "xpre", "auto"};

    @Test
    void isMatchesTrimEqualsIgnoreCase() {
        for (String v : WORDS) for (String k : WORDS) {
            if (!k.equals(k.toLowerCase(Locale.ROOT)) || !k.equals(k.trim())) continue;
            assertEquals(v.trim().equalsIgnoreCase(k), KeywordMatch.is(v, k), "'" + v + "' vs '" + k + "'");
        }
        assertFalse(KeywordMatch.is(null, "rtl"));
    }

    @Test
    void startsWithMatchesTrimStartsWith() {
        for (String v : WORDS) assertEquals(v.trim().startsWith("horizontal"), KeywordMatch.trimmedStartsWith(v, "horizontal"), v);
        assertFalse(KeywordMatch.trimmedStartsWith(null, "horizontal"));
    }

    private static WhiteSpace oldWs(String k) {
        if (k == null) return WhiteSpace.NORMAL;
        return switch (k.trim().toLowerCase()) {
            case "nowrap" -> WhiteSpace.NOWRAP;
            case "pre" -> WhiteSpace.PRE;
            case "pre-wrap" -> WhiteSpace.PRE_WRAP;
            case "pre-line" -> WhiteSpace.PRE_LINE;
            default -> WhiteSpace.NORMAL;
        };
    }

    private static WordBreak oldWb(String k) {
        if (k == null) return WordBreak.NORMAL;
        return switch (k.trim().toLowerCase()) {
            case "break-all" -> WordBreak.BREAK_ALL;
            case "keep-all" -> WordBreak.KEEP_ALL;
            default -> WordBreak.NORMAL;
        };
    }

    @Test
    void whiteSpaceAndWordBreakMatchOld() {
        for (String v : WORDS) {
            assertEquals(oldWs(v), WhiteSpace.of(v), v);
            assertEquals(oldWb(v), WordBreak.of(v), v);
        }
        assertEquals(WhiteSpace.NORMAL, WhiteSpace.of(null));
        assertEquals(WordBreak.NORMAL, WordBreak.of(null));
    }

    @Test
    void factsMatchOldDerivationOnRandomStyles() {
        Random rnd = new Random(20261113);
        for (int round = 0; round < 3000; round++) {
            Map<String, String> m = new LinkedHashMap<>();
            String dir = pick(rnd), wm = pick(rnd), ws = pick(rnd), wb = pick(rnd), kern = pick(rnd);
            if (dir != null) m.put(CssProperty.DIRECTION, dir);
            if (wm != null) m.put("writing-mode", wm);
            if (ws != null) m.put(CssProperty.WHITE_SPACE, ws);
            if (wb != null) m.put(CssProperty.WORD_BREAK, wb);
            if (kern != null) m.put(CssProperty.FONT_KERNING, kern);
            m.put(CssProperty.WIDTH, "100px");
            StyleFacts f = new ComputedStyle(m, 16f, 16f).layoutFacts();
            assertEquals("rtl".equalsIgnoreCase((dir == null ? "ltr" : dir).trim()), f.rtl);
            assertEquals(oldWs(ws), f.whiteSpace);
            assertEquals(oldWb(wb), f.wordBreak);
            assertEquals(!(kern != null && "none".equalsIgnoreCase(kern.trim())), f.fontStyle.kerning());
            boolean hz = wm == null || wm.trim().startsWith("horizontal");
            if (!hz) assertFalse(f.horizontalFixed);
        }
    }

    private static String pick(Random rnd) {
        return rnd.nextInt(3) == 0 ? null : WORDS[rnd.nextInt(WORDS.length)];
    }

    private static boolean oldRelativeUnit(String u) {
        return switch (u.toLowerCase(Locale.ROOT)) {
            case "vw", "vh", "vi", "vb", "vmin", "vmax",
                 "svw", "svh", "svi", "svb", "svmin", "svmax",
                 "lvw", "lvh", "lvi", "lvb", "lvmin", "lvmax",
                 "dvw", "dvh", "dvi", "dvb", "dvmin", "dvmax",
                 "cqw", "cqh", "cqi", "cqb", "cqmin", "cqmax" -> true;
            default -> false;
        };
    }

    @Test
    void relativeUnitMatchMatchesOldSwitch() {
        String[] units = {"vw", "VH", "vi", "vb", "vmin", "VMAX", "svw", "lvh", "dvi", "svb", "lvmin", "dvmax", "cqw", "CQH", "cqi",
                "cqb", "cqmin", "cqmax", "px", "em", "rem", "vx", "svx", "cq", "cqx", "cqminx", "v", "s", "sv", "dvminn", "pt", "deg",
                "vmi", "svmi", "xvw", "cqwh", "ch", "cm", "lv", "dv"};
        for (String u : units) assertEquals(oldRelativeUnit(u), ComputedStyle.isRelativeUnit(u, 0, u.length()), u);
        Random rnd = new Random(7);
        String alphabet = "vscqdlwhibmnaxp";
        for (int k = 0; k < 20000; k++) {
            int n = 1 + rnd.nextInt(7);
            StringBuilder b = new StringBuilder();
            for (int j = 0; j < n; j++) b.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
            String u = b.toString();
            assertEquals(oldRelativeUnit(u), ComputedStyle.isRelativeUnit("12" + u + "!", 2, 2 + u.length()), u);
        }
    }

    @Test
    void cssWideKeywordMatchesOldRule() {
        String[] vals = {"inherit", "INHERIT", " Initial ", "unset", "revert", "REVERT-LAYER", "revert-layer", "revert-layers", "inheri", "", " ", "red", null};
        for (String v : vals) {
            ValueComputer.CssWideKeyword old = null;
            if (v != null) {
                switch (v.trim().toLowerCase(Locale.ROOT)) {
                    case "inherit" -> old = ValueComputer.CssWideKeyword.INHERIT;
                    case "initial" -> old = ValueComputer.CssWideKeyword.INITIAL;
                    case "unset" -> old = ValueComputer.CssWideKeyword.UNSET;
                    case "revert" -> old = ValueComputer.CssWideKeyword.REVERT;
                    case "revert-layer" -> old = ValueComputer.CssWideKeyword.REVERT_LAYER;
                    default -> { }
                }
            }
            assertEquals(old, ValueComputer.CssWideKeyword.of(v), String.valueOf(v));
        }
    }
}
