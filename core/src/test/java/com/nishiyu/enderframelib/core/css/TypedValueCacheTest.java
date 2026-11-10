package com.nishiyu.enderframelib.core.css;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 型付き参照の結果を、スタイルごとの記憶表ではなく全スタイル共有の表で引くこと。 */
class TypedValueCacheTest {

    private static ComputedStyle style(String... kv) {
        Map<String, String> p = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) p.put(kv[i], kv[i + 1]);
        return ComputedStyle.of(p, 16f, 16f);
    }

    @Test
    void sameDeclarationAcrossStylesSharesOneCompiledLength() {
        TypedValueCache.clearForTest();
        ComputedStyle a = style(CssProperty.WIDTH, "16px");
        ComputedStyle b = style(CssProperty.HEIGHT, new String("16px"));
        assertEquals(16f, a.length(CssProperty.WIDTH, 100, 800, 600), 0f);
        assertEquals(16f, b.length(CssProperty.HEIGHT, 100, 800, 600), 0f);
        assertSame(TypedValueCache.length("16px"), TypedValueCache.length(new String("16px")));
        assertEquals(1, TypedValueCache.sizeForTest(), "16px は 1 回だけ解析して共有");
    }

    @Test
    void colorsAndNumbersResolveLikeTheirParsersAndRemember() {
        TypedValueCache.clearForTest();
        ComputedStyle s = style(CssProperty.COLOR, "#336699", CssProperty.OPACITY, "0.5", CssProperty.Z_INDEX, "auto");
        for (int i = 0; i < 3; i++) {
            assertEquals(ColorParser.parseColor("#336699"), s.color(CssProperty.COLOR, 0));
            assertEquals(0.5f, s.number(CssProperty.OPACITY, 1f), 0f);
            assertEquals(7f, s.number(CssProperty.Z_INDEX, 7f), 0f, "解けない値は既定値");
        }
        assertEquals(0, TypedValueCache.sizeForTest(), "算出スタイルの型付き読み取りは値オブジェクト（CssValue）に覚える。共有表は文字列から直に引く呼び出し用");
        assertEquals(9f, s.number(CssProperty.Z_INDEX, 9f), 0f, "解けないことを覚えても既定値は呼び出しごと");
        assertEquals(Float.NaN, TypedValueCache.number("NaN", 0f), "NaN は解けた値");
        assertEquals(-2f, style(CssProperty.Z_INDEX, " -2 ").number(CssProperty.Z_INDEX, 0f), 0f, "前後の空白は無視");
    }

    @Test
    void absentColorAndNumberReturnTheDefaultWithoutTouchingTheCache() {
        TypedValueCache.clearForTest();
        ComputedStyle s = style();
        assertEquals(0xFF123456, s.color(CssProperty.COLOR, 0xFF123456));
        assertEquals(3f, s.number(CssProperty.OPACITY, 3f), 0f);
        assertEquals(0, TypedValueCache.sizeForTest());
    }

    @Test
    void cacheIsBoundedAndStillCorrectAfterBeingCleared() {
        TypedValueCache.clearForTest();
        for (int i = 0; i < TypedValueCache.LIMIT * 2 + 5; i++) {
            assertEquals(i, TypedValueCache.length(i + "px").resolve(0, 0, 0, 16f, 16f), 0f);
        }
        assertTrue(TypedValueCache.sizeForTest() <= TypedValueCache.LIMIT);
        assertEquals(5f, TypedValueCache.length("5px").resolve(0, 0, 0, 16f, 16f), 0f);
    }

    @Test
    void keywordsFallBackToTheSharedInitialValueAndKeepTheirType() {
        ComputedStyle none = style();
        ComputedStyle set = style(CssProperty.POSITION, "ABSOLUTE", CssProperty.FLOAT, "bogus");
        for (int i = 0; i < 2; i++) {
            assertEquals(CssKeywords.Position.STATIC, none.keywordOf(CssProperty.POSITION, CssKeywords.Position.class));
            assertEquals(CssKeywords.Position.ABSOLUTE, set.keywordOf(CssProperty.POSITION, CssKeywords.Position.class));
            assertEquals(CssKeywords.FloatSide.NONE, set.keywordOf(CssProperty.FLOAT, CssKeywords.FloatSide.class), "知らない値は初期値");
        }
        int pos = ComputedStyle.idOf(CssProperty.POSITION);
        assertEquals(CssKeywords.Position.STATIC, none.keywordOf(pos, CssKeywords.Position.class));
        assertEquals(CssKeywords.Position.ABSOLUTE, set.keywordOf(pos, CssKeywords.Position.class));
    }
}
