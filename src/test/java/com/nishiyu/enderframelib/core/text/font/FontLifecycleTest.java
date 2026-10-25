package com.nishiyu.enderframelib.core.text.font;

import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontStyle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FreeType のハンドルの解放と開き直し、{@code @font-face}（メモリ上の書体）の OS の書体と同じ経路での測定。
 *
 * 何が壊れていたか: 開いた面は {@code LoadedFace.OPEN} に入ったまま二度と閉じられなかった（書体の数だけハンドルが
 * 生き続ける）。{@code @font-face} の TTF / OTF は Minecraft の TrueType プロバイダ（ビットマップ）で描かれたので、
 * OS の書体と指標が揃わなかった。
 */
public class FontLifecycleTest {

    private static SystemFonts system;
    private static SystemFonts.Face face;

    @BeforeAll
    static void setUp() {
        system = SystemFonts.system().withLocale(Locale.ENGLISH);
        Assumptions.assumeTrue(system.hasFamily("Arial") || system.hasFamily("DejaVu Sans"), "Arial / DejaVu Sans が無い環境");
        String name = system.hasFamily("Arial") ? "Arial" : "DejaVu Sans";
        face = system.family(name).stream().filter(f -> f.weight() == 400 && !f.italic()).findFirst().orElseThrow();
    }

    @AfterEach
    void cleanUp() {
        WebFonts.clear();
    }

    private static final FontStyle STYLE = new FontStyle(16, false, false, 0);

    @Test
    void releasedHandleReopensAndGivesTheSameMetrics() {
        LoadedFace loaded = LoadedFace.open(face);
        float advance = loaded.advanceEm('W');
        LoadedFace.GlyphBitmap before = loaded.rasterize('g', 20f, false, false, 1);
        int opened = LoadedFace.openHandleCount();
        assertTrue(opened >= 1);

        loaded.release();
        assertEquals(opened - 1, LoadedFace.openHandleCount(), "解放するとハンドルの数が減る");

        // 使うと開き直す。測定の表（寸法・送り幅・字形番号）は残っていて、ラスタライズは同じ結果になる
        assertEquals(advance, loaded.advanceEm('W'), 0f);
        LoadedFace.GlyphBitmap after = loaded.rasterize('g', 20f, false, false, 1);
        assertEquals(before.width(), after.width());
        assertEquals(before.height(), after.height());
        assertTrue(java.util.Arrays.equals(before.coverage(), after.coverage()), "開き直しても同じ字形");
        assertEquals(opened, LoadedFace.openHandleCount());
    }

    @Test
    void idleFacesAreReleasedAndBusyOnesAreNot() throws InterruptedException {
        LoadedFace loaded = LoadedFace.open(face);
        loaded.advanceEm('A');
        LoadedFace.releaseIdle(60_000_000_000L);
        assertTrue(LoadedFace.openHandleCount() >= 1, "使ったばかりの面は閉じない");
        Thread.sleep(5);
        assertTrue(LoadedFace.releaseIdle(1_000_000L) >= 1, "しばらく使われない面は閉じる");
        assertEquals(0, LoadedFace.openHandleCount());
        // 閉じたあとの測定は透過的に開き直す
        assertTrue(loaded.advanceEm('i') > 0);
    }

    @Test
    void webFontIsMeasuredThroughTheSamePathAsSystemFonts() throws IOException {
        byte[] bytes = Files.readAllBytes(face.path());
        // TTC の場合は先頭の書体が使われる。比較は同じファイルの同じ書体どうしで行う
        Assumptions.assumeTrue(face.faceIndex() == 0, "コレクションの 2 番目以降の書体は対象外");

        assertTrue(WebFonts.register("MyWebFont", bytes, 400, false));
        assertTrue(WebFonts.has("mywebfont"));

        FontFace web = system.resolve("'MyWebFont', serif");
        FontFace local = system.resolve("'" + face.familyNames().iterator().next() + "'");
        OutlineFontFace webOutline = assertInstanceOf(OutlineFontFace.class, web);
        OutlineFontFace localOutline = assertInstanceOf(OutlineFontFace.class, local);

        String text = "Hamburgefonstiv 0123";
        assertEquals(localOutline.advance(text, STYLE), webOutline.advance(text, STYLE), 0.001f,
                "同じ書体ファイルなら、OS の書体として使っても @font-face で使っても同じ幅");
        assertEquals(localOutline.ascent(STYLE), webOutline.ascent(STYLE), 0f);
        assertEquals(localOutline.descent(STYLE), webOutline.descent(STYLE), 0f);
        assertEquals(localOutline.xHeight(STYLE), webOutline.xHeight(STYLE), 0.001f);

        // 字形も描ける（メモリから開いた面でのラスタライズ）
        LoadedFace.GlyphBitmap g = webOutline.glyphFace('H', STYLE).face().rasterize('H', 24f, false, false);
        assertFalse(g.isEmpty());
        assertTrue(g.width() > 5 && g.height() > 10);
    }

    @Test
    void webFontShadowsASystemFontOfTheSameNameAndUnregisteringRestoresIt() throws IOException {
        byte[] bytes = Files.readAllBytes(face.path());
        Assumptions.assumeTrue(face.faceIndex() == 0);
        Assumptions.assumeTrue(system.hasFamily("Courier New") || system.hasFamily("Liberation Mono") || system.hasFamily("DejaVu Sans Mono"));
        String shadowed = system.hasFamily("Courier New") ? "Courier New"
                : system.hasFamily("Liberation Mono") ? "Liberation Mono" : "DejaVu Sans Mono";

        FontFace original = system.resolve("'" + shadowed + "'");
        float originalWidth = original.advance("iiiiWWWW", STYLE);

        assertTrue(WebFonts.register(shadowed, bytes, 400, false));
        FontFace overridden = system.resolve("'" + shadowed + "'");
        assertNotEquals(originalWidth, overridden.advance("iiiiWWWW", STYLE), "登録した書体が OS の同名の書体より優先される");

        WebFonts.clear();
        assertEquals(originalWidth, system.resolve("'" + shadowed + "'").advance("iiiiWWWW", STYLE), 0f);
    }

    @Test
    void unreadableFontDataIsRejected() {
        assertFalse(WebFonts.register("Broken", new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, 400, false));
        assertFalse(WebFonts.has("broken"));
        assertFalse(WebFonts.register("Empty", new byte[0], 400, false));
        assertFalse(WebFonts.register("", new byte[]{1}, 400, false));
    }

    @Test
    void asyncWarmUpIsIdempotent() {
        SystemFonts.warmUpAsync();
        SystemFonts.warmUpAsync();
        assertFalse(SystemFonts.system().isEmpty());
    }
}
