package com.nishiyu.enderframelib.core.kernel.style;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.kernel.memory.AtomTable;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StyleStructsTest {

    private final StyleStructs.Compiler compiler = new StyleStructs.Compiler(new AtomTable());

    private static ComputedStyle style(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        // 要素のフォントサイズは宣言から算出済みの値を別引数で受ける（ここでは font-size 宣言をそのまま使う）
        String fs = m.get("font-size");
        float px = fs == null ? 16f : Float.parseFloat(fs.replace("px", ""));
        return new ComputedStyle(m, px, 16f);
    }

    @Test
    void groupsReadTypedPrimitives() {
        StyleStructs s = compiler.compile(style(
                "font-size", "20px", "font-weight", "bold", "color", "#112233", "letter-spacing", "2px",
                "width", "50%", "height", "calc(100% - 8px)", "min-width", "10px", "flex-grow", "2",
                "margin-top", "4px", "padding-left", "1em", "border-top-width", "3px", "opacity", "0.5",
                "background-color", "#ff0000", "z-index", "7", "display", "flex", "box-sizing", "border-box",
                "position", "relative"), null);
        assertEquals(20f, s.font.fontSizePx());
        assertEquals(700, s.font.fontWeight());
        assertEquals(0xFF112233, s.text.colorArgb());
        assertEquals(2f, s.text.letterSpacingPx());
        assertEquals(100f, s.widthPx(200, 16, 0, 0));
        assertEquals(PackedCssValue.K_CALC, PackedCssValue.kind(s.box.height()));
        assertEquals(192f, StyleStructs.resolve(s.box.height(), 200, 20, 16, 0, 0,
                new StyleStructs.CalcResolver(compiler.calcs())));
        assertEquals(10f, PackedCssValue.floatPayload(s.box.minWidth()));
        assertEquals(2f, s.box.flexGrow());
        assertEquals(4f, PackedCssValue.floatPayload(s.surround.marginT()));
        assertEquals(PackedCssValue.U_EM, PackedCssValue.unit(s.surround.paddingL()));
        assertEquals(3f, s.surround.borderT());
        assertEquals(0.5f, s.paint.opacity());
        assertEquals(0xFFFF0000, s.paint.bgColorArgb());
        assertEquals(7, s.paint.zIndex());
        assertEquals(1, s.box.boxSizing());
        assertEquals(PackedCssValue.NONE, s.box.maxWidth(), "未指定は NONE");
    }

    @Test
    void equalGroupsAreSharedAcrossStyles() {
        StyleStructs a = compiler.compile(style("font-size", "14px", "color", "#000000", "width", "10px"), null);
        StyleStructs b = compiler.compile(style("font-size", "14px", "color", "#000000", "width", "99px"), null);
        assertSame(a.font, b.font, "同じフォントは 1 つの構造体");
        assertSame(a.text, b.text);
        assertNotSame(a.box, b.box);
    }

    @Test
    void inheritanceIsAReferenceCopy() {
        StyleStructs parent = compiler.compile(style("font-size", "18px", "color", "#336699"), null);
        StyleStructs child = compiler.compile(style("font-size", "18px", "color", "#336699", "width", "5px"), parent);
        assertSame(parent.font, child.font);
        assertSame(parent.text, child.text);
        StyleStructs diff = compiler.compile(style("font-size", "18px", "color", "#ff0000"), parent);
        assertSame(parent.font, diff.font);
        assertNotSame(parent.text, diff.text);
    }

    @Test
    void recordsAreValueEqualAndPooled() {
        int before = StyleStructs.pooledCount();
        StyleStructs.PaintEffectStruct p1 = StyleStructs.intern(new StyleStructs.PaintEffectStruct(0.123f, 1, 2, 3, 4, 5, 6));
        StyleStructs.PaintEffectStruct p2 = StyleStructs.intern(new StyleStructs.PaintEffectStruct(0.123f, 1, 2, 3, 4, 5, 6));
        assertSame(p1, p2);
        assertEquals(before + 1, StyleStructs.pooledCount());
    }
}
