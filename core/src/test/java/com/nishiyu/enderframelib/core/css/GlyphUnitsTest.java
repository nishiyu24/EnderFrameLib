package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.LengthResolver.Metrics;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import com.nishiyu.enderframelib.core.text.model.FontStyle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ch}（「0」の送り幅）と {@code ex}（x ハイト）は、要素の書体の指標から決める。
 *
 * 何が壊れていたか: 書体に関係なく 0.5em で近似していた（Arial の「0」は 0.556em、等幅の Consolas は 0.55em、
 * x ハイトは Arial で 0.519em）。
 */
public class GlyphUnitsTest {

    /** 「0」の幅が 0.6em、x ハイトが 0.4em の書体。呼ばれたときの指定を覚える。 */
    private static final class RecordingResolver implements FontResolver {
        final List<String> families = new ArrayList<>();
        final List<FontStyle> styles = new ArrayList<>();

        @Override
        public FontFace resolve(String fontFamily) {
            families.add(fontFamily);
            return new FontFace() {
                @Override
                public float advance(String text, FontStyle style) {
                    styles.add(style);
                    return style.fontSizePx() * 0.6f * text.length();
                }

                @Override
                public float ascent(FontStyle style) {
                    return style.fontSizePx() * 0.9f;
                }

                @Override
                public float descent(FontStyle style) {
                    return style.fontSizePx() * 0.2f;
                }

                @Override
                public float lineGap(FontStyle style) {
                    return 0;
                }

                @Override
                public float xHeight(FontStyle style) {
                    styles.add(style);
                    return style.fontSizePx() * 0.4f;
                }
            };
        }
    }

    @AfterEach
    void uninstall() {
        GlyphUnits.install(null);
    }

    private static float px(String value) {
        return Float.parseFloat(value.replace("px", ""));
    }

    @Test
    void withoutAResolverTheUnitsFallBackToHalfAnEm() {
        Metrics m = Metrics.of(0, 320, 200, 20, 16);
        assertEquals(10f, LengthResolver.lengthUnitInPixels("ch", m), 0f);
        assertEquals(10f, LengthResolver.lengthUnitInPixels("ex", m), 0f);
    }

    @Test
    void unitsComeFromTheFontMetrics() {
        GlyphUnits.install(new RecordingResolver());
        Map<String, String> style = Map.of("font-family", "Foo");
        Metrics m = new Metrics(0, 320, 200, 20, 16, 24, 24, Float.NaN, Float.NaN, GlyphUnits.of(style, Map.of(), 20));
        assertEquals(12f, LengthResolver.lengthUnitInPixels("ch", m), 0.001f); // 20px * 0.6
        assertEquals(8f, LengthResolver.lengthUnitInPixels("ex", m), 0.001f);  // 20px * 0.4
    }

    @Test
    void fontIsReadFromTheElementAndInheritedFromTheParent() {
        RecordingResolver resolver = new RecordingResolver();
        GlyphUnits.install(resolver);
        Map<String, String> own = Map.of("font-weight", "bold");
        Map<String, String> parent = Map.of("font-family", "'Inherited Font', serif", "font-style", "italic");
        Metrics m = new Metrics(0, 320, 200, 20, 16, 24, 24, Float.NaN, Float.NaN, GlyphUnits.of(own, parent, 20));
        LengthResolver.lengthUnitInPixels("ch", m);
        assertEquals(List.of("'Inherited Font', serif"), resolver.families);
        FontStyle used = resolver.styles.getFirst();
        assertEquals(700, used.weight());
        assertTrue(used.italic());
        assertEquals(20f, used.fontSizePx(), 0f);
    }

    @Test
    void theFontIsOnlyResolvedWhenAChOrExUnitIsUsed() {
        RecordingResolver resolver = new RecordingResolver();
        GlyphUnits.install(resolver);
        Metrics m = new Metrics(0, 320, 200, 20, 16, 24, 24, Float.NaN, Float.NaN, GlyphUnits.of(Map.of(), Map.of(), 20));
        assertEquals(40f, LengthResolver.lengthUnitInPixels("em", m) * 2, 0f);
        assertEquals(20f, LengthResolver.lengthUnitInPixels("px", m) * 20, 0f);
        assertTrue(resolver.families.isEmpty(), "em / px では書体を引かない（再スタイルの経路を遅くしない）");
        LengthResolver.lengthUnitInPixels("ex", m);
        LengthResolver.lengthUnitInPixels("ex", m);
        assertEquals(1, resolver.families.size(), "一度引いたら覚える");
    }

    @Test
    void computedStyleUsesTheMeasuredUnits() {
        GlyphUnits.install(new RecordingResolver());
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("font-family", "Foo");
        raw.put("font-size", "20px");
        raw.put("width", "10ch");
        raw.put("height", "5ex");
        ComputedStyle s = StyleResolver.finalizeComputedStyle(raw, null, null, Map.of(), 16f, 16f, 320, 200);
        assertEquals(120f, px(s.get(CssProperty.WIDTH)), 0.01f);
        assertEquals(40f, px(s.get(CssProperty.HEIGHT)), 0.01f);
    }

    @Test
    void childrenMeasureWithTheInheritedFamilyAndTheirOwnSize() {
        RecordingResolver resolver = new RecordingResolver();
        GlyphUnits.install(resolver);
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("font-size", "10px");
        raw.put("width", "10ch");
        Map<String, String> parent = Map.of("font-family", "Parent Font", "font-size", "30px");
        ComputedStyle s = StyleResolver.finalizeComputedStyle(raw, null, null, parent, 30f, 16f, 320, 200);
        assertEquals(60f, px(s.get(CssProperty.WIDTH)), 0.01f); // 10px * 0.6 * 10
        assertEquals("Parent Font", resolver.families.getFirst());
    }
}
