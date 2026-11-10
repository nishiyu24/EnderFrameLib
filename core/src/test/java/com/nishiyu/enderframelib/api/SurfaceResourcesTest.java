package com.nishiyu.enderframelib.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** フォントと画像の差し込み設定（EnderEngine.Builder の font / systemFonts / images / webImages）。 */
class SurfaceResourcesTest {

    private static int red(int argb) { return (argb >> 16) & 0xFF; }
    private static int green(int argb) { return (argb >> 8) & 0xFF; }
    private static int blue(int argb) { return argb & 0xFF; }

    private static int[] render(EnderEngine.Builder b) {
        try (WebViewSurface s = b.build()) {
            s.frame(0, true);
            return s.pixels().clone();
        }
    }

    /** OS の書体ファイル 1 つ（ファイルから読めるもの）。無ければテストを飛ばす。 */
    private static SystemFonts.Face systemFaceWithFile() {
        for (SystemFonts.Face f : SystemFonts.system().faces()) {
            if (f.path() != null && Files.isRegularFile(f.path()) && f.weight() == 400 && !f.italic()
                    && f.faceIndex() == 0 && !f.familyNames().isEmpty()) {
                return f;
            }
        }
        assumeTrue(false, "OS の書体ファイルが見つからない環境");
        return null;
    }

    private static String textPage(String family) {
        return "<html><body style=\"margin:0;background:#fff\"><p style=\"margin:4px;font:24px '" + family + "';color:#000\">Hamburgefonstiv</p></body></html>";
    }

    // ---- 画像 ----

    private static String imgPage(String src) {
        return "<html><body style=\"margin:0\"><img id=\"p\" src=\"" + src + "\" style=\"display:block;width:20px;height:20px\"></body></html>";
    }

    private static SurfaceImage solid(int w, int h, int argb) {
        int[] px = new int[w * h];
        Arrays.fill(px, argb);
        return SurfaceImage.of(w, h, px);
    }

    @Test
    void customImageSourceSuppliesPixels() {
        AtomicInteger asked = new AtomicInteger();
        List<String> srcs = new ArrayList<>();
        try (WebViewSurface s = EnderEngine.html(imgPage("mem:red")).size(40, 40).clearColor(0xFFFFFFFF)
                .images(src -> {
                    asked.incrementAndGet();
                    srcs.add(src);
                    return "mem:red".equals(src) ? solid(4, 4, 0xFFFF0000) : null;
                }).build()) {
            s.frame(0, true);
            int px = s.pixels()[10 * s.pixelWidth() + 10];
            assertEquals(255, red(px));
            assertEquals(0, green(px));
            assertEquals(0, blue(px));
            assertEquals(255, green(s.pixels()[30 * s.pixelWidth() + 30]), "画像の外は背景の白");
        }
        assertTrue(asked.get() > 0);
        assertTrue(srcs.contains("mem:red"), srcs.toString());
    }

    @Test
    void sourceImagesAreStraightAlphaAndGetPremultiplied() {
        int[] px = render(EnderEngine.html(imgPage("mem:half")).size(40, 40).clearColor(0xFFFFFFFF)
                .images(src -> solid(2, 2, 0x80FF0000)));
        int p = px[10 * 40 + 10];
        assertEquals(255, red(p));
        assertTrue(green(p) > 100 && green(p) < 160, "半透明の赤は白の上でピンク: g=" + green(p));
    }

    @Test
    void nullFromTheSourceFallsBackToTheDefaultResolution() {
        String svg = "data:image/svg+xml;utf8,%3Csvg xmlns='http://www.w3.org/2000/svg' width='20' height='20'%3E%3Crect width='20' height='20' fill='%2300ff00'/%3E%3C/svg%3E";
        AtomicInteger asked = new AtomicInteger();
        int[] px = render(EnderEngine.html(imgPage(svg)).size(40, 40).clearColor(0xFFFFFFFF)
                .images(src -> { asked.incrementAndGet(); return null; }));
        assertTrue(asked.get() > 0, "差し込みにも問い合わせる");
        int p = px[10 * 40 + 10];
        assertEquals(255, green(p));
        assertEquals(0, red(p), "既定の解決で data: の SVG が描かれる");
    }

    @Test
    void imageSourceAlsoServesCssBackgrounds() {
        String html = "<html><body style=\"margin:0\"><div style=\"width:20px;height:20px;background:url('mem:blue') no-repeat;background-size:100% 100%\"></div></body></html>";
        int[] px = render(EnderEngine.html(html).size(40, 40).clearColor(0xFFFFFFFF)
                .images(src -> "mem:blue".equals(src) ? solid(4, 4, 0xFF0000FF) : null));
        int p = px[10 * 40 + 10];
        assertEquals(255, blue(p));
        assertEquals(0, red(p));
    }

    @Test
    void surfaceImageValidatesItsArguments() {
        assertThrows(IllegalArgumentException.class, () -> SurfaceImage.of(0, 1, new int[0]));
        assertThrows(IllegalArgumentException.class, () -> SurfaceImage.of(2, 2, new int[3]));
        assertThrows(IllegalArgumentException.class, () -> SurfaceImage.of(1, 1, null));
        assertThrows(IllegalArgumentException.class, () -> new SurfaceImage(1, 1, new int[1], 0f, 1f));
        SurfaceImage ok = new SurfaceImage(2, 2, new int[4], 10f, 10f);
        assertEquals(10f, ok.cssWidth());
        assertEquals(2, SurfaceImage.of(2, 2, new int[4]).cssWidth());
    }

