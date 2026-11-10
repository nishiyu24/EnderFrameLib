package com.nishiyu.enderframelib.core.css;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Milestone 2: 添字定数（{@code CssProperty.Id}）は {@code ComputedStyle} → {@code PropertyRegistry} の初期化に依存するので、
 * それらを経由して初期化される側の静的な添字表が、途中の 0 のまま固まらないこと（最初に触るクラスを変えた別 JVM で確かめる）。
 */
class IdInitOrderTest {
    private static final List<String> FIRST_TOUCH = List.of(
            "com.nishiyu.enderframelib.core.css.CssProperty$Id",
            "com.nishiyu.enderframelib.core.css.ComputedStyle",
            "com.nishiyu.enderframelib.core.css.PropertyRegistry",
            "com.nishiyu.enderframelib.core.css.LengthResolver",
            "com.nishiyu.enderframelib.core.css.ValueComputer",
            "com.nishiyu.enderframelib.core.css.StyleFacts",
            "com.nishiyu.enderframelib.core.css.ShorthandPlan",
            "com.nishiyu.enderframelib.core.css.StyleTable",
            "com.nishiyu.enderframelib.core.paint.BoxDecorationPainter",
            "com.nishiyu.enderframelib.core.paint.DisplayListBuilder");

    @Test
    void idTablesAreCorrectWhicheverClassIsTouchedFirst() throws Exception {
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        for (String first : FIRST_TOUCH) {
            Process p = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                    "com.nishiyu.enderframelib.core.css.IdInitOrderMain", first).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            assertEquals(0, p.waitFor(), "最初に " + first + " を触ると添字表が壊れる:\n" + out);
        }
    }
}
