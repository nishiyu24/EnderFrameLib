package com.nishiyu.enderframelib.core.css;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** Milestone 2: 算出時の単位換算・currentcolor 置換の前判定（字句解析を避ける近道）が、近道なしの結果を取りこぼさない。 */
class AbsolutizeFastPathTest {

    private static boolean hasDimensionToken(String v) {
        for (CssSyntax.ComponentValue c : CssSyntax.parseComponentValues(v)) {
            if (containsDimension(c)) return true;
        }
        return false;
    }

    private static boolean containsDimension(CssSyntax.ComponentValue c) {
        if (c instanceof CssSyntax.Preserved p) return p.token().type() == CssSyntax.Type.DIMENSION;
        java.util.List<CssSyntax.ComponentValue> inner = c instanceof CssSyntax.Function f ? f.args() : ((CssSyntax.Block) c).body();
        for (CssSyntax.ComponentValue x : inner) if (containsDimension(x)) return true;
        return false;
    }

    @Test
    void noDimensionTokenWhenFastPathSaysNo() {
        String alphabet = "0123456789.%-+_ ,/()#abcdeEpxmv\"'\\ あ";
        Random rnd = new Random(20261115);
        int negatives = 0;
        for (int k = 0; k < 30000; k++) {
            int n = 1 + rnd.nextInt(10);
            StringBuilder b = new StringBuilder();
            for (int j = 0; j < n; j++) b.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
            String v = b.toString();
            if (!ValueComputer.mayContainDimension(v)) {
                negatives++;
                assertFalse(hasDimensionToken(v), "近道が「寸法なし」と言ったのに寸法トークンがある: " + v);
            }
        }
        assertTrue(negatives > 1000, "近道が効く入力が十分にある");
    }

    @Test
    void knownValues() {
        for (String v : new String[]{"block", "red", "#fff", "0", "12", "50%", "1 2 3", "none", "rgb(1,2,3)"}) {
            assertFalse(ValueComputer.mayContainDimension(v), v);
        }
        for (String v : new String[]{"12px", "1.5em", "calc(1px + 2em)", "10vw 5px", "1e3px", "translate(5deg)", "0.5s"}) {
            assertTrue(ValueComputer.mayContainDimension(v), v);
        }
        var m = new LengthResolver.Metrics(0, 800, 600, 10f, 16f, 12f, 16f, Float.NaN, Float.NaN);
        assertEquals("block", ValueComputer.absolutizeUnits("block", m));
        assertEquals("20px", ValueComputer.absolutizeUnits("2em", m));
    }
}