    @Test
    void webImagesFlagBuildsAndClosesWithoutTheNetwork() {
        try (WebViewSurface s = EnderEngine.html("<html><body><p>x</p></body></html>").size(40, 40).webImages(true).build()) {
            assertTrue(s.frame(0, true));
            assertDoesNotThrow(() -> s.frame(16, false));
        }
        try (WebViewSurface s = EnderEngine.html("<html><body><p>x</p></body></html>").size(40, 40)
                .webImages(true).images(src -> null).build()) {
            assertTrue(s.frame(0, true));
        }
    }

    @Test
    void imageSourceBuilderRejectsNull() {
        assertThrows(NullPointerException.class, () -> EnderEngine.html("x").images(null));
    }

    // ---- 書体 ----

    @Test
    void fontRecordValidatesItsArguments() {
        assertThrows(IllegalArgumentException.class, () -> SurfaceFont.of(" ", new byte[] {1}));
        assertThrows(IllegalArgumentException.class, () -> new SurfaceFont("A", null, null, 400, false));
        assertThrows(IllegalArgumentException.class, () -> new SurfaceFont("A", new byte[] {1}, Path.of("x"), 400, false));
        assertThrows(IllegalArgumentException.class, () -> new SurfaceFont("A", new byte[] {1}, null, 0, false));
        assertThrows(NullPointerException.class, () -> SurfaceFont.of("A", (byte[]) null));
        SurfaceFont bold = SurfaceFont.of("A", new byte[] {1}).withStyle(700, true);
        assertEquals(700, bold.weight());
        assertTrue(bold.italic());
    }

    @Test
    void unreadableFontsAreRejectedAtBuild() {
        assertThrows(IllegalArgumentException.class,
                () -> EnderEngine.html("<p>x</p>").size(40, 40).font("Broken", new byte[] {1, 2, 3, 4}).build());
        assertThrows(IllegalArgumentException.class,
                () -> EnderEngine.html("<p>x</p>").size(40, 40).font("Missing", Path.of("no/such/font.ttf")).build());
    }

    @Test
    void aFontFileBroughtInUnderANewNameRendersLikeTheSameFileUnderItsOwnName() {
        SystemFonts.Face face = systemFaceWithFile();
        String realName = face.familyNames().iterator().next();
        int[] viaOs = render(EnderEngine.html(textPage(realName)).size(300, 50).clearColor(0xFFFFFFFF));
        int[] viaBrought = render(EnderEngine.html(textPage("Brought Brand")).size(300, 50).clearColor(0xFFFFFFFF)
                .font("Brought Brand", face.path()));
        assertArrayEquals(viaOs, viaBrought, "持ち込んだ書体ファイルが、同じ書体を OS 名で引いた描画と一致する");
        boolean ink = false;
        for (int px : viaBrought) if (red(px) < 128) { ink = true; break; }
        assertTrue(ink, "文字が描かれている");
    }

    @Test
    void byteArrayAndFileFontsAgree() throws Exception {
        SystemFonts.Face face = systemFaceWithFile();
        assumeTrue(Files.size(face.path()) < 20 * 1024 * 1024);
        int[] viaFile = render(EnderEngine.html(textPage("Brand")).size(300, 50).clearColor(0xFFFFFFFF).font("Brand", face.path()));
        int[] viaBytes = render(EnderEngine.html(textPage("Brand")).size(300, 50).clearColor(0xFFFFFFFF)
                .font("Brand", Files.readAllBytes(face.path())));
        assertArrayEquals(viaFile, viaBytes);
    }

    @Test
    void broughtFontIsNotLeakedIntoTheProcessWideRegistry() {
        SystemFonts.Face face = systemFaceWithFile();
        int[] with = render(EnderEngine.html(textPage("Private Brand")).size(300, 50).clearColor(0xFFFFFFFF)
                .font("Private Brand", face.path()));
        int[] without = render(EnderEngine.html(textPage("Private Brand")).size(300, 50).clearColor(0xFFFFFFFF));
        assertFalse(Arrays.equals(with, without), "持ち込んだ面にだけ効く（別の面には漏れない）");
        assertFalse(SystemFonts.system().hasFamily("Private Brand"));
    }

    @Test
    void systemFontsCanBeTurnedOffForEnvironmentIndependentRendering() {
        SystemFonts.Face face = systemFaceWithFile();
        String realName = face.familyNames().iterator().next();
        int[] sys = render(EnderEngine.html(textPage(realName)).size(300, 50).clearColor(0xFFFFFFFF));
        int[] off = render(EnderEngine.html(textPage(realName)).size(300, 50).clearColor(0xFFFFFFFF).systemFonts(false));
        int[] offA = render(EnderEngine.html(textPage(realName)).size(300, 50).clearColor(0xFFFFFFFF).systemFonts(false));
        assertFalse(Arrays.equals(sys, off), "OS の書体を使わなければ内蔵の標準フォントになる");
        assertArrayEquals(off, offA, "環境に依らず同じ描画");
        int[] offBrought = render(EnderEngine.html(textPage("Only Mine")).size(300, 50).clearColor(0xFFFFFFFF)
                .systemFonts(false).font("Only Mine", face.path()));
        assertFalse(Arrays.equals(off, offBrought), "OS の書体なしでも持ち込んだ書体は使われる");
    }
}
