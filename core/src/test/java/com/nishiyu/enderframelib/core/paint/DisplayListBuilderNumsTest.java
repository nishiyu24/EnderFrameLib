package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** match が箱ごとに配列を確保しないための numAt が、記憶する numsOf と常に同じ値を返すことを確かめる。 */
class DisplayListBuilderNumsTest {
    @Test
    void numAtMatchesNumsOfForEveryField() {
        HtmlNode root = HtmlParser.parse("<div>x</div>");
        LayoutBox b = new LayoutBox(root.children.get(0), new ComputedStyle(Map.of("display", "block")));
        java.util.Random r = new java.util.Random(7);
        for (int round = 0; round < 50; round++) {
            b.x = r.nextFloat() * 100; b.y = r.nextFloat() * 100; b.w = r.nextFloat() * 100; b.h = r.nextFloat() * 100;
            b.pL = r.nextFloat(); b.pT = r.nextFloat(); b.pR = r.nextFloat(); b.pB = r.nextFloat();
            b.bL = r.nextFloat(); b.bT = r.nextFloat(); b.bR = r.nextFloat(); b.bB = r.nextFloat();
            b.lineHeightPx = r.nextFloat() * 30;
            float[] nums = DisplayListBuilder.numsOf(b);
            assertEquals(DisplayListBuilder.NUMS_LENGTH, nums.length);
            for (int i = 0; i < nums.length; i++) {
                assertEquals(Float.floatToIntBits(nums[i]), Float.floatToIntBits(DisplayListBuilder.numAt(b, i)), "field " + i);
            }
        }
        assertThrows(IndexOutOfBoundsException.class, () -> DisplayListBuilder.numAt(b, DisplayListBuilder.NUMS_LENGTH));
    }
}
