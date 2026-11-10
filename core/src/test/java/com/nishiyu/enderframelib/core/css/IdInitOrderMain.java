package com.nishiyu.enderframelib.core.css;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** {@link IdInitOrderTest} が別 JVM で起こす本体。最初に触るクラスを変えても、添字の定数が名前引きと一致することを確かめる。 */
public final class IdInitOrderMain {
    public static void main(String[] args) throws Exception {
        Class.forName(args[0], true, IdInitOrderMain.class.getClassLoader());
        StringBuilder bad = new StringBuilder();
        // 1) CssPropertyIndex の全定数
        for (Field f : CssProperty.class.getFields()) {
            if (f.getType() != String.class || !Modifier.isStatic(f.getModifiers())) continue;
            String name = (String) f.get(null);
            int expected = ComputedStyle.idOf(name);
            int actual = CssPropertyIndex.class.getField(f.getName()).getInt(null);
            if (expected < 0 || expected != actual) bad.append("Id.").append(f.getName()).append(' ').append(actual).append("!=").append(expected).append('\n');
        }
        // 2) 添字の表を持つ静的フィールド（初期化の順序で 0 のまま固まっていないか）
        check(bad, "com.nishiyu.enderframelib.core.css.LengthResolver", "CORNER_IDS",
                "border-top-left-radius", "border-top-right-radius", "border-bottom-right-radius", "border-bottom-left-radius");
        check(bad, "com.nishiyu.enderframelib.core.paint.BoxDecorationPainter", "BORDER_COLOR_IDS",
                "border-top-color", "border-right-color", "border-bottom-color", "border-left-color");
        check(bad, "com.nishiyu.enderframelib.core.paint.BoxDecorationPainter", "BORDER_STYLE_IDS",
                "border-top-style", "border-right-style", "border-bottom-style", "border-left-style");
        check(bad, "com.nishiyu.enderframelib.core.paint.DisplayListBuilder", "MOVING_PROPS",
                "transform", "translate", "rotate", "scale", "animation", "animation-name", "filter", "perspective");
        // 算出・ショートハンド展開の添字表
        check(bad, "com.nishiyu.enderframelib.core.css.ValueComputer", "BLOCK_SIZE_IDS", "height", "min-height", "max-height");
        String[][] linePairs = {{"border-top-style", "border-top-width"}, {"border-right-style", "border-right-width"},
                {"border-bottom-style", "border-bottom-width"}, {"border-left-style", "border-left-width"},
                {"outline-style", "outline-width"}, {"column-rule-style", "column-rule-width"}};
        Field lp = Class.forName("com.nishiyu.enderframelib.core.css.ValueComputer").getDeclaredField("LINE_PAIRS");
        lp.setAccessible(true);
        int[][] actualPairs = (int[][]) lp.get(null);
        for (int i = 0; i < linePairs.length; i++) {
            for (int k = 0; k < 2; k++) {
                if (actualPairs[i][k] != ComputedStyle.idOf(linePairs[i][k])) bad.append("LINE_PAIRS[").append(i).append("][").append(k).append("]\n");
            }
        }
        Field opaque = Class.forName("com.nishiyu.enderframelib.core.css.ValueComputer").getDeclaredField("OPAQUE_BY_ID");
        opaque.setAccessible(true);
        boolean[] opaqueIds = (boolean[]) opaque.get(null);
        for (String n : new String[]{"font-family", "content", "src", "animation-name", "transition-property", "grid-template-areas",
                "list-style-image", "background-image"}) {
            if (!opaqueIds[ComputedStyle.idOf(n)]) bad.append("OPAQUE_BY_ID ").append(n).append('\n');
        }
        Field plan = Class.forName("com.nishiyu.enderframelib.core.css.ShorthandPlan").getDeclaredField("IDS");
        plan.setAccessible(true);
        int[] planIds = (int[]) plan.get(null);
        java.util.List<String> order = PropertyRegistry.expansionOrder();
        for (int i = 0; i < order.size(); i++) {
            if (planIds[i] != ComputedStyle.idOf(order.get(i)) || planIds[i] < 0) bad.append("ShorthandPlan.IDS[").append(i).append("] ").append(order.get(i)).append('\n');
        }
        if (bad.length() > 0) {
            System.out.print(bad);
            System.exit(1);
        }
    }

    private static void check(StringBuilder bad, String cls, String field, String... names) throws Exception {
        Field f = Class.forName(cls).getDeclaredField(field);
        f.setAccessible(true);
        int[] actual = (int[]) f.get(null);
        for (int i = 0; i < names.length; i++) {
            if (actual[i] != ComputedStyle.idOf(names[i])) bad.append(cls).append('.').append(field).append('[').append(i).append("] ").append(actual[i]).append('\n');
        }
    }
}
