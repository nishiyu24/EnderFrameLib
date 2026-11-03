package com.nishiyu.enderframelib.core.css;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code rgb()} / {@code rgba()} の近道が、従来の解釈器（トークナイザ経由）と全く同じ結果になること。 */
class ColorParserFastPathTest {

    private static void same(String s) {
        assertEquals(ColorParser.parseColorSlow(s), ColorParser.parseColor(s), "'" + s + "'");
    }

    @Test
    void commonFormsMatchTheSlowParser() {
        String[] cases = {
                "rgb(0,0,0)", "rgb(255,255,255)", "rgb(10, 20, 30)", "rgb( 10 , 20 , 30 )", "rgba(10,20,30,0.5)", "rgba(10, 20, 30, .5)",
                "rgba(0,0,0,0)", "rgba(0,0,0,1)", "rgba(255,128,64,0.25)", "rgb(10,20,30,0.5)", "rgba(10,20,30)", "  rgb(1,2,3)  ",
                "rgb(300,-1,0)", "rgb(1.5,2.5,3.5)", "rgba(1,2,3,0.499)", "rgba(1,2,3,0.5)", "rgba(1,2,3,2)", "rgba(127.5,127.5,127.5,0.5)",
                "rgb(0.1,0.2,0.3)", "rgba(12,34,56,0.123456789)", "rgb(100,200,300)", "rgba(1,2,3,1.0)", "rgba(1,2,3,00.5)",
        };
        for (String c : cases) same(c);
    }

    @Test
    void unusualFormsFallBackAndStillMatch() {
        String[] cases = {
                "RGB(1,2,3)", "Rgba(1,2,3,0.5)", "rgb(1 2 3)", "rgb(1 2 3 / 0.5)", "rgb(10%,20%,30%)", "rgba(10%,20%,30%,50%)", "rgb(1,2)", "rgb(1,2,3,4,5)",
                "rgb(1,2,3,)", "rgb(,1,2,3)", "rgb()", "rgb(", "rgb(1,2,3", "rgb(1,2,3))", "rgb(1,2,3) x", "rgb(1.,2,3)", "rgb(1..2,2,3)",
                "rgb(1e2,2,3)", "rgb(+1,2,3)", "rgb(1px,2,3)", "rgb(calc(1+2),2,3)", "rgb(none,2,3)", "rgb(1,2,3,none)", "rgb(var(--x),2,3)",
                "rgb(\t1,\t2,\t3)", "rgb(1,\n2,3)", "rgba(1,2,3,50%)", "transparent", "#fff", "#ff000080", "red", "currentcolor", "", "   ", "rgb(1, 2, 3) rgb(4,5,6)",
                "rgb(123456789012345,0,0)", "rgb(0,0,0)extra", "xrgb(1,2,3)", "rgb (1,2,3)", "rgba(1,2,3,.)",
        };
        for (String c : cases) same(c);
    }

    @Test
    void randomizedStringsMatch() {
        Random r = new Random(42);
        String[] seps = {",", ", ", " ,", " , ", ",  "};
        String[] pre = {"", " ", "  "};
        String[] names = {"rgb(", "rgba(", "rgb( ", "rgba( "};
        for (int i = 0; i < 20000; i++) {
            StringBuilder sb = new StringBuilder(pre[r.nextInt(pre.length)]).append(names[r.nextInt(names.length)]);
            int n = 2 + r.nextInt(4);
            for (int k = 0; k < n; k++) {
                if (k > 0) sb.append(seps[r.nextInt(seps.length)]);
                switch (r.nextInt(8)) {
                    case 0 -> sb.append(r.nextInt(300));
                    case 1 -> sb.append(r.nextInt(256)).append('.').append(r.nextInt(1000));
                    case 2 -> sb.append('.').append(r.nextInt(1000));
                    case 3 -> sb.append(r.nextDouble());
                    case 4 -> sb.append(r.nextInt(101)).append('%');
                    case 5 -> sb.append(r.nextInt(256));
                    case 6 -> sb.append(r.nextInt(3) == 0 ? "0.5" : "1");
                    default -> sb.append(r.nextInt(256));
                }
            }
            sb.append(r.nextInt(20) == 0 ? "" : ")").append(pre[r.nextInt(pre.length)]);
            same(sb.toString());
        }
    }
}
