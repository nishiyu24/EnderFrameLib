package com.nishiyu.enderframelib.core.css;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 複数の値を持つプロパティ（背景の位置・大きさ、影、フィルター、clip-path）の補間と、ショートハンドの展開。 */
public class CompoundInterpolatorTest {

    private static String at(String property, String from, String to, double t) {
        return ValueInterpolator.interpolate(property, from, to, t);
    }

    @Test
    public void backgroundPosition() {
        assertEquals("10px 0", at("background-position", "0 0", "20px 0", 0.5));
        assertEquals("150% 0", at("background-position", "0% 0", "300% 0", 0.5));
        assertEquals("0 0, 0 32px", at("background-position", "0 0, 0 0", "0 0, 0 64px", 0.5));
        assertEquals("50% 50%", at("background-position", "left top", "right bottom", 0.5)); // キーワードは割合へ
        assertEquals("25px 50%", at("background-position", "10px", "40px", 0.5)); // 1 値は縦が中央
        assertEquals("0px 0", at("background-position", "0 0", "20px 0", 0)); // 単位の無い 0 は相手の単位になる
        assertEquals("20px 0", at("background-position", "0 0", "20px 0", 1));
        assertEquals("-10px 0", at("background-position", "0 0", "20px 0", -0.5)); // 範囲外は外挿
        assertNull(at("background-position", "0 0", "0 0, 0 0", 0.5)); // 層の数が違う
        assertNull(at("background-position", "right 10px top 5px", "left 0 top 0", 0.5)); // 辺からのずれは読まない
    }

    @Test
    public void backgroundSize() {
        assertEquals("20px 20px", at("background-size", "10px 10px", "30px 30px", 0.5));
        assertEquals("auto auto", at("background-size", "auto", "auto auto", 0.5));
        assertNull(at("background-size", "auto", "50%", 0.5)); // auto と長さは補間しない
        assertNull(at("background-size", "cover", "contain", 0.5));
    }

    @Test
    public void shadows() {
        assertEquals("0 0 10px 0px #fff", at("box-shadow", "0 0 0 #fff", "0 0 20px #fff", 0.5));
        // 影の無い側からは、相手と同じ色で不透明度 0 の影から伸びる
        assertEquals("0px 0px 10px 0px rgba(255, 0, 0, 0.4)", at("box-shadow", "none", "0 0 20px rgba(255,0,0,.8)", 0.5));
        // 数が違うときは、短いほうを埋める
        assertEquals("0 0 15px 0px red, 0px 0px 10px 0px rgba(0, 0, 255, 0.502)",
                at("box-shadow", "0 0 10px red, 0 0 20px blue", "0 0 20px red", 0.5));
        assertEquals("3px 3px 1px 0px #f0f", at("text-shadow", "0 0 0 #f0f", "6px 6px 2px #f0f", 0.5));
        assertNull(at("box-shadow", "0 0 4px red", "inset 0 0 4px red", 0.5)); // inset が違う
        assertNull(at("box-shadow", "0 0 4px", "0 0 4px red", 0.5)); // currentcolor と色は補間しない
    }

    @Test
    public void filters() {
        assertEquals("blur(3px)", at("filter", "blur(0)", "blur(6px)", 0.5));
        assertEquals("blur(5px) brightness(0.75)", at("filter", "none", "blur(10px) brightness(.5)", 0.5)); // none は何もしない関数
        assertEquals("hue-rotate(90deg)", at("filter", "hue-rotate(0deg)", "hue-rotate(180deg)", 0.5));
        assertEquals("grayscale(0.5)", at("filter", "grayscale(0)", "grayscale(100%)", 0.5));
        assertNull(at("filter", "blur(2px)", "grayscale(1)", 0.5)); // 関数が違う
        assertNull(at("filter", "blur(2px)", "blur(2px) brightness(1)", 0.5)); // 並びの長さが違う
        assertNull(at("filter", "url(#a)", "blur(2px)", 0.5));
    }

    @Test
    public void clipPath() {
        assertEquals("inset(20% 20% 20% 20%)", at("clip-path", "inset(0)", "inset(40%)", 0.5));
        assertEquals("inset(5px 10px 5px 10px round 9px)", at("clip-path", "inset(0 round 4px)", "inset(10px 20px round 14px)", 0.5));
        assertEquals("circle(30px at 35% 65%)", at("clip-path", "circle(10px at 50% 50%)", "circle(50px at 20% 80%)", 0.5));
        assertEquals("ellipse(15px 25px at 50% 50%)", at("clip-path", "ellipse(10px 20px)", "ellipse(20px 30px)", 0.5));
        assertEquals("polygon(0 50%, 100% 50%, 50% 50%)", at("clip-path", "polygon(0 0, 100% 0, 50% 100%)", "polygon(0 100%, 100% 100%, 50% 0)", 0.5));
        assertNull(at("clip-path", "inset(0)", "circle(50%)", 0.5)); // 図形が違う
        assertNull(at("clip-path", "polygon(0 0, 1px 1px, 2px 0)", "polygon(0 0, 1px 1px)", 0.5)); // 頂点の数が違う
        assertNull(at("clip-path", "inset(0 round 4px)", "inset(0)", 0.5)); // round の有無が違う
    }

    @Test
    public void transformOrigin() {
        assertEquals("50% 50%", at("transform-origin", "0 0", "100% 100%", 0.5));
    }

    @Test
    public void shorthandsExpandToLonghands() {
        Map<String, String> out = new LinkedHashMap<>();
        StyleAnimator.expandInto("border-radius", "6px", out, 0);
        assertEquals(Map.of("border-top-left-radius", "6px", "border-top-right-radius", "6px", "border-bottom-right-radius", "6px",
                "border-bottom-left-radius", "6px"), out);
        out.clear();
        StyleAnimator.expandInto("margin", "0 10px", out, 0);
        assertEquals(Map.of("margin-top", "0", "margin-right", "10px", "margin-bottom", "0", "margin-left", "10px"), out);
        out.clear();
        StyleAnimator.expandInto("border-color", "#f00 #00f", out, 0);
        assertEquals("#f00", out.get("border-top-color"));
        assertEquals("#00f", out.get("border-right-color"));
        out.clear();
        StyleAnimator.expandInto("inset", "20px", out, 0);
        assertEquals(Map.of("top", "20px", "right", "20px", "bottom", "20px", "left", "20px"), out);
        out.clear();
        StyleAnimator.expandInto("width", "10px", out, 0); // ロングハンドはそのまま
        assertEquals(Map.of("width", "10px"), out);
        out.clear();
        StyleAnimator.expandInto("--custom", "1", out, 0); // 未知のプロパティもそのまま
        assertEquals(Map.of("--custom", "1"), out);
    }
}
