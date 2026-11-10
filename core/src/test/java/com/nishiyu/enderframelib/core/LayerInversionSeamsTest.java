package com.nishiyu.enderframelib.core;

import com.nishiyu.enderframelib.core.css.ColorParser;
import com.nishiyu.enderframelib.core.dom.BindingHooks;
import com.nishiyu.enderframelib.core.dom.Bindings;
import com.nishiyu.enderframelib.core.paint.DisplayItemFonts;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.raster.CanvasRenderingContext2D;
import com.nishiyu.enderframelib.core.raster.ColorSyntax;
import com.nishiyu.enderframelib.core.text.model.FontStyle;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 7 層の逆方向参照を解消するために入れた継ぎ目（SPI・移設）が、元の挙動のまま繋がっていることを固定する。 */
class LayerInversionSeamsTest {

    @Test
    void colorSyntaxIsDiscoveredAndMatchesColorParser() {
        ColorSyntax s = ColorSyntax.get();
        for (String c : new String[] {"red", "#ff000080", "rgb(1, 2, 3)", "hsl(120 50% 50%)", "oklch(0.7 0.1 150)"}) {
            assertTrue(s.isColor(c), c);
            assertEquals(ColorParser.parseColor(c), s.parseColor(c), c);
        }
        assertFalse(s.isColor("not-a-color"));
        assertFalse(s.isColor(null));
    }

    @Test
    void canvasStillParsesCssColorsThroughTheSeam() {
        CanvasRenderingContext2D c = new CanvasRenderingContext2D(4, 4);
        c.fillStyle("#00ff00");
        c.fillRect(0, 0, 4, 4);
        byte[] px = c.getImageData(1, 1, 1, 1).data();
        assertEquals(0, px[0] & 0xFF);
        assertEquals(255, px[1] & 0xFF);
        assertEquals(0, px[2] & 0xFF);
        assertEquals(255, px[3] & 0xFF);
    }

    @Test
    void bindingsReadTranslationThroughHooksOfTheCurrentContext() throws Exception {
        EngineContext ctx = new EngineContext();
        ctx.i18nResolver = k -> "T:" + k;
        // 文脈の外（global）は既定のまま、中だけ差し替わる
        assertEquals("a.b", Bindings.resolve("{{i18n:a.b}}", Map.of(), Map.of()));
        String inside = EngineContext.callWith(ctx, () -> Bindings.resolve("{{i18n:a.b}}", Map.of(), Map.of()));
        assertEquals("T:a.b", inside);
        assertSame(ctx, EngineContext.callWith(ctx, () -> BindingHooks.current()));
    }

    @Test
    void identityHooksAreHarmless() {
        assertEquals("k", BindingHooks.IDENTITY.i18nResolver().apply("k"));
        assertEquals("k", BindingHooks.IDENTITY.i18nFormatter().apply("k", new Object[] {1}));
        assertNull(BindingHooks.IDENTITY.jsonListParser());
    }

    @Test
    void displayItemFontStyleReadsEventsLikeBefore() {
        DisplayItem el = new DisplayItem("id", "n", ElementType.TEXT, 0, 0, 10, 10, "t", 0, 0,
                Map.of("font-weight", "bold", "font-style", "italic", "letter-spacing", "1.5", "font-kerning", "none"),
                0, 1f, 0, 0, 1, 1, 0, 0, null, null, null);
        FontStyle f = DisplayItemFonts.fontStyleFor(el, 12f);
        assertEquals(700, f.weight());
        assertTrue(f.bold());
        assertTrue(f.italic());
        assertEquals(1.5f, f.letterSpacingPx());
        assertFalse(f.kerning());
        FontStyle d = DisplayItemFonts.fontStyleFor(new DisplayItem("i2", "n", ElementType.TEXT, 0, 0, 1, 1, "t", 0, 0,
                null, 0, 1f, 0, 0, 1, 1, 0, 0, null, null, null), 10f);
        assertEquals(400, d.weight());
        assertFalse(d.bold());
        assertTrue(d.kerning());
    }
}
