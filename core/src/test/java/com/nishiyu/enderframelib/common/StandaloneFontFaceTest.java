package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.css.CssParser;
import com.nishiyu.enderframelib.core.text.font.FontFaceRules;
import com.nishiyu.enderframelib.core.text.font.FontSources;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import com.nishiyu.enderframelib.core.text.font.WebFonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/** 窓（{@code StandaloneViewer}）で、HTML の {@code @font-face} が相対パスのファイルから効くこと。 */
class StandaloneFontFaceTest {

    @AfterEach
    void tearDown() {
        CssParser.fontFaceCallback = null;
        FontFaceRules.setFontReader(null);
        WebFonts.clear();
    }

    @Test
    void fontFaceInTheDocumentIsRegisteredAndMeasured(@TempDir Path dir) throws Exception {
        SystemFonts system = SystemFonts.system().withLocale(Locale.ENGLISH);
        String name = system.hasFamily("Arial") ? "Arial" : system.hasFamily("DejaVu Sans") ? "DejaVu Sans" : null;
        Assumptions.assumeTrue(name != null, "Arial / DejaVu Sans が無い環境");
        SystemFonts.Face face = system.family(name).stream().filter(f -> f.weight() == 400 && !f.italic()).findFirst().orElseThrow();
        Assumptions.assumeTrue(face.path() != null && face.faceIndex() == 0
                && face.path().toString().toLowerCase(Locale.ROOT).endsWith(".ttf"), "単体の TTF が無い環境");
        Files.copy(face.path(), dir.resolve("my.ttf"));
        Path page = dir.resolve("page.html");
        String html = "<html><head><style>@font-face{font-family:'Win Font';src:url(my.ttf)}"
                + "p{font-family:'Win Font';font-size:20px}</style></head><body><p>Hello</p></body></html>";
        Files.writeString(page, html);

        FontSources fonts = StandaloneViewer.installFonts(page.toString());
        try {
            assertFalse(WebFonts.has("win font"));
            StandaloneView view = new StandaloneView(html, 400, 200);
            assertTrue(WebFonts.has("win font"), "文書を読んだ時点で登録される");
            view.close();
        } finally {
            fonts.close();
        }
    }
}
