package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.font.ColorBitmap;
import com.nishiyu.enderframelib.core.text.font.LoadedFace;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import com.nishiyu.enderframelib.core.text.font.WebFonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ビットマップ絵文字（{@code CBDT}/{@code CBLC} と {@code sbix}）の読み込みと描画。
 * テスト内で最小の有効な OpenType フォント（PNG 絵文字入り）を組み立てて検証する。
 */
public class BitmapEmojiTest {

    private static final int SMILE = 0x1F600;

    @AfterEach
    public void cleanup() {
        WebFonts.clear();
    }

    @Test
    public void cbdtFontIsAcceptedLoadedAndScaled() throws IOException {
        byte[] png = solidPng(32, 32, 0x20, 0xC0, 0x40, 0xFF); // 緑
        byte[] sfnt = buildCbdtFont(32, png);

        assertTrue(WebFonts.isUsable(sfnt), "CBDT の非スケーラブルなカラービットマップ書体を受け入れる");
        assertTrue(WebFonts.register("test-cbdt-emoji", sfnt, 400, false));

        LoadedFace face = LoadedFace.open(new SystemFonts.Face(
                null, 0, Set.of("test-cbdt-emoji"), "TestCBDT", 400, false, 5, new SystemFonts.MemoryFont(sfnt)));
        assertNotNull(face);
        try {
            assertTrue(face.hasGlyph(SMILE));
            assertTrue(face.advanceEm(SMILE) > 0f, "送り幅が正: " + face.advanceEm(SMILE));
            assertTrue(face.ascent(32f) > 0f);

            ColorBitmap b32 = face.colorBitmap(SMILE, 32f);
            assertNotNull(b32, "CBDT のカラービットマップを返せる");
            assertEquals(32, b32.pixmap().width());
            assertEquals(32, b32.pixmap().height());
            assertEquals(0xFF20C040, b32.pixmap().get(16, 16), "ARGB 画素値が一致");
            assertEquals(32f, b32.width(), 0.01f);

            ColorBitmap b64 = face.colorBitmap(SMILE, 64f);
            assertNotNull(b64);
            assertSame(b32.pixmap(), b64.pixmap(), "同じストライクの Pixmap を共有する");
            assertEquals(64f, b64.width(), 0.01f, "要求サイズに合わせて拡縮する");
            assertNull(face.colorBitmap('A', 32f));
        } finally {
            face.release();
        }
    }

    @Test
    public void cbdtAndSbixRenderColouredPixelsOnPage() throws IOException {
        byte[] cbdtPng = solidPng(32, 32, 0x10, 0x70, 0xEE, 0xFF); // 青系
        byte[] cbdtSfnt = buildCbdtFont(32, cbdtPng);
        assertTrue(WebFonts.register("test-cbdt-page", cbdtSfnt, 400, false));

        byte[] sbixPng = solidPng(32, 32, 0xEE, 0x40, 0x20, 0xFF); // 赤系
        byte[] sbixSfnt = buildSbixFont(32, sbixPng);
        assertTrue(WebFonts.register("test-sbix-page", sbixSfnt, 400, false));

        int[] cbdtPx = renderEmoji("test-cbdt-page", "");
        assertHasColour(cbdtPx, 0x10, 0x70, 0xEE, "CBDT 絵文字の青系画素");

        int[] sbixPx = renderEmoji("test-sbix-page", "");
        assertHasColour(sbixPx, 0xEE, 0x40, 0x20, "sbix 絵文字の赤系画素");

        int[] rotatedPx = renderEmoji("test-sbix-page", "transform:rotate(25deg);");
        assertHasColour(rotatedPx, 0xEE, 0x40, 0x20, "回転時もビットマップ絵文字が補間描画される");
    }

