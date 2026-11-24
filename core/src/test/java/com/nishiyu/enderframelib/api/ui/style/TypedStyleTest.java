package com.nishiyu.enderframelib.api.ui.style;

import com.nishiyu.enderframelib.api.EnderEngine;
import com.nishiyu.enderframelib.api.WebViewSurface;
import com.nishiyu.enderframelib.api.dom.DOMRect;
import com.nishiyu.enderframelib.api.dom.Element;
import com.nishiyu.enderframelib.api.dom.StyleRule;
import com.nishiyu.enderframelib.api.ui.State;
import com.nishiyu.enderframelib.api.ui.style.Keyword.BorderStyle;
import com.nishiyu.enderframelib.api.ui.style.Keyword.Display;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.nishiyu.enderframelib.api.ui.UI.*;
import static com.nishiyu.enderframelib.api.ui.style.Css.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Java だけで書いた型付きスタイル（{@link StyleClass}・{@link Theme}・{@link Token}・{@link Modifier}）が、
 * 文書へ採用された規則としてカスケードに乗り、描画まで届くこと。
 */
class TypedStyleTest {

    static final Token<Color> SURFACE = Token.of("surface");
    static final Token<Color> ACCENT = Token.of("accent");
    static final Modifier ACTIVE = Modifier.of("active");

    static final Theme DARK = Theme.of("dark", SURFACE.is(hex(0x102030)));
    static final Theme LIGHT = Theme.of("light", SURFACE.is(hex(0xf0e0d0)));

    static final StyleClass BOX = StyleClass.of("box", display(Display.BLOCK), width(px(100)), height(px(40)));
    static final StyleClass PANEL = StyleClass.of("panel", BOX, background(SURFACE));
    static final StyleClass CHIP = StyleClass.of("chip", BOX, background(hex(0x445566)),
            when(ACTIVE, background(ACCENT)));

    @Test
    void classesThemesTokensAndModifiersReachThePixels() {
        try (WebViewSurface surface = EnderEngine.empty().size(320, 200).clearColor(0xFFFFFFFF).build()) {
            Element body = surface.dom().getBody();
            var dark = State.of(true);
            var accent = State.of(hex(0x38bdf8));
            var active = State.of(false);

            render(body,
                    div(Theme.select(dark.map(d -> d ? DARK : LIGHT)), ACCENT.bind(accent), inline(margin(ZERO)),
                            div(id("panel"), PANEL),
                            div(id("chip"), CHIP, CHIP.is(ACTIVE, active))));
            surface.frame(0, true);

            Element panel = $("#panel", body);
            Element chip = $("#chip", body);
            assertEquals("box panel", panel.getClassName(), "土台のクラスも付く");
            assertEquals(100, panel.getBoundingClientRect().width(), 0.5, "土台の宣言（width）が効く");
            assertRgb(0x102030, sample(surface, panel), "テーマのトークン（ダーク）");
            assertRgb(0x445566, sample(surface, chip), "状態なしのチップ");

            dark.set(false);
            surface.frame(16, false);
            assertRgb(0xf0e0d0, sample(surface, panel), "テーマを切り替えるとトークンの値が変わる");

            active.set(true);
            surface.frame(32, false);
            assertTrue(chip.getClassList().contains("chip--active"));
            assertRgb(0x38bdf8, sample(surface, chip), "状態の宣言が効き、値は束ねたトークンから");

            accent.set(hex(0xf59e0b));
            surface.frame(48, false);
            assertRgb(0xf59e0b, sample(surface, chip), "束ねたトークンの値を変えると追従する");
        }
    }

    @Test
    void sameNameWithDifferentRulesIsRejected() {
        try (WebViewSurface surface = EnderEngine.empty().size(100, 100).build()) {
            Element body = surface.dom().getBody();
            StyleClass a = StyleClass.of("dup", width(px(1)));
            StyleClass b = StyleClass.of("dup", width(px(2)));
            render(body, div(a), div(a));
            assertThrows(IllegalStateException.class, () -> render(body, div(b)));
        }
    }

    @Test
    void dynamicDeclarationsAreInlineOnly() {
        var w = State.of(px(10));
        assertThrows(IllegalArgumentException.class, () -> StyleClass.of("bad", width(w)));
        assertThrows(IllegalArgumentException.class, () -> Theme.of("bad", width(px(1))));
    }

    @Test
    void rulesSerializeFromTypedValues() {
        StyleClass card = StyleClass.of("card", padding(px(9), px(16)), border(px(1), BorderStyle.SOLID, SURFACE),
                hover(opacity(0.9)));
        assertEquals(List.of(
                new StyleRule(".card", List.of(
                        new StyleRule.Declaration("padding", "9px 16px"),
                        new StyleRule.Declaration("border", "1px solid var(--surface)"))),
                new StyleRule(".card:hover", List.of(new StyleRule.Declaration("opacity", "0.9")))), card.rules());
    }

    private static int sample(WebViewSurface surface, Element el) {
        DOMRect r = el.getBoundingClientRect();
        assertFalse(r.isEmpty(), "要素がレイアウトされていること");
        int x = Math.round(r.x() + r.width() / 2f);
        int y = Math.round(r.y() + r.height() / 2f);
        return surface.pixels()[y * surface.pixelWidth() + x];
    }

    private static void assertRgb(int expected, int argb, String message) {
        assertEquals(String.format("%06x", expected), String.format("%06x", argb & 0xffffff), message);
    }
}
