package com.nishiyu.enderframelib.core.text.font;

import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FontFaceRulesTest {

    @AfterEach void reset() { FontFaceRules.setFontReader(null); }

    @Test @DisplayName("書体名は引用符を外して小文字にする")
    void clean() {
        assertEquals("segoe ui", FontFaceRules.cleanFamilyName("  \"Segoe UI\" "));
        assertEquals("", FontFaceRules.cleanFamilyName(null));
        assertTrue(FontFaceRules.isWebSansSerifFamily("'Helvetica Neue'"));
        assertFalse(FontFaceRules.isWebSansSerifFamily("Comic Sans"));
    }

    @Test @DisplayName("font-weight 記述子")
    void weight() {
        assertEquals(700, FontFaceRules.parseFontWeightDescriptor("bold"));
        assertEquals(400, FontFaceRules.parseFontWeightDescriptor("normal"));
        assertEquals(100, FontFaceRules.parseFontWeightDescriptor("100 900"));
        assertEquals(400, FontFaceRules.parseFontWeightDescriptor("weird"));
    }

    @Test @DisplayName("@font-face を解いて取り除き、sink へ渡す。src の無いものは捨てる")
    void extract() {
        String css = "a{color:red} @font-face { font-family: 'My Font'; src: url(\"mod:fonts/my.woff2\"); font-weight: bold; font-style: italic; size: 12px }"
                + " @font-face { font-family: Broken } b{x:y}";
        List<FontFaceRules.FaceDecl> got = new ArrayList<>();
        String rest = FontFaceRules.extractAndRegister(css, got::add);
        assertEquals("a{color:red}   b{x:y}", rest);
        assertEquals(1, got.size());
        var d = got.get(0);
        assertEquals("my font", d.family());
        assertEquals("mod:fonts/my.woff2", d.src());
        assertEquals(700, d.weight());
        assertTrue(d.italic());
        assertEquals(12f, d.size());
        assertEquals(4f, d.oversample());
        assertEquals("a{}", FontFaceRules.extractAndRegister("a{}", null));
    }

    @Test @DisplayName("TTF / OTF 以外は読み手を呼ばない。ns:path は font/ を挟んだ置き場も試す")
    void readerCandidates() {
        List<String> asked = new ArrayList<>();
        FontFaceRules.setFontReader(s -> { asked.add(s); return null; });
        FontFaceRules.extractAndRegister("@font-face{font-family:a;src:url(x:f.woff2)}", null);
        assertTrue(asked.isEmpty());
        FontFaceRules.extractAndRegister("@font-face{font-family:b;src:url(x:f.ttf)}", null);
        assertEquals(List.of("x:f.ttf", "x:font/f.ttf"), asked);
    }

    @Test @DisplayName("minecraft の書体名と Minecraft 側の登録名は Minecraft の書体を優先する")
    void prefersMinecraft() {
        assertTrue(FontFaceRules.prefersMinecraftFont("minecraft:default", n -> false));
        assertTrue(FontFaceRules.prefersMinecraftFont("Zork Font", n -> n.equals("zork font")));
    }
}