    @Test
    public void multiStrikeSelectsSmallestStrikeAtOrAboveRequestedSize() throws IOException {
        byte[] green16 = solidPng(16, 16, 0x10, 0xD0, 0x30, 0xFF);
        byte[] blue64 = solidPng(64, 64, 0x20, 0x50, 0xF0, 0xFF);
        byte[] sfnt = buildMultiStrikeCbdtFont(new int[]{16, 64}, new byte[][]{green16, blue64});
        assertTrue(WebFonts.isUsable(sfnt));

        LoadedFace face = LoadedFace.open(new SystemFonts.Face(
                null, 0, Set.of("test-multi-cbdt"), "TestMultiCBDT", 400, false, 5, new SystemFonts.MemoryFont(sfnt)));
        assertNotNull(face);
        try {
            ColorBitmap small = face.colorBitmap(SMILE, 16f);
            assertNotNull(small);
            assertEquals(16, small.pixmap().width());
            assertEquals(0xFF10D030, small.pixmap().get(8, 8));

            ColorBitmap mid = face.colorBitmap(SMILE, 24f);
            assertNotNull(mid);
            assertEquals(64, mid.pixmap().width(), "16 < 24 <= 64 なので 64px ストライクを選ぶ");
            assertEquals(0xFF2050F0, mid.pixmap().get(32, 32));
            assertEquals(24f, mid.width(), 0.01f);

            ColorBitmap large = face.colorBitmap(SMILE, 96f);
            assertNotNull(large);
            assertSame(mid.pixmap(), large.pixmap(), "最大ストライク 64px を選んで拡大");
            assertEquals(96f, large.width(), 0.01f);
        } finally {
            face.release();
        }
    }

    @Test
    public void nonColorBitmapOnlyFontIsRejected() throws IOException {
        byte[] sfnt = buildMonochromeEbdtFont();
        assertFalse(WebFonts.isUsable(sfnt), "カラーではない固定サイズのみのビットマップ書体は弾く");
    }

