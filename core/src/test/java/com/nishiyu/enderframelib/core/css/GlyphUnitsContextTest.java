package com.nishiyu.enderframelib.core.css;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.EngineContext;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A13 第 3 段: {@code ch} / {@code ex} の測定に使う書体の解決は面ごとの文脈にあり、差し込んだ面だけが測る。 */
class GlyphUnitsContextTest {
    @Test
    void onlyTheContextWithAResolverMeasuresGlyphUnits() {
        FontResolver fonts = family -> null;
        EngineContext with = new EngineContext();
        with.updateHost(h -> h.withGlyphFontResolver(fonts));
        EngineContext without = new EngineContext();
        EngineContext.runWith(with, () -> assertNotNull(GlyphUnits.of(Map.of(), Map.of(), 16)));
        EngineContext.runWith(without, () -> assertNull(GlyphUnits.of(Map.of(), Map.of(), 16)));
        assertNull(GlyphUnits.of(Map.of(), Map.of(), 16), "global は差し込まれていない");
    }
}
