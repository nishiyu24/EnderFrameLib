package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** match が箱ごとに配列を確保しないための numAt が、記憶する numsOf と常に同じ値を返すことを確かめる。 */
class DisplayListBuilderNumsTest {
    @Test
    void numAtMatchesNumsOfForEveryField() {
        HtmlNode root = HtmlParser.parse("<div>x</div>");
        java.util.Random r = new java.util.Random(7);
        for (int round = 0; round < 50; round++) {
            Map<String, String> props = new HashMap<>();
            props.put("display", "block");
            props.put("width", (10f + r.nextFloat() * 100f) + "px");
            props.put("height", (10f + r.nextFloat() * 100f) + "px");
            props.put("padding-left", (1f + r.nextFloat() * 10f) + "px");
            props.put("padding-top", (1f + r.nextFloat() * 10f) + "px");
            props.put("padding-right", (1f + r.nextFloat() * 10f) + "px");
            props.put("padding-bottom", (1f + r.nextFloat() * 10f) + "px");
            for (String side : new String[]{"left", "top", "right", "bottom"}) {
                props.put("border-" + side + "-style", "solid");
                props.put("border-" + side + "-width", (1f + r.nextFloat() * 6f) + "px");
            }
            props.put("line-height", (12f + r.nextFloat() * 30f) + "px");
            LayoutBox b = new LayoutBox(root.children.get(0), new ComputedStyle(props));
            LayoutContext ctx = new LayoutContext(new Viewport(400, 400), null, new ScrollState(), FontResolver.FALLBACK, false, round + 1L, true, null);
            b.measure(400f, 400f, FormattingContext.BLOCK, ctx);
            b.layout(1f + r.nextFloat() * 100f, 1f + r.nextFloat() * 100f, 400f, 400f, ctx);
            float[] nums = BoxMemo.numsOf(b);
            assertEquals(BoxMemo.NUMS_LENGTH, nums.length);
            for (int i = 0; i < nums.length; i++) {
                assertTrue(nums[i] > 0f, "field " + i + " should be populated");
                assertEquals(Float.floatToIntBits(nums[i]), Float.floatToIntBits(BoxMemo.numAt(b, i)), "field " + i);
            }
        }
        LayoutBox b = new LayoutBox(root.children.get(0), new ComputedStyle(Map.of("display", "block")));
        assertThrows(IndexOutOfBoundsException.class, () -> BoxMemo.numAt(b, BoxMemo.NUMS_LENGTH));
    }
}