    private static int[] renderEmoji(String family, String extraStyle) {
        String html = "<html><body style='margin:0;background:#fff'><div style='position:absolute;left:20px;top:20px;"
                + extraStyle + "'><span style=\"font-family:'" + family + "';font-size:40px;color:#000\">😀</span></div></body></html>";
        StandaloneView view = new StandaloneView(html, 120, 120);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(120, 120, 1f)) {
            view.frame(1000, cpu, true);
            return cpu.pixmap().pixels().clone();
        } finally {
            view.close();
        }
    }

    private static void assertHasColour(int[] px, int expR, int expG, int expB, String msg) {
        int hits = 0;
        for (int p : px) {
            if ((p >>> 24) != 0xFF || (p & 0xFFFFFF) == 0xFFFFFF) continue;
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            if (Math.abs(r - expR) <= 4 && Math.abs(g - expG) <= 4 && Math.abs(b - expB) <= 4) hits++;
        }
        assertTrue(hits > 50, msg + " (hits=" + hits + ")");
    }

    // ------------------------------------------------------------------ 最小 SFNT ビルダー

    private static byte[] buildCbdtFont(int ppem, byte[] png) throws IOException {
        Map<String, byte[]> t = new TreeMap<>();
        t.put("head", headTable(1024, (short) 0));
        t.put("hhea", hheaTable(800, -200, 2));
        t.put("maxp", maxp05(2));
        t.put("OS/2", os2Table(800, 200));
        t.put("hmtx", hmtxTable(new int[]{512, 1024}, new int[]{0, 0}));
        t.put("cmap", cmapFormat12(SMILE, 1));
        t.put("name", nameTable("TestCBDT"));
        t.put("post", post30());

        // CBDT v3.0: header(4) + SmallGlyphMetrics(5) + dataLen(4) + png
        ByteArrayOutputStream cbdt = new ByteArrayOutputStream();
        DataOutputStream cbdtOut = new DataOutputStream(cbdt);
        cbdtOut.writeShort(3);
        cbdtOut.writeShort(0);
        cbdtOut.writeByte(ppem);     // height
        cbdtOut.writeByte(ppem);     // width
        cbdtOut.writeByte(0);        // bearingX
        cbdtOut.writeByte(ppem - 4); // bearingY
        cbdtOut.writeByte(ppem);     // advance
        cbdtOut.writeInt(png.length);
        cbdtOut.write(png);
        byte[] cbdtBytes = cbdt.toByteArray();
        int glyphDataSize = cbdtBytes.length - 4;

        // CBLC v3.0: header(8) + BitmapSize(48) + IndexSubTableArray(8) + IndexSubTable1(8 + 8)
        ByteArrayOutputStream cblc = new ByteArrayOutputStream();
        DataOutputStream cblcOut = new DataOutputStream(cblc);
        cblcOut.writeShort(3);
        cblcOut.writeShort(0);
        cblcOut.writeInt(1); // numSizes
        // BitmapSize (48 bytes)
        cblcOut.writeInt(8 + 48); // indexSubTableArrayOffset
        cblcOut.writeInt(8 + 16); // indexTablesSize
        cblcOut.writeInt(1);      // numberOfIndexSubTables
        cblcOut.writeInt(0);      // colorRef
        writeSbitLineMetrics(cblcOut, ppem - 4, -4, ppem); // hori
        writeSbitLineMetrics(cblcOut, ppem - 4, -4, ppem); // vert
        cblcOut.writeShort(1); // startGlyphIndex
        cblcOut.writeShort(1); // endGlyphIndex
        cblcOut.writeByte(ppem); // ppemX
        cblcOut.writeByte(ppem); // ppemY
        cblcOut.writeByte(32);   // bitDepth
        cblcOut.writeByte(1);    // flags: horizontal
        // IndexSubTableArray (8 bytes)
        cblcOut.writeShort(1); // firstGlyphIndex
        cblcOut.writeShort(1); // lastGlyphIndex
        cblcOut.writeInt(8);   // additionalOffsetToIndexSubtable
        // IndexSubTable1 (16 bytes)
        cblcOut.writeShort(1);  // indexFormat
        cblcOut.writeShort(17); // imageFormat (small metrics + PNG)
        cblcOut.writeInt(4);    // imageDataOffset in CBDT
        cblcOut.writeInt(0);
        cblcOut.writeInt(glyphDataSize);

        t.put("CBLC", cblc.toByteArray());
        t.put("CBDT", cbdtBytes);
        return assembleSfnt(t);
    }

    private static byte[] buildMultiStrikeCbdtFont(int[] ppems, byte[][] pngs) throws IOException {
        Map<String, byte[]> t = new TreeMap<>();
        t.put("head", headTable(1024, (short) 0));
        t.put("hhea", hheaTable(800, -200, 2));
        t.put("maxp", maxp05(2));
        t.put("OS/2", os2Table(800, 200));
        t.put("hmtx", hmtxTable(new int[]{512, 1024}, new int[]{0, 0}));
        t.put("cmap", cmapFormat12(SMILE, 1));
        t.put("name", nameTable("TestMultiCBDT"));
        t.put("post", post30());

        int n = ppems.length;
        ByteArrayOutputStream cbdt = new ByteArrayOutputStream();
        DataOutputStream cbdtOut = new DataOutputStream(cbdt);
        cbdtOut.writeShort(3);
        cbdtOut.writeShort(0);
        int[] imgOffsets = new int[n];
        int[] dataLens = new int[n];
        for (int i = 0; i < n; i++) {
            imgOffsets[i] = cbdt.size();
            int ppem = ppems[i];
            byte[] png = pngs[i];
            cbdtOut.writeByte(ppem);
            cbdtOut.writeByte(ppem);
            cbdtOut.writeByte(0);
            cbdtOut.writeByte(ppem - 4);
            cbdtOut.writeByte(ppem);
            cbdtOut.writeInt(png.length);
            cbdtOut.write(png);
            dataLens[i] = cbdt.size() - imgOffsets[i];
        }

        ByteArrayOutputStream cblc = new ByteArrayOutputStream();
        DataOutputStream cblcOut = new DataOutputStream(cblc);
        cblcOut.writeShort(3);
        cblcOut.writeShort(0);
        cblcOut.writeInt(n);
        int subTablesStart = 8 + 48 * n;
        for (int i = 0; i < n; i++) {
            int ppem = ppems[i];
            cblcOut.writeInt(subTablesStart + i * 24); // indexSubTableArrayOffset
            cblcOut.writeInt(24); // indexTablesSize
            cblcOut.writeInt(1);
            cblcOut.writeInt(0);
            writeSbitLineMetrics(cblcOut, ppem - 4, -4, ppem);
            writeSbitLineMetrics(cblcOut, ppem - 4, -4, ppem);
            cblcOut.writeShort(1);
            cblcOut.writeShort(1);
            cblcOut.writeByte(ppem);
            cblcOut.writeByte(ppem);
            cblcOut.writeByte(32);
            cblcOut.writeByte(1);
        }
        for (int i = 0; i < n; i++) {
            cblcOut.writeShort(1);
            cblcOut.writeShort(1);
            cblcOut.writeInt(8);
            cblcOut.writeShort(1);
            cblcOut.writeShort(17);
            cblcOut.writeInt(imgOffsets[i]);
            cblcOut.writeInt(0);
            cblcOut.writeInt(dataLens[i]);
        }

        t.put("CBLC", cblc.toByteArray());
        t.put("CBDT", cbdt.toByteArray());
        return assembleSfnt(t);
    }

    private static byte[] buildMonochromeEbdtFont() throws IOException {
        Map<String, byte[]> t = new TreeMap<>();
        t.put("head", headTable(1024, (short) 0));
        t.put("hhea", hheaTable(800, -200, 2));
        t.put("maxp", maxp05(2));
        t.put("OS/2", os2Table(800, 200));
        t.put("hmtx", hmtxTable(new int[]{512, 1024}, new int[]{0, 0}));
        t.put("cmap", cmapFormat12('A', 1));
        t.put("name", nameTable("TestEBDT"));
        t.put("post", post30());

        // EBDT v2.0, format 1: SmallGlyphMetrics(5) + 8 rows of 1 byte (8x8 1-bit)
        ByteArrayOutputStream ebdt = new ByteArrayOutputStream();
        DataOutputStream ebdtOut = new DataOutputStream(ebdt);
        ebdtOut.writeShort(2);
        ebdtOut.writeShort(0);
        ebdtOut.writeByte(8);
        ebdtOut.writeByte(8);
        ebdtOut.writeByte(0);
        ebdtOut.writeByte(7);
        ebdtOut.writeByte(8);
        for (int i = 0; i < 8; i++) ebdtOut.writeByte(0xFF);

        ByteArrayOutputStream eblc = new ByteArrayOutputStream();
        DataOutputStream eblcOut = new DataOutputStream(eblc);
        eblcOut.writeShort(2);
        eblcOut.writeShort(0);
        eblcOut.writeInt(1);
        eblcOut.writeInt(8 + 48);
        eblcOut.writeInt(24);
        eblcOut.writeInt(1);
        eblcOut.writeInt(0);
        writeSbitLineMetrics(eblcOut, 7, -1, 8);
        writeSbitLineMetrics(eblcOut, 7, -1, 8);
        eblcOut.writeShort(1);
        eblcOut.writeShort(1);
        eblcOut.writeByte(8);
        eblcOut.writeByte(8);
        eblcOut.writeByte(1); // 1-bit mono
        eblcOut.writeByte(1);
        eblcOut.writeShort(1);
        eblcOut.writeShort(1);
        eblcOut.writeInt(8);
        eblcOut.writeShort(1);
        eblcOut.writeShort(1); // imageFormat 1 (mono byte-aligned)
        eblcOut.writeInt(4);
        eblcOut.writeInt(0);
        eblcOut.writeInt(5 + 8);

        t.put("EBLC", eblc.toByteArray());
        t.put("EBDT", ebdt.toByteArray());
        return assembleSfnt(t);
    }

    private static byte[] buildSbixFont(int ppem, byte[] png) throws IOException {
        Map<String, byte[]> t = new TreeMap<>();
        t.put("head", headTable(1024, (short) 0));
        t.put("hhea", hheaTable(800, -200, 2));
        t.put("maxp", maxp10(2));
        t.put("OS/2", os2Table(800, 200));
        t.put("hmtx", hmtxTable(new int[]{512, 1024}, new int[]{0, 0}));
        t.put("cmap", cmapFormat12(SMILE, 1));
        t.put("loca", new byte[6]); // 3 uint16 offsets = 0
        t.put("glyf", new byte[2]); // dummy empty
        t.put("name", nameTable("TestSBIX"));
        t.put("post", post30());

        // sbix: header(8 + 4) + strike(4 + 3*4 + glyph1(8 + png))
        ByteArrayOutputStream sbix = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(sbix);
        out.writeShort(1); // version
        out.writeShort(1); // flags
        out.writeInt(1);   // numStrikes
        out.writeInt(12);  // strikeOffsets[0]
        // strike
        out.writeShort(ppem); // ppem
        out.writeShort(72);   // ppi
        int base = 4 + 3 * 4;
        out.writeInt(base); // glyph 0 start (empty)
        out.writeInt(base); // glyph 1 start
        out.writeInt(base + 8 + png.length); // end
        // glyph 1
        out.writeShort(0);  // originOffsetX
        out.writeShort(-4); // originOffsetY
        out.write("png ".getBytes(StandardCharsets.US_ASCII));
        out.write(png);
        t.put("sbix", sbix.toByteArray());
        return assembleSfnt(t);
    }

    private static void writeSbitLineMetrics(DataOutputStream out, int asc, int desc, int wMax) throws IOException {
        out.writeByte(asc);
        out.writeByte(desc);
        out.writeByte(wMax);
        out.writeByte(1); // caretSlopeNumerator
        for (int i = 0; i < 8; i++) out.writeByte(0);
    }

    private static byte[] headTable(int upem, short indexToLocFormat) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(0x00010000); // major.minor
        out.writeInt(0x00010000); // fontRevision
        out.writeInt(0);          // checkSumAdjustment
        out.writeInt(0x5F0F3CF5); // magicNumber
        out.writeShort(0);        // flags
        out.writeShort(upem);
        out.writeLong(0);         // created
        out.writeLong(0);         // modified
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(upem);
        out.writeShort(upem);
        out.writeShort(0);        // macStyle
        out.writeShort(8);        // lowestRecPPEM
        out.writeShort(2);        // fontDirectionHint
        out.writeShort(indexToLocFormat);
        out.writeShort(0);        // glyphDataFormat
        return bos.toByteArray();
    }

    private static byte[] hheaTable(int asc, int desc, int numHMetrics) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(0x00010000);
        out.writeShort(asc);
        out.writeShort(desc);
        out.writeShort(0);    // lineGap
        out.writeShort(1024); // advanceWidthMax
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(1024);
        out.writeShort(1); // caretSlopeRise
        out.writeShort(0);
        out.writeShort(0);
        for (int i = 0; i < 4; i++) out.writeShort(0);
        out.writeShort(0); // metricDataFormat
        out.writeShort(numHMetrics);
        return bos.toByteArray();
    }

    private static byte[] maxp05(int numGlyphs) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(0x00005000);
        out.writeShort(numGlyphs);
        return bos.toByteArray();
    }

    private static byte[] maxp10(int numGlyphs) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(0x00010000);
        out.writeShort(numGlyphs);
        for (int i = 0; i < 13; i++) out.writeShort(0);
        return bos.toByteArray();
    }

    private static byte[] os2Table(int winAsc, int winDesc) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeShort(4);   // version
        out.writeShort(512); // xAvgCharWidth
        out.writeShort(400); // usWeightClass
        out.writeShort(5);   // usWidthClass
        out.writeShort(0);   // fsType
        for (int i = 0; i < 10; i++) out.writeShort(0); // ySub/Super/Strike
        out.writeShort(0);   // sFamilyClass
        for (int i = 0; i < 10; i++) out.writeByte(0); // panose
        out.writeInt(0); out.writeInt(0); out.writeInt(0); out.writeInt(0); // ulUnicodeRange
        out.writeInt(0x4E4F4E45); // achVendID
        out.writeShort(0x0040); // fsSelection REGULAR
        out.writeShort(0xFFFF); // usFirstCharIndex
        out.writeShort(0xFFFF); // usLastCharIndex
        out.writeShort(winAsc); // sTypoAscender
        out.writeShort(-winDesc); // sTypoDescender
        out.writeShort(0);      // sTypoLineGap
        out.writeShort(winAsc); // usWinAscent
        out.writeShort(winDesc);// usWinDescent
        out.writeInt(1); out.writeInt(0); // codePageRange
        out.writeShort(500); // sxHeight
        out.writeShort(700); // sCapHeight
        out.writeShort(0); out.writeShort(0); out.writeShort(0);
        return bos.toByteArray();
    }

    private static byte[] hmtxTable(int[] advances, int[] lsbs) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        for (int i = 0; i < advances.length; i++) {
            out.writeShort(advances[i]);
            out.writeShort(lsbs[i]);
        }
        return bos.toByteArray();
    }

    private static byte[] cmapFormat12(int codePoint, int glyphId) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeShort(0); // version
        out.writeShort(1); // numTables
        out.writeShort(3); // platformID Windows
        out.writeShort(10); // encodingID Unicode full
        out.writeInt(12);  // offset
        // Format 12
        out.writeShort(12);
        out.writeShort(0);
        out.writeInt(16 + 12); // length
        out.writeInt(0);       // language
        out.writeInt(1);       // numGroups
        out.writeInt(codePoint);
        out.writeInt(codePoint);
        out.writeInt(glyphId);
        return bos.toByteArray();
    }

    private static byte[] nameTable(String family) throws IOException {
        byte[] utf16 = family.getBytes(StandardCharsets.UTF_16BE);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeShort(0); // format
        out.writeShort(1); // count
        out.writeShort(6 + 12); // stringOffset
        out.writeShort(3); // Windows
        out.writeShort(1); // Unicode BMP
        out.writeShort(0x0409); // en-US
        out.writeShort(1); // family
        out.writeShort(utf16.length);
        out.writeShort(0);
        out.write(utf16);
        return bos.toByteArray();
    }

    private static byte[] post30() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(0x00030000);
        for (int i = 0; i < 7; i++) out.writeInt(0);
        return bos.toByteArray();
    }

    private static byte[] assembleSfnt(Map<String, byte[]> tables) throws IOException {
        int n = tables.size();
        int searchRange = Integer.highestOneBit(n) * 16;
        int entrySelector = Integer.numberOfTrailingZeros(Integer.highestOneBit(n));
        int rangeShift = n * 16 - searchRange;

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(0x00010000);
        out.writeShort(n);
        out.writeShort(searchRange);
        out.writeShort(entrySelector);
        out.writeShort(rangeShift);

        int offset = 12 + n * 16;
        List<byte[]> padded = new java.util.ArrayList<>();
        for (var e : tables.entrySet()) {
            byte[] data = e.getValue();
            int len = data.length;
            int padLen = (len + 3) & ~3;
            byte[] pad = java.util.Arrays.copyOf(data, padLen);
            padded.add(pad);
            out.write(e.getKey().getBytes(StandardCharsets.US_ASCII), 0, 4);
            out.writeInt(checksum(pad));
            out.writeInt(offset);
            out.writeInt(len);
            offset += padLen;
        }
        for (byte[] p : padded) out.write(p);
        return bos.toByteArray();
    }

    private static int checksum(byte[] data) {
        int sum = 0;
        for (int i = 0; i < data.length; i += 4) {
            sum += ((data[i] & 0xFF) << 24) | ((data[i + 1] & 0xFF) << 16)
                    | ((data[i + 2] & 0xFF) << 8) | (data[i + 3] & 0xFF);
        }
        return sum;
    }

    private static byte[] solidPng(int w, int h, int r, int g, int b, int a) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (int y = 0; y < h; y++) {
            raw.write(0); // filter none
            for (int x = 0; x < w; x++) {
                raw.write(r);
                raw.write(g);
                raw.write(b);
                raw.write(a);
            }
        }
        byte[] rawBytes = raw.toByteArray();
        Deflater def = new Deflater();
        def.setInput(rawBytes);
        def.finish();
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        while (!def.finished()) {
            int n = def.deflate(buf);
            idat.write(buf, 0, n);
        }
        def.end();

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        DataOutputStream ih = new DataOutputStream(ihdr);
        ih.writeInt(w);
        ih.writeInt(h);
        ih.writeByte(8); // bit depth
        ih.writeByte(6); // RGBA
        ih.writeByte(0);
        ih.writeByte(0);
        ih.writeByte(0);
        writeChunk(png, "IHDR", ihdr.toByteArray());
        writeChunk(png, "IDAT", idat.toByteArray());
        writeChunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(data.length);
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.write(t);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        dos.writeInt((int) crc.getValue());
    }
}
